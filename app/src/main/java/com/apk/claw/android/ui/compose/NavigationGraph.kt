package com.apk.claw.android.ui.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.apk.claw.android.ui.compose.screen.ChatScreen
import com.apk.claw.android.ui.compose.screen.DeviceScreen
import com.apk.claw.android.ui.compose.screen.DiscoverScreen
import com.apk.claw.android.ui.compose.screen.SettingsScreen
import kotlinx.coroutines.launch

/**
 * 顶级 Composable：Scaffold 壳 + 底部导航栏 + NavHost。
 * 从 [MainActivity] 拆出，便于：
 *  1. 在 Preview / 测试中独立渲染整张图
 *  2. 后续接入 deep link / 多图导航时只动这一个文件
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OctopusApp() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // 统一的轻量反馈入口：替代分散的 Toast
    val showMessage: (String) -> Unit = { msg ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                tonalElevation = 8.dp,
            ) {
                Screen.bottomBar.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = stringResource(screen.labelRes)) },
                        label = { Text(stringResource(screen.labelRes), style = TextStyle(fontSize = 11.sp)) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        OctopusNavHost(
            navController = navController,
            showMessage = showMessage,
            modifier = Modifier.padding(innerPadding)
        )
    }
}

/**
 * 应用导航图：声明所有 destination 与对应 Composable 的映射。
 * 后续如要加 deep link、嵌套图、动画过渡，只动这里。
 */
@Composable
fun OctopusNavHost(
    navController: androidx.navigation.NavHostController,
    showMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Discover.route,
        modifier = modifier
    ) {
        composable(Screen.Discover.route) {
            DiscoverScreen(onNavigate = { route ->
                navController.navigate(route) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            })
        }
        composable(Screen.Chat.route) { ChatScreen() }
        composable(Screen.Device.route) { DeviceScreen(onMessage = showMessage) }
        composable(Screen.Settings.route) { SettingsScreen(onMessage = showMessage) }
    }
}
