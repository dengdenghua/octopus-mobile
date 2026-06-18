package com.apk.claw.android.ui.compose.screen

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
import com.apk.claw.android.widget.MjpegImageView
import java.net.URLEncoder
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardVoice
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.apk.claw.android.BuildConfig
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.octopus_mobile.ControlTarget
import com.apk.claw.android.octopus_mobile.DeviceInfo
import com.apk.claw.android.octopus_mobile.RoutineStore
import com.apk.claw.android.octopus_mobile.VoiceInput
import com.apk.claw.android.server.ConfigServerManager
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.ui.settings.LlmConfigActivity
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.content.ContextCompat
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusIconSize
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusTints
import com.apk.claw.android.ui.compose.theme.OctopusType
import kotlinx.coroutines.launch
import java.util.Calendar

// 颜色
private val PrimaryColor get() = OctopusColors.Primary
private val SuccessColor get() = OctopusColors.Success
private val WarningColor get() = OctopusColors.Warning
private val ErrorColor get() = OctopusColors.Error
private val AccentColor get() = OctopusColors.Accent
private val BackgroundColor get() = OctopusColors.Background
private val SurfaceColor get() = OctopusColors.Surface
private val SurfaceVariantColor get() = OctopusColors.SurfaceVariant
private val SurfaceDeepColor get() = OctopusColors.SurfaceDeep
private val TextPrimary get() = OctopusColors.TextPrimary
private val TextSecondary get() = OctopusColors.TextSecondary
private val TextMuted get() = OctopusColors.TextMuted
private val BorderColor get() = OctopusColors.Border
private val OverlayDimColor get() = OctopusColors.OverlayDim
private val OnPrimaryColor get() = OctopusColors.OnPrimary
private val VideoBackgroundColor get() = Color.Black
private val AgentBubbleColor get() = OctopusColors.Surface
private val UserBubbleColor get() = OctopusColors.Primary

// 消息数据模型
sealed class ChatMessage {
    /** 稳定唯一 ID，用于 LazyColumn key 参数；不指定时由构造时自动生成 */
    abstract val id: Long

    data class UserMessage(val text: String, override val id: Long = nextId()) : ChatMessage()
    data class AgentMessage(val text: String, override val id: Long = nextId()) : ChatMessage()
    data class ToolCall(
        val icon: String,
        val toolName: String,
        val args: String,
        val result: String?,
        override val id: Long = nextId()
    ) : ChatMessage()
    data class Thinking(val text: String, override val id: Long = nextId()) : ChatMessage()
}

/** 自增 ID 计数器（线程安全）。仅用于 UI 层稳定 key，不参与业务逻辑。 */
private val chatIdCounter = java.util.concurrent.atomic.AtomicLong(0L)
private fun nextId(): Long = chatIdCounter.incrementAndGet()

