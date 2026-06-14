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
    primary = OctopusColors.Primary,
    onPrimary = Color.White,
    primaryContainer = OctopusColors.SurfaceVariant,
    secondary = OctopusColors.Accent,
    secondaryContainer = OctopusColors.SurfaceDeep,
    tertiary = OctopusColors.Warning,
    background = OctopusColors.Background,
    surface = OctopusColors.Surface,
    surfaceVariant = OctopusColors.SurfaceVariant,
    onBackground = OctopusColors.TextPrimary,
    onSurface = OctopusColors.TextPrimary,
    onSurfaceVariant = OctopusColors.TextMuted,
    error = OctopusColors.Error,
    outline = OctopusColors.Border,
)

@Composable
fun OctopusTheme(
    content: @Composable () -> Unit
) {
    val light = OctopusColors.isLight
    val colorScheme = if (light) lightColorScheme(
        primary = OctopusColors.Primary,
        onPrimary = Color.White,
        primaryContainer = OctopusColors.SurfaceVariant,
        secondary = OctopusColors.Accent,
        secondaryContainer = OctopusColors.SurfaceDeep,
        tertiary = OctopusColors.Warning,
        background = OctopusColors.Background,
        surface = OctopusColors.Surface,
        surfaceVariant = OctopusColors.SurfaceVariant,
        onBackground = OctopusColors.TextPrimary,
        onSurface = OctopusColors.TextPrimary,
        onSurfaceVariant = OctopusColors.TextMuted,
        error = OctopusColors.Error,
        outline = OctopusColors.Border,
    ) else DarkColorScheme
    val view = LocalView.current
    val activity = view.context as? Activity

    SideEffect {
        activity?.window?.let { window ->
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
