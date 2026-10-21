package com.bookkeeping.app.ui.screen.assets

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookkeeping.app.data.importer.BillImporter
import com.bookkeeping.app.data.repository.AccountRepository
import com.bookkeeping.app.data.repository.TransactionRepository
import com.bookkeeping.app.data.importer.ConstructionBankPdfImporter
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
import javax.inject.Inject

@HiltViewModel
class AssetsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accounts: AccountRepository,
    private val transactions: TransactionRepository,
) : ViewModel() {
    val accountList = accounts.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val transactionList = transactions.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    fun addAccount(name: String, kind: String, balanceCents: Long) {
        viewModelScope.launch {
            accounts.add(name.trim(), kind, balanceCents)
            _message.value = "资产已添加。金额以你填写的数值为准。"
        }
    }

    fun updateBalance(id: Long, balanceCents: Long) {
        viewModelScope.launch {
            accounts.updateBalance(id, balanceCents)
            _message.value = "金额已更新"
        }
    }

    fun deleteAccount(id: Long) {
        viewModelScope.launch { accounts.delete(id) }
    }

    fun linkPendingTransaction(transactionId: Long, accountId: Long) {
        viewModelScope.launch {
            if (!transactions.setBalanceAccount(transactionId, accountId))
                _message.value = "这笔账单无法计入该账户，请到账单页核对。"
        }
    }

    fun importBill(uri: Uri, fileName: String) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _message.value = "正在读取账单…"
            try {
                val result = withContext(Dispatchers.IO) {
                    val bills = context.contentResolver.openInputStream(uri)?.use {
                        if (fileName.endsWith(".pdf", ignoreCase = true)) {
                            PDFBoxResourceLoader.init(context.applicationContext)
                            ConstructionBankPdfImporter.parse(it)
                        } else BillImporter.parse(it, fileName)
                    }
                        ?: throw IllegalArgumentException("无法打开文件")
                    transactions.importBills(bills)
                }
                val pdfNotice = if (fileName.endsWith(".pdf", ignoreCase = true))
                    "。银行卡付款可能与微信、支付宝账单重复，请核对" else ""
                _message.value = "识别 ${result.found} 笔，新增 ${result.added} 笔，跳过重复 ${result.duplicates} 笔，更新余额账户 ${result.balancesLinked} 笔，补全方向 ${result.directionsUpdated} 笔，匹配内部转账 ${result.transfers} 笔$pdfNotice"
            } catch (error: Exception) {
                _message.value = "导入失败：${error.message ?: "文件格式不受支持"}"
            } finally {
                _busy.value = false
            }
        }
    }
}
