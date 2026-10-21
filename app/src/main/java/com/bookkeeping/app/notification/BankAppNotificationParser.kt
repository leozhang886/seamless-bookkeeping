package com.bookkeeping.app.notification

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.local.entity.TransactionType
import java.math.BigDecimal

/** Only the CCB app's explicit account movement notice is safe to treat as a transaction. */
internal object BankAppNotificationParser {
    const val CCB_PACKAGE = "com.chinamworld.main"
    private val movement = Regex("""尾号\s*(\d{4}).{0,100}?(支出|收入)\s*人民币\s*([0-9]+(?:\.[0-9]{1,2})?)\s*元""")
    private val notCompleted = listOf("失败", "未成功", "已撤销", "预约")
    private val internalTransfer = listOf("基金申购", "基金购买", "基金赎回", "转账", "充值", "提现", "还款")

    fun parse(title: String?, text: String?, timestampMs: Long): TransactionEntity? {
        if (title?.contains("动账提醒") != true) return null
        val body = text?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (body.isBlank() || notCompleted.any(body::contains)) return null
        val match = movement.find(body) ?: return null
        val amountCents = runCatching {
            BigDecimal(match.groupValues[3]).movePointRight(2).setScale(0).longValueExact()
        }.getOrNull()?.takeIf { it > 0 } ?: return null
        // Grouped or promotional notices can mention more than one transaction; do not guess.
        if (movement.find(body, match.range.last + 1) != null) return null
        val outflow = match.groupValues[2] == "支出"
        val transfer = internalTransfer.any(body::contains)
        val type = when {
            transfer -> TransactionType.TRANSFER
            outflow -> TransactionType.EXPENSE
            else -> TransactionType.INCOME
        }
        return TransactionEntity(
            amountCents = amountCents,
            type = type.name,
            category = if (transfer) TransactionCategory.TRANSFER.name else CategoryClassifier.classify("", body).name,
            note = "[银行通知] " + body.take(500),
            source = TransactionSource.BANK_APP.name,
            account = "建设银行 (${match.groupValues[1]})",
            timestamp = timestampMs,
            bankFlow = if (transfer) if (outflow) "OUT" else "IN" else null,
        )
    }
}
