package com.apk.claw.android.ui.compose

import android.content.Intent
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalContext
import com.apk.claw.android.ui.browser.BrowserActivity
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.apk.claw.android.ui.compose.screen.ChatScreen
import com.apk.claw.android.ui.compose.screen.DiscoverScreen
import com.apk.claw.android.ui.compose.screen.FeatureHubScreen
import com.apk.claw.android.ui.compose.screen.SettingsScreen
import com.apk.claw.android.ui.compose.theme.OctopusColors
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
    val context = LocalContext.current
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
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            CompactBottomBar(
                currentRoute = currentDestination?.route,
                onSelect = { screen ->
                    if (screen == Screen.Discover) {
                        // 「浏览器」Tab 直接打开内置浏览器本体(已有桌面式首页),不再走旧的 DiscoverScreen 落地页
                        runCatching { context.startActivity(Intent(context, BrowserActivity::class.java)) }
                    } else {
                        navController.navigate(screen.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
            )
        }
    ) { innerPadding ->
        OctopusNavHost(
            navController = navController,
            showMessage = showMessage,
            modifier = Modifier.padding(innerPadding)
        )
    }
}

@Composable
private fun CompactBottomBar(
    currentRoute: String?,
    onSelect: (Screen) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        color = OctopusColors.Surface.copy(alpha = 0.98f),
        border = BorderStroke(1.dp, OctopusColors.Border.copy(alpha = 0.65f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 16.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Screen.bottomBar.forEach { screen ->
                val selected = currentRoute == screen.route
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .clickable { onSelect(screen) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 52.dp, height = 28.dp)
                            .background(
                                if (selected) OctopusColors.Primary.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent,
                                RoundedCornerShape(14.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            screen.icon,
                            contentDescription = stringResource(screen.labelRes),
                            tint = if (selected) OctopusColors.Primary else OctopusColors.TextMuted,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                    Text(
                        stringResource(screen.labelRes),
                        color = if (selected) OctopusColors.Primary else OctopusColors.TextMuted,
                        fontSize = 10.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
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
        // 对话即首页：落地直接进入对话主界面
        startDestination = Screen.Chat.route,
        modifier = modifier,
        // Tab 切换的淡入淡出 + 轻微放大「fade-through」过渡
        enterTransition = { fadeIn(tween(220)) + scaleIn(initialScale = 0.96f, animationSpec = tween(220)) },
        exitTransition = { fadeOut(tween(160)) },
        popEnterTransition = { fadeIn(tween(220)) + scaleIn(initialScale = 0.96f, animationSpec = tween(220)) },
        popExitTransition = { fadeOut(tween(160)) },
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
        composable(Screen.Features.route) { FeatureHubScreen() }
        composable(Screen.Settings.route) { SettingsScreen(onMessage = showMessage) }
    }
}
