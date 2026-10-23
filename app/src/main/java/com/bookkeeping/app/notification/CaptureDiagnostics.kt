package com.bookkeeping.app.notification

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only operational metadata; never amounts, notification text, or account numbers. */
object CaptureDiagnostics {
    data class Status(val connected: Boolean = false, val lastEvent: Long? = null,
        val lastSaved: Long? = null, val lastError: String? = null)
    private val state = MutableStateFlow(Status())
    val status = state.asStateFlow()
    fun connection(value: Boolean) { state.value = state.value.copy(connected = value) }
    fun received() { state.value = state.value.copy(lastEvent = System.currentTimeMillis()) }
    fun saved() { state.value = state.value.copy(lastSaved = System.currentTimeMillis(), lastError = null) }
    fun failed() { state.value = state.value.copy(lastError = "通知处理失败，可重新导入原始账单补齐") }
}
