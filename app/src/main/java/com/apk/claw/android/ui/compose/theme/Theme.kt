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
    // 反应式真源 = OctopusColors.isLight。设置页切换主题时**直接改它**;这里只在「首次组合 / 系统暗色
    // 变化」时,按存储偏好(null=跟随系统)播种一次,**不在每次重组里回写**——否则会把用户刚切到的
    // 深色又冲回去(这正是「深色不生效」的元凶:原来用 remember 记了旧值 + SideEffect 每次回写)。
    LaunchedEffect(systemDark) {
        OctopusColors.isLight = com.apk.claw.android.utils.KVUtils.getThemeMode() ?: !systemDark
    }
    val light = OctopusColors.isLight

    // 风格/玻璃参数:设置页写 KV,这里读 KV 同步到反应式对象(单一真源;读的是最新值,不会回退)。
    SideEffect {
        val prefs = com.apk.claw.android.utils.KVUtils
        OctopusThemeStyle.style = UiStyle.fromStorage(prefs.getUiStyle())
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
        inversePrimary = OctopusColors.PrimaryVariant,
        secondary = OctopusColors.Accent,
        secondaryContainer = OctopusColors.SuccessContainer,
        onSecondaryContainer = OctopusColors.SuccessOnContainer,
        tertiary = OctopusColors.Warning,
        tertiaryContainer = OctopusColors.WarningContainer,
        onTertiaryContainer = OctopusColors.WarningOnContainer,
        background = OctopusColors.Background,
        onBackground = OctopusColors.TextPrimary,
        surface = OctopusColors.Surface,
        surfaceVariant = OctopusColors.SurfaceVariant,
        onSurface = OctopusColors.TextPrimary,
        onSurfaceVariant = OctopusColors.TextSecondary,
        error = OctopusColors.Error,
        onErrorContainer = OctopusColors.ErrorOnContainer,
        errorContainer = OctopusColors.ErrorContainer,
        outline = OctopusColors.Border,
        outlineVariant = OctopusColors.BorderLow,
        inverseSurface = OctopusColors.ContainerInverse,
        inverseOnSurface = OctopusColors.TextInverse,
        scrim = OctopusColors.OverlayDim,
    ) else darkColorScheme(
        primary = OctopusColors.Primary,
        onPrimary = OctopusColors.OnPrimary,
        primaryContainer = OctopusColors.PrimaryContainer,
        onPrimaryContainer = OctopusColors.OnPrimaryContainer,
        inversePrimary = OctopusColors.PrimaryVariant,
        secondary = OctopusColors.Accent,
        secondaryContainer = OctopusColors.SuccessContainer,
        onSecondaryContainer = OctopusColors.SuccessOnContainer,
        tertiary = OctopusColors.Warning,
        tertiaryContainer = OctopusColors.WarningContainer,
        onTertiaryContainer = OctopusColors.WarningOnContainer,
        background = OctopusColors.Background,
        onBackground = OctopusColors.TextPrimary,
        surface = OctopusColors.Surface,
        surfaceVariant = OctopusColors.SurfaceVariant,
        onSurface = OctopusColors.TextPrimary,
        onSurfaceVariant = OctopusColors.TextSecondary,
        error = OctopusColors.Error,
        onErrorContainer = OctopusColors.ErrorOnContainer,
        errorContainer = OctopusColors.ErrorContainer,
        outline = OctopusColors.Border,
        outlineVariant = OctopusColors.BorderLow,
        inverseSurface = OctopusColors.ContainerInverse,
        inverseOnSurface = OctopusColors.TextInverse,
        scrim = OctopusColors.OverlayDim,
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
