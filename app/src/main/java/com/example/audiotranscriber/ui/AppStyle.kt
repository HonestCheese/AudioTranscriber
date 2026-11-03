package com.example.audiotranscriber.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val AppBackground = Color(0xFF101116)
val AppSurface = Color(0xFF1B1D26)
val AppOutline = Color(0xFF343746)
val AppAccent = Color(0xFF9B8CFF)
val AppTextMuted = Color(0xFFAAADBD)

private val palette = darkColorScheme(
    primary = AppAccent,
    onPrimary = Color(0xFF111018),
    background = AppBackground,
    onBackground = Color(0xFFF4F3FA),
    surface = AppSurface,
    onSurface = Color(0xFFF4F3FA),
    surfaceVariant = Color(0xFF252733),
    onSurfaceVariant = AppTextMuted,
    outline = AppOutline,
    secondaryContainer = Color(0xFF2D293F),
    primaryContainer = Color(0xFF29263F),
    error = Color(0xFFFF758D)
)

@Composable
fun AppStyle(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = palette, content = content)
}
