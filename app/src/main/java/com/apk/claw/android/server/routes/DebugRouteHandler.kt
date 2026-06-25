package com.apk.claw.android.server.routes

import android.content.Context
import com.apk.claw.android.BuildConfig
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.XLog
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD

private const val MIME_JSON = "application/json"
private const val TAG = "DebugRouteHandler"

/**
 * Debug 工具路由（仅 DEBUG 构建）
 */
class DebugRouteHandler(
    private val context: Context,
) : RouteHandler {

    override fun canHandle(uri: String, method: NanoHTTPD.Method): Boolean {
        if (!BuildConfig.DEBUG) return false
        return when (uri) {
            "/api/debug/tools" -> method == NanoHTTPD.Method.GET
            "/api/debug/execute" -> method == NanoHTTPD.Method.POST
            else -> uri.startsWith("/api/debug/file") && method == NanoHTTPD.Method.GET
        }
    }

    override fun handle(session: NanoHTTPD.IHTTPSession, ctx: RouteContext): NanoHTTPD.Response {
        return when {
            session.uri == "/api/debug/tools" && session.method == NanoHTTPD.Method.GET -> handleGetTools(ctx)
            session.uri == "/api/debug/execute" && session.method == NanoHTTPD.Method.POST -> handleExecuteTool(session, ctx)
            session.uri.startsWith("/api/debug/file") && session.method == NanoHTTPD.Method.GET -> handleServeFile(session, ctx)
            else -> ctx.corsResponse(
                NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.NOT_FOUND, MIME_JSON,
                    """{"code":-1,"message":"接口不存在"}"""
                )
            )
        }
    }

    private fun handleGetTools(ctx: RouteContext): NanoHTTPD.Response {
        val tools = ToolRegistry.getAllTools()
        val arr = JsonArray()
        for (tool in tools) {
            val obj = JsonObject().apply {
                addProperty("name", tool.getName())
                addProperty("displayName", tool.getDisplayName())
                addProperty("description", tool.getDescription())
                val params = JsonArray()
                for (p in tool.getParameters()) {
                    params.add(JsonObject().apply {
                        addProperty("name", p.name)
                        addProperty("type", p.type)
                        addProperty("description", p.description)
                        addProperty("required", p.isRequired)
                    })
                }
                add("parameters", params)
            }
            arr.add(obj)
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", arr)
        }
        return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, MIME_JSON, result.toString()))
    }

    private fun handleExecuteTool(session: NanoHTTPD.IHTTPSession, ctx: RouteContext): NanoHTTPD.Response {
        val json = ctx.readJsonBody(session)

        val toolName = json.get("tool")?.asString ?: return ctx.corsResponse(
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"缺少工具名称"}"""
            )
        )

        val params = mutableMapOf<String, Any>()
        try {
            json.getAsJsonObject("params")?.entrySet()?.forEach { (key, value) ->
                when {
                    value.isJsonNull -> {}
                    !value.isJsonPrimitive -> params[key] = value.toString()
                    value.asJsonPrimitive.isNumber -> params[key] = value.asNumber
                    value.asJsonPrimitive.isBoolean -> params[key] = value.asBoolean
                    else -> params[key] = value.asString
                }
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Debug param parse error: ${e.message}")
        }

        XLog.d(TAG, "Debug execute: $toolName params=$params")

        val toolResult = try {
            ToolRegistry.withUntrustedSource {
                ToolRegistry.executeTool(toolName, params)
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Debug execute error", e)
            ToolResult.error("Exception: ${e.message}")
        }

        val data = JsonObject().apply {
            addProperty("success", toolResult.isSuccess)
            addProperty("data", toolResult.data)
            addProperty("error", toolResult.error)
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
        }
        return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, MIME_JSON, result.toString()))
    }

    private fun handleServeFile(session: NanoHTTPD.IHTTPSession, ctx: RouteContext): NanoHTTPD.Response {
        val path = session.parms["path"] ?: return ctx.corsResponse(
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"缺少 path 参数"}"""
            )
        )
        val cacheDir = context.cacheDir.absolutePath
        val file = java.io.File(path)
        if (!file.exists() || !file.absolutePath.startsWith(cacheDir)) {
            return ctx.corsResponse(
                NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.NOT_FOUND, MIME_JSON,
                    """{"code":-1,"message":"文件不存在或无权访问"}"""
                )
            )
        }
        val mime = when (file.extension.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
        return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, mime, file.inputStream(), file.length()))
    }
}
