package com.spoolcheck.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Primary = Color(0xFF1E5F8E)
private val Accent = Color(0xFF26A65B)
val StatusVerified = Color(0xFF16A34A)
val StatusExpected = Color(0xFF9CA3AF)
val StatusMissing = Color(0xFFDC2626)
val StatusDamaged = Color(0xFFEA580C)
val StatusPending = Color(0xFFF59E0B)

private val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    secondary = Accent,
    onSecondary = Color.White,
    background = Color(0xFFF5F7FA),
    surface = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    secondary = Accent,
    onSecondary = Color.White,
)

@Composable
fun SpoolCheckTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
