package com.apk.claw.android.octopus_mobile.safety

import android.util.Log
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

/**
 * URL 安全守卫 —— 从母体 runtime/safety/immunity/url_guard.py 移植.
 *
 * 防止 SSRF（Server-Side Request Forgery）：
 *  - 只允许 http/https 协议
 *  - 阻止内网 IP（10.x / 172.16-31.x / 192.168.x / 127.x）
 *  - 阻止云元数据端点（169.254.169.254 / metadata.google.internal）
 *  - 阻止本地域名（localhost / .local / .internal）
 *
 * 用法：
 * ```kotlin
 * val verdict = UrlGuard.check("https://example.com")
 * if (!verdict.allow) { /* 阻止访问 */ }
 *
 * val verdict2 = UrlGuard.check("http://169.254.169.254/latest/meta-data/")
 * // verdict2.allow = false, reason = "private_ip"
 * ```
 */
object UrlGuard {

    private const val TAG = "UrlGuard"

    private val SAFE_SCHEMES = setOf("http", "https")

    private val BLOCKED_HOSTS = setOf(
        "localhost",
        "metadata.google.internal",
        "metadata.azure.internal",
        "instance-data",
        "instance-data.ec2.internal",
    )

    private val BLOCKED_SUFFIXES = listOf(
        ".localhost",
        ".local",
        ".internal",
        ".svc.cluster.local",
        ".lan",
    )

    // ── 结果 ──────────────────────────────────────────

    data class URLVerdict(
        val allow: Boolean,
        val url: String,
        val reason: String = "",
        val resolvedIp: String? = null,
    )

    // ── 检查 ──────────────────────────────────────────

    fun check(
        url: String,
        allowPrivate: Boolean = false,
        resolveDns: Boolean = true,
    ): URLVerdict {
        if (url.isBlank()) {
            return URLVerdict(false, url, "empty_url")
        }

        val parsed = try {
            URI(url)
        } catch (e: Exception) {
            return URLVerdict(false, url, "unparseable: ${e.message}")
        }

        // 协议检查
        val scheme = (parsed.scheme ?: "").lowercase()
        if (scheme !in SAFE_SCHEMES) {
            return URLVerdict(false, url, "disallowed_scheme: $scheme")
        }

        // 主机名检查
        val host = parsed.host
        if (host.isNullOrBlank()) {
            return URLVerdict(false, url, "missing_host")
        }

        val hostLc = host.lowercase()

        if (!allowPrivate) {
            // 阻止已知危险主机名
            if (hostLc in BLOCKED_HOSTS) {
                return URLVerdict(false, url, "blocked_host: $hostLc")
            }
            // 阻止危险后缀
            for (suffix in BLOCKED_SUFFIXES) {
                if (hostLc.endsWith(suffix)) {
                    return URLVerdict(false, url, "blocked_suffix: $suffix")
                }
            }
        }

        // IP 地址检查
        val ip = parseIp(host)
        if (ip != null) {
            if (!allowPrivate && isPrivateIp(ip)) {
                return URLVerdict(false, url, "private_ip: $ip", ip)
            }
            return URLVerdict(true, url, resolvedIp = ip)
        }

        // DNS 解析检查
        if (!resolveDns) {
            return URLVerdict(true, url)
        }

        val resolved = resolveHost(host)
        if (resolved == null) {
            return URLVerdict(false, url, "dns_resolution_failed")
        }

        if (!allowPrivate && isPrivateIp(resolved)) {
            return URLVerdict(
                false, url,
                "dns_resolves_to_private: $host → $resolved",
                resolved,
            )
        }

        return URLVerdict(true, url, resolvedIp = resolved)
    }

    fun isSafeUrl(url: String, allowPrivate: Boolean = false): Boolean {
        return check(url, allowPrivate = allowPrivate).allow
    }

    // ── 内部 ──────────────────────────────────────────

    private fun parseIp(host: String): String? {
        val stripped = host.trim('[', ']')
        return try {
            InetAddress.getByName(stripped)
            stripped
        } catch (e: Exception) {
            null
        }
    }

    private fun isPrivateIp(ip: String): Boolean {
        // 10.0.0.0/8
        if (ip.startsWith("10.")) return true
        // 172.16.0.0/12
        if (ip.startsWith("172.")) {
            val second = ip.split(".").getOrNull(1)?.toIntOrNull() ?: return false
            if (second in 16..31) return true
        }
        // 192.168.0.0/16
        if (ip.startsWith("192.168.")) return true
        // 127.0.0.0/8 (loopback)
        if (ip.startsWith("127.")) return true
        // 169.254.0.0/16 (link-local / AWS metadata)
        if (ip.startsWith("169.254.")) return true
        // 0.0.0.0
        if (ip == "0.0.0.0") return true
        // ::1 (IPv6 loopback)
        if (ip == "::1" || ip == "0:0:0:0:0:0:0:1") return true
        // fe80:: (IPv6 link-local)
        if (ip.startsWith("fe80:") || ip.lowercase().startsWith("fe80:")) return true
        // fc00::/7 (IPv6 ULA)
        if (ip.startsWith("fc") || ip.startsWith("fd")) {
            if (ip.length > 2 && ip[2] == ':') return true
        }
        return false
    }

    private fun resolveHost(host: String): String? {
        return try {
            val addr = InetAddress.getByName(host)
            addr.hostAddress
        } catch (e: UnknownHostException) {
            Log.d(TAG, "DNS resolution failed: $host")
            null
        }
    }
}
