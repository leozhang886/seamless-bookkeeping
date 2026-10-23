package com.bookkeeping.app.ui.screen.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearProgressIndicator
import com.bookkeeping.app.ui.components.*
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.AccountEntity
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.data.repository.balanceDeltaCents
import com.bookkeeping.app.data.repository.canLinkBalance
import com.bookkeeping.app.data.repository.eligibleBalanceAccount
import com.bookkeeping.app.ui.theme.LocalFinancePalette
import com.bookkeeping.app.ui.theme.sourceLabel
import com.bookkeeping.app.ui.theme.formatYuan
import java.text.SimpleDateFormat
import java.math.BigDecimal
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionListScreen(
    viewModel: TransactionViewModel = hiltViewModel(),
    openAddRequest: Boolean = false,
    onAddOpened: () -> Unit = {},
) {
    val transactions by viewModel.displayedTransactions.collectAsState()
    val allTransactions by viewModel.allTransactions.collectAsState()
    val deleted by viewModel.deletedTransactions.collectAsState()
    val query by viewModel.query.collectAsState()
    val filters by viewModel.filters.collectAsState()
    val showAll by viewModel.showAll.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val suggestionsLoading by viewModel.suggestionsLoading.collectAsState()
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var showTrash by rememberSaveable { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    val recentCategory by viewModel.recentCategory.collectAsState()
    val defaultAccountId by viewModel.defaultAccountId.collectAsState()
    val accounts by viewModel.accountList.collectAsState()
    val monthlyExpense by viewModel.monthlyExpenseCents.collectAsState()
    val monthlyIncome by viewModel.monthlyIncomeCents.collectAsState()
    val selectedCategory by viewModel.categoryFilter.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var showTransferDialog by rememberSaveable { mutableStateOf(false) }
    var reviewingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val reviewing = allTransactions.firstOrNull { it.id == reviewingId }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val editing = allTransactions.firstOrNull { it.id == editingId }
    var editError by remember { mutableStateOf<String?>(null) }
    var duplicateReviewId by rememberSaveable { mutableStateOf<Long?>(null) }
    val duplicateReview = allTransactions.firstOrNull { it.id == duplicateReviewId }
    var returnReviewId by rememberSaveable { mutableStateOf<Long?>(null) }
    val returnReview = allTransactions.firstOrNull { it.id == returnReviewId }
    LaunchedEffect(openAddRequest) {
        if (openAddRequest) {
            showAddDialog = true
            onAddOpened()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = { TopAppBar(
            title = { Text("账单", style = MaterialTheme.typography.headlineSmall) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        ) },
        floatingActionButton = {
            FloatingActionButton(onClick = { formError = null; showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary) {
                Icon(Icons.Default.Add, contentDescription = "新建账单")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (message.isNotBlank()) Text(message, modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.primary)
            MonthSelector(viewModel.period)
            // 顶部统计卡片（基于全月数据，不受 filter 影响）
            MonthlySummaryCard(
                monthlyExpenseCents = monthlyExpense,
                monthlyIncomeCents = monthlyIncome,
            )

            OutlinedTextField(query, viewModel::setQuery, label = { Text("搜索商户、备注或订单号") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                FilterChip(showAll, onClick = { viewModel.setShowAll(!showAll) }, label = { Text("跨月搜索") })
                TextButton(onClick = { showFilters = true }) { Text("筛选") }
                TextButton(onClick = viewModel::clearFilters) { Text("清除筛选") }
                TextButton(onClick = { showTrash = true }) { Text("回收站") }
            }
            // 分类筛选 chip 行
            CategoryFilterRow(
                selected = selectedCategory,
                onSelect = viewModel::setCategoryFilter,
            )
            TextButton(onClick = { formError = null; showTransferDialog = true }, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("账户转账")
            }

            if (transactions.isEmpty()) {
                EmptyState(filterActive = true)
            } else {
                // 按日期分组渲染
                GroupedTransactionList(
                    transactions = transactions,
                    allTransactions = allTransactions,
                    onDelete = { tx -> viewModel.delete(tx) {
                        scope.launch {
                            if (snackbarHost.showSnackbar("已删除流水", "撤销") == SnackbarResult.ActionPerformed)
                                viewModel.restore(tx.id)
                        }
                    } },
                    accounts = accounts,
                    onReview = { reviewingId = it.id },
                    onEdit = { editingId = it.id; editError = null },
                    onDuplicateReview = { duplicateReviewId = it.id; viewModel.findDuplicates(it.id) },
                    onUnlinkDuplicate = { viewModel.unlinkDuplicate(it.id) },
                    onReturnReview = { returnReviewId = it.id },
                    onUnlinkReturn = { viewModel.unlinkReturn(it.id) },
                    onMarkOrdinaryIncome = { viewModel.markOrdinaryIncome(it.id) },
                )
            }
        }
    }
    duplicateReview?.let { tx -> AlertDialog(
        onDismissRequest = { duplicateReviewId = null; viewModel.clearSuggestions() },
        title = { Text("核对是否同一笔流水") },
        text = { Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("当前：${tx.merchant} · ¥${BigDecimal.valueOf(tx.amountCents, 2).toPlainString()} · ${sourceLabel(tx.source)}")
            Text("以下只是相似流水，必须对照原账单、商户和付款方式。")
            if (suggestionsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!suggestionsLoading && suggestions.isEmpty()) Text("没有找到相近的其他来源流水。")
            suggestions.forEach { suggestion ->
                val other = suggestion.other
                Text("${ledgerDate(other.timestamp)} · ${other.merchant} · ${sourceLabel(other.source)} · ${other.account.orEmpty()} · ${other.bankFlow.orEmpty()}")
                TextButton(onClick = {
                    viewModel.linkDuplicate(tx.id, other.id) { saved ->
                        if (saved) { duplicateReviewId = null; viewModel.clearSuggestions() }
                    }
                }) { Text("确认是同一笔") }
            }
        } },
        dismissButton = { if (tx.reviewReason != null) TextButton(enabled = !busy && !suggestionsLoading, onClick = {
            viewModel.confirmIndependent(tx.id) { saved -> if (saved) { duplicateReviewId = null; viewModel.clearSuggestions() } }
        }) { Text("确认是独立流水") } },
        confirmButton = { TextButton(onClick = { duplicateReviewId = null; viewModel.clearSuggestions() }) { Text("关闭") } },
    ) }

    if (showAddDialog) {
        AddTransactionDialog(
            accounts = accounts,
            errorMessage = formError,
            defaultCategory = recentCategory, defaultAccountId = defaultAccountId, onDefaultAccount = viewModel::setDefaultAccount,
            onDismiss = { showAddDialog = false; formError = null },
            onConfirm = { amount, type, category, merchant, note, accountId, at, requestId ->
                formError = null
                viewModel.addTransaction(amount, type, category, merchant, note, accountId, at, requestId) { saved ->
                    if (saved) showAddDialog = false else formError = "保存失败，请核对账户和金额后重试。"
                }
            }
        )
    }
    if (showTransferDialog) TransferDialog(accounts, onDismiss = { showTransferDialog = false }, busy = busy, error = formError) {
        fromId, toId, amount, fee, note, at, requestId ->
        formError = null
        viewModel.createTransfer(fromId, toId, amount, fee, note, at, requestId) { saved ->
            if (saved) showTransferDialog = false else formError = "保存失败，请检查账户和金额。"
        }
    }
    returnReview?.let { original -> ReturnDialog(original,
        remainingCents = viewModel.remainingReturn(original), busy = busy, error = formError,
        candidates = viewModel.returnCandidates(original), accounts = accounts,
        onDismiss = { returnReviewId = null },
        onRecord = { kind, cents, accountId, note, at, requestId ->
            formError = null
            viewModel.recordReturn(original.id, kind, cents, accountId, note, at, requestId) { saved ->
                if (saved) returnReviewId = null else formError = "保存失败，请检查原支出与已退金额。"
            }
        },
        onLink = { returnId, kind ->
            viewModel.linkExistingReturn(original.id, returnId, kind) { saved ->
                if (saved) returnReviewId = null else formError = "关联失败，请重新核对。"
            }
        }) }
    editing?.let { transaction ->
        AddTransactionDialog(
            accounts = accounts,
            transaction = transaction,
            errorMessage = editError,
            onDismiss = { editingId = null; editError = null },
            onConfirm = { amount, type, category, merchant, note, accountId, at, _ ->
                editError = null
                viewModel.editTransaction(transaction.id, amount, type, category, merchant, note, accountId, at) { saved ->
                    if (saved) { editingId = null; editError = null }
                    else editError = "保存失败，请核对账户与账单方向。"
                }
            },
        )
    }
    if (showFilters) TransactionFilterDialog(filters, accounts, onDismiss = { showFilters = false }) {
        viewModel.setFilters(it); showFilters = false
    }
    if (showTrash) AlertDialog(onDismissRequest = { showTrash = false }, title = { Text("已删除流水") },
        text = { LazyColumn(Modifier.heightIn(max = 440.dp)) {
            if (deleted.isEmpty()) item { Text("回收站为空") }
            items(deleted, key = { it.id }) { tx -> Column {
                Text("${ledgerDate(tx.timestamp)} · ${tx.merchant} · ¥${formatYuan(tx.amountCents)}")
                TextButton(onClick = { viewModel.restore(tx.id) }, enabled = !busy) { Text("恢复") }
            } }
        } }, confirmButton = { TextButton(onClick = { showTrash = false }) { Text("关闭") } })
    reviewing?.let { transaction ->
        val available = accounts.filter { eligibleBalanceAccount(it, transaction) }
        val delta = balanceDeltaCents(transaction) ?: 0L
        AlertDialog(
            onDismissRequest = { reviewingId = null },
            title = { Text("核对余额账户") },
            text = {
                Column(modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("这笔交易对所选账户 ${if (delta < 0) "−" else "+"}¥${BigDecimal.valueOf(delta, 2).abs().toPlainString()}")
                    if (transaction.source == "ALIPAY" || transaction.source == "WECHAT") {
                        Text("若这笔钱已包含在手动填写的当前余额中，请选“暂不计入”，避免重复加减。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (available.isEmpty()) Text("没有可用于这笔交易的账户。请先在资产页填写当前余额。")
                    available.forEach { account ->
                        TextButton(onClick = {
                            viewModel.setBalanceAccount(transaction, account.id) { if (it) reviewingId = null }
                        }) { Text(account.name) }
                    }
                    TextButton(onClick = {
                        viewModel.setBalanceAccount(transaction, null) { if (it) reviewingId = null }
                    }) { Text("稍后核对") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setBalanceAccount(transaction, null, ignore = true) { if (it) reviewingId = null }
                }) { Text("暂不计入") }
            },
            dismissButton = { TextButton(onClick = { reviewingId = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun MonthlySummaryCard(
    monthlyExpenseCents: Long,
    monthlyIncomeCents: Long,
) {
    val balanceCents = monthlyIncomeCents - monthlyExpenseCents
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "所选月份 · 净结余",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
            text = "${if (balanceCents >= 0) "+" else "−"}¥${formatYuan(kotlin.math.abs(balanceCents))}",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                SummaryStat(
                    label = "收入",
                    amount = "+¥${formatYuan(monthlyIncomeCents)}",
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f)
                )
                SummaryStat(
                    label = "支出",
                    amount = "−¥${formatYuan(monthlyExpenseCents)}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SummaryStat(
    label: String,
    amount: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Text(
            text = amount,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

@Composable
private fun CategoryFilterRow(
    selected: TransactionCategory?,
    onSelect: (TransactionCategory?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text("全部") }
        )
        TransactionCategory.entries.forEach { cat ->
            FilterChip(
                selected = selected == cat,
                onClick = { onSelect(if (selected == cat) null else cat) },
                label = { Text(cat.displayName) }
            )
        }
    }
}

@Composable
private fun GroupedTransactionList(
    transactions: List<TransactionEntity>,
    allTransactions: List<TransactionEntity>,
    onDelete: (TransactionEntity) -> Unit,
    accounts: List<AccountEntity>,
    onReview: (TransactionEntity) -> Unit,
    onEdit: (TransactionEntity) -> Unit,
    onDuplicateReview: (TransactionEntity) -> Unit,
    onUnlinkDuplicate: (TransactionEntity) -> Unit,
    onReturnReview: (TransactionEntity) -> Unit,
    onUnlinkReturn: (TransactionEntity) -> Unit,
    onMarkOrdinaryIncome: (TransactionEntity) -> Unit,
) {
    // 按 dayKey 分组（同一天 = 同一组）。LinkedHashMap 保留插入顺序（即时间倒序）
    val groups = remember(transactions) {
        transactions.groupBy { dayKey(it.timestamp) }
    }
    val refunds = remember(allTransactions) {
        allTransactions.asSequence().filter { it.deletedAt == null && it.duplicateOfId == null && it.reviewReason == null &&
            it.relationKind in setOf("REFUND", "REIMBURSEMENT") && it.type == TransactionType.INCOME.name }
            .groupBy { it.relatedTransactionId }.mapValues { (_, rows) -> rows.sumOf { it.amountCents } }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        groups.forEach { (dayKey, dayItems) ->
            // 每组的头部：日期 + 当日合计
            item(key = "header_$dayKey") {
                DayHeader(dayKey = dayKey, items = dayItems, refunds = refunds)
            }
            // 该组内的交易项
            items(dayItems, key = { it.id }) { tx ->
                TransactionItem(transaction = tx, onDelete = { onDelete(tx) }, accounts = accounts,
                    onReview = { onReview(tx) }, onEdit = { onEdit(tx) },
                    onDuplicateReview = { onDuplicateReview(tx) },
                    onUnlinkDuplicate = { onUnlinkDuplicate(tx) },
                    onReturnReview = { onReturnReview(tx) },
                    onUnlinkReturn = { onUnlinkReturn(tx) },
                    onMarkOrdinaryIncome = { onMarkOrdinaryIncome(tx) })
            }
        }
    }
}

@Composable
private fun DayHeader(dayKey: String, items: List<TransactionEntity>, refunds: Map<Long?, Long>) {
    val expense = dailyExpenseCents(items, refunds)
    val income = items.filter { it.duplicateOfId == null && it.reviewReason == null && it.relationKind !in
        setOf("REFUND", "REIMBURSEMENT", "REFUND_PENDING", "REIMBURSEMENT_PENDING") &&
        it.type == TransactionType.INCOME.name }.sumOf { it.amountCents }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = dayLabel(dayKey),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (income > 0) {
            Text(
                text = "+¥${formatYuan(income)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(end = 12.dp)
            )
        }
        if (expense > 0) {
            Text(
                text = "−¥${formatYuan(expense)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

internal fun dailyExpenseCents(items: List<TransactionEntity>, refunds: Map<Long?, Long>): Long =
    items.filter { it.deletedAt == null && it.duplicateOfId == null && it.reviewReason == null &&
        it.type == TransactionType.EXPENSE.name }
        .sumOf { (it.amountCents - (refunds[it.id] ?: 0L)).coerceAtLeast(0L) }

@Composable
private fun TransactionItem(
    transaction: TransactionEntity,
    onDelete: () -> Unit,
    accounts: List<AccountEntity>,
    onReview: () -> Unit,
    onEdit: () -> Unit,
    onDuplicateReview: () -> Unit,
    onUnlinkDuplicate: () -> Unit,
    onReturnReview: () -> Unit,
    onUnlinkReturn: () -> Unit,
    onMarkOrdinaryIncome: () -> Unit,
) {
    val type = runCatching { TransactionType.valueOf(transaction.type) }.getOrDefault(TransactionType.EXPENSE)
    val category = runCatching { TransactionCategory.valueOf(transaction.category) }.getOrDefault(TransactionCategory.OTHER)
    val amount = formatYuan(transaction.amountCents)
    val route = transferRoute(transaction)
    val title = if (route == null) transaction.merchant.ifBlank { category.displayName } else when {
        transaction.note.contains("基金赎回") && transaction.bankFlow == "IN" -> "基金 → ${transaction.account ?: "银行卡"}"
        transaction.bankFlow == "IN" -> "${transaction.merchant.ifBlank { "转出账户" }} → ${transaction.account ?: "银行卡"}"
        else -> "${transaction.account ?: "银行卡"} → ${transaction.merchant.ifBlank { "转入账户" }}"
    }
    val transferSign = when (transaction.bankFlow) { "IN" -> "+"; "OUT" -> "−"; else -> "转 " }
    val finance = LocalFinancePalette.current
    var expanded by rememberSaveable(transaction.id) { mutableStateOf(false) }
    val sign = when (type) {
        TransactionType.INCOME -> "+"
        TransactionType.EXPENSE -> "−"
        TransactionType.TRANSFER -> ""
    }
    val amountColor = when {
        transaction.relationKind in setOf("REFUND", "REIMBURSEMENT") -> finance.refund
        transaction.relationKind in setOf("REFUND_PENDING", "REIMBURSEMENT_PENDING") -> finance.warning
        type == TransactionType.INCOME -> finance.income
        type == TransactionType.EXPENSE -> finance.expense
        else -> finance.transfer
    }
    val linked = accounts.firstOrNull { it.id == transaction.balanceAccountId }
    val status = when {
        transaction.reviewReason != null -> "流水待核对 · 暂不统计"
        transaction.duplicateOfId != null -> "辅助流水 · 不重复统计"
        transaction.relationKind in setOf("REFUND_PENDING", "REIMBURSEMENT_PENDING") -> "退款/报销待核对"
        transaction.relationKind in setOf("REFUND", "REIMBURSEMENT") -> "已关联原支出"
        canLinkBalance(transaction) && linked == null && !transaction.balanceIgnored -> "余额待核对"
        else -> null
    }

    Card(modifier = Modifier.fillMaxWidth(), onClick = { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center) {
                    Text(category.displayName.take(1), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title,
                        style = MaterialTheme.typography.titleMedium, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(category.displayName.takeIf { route == null && transaction.merchant.isNotBlank() },
                        linked?.name ?: transaction.account, ledgerDate(transaction.timestamp, "HH:mm"))
                        .joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                Text(if (route != null) "$transferSign¥$amount" else "$sign¥$amount",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    color = amountColor, textAlign = TextAlign.End, maxLines = 2)
            }
            if (status != null) Text(status, style = MaterialTheme.typography.labelSmall,
                color = if (status.contains("待核对")) finance.warning else MaterialTheme.colorScheme.onSurfaceVariant)
            if (expanded) {
                if (route != null) Text(route, style = MaterialTheme.typography.bodySmall,
                    color = finance.transfer)
                transaction.reviewReason?.let { Text(it, color = finance.warning) }
                if (transaction.note.isNotBlank()) Text(transaction.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (transaction.duplicateOfId != null) {
                    TextButton(onClick = onUnlinkDuplicate) { Text("取消重复关联") }
                } else {
                    TextButton(onClick = onDuplicateReview) { Text(if (transaction.reviewReason != null) "核对这笔流水" else "核对其他账单是否重复") }
                }
                if (transaction.relationKind in setOf("REFUND", "REIMBURSEMENT")) {
                    TextButton(onClick = onUnlinkReturn) { Text("取消退款/报销关联") }
                } else if (transaction.relationKind in setOf("REFUND_PENDING", "REIMBURSEMENT_PENDING")) {
                    TextButton(onClick = onMarkOrdinaryIncome) { Text("这其实是普通收入") }
                } else if (type == TransactionType.EXPENSE && transaction.duplicateOfId == null) {
                    TextButton(onClick = onReturnReview) { Text("关联退款/报销") }
                }
                if (canLinkBalance(transaction)) {
                    when {
                        linked != null && transaction.timestamp <= linked.updatedAt ->
                            Text("已包含在新设定的余额中", style = MaterialTheme.typography.bodySmall)
                        transaction.balanceIgnored -> TextButton(onClick = onReview) { Text("已标记不计入 · 更改") }
                        linked == null && accounts.isNotEmpty() &&
                            accounts.none { eligibleBalanceAccount(it, transaction) } ->
                            Text("没有可匹配的余额账户，或交易早于余额起点", style = MaterialTheme.typography.bodySmall)
                        else -> TextButton(onClick = onReview) {
                            Text(if (linked == null) "核对余额账户" else "已计入 ${linked.name} · 更改")
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(sourceLabel(transaction.source), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "编辑", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(filterActive: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (filterActive) "该分类下还没有记录" else "还没有账单",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!filterActive) {
                Text(
                    text = "点击右下角 + 添加第一笔",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// === 时间分组辅助 ===

private fun dayKey(timestampMs: Long): String = ledgerDate(timestampMs, "yyyy-MM-dd")
private fun dayLabel(dayKey: String): String = when (dayKey) {
    ledgerToday().toString() -> "今天"
    ledgerToday().minusDays(1).toString() -> "昨天"
    else -> dayKey
}
