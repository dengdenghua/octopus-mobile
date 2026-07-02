package com.apk.claw.android.ui.desktop

import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Videocam
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
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

    // 桌面:浏览器是常驻底板;发现/广场/mini-app 以可拖浮动窗口打开(移植 OpenRoom windowManager)。
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val windows = remember { androidx.compose.runtime.mutableStateListOf<Pair<Long, WinContent>>() }
    val minimized = remember { androidx.compose.runtime.mutableStateListOf<Long>() }
    var winSeq by remember { mutableLongStateOf(0L) }
    val openWindow: (WinContent) -> Unit = { kind ->
        val idx = windows.indexOfFirst { it.second == kind }
        if (idx >= 0) {
            minimized.removeAll { it == windows[idx].first }  // 已开:取消最小化 + 置顶
            val w = windows.removeAt(idx); windows.add(w)
        } else windows.add((winSeq++) to kind)
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

    // HUD 遥测:母体连接态(时钟下沉到 TopBar/Taskbar 各自内部,避免全工作区每秒重组)
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

    // 对话:直播间窗 + 对话窗共用同一条主 Agent 会话([ChatAgentBridge])。
    //   convo = 完整会话;直播间字幕 = convo 里最后一条角色发言(流式)。
    val convo = remember { androidx.compose.runtime.mutableStateListOf<DeskMsg>() }
    var running by remember { mutableStateOf(false) }
    var toolNote by remember { mutableStateOf("") }
    var seq by remember { mutableLongStateOf(0L) }
    var likeCount by remember { mutableIntStateOf(119) }
    // 每轮生成一张「角色在场景里」的图,作直播间背景(对齐 OpenRoom 核心差异)。可在顶栏关。
    var sceneUrl by remember { mutableStateOf<String?>(null) }
    var sceneLoading by remember { mutableStateOf(false) }
    var sceneGen by rememberSaveable { mutableStateOf(true) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // 当前角色:统一读取一次,下传给 windowSpec / 子组件,避免散读 CharacterRegistry.current。
    // 不用 remember{} —— current 的 getter 订阅 idx 状态,切角色时自动重组(此时才读)。
    val character = CharacterRegistry.current
    val send: (String) -> Unit = fn@{ raw ->
        val t = raw.trim()
        if (t.isEmpty() || running) return@fn
        convo.add(DeskMsg(seq++, fromUser = true, text = t))
        if (!com.apk.claw.android.ui.compose.screen.ChatAgentBridge.isConfigured()) {
            convo.add(DeskMsg(seq++, fromUser = false, text = "请先配置模型(顶部「技能」旁或设置 → 模型),再和我对话。"))
            return@fn
        }
        // 场景生成:异步、不阻塞对话;失败/未配置则保留上一张(或退回立绘)。
        if (sceneGen) {
            val c = character
            scope.launch {
                sceneLoading = true
                com.apk.claw.android.media.MediaRepository.generateImage(scenePrompt(c, t), "1280x720").fold(
                    onSuccess = { sceneUrl = it.url; sceneLoading = false },
                    onFailure = { sceneLoading = false },
                )
            }
        }
        running = true; toolNote = ""
        val buf = StringBuilder()
        var streamId: Long? = null
        // 回调都 post 到主线程(见 ChatAgentBridge),可直接改 Compose 状态。首个 token 才建角色气泡,
        // 后续按 id 原地更新(流式)。
        val put = { s: String ->
            val id = streamId
            if (id == null) { val m = DeskMsg(seq++, fromUser = false, text = s); streamId = m.id; convo.add(m) }
            else { val i = convo.indexOfFirst { it.id == id }; if (i >= 0) convo[i] = convo[i].copy(text = s) }
        }
        com.apk.claw.android.ui.compose.screen.ChatAgentBridge.run(
            prompt = t,
            onTool = { _, name, _, _ -> toolNote = "· 使用 $name" },
            onText = { tok -> buf.append(tok); put(buf.toString()) },
            onDone = { ans -> put(ans.ifBlank { buf.toString() }); running = false; toolNote = "" },
            onError = { e -> put("⚠️ $e"); running = false; toolNote = "" },
        )
    }
    val stop = { com.apk.claw.android.ui.compose.screen.ChatAgentBridge.cancel(); running = false; toolNote = "" }
    val subtitle = convo.lastOrNull { !it.fromUser }?.text ?: ""
    // 是否在浏览网页:是则中间显玻璃浏览器盒,否则中间就是壁纸 + 浮动窗口
    val browsing = currentUrl.isNotBlank() && currentUrl != "about:blank"

    // 默认开「直播间」「对话」两个平级窗口(对齐 OpenRoom 全景:并列可拖窗口)
    LaunchedEffect(Unit) {
        if (windows.none { it.second == WinContent.LiveRoom }) openWindow(WinContent.LiveRoom)
        if (windows.none { it.second == WinContent.Chat }) openWindow(WinContent.Chat)
    }

    // 布局(对齐 OpenRoom 全景):壁纸打底 + 全宽顶栏 / 左应用栏 | 中间(壁纸 / 浏览器);
    // 直播间/对话/档案等一律为平级可拖浮动窗口,浮于 shell 之上;屏幕底部输入 + 任务栏。
    Box(Modifier.fillMaxSize()) {
        HoloBackground(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize()) {
            DesktopTopBar(
                connLabel = connLabel,
                connColor = connColor,
                sceneOn = sceneGen,
                onToggleScene = { sceneGen = !sceneGen },
                onExit = { (ctx as? android.app.Activity)?.finish() },
            )
            // 中:全宽。空闲=壁纸透出(桌面图标浮其上);浏览网页时=玻璃浏览器盒。
            Box(
                Modifier.weight(1f).fillMaxWidth()
                    .padding(horizontal = if (browsing) 24.dp else 0.dp, vertical = if (browsing) 12.dp else 0.dp),
            ) {
                Box(
                    Modifier.fillMaxSize()
                        .then(if (browsing) Modifier.holoGlass(16.dp) else Modifier)
                        .clipToBounds(),
                ) {
                    DesktopMonitor(
                        engine = engine,
                        currentUrl = currentUrl,
                        pageTitle = pageTitle,
                        loading = loading,
                        progress = progress,
                        browsing = browsing,
                        onNavigate = { currentUrl = it; pageTitle = "" },
                    )
                }
            }
            DesktopReplyBar(running = running, onSend = send, onStop = stop)
            DesktopTaskbar(
                minimized = windows.filter { it.first in minimized },
                charName = character.name,
                onRestore = { id ->
                    minimized.removeAll { it == id }
                    val idx = windows.indexOfFirst { it.first == id }
                    if (idx in 0 until windows.size - 1) { val w = windows.removeAt(idx); windows.add(w) }
                },
                onStart = { runCatching { ctx.startActivity(android.content.Intent(ctx, MiniAppListActivity::class.java)) } },
            )
        }
        // 桌面图标(直接铺在壁纸上,无侧边栏面板)。在窗口层之下 → 窗口可盖住,像真桌面。
        DesktopIcons(
            modifier = Modifier.align(Alignment.TopStart).padding(top = 38.dp, start = 6.dp, bottom = 132.dp),
            onLiveRoom = { openWindow(WinContent.LiveRoom) },
            onChat = { openWindow(WinContent.Chat) },
            onCharacter = { openWindow(WinContent.Character) },
            onDiscover = { openWindow(WinContent.Discover) },
            onSquare = { openWindow(WinContent.Square) },
            onLaunchMiniApp = { id -> MiniAppRegistry.get(id)?.let { openWindow(WinContent.Mini(id, it.name)) } },
            onAllApps = { runCatching { ctx.startActivity(android.content.Intent(ctx, MiniAppListActivity::class.java)) } },
        )
        // 浮动窗口层(浮于 shell + 桌面图标之上,可拖到桌面任意位置)。最小化的不渲染,收进任务栏。
        windows.forEachIndexed { i, (id, kind) ->
            if (id in minimized) return@forEachIndexed
            androidx.compose.runtime.key(id) {
                val spec = windowSpec(kind, i, character.name)
                HoloWindow(
                    title = spec.title,
                    startX = spec.x,
                    startY = spec.y,
                    width = spec.w,
                    height = spec.h,
                    onClose = { windows.removeAll { it.first == id }; minimized.removeAll { it == id } },
                    onMinimize = { if (id !in minimized) minimized.add(id) },
                    onFocus = {
                        val idx = windows.indexOfFirst { it.first == id }
                        if (idx in 0 until windows.size - 1) { val w = windows.removeAt(idx); windows.add(w) }
                    },
                ) {
                    when (kind) {
                        WinContent.LiveRoom -> LiveRoomContent(
                            sceneUrl = sceneUrl, sceneLoading = sceneLoading, sceneOn = sceneGen,
                            subtitle = subtitle, running = running, toolNote = toolNote,
                            likeCount = likeCount, onLike = { likeCount++ },
                        )
                        WinContent.Chat -> ChatContent(convo = convo, running = running, toolNote = toolNote)
                        WinContent.Character -> CharacterHud(Modifier.fillMaxSize())
                        WinContent.Discover -> DiscoverScreen(onOpenUrl = { url -> url?.let { engine.navigate(normalizeUrl(it)) } })
                        WinContent.Square -> AgentSquareScreen(onBack = { windows.removeAll { it.first == id } })
                        is WinContent.Mini -> MiniAppWindow(kind.appId)
                    }
                }
            }
        }
        // 右下角:当前角色头像(点击打开角色档案窗)
        Box(
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 54.dp)
                .size(44.dp).clip(CircleShape).border(2.dp, Holo.Accent, CircleShape)
                .clickable { openWindow(WinContent.Character) },
            contentAlignment = Alignment.Center,
        ) { CharacterAvatar(44.dp) }
    }
}

