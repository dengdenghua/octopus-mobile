package com.apk.claw.android.ui.desktop

import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.apk.claw.android.R
import com.apk.claw.android.appViewModel
import com.apk.claw.android.octopus_mobile.ConnectionState
import com.apk.claw.android.octopus_mobile.browser.BrowserEngine
import com.apk.claw.android.octopus_mobile.browser.BrowserEngineFactory
import com.apk.claw.android.octopus_mobile.browser.EngineEvent
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.plugin.MiniAppRegistry
import com.apk.claw.android.ui.compose.screen.AgentSquareScreen
import com.apk.claw.android.ui.compose.screen.ChatScreen
import com.apk.claw.android.ui.compose.screen.DiscoverScreen
import com.apk.claw.android.ui.featurescreens.MiniAppListActivity
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.ui.compose.theme.OctopusBackground
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 横屏「桌面模式」——「一台 Agent 的电脑」(v1)。
 *
 *  - **左**:一块可预览网页的浏览器桌面(复用 [BrowserEngine]/`SystemWebViewEngine`),并把该引擎
 *    注册为 [ToolRegistry] 的当前引擎 —— Agent 的 `browser_navigate`/`browser_evaluate` 等即作用于
 *    这块 WebView。空闲(无 URL)显示壁纸(时钟 + 母体连接状态)。
 *  - **右**:现有 [ChatScreen] 对话区,在右侧下达指令。
 *
 * v1:接入引擎事件 —— 加载进度条、「正在打开 X」提示、地址栏跟随 Agent 导航;壁纸活起来
 * (实时时钟 + 连接状态)。后续:对话收起为悬浮球、专用设备默认启动/常亮。
 *
 * 锁横屏 + `configChanges` 防旋转 recreate 闪断 WebView(见 AndroidManifest)。
 */
class DesktopActivity : AppCompatActivity() {

    private var engine: BrowserEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 常亮:桌面模式面向支起来/投显示器的场景,前台时不熄屏(离开 Activity 自动解除)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val eng = BrowserEngineFactory.selectBest(this)
        engine = eng
        // 注册为当前浏览器引擎:此后 Agent 的 browser_* 工具作用到桌面这块 WebView。
        // 规矩:前台谁显示 WebView 谁 setBrowserEngine;桌面模式在前台时不再并存 BrowserActivity。
        ToolRegistry.setBrowserEngine(eng)
        setContent {
            OctopusTheme {
                DesktopWorkspace(eng)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // 桌面模式常驻 + KEEP_SCREEN_ON,后台时尤其需要暂停 WebView 的 JS 定时器 / 网络 / 音频。
        engine?.onPause()
    }

    override fun onResume() {
        super.onResume()
        engine?.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        engine?.destroy()
        ToolRegistry.clearBrowserEngine()
        engine = null
    }
}

@Composable
private fun DesktopWorkspace(engine: BrowserEngine) {
    var currentUrl by remember { mutableStateOf(engine.currentUrl()) }
    var pageTitle by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }

    // 引擎事件驱动:用户手动导航或 Agent 导航都会走这里
    LaunchedEffect(engine) {
        engine.events().collect { ev ->
            when (ev) {
                is EngineEvent.PageStarted -> {
                    loading = true; progress = 0
                    // onPageStarted 已把 currentUrlValue 设为新 URL,取来作「正在打开 X」
                    currentUrl = engine.currentUrl()
                }
                is EngineEvent.ProgressChanged -> {
                    progress = ev.percent
                    if (ev.percent in 1..99) loading = true
                    if (ev.percent >= 100) loading = false
                }
                is EngineEvent.PageFinished -> {
                    loading = false; progress = 100
                    currentUrl = ev.url; pageTitle = ev.title
                    // 同步到共享标签 store(竖屏浏览器也看得到)
                    com.apk.claw.android.ui.browser.BrowserTabsStore.updateCurrent(ev.url, ev.title)
                }
                is EngineEvent.Error -> loading = false
                else -> {}
            }
        }
    }

    // 对话展开/收起:收起时对话卡收成右下悬浮球(仍在组合中,不丢上下文/不打断运行中的任务)。
    var chatExpanded by rememberSaveable { mutableStateOf(true) }

