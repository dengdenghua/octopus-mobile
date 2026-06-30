package com.apk.claw.android.plugin

import android.app.Activity
import android.app.AlertDialog
import android.webkit.JavascriptInterface
import com.apk.claw.android.account.AccountRepository
import com.apk.claw.android.tool.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

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
class OctopusBridge(
    private val manifest: PluginManifest,
    private val activityRef: WeakReference<Activity>? = null,
) {

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

    /**
     * 积分内购(受 allowPay + 用户授予)。
     *
     * orderJson = `{item, credits, description}`:
     *  - item: 商品标识(英文,用于账单流水)
     *  - credits: 扣除积分数(1–1000)
     *  - description: 展示给用户的购买说明
     *
     * 流程:① 解析订单 → ② native 确认弹窗(等待用户) → ③ 服务端原子扣积分 → 返回余额。
     * 整个流程在 WebView 后台线程同步执行(runBlocking);dialog 通过 runOnUiThread 投递。
     */
    @JavascriptInterface
    fun pay(orderJson: String?): String {
        if (!PermissionGate.allowPay(manifest)) return err("pay not granted")

        val o = runCatching { JSONObject(orderJson ?: "{}") }.getOrNull()
            ?: return err("invalid order JSON")
        val item = o.optString("item").trim().ifBlank { return err("item required") }
        val credits = o.optInt("credits", 0).takeIf { it in 1..1000 }
            ?: return err("credits must be 1–1000")
        val description = o.optString("description", item).take(200)

        val activity = activityRef?.get() ?: return err("activity unavailable")

        // 等待用户在 UI 线程点确认/取消
        val confirmed = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle("${manifest.name} 请求支付")
                .setMessage("「$description」\n扣除 $credits 积分")
                .setPositiveButton("确认") { _, _ -> confirmed.set(true); latch.countDown() }
                .setNegativeButton("取消") { _, _ -> latch.countDown() }
                .setOnCancelListener { latch.countDown() }
                .show()
        }
        latch.await()
        if (!confirmed.get()) return err("用户取消支付")

        val result = runBlocking {
            AccountRepository.pluginPay(manifest.id, item, credits, description)
        }
        return result.fold(
            onSuccess = { r ->
                if (r.success) ok(JSONObject().put("balance_after", r.data?.balanceAfter ?: 0).toString())
                else err("支付失败:积分不足或服务错误")
            },
            onFailure = { err("支付失败:${it.message}") }
        )
    }

    /** 设备自动化(受 allowDevice + 用户授予):直接派发到 ToolRegistry(cap 即工具名)。 */
    @JavascriptInterface
    fun deviceAutomate(cap: String, argsJson: String?): String {
        if (!PermissionGate.allowDevice(manifest, cap)) return err("device cap not granted: $cap")
        val args: Map<String, Any> = runCatching {
            val o = JSONObject(argsJson ?: "{}")
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
