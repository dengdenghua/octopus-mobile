package com.apk.claw.android.plugin

import android.webkit.JavascriptInterface
import com.apk.claw.android.tool.ToolRegistry
import org.json.JSONObject

/**
 * `octopus.*` 桥 —— mini-app(H5)访问宿主能力的**权限网关化**入口.
 *
 * 暴露给页面的原生对象名为 `octopusNative`,[MiniAppActivity] 注入一段 `window.octopus` shim 包装它。
 * 每个调用先过 [PermissionGate],**默认拒绝**;敏感能力(pay/device)即便声明也需用户授予,
 * 未接通的能力**显式返回失败**(不静默假成功)。返回值统一 `{ok, data|error}` JSON 字符串。
 *
 * 安全:本桥只挂在 MiniAppActivity 的本地 file:// 页面上,且该 WebView 禁止导航/加载远端
 * (见 MiniAppActivity),避免桥被远端内容拿到。
 */
class OctopusBridge(private val manifest: PluginManifest) {

    @JavascriptInterface
    fun pluginId(): String = manifest.id

    /** 调用一个内置工具(受 allowTools 限制)。argsJson = 参数对象 JSON。 */
    @JavascriptInterface
    fun callTool(name: String, argsJson: String?): String {
        if (!PermissionGate.allowTool(manifest, name)) return err("tool not permitted: $name")
        val args: Map<String, Any> = runCatching {
            val o = JSONObject(argsJson ?: "{}")
            o.keys().asSequence().associateWith { o.get(it) }
        }.getOrElse { emptyMap() }
        val r = ToolRegistry.executeTool(name, args)
        return if (r.isSuccess) ok(r.data ?: "") else err(r.error ?: "tool failed")
    }

    /** 计费(受 allowPay + 用户授予)。扩展点:接积分/计费;未接通前显式拒绝。 */
    @JavascriptInterface
    fun pay(orderJson: String?): String {
        if (!PermissionGate.allowPay(manifest)) return err("pay not granted")
        return err("pay bridge not wired yet")
    }

    /** 设备自动化(受 allowDevice + 用户授予):直接派发到 ToolRegistry(cap 即工具名)。 */
    @JavascriptInterface
    fun deviceAutomate(cap: String, argsJson: String?): String {
        if (!PermissionGate.allowDevice(manifest, cap)) return err("device cap not granted: $cap")
        val args: Map<String, Any> = runCatching {
            val o = org.json.JSONObject(argsJson ?: "{}")
            o.keys().asSequence().associateWith { o.get(it) }
        }.getOrElse { emptyMap() }
        val r = ToolRegistry.executeTool(cap, args)
        return if (r.isSuccess) ok(r.data ?: "") else err(r.error ?: "device tool failed")
    }

    private fun ok(data: String) = JSONObject().put("ok", true).put("data", data).toString()
    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg).toString()

    companion object {
        /** 注入页面的 `window.octopus` shim:把同步桥包成易用 API(返回已解析对象)。 */
        const val SHIM_JS = """
(function () {
  if (typeof octopusNative === 'undefined') return;
  var parse = function (s) { try { return JSON.parse(s); } catch (e) { return { ok: false, error: 'bad bridge response' }; } };
  window.octopus = {
    id: function () { return octopusNative.pluginId(); },
    callTool: function (name, args) { return parse(octopusNative.callTool(name, JSON.stringify(args || {}))); },
    pay: function (order) { return parse(octopusNative.pay(JSON.stringify(order || {}))); },
    device: function (cap, args) { return parse(octopusNative.deviceAutomate(cap, JSON.stringify(args || {}))); }
  };
})();
"""
    }
}
