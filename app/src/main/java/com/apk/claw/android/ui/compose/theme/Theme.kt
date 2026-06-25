package com.apk.claw.android.ui.compose.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * 统一主题入口。
 *
 * 暗色模式策略（统一双轨）：
 * - 默认跟随系统 [isSystemInDarkTheme]。
 * - 用户可在设置页强制切换（写入 KVUtils.isLightTheme），此时覆盖系统值。
 * - 切换后同步更新 [OctopusColors.isLight]，保证所有读取 OctopusColors 的 Composable 重组。
 *
 * 颜色方案：所有 token 来自 [OctopusColors]（即 XML colors.xml 单一真源），
 * 与 View 体系 [com.apk.claw.android.R.style.Theme_OctopusMobile] 完全对齐。
 */
@Composable
fun OctopusTheme(
    content: @Composable () -> Unit
) {
    // 系统暗色模式
    val systemDark = isSystemInDarkTheme()
    // 用户偏好（默认 null 表示跟随系统；true/false 表示强制亮/暗）
    val userOverride = remember { com.apk.claw.android.utils.KVUtils.getThemeMode() }
    val light = when (userOverride) {
        null -> !systemDark           // 跟随系统
        true -> true                  // 强制亮色
        false -> false                // 强制暗色
    }

    // 同步给 OctopusColors，触发所有读取它的 Composable 重组
    SideEffect {
        if (OctopusColors.isLight != light) {
            OctopusColors.isLight = light
        }
        val prefs = com.apk.claw.android.utils.KVUtils
        val blurRadius = prefs.getGlassBlurRadius().dp
        if (OctopusGlass.blurRadius != blurRadius) {
            OctopusGlass.blurRadius = blurRadius
        }
        OctopusGlass.quality = OctopusGlassQuality.fromStorage(prefs.getGlassQuality())
        OctopusGlass.refraction = prefs.getGlassRefraction()
        OctopusGlass.highlight = prefs.getGlassHighlight()
        OctopusGlass.noise = prefs.getGlassNoise()
        OctopusGlass.animationEnabled = prefs.isGlassAnimationEnabled()
    }

    val colorScheme = if (light) lightColorScheme(
        primary = OctopusColors.Primary,
        onPrimary = OctopusColors.OnPrimary,
        primaryContainer = OctopusColors.PrimaryContainer,
        onPrimaryContainer = OctopusColors.OnPrimaryContainer,
        secondary = OctopusColors.Accent,
        tertiary = OctopusColors.Warning,
        background = OctopusColors.Background,
        surface = OctopusColors.Surface,
        surfaceVariant = OctopusColors.SurfaceVariant,
        onBackground = OctopusColors.TextPrimary,
        onSurface = OctopusColors.TextPrimary,
        onSurfaceVariant = OctopusColors.TextSecondary,
        error = OctopusColors.Error,
        outline = OctopusColors.Border,
    ) else darkColorScheme(
        primary = OctopusColors.Primary,
        onPrimary = OctopusColors.OnPrimary,
        primaryContainer = OctopusColors.PrimaryContainer,
        onPrimaryContainer = OctopusColors.OnPrimaryContainer,
        secondary = OctopusColors.Accent,
        tertiary = OctopusColors.Warning,
        background = OctopusColors.Background,
        surface = OctopusColors.Surface,
        surfaceVariant = OctopusColors.SurfaceVariant,
        onBackground = OctopusColors.TextPrimary,
        onSurface = OctopusColors.TextPrimary,
        onSurfaceVariant = OctopusColors.TextSecondary,
        error = OctopusColors.Error,
        outline = OctopusColors.Border,
    )

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
