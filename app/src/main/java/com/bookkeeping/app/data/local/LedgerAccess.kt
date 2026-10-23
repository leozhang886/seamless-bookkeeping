package com.bookkeeping.app.data.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A restore invalidates queued writes and all UI objects referring to the old ledger. */
class LedgerAccess {
    private val mutex = Mutex()
    private val _generation = MutableStateFlow(0L)
    val generation = _generation.asStateFlow()
    private val _restoring = MutableStateFlow(false)
    val restoring = _restoring.asStateFlow()

    suspend fun <T> write(block: suspend () -> T): T {
        check(!_restoring.value) { "正在恢复账本，请稍后重试" }
        val expected = _generation.value
        return mutex.withLock {
            check(expected == _generation.value) { "账本已恢复，请重新打开记录" }
            block()
        }
    }

    suspend fun <T> replace(block: suspend () -> T): T = mutex.withLock {
        _restoring.value = true
        _generation.value++
        try { block() } finally {
            _generation.value++
            _restoring.value = false
        }
    }
}
