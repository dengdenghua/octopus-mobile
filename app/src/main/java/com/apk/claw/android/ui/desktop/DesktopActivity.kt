package com.apk.claw.android.ui.desktop

import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PushPin
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
import androidx.compose.ui.input.pointer.pointerInput
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
import com.apk.claw.android.ui.device.DeviceListActivity
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.ui.compose.theme.OctopusTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「数字管家桌面」—— 科幻全息交互桌面(v2)。
 *
 * 统一触屏 + D-pad 焦点导航,适配手机/平板/TV 投屏:
 *  - 全息半透明浮动窗口(聚焦时更实、失焦更透,柔黄边框高亮)
 *  - 所有交互元素可 D-pad 聚焦,方向键遍历
 *  - overscan 安全区(四边 48dp),TV 投屏不裁切
 *  - 放大字号(3m 观看距离可读)
 *  - 复用 [BrowserEngine] + [ChatAgentBridge],触屏与遥控器同一套交互
 *
 * 锁横屏 + `configChanges` 防旋转 recreate 闪断 WebView(见 AndroidManifest)。
 */
class DesktopActivity : AppCompatActivity() {

    private var engine: BrowserEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemStatusBar()
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
        // 沉浸态在切走再回来时可能被系统恢复,重进时再隐一次。
        hideSystemStatusBar()
    }

    /**
     * 桌面/TV 横屏为沉浸态:隐藏系统状态栏(时间/信号/电量),避免与右上角控制中心([TvTopChrome])
     * 重叠;下滑可临时唤出。只隐状态栏、保留导航手势区,不影响返回。
     */
    private fun hideSystemStatusBar() {
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
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
    // TV 首页:同一时刻只聚焦一件内容(选中磁贴/头像 → 内容层);null = 停在首页磁贴网格。
    var openContent by remember { mutableStateOf<WinContent?>(null) }
    // 钉住:内容层从居中浮层切到右侧停靠面板(Copilot 式:左桌面 + 右对话)。记住用户偏好,不随开关重置。
    var pinned by remember { mutableStateOf(false) }
    val openWindow: (WinContent) -> Unit = { kind -> openContent = kind }
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
    // 对话:主 Agent 会话([ChatAgentBridge]);convo = 完整会话。
    val convo = remember { androidx.compose.runtime.mutableStateListOf<DeskMsg>() }
    var running by remember { mutableStateOf(false) }
    var toolNote by remember { mutableStateOf("") }
    var seq by remember { mutableLongStateOf(0L) }
    // 每轮生成一张「角色在场景里」的图,作 Hero 影视壁纸。可在顶栏控制中心关。
    var sceneUrl by remember { mutableStateOf<String?>(null) }
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
                com.apk.claw.android.media.MediaRepository.generateImage(scenePrompt(c, t), "1280x720")
                    .onSuccess { sceneUrl = it.url }
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
    // 是否在浏览网页:是则中间显玻璃浏览器盒,否则中间就是壁纸 + 浮动窗口
    val browsing = currentUrl.isNotBlank() && currentUrl != "about:blank"

    // TV 遥控兼容:无触屏设备(TV 盒子)进桌面时,把焦点落到第一个图标,D-pad 立即可用;
    // 手机有触屏 → 不抢焦点(触屏直接点,避免无端高亮)。
    val firstIconFocus = remember { FocusRequester() }
    val hasTouch = remember {
        ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
    }
    LaunchedEffect(Unit) { if (!hasTouch) runCatching { firstIconFocus.requestFocus() } }
    // 遥控/系统返回键:有全屏内容层/浏览器时先关掉,否则才退出桌面。
    BackHandler(enabled = browsing || openContent != null) {
        when {
            browsing -> { engine.navigate("about:blank"); currentUrl = "about:blank"; pageTitle = "" }
            else -> openContent = null
        }
    }

    // Apple TV 式首页:壁纸全出血打底 + 顶栏 + 大图焦点磁贴网格;选中磁贴/头像 → 全屏内容层。
    // 头像作为常驻「悬浮桌面 agent」浮在右下角,点开即对话。
    Box(Modifier.fillMaxSize()) {
        HoloBackground(Modifier.fillMaxSize())
        // 响应式:无触屏(TV/盒子)= 3 米外 10-foot 观看 → 图标/字号放大;手机触屏用常规尺寸。
        // 比例不写死:图标架给自然高度,Hero 用 weight(1f) 吃掉剩余高度,任意屏幕比例(手机 2.2:1 /
        // 电视 16:9 / 平板)都自动协调。
        val bigUi = !hasTouch
        // 钉住且有内容 → 右侧分屏:桌面让出 dockW 缩到左侧,对话面板并排在右(不重叠、不遮桌面)。
        val docked = pinned && openContent != null
        val dockW = if (bigUi) 460.dp else 360.dp
        // 桌面内容(Apple TV 式):Hero 全宽头 + 图标网格,整屏纵向瀑布流——向下滚/翻页看更多图标。
        // Hero 点击 = 和角色对话。分屏窄区列数减少,自然多排、往下翻。
        val desktopContent = @Composable {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val heroH = maxHeight * TV_HERO_HEIGHT_FRACTION
                val maxCols = if (docked) TV_COLS_DOCKED else if (bigUi) TV_COLS_TV else TV_COLS_PHONE
                val miniApps = remember { MiniAppRegistry.all() }
                val apps = buildList {
                    add(TvAppSpec(Icons.Filled.ChatBubbleOutline, TvGradChat) { openWindow(WinContent.Chat) })
                    add(TvAppSpec(Icons.Filled.Person, TvGradChar) { openWindow(WinContent.Character) })
                    add(TvAppSpec(Icons.Filled.Explore, TvGradDiscover) { openWindow(WinContent.Discover) })
                    add(TvAppSpec(Icons.Filled.Forum, TvGradSquare) { openWindow(WinContent.Square) })
                    // 「全部应用」入口去掉:主页少一个图标,右下角空出来给悬浮头像 agent。
                    miniApps.forEach { m ->
                        add(
                            TvAppSpec(Icons.Filled.Apps, TvGradMini) {
                                openWindow(WinContent.Mini(m.id, m.name))
                            },
                        )
                    }
                }
                // 列数 = 图标数(封顶 maxCols):图标少时单行填满(统一大小),超过才多排、往下滚(瀑布流)。
                val cols = apps.size.coerceIn(1, maxCols)
                // 图标区两边对称留白 → 整行居中(重心不偏);右侧留白正好容纳右下角悬浮头像,不重叠。
                val sideGap = if (docked) 20.dp else 104.dp
                // 场景壁纸**全屏打底**:Hero 与图标栏共用同一张、边到边全出血,消除首页中部那道接缝。
                // 无壁纸(未生成/已关场景)则露出底层 HoloBackground 霓虹。
                if (sceneUrl != null) {
                    coil.compose.AsyncImage(
                        model = sceneUrl,
                        contentDescription = character.zh,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                    // 柔和「全屏」压暗:顶部(状态栏/顶栏)与底部(标题/图标)各一点点、中段全透——
                    // 均匀不偏,取代原来只压 Hero 上半、中部出现硬边「蒙层」的做法。
                    Box(
                        Modifier.matchParentSize()
                            .background(androidx.compose.ui.graphics.Brush.verticalGradient(TvWallScrim)),
                    )
                }
                Column(Modifier.fillMaxSize()) {
                    // Hero 全宽头(不受留白影响,标题贴左);占视口 ~72%,首页只露一排图标。
                    TvHero(
                        character = character,
                        big = bigUi,
                        modifier = Modifier.fillMaxWidth().height(heroH),
                        onClick = { openWindow(WinContent.Chat) },
                    )
                    // 图标网格:向下滚露出更多行(ATV 瀑布流);统一大小 + 两侧留白居中。
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(cols),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = sideGap, end = sideGap, bottom = 20.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        itemsIndexed(apps) { i, a ->
                            TvAppIcon(
                                a.icon, a.grad, a.onClick,
                                if (i == 0) Modifier.focusRequester(firstIconFocus) else Modifier,
                            )
                        }
                    }
                }
                // 极简顶栏:浮在右上(满屏 / 分屏左区均对齐)。
                TvTopChrome(
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 16.dp),
                    connColor = connColor,
                    sceneOn = sceneGen,
                    onToggleScene = { sceneGen = !sceneGen },
                    onDeviceSwitch = {
                        runCatching { ctx.startActivity(android.content.Intent(ctx, DeviceListActivity::class.java)) }
                    },
                    onExit = { (ctx as? android.app.Activity)?.finish() },
                )
            }
        }

        // 未钉:桌面满屏、重心居中。钉住:桌面填满「左区」(让出 dockW),无边框、与右侧 dock 无缝对接。
        Box(Modifier.fillMaxSize().padding(end = if (docked) dockW else 0.dp)) {
            desktopContent()
        }
        if (!docked) {
            // 头像悬浮桌面 agent:常驻右下,点开即对话(分屏时隐藏,右侧已在对话)。
            FloatingAgentAvatar(
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 18.dp),
                running = running,
                onClick = { openWindow(WinContent.Chat) },
            )
        }
        // 内容层:钉住 → 右侧分屏面板(与左桌面并排,不遮挡);未钉 → 居中较窄浮层(带遮罩)。
        openContent?.let { kind ->
            val body: @Composable () -> Unit = {
                when (kind) {
                    WinContent.Chat -> ChatContent(
                        convo = convo, running = running, toolNote = toolNote, narrow = docked,
                    )
                    WinContent.Character -> CharacterHud(Modifier.fillMaxSize())
                    WinContent.Discover -> DiscoverScreen(onOpenUrl = { url -> url?.let { engine.navigate(normalizeUrl(it)) } })
                    WinContent.Square -> AgentSquareScreen(onBack = { openContent = null })
                    is WinContent.Mini -> MiniAppWindow(kind.appId)
                }
            }
            val bar: (@Composable () -> Unit)? = if (kind == WinContent.Chat) {
                @Composable { DesktopReplyBar(running = running, onSend = send, onStop = stop) }
            } else {
                null
            }
            val panel = @Composable {
                TvContentPanel(
                    title = contentTitle(kind, character.name),
                    onClose = { openContent = null },
                    pinned = pinned,
                    onTogglePin = { pinned = !pinned },
                    bottomBar = bar,
                    content = body,
                )
            }
            if (docked) {
                Box(
                    Modifier.align(Alignment.CenterEnd).width(dockW).fillMaxHeight().background(TvShelfBg),
                ) { panel() }
            } else {
                Box(Modifier.fillMaxSize().background(TvOverlayScrim), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.fillMaxHeight(fraction = 0.92f)
                            .fillMaxWidth(fraction = 0.58f).widthIn(min = 360.dp, max = 620.dp),
                    ) { panel() }
                }
            }
        }
        // 浏览网页:居中较宽浮层(暂不参与右侧分屏)。
        if (browsing) {
            Box(Modifier.fillMaxSize().background(TvOverlayScrim), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxHeight(fraction = 0.94f).fillMaxWidth(fraction = 0.74f)) {
                    TvContentPanel(
                        title = pageTitle.ifBlank { currentUrl },
                        onClose = { engine.navigate("about:blank"); currentUrl = "about:blank"; pageTitle = "" },
                        pinned = false,
                        onTogglePin = {},
                        bottomBar = null,
                    ) {
                        DesktopMonitor(
                            engine = engine,
                            currentUrl = currentUrl,
                            pageTitle = pageTitle,
                            loading = loading,
                            progress = progress,
                            browsing = true,
                            onNavigate = { currentUrl = it; pageTitle = "" },
                        )
                    }
                }
            }
        }
    }
}

