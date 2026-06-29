package com.apk.claw.android.server.routes

import com.apk.claw.android.BuildConfig
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolRegistry
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import fi.iki.elonen.NanoHTTPD

private const val MIME_JSON = "application/json"
private const val MIME_SSE = "text/event-stream"

/**
 * MCP（Model Context Protocol）server 路由 —— 把本机 [ToolRegistry] 暴露为标准 MCP 工具，
 * 让任意 MCP 客户端（Claude Desktop / Cursor / OpenClaw）一行配置即可把这台手机当"数字员工"驱动。
 *
 * 传输：MCP Streamable HTTP —— 单 `POST /mcp` 端点，JSON-RPC 2.0。
 *   - 客户端 `Accept: application/json` → 即时 JSON 响应；
 *   - 客户端 `Accept: text/event-stream` → 把同一份响应包成单个 SSE 事件返回（兼容要求 SSE 的客户端）。
 * 鉴权：复用 ConfigServer token（`Authorization: Bearer <token>` 或 `?token=<token>`），/mcp 非公开自动强制。
 * 安全：tools/call 包在 [ToolRegistry.withUntrustedSource] 内 —— 外部客户端=不可信来源，高危工具走来源闸门。
 *
 * 协议分发逻辑在纯对象 [McpServerCore]（无 Android/HTTP 依赖，可离线单测）；本类只做 HTTP 传输适配。
 */
class McpRouteHandler : RouteHandler {

    /** 把 ToolRegistry 适配成 [McpToolProvider]，供纯核心调用。 */
    private val toolProvider = object : McpToolProvider {
        override fun list(): List<McpToolSpec> =
            ToolRegistry.getInstance().getAllTools().map {
                McpToolSpec(it.getName(), it.getDescription(), it.getParametersWithWaitAfter())
            }

        override fun call(name: String, args: Map<String, Any>): McpCallOutcome {
            // 外部 MCP 客户端 = 不可信来源：高危工具走来源闸门/审批（安全默认）。
            val r = ToolRegistry.getInstance().withUntrustedSource {
                ToolRegistry.getInstance().executeTool(name, args)
            }
            val text = if (r.isSuccess) (r.data ?: "OK") else (r.error ?: "Tool failed")
            return McpCallOutcome(text, !r.isSuccess)
        }
    }

    override fun canHandle(uri: String, method: NanoHTTPD.Method): Boolean =
        uri == "/mcp" && (method == NanoHTTPD.Method.POST || method == NanoHTTPD.Method.GET)

    override fun handle(session: NanoHTTPD.IHTTPSession, ctx: RouteContext): NanoHTTPD.Response {
        // GET /mcp：MCP 允许服务端不提供 server→client 通知流，返回 405（本 server 不主动推送）。
        if (session.method == NanoHTTPD.Method.GET) {
            return ctx.corsResponse(
                NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, MIME_JSON,
                    """{"jsonrpc":"2.0","error":{"code":-32600,"message":"Use POST for JSON-RPC requests"}}""",
                )
            )
        }

        val startMs = System.currentTimeMillis()
        val body = try {
            ctx.readJsonBody(session)
        } catch (e: Exception) {
            return respond(ctx, session, McpServerCore.errorEnvelope(null, -32700, "Parse error: ${e.message}"))
        }

        val method = body.get("method")?.asString.orEmpty()
        val id: JsonElement? = body.get("id")
        val params: JsonObject = body.getAsJsonObject("params") ?: JsonObject()

        // 通知（无 id，notifications/*）：JSON-RPC 不需响应体，回 202。
        if (id == null || id.isJsonNull) {
            ctx.recordRemoteAccess(session, "mcp_notify", true, "method=$method", startMs)
            return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.ACCEPTED, MIME_JSON, ""))
        }

        val envelope = try {
            McpServerCore.dispatch(method, id, params, toolProvider, "octopus-mobile", BuildConfig.VERSION_NAME)
        } catch (e: Exception) {
            ctx.recordRemoteAccess(session, "mcp_error", false, "method=$method err=${e.message}", startMs)
            McpServerCore.errorEnvelope(id, -32603, "Internal error: ${e.message}")
        }
        val ok = envelope.containsKey("result")
        ctx.recordRemoteAccess(
            session,
            if (method == "tools/call") "mcp_call" else "mcp_$method",
            ok,
            if (method == "tools/call") "tool=${params.get("name")?.asString}" else "method=$method",
            startMs,
        )
        return respond(ctx, session, envelope)
    }

    /** 按客户端 Accept 头决定回 JSON 还是 SSE（同一份 JSON-RPC 响应，两种封装）。 */
    private fun respond(ctx: RouteContext, session: NanoHTTPD.IHTTPSession, envelope: Map<String, Any?>): NanoHTTPD.Response {
        val json = gson.toJson(envelope)
        val accept = session.headers["accept"].orEmpty()
        return if (accept.contains(MIME_SSE, ignoreCase = true)) {
            // 单个 SSE 事件承载这次响应，随后关闭（请求-响应模型）。
            val sse = "event: message\ndata: $json\n\n"
            val resp = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, MIME_SSE, sse)
            resp.addHeader("Cache-Control", "no-cache")
            ctx.corsResponse(resp)
        } else {
            ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, MIME_JSON, json))
        }
    }

    private val gson = Gson()
}

