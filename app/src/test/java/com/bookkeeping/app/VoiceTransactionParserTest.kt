package com.bookkeeping.app

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.ui.screen.transactions.parseVoiceTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceTransactionParserTest {
    @Test fun chineseAmountAndMeal() {
        val draft = parseVoiceTransaction("午饭花了二十五块五")!!
        assertEquals("25.5", draft.amountText)
        assertEquals(TransactionType.EXPENSE, draft.type)
        assertEquals(TransactionCategory.FOOD, draft.category)
    }

    @Test fun salaryAndDecimal() {
        val draft = parseVoiceTransaction("工资到账三千元")!!
        assertEquals("3000", draft.amountText)
        assertEquals(TransactionType.INCOME, draft.type)
        assertEquals(TransactionCategory.SALARY, draft.category)
        assertEquals("18.5", parseVoiceTransaction("打车18.50元")!!.amountText)
    }

    @Test fun bareAmountAndUnclearAmount() {
        assertEquals("18", parseVoiceTransaction("打车18")!!.amountText)
        assertNull(parseVoiceTransaction("昨天打车18又花20")!!.amountText)
        assertNull(parseVoiceTransaction("原价20元实际付10元")!!.amountText)
        assertNull(parseVoiceTransaction("买东西两百五元")!!.amountText)
        assertEquals("20.85", parseVoiceTransaction("午饭二十元八角五分")!!.amountText)
    }

    @Test fun transfersDoNotBecomeIncomeOrExpense() {
        assertNull(parseVoiceTransaction("银行卡转账支付宝余额200元"))
        assertNull(parseVoiceTransaction("基金申购50元"))
        assertNull(parseVoiceTransaction("信用卡还款100元"))
    }
}
