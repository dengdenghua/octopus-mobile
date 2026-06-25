package com.apk.claw.android.octopus_mobile

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Octopus Mobile 客户端 —— Octopus Mobile 与 octopus-agent Runtime 之间的 WebSocket 通道.
 *
 * Phase 0 骨架实现（不依赖任何 Octopus Mobile 现有代码，纯 add-only 新增）：
 *  - 构造时接受 runtimeUrl，connect() 后启动 OkHttp WebSocket
 *  - send(envelope) 发 JSON 消息
 *  - 接收消息分发到 onMessage 回调
 *  - 30s 心跳由 [HeartbeatReporter] 负责
 *
 * Phase 1 实装：
 *  - 接入 ToolCallDispatcher 把 tool/execute 路由到 BaseTool
 *  - 接入 ScreenStreamer 推送屏幕变化
 *  - 接入 DualConfigWriter 双写 MMKV
 *  - executeRemoteTask: 向母体发送任务请求并等待结果
 *  - onToolExecute: 接收母体下发的 tool/execute 并执行
 *
 * 完整设计：
 *  - octopus-agent 仓库：docs/mobile/architecture.md 第 3 节
 *  - 协议：docs/mobile/protocol.md
 *  - 集成：docs/adr/008-octopus-mobile.md
 */