/** 每类窗口的标题 + 默认位置/尺寸(dp)。 */
private data class WinSpec(val title: String, val x: Dp, val y: Dp, val w: Dp, val h: Dp)

private fun windowSpec(kind: WinContent, i: Int, charName: String): WinSpec {
    // 默认尺寸按「横屏手机」的 dp 空间(约 860×390dp)裁,保证底部输入不被盖;大屏可自行拖拽放大。
    val name = charName
    return when (kind) {
        WinContent.LiveRoom -> WinSpec("直播间 · $name", 92.dp, 40.dp, 380.dp, 246.dp)
        WinContent.Chat -> WinSpec("对话 · $name", 486.dp, 48.dp, 300.dp, 262.dp)
        WinContent.Character -> WinSpec("角色档案 · $name", 150.dp, 56.dp, 430.dp, 264.dp)
        WinContent.Discover -> WinSpec("发现", (110 + i * 24).dp, (44 + i * 24).dp, 320.dp, 236.dp)
        WinContent.Square -> WinSpec("广场", (110 + i * 24).dp, (44 + i * 24).dp, 320.dp, 236.dp)
        is WinContent.Mini -> WinSpec(kind.name, (110 + i * 24).dp, (44 + i * 24).dp, 320.dp, 236.dp)
    }
}

