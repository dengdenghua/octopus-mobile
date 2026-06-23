package com.apk.claw.android.server

import android.content.Context
import com.apk.claw.android.BuildConfig
import com.apk.claw.android.channel.ChannelManager
import com.apk.claw.android.octopus_mobile.safety.ToolRiskPolicy
import com.apk.claw.android.server.routes.RouteContext
import com.apk.claw.android.server.routes.RouteHandler
import com.apk.claw.android.server.routes.ScreenHandler
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD
import java.security.SecureRandom

/**
 * 局域网 HTTP 配置服务器
 * 提供 H5 页面用于在电脑浏览器上配置钉钉/飞书 key
 */
class ConfigServer(
    private val context: Context,
    port: Int = PORT,
    hostname: String? = null
) : NanoHTTPD(hostname, port) {

    companion object {
        private const val TAG = "ConfigServer"
        const val PORT = 9527
        private const val MIME_HTML = "text/html"
        private const val MIME_JSON = "application/json"
        private const val AUTH_TOKEN_KEY = "config_server_auth_token"
        private const val AUTH_TOKEN_BYTES = 24
        private val SECURE_RANDOM = SecureRandom()

        /** 生成 24 字节随机 token（base64url，~32 字符） */
        fun generateAuthToken(): String {
            val bytes = ByteArray(AUTH_TOKEN_BYTES)
            SECURE_RANDOM.nextBytes(bytes)
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }

    private val gson = Gson()
    private val routeContext = RouteContext(context, gson)
    private val handlers: List<RouteHandler> = listOf(ScreenHandler())
    private val deviceRegistry = com.apk.claw.android.ClawApplication.instance.deviceRegistry
    private val deviceDiscoveryManager = com.apk.claw.android.ClawApplication.instance.deviceDiscoveryManager

    /** 当前生效的鉴权 token（首次启动时持久化到 KVUtils） */
    val authToken: String by lazy {
        KVUtils.getString(AUTH_TOKEN_KEY).takeIf { it.isNotEmpty() }
            ?: ConfigServer.generateAuthToken().also { KVUtils.putString(AUTH_TOKEN_KEY, it) }
    }

    /**
     * 校验请求的 token。
     * 接受两种方式：
     *   - HTTP 头: Authorization: Bearer <token>
     *   - 查询参数: ?token=<token>
     * 防止同 WiFi 邻居未授权访问配网页面。
     */
    private fun validateAuth(session: IHTTPSession): Boolean {
        val provided = session.headers["authorization"]
            ?.removePrefix("Bearer ")?.trim()
            ?: session.parms["token"]?.trim()
            ?: return false
        // 恒定时间比较，避免 token 时序泄露
        return constantTimeEquals(provided, authToken)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        // 用经过验证的常量时间比较（等长时不短路），避免逐字符比较的时序泄露。
        return java.security.MessageDigest.isEqual(
            a.toByteArray(Charsets.UTF_8),
            b.toByteArray(Charsets.UTF_8),
        )
    }

    private fun unauthorizedResponse(): Response = routeContext.corsResponse(
        newFixedLengthResponse(
            Response.Status.UNAUTHORIZED, MIME_JSON,
            """{"code":401,"message":"未授权,请通过 Authorization: Bearer <token> 或 ?token=<token> 传入访问令牌"}"""
        )
    )

    override fun serve(session: IHTTPSession): Response {
        // CORS 预检请求
        if (session.method == Method.OPTIONS) {
            return routeContext.corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, ""))
        }

        val uri = session.uri
        val method = session.method

        // 鉴权：放行 H5 页面、debug 静态资源；其余 /api/* 必须带 token
        val isPublic = uri == "/" || uri == "/index.html" || uri == "/debug.html" ||
            uri == "/console" || uri == "/console.html"
        if (!isPublic && !validateAuth(session)) {
            routeContext.recordRemoteAccess(session, "auth_denied", false, "uri=$uri", System.currentTimeMillis())
            return unauthorizedResponse()
        }

        return try {
            // 优先分发给独立 RouteHandler
            handlers.firstOrNull { it.canHandle(uri, method) }?.let {
                return it.handle(session, routeContext)
            }

            when {
                (uri == "/" || uri == "/index.html") && method == Method.GET -> serveHtml()
                (uri == "/console" || uri == "/console.html") && method == Method.GET -> serveConsoleHtml()
                uri == "/api/auth/check" && method == Method.GET -> handleAuthCheck()
                uri == "/api/channels" && method == Method.GET -> handleGetChannels()
                uri == "/api/channels" && method == Method.POST -> handlePostChannels(session)
                uri == "/api/llm" && method == Method.GET -> handleGetLlm()
                uri == "/api/llm" && method == Method.POST -> handlePostLlm(session)
                uri == "/api/cast" && method == Method.GET -> handleGetCast()
                uri == "/api/cast/start" && method == Method.POST -> handleStartCast()
                uri == "/api/cast/stop" && method == Method.POST -> handleStopCast()
                uri == "/api/cast/launch" && method == Method.POST -> handleCastLaunch(session)

                // 多设备协同 API
                uri == "/api/devices" && method == Method.GET -> handleGetDevices()
                uri == "/api/devices/discover" && method == Method.POST -> handleDiscoverDevices()
                uri == "/api/control/input" && method == Method.POST -> handleControlInput(session)

                // 网页聊天:驱动同一个 Agent(双屏右侧对话用)
                uri == "/api/agent/run" && method == Method.POST -> handleAgentRun(session)
                uri == "/api/agent/events" && method == Method.GET -> handleAgentEvents(session)

                // AI NAS 文件管理 API
                uri == "/api/files/browse" && method == Method.GET -> handleFileBrowse(session)
                uri == "/api/files/search" && method == Method.GET -> handleFileSearch(session)
                uri == "/api/files/storage" && method == Method.GET -> handleStorageOverview()
                uri == "/api/files/download" && method == Method.GET -> handleFileDownload(session)
                uri == "/api/files/upload" && method == Method.POST -> handleFileUpload(session)
                uri == "/api/files/delete" && method == Method.POST -> handleFileDelete(session)

                uri == "/debug.html" && method == Method.GET && BuildConfig.DEBUG -> serveDebugHtml()
                uri == "/api/debug/tools" && method == Method.GET && BuildConfig.DEBUG -> handleGetTools()
                uri == "/api/debug/execute" && method == Method.POST && BuildConfig.DEBUG -> handleExecuteTool(session)
                uri.startsWith("/api/debug/file") && method == Method.GET && BuildConfig.DEBUG -> handleServeFile(session)
                else -> routeContext.corsResponse(
                    newFixedLengthResponse(
                        Response.Status.NOT_FOUND, MIME_JSON,
                        """{"code":-1,"message":"接口不存在"}"""
                    )
                )
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Server error: ${e.message}")
            routeContext.recordRemoteAccess(session, "server_error", false, "error=${e.message}", System.currentTimeMillis())
            routeContext.corsResponse(
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, MIME_JSON,
                    """{"code":-1,"message":"${e.message}"}"""
                )
            )
        }
    }

    private fun serveHtml(): Response {
        val inputStream = context.assets.open("web/index.html")
        val html = inputStream.bufferedReader().use { it.readText() }
        return routeContext.corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_HTML, html))
    }

    /** 网页遥控台:实时屏幕(MJPEG)+ 点击/滑动/键盘 → /api/control/input。页面公开,API 仍要 token。 */
    private fun serveConsoleHtml(): Response {
        val html = context.assets.open("web/console.html").bufferedReader().use { it.readText() }
        return routeContext.corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_HTML, html))
    }

    /** POST /api/agent/run { "prompt": "..." } —— 网页发指令驱动 Agent。 */
    private fun handleAgentRun(session: IHTTPSession): Response {
        val startMs = System.currentTimeMillis()
        val params = routeContext.readJsonBody(session)
        val prompt = params.get("prompt")?.asString?.trim().orEmpty()
        if (prompt.isEmpty()) {
            routeContext.recordRemoteAccess(session, "agent_run", false, "prompt=<empty>", startMs)
            return routeContext.corsResponse(newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON, """{"code":-1,"message":"请输入指令"}"""))
        }
        val ok = AgentWebBridge.run(prompt)
        routeContext.recordRemoteAccess(session, "agent_run", ok, "promptChars=${prompt.length}", startMs)
        val json = gson.toJson(mapOf(
            "code" to if (ok) 0 else -1,
            "running" to AgentWebBridge.isRunning(),
            "total" to AgentWebBridge.total(),
            "message" to if (ok) "已开始执行" else "任务正在执行中或模型未配置",
        ))
        return routeContext.corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, json))
    }

    /** GET /api/agent/events?since=N —— 拉取从游标 N 起的增量事件(轮询)。 */
    private fun handleAgentEvents(session: IHTTPSession): Response {
        val since = session.parms["since"]?.toIntOrNull() ?: 0
        val evs = AgentWebBridge.eventsSince(since).map {
            mapOf("i" to it.i, "type" to it.type, "data" to it.data)
        }
        val json = gson.toJson(mapOf(
            "code" to 0,
            "running" to AgentWebBridge.isRunning(),
            "total" to AgentWebBridge.total(),
            "events" to evs,
        ))
        return routeContext.corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, json))
    }

    /** H5 页面调用此接口确认当前 token 是否有效 */
    private fun handleAuthCheck(): Response {
        val result = JsonObject().apply {
            addProperty("code", 0)
            addProperty("message", "ok")
        }
        return routeContext.corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
    }

    private fun handleGetChannels(): Response {
        // 仅回显脱敏后的密钥/令牌（POST 端会跳过带 * 的脱敏值，避免被覆盖）。
        // AppKey / AppId 属于标识符而非机密，且 POST 端会原样保存，故不脱敏。
        val data = JsonObject().apply {
            addProperty("dingtalkAppKey", KVUtils.getDingtalkAppKey())
            addProperty("dingtalkAppSecret", routeContext.maskSecret(KVUtils.getDingtalkAppSecret()))
            addProperty("feishuAppId", KVUtils.getFeishuAppId())
            addProperty("feishuAppSecret", routeContext.maskSecret(KVUtils.getFeishuAppSecret()))
            addProperty("qqAppId", KVUtils.getQqAppId())
            addProperty("qqAppSecret", routeContext.maskSecret(KVUtils.getQqAppSecret()))
            addProperty("discordBotToken", routeContext.maskSecret(KVUtils.getDiscordBotToken()))
            addProperty("telegramBotToken", routeContext.maskSecret(KVUtils.getTelegramBotToken()))
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
            addProperty("message", "ok")
        }
        return routeContext.corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
    }

    private fun handlePostChannels(session: IHTTPSession): Response {
        val json = routeContext.readJsonBody(session)

        var reinitDingtalk = false
        var reinitFeishu = false
        var reinitQQ = false
        var reinitDiscord = false
        var reinitTelegram = false

        // 钉钉配置
        if (json.has("dingtalkAppKey")) {
            val value = json.get("dingtalkAppKey").asString
            KVUtils.setDingtalkAppKey(value)
            reinitDingtalk = true
        }
        if (json.has("dingtalkAppSecret")) {
            val value = json.get("dingtalkAppSecret").asString
            // 如果是脱敏值则跳过
            if (!routeContext.isMaskedValue(value)) {
                KVUtils.setDingtalkAppSecret(value)
                reinitDingtalk = true
            }
        }

        // 飞书配置
        if (json.has("feishuAppId")) {
            val value = json.get("feishuAppId").asString
            KVUtils.setFeishuAppId(value)
            reinitFeishu = true
        }
        if (json.has("feishuAppSecret")) {
            val value = json.get("feishuAppSecret").asString
            if (!routeContext.isMaskedValue(value)) {
                KVUtils.setFeishuAppSecret(value)
                reinitFeishu = true
            }
        }

        // QQ 配置
        if (json.has("qqAppId")) {
            val value = json.get("qqAppId").asString
            KVUtils.setQqAppId(value)
            reinitQQ = true
        }
        if (json.has("qqAppSecret")) {
            val value = json.get("qqAppSecret").asString
            if (!routeContext.isMaskedValue(value)) {
                KVUtils.setQqAppSecret(value)
                reinitQQ = true
            }
        }

        // Discord 配置
        if (json.has("discordBotToken")) {
            val value = json.get("discordBotToken").asString
            if (!routeContext.isMaskedValue(value)) {
                KVUtils.setDiscordBotToken(value)
                reinitDiscord = true
            }
        }

        // Telegram 配置
        if (json.has("telegramBotToken")) {
            val value = json.get("telegramBotToken").asString
            if (!routeContext.isMaskedValue(value)) {
                KVUtils.setTelegramBotToken(value)
                reinitTelegram = true
            }
        }

        // 重新初始化对应通道
        if (reinitDingtalk) {
            ChannelManager.reinitDingTalkFromStorage()
        }
        if (reinitFeishu) {
            ChannelManager.reinitFeiShuFromStorage()
        }
        if (reinitQQ) {
            ChannelManager.reinitQQFromStorage()
        }
        if (reinitDiscord) {
            ChannelManager.reinitDiscordFromStorage()
        }
        if (reinitTelegram) {
            ChannelManager.reinitTelegramFromStorage()
        }

        // 通知 Settings 页面刷新绑定状态
        if (reinitDingtalk || reinitFeishu || reinitQQ || reinitDiscord || reinitTelegram) {
            ConfigServerManager.notifyConfigChanged()
        }

        val result = JsonObject().apply {
            addProperty("code", 0)
            addProperty("message", "ok")
        }
        return routeContext.corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
    }

    private fun handleGetLlm(): Response {
        val apiKey = KVUtils.getLlmApiKey()
        val data = JsonObject().apply {
            addProperty("llmApiKey", routeContext.maskSecret(apiKey))   // 脱敏回显；POST 端跳过带 * 的值
            addProperty("llmBaseUrl", KVUtils.getLlmBaseUrl())
            addProperty("llmModelName", KVUtils.getLlmModelName())
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
            addProperty("message", "ok")
        }
        return routeContext.corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
    }

    private fun handlePostLlm(session: IHTTPSession): Response {
        val json = routeContext.readJsonBody(session)

        if (json.has("llmApiKey")) {
            val value = json.get("llmApiKey").asString
            if (!routeContext.isMaskedValue(value)) {
                KVUtils.setLlmApiKey(value)
            }
        }
        if (json.has("llmBaseUrl")) {
            KVUtils.setLlmBaseUrl(json.get("llmBaseUrl").asString)
        }
        if (json.has("llmModelName")) {
            val value = json.get("llmModelName").asString.trim()
            KVUtils.setLlmModelName(if (value.isEmpty()) "" else value)
        }

        ConfigServerManager.notifyConfigChanged()

        val result = JsonObject().apply {
            addProperty("code", 0)
            addProperty("message", "ok")
        }
        return routeContext.corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
    }

    // ==================== Debug (仅 DEBUG 构建) ====================

    private fun serveDebugHtml(): Response {
        val inputStream = context.assets.open("web/debug.html")
        val html = inputStream.bufferedReader().use { it.readText() }
        return routeContext.corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_HTML, html))
    }

    private fun handleGetTools(): Response {
        val tools = ToolRegistry.getAllTools()
        val arr = JsonArray()
        for (tool in tools) {
            val obj = JsonObject().apply {
                addProperty("name", tool.getName())
                addProperty("displayName", tool.getDisplayName())
                addProperty("description", tool.getDescription())
                val params = JsonArray()
                for (p in tool.getParameters()) {
                    params.add(JsonObject().apply {
                        addProperty("name", p.name)
                        addProperty("type", p.type)
                        addProperty("description", p.description)
                        addProperty("required", p.isRequired)
                    })
                }
                add("parameters", params)
            }
            arr.add(obj)
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", arr)
        }
        return routeContext.corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
    }

    private fun handleExecuteTool(session: IHTTPSession): Response {
        val json = routeContext.readJsonBody(session)

        val toolName = json.get("tool")?.asString ?: return routeContext.corsResponse(
            newFixedLengthResponse(
                Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"缺少工具名称"}"""
            )
        )

        val params = mutableMapOf<String, Any>()
        try {
            json.getAsJsonObject("params")?.entrySet()?.forEach { (key, value) ->
                when {
                    value.isJsonNull -> {}
                    !value.isJsonPrimitive -> params[key] = value.toString()
                    value.asJsonPrimitive.isNumber -> params[key] = value.asNumber
                    value.asJsonPrimitive.isBoolean -> params[key] = value.asBoolean
                    else -> params[key] = value.asString
                }
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Debug param parse error: ${e.message}")
        }

        XLog.d(TAG, "Debug execute: $toolName params=$params")

        val toolResult = try {
            // LAN HTTP 下发的工具执行标记为"不可信来源"：高危工具默认被来源闸门拦截(R2)。
            ToolRegistry.withUntrustedSource {
                ToolRegistry.executeTool(toolName, params)
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Debug execute error", e)
            ToolResult.error("Exception: ${e.message}")
        }

        val data = JsonObject().apply {
            addProperty("success", toolResult.isSuccess)
            addProperty("data", toolResult.data)
            addProperty("error", toolResult.error)
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
        }
        return routeContext.corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
    }

    private fun handleServeFile(session: IHTTPSession): Response {
        val path = session.parms["path"] ?: return routeContext.corsResponse(
            newFixedLengthResponse(
                Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"缺少 path 参数"}"""
            )
        )
        // 安全校验：只允许访问 cache 目录下的文件
        val cacheDir = context.cacheDir.absolutePath
        val file = java.io.File(path)
        if (!file.exists() || !file.absolutePath.startsWith(cacheDir)) {
            return routeContext.corsResponse(
                newFixedLengthResponse(
                    Response.Status.NOT_FOUND, MIME_JSON,
                    """{"code":-1,"message":"文件不存在或无权访问"}"""
                )
            )
        }
        val mime = when (file.extension.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
        return routeContext.corsResponse(newFixedLengthResponse(Response.Status.OK, mime, file.inputStream(), file.length()))
    }

    // ======================== 异步投屏 API ========================

    /**
     * GET /api/cast —— 获取投屏状态。
     */
    private fun handleGetCast(): Response {
        val castService = com.apk.claw.android.cast.ScreenCastService.getInstance(context)
        val info = castService.getStatusInfo()
        val json = gson.toJson(mapOf("code" to 0, "data" to info))
        return routeContext.corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, json))
    }

    /**
     * POST /api/cast/start —— 启动投屏。
     */
    private fun handleStartCast(): Response {
        val castService = com.apk.claw.android.cast.ScreenCastService.getInstance(context)
        castService.start()
        val json = gson.toJson(mapOf(
            "code" to 0,
            "message" to "投屏服务已启动",
            "data" to castService.getStatusInfo()
        ))
        return routeContext.corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, json))
    }

    /**
     * POST /api/cast/stop —— 停止投屏。
     */
    private fun handleStopCast(): Response {
        val castService = com.apk.claw.android.cast.ScreenCastService.getInstance(context)
        castService.stop()
        val json = gson.toJson(mapOf("code" to 0, "message" to "投屏服务已停止"))
        return routeContext.corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, json))
    }

    /**
     * POST /api/cast/launch —— 在外接屏上启动 App。
     *
     * Body: { "package_name": "com.tencent.mm", "x": 100, "y": 100, "width": 800, "height": 600 }
     */
    private fun handleCastLaunch(session: IHTTPSession): Response {
        val params = routeContext.readJsonBody(session)

        val packageName = params.get("package_name")?.asString
            ?: return routeContext.corsResponse(newFixedLengthResponse(
                Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"缺少 package_name"}"""
            ))

        val x = params.get("x")?.asInt ?: 100
        val y = params.get("y")?.asInt ?: 100
        val width = params.get("width")?.asInt ?: 800
        val height = params.get("height")?.asInt ?: 600

        val castService = com.apk.claw.android.cast.ScreenCastService.getInstance(context)
        val success = castService.launchAppOnExternalDisplay(packageName, x, y, width, height)

        val json = gson.toJson(mapOf(
            "code" to if (success) 0 else -1,
            "message" to if (success) "已在外接屏启动 $packageName" else "启动失败,请检查投屏状态"
        ))
        return routeContext.corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, json))
    }

    // ======================== 多设备协同 API ========================

    /**
     * GET /api/devices
     * 获取已发现的设备列表。
     */
    private fun handleGetDevices(): Response {
        val devices = deviceRegistry.getAllDevices().map { d ->
            mapOf(
                "deviceId" to d.deviceId,
                "deviceName" to d.deviceName,
                "ip" to d.ip,
                "configServerPort" to d.configServerPort,
                "androidVersion" to d.androidVersion,
                "appVersion" to d.appVersion,
                "online" to d.online,
                "lastSeenTs" to d.lastSeenTs,
                "baseUrl" to d.getBaseUrl()
            )
        }
        val json = gson.toJson(mapOf("code" to 0, "data" to devices))
        return routeContext.corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, json))
    }

    /**
     * POST /api/devices/discover
     * 启动设备发现（如果尚未启动）。
     */
    private fun handleDiscoverDevices(): Response {
        if (!deviceDiscoveryManager.isRunning()) {
            deviceDiscoveryManager.start()
        }
        val devices = deviceRegistry.getOnlineDevices().map { d ->
            mapOf(
                "deviceId" to d.deviceId,
                "deviceName" to d.deviceName,
                "ip" to d.ip,
                "online" to d.online
            )
        }
        val json = gson.toJson(mapOf(
            "code" to 0,
            "message" to "设备发现已开启",
            "data" to devices
        ))
        return routeContext.corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, json))
    }

    /**
     * POST /api/control/input
     * 远程输入：tap/swipe/key/text
     *
     * Body:
     * ```json
     * { "action": "tap", "x": 500, "y": 1000 }
     * { "action": "swipe", "x1": 500, "y1": 1500, "x2": 500, "y2": 500, "duration": 300 }
     * { "action": "key", "keyCode": 4 }
     * { "action": "text", "text": "hello world" }
     * { "action": "back" }
     * { "action": "home" }
     * ```
     */

    /** 在不可信来源上下文中执行工具，供 LAN HTTP 控制输入统一调用。 */
    private fun runTool(toolName: String, params: Map<String, Any>): ToolResult {
        return ToolRegistry.withUntrustedSource {
            ToolRegistry.executeTool(toolName, params)
        }
    }

    private fun handleControlInput(session: IHTTPSession): Response {
        val startMs = System.currentTimeMillis()
        val params = routeContext.readJsonBody(session)   // 修正中文乱码(text 输入)

        val action = params.get("action")?.asString
            ?: run {
                routeContext.recordRemoteAccess(session, "control_input", false, "action=<missing>", startMs)
                return routeContext.corsResponse(newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, MIME_JSON,
                    """{"code":-1,"message":"缺少 action"}"""
                ))
            }

        // LAN HTTP 控制输入统一走 ToolRegistry，标记为不可信来源，使高危/中危工具受来源闸门、
        // 断路器、审计日志约束，避免直接调用 AccessibilityService 绕过所有安全层。
        val toolResult: ToolResult = when (action) {
            "tap" -> runTool(
                "tap",
                mapOf(
                    "x" to (params.get("x")?.asInt ?: 0),
                    "y" to (params.get("y")?.asInt ?: 0),
                )
            )
            "swipe" -> runTool(
                "swipe",
                mapOf(
                    "start_x" to (params.get("x1")?.asInt ?: 0),
                    "start_y" to (params.get("y1")?.asInt ?: 0),
                    "end_x" to (params.get("x2")?.asInt ?: 0),
                    "end_y" to (params.get("y2")?.asInt ?: 0),
                    "duration_ms" to (params.get("duration")?.asLong ?: 300L),
                )
            )
            "key" -> runTool(
                "system_key",
                mapOf("key_code" to (params.get("keyCode")?.asInt ?: 0))
            )
            "text" -> runTool(
                "input_text",
                mapOf("text" to (params.get("text")?.asString ?: ""))
            )
            "long_press" -> runTool(
                "long_press",
                mapOf(
                    "x" to (params.get("x")?.asInt ?: 0),
                    "y" to (params.get("y")?.asInt ?: 0),
                    "duration_ms" to (params.get("duration")?.asLong ?: 600L),
                )
            )
            "open_app" -> runTool(
                "open_app",
                mapOf("package_name" to (params.get("package")?.asString ?: ""))
            )
            "back" -> runTool("system_key", mapOf("key" to "back"))
            "home" -> runTool("system_key", mapOf("key" to "home"))
            "recent" -> runTool("system_key", mapOf("key" to "recent_apps"))
            "notifications" -> runTool("system_key", mapOf("key" to "notifications"))
            else -> ToolResult.error("Unknown action: $action")
        }

        val success = toolResult.isSuccess
        val message = if (success) "操作已执行" else "操作失败: $action (${toolResult.error})"
        val json = gson.toJson(mapOf(
            "code" to if (success) 0 else -1,
            "message" to message
        ))
        val safeParams = ToolRiskPolicy.summarizeParams(routeContext.jsonToSafeMap(params, setOf("text")))
        routeContext.recordRemoteAccess(session, "control_input", success, "action=$action,params=$safeParams", startMs)
        return routeContext.corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, json))
    }

    // ======================== AI NAS 文件管理 API ========================

    /**
     * GET /api/files/browse?path=/sdcard/Download&hidden=false
     */
    private fun handleFileBrowse(session: IHTTPSession): Response {
        val startMs = System.currentTimeMillis()
        val path = session.parms["path"] ?: "/sdcard"
        val showHidden = session.parms["hidden"]?.toBoolean() ?: false
        if (!routeContext.isAllowedUserPath(path)) {
            routeContext.recordRemoteAccess(session, "file_browse", false, "path=$path,forbidden=true", startMs)
            return routeContext.forbiddenPathResponse()
        }

        val shizuku = com.apk.claw.android.shizuku.ShizukuShellService
        val listing = shizuku.listFiles(path, showHidden)
            ?: run {
                routeContext.recordRemoteAccess(session, "file_browse", false, "path=$path,shizuku=unavailable", startMs)
                return routeContext.corsResponse(newFixedLengthResponse(
                    Response.Status.OK, MIME_JSON,
                    """{"code":-1,"message":"Shizuku 不可用"}"""
                ))
            }

        val json = gson.toJson(mapOf("code" to 0, "data" to mapOf(
            "path" to path,
            "listing" to listing
        )))
        routeContext.recordRemoteAccess(session, "file_browse", true, "path=$path,hidden=$showHidden", startMs)
        return routeContext.corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, json))
    }

    /**
     * GET /api/files/search?path=/sdcard&pattern=*.jpg&max=30
     */
    private fun handleFileSearch(session: IHTTPSession): Response {
        val startMs = System.currentTimeMillis()
        val path = session.parms["path"] ?: "/sdcard"
        val pattern = session.parms["pattern"] ?: "*"
        val maxResults = session.parms["max"]?.toIntOrNull() ?: 30
        if (!routeContext.isAllowedUserPath(path)) {
            routeContext.recordRemoteAccess(session, "file_search", false, "path=$path,pattern=$pattern,forbidden=true", startMs)
            return routeContext.forbiddenPathResponse()
        }

        val shizuku = com.apk.claw.android.shizuku.ShizukuShellService
        val result = shizuku.searchFiles(path, pattern, maxResults)
            ?: run {
                routeContext.recordRemoteAccess(session, "file_search", false, "path=$path,pattern=$pattern,shizuku=unavailable", startMs)
                return routeContext.corsResponse(newFixedLengthResponse(
                    Response.Status.OK, MIME_JSON,
                    """{"code":-1,"message":"Shizuku 不可用"}"""
                ))
            }

        val files = result.lines().filter { it.isNotBlank() }
        val json = gson.toJson(mapOf("code" to 0, "data" to mapOf(
            "path" to path,
            "pattern" to pattern,
            "count" to files.size,
            "files" to files
        )))
        routeContext.recordRemoteAccess(session, "file_search", true, "path=$path,pattern=$pattern,count=${files.size}", startMs)
        return routeContext.corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, json))
    }

    /**
     * GET /api/files/storage
     */
    private fun handleStorageOverview(): Response {
        val shizuku = com.apk.claw.android.shizuku.ShizukuShellService
        val overview = shizuku.getStorageOverview()
            ?: return routeContext.corsResponse(newFixedLengthResponse(
                Response.Status.OK, MIME_JSON,
                """{"code":-1,"message":"Shizuku 不可用"}"""
            ))

        val json = gson.toJson(mapOf("code" to 0, "data" to overview))
        return routeContext.corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, json))
    }

    /**
     * GET /api/files/download?path=/sdcard/Download/file.pdf
     *
     * 通过 Shizuku shell 读取文件并流式返回。
     */
    private fun handleFileDownload(session: IHTTPSession): Response {
        val startMs = System.currentTimeMillis()
        val path = session.parms["path"] ?: run {
            routeContext.recordRemoteAccess(session, "file_download", false, "path=<missing>", startMs)
            return routeContext.corsResponse(newFixedLengthResponse(
                Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"缺少 path 参数"}"""
            ))
        }

        // 安全检查：仅允许 /sdcard，且排除 .. 与注入字符
        if (!routeContext.isAllowedUserPath(path)) {
            routeContext.recordRemoteAccess(session, "file_download", false, "path=$path,forbidden=true", startMs)
            return routeContext.forbiddenPathResponse()
        }

        // 复制到 cacheDir 然后返回
        // 使用 externalFilesDir（/sdcard/Android/data/<pkg>/files/），shell 可写、app 可读
        val externalDir = context.getExternalFilesDir(null)
            ?: run {
                routeContext.recordRemoteAccess(session, "file_download", false, "path=$path,external_storage=unavailable", startMs)
                return routeContext.corsResponse(newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, MIME_JSON,
                    """{"code":-1,"message":"外部存储不可用"}"""
                ))
            }
        val cacheFile = java.io.File(externalDir, "download_${System.currentTimeMillis()}")
        val shizuku = com.apk.claw.android.shizuku.ShizukuShellService
        val result = shizuku.exec("cp \"$path\" \"${cacheFile.absolutePath}\" && chmod 644 \"${cacheFile.absolutePath}\"")
        if (result == null || result.exitCode != 0) {
            cacheFile.delete()
            routeContext.recordRemoteAccess(session, "file_download", false, "path=$path,copy_failed=true", startMs)
            return routeContext.corsResponse(newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, MIME_JSON,
                """{"code":-1,"message":"复制文件失败: ${result?.stderr?.trim() ?: "Shizuku 不可用"}"}"""
            ))
        }

        val mime = when (path.substringAfterLast('.').lowercase()) {
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "txt", "log" -> "text/plain"
            "json" -> "application/json"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }

        return routeContext.corsResponse(newFixedLengthResponse(
            Response.Status.OK, mime,
            cacheFile.inputStream(), cacheFile.length()
        ).also {
            it.addHeader("Content-Disposition", "attachment; filename=\"${path.substringAfterLast('/')}\"")
            routeContext.recordRemoteAccess(session, "file_download", true, "path=$path,mime=$mime,bytes=${cacheFile.length()}", startMs)
        })
    }

    /**
     * POST /api/files/upload
     * Body: { "path": "/sdcard/Download/uploaded.pdf", "base64": "..." }
     */
    private fun handleFileUpload(session: IHTTPSession): Response {
        val startMs = System.currentTimeMillis()
        val params = routeContext.readJsonBody(session)

        val path = params.get("path")?.asString ?: run {
            routeContext.recordRemoteAccess(session, "file_upload", false, "path=<missing>", startMs)
            return routeContext.corsResponse(newFixedLengthResponse(
                Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"缺少 path"}"""
            ))
        }
        val base64Data = params.get("base64")?.asString ?: run {
            routeContext.recordRemoteAccess(session, "file_upload", false, "path=$path,base64=<missing>", startMs)
            return routeContext.corsResponse(newFixedLengthResponse(
                Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"缺少 base64 数据"}"""
            ))
        }

        if (!routeContext.isAllowedUserPath(path)) {
            routeContext.recordRemoteAccess(session, "file_upload", false, "path=$path,forbidden=true", startMs)
            return routeContext.forbiddenPathResponse()
        }

        try {
            val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
            // 写入 app 私有目录（shell 可读 /sdcard/Android/data/<pkg>/cache/）
            val externalCache = context.getExternalFilesDir(null)
                ?: run {
                    routeContext.recordRemoteAccess(session, "file_upload", false, "path=$path,external_storage=unavailable", startMs)
                    return routeContext.corsResponse(newFixedLengthResponse(
                        Response.Status.INTERNAL_ERROR, MIME_JSON,
                        """{"code":-1,"message":"外部存储不可用"}"""
                    ))
                }
            val tempFile = java.io.File(externalCache, "upload_${System.currentTimeMillis()}")
            tempFile.writeBytes(bytes)
            // 通过 Shizuku 复制到目标路径
            val shizuku = com.apk.claw.android.shizuku.ShizukuShellService
            val ok = shizuku.copyFile(tempFile.absolutePath, path)
            tempFile.delete()

            val json = gson.toJson(mapOf(
                "code" to if (ok == true) 0 else -1,
                "message" to if (ok == true) "已上传到 $path" else "上传失败"
            ))
            routeContext.recordRemoteAccess(session, "file_upload", ok == true, "path=$path,bytes=${bytes.size}", startMs)
            return routeContext.corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, json))
        } catch (e: Exception) {
            val json = gson.toJson(mapOf("code" to -1, "message" to "上传异常: ${e.message}"))
            routeContext.recordRemoteAccess(session, "file_upload", false, "path=$path,error=${e.message}", startMs)
            return routeContext.corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, json))
        }
    }

    /**
     * POST /api/files/delete
     * Body: { "path": "/sdcard/Download/temp.txt" }
     */
    private fun handleFileDelete(session: IHTTPSession): Response {
        val startMs = System.currentTimeMillis()
        val params = routeContext.readJsonBody(session)

        val path = params.get("path")?.asString ?: run {
            routeContext.recordRemoteAccess(session, "file_delete", false, "path=<missing>", startMs)
            return routeContext.corsResponse(newFixedLengthResponse(
                Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"缺少 path"}"""
            ))
        }

        if (!routeContext.isAllowedUserPath(path)) {
            routeContext.recordRemoteAccess(session, "file_delete", false, "path=$path,forbidden=true", startMs)
            return routeContext.forbiddenPathResponse()
        }

        val shizuku = com.apk.claw.android.shizuku.ShizukuShellService
        val ok = shizuku.deleteFile(path)
        val json = gson.toJson(mapOf(
            "code" to if (ok == true) 0 else -1,
            "message" to if (ok == true) "已删除: $path" else "删除失败"
        ))
        routeContext.recordRemoteAccess(session, "file_delete", ok == true, "path=$path", startMs)
        return routeContext.corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, json))
    }

}
