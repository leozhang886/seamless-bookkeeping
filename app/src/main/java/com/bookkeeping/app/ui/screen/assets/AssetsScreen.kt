package com.bookkeeping.app.ui.screen.assets

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bookkeeping.app.ui.theme.LocalFinancePalette
import com.bookkeeping.app.ui.theme.formatYuan
import com.bookkeeping.app.data.local.entity.AccountEntity
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionType
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetsScreen(onQuickAdd: () -> Unit = {}, onViewBills: () -> Unit = {},
                 viewModel: AssetsViewModel = hiltViewModel()) {
    val archived by viewModel.archivedAccounts.collectAsState()
    val batches by viewModel.importBatches.collectAsState()
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var batchToUndo by rememberSaveable { mutableStateOf<Long?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }
    var renameId by rememberSaveable { mutableStateOf<Long?>(null) }
    val accounts by viewModel.accountList.collectAsState()
    val transactions by viewModel.transactionList.collectAsState()
    val message by viewModel.message.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val preview by viewModel.preview.collectAsState()
    val checkpoints by viewModel.checkpoints.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var addAccount by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val editing = accounts.firstOrNull { it.id == editingId }
    val deleting = accounts.firstOrNull { it.id == deletingId }
    var reconcilingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var historyId by rememberSaveable { mutableStateOf<Long?>(null) }
    var amountsHidden by rememberSaveable { mutableStateOf(false) }
    var showBalanceHelp by rememberSaveable { mutableStateOf(false) }
    val finance = LocalFinancePalette.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: uri.lastPathSegment.orEmpty()
            viewModel.previewBill(uri, name)
        }
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbarHost) }, topBar = {
        TopAppBar(
            title = { Text("我的资产", style = MaterialTheme.typography.headlineSmall) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = finance.hero),
                ) {
                    Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("当前资产总额", style = MaterialTheme.typography.labelLarge,
                                color = finance.onHeroMuted, modifier = Modifier.weight(1f))
                            IconButton(onClick = { amountsHidden = !amountsHidden }) {
                                Icon(if (amountsHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (amountsHidden) "显示资产金额" else "隐藏资产金额",
                                    tint = finance.onHero)
                            }
                        }
                        Text(if (amountsHidden) "¥ ••••" else "¥${formatYuan(accounts.sumOf { it.balanceCents })}",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold, color = finance.onHero)
                        Text("${accounts.size} 个账户与基金 · 余额由账单和手动校准共同维护",
                            style = MaterialTheme.typography.bodySmall, color = finance.onHeroMuted)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onQuickAdd, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("记一笔", modifier = Modifier.padding(start = 4.dp))
                    }
                    OutlinedButton(onClick = { picker.launch(arrayOf("text/*", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/pdf", "application/octet-stream")) },
                        enabled = !busy, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("导入账单", modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
            if (message.isNotBlank()) item {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Text(message, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) }
            item {
                TextButton(onClick = { showHistory = true }, enabled = !busy,
                    modifier = Modifier.padding(horizontal = 16.dp)) { Text("导入记录与撤销") }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("账户与基金", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showArchived = true }) { Text("已归档") }
                    TextButton(onClick = { formError = null; addAccount = true }) { Text("添加资产") }
                }
            }
            if (accounts.isEmpty()) item {
                Text("还没有资产记录。可添加银行卡、支付宝余额、微信零钱，以及每只支付宝基金的当前市值。",
                    modifier = Modifier.padding(horizontal = 16.dp))
            }
            items(accounts, key = { it.id }) { account ->
                var moreOpen by remember(account.id) { mutableStateOf(false) }
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            val icon = when (account.kind) {
                                "BANK" -> Icons.Default.AccountBalance
                                "FUND" -> Icons.Default.Savings
                                else -> Icons.Default.AccountBalanceWallet
                            }
                            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp))
                            Text("${kindLabel(account.kind)} · ${account.name}",
                                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        }
                        Text(if (amountsHidden) "¥ ••••" else "¥${formatYuan(account.balanceCents)}",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(top = 4.dp))
                        Text("余额起点设定于 ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(account.updatedAt))}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            TextButton(onClick = { renameId = account.id; formError = null }) { Text("重命名") }
                            if (account.kind != "FUND") TextButton(onClick = { reconcilingId = account.id }) { Text("对账") }
                            TextButton(onClick = { editingId = account.id }) { Text(if (account.kind == "FUND") "更新市值" else "更新余额") }
                            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                            androidx.compose.foundation.layout.Box {
                                IconButton(onClick = { moreOpen = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "更多账户操作")
                                }
                                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                                    DropdownMenuItem(text = { Text("校准记录") }, onClick = {
                                        moreOpen = false; historyId = account.id; viewModel.loadCheckpoints(account.id)
                                    })
                                    DropdownMenuItem(text = { Text("归档账户") }, onClick = {
                                        moreOpen = false; deletingId = account.id
                                    })
                                }
                            }
                        }
                    }
                }
            }
            val recent = transactions.filter { it.duplicateOfId == null }.take(3)
            if (recent.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("最近交易", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = onViewBills) { Text("查看全部") }
                    }
                }
                items(recent, key = { "recent_${it.id}" }) { transaction ->
                    val amountColor = when (transaction.type) {
                        TransactionType.INCOME.name -> finance.income
                        TransactionType.EXPENSE.name -> finance.expense
                        else -> finance.transfer
                    }
                    val sign = when (transaction.type) {
                        TransactionType.INCOME.name -> "+"
                        TransactionType.EXPENSE.name -> "−"
                        else -> when (transaction.bankFlow) { "IN" -> "+"; "OUT" -> "−"; else -> "转 " }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(transaction.merchant.ifBlank {
                                runCatching { TransactionCategory.valueOf(transaction.category).displayName }
                                    .getOrDefault("交易")
                            },
                                style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            Text(SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(transaction.timestamp)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(if (amountsHidden) "¥ ••••" else "$sign¥${formatYuan(transaction.amountCents)}",
                            style = MaterialTheme.typography.titleMedium, color = amountColor)
                    }
                }
            }
            item {
                TextButton(onClick = { showBalanceHelp = !showBalanceHelp },
                    modifier = Modifier.padding(horizontal = 16.dp)) { Text(if (showBalanceHelp) "收起余额说明" else "余额如何更新？") }
            }
            if (showBalanceHelp) item {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("关于余额更新", style = MaterialTheme.typography.titleMedium)
                        Text("以最后一次手动填写的余额为起点。建行 PDF 按卡尾号更新银行卡；微信、支付宝账单仅在明确写出余额或零钱时更新。基金市值由你手动更新。",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("建议每周或每两周导入一次原始账单，并抽查退款、转账和重复付款。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (showHistory) AlertDialog(onDismissRequest = { showHistory = false }, title = { Text("导入记录") },
        text = { LazyColumn(Modifier.heightIn(max = 460.dp)) {
            if (batches.isEmpty()) item { Text("尚无导入记录") }
            items(batches, key = { it.id }) { batch -> Column(Modifier.padding(vertical = 8.dp)) {
                Text(batch.fileName)
                Text("${com.bookkeeping.app.ui.components.ledgerDate(batch.importedAt)} · ${batch.parsedRows} 笔 · ${if (batch.undoneAt == null) "已导入" else "已撤销"}")
                TextButton(enabled = !busy, onClick = {
                    if (batch.undoneAt == null) batchToUndo = batch.id else viewModel.restoreImport(batch.id)
                }) { Text(if (batch.undoneAt == null) "撤销此批次" else "恢复此批次") }
            } }
        } }, confirmButton = { TextButton(onClick = { showHistory = false }) { Text("关闭") } })
    batchToUndo?.let { id -> AlertDialog(onDismissRequest = { batchToUndo = null }, title = { Text("撤销这次导入？") },
        text = { Text("${batches.firstOrNull { it.id == id }?.fileName.orEmpty()}\n只撤销本批次新增的流水，同时撤回它们对余额和统计的影响。") },
        confirmButton = { TextButton(enabled = !busy, onClick = { viewModel.undoImport(id); batchToUndo = null }) { Text("确认撤销") } },
        dismissButton = { TextButton(onClick = { batchToUndo = null }) { Text("取消") } }) }
    if (showArchived) AlertDialog(onDismissRequest = { showArchived = false }, title = { Text("已归档账户") },
        text = { LazyColumn(Modifier.heightIn(max = 440.dp)) {
            if (archived.isEmpty()) item { Text("没有已归档账户") }
            items(archived, key = { it.id }) { account -> Row {
                Text(account.name, Modifier.weight(1f))
                TextButton(enabled = !busy, onClick = { viewModel.restoreAccount(account.id) }) { Text("恢复") }
            } }
        } }, confirmButton = { TextButton(onClick = { showArchived = false }) { Text("关闭") } })
    renameId?.let { id ->
        var name by rememberSaveable(id) { mutableStateOf(accounts.firstOrNull { it.id == id }?.name.orEmpty()) }
        AlertDialog(onDismissRequest = { renameId = null }, title = { Text("账户名称") }, text = { Column {
            OutlinedTextField(name, { name = it.take(40) }, label = { Text("名称，只填卡尾号") })
            formError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(enabled = !busy && name.isNotBlank(), onClick = {
            viewModel.rename(id, name) { if (it) renameId = null else formError = "修改失败" }
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = { renameId = null }) { Text("取消") } })
    }
    preview?.let { selected ->
        var previewPage by rememberSaveable(selected.fileName) { mutableStateOf(0) }
        var issuePage by rememberSaveable(selected.fileName) { mutableStateOf(0) }
        var acceptRejected by remember(selected) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { if (!busy) viewModel.dismissPreview() },
            title = { Text("核对导入 · ${selected.fileName.take(35)}") },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val report = selected.report
                    Text("来源 ${report.source} · 原始 ${report.originalRows} 行 · 可导入 ${report.records.size} 笔 · 忽略 ${report.ignoredRows} 行 · 异常 ${report.rejected.size} 行")
                    Text("已存在同单号 ${selected.assessment.exactDuplicates} 笔；疑似跨账单重复 ${selected.assessment.crossSourceCandidates} 笔，导入后请人工关联。")
                    Text("识别金额合计 ¥${money(report.amountCents)}。这只是原始金额合计，不等于收入或支出。")
                    if (report.source == "BANK_PDF") Text("PDF 只识别建行文字版固定表格。原单只含日期；与当天手动余额起点重合的流水不会自动改余额，请对照原文件核对笔数与金额。")
                    report.blockingErrors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (report.rejected.isNotEmpty()) {
                        Text("异常行：", style = MaterialTheme.typography.titleSmall)
                        report.rejected.drop(issuePage * 20).take(20).forEach { Text("第 ${it.row} 行：${it.reason}") }
                        if (report.rejected.size > 20) Row {
                            TextButton(enabled = issuePage > 0, onClick = { issuePage-- }) { Text("上一页") }
                            Text("异常 ${issuePage + 1}/${(report.rejected.size + 19) / 20}")
                            TextButton(enabled = (issuePage + 1) * 20 < report.rejected.size, onClick = { issuePage++ }) { Text("下一页") }
                        }
                        Row { Checkbox(checked = acceptRejected, onCheckedChange = { acceptRejected = it }); Text("已核对异常行，仍导入可识别的流水") }
                    }
                    Text("流水预览：", style = MaterialTheme.typography.titleSmall)
                    report.records.drop(previewPage * 20).take(20).forEach { bill ->
                        Text("${bill.sourceRow?.let { "第 $it 行 " }.orEmpty()}${com.bookkeeping.app.ui.components.ledgerDate(bill.timestamp)} · ${bill.merchant} · ${com.bookkeeping.app.ui.theme.transactionAmount(bill)} · ${bill.sourcePaymentMethod.orEmpty()}")
                    }
                    if (report.records.size > 20) Row {
                        TextButton(enabled = previewPage > 0, onClick = { previewPage-- }) { Text("上一页") }
                        Text("${previewPage + 1}/${(report.records.size + 19) / 20}页")
                        TextButton(enabled = (previewPage + 1) * 20 < report.records.size, onClick = { previewPage++ }) { Text("下一页") }
                    }
                    Text("重复单号会在提交时跳过。跨银行与支付平台的同一消费需要人工核对，不能只凭相同金额自动合并。")
                }
            },
            confirmButton = { TextButton(onClick = viewModel::confirmImport,
                enabled = !busy && selected.report.blockingErrors.isEmpty() && selected.report.records.isNotEmpty() &&
                    (selected.report.rejected.isEmpty() || acceptRejected)) {
                Text("确认导入")
            } },
            dismissButton = { TextButton(onClick = viewModel::dismissPreview, enabled = !busy) { Text("取消") } },
        )
    }
    if (addAccount) BalanceDialog(null, busy = busy, error = formError, onDismiss = { addAccount = false }) { name, kind, cents, _ ->
        formError = null
        viewModel.addAccount(name, kind, cents) { saved -> if (saved) addAccount = false else formError = "保存失败，请重试" }
    }
    editing?.let { account ->
        BalanceDialog(account, busy = busy, error = formError, onDismiss = { editingId = null }) { _, _, cents, reason ->
            formError = null
            viewModel.updateBalance(account.id, cents, reason) { saved -> if (saved) editingId = null else formError = "保存失败，请重试" }
        }
    }
    deleting?.let { account ->
        AlertDialog(onDismissRequest = { deletingId = null }, title = { Text("归档账户？") },
            text = { Text("归档后资产总额不再包含这个账户，已有交易记录仍保留。可立即撤销。") },
            confirmButton = { TextButton(onClick = {
                viewModel.deleteAccount(account.id) {
                    scope.launch {
                        if (snackbarHost.showSnackbar("账户已归档", "撤销") == SnackbarResult.ActionPerformed)
                            viewModel.restoreAccount(account.id)
                    }
                }
                deletingId = null
            }) { Text("归档") } },
            dismissButton = { TextButton(onClick = { deletingId = null }) { Text("取消") } })
    }
    historyId?.let { id -> AlertDialog(onDismissRequest = { historyId = null },
        title = { Text("余额校准记录") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            checkpoints.filter { it.accountId == id }.forEach { point ->
                Text("${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(point.occurredAt))} · ${point.reason} · ¥${money(point.balanceCents)}")
            }
        } },
        confirmButton = { TextButton(onClick = { historyId = null }) { Text("关闭") } }) }
    reconcilingId?.let { id ->
        accounts.firstOrNull { it.id == id }?.let { account ->
            ReconciliationDialog(
                account = account,
                pending = pendingForAccount(account, accounts, transactions),
                onDismiss = { reconcilingId = null },
                onLink = { transaction -> viewModel.linkPendingTransaction(transaction.id, account.id) },
                busy = busy, error = message.takeIf { it.startsWith("操作失败") },
                onCalibrate = { cents -> viewModel.updateBalance(account.id, cents, "对账后按实际余额校准") {
                    if (it) reconcilingId = null
                } },
            )
        }
    }
}

