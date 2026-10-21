package com.bookkeeping.app

import com.bookkeeping.app.data.local.entity.AccountEntity
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.repository.accountingPeriod
import com.bookkeeping.app.data.repository.effectiveBalanceAccounts
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountingIntegrityTest {
    private fun tx(id: Long, cents: Long, type: String, at: Long = 100L,
                   accountId: Long? = null, bankFlow: String? = null,
                   related: Long? = null, relation: String? = null,
                   duplicate: Long? = null, deleted: Long? = null) = TransactionEntity(
        id = id, amountCents = cents, type = type, category = "OTHER", source = "MANUAL",
        timestamp = at, balanceAccountId = accountId, bankFlow = bankFlow,
        relatedTransactionId = related, relationKind = relation,
        duplicateOfId = duplicate, deletedAt = deleted)

    @Test fun refundAndReimbursementReduceOriginalSpendingWithoutIncome() {
        val original = tx(1, 10_000, "EXPENSE", at = 100)
        val refund = tx(2, 3_000, "INCOME", at = 200, related = 1, relation = "REFUND")
        val reimbursement = tx(3, 2_000, "INCOME", at = 300, related = 1, relation = "REIMBURSEMENT")
        assertEquals(5_000L, accountingPeriod(listOf(original, refund, reimbursement), 0, 150)
            .single().amountCents)
        assertEquals(emptyList<TransactionEntity>(),
            accountingPeriod(listOf(original, refund, reimbursement), 150, 400))
    }

    @Test fun duplicateDeletedAndTransferNeverInflateTotals() {
        val original = tx(1, 1_000, "EXPENSE")
        val duplicate = tx(2, 1_000, "EXPENSE", duplicate = 1)
        val deleted = tx(3, 2_000, "EXPENSE", deleted = 500)
        val transfer = tx(4, 5_000, "TRANSFER", bankFlow = "OUT")
        val rows = accountingPeriod(listOf(original, duplicate, deleted, transfer), 0, 200)
        assertEquals(1_000L, rows.filter { it.type == "EXPENSE" }.sumOf { it.amountCents })
        assertEquals(0L, rows.filter { it.type == "INCOME" }.sumOf { it.amountCents })
    }

    @Test fun pairedTransferMovesBothBalancesAndFeeIsExpense() {
        val accounts = listOf(AccountEntity(1, "银行卡", "BANK", 10_000, 10),
            AccountEntity(2, "微信零钱", "WECHAT", 2_000, 10))
        val out = tx(1, 3_000, "TRANSFER", accountId = 1, bankFlow = "OUT", relation = "TRANSFER_OUT")
        val incoming = tx(2, 3_000, "TRANSFER", accountId = 2, bankFlow = "IN", related = 1, relation = "TRANSFER_IN")
        val fee = tx(3, 50, "EXPENSE", accountId = 1, related = 1, relation = "TRANSFER_FEE")
        val result = effectiveBalanceAccounts(accounts, listOf(out, incoming, fee))
        assertEquals(6_950L, result[0].balanceCents)
        assertEquals(5_000L, result[1].balanceCents)
        assertEquals(50L, accountingPeriod(listOf(out, incoming, fee), 0, 200)
            .filter { it.type == "EXPENSE" }.sumOf { it.amountCents })
    }
}
