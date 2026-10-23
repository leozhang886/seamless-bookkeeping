package com.bookkeeping.app

import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.notification.NotificationParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationParserTest {
    private val alipay = "com.eg.android.AlipayGphone"
    private val wechat = "com.tencent.mm"

    @Test fun couponGiftIsNotCashIncomeOrExpense() {
        assertNull(NotificationParser.parse(alipay, "淘宝闪购", "送你20元红包，点击领取", 1L))
        assertNull(NotificationParser.parse(alipay, "支付宝", "淘宝闪购20元红包已到账，立即使用", 1L))
        assertNull(NotificationParser.parse(alipay, "支付宝", "收到红包20元", 1L))
        assertNull(NotificationParser.parse(alipay, "支付宝", "活动返现20元，点击查看", 1L))
    }

    @Test fun confirmedPaymentsAndRefundsStillCount() {
        val payment = NotificationParser.parse(alipay, "付款成功", "付款成功￥30.73 便利店", 2L)
        assertNotNull(payment)
        assertEquals(3073L, payment!!.amountCents)
        assertEquals(TransactionType.EXPENSE.name, payment.type)

        val receipt = NotificationParser.parse(wechat, "微信支付", "向你付款 20.00 元", 3L)
        assertEquals(TransactionType.INCOME.name, receipt!!.type)

        val refund = NotificationParser.parse(alipay, "支付宝", "收到退款 12.34 元", 4L)
        assertEquals(1234L, refund!!.amountCents)
        assertEquals(TransactionType.INCOME.name, refund.type)
    }

    @Test fun unrelatedAmountAndFailedPaymentAreIgnored() {
        assertNull(NotificationParser.parse(alipay, "活动提醒", "限时活动，奖励20元", 5L))
        assertNull(NotificationParser.parse(alipay, "支付宝", "支付失败￥20.00", 6L))
    }
    @Test fun commaAmountAndDiscountedActualPaymentAreExact() {
        assertEquals(123456L, NotificationParser.parse(alipay, "付款成功", "付款成功￥1,234.56 商店", 10)!!.amountCents)
        assertEquals(1200L, NotificationParser.parse(alipay, "付款成功", "原价32.00元，优惠券20.00元，实付12.00元", 10)!!.amountCents)
        assertNull(NotificationParser.parse(alipay, "付款成功", "消费10元，消费20元", 10))
        assertNull(NotificationParser.parse(alipay, "付款成功", "￥12,34.56", 10))
    }
}