@Composable
private fun ReconciliationDialog(
    account: AccountEntity,
    pending: List<TransactionEntity>,
    onDismiss: () -> Unit,
    onLink: (TransactionEntity) -> Unit,
    busy: Boolean, error: String?, onCalibrate: (Long) -> Unit,
) {
    var confirmCalibration by rememberSaveable(account.id) { mutableStateOf(false) }
    var actualText by rememberSaveable(account.id) { mutableStateOf("") }
    val actualCents = parseMoney(actualText)
    val difference = actualCents?.let { BigDecimal.valueOf(it, 2) - BigDecimal.valueOf(account.balanceCents, 2) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("余额对账 · ${account.name}") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("账本余额 ¥${money(account.balanceCents)}")
                OutlinedTextField(
                    value = actualText,
                    onValueChange = { actualText = it },
                    label = { Text("实际余额（元）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    isError = actualText.isNotEmpty() && actualCents == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (difference != null) {
                    val label = when {
                        difference.signum() > 0 -> "实际比账本多"
                        difference.signum() < 0 -> "实际比账本少"
                        else -> "余额一致"
                    }
                    Text(if (difference.signum() == 0) label else "$label ¥${difference.abs().toPlainString()}",
                        style = MaterialTheme.typography.titleMedium)
                }
                Text("仅比较余额，不会自动改账本。以下账单尚未关联余额账户，请确认付款方式后再计入。",
                    style = MaterialTheme.typography.bodySmall)
                Text("可能相关的待核对账单：${pending.size} 笔", fontWeight = FontWeight.SemiBold)
                pending.take(20).forEach { transaction ->
                    val sign = if (transaction.type == "INCOME" || transaction.bankFlow == "IN") "+" else "−"
                    Column {
                        Text("${transaction.merchant.ifBlank { transaction.note.ifBlank { "账单" } }}  $sign¥${money(transaction.amountCents)}")
                        Text(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(transaction.timestamp)),
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = !busy, onClick = { onLink(transaction) }) { Text("确认计入此账户") }
                    }
                }
                if (pending.size > 20) Text("这里只显示最近 20 笔；其余可在账单页核对。")
                if (pending.isEmpty()) Text("没有待核对账单。若仍有差额，请检查漏记或重复记录。")
                TextButton(enabled = !busy && actualCents != null && actualCents != account.balanceCents,
                    onClick = { confirmCalibration = true }) { Text("按实际余额校准") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
    if (confirmCalibration) AlertDialog(onDismissRequest = { if (!busy) confirmCalibration = false },
        title = { Text("确认余额校准") },
        text = { Text("将余额起点改为现在的 ¥${actualCents?.let(::money).orEmpty()}。历史流水保留，以前的交易不再加减这个起点；本次操作会留下校准记录。") },
        confirmButton = { TextButton(enabled = !busy && actualCents != null, onClick = { actualCents?.let(onCalibrate) }) { Text("确认校准") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { confirmCalibration = false }) { Text("返回核对") } })
}

@Composable
private fun BalanceDialog(account: AccountEntity?, busy: Boolean = false, error: String? = null, onDismiss: () -> Unit, onSave: (String, String, Long, String) -> Unit) {
    var name by rememberSaveable(account?.id) { mutableStateOf(account?.name.orEmpty()) }
    var kind by rememberSaveable(account?.id) { mutableStateOf(account?.kind ?: "BANK") }
    var balance by rememberSaveable(account?.id) { mutableStateOf(account?.balanceCents?.let(::money).orEmpty()) }
    var reason by rememberSaveable(account?.id) { mutableStateOf("") }
    val cents = remember(balance) { parseMoney(balance) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (account == null) "添加账户" else "更新余额") },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
                if (account == null) {
                    listOf("BANK", "ALIPAY", "WECHAT", "FUND", "CASH").chunked(2).forEach { options ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            options.forEach { option ->
                                FilterChip(selected = kind == option, onClick = { kind = option }, label = { Text(kindLabel(option)) })
                            }
                        }
                    }
                    OutlinedTextField(value = name, onValueChange = { name = it.take(40) },
                        label = { Text(when (kind) { "BANK" -> "银行名称与尾号"; "FUND" -> "基金名称"; else -> "账户名称" }) }, singleLine = true)
                    if (kind == "BANK") Text("只填卡尾号，不要输入完整卡号。", style = MaterialTheme.typography.bodySmall)
                    if (kind == "ALIPAY") Text("只填写支付宝余额，不包含基金市值。", style = MaterialTheme.typography.bodySmall)
                    if (kind == "FUND") Text("每只基金单独添加，填写支付宝显示的当前市值。", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(value = balance, onValueChange = { balance = it }, label = { Text(if (kind == "FUND") "当前市值（元）" else "当前余额（元）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                if (account != null) OutlinedTextField(value = reason, onValueChange = { reason = it.take(100) },
                    label = { Text("校准原因（可选）") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, kind, cents ?: 0, reason) },
            enabled = !busy && (account != null || name.isNotBlank()) && cents != null) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

private fun kindLabel(kind: String) = when (kind) {
    "BANK" -> "银行卡"
    "ALIPAY" -> "支付宝余额"
    "WECHAT" -> "微信零钱"
    "FUND" -> "支付宝基金"
    "CASH" -> "现金"
    else -> "其他资产"
}
private fun money(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()
private fun parseMoney(value: String): Long? = runCatching {
    BigDecimal(value.trim()).movePointRight(2).setScale(0).longValueExact()
}.getOrNull()?.takeIf { it in -com.bookkeeping.app.data.repository.MAX_TRANSACTION_CENTS..com.bookkeeping.app.data.repository.MAX_TRANSACTION_CENTS }
