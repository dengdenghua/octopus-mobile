package com.apk.claw.android.plugin

import android.app.Activity
import android.webkit.WebView
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * mini-app 动作总线 —— 移植 OpenRoom 双工 Action 架构(MVP)。
 *
 *  - **Agent → mini-app**([dispatch]):把一个 action 派发给前台运行中的 mini-app 的 JS 处理器
 *    (`window.octopus.onAgentAction`),同步等回结果。这让 Agent 能"操作"运行中的 mini-app,而不只是调工具。
 *  - **mini-app → Agent**([onReportedAction]):记录 mini-app 主动上报的用户动作(`octopus.reportAction`),
 *    供 Agent 感知页面内发生了什么。
 *
 * 作用域:当前只支持"前台单个运行中的 mini-app"([MiniAppActivity] 单实例场景)。未运行时的拉起由
 * `AppActionTool` 负责(startActivity 后轮询等注册)。安全:dispatch 只触发 mini-app 自己的处理器,
 * 处理器再调 `octopus.callTool/device` 仍走 [OctopusBridge] 的权限门 + 来源闸门。
 */
object MiniAppActionBus {

    private class Live(
        val appId: String,
        val activityRef: WeakReference<Activity>,
        val webViewRef: WeakReference<WebView>,
    )

    @Volatile
    private var live: Live? = null

    data class ReportedEvent(val appId: String, val actionType: String, val params: String, val ts: Long)

    private val reported = ConcurrentLinkedDeque<ReportedEvent>()
    private const val MAX_REPORTED = 50

    /** MiniAppActivity 前台时注册(onResume)。 */
    fun registerLive(appId: String, activity: Activity, webView: WebView) {
        live = Live(appId, WeakReference(activity), WeakReference(webView))
    }

    /** 退到后台/销毁时注销(onPause/onDestroy)。 */
    fun unregister(appId: String) {
        if (live?.appId == appId) live = null
    }

    /** 当前前台运行中的 mini-app id(activity/webview 都还活着才算)。 */
    fun runningAppId(): String? =
        live?.takeIf { it.activityRef.get() != null && it.webViewRef.get() != null }?.appId

    /**
     * Agent → mini-app 派发一个 action。要求目标 mini-app 前台运行。
     * @return `{ok, data|error}` JSON 字符串。
     */
    fun dispatch(appId: String, actionType: String, paramsJson: String, timeoutMs: Long = 8000): String {
        val l = live?.takeIf { it.appId == appId } ?: return err("mini-app '$appId' 未在前台运行")
        val activity = l.activityRef.get() ?: return err("mini-app activity 已销毁")
        val webView = l.webViewRef.get() ?: return err("mini-app webview 已销毁")

        val latch = CountDownLatch(1)
        val raw = AtomicReference<String?>(null)
        val script = "window.__octopusDispatch(" +
            JSONObject.quote(actionType) + "," + JSONObject.quote(paramsJson) + ")"
        activity.runOnUiThread {
            try {
                webView.evaluateJavascript(script) { value -> raw.set(value); latch.countDown() }
            } catch (e: Exception) {
                raw.set(null); latch.countDown()
            }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return err("dispatch 超时")
        return decodeEvalString(raw.get()) ?: err("mini-app 无返回(是否注册了 octopus.onAgentAction?)")
    }

    /** mini-app → Agent:记录上报的动作(供 Agent 查询"页面里发生了什么")。 */
    fun onReportedAction(appId: String, actionType: String, paramsJson: String?) {
        reported.addFirst(ReportedEvent(appId, actionType, paramsJson ?: "{}", System.currentTimeMillis()))
        while (reported.size > MAX_REPORTED) reported.pollLast()
    }

    fun recentReported(limit: Int = 20): List<ReportedEvent> = reported.take(limit)

    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg).toString()

    /**
     * evaluateJavascript 回调返回的是"JSON 编码后的字面量"(如把内部字符串再套一层引号转义,
     * 或 "null")。解一层拿到 mini-app 实际返回的 `{ok,...}` 字符串。
     */
    private fun decodeEvalString(evalResult: String?): String? {
        if (evalResult == null || evalResult == "null") return null
        return try {
            JSONObject("{\"v\":$evalResult}").getString("v")
        } catch (e: Exception) {
            evalResult
        }
    }
}
