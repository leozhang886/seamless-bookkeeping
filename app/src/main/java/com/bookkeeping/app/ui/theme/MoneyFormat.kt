package com.bookkeeping.app.ui.theme

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/** Presentation only: the ledger continues to store integer cents. */
fun formatYuan(cents: Long): String = NumberFormat.getNumberInstance(Locale.CHINA).apply {
    minimumFractionDigits = 2
    maximumFractionDigits = 2
    isGroupingUsed = true
}.format(BigDecimal.valueOf(cents, 2))
