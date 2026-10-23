package com.bookkeeping.app.ui.screen.stats

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.remember
import com.bookkeeping.app.ui.components.*
import com.bookkeeping.app.ui.screen.transactions.TransactionViewModel
import com.bookkeeping.app.ui.screen.transactions.AddTransactionDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.hilt.navigation.compose.hiltViewModel
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.ui.theme.sourceLabel
import com.bookkeeping.app.ui.theme.formatYuan
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(viewModel: StatsViewModel = hiltViewModel(), ledger: TransactionViewModel = hiltViewModel()) {
    val originals by ledger.allTransactions.collectAsState()
    val accounts by ledger.accountList.collectAsState()
    var detailId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editError by remember { mutableStateOf<String?>(null) }
    var showCharts by rememberSaveable { mutableStateOf(false) }
    val stats by viewModel.categoryStats.collectAsState()
    val totalCents by viewModel.totalCents.collectAsState()
    val viewingType by viewModel.viewingType.collectAsState()
    val dailyTotals by viewModel.dailyTotals.collectAsState()
    val monthlyTransactions by viewModel.monthlyTransactions.collectAsState()
    var selectedCategoryName by rememberSaveable { mutableStateOf<String?>(null) }
    var sortAscending by rememberSaveable { mutableStateOf(false) }
    val selectedCategory = selectedCategoryName?.let { TransactionCategory.valueOf(it) }
    BackHandler(enabled = selectedCategory != null) { selectedCategoryName = null }

    Scaffold(topBar = { TopAppBar(
        title = { Text(selectedCategory?.displayName ?: "统计", style = MaterialTheme.typography.headlineSmall) },
        navigationIcon = {
            if (selectedCategory != null) IconButton(onClick = { selectedCategoryName = null }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回统计")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    ) }) { innerPadding ->
        if (selectedCategory != null) {
            CategoryDetails(
                category = selectedCategory,
                viewingType = viewingType,
                transactions = categoryTransactions(monthlyTransactions, viewingType, selectedCategory),
                modifier = Modifier.padding(innerPadding),
                onOpen = { detailId = it.id },
            )
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            MonthSelector(viewModel.period)
            // 收入/支出 切换
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = viewingType == TransactionType.EXPENSE,
                    onClick = { viewModel.setViewingType(TransactionType.EXPENSE) },
                    label = { Text("支出") },
                )
                FilterChip(
                    selected = viewingType == TransactionType.INCOME,
                    onClick = { viewModel.setViewingType(TransactionType.INCOME) },
                    label = { Text("收入") },
                )
            }

            Text("按净消费统计：退款和报销抵减原支出月份，转账与待核对流水不计入。",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            // 总金额卡片
            TotalCard(totalCents = totalCents, type = viewingType)

            if (stats.isEmpty()) {
                EmptyStats(type = viewingType)
            } else {
                val sortedStats = if (sortAscending) stats.sortedBy { it.totalCents }
                    else stats.sortedByDescending { it.totalCents }
                val barColor = if (viewingType == TransactionType.INCOME)
                    MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // 分类明细列表
                    item(key = "sort_controls") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("分类排行", modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall)
                            FilterChip(
                                selected = !sortAscending,
                                onClick = { sortAscending = false },
                                label = { Text("金额降序") },
                            )
                            FilterChip(
                                selected = sortAscending,
                                onClick = { sortAscending = true },
                                label = { Text("金额升序") },
                            )
                        }
                    }
                    items(sortedStats, key = { it.category.name }) { stat ->
                        CategoryStatRow(stat = stat, viewingType = viewingType,
                            onClick = { selectedCategoryName = stat.category.name })
                    }
                    item { TextButton(onClick = { showCharts = !showCharts }) { Text(if (showCharts) "收起图表" else "查看图表与每日数据") } }
                    if (showCharts) {
                    // 饼图
                    item(key = "pie_chart") {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text(
                                    text = "分类占比",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                CategoryPieChart(
                                    stats = stats,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                )
                            }
                        }
                    }
                    // 日趋势柱状图
                    item(key = "daily_chart") {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text(
                                    text = "每日趋势",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                DailyBarChart(
                                    dailyTotals = dailyTotals,
                                    barColor = barColor,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                )
                            }
                        }
                    }
                        items(dailyTotals.filter { it.totalCents > 0 }, key = { "day_${it.day}" }) { day ->
                            Text("${day.day}日 · ¥${formatYuan(day.totalCents)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    detailId?.let { id -> originals.firstOrNull { it.id == id }?.let { original ->
        val net = monthlyTransactions.firstOrNull { it.id == id }?.amountCents ?: original.amountCents
        AlertDialog(onDismissRequest = { detailId = null }, title = { Text(original.merchant.ifBlank { "交易详情" }) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("原始金额 ¥${formatYuan(original.amountCents)}")
                if (original.type == "EXPENSE") Text("抵减退款与报销后 ¥${formatYuan(net)}")
                Text(ledgerDate(original.timestamp))
                Text(accounts.firstOrNull { it.id == original.balanceAccountId }?.name ?: original.account.orEmpty())
                Text("来源 ${sourceLabel(original.source)}")
                Text(original.note)
            } }, confirmButton = { TextButton(onClick = { editId = id; detailId = null; editError = null }) { Text("编辑原始流水") } },
            dismissButton = { TextButton(onClick = { detailId = null }) { Text("关闭") } })
    } }
    editId?.let { id -> originals.firstOrNull { it.id == id }?.let { original ->
        AddTransactionDialog(accounts, original, errorMessage = editError, onDismiss = { editId = null },
            onConfirm = { amount, type, category, merchant, note, accountId, at, _ ->
                editError = null
                ledger.editTransaction(id, amount, type, category, merchant, note, accountId, at) {
                    if (it) editId = null else editError = "保存失败，请核对退款额度和账户。"
                }
            })
    } }

}

@Composable
private fun TotalCard(totalCents: Long, type: TransactionType) {
    val sign = if (type == TransactionType.INCOME) "+" else "−"
    val label = if (type == TransactionType.INCOME) "所选月份总收入" else "所选月份净支出"
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
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = "$sign¥${formatYuan(totalCents)}",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun CategoryStatRow(stat: CategoryStat, viewingType: TransactionType, onClick: () -> Unit) {
    val amountColor = if (viewingType == TransactionType.INCOME)
        MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stat.category.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${"%.1f".format(stat.percentage * 100)}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = "¥${formatYuan(stat.totalCents)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = amountColor,
                )
                Icon(Icons.Default.ChevronRight, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LinearProgressIndicator(
                    progress = { stat.percentage.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp),
                    color = amountColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    drawStopIndicator = {},
                )
                Text(
                    text = "${stat.count} 笔",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        HorizontalDivider(modifier = Modifier.padding(top = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun CategoryDetails(
    category: TransactionCategory,
    viewingType: TransactionType,
    transactions: List<TransactionEntity>,
    modifier: Modifier = Modifier,
    onOpen: (TransactionEntity) -> Unit,
) {
    val totalCents = transactions.sumOf { it.amountCents }
    val sign = if (viewingType == TransactionType.INCOME) "+" else "−"
    val typeLabel = if (viewingType == TransactionType.INCOME) "收入" else "支出"
    val amountColor = if (viewingType == TransactionType.INCOME)
        MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "summary") {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text("所选月份$typeLabel · ${category.displayName} · ${transactions.size} 笔",
                        style = MaterialTheme.typography.labelLarge)
                    Text("$sign¥${formatYuan(totalCents)}",
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                        color = amountColor)
                }
            }
        }
        items(transactions, key = { it.id }) { transaction ->
            Card(modifier = Modifier.fillMaxWidth(), onClick = { onOpen(transaction) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(transaction.merchant.ifBlank { "未填写商户" },
                            modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text("$sign¥${formatYuan(transaction.amountCents)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold, color = amountColor)
                    }
                    Text(ledgerDate(transaction.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (transaction.note.isNotBlank()) Text(transaction.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}


@Composable
private fun EmptyStats(type: TransactionType) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = if (type == TransactionType.INCOME) "所选月份没有收入" else "所选月份没有支出",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
