package com.bookkeeping.app.ui.screen.transactions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookkeeping.app.data.local.entity.*
import com.bookkeeping.app.data.repository.AccountRepository
import com.bookkeeping.app.data.repository.TransactionRepository
import com.bookkeeping.app.data.repository.accountingPeriod
import com.bookkeeping.app.ui.components.MonthSelection
import com.bookkeeping.app.ui.components.ledgerMonthRange
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

/** All entry points wait for the same repository result before closing a form. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TransactionViewModel @Inject constructor(
    private val repository: TransactionRepository,
    accounts: AccountRepository,
    private val saved: SavedStateHandle,
    private val preferences: com.bookkeeping.app.data.settings.SettingsPreferences,
) : ViewModel() {
    val period = MonthSelection(viewModelScope, saved)
    val accountList = accounts.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val recentCategory = preferences.observeRecentCategory().map {
        runCatching { TransactionCategory.valueOf(it) }.getOrDefault(TransactionCategory.FOOD)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TransactionCategory.FOOD)
    val defaultAccountId = combine(preferences.observeDefaultAccount(), accountList) { preferred, list ->
        list.firstOrNull { preferred != null && it.id == preferred.first && it.name == preferred.second && it.kind == preferred.third }?.id
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    fun setDefaultAccount(id: Long?) = viewModelScope.launch {
        runCatching { preferences.setDefaultAccount(accountList.value.firstOrNull { it.id == id }) }
            .onFailure { _message.value = "常用账户保存失败" }
    }
    val allTransactions = repository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val deletedTransactions = repository.observeDeleted().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _categoryFilter = MutableStateFlow(saved.get<String>("category")?.let { runCatching { TransactionCategory.valueOf(it) }.getOrNull() })
    val categoryFilter = _categoryFilter.asStateFlow()
    private val _query = MutableStateFlow(saved.get<String>("query").orEmpty())
    val query = _query.asStateFlow()
    private val _filters = MutableStateFlow(TransactionFilters(
        saved.get<Long>("filterAccount"), saved.get<String>("filterType"), saved.get<String>("filterSource"),
        saved.get<Long>("filterMin"), saved.get<Long>("filterMax"), saved.get<Boolean>("filterPending") ?: false))
    val filters = _filters.asStateFlow()
    private val _showAll = MutableStateFlow(saved.get<Boolean>("allMonths") ?: false)
    val showAll = _showAll.asStateFlow()
    val displayedTransactions = combine(allTransactions, _categoryFilter, _query, _filters, _showAll) { rows, category, text, filter, all ->
        Triple(rows, category, Triple(text, filter, all))
    }.combine(period.month) { (rows, category, state), month ->
        val (text, filter, all) = state
        val range = ledgerMonthRange(month)
        rows.filter { row -> (all || row.timestamp >= range.first && row.timestamp < range.second) &&
            (category == null || row.category == category.name) && filter.matches(row) &&
            (text.isBlank() || listOfNotNull(row.merchant, row.note, row.account, row.sourceOrderId)
                .any { it.contains(text, ignoreCase = true) }) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val monthRows = period.month.flatMapLatest { month ->
        val (start, end) = ledgerMonthRange(month); repository.observeBetween(start, end)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val monthlyExpenseCents = monthRows.map { it.filter { row -> row.type == "EXPENSE" }.sumOf { row -> row.amountCents } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    val monthlyIncomeCents = monthRows.map { it.filter { row -> row.type == "INCOME" }.sumOf { row -> row.amountCents } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    private val _suggestions = MutableStateFlow<List<TransactionRepository.DuplicateSuggestion>>(emptyList())
    val suggestions = _suggestions.asStateFlow()
    private val _suggestionsLoading = MutableStateFlow(false)
    val suggestionsLoading = _suggestionsLoading.asStateFlow()
    private var suggestionJob: Job? = null
    private val _message = MutableStateFlow("")
    val message = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun setCategoryFilter(category: TransactionCategory?) { _categoryFilter.value = category; saved["category"] = category?.name }
    fun setQuery(value: String) { _query.value = value; saved["query"] = value }
    fun setFilters(value: TransactionFilters) { _filters.value = value
        saved["filterAccount"] = value.accountId; saved["filterType"] = value.type; saved["filterSource"] = value.source
        saved["filterMin"] = value.minimum; saved["filterMax"] = value.maximum; saved["filterPending"] = value.pendingOnly
    }
    fun setShowAll(value: Boolean) { _showAll.value = value; saved["allMonths"] = value }
    fun clearFilters() { setQuery(""); setCategoryFilter(null); setFilters(TransactionFilters()); setShowAll(false) }
    fun findDuplicates(id: Long) {
        suggestionJob?.cancel(); _suggestions.value = emptyList(); _suggestionsLoading.value = true
        suggestionJob = viewModelScope.launch {
            try { _suggestions.value = repository.suggestDuplicates(id) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { _message.value = "重复候选读取失败，请重试" }
            finally { _suggestionsLoading.value = false }
        }
    }
    fun clearSuggestions() { suggestionJob?.cancel(); _suggestions.value = emptyList(); _suggestionsLoading.value = false }
    private fun action(onResult: (Boolean) -> Unit = {}, block: suspend () -> Boolean) {
        if (_busy.value) { onResult(false); return }
        _busy.value = true
        viewModelScope.launch {
            try { onResult(block()) }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { _message.value = "操作失败：${error.message ?: "请稍后重试"}"; onResult(false) }
            finally { _busy.value = false }
        }
    }
    fun linkDuplicate(firstId: Long, secondId: Long, onResult: (Boolean) -> Unit = {}) = action(onResult) {
        repository.linkDuplicate(firstId, secondId).also { _message.value = if (it) "已关联同一资金流水，只计一次" else "无法关联，请核对方向、账户，或先解除已有关系" }
    }
    fun confirmIndependent(id: Long, onResult: (Boolean) -> Unit = {}) = action(onResult) { repository.confirmIndependent(id).also { _message.value = if (it) "已确认独立交易，统计和余额已刷新" else "核对失败" } }
    fun unlinkDuplicate(id: Long) = action { repository.unlinkDuplicate(id).also { _message.value = if (it) "已取消关联，请确认是否为独立交易" else "无法安全取消：旧关联缺少退款原归属，或关联后退款已更改。请先核对，原记录已保留。" } }
    fun createTransfer(fromId: Long, toId: Long?, cents: Long, fee: Long, note: String,
        at: Long = System.currentTimeMillis(), requestId: String? = null, onResult: (Boolean) -> Unit = {}) = action(onResult) {
        repository.createTransfer(fromId, toId, cents, fee, note, at, requestId).also { _message.value = if (it) "转账已保存，疑似重复资金腿请先核对" else "保存失败，请检查账户和金额" }
    }
    fun remainingReturn(original: TransactionEntity): Long = original.amountCents - allTransactions.value.filter {
        it.relatedTransactionId == original.id && it.duplicateOfId == null && it.reviewReason == null &&
            it.relationKind in setOf("REFUND", "REIMBURSEMENT") }.sumOf { it.amountCents }
    fun returnCandidates(original: TransactionEntity) = allTransactions.value.filter {
        it.type == "INCOME" && it.relatedTransactionId == null && it.duplicateOfId == null &&
            it.amountCents <= remainingReturn(original) && it.timestamp >= original.timestamp }
    fun recordReturn(originalId: Long, kind: String, cents: Long, accountId: Long?, note: String,
        at: Long = System.currentTimeMillis(), requestId: String? = null, onResult: (Boolean) -> Unit = {}) = action(onResult) {
        repository.recordReturn(originalId, kind, cents, accountId, note, at, requestId).also { _message.value = if (it) "已关联原支出，按净消费统计" else "保存失败，请核对退款额度和账户" }
    }
    fun linkExistingReturn(originalId: Long, returnId: Long, kind: String, onResult: (Boolean) -> Unit = {}) = action(onResult) {
        repository.linkExistingReturn(originalId, returnId, kind).also { _message.value = if (it) "已关联到账流水" else "关联失败，请核对额度和原交易" }
    }
    fun unlinkReturn(id: Long) = action { repository.unlinkReturn(id).also { _message.value = if (it) "已取消退款关联，待重新核对" else "取消失败" } }
    fun markOrdinaryIncome(id: Long) = action { repository.markOrdinaryIncome(id).also { _message.value = if (it) "已按普通收入统计" else "操作失败" } }
    fun restore(id: Long) = action { repository.restore(id).also { _message.value = if (it) "已恢复流水" else "恢复受阻：请核对原交易和已退款额度" } }
    fun addTransaction(cents: Long, type: TransactionType, category: TransactionCategory, merchant: String,
        note: String, accountId: Long?, at: Long = System.currentTimeMillis(), requestId: String? = null, onResult: (Boolean) -> Unit = {}) = action(onResult) {
        val id = repository.insert(TransactionEntity(amountCents = cents, type = type.name, category = category.name,
            merchant = merchant, note = note, source = "MANUAL", timestamp = at, balanceAccountId = accountId, clientRequestId = requestId))
        if (type == TransactionType.EXPENSE) runCatching { preferences.setRecentCategory(category.name) }
        _message.value = if (repository.getById(id)?.reviewReason != null) "已保存，疑似重复请先核对" else "账单已保存"
        true
    }
    fun delete(entity: TransactionEntity, onDeleted: () -> Unit) = action {
        (repository.delete(entity) > 0).also { if (it) onDeleted() else _message.value = "请先解除原交易的退款或辅助流水关系" }
    }
    fun editTransaction(id: Long, cents: Long, type: TransactionType, category: TransactionCategory,
        merchant: String, note: String, accountId: Long?, at: Long? = null, onResult: (Boolean) -> Unit) = action(onResult) {
        repository.edit(id, cents, type, category, merchant, note, accountId, at).also {
            _message.value = if (it) "修改已保存" else "保存失败：请核对账户、退款额度，或先取消重复关联"
        }
    }
    fun setBalanceAccount(row: TransactionEntity, accountId: Long?, ignore: Boolean = false, onResult: (Boolean) -> Unit = {}) = action(onResult) {
        repository.setBalanceAccount(row.id, accountId, ignore).also { _message.value = if (it) "余额账户已更新" else "无法计入该账户，请核对余额起点和资金来源" }
    }
}

data class TransactionFilters(val accountId: Long? = null, val type: String? = null,
    val source: String? = null, val minimum: Long? = null, val maximum: Long? = null, val pendingOnly: Boolean = false) {
    fun matches(row: TransactionEntity) = (accountId == null || row.balanceAccountId == accountId) &&
        (type == null || row.type == type) && (source == null || if (source == "NOTIFICATION") row.importKey == null && row.source in setOf("ALIPAY", "WECHAT", "BANK_APP") else row.source == source) &&
        (minimum == null || row.amountCents >= minimum) && (maximum == null || row.amountCents <= maximum) &&
        (!pendingOnly || row.reviewReason != null || row.relationKind in setOf("REFUND_PENDING", "REIMBURSEMENT_PENDING"))
}