/** 最底 Windows 式任务栏:开始(全部应用)+ 最小化窗口按钮(点击还原)+ 时钟。 */
@Composable
private fun DesktopTaskbar(
    minimized: List<Pair<Long, WinContent>>,
    charName: String,
    onRestore: (Long) -> Unit,
    onStart: () -> Unit,
) {
    // 时钟内部维护,避免上层每秒重组
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { nowMs = System.currentTimeMillis(); delay(1000) } }
    val timeText = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }.format(Date(nowMs))

    Row(
        modifier = Modifier.fillMaxWidth().height(30.dp).background(Holo.Panel.copy(alpha = 0.85f))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onStart),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.GridView, contentDescription = "开始", tint = Holo.Accent.copy(alpha = 0.9f), modifier = Modifier.size(15.dp))
        }
        // 最小化的窗口:任务栏按钮,点击还原
        minimized.forEach { (id, kind) ->
            Text(
                windowSpec(kind, 0, charName).title.substringBefore(" ·").let { if (it.length > 6) it.take(6) else it },
                color = Holo.TextHud, fontSize = 10.sp,
                modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Holo.Surface2)
                    .clickable { onRestore(id) }.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Text(timeText, color = Holo.TextSecondary, fontSize = 10.sp)
    }
}

/**
 * 对话窗内容(HoloWindow 提供边框/标题):左角色立绘常驻 | 右会话气泡列表(顶条:音波 + 阶段)。
 * 会话为主 Agent 同一条 [convo]。
 */
