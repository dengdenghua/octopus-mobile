package com.apk.claw.android.ui.compose.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.R
import kotlinx.coroutines.launch

// 颜色
private val PrimaryColor = Color(0xFF0A84FF)
private val SuccessColor = Color(0xFF30D158)
private val WarningColor = Color(0xFFFF9F0A)
private val BackgroundColor = Color(0xFF000000)
private val SurfaceColor = Color(0xFF1C1C1E)
private val TextPrimary = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFF98989D)
private val TextMuted = Color(0xFF8E8E93)
private val BorderColor = Color(0xFF38383A)
private val AgentBubbleColor = Color(0xFF1C1C1E)
private val UserBubbleColor = Color(0xFF0A84FF)

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
    var remoteMode by remember { mutableStateOf(true) }
    var isRunning by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
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

    Column(modifier = Modifier.fillMaxSize().background(BackgroundColor)) {
        // 顶部栏
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Octopus", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(10.dp))
                    // 决策模式胶囊
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = PrimaryColor.copy(alpha = 0.12f),
                        modifier = Modifier.clickable { remoteMode = !remoteMode }
                    ) {
                        Text(
                            stringResource(if (remoteMode) R.string.chat_remote else R.string.chat_local),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PrimaryColor,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    // 在线状态
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(8.dp).background(SuccessColor, RoundedCornerShape(4.dp)))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.status_online), fontSize = 11.sp, color = TextSecondary)
                    }
                }
            },
            actions = {
                // 新建会话
                IconButton(onClick = { if (!isRunning) newChat() }) {
                    Text("＋", fontSize = 20.sp, color = TextPrimary)
                }
                // 会话列表(切换/删除)
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Text("☰", fontSize = 16.sp, color = TextPrimary)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        sessions.forEach { s ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        (if (s.id == currentId) "• " else "") + s.title,
                                        fontWeight = if (s.id == currentId) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                    )
                                },
                                onClick = { switchTo(s.id); menuOpen = false },
                                trailingIcon = {
                                    Text("✕", fontSize = 13.sp, color = TextMuted,
                                        modifier = Modifier.clickable { deleteSession(s.id) })
                                },
                            )
                        }
                    }
                }
                // 清空当前会话
                IconButton(onClick = {
                    if (!isRunning) { messages.clear(); ChatStore.clear(currentId) }
                }) {
                    Text("🗑️", fontSize = 16.sp)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = BackgroundColor,
                titleContentColor = TextPrimary,
            )
        )

        // 消息列表 / 空状态
        if (messages.none { it !is ChatMessage.Thinking }) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.chat_empty_hint),
                    fontSize = 13.sp,
                    color = TextMuted,
                )
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
            color = BackgroundColor.copy(alpha = 0.95f),
        ) {
            Row(
                modifier = Modifier.padding(12.dp, 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.chat_input_hint), color = TextMuted) },
                    shape = RoundedCornerShape(24.dp),
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
                    textStyle = TextStyle(fontSize = 14.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                )
                Spacer(modifier = Modifier.width(10.dp))
                // 发送按钮
                // 运行中=红色停止键(可中断);否则=发送键(无输入时淡化)
                val btnActive = isRunning || inputText.isNotBlank()
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            (if (isRunning) Color(0xFFFF453B) else PrimaryColor)
                                .copy(alpha = if (btnActive) 1f else 0.35f),
                            RoundedCornerShape(20.dp)
                        )
                        .clickable(onClick = if (isRunning) stop else send),
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (isRunning) "■" else "➤", color = Color.White, fontSize = 16.sp)
                }
            }
        }
    }
}

/** 首次启动（无持久化历史）时展示的演示对话。 */
private fun demoSeed(): List<ChatMessage> = listOf(
    ChatMessage.UserMessage("打开微信发消息给小明"),
    ChatMessage.ToolCall("🔍", "get_screen_info", "", "✓ 主屏幕"),
    ChatMessage.ToolCall("📱", "open_app", "com.tencent.mm", "✓"),
    ChatMessage.ToolCall("👆", "tap", "(540, 380)", "✓ 搜索"),
    ChatMessage.ToolCall("⌨️", "input_text", "(\"小明\")", "✓"),
    ChatMessage.ToolCall("👆", "tap", "(270, 280)", "✓ 小明"),
    ChatMessage.ToolCall("⌨️", "input_text", "(\"你好，今晚一起吃饭吗？\")", "✓"),
    ChatMessage.ToolCall("👆", "tap", "(980, 1820)", "✓ 发送"),
    ChatMessage.AgentMessage("已打开微信并找到小明的对话，消息\"你好，今晚一起吃饭吗？\"已发送成功。"),
    ChatMessage.UserMessage("帮我看看明天的天气"),
    ChatMessage.ToolCall("📱", "open_app", "com.miui.weather", "✓"),
    ChatMessage.ToolCall("🔍", "get_screen_info", "", "✓ 天气详情"),
    ChatMessage.AgentMessage("明天北京天气：晴转多云，最高 28°C，最低 16°C，空气质量良好。"),
)

@Composable
private fun UserBubble(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
            color = UserBubbleColor,
        ) {
            Text(
                text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                fontSize = 14.sp,
                color = Color.White,
                lineHeight = 21.sp,
            )
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
            // 折叠头:🔧 N 步骤 · 最后一步 + 箭头
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔧", fontSize = 14.sp)
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
                Text(if (expanded) "▾" else "▸", fontSize = 12.sp, color = TextMuted)
            }
            if (expanded) {
                tools.forEach { t ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.icon, fontSize = 13.sp)
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
            Text(msg.icon, fontSize = 14.sp)
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
