@file:Suppress(
    "PackageNaming", "MagicNumber", "MaxLineLength", "ReturnCount",
    "TooGenericExceptionThrown", "TooGenericExceptionCaught",
    "CyclomaticComplexMethod", "InstanceOfCheckForException", "NestedBlockDepth", "ThrowsCount",
)   // 并行原文件存量:下划线包/HTTP 码等内联常量/失败链多出口/HTTP 重试圈复杂度

package com.apk.claw.android.octopus_mobile

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.pow

/**
 * 多模型路由链（Model Chain）——从 octopus-os MultiModelRouter 移植。
 *
 * 支持 primary → fallback 链式调用，当前模型失败时自动故障转移到下一个。
 * 适用于：主模型限流/超载/不存在时，自动降级到备用模型。
 *
 * 当前手机端只有一个配置模型，但架构已支持未来多模型配置。
 * 可通过 KVUtils 配置 fallback 模型列表（逗号分隔的 model 名，使用相同 baseUrl/apiKey）。
 */
class ModelChain(
    private val primary: ModelEndpoint,
    private val fallbacks: List<ModelEndpoint> = emptyList(),
    private val httpClient: okhttp3.OkHttpClient,
) {
    companion object {
        private const val TAG = "ModelChain"

        fun fromEffective(eff: com.apk.claw.android.account.EffectiveLlm, http: okhttp3.OkHttpClient): ModelChain {
            val primary = ModelEndpoint(eff.baseUrl, eff.apiKey, eff.model)
            // 备用模型:与主模型同 baseUrl/apiKey,只换 model 名(KVUtils 配置,逗号分隔);空=退化为单端点。
            val fallbacks = com.apk.claw.android.utils.KVUtils.getLlmFallbackModels()
                .filter { it != eff.model }
                .map { ModelEndpoint(eff.baseUrl, eff.apiKey, it) }
            return ModelChain(primary, fallbacks, http)
        }
    }

    data class ModelEndpoint(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
    )

    /**
     * 链式调用：依次尝试 primary → fallbacks，每端最多 retriesPerEndpoint 次重试。
     * 瞬态错误(429/5xx/超时)在同一端点重试，非瞬态错误(401/404/参数错误)直接跳到下一端点。
     */
    fun call(
        userPrompt: String,
        temperature: Double = 0.2,
        retriesPerEndpoint: Int = 2,
        baseRetryMs: Long = 800,
    ): String {
        val chain = listOf(primary) + fallbacks
        var lastError: Exception? = null

        for ((idx, endpoint) in chain.withIndex()) {
            val endpointName = if (idx == 0) "primary(${endpoint.model})" else "fallback[$idx](${endpoint.model})"
            try {
                val result = callEndpoint(endpoint, userPrompt, temperature, retriesPerEndpoint, baseRetryMs)
                if (idx > 0) {
                    EvolutionMetrics.modelFailover()
                    Log.i(TAG, "Failover to $endpointName succeeded")
                }
                return result
            } catch (e: Exception) {
                lastError = e
                val isFailoverable = isFailoverableError(e)
                Log.w(TAG, "$endpointName failed: ${e.message}, failoverable=$isFailoverable")
                if (!isFailoverable || idx >= chain.lastIndex) throw e
            }
        }
        throw lastError ?: RuntimeException("ModelChain: all endpoints failed")
    }

    private fun callEndpoint(
        endpoint: ModelEndpoint,
        userPrompt: String,
        temperature: Double,
        maxRetries: Int,
        baseRetryMs: Long,
    ): String {
        val url = endpoint.baseUrl.trimEnd('/') + "/chat/completions"
        var lastError: Exception? = null

        for (attempt in 0..maxRetries) {
            try {
                val body = JSONObject().apply {
                    put("model", endpoint.model)
                    put("temperature", temperature)
                    put("messages", JSONArray().put(
                        JSONObject().put("role", "user").put("content", userPrompt)
                    ))
                }
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer ${endpoint.apiKey}")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(request).execute().use { resp ->
                    val respBody = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val isTransient = resp.code == 429 || resp.code >= 500
                        if (isTransient && attempt < maxRetries) {
                            val delay = computeBackoff(attempt, baseRetryMs)
                            Log.w(TAG, "HTTP ${resp.code} (attempt ${attempt + 1}), retry in ${delay}ms")
                            Thread.sleep(delay)
                            return@use
                        }
                        // 404 model not found → failover to next endpoint
                        if (resp.code == 404 && respBody.contains("model", ignoreCase = true)) {
                            throw ModelFailoverException("Model not found: ${endpoint.model}")
                        }
                        error("HTTP ${resp.code}: ${respBody.take(200)}")
                    }
                    val message = JSONObject(respBody)
                        .getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                    return message.optString("content", "").takeIf { it.isNotBlank() }
                        ?: message.optString("reasoning_content", "")
                }
            } catch (e: Exception) {
                lastError = e
                if (e is ModelFailoverException) throw e
                if (attempt < maxRetries && isTransientException(e)) {
                    val delay = computeBackoff(attempt, baseRetryMs)
                    Log.w(TAG, "Call failed (attempt ${attempt + 1}): ${e.message}, retry in ${delay}ms")
                    Thread.sleep(delay)
                } else {
                    throw e
                }
            }
        }
        throw lastError ?: RuntimeException("callEndpoint failed")
    }

    private fun isFailoverableError(e: Exception): Boolean {
        if (e is ModelFailoverException) return true
        val msg = e.message ?: return false
        return msg.contains("HTTP 429") || msg.contains("HTTP 5") ||
                msg.contains("timeout", ignoreCase = true) ||
                msg.contains("connection", ignoreCase = true) ||
                msg.contains("model not found", ignoreCase = true) ||
                msg.contains("overloaded", ignoreCase = true) ||
                msg.contains("capacity", ignoreCase = true)
    }

    private fun isTransientException(e: Exception): Boolean {
        val msg = e.message ?: return false
        return msg.contains("HTTP 429") || msg.contains("HTTP 5") ||
                msg.contains("timeout", ignoreCase = true) ||
                msg.contains("connection", ignoreCase = true) ||
                msg.contains("reset", ignoreCase = true)
    }

    private fun computeBackoff(attempt: Int, baseDelayMs: Long): Long {
        val raw = (baseDelayMs * 2.0.pow(attempt)).toLong().coerceAtMost(15_000L)
        val jitter = 0.75 + Math.random() * 0.25
        return (raw * jitter).toLong().coerceAtLeast(0)
    }

    private class ModelFailoverException(msg: String) : RuntimeException(msg)
}
