package com.bookkeeping.app

import com.bookkeeping.app.data.local.entity.AccountEntity
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.data.repository.matchingAccountForLiveTransaction
import com.bookkeeping.app.data.repository.sameBankMovement
import com.bookkeeping.app.notification.NotificationParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BankAppNotificationTest {
    private val ccb = "com.chinamworld.main"
    private val notice = "您尾号4562的储蓄账户10月19日11时43分支出人民币10.00元。"

    @Test fun screenshotNoticeIsCapturedAndMatchesOnlyItsCard() {
        val transaction = NotificationParser.parse(ccb, "动账提醒", notice, 2_000L)!!
        assertEquals(1000L, transaction.amountCents)
        assertEquals(TransactionType.EXPENSE.name, transaction.type)
        assertEquals("BANK_APP", transaction.source)
        assertEquals("建设银行 (4562)", transaction.account)
        val matching = AccountEntity(1, "建设银行尾号4562", "BANK", 10000, 1000)
        val other = matching.copy(id = 2, name = "建设银行尾号1234")
        assertEquals(matching, matchingAccountForLiveTransaction(listOf(matching, other), transaction))
    }

    @Test fun repeatedNoticesAreDeduplicatedWithoutDroppingDifferentAmounts() {
        val first = NotificationParser.parse(ccb, "动账提醒", notice, 2_000L)!!
        val repeated = NotificationParser.parse(ccb, "动账提醒", notice, 70_000L)!!
        val five = NotificationParser.parse(ccb, "动账提醒", notice.replace("10.00", "5.00"), 70_000L)!!
        assertTrue(sameBankMovement(first, repeated))
        assertFalse(sameBankMovement(first, five))
        assertFalse(sameBankMovement(first, repeated.copy(note = "另一笔真实交易")))
        assertTrue(sameBankMovement(first, repeated.copy(source = "BANK_SMS", timestamp = 30_000L)))
    }

    @Test fun advertisingAndUnclearNoticesAreSkipped() {
        assertNull(NotificationParser.parse(ccb, "活动提醒", "购买10元优惠券", 2_000L))
        assertNull(NotificationParser.parse(ccb, "动账提醒", "尾号4562可领取10.00元红包", 2_000L))
        assertNull(NotificationParser.parse(ccb, "动账提醒", notice.replace("支出", "支付失败"), 2_000L))
        assertNull(NotificationParser.parse("com.example.fakebank", "动账提醒", notice, 2_000L))
    }

    @Test fun fundPurchaseStaysTransferAndBankDebit() {
        val transaction = NotificationParser.parse(ccb, "动账提醒", "$notice 基金申购成功", 2_000L)
        assertNotNull(transaction)
        assertEquals(TransactionType.TRANSFER.name, transaction!!.type)
        assertEquals("OUT", transaction.bankFlow)
    }
}
