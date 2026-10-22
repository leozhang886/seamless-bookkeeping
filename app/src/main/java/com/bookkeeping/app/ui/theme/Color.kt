package com.bookkeeping.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.staticCompositionLocalOf

val Forest = Color(0xFF246448)
val ForestDark = Color(0xFF153C2D)
val Mint = Color(0xFFE2F2E7)
val Canvas = Color(0xFFF7F9F5)
val Ink = Color(0xFF1D2922)
val MutedInk = Color(0xFF66746A)
val Hairline = Color(0xFFE2E9E1)
val Expense = Color(0xFFB44F43)
val Income = Color(0xFF247451)

data class FinancePalette(
    val income: Color,
    val expense: Color,
    val transfer: Color,
    val refund: Color,
    val asset: Color,
    val warning: Color,
    val hero: Color,
    val onHero: Color,
    val onHeroMuted: Color,
)

val LightFinancePalette = FinancePalette(
    income = Income, expense = Expense, transfer = Color(0xFF526476),
    refund = Forest, asset = ForestDark, warning = Color(0xFF8B5E12),
    hero = ForestDark, onHero = Color.White, onHeroMuted = Color(0xFFC8DECF),
)

val DarkFinancePalette = FinancePalette(
    income = Color(0xFF9FD5B0), expense = Color(0xFFFFB4A9),
    transfer = Color(0xFFB1C5D7), refund = Color(0xFFB7D9BE),
    asset = Color(0xFFD5EFDC), warning = Color(0xFFFFD58A),
    hero = Color(0xFF254735), onHero = Color(0xFFF2FAF3), onHeroMuted = Color(0xFFC9DDCE),
)

val LocalFinancePalette = staticCompositionLocalOf { LightFinancePalette }
