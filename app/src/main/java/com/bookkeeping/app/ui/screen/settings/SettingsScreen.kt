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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.bookkeeping.app.ui.components.ledgerDate
import com.bookkeeping.app.notification.CaptureDiagnostics
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
import com.bookkeeping.app.ui.theme.sourceLabel
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
    val nearbyKey by viewModel.nearbyKey.collectAsState()
    val validation by viewModel.validation.collectAsState()
    val history by viewModel.processed.collectAsState()
    val screenProtection by viewModel.screenProtection.collectAsState()
    val appLock by viewModel.appLock.collectAsState()
    val lastBackup by viewModel.lastBackup.collectAsState()
    val capture by CaptureDiagnostics.status.collectAsState()
    var permission by remember { mutableStateOf(NotificationListenerHelper.isEnabled(context)) }
    LifecycleResumeEffect(Unit) { permission = NotificationListenerHelper.isEnabled(context); onPauseOrDispose {} }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val selected = pending.firstOrNull { it.id == selectedId }
    val pendingGroups = remember(pending) { pending.groupBy { it.eventKey ?: "single-${it.id}" }.values.toList() }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showRecovery by rememberSaveable { mutableStateOf(false) }
    var confirmationPassword by remember { mutableStateOf("") }
    var formError by remember { mutableStateOf<String?>(null) }
    val deviceLock = context.getSystemService(android.app.KeyguardManager::class.java)
    var exportDialog by rememberSaveable { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var password by remember { mutableStateOf("") }
    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) viewModel.export(uri, password)
        password = ""
    }
    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        restoreUri = uri
        password = ""; viewModel.clearValidation()
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
                        if (autoEnabled && permission) Text(if (capture.connected) "通知服务已连接" else "已授权，等待系统连接通知服务")
                        capture.lastSaved?.let { Text("最近保存草稿：${ledgerDate(it)}", style = MaterialTheme.typography.bodySmall) }
                        capture.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Text("没有通知时不会生成账单。澎湃OS 请在应用设置允许后台运行；银行卡余额仍仅由银行 PDF 更新。", style = MaterialTheme.typography.bodySmall)
                        if (autoEnabled && !permission) {
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
                        lastBackup?.let { Text("最近校验成功：${ledgerDate(it)}", style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = { showRecovery = true }, enabled = !busy) { Text("恢复前的安全副本") }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { password = ""; confirmationPassword = ""; exportDialog = true }, enabled = !busy) { Text("导出备份") }
                            OutlinedButton(onClick = { pickBackup.launch(arrayOf("*/*")) }, enabled = !busy) { Text("恢复备份") }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp)) {
                    Text("隐私保护", style = MaterialTheme.typography.titleMedium)
                    Row { Text("禁止截图和最近任务预览", Modifier.weight(1f)); Switch(screenProtection, viewModel::setScreenProtection) }
                    Row { Text("使用系统锁屏凭据解锁账本", Modifier.weight(1f)); Switch(appLock, { enabled ->
                        if (!enabled || deviceLock.isDeviceSecure) viewModel.setAppLock(enabled)
                        else formError = "请先在手机系统中设置锁屏密码"
                    }) }
                    Text("账本离线保存，口令不保存在应用中。系统语音输入可能使用手机配置的在线识别服务。", style = MaterialTheme.typography.bodySmall)
                } }
            }
            formError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (message.isNotBlank()) item { Text(message,
                color = if (message.contains("失败") || message.contains("错误"))
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
            item { TextButton(onClick = { showHistory = true }) { Text("已处理通知与恢复") } }
            item { Text("待确认通知 · ${pending.size}", style = MaterialTheme.typography.titleLarge) }
            if (pending.isEmpty()) item { Text("没有待确认通知。定期导入账单仍可独立使用。") }
            items(pendingGroups, key = { it.first().eventKey ?: "single-${it.first().id}" }) { group ->
                var expanded by rememberSaveable(group.first().id) { mutableStateOf(false) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (group.size > 1) TextButton(onClick = { expanded = !expanded }) {
                    Text("同内容通知 ${group.size} 条 · ${if (expanded) "收起" else "展开逐条核对"}")
                }
                (if (expanded) group else group.take(1)).forEach { draft ->
                Card(onClick = { selectedId = draft.id; formError = null },
                    modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(draft.merchant.ifBlank { "待识别交易" }, style = MaterialTheme.typography.titleMedium)
                            Text("${sourceLabel(draft.source)} · ${ledgerDate(draft.rawTimestamp)}",
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
        }
    }
    selected?.let { draft ->
        var amount by rememberSaveable(draft.id) { mutableStateOf(money(draft.amountCents)) }
        var merchant by rememberSaveable(draft.id) { mutableStateOf(draft.merchant) }
        var type by rememberSaveable(draft.id) { mutableStateOf(draft.type) }
        var category by rememberSaveable(draft.id) { mutableStateOf(draft.category) }
        var accountId by rememberSaveable(draft.id) { mutableStateOf<Long?>(null) }
        var checkedNearby by rememberSaveable(draft.id) { mutableStateOf(false) }
        val cents = parseMoney(amount)?.takeIf { it in 1..com.bookkeeping.app.data.repository.MAX_TRANSACTION_CENTS }
        val loaded = cents != null && nearbyKey == "${draft.id}|$cents|$type"
        LaunchedEffect(draft.id, cents, type) { checkedNearby = false; viewModel.loadNearby(draft.id, cents, type) }
        LaunchedEffect(nearby) { checkedNearby = false }
        LaunchedEffect(type) {
            if (type == "EXPENSE" && category in setOf("SALARY", "TRANSFER")) category = "OTHER"
            if (type == "INCOME" && category !in setOf("SALARY", "OTHER")) category = "OTHER"
        }
        AlertDialog(onDismissRequest = { selectedId = null; viewModel.clearNearby() },
            title = { Text("核对通知") },
            text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                formError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
                    TransactionCategory.entries.filter { if (type == "INCOME") it.name in setOf("SALARY", "OTHER") else it.name !in setOf("SALARY", "TRANSFER") }.forEach { option ->
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
                if (!loaded && cents != null) Text("正在核对相似流水…")
                if (loaded && nearby.isNotEmpty()) {
                    Text("已有 ${nearby.size} 笔同金额、相近日期的流水：")
                    nearby.forEach { Text("${ledgerDate(it.timestamp)} · ${it.merchant} · ¥${money(it.amountCents)} · ${it.account.orEmpty()} · ${sourceLabel(it.source)}") }
                    Row { Checkbox(checkedNearby, onCheckedChange = { checkedNearby = it }); Text("已核对，确认不是重复") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        viewModel.dismiss(draft.id, true) { if (it) { selectedId = null; viewModel.clearNearby() } }
                    }) { Text("标记重复") }
                    TextButton(onClick = {
                        viewModel.dismiss(draft.id, false) { if (it) { selectedId = null; viewModel.clearNearby() } }
                    }) { Text("忽略通知") }
                }
            } },
            confirmButton = { TextButton(onClick = {
                formError = null
                viewModel.confirm(draft.id, cents!!, type, category, merchant, accountId, nearby.map { it.id }.toSet()) { saved ->
                    if (saved) { selectedId = null; viewModel.clearNearby() }
                    else { formError = "保存失败，请重新核对候选和账户"; viewModel.loadNearby(draft.id, cents, type) }
                }
            }, enabled = !busy && loaded && cents != null && cents > 0 && type != "TRANSFER" && category != "TRANSFER" &&
                (nearby.isEmpty() || checkedNearby)) { Text("确认入账") } },
            dismissButton = { TextButton(onClick = { selectedId = null; viewModel.clearNearby() }) { Text("取消") } },
        )
    }
    if (exportDialog) AlertDialog(onDismissRequest = { exportDialog = false; password = "" },
        title = { Text("导出加密备份") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(password, { password = it }, label = { Text("备份口令，至少 12 个字符") },
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(confirmationPassword, { confirmationPassword = it }, label = { Text("再输入一次口令") },
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            if (confirmationPassword.isNotEmpty() && confirmationPassword != password) Text("两次口令不一致")
        } },
        confirmButton = { TextButton(onClick = { exportDialog = false; createBackup.launch("记账备份-${ledgerDate(System.currentTimeMillis(), "yyyyMMdd-HHmmss")}.sbk") },
            enabled = password.length >= 12 && password == confirmationPassword && !busy) { Text("选择保存位置") } },
        dismissButton = { TextButton(onClick = { exportDialog = false; password = "" }) { Text("取消") } })
    restoreUri?.let { uri -> AlertDialog(onDismissRequest = { restoreUri = null; password = "" },
        title = { Text("恢复备份并覆盖当前账本？") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("先校验文件，再确认覆盖。恢复前会用同一口令保存当前账本的加密安全副本。")
            validation?.let { Text("已校验：${it.accounts} 个账户、${it.transactions} 笔流水") }
            if (message.contains("失败")) Text(message, color = MaterialTheme.colorScheme.error)
            OutlinedTextField(password, { password = it; viewModel.clearValidation() }, label = { Text("备份口令") },
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(onClick = {
            if (validation == null) viewModel.validate(uri, password)
            else viewModel.restore(uri, password) { if (it) { restoreUri = null; password = ""; viewModel.clearValidation() } }
        }, enabled = password.isNotBlank() && !busy) { Text(if (validation == null) "校验备份" else "确认覆盖并恢复") } },
        dismissButton = { TextButton(onClick = { restoreUri = null; password = "" }) { Text("取消") } }) }
    if (showHistory) AlertDialog(onDismissRequest = { showHistory = false }, title = { Text("已处理通知（最近 100 条）") },
        text = { LazyColumn(Modifier.heightIn(max = 440.dp)) {
            if (history.isEmpty()) item { Text("暂无记录") }
            items(history, key = { it.id }) { row -> Column(Modifier.padding(vertical = 8.dp)) {
                Text("${ledgerDate(row.rawTimestamp)} · ${row.merchant} · ${when (row.status) { "CONFIRMED" -> "已入账"; "DUPLICATE" -> "重复"; else -> "已忽略" }}")
                if (row.status in setOf("DISMISSED", "DUPLICATE")) TextButton(onClick = { viewModel.reopen(row.id) }, enabled = !busy) { Text("恢复到待确认") }
            } }
        } }, confirmButton = { TextButton(onClick = { showHistory = false }) { Text("关闭") } })
    if (showRecovery) AlertDialog(onDismissRequest = { showRecovery = false }, title = { Text("恢复前的安全副本") },
        text = { LazyColumn(Modifier.heightIn(max = 440.dp)) {
            item { Text("使用当时恢复备份的口令打开。副本存于本机，卸载应用会清除，请另行导出重要账本。") }
            val copies = viewModel.recoveryFiles()
            if (copies.isEmpty()) item { Text("尚未恢复过账本") }
            items(copies, key = { it.name }) { file -> TextButton(onClick = {
                restoreUri = Uri.fromFile(file); password = ""; viewModel.clearValidation(); showRecovery = false
            }) { Text(ledgerDate(file.lastModified())) } }
        } }, confirmButton = { TextButton(onClick = { showRecovery = false }) { Text("关闭") } })

}

private fun money(cents: Long) = BigDecimal.valueOf(cents, 2).toPlainString()
private fun parseMoney(value: String): Long? = runCatching {
    BigDecimal(value.trim()).movePointRight(2).setScale(0).longValueExact()
}.getOrNull()
