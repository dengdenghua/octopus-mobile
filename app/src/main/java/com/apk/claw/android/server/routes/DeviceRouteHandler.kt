package com.apk.claw.android.server.routes

import com.apk.claw.android.ClawApplication
import com.apk.claw.android.octopus_mobile.safety.ToolRiskPolicy
import com.apk.claw.android.server.RemoteControlIndicator
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.tool.ToolResult
import fi.iki.elonen.NanoHTTPD

private const val MIME_JSON = "application/json"

/**
 * 多设备协同 & 远程控制输入路由
 */
class DeviceRouteHandler : RouteHandler {

    private val deviceRegistry = ClawApplication.instance.deviceRegistry
    private val deviceDiscoveryManager = ClawApplication.instance.deviceDiscoveryManager

    override fun canHandle(uri: String, method: NanoHTTPD.Method): Boolean {
        return when (uri) {
            "/api/devices" -> method == NanoHTTPD.Method.GET
            "/api/devices/discover" -> method == NanoHTTPD.Method.POST
            "/api/control/input" -> method == NanoHTTPD.Method.POST
            else -> false
        }
    }

    override fun handle(session: NanoHTTPD.IHTTPSession, ctx: RouteContext): NanoHTTPD.Response {
        return when {
            session.uri == "/api/devices" && session.method == NanoHTTPD.Method.GET -> handleGetDevices(ctx)
            session.uri == "/api/devices/discover" && session.method == NanoHTTPD.Method.POST -> handleDiscoverDevices(ctx)
            session.uri == "/api/control/input" && session.method == NanoHTTPD.Method.POST -> handleControlInput(session, ctx)
            else -> ctx.corsResponse(
                NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.NOT_FOUND, MIME_JSON,
                    """{"code":-1,"message":"接口不存在"}"""
                )
            )
        }
    }

    /**
     * GET /api/devices
     * 获取已发现的设备列表。
     */
    private fun handleGetDevices(ctx: RouteContext): NanoHTTPD.Response {
        val devices = deviceRegistry.getAllDevices().map { d ->
            mapOf(
                "deviceId" to d.deviceId,
                "deviceName" to d.deviceName,
                "ip" to d.ip,
                "configServerPort" to d.configServerPort,
                "androidVersion" to d.androidVersion,
                "appVersion" to d.appVersion,
                "online" to d.online,
                "lastSeenTs" to d.lastSeenTs,
                "baseUrl" to d.getBaseUrl()
            )
        }
        val json = ctx.gson.toJson(mapOf("code" to 0, "data" to devices))
        return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, MIME_JSON, json))
    }

    /**
     * POST /api/devices/discover
     * 启动设备发现（如果尚未启动）。
     */
    private fun handleDiscoverDevices(ctx: RouteContext): NanoHTTPD.Response {
        if (!deviceDiscoveryManager.isRunning()) {
            deviceDiscoveryManager.start()
        }
        val devices = deviceRegistry.getOnlineDevices().map { d ->
            mapOf(
                "deviceId" to d.deviceId,
                "deviceName" to d.deviceName,
                "ip" to d.ip,
                "online" to d.online
            )
        }
        val json = ctx.gson.toJson(mapOf(
            "code" to 0,
            "message" to "设备发现已开启",
            "data" to devices
        ))
        return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, MIME_JSON, json))
    }

    /**
     * POST /api/control/input
     * 远程输入：tap/swipe/key/text/back/home/recent/notifications/long_press/open_app
     */
    private fun handleControlInput(session: NanoHTTPD.IHTTPSession, ctx: RouteContext): NanoHTTPD.Response {
        val startMs = System.currentTimeMillis()
        val params = ctx.readJsonBody(session)

        val action = params.get("action")?.asString
            ?: run {
                ctx.recordRemoteAccess(session, "control_input", false, "action=<missing>", startMs)
                return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.BAD_REQUEST, MIME_JSON,
                    """{"code":-1,"message":"缺少 action"}"""
                ))
            }

        val toolResult: ToolResult = when (action) {
            "tap" -> runTool(
                "tap",
                mapOf(
                    "x" to (params.get("x")?.asInt ?: 0),
                    "y" to (params.get("y")?.asInt ?: 0),
                )
            )
            "swipe" -> runTool(
                "swipe",
                mapOf(
                    "start_x" to (params.get("x1")?.asInt ?: 0),
                    "start_y" to (params.get("y1")?.asInt ?: 0),
                    "end_x" to (params.get("x2")?.asInt ?: 0),
                    "end_y" to (params.get("y2")?.asInt ?: 0),
                    "duration_ms" to (params.get("duration")?.asLong ?: 300L),
                )
            )
            "key" -> runTool(
                "system_key",
                mapOf("key_code" to (params.get("keyCode")?.asInt ?: 0))
            )
            "text" -> runTool(
                "input_text",
                mapOf("text" to (params.get("text")?.asString ?: ""))
            )
            "long_press" -> runTool(
                "long_press",
                mapOf(
                    "x" to (params.get("x")?.asInt ?: 0),
                    "y" to (params.get("y")?.asInt ?: 0),
                    "duration_ms" to (params.get("duration")?.asLong ?: 600L),
                )
            )
            "open_app" -> runTool(
                "open_app",
                mapOf("package_name" to (params.get("package")?.asString ?: ""))
            )
            "back" -> runTool("system_key", mapOf("key" to "back"))
            "home" -> runTool("system_key", mapOf("key" to "home"))
            "recent" -> runTool("system_key", mapOf("key" to "recent_apps"))
            "notifications" -> runTool("system_key", mapOf("key" to "notifications"))
            else -> ToolResult.error("Unknown action: $action")
        }

        val success = toolResult.isSuccess
        val message = if (success) "操作已执行" else "操作失败: $action (${toolResult.error})"
        val json = ctx.gson.toJson(mapOf(
            "code" to if (success) 0 else -1,
            "message" to message
        ))
        val safeParams = ToolRiskPolicy.summarizeParams(ctx.jsonToSafeMap(params, setOf("text")))
        ctx.recordRemoteAccess(session, "control_input", success, "action=$action,params=$safeParams", startMs)
        // 远程控制指示器：通知被控方有人正在操作
        RemoteControlIndicator.onControlInput(ctx.sourceOf(session))
        return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, MIME_JSON, json))
    }

    /** 在不可信来源上下文中执行工具，供 LAN HTTP 控制输入统一调用。 */
    private fun runTool(toolName: String, params: Map<String, Any>): ToolResult {
        return ToolRegistry.withUntrustedSource {
            ToolRegistry.executeTool(toolName, params)
        }
    }
}
