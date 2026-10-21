package com.bookkeeping.app.ui.screen.transactions

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.AccountEntity
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.data.repository.canLinkBalance
import com.bookkeeping.app.data.repository.balanceEditImpactCents
import com.bookkeeping.app.data.repository.editedTransaction
import com.bookkeeping.app.data.repository.eligibleBalanceAccount
import java.math.BigDecimal

// "新建账单"对话框
// 表单在对话框打开期间保存输入；关闭后不保留未保存的内容。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionDialog(
    accounts: List<AccountEntity>,
    transaction: TransactionEntity? = null,
    errorMessage: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (
        amountCents: Long,
        type: TransactionType,
        category: TransactionCategory,
        merchant: String,
        note: String,
        accountId: Long?,
    ) -> Unit,
) {
    // 用户输入的本地 UI 状态（对话框关闭就丢弃，不存到 ViewModel）
    var amountText by remember(transaction?.id) { mutableStateOf(transaction?.amountCents?.let { BigDecimal.valueOf(it, 2).toPlainString() }.orEmpty()) }
    var selectedType by remember(transaction?.id) { mutableStateOf(transaction?.type?.let { runCatching { TransactionType.valueOf(it) }.getOrNull() } ?: TransactionType.EXPENSE) }
    var selectedCategory by remember(transaction?.id) { mutableStateOf(transaction?.category?.let { runCatching { TransactionCategory.valueOf(it) }.getOrNull() } ?: TransactionCategory.FOOD) }
    var merchant by remember(transaction?.id) { mutableStateOf(transaction?.merchant.orEmpty()) }
    var note by remember(transaction?.id) { mutableStateOf(transaction?.note.orEmpty()) }
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var accountMenuExpanded by remember { mutableStateOf(false) }
    var selectedAccountId by remember(transaction?.id) { mutableStateOf(transaction?.balanceAccountId?.takeIf { id -> accounts.any { it.id == id } }) }
    var voiceStatus by remember { mutableStateOf<String?>(null) }
    val transfer = transaction?.type == TransactionType.TRANSFER.name
    val linkedReturn = transaction?.relationKind in setOf("REFUND", "REIMBURSEMENT")
    val availableAccounts = if (transaction == null) accounts.filter { it.kind != "FUND" } else
        accounts.filter { canLinkBalance(transaction) &&
            (eligibleBalanceAccount(it, transaction) || it.id == transaction.balanceAccountId) }
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            val draft = spoken?.let(::parseVoiceTransaction)
            when {
                spoken.isNullOrBlank() -> voiceStatus = "没有听清，请重试或手动填写。"
                draft == null -> {
                    amountText = ""
                    selectedAccountId = null
                    voiceStatus = "识别到“$spoken”。转账、基金申购等请手动核对后记账。"
                }
                else -> {
                    amountText = draft.amountText ?: ""
                    selectedType = draft.type
                    selectedCategory = draft.category
                    note = draft.note
                    selectedAccountId = when {
                        spoken.contains("支付宝余额") -> availableAccounts.singleOrNull { it.kind == "ALIPAY" }?.id
                        spoken.contains("微信零钱") -> availableAccounts.singleOrNull { it.kind == "WECHAT" }?.id
                        else -> null
                    }
                    voiceStatus = if (draft.amountText == null) "已识别文字，请手动填写金额并核对账户。" else "已填入识别结果，请核对金额、分类和余额账户后保存。"
                }
            }
        }
    }

    val cents = parseAmountCents(amountText)
    val amountValid = cents != null && cents > 0
    val proposed = if (transaction != null && cents != null)
        editedTransaction(transaction, cents, selectedType, selectedCategory, merchant, note, selectedAccountId)
    else null
    val balanceChanges = if (transaction != null && proposed != null)
        balanceEditImpactCents(transaction, proposed, accounts) else emptyMap()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (transaction == null) "新建账单" else "编辑账单") },
        text = {
            Column(modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (errorMessage != null) Text(errorMessage)
                if (!transfer && !linkedReturn) TextButton(onClick = {
                    voiceStatus = null
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "例如：午饭花了二十五块五")
                    }
                    try {
                        voiceLauncher.launch(intent)
                    } catch (_: ActivityNotFoundException) {
                        voiceStatus = "手机没有可用的语音识别服务，请手动填写。"
                    } catch (_: SecurityException) {
                        voiceStatus = "语音识别服务无法启动，请检查手机设置。"
                    }
                }) { Text("🎙 语音输入") }
                if (!transfer && !linkedReturn) Text("语音由手机的识别服务处理，保存前请核对。")
                voiceStatus?.let { Text(it) }
                // 收入 / 支出 切换
                if (transfer) Text("转账方向沿用原流水；修改金额会同步改变转入账户，手续费不变。")
                else if (linkedReturn) Text("这笔款项已关联原支出；只能调整金额和说明。")
                else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedType == TransactionType.EXPENSE,
                        onClick = { selectedType = TransactionType.EXPENSE },
                        label = { Text("支出") }
                    )
                    FilterChip(
                        selected = selectedType == TransactionType.INCOME,
                        onClick = { selectedType = TransactionType.INCOME },
                        label = { Text("收入") }
                    )
                }

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("金额（元）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    isError = amountText.isNotEmpty() && !amountValid,
                    modifier = Modifier.fillMaxWidth()
                )

                // 分类下拉
                ExposedDropdownMenuBox(
                    expanded = categoryMenuExpanded,
                    onExpandedChange = { if (!transfer && !linkedReturn) categoryMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedCategory.displayName,
                        onValueChange = { },
                        readOnly = true,
                        enabled = !transfer && !linkedReturn,
                        label = { Text("分类") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryMenuExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    DropdownMenu(
                        expanded = categoryMenuExpanded,
                        onDismissRequest = { categoryMenuExpanded = false }
                    ) {
                        TransactionCategory.entries.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(cat.displayName) },
                                onClick = {
                                    selectedCategory = cat
                                    categoryMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text("商户/对方（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (transaction == null || canLinkBalance(transaction)) ExposedDropdownMenuBox(
                    expanded = accountMenuExpanded,
                    onExpandedChange = { accountMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = accounts.firstOrNull { it.id == selectedAccountId }?.name ?: "稍后核对",
                        onValueChange = {}, readOnly = true,
                        label = { Text("余额对应账户") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = accountMenuExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    DropdownMenu(expanded = accountMenuExpanded, onDismissRequest = { accountMenuExpanded = false }) {
                        DropdownMenuItem(text = { Text("稍后核对") }, onClick = {
                            selectedAccountId = null; accountMenuExpanded = false
                        })
                        availableAccounts.forEach { account ->
                            DropdownMenuItem(text = { Text(account.name) }, onClick = {
                                selectedAccountId = account.id; accountMenuExpanded = false
                            })
                        }
                    }
                } else Text("这笔记录无法关联当前余额，或资金方向不明确。")
                if (transaction != null && amountValid) {
                    if (balanceChanges.isEmpty()) Text("保存后，当前账户余额不变。")
                    accounts.filter { it.id in balanceChanges }.forEach { account ->
                        val change = balanceChanges.getValue(account.id)
                        val after = BigDecimal.valueOf(account.balanceCents) + change
                        Text("${account.name}：¥${BigDecimal.valueOf(account.balanceCents, 2).toPlainString()} → " +
                            "¥${after.movePointLeft(2).toPlainString()}（${if (change.signum() > 0) "+" else "−"}¥${change.abs().movePointLeft(2).toPlainString()}）")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val amount = cents ?: return@TextButton
                    onConfirm(amount, selectedType, selectedCategory, merchant, note, selectedAccountId)
                },
                enabled = amountValid
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

internal fun parseAmountCents(value: String): Long? = runCatching {
    BigDecimal(value.trim()).movePointRight(2).setScale(0).longValueExact()
}.getOrNull()?.takeIf { it > 0 }
