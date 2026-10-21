package com.bookkeeping.app.ui.screen.transactions

import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.local.entity.TransactionType
import java.math.BigDecimal

internal fun transferRoute(transaction: TransactionEntity): String? {
    if (transaction.source !in setOf(TransactionSource.BANK_PDF.name, TransactionSource.BANK_SMS.name,
            TransactionSource.BANK_APP.name) ||
        transaction.type != TransactionType.TRANSFER.name) return null
    val bank = transaction.account ?: "银行卡"
    val amount = "¥${BigDecimal.valueOf(transaction.amountCents, 2).toPlainString()}"
    return when {
        (transaction.note.contains("基金申购") || transaction.note.contains("基金购买")) &&
            transaction.bankFlow != "IN" -> "$bank −$amount → 基金 +$amount"
        transaction.note.contains("基金赎回") && transaction.bankFlow != "OUT" ->
            "基金 −$amount → $bank +$amount"
        transaction.source in setOf(TransactionSource.BANK_PDF.name, TransactionSource.BANK_APP.name) &&
            transaction.bankFlow == "OUT" ->
            "$bank −$amount"
        transaction.source in setOf(TransactionSource.BANK_PDF.name, TransactionSource.BANK_APP.name) &&
            transaction.bankFlow == "IN" ->
            "$bank +$amount"
        transaction.source == TransactionSource.BANK_PDF.name -> "银行卡方向待补全，请重新导入原 PDF"
        else -> null
    }
}
