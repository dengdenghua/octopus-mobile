package com.apk.claw.android.agent

import com.apk.claw.android.account.LlmRouting
import com.apk.claw.android.utils.OctoHttp
import com.apk.claw.android.utils.XLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 上下文压缩的「LLM 真总结」通道 —— 注入给 [com.apk.claw.android.octopus_mobile.memory.ContextCompressor]。
 *
 * 一次极小的无状态 LLM 调用:走 [LlmRouting.effective] 拿到跟主 Agent 完全相同的中转/BYO 路由,
 * 低温、限 max_tokens,把「更早的历史」压成要点。任何异常/未配置都返回 null,ContextCompressor
 * 随即退回旧的硬截断——**绝不阻断主循环**。
 */
object ContextSummarizer {

    private const val TAG = "ContextSummarizer"

    // 总结要快:比生成 App 更短的超时,避免拖慢主 Agent 的一次压缩。
    private val http = OctoHttp.shared.newBuilder()
        .callTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** prompt → 摘要文本;失败/未配置返回 null。 */
    fun summarize(prompt: String): String? {
        return try {
            val eff = LlmRouting.effective()
            if (eff.apiKey.isBlank() || eff.baseUrl.isBlank()) return null
            val url = eff.baseUrl.trimEnd('/') + "/chat/completions"
            val body = JSONObject().apply {
                put("model", eff.model)
                put("temperature", 0.2)
                put("max_tokens", 900)
                put(
                    "messages",
                    JSONArray().put(JSONObject().put("role", "user").put("content", prompt)),
                )
            }
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${eff.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    XLog.w(TAG, "summarize HTTP ${resp.code}: ${respBody.take(160)}")
                    return null
                }
                val message = JSONObject(respBody)
                    .getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                message.optString("content", "").takeIf { it.isNotBlank() }
                    ?: message.optString("reasoning_content", "").takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            XLog.w(TAG, "summarize failed: ${e.message}")
            null
        }
    }
}