private const val CHAT_MESSAGES_LIMIT = 240
private const val CHAT_SCROLL_THROTTLE_MS = 180L
private const val CHAT_STREAM_UI_THROTTLE_MS = 80L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen() {
    // 多会话:会话索引 + 当前会话 + 当前会话的消息
    val sessions = remember { mutableStateListOf<SessionStore.SessionMeta>() }
    var currentId by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<ChatMessage>() }

    var inputText by remember { mutableStateOf("") }
    var isRunning by remember { mutableStateOf(false) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var previewDevice by remember { mutableStateOf<DeviceInfo?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val context = LocalContext.current
    val devices by ClawApplication.instance.deviceRegistry.deviceList.collectAsState()
    // 设备已不再独立成页：在主对话界面启动局域网发现 + 配置服务，
    // 使输入框的「目标选择器」能发现设备、本机也可被发现/被控。
    LaunchedEffect(Unit) {
        runCatching { ClawApplication.instance.deviceDiscoveryManager.start() }
        runCatching { ConfigServerManager.start(context) }
    }
    // 设置完成度:每次回到前台重新检测(配置/授权可能在外部页面变更)
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshTick by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refreshTick++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val llmOk = remember(refreshTick) { ChatAgentBridge.isConfigured() }
    val a11yOk = remember(refreshTick) { ClawAccessibilityService.isRunning() }
    // 工具组展开状态(key=组首工具 id),默认折叠
    val expandedGroups = remember { mutableStateMapOf<Long, Boolean>() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val ackText = stringResource(R.string.chat_ack)
    val thinkingText = stringResource(R.string.chat_thinking)
    val newChatTitle = stringResource(R.string.chat_new)
    var lastScrollTs by remember { mutableLongStateOf(0L) }
    val scrollEnd = {
        val now = System.currentTimeMillis()
        if (now - lastScrollTs > CHAT_SCROLL_THROTTLE_MS) {
            lastScrollTs = now
            scope.launch {
                val target = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                listState.scrollToItem(target)
            }
        }
        Unit
    }

    // 运行时消息上限：超过则丢弃最早的部分，防止长会话 OOM/卡顿
    LaunchedEffect(messages.size) {
        if (messages.size > CHAT_MESSAGES_LIMIT) {
            val drop = messages.size - CHAT_MESSAGES_LIMIT
            repeat(drop) { messages.removeAt(0) }
        }
    }

    // 保存当前会话消息 + 用首条用户消息更新会话标题/时间并置顶
    val persist = {
        if (currentId.isNotEmpty()) {
            ChatStore.save(currentId, messages)
            val firstUser = messages.firstOrNull { it is ChatMessage.UserMessage } as? ChatMessage.UserMessage
            if (firstUser != null) {
                val title = firstUser.text.take(18)
                val now = System.currentTimeMillis()
                SessionStore.updateMeta(currentId, title, now)
                val i = sessions.indexOfFirst { it.id == currentId }
                if (i >= 0) sessions[i] = sessions[i].copy(title = title, updatedAt = now)
            }
        }
    }

    // 初始化:确保至少一个会话,加载当前会话
    LaunchedEffect(Unit) {
        if (currentId.isEmpty()) {
            val idx = SessionStore.ensureAtLeastOne(System.currentTimeMillis(), demoSeed())
            sessions.clear(); sessions.addAll(idx)
            currentId = SessionStore.currentId() ?: idx.first().id
            messages.clear(); messages.addAll(ChatStore.load(currentId) ?: emptyList())
            if (messages.isNotEmpty()) listState.scrollToItem(messages.size)
        }
    }
    val switchTo = { id: String ->
        if (id != currentId && id.isNotEmpty()) {
            ChatStore.save(currentId, messages)
            SessionStore.setCurrent(id); currentId = id
            messages.clear(); messages.addAll(ChatStore.load(id) ?: emptyList())
            scrollEnd()
        }
    }
    val newChat = {
        if (currentId.isNotEmpty()) ChatStore.save(currentId, messages)
        val meta = SessionStore.create(System.currentTimeMillis()).copy(title = newChatTitle)
        SessionStore.updateMeta(meta.id, newChatTitle, meta.updatedAt)
        sessions.add(0, meta); currentId = meta.id; messages.clear()
    }
    val deleteSession = { id: String ->
        SessionStore.delete(id)
        sessions.removeAll { it.id == id }
        if (id == currentId) {
            val next = sessions.firstOrNull()?.id
                ?: SessionStore.create(System.currentTimeMillis()).also { sessions.add(0, it) }.id
            SessionStore.setCurrent(next); currentId = next
            messages.clear(); messages.addAll(ChatStore.load(next) ?: emptyList())
        }
    }

    // 发送指令：配置了 LLM 则真正驱动 Agent;运行期间显示「思考中」、发送键变停止键
    val send = {
        val t = inputText.trim()
        if (t.isNotEmpty() && !isRunning) {
            messages.add(ChatMessage.UserMessage(t))
            inputText = ""
            scrollEnd(); persist()
            if (ChatAgentBridge.isConfigured()) {
                isRunning = true
                val thinking = ChatMessage.Thinking(thinkingText)
                val showThinking = { if (!messages.contains(thinking)) { messages.add(thinking); scrollEnd() } }
                val hideThinking = { messages.remove(thinking) }
                // 流式:把 token 累积进同一个气泡(live bubble),实时更新
                var streamId: Long? = null
                var buf = StringBuilder()
                var lastStreamUiTs = 0L
                val flushStream = {
                    val id = streamId
                    if (id != null) {
                        val idx = messages.indexOfFirst { it.id == id }
                        if (idx >= 0) messages[idx] = ChatMessage.AgentMessage(buf.toString(), id)
                    }
                    Unit
                }
                val appendStream = { tok: String ->
                    hideThinking()
                    val id = streamId
                    if (id == null) {
                        buf = StringBuilder(tok)
                        val m = ChatMessage.AgentMessage(buf.toString())
                        streamId = m.id
                        messages.add(m)
                        lastStreamUiTs = System.currentTimeMillis()
                        scrollEnd()
                    } else {
                        buf.append(tok)
                        val now = System.currentTimeMillis()
                        if (now - lastStreamUiTs >= CHAT_STREAM_UI_THROTTLE_MS) {
                            flushStream()
                            lastStreamUiTs = now
                            scrollEnd()
                        }
                    }
                }
                // 结束当前流式气泡(final!=null 时用最终文本覆盖;否则定格已流式内容)
                val finalizeStream = { final: String? ->
                    val id = streamId
                    if (id != null && final != null) {
                        val idx = messages.indexOfFirst { it.id == id }
                        if (idx >= 0) messages[idx] = ChatMessage.AgentMessage(final, id)
                    } else if (id != null) {
                        flushStream()
                    } else if (id == null && final != null) {
                        messages.add(ChatMessage.AgentMessage(final))
                    }
                    streamId = null; buf = StringBuilder()
                }
                showThinking()
                ChatAgentBridge.run(
                    prompt = t,
                    onTool = { icon, name, args, res -> finalizeStream(null); hideThinking(); messages.add(ChatMessage.ToolCall(icon, name, args, res)); showThinking(); persist() },
                    onText = { txt -> appendStream(txt) },
                    onDone = { ans -> hideThinking(); finalizeStream(ans); isRunning = false; scrollEnd(); persist() },
                    onError = { e -> hideThinking(); finalizeStream(null); messages.add(ChatMessage.AgentMessage("⚠️ $e")); isRunning = false; scrollEnd(); persist() },
                )
            } else {
                messages.add(ChatMessage.AgentMessage(ackText))
                scrollEnd(); persist()
            }
        }
    }
    val stop = { ChatAgentBridge.cancel() }
    val closeDrawer = { scope.launch { drawerState.close() }; Unit }

    // ── 语音优先输入：麦克风「按住说话」，松手即发 ──────────────────
    var voiceMode by remember { mutableStateOf(true) }   // 默认语音优先；键盘为次选
    var listening by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }       // 实时部分识别结果
    val voice = remember { VoiceInput(context) }
    DisposableEffect(Unit) { onDispose { voice.destroy() } }

    val startListening = {
        partial = ""
        listening = true
        voice.start(
            onPartial = { partial = it },
            onResult = { txt ->
                listening = false; partial = ""
                if (txt.isNotBlank()) { inputText = txt; send() }
            },
            onError = { err ->
                listening = false; partial = ""
                if (err.isNotEmpty()) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
            },
        )
    }
    // 录音授权：未授权时先请求，授权后再「按住说话」
    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Toast.makeText(
            context,
            if (granted) context.getString(R.string.chat_mic_permission_granted) else context.getString(R.string.chat_mic_permission_denied),
            Toast.LENGTH_SHORT,
        ).show()
    }
    val hasMic = { ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }

    ModalNavigationDrawer(
        drawerState = drawerState,
        scrimColor = OverlayDimColor,
        drawerContent = {
            ChatSessionDrawer(
                sessions = sessions,
                currentId = currentId,
                isRunning = isRunning,
                llmOk = llmOk,
                a11yOk = a11yOk,
                deviceCount = devices.size,
                onNewChat = {
                    if (!isRunning) {
                        newChat()
                        closeDrawer()
                    }
                },
                onSwitch = {
                    switchTo(it)
                    closeDrawer()
                },
                onDelete = { deleteSession(it) },
            )
        },
    ) {
    Column(modifier = Modifier.fillMaxSize().background(BackgroundColor)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
                .padding(start = OctopusSpacing.sm, end = OctopusSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.chat_sessions), tint = TextSecondary)
            }
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text("Octopus", fontWeight = FontWeight.SemiBold, fontSize = OctopusType.titleLg, color = TextPrimary)
                Spacer(Modifier.width(OctopusSpacing.sm))
                val ready = llmOk && a11yOk
                Surface(
                    shape = OctopusShape.capsule,
                    color = SurfaceColor,
                    border = BorderStroke(1.dp, BorderColor),
                    shadowElevation = 1.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(if (ready) SuccessColor else WarningColor, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(OctopusSpacing.xs))
                        Text(
                            stringResource(if (ready) R.string.chat_agent_ready else R.string.chat_agent_setup_needed),
                            fontSize = OctopusType.caption,
                            color = TextMuted,
                        )
                    }
                }
            }
            // 目标选择器:决定 Agent 在「本机」还是某台局域网设备上执行(移到右上,与三点并排)
            TargetSelector(onPreview = { previewDevice = it })
            Spacer(modifier = Modifier.width(OctopusSpacing.xs))
            Box {
                IconButton(onClick = { moreMenuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.common_more), tint = TextPrimary)
                }
                DropdownMenu(expanded = moreMenuOpen, onDismissRequest = { moreMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.routines_title)) },
                        onClick = {
                            moreMenuOpen = false
                            runCatching { context.startActivity(android.content.Intent(context, com.apk.claw.android.ui.featurescreens.RoutinesActivity::class.java)) }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.activity_screen_title)) },
                        onClick = {
                            moreMenuOpen = false
                            runCatching { context.startActivity(android.content.Intent(context, com.apk.claw.android.ui.featurescreens.ActivityActivity::class.java)) }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.trustcenter_title)) },
                        onClick = {
                            moreMenuOpen = false
                            runCatching { context.startActivity(android.content.Intent(context, com.apk.claw.android.ui.featurescreens.TrustCenterActivity::class.java)) }
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_clear_current)) },
                        enabled = !isRunning,
                        onClick = {
                            moreMenuOpen = false
                            messages.clear()
                            ChatStore.clear(currentId)
                        },
                    )
                }
            }
        }

        // 设备实时预览(选了远程设备后出现在上半屏);「进入控制」开横屏全控页
        previewDevice?.let { dev ->
            key(dev.deviceId) {
                DevicePreviewPanel(
                    device = dev,
                    onEnter = { runCatching { com.apk.claw.android.ui.device.RemoteControlActivity.start(context, dev.deviceId) } },
                    onClose = { previewDevice = null },
                )
            }
        }

        // 设置指引:未配置完成时显示,配齐后自动隐藏
        if (!llmOk || !a11yOk) {
            SetupGuideCard(
                llmOk = llmOk,
                a11yOk = a11yOk,
                onConfigLlm = { runCatching { context.startActivity(android.content.Intent(context, LlmConfigActivity::class.java)) } },
                onEnableA11y = { runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) } },
            )
        }

        // 消息列表 / 空状态
        if (messages.none { it !is ChatMessage.Thinking }) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = OctopusSpacing.lg),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(R.string.chat_empty_title),
                    fontSize = OctopusType.display,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    letterSpacing = 0.sp,
                )
                Spacer(modifier = Modifier.height(OctopusSpacing.sm))
                Text(
                    stringResource(R.string.chat_empty_hint),
                    fontSize = OctopusType.body,
                    color = TextMuted,
                    lineHeight = 18.sp,
                )
                Spacer(modifier = Modifier.height(OctopusSpacing.lg))
                AgentHomeStatusCard(
                    llmOk = llmOk,
                    a11yOk = a11yOk,
                    isRunning = isRunning,
                    target = ControlTarget.label(),
                    deviceCount = devices.size,
                )
                Spacer(modifier = Modifier.height(OctopusSpacing.lg))
                listOf(
                    stringResource(R.string.chat_suggestion_notifications),
                    stringResource(R.string.chat_suggestion_files),
                    stringResource(R.string.chat_suggestion_app),
                ).forEach { suggestion ->
                    PromptSuggestion(suggestion) { inputText = suggestion; send() }
                }
            }
        } else {
        val rows by remember { derivedStateOf { buildChatRows(messages) } }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = OctopusSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(OctopusSpacing.md),
        ) {
            items(rows, key = { it.key }) { row ->
                when (row) {
                    is ChatRow.Single -> when (val msg = row.msg) {
                        is ChatMessage.UserMessage -> UserBubble(msg.text)
                        is ChatMessage.AgentMessage -> AgentBubble(msg.text)
                        is ChatMessage.ToolCall -> ToolCallItem(msg)
                        is ChatMessage.Thinking -> ThinkingItem(msg.text)
                    }
                    is ChatRow.ToolGroup -> ToolGroupItem(
                        tools = row.tools,
                        expanded = expandedGroups[row.tools.first().id] == true,
                        onToggle = { id -> expandedGroups[id] = expandedGroups[id] != true },
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(OctopusSpacing.sm)) }
        }
        }

        // 输入框
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = SurfaceColor,
            border = BorderStroke(1.dp, BorderColor.copy(alpha = 0.7f)),
            shadowElevation = 1.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.md)) {
            if (voiceMode) {
                // ── 语音优先：左侧键盘切换（次选）+ 大麦克风「按住说话」 ──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(SurfaceDeepColor, CircleShape)
                            .clickable { voiceMode = false },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Keyboard, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(OctopusIconSize.medium))
                    }
                    Spacer(modifier = Modifier.width(OctopusSpacing.md))
                    val red = ErrorColor
                    val pillColor = if (isRunning || listening) red else SurfaceDeepColor
                    val listeningText = stringResource(R.string.chat_voice_listening_release)
                    val pillText = when {
                        isRunning -> stringResource(R.string.chat_voice_stop_agent)
                        listening -> partial.ifBlank { listeningText }
                        else -> stringResource(R.string.chat_voice_hold_to_speak)
                    }
                    // 运行中=点按停止；否则=按住说话（松手即发）
                    val pillGesture = if (isRunning) {
                        Modifier.clickable(onClick = stop)
                    } else {
                        Modifier.pointerInput(Unit) {
                            detectTapGestures(onPress = {
                                if (hasMic()) {
                                    startListening()
                                    tryAwaitRelease()
                                    voice.stop()   // 松手 → 收尾 → onResult 自动 send()
                                } else {
                                    micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            })
                        }
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .background(pillColor, OctopusShape.large)
                            .then(pillGesture),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Mic, contentDescription = null, tint = if (isRunning || listening) OnPrimaryColor else TextSecondary, modifier = Modifier.size(OctopusIconSize.small))
                            Spacer(Modifier.width(OctopusSpacing.sm))
                            Text(
                                pillText,
                                color = if (isRunning || listening) OnPrimaryColor else TextSecondary,
                                fontSize = OctopusType.bodyStrong,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                            )
                        }
                    }
                }
            } else {
                // ── 文本模式：左侧麦克风切回 + 文本框 + 发送/停止 ──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(SurfaceDeepColor, CircleShape)
                            .clickable { voiceMode = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.KeyboardVoice, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(OctopusIconSize.medium))
                    }
                    Spacer(modifier = Modifier.width(OctopusSpacing.md))
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.chat_input_hint), color = TextMuted) },
                        shape = OctopusShape.large,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryColor,
                            unfocusedBorderColor = BorderColor,
                            focusedContainerColor = SurfaceDeepColor,
                            unfocusedContainerColor = SurfaceDeepColor,
                            cursorColor = PrimaryColor,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                        ),
                        singleLine = true,
                        textStyle = TextStyle(fontSize = OctopusType.bodyStrong),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                    )
                    Spacer(modifier = Modifier.width(OctopusSpacing.md))
                    // 运行中=红色停止键(可中断);否则=发送键(无输入时淡化)
                    val btnActive = isRunning || inputText.isNotBlank()
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(
                                (if (isRunning) ErrorColor else PrimaryColor)
                                    .copy(alpha = if (btnActive) 1f else 0.35f),
                                CircleShape
                            )
                            .clickable(onClick = if (isRunning) stop else send),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isRunning) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            tint = OnPrimaryColor,
                            modifier = Modifier.size(OctopusIconSize.medium),
                        )
                    }
                }
            }
            }
        }
    }
    }
}