    // 桌面:浏览器是常驻底板;发现/广场/mini-app 以可拖浮动窗口打开(移植 OpenRoom windowManager)。
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val windows = remember { androidx.compose.runtime.mutableStateListOf<Pair<Long, WinContent>>() }
    var winSeq by remember { mutableLongStateOf(0L) }
    val openWindow: (WinContent) -> Unit = { kind ->
        val idx = windows.indexOfFirst { it.second == kind }
        if (idx >= 0) { val w = windows.removeAt(idx); windows.add(w) }  // 已开则置顶
        else windows.add((winSeq++) to kind)
    }
    // app_action 未运行时请桌面把 mini-app 开成窗口(后台线程 → 切主线程 openWindow)
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        com.apk.claw.android.plugin.MiniAppWindowController.opener = { appId ->
            val m = MiniAppRegistry.get(appId)
            if (m == null) false else { mainHandler.post { openWindow(WinContent.Mini(appId, m.name)) }; true }
        }
        onDispose { com.apk.claw.android.plugin.MiniAppWindowController.opener = null }
    }

    // HUD 遥测:母体连接态 + 走秒时钟(等宽,科幻直播条用)
    val connState by appViewModel.connectionState.collectAsState()
    val connColor = when (connState) {
        ConnectionState.ONLINE -> Holo.Accent
        ConnectionState.CONNECTING, ConnectionState.CONNECTED,
        ConnectionState.HELLO_SENT, ConnectionState.RECONNECTING -> Color(0xFFFFC24D)
        else -> Holo.AccentDim
    }
    val connLabel = when (connState) {
        ConnectionState.ONLINE -> "LINK OK"
        ConnectionState.CONNECTING, ConnectionState.CONNECTED,
        ConnectionState.HELLO_SENT, ConnectionState.RECONNECTING -> "LINK…"
        else -> "NO LINK"
    }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { nowMs = System.currentTimeMillis(); delay(1000) } }
    val hudClock = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }.format(Date(nowMs))

    // 对话卡最大化:在「上半屏悬浮」与「近满高」之间切换(参考图右上的 □ 按钮)
    var chatMaximized by rememberSaveable { mutableStateOf(false) }

    // 布局(对齐 OpenRoom 参考):全宽顶栏 / 左竖排应用栏 | 中间舞台;对话卡悬浮右上,壁纸打底。
    Box(Modifier.fillMaxSize()) {
        HoloBackground(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize()) {
            DesktopTopBar(
                connLabel = connLabel,
                connColor = connColor,
                timeText = hudClock,
                onGallery = { runCatching { ctx.startActivity(android.content.Intent(ctx, MiniAppListActivity::class.java)) } },
                onSkills = { runCatching { ctx.startActivity(android.content.Intent(ctx, com.apk.claw.android.ui.featurescreens.SkillsActivity::class.java)) } },
            )
            Row(Modifier.weight(1f).fillMaxWidth()) {
                // 左:竖排应用栏(浏览器/发现/广场 + 已装小程序 + 全部)
                LeftAppRail(
                    onDiscover = { openWindow(WinContent.Discover) },
                    onSquare = { openWindow(WinContent.Square) },
                    onLaunchMiniApp = { id -> MiniAppRegistry.get(id)?.let { openWindow(WinContent.Mini(id, it.name)) } },
                    onAllApps = { runCatching { ctx.startActivity(android.content.Intent(ctx, MiniAppListActivity::class.java)) } },
                )
                // 中:玻璃「舞台」—— 浏览器 monitor(空闲显角色档案)+ 浮动窗口层
                Box(Modifier.weight(1f).fillMaxHeight().padding(10.dp)) {
                    Box(Modifier.fillMaxSize().holoGlass(16.dp).clipToBounds()) {
                        DesktopMonitor(
                            engine = engine,
                            currentUrl = currentUrl,
                            pageTitle = pageTitle,
                            loading = loading,
                            progress = progress,
                            onNavigate = { currentUrl = it; pageTitle = "" },
                        )
                        // 浮动窗口层:发现/广场/mini-app,可拖、可缩、可关、点击置顶(末尾在最上)
                        windows.forEachIndexed { i, (id, kind) ->
                            val title = when (kind) {
                                WinContent.Discover -> "发现"
                                WinContent.Square -> "广场"
                                is WinContent.Mini -> kind.name
                            }
                            HoloWindow(
                                title = title,
                                startX = (24 + i * 26).dp,
                                startY = (24 + i * 26).dp,
                                width = 360.dp,
                                height = 320.dp,
                                onClose = { windows.removeAll { it.first == id } },
                                onFocus = {
                                    val idx = windows.indexOfFirst { it.first == id }
                                    if (idx in 0 until windows.size - 1) { val w = windows.removeAt(idx); windows.add(w) }
                                },
                            ) {
                                when (kind) {
                                    WinContent.Discover -> DiscoverScreen(onOpenUrl = { url ->
                                        url?.let { engine.navigate(normalizeUrl(it)) }
                                    })
                                    WinContent.Square -> AgentSquareScreen(onBack = { windows.removeAll { it.first == id } })
                                    is WinContent.Mini -> MiniAppWindow(kind.appId)
                                }
                            }
                        }
                    }
                }
            }
        }
        // 右上悬浮对话卡(透明壁纸感):角色名 + 阶段进度 + 最小/最大化,立绘在卡内。
        if (chatExpanded) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 52.dp, end = 12.dp, bottom = 14.dp)
                    .width(if (chatMaximized) 400.dp else 360.dp)
                    .fillMaxHeight(if (chatMaximized) 0.92f else 0.66f),
            ) {
                DesktopChatCard(
                    maximized = chatMaximized,
                    onToggleMax = { chatMaximized = !chatMaximized },
                    onMinimize = { chatExpanded = false },
                )
            }
        } else {
            // 收起态:右下角当前角色头像悬浮球(黄色霓虹环),点开展开对话
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Holo.Glass)
                    .border(2.dp, Holo.Accent, CircleShape)
                    .clickable { chatExpanded = true },
                contentAlignment = Alignment.Center,
            ) {
                CharacterAvatar(56.dp)
            }
        }
    }
}

