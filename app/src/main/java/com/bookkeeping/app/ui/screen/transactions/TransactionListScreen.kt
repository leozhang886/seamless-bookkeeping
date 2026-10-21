package com.bookkeeping.app.ui.screen.transactions

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import java.text.SimpleDateFormat
import java.math.BigDecimal
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionListScreen(
    viewModel: TransactionViewModel = hiltViewModel()
) {
    val transactions by viewModel.displayedTransactions.collectAsState()
    val accounts by viewModel.accountList.collectAsState()
    val monthlyExpense by viewModel.monthlyExpenseCents.collectAsState()
    val monthlyIncome by viewModel.monthlyIncomeCents.collectAsState()
    val selectedCategory by viewModel.categoryFilter.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showAddDialog by remember { mutableStateOf(false) }
    var showTransferDialog by remember { mutableStateOf(false) }
    var reviewing by remember { mutableStateOf<TransactionEntity?>(null) }
    var editing by remember { mutableStateOf<TransactionEntity?>(null) }
    var editError by remember { mutableStateOf<String?>(null) }
    var duplicateReview by remember { mutableStateOf<TransactionEntity?>(null) }
    var returnReview by remember { mutableStateOf<TransactionEntity?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = { TopAppBar(
            title = { Text("账单", style = MaterialTheme.typography.headlineSmall) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        ) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true },
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
            // 顶部统计卡片（基于全月数据，不受 filter 影响）
            MonthlySummaryCard(
                monthlyExpenseCents = monthlyExpense,
                monthlyIncomeCents = monthlyIncome,
            )

            // 分类筛选 chip 行
            CategoryFilterRow(
                selected = selectedCategory,
                onSelect = viewModel::setCategoryFilter,
            )
            TextButton(onClick = { showTransferDialog = true }, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("账户转账")
            }

            if (transactions.isEmpty()) {
                EmptyState(filterActive = selectedCategory != null)
            } else {
                // 按日期分组渲染
                GroupedTransactionList(
                    transactions = transactions,
                    onDelete = { tx -> viewModel.delete(tx) {
                        scope.launch {
                            if (snackbarHost.showSnackbar("已删除流水", "撤销") == SnackbarResult.ActionPerformed)
                                viewModel.restore(tx.id)
                        }
                    } },
                    accounts = accounts,
                    onReview = { reviewing = it },
                    onEdit = { editing = it; editError = null },
                    onDuplicateReview = { duplicateReview = it; viewModel.findDuplicates(it.id) },
                    onUnlinkDuplicate = { viewModel.unlinkDuplicate(it.id) },
                    onReturnReview = { returnReview = it },
                    onUnlinkReturn = { viewModel.unlinkReturn(it.id) },
                    onMarkOrdinaryIncome = { viewModel.markOrdinaryIncome(it.id) },
                )
            }
        }
    }
    duplicateReview?.let { tx -> AlertDialog(
        onDismissRequest = { duplicateReview = null; viewModel.clearSuggestions() },
        title = { Text("核对是否同一消费") },
        text = { Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("当前：${tx.merchant} · ¥${BigDecimal.valueOf(tx.amountCents, 2).toPlainString()} · ${tx.source}")
            Text("以下只是相似流水，必须对照原账单、商户和付款方式。")
            if (suggestions.isEmpty()) Text("没有找到相近的其他来源流水。")
            suggestions.forEach { suggestion ->
                val other = suggestion.other
                Text("相似度 ${suggestion.score}% · ${other.merchant} · ${other.source}")
                TextButton(onClick = {
                    viewModel.linkDuplicate(tx.id, other.id)
                    duplicateReview = null; viewModel.clearSuggestions()
                }) { Text("确认是同一笔") }
            }
        } },
        confirmButton = { TextButton(onClick = { duplicateReview = null; viewModel.clearSuggestions() }) { Text("关闭") } },
    ) }

    if (showAddDialog) {
        AddTransactionDialog(
            accounts = accounts,
            onDismiss = { showAddDialog = false },
            onConfirm = { amount, type, category, merchant, note, accountId ->
                viewModel.addTransaction(amount, type, category, merchant, note, accountId)
                showAddDialog = false
            }
        )
    }
    if (showTransferDialog) TransferDialog(accounts, onDismiss = { showTransferDialog = false }) {
        fromId, toId, amount, fee, note ->
        viewModel.createTransfer(fromId, toId, amount, fee, note)
        showTransferDialog = false
    }
    returnReview?.let { original -> ReturnDialog(original,
        remainingCents = viewModel.remainingReturn(original),
        candidates = viewModel.returnCandidates(original), accounts = accounts,
        onDismiss = { returnReview = null },
        onRecord = { kind, cents, accountId, note ->
            viewModel.recordReturn(original.id, kind, cents, accountId, note)
            returnReview = null
        },
        onLink = { returnId, kind ->
            viewModel.linkExistingReturn(original.id, returnId, kind)
            returnReview = null
        }) }
    editing?.let { transaction ->
        AddTransactionDialog(
            accounts = accounts,
            transaction = transaction,
            errorMessage = editError,
            onDismiss = { editing = null; editError = null },
            onConfirm = { amount, type, category, merchant, note, accountId ->
                viewModel.editTransaction(transaction.id, amount, type, category, merchant, note, accountId) { saved ->
                    if (saved) { editing = null; editError = null }
                    else editError = "保存失败，请核对账户与账单方向。"
                }
            },
        )
    }
    reviewing?.let { transaction ->
        val available = accounts.filter { eligibleBalanceAccount(it, transaction) }
        val delta = balanceDeltaCents(transaction) ?: 0L
        AlertDialog(
            onDismissRequest = { reviewing = null },
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
                            viewModel.setBalanceAccount(transaction, account.id)
                            reviewing = null
                        }) { Text(account.name) }
                    }
                    TextButton(onClick = {
                        viewModel.setBalanceAccount(transaction, null)
                        reviewing = null
                    }) { Text("稍后核对") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setBalanceAccount(transaction, null, ignore = true)
                    reviewing = null
                }) { Text("暂不计入") }
            },
            dismissButton = { TextButton(onClick = { reviewing = null }) { Text("取消") } },
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
                text = "本月结余",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = "${if (balanceCents >= 0) "+" else "−"}¥${BigDecimal.valueOf(balanceCents, 2).abs().toPlainString()}",
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
                    amount = "+¥${BigDecimal.valueOf(monthlyIncomeCents, 2).toPlainString()}",
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f)
                )
                SummaryStat(
                    label = "支出",
                    amount = "−¥${BigDecimal.valueOf(monthlyExpenseCents, 2).toPlainString()}",
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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        groups.forEach { (dayKey, dayItems) ->
            // 每组的头部：日期 + 当日合计
            item(key = "header_$dayKey") {
                DayHeader(dayKey = dayKey, items = dayItems)
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
private fun DayHeader(dayKey: String, items: List<TransactionEntity>) {
    val expense = items.filter { it.duplicateOfId == null && it.type == TransactionType.EXPENSE.name }.sumOf { it.amountCents }
    val income = items.filter { it.duplicateOfId == null && it.relationKind !in
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
                text = "+¥${BigDecimal.valueOf(income, 2).toPlainString()}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(end = 12.dp)
            )
        }
        if (expense > 0) {
            Text(
                text = "−¥${BigDecimal.valueOf(expense, 2).toPlainString()}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

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
    val amount = BigDecimal.valueOf(transaction.amountCents, 2).toPlainString()
    val route = transferRoute(transaction)
    val sign = when (type) {
        TransactionType.INCOME -> "+"
        TransactionType.EXPENSE -> "−"
        TransactionType.TRANSFER -> ""
    }
    val amountColor = when (type) {
        TransactionType.INCOME -> MaterialTheme.colorScheme.tertiary
        TransactionType.EXPENSE -> MaterialTheme.colorScheme.error
        TransactionType.TRANSFER -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = category.displayName +
                        if (transaction.merchant.isNotBlank()) " · ${transaction.merchant}" else "",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (route == null) Text(
                    text = if (type == TransactionType.TRANSFER) "转 ¥$amount" else "$sign¥$amount",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = amountColor,
                )
            }
            if (route != null) Text(route, style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            if (transaction.note.isNotBlank()) Text(transaction.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (transaction.duplicateOfId != null) {
                TextButton(onClick = onUnlinkDuplicate) { Text("同一消费的辅助流水 · 不重复统计（取消关联）") }
            } else if (transaction.importKey != null && transaction.type != TransactionType.TRANSFER.name) {
                TextButton(onClick = onDuplicateReview) { Text("核对其他账单是否重复") }
            }
            if (transaction.relationKind in setOf("REFUND", "REIMBURSEMENT")) {
                TextButton(onClick = onUnlinkReturn) { Text("已关联原支出 · 不算普通收入（取消关联）") }
            } else if (transaction.relationKind in setOf("REFUND_PENDING", "REIMBURSEMENT_PENDING")) {
                Text("退款/报销待关联原支出 · 暂不计普通收入", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onMarkOrdinaryIncome) { Text("这其实是普通收入") }
            } else if (transaction.type == TransactionType.EXPENSE.name && transaction.duplicateOfId == null) {
                TextButton(onClick = onReturnReview) { Text("关联退款/报销") }
            }
            if (canLinkBalance(transaction)) {
                val linked = accounts.firstOrNull { it.id == transaction.balanceAccountId }
                if (linked != null && transaction.timestamp <= linked.updatedAt) {
                    Text("已包含在新设定的余额中", style = MaterialTheme.typography.labelSmall)
                } else if (transaction.balanceIgnored) {
                    TextButton(onClick = onReview) { Text("已标记不计入（点此更改）") }
                } else if (linked == null && accounts.isNotEmpty() &&
                    accounts.none { eligibleBalanceAccount(it, transaction) }) {
                    Text("没有可匹配的余额账户，或交易早于余额起点", style = MaterialTheme.typography.labelSmall)
                } else {
                    TextButton(onClick = onReview) {
                        Text(if (linked == null) "余额待核对" else "已计入余额 · ${linked.name}（点此更改）")
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(timeOnlyFormat.format(Date(transaction.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑", tint = MaterialTheme.colorScheme.outline)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.outline)
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

private val dayKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
private val timeOnlyFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private val monthDayFormat = SimpleDateFormat("MM月dd日", Locale.getDefault())

// 把毫秒时间戳转成 "yyyy-MM-dd" 字符串作为分组 key
private fun dayKey(timestampMs: Long): String = dayKeyFormat.format(Date(timestampMs))

// 把 dayKey 转成展示用的标签："今天" / "昨天" / "MM月dd日"
private fun dayLabel(dayKey: String): String {
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val todayKey = dayKeyFormat.format(today.time)
    val yesterdayKey = dayKeyFormat.format(Date(today.timeInMillis - 86_400_000L))
    return when (dayKey) {
        todayKey -> "今天"
        yesterdayKey -> "昨天"
        else -> {
            // 解析回 Date，然后格式化为 MM月dd日
            runCatching { monthDayFormat.format(dayKeyFormat.parse(dayKey)!!) }.getOrDefault(dayKey)
        }
    }
}
