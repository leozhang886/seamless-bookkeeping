package com.bookkeeping.app.ui.screen.stats

import androidx.compose.ui.graphics.Color
import com.bookkeeping.app.data.local.entity.TransactionCategory

// 图表分类色保持可区分，但降低大面积饼图的饱和度。
private val PALETTE: Map<TransactionCategory, Color> = mapOf(
    TransactionCategory.FOOD          to Color(0xFFC77465),
    TransactionCategory.TRANSPORT     to Color(0xFF5B8FA6),
    TransactionCategory.SHOPPING      to Color(0xFF947BB3),
    TransactionCategory.ENTERTAINMENT to Color(0xFFBD7490),
    TransactionCategory.HOUSING       to Color(0xFF6B7EA8),
    TransactionCategory.UTILITIES     to Color(0xFF5C9F92),
    TransactionCategory.HEALTH        to Color(0xFFA58375),
    TransactionCategory.EDUCATION     to Color(0xFFC9964E),
    TransactionCategory.SALARY        to Color(0xFF709A6B),
    TransactionCategory.TRANSFER      to Color(0xFF837BAE),
    TransactionCategory.OTHER         to Color(0xFF89918F),
)

fun categoryColor(category: TransactionCategory): Color =
    PALETTE[category] ?: Color(0xFF89918F)
