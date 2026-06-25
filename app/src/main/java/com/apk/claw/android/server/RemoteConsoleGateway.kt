package com.apk.claw.android.server
import com.apk.claw.android.utils.OctoHttp

import android.os.Build
import android.os.Handler
import android.os.Looper
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.account.AccountConfig
import com.apk.claw.android.account.AccountStore
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog
import com.apk.claw.android.utils.runCatchingOrDefault
import com.apk.claw.android.utils.runCatchingOrNull
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 官网远程控制台网关。
 *
 * 手机端主动连接官网 WebSocket,避免 NAT/局域网/IP 变动问题。官网控制台只和服务端通信,
 * 服务端再把 JSON 指令转发到这里执行。
 */
object RemoteConsoleGateway {
    private const val TAG = "RemoteConsoleGateway"
    private const val KEY_DEVICE_ID = "REMOTE_CONSOLE_DEVICE_ID"
    private const val KEY_DEVICE_TOKEN = "REMOTE_CONSOLE_DEVICE_TOKEN"
    private const val KEY_DEVICE_NAME = "REMOTE_CONSOLE_DEVICE_NAME"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val http = OctoHttp.shared.newBuilder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var manualStop = false
    @Volatile private var retryMs = 2_000L

    val isPaired: Boolean get() = deviceId.isNotEmpty() && deviceToken.isNotEmpty()
    val isConnected: Boolean get() = socket != null

    var deviceId: String
        get() = KVUtils.getString(KEY_DEVICE_ID, "")
        private set(v) {
            KVUtils.putString(KEY_DEVICE_ID, v)
        }

    private var deviceToken: String
        get() = KVUtils.getString(KEY_DEVICE_TOKEN, "")
        set(v) {
            KVUtils.putString(KEY_DEVICE_TOKEN, v)
        }

    var deviceName: String
        get() = KVUtils.getString(KEY_DEVICE_NAME, defaultDeviceName())
        private set(v) {
            KVUtils.putString(KEY_DEVICE_NAME, v)
        }

    suspend fun claimPairCode(code: String, name: String = defaultDeviceName()): String = withContext(Dispatchers.IO) {
        val auth = AccountStore.token.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("请先登录账号")
        val normalized = code.trim()
        require(normalized.matches(Regex("\\d{6,9}"))) { "配对码格式不正确" }
        val stableDeviceId = deviceId.ifBlank {
            "d_" + UUID.randomUUID().toString().replace("-", "").take(16)
        }
        val bodyJson = gson.toJson(mapOf(
            "code" to normalized,
            "deviceName" to name.ifBlank { defaultDeviceName() },
            "deviceId" to stableDeviceId,
        ))
        val req = Request.Builder()
            .url(AccountConfig.baseUrl.trimEnd('/') + "/remote/pair/claim")
            .header("Authorization", "Bearer $auth")
            .post(bodyJson.toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException(serverDetail(text) ?: "配对失败: HTTP ${resp.code}")
            val json = gson.fromJson(text, JsonObject::class.java)
            deviceId = json.get("deviceId")?.asString.orEmpty()
            deviceToken = json.get("deviceToken")?.asString.orEmpty()
            deviceName = json.get("deviceName")?.asString?.ifBlank { name } ?: name
            if (!isPaired) throw RuntimeException("服务端返回的设备凭证不完整")
        }
        connect()
        "已配对 ${deviceName}"
    }