@Composable
private fun ChatSessionDrawer(
    sessions: List<SessionStore.SessionMeta>,
    currentId: String,
    isRunning: Boolean,
    llmOk: Boolean,
    a11yOk: Boolean,
    deviceCount: Int,
    onNewChat: () -> Unit,
    onSwitch: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val now = remember { System.currentTimeMillis() }
    val grouped = remember(sessions, now) { sessions.groupBy { sessionBucket(it.updatedAt, now) } }
    ModalDrawerSheet(
        drawerContainerColor = BackgroundColor,
        drawerContentColor = TextPrimary,
        modifier = Modifier.fillMaxHeight().width(304.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.lg)) {
            Text("Octopus", color = TextPrimary, fontSize = OctopusType.headlineSm, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(OctopusSpacing.xs))
            Text(stringResource(R.string.chat_drawer_subtitle), color = TextMuted, fontSize = OctopusType.label)
            Spacer(Modifier.height(OctopusSpacing.lg))
            DrawerStatusPanel(llmOk = llmOk, a11yOk = a11yOk, deviceCount = deviceCount)
            Spacer(Modifier.height(OctopusSpacing.lg))
            Surface(
                modifier = Modifier.fillMaxWidth().clickable(enabled = !isRunning, onClick = onNewChat),
                shape = OctopusShape.large,
                color = PrimaryColor.copy(alpha = if (isRunning) 0.12f else 0.18f),
                border = BorderStroke(1.dp, PrimaryColor.copy(alpha = 0.28f)),
            ) {
                Row(modifier = Modifier.padding(OctopusSpacing.md), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = PrimaryColor, modifier = Modifier.size(OctopusIconSize.medium))
                    Spacer(Modifier.width(OctopusSpacing.md))
                    Text(stringResource(R.string.chat_new), color = TextPrimary, fontSize = OctopusType.bodyStrong, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(OctopusSpacing.lg))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(OctopusSpacing.sm)) {
                listOf(SessionBucket.Today, SessionBucket.Recent, SessionBucket.Earlier).forEach { bucket ->
                    val bucketSessions = grouped[bucket].orEmpty()
                    if (bucketSessions.isNotEmpty()) {
                        item(key = "h_$bucket") { DrawerSectionTitle(bucket.label()) }
                        items(bucketSessions, key = { it.id }) { session ->
                            SessionDrawerRow(
                                session = session,
                                selected = session.id == currentId,
                                onSwitch = onSwitch,
                                onDelete = onDelete,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentHomeStatusCard(
    llmOk: Boolean,
    a11yOk: Boolean,
    isRunning: Boolean,
    target: String,
    deviceCount: Int,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = OctopusShape.xl,
        color = SurfaceColor,
        border = BorderStroke(1.dp, BorderColor),
        shadowElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(OctopusSpacing.lg), verticalArrangement = Arrangement.spacedBy(OctopusSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SmartToy, contentDescription = null, tint = PrimaryColor, modifier = Modifier.size(OctopusIconSize.medium))
                Spacer(Modifier.width(OctopusSpacing.sm))
                Text(stringResource(R.string.chat_home_status_title), color = TextPrimary, fontSize = OctopusType.bodyStrong, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                StatusDot(if (isRunning) WarningColor else if (llmOk && a11yOk) SuccessColor else WarningColor)
                Spacer(Modifier.width(OctopusSpacing.xs))
                Text(
                    stringResource(
                        when {
                            isRunning -> R.string.chat_status_running
                            llmOk && a11yOk -> R.string.chat_agent_ready
                            else -> R.string.chat_agent_setup_needed
                        }
                    ),
                    color = TextSecondary,
                    fontSize = OctopusType.caption,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm), modifier = Modifier.fillMaxWidth()) {
                MiniMetric(
                    label = stringResource(R.string.setup_a11y),
                    value = if (a11yOk) stringResource(R.string.status_online) else stringResource(R.string.chat_agent_setup_needed),
                    ok = a11yOk,
                    modifier = Modifier.weight(1f),
                )
                MiniMetric(
                    label = stringResource(R.string.chat_metric_target),
                    value = target,
                    ok = true,
                    modifier = Modifier.weight(1f),
                )
                MiniMetric(
                    label = stringResource(R.string.chat_metric_devices),
                    value = deviceCount.toString(),
                    ok = deviceCount > 0,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PromptSuggestion(text: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = OctopusSpacing.xs).clickable(onClick = onClick),
        shape = OctopusShape.large,
        color = SurfaceColor,
        border = BorderStroke(1.dp, BorderColor),
        shadowElevation = 1.dp,
    ) {
        Row(modifier = Modifier.padding(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.md), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(7.dp).background(AccentColor, RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.width(OctopusSpacing.md))
            Text(text, modifier = Modifier.weight(1f), color = TextSecondary, fontSize = OctopusType.body, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun DrawerStatusPanel(llmOk: Boolean, a11yOk: Boolean, deviceCount: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = OctopusShape.large,
        color = SurfaceColor,
        border = BorderStroke(1.dp, BorderColor),
        shadowElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(OctopusSpacing.md), verticalArrangement = Arrangement.spacedBy(OctopusSpacing.sm)) {
            DrawerStatusRow(Icons.Filled.PhoneAndroid, stringResource(R.string.setup_a11y), a11yOk)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Devices, contentDescription = null, tint = PrimaryColor, modifier = Modifier.size(OctopusIconSize.small))
                Spacer(Modifier.width(OctopusSpacing.sm))
                Text(stringResource(R.string.chat_metric_devices), modifier = Modifier.weight(1f), color = TextSecondary, fontSize = OctopusType.label)
                Text(deviceCount.toString(), color = if (deviceCount > 0) SuccessColor else TextMuted, fontSize = OctopusType.label, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun DrawerStatusRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = if (ok) SuccessColor else TextMuted, modifier = Modifier.size(OctopusIconSize.small))
        Spacer(Modifier.width(OctopusSpacing.sm))
        Text(label, modifier = Modifier.weight(1f), color = TextSecondary, fontSize = OctopusType.label)
        Text(
            stringResource(if (ok) R.string.status_online else R.string.chat_agent_setup_needed),
            color = if (ok) SuccessColor else WarningColor,
            fontSize = OctopusType.caption,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun MiniMetric(label: String, value: String, ok: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = OctopusShape.large,
        color = SurfaceDeepColor,
        border = BorderStroke(1.dp, if (ok) PrimaryColor.copy(alpha = 0.16f) else BorderColor),
        shadowElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = OctopusSpacing.sm, vertical = OctopusSpacing.sm)) {
            Text(label, color = TextMuted, fontSize = OctopusType.tag, maxLines = 1)
            Spacer(Modifier.height(OctopusSpacing.xs))
            Text(value, color = if (ok) TextPrimary else WarningColor, fontSize = OctopusType.label, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun DrawerSectionTitle(text: String) {
    Text(
        text,
        color = TextMuted,
        fontSize = OctopusType.caption,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = OctopusSpacing.xs, top = OctopusSpacing.sm, bottom = OctopusSpacing.xs),
    )
}

@Composable
private fun SessionDrawerRow(
    session: SessionStore.SessionMeta,
    selected: Boolean,
    onSwitch: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onSwitch(session.id) },
        shape = OctopusShape.large,
        color = if (selected) SurfaceVariantColor else SurfaceColor,
        border = BorderStroke(1.dp, if (selected) PrimaryColor.copy(alpha = 0.26f) else BorderColor),
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.ChatBubbleOutline,
                contentDescription = null,
                tint = if (selected) PrimaryColor else TextMuted,
                modifier = Modifier.size(OctopusIconSize.small),
            )
            Spacer(Modifier.width(OctopusSpacing.sm))
            Text(
                session.title,
                modifier = Modifier.weight(1f),
                color = if (selected) TextPrimary else TextSecondary,
                fontSize = OctopusType.body,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                "×",
                color = TextMuted,
                fontSize = OctopusType.title,
                modifier = Modifier.clickable { onDelete(session.id) }.padding(start = OctopusSpacing.sm),
            )
        }
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(modifier = Modifier.size(8.dp).background(color, RoundedCornerShape(4.dp)))
}

private enum class SessionBucket { Today, Recent, Earlier }

private fun sessionBucket(updatedAt: Long, now: Long): SessionBucket {
    val todayStart = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return when {
        updatedAt >= todayStart -> SessionBucket.Today
        updatedAt >= todayStart - 6L * 24L * 60L * 60L * 1000L -> SessionBucket.Recent
        else -> SessionBucket.Earlier
    }
}

@Composable
private fun SessionBucket.label(): String = stringResource(
    when (this) {
        SessionBucket.Today -> R.string.chat_sessions_today
        SessionBucket.Recent -> R.string.chat_sessions_recent
        SessionBucket.Earlier -> R.string.chat_sessions_earlier
    }
)

@Composable
private fun TargetSelector(onPreview: (DeviceInfo?) -> Unit = {}) {
    val devices by ClawApplication.instance.deviceRegistry.deviceList.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf(ControlTarget.label()) }
    val remote = remember(label) { ControlTarget.isRemote() }
    val loopbackName = stringResource(R.string.chat_loopback_device)  // 提前取,onClick 内不能调 @Composable
    Box {
        // 只一个「手机/电脑」复合图标;点击才弹出设备列表。远程时高亮 + 右上角小圆点提示。
        IconButton(onClick = { menu = true }) {
            BadgedBox(
                badge = { if (remote) Badge(containerColor = PrimaryColor, modifier = Modifier.size(7.dp)) }
            ) {
                Icon(
                    Icons.Filled.Devices,
                    contentDescription = label,
                    tint = if (remote) PrimaryColor else TextSecondary,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_target_local_device)) },
                onClick = { ControlTarget.setLocal(); label = ControlTarget.label(); menu = false; onPreview(null) },
            )
            devices.forEach { d ->
                DropdownMenuItem(
                    text = { Text("🖥 ${d.deviceName}") },
                    onClick = { ControlTarget.setRemote(d); label = ControlTarget.label(); menu = false; onPreview(d) },
                )
            }
            // 调试：回环目标（远程控制自己，用于单机验证远程路由）
            if (BuildConfig.DEBUG) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_loopback)) },
                    onClick = {
                        val token = runCatching { ConfigServerManager.getAuthToken() }.getOrNull() ?: ""
                        // ConfigServer 绑定在本机 WiFi IP（非 127.0.0.1），用其真实地址回环
                        val addr = runCatching { ConfigServerManager.getAddress() }.getOrNull() ?: "127.0.0.1:9527"
                        val ip = addr.substringBefore(":")
                        val port = addr.substringAfter(":").toIntOrNull() ?: 9527
                        ControlTarget.setRemote(
                            DeviceInfo(
                                deviceId = "loopback",
                                deviceName = loopbackName,
                                ip = ip,
                                configServerPort = port,
                                authToken = token,
                            )
                        )
                        label = ControlTarget.label()
                        menu = false
                    },
                )
            }
        }
    }
}

/**
 * 设备实时预览面板(上半屏)。点设备后出现:MJPEG 直播画面 + 「进入控制」开横屏全控页。
 * 用 key(deviceId) 保证切设备时整块重建,串流随之重启;离开组合时 onRelease 停流。
 */
@Composable
private fun DevicePreviewPanel(device: DeviceInfo, onEnter: () -> Unit, onClose: () -> Unit) {
    // Marvis 风格:紧贴顶栏的一块干净圆角实时画面,无内部标题栏;点画面=进入控制,下方一根拖拽柄。
    val previewH = (LocalConfiguration.current.screenHeightDp * 0.42f).dp
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = OctopusSpacing.md).padding(top = OctopusSpacing.sm)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(previewH)
                .clip(OctopusShape.large)
                .background(VideoBackgroundColor)
                .clickable { onEnter() },
        ) {
            AndroidView(
                factory = { ctx ->
                    MjpegImageView(ctx).apply {
                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        setBackgroundColor(android.graphics.Color.BLACK)
                        val token = URLEncoder.encode(device.authToken, "UTF-8")
                        start("${device.getBaseUrl()}/api/screen/stream?quality=45&maxWidth=600&fps=10&token=$token")
                    }
                },
                onRelease = { it.stop() },
                modifier = Modifier.fillMaxSize(),
            )
            // 左上角:设备名小药丸(画面内做上下文,不占独立标题栏)
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(OctopusSpacing.sm)
                    .clip(RoundedCornerShape(10.dp))
                    .background(OverlayDimColor)
                    .padding(horizontal = OctopusSpacing.sm, vertical = OctopusSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(SuccessColor))
                Spacer(Modifier.width(OctopusSpacing.xs))
                Text(device.deviceName, color = OnPrimaryColor, fontSize = OctopusType.caption, fontWeight = FontWeight.Medium, maxLines = 1)
            }
            // 右上角:关闭
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(OctopusSpacing.sm)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(OverlayDimColor)
                    .clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.feature_close), tint = OnPrimaryColor, modifier = Modifier.size(OctopusIconSize.small))
            }
        }
        // 拖拽柄(视觉提示:可点画面进入全控)
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = OctopusSpacing.sm), contentAlignment = Alignment.Center) {
            Box(modifier = Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(BorderColor))
        }
    }
}

