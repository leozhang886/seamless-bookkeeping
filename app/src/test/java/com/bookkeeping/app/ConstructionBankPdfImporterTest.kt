package com.bookkeeping.app

import com.bookkeeping.app.data.importer.ConstructionBankPdfImporter
import com.bookkeeping.app.data.local.entity.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConstructionBankPdfImporterTest {
    @Test fun fundPurchaseIsTransferAndCardNumberIsNotStored() {
        val row = listOf("1", "基金申购", "20261015", "-123.45", "1000.00", "手机银行", "1234567890123456/基金公司")
        val bill = ConstructionBankPdfImporter.parseColumns(row, "3456")
        assertEquals(12345L, bill.amountCents)
        assertEquals("OUT", bill.bankFlow)
        assertEquals(TransactionType.TRANSFER.name, bill.type)
        assertEquals("建设银行（尾号3456）", bill.account)
        assertFalse(bill.merchant.contains("1234567890123456"))
        assertTrue(bill.note.contains("仅含日期"))
    }

    @Test fun oppositePdfSignsKeepTheSameExactAmount() {
        val out = listOf("2", "汇兑", "20261015", "-3,300.00", "1000.00", "", "某账户")
        val incoming = out.toMutableList().apply { this[3] = "+3,300.00" }
        val debit = ConstructionBankPdfImporter.parseColumns(out, "3456")
        val credit = ConstructionBankPdfImporter.parseColumns(incoming, "3456")
        assertEquals(330000L, debit.amountCents)
        assertEquals(330000L, credit.amountCents)
        assertEquals("OUT", debit.bankFlow)
        assertEquals("IN", credit.bankFlow)
    }

    @Test fun repeatedRowHasStableKeyAndDistinctBalanceHasDifferentKey() {
        val row = listOf("1", "消费", "20261015", "-12.00", "988.00", "", "商户")
        val first = ConstructionBankPdfImporter.parseColumns(row, "3456")
        val repeated = ConstructionBankPdfImporter.parseColumns(row, "3456")
        val changed = ConstructionBankPdfImporter.parseColumns(row.toMutableList().apply { this[4] = "976.00" }, "3456")
        val nextSequence = ConstructionBankPdfImporter.parseColumns(row.toMutableList().apply { this[0] = "2" }, "3456")
        assertEquals(TransactionType.EXPENSE.name, first.type)
        assertEquals(first.importKey, repeated.importKey)
        assertNotEquals(first.importKey, changed.importKey)
        assertNotEquals(first.importKey, nextSequence.importKey)
    }
    @Test fun exportSequenceChangesPreserveSourceEvidenceButNeverMergeDistinctPostedBalances() {
        val columns = listOf("1", "汇兑", "20261015", "-12.00", "988.00", "手机银行", "某账户")
        val first = ConstructionBankPdfImporter.parseColumns(columns, "3456")
        val overlap = ConstructionBankPdfImporter.parseColumns(columns.toMutableList().apply { this[0] = "47" }, "3456")
        assertEquals(first.sourceIdentity, overlap.sourceIdentity)
        assertEquals("EXPENSE", first.type)
        assertTrue(first.reviewReason != null)
        val next = ConstructionBankPdfImporter.parseColumns(columns.toMutableList().apply { this[4] = "976.00" }, "3456")
        assertNotEquals(first.sourceIdentity, next.sourceIdentity)
        assertTrue(ConstructionBankPdfImporter.balancesContinuous(listOf(first, next)))
        assertFalse(ConstructionBankPdfImporter.balancesContinuous(listOf(first, next.copy(sourceBalanceCents = 97500))))
    }
}
