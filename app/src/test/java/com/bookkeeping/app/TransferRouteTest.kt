package com.bookkeeping.app

import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.ui.screen.transactions.transferRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransferRouteTest {
    @Test fun bankFundPurchaseShowsBothSides() {
        val purchase = TransactionEntity(
            amountCents = 4080,
            type = TransactionType.TRANSFER.name,
            category = "TRANSFER",
            note = "基金申购 / 原账单仅含日期",
            source = "BANK_PDF",
            account = "建设银行（尾号1234）",
            timestamp = 1L,
        )
        assertEquals("建设银行（尾号1234） −¥40.80 → 基金 +¥40.80", transferRoute(purchase))
        assertNull(transferRoute(purchase.copy(type = TransactionType.INCOME.name)))
    }

    @Test fun bankTransferShowsThePdfAmountDirection() {
        val transfer = TransactionEntity(amountCents = 28331, type = TransactionType.TRANSFER.name,
            category = "TRANSFER", note = "还款 / 原账单仅含日期", source = "BANK_PDF",
            account = "建设银行（尾号1234）", timestamp = 1L, bankFlow = "OUT")
        assertEquals("建设银行（尾号1234） −¥283.31", transferRoute(transfer))
        assertEquals("建设银行（尾号1234） +¥283.31", transferRoute(transfer.copy(bankFlow = "IN")))
        assertEquals("银行卡方向待补全，请重新导入原 PDF", transferRoute(transfer.copy(bankFlow = null)))
        assertEquals("建设银行（尾号1234） −¥283.31", transferRoute(transfer.copy(source = "BANK_APP")))
        assertEquals("建设银行（尾号1234） +¥283.31", transferRoute(transfer.copy(source = "BANK_APP", bankFlow = "IN")))
    }
}