/** 全屏内容层的标题。 */
private fun contentTitle(kind: WinContent, name: String): String = when (kind) {
    WinContent.Chat -> "对话 · $name"
    WinContent.Character -> "角色档案 · $name"
    WinContent.Discover -> "发现"
    WinContent.Square -> "广场"
    is WinContent.Mini -> kind.name
}

// ── Apple TV 式布局比例 + 配色(top-level 具名常量:满足 MagicNumber 豁免;彩色亮图标) ──
private const val TV_GLYPH_RATIO = 0.44f            // 图标内白色字形相对图标高度的比例
private const val TV_HERO_HEIGHT_FRACTION = 0.72f   // Hero 占视口高度比例:首页只露一排图标,往下滑翻出应用页
private const val TV_ICON_ASPECT = 1.75f            // 图标单元格宽高比(更宽、不那么高,贴 Apple TV)
private const val TV_COLS_DOCKED = 4                // 分屏窄区列数
private const val TV_COLS_TV = 7                    // TV/大屏列数(更密、图标更小)
private const val TV_COLS_PHONE = 6                 // 手机列数
private val TvShelfBg = Color(0xF20A0B0E)
private val TvGradChat = listOf(Color(0xFF5AD07A), Color(0xFF23A94B))       // 对话 绿
private val TvGradChar = listOf(Color(0xFFB18CFF), Color(0xFF6B4BFF))       // 角色 紫
private val TvGradDiscover = listOf(Color(0xFF5AB0FF), Color(0xFF0A84FF))   // 发现 蓝
private val TvGradSquare = listOf(Color(0xFFFFC24D), Color(0xFFFF9500))     // 广场 橙
private val TvGradMini = listOf(Color(0xFF3DE0D0), Color(0xFF16B8A6))       // 小程序 青
// 全屏壁纸的柔和压暗:顶(状态栏/顶栏可读)—中段全透—底(标题/图标可读),均匀无硬边。
private val TvWallScrim = listOf(Color(0x40000000), Color(0x00000000), Color(0x00000000), Color(0x73000000))

