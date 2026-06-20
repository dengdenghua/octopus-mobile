package com.apk.claw.android.server

import android.content.Context
import com.apk.claw.android.BuildConfig
import com.apk.claw.android.channel.ChannelManager
import com.apk.claw.android.octopus_mobile.RemoteAccessLog
import com.apk.claw.android.octopus_mobile.nerves.EventBus
import com.apk.claw.android.octopus_mobile.safety.ToolRiskPolicy
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.KVUtils
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.apk.claw.android.utils.XLog
import fi.iki.elonen.NanoHTTPD
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.SecureRandom

/**
 * 局域网 HTTP 配置服务器
 * 提供 H5 页面用于在电脑浏览器上配置钉钉/飞书 key
 */
class ConfigServer(
    private val context: Context,
    port: Int = PORT
) : NanoHTTPD(port) {

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
    private val screenCaptureManager = ScreenCaptureManager()
    private val mjpegStreamLock = java.util.concurrent.Semaphore(2) // 限制 2 路并发 MJPEG
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

    private fun sourceOf(session: IHTTPSession): String {
        return session.headers["x-forwarded-for"]?.substringBefore(",")?.trim()?.takeIf { it.isNotEmpty() }
            ?: session.headers["x-real-ip"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: session.headers["remote-addr"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: session.headers["http-client-ip"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: "unknown"
    }

    private fun recordRemoteAccess(
        session: IHTTPSession,
        action: String,
        success: Boolean,
        summary: String,
        startMs: Long,
    ) {
        val duration = System.currentTimeMillis() - startMs
        val safeSummary = summary.replace('\n', ' ').take(500)
        RemoteAccessLog.record(
            RemoteAccessLog.Entry(
                id = "remote_${startMs}_${System.nanoTime()}_${action}",
                ts = startMs,
                method = session.method.name,
                uri = session.uri,
                source = sourceOf(session),
                action = action,
                success = success,
                summary = safeSummary,
                durationMs = duration,
            )
        )
        runCatching {
            com.apk.claw.android.ClawApplication.instance.eventBus.publish(
                EventBus.RemoteAccessAuditEvent(
                    method = session.method.name,
                    uri = session.uri,
                    source = sourceOf(session),
                    action = action,
                    success = success,
                    durationMs = duration,
                )
            )
        }
    }

    private fun jsonToSafeMap(json: JsonObject, redactKeys: Set<String> = emptySet()): Map<String, Any> {
        return json.entrySet().associate { (key, value) ->
            val safeValue = when {
                key.lowercase() in redactKeys -> "<redacted>"
                value.isJsonNull -> ""
                value.isJsonPrimitive -> value.asString
                else -> value.toString()
            }
            key to safeValue
        }
    }

    private fun unauthorizedResponse(): Response = corsResponse(
        newFixedLengthResponse(
            Response.Status.UNAUTHORIZED, MIME_JSON,
            """{"code":401,"message":"未授权,请通过 Authorization: Bearer <token> 或 ?token=<token> 传入访问令牌"}"""
        )
    )

    override fun serve(session: IHTTPSession): Response {
        // CORS 预检请求
        if (session.method == Method.OPTIONS) {
            return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, ""))
        }

        val uri = session.uri
        val method = session.method

        // 鉴权：放行 H5 页面、debug 静态资源；其余 /api/* 必须带 token
        val isPublic = uri == "/" || uri == "/index.html" || uri == "/debug.html" ||
            uri == "/console" || uri == "/console.html"
        if (!isPublic && !validateAuth(session)) {
            recordRemoteAccess(session, "auth_denied", false, "uri=$uri", System.currentTimeMillis())
            return unauthorizedResponse()
        }

        return try {
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

                // 屏幕截图 API
                uri == "/api/screen/screenshot" && method == Method.GET -> handleScreenshot(session)
                uri == "/api/screen/stream" && method == Method.GET -> handleScreenStream(session)
                uri == "/api/screen/info" && method == Method.GET -> handleScreenInfo()
                uri == "/api/screen/tree" && method == Method.GET -> handleScreenTree(session)

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
                uri == "/api/debug/screen-full" && method == Method.GET && BuildConfig.DEBUG -> handleGetScreenFull()
                uri.startsWith("/api/debug/file") && method == Method.GET && BuildConfig.DEBUG -> handleServeFile(session)
                else -> corsResponse(
                    newFixedLengthResponse(
                        Response.Status.NOT_FOUND, MIME_JSON,
                        """{"code":-1,"message":"接口不存在"}"""
                    )
                )
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Server error: ${e.message}")
            recordRemoteAccess(session, "server_error", false, "error=${e.message}", System.currentTimeMillis())
            corsResponse(
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
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_HTML, html))
    }

    /** 网页遥控台:实时屏幕(MJPEG)+ 点击/滑动/键盘 → /api/control/input。页面公开,API 仍要 token。 */
    private fun serveConsoleHtml(): Response {
        val html = context.assets.open("web/console.html").bufferedReader().use { it.readText() }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_HTML, html))
    }

    /**
     * 读取 POST JSON body 并修正中文乱码。
     * NanoHTTPD 默认按 ISO-8859-1 把 body 读成字符串,UTF-8 中文会乱;按字节回转再以 UTF-8 解码修正。
     */
    private fun readJsonBody(session: IHTTPSession): JsonObject {
        // 直接按 Content-Length 从原始输入流读字节,以 UTF-8 解码 —— 绕开 NanoHTTPD.parseBody
        // 把 body 当 ASCII/ISO-8859-1 解码导致中文丢失的问题。
        val raw = runCatching {
            val len = session.headers["content-length"]?.toIntOrNull() ?: 0
            if (len <= 0) return@runCatching "{}"
            val buf = ByteArray(len)
            var off = 0
            while (off < len) {
                val r = session.inputStream.read(buf, off, len - off)
                if (r <= 0) break
                off += r
            }
            String(buf, 0, off, Charsets.UTF_8)
        }.getOrDefault("{}")
        return runCatching { gson.fromJson(raw.ifBlank { "{}" }, JsonObject::class.java) }.getOrNull()
            ?: gson.fromJson("{}", JsonObject::class.java)
    }

    /** POST /api/agent/run { "prompt": "..." } —— 网页发指令驱动 Agent。 */
    private fun handleAgentRun(session: IHTTPSession): Response {
        val startMs = System.currentTimeMillis()
        val params = readJsonBody(session)
        val prompt = params.get("prompt")?.asString?.trim().orEmpty()
        if (prompt.isEmpty()) {
            recordRemoteAccess(session, "agent_run", false, "prompt=<empty>", startMs)
            return corsResponse(newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON, """{"code":-1,"message":"请输入指令"}"""))
        }
        val ok = AgentWebBridge.run(prompt)
        recordRemoteAccess(session, "agent_run", ok, "promptChars=${prompt.length}", startMs)
        val json = gson.toJson(mapOf(
            "code" to if (ok) 0 else -1,
            "running" to AgentWebBridge.isRunning(),
            "total" to AgentWebBridge.total(),
            "message" to if (ok) "已开始执行" else "任务正在执行中或模型未配置",
        ))
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, json))
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
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, json))
    }

    /** H5 页面调用此接口确认当前 token 是否有效 */
    private fun handleAuthCheck(): Response {
        val result = JsonObject().apply {
            addProperty("code", 0)
            addProperty("message", "ok")
        }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
    }

    private fun handleGetChannels(): Response {
        // 仅回显脱敏后的密钥/令牌（POST 端会跳过带 * 的脱敏值，避免被覆盖）。
        // AppKey / AppId 属于标识符而非机密，且 POST 端会原样保存，故不脱敏。
        val data = JsonObject().apply {
            addProperty("dingtalkAppKey", KVUtils.getDingtalkAppKey())
            addProperty("dingtalkAppSecret", maskSecret(KVUtils.getDingtalkAppSecret()))
            addProperty("feishuAppId", KVUtils.getFeishuAppId())
            addProperty("feishuAppSecret", maskSecret(KVUtils.getFeishuAppSecret()))
            addProperty("qqAppId", KVUtils.getQqAppId())
            addProperty("qqAppSecret", maskSecret(KVUtils.getQqAppSecret()))
            addProperty("discordBotToken", maskSecret(KVUtils.getDiscordBotToken()))
            addProperty("telegramBotToken", maskSecret(KVUtils.getTelegramBotToken()))
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
            addProperty("message", "ok")
        }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
    }

    private fun handlePostChannels(session: IHTTPSession): Response {
        val json = readJsonBody(session)

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
            if (!isMaskedValue(value)) {
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
            if (!isMaskedValue(value)) {
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
            if (!isMaskedValue(value)) {
                KVUtils.setQqAppSecret(value)
                reinitQQ = true
            }
        }

        // Discord 配置
        if (json.has("discordBotToken")) {
            val value = json.get("discordBotToken").asString
            if (!isMaskedValue(value)) {
                KVUtils.setDiscordBotToken(value)
                reinitDiscord = true
            }
        }

        // Telegram 配置
        if (json.has("telegramBotToken")) {
            val value = json.get("telegramBotToken").asString
            if (!isMaskedValue(value)) {
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
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
    }

    private fun handleGetLlm(): Response {
        val apiKey = KVUtils.getLlmApiKey()
        val data = JsonObject().apply {
            addProperty("llmApiKey", maskSecret(apiKey))   // 脱敏回显；POST 端跳过带 * 的值
            addProperty("llmBaseUrl", KVUtils.getLlmBaseUrl())
            addProperty("llmModelName", KVUtils.getLlmModelName())
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
            addProperty("message", "ok")
        }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
    }

    private fun handlePostLlm(session: IHTTPSession): Response {
        val json = readJsonBody(session)

        if (json.has("llmApiKey")) {
            val value = json.get("llmApiKey").asString
            if (!isMaskedValue(value)) {
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
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
    }

    // ==================== Debug (仅 DEBUG 构建) ====================

    private fun handleGetScreenFull(): Response {
        val service = com.apk.claw.android.service.ClawAccessibilityService.getInstance()
            ?: return corsResponse(
                newFixedLengthResponse(
                    Response.Status.OK, MIME_JSON,
                    """{"code":-1,"message":"无障碍服务未运行"}"""
                )
            )
        val tree = service.screenTreeFull
        val data = JsonObject().apply {
            addProperty("success", tree != null)
            addProperty("data", tree ?: "")
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
        }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
    }

    private fun serveDebugHtml(): Response {
        val inputStream = context.assets.open("web/debug.html")
        val html = inputStream.bufferedReader().use { it.readText() }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_HTML, html))
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
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
    }

    private fun handleExecuteTool(session: IHTTPSession): Response {
        val json = readJsonBody(session)

        val toolName = json.get("tool")?.asString ?: return corsResponse(
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
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
    }

    private fun handleServeFile(session: IHTTPSession): Response {
        val path = session.parms["path"] ?: return corsResponse(
            newFixedLengthResponse(
                Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"缺少 path 参数"}"""
            )
        )
        // 安全校验：只允许访问 cache 目录下的文件
        val cacheDir = context.cacheDir.absolutePath
        val file = java.io.File(path)
        if (!file.exists() || !file.absolutePath.startsWith(cacheDir)) {
            return corsResponse(
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
        return corsResponse(newFixedLengthResponse(Response.Status.OK, mime, file.inputStream(), file.length()))
    }

    /**
     * 脱敏：只显示后4位，前面用 * 替代
     */
    private fun maskSecret(secret: String): String {
        if (secret.isEmpty()) return ""
        if (secret.length <= 4) return secret
        return "*".repeat(secret.length - 4) + secret.takeLast(4)
    }

    /**
     * 判断是否为脱敏后的值（包含 *）
     */
    private fun isMaskedValue(value: String): Boolean {
        return value.contains("*")
    }

    /**
     * 文件 API 路径白名单：仅允许访问用户存储区(/sdcard)。
     * 防止经 ?path=/data/data/<pkg>/... 遍历到 app 私有目录读取 MMKV(内含 API 密钥)。
     * ShizukuShellService.isValidPath 只校验字符集，会放行 /data/data，故必须在此再加前缀限制。
     */
    private fun isAllowedUserPath(path: String): Boolean =
        (path == "/sdcard" || path.startsWith("/sdcard/")) &&
            !path.contains("..") &&
            com.apk.claw.android.shizuku.ShizukuShellService.isValidPath(path)

    private fun forbiddenPathResponse(): Response = corsResponse(newFixedLengthResponse(
        Response.Status.FORBIDDEN, MIME_JSON,
        """{"code":-1,"message":"访问被拒绝,仅允许访问 /sdcard/ 路径"}"""
    ))

    // ======================== 异步投屏 API ========================

    /**
     * GET /api/cast —— 获取投屏状态。
     */
    private fun handleGetCast(): Response {
        val castService = com.apk.claw.android.cast.ScreenCastService.getInstance(context)
        val info = castService.getStatusInfo()
        val json = gson.toJson(mapOf("code" to 0, "data" to info))
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, json))
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
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, json))
    }

    /**
     * POST /api/cast/stop —— 停止投屏。
     */
    private fun handleStopCast(): Response {
        val castService = com.apk.claw.android.cast.ScreenCastService.getInstance(context)
        castService.stop()
        val json = gson.toJson(mapOf("code" to 0, "message" to "投屏服务已停止"))
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, json))
    }

    /**
     * POST /api/cast/launch —— 在外接屏上启动 App。
     *
     * Body: { "package_name": "com.tencent.mm", "x": 100, "y": 100, "width": 800, "height": 600 }
     */
    private fun handleCastLaunch(session: IHTTPSession): Response {
        val params = readJsonBody(session)

        val packageName = params.get("package_name")?.asString
            ?: return corsResponse(newFixedLengthResponse(
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
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, json))
    }

    // ======================== 屏幕截图 API ========================

    /**
     * GET /api/screen/screenshot?quality=60&maxWidth=720
     * 单张 JPEG 截图。
     */
    private fun handleScreenshot(session: IHTTPSession): Response {
        val startMs = System.currentTimeMillis()
        val quality = session.parms["quality"]?.toIntOrNull() ?: 60
        val maxWidth = session.parms["maxWidth"]?.toIntOrNull() ?: 720

        val jpeg = if (maxWidth > 0) {
            screenCaptureManager.captureScaledJpeg(maxWidth, quality)
        } else {
            screenCaptureManager.captureJpeg(quality)
        }

        if (jpeg == null) {
            recordRemoteAccess(session, "screen_screenshot", false, "quality=$quality,maxWidth=$maxWidth", startMs)
            return corsResponse(
                newFixedLengthResponse(
                    Response.Status.SERVICE_UNAVAILABLE, MIME_JSON,
                    """{"code":-1,"message":"当前无法获取截图"}"""
                )
            )
        }

        val response = newFixedLengthResponse(
            Response.Status.OK, "image/jpeg",
            java.io.ByteArrayInputStream(jpeg), jpeg.size.toLong()
        )
        response.addHeader("Cache-Control", "no-cache, no-store")
        recordRemoteAccess(session, "screen_screenshot", true, "quality=$quality,maxWidth=$maxWidth,bytes=${jpeg.size}", startMs)
        return corsResponse(response)
    }

    /**
     * GET /api/screen/stream?quality=50&maxWidth=720&fps=10
     * MJPEG 实时流（限制 2 路并发）。
     */
    private fun handleScreenStream(session: IHTTPSession): Response {
        val startMs = System.currentTimeMillis()
        if (!mjpegStreamLock.tryAcquire()) {
            recordRemoteAccess(session, "screen_stream", false, "too_many_streams", startMs)
            return corsResponse(
                newFixedLengthResponse(
                    Response.Status.TOO_MANY_REQUESTS, MIME_JSON,
                    """{"code":-1,"message":"屏幕流连接过多,最多支持 2 路"}"""
                )
            )
        }

        val quality = session.parms["quality"]?.toIntOrNull() ?: 50
        val maxWidth = session.parms["maxWidth"]?.toIntOrNull() ?: 720
        val fps = session.parms["fps"]?.toIntOrNull()?.coerceIn(1, 15) ?: 10
        val frameIntervalMs = (1000L / fps)

        val boundary = "octopus_mjpeg_boundary"
        val contentType = "multipart/x-mixed-replace; boundary=$boundary"

        val pipe = PipedInputStream()
        val pipeOut = PipedOutputStream(pipe)

        // 后台线程写帧
        Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val jpeg = screenCaptureManager.captureScaledJpeg(maxWidth, quality)
                    if (jpeg != null && jpeg.isNotEmpty()) {
                        pipeOut.write("--$boundary\r\n".toByteArray())
                        pipeOut.write("Content-Type: image/jpeg\r\n".toByteArray())
                        pipeOut.write("Content-Length: ${jpeg.size}\r\n\r\n".toByteArray())
                        pipeOut.write(jpeg)
                        pipeOut.write("\r\n".toByteArray())
                        pipeOut.flush()
                    }
                    Thread.sleep(frameIntervalMs)
                }
            } catch (_: java.io.IOException) {
                // 客户端断开
            } catch (_: InterruptedException) {
                // 正常停止
            } finally {
                try { pipeOut.close() } catch (_: Exception) {}
                mjpegStreamLock.release()
            }
        }, "MJPEG-Stream").apply {
            isDaemon = true
            start()
        }

        val response = newFixedLengthResponse(Response.Status.OK, contentType, pipe, Long.MAX_VALUE)
        response.addHeader("Cache-Control", "no-cache, no-store")
        recordRemoteAccess(session, "screen_stream", true, "quality=$quality,maxWidth=$maxWidth,fps=$fps", startMs)
        return corsResponse(response)
    }

    /**
     * GET /api/screen/info
     * 屏幕分辨率及无障碍服务状态。
     */
    private fun handleScreenInfo(): Response {
        val info = screenCaptureManager.getScreenInfo() ?: mapOf(
            "width" to 0,
            "height" to 0,
            "serviceRunning" to false
        )
        val json = gson.toJson(mapOf("code" to 0, "data" to info))
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, json))
    }

    /**
     * GET /api/screen/tree?full=false
     * 远程读屏：返回无障碍可见的 UI 树（供远端 Agent 决策点击坐标）。
     */
    private fun handleScreenTree(session: IHTTPSession): Response {
        val startMs = System.currentTimeMillis()
        val full = session.parms["full"]?.toBoolean() ?: false
        val svc = com.apk.claw.android.service.ClawAccessibilityService.getInstance()
            ?: run {
                recordRemoteAccess(session, "screen_tree", false, "full=$full,service=unavailable", startMs)
                return corsResponse(newFixedLengthResponse(
                    Response.Status.SERVICE_UNAVAILABLE, MIME_JSON,
                    """{"code":-1,"message":"无障碍服务未运行"}"""
                ))
            }
        val tree = if (full) svc.screenTreeFull else svc.screenTree
        val json = gson.toJson(mapOf("code" to 0, "data" to (tree ?: "")))
        recordRemoteAccess(session, "screen_tree", true, "full=$full,chars=${tree?.length ?: 0}", startMs)
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, json))
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
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, json))
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
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, json))
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
    private fun handleControlInput(session: IHTTPSession): Response {
        val startMs = System.currentTimeMillis()
        val params = readJsonBody(session)   // 修正中文乱码(text 输入)

        val action = params.get("action")?.asString
            ?: run {
                recordRemoteAccess(session, "control_input", false, "action=<missing>", startMs)
                return corsResponse(newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, MIME_JSON,
                    """{"code":-1,"message":"缺少 action"}"""
                ))
            }

        val service = com.apk.claw.android.service.ClawAccessibilityService.getInstance()
            ?: run {
                recordRemoteAccess(session, "control_input", false, "action=$action,service=unavailable", startMs)
                return corsResponse(newFixedLengthResponse(
                    Response.Status.SERVICE_UNAVAILABLE, MIME_JSON,
                    """{"code":-1,"message":"无障碍服务未运行"}"""
                ))
            }

        val success = when (action) {
            "tap" -> {
                val x = params.get("x")?.asInt ?: 0
                val y = params.get("y")?.asInt ?: 0
                service.performTap(x, y)
            }
            "swipe" -> {
                val x1 = params.get("x1")?.asInt ?: 0
                val y1 = params.get("y1")?.asInt ?: 0
                val x2 = params.get("x2")?.asInt ?: 0
                val y2 = params.get("y2")?.asInt ?: 0
                val duration = params.get("duration")?.asLong ?: 300L
                service.performSwipe(x1, y1, x2, y2, duration)
            }
            "key" -> {
                val keyCode = params.get("keyCode")?.asInt ?: 0
                service.sendKeyEvent(keyCode)
            }
            "text" -> {
                // 通过 ToolRegistry 的 input_text 工具实现
                val text = params.get("text")?.asString ?: ""
                val result = ToolRegistry.executeTool("input_text", mapOf("text" to text))
                result.isSuccess
            }
            "long_press" -> {
                val x = params.get("x")?.asInt ?: 0
                val y = params.get("y")?.asInt ?: 0
                val duration = params.get("duration")?.asLong ?: 600L
                service.performLongPress(x, y, duration)
            }
            "open_app" -> {
                val pkg = params.get("package")?.asString ?: ""
                service.openApp(pkg)
            }
            "back" -> service.pressBack()
            "home" -> service.pressHome()
            "recent" -> service.openRecentApps()
            "notifications" -> service.expandNotifications()
            else -> false
        }

        val json = gson.toJson(mapOf(
            "code" to if (success) 0 else -1,
            "message" to if (success) "操作已执行" else "操作失败: $action"
        ))
        val safeParams = ToolRiskPolicy.summarizeParams(jsonToSafeMap(params, setOf("text")))
        recordRemoteAccess(session, "control_input", success, "action=$action,params=$safeParams", startMs)
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, json))
    }

    // ======================== AI NAS 文件管理 API ========================

    /**
     * GET /api/files/browse?path=/sdcard/Download&hidden=false
     */
    private fun handleFileBrowse(session: IHTTPSession): Response {
        val startMs = System.currentTimeMillis()
        val path = session.parms["path"] ?: "/sdcard"
        val showHidden = session.parms["hidden"]?.toBoolean() ?: false
        if (!isAllowedUserPath(path)) {
            recordRemoteAccess(session, "file_browse", false, "path=$path,forbidden=true", startMs)
            return forbiddenPathResponse()
        }

        val shizuku = com.apk.claw.android.shizuku.ShizukuShellService
        val listing = shizuku.listFiles(path, showHidden)
            ?: run {
                recordRemoteAccess(session, "file_browse", false, "path=$path,shizuku=unavailable", startMs)
                return corsResponse(newFixedLengthResponse(
                    Response.Status.OK, MIME_JSON,
                    """{"code":-1,"message":"Shizuku 不可用"}"""
                ))
            }

        val json = gson.toJson(mapOf("code" to 0, "data" to mapOf(
            "path" to path,
            "listing" to listing
        )))
        recordRemoteAccess(session, "file_browse", true, "path=$path,hidden=$showHidden", startMs)
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, json))
    }

    /**
     * GET /api/files/search?path=/sdcard&pattern=*.jpg&max=30
     */
    private fun handleFileSearch(session: IHTTPSession): Response {
        val startMs = System.currentTimeMillis()
        val path = session.parms["path"] ?: "/sdcard"
        val pattern = session.parms["pattern"] ?: "*"
        val maxResults = session.parms["max"]?.toIntOrNull() ?: 30
        if (!isAllowedUserPath(path)) {
            recordRemoteAccess(session, "file_search", false, "path=$path,pattern=$pattern,forbidden=true", startMs)
            return forbiddenPathResponse()
        }

        val shizuku = com.apk.claw.android.shizuku.ShizukuShellService
        val result = shizuku.searchFiles(path, pattern, maxResults)
            ?: run {
                recordRemoteAccess(session, "file_search", false, "path=$path,pattern=$pattern,shizuku=unavailable", startMs)
                return corsResponse(newFixedLengthResponse(
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
        recordRemoteAccess(session, "file_search", true, "path=$path,pattern=$pattern,count=${files.size}", startMs)
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, json))
    }

    /**
     * GET /api/files/storage
     */
    private fun handleStorageOverview(): Response {
        val shizuku = com.apk.claw.android.shizuku.ShizukuShellService
        val overview = shizuku.getStorageOverview()
            ?: return corsResponse(newFixedLengthResponse(
                Response.Status.OK, MIME_JSON,
                """{"code":-1,"message":"Shizuku 不可用"}"""
            ))

        val json = gson.toJson(mapOf("code" to 0, "data" to overview))
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, json))
    }

    /**
     * GET /api/files/download?path=/sdcard/Download/file.pdf
     *
     * 通过 Shizuku shell 读取文件并流式返回。
     */
    private fun handleFileDownload(session: IHTTPSession): Response {
        val startMs = System.currentTimeMillis()
        val path = session.parms["path"] ?: run {
            recordRemoteAccess(session, "file_download", false, "path=<missing>", startMs)
            return corsResponse(newFixedLengthResponse(
                Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"缺少 path 参数"}"""
            ))
        }

        // 安全检查：仅允许 /sdcard，且排除 .. 与注入字符
        if (!isAllowedUserPath(path)) {
            recordRemoteAccess(session, "file_download", false, "path=$path,forbidden=true", startMs)
            return forbiddenPathResponse()
        }

        // 复制到 cacheDir 然后返回
        // 使用 externalFilesDir（/sdcard/Android/data/<pkg>/files/），shell 可写、app 可读
        val externalDir = context.getExternalFilesDir(null)
            ?: run {
                recordRemoteAccess(session, "file_download", false, "path=$path,external_storage=unavailable", startMs)
                return corsResponse(newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, MIME_JSON,
                    """{"code":-1,"message":"外部存储不可用"}"""
                ))
            }
        val cacheFile = java.io.File(externalDir, "download_${System.currentTimeMillis()}")
        val shizuku = com.apk.claw.android.shizuku.ShizukuShellService
        val result = shizuku.exec("cp \"$path\" \"${cacheFile.absolutePath}\" && chmod 644 \"${cacheFile.absolutePath}\"")
        if (result == null || result.exitCode != 0) {
            cacheFile.delete()
            recordRemoteAccess(session, "file_download", false, "path=$path,copy_failed=true", startMs)
            return corsResponse(newFixedLengthResponse(
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

        return corsResponse(newFixedLengthResponse(
            Response.Status.OK, mime,
            cacheFile.inputStream(), cacheFile.length()
        ).also {
            it.addHeader("Content-Disposition", "attachment; filename=\"${path.substringAfterLast('/')}\"")
            recordRemoteAccess(session, "file_download", true, "path=$path,mime=$mime,bytes=${cacheFile.length()}", startMs)
        })
    }

    /**
     * POST /api/files/upload
     * Body: { "path": "/sdcard/Download/uploaded.pdf", "base64": "..." }
     */
    private fun handleFileUpload(session: IHTTPSession): Response {
        val startMs = System.currentTimeMillis()
        val params = readJsonBody(session)

        val path = params.get("path")?.asString ?: run {
            recordRemoteAccess(session, "file_upload", false, "path=<missing>", startMs)
            return corsResponse(newFixedLengthResponse(
                Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"缺少 path"}"""
            ))
        }
        val base64Data = params.get("base64")?.asString ?: run {
            recordRemoteAccess(session, "file_upload", false, "path=$path,base64=<missing>", startMs)
            return corsResponse(newFixedLengthResponse(
                Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"缺少 base64 数据"}"""
            ))
        }

        if (!isAllowedUserPath(path)) {
            recordRemoteAccess(session, "file_upload", false, "path=$path,forbidden=true", startMs)
            return forbiddenPathResponse()
        }

        try {
            val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
            // 写入 app 私有目录（shell 可读 /sdcard/Android/data/<pkg>/cache/）
            val externalCache = context.getExternalFilesDir(null)
                ?: run {
                    recordRemoteAccess(session, "file_upload", false, "path=$path,external_storage=unavailable", startMs)
                    return corsResponse(newFixedLengthResponse(
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
            recordRemoteAccess(session, "file_upload", ok == true, "path=$path,bytes=${bytes.size}", startMs)
            return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, json))
        } catch (e: Exception) {
            val json = gson.toJson(mapOf("code" to -1, "message" to "上传异常: ${e.message}"))
            recordRemoteAccess(session, "file_upload", false, "path=$path,error=${e.message}", startMs)
            return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, json))
        }
    }

    /**
     * POST /api/files/delete
     * Body: { "path": "/sdcard/Download/temp.txt" }
     */
    private fun handleFileDelete(session: IHTTPSession): Response {
        val startMs = System.currentTimeMillis()
        val params = readJsonBody(session)

        val path = params.get("path")?.asString ?: run {
            recordRemoteAccess(session, "file_delete", false, "path=<missing>", startMs)
            return corsResponse(newFixedLengthResponse(
                Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"缺少 path"}"""
            ))
        }

        if (!isAllowedUserPath(path)) {
            recordRemoteAccess(session, "file_delete", false, "path=$path,forbidden=true", startMs)
            return forbiddenPathResponse()
        }

        val shizuku = com.apk.claw.android.shizuku.ShizukuShellService
        val ok = shizuku.deleteFile(path)
        val json = gson.toJson(mapOf(
            "code" to if (ok == true) 0 else -1,
            "message" to if (ok == true) "已删除: $path" else "删除失败"
        ))
        recordRemoteAccess(session, "file_delete", ok == true, "path=$path", startMs)
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, json))
    }

    private fun corsResponse(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
        return response
    }
}
