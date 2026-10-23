package com.bookkeeping.app.data.importer

import com.bookkeeping.app.data.local.entity.TransactionEntity

data class BillRowIssue(val row: Int, val reason: String)

data class BillParseReport(
    val source: String,
    val records: List<TransactionEntity>,
    val originalRows: Int,
    val ignoredRows: Int,
    val rejected: List<BillRowIssue>,
    val blockingErrors: List<String> = emptyList(),
) {
    val amountCents: Long get() = records.fold(0L) { total, row -> Math.addExact(total, row.amountCents) }
}
