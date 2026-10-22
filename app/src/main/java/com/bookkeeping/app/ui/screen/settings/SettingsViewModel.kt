package com.bookkeeping.app.ui.screen.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookkeeping.app.data.backup.LedgerBackup
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.repository.AccountRepository
import com.bookkeeping.app.data.repository.PendingNotificationRepository
import com.bookkeeping.app.data.settings.SettingsPreferences
import com.bookkeeping.app.data.settings.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: SettingsPreferences,
    private val backup: LedgerBackup,
    private val pendingRepository: PendingNotificationRepository,
    private val accountRepository: AccountRepository,
) : ViewModel() {
    val autoCaptureEnabled = prefs.observeAutoCaptureEnabled()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val themeMode = prefs.observeThemeMode()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)
    val pending = pendingRepository.observePending()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val accounts = accountRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _nearby = MutableStateFlow<List<TransactionEntity>>(emptyList())
    val nearby: StateFlow<List<TransactionEntity>> = _nearby
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message

    fun setAutoCapture(enabled: Boolean) = viewModelScope.launch {
        prefs.setAutoCaptureEnabled(enabled)
        _message.value = if (enabled) "通知会先进入待确认，不会直接改账本或余额。" else "已停止读取新支付通知。"
    }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { prefs.setThemeMode(mode) }

    fun loadNearby(id: Long) = viewModelScope.launch { _nearby.value = pendingRepository.nearby(id) }
    fun clearNearby() { _nearby.value = emptyList() }

    fun confirm(id: Long, cents: Long, type: String, category: String,
                merchant: String, accountId: Long?) = viewModelScope.launch {
        runCatching { pendingRepository.confirm(id, cents, type, category, merchant, accountId) }
            .onSuccess { _message.value = if (it) "已确认入账" else "确认失败，请重新检查金额" }
            .onFailure { _message.value = "确认失败：${it.message ?: "未知错误"}" }
    }

    fun dismiss(id: Long, duplicate: Boolean) = viewModelScope.launch {
        pendingRepository.dismiss(id, duplicate)
        _message.value = if (duplicate) "已标记为重复" else "已忽略这条通知"
    }

    fun export(uri: Uri, password: String) = runBackup("备份") { backup.export(uri, password) }
    fun restore(uri: Uri, password: String) = runBackup("恢复") { backup.restore(uri, password) }

    private fun runBackup(label: String, action: suspend () -> LedgerBackup.Result) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val result = action()
                _message.value = "$label 完成：${result.accounts} 个账户、${result.transactions} 笔流水。"
            } catch (error: Exception) {
                _message.value = "$label 失败：${error.message ?: "文件不可用"}"
            } finally { _busy.value = false }
        }
    }
}
