@file:Suppress("MagicNumber", "MaxLineLength", "TooManyFunctions")

package com.apk.claw.android.voice.realtime

import android.util.Base64
import com.apk.claw.android.account.AccountConfig
import com.apk.claw.android.account.AccountStore
import com.apk.claw.android.utils.OctoHttp
import com.apk.claw.android.utils.XLog
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** 通话状态机(UI 据此渲染)。 */
sealed interface VoiceState {
    data object Idle : VoiceState
    data object Connecting : VoiceState
    data object Connected : VoiceState
    data class Ended(val reason: String) : VoiceState
    data class Error(val msg: String) : VoiceState
}

/** 计费/会话概要(来自服务端 voice.session / voice.tick 自定义事件)。 */
data class VoiceStats(
    val billedMode: String = "",
    val perMinCredits: Int = 0,
    val maxMinutes: Int = 0,
    val minute: Int = 0,
    val creditsSpent: Int = 0,
)

/**
 * 实时语音对话客户端 —— OkHttp WebSocket 连服务端 /voice/realtime,编排 [AudioIn]/[AudioOut]。
 *
 * 关键设计:
 *  - 只带 [AccountStore.token](query),**绝不内置任何模型/DashScope key** —— 上游鉴权在服务端网关。
 *  - **不发 session.update**:服务端连上游后已注入会话参数(Cherry 音色/中文/pcm16/server_vad),
 *    客户端再发会用 OpenAI 的枚举冲掉 DashScope 配置。客户端只持续发 input_audio_buffer.append。
 *  - server_vad 自动断句,**不需要**客户端发 commit / response.create。
 *  - 通话不自动重连(重连=新会话=重新计费首分钟),断开即结束,由用户重拨。
 *  - 事件在 OkHttp reader 单线程串行到达,StateFlow 更新线程安全,transcript `+=` 无并发。
 */
class VoiceRealtimeClient {

    private companion object {
        const val TAG = "VoiceRealtime"
        const val CONNECT_TIMEOUT_S = 12L
        const val WRITE_TIMEOUT_S = 12L
        const val PING_INTERVAL_S = 20L
        const val CLOSE_NORMAL = 1000
    }

    private val gson = Gson()
    private val http = OctoHttp.shared.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // ★ WS 长连禁读超时(shared 默认 10s 会误杀)
        .writeTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS)
        .pingInterval(PING_INTERVAL_S, TimeUnit.SECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var manualStop = false
    @Volatile private var audioIn: AudioIn? = null
    @Volatile private var audioOut: AudioOut? = null

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()
    private val _stats = MutableStateFlow(VoiceStats())
    val stats: StateFlow<VoiceStats> = _stats.asStateFlow()
    private val _aiTranscript = MutableStateFlow("")
    val aiTranscript: StateFlow<String> = _aiTranscript.asStateFlow()
    private val _userTranscript = MutableStateFlow("")
    val userTranscript: StateFlow<String> = _userTranscript.asStateFlow()
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    fun connect() {
        if (ws != null) return
        if (!AccountStore.isLoggedIn) {
            _state.value = VoiceState.Error("请先登录后再使用语音通话")
            return
        }
        manualStop = false
        _aiTranscript.value = ""
        _userTranscript.value = ""
        _stats.value = VoiceStats()
        _state.value = VoiceState.Connecting
        val req = Request.Builder().url(wsUrl()).build()
        ws = http.newWebSocket(req, listener)
    }

    fun disconnect() {
        manualStop = true
        ws?.close(CLOSE_NORMAL, "user")
        ws = null
        teardownAudio()
        val s = _state.value
        if (s is VoiceState.Connected || s is VoiceState.Connecting) _state.value = VoiceState.Idle
    }

    private fun wsUrl(): String {
        val base = AccountConfig.baseUrl.trimEnd('/')
        val wsBase = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        return "$wsBase/voice/realtime?token=" + URLEncoder.encode(AccountStore.token, "UTF-8")
    }

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) = handleEvent(text)

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (ws === webSocket) ws = null
            teardownAudio()
            if (!manualStop && _state.value !is VoiceState.Ended && _state.value !is VoiceState.Error) {
                _state.value = VoiceState.Ended(reason.ifEmpty { "通话已结束" })
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (ws === webSocket) ws = null
            teardownAudio()
            XLog.e(TAG, "语音连接失败: ${t.message}")
            if (!manualStop) _state.value = VoiceState.Error(t.message ?: "网络连接失败")
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun handleEvent(text: String) {
        val obj = runCatching { gson.fromJson(text, JsonObject::class.java) }.getOrNull() ?: return
        when (obj.get("type")?.asString) {
            "voice.session" -> onVoiceSession(obj)
            "voice.tick" -> onVoiceTick(obj)
            "voice.ended" -> endedByServer(obj.get("reason")?.asString ?: "通话已结束")
            "response.created" -> _aiTranscript.value = ""
            "response.audio.delta" -> onAudioDelta(obj)
            "response.audio.done" -> _speaking.value = false
            "response.audio_transcript.delta" -> _aiTranscript.value += (obj.get("delta")?.asString ?: "")
            "input_audio_buffer.speech_started" -> { audioOut?.interrupt(); _speaking.value = false }
            "conversation.item.input_audio_transcription.completed" ->
                obj.get("transcript")?.asString?.let { if (it.isNotBlank()) _userTranscript.value = it }
            "error" -> onError(obj)
            else -> { /* 其他控制事件忽略 */ }
        }
    }

    private fun onVoiceSession(obj: JsonObject) {
        _stats.value = _stats.value.copy(
            billedMode = obj.get("billedMode")?.asString ?: "",
            perMinCredits = obj.get("perMinCredits")?.asInt ?: 0,
            maxMinutes = obj.get("maxMinutes")?.asInt ?: 0,
            minute = 1,
        )
        _state.value = VoiceState.Connected
        startAudio()
    }

    private fun onVoiceTick(obj: JsonObject) {
        val cur = _stats.value
        _stats.value = cur.copy(
            minute = obj.get("minute")?.asInt ?: cur.minute,
            billedMode = obj.get("billedMode")?.asString ?: cur.billedMode,
            creditsSpent = obj.get("creditsSpent")?.asInt ?: cur.creditsSpent,
        )
    }

    private fun onAudioDelta(obj: JsonObject) {
        val b64 = obj.get("delta")?.asString ?: return
        val pcm = runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull() ?: return
        _speaking.value = true
        audioOut?.write(pcm)
    }

    private fun onError(obj: JsonObject) {
        val err = obj.getAsJsonObject("error")
        val msg = err?.get("message")?.asString ?: "语音服务出错"
        teardownAudio()
        _state.value = VoiceState.Error(msg)
    }

    private fun endedByServer(reason: String) {
        manualStop = true
        ws?.close(CLOSE_NORMAL, "server ended")
        ws = null
        teardownAudio()
        _state.value = VoiceState.Ended(reason)
    }

    private fun startAudio() {
        val out = AudioOut()
        audioOut = out
        out.start()
        val mic = AudioIn { pcm -> sendAudio(pcm) }
        audioIn = mic
        mic.start()
    }

    private fun teardownAudio() {
        audioIn?.stop()
        audioIn = null
        audioOut?.stop()
        audioOut = null
        _speaking.value = false
    }

    private fun sendAudio(pcm: ByteArray) {
        val sock = ws ?: return
        val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
        sock.send("""{"type":"input_audio_buffer.append","audio":"$b64"}""")
    }
}
