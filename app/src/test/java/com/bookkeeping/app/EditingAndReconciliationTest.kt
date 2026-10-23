package com.bookkeeping.app

import com.bookkeeping.app.data.local.entity.AccountEntity
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.data.repository.editedTransaction
import com.bookkeeping.app.data.repository.balanceEditImpactCents
import com.bookkeeping.app.data.repository.effectiveBalanceAccounts
import com.bookkeeping.app.ui.screen.assets.pendingForAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class EditingAndReconciliationTest {
    private val bank = AccountEntity(1, "建设银行尾号1234", "BANK", 10000, 1000)
    private val alipay = AccountEntity(2, "支付宝余额", "ALIPAY", 5000, 1000)
    private val expense = TransactionEntity(
        id = 7, amountCents = 2000, type = "EXPENSE", category = "SHOPPING",
        merchant = "商店", source = "MANUAL", timestamp = 2000, balanceAccountId = bank.id,
    )

    @Test fun editingAmountAndDirectionRecomputesLinkedBalance() {
        assertEquals(8000L, effectiveBalanceAccounts(listOf(bank), listOf(expense)).single().balanceCents)
        val edited = editedTransaction(expense, 3000, TransactionType.INCOME,
            TransactionCategory.OTHER, "  退款  ", "  核对过  ", bank.id)!!
        assertEquals(13000L, effectiveBalanceAccounts(listOf(bank), listOf(edited)).single().balanceCents)
        assertEquals(expense.id, edited.id)
        assertEquals(expense.timestamp, edited.timestamp)
        assertEquals(expense.source, edited.source)
        assertEquals("退款", edited.merchant)
        assertEquals("核对过", edited.note)
        assertEquals(BigDecimal.valueOf(5000), balanceEditImpactCents(expense, edited, listOf(bank))[bank.id])
    }

    @Test fun changingAccountMovesTheEffectWithoutChangingStartingBalances() {
        val edited = editedTransaction(expense, 2000, TransactionType.EXPENSE,
            TransactionCategory.SHOPPING, expense.merchant, expense.note, alipay.id)!!
        val balances = effectiveBalanceAccounts(listOf(bank, alipay), listOf(edited))
        assertEquals(10000L, balances[0].balanceCents)
        assertEquals(3000L, balances[1].balanceCents)
        assertEquals(10000L, effectiveBalanceAccounts(listOf(bank.copy(updatedAt = 3000)), listOf(expense))
            .single().balanceCents)
        val impact = balanceEditImpactCents(expense, edited, listOf(bank, alipay))
        assertEquals(BigDecimal.valueOf(2000), impact[bank.id])
        assertEquals(BigDecimal.valueOf(-2000), impact[alipay.id])
    }

    @Test fun transferCannotAccidentallyBecomeIncome() {
        val transfer = expense.copy(type = "TRANSFER", category = "TRANSFER", bankFlow = "OUT", relationKind = "TRANSFER_OUT")
        assertNull(editedTransaction(transfer, 2000, TransactionType.INCOME,
            TransactionCategory.OTHER, "", "", bank.id))
        assertNull(editedTransaction(expense, 0, TransactionType.EXPENSE,
            TransactionCategory.SHOPPING, "", "", bank.id))
    }

    @Test fun reconciliationShowsOnlyEligibleUnlinkedTransactions() {
        val pending = expense.copy(id = 10, balanceAccountId = null)
        val imported = pending.copy(id = 8, importKey = "old-import")
        val otherBank = pending.copy(id = 9, source = "BANK_SMS", account = "工商银行 (5678)")
        val ignored = pending.copy(id = 11, balanceIgnored = true)
        val result = pendingForAccount(bank, listOf(bank, alipay),
            listOf(expense, pending, imported, otherBank, ignored))
        assertTrue(result.any { it.id == pending.id })
        assertFalse(result.any { it.id == imported.id })
        assertFalse(result.any { it.id == expense.id })
        assertFalse(result.any { it.id == otherBank.id })
        assertFalse(result.any { it.id == ignored.id })
    }
}