/**
 * 右上悬浮对话卡(对齐 OpenRoom):header(角色名 › 切角色 + 阶段进度 + 最小/最大化)+
 * 主体两栏(左角色立绘 | 右 [ChatScreen] 完整对话,含输入与快捷建议)。
 */
@Composable
private fun DesktopChatCard(maximized: Boolean, onToggleMax: () -> Unit, onMinimize: () -> Unit) {
    Column(Modifier.fillMaxSize().holoGlass(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(40.dp).background(Holo.Surface2)
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${CharacterRegistry.current.name} ›", color = Holo.Accent, fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { CharacterRegistry.next() },
            )
            Spacer(Modifier.weight(1f))
            PhaseDots(current = 1, total = 4)
            Spacer(Modifier.width(6.dp))
            // 最小化(收起为悬浮球)
            Box(Modifier.size(30.dp).clickable(onClick = onMinimize), contentAlignment = Alignment.Center) {
                Text("—", color = Holo.TextSecondary, fontSize = 16.sp)
            }
            // 最大化 / 还原
            Box(Modifier.size(30.dp).clickable(onClick = onToggleMax), contentAlignment = Alignment.Center) {
                Text(if (maximized) "▢" else "□", color = Holo.TextSecondary, fontSize = 15.sp)
            }
        }
        Row(Modifier.weight(1f)) {
            // 左:当前角色全息立绘常驻(更深底)
            Box(Modifier.width(96.dp).fillMaxHeight().background(Holo.AvatarBg)) {
                HoloFigure(
                    CharacterRegistry.current.frontRes,
                    Modifier.align(Alignment.BottomCenter)
                        .fillMaxHeight(0.96f)
                        .aspectRatio(0.46f, matchHeightConstraintsFirst = true),
                )
                Text(
                    CharacterRegistry.current.zh, color = Holo.Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 6.dp),
                )
            }
            // 右:完整对话(自带输入 + 快捷建议)
            Box(Modifier.weight(1f)) { ChatScreen() }
        }
    }
}

/** 阶段进度点(参考图右上「阶段 1/4」):首点强调,其余暗;纯视觉章节指示。 */
@Composable
private fun PhaseDots(current: Int, total: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("阶段 $current/$total", color = Holo.TextSecondary, fontSize = 9.sp)
        Spacer(Modifier.width(3.dp))
        repeat(total) { i ->
            Box(
                Modifier.size(width = 12.dp, height = 3.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (i < current) Holo.Accent else Holo.BorderStrong),
            )
        }
    }
}

