package com.bookkeeping.app.ui.screen.transactions

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.data.local.entity.AccountEntity

@Composable
fun TransferDialog(accounts: List<AccountEntity>, onDismiss: () -> Unit,
                   onSave: (Long, Long?, Long, Long, String) -> Unit) {
    val eligible = accounts.filter { it.kind != "FUND" }
    var sourceId by remember { mutableStateOf<Long?>(null) }
    var targetId by remember { mutableStateOf<Long?>(null) }
    var amount by remember { mutableStateOf("") }
    var fee by remember { mutableStateOf("0") }
    var note by remember { mutableStateOf("") }
    val amountCents = parseAmountCents(amount)
    val feeCents = parseAmountCents(fee) ?: if (fee.trim() in setOf("", "0", "0.00")) 0L else null
    AlertDialog(onDismissRequest = onDismiss, title = { Text("账户转账") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("转出账户")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                eligible.forEach { account -> FilterChip(sourceId == account.id,
                    onClick = { sourceId = account.id; if (targetId == account.id) targetId = null },
                    label = { Text(account.name) }) }
            }
            Text("转入账户")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(targetId == null, onClick = { targetId = null }, label = { Text("基金市值手动更新") })
                eligible.filter { it.id != sourceId }.forEach { account ->
                    FilterChip(targetId == account.id, onClick = { targetId = account.id }, label = { Text(account.name) })
                }
            }
            OutlinedTextField(amount, { amount = it }, label = { Text("转账金额（元）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(fee, { fee = it }, label = { Text("手续费（元，没有填 0）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(note, { note = it.take(200) }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth())
            Text(if (targetId == null) "银行卡会减少；基金市值仍由你手动更新。" else
                "转出账户减少金额与手续费，转入账户增加转账金额；转账不算收入或消费。")
        } },
        confirmButton = { TextButton(onClick = { onSave(sourceId!!, targetId, amountCents!!, feeCents!!, note) },
            enabled = sourceId != null && amountCents != null && feeCents != null) { Text("保存转账") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
