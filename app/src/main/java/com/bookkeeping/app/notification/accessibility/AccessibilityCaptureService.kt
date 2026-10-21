package com.bookkeeping.app.notification.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.repository.TransactionRepository
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

// 无障碍兜底服务：监听微信、支付宝两个 app 的窗口事件，
// 在通知监听抓不到的场景（微信转账/红包、支付宝转账入账）里读 UI 抽金额。
//
// 限制只在 com.tencent.mm + com.eg.android.AlipayGphone 两个包名上触发（见 xml/accessibility_service_config）。
// 默认未启用，用户必须在系统「无障碍」里手动勾选我们才会跑。
class AccessibilityCaptureService : AccessibilityService() {

    private lateinit var repository: TransactionRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 包名 → 该包对应的 Extractor 列表（按优先级试）
    private lateinit var extractors: Map<String, List<AccessibilityExtractor>>

    // 短路：相同来源+金额+方向 30s 内只 emit 一次，避免 CONTENT_CHANGED 反复触发
    private var lastFingerprint: String? = null
    private var lastEmitAtMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        val ep = EntryPointAccessors.fromApplication(
            applicationContext,
            AccessibilityServiceEntryPoint::class.java,
        )
        repository = ep.transactionRepository()
        extractors = mapOf(
            PKG_ALIPAY to listOf(AlipayTransferExtractor()),
            PKG_WECHAT to listOf(
                WechatTransferExtractor(),
                WechatRedPacketExtractor(),
            ),
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return

        val list = extractors[pkg] ?: return
        val root = rootInActiveWindow
        if (root == null) return

        // 强制刷新一次根节点，避免事件到达时 UI 树尚未填充完
        runCatching { root.refresh() }

        for (ext in list) {
            val tx = runCatching { ext.extract(root, event) }
                .getOrNull()

            if (tx == null) continue

            if (!shouldEmit(tx)) continue
            persist(tx)
            break
        }
    }

    private fun shouldEmit(tx: TransactionEntity): Boolean {
        val fp = "${tx.source}|${tx.amountCents}|${tx.type}"
        val now = System.currentTimeMillis()
        if (fp == lastFingerprint && now - lastEmitAtMs < FINGERPRINT_TTL_MS) return false
        lastFingerprint = fp
        lastEmitAtMs = now
        return true
    }

    private fun persist(tx: TransactionEntity) {
        scope.launch {
            repository.insertIfNotDuplicate(tx)
        }
    }

    override fun onInterrupt() {
        // 系统要求覆盖。我们没有要中断的工作。
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val FINGERPRINT_TTL_MS = 30_000L

        const val PKG_ALIPAY = "com.eg.android.AlipayGphone"
        const val PKG_WECHAT = "com.tencent.mm"

        // 检查本服务是否被用户在系统「无障碍」里勾选了。
        // 通过读 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES 字符串，按 ':' 分割比对组件名。
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, AccessibilityCaptureService::class.java)
                .flattenToString()
            val list = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            return list.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }
}
