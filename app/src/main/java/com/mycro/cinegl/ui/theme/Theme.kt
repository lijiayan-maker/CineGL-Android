package com.mycro.cinegl.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

object CineColors {
    val Background = Color(0xFF000000)
    val Panel = Color(0xF0111113)
    val Surface = Color(0xFF1A1A1D)
    val SurfaceHigh = Color(0xFF2A2A2E)
    val Accent = Color(0xFFFF8A3D)
    val TextPrimary = Color(0xFFF2F2F2)
    val TextSecondary = Color(0xFF8E8E93)
    val Record = Color(0xFFFF3B30)
    val MeterGreen = Color(0xFF34D399)
    val MeterYellow = Color(0xFFFBBF24)
    val MeterRed = Color(0xFFF87171)
}

/**
 * Material3 默认的 bodyLarge 带 24sp 行高：给 Text 只设 fontSize = 8.sp 时行高仍是 24sp，
 * 小字会被撑得很高（电平表、工具栏都中招）。这里把行高改成「跟随字体」。
 */
private val CineTypography = Typography().let { t ->
    t.copy(
        bodyLarge = t.bodyLarge.copy(lineHeight = TextUnit.Unspecified, letterSpacing = 0.sp),
        bodyMedium = t.bodyMedium.copy(lineHeight = TextUnit.Unspecified, letterSpacing = 0.sp),
        bodySmall = t.bodySmall.copy(lineHeight = TextUnit.Unspecified, letterSpacing = 0.sp),
        labelLarge = t.labelLarge.copy(lineHeight = TextUnit.Unspecified),
        labelMedium = t.labelMedium.copy(lineHeight = TextUnit.Unspecified),
        labelSmall = t.labelSmall.copy(lineHeight = TextUnit.Unspecified),
    )
}

@Composable
fun CineTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        typography = CineTypography,
        colorScheme = darkColorScheme(
            primary = CineColors.Accent,
            onPrimary = Color.Black,
            background = CineColors.Background,
            surface = CineColors.Surface,
            onSurface = CineColors.TextPrimary,
            surfaceVariant = CineColors.SurfaceHigh,
            onSurfaceVariant = CineColors.TextSecondary,
        ),
        content = content,
    )
}
