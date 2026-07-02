package com.apk.claw.android.tool.impl

import com.apk.claw.android.plugin.MiniAppActionBus
import com.apk.claw.android.plugin.MiniAppRegistry
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.tool.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * `list_apps` —— 移植 OpenRoom 的两工具间接层:列出已安装 mini-app 及各自声明的可调用动作。
 * Agent 先用它发现"有哪些 app、每个 app 能被调哪些 action、参数是什么",再用 [AppActionTool] 派发。
 * 好处:不管装多少 mini-app,Agent 的工具列表永远只多 list_apps + app_action 两个,不爆炸。
 */
class ListAppsTool : BaseTool() {
    override fun getName() = "list_apps"
    override fun getDisplayName() = "列出小程序"
    override fun getDescriptionEN() =
        "List installed mini-apps and the agent-callable actions each declares (with param schemas). Call this before app_action."
    override fun getDescriptionCN() =
        "列出已安装的小程序及各自声明的可被 Agent 调用的动作(含参数)。在 app_action 之前调用。"

    override fun getParameters(): List<ToolParameter> = emptyList()

    override fun execute(params: Map<String, Any>): ToolResult {
        val running = MiniAppActionBus.runningAppId()
        val arr = JSONArray()
        MiniAppRegistry.all().forEach { m ->
            val actions = JSONArray()
            m.actions.forEach { a ->
                val ps = JSONArray()
                a.params.forEach { p ->
                    ps.put(
                        JSONObject()
                            .put("name", p.name)
                            .put("type", p.type)
                            .put("description", p.description)
                            .put("required", p.required),
                    )
                }
                actions.put(
                    JSONObject()
                        .put("action_type", a.name)
                        .put("description", a.description)
                        .put("params", ps),
                )
            }
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("name", m.name)
                    .put("description", m.description)
                    .put("running", m.id == running)
                    .put("actions", actions),
            )
        }
        return ToolResult.success(JSONObject().put("apps", arr).toString())
    }
}

/**
 * `app_action` —— 向某个 mini-app 派发一个动作(移植 OpenRoom 的通用 app_action 派发器)。
 * 目标 mini-app 未在前台运行时,先把它拉起并等其注册,再派发。动作由 mini-app 的
 * `octopus.onAgentAction` 处理并回传结果。
 */
class AppActionTool : BaseTool() {
    override fun getName() = "app_action"
    override fun getDisplayName() = "调用小程序动作"
    override fun getDescriptionEN() =
        "Dispatch an action to a mini-app (auto-opens it if not running). Args: app_id, action_type, params (JSON string). Call list_apps first to discover valid action_type + params."
    override fun getDescriptionCN() =
        "向小程序派发一个动作(未运行则自动打开)。参数:app_id、action_type、params(JSON 字符串)。先用 list_apps 查可用动作与参数。"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("app_id", "string", "小程序 id(见 list_apps)", true),
        ToolParameter("action_type", "string", "动作类型(见该小程序 actions)", true),
        ToolParameter("params", "string", "动作参数,JSON 对象字符串,如 {\"text\":\"hi\"}", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val appId = params["app_id"]?.toString()?.trim().orEmpty()
        if (appId.isBlank()) return ToolResult.error("app_id required")
        val actionType = params["action_type"]?.toString()?.trim().orEmpty()
        if (actionType.isBlank()) return ToolResult.error("action_type required")
        val paramsJson = when (val p = params["params"]) {
            null -> "{}"
            is String -> p.ifBlank { "{}" }
            else -> runCatching { com.google.gson.Gson().toJson(p) }.getOrDefault("{}")
        }

        MiniAppRegistry.get(appId) ?: return ToolResult.error("未找到小程序: $appId(用 list_apps 查可用 id)")

        // 未在前台运行 → 拉起并等注册(BaseTool.execute 本就在后台线程,可阻塞轮询)
        if (MiniAppActionBus.runningAppId() != appId) {
            val ctx = ToolRegistry.getInstance().appContext
                ?: return ToolResult.error("无法打开小程序:缺少 Context")
            MiniAppRegistry.launch(ctx, appId)
            val deadline = System.currentTimeMillis() + 4000
            while (MiniAppActionBus.runningAppId() != appId && System.currentTimeMillis() < deadline) {
                runCatching { Thread.sleep(120) }
            }
            if (MiniAppActionBus.runningAppId() != appId) {
                return ToolResult.error("小程序未能进入前台(加载慢或被拦截),请稍后重试")
            }
            runCatching { Thread.sleep(400) } // 等页面 JS 注册 onAgentAction
        }

        val res = MiniAppActionBus.dispatch(appId, actionType, paramsJson)
        return try {
            val o = JSONObject(res)
            if (o.optBoolean("ok", false)) ToolResult.success(o.optString("data", "(ok)"))
            else ToolResult.error(o.optString("error", "action failed"))
        } catch (e: Exception) {
            ToolResult.success(res)
        }
    }
}

/**
 * `read_app_events` —— 读 mini-app 主动上报(`octopus.reportAction`)的最近事件,让 Agent 感知
 * "用户在小程序里做了什么"(移植 OpenRoom reportAction 的消费侧)。
 */
class ReadAppEventsTool : BaseTool() {
    override fun getName() = "read_app_events"
    override fun getDisplayName() = "读小程序事件"
    override fun getDescriptionEN() =
        "Read recent events reported by mini-apps (user actions inside a mini-app). Use to perceive what happened in an app."
    override fun getDescriptionCN() =
        "读取小程序主动上报的最近事件(用户在小程序里的动作),用于感知页面内发生了什么。"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("limit", "number", "返回最近多少条(默认 20)", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val limit = (params["limit"] as? Number)?.toInt()
            ?: params["limit"]?.toString()?.toIntOrNull() ?: 20
        val arr = JSONArray()
        MiniAppActionBus.recentReported(limit.coerceIn(1, 50)).forEach { e ->
            arr.put(
                JSONObject()
                    .put("app_id", e.appId)
                    .put("action_type", e.actionType)
                    .put("params", e.params)
                    .put("ts", e.ts),
            )
        }
        return ToolResult.success(JSONObject().put("events", arr).toString())
    }
}