/** 首次启动（无持久化历史）时展示的演示对话。 */
private fun demoSeed(): List<ChatMessage> = listOf(
    ChatMessage.UserMessage(ClawApplication.instance.getString(R.string.chat_demo_open_wechat)),
    ChatMessage.ToolCall("🔍", "get_screen_info", "", ClawApplication.instance.getString(R.string.chat_demo_main_screen)),
    ChatMessage.ToolCall("📱", "open_app", "com.tencent.mm", "✓"),
    ChatMessage.ToolCall("👆", "tap", "(540, 380)", ClawApplication.instance.getString(R.string.chat_demo_search)),
    ChatMessage.ToolCall("⌨️", "input_text", "(\"${ClawApplication.instance.getString(R.string.chat_demo_contact_name)}\")", "✓"),
    ChatMessage.ToolCall("👆", "tap", "(270, 280)", ClawApplication.instance.getString(R.string.chat_demo_xiaoming)),
    ChatMessage.ToolCall("⌨️", "input_text", "(\"${ClawApplication.instance.getString(R.string.chat_demo_dinner_msg)}\")", "✓"),
    ChatMessage.ToolCall("👆", "tap", "(980, 1820)", ClawApplication.instance.getString(R.string.chat_demo_send)),
    ChatMessage.AgentMessage(ClawApplication.instance.getString(R.string.chat_demo_wechat_sent)),
    ChatMessage.UserMessage(ClawApplication.instance.getString(R.string.chat_demo_check_weather)),
    ChatMessage.ToolCall("📱", "open_app", "com.miui.weather", "✓"),
    ChatMessage.ToolCall("🔍", "get_screen_info", "", ClawApplication.instance.getString(R.string.chat_demo_weather_details)),
    ChatMessage.AgentMessage(ClawApplication.instance.getString(R.string.chat_demo_weather_forecast)),
)

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun UserBubble(text: String) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd
    ) {
        Box {
            Surface(
                shape = OctopusShape.userBubble,
                color = UserBubbleColor,
                shadowElevation = 1.dp,
                // 长按一条指令 → 存为可复用例程
                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { menu = true }),
            ) {
                Text(
                    text,
                    modifier = Modifier.padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.md),
                    fontSize = OctopusType.bodyStrong,
                    color = OnPrimaryColor,
                    lineHeight = 21.sp,
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_save_as_routine)) },
                    onClick = {
                        menu = false
                        val now = System.currentTimeMillis()
                        RoutineStore.add(
                            RoutineStore.Routine(
                                id = "rt_$now",
                                name = text.take(20),
                                prompt = text,
                                targetId = ControlTarget.id(),
                                targetLabel = ControlTarget.label(),
                                createdAt = now,
                            )
                        )
                        Toast.makeText(context, context.getString(R.string.chat_routine_saved_toast), Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    }
}

