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
import com.bookkeeping.app.ui.theme.formatYuan
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(viewModel: StatsViewModel = hiltViewModel()) {
    val stats by viewModel.categoryStats.collectAsState()
    val totalCents by viewModel.totalCents.collectAsState()
    val viewingType by viewModel.viewingType.collectAsState()
    val dailyTotals by viewModel.dailyTotals.collectAsState()
    val monthlyTransactions by viewModel.monthlyTransactions.collectAsState()
    var selectedCategoryName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedCategory = selectedCategoryName?.let { TransactionCategory.valueOf(it) }
    BackHandler(enabled = selectedCategory != null) { selectedCategoryName = null }

    Scaffold(topBar = { TopAppBar(
        title = { Text(selectedCategory?.displayName ?: "本月统计", style = MaterialTheme.typography.headlineSmall) },
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
            )
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
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

            // 总金额卡片
            TotalCard(totalCents = totalCents, type = viewingType)

            if (stats.isEmpty()) {
                EmptyStats(type = viewingType)
            } else {
                val barColor = if (viewingType == TransactionType.INCOME)
                    MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
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
                    // 分类明细列表
                    items(stats, key = { it.category.name }) { stat ->
                        CategoryStatRow(stat = stat, viewingType = viewingType,
                            onClick = { selectedCategoryName = stat.category.name })
                    }
                }
            }
        }
    }
}

@Composable
private fun TotalCard(totalCents: Long, type: TransactionType) {
    val sign = if (type == TransactionType.INCOME) "+" else "−"
    val label = if (type == TransactionType.INCOME) "本月总收入" else "本月总支出"
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
                    Text("本月$typeLabel · ${category.displayName} · ${transactions.size} 笔",
                        style = MaterialTheme.typography.labelLarge)
                    Text("$sign¥${formatYuan(totalCents)}",
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                        color = amountColor)
                }
            }
        }
        items(transactions, key = { it.id }) { transaction ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(transaction.merchant.ifBlank { "未填写商户" },
                            modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text("$sign¥${formatYuan(transaction.amountCents)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold, color = amountColor)
                    }
                    Text(detailDateFormat.format(Date(transaction.timestamp)),
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

private val detailDateFormat = SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.getDefault())

@Composable
private fun EmptyStats(type: TransactionType) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = if (type == TransactionType.INCOME) "本月还没有收入" else "本月还没有支出",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
