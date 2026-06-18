package com.apk.claw.android.ui.compose.theme

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp

/**
 * 全局配色 —— 与 XML colors.xml 保持视觉一致的单一调色板。
 *
 * 设计决策：
 * - 颜色值与 `values/colors.xml` + `values-night/colors.xml` 对齐（消除原蓝紫分裂）。
 * - 通过 [isLight] 按主题动态计算，[isLight] 是 Compose State，切换时触发重组。
 * - 顶层属性别名（如 `val FMuted get() = OctopusColors.TextMuted`）可正常工作，
 *   因为各字段是普通 getter（非 @Composable），适合在非 Composable 上下文引用。
 *
 * 注意：各处别名必须写成 `get() = OctopusColors.X`（而非 `= OctopusColors.X`），
 * 否则会在类加载时被定格成一种配色，主题切换不生效。
 */
object OctopusColors {
    private val _isLight = mutableStateOf(false)
    var isLight: Boolean
        get() = _isLight.value
        set(value) { _isLight.value = value }

    // ── Brand（与 XML colorBrand* 对齐：紫色 #5856D6 / 暗色 #8A88F4）──
    val Primary: Color get() = if (isLight) Color(0xFF5856D6) else Color(0xFF8A88F4)
    val OnPrimary: Color get() = Color.White
    val PrimaryContainer: Color get() = if (isLight) Color(0xFFF7F7FF) else Color(0xFF292933)
    val OnPrimaryContainer: Color get() = if (isLight) Color(0xFF2F2D84) else Color(0xFFE5E4FF)
    val PrimaryVariant: Color get() = if (isLight) Color(0xFF7E7DB2) else Color(0xFF53538A)

    /** 辅色（保留原 Accent 语义，映射到 Success 系列作为强调色） */
    val Accent: Color get() = if (isLight) Color(0xFF2BA471) else Color(0xFF56C08D)

    // ── Semantic（与 XML colorError/Warning/Success/Info 对齐）──
    val Success: Color get() = if (isLight) Color(0xFF2BA471) else Color(0xFF56C08D)
    val Warning: Color get() = if (isLight) Color(0xFFE37318) else Color(0xFFFA9550)
    val Error: Color get() = if (isLight) Color(0xFFF6685D) else Color(0xFFFF9285)
    val Info: Color get() = if (isLight) Color(0xFF1AAFFF) else Color(0xFF009CF0)

    // ── Backgrounds（与 XML colorBg* / colorContainer* 对齐）──
    val Background: Color get() = if (isLight) Color(0xFFFFFFFF) else Color(0xFF0E0E0E)
    val BackgroundSecondary: Color get() = if (isLight) Color(0xFFF2F2F7) else Color(0xFF1D1D1F)
    val Surface: Color get() = if (isLight) Color(0xFFFFFFFF) else Color(0xFF363638)
    val SurfaceVariant: Color get() = if (isLight) Color(0xFFF2F2F7) else Color(0xFF2C2C2E)
    val SurfaceDeep: Color get() = if (isLight) Color(0xFFF8F8FC) else Color(0xFF202021)
    val SurfaceHigh: Color get() = if (isLight) Color(0xFFE0E0EA) else Color(0xFF333335)
    val SurfaceHighest: Color get() = if (isLight) Color(0xFFD1D1D6) else Color(0xFF3A3A3C)

    // ── Borders（与 XML colorBorder* 对齐）──
    val Border: Color get() = if (isLight) Color(0x0F000000) else Color(0x0DFFFFFF)
    val BorderLow: Color get() = if (isLight) Color(0x0A000000) else Color(0x05FFFFFF)
    val BorderHigh: Color get() = if (isLight) Color(0x1F000000) else Color(0xFF383838)

    // ── Text（与 XML colorText* 对齐）──
    val TextPrimary: Color get() = if (isLight) Color(0xF5000000) else Color(0xEBFFFFFF)
    val TextSecondary: Color get() = if (isLight) Color(0xAD000000) else Color(0xA3FFFFFF)
    val TextTertiary: Color get() = if (isLight) Color(0x61000000) else Color(0x52FFFFFF)
    val TextDisabled: Color get() = if (isLight) Color(0x33000000) else Color(0x33FFFFFF)
    val TextInverse: Color get() = if (isLight) Color(0xFFFFFFFF) else Color(0xFF000000)

    /** 向后兼容别名：TextMuted = TextTertiary（原 OctopusColors.TextMuted 语义） */
    val TextMuted: Color get() = TextTertiary

    // ── Overlays（与 XML colorOverlay* 对齐）──
    /** 半透明遮罩（用于抽屉/弹窗背景） */
    val OverlayDim: Color get() = if (isLight) Color(0x80000000) else Color(0xAD000000)
    val OverlayScrim: Color get() = if (isLight) Color(0x33000000) else Color(0x52000000)

    /** 各 Activity 的 window.statusBarColor 用它，随主题自适应。 */
    val statusBarArgb: Int get() = Background.toArgb()
}

/**
 * 装饰性强调色（用于工具卡片图标背景、标签等）。
 *
 * 这些颜色是"品牌延伸色"，不随主题切换（在亮/暗模式下保持一致），
 * 因为它们用于装饰性场景，对比度由周围的 Surface 背景保证。
 */
object OctopusTints {
    val Skill = Color(0xFF74A7FF)      // 技能 - 蓝
    val Plugin = Color(0xFFEAB85C)     // 插件 - 黄
    val Routine = Color(0xFFFF9F5A)    // 例程 - 橙
    val Cloud = Color(0xFF42C893)      // 云盘 - 绿
    val Memory = Color(0xFF9B8CFF)     // 记忆 - 紫
    val Video = Color(0xFFFF6B6B)      // 视频 - 红
    val Window = Color(0xFF5AA0FF)     // 多窗口 - 天蓝
    val Evolve = Color(0xFF34C7A8)     // 进化 - 青绿
    val Trust = Color(0xFF8FD8B1)      // 信任 - 浅绿
    val Browser = Color(0xFF5DBCD8)    // 浏览器 - 浅青

    /** 热门标签红色（FeatureHubScreen 重复使用 3 次） */
    val Hot = Color(0xFFFF6B6B)
}

/**
 * 统一的 Shape token（合并原 OctopusShape 与 OctopusShapes 双轨）。
 *
 * 数值与 XML ShapeAppearance 对齐：
 * - small  = 8dp（小控件：Chip、Tag）
 * - medium = 12dp（中控件：按钮、输入框）
 * - large  = 16dp（大控件：卡片）
 * - xl     = 20dp（面板、底部弹窗）
 * - capsule = 50%（胶囊）
 *
 * 注意：原 OctopusShape（裸 Dp）已废弃，请使用本对象的 RoundedCornerShape 字段。
 */
object OctopusShape {
    val small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    val medium = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    val large = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    val xl = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
    val capsule = androidx.compose.foundation.shape.RoundedCornerShape(50)

    /** 向后兼容：原 OctopusShape.Card/Panel/Control 的 Dp 值 */
    val Card = 16.dp
    val Panel = 20.dp
    val Control = 12.dp
}
