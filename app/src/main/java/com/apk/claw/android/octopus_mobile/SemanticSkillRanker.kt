package com.apk.claw.android.octopus_mobile
import com.apk.claw.android.utils.OctoHttp

import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 语义排序客户端 —— 把候选技能/缓存动作按"意思"排序,而不是关键词 `contains`。
 *
 * 委托给 octopus-agent 网关的 `/api/retrieve/rank`(服务端用可配嵌入器,缺嵌入器
 * 时退回词法重叠,永不弱于关键词)。手机与 agent 配对时可用;离线/未配对/出错
 * 时返回 null,调用方保留原有关键词路径。
 *
 * 用法:
 *   val order = SemanticSkillRanker.rank(userTask, skills.map { it.name + " " + it.description })
 *   val ranked = order?.map { skills[it] } ?: skills   // null → 原顺序兜底
 */
object SemanticSkillRanker {
    private const val TAG = "SemanticRank"
    private const val GATEWAY_HTTP_PORT = 8000

    private val http: OkHttpClient by lazy {
        OctoHttp.shared.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /** 从已配对的 ws/wss RPC 地址推导 HTTP 网关基址。
     *  wss://host:8765 → https://host:8000 (强制 TLS,保护 query 和排序结果不被嗅探/篡改)
     *  ws://host:8765 → http://host:8000 (仅 loopback/局域网回退,生产环境应配 wss://) */
    private fun gatewayHttpBase(): String {
        val ws = KVUtils.getOctopusRpcUrl().trim()
        if (ws.isEmpty()) return ""
        return try {
            val uri = android.net.Uri.parse(ws)
            val host = uri.host ?: return ""
            val scheme = if (ws.startsWith("wss://")) "https" else "http"
            "$scheme://$host:$GATEWAY_HTTP_PORT"
        } catch (e: Exception) {
            ""
        }
    }

    /** 获取母体配对 token,用于 HTTP 网关鉴权。 */
    private fun gatewayAuthToken(): String {
        return KVUtils.getOctopusAuthToken().trim()
    }

    /**
     * 返回 [candidates] 的下标,按与 [query] 的相关度从高到低;取前 [topK] 个。
     * 失败(离线/未配对/网络错)返回 null —— 调用方据此走关键词兜底。
     */
    suspend fun rank(
        query: String,
        candidates: List<String>,
        topK: Int = 8,
    ): List<Int>? = withContext(Dispatchers.IO) {
        val base = gatewayHttpBase()
        if (base.isEmpty() || query.isBlank() || candidates.isEmpty()) {
            return@withContext null
        }
        try {
            val payload = JSONObject().apply {
                put("query", query)
                put("candidates", JSONArray(candidates))
                put("top_k", topK)
            }
            val request = Request.Builder()
                .url("$base/api/retrieve/rank")
                .post(payload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .apply {
                    // 携带母体配对 token 鉴权,避免未授权请求
                    val tok = gatewayAuthToken()
                    if (tok.isNotEmpty()) {
                        addHeader("Authorization", "Bearer $tok")
                    }
                }
                .build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val json = JSONObject(resp.body?.string() ?: return@withContext null)
                val ranked = json.optJSONArray("ranked") ?: return@withContext null
                val order = ArrayList<Int>(ranked.length())
                for (i in 0 until ranked.length()) {
                    val idx = ranked.getJSONObject(i).optInt("index", -1)
                    if (idx in candidates.indices) order.add(idx)
                }
                XLog.i(TAG, "ranked ${order.size}/${candidates.size} via ${json.optString("backend")}")
                if (order.isEmpty()) null else order
            }
        } catch (e: Exception) {
            XLog.w(TAG, "semantic rank failed, keyword fallback: ${e.message}")
            null
        }
    }
}