/**
 * 顶栏(全宽,对齐 OpenRoom):左 logo +「本地虚拟电脑」,右 模组画廊 / 技能 + 连接态 + 时钟。
 */
@Composable
private fun DesktopTopBar(
    connLabel: String,
    connColor: Color,
    timeText: String,
    onGallery: () -> Unit,
    onSkills: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(44.dp)
            .background(Holo.Panel.copy(alpha = 0.9f))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.DesktopWindows, contentDescription = null, tint = Holo.Accent, modifier = Modifier.size(20.dp))
        Text("本地虚拟电脑", color = Holo.TextHud, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        TopBarAction("模组画廊", onGallery)
        TopBarAction("技能", onSkills)
        Spacer(Modifier.width(4.dp))
        HoloDot(connColor)
        Text(connLabel, color = Holo.TextHud, fontSize = 10.sp)
        Text(timeText, color = Holo.Accent, fontSize = 10.sp)
    }
}

@Composable
private fun TopBarAction(label: String, onClick: () -> Unit) {
    Text(
        label, color = Holo.TextSecondary, fontSize = 12.sp,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/**
 * 左侧竖排应用栏(对齐 OpenRoom 左侧图标栏):浏览器 / 发现 / 广场 + 已装小程序 + 全部。
 * 浏览器是常驻底板(点它仅高亮);发现/广场/小程序以浮动窗口打开。
 */
@Composable
private fun LeftAppRail(
    onDiscover: () -> Unit,
    onSquare: () -> Unit,
    onLaunchMiniApp: (String) -> Unit,
    onAllApps: () -> Unit,
) {
    val miniApps = remember { MiniAppRegistry.all() }
    Column(
        modifier = Modifier.width(78.dp).fillMaxHeight()
            .background(Holo.Panel.copy(alpha = 0.6f))
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RailItem(Icons.Filled.Language, "浏览器", active = true) {}
        RailItem(Icons.Filled.Explore, "发现", active = false, onClick = onDiscover)
        RailItem(Icons.Filled.Forum, "广场", active = false, onClick = onSquare)
        if (miniApps.isNotEmpty()) {
            Box(Modifier.padding(vertical = 2.dp).size(width = 40.dp, height = 1.dp).background(Holo.BorderDim))
        }
        miniApps.take(10).forEach { m ->
            RailItem(Icons.Filled.Apps, m.name.ifBlank { m.id }, active = false) { onLaunchMiniApp(m.id) }
        }
        RailItem(Icons.Filled.GridView, "全部", active = false, onClick = onAllApps)
    }
}

@Composable
private fun RailItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) Holo.Surface2 else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(11.dp))
                .background(if (active) Holo.Accent.copy(alpha = 0.14f) else Holo.AvatarBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, contentDescription = label,
                tint = if (active) Holo.Accent else Holo.TextHud.copy(alpha = 0.7f),
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            label.take(4),
            color = if (active) Holo.Accent else Holo.TextHud.copy(alpha = 0.6f),
            fontSize = 9.sp, maxLines = 1,
        )
    }
}

