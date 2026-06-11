package com.apk.claw.android.ui.compose.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// 颜色定义 - 深色主题
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF6C5CE7),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E1E36),
    secondary = Color(0xFF00D2A0),
    secondaryContainer = Color(0xFF0A2A20),
    tertiary = Color(0xFFFFC048),
    background = Color(0xFF0A0A0F),
    surface = Color(0xFF12121A),
    surfaceVariant = Color(0xFF1A1A28),
    onBackground = Color(0xFFE8E8F0),
    onSurface = Color(0xFFE8E8F0),
    onSurfaceVariant = Color(0xFF8888A8),
    error = Color(0xFFFF5C72),
    outline = Color(0xFF2A2A40),
)

@Composable
fun OctopusTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme
    val view = LocalView.current
    val activity = view.context as? Activity

    SideEffect {
        activity?.window?.let { window ->
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
