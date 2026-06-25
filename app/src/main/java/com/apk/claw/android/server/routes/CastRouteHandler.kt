package com.apk.claw.android.server.routes

import com.apk.claw.android.cast.ScreenCastService
import fi.iki.elonen.NanoHTTPD

private const val MIME_JSON = "application/json"

/**
 * 异步投屏 API 路由
 */
class CastRouteHandler(
    private val context: android.content.Context,
) : RouteHandler {

    override fun canHandle(uri: String, method: NanoHTTPD.Method): Boolean {
        return when (uri) {
            "/api/cast" -> method == NanoHTTPD.Method.GET
            "/api/cast/start" -> method == NanoHTTPD.Method.POST
            "/api/cast/stop" -> method == NanoHTTPD.Method.POST
            "/api/cast/launch" -> method == NanoHTTPD.Method.POST
            else -> false
        }
    }

    override fun handle(session: NanoHTTPD.IHTTPSession, ctx: RouteContext): NanoHTTPD.Response {
        return when {
            session.uri == "/api/cast" && session.method == NanoHTTPD.Method.GET -> handleGetCast(ctx)
            session.uri == "/api/cast/start" && session.method == NanoHTTPD.Method.POST -> handleStartCast(ctx)
            session.uri == "/api/cast/stop" && session.method == NanoHTTPD.Method.POST -> handleStopCast(ctx)
            session.uri == "/api/cast/launch" && session.method == NanoHTTPD.Method.POST -> handleCastLaunch(session, ctx)
            else -> ctx.corsResponse(
                NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.NOT_FOUND, MIME_JSON,
                    """{"code":-1,"message":"接口不存在"}"""
                )
            )
        }
    }

    /**
     * GET /api/cast —— 获取投屏状态。
     */
    private fun handleGetCast(ctx: RouteContext): NanoHTTPD.Response {
        val castService = ScreenCastService.getInstance(context)
        val info = castService.getStatusInfo()
        val json = ctx.gson.toJson(mapOf("code" to 0, "data" to info))
        return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, MIME_JSON, json))
    }

    /**
     * POST /api/cast/start —— 启动投屏。
     */
    private fun handleStartCast(ctx: RouteContext): NanoHTTPD.Response {
        val castService = ScreenCastService.getInstance(context)
        castService.start()
        val json = ctx.gson.toJson(mapOf(
            "code" to 0,
            "message" to "投屏服务已启动",
            "data" to castService.getStatusInfo()
        ))
        return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, MIME_JSON, json))
    }

    /**
     * POST /api/cast/stop —— 停止投屏。
     */
    private fun handleStopCast(ctx: RouteContext): NanoHTTPD.Response {
        val castService = ScreenCastService.getInstance(context)
        castService.stop()
        val json = ctx.gson.toJson(mapOf("code" to 0, "message" to "投屏服务已停止"))
        return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, MIME_JSON, json))
    }

    /**
     * POST /api/cast/launch —— 在外接屏上启动 App。
     *
     * Body: { "package_name": "com.tencent.mm", "x": 100, "y": 100, "width": 800, "height": 600 }
     */
    private fun handleCastLaunch(session: NanoHTTPD.IHTTPSession, ctx: RouteContext): NanoHTTPD.Response {
        val params = ctx.readJsonBody(session)

        val packageName = params.get("package_name")?.asString
            ?: return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"缺少 package_name"}"""
            ))

        val x = params.get("x")?.asInt ?: 100
        val y = params.get("y")?.asInt ?: 100
        val width = params.get("width")?.asInt ?: 800
        val height = params.get("height")?.asInt ?: 600

        val castService = ScreenCastService.getInstance(context)
        val success = castService.launchAppOnExternalDisplay(packageName, x, y, width, height)

        val json = ctx.gson.toJson(mapOf(
            "code" to if (success) 0 else -1,
            "message" to if (success) "已在外接屏启动 $packageName" else "启动失败,请检查投屏状态"
        ))
        return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, MIME_JSON, json))
    }
}
