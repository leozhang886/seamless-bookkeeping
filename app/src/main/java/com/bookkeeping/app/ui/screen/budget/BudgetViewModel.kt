package com.bookkeeping.app.ui.screen.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.bookkeeping.app.data.budget.BudgetPreferences
import com.bookkeeping.app.data.budget.BudgetSettings
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.bookkeeping.app.ui.components.MonthSelection
import com.bookkeeping.app.ui.components.ledgerMonthRange
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class BudgetViewModel @Inject constructor(
    private val budgetPrefs: BudgetPreferences,
    private val transactionRepository: TransactionRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val period = MonthSelection(viewModelScope, savedStateHandle)
    val message = MutableStateFlow("")
    private var removed: Pair<TransactionCategory, Long>? = null

    val budget: StateFlow<BudgetSettings> = budgetPrefs.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetSettings())

    // 本月各分类的支出（cents）。只统计 EXPENSE。
    val expenseByCategory: StateFlow<Map<TransactionCategory, Long>> =
        period.month.flatMapLatest { month ->
            val range = ledgerMonthRange(month); transactionRepository.observeBetween(range.first, range.second)
        }
            .map { txs ->
                txs.filter { it.type == TransactionType.EXPENSE.name }
                    .groupBy {
                        runCatching { TransactionCategory.valueOf(it.category) }
                            .getOrDefault(TransactionCategory.OTHER)
                    }
                    .mapValues { (_, list) -> list.sumOf { it.amountCents } }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // 本月总支出
    val totalExpenseCents: StateFlow<Long> = expenseByCategory
        .map { it.values.sum() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val busy = MutableStateFlow(false)
    private fun change(onResult: (Boolean) -> Unit = {}, block: suspend () -> Unit) {
        if (busy.value) { onResult(false); return }
        busy.value = true
        viewModelScope.launch {
            try { block(); onResult(true) }
            catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { message.value = "保存失败，请重试"; onResult(false) }
            finally { busy.value = false }
        }
    }
    fun setMonthlyTotal(cents: Long, onResult: (Boolean) -> Unit = {}) = change(onResult) {
        budgetPrefs.setMonthlyTotal(cents); message.value = "月预算模板已保存"
    }
    fun setCategoryBudget(category: TransactionCategory, cents: Long, onResult: (Boolean) -> Unit = {}) = change(onResult) {
        budgetPrefs.setCategoryBudget(category, cents); message.value = "分类预算模板已保存"
    }
    fun removeCategoryBudget(category: TransactionCategory) = change {
        val old = category to (budget.value.perCategory[category] ?: 0)
        budgetPrefs.setCategoryBudget(category, 0L)
        removed = old; message.value = "分类预算已移除，可撤销"
    }
    fun undoRemove() {
        val (category, amount) = removed ?: return
        setCategoryBudget(category, amount) { if (it) removed = null }
    }
}
