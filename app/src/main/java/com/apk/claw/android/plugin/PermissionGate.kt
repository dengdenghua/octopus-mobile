package com.apk.claw.android.plugin

import android.net.Uri
import com.apk.claw.android.utils.KVUtils

/**
 * 插件权限网关 —— 运行时按 manifest 声明 + 用户授予,门控每一次能力调用。**默认拒绝。**
 *
 * 设计(对接安全审计定位):
 *  - 普通能力(访问声明域名 / 调声明工具):manifest 声明即可,静默放行。
 *  - 敏感能力(`device.*` 自动化 / `pay` 计费):**必须 manifest 声明 + 用户显式授予**(存 KVUtils)。
 *  - 授予以 CSV 存 `PLUGIN_GRANTS_<id>`(与 KVUtils.getDisabledTools 的存法一致)。
 *
 * 注:browser-script 注入的"敏感域 denylist(银行/支付/验证码禁注)"由注入侧落实,
 *     此网关只管 tool/桥 调用的域名 + 工具 + 敏感能力。
 */
object PermissionGate {

    private fun key(id: String) = "PLUGIN_GRANTS_$id"

    fun grantedCaps(id: String): Set<String> =
        KVUtils.getString(key(id), "").split(",").filter { it.isNotBlank() }.toSet()

    fun grant(id: String, cap: String) {
        val s = grantedCaps(id).toMutableSet().apply { add(cap) }
        KVUtils.putString(key(id), s.joinToString(","))
    }

    fun revoke(id: String, cap: String) {
        val s = grantedCaps(id).toMutableSet().apply { remove(cap) }
        KVUtils.putString(key(id), s.joinToString(","))
    }

    /** 该 URL 的 host 是否在插件声明的 allowHosts 内(默认 deny;支持精确 / 子域 / "*")。 */
    fun allowHost(m: PluginManifest, url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (m.allowHosts.isEmpty()) return false
        if (m.allowHosts.any { it == "*" }) return true
        val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return false
        return m.allowHosts.any { host == it || host.endsWith(".$it") }
    }

    /** 该工具名是否在插件声明的 allowTools 内。 */
    fun allowTool(m: PluginManifest, tool: String): Boolean =
        m.allowTools.any { it == "*" || it == tool }

    /** 敏感设备能力:manifest 声明 + 用户已授予,两者皆需。 */
    fun allowDevice(m: PluginManifest, cap: String): Boolean =
        cap in m.allowDevice && cap in grantedCaps(m.id)

    /** 计费:manifest 声明 + 用户已授予 "pay"。 */
    fun allowPay(m: PluginManifest): Boolean =
        m.allowPay && "pay" in grantedCaps(m.id)
}
