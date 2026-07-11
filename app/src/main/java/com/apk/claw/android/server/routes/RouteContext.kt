package com.apk.claw.android.server.routes

import android.content.Context
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.octopus_mobile.RemoteAccessLog
import com.apk.claw.android.octopus_mobile.nerves.EventBus
import com.apk.claw.android.shizuku.ShizukuShellService
import com.apk.claw.android.utils.runCatchingLog
import com.apk.claw.android.utils.runCatchingOrDefault
import com.apk.claw.android.utils.runCatchingOrNull
import com.google.gson.Gson
import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD

private const val MIME_JSON = "application/json"

class RouteContext(
    context: Context,
    val gson: Gson,
) {

    companion object {
        /** 每请求的 Origin,用于 CORS 回显。NanoHTTPD 单请求单线程,ThreadLocal 安全。 */
        private val requestOrigin = ThreadLocal<String?>()
    }

    /** 在请求入口绑定 Origin,供 corsResponse 回显。 */
    fun bindRequestOrigin(session: NanoHTTPD.IHTTPSession) {
        val origin = session.headers["origin"]?.trim()?.takeIf { it.isNotEmpty() }
        requestOrigin.set(origin)
    }

    /** 请求结束后清理,避免线程复用时 Origin 串号。 */
    fun clearRequestOrigin() {
        requestOrigin.remove()
    }

    /**
     * 仅放行本机与局域网来源(localhost / 127.0.0.1 / 192.168.x / 10.x),
     * 其它来源不回显 Origin(等价同源限制),避免 `*` 暴露接口给任意公网页面。
     * 通过解析 host 判断,防止 `http://10.evil.com` 这类前缀绕过。
     */
    private fun allowedOrigin(): String? {
        val origin = requestOrigin.get() ?: return null
        val host = try {
            java.net.URI(origin).host?.lowercase()
        } catch (e: Exception) {
            null
        }
        if (host.isNullOrBlank()) return null
        if (host == "localhost" || host == "::1" || host == "127.0.0.1") return origin
        val parts = host.split(".")
        if (parts.size == 4) {
            val a = parts[0].toIntOrNull() ?: return null
            val b = parts[1].toIntOrNull() ?: return null
            if (a == 10) return origin               // 10.0.0.0/8
            if (a == 192 && b == 168) return origin   // 192.168.0.0/16
        }
        return null
    }

    fun corsResponse(response: NanoHTTPD.Response): NanoHTTPD.Response {
        val allowed = allowedOrigin()
        if (allowed != null) {
            response.addHeader("Access-Control-Allow-Origin", allowed)
            response.addHeader("Vary", "Origin")
        }
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
        return response
    }

    /**
     * 读取 POST JSON body 并修正中文乱码。
     * NanoHTTPD 默认按 ISO-8859-1 把 body 读成字符串,UTF-8 中文会乱;按字节回转再以 UTF-8 解码修正。
     */
    fun readJsonBody(session: NanoHTTPD.IHTTPSession): JsonObject {
        // 直接按 Content-Length 从原始输入流读字节,以 UTF-8 解码 —— 绕开 NanoHTTPD.parseBody
        // 把 body 当 ASCII/ISO-8859-1 解码导致中文丢失的问题。
        val raw = runCatchingOrDefault("ConfigServer", "{}") {
            val len = session.headers["content-length"]?.toIntOrNull() ?: 0
            if (len <= 0) return@runCatchingOrDefault "{}"
            val buf = ByteArray(len)
            var off = 0
            while (off < len) {
                val r = session.inputStream.read(buf, off, len - off)
                if (r <= 0) break
                off += r
            }
            String(buf, 0, off, Charsets.UTF_8)
        }
        return runCatchingOrNull("ConfigServer") {
            gson.fromJson(raw.ifBlank { "{}" }, JsonObject::class.java)
        } ?: gson.fromJson("{}", JsonObject::class.java)
    }

    fun recordRemoteAccess(
        session: NanoHTTPD.IHTTPSession,
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
        runCatchingLog("ConfigServer") {
            ClawApplication.instance.eventBus.publish(
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

    fun jsonToSafeMap(json: JsonObject, redactKeys: Set<String> = emptySet()): Map<String, Any> {
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

    /**
     * 脱敏：只显示后4位，前面用 * 替代
     */
    fun maskSecret(secret: String): String {
        if (secret.isEmpty()) return ""
        if (secret.length <= 4) return secret
        return "*".repeat(secret.length - 4) + secret.takeLast(4)
    }

    /**
     * 判断是否为脱敏后的值（包含 *）
     */
    fun isMaskedValue(value: String): Boolean {
        return value.contains("*")
    }

    /**
     * 文件 API 路径白名单：仅允许访问用户存储区(/sdcard)。
     * 防止经 ?path=/data/data/<pkg>/... 遍历到 app 私有目录读取 MMKV(内含 API 密钥)。
     * ShizukuShellService.isValidPath 只校验字符集，会放行 /data/data，故必须在此再加前缀限制。
     */
    fun isAllowedUserPath(path: String): Boolean =
        (path == "/sdcard" || path.startsWith("/sdcard/")) &&
            !path.contains("..") &&
            ShizukuShellService.isValidPath(path)

    fun forbiddenPathResponse(): NanoHTTPD.Response = corsResponse(
        NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.FORBIDDEN, MIME_JSON,
            """{"code":-1,"message":"访问被拒绝,仅允许访问 /sdcard/ 路径"}"""
        )
    )

    fun sourceOf(session: NanoHTTPD.IHTTPSession): String {
        // 优先使用真实 socket 对端 IP，避免攻击者通过转发头伪造审计源。
        return session.remoteIpAddress?.takeIf { it.isNotBlank() }
            ?: session.headers["remote-addr"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: "unknown"
    }
}
