package com.bookkeeping.app.ui.screen.transactions

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.saveable.rememberSaveable
import com.bookkeeping.app.ui.components.TransactionDateField
import java.util.UUID
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bookkeeping.app.data.local.entity.AccountEntity

@Composable
fun TransferDialog(accounts: List<AccountEntity>, onDismiss: () -> Unit,
    busy: Boolean = false, error: String? = null,
                   onSave: (Long, Long?, Long, Long, String, Long, String) -> Unit) {
    var occurredAt by rememberSaveable { mutableStateOf(System.currentTimeMillis()) }
    val requestId = rememberSaveable { UUID.randomUUID().toString() }
    val eligible = accounts.filter { it.kind != "FUND" }
    var sourceId by rememberSaveable { mutableStateOf<Long?>(null) }
    var targetId by rememberSaveable { mutableStateOf<Long?>(null) }
    var amount by rememberSaveable { mutableStateOf("") }
    var fee by rememberSaveable { mutableStateOf("0") }
    var note by rememberSaveable { mutableStateOf("") }
    val amountCents = parseAmountCents(amount)
    val feeCents = parseAmountCents(fee) ?: if (fee.trim() in setOf("", "0", "0.00")) 0L else null
    var abandon by rememberSaveable { mutableStateOf(false) }
    val requestDismiss: () -> Unit = { if (!busy) { if (amount.isNotBlank() || note.isNotBlank() || sourceId != null || targetId != null || fee != "0") abandon = true else onDismiss() } }
    AlertDialog(onDismissRequest = requestDismiss, title = { Text("账户转账") },
        text = { Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()).imePadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (error != null) Text(error, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            TransactionDateField(occurredAt) { occurredAt = it }
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
            Text(if (targetId == null) "转出账户会减少；基金市值仍由你手动更新。" else
                "转出账户减少金额与手续费，转入账户增加转账金额；转账不算收入或消费。")
        } },
        confirmButton = { TextButton(onClick = { onSave(sourceId!!, targetId, amountCents!!, feeCents!!, note, occurredAt, requestId) },
            enabled = !busy && sourceId != null && amountCents != null && feeCents != null) { Text("保存转账") } },
        dismissButton = { TextButton(onClick = requestDismiss, enabled = !busy) { Text("取消") } })
    if (abandon) AlertDialog(onDismissRequest = { abandon = false }, title = { Text("放弃未保存的输入？") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("放弃") } },
        dismissButton = { TextButton(onClick = { abandon = false }) { Text("继续填写") } })

}
