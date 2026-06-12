package com.apk.claw.android.ui.compose.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.content.Context
import android.content.Intent
import com.apk.claw.android.R
import com.apk.claw.android.octopus_mobile.browser.SearchEngines
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.ui.browser.BrowserActivity
import com.apk.claw.android.utils.KVUtils

// 打开内置真浏览器：query 为空开首页，否则按「网址/搜索词」处理（BrowserActivity 内部判定）
private fun openBrowser(context: Context, query: String?) {
    val intent = Intent(context, BrowserActivity::class.java)
    if (!query.isNullOrBlank()) intent.putExtra(BrowserActivity.EXTRA_URL, query)
    runCatching { context.startActivity(intent) }
}

// 颜色常量
private val PrimaryColor = Color(0xFF0A84FF)
private val SuccessColor = Color(0xFF30D158)
private val WarningColor = Color(0xFFFF9F0A)
private val ErrorColor = Color(0xFFFF453B)
private val SurfaceColor = Color(0xFF1C1C1E)
private val BackgroundColor = Color(0xFF000000)
private val TextPrimary = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFF98989D)
private val TextMuted = Color(0xFF8E8E93)
private val BorderColor = Color(0xFF38383A)

@Composable
fun DiscoverScreen(onNavigate: (String) -> Unit = {}) {
    val context = LocalContext.current
    var searchText by remember { mutableStateOf("") }
    // 搜索引擎选择（持久化），点搜索框左侧标记可切换
    var engineId by remember { mutableStateOf(KVUtils.getSearchEngine()) }
    var engineMenuOpen by remember { mutableStateOf(false) }
    val engine = SearchEngines.byId(engineId)
    // 提交搜索：用内置真浏览器做网页搜索（网址直达 / 关键词搜索），并清空输入框
    val submit = {
        if (searchText.isNotBlank()) {
            openBrowser(context, searchText.trim())
            searchText = ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundColor)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(60.dp))

        // Logo
        Text(
            text = "🐙",
            fontSize = 42.sp,
            modifier = Modifier.shadow(20.dp, CircleShape)
        )
        Spacer(modifier = Modifier.height(6.dp))

        // 标题
        Text(
            text = "Octopus",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = PrimaryColor,
        )
        Spacer(modifier = Modifier.height(28.dp))

        // 搜索栏
        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.discover_search_hint), color = TextMuted) },
            leadingIcon = {
                // 当前搜索引擎标记，点击切换
                Box {
                    Box(
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .size(30.dp)
                            .background(PrimaryColor.copy(alpha = 0.15f), CircleShape)
                            .clickable { engineMenuOpen = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(engine.tag, color = PrimaryColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    DropdownMenu(
                        expanded = engineMenuOpen,
                        onDismissRequest = { engineMenuOpen = false },
                    ) {
                        SearchEngines.ALL.forEach { e ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        (if (e.id == engineId) "✓ " else "    ") + e.label,
                                        color = if (e.id == engineId) PrimaryColor else TextPrimary,
                                        fontSize = 14.sp,
                                    )
                                },
                                onClick = {
                                    engineId = e.id
                                    KVUtils.setSearchEngine(e.id)
                                    engineMenuOpen = false
                                },
                            )
                        }
                    }
                }
            },
            trailingIcon = {
                // 发送按钮
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        // 输入为空时按钮淡化，提示「无内容可发送」
                        .background(
                            PrimaryColor.copy(alpha = if (searchText.isNotBlank()) 1f else 0.35f),
                            CircleShape
                        )
                        .clickable(onClick = submit),
                    contentAlignment = Alignment.Center
                ) {
                    Text("➤", color = Color.White, fontSize = 14.sp)
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            shape = RoundedCornerShape(20.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = PrimaryColor,
                unfocusedBorderColor = BorderColor,
                focusedContainerColor = SurfaceColor,
                unfocusedContainerColor = SurfaceColor,
                cursorColor = PrimaryColor,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
            ),
            singleLine = true,
            textStyle = TextStyle(fontSize = 15.sp),
        )
        Spacer(modifier = Modifier.height(32.dp))

        // 快捷入口 4x2 网格
        // 浏览器 → 内置真浏览器；插件 → 设置；其余皆为 Agent 任务 → 对话页
        val shortcuts = listOf(
            Triple("🌐", stringResource(R.string.discover_shortcut_browser), "browser"),
            Triple("☁️", stringResource(R.string.discover_shortcut_clouddrive), "chat"),
            Triple("🎬", stringResource(R.string.discover_shortcut_video), "chat"),
            Triple("🧩", stringResource(R.string.discover_shortcut_plugin), "settings"),
            Triple("🖥", stringResource(R.string.discover_shortcut_cast), "chat"),
            Triple("📱", stringResource(R.string.discover_shortcut_multiwindow), "chat"),
            Triple("💾", stringResource(R.string.discover_shortcut_memory), "chat"),
            Triple("🧬", stringResource(R.string.discover_shortcut_evolution), "chat"),
        )

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            shortcuts.chunked(4).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    row.forEach { (icon, name, route) ->
                        ShortcutItem(icon, name) {
                            if (route == "browser") openBrowser(context, null) else onNavigate(route)
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))

        // AI 建议 - 底部横排
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                stringResource(R.string.discover_suggest_weather) to "smart",
                stringResource(R.string.discover_suggest_organize) to "efficiency",
                stringResource(R.string.discover_suggest_resume) to "recommended",
            ).forEach { (text, tag) ->
                SuggestionChip(text, tag) { onNavigate("chat") }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        // 底部状态条 —— 真实数据：会话数 / 无障碍状态 / 模型状态，回到前台时刷新
        val lifecycleOwner = LocalLifecycleOwner.current
        var refreshTick by remember { mutableStateOf(0) }
        DisposableEffect(lifecycleOwner) {
            val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refreshTick++ }
            lifecycleOwner.lifecycle.addObserver(obs)
            onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
        }
        val sessionCount = remember(refreshTick) { runCatching { SessionStore.index().size }.getOrDefault(0) }
        val a11yOk = remember(refreshTick) { ClawAccessibilityService.isRunning() }
        val llmOk = remember(refreshTick) { ChatAgentBridge.isConfigured() }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.discover_stat_sessions, sessionCount), color = TextMuted, fontSize = 10.sp)
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                stringResource(if (a11yOk) R.string.discover_stat_a11y_on else R.string.discover_stat_a11y_off),
                color = if (a11yOk) SuccessColor else TextMuted, fontSize = 10.sp
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                stringResource(if (llmOk) R.string.discover_stat_llm_on else R.string.discover_stat_llm_off),
                color = if (llmOk) SuccessColor else TextMuted, fontSize = 10.sp
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

// 快捷入口项
@Composable
private fun ShortcutItem(icon: String, name: String, onClick: () -> Unit = {}) {
    // 按下时轻微缩放，提供「跟手」的触感反馈
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.90f else 1f, label = "tileScale")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .scale(scale)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            )
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(SurfaceColor, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(icon, fontSize = 22.sp)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(name, fontSize = 11.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
    }
}

// AI 建议标签
@Composable
private fun RowScope.SuggestionChip(text: String, tag: String, onClick: () -> Unit = {}) {
    Box(
        modifier = Modifier
            .weight(1f)
            .background(PrimaryColor.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
            .border(1.dp, PrimaryColor.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, fontSize = 12.sp, color = TextSecondary)
        }
    }
}
