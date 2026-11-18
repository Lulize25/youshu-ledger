package com.youshu.ledger.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 「记一笔」品牌色：账本绿
private val LedgerGreen = Color(0xFF2E7D32)
private val LedgerGreenDark = Color(0xFF1B5E20)
private val ExpenseRed = Color(0xFFE53935)
private val IncomeGreen = Color(0xFF43A047)
private val WarnOrange = Color(0xFFFB8C00)

val LightColors = lightColorScheme(
    primary = LedgerGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA5D6A7),
    onPrimaryContainer = LedgerGreenDark,
    secondary = LedgerGreenDark,
    surfaceVariant = Color(0xFFF0F4EC),
)

val DarkColors = darkColorScheme(
    primary = Color(0xFFA5D6A7),
    onPrimary = LedgerGreenDark,
    primaryContainer = LedgerGreen,
    onPrimaryContainer = Color.White,
)

val ColorScheme.expense: Color get() = ExpenseRed
val ColorScheme.income: Color get() = IncomeGreen
val ColorScheme.warn: Color get() = WarnOrange

@Composable
fun YoushuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
