package com.bookkeeping.app.ui.screen.transactions

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.data.local.entity.AccountEntity
import com.bookkeeping.app.ui.theme.formatYuan

@Composable
fun TransactionFilterDialog(current: TransactionFilters, accounts: List<AccountEntity>,
    onDismiss: () -> Unit, onApply: (TransactionFilters) -> Unit) {
    var account by rememberSaveable { mutableStateOf(current.accountId) }
    var type by rememberSaveable { mutableStateOf(current.type) }
    var source by rememberSaveable { mutableStateOf(current.source) }
    var pending by rememberSaveable { mutableStateOf(current.pendingOnly) }
    var minimum by rememberSaveable { mutableStateOf(current.minimum?.let(::formatYuan).orEmpty()) }
    var maximum by rememberSaveable { mutableStateOf(current.maximum?.let(::formatYuan).orEmpty()) }
    val min = parseAmountCents(minimum)
    val max = parseAmountCents(maximum)
    val valid = (minimum.isBlank() || min != null) && (maximum.isBlank() || max != null) &&
        (min == null || max == null || min <= max)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("组合筛选") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("账户")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(account == null, { account = null }, label = { Text("全部") })
                accounts.forEach { row -> FilterChip(account == row.id, { account = row.id }, label = { Text(row.name) }) }
            }
            Text("类型")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null to "全部", "EXPENSE" to "支出", "INCOME" to "收入", "TRANSFER" to "转账").forEach { (key, name) ->
                    FilterChip(type == key, { type = key }, label = { Text(name) })
                }
            }
            Text("来源")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null to "全部", "MANUAL" to "手记", "ALIPAY" to "支付宝", "WECHAT" to "微信", "BANK_PDF" to "银行 PDF", "NOTIFICATION" to "通知").forEach { (key, name) ->
                    FilterChip(source == key, { source = key }, label = { Text(name) })
                }
            }
            OutlinedTextField(minimum, { minimum = it }, label = { Text("最低金额（元，可不填）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(maximum, { maximum = it }, label = { Text("最高金额（元，可不填）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            FilterChip(pending, { pending = !pending }, label = { Text("仅看待核对") })
            if (!valid) Text("请填写有效金额，最低金额不能高于最高金额。", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(enabled = valid, onClick = { onApply(TransactionFilters(account, type, source, min, max, pending)) }) { Text("应用") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
