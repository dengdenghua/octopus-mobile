package com.apk.claw.android.ui.compose.theme

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 全局窗口宽度尺寸等级。
 *
 * - Compact: < 600dp(手机)
 * - Medium: 600-840dp(平板/折叠屏)
 * - Expanded: > 840dp(TV/桌面)
 *
 * 由 [com.apk.claw.android.ui.compose.MainActivity] 在 setContent 中通过
 * `calculateWindowSizeClass(this).widthSizeClass` 计算并 provides。
 */
val LocalWindowWidthSizeClass = staticCompositionLocalOf { WindowWidthSizeClass.Compact }

/**
 * 是否为大屏(TV/平板/桌面):Medium 或 Expanded。
 */
fun WindowWidthSizeClass.isLargeScreen(): Boolean =
    this == WindowWidthSizeClass.Expanded || this == WindowWidthSizeClass.Medium

/**
 * 根据屏宽返回建议的网格列数。
 * - Expanded(TV/桌面) → 4 列
 * - Medium(平板/折叠屏) → 3 列
 * - Compact(手机) → 2 列
 */
fun WindowWidthSizeClass.columnCount(): Int = when (this) {
    WindowWidthSizeClass.Expanded -> 4
    WindowWidthSizeClass.Medium -> 3
    else -> 2
}