// ───────────────────────── 纯协议核心（可离线单测） ─────────────────────────

/** 一个 MCP 工具规格（名称 / 描述 / 参数），供 tools/list 生成 schema。 */
internal data class McpToolSpec(val name: String, val description: String, val parameters: List<ToolParameter>)

/** 一次工具调用结果：文本 + 是否错误（MCP 把工具错误作为 result 返回，而非 JSON-RPC error）。 */
internal data class McpCallOutcome(val text: String, val isError: Boolean)

/** 工具后端抽象 —— 解耦协议核心与具体 ToolRegistry，便于注入 mock 测试。 */
internal interface McpToolProvider {
    fun list(): List<McpToolSpec>
    fun call(name: String, args: Map<String, Any>): McpCallOutcome
}

/**
 * MCP JSON-RPC 2.0 分发核心。纯逻辑、无 Android/HTTP 依赖。
 * 输入 (method, id, params) + 工具后端，输出完整的 JSON-RPC 响应信封（result 或 error）。
 */
internal object McpServerCore {

    const val PROTOCOL_VERSION = "2024-11-05"
    private val gson = Gson()

    fun dispatch(
        method: String,
        id: JsonElement,
        params: JsonObject,
        provider: McpToolProvider,
        serverName: String,
        serverVersion: String,
    ): Map<String, Any?> = when (method) {
        "initialize" -> okEnvelope(id, mapOf(
            "protocolVersion" to PROTOCOL_VERSION,
            "capabilities" to mapOf("tools" to mapOf("listChanged" to false)),
            "serverInfo" to mapOf("name" to serverName, "version" to serverVersion),
        ))

        "tools/list" -> okEnvelope(id, mapOf(
            "tools" to provider.list().map {
                mapOf(
                    "name" to it.name,
                    "description" to it.description,
                    "inputSchema" to McpSchema.inputSchema(it.parameters),
                )
            },
        ))

        "tools/call" -> {
            val name = params.get("name")?.asString
            if (name.isNullOrBlank()) {
                okEnvelope(id, errorContent("Missing tool name"))
            } else {
                val argsObj = params.getAsJsonObject("arguments") ?: JsonObject()
                val mapType = object : TypeToken<Map<String, Any>>() {}.type
                val args: Map<String, Any> = gson.fromJson(argsObj, mapType) ?: emptyMap()
                val outcome = provider.call(name, args)
                okEnvelope(id, mapOf(
                    "content" to listOf(mapOf("type" to "text", "text" to outcome.text)),
                    "isError" to outcome.isError,
                ))
            }
        }

        "ping" -> okEnvelope(id, emptyMap<String, Any>())

        else -> errorEnvelope(id, -32601, "Method not found: $method")
    }

    fun okEnvelope(id: JsonElement, result: Any): Map<String, Any?> =
        mapOf("jsonrpc" to "2.0", "id" to id, "result" to result)

    fun errorEnvelope(id: JsonElement?, code: Int, message: String): Map<String, Any?> =
        mapOf("jsonrpc" to "2.0", "id" to id, "error" to mapOf("code" to code, "message" to message))

    private fun errorContent(message: String): Map<String, Any> = mapOf(
        "content" to listOf(mapOf("type" to "text", "text" to message)),
        "isError" to true,
    )
}

/**
 * MCP 工具参数 → JSON Schema 的纯映射逻辑（无 Android 依赖，可单测）。
 */
internal object McpSchema {

    /** 把工具参数列表转成 JSON Schema（object + properties + required）。 */
    fun inputSchema(params: List<ToolParameter>): Map<String, Any> {
        val properties = LinkedHashMap<String, Any>()
        val required = ArrayList<String>()
        for (p in params) {
            properties[p.name] = mapOf("type" to jsonSchemaType(p.type), "description" to p.description)
            if (p.isRequired) required.add(p.name)
        }
        return mapOf(
            "type" to "object",
            "properties" to properties,
            "required" to required,
        )
    }

    fun jsonSchemaType(type: String): String = when (type.lowercase()) {
        "integer", "int", "long" -> "integer"
        "number", "float", "double" -> "number"
        "boolean", "bool" -> "boolean"
        "array", "list" -> "array"
        "object", "map" -> "object"
        else -> "string"
    }
}