open class OctopusMobileClient(
    private val runtimeUrl: String,
    private val tentacleId: String,
    private val authToken: String? = null
) {
    private val tag = "OctopusMobile"

    private val gson = Gson()

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)  // WebSocket 长连接，无读超时
        .build()

    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 通用消息监听器列表（多订阅者） */
    private val messageListeners = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()

    /** 接收消息回调（兼容旧 API，调用 addMessageListener / removeMessageListener）。
     *  无 backing field，实际由线程安全的 [messageListeners] 支持。 */
    var onMessage: ((String) -> Unit)?
        get() = messageListeners.firstOrNull()
        set(value) {
            messageListeners.clear()
            if (value != null) messageListeners.add(value)
        }

    /** 连接状态变化回调 */
    @Volatile
    var onStateChanged: ((ConnectionState) -> Unit)? = null

    /** 工具执行回调：收到母体 tool/execute 后调用 */
    @Volatile
    var onToolExecute: ((ToolCall) -> Unit)? = null

    /** PC 屏幕帧回调：收到母体 push_pc_frame 推来的二进制帧后调用（远程桌面用） */
    @Volatile
    var onPcFrame: ((ByteArray) -> Unit)? = null

    /** 配置变更回调：收到母体 config/sync_pull_response 后调用 */
    @Volatile
    var onConfigChange: ((String) -> Unit)? = null

    /** 等待远程任务结果的 future：task_id → CompletableDeferred */
    private val pendingTasks = ConcurrentHashMap<String, CompletableDeferred<RemoteTaskResult>>()

    @Volatile
    private var state: ConnectionState = ConnectionState.OFFLINE

    /** 启动客户端 —— 建立 WebSocket 连接 + 发送 hello + 状态流转. */
    open fun connect() {
        Log.i(tag, "connecting to $runtimeUrl as $tentacleId")

        val request = Request.Builder()
            .url(runtimeUrl)
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(tag, "websocket opened")
                this@OctopusMobileClient.webSocket = webSocket
                setState(ConnectionState.CONNECTED)

                // 发送 hello 握手
                val hello = EnvelopeFactory.hello(
                    tentacleId = tentacleId,
                    deviceMeta = mapOf(
                        "brand" to android.os.Build.BRAND,
                        "model" to android.os.Build.MODEL,
                        "android_version" to android.os.Build.VERSION.RELEASE,
                        "sdk" to android.os.Build.VERSION.SDK_INT
                    ),
                    capabilities = emptyList(),
                    authToken = authToken
                )
                webSocket.send(hello.toJson())
                setState(ConnectionState.HELLO_SENT)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(tag, "received: $text")
                // 收到任何消息且处于 HELLO_SENT 状态 → 视为握手成功
                if (state == ConnectionState.HELLO_SENT) {
                    setState(ConnectionState.ONLINE)
                    reconnectAttempts.set(0)
                }
                handleIncomingMessage(text)
                onMessage?.invoke(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // 二进制帧：母体 push_pc_frame 推来的 PC 屏幕帧（远程桌面）
                if (state == ConnectionState.HELLO_SENT) {
                    setState(ConnectionState.ONLINE)
                    reconnectAttempts.set(0)
                }
                onPcFrame?.invoke(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(tag, "websocket closing code=$code reason=$reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(tag, "websocket closed code=$code reason=$reason")
                this@OctopusMobileClient.webSocket = null
                setState(ConnectionState.OFFLINE)
                failPendingTasks("Connection closed (code=$code)")
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(tag, "websocket failure: ${t.message}")
                this@OctopusMobileClient.webSocket = null
                setState(ConnectionState.OFFLINE)
                failPendingTasks("Connection failed: ${t.message}")
                scheduleReconnect()
            }
        }

        httpClient.newWebSocket(request, listener)
    }

    /** 指数退避重连（基础 2s，最大 30s） */
    private val reconnectAttempts = AtomicInteger(0)
    @Volatile
    private var reconnectJob: kotlinx.coroutines.Job? = null

    private fun scheduleReconnect() {
        val attempts = reconnectAttempts.incrementAndGet()
        if (state == ConnectionState.OFFLINE && attempts <= 10) {
            val baseDelay = 2000L
            val maxDelay = 30_000L
            val delay = minOf(baseDelay * (1L shl (attempts - 1).coerceAtLeast(0)), maxDelay)
            val jitter = (Math.random() * 1000).toLong()
            Log.i(tag, "Reconnecting in ${delay + jitter}ms (attempt $attempts)")
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                kotlinx.coroutines.delay(delay + jitter)
                if (state == ConnectionState.OFFLINE) {
                    setState(ConnectionState.CONNECTING)
                    connect()
                }
            }
        } else {
            reconnectAttempts.decrementAndGet()
        }
    }

    /**
     * 断连时把所有在途任务标记失败并清空。
     *
     * 否则旧连接的 deferred 会挂起到 60s 超时；且重连后旧连接迟到的 task/result
     * 可能错误完成新连接的任务(跨连接泄漏)。在 setState(OFFLINE) 之后调用——
     * 此时 executeRemoteTask 因状态非 ONLINE 不会再注册新任务,无竞态。
     */
    private fun failPendingTasks(reason: String) {
        if (pendingTasks.isEmpty()) return
        val deferreds = pendingTasks.values.toList()
        pendingTasks.clear()
        deferreds.forEach { it.complete(RemoteTaskResult.Failure(reason)) }
    }

    /**
     * 处理收到的消息 —— 解析 task/result / task/error / tool/execute / config/sync_pull_response.
     *
     * 使用 Gson 解析 JSON，替代之前的正则提取（正则无法正确处理嵌套 JSON、转义字符、Unicode）。
     */
    private fun handleIncomingMessage(text: String) {
        try {
            val root = JsonParser.parseString(text).asJsonObject
            // 母体（Python json.dumps）发的是 "method": "..."，Gson 自动处理空白
            val method = root.get("method")?.asString ?: return

            when (method) {
                // 任务结果（母体返回的任务执行结果）
                "task/result" -> {
                    val taskId = root.get("task_id")?.asString ?: return
                    val response = root.get("response")?.asString ?: ""
                    val steps = root.get("steps")?.asInt ?: 0
                    val deferred = pendingTasks.remove(taskId)
                    deferred?.complete(RemoteTaskResult.Success(steps, response, TokenUsage(0, 0, 0)))
                }
                // 任务错误
                "task/error" -> {
                    val taskId = root.get("task_id")?.asString ?: return
                    val error = root.get("error")?.asString ?: "Unknown error"
                    val deferred = pendingTasks.remove(taskId)
                    deferred?.complete(RemoteTaskResult.Failure(error))
                }
                // 工具执行（母体下发的 tool/execute）
                "tool/execute" -> {
                    val callId = root.get("id")?.asString ?: return
                    val tool = root.get("tool")?.asString ?: return
                    val argsElement = root.get("args")
                    val args = parseArgs(argsElement)
                    val call = ToolCall(id = callId, name = tool, args = args)
                    onToolExecute?.invoke(call)
                }
                // 配置同步响应（母体推来的配置变更）
                "config/sync_pull_response" -> {
                    onConfigChange?.invoke(text)
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "handleIncomingMessage error: ${e.message}")
        }
        // 通用消息分发（所有监听器）
        for (listener in messageListeners) {
            try {
                listener.invoke(text)
            } catch (e: Exception) {
                Log.w(tag, "messageListener failed: ${e.message}")
            }
        }
    }

    /**
     * 添加一个通用消息监听器.
     */
    fun addMessageListener(listener: (String) -> Unit) {
        messageListeners.add(listener)
    }

    /**
     * 移除一个通用消息监听器.
     */
    fun removeMessageListener(listener: (String) -> Unit) {
        messageListeners.remove(listener)
    }

    /**
     * 向母体发送远程任务请求，等待结果.
     *
     * 这是 BrainModeSelector.decideRemotely() 的核心调用.
     */
    suspend fun executeRemoteTask(task: String, intent: IntentClassifier.ClassificationResult): RemoteTaskResult {
        val ws = webSocket
        if (ws == null || state != ConnectionState.ONLINE) {
            return RemoteTaskResult.Failure("Not connected to runtime")
        }

        val taskId = java.util.UUID.randomUUID().toString()
        val deferred = CompletableDeferred<RemoteTaskResult>()
        pendingTasks[taskId] = deferred

        val request = Envelope.Request(
            method = "task/execute",
            params = mapOf(
                "task_id" to taskId,
                "task" to task,
                "intent" to intent.primary.name,
                "confidence" to intent.confidence,
                "tentacle_id" to tentacleId
            ),
            id = taskId
        )

        return try {
            ws.send(request.toJson())
            // 等待结果（60 秒超时）
            withTimeout(60_000) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            pendingTasks.remove(taskId)
            RemoteTaskResult.Failure("Task timeout after 60s")
        } catch (e: Exception) {
            pendingTasks.remove(taskId)
            RemoteTaskResult.Failure("Task failed: ${e.message}")
        }
    }

    /**
     * 发送工具执行结果给母体.
     *
     * 手机端执行完 tool/execute 后调用.
     */
    open fun sendToolResult(
        callId: String,
        success: Boolean,
        data: String? = null,
        error: String? = null,
        errorCode: Int? = null,
        durationMs: Int = 0,
        screenHashAfter: String? = null,
    ) {
        val ws = webSocket ?: return
        // 母体 ws_server._handle_tool_result 按 method="tool/result" 路由，并从
        // params.{call_id,success,data,error{code,message},duration_ms} 读取 ——
        // 必须发 method 风格消息，而非 JSON-RPC reply（否则母体当作未知方法、调用方超时）。
        val params = mutableMapOf<String, Any?>(
            "call_id" to callId,
            "success" to success,
            "duration_ms" to durationMs,
        )
        if (success) {
            params["data"] = data ?: ""
            if (screenHashAfter != null) params["screen_hash_after"] = screenHashAfter
        } else {
            params["error"] = mapOf(
                "code" to (errorCode ?: -32603),
                "message" to (error ?: "Unknown error"),
            )
        }
        ws.send(Envelope.Request(method = "tool/result", params = params).toJson())
    }

    /** 订阅母体 PC 屏幕流（远程桌面：母体随后通过 push_pc_frame 推 JPEG 帧）。 */
    fun subscribePcScreen() {
        send(Envelope.Request(method = "pc_screen/subscribe", params = mapOf("tentacle_id" to tentacleId)))
    }

    /** 取消订阅母体 PC 屏幕流。 */
    fun unsubscribePcScreen() {
        send(Envelope.Request(method = "pc_screen/unsubscribe", params = mapOf("tentacle_id" to tentacleId)))
    }

    /**
     * 远程控制母体（remote/input）。坐标为归一化 [0,1]，母体按其屏幕尺寸还原。
     * @param action tap / move / down / up / type / key 等
     */
    fun sendRemoteInput(action: String, x: Float = 0f, y: Float = 0f, text: String? = null) {
        val params = mutableMapOf<String, Any?>(
            "action" to action,
            "x" to x.toDouble(),
            "y" to y.toDouble(),
            "tentacle_id" to tentacleId,
        )
        if (text != null) params["text"] = text
        send(Envelope.Request(method = "remote/input", params = params))
    }

    /**
     * 发送 envelope 消息.
     */
    fun send(envelope: Envelope) {
        val ws = webSocket ?: run {
            Log.w(tag, "send skipped: not connected")
            return
        }
        ws.send(envelope.toJson())
    }

    /**
     * 主动断开连接.
     */
    fun disconnect() {
        reconnectJob?.cancel()
        reconnectAttempts.set(0)
        webSocket?.close(1000, "client disconnect")
        webSocket = null
        setState(ConnectionState.OFFLINE)
    }

    private fun setState(newState: ConnectionState) {
        if (state != newState) {
            state = newState
            onStateChanged?.invoke(newState)
        }
    }

    open fun currentState(): ConnectionState = state

    // ── 辅助 ────────────────────────────────────────────────

    /**
     * 解析 args 元素为 Map<String, Any>.
     * 支持 string/int/long/double/boolean 及嵌套对象/数组（Gson 自动处理类型）。
     */
    private fun parseArgs(element: com.google.gson.JsonElement?): Map<String, Any> {
        if (element == null || !element.isJsonObject) return emptyMap()
        val mapType = object : TypeToken<Map<String, Any>>() {}.type
        return try {
            gson.fromJson(element, mapType) ?: emptyMap()
        } catch (e: Exception) {
            Log.w(tag, "parseArgs failed: ${e.message}")
            emptyMap()
        }
    }
}
