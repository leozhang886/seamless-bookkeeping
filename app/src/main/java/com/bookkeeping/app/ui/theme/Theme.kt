package com.bookkeeping.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF9FD5B0),
    onPrimary = ForestDark,
    primaryContainer = ForestDark,
    onPrimaryContainer = Color(0xFFD5EFDC),
    secondary = Color(0xFFB7D9BE),
    tertiary = Color(0xFF8CD2A6),
    error = Color(0xFFFFB4A9),
    background = Color(0xFF101A15),
    surface = Color(0xFF1B2920),
    surfaceVariant = Color(0xFF2A3A30),
)

private val LightColorScheme = lightColorScheme(
    primary = Forest,
    onPrimary = Color.White,
    primaryContainer = Mint,
    onPrimaryContainer = ForestDark,
    secondary = ForestDark,
    secondaryContainer = Color(0xFFEEF4EE),
    tertiary = Income,
    error = Expense,
    background = Canvas,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF0F4EF),
    onSurfaceVariant = MutedInk,
    outline = Color(0xFF829187),
    outlineVariant = Hairline,
)

@Composable
fun BookkeepingAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}