@Composable
private fun DesktopMonitor(
    engine: BrowserEngine,
    currentUrl: String,
    pageTitle: String,
    loading: Boolean,
    progress: Int,
    onNavigate: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        DesktopTabStrip(engine, onNavigate)
        DesktopAddressBar(currentUrl, pageTitle, loading) { url ->
            engine.navigate(url)
            onNavigate(url)
        }
        // 「正在打开 X」+ 进度条:仅加载时显示
        if (loading) {
            Text(
                stringResource(R.string.desktop_opening_host, hostOf(currentUrl)),
                color = Holo.Accent, fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().background(Holo.Panel)
                    .padding(horizontal = 12.dp, vertical = 2.dp),
            )
            LinearProgressIndicator(
                progress = { (progress.coerceIn(0, 100)) / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = Holo.Accent,
                trackColor = Color.Transparent,
            )
        }
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx -> engine.createView(ctx) },
                modifier = Modifier.fillMaxSize(),
            )
            val idle = currentUrl.isBlank() || currentUrl == "about:blank"
            // 空闲 = 全息角色档案面板(信息卡 + 技能/插件配置 + Zero 三视图立绘)
            if (idle) CharacterHud(Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun DesktopAddressBar(currentUrl: String, pageTitle: String, loading: Boolean, onGo: (String) -> Unit) {
    var text by remember(currentUrl) { mutableStateOf(currentUrl) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Holo.Panel)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 状态点:加载中黄,空闲/完成灰
        Dot(if (loading) Holo.Accent else Holo.TextSecondary)
        Spacer(Modifier.width(8.dp))
        TextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            placeholder = { Text(pageTitle.ifBlank { stringResource(R.string.desktop_address_placeholder) }, fontSize = 13.sp, maxLines = 1, color = Holo.TextSecondary) },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Holo.TextHud),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = {
                val u = normalizeUrl(text)
                if (u.isNotBlank()) onGo(u)
            }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Holo.Surface2,
                unfocusedContainerColor = Holo.Surface2,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.weight(1f),
        )
        // 收藏 ★/☆(数据走共享 BookmarkManager,竖屏浏览器同一份)
        val isPage = currentUrl.isNotBlank() && currentUrl != "about:blank"
        if (isPage) {
            var marked by remember(currentUrl) { mutableStateOf(com.apk.claw.android.ui.browser.BookmarkManager.isBookmarked(currentUrl)) }
            Spacer(Modifier.width(6.dp))
            Text(
                if (marked) "★" else "☆",
                color = if (marked) Holo.Accent else Holo.TextSecondary,
                fontSize = 16.sp,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable {
                        if (marked) com.apk.claw.android.ui.browser.BookmarkManager.remove(currentUrl)
                        else com.apk.claw.android.ui.browser.BookmarkManager.add(currentUrl, pageTitle.ifBlank { currentUrl })
                        marked = !marked
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** 桌面浏览器标签条 —— 读共享 [com.apk.claw.android.ui.browser.BrowserTabsStore],与竖屏浏览器同一组标签。 */
@Composable
private fun DesktopTabStrip(engine: BrowserEngine, onNavigate: (String) -> Unit) {
    val tabsStore = com.apk.claw.android.ui.browser.BrowserTabsStore
    val tabs by tabsStore.tabs.collectAsState()
    val curId by tabsStore.currentId.collectAsState()
    val newTabLabel = stringResource(R.string.browser_new_tab)
    LaunchedEffect(Unit) { tabsStore.ensureAtLeastOne(newTabLabel) }
    if (tabs.size <= 1) return  // 单标签时不占地方

    val goto: (com.apk.claw.android.ui.browser.BrowserTabsStore.Tab?) -> Unit = { t ->
        if (t != null) { tabsStore.select(t.id); engine.navigate(t.url.ifBlank { "about:blank" }); onNavigate(t.url) }
    }
    Row(
        modifier = Modifier.fillMaxWidth().background(Holo.Panel)
            .horizontalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { t ->
            val active = t.id == curId
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (active) Holo.Surface2 else Color.Transparent)
                    .border(1.dp, if (active) Holo.Accent else Holo.Border, RoundedCornerShape(6.dp))
                    .clickable { tabsStore.select(t.id); engine.navigate(t.url.ifBlank { "about:blank" }); onNavigate(t.url) }
                    .padding(start = 8.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    t.title.ifBlank { newTabLabel }.take(16),
                    color = if (active) Holo.TextHud else Holo.TextSecondary,
                    fontSize = 11.sp, maxLines = 1,
                )
                Text(
                    "×", color = Holo.TextSecondary, fontSize = 13.sp,
                    modifier = Modifier.clip(CircleShape).clickable {
                        tabsStore.close(t.id, newTabLabel)
                        if (t.id == curId) goto(tabsStore.current())
                    }.padding(horizontal = 5.dp),
                )
            }
        }
        Text(
            "+", color = Holo.Accent, fontSize = 16.sp,
            modifier = Modifier.clip(CircleShape).clickable {
                tabsStore.newTab(newTabLabel); engine.navigate("about:blank"); onNavigate("")
            }.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun DesktopWallpaper(modifier: Modifier = Modifier) {
    // 实时时钟
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val datePattern = stringResource(R.string.desktop_date_format)
    val dateFmt = remember(datePattern) { SimpleDateFormat(datePattern, Locale.getDefault()) }

    // 母体连接状态
    val connState by appViewModel.connectionState.collectAsState()
    val (connLabel, connColor) = when (connState) {
        ConnectionState.ONLINE -> stringResource(R.string.desktop_conn_online) to OctopusColors.Success
        ConnectionState.CONNECTING, ConnectionState.CONNECTED,
        ConnectionState.HELLO_SENT, ConnectionState.RECONNECTING -> stringResource(R.string.desktop_conn_connecting) to OctopusColors.Warning
        else -> stringResource(R.string.desktop_conn_disconnected) to OctopusColors.TextMuted
    }

    Box(modifier = modifier.background(OctopusBackground.pageBrush()), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(timeFmt.format(Date(nowMs)), color = OctopusColors.TextPrimary, fontSize = 48.sp, fontWeight = FontWeight.Light)
            Text(dateFmt.format(Date(nowMs)), color = OctopusColors.TextMuted, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Dot(connColor)
                Text(connLabel, color = OctopusColors.TextSecondary, fontSize = 12.sp)
            }
            Spacer(Modifier.height(14.dp))
            Icon(Icons.Filled.DesktopWindows, contentDescription = null, tint = OctopusColors.TextMuted, modifier = Modifier.size(40.dp))
            Text(
                stringResource(R.string.desktop_idle_hint),
                color = OctopusColors.TextMuted, fontSize = 12.sp,
            )
            Spacer(Modifier.height(6.dp))
            DefaultLaunchToggle()
        }
    }
}

/** 「启动直达桌面模式」开关(专用设备用):写 KVUtils,SplashActivity 据此在登录后直接进桌面。 */
@Composable
private fun DefaultLaunchToggle() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var pinned by remember { mutableStateOf(KVUtils.isDesktopModeDefault()) }
    val pinnedText = ctx.getString(R.string.desktop_launch_pinned)
    val unpinnedText = ctx.getString(R.string.desktop_launch_pin)
    val pinnedToast = ctx.getString(R.string.desktop_launch_pinned_toast)
    val unpinnedToast = ctx.getString(R.string.desktop_launch_unpinned_toast)
    Text(
        text = if (pinned) pinnedText else unpinnedText,
        color = if (pinned) OctopusColors.Primary else OctopusColors.TextMuted,
        fontSize = 11.sp,
        modifier = Modifier
            .clip(CircleShape)
            .clickable {
                pinned = !pinned
                KVUtils.setDesktopModeDefault(pinned)
                android.widget.Toast.makeText(
                    ctx,
                    if (pinned) pinnedToast else unpinnedToast,
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

/** 桌面浮动窗口的内容类型。 */
private sealed interface WinContent {
    data object Discover : WinContent
    data object Square : WinContent
    data class Mini(val appId: String, val name: String) : WinContent
}

/**
 * mini-app 浮动窗口内容 —— 用共享的 [com.apk.claw.android.plugin.MiniAppHost] 造 WebView(与全屏
 * Activity 同一沙箱),挂载时注册到 [com.apk.claw.android.plugin.MiniAppActionBus](Agent 的 app_action
 * 可派发到本窗口),关闭时注销并销毁 WebView。
 */
@Composable
private fun MiniAppWindow(appId: String) {
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity ?: return
    val manifest = remember(appId) { MiniAppRegistry.get(appId) } ?: return
    androidx.compose.runtime.DisposableEffect(appId) {
        onDispose { com.apk.claw.android.plugin.MiniAppActionBus.unregister(appId) }
    }
    AndroidView(
        factory = {
            val wv = com.apk.claw.android.plugin.MiniAppHost.createWebView(activity, manifest)
            if (wv != null) {
                com.apk.claw.android.plugin.MiniAppActionBus.registerLive(appId, activity, wv)
                wv
            } else {
                android.view.View(activity)
            }
        },
        onRelease = { v -> if (v is android.webkit.WebView) com.apk.claw.android.plugin.MiniAppHost.destroyWebView(v) },
        modifier = Modifier.fillMaxSize(),
    )
}

/** 从 URL 取 host 作「正在打开 X」的 X;取不到就退回原串。 */
private fun hostOf(url: String): String =
    runCatching { Uri.parse(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url

/** 补 scheme:无协议头则默认 https://。 */
private fun normalizeUrl(raw: String): String {
    val t = raw.trim()
    if (t.isEmpty()) return ""
    return when {
        t.startsWith("http://") || t.startsWith("https://") || t.startsWith("about:") -> t
        else -> "https://$t"
    }
}