/** 焦点图标规格(下发到 [TvIconShelf] 渲染)。 */
private data class TvAppSpec(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val grad: List<Color>,
    val onClick: () -> Unit,
)

/**
 * 影院级 Hero:用生成的场景图 [sceneUrl] 当全铺壁纸(没有则暗色渐变兜底),
 * 底部渐隐压出角色名/代号/标语(像影片标题),点击进入直播间。
 */
@Composable
private fun TvHero(
    character: CharacterProfile,
    big: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    // 壁纸与柔和压暗都在 desktopContent 里做(全屏、均匀);Hero 这里只放标题。
    // 标题给一层文字投影,保证在明亮壁纸上也读得清,不再靠 Hero 局部大蒙层。
    val titleShadow = androidx.compose.ui.graphics.Shadow(
        color = Color(0xCC000000),
        offset = androidx.compose.ui.geometry.Offset(0f, 2f),
        blurRadius = 12f,
    )
    Box(modifier.clickable(onClick = onClick)) {
        Column(
            Modifier.align(Alignment.BottomStart)
                .padding(start = if (big) 40.dp else 28.dp, bottom = if (big) 32.dp else 20.dp),
        ) {
            Text(
                character.zh, color = Color.White,
                fontSize = if (big) 46.sp else 34.sp, fontWeight = FontWeight.Bold,
                style = androidx.compose.ui.text.TextStyle(shadow = titleShadow),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${character.codename} · ${character.role}",
                color = Color.White.copy(alpha = 0.85f), fontSize = if (big) 18.sp else 14.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "“${character.quote}”",
                color = Color.White.copy(alpha = 0.72f), fontSize = if (big) 16.sp else 13.sp, maxLines = 2,
            )
        }
    }
}

