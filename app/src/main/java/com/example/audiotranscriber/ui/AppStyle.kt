package com.example.audiotranscriber.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Calm, iOS-inspired dark palette.
val AppBackground = Color(0xFF0B0D11)
val AppSurface = Color(0xFF171A20)
val AppCard = Color(0xFF1D2129)
val AppOutline = Color(0xFF2B303A)
val AppAccent = Color(0xFF0A84FF)
val AppAccentSoft = Color(0xFF172D45)
val AppTextMuted = Color(0xFF9299A6)
val AppText = Color(0xFFF5F6F8)

private val palette = darkColorScheme(
    primary = AppAccent,
    onPrimary = Color.White,
    primaryContainer = AppAccentSoft,
    onPrimaryContainer = Color(0xFFD9EBFF),
    secondary = Color(0xFF9AA9BC),
    onSecondary = Color(0xFF11151B),
    background = AppBackground,
    onBackground = AppText,
    surface = AppSurface,
    onSurface = AppText,
    surfaceVariant = AppCard,
    onSurfaceVariant = AppTextMuted,
    outline = AppOutline,
    outlineVariant = Color(0xFF252A32),
    secondaryContainer = Color(0xFF26313F),
    onSecondaryContainer = Color(0xFFE1EAF5),
    error = Color(0xFFFF453A),
    onError = Color.White
)

private val appTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.8).sp
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.7).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.5).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 27.sp,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp
    )
)

@Composable
fun AppStyle(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = palette,
        typography = appTypography,
        content = content
    )
}
