package com.apk.claw.android.octopus_mobile.safety

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * SSRF 安全的 HTTP 执行器 —— 对"会跟随重定向"的出站请求做**逐跳** [UrlGuard] 校验。
 *
 * 背景:OkHttp 默认自动跟随 3xx 重定向,若只在发起前校验首跳 URL,攻击者可让受控外部
 * 主机回 `302 Location: http://169.254.169.254/...`(云元数据)或 `http://127.0.0.1:9527/...`
 * (本机服务),OkHttp 透明跟随即造成 SSRF。本执行器要求传入**已禁用自动重定向**的 client,
 * 自己手动跟随并对每一跳(含初始 URL)重跑 [UrlGuard.check],任一跳不通过即抛
 * [SecurityException],绝不连到内网/回环/link-local/元数据端点。
 *
 * 用法:
 * ```kotlin
 * val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
 * SsrfSafeHttp.execute(client, request).use { resp -> ... }
 * ```
 */
object SsrfSafeHttp {

    private const val MAX_REDIRECTS = 5

    /**
     * 逐跳校验地执行请求并手动跟随重定向。
     *
     * @param client 必须以 `followRedirects(false).followSslRedirects(false)` 构建
     * @param initial 初始请求
     * @param hopAllowed 可选的额外逐跳白名单校验(如插件 manifest 的 allowHost),对初始 URL 与
     *        每个重定向目标都会调用;返回 false 则整体以 [SecurityException] 中止。
     * @return 最终响应(调用方负责 close)
     * @throws SecurityException 任一跳(含初始 URL)未通过 UrlGuard 或 [hopAllowed]
     * @throws IOException 网络错误或超过最大重定向次数
     */
    fun execute(
        client: OkHttpClient,
        initial: Request,
        hopAllowed: ((String) -> Boolean)? = null,
    ): Response {
        var request = initial
        var hops = 0
        while (true) {
            val currentUrl = request.url.toString()
            val verdict = UrlGuard.check(currentUrl)
            if (!verdict.allow) {
                throw SecurityException("URL blocked by SSRF guard: ${verdict.reason}")
            }
            if (hopAllowed != null && !hopAllowed(currentUrl)) {
                throw SecurityException("URL not permitted by caller allowlist: $currentUrl")
            }

            val resp = client.newCall(request).execute()
            val code = resp.code
            if (code !in REDIRECT_CODES) return resp

            val location = resp.header("Location")
            if (location.isNullOrBlank()) return resp  // 无 Location 的 3xx,原样返回

            if (++hops > MAX_REDIRECTS) {
                resp.close()
                throw IOException("too many redirects (>$MAX_REDIRECTS)")
            }
            val nextUrl = resp.request.url.resolve(location)
            if (nextUrl == null) {
                resp.close()
                throw IOException("invalid redirect location: $location")
            }
            resp.close()

            // 按 HTTP 语义构造下一跳:303,以及非幂等方法遇 301/302,降级为 GET;307/308 保持方法与 body
            val method = request.method
            val builder = request.newBuilder().url(nextUrl)
            if (code == 303 || ((code == 301 || code == 302) && method != "GET" && method != "HEAD")) {
                builder.method("GET", null)
                builder.removeHeader("Content-Type")
                builder.removeHeader("Content-Length")
                builder.removeHeader("Transfer-Encoding")
            }
            request = builder.build()
        }
    }

    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
}