/** 单个彩色亮图标(渐变圆角方 + 白色图标 + 焦点高亮)。填满网格单元格,固定 16:10 宽高比。 */
@Composable
private fun TvAppIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    grad: List<Color>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(TV_ICON_ASPECT)
            .clip(RoundedCornerShape(16.dp))
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(grad))
            .holoFocus(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, contentDescription = null, tint = Color.White,
            modifier = Modifier.fillMaxHeight(TV_GLYPH_RATIO),
        )
    }
}

/** 控制中心磁贴(图标 + 标签,焦点高亮);active=开启态高亮,danger=危险色。 */
@Composable
private fun CcTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = when {
        danger -> Holo.Live
        active -> Holo.Accent
        else -> Holo.TextHud
    }
    Column(
        modifier = Modifier
            .size(width = 108.dp, height = 66.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) Holo.Accent.copy(alpha = 0.24f) else Color(0xFF24272F))
            .holoFocus(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = tint, fontSize = 12.sp, maxLines = 1)
    }
}

/** 极简顶栏:状态点 + 时间 + 头像菜单(控制中心:设备切换 / 切换角色 / 场景 / 退出),浮在 Hero 右上。 */
@Composable
private fun TvTopChrome(
    connColor: Color,
    sceneOn: Boolean,
    onToggleScene: () -> Unit,
    onDeviceSwitch: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier,
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { nowMs = System.currentTimeMillis(); delay(1000) } }
    val timeText = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }.format(Date(nowMs))
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HoloDot(connColor)
        Text(timeText, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Box {
            // 应用内 agent 头像 = 当前角色头像(和右下角悬浮头像同一来源 CharacterAvatar);
            // 点开即控制中心。八爪鱼图标只留给应用外品牌位(桌面图标/通知/闪屏)。
            Box(
                Modifier.size(32.dp).clip(CircleShape)
                    .holoFocus(CircleShape).clickable { menu = true },
                contentAlignment = Alignment.Center,
            ) {
                CharacterAvatar(32.dp)
            }
            // 控制中心式下拉:2×2 玻璃磁贴(参考 Apple TV 控制中心)。
            androidx.compose.material3.DropdownMenu(
                expanded = menu,
                onDismissRequest = { menu = false },
                containerColor = Holo.Panel,
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CcTile(Icons.Filled.Devices, "设备切换") { onDeviceSwitch(); menu = false }
                        CcTile(Icons.Filled.Person, "切换角色") { CharacterRegistry.next(); menu = false }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CcTile(
                            Icons.Filled.AutoAwesome,
                            if (sceneOn) "场景 开" else "场景 关",
                            active = sceneOn,
                        ) { onToggleScene() }
                        CcTile(Icons.Filled.Close, "退出竖屏", danger = true) { onExit(); menu = false }
                    }
                }
            }
        }
    }
}

