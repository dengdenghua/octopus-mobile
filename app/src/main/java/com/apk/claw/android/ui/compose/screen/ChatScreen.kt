package com.apk.claw.android.ui.compose.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardVoice
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Build
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.launch
import java.util.Calendar

// 颜色
private val PrimaryColor = OctopusColors.Primary
private val SuccessColor = OctopusColors.Success
private val WarningColor = OctopusColors.Warning
private val BackgroundColor = OctopusColors.Background
private val SurfaceColor = OctopusColors.Surface
private val SurfaceVariantColor = OctopusColors.SurfaceVariant
private val TextPrimary = OctopusColors.TextPrimary
private val TextSecondary = OctopusColors.TextSecondary
private val TextMuted = OctopusColors.TextMuted
private val BorderColor = OctopusColors.Border
private val AgentBubbleColor = OctopusColors.Surface
private val UserBubbleColor = OctopusColors.Primary

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
    val scrollEnd = { scope.launch { listState.animateScrollToItem(messages.size) }; Unit }

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
                val appendStream = { tok: String ->
                    hideThinking()
                    val id = streamId
                    if (id == null) {
                        buf = StringBuilder(tok)
                        val m = ChatMessage.AgentMessage(buf.toString())
                        streamId = m.id
                        messages.add(m)
                    } else {
                        buf.append(tok)
                        val idx = messages.indexOfFirst { it.id == id }
                        if (idx >= 0) messages[idx] = ChatMessage.AgentMessage(buf.toString(), id)
                    }
                    scrollEnd()
                }
                // 结束当前流式气泡(final!=null 时用最终文本覆盖;否则定格已流式内容)
                val finalizeStream = { final: String? ->
                    val id = streamId
                    if (id != null && final != null) {
                        val idx = messages.indexOfFirst { it.id == id }
                        if (idx >= 0) messages[idx] = ChatMessage.AgentMessage(final, id)
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
        scrimColor = Color.Black.copy(alpha = 0.48f),
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
                .padding(start = 8.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.chat_sessions), tint = TextSecondary)
            }
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Octopus", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = TextPrimary)
                    val ready = llmOk && a11yOk
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(if (ready) SuccessColor else WarningColor, RoundedCornerShape(3.dp))
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            stringResource(if (ready) R.string.chat_agent_ready else R.string.chat_agent_setup_needed),
                            fontSize = 11.sp,
                            color = TextMuted,
                        )
                    }
                }
            }
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
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(R.string.chat_empty_title),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    letterSpacing = 0.sp,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    stringResource(R.string.chat_empty_hint),
                    fontSize = 13.sp,
                    color = TextMuted,
                    lineHeight = 18.sp,
                )
                Spacer(modifier = Modifier.height(16.dp))
                AgentHomeStatusCard(
                    llmOk = llmOk,
                    a11yOk = a11yOk,
                    isRunning = isRunning,
                    target = ControlTarget.label(),
                    deviceCount = devices.size,
                )
                Spacer(modifier = Modifier.height(14.dp))
                listOf(
                    stringResource(R.string.chat_suggestion_notifications),
                    stringResource(R.string.chat_suggestion_files),
                    stringResource(R.string.chat_suggestion_app),
                ).forEach { suggestion ->
                    PromptSuggestion(suggestion) { inputText = suggestion }
                }
            }
        } else {
        val rows = buildChatRows(messages)
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
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
            item { Spacer(modifier = Modifier.height(8.dp)) }
        }
        }

        // 输入框
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = SurfaceColor,
            border = BorderStroke(1.dp, BorderColor.copy(alpha = 0.7f)),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            // 目标选择器：决定 Agent 在「本机」还是某台局域网设备上执行
            TargetSelector()
            Spacer(modifier = Modifier.height(8.dp))
            if (voiceMode) {
                // ── 语音优先：左侧键盘切换（次选）+ 大麦克风「按住说话」 ──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(OctopusColors.SurfaceDeep, RoundedCornerShape(21.dp))
                            .clickable { voiceMode = false },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Keyboard, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(19.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    val red = Color(0xFFFF453B)
                    val pillColor = if (isRunning || listening) red else OctopusColors.SurfaceDeep
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
                            .background(pillColor, RoundedCornerShape(18.dp))
                            .then(pillGesture),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Mic, contentDescription = null, tint = if (isRunning || listening) Color.White else TextSecondary, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                pillText,
                                color = if (isRunning || listening) Color.White else TextSecondary,
                                fontSize = 14.sp,
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
                            .background(OctopusColors.SurfaceDeep, RoundedCornerShape(21.dp))
                            .clickable { voiceMode = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.KeyboardVoice, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(19.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.chat_input_hint), color = TextMuted) },
                        shape = RoundedCornerShape(18.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryColor,
                            unfocusedBorderColor = BorderColor,
                            focusedContainerColor = OctopusColors.SurfaceDeep,
                            unfocusedContainerColor = OctopusColors.SurfaceDeep,
                            cursorColor = PrimaryColor,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                        ),
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 14.sp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    // 运行中=红色停止键(可中断);否则=发送键(无输入时淡化)
                    val btnActive = isRunning || inputText.isNotBlank()
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(
                                (if (isRunning) Color(0xFFFF453B) else PrimaryColor)
                                    .copy(alpha = if (btnActive) 1f else 0.35f),
                                RoundedCornerShape(21.dp)
                            )
                            .clickable(onClick = if (isRunning) stop else send),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isRunning) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
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
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 18.dp)) {
            Text("Octopus", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.chat_drawer_subtitle), color = TextMuted, fontSize = 12.sp)
            Spacer(Modifier.height(14.dp))
            DrawerStatusPanel(llmOk = llmOk, a11yOk = a11yOk, deviceCount = deviceCount)
            Spacer(Modifier.height(14.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().clickable(enabled = !isRunning, onClick = onNewChat),
                shape = RoundedCornerShape(16.dp),
                color = PrimaryColor.copy(alpha = if (isRunning) 0.12f else 0.18f),
                border = BorderStroke(1.dp, PrimaryColor.copy(alpha = 0.28f)),
            ) {
                Row(modifier = Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = PrimaryColor, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.chat_new), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(16.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        shape = RoundedCornerShape(20.dp),
        color = SurfaceColor,
        border = BorderStroke(1.dp, BorderColor),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SmartToy, contentDescription = null, tint = PrimaryColor, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.chat_home_status_title), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                StatusDot(if (isRunning) WarningColor else if (llmOk && a11yOk) SuccessColor else WarningColor)
                Spacer(Modifier.width(5.dp))
                Text(
                    stringResource(
                        when {
                            isRunning -> R.string.chat_status_running
                            llmOk && a11yOk -> R.string.chat_agent_ready
                            else -> R.string.chat_agent_setup_needed
                        }
                    ),
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                MiniMetric(
                    label = stringResource(R.string.chat_metric_model),
                    value = if (llmOk) stringResource(R.string.status_online) else stringResource(R.string.chat_agent_setup_needed),
                    ok = llmOk,
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = SurfaceColor,
        border = BorderStroke(1.dp, BorderColor),
    ) {
        Row(modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(7.dp).background(OctopusColors.Accent, RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Text(text, modifier = Modifier.weight(1f), color = TextSecondary, fontSize = 13.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun DrawerStatusPanel(llmOk: Boolean, a11yOk: Boolean, deviceCount: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = SurfaceColor,
        border = BorderStroke(1.dp, BorderColor),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            DrawerStatusRow(Icons.Filled.SmartToy, stringResource(R.string.chat_metric_model), llmOk)
            DrawerStatusRow(Icons.Filled.PhoneAndroid, stringResource(R.string.setup_a11y), a11yOk)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Devices, contentDescription = null, tint = PrimaryColor, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.chat_metric_devices), modifier = Modifier.weight(1f), color = TextSecondary, fontSize = 12.sp)
                Text(deviceCount.toString(), color = if (deviceCount > 0) SuccessColor else TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun DrawerStatusRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = if (ok) SuccessColor else TextMuted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, modifier = Modifier.weight(1f), color = TextSecondary, fontSize = 12.sp)
        Text(
            stringResource(if (ok) R.string.status_online else R.string.chat_agent_setup_needed),
            color = if (ok) SuccessColor else WarningColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun MiniMetric(label: String, value: String, ok: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(13.dp),
        color = OctopusColors.SurfaceDeep,
        border = BorderStroke(1.dp, if (ok) PrimaryColor.copy(alpha = 0.16f) else BorderColor),
    ) {
        Column(modifier = Modifier.padding(horizontal = 9.dp, vertical = 9.dp)) {
            Text(label, color = TextMuted, fontSize = 10.sp, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(value, color = if (ok) TextPrimary else WarningColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun DrawerSectionTitle(text: String) {
    Text(
        text,
        color = TextMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 3.dp, top = 6.dp, bottom = 2.dp),
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
        shape = RoundedCornerShape(14.dp),
        color = if (selected) SurfaceVariantColor else SurfaceColor,
        border = BorderStroke(1.dp, if (selected) PrimaryColor.copy(alpha = 0.26f) else BorderColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.ChatBubbleOutline,
                contentDescription = null,
                tint = if (selected) PrimaryColor else TextMuted,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(9.dp))
            Text(
                session.title,
                modifier = Modifier.weight(1f),
                color = if (selected) TextPrimary else TextSecondary,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                "×",
                color = TextMuted,
                fontSize = 16.sp,
                modifier = Modifier.clickable { onDelete(session.id) }.padding(start = 8.dp),
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
private fun TargetSelector() {
    val devices by ClawApplication.instance.deviceRegistry.deviceList.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf(ControlTarget.label()) }
    val remote = remember(label) { ControlTarget.isRemote() }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(
                    (if (remote) PrimaryColor else OctopusColors.SurfaceDeep).copy(alpha = if (remote) 0.16f else 1f),
                    RoundedCornerShape(12.dp)
                )
                .clickable { menu = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Icon(
                if (remote) Icons.Filled.Devices else Icons.Filled.PhoneAndroid,
                contentDescription = null,
                tint = if (remote) PrimaryColor else TextMuted,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(label, color = if (remote) PrimaryColor else TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.width(2.dp))
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = TextMuted, modifier = Modifier.size(15.dp))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_target_local_device)) },
                onClick = { ControlTarget.setLocal(); label = ControlTarget.label(); menu = false },
            )
            devices.forEach { d ->
                DropdownMenuItem(
                    text = { Text("🖥 ${d.deviceName}") },
                    onClick = { ControlTarget.setRemote(d); label = ControlTarget.label(); menu = false },
                )
            }
            // 调试：回环目标（远程控制自己，用于单机验证远程路由）
            if (BuildConfig.DEBUG) {
                DropdownMenuItem(
                    text = { Text("🔁 回环(本机:9527)") },
                    onClick = {
                        val token = runCatching { ConfigServerManager.getAuthToken() }.getOrNull() ?: ""
                        // ConfigServer 绑定在本机 WiFi IP（非 127.0.0.1），用其真实地址回环
                        val addr = runCatching { ConfigServerManager.getAddress() }.getOrNull() ?: "127.0.0.1:9527"
                        val ip = addr.substringBefore(":")
                        val port = addr.substringAfter(":").toIntOrNull() ?: 9527
                        ControlTarget.setRemote(
                            DeviceInfo(
                                deviceId = "loopback",
                                deviceName = "回环",
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
                shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                color = UserBubbleColor,
                // 长按一条指令 → 存为可复用例程
                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { menu = true }),
            ) {
                Text(
                    text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    fontSize = 14.sp,
                    color = Color.White,
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
            shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
            color = AgentBubbleColor,
            border = BorderStroke(1.dp, BorderColor),
        ) {
            Text(
                text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                fontSize = 14.sp,
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(14.dp),
        color = PrimaryColor.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, PrimaryColor.copy(alpha = 0.25f)),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                stringResource(R.string.setup_title),
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            SetupRow(stringResource(R.string.setup_llm), llmOk, onConfigLlm)
            Spacer(modifier = Modifier.height(4.dp))
            SetupRow(stringResource(R.string.setup_a11y), a11yOk, onEnableA11y)
        }
    }
}

@Composable
private fun SetupRow(label: String, done: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !done, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (done) "✓" else "○", fontSize = 13.sp, color = if (done) SuccessColor else TextMuted)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            label, fontSize = 12.sp,
            color = if (done) TextMuted else TextPrimary,
            modifier = Modifier.weight(1f),
        )
        if (!done) Text(stringResource(R.string.setup_go), fontSize = 11.sp, color = PrimaryColor, fontWeight = FontWeight.SemiBold)
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

/** 单个工具直接显示;多个工具折叠成可展开的「N 步骤」块。 */
@Composable
private fun ToolGroupItem(tools: List<ChatMessage.ToolCall>, expanded: Boolean, onToggle: (Long) -> Unit) {
    if (tools.size == 1) { ToolCallItem(tools[0]); return }
    val gid = tools.first().id
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = PrimaryColor.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, PrimaryColor.copy(alpha = 0.15f)),
        modifier = Modifier.clickable { onToggle(gid) },
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Build, contentDescription = null, tint = PrimaryColor, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.chat_tool_steps, tools.size),
                    fontSize = 11.sp, color = PrimaryColor, fontWeight = FontWeight.SemiBold,
                )
                if (!expanded) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "· ${tools.last().toolName}",
                        fontSize = 11.sp, color = TextMuted,
                        fontFamily = FontFamily.Monospace, maxLines = 1,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
            }
            if (expanded) {
                tools.forEach { t ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Build, contentDescription = null, tint = TextMuted, modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(t.toolName, fontSize = 11.sp, color = PrimaryColor, fontFamily = FontFamily.Monospace)
                        if (t.args.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(t.args, fontSize = 10.sp, color = TextMuted, fontFamily = FontFamily.Monospace, maxLines = 1)
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        if (t.result != null) Text(t.result, fontSize = 10.sp, color = SuccessColor, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolCallItem(msg: ChatMessage.ToolCall) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = PrimaryColor.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, PrimaryColor.copy(alpha = 0.15f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Build, contentDescription = null, tint = PrimaryColor, modifier = Modifier.size(15.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                msg.toolName,
                fontSize = 11.sp,
                color = PrimaryColor,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace
            )
            if (msg.args.isNotEmpty()) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(msg.args, fontSize = 11.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
            }
            Spacer(modifier = Modifier.weight(1f))
            if (msg.result != null) {
                Text(msg.result, fontSize = 11.sp, color = SuccessColor)
            }
        }
    }
}

@Composable
private fun ThinkingItem(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("🤔", fontSize = 14.sp)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, fontSize = 12.sp, color = TextSecondary)
        Spacer(modifier = Modifier.width(4.dp))
        // 动画点
        Text("...", fontSize = 12.sp, color = PrimaryColor)
    }
}
