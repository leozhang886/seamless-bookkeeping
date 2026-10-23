package com.bookkeeping.app.ui.screen.assets

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookkeeping.app.data.importer.BillImporter
import com.bookkeeping.app.data.importer.BillParseReport
import com.bookkeeping.app.data.repository.AccountRepository
import com.bookkeeping.app.data.repository.TransactionRepository
import com.bookkeeping.app.data.importer.ConstructionBankPdfImporter
import com.bookkeeping.app.data.local.entity.ImportBatchEntity
import com.bookkeeping.app.data.local.entity.BalanceCheckpointEntity
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject

@HiltViewModel
class AssetsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accounts: AccountRepository,
    private val transactions: TransactionRepository,
) : ViewModel() {
    val accountList = accounts.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val archivedAccounts = accounts.observeArchived().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val importBatches = transactions.observeImportBatches().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val transactionList = transactions.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _preview = MutableStateFlow<BillPreview?>(null)
    val preview: StateFlow<BillPreview?> = _preview
    private val _checkpoints = MutableStateFlow<List<BalanceCheckpointEntity>>(emptyList())
    val checkpoints: StateFlow<List<BalanceCheckpointEntity>> = _checkpoints

    data class BillPreview(val fileName: String, val report: BillParseReport,
                           val assessment: TransactionRepository.ImportAssessment)

    private fun change(onResult: (Boolean) -> Unit = {}, block: suspend () -> Unit) {
        if (_busy.value) { onResult(false); return }
        _busy.value = true
        viewModelScope.launch {
            try { block(); onResult(true) }
            catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (e: Exception) { _message.value = "操作失败：${e.message ?: "请重试"}"; onResult(false) }
            finally { _busy.value = false }
        }
    }
    fun addAccount(name: String, kind: String, balanceCents: Long, onResult: (Boolean) -> Unit = {}) = change(onResult) {
        accounts.add(name.trim(), kind, balanceCents); _message.value = "资产已添加"
    }
    fun updateBalance(id: Long, balanceCents: Long, reason: String, onResult: (Boolean) -> Unit = {}) = change(onResult) {
        accounts.updateBalance(id, balanceCents, reason.ifBlank { "手动校准" }); _message.value = "余额已校准，历史流水保留"
    }
    fun rename(id: Long, name: String, onResult: (Boolean) -> Unit) = change(onResult) {
        accounts.rename(id, name); _message.value = "账户名称已更新"
    }
    fun deleteAccount(id: Long, onDeleted: () -> Unit) = change {
        if (accounts.delete(id) > 0) onDeleted()
    }
    fun restoreAccount(id: Long) = change { accounts.restore(id); _message.value = "账户已恢复" }
    fun loadCheckpoints(id: Long) = viewModelScope.launch {
        runCatching { accounts.checkpoints(id) }.onSuccess { _checkpoints.value = it }
            .onFailure { _message.value = "读取校准记录失败" }
    }
    fun linkPendingTransaction(transactionId: Long, accountId: Long) = change {
        check(transactions.setBalanceAccount(transactionId, accountId)) { "这笔账单无法计入该账户，请到账单页核对。" }
    }
    fun undoImport(id: Long) = change {
        val count = transactions.undoImport(id)
        check(count >= 0) { "本批流水已关联退款或辅助账单，请先取消关联" }
        _message.value = "已撤销 $count 笔流水，可在导入记录中恢复"
    }
    fun restoreImport(id: Long) = change {
        val count = transactions.restoreImport(id)
        check(count >= 0) { "恢复受阻，请核对原交易及退款额度" }
        _message.value = "已恢复 $count 笔流水"
    }

    fun previewBill(uri: Uri, fileName: String) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            _message.value = "正在读取账单…"
            try {
                val report = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {
                        if (fileName.endsWith(".pdf", ignoreCase = true)) {
                            PDFBoxResourceLoader.init(context.applicationContext)
                            ConstructionBankPdfImporter.parseReport(it)
                        } else BillImporter.parseReport(it, fileName)
                    }
                        ?: throw IllegalArgumentException("无法打开文件")
                }
                val assessment = withContext(Dispatchers.IO) { transactions.assessImport(report.records) }
                _preview.value = BillPreview(fileName, report, assessment)
                _message.value = "文件已读取，尚未写入账本。请核对预览。"
            } catch (error: Exception) {
                _message.value = "导入失败：${error.message ?: "文件格式不受支持"}"
            } finally {
                _busy.value = false
            }
        }
    }

    fun dismissPreview() { _preview.value = null }

    fun confirmImport() {
        val selected = _preview.value ?: return
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val report = selected.report
                require(report.blockingErrors.isEmpty()) { "账单完整性校验未通过，请重新导出原文件" }
                val fingerprint = MessageDigest.getInstance("SHA-256")
                    .digest(report.records.joinToString("|") { it.importKey.orEmpty() }.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                val batch = ImportBatchEntity(fileName = selected.fileName, source = report.source,
                    importedAt = System.currentTimeMillis(), originalRows = report.originalRows,
                    parsedRows = report.records.size, rejectedRows = report.rejected.size,
                    amountCents = report.amountCents, fingerprint = fingerprint)
                val result = withContext(Dispatchers.IO) { transactions.importBills(report.records, batch) }
                _preview.value = null
                _message.value = "新增 ${result.added} 笔，跳过已导入 ${result.duplicates} 笔。可撤销本批次。"
            } catch (error: Exception) {
                _message.value = "导入失败，账本未改动：${error.message ?: "未知错误"}"
            } finally { _busy.value = false }
        }
    }

}
