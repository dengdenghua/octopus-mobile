package com.apk.claw.android.server.routes

import com.apk.claw.android.channel.ChannelManager
import com.apk.claw.android.server.ConfigServerManager
import com.apk.claw.android.utils.KVUtils
import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD

private const val MIME_JSON = "application/json"

/**
 * 通道配置 & LLM 配置 & 鉴权检查路由
 */
class ChannelRouteHandler : RouteHandler {

    override fun canHandle(uri: String, method: NanoHTTPD.Method): Boolean {
        return when (uri) {
            "/api/channels" -> method == NanoHTTPD.Method.GET || method == NanoHTTPD.Method.POST
            "/api/llm" -> method == NanoHTTPD.Method.GET || method == NanoHTTPD.Method.POST
            "/api/auth/check" -> method == NanoHTTPD.Method.GET
            else -> false
        }
    }

    override fun handle(session: NanoHTTPD.IHTTPSession, ctx: RouteContext): NanoHTTPD.Response {
        return when {
            session.uri == "/api/auth/check" && session.method == NanoHTTPD.Method.GET -> handleAuthCheck(ctx)
            session.uri == "/api/channels" && session.method == NanoHTTPD.Method.GET -> handleGetChannels(ctx)
            session.uri == "/api/channels" && session.method == NanoHTTPD.Method.POST -> handlePostChannels(session, ctx)
            session.uri == "/api/llm" && session.method == NanoHTTPD.Method.GET -> handleGetLlm(ctx)
            session.uri == "/api/llm" && session.method == NanoHTTPD.Method.POST -> handlePostLlm(session, ctx)
            else -> ctx.corsResponse(
                NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.NOT_FOUND, MIME_JSON,
                    """{"code":-1,"message":"接口不存在"}"""
                )
            )
        }
    }

    // ==================== 鉴权检查 ====================

    /** H5 页面调用此接口确认当前 token 是否有效 */
    private fun handleAuthCheck(ctx: RouteContext): NanoHTTPD.Response {
        val result = JsonObject().apply {
            addProperty("code", 0)
            addProperty("message", "ok")
        }
        return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, MIME_JSON, result.toString()))
    }

    // ==================== 通道配置 ====================

    private fun handleGetChannels(ctx: RouteContext): NanoHTTPD.Response {
        val data = JsonObject().apply {
            addProperty("dingtalkAppKey", KVUtils.getDingtalkAppKey())
            addProperty("dingtalkAppSecret", ctx.maskSecret(KVUtils.getDingtalkAppSecret()))
            addProperty("feishuAppId", KVUtils.getFeishuAppId())
            addProperty("feishuAppSecret", ctx.maskSecret(KVUtils.getFeishuAppSecret()))
            addProperty("qqAppId", KVUtils.getQqAppId())
            addProperty("qqAppSecret", ctx.maskSecret(KVUtils.getQqAppSecret()))
            addProperty("discordBotToken", ctx.maskSecret(KVUtils.getDiscordBotToken()))
            addProperty("telegramBotToken", ctx.maskSecret(KVUtils.getTelegramBotToken()))
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
            addProperty("message", "ok")
        }
        return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, MIME_JSON, result.toString()))
    }

    private fun handlePostChannels(session: NanoHTTPD.IHTTPSession, ctx: RouteContext): NanoHTTPD.Response {
        val json = ctx.readJsonBody(session)

        var reinitDingtalk = false
        var reinitFeishu = false
        var reinitQQ = false
        var reinitDiscord = false
        var reinitTelegram = false

        // 钉钉配置
        if (json.has("dingtalkAppKey")) {
            val value = json.get("dingtalkAppKey").asString
            KVUtils.setDingtalkAppKey(value)
            reinitDingtalk = true
        }
        if (json.has("dingtalkAppSecret")) {
            val value = json.get("dingtalkAppSecret").asString
            if (!ctx.isMaskedValue(value)) {
                KVUtils.setDingtalkAppSecret(value)
                reinitDingtalk = true
            }
        }

        // 飞书配置
        if (json.has("feishuAppId")) {
            val value = json.get("feishuAppId").asString
            KVUtils.setFeishuAppId(value)
            reinitFeishu = true
        }
        if (json.has("feishuAppSecret")) {
            val value = json.get("feishuAppSecret").asString
            if (!ctx.isMaskedValue(value)) {
                KVUtils.setFeishuAppSecret(value)
                reinitFeishu = true
            }
        }

        // QQ 配置
        if (json.has("qqAppId")) {
            val value = json.get("qqAppId").asString
            KVUtils.setQqAppId(value)
            reinitQQ = true
        }
        if (json.has("qqAppSecret")) {
            val value = json.get("qqAppSecret").asString
            if (!ctx.isMaskedValue(value)) {
                KVUtils.setQqAppSecret(value)
                reinitQQ = true
            }
        }

        // Discord 配置
        if (json.has("discordBotToken")) {
            val value = json.get("discordBotToken").asString
            if (!ctx.isMaskedValue(value)) {
                KVUtils.setDiscordBotToken(value)
                reinitDiscord = true
            }
        }

        // Telegram 配置
        if (json.has("telegramBotToken")) {
            val value = json.get("telegramBotToken").asString
            if (!ctx.isMaskedValue(value)) {
                KVUtils.setTelegramBotToken(value)
                reinitTelegram = true
            }
        }

        // 重新初始化对应通道
        if (reinitDingtalk) ChannelManager.reinitDingTalkFromStorage()
        if (reinitFeishu) ChannelManager.reinitFeiShuFromStorage()
        if (reinitQQ) ChannelManager.reinitQQFromStorage()
        if (reinitDiscord) ChannelManager.reinitDiscordFromStorage()
        if (reinitTelegram) ChannelManager.reinitTelegramFromStorage()

        // 通知 Settings 页面刷新绑定状态
        if (reinitDingtalk || reinitFeishu || reinitQQ || reinitDiscord || reinitTelegram) {
            ConfigServerManager.notifyConfigChanged()
        }

        val result = JsonObject().apply {
            addProperty("code", 0)
            addProperty("message", "ok")
        }
        return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, MIME_JSON, result.toString()))
    }

    // ==================== LLM 配置 ====================

    private fun handleGetLlm(ctx: RouteContext): NanoHTTPD.Response {
        val apiKey = KVUtils.getLlmApiKey()
        val data = JsonObject().apply {
            addProperty("llmApiKey", ctx.maskSecret(apiKey))
            addProperty("llmBaseUrl", KVUtils.getLlmBaseUrl())
            addProperty("llmModelName", KVUtils.getLlmModelName())
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
            addProperty("message", "ok")
        }
        return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, MIME_JSON, result.toString()))
    }

    private fun handlePostLlm(session: NanoHTTPD.IHTTPSession, ctx: RouteContext): NanoHTTPD.Response {
        val json = ctx.readJsonBody(session)

        if (json.has("llmApiKey")) {
            val value = json.get("llmApiKey").asString
            if (!ctx.isMaskedValue(value)) {
                KVUtils.setLlmApiKey(value)
            }
        }
        if (json.has("llmBaseUrl")) {
            KVUtils.setLlmBaseUrl(json.get("llmBaseUrl").asString)
        }
        if (json.has("llmModelName")) {
            val value = json.get("llmModelName").asString.trim()
            KVUtils.setLlmModelName(if (value.isEmpty()) "" else value)
        }

        ConfigServerManager.notifyConfigChanged()

        val result = JsonObject().apply {
            addProperty("code", 0)
            addProperty("message", "ok")
        }
        return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, MIME_JSON, result.toString()))
    }
}
