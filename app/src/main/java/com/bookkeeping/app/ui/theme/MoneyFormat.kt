package com.bookkeeping.app.ui.theme

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/** Presentation only: the ledger continues to store integer cents. */
fun formatYuan(cents: Long): String = NumberFormat.getNumberInstance(Locale.CHINA).apply {
    minimumFractionDigits = 2
    maximumFractionDigits = 2
    isGroupingUsed = true
}.format(BigDecimal.valueOf(cents, 2))

fun sourceLabel(source: String): String = when (source) {
    "MANUAL" -> "手动记账"
    "ALIPAY" -> "支付宝"
    "WECHAT" -> "微信"
    "BANK_PDF" -> "银行 PDF"
    "BANK_APP" -> "银行通知"
    "BANK_SMS" -> "历史短信"
    else -> source
}
fun transactionAmount(row: com.bookkeeping.app.data.local.entity.TransactionEntity): String {
    val sign = when (row.type) {
        "EXPENSE" -> "−"
        "INCOME" -> "+"
        else -> when (row.bankFlow) { "IN" -> "+"; "OUT" -> "−"; else -> "转 " }
    }
    return "$sign¥${formatYuan(row.amountCents)}"
}
