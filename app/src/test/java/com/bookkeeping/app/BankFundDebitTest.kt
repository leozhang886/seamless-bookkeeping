package com.bookkeeping.app

import com.bookkeeping.app.data.local.entity.AccountEntity
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.data.repository.matchingBankForFundDebit
import com.bookkeeping.app.data.repository.effectiveBalanceAccounts
import com.bookkeeping.app.notification.SmsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BankFundDebitTest {
    private val sms = "【建设银行】您尾号1234账户基金申购成功，扣款人民币40.80元，余额100.00元"
    private val account = AccountEntity(id = 1, name = "建设银行（尾号1234）", kind = "BANK",
        balanceCents = 10000, updatedAt = 1000)

    @Test fun purchaseIsTransferAndMatchesOnlyTheRightBankCard() {
        val transaction = SmsParser.parse(null, sms, 2000)!!
        assertEquals(4080L, transaction.amountCents)
        assertEquals(TransactionType.TRANSFER.name, transaction.type)
        assertEquals(TransactionCategory.TRANSFER.name, transaction.category)
        assertEquals(account, matchingBankForFundDebit(listOf(account), transaction))
        assertEquals(5920L, effectiveBalanceAccounts(listOf(account),
            listOf(transaction.copy(balanceAccountId = account.id))).single().balanceCents)
        assertNull(matchingBankForFundDebit(listOf(account.copy(name = "建设银行（尾号5678）")), transaction))
        assertNull(matchingBankForFundDebit(listOf(account.copy(kind = "FUND")), transaction))
        assertNull(matchingBankForFundDebit(listOf(account, account.copy(id = 2)), transaction))
    }

    @Test fun oldSmsAndUnmatchedCardNeverDebitCurrentBalance() {
        val old = SmsParser.parse(null, sms, 1000)!!
        assertNull(matchingBankForFundDebit(listOf(account), old))
        val noTail = SmsParser.parse(null, "【建设银行】基金申购成功，扣款人民币40.80元", 2000)!!
        assertNull(matchingBankForFundDebit(listOf(account), noTail))
    }

    @Test fun failedFundPurchaseIsIgnored() {
        assertNull(SmsParser.parse(null, "【建设银行】您尾号1234基金申购扣款失败，人民币40.80元", 2000))
        assertNull(SmsParser.parse(null, "【建设银行】您尾号1234预约基金申购人民币40.80元", 2000))
    }
}
