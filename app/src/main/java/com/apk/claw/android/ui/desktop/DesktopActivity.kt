package com.apk.claw.android.ui.desktop

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.apk.claw.android.octopus_mobile.browser.BrowserEngine
import com.apk.claw.android.octopus_mobile.browser.BrowserEngineFactory
import com.apk.claw.android.octopus_mobile.browser.EngineEvent
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.ui.compose.screen.ChatScreen
import com.apk.claw.android.ui.compose.theme.OctopusBackground
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusTheme
import kotlinx.coroutines.flow.collectLatest

/**
 * 横屏「桌面模式」——「一台 Agent 的电脑」(v0 骨架)。
 *
 *  - **左**:一块可预览网页的浏览器桌面(复用 [BrowserEngine]/`SystemWebViewEngine`),并把该引擎
 *    注册为 [ToolRegistry] 的当前引擎 —— Agent 的 `browser_navigate`/`browser_evaluate` 等即作用于
 *    这块 WebView。空闲(无 URL)时显示壁纸。
 *  - **右**:现有 [ChatScreen] 对话区,在右侧下达指令。
 *
 * v0 目标:验证「桌面 + 对话」同屏可跑 + Agent 导航作用到左侧。地址栏可手动导航测试。
 * 后续迭代:事件联动细化、对话收起为悬浮球([com.apk.claw.android.floating.FloatingCircleManager])、
 * 专用设备默认启动 / 常亮。
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
    // 当前 URL:用户手动导航或 Agent 导航(EngineEvent.PageFinished)都会刷新
    var currentUrl by remember { mutableStateOf(engine.currentUrl()) }

    LaunchedEffect(engine) {
        engine.events().collectLatest { ev ->
            if (ev is EngineEvent.PageFinished) currentUrl = ev.url
        }
    }

    Row(Modifier.fillMaxSize().background(OctopusColors.Background)) {
        // 左:浏览器桌面
        Box(Modifier.weight(0.62f).fillMaxHeight()) {
            DesktopMonitor(engine, currentUrl, onNavigate = { currentUrl = it })
        }
        // 分隔线
        Box(Modifier.width(1.dp).fillMaxHeight().background(OctopusColors.Border))
        // 右:对话
        Box(Modifier.weight(0.38f).fillMaxHeight()) {
            ChatScreen()
        }
    }
}

@Composable
private fun DesktopMonitor(
    engine: BrowserEngine,
    currentUrl: String,
    onNavigate: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        DesktopAddressBar(currentUrl) { url ->
            engine.navigate(url)
            onNavigate(url)
        }
        Box(Modifier.fillMaxSize()) {
            // 浏览器 View 由引擎产出(createView 只调用一次,AndroidView 记住实例)
            AndroidView(
                factory = { ctx -> engine.createView(ctx) },
                modifier = Modifier.fillMaxSize(),
            )
            // 空闲态:无内容时盖一层壁纸(此时 WebView 本就空白,覆盖无碍且保活引擎)
            val idle = currentUrl.isBlank() || currentUrl == "about:blank"
            if (idle) DesktopWallpaper(Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun DesktopAddressBar(currentUrl: String, onGo: (String) -> Unit) {
    // Agent 导航时 currentUrl 变化 → 地址栏跟随刷新
    var text by remember(currentUrl) { mutableStateOf(currentUrl) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OctopusColors.Surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            placeholder = { Text("输入网址…", fontSize = 13.sp) },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = OctopusColors.TextPrimary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = {
                val u = normalizeUrl(text)
                if (u.isNotBlank()) onGo(u)
            }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = OctopusColors.SurfaceVariant,
                unfocusedContainerColor = OctopusColors.SurfaceVariant,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DesktopWallpaper(modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(OctopusBackground.pageBrush()), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Filled.DesktopWindows,
                contentDescription = null,
                tint = OctopusColors.TextMuted,
                modifier = Modifier.size(56.dp),
            )
            Text("Agent 桌面", color = OctopusColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "空闲中 · 在右侧对话下达指令,Agent 会在这块屏幕上操作",
                color = OctopusColors.TextMuted, fontSize = 12.sp,
            )
        }
    }
}

/** 补 scheme:无协议头则默认 https://;看着不像域名的当作占位不导航。 */
private fun normalizeUrl(raw: String): String {
    val t = raw.trim()
    if (t.isEmpty()) return ""
    return when {
        t.startsWith("http://") || t.startsWith("https://") || t.startsWith("about:") -> t
        else -> "https://$t"
    }
}
