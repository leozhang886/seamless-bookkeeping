package com.bookkeeping.app.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.bookkeeping.app.data.repository.TransactionRepository
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

// 监听全系统通知，只解析已支持的支付应用和建设银行动账提醒。
// 嵌入式类比：注册了一个"中断处理函数"，系统在通知到达时调用我们。
class PaymentNotificationListenerService : NotificationListenerService() {

    private lateinit var repository: TransactionRepository

    // 后台协程作用域。Service 销毁时一起 cancel。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Hilt EntryPoint 方式拿到 Repository（@AndroidEntryPoint 不支持本类）
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            NotificationListenerEntryPoint::class.java
        )
        repository = entryPoint.transactionRepository()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // 通知到达时回调（关键入口）
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName ?: return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val regularText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val text = if (packageName == BankAppNotificationParser.CCB_PACKAGE) {
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            val onlyLine = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.singleOrNull()?.toString()
            listOfNotNull(regularText, bigText, onlyLine).maxByOrNull { it.length }
        } else regularText

        val parsed = NotificationParser.parse(
            packageName = packageName,
            title = title,
            text = text,
            timestampMs = sbn.postTime,
        ) ?: return

        // 在后台协程里入库（IO 不能在主线程）。
        // insertIfNotDuplicate 处理重复通知，以及银行短信与应用通知的重复记录。
        scope.launch {
            repository.insertIfNotDuplicate(parsed)
        }
    }
}
