package com.apk.claw.android.ui.compose

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 应用底部导航栏 4 个 Tab 的路由定义。
 * 使用 sealed class 是为了在编译期保证每个 screen 都有 route/label/icon 三要素。
 */
sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Discover : Screen("discover", "发现", Icons.Filled.Explore)
    data object Chat : Screen("chat", "对话", Icons.Filled.Chat)
    data object Device : Screen("device", "设备", Icons.Filled.Devices)
    data object Settings : Screen("settings", "设置", Icons.Filled.Settings)

    companion object {
        /** 底部 Tab 顺序：发现 / 对话 / 设备 / 设置 */
        val bottomBar: List<Screen> = listOf(Discover, Chat, Device, Settings)
    }
}
