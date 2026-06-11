package com.apk.claw.android.ui.compose

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.apk.claw.android.R

/**
 * 应用底部导航栏 4 个 Tab 的路由定义。
 * 使用 sealed class 是为了在编译期保证每个 screen 都有 route/labelRes/icon 三要素。
 * labelRes 为字符串资源 ID，渲染时用 stringResource 解析，以支持多语言。
 */
sealed class Screen(val route: String, @StringRes val labelRes: Int, val icon: ImageVector) {
    data object Discover : Screen("discover", R.string.nav_discover, Icons.Filled.Explore)
    data object Chat : Screen("chat", R.string.nav_chat, Icons.Filled.Chat)
    data object Device : Screen("device", R.string.nav_device, Icons.Filled.Devices)
    data object Settings : Screen("settings", R.string.nav_settings, Icons.Filled.Settings)

    companion object {
        /** 底部 Tab 顺序：发现 / 对话 / 设备 / 设置 */
        val bottomBar: List<Screen> = listOf(Discover, Chat, Device, Settings)
    }
}
