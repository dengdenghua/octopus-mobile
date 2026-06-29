package com.apk.claw.android.ui.compose

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.apk.claw.android.ui.compose.screen.AgentSquareScreen
import com.apk.claw.android.ui.compose.screen.ChatScreen
import com.apk.claw.android.ui.compose.screen.DiscoverScreen
import com.apk.claw.android.ui.compose.screen.FeatureHubScreen
import com.apk.claw.android.ui.compose.screen.GhostChatSessionStore
import com.apk.claw.android.ui.compose.screen.SettingsScreen
import com.apk.claw.android.ui.compose.screen.UniverseScreen
import com.apk.claw.android.ui.compose.component.LiquidGlassLayer
import com.apk.claw.android.ui.compose.component.rememberGlassPressState
import com.apk.claw.android.ui.compose.theme.OctopusBackground
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusGlass
import com.apk.claw.android.ui.compose.theme.OctopusGlassMaterial
import com.apk.claw.android.ui.compose.theme.OctopusIconSize
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusLayout
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
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
    val selectedBottomRoute = when (currentDestination?.route) {
        Screen.AgentSquare.route -> Screen.Features.route
        Screen.Universe.route -> Screen.Features.route
        else -> currentDestination?.route
    }
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
                currentRoute = selectedBottomRoute,
                onSelect = { screen ->
                    if (selectedBottomRoute == screen.route && currentDestination?.route == screen.route) return@CompactBottomBar
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
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

// 底部 dock 专用颜色别名（跟随主题切换）
private val NavPrimaryColor get() = OctopusColors.Primary
private val NavTextMutedColor get() = OctopusColors.TextMuted
private val NavGlassSurfaceColor get() = OctopusBackground.glassSurface
private val NavGlassBorderColor get() = OctopusBackground.glassBorder

@Composable
private fun CompactBottomBar(
    currentRoute: String?,
    onSelect: (Screen) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(OctopusBackground.pageBrush())
            .navigationBarsPadding()
            .padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = OctopusShape.capsule,
            color = NavGlassSurfaceColor.copy(alpha = if (OctopusColors.isLight) 0.72f else 0.68f),
            border = BorderStroke(OctopusLayout.bottomNavBorder, NavGlassBorderColor.copy(alpha = if (OctopusColors.isLight) 0.82f else 1f)),
            shadowElevation = 10.dp,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(OctopusLayout.bottomNavHeight)
                    .clip(OctopusShape.capsule),
            ) {
                LiquidGlassLayer(
                    shape = OctopusShape.capsule,
                    blurRadius = OctopusGlass.liquidBlurRadius,
                    tint = NavGlassSurfaceColor.copy(alpha = if (OctopusColors.isLight) 0.76f else 0.68f),
                    highlightIntensity = OctopusGlass.highlightIntensity * 1.14f,
                    material = OctopusGlassMaterial.Dock,
                    modifier = Modifier.fillMaxSize(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = OctopusSpacing.sm, vertical = OctopusSpacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Screen.bottomBar.forEach { screen ->
                        val selected = currentRoute == screen.route
                        BottomTabItem(
                            screen = screen,
                            selected = selected,
                            modifier = Modifier
                                .weight(1f)
                                .height(OctopusLayout.bottomNavItemHeight),
                            onClick = { onSelect(screen) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomTabItem(
    screen: Screen,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val press = rememberGlassPressState()
    val label = stringResource(screen.labelRes)
    val contentColor = if (selected) NavPrimaryColor else NavTextMutedColor
    val bgBrush = if (selected) {
        Brush.horizontalGradient(
            listOf(
                NavPrimaryColor.copy(alpha = 0.18f * press.boost),
                Color.White.copy(alpha = (if (OctopusColors.isLight) 0.30f else 0.08f) * press.boost),
                NavPrimaryColor.copy(alpha = 0.10f * press.boost),
            )
        )
    } else {
        Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
    }
    val borderColor = if (selected) Color.White.copy(alpha = if (OctopusColors.isLight) 0.78f else 0.18f) else Color.Transparent
    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = press.scale
                scaleY = press.scale
            }
            .then(press.touchModifier)
            .clip(OctopusShape.capsule)
            .background(bgBrush, OctopusShape.capsule)
            .clickable(
                interactionSource = press.interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = OctopusSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = OctopusShape.capsule,
            color = if (selected) Color.White.copy(alpha = if (OctopusColors.isLight) 0.34f else 0.10f) else Color.Transparent,
            border = if (selected) BorderStroke(OctopusLayout.bottomNavBorder, borderColor) else null,
        ) {
            Icon(
                screen.icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.padding(OctopusSpacing.xs).size(OctopusIconSize.medium),
            )
        }
        if (selected) {
            Text(
                label,
                color = contentColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.padding(start = OctopusSpacing.xs),
            )
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
            DiscoverScreen()
        }
        composable(Screen.Chat.route) { ChatScreen() }
        composable(Screen.Features.route) {
            FeatureHubScreen(
                onNavigateToAgentSquare = { navController.navigate(Screen.AgentSquare.route) },
                onNavigateToUniverse = { navController.navigate(Screen.Universe.route) },
            )
        }
        composable(Screen.AgentSquare.route) {
            AgentSquareScreen(
                onBack = { navController.popBackStack() },
                onOpenSearch = { /* TODO */ },
                onCreatePost = { /* TODO */ },
            )
        }
        composable(Screen.Universe.route) {
            UniverseScreen(
                onBack = { navController.popBackStack() },
                onOpenGhostChat = { feed ->
                    GhostChatSessionStore.openSession(feed)
                    navController.navigate(Screen.Chat.route) {
                        launchSingleTop = true
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                    }
                },
            )
        }
        composable(Screen.Settings.route) { SettingsScreen(onMessage = showMessage) }
    }
}