@Composable
private fun AgentBubble(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        Surface(
            shape = OctopusShape.agentBubble,
            color = AgentBubbleColor,
            border = BorderStroke(1.dp, BorderColor),
            shadowElevation = 1.dp,
        ) {
            Text(
                text,
                modifier = Modifier.padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.md),
                fontSize = OctopusType.bodyStrong,
                color = TextPrimary,
                lineHeight = 21.sp,
            )
        }
    }
}

// ── 设置指引 ──────────────────────────────────────────

@Composable
private fun SetupGuideCard(
    llmOk: Boolean,
    a11yOk: Boolean,
    onConfigLlm: () -> Unit,
    onEnableA11y: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.sm),
        shape = OctopusShape.large,
        color = PrimaryColor.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, PrimaryColor.copy(alpha = 0.25f)),
        shadowElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(OctopusSpacing.lg)) {
            Text(
                stringResource(R.string.setup_title),
                fontSize = OctopusType.body, fontWeight = FontWeight.SemiBold, color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(OctopusSpacing.sm))
            SetupRow(stringResource(R.string.setup_llm), llmOk, onConfigLlm)
            Spacer(modifier = Modifier.height(OctopusSpacing.xs))
            SetupRow(stringResource(R.string.setup_a11y), a11yOk, onEnableA11y)
        }
    }
}

