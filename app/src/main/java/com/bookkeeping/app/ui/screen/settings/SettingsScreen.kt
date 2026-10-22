package com.bookkeeping.app.ui.screen.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bookkeeping.app.data.local.entity.PendingNotificationEntity
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.notification.NotificationListenerHelper
import com.bookkeeping.app.data.settings.ThemeMode
import com.bookkeeping.app.ui.theme.formatYuan
import java.math.BigDecimal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val autoEnabled by viewModel.autoCaptureEnabled.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val pending by viewModel.pending.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val nearby by viewModel.nearby.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val message by viewModel.message.collectAsState()
    var selected by remember { mutableStateOf<PendingNotificationEntity?>(null) }
    var exportDialog by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var password by remember { mutableStateOf("") }
    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) viewModel.export(uri, password)
        password = ""
    }
    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        restoreUri = uri
        password = ""
    }

    Scaffold(topBar = { TopAppBar(
        title = { Text("设置", style = MaterialTheme.typography.headlineSmall) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    ) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Text("外观", style = MaterialTheme.typography.titleLarge)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ThemeMode.SYSTEM to "跟随系统", ThemeMode.LIGHT to "浅色", ThemeMode.DARK to "深色")
                        .forEach { (mode, label) ->
                            FilterChip(selected = themeMode == mode,
                                onClick = { viewModel.setThemeMode(mode) }, label = { Text(label) })
                        }
                }
            }
            item { Text("通知与数据", style = MaterialTheme.typography.titleLarge) }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("通知辅助记账", style = MaterialTheme.typography.titleMedium)
                            Switch(checked = autoEnabled, onCheckedChange = viewModel::setAutoCapture)
                        }
                        Text("默认关闭。开启后只读取支付宝、微信和建行支付通知，识别结果先进入待确认；你确认后才写入账本。")
                        if (autoEnabled && !NotificationListenerHelper.isEnabled(context)) {
                            Text("系统尚未授予通知使用权，当前不会读取通知。")
                            OutlinedButton(onClick = { NotificationListenerHelper.openSettings(context) }) {
                                Text("打开系统通知使用权设置")
                            }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("加密备份", style = MaterialTheme.typography.titleMedium)
                        Text("备份含账户、流水、导入记录、待确认通知和预算。口令至少 12 个字符，请自己保存；忘记后无法恢复。")
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { password = ""; exportDialog = true }, enabled = !busy) { Text("导出备份") }
                            OutlinedButton(onClick = { pickBackup.launch(arrayOf("*/*")) }, enabled = !busy) { Text("恢复备份") }
                        }
                    }
                }
            }
            if (message.isNotBlank()) item { Text(message,
                color = if (message.contains("失败") || message.contains("错误"))
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
            item { Text("待确认通知 · ${pending.size}", style = MaterialTheme.typography.titleLarge) }
            if (pending.isEmpty()) item { Text("没有待确认通知。定期导入账单仍可独立使用。") }
            items(pending, key = { it.id }) { draft ->
                Card(onClick = { selected = draft; viewModel.loadNearby(draft.id) },
                    modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(draft.merchant.ifBlank { "待识别交易" }, style = MaterialTheme.typography.titleMedium)
                            Text("${draft.source} · 待确认账户与重复流水",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("¥${formatYuan(draft.amountCents)}", style = MaterialTheme.typography.titleMedium)
                        Icon(Icons.Default.ChevronRight, contentDescription = null)
                    }
                }
            }
        }
    }
    selected?.let { draft ->
        var amount by remember(draft.id) { mutableStateOf(money(draft.amountCents)) }
        var merchant by remember(draft.id) { mutableStateOf(draft.merchant) }
        var type by remember(draft.id) { mutableStateOf(draft.type) }
        var category by remember(draft.id) { mutableStateOf(draft.category) }
        var accountId by remember(draft.id) { mutableStateOf<Long?>(null) }
        var checkedNearby by remember(draft.id) { mutableStateOf(false) }
        val cents = parseMoney(amount)
        AlertDialog(onDismissRequest = { selected = null; viewModel.clearNearby() },
            title = { Text("核对通知") },
            text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(draft.rawText, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(amount, { amount = it }, label = { Text("实际金额（元）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(merchant, { merchant = it.take(80) }, label = { Text("商户或对方") }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TransactionType.entries.forEach { option ->
                        FilterChip(type == option.name, onClick = { type = option.name },
                            label = { Text(when (option) { TransactionType.EXPENSE -> "支出"; TransactionType.INCOME -> "收入"; else -> "转账" }) })
                    }
                }
                if (type == "TRANSFER") Text("转账需要明确转出和转入账户；请在账单页使用“账户转账”，或导入原始账单。")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TransactionCategory.entries.forEach { option ->
                        FilterChip(category == option.name, onClick = { category = option.name }, label = { Text(option.displayName) })
                    }
                }
                Text("余额账户：只有确认真实付款账户时才选择")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(accountId == null, onClick = { accountId = null }, label = { Text("暂不关联") })
                    accounts.filter { account -> type != "TRANSFER" && draft.rawTimestamp > account.updatedAt &&
                        when (draft.source) {
                            "ALIPAY" -> account.kind == "ALIPAY" &&
                                listOf("支付宝余额", "余额支付", "余额付款").any(draft.rawText::contains) &&
                                !draft.rawText.contains("余额宝")
                            "WECHAT" -> account.kind == "WECHAT" &&
                                listOf("微信零钱", "零钱支付", "零钱付款").any(draft.rawText::contains) &&
                                !draft.rawText.contains("零钱通")
                            else -> false
                        }
                    }.forEach { account ->
                        FilterChip(accountId == account.id, onClick = { accountId = account.id }, label = { Text(account.name) })
                    }
                }
                if (nearby.isNotEmpty()) {
                    Text("已有 ${nearby.size} 笔同金额、相近日期的流水：")
                    nearby.take(5).forEach { Text("${it.merchant}  ¥${money(it.amountCents)}  ${it.source}") }
                    Row { Checkbox(checkedNearby, onCheckedChange = { checkedNearby = it }); Text("已核对，确认不是重复") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        viewModel.dismiss(draft.id, true); selected = null; viewModel.clearNearby()
                    }) { Text("标记重复") }
                    TextButton(onClick = {
                        viewModel.dismiss(draft.id, false); selected = null; viewModel.clearNearby()
                    }) { Text("忽略通知") }
                }
            } },
            confirmButton = { TextButton(onClick = {
                viewModel.confirm(draft.id, cents!!, type, category, merchant, accountId)
                selected = null; viewModel.clearNearby()
            }, enabled = cents != null && cents > 0 && type != "TRANSFER" && category != "TRANSFER" &&
                (nearby.isEmpty() || checkedNearby)) { Text("确认入账") } },
            dismissButton = { TextButton(onClick = { selected = null; viewModel.clearNearby() }) { Text("取消") } },
        )
    }
    if (exportDialog) AlertDialog(onDismissRequest = { exportDialog = false; password = "" },
        title = { Text("导出加密备份") },
        text = { OutlinedTextField(password, { password = it }, label = { Text("备份口令，至少 12 个字符") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { exportDialog = false; createBackup.launch("记账备份.sbk") },
            enabled = password.length >= 12) { Text("选择保存位置") } },
        dismissButton = { TextButton(onClick = { exportDialog = false; password = "" }) { Text("取消") } })
    restoreUri?.let { uri -> AlertDialog(onDismissRequest = { restoreUri = null; password = "" },
        title = { Text("恢复备份并覆盖当前账本？") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("恢复会替换当前账户和所有流水。请先导出一份当前账本备份。口令或文件错误时不会改动当前账本。")
            OutlinedTextField(password, { password = it }, label = { Text("备份口令") },
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(onClick = { viewModel.restore(uri, password); restoreUri = null; password = "" },
            enabled = password.isNotBlank() && !busy) { Text("确认覆盖并恢复") } },
        dismissButton = { TextButton(onClick = { restoreUri = null; password = "" }) { Text("取消") } }) }
}

private fun money(cents: Long) = BigDecimal.valueOf(cents, 2).toPlainString()
private fun parseMoney(value: String): Long? = runCatching {
    BigDecimal(value.trim()).movePointRight(2).setScale(0).longValueExact()
}.getOrNull()