/** 头像悬浮桌面 agent:常驻角落,运行时描边转红;点击打开对话。 */
@Composable
private fun FloatingAgentAvatar(
    modifier: Modifier,
    running: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(72.dp)
            .clip(CircleShape)
            .border(2.dp, if (running) Holo.Live else Holo.Accent, CircleShape)
            .holoFocus(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { CharacterAvatar(72.dp) }
}

/** 全屏内容层的半透明遮罩底色(压暗壁纸,突出内容)。 */
private val TvOverlayScrim = Color(0xE6060810)

/** 内容层顶栏小圆按钮(返回 / 钉);active=true 高亮。 */
@Composable
private fun TvIconBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    Box(
        Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
            .background(if (active) Holo.Accent.copy(alpha = 0.22f) else Color.Transparent)
            .holoFocus(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, contentDescription = desc,
            tint = if (active) Holo.Accent else Holo.TextHud, modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * 内容层面板(顶栏:返回 + 标题 + 钉;中间玻璃内容盒;可选底部输入)。
 * 定位交给布局层:未钉 = 居中较窄浮层;钉住 = 右侧分屏面板(与左桌面并排,不重叠)。
 */
@Composable
private fun TvContentPanel(
    title: String,
    onClose: () -> Unit,
    pinned: Boolean,
    onTogglePin: () -> Unit,
    bottomBar: (@Composable () -> Unit)?,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(34.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvIconBtn(Icons.Filled.Close, "返回", onClose)
            Spacer(Modifier.width(8.dp))
            Text(title, color = Holo.TextHud, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
            TvIconBtn(
                Icons.Filled.PushPin,
                if (pinned) "取消停靠" else "停靠右侧",
                onTogglePin,
                active = pinned,
            )
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)).holoGlass(16.dp).clipToBounds(),
        ) { content() }
        bottomBar?.let {
            Spacer(Modifier.height(8.dp))
            it()
        }
    }
}

/**
 * 对话窗内容(HoloWindow 提供边框/标题):左角色立绘常驻 | 右会话气泡列表(顶条:音波 + 阶段)。
 * 会话为主 Agent 同一条 [convo]。
 */
@Composable
private fun ChatContent(convo: List<DeskMsg>, running: Boolean, toolNote: String, narrow: Boolean = false) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(convo.size, convo.lastOrNull()?.text) {
        if (convo.isNotEmpty()) runCatching { listState.animateScrollToItem(convo.size - 1) }
    }
    Row(Modifier.fillMaxSize()) {
        // 左:当前角色立绘常驻(更深底,底部站立)。窄栏(右侧分屏)时隐藏,给会话让宽。
        if (!narrow) {
            Box(Modifier.width(140.dp).fillMaxHeight().background(Holo.AvatarBg)) {
                HoloFigure(
                    CharacterRegistry.current.frontRes,
                    Modifier.align(Alignment.BottomCenter).fillMaxHeight(0.98f)
                        .aspectRatio(0.5f, matchHeightConstraintsFirst = true),
                )
                Text(
                    CharacterRegistry.current.zh, color = Holo.Accent,
                    fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 6.dp),
                )
            }
        }
        // 右:会话气泡。去掉了「阶段 x/4」条;思考时底部内联一行黄色竖点脉动,不再弹独立思考窗。
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxSize()) {
                if (convo.isEmpty() && !running) {
                    // 空态:小气泡(不占满、不做大灰块),像角色发来的第一句。
                    Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.TopStart) {
                        Text(
                            "点下面的输入框,和我说点什么。",
                            color = Holo.TextHud, fontSize = 13.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Holo.Surface2)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp),
                    ) {
                        items(convo, key = { it.id }) { m -> DialogBubble(m) }
                        if (running) {
                            item(key = "thinking") { ThinkingRow(toolNote) }
                        }
                    }
                }
            }
        }
    }
}