@Composable
private fun ChatContent(convo: List<DeskMsg>, running: Boolean, toolNote: String) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(convo.size, convo.lastOrNull()?.text) {
        if (convo.isNotEmpty()) runCatching { listState.animateScrollToItem(convo.size - 1) }
    }
    Row(Modifier.fillMaxSize()) {
        // 左:当前角色立绘常驻(更深底,底部站立)
        Box(Modifier.width(140.dp).fillMaxHeight().background(Holo.AvatarBg)) {
            HoloFigure(
                CharacterRegistry.current.frontRes,
                Modifier.align(Alignment.BottomCenter).fillMaxHeight(0.98f)
                    .aspectRatio(0.5f, matchHeightConstraintsFirst = true),
            )
            Text(
                CharacterRegistry.current.zh, color = Holo.Accent, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 6.dp),
            )
        }
        // 右:顶条(音波 + 阶段)+ 会话气泡
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Waveform(active = running)
                Spacer(Modifier.weight(1f))
                PhaseDots(current = 1, total = 4)
            }
            Box(Modifier.weight(1f)) {
                if (convo.isEmpty() && !running) {
                    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text("点下面的输入框,和我说点什么。", color = Holo.TextSecondary, fontSize = 13.sp)
                    }
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp),
                    ) {
                        items(convo, key = { it.id }) { m -> DialogBubble(m) }
                        if (running && toolNote.isNotBlank()) {
                            item(key = "toolnote") { Text(toolNote, color = Holo.Accent, fontSize = 10.sp) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 直播间窗内容:背景 = 每轮生成的场景图([sceneUrl]),无则退回角色立绘;覆盖 LIVE/观众数、
 * 生成中提示、♥ 点赞、底部字幕(最后一条角色发言)。
 */
@Composable
private fun LiveRoomContent(
    sceneUrl: String?,
    sceneLoading: Boolean,
    sceneOn: Boolean,
    subtitle: String,
    running: Boolean,
    toolNote: String,
    likeCount: Int,
    onLike: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Holo.AvatarBg)) {
        if (sceneUrl != null) {
            coil.compose.AsyncImage(
                model = sceneUrl,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize().background(Holo.bgBrush)) {
                HoloFigure(
                    CharacterRegistry.current.frontRes,
                    Modifier.align(Alignment.BottomCenter).fillMaxHeight(0.96f)
                        .aspectRatio(0.5f, matchHeightConstraintsFirst = true),
                )
            }
        }
        // 左上:● LIVE + 观众数
        Row(
            Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(8.dp))
                .background(Holo.Panel.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HoloDot(Holo.Live)
            Text("LIVE", color = Holo.Live, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text("👁 $likeCount", color = Holo.TextSecondary, fontSize = 10.sp)
        }
        // 右上:场景生成状态
        if (sceneLoading) {
            Text(
                "生成场景…", color = Holo.Accent, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(6.dp))
                    .background(Holo.Panel.copy(alpha = 0.6f)).padding(horizontal = 6.dp, vertical = 3.dp),
            )
        } else if (!sceneOn && sceneUrl == null) {
            Text(
                "场景生成:关(顶栏可开)", color = Holo.TextSecondary, fontSize = 9.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            )
        }
        // 右下:♥ 点赞
        Column(
            Modifier.align(Alignment.BottomEnd).padding(10.dp).clip(RoundedCornerShape(20.dp))
                .background(Holo.Panel.copy(alpha = 0.6f)).clickable(onClick = onLike)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("♥", color = Holo.Live, fontSize = 18.sp)
            Text("$likeCount", color = Holo.TextHud, fontSize = 9.sp)
        }
        // 底部字幕(最后一条角色发言)
        if (subtitle.isNotBlank() || running) {
            val scroll = androidx.compose.foundation.rememberScrollState()
            LaunchedEffect(subtitle) { runCatching { scroll.animateScrollTo(scroll.maxValue) } }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 12.dp, end = 60.dp, bottom = 12.dp),
            ) {
                if (running && toolNote.isNotBlank()) {
                    Text(toolNote, color = Holo.Accent, fontSize = 10.sp, modifier = Modifier.padding(bottom = 4.dp))
                }
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 96.dp).clip(RoundedCornerShape(10.dp))
                        .background(Color(0xCC0E0F12)).padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Column(Modifier.verticalScroll(scroll)) {
                        Text(subtitle.ifBlank { "……" }, color = Holo.TextHud, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }
            }
        }
    }
}

