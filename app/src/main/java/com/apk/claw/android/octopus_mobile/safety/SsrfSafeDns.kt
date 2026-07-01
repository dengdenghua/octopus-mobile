package com.apk.claw.android.octopus_mobile.safety

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * SSRF 安全的 DNS 解析器 —— 在 **OkHttp 连接期** 对实际使用的解析结果逐个过 [UrlGuard]。
 *
 * 背景(DNS rebinding):[UrlGuard.check] 在发起前解析并校验一次 IP,但 OkHttp 执行时会
 * **再解析一次**。恶意/受控 DNS 可对第一次解析返回公网 IP(骗过 check)、对第二次返回
 * 127.0.0.1/169.254.169.254/内网(实际连接)。把校验挪到 OkHttp 真正使用的这一次解析上,
 * 只放行公网地址、剔除内网/回环/link-local/元数据,即可消除该 TOCTOU 窗口。
 *
 * 用法:给 SSRF 敏感的 client 加 `.dns(SsrfSafeDns)`(与 [SsrfSafeHttp] 搭配)。
 */
object SsrfSafeDns : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val resolved = Dns.SYSTEM.lookup(hostname)
        val safe = resolved.filter { !UrlGuard.isDisallowedAddress(it) }
        if (safe.isEmpty()) {
            // 全部被判为内网/回环/元数据 → 视作解析失败,阻断连接
            throw UnknownHostException("SSRF guard: $hostname resolves only to disallowed addresses")
        }
        return safe
    }
}