@Composable
private fun SetupRow(label: String, done: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !done, onClick = onClick).padding(vertical = OctopusSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (done) "✓" else "○", fontSize = OctopusType.body, color = if (done) SuccessColor else TextMuted)
        Spacer(modifier = Modifier.width(OctopusSpacing.sm))
        Text(
            label, fontSize = OctopusType.label,
            color = if (done) TextMuted else TextPrimary,
            modifier = Modifier.weight(1f),
        )
        if (!done) Text(stringResource(R.string.setup_go), fontSize = OctopusType.caption, color = PrimaryColor, fontWeight = FontWeight.SemiBold)
    }
}

// ── 工具步骤折叠 ──────────────────────────────────────

/** 渲染行：普通消息 or 连续工具调用组。 */
private sealed class ChatRow {
    abstract val key: String
    data class Single(val msg: ChatMessage) : ChatRow() { override val key = "m${msg.id}" }
    data class ToolGroup(val tools: List<ChatMessage.ToolCall>) : ChatRow() { override val key = "g${tools.first().id}" }
}

/** 把连续的 ToolCall 合并成一组，其余消息原样保留。 */
private fun buildChatRows(msgs: List<ChatMessage>): List<ChatRow> {
    val out = mutableListOf<ChatRow>()
    var i = 0
    while (i < msgs.size) {
        val m = msgs[i]
        if (m is ChatMessage.ToolCall) {
            val group = mutableListOf<ChatMessage.ToolCall>()
            while (i < msgs.size && msgs[i] is ChatMessage.ToolCall) {
                group.add(msgs[i] as ChatMessage.ToolCall); i++
            }
            out.add(ChatRow.ToolGroup(group))
        } else {
            out.add(ChatRow.Single(m)); i++
        }
    }
    return out
}