    fun connect() {
        if (!isPaired || socket != null) return
        manualStop = false
        val req = Request.Builder().url(wsUrl()).build()
        http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                retryMs = 2_000L
                sendDeviceInfo(webSocket)
                XLog.i(TAG, "官网远程控制台已连接")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(webSocket, text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket === webSocket) socket = null
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket === webSocket) socket = null
                XLog.e(TAG, "官网远程控制台连接失败: ${t.message}")
                scheduleReconnect()
            }
        })
    }

    fun disconnect() {
        manualStop = true
        socket?.close(1000, "user disconnect")
        socket = null
    }

    fun clearPairing() {
        disconnect()
        deviceId = ""
        deviceToken = ""
        deviceName = defaultDeviceName()
    }

    private fun handleMessage(ws: WebSocket, text: String) {
        val msg = runCatchingOrNull(TAG) { gson.fromJson(text, JsonObject::class.java) }
        if (msg == null) {
            sendResult(ws, "", false, "消息格式错误")
            return
        }
        if (msg.get("type")?.asString == "revoked") {
            clearPairing()
            return
        }
        val id = msg.get("id")?.asString.orEmpty()
        when (msg.get("type")?.asString) {
            "ping" -> ws.send(gson.toJson(mapOf("type" to "pong", "id" to id)))
            "control" -> {
                val action = msg.get("action")?.asString.orEmpty()
                val ok = runCatching { performControl(action, msg) }.getOrElse {
                    XLog.e(TAG, "远程控制执行失败: ${it.message}")
                    false
                }
                sendResult(ws, id, ok, if (ok) "操作已执行" else "操作失败: $action")
            }
            else -> sendResult(ws, id, false, "暂不支持的指令类型")
        }
    }

    private fun performControl(action: String, msg: JsonObject): Boolean {
        val svc = ClawAccessibilityService.getInstance() ?: return false
        return when (action) {
            "tap" -> svc.performTap(msg.int("x"), msg.int("y"))
            "swipe" -> svc.performSwipe(
                msg.int("x1"), msg.int("y1"),
                msg.int("x2"), msg.int("y2"),
                msg.long("duration", 300L),
            )
            "long_press" -> svc.performLongPress(msg.int("x"), msg.int("y"), msg.long("duration", 600L))
            "key" -> svc.sendKeyEvent(msg.int("keyCode"))
            "text" -> ToolRegistry.executeTool("input_text", mapOf("text" to (msg.get("text")?.asString ?: ""))).isSuccess
            "open_app" -> svc.openApp(msg.get("package")?.asString.orEmpty())
            "back" -> svc.pressBack()
            "home" -> svc.pressHome()
            "recent" -> svc.openRecentApps()
            "notifications" -> svc.expandNotifications()
            else -> false
        }
    }

    private fun sendResult(ws: WebSocket, id: String, success: Boolean, message: String) {
        ws.send(gson.toJson(mapOf(
            "type" to "result",
            "id" to id,
            "success" to success,
            "message" to message,
        )))
    }

    private fun sendDeviceInfo(ws: WebSocket) {
        val lan = runCatchingOrNull(TAG) { ensureLanConsole() }
        ws.send(gson.toJson(mapOf(
            "type" to "device_info",
            "deviceId" to deviceId,
            "deviceName" to deviceName,
            "lanBaseUrl" to (lan?.first ?: ""),
            "lanAuthToken" to (lan?.second ?: ""),
            "lanConsoleUrl" to (lan?.third ?: ""),
        )))
    }

    private fun ensureLanConsole(): Triple<String, String, String>? {
        KVUtils.setConfigServerEnabled(true)
        if (!ConfigServerManager.start(ClawApplication.instance)) return null
        val address = ConfigServerManager.getAddress() ?: return null
        val token = ConfigServerManager.getAuthToken() ?: return null
        val baseUrl = "http://$address"
        return Triple(baseUrl, token, "$baseUrl/console?token=${enc(token)}")
    }

    private fun scheduleReconnect() {
        if (manualStop || !isPaired) return
        val delay = retryMs.coerceAtMost(60_000L)
        retryMs = (retryMs * 2).coerceAtMost(60_000L)
        mainHandler.postDelayed({ connect() }, delay)
    }

    private fun wsUrl(): String {
        val base = AccountConfig.baseUrl.trimEnd('/')
        val wsBase = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        return wsBase + "/remote/device/ws?device_id=" + enc(deviceId) + "&device_token=" + enc(deviceToken)
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun defaultDeviceName(): String = "Octopus ${Build.MODEL ?: "Android"}"

    private fun serverDetail(body: String): String? = runCatchingOrNull(TAG) {
        gson.fromJson(body, JsonObject::class.java)
            ?.get("detail")?.takeIf { it.isJsonPrimitive }?.asString
    }

    private fun JsonObject.int(key: String, default: Int = 0): Int =
        runCatchingOrDefault(TAG, default) { get(key)?.asInt ?: default }

    private fun JsonObject.long(key: String, default: Long = 0L): Long =
        runCatchingOrDefault(TAG, default) { get(key)?.asLong ?: default }
}
