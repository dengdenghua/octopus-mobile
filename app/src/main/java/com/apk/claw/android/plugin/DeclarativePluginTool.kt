package com.apk.claw.android.plugin

import com.apk.claw.android.octopus_mobile.safety.SsrfSafeDns
import com.apk.claw.android.octopus_mobile.safety.SsrfSafeHttp
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.OctoHttp
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/**
 * 声明式工具插件 —— `type=tool` 的插件运行时.
 *
 * 不跑任意原生代码:一个工具 = manifest 里一段 HTTP 配方(method/url/headers/body,`{{param}}`
 * 占位),执行时按入参替换 + **过 [PermissionGate.allowHost] 域名网关** + OkHttp 同步请求。
 * 既给 agent "可执行插件",又躲开 dex/沙箱风险与商店政策。
 *
 * 风险:未知工具名在 ToolRiskPolicy 默认 RISK_LOW;HTTP 已被声明+授予的 host 限定,可接受。
 */
class DeclarativePluginTool(private val manifest: PluginManifest) : BaseTool() {

    companion object {
        // 禁用自动重定向:allowHost 只校验首跳,若跟随 302 到内网/元数据即 SSRF。
        // 改由 SsrfSafeHttp 逐跳过 UrlGuard + 手动重定向,并对每一跳重跑 allowHost。
        // dns(SsrfSafeDns):连接期再校验解析结果,消除 DNS rebinding 窗口。
        private val NO_REDIRECT_CLIENT: OkHttpClient = OctoHttp.shared.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .dns(SsrfSafeDns)
            .build()
    }

    override fun getName(): String = manifest.toolName.ifBlank { "plugin_${manifest.id}" }
    override fun getDisplayName(): String = manifest.name.ifBlank { getName() }
    override fun getDescriptionEN(): String = manifest.description
    override fun getDescriptionCN(): String = manifest.description

    override fun getParameters(): List<ToolParameter> =
        manifest.toolParams.map { ToolParameter(it.name, it.type, it.description, it.required) }

    override fun execute(params: Map<String, Any>): ToolResult {
        val recipe = manifest.http ?: return ToolResult.error("plugin tool has no http recipe")
        val url = substitute(recipe.url, params, urlEncode = true)
        if (!PermissionGate.allowHost(manifest, url)) {
            return ToolResult.error("Host not permitted by plugin manifest: $url")
        }
        return try {
            val builder = Request.Builder().url(url)
            recipe.headers.forEach { (k, v) -> builder.addHeader(k, substitute(v, params, false)) }
            val method = recipe.method.uppercase()
            if (method == "GET" || method == "HEAD") {
                builder.method(method, null)
            } else {
                val bodyStr = substitute(recipe.body ?: "", params, false)
                val mt = (recipe.headers["Content-Type"] ?: "application/json").toMediaTypeOrNull()
                builder.method(method, bodyStr.toRequestBody(mt))
            }
            // 安全(SSRF):禁用自动重定向,逐跳过 UrlGuard(拒内网/回环/元数据)+ 逐跳重跑
            // manifest allowHost(重定向目标也必须在声明域内),防 302→内网/越域。
            SsrfSafeHttp.execute(NO_REDIRECT_CLIENT, builder.build()) { hopUrl ->
                PermissionGate.allowHost(manifest, hopUrl)
            }.use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) ToolResult.success(body.ifBlank { "OK (${resp.code})" })
                else ToolResult.error("HTTP ${resp.code}: ${body.take(300)}")
            }
        } catch (e: SecurityException) {
            ToolResult.error("plugin tool blocked: ${e.message}")
        } catch (e: Exception) {
            ToolResult.error("plugin tool request failed: ${e.message}")
        }
    }

    /** 把模板里的 `{{key}}` 用入参替换;URL 段做 URL 编码,header/body 原样。 */
    private fun substitute(tpl: String, params: Map<String, Any>, urlEncode: Boolean): String {
        var out = tpl
        params.forEach { (k, v) ->
            val value = v.toString()
            val rep = if (urlEncode) URLEncoder.encode(value, "UTF-8") else value
            out = out.replace("{{$k}}", rep)
        }
        return out
    }
}
