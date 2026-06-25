package com.apk.claw.android.server.routes

import com.apk.claw.android.server.AgentWebBridge
import fi.iki.elonen.NanoHTTPD

private const val MIME_JSON = "application/json"

/**
 * 网页聊天 Agent 路由：驱动同一个 Agent（双屏右侧对话用）
 */
class AgentRouteHandler : RouteHandler {

    override fun canHandle(uri: String, method: NanoHTTPD.Method): Boolean {
        return when (uri) {
            "/api/agent/run" -> method == NanoHTTPD.Method.POST
            "/api/agent/events" -> method == NanoHTTPD.Method.GET
            else -> false
        }
    }

    override fun handle(session: NanoHTTPD.IHTTPSession, ctx: RouteContext): NanoHTTPD.Response {
        return when {
            session.uri == "/api/agent/run" && session.method == NanoHTTPD.Method.POST -> handleAgentRun(session, ctx)
            session.uri == "/api/agent/events" && session.method == NanoHTTPD.Method.GET -> handleAgentEvents(session, ctx)
            else -> ctx.corsResponse(
                NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.NOT_FOUND, MIME_JSON,
                    """{"code":-1,"message":"接口不存在"}"""
                )
            )
        }
    }

    /** POST /api/agent/run { "prompt": "..." } —— 网页发指令驱动 Agent。 */
    private fun handleAgentRun(session: NanoHTTPD.IHTTPSession, ctx: RouteContext): NanoHTTPD.Response {
        val startMs = System.currentTimeMillis()
        val params = ctx.readJsonBody(session)
        val prompt = params.get("prompt")?.asString?.trim().orEmpty()
        if (prompt.isEmpty()) {
            ctx.recordRemoteAccess(session, "agent_run", false, "prompt=<empty>", startMs)
            return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, MIME_JSON, """{"code":-1,"message":"请输入指令"}"""))
        }
        val ok = AgentWebBridge.run(prompt)
        ctx.recordRemoteAccess(session, "agent_run", ok, "promptChars=${prompt.length}", startMs)
        val json = ctx.gson.toJson(mapOf(
            "code" to if (ok) 0 else -1,
            "running" to AgentWebBridge.isRunning(),
            "total" to AgentWebBridge.total(),
            "message" to if (ok) "已开始执行" else "任务正在执行中或模型未配置",
        ))
        return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, MIME_JSON, json))
    }

    /** GET /api/agent/events?since=N —— 拉取从游标 N 起的增量事件(轮询)。 */
    private fun handleAgentEvents(session: NanoHTTPD.IHTTPSession, ctx: RouteContext): NanoHTTPD.Response {
        val since = session.parms["since"]?.toIntOrNull() ?: 0
        val evs = AgentWebBridge.eventsSince(since).map {
            mapOf("i" to it.i, "type" to it.type, "data" to it.data)
        }
        val json = ctx.gson.toJson(mapOf(
            "code" to 0,
            "running" to AgentWebBridge.isRunning(),
            "total" to AgentWebBridge.total(),
            "events" to evs,
        ))
        return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, MIME_JSON, json))
    }
}
