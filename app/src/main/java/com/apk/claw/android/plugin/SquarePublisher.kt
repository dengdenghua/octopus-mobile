package com.apk.claw.android.plugin

import android.content.Context
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.account.AccountConfig
import com.apk.claw.android.account.AccountStore
import com.apk.claw.android.utils.OctoHttp
import com.apk.claw.android.utils.XLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * 把「本地生成的小程序/网页」投稿到广场。
 *
 * 现状:广场是**服务端策展的只读 feed**(见 SquareCatalog),客户端此前只拉不推。这里补上客户端
 * 侧的投稿通路 —— 打包 manifest + html,带登录态 POST 到 `<squareBaseUrl>/square/publish`,由后台
 * 审核后并入 feed。服务端端点未接时(404/501)给出明确提示,不假装成功。
 *
 * 契约(POST JSON):{ id, name, description, type:"mini-app", version, html,
 *   actions:[名], allow_tools:[..], allow_hosts:[..], allow_device:[..] };鉴权 Bearer <登录 token>。
 * 期望响应 2xx,可含 { message } 展示给用户。
 */
object SquarePublisher {

    private const val TAG = "SquarePublisher"
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val http = OctoHttp.shared.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class Outcome(val ok: Boolean, val message: String)

    suspend fun publish(manifest: PluginManifest): Outcome = withContext(Dispatchers.IO) {
        val ctx = ClawApplication.instance
        // 用登录态账号 token(AccountStore.token,与余额/LLM 路由同源),不是官网控制台那个手填 token。
        val token = AccountStore.token
        if (token.isBlank()) return@withContext Outcome(false, "请先登录后再分享到广场")

        val html = readHtml(ctx, manifest)
            ?: return@withContext Outcome(false, "找不到该小程序的页面文件,无法分享(仅本地生成的小程序可投稿)")

        val base = AccountConfig.squareBaseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return@withContext Outcome(false, "广场服务地址未配置,暂时无法投稿")

        val payload = JSONObject().apply {
            put("id", manifest.id)
            put("name", manifest.name.ifBlank { manifest.id })
            put("description", manifest.description)
            put("type", "mini-app")
            put("version", manifest.version)
            put("html", html)
            put("actions", JSONArray(manifest.actions.map { it.name }))
            put("allow_tools", JSONArray(manifest.allowTools))
            put("allow_hosts", JSONArray(manifest.allowHosts))
            put("allow_device", JSONArray(manifest.allowDevice))
        }

        val req = Request.Builder()
            .url("$base/square/publish")
            .header("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody(JSON))
            .build()

        runCatching {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when {
                    resp.isSuccessful -> {
                        val msg = runCatching { JSONObject(body).optString("message") }.getOrDefault("")
                        Outcome(true, msg.ifBlank { "已提交到广场,审核通过后就能被大家看到啦" })
                    }
                    resp.code == HTTP_NOT_FOUND || resp.code == HTTP_NOT_IMPLEMENTED ->
                        Outcome(false, "服务端还没开放广场投稿(端点未接入);小程序已存在本地,可稍后再试")
                    resp.code == HTTP_UNAUTHORIZED || resp.code == HTTP_FORBIDDEN ->
                        Outcome(false, "登录已过期或没有投稿权限,请重新登录后再试")
                    else -> Outcome(false, "投稿失败:HTTP ${resp.code} ${body.take(ERROR_BODY_TAIL)}")
                }
            }
        }.getOrElse {
            XLog.w(TAG, "publish failed", it)
            Outcome(false, "网络错误,投稿失败:${it.message}")
        }
    }

    /** 读小程序入口页 html(复用 MiniAppHost 的定位逻辑,只处理本地 filesDir 的 file:// 生成物)。 */
    private fun readHtml(ctx: Context, manifest: PluginManifest): String? {
        val uri = MiniAppHost.resolvePageUrl(ctx, manifest)
        // assets 里的内置插件不投稿(uri 为空或非 file: 均返回 null)
        return uri?.takeIf { it.startsWith("file:") }
            ?.let { runCatching { File(URI(it)).readText() }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
    }

    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_NOT_IMPLEMENTED = 501
    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403
    private const val ERROR_BODY_TAIL = 120
}
