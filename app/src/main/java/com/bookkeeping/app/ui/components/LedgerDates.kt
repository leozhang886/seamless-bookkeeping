package com.bookkeeping.app.ui.components

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

val LEDGER_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
fun ledgerToday(): LocalDate = LocalDate.now(LEDGER_ZONE)
fun ledgerMonthRange(month: YearMonth): Pair<Long, Long> =
    month.atDay(1).atStartOfDay(LEDGER_ZONE).toInstant().toEpochMilli() to
        month.plusMonths(1).atDay(1).atStartOfDay(LEDGER_ZONE).toInstant().toEpochMilli()
fun ledgerDate(timestamp: Long, pattern: String = "yyyy年M月d日 HH:mm"): String =
    DateTimeFormatter.ofPattern(pattern, Locale.CHINA).format(Instant.ofEpochMilli(timestamp).atZone(LEDGER_ZONE))

class MonthSelection(scope: CoroutineScope, private val saved: SavedStateHandle = SavedStateHandle()) {
    private val today = MutableStateFlow(ledgerToday())
    private val selected = MutableStateFlow(saved.get<String>("ledgerMonth"))
    val month = combine(today, selected) { date, value ->
        value?.let { runCatching { YearMonth.parse(it) }.getOrNull() } ?: YearMonth.from(date)
    }.stateIn(scope, SharingStarted.Eagerly, selected.value?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
        ?: YearMonth.from(today.value))
    val daysRemaining = combine(today, month) { date, target ->
        if (YearMonth.from(date) == target) target.lengthOfMonth() - date.dayOfMonth + 1 else 0
    }.stateIn(scope, SharingStarted.Eagerly, today.value.lengthOfMonth() - today.value.dayOfMonth + 1)
    init { scope.launch { while (true) { delay(30_000); refresh() } } }
    fun refresh() { today.value = ledgerToday() }
    fun select(value: YearMonth?) { selected.value = value?.toString(); saved["ledgerMonth"] = selected.value; refresh() }
    fun previous() = select(month.value.minusMonths(1))
    fun next() = select(month.value.plusMonths(1))
}

@Composable
fun MonthSelector(selection: MonthSelection, modifier: Modifier = Modifier) {
    val month by selection.month.collectAsState()
    var choosing by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(selection) { selection.refresh(); onPauseOrDispose {} }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = selection::previous) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "上个月") }
        TextButton(onClick = { choosing = true }, modifier = Modifier.weight(1f)) {
            Text("${month.year}年${month.monthValue}月")
        }
        IconButton(onClick = selection::next) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "下个月") }
        TextButton(onClick = { selection.select(null) }) { Text("本月") }
    }
    if (choosing) {
        var year by rememberSaveable { mutableStateOf(month.year.toString()) }
        AlertDialog(onDismissRequest = { choosing = false }, title = { Text("选择月份") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, label = { Text("年份") }, singleLine = true)
                (1..12).chunked(3).forEach { months -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    months.forEach { value -> FilterChip(month.monthValue == value,
                        onClick = { year.toIntOrNull()?.takeIf { it in 1900..2200 }?.let {
                            selection.select(YearMonth.of(it, value)); choosing = false
                        } }, label = { Text("${value}月") }) }
                } }
            } }, confirmButton = { TextButton(onClick = { choosing = false }) { Text("取消") } })
    }
}

@Composable
fun TransactionDateField(timestamp: Long, onChange: (Long) -> Unit) {
    val context = LocalContext.current
    val date = Instant.ofEpochMilli(timestamp).atZone(LEDGER_ZONE)
    Column {
        Text("交易时间（中国标准时间）", style = MaterialTheme.typography.labelMedium)
        Row {
            TextButton(onClick = { DatePickerDialog(context, { _, y, m, d ->
                onChange(date.withYear(y).withMonth(m + 1).withDayOfMonth(1).withDayOfMonth(d).toInstant().toEpochMilli())
            }, date.year, date.monthValue - 1, date.dayOfMonth).show() }) { Text(ledgerDate(timestamp, "yyyy年M月d日")) }
            TextButton(onClick = { TimePickerDialog(context, { _, h, m ->
                onChange(date.withHour(h).withMinute(m).withSecond(0).toInstant().toEpochMilli())
            }, date.hour, date.minute, true).show() }) { Text(ledgerDate(timestamp, "HH:mm")) }
        }
    }
}
