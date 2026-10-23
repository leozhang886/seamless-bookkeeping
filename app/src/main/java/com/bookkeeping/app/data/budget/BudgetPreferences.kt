package com.bookkeeping.app.data.budget

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.BudgetEntryEntity
import com.bookkeeping.app.data.local.BookkeepingDatabase
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

// DataStore 委托必须放在 file top level（不能在 class 里）
private val Context.budgetDataStore: DataStore<Preferences> by preferencesDataStore(name = "budget_prefs")

// Import the old DataStore once; all subsequent writes share the ledger transaction.
@Singleton
class BudgetPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: BookkeepingDatabase,
) {
    private val dao get() = database.ledgerMetaDao()
    suspend fun snapshotInTransaction(): BudgetSettings {
        val rows = dao.allBudgets()
        return BudgetSettings(rows.firstOrNull { it.category == "TOTAL" }?.amountCents ?: 0,
            rows.mapNotNull { row -> runCatching { TransactionCategory.valueOf(row.category) }.getOrNull()
                ?.takeIf { row.amountCents > 0 }?.let { it to row.amountCents } }.toMap())
    }
    suspend fun ensureMigrated() {
        if (dao.allBudgets().any { it.category == "TOTAL" }) return
        val legacy = context.budgetDataStore.data.first()
        database.withTransaction {
            if (dao.allBudgets().any { it.category == "TOTAL" }) return@withTransaction
            dao.putBudget(BudgetEntryEntity("TOTAL", legacy[KEY_MONTHLY_TOTAL] ?: 0L))
            TransactionCategory.entries.forEach { category ->
                legacy[longKey(category)]?.takeIf { it > 0 }?.let {
                    dao.putBudget(BudgetEntryEntity(category.name, it))
                }
            }
        }
    }
    fun observe(): Flow<BudgetSettings> = flow {
        ensureMigrated()
        emitAll(dao.observeBudgets().map { rows ->
            BudgetSettings(rows.firstOrNull { it.category == "TOTAL" }?.amountCents ?: 0,
                rows.mapNotNull { row -> runCatching { TransactionCategory.valueOf(row.category) }
                    .getOrNull()?.takeIf { it !in setOf(TransactionCategory.SALARY, TransactionCategory.TRANSFER) && row.amountCents > 0 }
                    ?.let { it to row.amountCents } }.toMap())
        })
    }

    suspend fun setMonthlyTotal(cents: Long) {
        ensureMigrated()
        database.writeLedger { dao.putBudget(BudgetEntryEntity("TOTAL", cents.coerceAtLeast(0))) }
    }

    suspend fun setCategoryBudget(category: TransactionCategory, cents: Long) {
        ensureMigrated()
        database.writeLedger { dao.putBudget(BudgetEntryEntity(category.name, cents.coerceAtLeast(0))) }
    }

    suspend fun replace(settings: BudgetSettings) {
        database.writeLedger { replaceInTransaction(settings) }
    }
    suspend fun replaceInTransaction(settings: BudgetSettings) {
        dao.clearBudgets()
        dao.putBudget(BudgetEntryEntity("TOTAL", settings.monthlyTotalCents.coerceAtLeast(0)))
        settings.perCategory.forEach { (category, amount) ->
            dao.putBudget(BudgetEntryEntity(category.name, amount.coerceAtLeast(0)))
        }
    }

    private fun longKey(cat: TransactionCategory) = longPreferencesKey("cat_${cat.name}")

    companion object {
        private val KEY_MONTHLY_TOTAL = longPreferencesKey("monthly_total")
    }
}
