package com.bookkeeping.app.ui.screen.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.repository.AccountRepository
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

@HiltViewModel
class TransactionViewModel @Inject constructor(
    private val repository: TransactionRepository,
    accounts: AccountRepository,
) : ViewModel() {
    val accountList = accounts.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // 当前选中的分类筛选；null = 不筛选（显示全部）
    private val _categoryFilter = MutableStateFlow<TransactionCategory?>(null)
    val categoryFilter: StateFlow<TransactionCategory?> = _categoryFilter

    // 原始所有交易（数据库 → Flow → StateFlow）
    private val allTransactions: StateFlow<List<TransactionEntity>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _suggestions = MutableStateFlow<List<TransactionRepository.DuplicateSuggestion>>(emptyList())
    val suggestions: StateFlow<List<TransactionRepository.DuplicateSuggestion>> = _suggestions
    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message

    fun findDuplicates(id: Long) = viewModelScope.launch {
        _suggestions.value = repository.suggestDuplicates(id)
    }
    fun clearSuggestions() { _suggestions.value = emptyList() }
    fun linkDuplicate(firstId: Long, secondId: Long) = viewModelScope.launch {
        _message.value = if (repository.linkDuplicate(firstId, secondId)) "已关联为同一消费，统计只计一次" else "无法关联，请核对金额、时间和余额账户"
    }
    fun unlinkDuplicate(id: Long) = viewModelScope.launch {
        _message.value = if (repository.unlinkDuplicate(id)) "已取消重复关联" else "取消关联失败"
    }
    fun createTransfer(fromId: Long, toId: Long?, amountCents: Long, feeCents: Long, note: String) =
        viewModelScope.launch {
            _message.value = if (repository.createTransfer(fromId, toId, amountCents, feeCents, note))
                "转账已记录，账户余额已同步" else "转账失败，请检查账户与金额"
        }
    fun remainingReturn(original: TransactionEntity): Long = original.amountCents -
        allTransactions.value.filter { it.relatedTransactionId == original.id &&
            it.relationKind in setOf("REFUND", "REIMBURSEMENT") }.sumOf { it.amountCents }

    fun returnCandidates(original: TransactionEntity): List<TransactionEntity> =
        allTransactions.value.filter { it.type == TransactionType.INCOME.name &&
            it.relatedTransactionId == null && it.duplicateOfId == null &&
            it.amountCents <= remainingReturn(original) &&
            it.timestamp >= original.timestamp && it.timestamp <= original.timestamp + 180L * 86_400_000L }
            .take(20)

    fun recordReturn(originalId: Long, kind: String, amountCents: Long,
                     accountId: Long?, note: String) = viewModelScope.launch {
        _message.value = if (repository.recordReturn(originalId, kind, amountCents, accountId, note))
            "已关联原支出，统计会按净消费计算" else "保存失败：退款金额可能超过原支出"
    }

    fun linkExistingReturn(originalId: Long, returnId: Long, kind: String) = viewModelScope.launch {
        _message.value = if (repository.linkExistingReturn(originalId, returnId, kind))
            "已关联已导入的退款或报销" else "关联失败，请核对金额与流水状态"
    }

    fun unlinkReturn(id: Long) = viewModelScope.launch {
        _message.value = if (repository.unlinkReturn(id)) "已取消退款关联" else "取消关联失败"
    }
    fun markOrdinaryIncome(id: Long) = viewModelScope.launch {
        _message.value = if (repository.markOrdinaryIncome(id)) "已按普通收入计入统计" else "操作失败"
    }
    fun restore(id: Long) = viewModelScope.launch {
        repository.restore(id)
        _message.value = "已恢复流水"
    }

    // 过滤后用于显示的交易列表 = allTransactions × categoryFilter
    // combine 把两个 Flow 合并：任一变化都触发重新计算
    // 嵌入式类比：两个输入信号通过组合逻辑产生新的输出
    val displayedTransactions: StateFlow<List<TransactionEntity>> =
        combine(allTransactions, _categoryFilter) { list, filter ->
            if (filter == null) list else list.filter { it.category == filter.name }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // 本月支出/收入：注意基于全部数据（不受 filter 影响）
    val monthlyExpenseCents: StateFlow<Long> = run {
        val (start, end) = currentMonthRange()
        repository.observeTotalExpenseBetween(start, end)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)
    }

    val monthlyIncomeCents: StateFlow<Long> = run {
        val (start, end) = currentMonthRange()
        repository.observeTotalIncomeBetween(start, end)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)
    }

    fun setCategoryFilter(category: TransactionCategory?) {
        _categoryFilter.value = category
    }

    fun addTransaction(
        amountCents: Long,
        type: TransactionType,
        category: TransactionCategory,
        merchant: String,
        note: String,
        accountId: Long?,
    ) {
        viewModelScope.launch {
            val entity = TransactionEntity(
                amountCents = amountCents,
                type = type.name,
                category = category.name,
                merchant = merchant,
                note = note,
                source = TransactionSource.MANUAL.name,
                timestamp = System.currentTimeMillis(),
                balanceAccountId = accountId,
            )
            repository.insert(entity)
        }
    }

    fun delete(entity: TransactionEntity, onDeleted: () -> Unit) {
        viewModelScope.launch {
            if (repository.delete(entity) > 0) onDeleted()
            else _message.value = "这笔流水关联了退款或辅助账单，请先取消关联"
        }
    }

    fun editTransaction(
        id: Long,
        amountCents: Long,
        type: TransactionType,
        category: TransactionCategory,
        merchant: String,
        note: String,
        accountId: Long?,
        onResult: (Boolean) -> Unit,
    ) {
        viewModelScope.launch {
            onResult(repository.edit(id, amountCents, type, category, merchant, note, accountId))
        }
    }

    fun setBalanceAccount(transaction: TransactionEntity, accountId: Long?, ignore: Boolean = false) {
        viewModelScope.launch { repository.setBalanceAccount(transaction.id, accountId, ignore) }
    }

    private fun currentMonthRange(): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        val end = cal.timeInMillis
        return start to end
    }
}
