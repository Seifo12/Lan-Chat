package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class AppColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val surfaceElevated: Color,
    val cardBackground: Color,
    val dialogBackground: Color,
    val tabBackground: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textDisabled: Color,
    val border: Color,
    val borderLight: Color,
    val borderFocused: Color,
    val primary: Color,
    val primaryLight: Color,
    val primaryDark: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val bubbleSender: Color,
    val bubbleSenderBorder: Color,
    val bubbleSenderText: Color,
    val bubbleReceiver: Color,
    val bubbleReceiverBorder: Color,
    val bubbleReceiverText: Color,
    val chatBackground: Color,
    val topBarBackground: Color,
    val error: Color,
    val online: Color,
    val offline: Color
)

val LightAppColors = AppColors(
    isDark = false,
    background = Color(0xFFF8FAFC), // Modern crisp Slate-50 canvas
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF1F5F9), // Slate-100
    surfaceElevated = Color(0xFFFFFFFF),
    cardBackground = Color(0xFFFFFFFF),
    dialogBackground = Color(0xFFFFFFFF),
    tabBackground = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF0F172A), // Slate-900 high-contrast
    textSecondary = Color(0xFF475569), // Slate-600
    textDisabled = Color(0xFF94A3B8), // Slate-400
    border = Color(0xFFE2E8F0), // Slate-200
    borderLight = Color(0xFFF1F5F9),
    borderFocused = Color(0xFF2563EB), // Modern Royal Blue
    primary = Color(0xFF2563EB), // Signature brand Royal Blue
    primaryLight = Color(0xFF3B82F6), // Vibrant Sky/Cobalt
    primaryDark = Color(0xFF1D4ED8), // Deep Indigo
    primaryContainer = Color(0xFFEFF6FF), // Soft Blue tint
    onPrimaryContainer = Color(0xFF1E3A8A),
    bubbleSender = Color(0xFF2563EB), // Modern vibrant sapphire bubble
    bubbleSenderBorder = Color(0xFF1D4ED8),
    bubbleSenderText = Color(0xFFFFFFFF), // High-contrast white text
    bubbleReceiver = Color(0xFFF1F5F9), // Modern sleek slate bubble
    bubbleReceiverBorder = Color(0xFFE2E8F0),
    bubbleReceiverText = Color(0xFF0F172A), // Crisp slate-900 text
    chatBackground = Color(0xFFF8FAFC), // Pure, elegant slate canvas
    topBarBackground = Color(0xFFFFFFFF),
    error = Color(0xFFEF4444),
    online = Color(0xFF10B981),
    offline = Color(0xFF94A3B8)
)

val DarkAppColors = AppColors(
    isDark = true,
    background = Color(0xFF0B0F19), // Deep Obsidian Slate
    surface = Color(0xFF111827), // Sleek Dark Surface
    surfaceVariant = Color(0xFF1F2937),
    surfaceElevated = Color(0xFF1E293B),
    cardBackground = Color(0xFF111827),
    dialogBackground = Color(0xFF161F30),
    tabBackground = Color(0xFF0E1424),
    textPrimary = Color(0xFFF8FAFC),
    textSecondary = Color(0xFF94A3B8),
    textDisabled = Color(0xFF64748B),
    border = Color(0xFF1E293B),
    borderLight = Color(0xFF172235),
    borderFocused = Color(0xFF38BDF8),
    primary = Color(0xFF3B82F6), // Electric Cobalt
    primaryLight = Color(0xFF60A5FA),
    primaryDark = Color(0xFF1D4ED8),
    primaryContainer = Color(0xFF1E293B),
    onPrimaryContainer = Color(0xFF93C5FD),
    bubbleSender = Color(0xFF2563EB), // Vibrant Cobalt outgoing bubble
    bubbleSenderBorder = Color(0xFF1D4ED8),
    bubbleSenderText = Color(0xFFFFFFFF),
    bubbleReceiver = Color(0xFF1E293B), // Refined Slate card
    bubbleReceiverBorder = Color(0xFF334155),
    bubbleReceiverText = Color(0xFFF8FAFC),
    chatBackground = Color(0xFF080C14),
    topBarBackground = Color(0xFF0E1424),
    error = Color(0xFFF87171),
    online = Color(0xFF10B981),
    offline = Color(0xFF64748B)
)

val LocalAppColors = staticCompositionLocalOf { LightAppColors }

object AppTheme {
    val colors: AppColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAppColors.current
}

private val LightColorScheme = lightColorScheme(
    primary = LightAppColors.primary,
    onPrimary = Color.White,
    primaryContainer = LightAppColors.primaryContainer,
    onPrimaryContainer = LightAppColors.onPrimaryContainer,
    secondary = LightAppColors.primaryLight,
    onSecondary = Color.White,
    background = LightAppColors.background,
    onBackground = LightAppColors.textPrimary,
    surface = LightAppColors.surface,
    onSurface = LightAppColors.textPrimary,
    surfaceVariant = LightAppColors.surfaceVariant,
    onSurfaceVariant = LightAppColors.textSecondary,
    outline = LightAppColors.border
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkAppColors.primary,
    onPrimary = Color.Black,
    primaryContainer = DarkAppColors.primaryContainer,
    onPrimaryContainer = DarkAppColors.onPrimaryContainer,
    secondary = DarkAppColors.primaryLight,
    onSecondary = Color.Black,
    background = DarkAppColors.background,
    onBackground = DarkAppColors.textPrimary,
    surface = DarkAppColors.surface,
    onSurface = DarkAppColors.textPrimary,
    surfaceVariant = DarkAppColors.surfaceVariant,
    onSurfaceVariant = DarkAppColors.textSecondary,
    outline = DarkAppColors.border
)

@Composable
fun LanChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val appColors = if (darkTheme) DarkAppColors else LightAppColors
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    CompositionLocalProvider(LocalAppColors provides appColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