/** 小音波条(装饰,呼应 OpenRoom 卡头波形):运行中动画跳动,空闲静止。 */
@Composable
private fun Waveform(active: Boolean) {
    val heights = if (active) {
        val t = rememberInfiniteTransition(label = "wave")
        (0 until 5).map { i ->
            t.animateFloat(
                initialValue = 0.3f, targetValue = 1f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    androidx.compose.animation.core.tween(360 + i * 90),
                    androidx.compose.animation.core.RepeatMode.Reverse,
                ),
                label = "w$i",
            ).value
        }
    } else listOf(0.4f, 0.7f, 0.5f, 0.8f, 0.45f)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        heights.forEach { hf ->
            Box(
                Modifier.width(2.5.dp).height((14 * hf).dp).clip(RoundedCornerShape(2.dp))
                    .background(Holo.Accent.copy(alpha = 0.8f)),
            )
        }
    }
}

/** 会话气泡:用户右对齐(黄底深字),角色左对齐(深底浅字)。 */
@Composable
private fun DialogBubble(m: DeskMsg) {
    Row(Modifier.fillMaxWidth()) {
        if (m.fromUser) Spacer(Modifier.weight(0.18f))
        Box(
            modifier = Modifier.weight(0.82f).clip(RoundedCornerShape(12.dp))
                .background(if (m.fromUser) Holo.Accent else Holo.Surface2)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                m.text,
                color = if (m.fromUser) Color(0xFF1A1A1A) else Holo.TextHud,
                fontSize = 14.sp, lineHeight = 20.sp,
            )
        }
        if (!m.fromUser) Spacer(Modifier.weight(0.18f))
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
 * 屏幕正底部回复条(对齐 OpenRoom):上排快捷回复气泡 + 下排黄色输入胶囊。
 * 运行中输入胶囊变停止键。快捷回复为预设短句,点击即发。
 */
@Composable
private fun DesktopReplyBar(running: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val quick = remember { listOf("你能做什么?", "帮我打开一个网页", "整理一下今天的信息") }
    val charName = CharacterRegistry.current.name
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 快捷回复气泡(运行中隐藏,避免误触)
        if (!running) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                quick.forEach { q ->
                    Text(
                        q, color = Holo.TextHud, fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Holo.Surface2)
                            .clickable { onSend(q) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
        // 输入胶囊(黄):居中紧凑、更薄,窄屏自适应
        Row(
            modifier = Modifier.widthIn(max = 380.dp).fillMaxWidth().height(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Holo.Accent)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = Color(0xFF1A1A1A), fontSize = 13.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF1A1A1A)),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (input.isNotBlank()) { onSend(input); input = "" } }),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (input.isEmpty()) {
                        Text("$charName 在等你回复…", color = Color(0x991A1A1A), fontSize = 13.sp, maxLines = 1)
                    }
                    inner()
                },
            )
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(Color(0xFF1A1A1A))
                    .clickable {
                        if (running) onStop()
                        else if (input.isNotBlank()) { onSend(input); input = "" }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (running) Icons.Filled.Stop else Icons.Filled.ArrowUpward,
                    contentDescription = if (running) "停止" else "发送",
                    tint = Holo.Accent, modifier = Modifier.size(18.dp),
                )
            }
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
    sceneOn: Boolean,
    onToggleScene: () -> Unit,
    onExit: () -> Unit,
) {
    // 时钟内部维护,避免上层每秒重组
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { nowMs = System.currentTimeMillis(); delay(1000) } }
    val timeText = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }.format(Date(nowMs))

    Row(
        modifier = Modifier.fillMaxWidth().height(32.dp)
            .background(Holo.Panel.copy(alpha = 0.85f))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.DesktopWindows, contentDescription = null, tint = Holo.Accent.copy(alpha = 0.9f), modifier = Modifier.size(14.dp))
        Text("本地虚拟电脑", color = Holo.TextHud, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        HoloDot(connColor)
        Text(connLabel, color = Holo.TextSecondary, fontSize = 10.sp)
        Text(timeText, color = Holo.Accent.copy(alpha = 0.9f), fontSize = 10.sp)
        Spacer(Modifier.width(2.dp))
        // 场景生成开关(每轮生成会扣积分,给用户一个显式闸门)
        Text(
            if (sceneOn) "场景:开" else "场景:关",
            color = if (sceneOn) Holo.Accent else Holo.TextSecondary, fontSize = 11.sp,
            modifier = Modifier.clip(RoundedCornerShape(5.dp)).clickable(onClick = onToggleScene)
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
        // 退出桌面模式(返回上一界面)
        Row(
            modifier = Modifier.clip(RoundedCornerShape(5.dp)).clickable(onClick = onExit)
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(Icons.Filled.Close, contentDescription = "退出桌面", tint = Holo.TextSecondary, modifier = Modifier.size(13.dp))
            Text("退出", color = Holo.TextSecondary, fontSize = 11.sp)
        }
    }
}

