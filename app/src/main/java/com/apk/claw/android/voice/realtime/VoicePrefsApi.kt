@file:Suppress("MagicNumber")

package com.apk.claw.android.voice.realtime

import com.apk.claw.android.account.AccountConfig
import com.apk.claw.android.account.AccountStore
import com.apk.claw.android.utils.OctoHttp
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 语音个性化(音色/人设)客户端 API,对接 server 的 /voice/prefs(账号服务器,非广场域)。
 * 失败抛异常,调用方自行 try/catch。
 */
internal object VoicePrefsApi {

    private val gson = Gson()
    private val http = OctoHttp.shared.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    data class VoicePrefs(
        val voice: String = "",
        val persona: String = "",
        val presets: List<String> = emptyList(),
        @SerializedName("hasCloned") val hasCloned: Boolean = false,
        val default: String = "",
    )

    private fun base(): String = AccountConfig.baseUrl.trim().trimEnd('/')

    suspend fun get(): VoicePrefs = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(base() + "/voice/prefs")
            .header("Authorization", "Bearer " + AccountStore.token).build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            gson.fromJson(body, VoicePrefs::class.java) ?: VoicePrefs()
        }
    }

    suspend fun save(voice: String, persona: String): Boolean = withContext(Dispatchers.IO) {
        val payload = gson.toJson(mapOf("voice" to voice, "persona" to persona))
        val req = Request.Builder().url(base() + "/voice/prefs")
            .header("Authorization", "Bearer " + AccountStore.token)
            .post(payload.toRequestBody(jsonType)).build()
        http.newCall(req).execute().use { it.isSuccessful }
    }

    /** 上传本人录音(base64 WAV)做声音复刻。成功返回 voiceId,失败返回 null(含服务端 4xx/5xx)。 */
    suspend fun clone(audioBase64: String): String? = withContext(Dispatchers.IO) {
        val cloneHttp = OctoHttp.shared.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)   // enroll 上游可能稍慢
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
        val payload = gson.toJson(mapOf("audio" to audioBase64))
        val req = Request.Builder().url(base() + "/voice/clone")
            .header("Authorization", "Bearer " + AccountStore.token)
            .post(payload.toRequestBody(jsonType)).build()
        cloneHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val body = resp.body?.string().orEmpty()
            runCatching { gson.fromJson(body, CloneResult::class.java)?.voiceId }.getOrNull()
        }
    }

    private data class CloneResult(val ok: Boolean = false, val voiceId: String = "")
}
