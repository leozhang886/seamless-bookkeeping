package com.bookkeeping.app.ui.screen.transactions

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.bookkeeping.app.data.local.entity.TransactionEntity
import java.math.BigDecimal

@Composable
fun ReturnDialog(original: TransactionEntity, remainingCents: Long,
                 candidates: List<TransactionEntity>, accounts: List<AccountEntity>,
                 onDismiss: () -> Unit,
    busy: Boolean = false, error: String? = null,
                 onRecord: (String, Long, Long?, String, Long, String) -> Unit,
                 onLink: (Long, String) -> Unit) {
    var kind by rememberSaveable(original.id) { mutableStateOf("REFUND") }
    var amount by rememberSaveable(original.id) { mutableStateOf("") }
    var note by rememberSaveable(original.id) { mutableStateOf("") }
    var accountId by rememberSaveable(original.id) { mutableStateOf<Long?>(null) }
    var occurredAt by rememberSaveable { mutableStateOf(System.currentTimeMillis()) }
    val requestId = rememberSaveable { UUID.randomUUID().toString() }
    val cents = parseAmountCents(amount)
    var abandon by rememberSaveable { mutableStateOf(false) }
    val requestDismiss: () -> Unit = { if (!busy) { if (amount.isNotBlank() || note.isNotBlank() || accountId != null || kind != "REFUND") abandon = true else onDismiss() } }
    AlertDialog(onDismissRequest = requestDismiss, title = { Text("关联退款或报销") },
        text = { Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (error != null) Text(error, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            TransactionDateField(occurredAt) { occurredAt = it }
            Text("原支出：${original.merchant} ¥${BigDecimal.valueOf(original.amountCents, 2).toPlainString()}")
            Text("尚可关联：¥${BigDecimal.valueOf(remainingCents, 2).toPlainString()}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(kind == "REFUND", onClick = { kind = "REFUND" }, label = { Text("退款") })
                FilterChip(kind == "REIMBURSEMENT", onClick = { kind = "REIMBURSEMENT" }, label = { Text("报销") })
            }
            Text("如果退款已通过账单导入，请关联下面的收入，不要再新建一笔。")
            if (candidates.isEmpty()) Text("没有找到可关联的已入账收入。")
            candidates.forEach { candidate ->
                Text("${candidate.merchant} · ¥${BigDecimal.valueOf(candidate.amountCents, 2).toPlainString()} · ${candidate.source}")
                TextButton(onClick = { onLink(candidate.id, kind) }, enabled = !busy) { Text("关联这笔收入") }
            }
            Text("未导入退款时，才手动记录：")
            OutlinedTextField(amount, { amount = it }, label = { Text("到账金额（元）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            Text("到账账户，只有确认后才更新余额")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(accountId == null, onClick = { accountId = null }, label = { Text("稍后核对") })
                accounts.filter { it.kind != "FUND" }.forEach { account ->
                    FilterChip(accountId == account.id, onClick = { accountId = account.id }, label = { Text(account.name) })
                }
            }
            OutlinedTextField(note, { note = it.take(200) }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(onClick = { onRecord(kind, cents!!, accountId, note, occurredAt, requestId) },
            enabled = !busy && cents != null && cents <= remainingCents) { Text("新记到账") } },
        dismissButton = { TextButton(onClick = requestDismiss, enabled = !busy) { Text("关闭") } })
    if (abandon) AlertDialog(onDismissRequest = { abandon = false }, title = { Text("放弃未保存的输入？") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("放弃") } },
        dismissButton = { TextButton(onClick = { abandon = false }) { Text("继续填写") } })

}
