package com.apk.claw.android.ui.compose.theme

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp

/**
 * 全局配色。支持「深色 / 明亮」双主题:所有字段都是按 [isLight] 计算的 get(),
 * 这样无论是 @Composable 里直接读 OctopusColors.X,还是各文件里的
 * `val X get() = OctopusColors.X` 别名,切换主题时都会实时重组(isLight 是 Compose State)。
 *
 * 注意:各处别名必须写成 `get() = OctopusColors.X`(而非 `= OctopusColors.X`),
 * 否则会在类加载时被定格成一种配色,主题切换不生效。
 */
object OctopusColors {
    private val _isLight = mutableStateOf(false)
    var isLight: Boolean
        get() = _isLight.value
        set(value) { _isLight.value = value }

    val Primary: Color get() = if (isLight) Color(0xFF2F6FE0) else Color(0xFF74A7FF)
    val Accent: Color get() = if (isLight) Color(0xFF1FA97D) else Color(0xFF8FD8B1)
    val Success: Color get() = if (isLight) Color(0xFF1FA971) else Color(0xFF42C893)
    val Warning: Color get() = if (isLight) Color(0xFFC0820F) else Color(0xFFEAB85C)
    val Error: Color get() = if (isLight) Color(0xFFE0453F) else Color(0xFFFF6B6B)

    val Background: Color get() = if (isLight) Color(0xFFF2F2F7) else Color(0xFF090B10)
    val Surface: Color get() = if (isLight) Color(0xFFFFFFFF) else Color(0xFF12161D)
    val SurfaceVariant: Color get() = if (isLight) Color(0xFFE9E9EF) else Color(0xFF1A2029)
    val SurfaceDeep: Color get() = if (isLight) Color(0xFFF7F7FB) else Color(0xFF0D1117)
    val Border: Color get() = if (isLight) Color(0xFFD8DAE0) else Color(0xFF252B36)

    val TextPrimary: Color get() = if (isLight) Color(0xFF15171C) else Color(0xFFF4F7FB)
    val TextSecondary: Color get() = if (isLight) Color(0xFF4B515E) else Color(0xFFB4BBC7)
    val TextMuted: Color get() = if (isLight) Color(0xFF8A909C) else Color(0xFF778190)

    /** 各 Activity 的 window.statusBarColor 用它,随主题自适应(深色给深底,明亮给浅底)。 */
    val statusBarArgb: Int get() = Background.toArgb()
}

object OctopusShape {
    val Card = 14.dp
    val Panel = 18.dp
    val Control = 12.dp
}
