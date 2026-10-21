package com.bookkeeping.app

import com.bookkeeping.app.data.local.entity.AccountEntity
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.importer.BillImporter
import com.bookkeeping.app.data.importer.ConstructionBankPdfImporter
import com.bookkeeping.app.data.repository.balanceDeltaCents
import com.bookkeeping.app.data.repository.effectiveBalanceAccounts
import com.bookkeeping.app.data.repository.eligibleBalanceAccount
import com.bookkeeping.app.data.repository.matchingAccountForLiveTransaction
import com.bookkeeping.app.notification.SmsParser
import com.bookkeeping.app.notification.NotificationParser
import com.bookkeeping.app.ui.screen.transactions.parseAmountCents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class BalanceLinkingTest {
    private val bank = AccountEntity(id = 1, name = "建设银行（尾号1234）", kind = "BANK",
        balanceCents = 10000, updatedAt = 1000)
    private val alipay = AccountEntity(id = 2, name = "支付宝余额", kind = "ALIPAY",
        balanceCents = 5000, updatedAt = 1000)

    @Test fun onlyIdentifiedLiveAccountsAreLinked() {
        val bankExpense = SmsParser.parse(null, "【建设银行】您尾号1234消费人民币12.34元", 2000)!!
        assertEquals(-1234L, balanceDeltaCents(bankExpense))
        assertEquals(-1234L, balanceDeltaCents(bankExpense.copy(type = "TRANSFER", bankFlow = "OUT")))
        assertEquals(bank, matchingAccountForLiveTransaction(listOf(bank, alipay), bankExpense))

        val clearAlipay = TransactionEntity(amountCents = 2000, type = "EXPENSE", category = "OTHER",
            note = "[自动抓取] 付款成功￥20.00，支付宝余额支付", source = "ALIPAY", timestamp = 2000)
        assertEquals(alipay, matchingAccountForLiveTransaction(listOf(bank, alipay), clearAlipay))
        val captured = NotificationParser.parse("com.eg.android.AlipayGphone", "支付宝",
            "付款成功￥20.00，支付宝余额支付", 2000)!!
        assertEquals(alipay, matchingAccountForLiveTransaction(listOf(bank, alipay), captured))
        assertNull(matchingAccountForLiveTransaction(listOf(bank, alipay),
            clearAlipay.copy(note = "[自动抓取] 付款成功￥20.00")))
        assertNull(matchingAccountForLiveTransaction(listOf(bank, alipay),
            clearAlipay.copy(note = "[自动抓取] 付款成功￥20.00，余额宝扣款")))
    }

    @Test fun linkedChangesAreRecomputedAndOldRecordsAreExcluded() {
        val expense = TransactionEntity(id = 10, amountCents = 1234, type = "EXPENSE", category = "OTHER",
            source = "BANK_SMS", timestamp = 2000, balanceAccountId = bank.id)
        val income = expense.copy(id = 11, amountCents = 50, type = "INCOME", timestamp = 3000)
        assertEquals(8816L, effectiveBalanceAccounts(listOf(bank), listOf(expense, income)).single().balanceCents)
        assertEquals(10050L, effectiveBalanceAccounts(listOf(bank), listOf(income)).single().balanceCents)
        assertEquals(10000L, effectiveBalanceAccounts(listOf(bank.copy(updatedAt = 4000)),
            listOf(expense, income)).single().balanceCents)
        assertEquals(10000L, effectiveBalanceAccounts(listOf(bank),
            listOf(expense.copy(balanceTrackingVersion = null))).single().balanceCents)
    }

    @Test fun manualAmountUsesExactCents() {
        assertEquals(29L, parseAmountCents("0.29"))
        assertNull(parseAmountCents("0.001"))
    }

    @Test fun importedBillsChangeOnlyTheirConfirmedAccountAfterTheBalanceBaseline() {
        val bankBill = ConstructionBankPdfImporter.parseColumns(
            listOf("1", "消费", "20261020", "-12.34", "100.00", "", "商户"), "1234")
        assertEquals(bank, matchingAccountForLiveTransaction(listOf(bank, alipay), bankBill))
        assertEquals(8766L, effectiveBalanceAccounts(listOf(bank),
            listOf(bankBill.copy(balanceAccountId = bank.id))).single().balanceCents)
        assertNull(matchingAccountForLiveTransaction(
            listOf(bank.copy(updatedAt = bankBill.timestamp + 1)), bankBill))

        val csv = "交易时间,交易对方,商品说明,收/支,金额(元),收/付款方式,交易状态,交易订单号\n" +
            "2026-10-20 12:00:00,商户,商品,支出,20.00,支付宝余额,交易成功,ALI002\n" +
            "2026-10-20 12:01:00,商户,商品,支出,30.00,建设银行卡,交易成功,ALI003\n"
        val bills = BillImporter.parse(ByteArrayInputStream(csv.toByteArray()), "支付宝账单.csv")
        assertEquals(alipay, matchingAccountForLiveTransaction(listOf(bank, alipay), bills[0]))
        assertNull(matchingAccountForLiveTransaction(listOf(bank, alipay), bills[1]))
        assertEquals(false, eligibleBalanceAccount(alipay, bills[1]))
        assertEquals(3000L, effectiveBalanceAccounts(listOf(bank, alipay),
            listOf(bills[0].copy(balanceAccountId = alipay.id))).last().balanceCents)
    }
}
