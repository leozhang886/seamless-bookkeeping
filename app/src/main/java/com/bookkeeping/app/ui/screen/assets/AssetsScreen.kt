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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bookkeeping.app.ui.theme.ForestDark
import com.bookkeeping.app.data.local.entity.AccountEntity
import com.bookkeeping.app.data.local.entity.TransactionEntity
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetsScreen(viewModel: AssetsViewModel = hiltViewModel()) {
    val accounts by viewModel.accountList.collectAsState()
    val transactions by viewModel.transactionList.collectAsState()
    val message by viewModel.message.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val context = LocalContext.current
    var addAccount by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AccountEntity?>(null) }
    var deleting by remember { mutableStateOf<AccountEntity?>(null) }
    var reconcilingId by remember { mutableStateOf<Long?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: uri.lastPathSegment.orEmpty()
            viewModel.importBill(uri, name)
        }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("我的资产", style = MaterialTheme.typography.headlineSmall) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = ForestDark),
                ) {
                    Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("当前资产总额", style = MaterialTheme.typography.labelLarge, color = Color(0xFFC8DECF))
                        Text("¥${money(accounts.sumOf { it.balanceCents })}",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold, color = Color.White)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            listOf("BANK", "ALIPAY").forEach { kind ->
                                AssetAmount(kindLabel(kind), accounts.filter { it.kind == kind }.sumOf { it.balanceCents }, Modifier.weight(1f))
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            listOf("WECHAT", "FUND").forEach { kind ->
                                AssetAmount(kindLabel(kind), accounts.filter { it.kind == kind }.sumOf { it.balanceCents }, Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { addAccount = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("添加资产", modifier = Modifier.padding(start = 4.dp))
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
            item {
                Text("账户与基金", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            }
            if (accounts.isEmpty()) item {
                Text("还没有资产记录。可添加银行卡、支付宝余额、微信零钱，以及每只支付宝基金的当前市值。",
                    modifier = Modifier.padding(horizontal = 16.dp))
            }
            items(accounts, key = { it.id }) { account ->
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                        Text("¥${money(account.balanceCents)}", style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(top = 4.dp))
                        Text("余额起点设定于 ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(account.updatedAt))}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (account.kind != "FUND") TextButton(onClick = { reconcilingId = account.id }) { Text("对账") }
                            TextButton(onClick = { editing = account }) { Text(if (account.kind == "FUND") "更新市值" else "更新余额") }
                            TextButton(onClick = { deleting = account }) { Text("删除") }
                        }
                    }
                }
            }
            item {
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
    if (addAccount) BalanceDialog(null, onDismiss = { addAccount = false }) { name, kind, cents ->
        viewModel.addAccount(name, kind, cents); addAccount = false
    }
    editing?.let { account ->
        BalanceDialog(account, onDismiss = { editing = null }) { _, _, cents ->
            viewModel.updateBalance(account.id, cents); editing = null
        }
    }
    deleting?.let { account ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除账户？") },
            text = { Text("只删除这条资产记录，已有交易记录仍保留。") },
            confirmButton = { TextButton(onClick = { viewModel.deleteAccount(account.id); deleting = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
    reconcilingId?.let { id ->
        accounts.firstOrNull { it.id == id }?.let { account ->
            ReconciliationDialog(
                account = account,
                pending = pendingForAccount(account, accounts, transactions),
                onDismiss = { reconcilingId = null },
                onLink = { transaction -> viewModel.linkPendingTransaction(transaction.id, account.id) },
            )
        }
    }
}

@Composable
private fun AssetAmount(label: String, cents: Long, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Color(0xFFC8DECF))
        Text("¥${money(cents)}", style = MaterialTheme.typography.titleMedium, color = Color.White)
    }
}

@Composable
private fun ReconciliationDialog(
    account: AccountEntity,
    pending: List<TransactionEntity>,
    onDismiss: () -> Unit,
    onLink: (TransactionEntity) -> Unit,
) {
    var actualText by remember(account.id) { mutableStateOf("") }
    val actualCents = parseMoney(actualText)
    val difference = actualCents?.let { BigDecimal.valueOf(it, 2) - BigDecimal.valueOf(account.balanceCents, 2) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("余额对账 · ${account.name}") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        TextButton(onClick = { onLink(transaction) }) { Text("确认计入此账户") }
                    }
                }
                if (pending.size > 20) Text("这里只显示最近 20 笔；其余可在账单页核对。")
                if (pending.isEmpty()) Text("没有待核对账单。若仍有差额，请检查漏记或重复记录。")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun BalanceDialog(account: AccountEntity?, onDismiss: () -> Unit, onSave: (String, String, Long) -> Unit) {
    var name by remember(account) { mutableStateOf(account?.name.orEmpty()) }
    var kind by remember(account) { mutableStateOf(account?.kind ?: "BANK") }
    var balance by remember(account) { mutableStateOf(account?.balanceCents?.let(::money).orEmpty()) }
    val cents = remember(balance) { parseMoney(balance) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (account == null) "添加账户" else "更新余额") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (account == null) {
                    listOf("BANK", "ALIPAY", "WECHAT", "FUND").chunked(2).forEach { options ->
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
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, kind, cents ?: 0) },
            enabled = (account != null || name.isNotBlank()) && cents != null) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

private fun kindLabel(kind: String) = when (kind) {
    "BANK" -> "银行卡"
    "ALIPAY" -> "支付宝余额"
    "WECHAT" -> "微信零钱"
    "FUND" -> "支付宝基金"
    else -> "其他资产"
}
private fun money(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()
private fun parseMoney(value: String): Long? = runCatching {
    BigDecimal(value.trim()).movePointRight(2).setScale(0).longValueExact()
}.getOrNull()