/** 思考指示行:几个黄色竖点脉动 +(可选)当前工具名。内联在对话底部,不弹独立思考窗。 */
@Composable
private fun ThinkingRow(toolNote: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ThinkingDots()
        if (toolNote.isNotBlank()) Text(toolNote, color = Holo.Accent, fontSize = 10.sp)
    }
}

/** 几个黄色竖点,依次脉动 —— 思考中的极简动态,替代原音波/阶段条。 */
@Composable
private fun ThinkingDots() {
    val t = rememberInfiniteTransition(label = "think")
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { i ->
            val hf = t.animateFloat(
                initialValue = 0.35f, targetValue = 1f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    androidx.compose.animation.core.tween(460, delayMillis = i * 150),
                    androidx.compose.animation.core.RepeatMode.Reverse,
                ),
                label = "d$i",
            ).value
            Box(
                Modifier.width(4.dp).height((16 * hf).dp).clip(RoundedCornerShape(2.dp))
                    .background(Holo.Accent),
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

/**
 * 屏幕正底部回复条(对齐 OpenRoom):上排快捷回复气泡 + 下排黄色输入胶囊。
 * 运行中输入胶囊变停止键。快捷回复为预设短句,点击即发。
 */
/** 快捷回复气泡:居中 + 自动换行(窄栏 dock 放不下就往下另起一行)。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ReplyQuickPrompts(prompts: List<String>, onSend: (String) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        prompts.forEach { q ->
            Text(
                q, color = Holo.TextHud, fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(Holo.Surface2.copy(alpha = 0.7f))
                    .holoFocus(RoundedCornerShape(9.dp))
                    .clickable { onSend(q) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun DesktopReplyBar(running: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    var input by remember { mutableStateOf("") }
    // 语音输入:voiceMode = 收起成小圆气泡的语音模式;listening = 正在识别;partial = 实时文本。
    var voiceMode by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val voice = remember { com.apk.claw.android.octopus_mobile.VoiceInput(ctx) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { voice.destroy() } }
    val micPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) voiceMode = true }
    val hasMic = {
        androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val quick = remember { listOf("你能做什么?", "帮我打开一个网页", "整理一下今天的信息") }
    val charName = CharacterRegistry.current.name
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 快捷回复气泡(运行中隐藏,避免误触)。
        if (!running) {
            ReplyQuickPrompts(quick, onSend)
        }
        if (voiceMode) {
            // 收起的小圆气泡语音模式:按住麦克风说话、松手发送;上方显示实时识别文本。
            VoiceBubbleInput(
                listening = listening,
                partial = partial,
                onStartHold = {
                    listening = true
                    partial = ""
                    voice.start(
                        onPartial = { partial = it },
                        onResult = { t -> listening = false; partial = ""; if (t.isNotBlank()) onSend(t) },
                        onError = { listening = false; partial = "" },
                    )
                },
                onRelease = { voice.stop() },
                onExitVoice = { if (!listening) voiceMode = false },
            )
        } else {
            // 输入胶囊(黄)+ 麦克风(切语音)+ 发送
            Row(
                modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth().height(46.dp)
                    .clip(RoundedCornerShape(23.dp))
                    .background(Holo.Accent)
                    .padding(start = 18.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color(0xFF1A1A1A), fontSize = 14.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF1A1A1A)),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (input.isNotBlank()) { onSend(input); input = "" } }),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (input.isEmpty()) {
                            Text("$charName 在等你回复…", color = Color(0x991A1A1A), fontSize = 14.sp, maxLines = 1)
                        }
                        inner()
                    },
                )
                // 麦克风:收起成小圆气泡语音模式(无权限先申请)
                Box(
                    Modifier.size(36.dp).clip(CircleShape).holoFocus(CircleShape).clickable {
                        if (hasMic()) voiceMode = true
                        else micPermLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Mic, contentDescription = "语音输入",
                        tint = Color(0xCC1A1A1A), modifier = Modifier.size(20.dp),
                    )
                }
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF1A1A1A))
                        .holoFocus(CircleShape)
                        .clickable {
                            if (running) onStop()
                            else if (input.isNotBlank()) { onSend(input); input = "" }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (running) Icons.Filled.Stop else Icons.Filled.ArrowUpward,
                        contentDescription = if (running) "停止" else "发送",
                        tint = Holo.Accent, modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/** 收起的小圆气泡语音输入:按住大圆麦克风说话、松手发送;上方实时识别文本;左侧键盘键退回文本输入。 */
@Composable
private fun VoiceBubbleInput(
    listening: Boolean,
    partial: String,
    onStartHold: () -> Unit,
    onRelease: () -> Unit,
    onExitVoice: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (partial.isNotBlank()) {
            Text(
                partial, color = Holo.TextHud, fontSize = 13.sp, maxLines = 2,
                modifier = Modifier.widthIn(max = 360.dp)
                    .clip(RoundedCornerShape(12.dp)).background(Holo.Surface2)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        } else {
            Text(
                if (listening) "正在聆听…松手发送" else "按住说话",
                color = Holo.TextSecondary, fontSize = 12.sp,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 退回键盘(文本输入)
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Holo.Surface2)
                    .holoFocus(CircleShape).clickable(onClick = onExitVoice),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Keyboard, contentDescription = "键盘", tint = Holo.TextHud, modifier = Modifier.size(20.dp)) }
            // 大圆麦克风气泡:按住说话,松手发送。识别中变红。
            Box(
                Modifier.size(if (listening) 64.dp else 56.dp).clip(CircleShape)
                    .background(if (listening) Holo.Live else Holo.Accent)
                    .holoFocus(CircleShape)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                onStartHold()
                                tryAwaitRelease()
                                onRelease()
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Mic, contentDescription = "按住说话", tint = Color(0xFF1A1A1A), modifier = Modifier.size(28.dp)) }
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
    data object Chat : WinContent
    data object Discover : WinContent
    data object Square : WinContent
    data object Character : WinContent
    data class Mini(val appId: String, val name: String) : WinContent
}

/** 桌面对话一条消息。 */
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
