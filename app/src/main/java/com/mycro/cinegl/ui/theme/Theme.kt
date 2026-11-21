package com.mycro.cinegl.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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

@Composable
fun CineTheme(content: @Composable () -> Unit) {
    MaterialTheme(
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
