package com.audiocast.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF00D4FF),
    onPrimary = Color(0xFF003544),
    primaryContainer = Color(0xFF004D63),
    onPrimaryContainer = Color(0xFF99ECFF),
    secondary = Color(0xFF89CFF0),
    onSecondary = Color(0xFF003147),
    secondaryContainer = Color(0xFF004A68),
    onSecondaryContainer = Color(0xFFBEE8FF),
    background = Color(0xFF0F0F23),
    onBackground = Color(0xFFE0E0E0),
    surface = Color(0xFF1A1A2E),
    onSurface = Color(0xFFE0E0E0),
    surfaceVariant = Color(0xFF2A2A4A),
    onSurfaceVariant = Color(0xFFBBBBCC),
    error = Color(0xFFFF4444),
    onError = Color(0xFF690005),
)

@Composable
fun AudioCastTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography(),
        content = content
    )
}
