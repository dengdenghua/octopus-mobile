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
import java.util.concurrent.TimeUnit
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

    /** mini-app → Agent 事件上报(移植 OpenRoom reportAction):记录页面内发生的用户动作。 */
    @JavascriptInterface
    fun reportAction(actionType: String, paramsJson: String?) {
        MiniAppActionBus.onReportedAction(manifest.id, actionType, paramsJson)
    }

    /** 调用一个内置工具(受 allowTools 限制)。argsJson = 参数对象 JSON。 */
    @JavascriptInterface
    fun callTool(name: String, argsJson: String?): String {
        if (!PermissionGate.allowTool(manifest, name)) return err("tool not permitted: $name")
        val args: Map<String, Any> = runCatching {
            val o = JSONObject(argsJson ?: "{}")
            o.keys().asSequence().associateWith { o.get(it) }
        }.getOrElse { emptyMap() }
        // 安全(来源闸门):小程序页面是 registry 下载的不可信内容(manifest 仅字节完整性校验,
        // allow_tools 由发布者控制),必须标记为不可信来源,让高危工具走审批/BLOCK 闸门,
        // 与 WS/MCP/agent 等所有其它不可信入口一致(见 ToolRegistry.needsSourceGate)。
        val r = ToolRegistry.withUntrustedSource { ToolRegistry.executeTool(name, args) }
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
            // Activity 已在销毁中则不再弹窗,直接放行 latch(视为取消),避免泄漏 JS 桥线程
            if (activity.isFinishing || activity.isDestroyed) { latch.countDown(); return@runOnUiThread }
            AlertDialog.Builder(activity)
                .setTitle("${manifest.name} 请求支付")
                .setMessage("「$description」\n扣除 $credits 积分")
                .setPositiveButton("确认") { _, _ -> confirmed.set(true); latch.countDown() }
                .setNegativeButton("取消") { _, _ -> latch.countDown() }
                .setOnCancelListener { latch.countDown() }
                .show()
        }
        // 有超时上限:用户长时间不响应(或 Activity 被杀导致弹窗未展示)时,不无限阻塞 JS 桥线程
        val responded = latch.await(PAY_CONFIRM_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!responded) return err("支付确认超时")
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
        // 安全(来源闸门):一次性授予的设备能力若映射到高危工具名(cap 即工具名),仍须逐次过
        // 高危来源闸门(不可信内容驱动),不能因一次授予就跳过审批。见 callTool 注释。
        val r = ToolRegistry.withUntrustedSource { ToolRegistry.executeTool(cap, args) }
        return if (r.isSuccess) ok(r.data ?: "") else err(r.error ?: "device tool failed")
    }

    private fun ok(data: String) = JSONObject().put("ok", true).put("data", data).toString()
    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg).toString()

    companion object {
        /** pay() 等待用户确认的上限,超时视为取消,避免无限阻塞 WebView JS 桥线程。 */
        private const val PAY_CONFIRM_TIMEOUT_MS = 60_000L

        /** 注入页面的 `window.octopus` shim:把同步桥包成易用 API(返回已解析对象)。 */
        const val SHIM_JS = """
(function () {
  if (typeof octopusNative === 'undefined') return;
  var parse = function (s) { try { return JSON.parse(s); } catch (e) { return { ok: false, error: 'bad bridge response' }; } };
  window.octopus = {
    id: function () { return octopusNative.pluginId(); },
    callTool: function (name, args) { return parse(octopusNative.callTool(name, JSON.stringify(args || {}))); },
    pay: function (order) { return parse(octopusNative.pay(JSON.stringify(order || {}))); },
    device: function (cap, args) { return parse(octopusNative.deviceAutomate(cap, JSON.stringify(args || {}))); },
    // mini-app 设为 function(actionType, paramsObj) -> string,处理 Agent 派发来的动作(app_action)。
    onAgentAction: null,
    // mini-app → Agent 上报页面内发生的动作(Agent 可感知)。
    reportAction: function (actionType, params) {
      try { octopusNative.reportAction(actionType, JSON.stringify(params || {})); } catch (e) {}
    }
  };
  // Agent → mini-app 派发入口(由原生 evaluateJavascript 调用):调 onAgentAction 并把结果包成 {ok,data|error}。
  window.__octopusDispatch = function (actionType, paramsJson) {
    try {
      if (typeof window.octopus.onAgentAction !== 'function')
        return JSON.stringify({ ok: false, error: 'mini-app 未注册 octopus.onAgentAction' });
      var params = {}; try { params = JSON.parse(paramsJson || '{}'); } catch (e) {}
      var r = window.octopus.onAgentAction(actionType, params);
      return JSON.stringify({ ok: true, data: (r == null ? '' : String(r)) });
    } catch (e) {
      return JSON.stringify({ ok: false, error: String((e && e.message) || e) });
    }
  };
})();
"""
    }
}
