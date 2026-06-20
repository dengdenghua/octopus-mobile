package com.apk.claw.android.ui.compose.theme

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    // 提到 WCAG AA(≈4.5:1)：原 0x61/0x52 在白/暗底仅 ~2.7:1，正文级副文本不达标。
    val TextTertiary: Color get() = if (isLight) Color(0x8A000000) else Color(0x8AFFFFFF)
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

    /** 浏览器分类色（DiscoverScreen 分类图标 + 快捷方式） */
    val CatAI = Color(0xFF7C6BFF)
    val CatVideo = Color(0xFFFF6B7A)
    val CatDev = Color(0xFF5B9BFF)
    val CatKnowledge = Color(0xFF30B889)
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

    /** 对话气泡专用：大半圆 + 小尾角 */
    val agentBubble = androidx.compose.foundation.shape.RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    val userBubble = androidx.compose.foundation.shape.RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
}

/**
 * 统一的间距网格（4dp 基准）。
 */
object OctopusSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
}

/**
 * 统一的图标尺寸。
 */
object OctopusIconSize {
    val small = 16.dp
    val medium = 20.dp
    val large = 24.dp
}

/**
 * 顶级 Compose 页面通用布局尺寸。
 *
 * Scaffold 已经把底部导航高度注入 innerPadding，页面只需要再留出少量
 * 视觉呼吸空间；集中在这里，避免各 Tab 各写一套 magic number。
 */
object OctopusLayout {
    val bottomNavContentPadding = 24.dp
    val bottomNavHeight = 64.dp
    val bottomNavItemHeight = 48.dp
    val bottomNavElevation = 14.dp
    val bottomNavBorder = 1.dp
}

/**
 * 全局玻璃背景 token。
 *
 * 浏览器首页先完成了暖色液态玻璃方向，这里把同一套背景/玻璃面板语义提升为
 * App 级别 token，避免一级页面各自散落一套颜色。
 */
object OctopusBackground {
    fun pageBrush(): Brush = if (OctopusColors.isLight) {
        Brush.linearGradient(
            listOf(
                Color(0xFF91A3B7),
                Color(0xFFADA9B8),
                Color(0xFFC0A197),
            )
        )
    } else {
        Brush.linearGradient(
            listOf(
                Color(0xFF17151D),
                Color(0xFF211C24),
                Color(0xFF2B2421),
            )
        )
    }

    val glassSurface: Color
        get() = if (OctopusColors.isLight) Color.White.copy(alpha = 0.82f) else Color(0xFF222225).copy(alpha = 0.72f)

    val glassBorder: Color
        get() = if (OctopusColors.isLight) Color.White.copy(alpha = 0.82f) else Color.White.copy(alpha = 0.14f)
}

/**
 * 统一的字号阶梯（sp）。token 取值与现有 UI 实际字号一一对齐，迁移为纯别名、零视觉变化。
 *
 * - micro      = 9sp （极小徽章，如「已就绪」状态点）
 * - tag        = 10sp（标签、计数）
 * - caption    = 11sp（辅助说明、副标题）
 * - label      = 12sp（次要正文、分区标签）
 * - body       = 13sp（正文）
 * - bodyStrong = 14sp（强调正文）
 * - bodyLg     = 15sp（大正文 / 搜索提示）
 * - title      = 16sp（卡片 / 分区标题）
 * - titleSm    = 17sp（次级标题，如未选中 Tab）
 * - titleLg    = 18sp（页面标题）
 * - headlineSm = 20sp（小号大标题：顶栏品牌名 / 首页标题）
 * - headline   = 22sp（大标题 / Tab 选中态）
 * - display    = 24sp（空状态 Hero 标题）
 */
object OctopusType {
    val micro = 9.sp
    val tag = 10.sp
    val caption = 11.sp
    val label = 12.sp
    val body = 13.sp
    val bodyStrong = 14.sp
    val bodyLg = 15.sp
    val title = 16.sp
    val titleSm = 17.sp
    val titleLg = 18.sp
    val headlineSm = 20.sp
    val headline = 22.sp
    val display = 24.sp
}