private fun toolIcon(name: String): androidx.compose.ui.graphics.vector.ImageVector = when (name) {
    "get_screen_info" -> Icons.Filled.Visibility
    "open_app" -> Icons.AutoMirrored.Filled.Launch
    "tap", "long_press" -> Icons.Filled.TouchApp
    "input_text" -> Icons.Filled.Keyboard
    "swipe", "scroll_to_find" -> Icons.Filled.Swipe
    "take_screenshot" -> Icons.Filled.CameraAlt
    "wait" -> Icons.Filled.Timer
    "system_key" -> Icons.Filled.SettingsRemote
    "find_and_tap", "find_text", "find_node" -> Icons.Filled.Search
    "launch_freeform" -> Icons.AutoMirrored.Filled.OpenInNew
    else -> Icons.Filled.Build
}

private fun toolColor(name: String): Color = when (name) {
    "get_screen_info" -> OctopusTints.Window
    "open_app" -> OctopusTints.Plugin
    "tap", "long_press", "swipe", "scroll_to_find" -> OctopusTints.Routine
    "input_text" -> OctopusTints.Memory
    "take_screenshot" -> OctopusTints.Video
    "wait" -> OctopusTints.Trust
    "system_key" -> OctopusTints.Skill
    "find_and_tap", "find_text", "find_node" -> OctopusTints.Browser
    "launch_freeform" -> OctopusTints.Cloud
    else -> PrimaryColor
}

