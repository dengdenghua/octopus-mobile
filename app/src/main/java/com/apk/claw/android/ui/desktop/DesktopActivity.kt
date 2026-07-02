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

    // 对话展开/收起:收起时对话面板宽度动画到 0(仍在组合中,不丢上下文/不打断运行中的任务),
    // 桌面占满;右下角出现悬浮球,点开恢复。
    var chatExpanded by rememberSaveable { mutableStateOf(true) }
    // OpenRoom 风:对话面板 = avatarSide(Zero 立绘)+ chatSide,需更宽
    val chatWidth by animateDpAsState(if (chatExpanded) 430.dp else 0.dp, label = "chatWidth")

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
    val webLive = currentUrl.isNotBlank() && currentUrl != "about:blank"

    // 全屏科幻壁纸打底,面板悬浮其上(带留白 = 全息漂浮感)
    Box(Modifier.fillMaxSize()) {
        HoloBackground(Modifier.fillMaxSize())
        Row(Modifier.fillMaxSize().padding(10.dp)) {
            // 左:玻璃「直播间」—— 顶部 HUD 条 + 内容区(浏览器/发现/广场)+ Dock
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().holoGlass(16.dp)) {
                    HudStrip(
                        urlOrIdle = if (webLive) currentUrl else "本地虚拟电脑 · 待命",
                        live = loading || webLive,
                        connLabel = connLabel,
                        connColor = connColor,
                        timeText = hudClock,
                    )
                    Box(Modifier.weight(1f).clipToBounds()) {
                        // 底板:浏览器桌面(空闲显角色档案 HUD)
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
                    DesktopDock(
                        active = DeskContent.Web,
                        onSelect = { kind ->
                            when (kind) {
                                DeskContent.Discover -> openWindow(WinContent.Discover)
                                DeskContent.Square -> openWindow(WinContent.Square)
                                else -> {}
                            }
                        },
                        onLaunchMiniApp = { id -> MiniAppRegistry.get(id)?.let { openWindow(WinContent.Mini(id, it.name)) } },
                        onAllApps = { runCatching { ctx.startActivity(android.content.Intent(ctx, MiniAppListActivity::class.java)) } },
                    )
                }
            }
            if (chatWidth > 0.dp) Spacer(Modifier.width(10.dp))
            // 右:悬浮玻璃对话面板(透过面板边缘可见壁纸)
            Box(Modifier.width(chatWidth).fillMaxHeight().clipToBounds()) {
                if (chatWidth > 0.dp) {
                    // OpenRoom ChatPanel:两栏 avatarSide(角色立绘)| chatSide(header+对话)
                    Row(Modifier.fillMaxSize().holoGlass(12.dp)) {
                        // avatarSide:更深底 + 当前角色全息立绘常驻
                        Box(Modifier.width(104.dp).fillMaxHeight().background(Holo.AvatarBg)) {
                            HoloFigure(
                                CharacterRegistry.current.frontRes,
                                Modifier.align(Alignment.BottomCenter)
                                    .fillMaxHeight(0.96f)
                                    .aspectRatio(0.46f, matchHeightConstraintsFirst = true),
                            )
                            Text(
                                CharacterRegistry.current.zh, color = Holo.Accent, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 6.dp),
                            )
                        }
                        // chatSide:header(角色名 › 点击切角色 + 收起)+ ChatScreen
                        Column(Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().height(34.dp).padding(start = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${CharacterRegistry.current.name} ›", color = Holo.Accent, fontSize = 12.sp,
                                    modifier = Modifier.weight(1f).clickable { CharacterRegistry.next() },
                                )
                                IconButton(onClick = { chatExpanded = false }, modifier = Modifier.size(30.dp)) {
                                    Icon(Icons.Filled.ChevronRight, contentDescription = "收起对话", tint = Holo.TextSecondary, modifier = Modifier.size(18.dp))
                                }
                            }
                            Box(Modifier.weight(1f)) { ChatScreen() }
                        }
                    }
                }
            }
        }
        // 收起态:右下角 Zero 头像悬浮球(青色霓虹环),点开展开对话
        if (!chatExpanded) {
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

/** 桌面左侧内容区可展示的东西(Dock 分类用)。 */
private enum class DeskContent { Web, Discover, Square }

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

/**
 * 桌面底部 Dock(macOS 风):浏览器 / 发现 / 广场 + 已安装小程序 + 全部小程序。
 * 浏览器/发现/广场在桌面内容区内切换;小程序点击启动([MiniAppActivity])。
 */
@Composable
private fun DesktopDock(
    active: DeskContent,
    onSelect: (DeskContent) -> Unit,
    onLaunchMiniApp: (String) -> Unit,
    onAllApps: () -> Unit,
) {
    val miniApps = remember { MiniAppRegistry.all() }
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(Holo.Glass.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DockItem(Icons.Filled.Language, "浏览器", active == DeskContent.Web) { onSelect(DeskContent.Web) }
        DockItem(Icons.Filled.Explore, "发现", active == DeskContent.Discover) { onSelect(DeskContent.Discover) }
        DockItem(Icons.Filled.Forum, "广场", active == DeskContent.Square) { onSelect(DeskContent.Square) }
        if (miniApps.isNotEmpty()) {
            Box(Modifier.size(width = 1.dp, height = 26.dp).background(Holo.BorderDim))
        }
        miniApps.take(8).forEach { m ->
            DockItem(Icons.Filled.Apps, m.name.ifBlank { m.id }, false) { onLaunchMiniApp(m.id) }
        }
        DockItem(Icons.Filled.GridView, "全部", false, onAllApps)
    }
}

@Composable
private fun DockItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (active) Holo.Accent else Holo.TextHud.copy(alpha = 0.65f),
            modifier = Modifier.size(22.dp),
        )
        Text(
            label,
            color = if (active) Holo.Accent else Holo.TextHud.copy(alpha = 0.55f),
            fontSize = 9.sp,
            maxLines = 1,
        )
    }
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
