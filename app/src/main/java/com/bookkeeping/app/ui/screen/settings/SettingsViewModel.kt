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
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
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
    val screenProtection = prefs.observeScreenProtection().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val appLock = prefs.observeAppLock().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val lastBackup = prefs.observeLastBackup().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val processed = pendingRepository.observeProcessed().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
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

    val nearbyKey = MutableStateFlow<String?>(null)
    private var nearbyJob: Job? = null
    fun loadNearby(id: Long, cents: Long?, type: String) {
        nearbyJob?.cancel(); nearbyKey.value = null; _nearby.value = emptyList()
        if (cents == null) return
        nearbyJob = viewModelScope.launch {
            try {
                _nearby.value = pendingRepository.nearby(id, cents, type)
                nearbyKey.value = "$id|$cents|$type"
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { _message.value = "候选读取失败，请重新打开核对" }
        }
    }
    fun clearNearby() { nearbyJob?.cancel(); nearbyKey.value = null; _nearby.value = emptyList() }
    private fun action(onResult: (Boolean) -> Unit = {}, block: suspend () -> Boolean) {
        if (_busy.value) { onResult(false); return }
        _busy.value = true
        viewModelScope.launch {
            try { onResult(block()) }
            catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { _message.value = "操作失败：${e.message ?: "请重试"}"; onResult(false) }
            finally { _busy.value = false }
        }
    }
    fun confirm(id: Long, cents: Long, type: String, category: String,
        merchant: String, accountId: Long?, acknowledged: Set<Long>, onResult: (Boolean) -> Unit) = action(onResult) {
        pendingRepository.confirm(id, cents, type, category, merchant, accountId, acknowledged).also {
            _message.value = if (it) "已确认入账" else "确认失败，请重新核对"
        }
    }
    fun dismiss(id: Long, duplicate: Boolean, onResult: (Boolean) -> Unit = {}) = action(onResult) {
        (pendingRepository.dismiss(id, duplicate) > 0).also { _message.value = if (it) "通知已处理，可在历史记录中恢复" else "处理失败" }
    }
    fun reopen(id: Long) = action { (pendingRepository.reopen(id) > 0).also { _message.value = if (it) "已重新加入待确认" else "恢复失败" } }
    fun setScreenProtection(value: Boolean) = action { prefs.setScreenProtection(value); true }
    fun setAppLock(value: Boolean) = action { prefs.setAppLock(value); true }
    fun recoveryFiles() = backup.recoveryFiles()
    val validation = MutableStateFlow<LedgerBackup.Result?>(null)
    fun clearValidation() { validation.value = null }
    fun validate(uri: Uri, password: String) = action {
        validation.value = backup.validate(uri, password); _message.value = "备份校验通过，尚未替换账本"; true
    }
    fun export(uri: Uri, password: String) = action {
        val result = backup.export(uri, password); prefs.recordBackup()
        _message.value = "备份已写入并回读校验：${result.accounts} 个账户、${result.transactions} 笔流水"; true
    }
    fun restore(uri: Uri, password: String, onResult: (Boolean) -> Unit = {}) = action(onResult) {
        val result = backup.restore(uri, password)
        _message.value = "恢复完成：${result.accounts} 个账户、${result.transactions} 笔流水"; true
    }
}
