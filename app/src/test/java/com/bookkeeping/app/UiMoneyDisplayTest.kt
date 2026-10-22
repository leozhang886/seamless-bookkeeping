package com.bookkeeping.app

import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.ui.screen.transactions.dailyExpenseCents
import com.bookkeeping.app.ui.theme.formatYuan
import org.junit.Assert.assertEquals
import org.junit.Test

class UiMoneyDisplayTest {
    @Test fun moneyDisplayKeepsTwoDecimalsAndGroupsThousands() {
        assertEquals("12,345.67", formatYuan(1_234_567))
        assertEquals("-12.34", formatYuan(-1_234))
    }

    @Test fun dayHeaderNetsLinkedRefundWithoutCountingDuplicate() {
        val expense = TransactionEntity(id = 1, amountCents = 10_000, type = "EXPENSE",
            category = "FOOD", source = "MANUAL", timestamp = 100)
        val duplicate = expense.copy(id = 2, duplicateOfId = 1)
        assertEquals(7_000L, dailyExpenseCents(listOf(expense, duplicate), mapOf(1L to 3_000L)))
    }
}
