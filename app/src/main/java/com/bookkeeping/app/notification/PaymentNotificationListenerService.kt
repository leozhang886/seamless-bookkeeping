package com.bookkeeping.app.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.bookkeeping.app.data.repository.PendingNotificationRepository
import com.bookkeeping.app.data.settings.SettingsPreferences
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

// 监听全系统通知，只解析已支持的支付应用和建设银行动账提醒。
// 嵌入式类比：注册了一个"中断处理函数"，系统在通知到达时调用我们。
class PaymentNotificationListenerService : NotificationListenerService() {

    private lateinit var pending: PendingNotificationRepository
    private lateinit var settings: SettingsPreferences

    // 后台协程作用域。Service 销毁时一起 cancel。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Hilt EntryPoint 方式拿到 Repository（@AndroidEntryPoint 不支持本类）
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            NotificationListenerEntryPoint::class.java
        )
        pending = entryPoint.pendingNotificationRepository()
        settings = entryPoint.settingsPreferences()
    }

    override fun onListenerConnected() { super.onListenerConnected(); CaptureDiagnostics.connection(true) }
    override fun onListenerDisconnected() { CaptureDiagnostics.connection(false); super.onListenerDisconnected() }

    override fun onDestroy() {
        CaptureDiagnostics.connection(false)
        scope.cancel()
        super.onDestroy()
    }

    // 通知到达时回调（关键入口）
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        scope.launch {
            try {
            // The app switch is checked before reading notification text.
            if (!settings.observeAutoCaptureEnabled().first()) return@launch
            val packageName = sbn.packageName ?: return@launch
            if (packageName !in setOf("com.eg.android.AlipayGphone", "com.tencent.mm",
                    BankAppNotificationParser.CCB_PACKAGE)) return@launch
            CaptureDiagnostics.received()
            if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return@launch
            val extras = sbn.notification?.extras ?: return@launch
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val regularText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            val text = run {
                val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                val onlyLine = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                    ?.singleOrNull()?.toString()
                listOfNotNull(regularText, bigText, onlyLine).maxByOrNull { it.length }
            }
            val parsed = NotificationParser.parse(packageName, title, text, sbn.postTime)
                ?: return@launch
            if (pending.save(packageName, sbn.key, title, text, parsed, sbn.postTime)) CaptureDiagnostics.saved()
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { CaptureDiagnostics.failed() }
        }
    }
}