/**
 * 左侧竖排应用栏(对齐 OpenRoom 左侧图标栏):浏览器 / 发现 / 广场 + 已装小程序 + 全部。
 * 浏览器是常驻底板(点它仅高亮);发现/广场/小程序以浮动窗口打开。
 */
@Composable
private fun DesktopIcons(
    modifier: Modifier = Modifier,
    onLiveRoom: () -> Unit,
    onChat: () -> Unit,
    onCharacter: () -> Unit,
    onDiscover: () -> Unit,
    onSquare: () -> Unit,
    onLaunchMiniApp: (String) -> Unit,
    onAllApps: () -> Unit,
) {
    val miniApps = remember { MiniAppRegistry.all() }
    // 无面板背景:图标直接浮在壁纸上(桌面图标)。
    Column(
        modifier = modifier.width(72.dp)
            .verticalScroll(androidx.compose.foundation.rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 角色三窗(直播间/对话/档案)—— 平级可拖窗口,点开或置顶
        RailItem(Icons.Filled.Videocam, "直播间", active = false, onClick = onLiveRoom)
        RailItem(Icons.Filled.ChatBubbleOutline, "对话", active = false, onClick = onChat)
        RailItem(Icons.Filled.Person, "角色", active = false, onClick = onCharacter)
        Box(Modifier.padding(vertical = 2.dp).size(width = 40.dp, height = 1.dp).background(Holo.Border))
        RailItem(Icons.Filled.Explore, "发现", active = false, onClick = onDiscover)
        RailItem(Icons.Filled.Forum, "广场", active = false, onClick = onSquare)
        if (miniApps.isNotEmpty()) {
            Box(Modifier.padding(vertical = 2.dp).size(width = 40.dp, height = 1.dp).background(Holo.Border))
        }
        miniApps.take(8).forEach { m ->
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
    browsing: Boolean,
    onNavigate: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        // 浏览器 chrome(标签/地址栏/进度)仅在浏览网页时出现;空闲时中间是壁纸,不占地方。
        if (browsing) {
            DesktopTabStrip(engine, onNavigate)
            DesktopAddressBar(currentUrl, pageTitle, loading) { url ->
                engine.navigate(url)
                onNavigate(url)
            }
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
        }
        Box(Modifier.fillMaxSize()) {
            // WebView 常挂(引擎/Agent browser_* 需要),空闲置 GONE:about:blank 白屏不穿透盖住壁纸。
            AndroidView(
                factory = { ctx -> engine.createView(ctx) },
                update = { it.visibility = if (browsing) android.view.View.VISIBLE else android.view.View.GONE },
                modifier = Modifier.fillMaxSize(),
            )
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
private fun Dot(color: Color) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

/** 桌面浮动窗口的内容类型。 */
private sealed interface WinContent {
    data object LiveRoom : WinContent
    data object Chat : WinContent
    data object Discover : WinContent
    data object Square : WinContent
    data object Character : WinContent
    data class Mini(val appId: String, val name: String) : WinContent
}

/** 桌面对话一条消息(对话窗气泡 + 直播间字幕同源)。 */
private data class DeskMsg(val id: Long, val fromUser: Boolean, val text: String)

/** 每轮直播间场景图的生成提示:角色在场景里回应用户,赛博霓虹、电影感。 */
private fun scenePrompt(c: CharacterProfile, userText: String): String =
    "cinematic wide shot, anime illustration of a character named ${c.name} (${c.codename}, ${c.role}), " +
        "reacting in-scene to: \"${userText.take(120)}\". cyberpunk neon interior, dramatic rim lighting, " +
        "highly detailed, atmospheric, 16:9"

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