/** 单个工具直接显示;多个工具折叠成可展开的「N 步骤」块。 */
@Composable
private fun ToolGroupItem(tools: List<ChatMessage.ToolCall>, expanded: Boolean, onToggle: (Long) -> Unit) {
    if (tools.size == 1) { ToolCallItem(tools[0]); return }
    val gid = tools.first().id
    val firstTint = toolColor(tools.first().toolName)
    val firstIcon = toolIcon(tools.first().toolName)
    Surface(
        shape = OctopusShape.medium,
        color = PrimaryColor.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, PrimaryColor.copy(alpha = 0.15f)),
        modifier = Modifier.clickable { onToggle(gid) },
        shadowElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolColorBar(firstTint)
                Spacer(modifier = Modifier.width(OctopusSpacing.sm))
                Icon(firstIcon, contentDescription = null, tint = firstTint, modifier = Modifier.size(OctopusIconSize.small))
                Spacer(modifier = Modifier.width(OctopusSpacing.sm))
                Text(
                    stringResource(R.string.chat_tool_steps, tools.size),
                    fontSize = OctopusType.caption, color = PrimaryColor, fontWeight = FontWeight.SemiBold,
                )
                if (!expanded) {
                    Spacer(modifier = Modifier.width(OctopusSpacing.sm))
                    Text(
                        "· ${tools.last().toolName}",
                        fontSize = OctopusType.caption, color = TextMuted,
                        fontFamily = FontFamily.Monospace, maxLines = 1,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = TextMuted, modifier = Modifier.size(OctopusIconSize.small))
            }
            if (expanded) {
                tools.forEach { t ->
                    val tint = toolColor(t.toolName)
                    val icon = toolIcon(t.toolName)
                    Spacer(modifier = Modifier.height(OctopusSpacing.sm))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ToolColorBar(tint)
                        Spacer(modifier = Modifier.width(OctopusSpacing.sm))
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(OctopusIconSize.small))
                        Spacer(modifier = Modifier.width(OctopusSpacing.sm))
                        Text(t.toolName, fontSize = OctopusType.caption, color = PrimaryColor, fontFamily = FontFamily.Monospace)
                        if (t.args.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(OctopusSpacing.xs))
                            Text(t.args, fontSize = OctopusType.tag, color = TextMuted, fontFamily = FontFamily.Monospace, maxLines = 1)
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        if (t.result != null) Text(t.result, fontSize = OctopusType.tag, color = SuccessColor, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolColorBar(color: Color) {
    Box(
        modifier = Modifier
            .width(3.dp)
            .height(20.dp)
            .background(color, RoundedCornerShape(2.dp))
    )
}

@Composable
private fun ToolCallItem(msg: ChatMessage.ToolCall) {
    val tint = toolColor(msg.toolName)
    val icon = toolIcon(msg.toolName)
    Surface(
        shape = OctopusShape.medium,
        color = PrimaryColor.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, PrimaryColor.copy(alpha = 0.15f)),
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolColorBar(tint)
            Spacer(modifier = Modifier.width(OctopusSpacing.sm))
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(OctopusIconSize.small))
            Spacer(modifier = Modifier.width(OctopusSpacing.sm))
            Text(
                msg.toolName,
                fontSize = OctopusType.caption,
                color = PrimaryColor,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace
            )
            if (msg.args.isNotEmpty()) {
                Spacer(modifier = Modifier.width(OctopusSpacing.xs))
                Text(msg.args, fontSize = OctopusType.caption, color = TextMuted, fontFamily = FontFamily.Monospace)
            }
            Spacer(modifier = Modifier.weight(1f))
            if (msg.result != null) {
                Text(msg.result, fontSize = OctopusType.caption, color = SuccessColor)
            }
        }
    }
}

@Composable
private fun ThinkingItem(text: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(PrimaryColor.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Psychology,
                contentDescription = null,
                tint = PrimaryColor,
                modifier = Modifier.size(OctopusIconSize.medium),
            )
        }
        Spacer(modifier = Modifier.width(OctopusSpacing.sm))
        Text(text, fontSize = OctopusType.label, color = TextSecondary)
        Spacer(modifier = Modifier.width(OctopusSpacing.sm))
        (0..2).forEach { i ->
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 400, delayMillis = i * 150),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Text(
                "·",
                fontSize = OctopusType.title,
                fontWeight = FontWeight.Bold,
                color = PrimaryColor.copy(alpha = alpha),
            )
        }
    }
}
