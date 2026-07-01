package com.apk.claw.android.ui.desktop

import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DesktopWindows
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.apk.claw.android.appViewModel
import com.apk.claw.android.octopus_mobile.ConnectionState
import com.apk.claw.android.octopus_mobile.browser.BrowserEngine
import com.apk.claw.android.octopus_mobile.browser.BrowserEngineFactory
import com.apk.claw.android.octopus_mobile.browser.EngineEvent
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.ui.compose.screen.ChatScreen
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
                }
                is EngineEvent.Error -> loading = false
                else -> {}
            }
        }
    }

    // 对话展开/收起:收起时对话面板宽度动画到 0(仍在组合中,不丢上下文/不打断运行中的任务),
    // 桌面占满;右下角出现悬浮球,点开恢复。
    var chatExpanded by rememberSaveable { mutableStateOf(true) }
    val chatWidth by animateDpAsState(if (chatExpanded) 340.dp else 0.dp, label = "chatWidth")

    Box(Modifier.fillMaxSize().background(OctopusColors.Background)) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                DesktopMonitor(
                    engine = engine,
                    currentUrl = currentUrl,
                    pageTitle = pageTitle,
                    loading = loading,
                    progress = progress,
                    onNavigate = { currentUrl = it; pageTitle = "" },
                )
            }
            if (chatWidth > 0.dp) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(OctopusColors.Border))
            }
            // ChatScreen 常驻组合,只动宽度 → 收起再展开不丢对话/输入/运行状态
            Box(Modifier.width(chatWidth).fillMaxHeight().clipToBounds()) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(30.dp)
                            .background(OctopusColors.Surface).padding(start = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("对话", color = OctopusColors.TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { chatExpanded = false }, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Filled.ChevronRight, contentDescription = "收起对话", tint = OctopusColors.TextMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                    Box(Modifier.weight(1f)) { ChatScreen() }
                }
            }
        }
        // 收起态:右下角悬浮球,点开展开对话
        if (!chatExpanded) {
            FloatingActionButton(
                onClick = { chatExpanded = true },
                containerColor = OctopusColors.Primary,
                modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
            ) {
                Icon(Icons.Filled.ChatBubbleOutline, contentDescription = "展开对话", tint = Color.White)
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
        DesktopAddressBar(currentUrl, pageTitle, loading) { url ->
            engine.navigate(url)
            onNavigate(url)
        }
        // 「正在打开 X」+ 进度条:仅加载时显示
        if (loading) {
            Text(
                "正在打开 ${hostOf(currentUrl)}…",
                color = OctopusColors.Primary, fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().background(OctopusColors.Surface)
                    .padding(horizontal = 12.dp, vertical = 2.dp),
            )
            LinearProgressIndicator(
                progress = { (progress.coerceIn(0, 100)) / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = OctopusColors.Primary,
                trackColor = Color.Transparent,
            )
        }
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx -> engine.createView(ctx) },
                modifier = Modifier.fillMaxSize(),
            )
            val idle = currentUrl.isBlank() || currentUrl == "about:blank"
            if (idle) DesktopWallpaper(Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun DesktopAddressBar(currentUrl: String, pageTitle: String, loading: Boolean, onGo: (String) -> Unit) {
    var text by remember(currentUrl) { mutableStateOf(currentUrl) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OctopusColors.Surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 状态点:加载中琥珀,空闲/完成灰
        Dot(if (loading) OctopusColors.Warning else OctopusColors.TextMuted)
        Spacer(Modifier.width(8.dp))
        TextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            placeholder = { Text(pageTitle.ifBlank { "输入网址…" }, fontSize = 13.sp, maxLines = 1) },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = OctopusColors.TextPrimary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = {
                val u = normalizeUrl(text)
                if (u.isNotBlank()) onGo(u)
            }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = OctopusColors.SurfaceVariant,
                unfocusedContainerColor = OctopusColors.SurfaceVariant,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.weight(1f),
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
    val dateFmt = remember { SimpleDateFormat("M月d日 EEEE", Locale.getDefault()) }

    // 母体连接状态
    val connState by appViewModel.connectionState.collectAsState()
    val (connLabel, connColor) = when (connState) {
        ConnectionState.ONLINE -> "母体在线" to OctopusColors.Success
        ConnectionState.CONNECTING, ConnectionState.CONNECTED,
        ConnectionState.HELLO_SENT, ConnectionState.RECONNECTING -> "连接中…" to OctopusColors.Warning
        else -> "未连接" to OctopusColors.TextMuted
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
                "Agent 桌面 · 空闲中,在右侧对话下达指令",
                color = OctopusColors.TextMuted, fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
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
