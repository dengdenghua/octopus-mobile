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
    // 对话历史：优先读持久化，首启无历史时回退演示数据
    val messages = remember {
        mutableStateListOf<ChatMessage>().apply { addAll(ChatStore.load() ?: demoSeed()) }
    }

    var inputText by remember { mutableStateOf("") }
    var remoteMode by remember { mutableStateOf(true) }
    var isRunning by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val ackText = stringResource(R.string.chat_ack)
    val thinkingText = stringResource(R.string.chat_thinking)
    val scrollEnd = { scope.launch { listState.animateScrollToItem(messages.size) }; Unit }
    val persist = { ChatStore.save(messages) }

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
                showThinking()
                ChatAgentBridge.run(
                    prompt = t,
                    onTool = { icon, name, args, res -> hideThinking(); messages.add(ChatMessage.ToolCall(icon, name, args, res)); showThinking(); persist() },
                    onText = { txt -> hideThinking(); messages.add(ChatMessage.AgentMessage(txt)); showThinking(); persist() },
                    onDone = { ans -> hideThinking(); messages.add(ChatMessage.AgentMessage(ans)); isRunning = false; scrollEnd(); persist() },
                    onError = { e -> hideThinking(); messages.add(ChatMessage.AgentMessage("⚠️ $e")); isRunning = false; scrollEnd(); persist() },
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
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = BackgroundColor,
                titleContentColor = TextPrimary,
            )
        )

        // 任务队列条
        Surface(
            modifier = Modifier.padding(horizontal = 16.dp),
            shape = RoundedCornerShape(12.dp),
            color = PrimaryColor.copy(alpha = 0.08f),
            border = BorderStroke(1.dp, PrimaryColor.copy(alpha = 0.15f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("▶", fontSize = 10.sp, color = PrimaryColor)
                Spacer(modifier = Modifier.width(8.dp))
                Text("打开微信发消息", fontSize = 12.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(6.dp), color = PrimaryColor.copy(alpha = 0.15f)) {
                    Text(
                        "轮次 3",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 10.sp,
                        color = PrimaryColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        // 排队提示
        Surface(
            modifier = Modifier.padding(horizontal = 16.dp),
            shape = RoundedCornerShape(10.dp),
            color = WarningColor.copy(alpha = 0.06f),
            border = BorderStroke(1.dp, WarningColor.copy(alpha = 0.1f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("⏳", fontSize = 12.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("查明天天气 · 排队中", fontSize = 11.sp, color = TextMuted)
            }
        }

        // 消息列表
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(messages, key = { it.id }) { msg ->
                when (msg) {
                    is ChatMessage.UserMessage -> UserBubble(msg.text)
                    is ChatMessage.AgentMessage -> AgentBubble(msg.text)
                    is ChatMessage.ToolCall -> ToolCallItem(msg)
                    is ChatMessage.Thinking -> ThinkingItem(msg.text)
                }
            }
            item { Spacer(modifier = Modifier.height(8.dp)) }
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
