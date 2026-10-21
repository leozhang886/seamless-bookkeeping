package com.bookkeeping.app

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.ui.screen.stats.categoryTransactions
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryDetailsTest {
    @Test
    fun otherExpenseDetailsIncludeLegacyCategoryButExcludeIncomeAndTransfers() {
        fun transaction(id: Long, type: TransactionType, category: String, cents: Long) =
            TransactionEntity(id = id, type = type.name, category = category,
                amountCents = cents, source = "MANUAL", timestamp = 1L)

        val month = listOf(
            transaction(1, TransactionType.EXPENSE, "OTHER", 1_000),
            transaction(2, TransactionType.EXPENSE, "LEGACY_UNKNOWN", 2_000),
            transaction(3, TransactionType.INCOME, "OTHER", 3_000),
            transaction(4, TransactionType.TRANSFER, "OTHER", 4_000),
            transaction(5, TransactionType.EXPENSE, "FOOD", 5_000),
        )

        val details = categoryTransactions(month, TransactionType.EXPENSE, TransactionCategory.OTHER)
        assertEquals(listOf(1L, 2L), details.map { it.id })
        assertEquals(3_000L, details.sumOf { it.amountCents })
        assertEquals(listOf(5L), categoryTransactions(month, TransactionType.EXPENSE,
            TransactionCategory.FOOD).map { it.id })
    }
}
