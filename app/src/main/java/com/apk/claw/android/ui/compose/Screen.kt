package com.apk.claw.android.ui.compose

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.ui.graphics.vector.ImageVector
import com.apk.claw.android.R

/**
 * 应用底部导航栏 4 个 Tab 的路由定义。
 * 使用 sealed class 是为了在编译期保证每个 screen 都有 route/labelRes/icon 三要素。
 * labelRes 为字符串资源 ID，渲染时用 stringResource 解析，以支持多语言。
 */
sealed class Screen(val route: String, @StringRes val labelRes: Int, val icon: ImageVector) {
    data object Discover : Screen("discover", R.string.nav_browser, Icons.Filled.Search)
    data object Chat : Screen("chat", R.string.nav_chat, Icons.Filled.Chat)
    data object Device : Screen("device", R.string.nav_device, Icons.Filled.Devices)
    data object Features : Screen("features", R.string.nav_features, Icons.Filled.GridView)
    data object Settings : Screen("settings", R.string.nav_settings, Icons.Filled.Settings)
    data object AgentSquare : Screen("agent_square", R.string.nav_agent_square, Icons.Filled.SmartToy)
    data object Universe : Screen("universe", R.string.nav_universe, Icons.Filled.AutoAwesome)
    // 技能商城:广场 → 能力 的嵌套页(非底部 Tab)
    data object SkillMarketplace : Screen("skill_marketplace", R.string.skill_marketplace_title, Icons.Filled.GridView)
    // 插件商城:广场 → 插件(非底部 Tab)
    data object PluginMarketplace : Screen("plugin_marketplace", R.string.plugin_marketplace_title, Icons.Filled.GridView)

    companion object {
        /** 底部 Tab：对话优先。设备不再独立成页——设备发现/选择已并入对话目标选择器，
         *  局域网控制与权限并入信任中心。「功能」汇集技能/插件/云盘等子页入口。 */
        val bottomBar: List<Screen> = listOf(Chat, Discover, Features, Settings)
    }
}
