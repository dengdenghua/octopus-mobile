package com.apk.claw.android.tool.impl

import com.apk.claw.android.ClawApplication
import com.apk.claw.android.service.ClawVpnService
import com.apk.claw.android.service.VpnPermissionActivity
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * start_vpn —— 启动 SOCKS5 代理隧道,把设备流量转发到用户指定的代理服务器。
 *
 * **官方只提供能力,不提供代理服务器。** 社区用户用 generate_app 生成小程序,
 * 在 UI 里填入自己的 SOCKS5 代理信息,调 `octopus.callTool("start_vpn", {...})` 即可。
 *
 * 安全:
 *  - HIGH 风险工具(劫持全局网络流量),不可信来源走审批闸门
 *  - 非幂等(启动有副作用),不自动重试
 *  - 需用户在系统弹窗中授权 VPN(系统级行为,无法绕过)
 *  - 代理服务器信息由用户/插件提供,不经手官方
 */
class StartVpnTool : BaseTool() {

    override fun getName() = "start_vpn"
    override fun getDisplayName() = if (useChineseDescription) "启动代理" else "Start VPN"

    override fun getParameters() = listOf(
        ToolParameter(
            "host", "string",
            "SOCKS5 proxy server hostname or IP (e.g. 1.2.3.4 or proxy.example.com).", true
        ),
        ToolParameter(
            "port", "integer",
            "SOCKS5 proxy server port (1-65535, e.g. 1080).", true
        ),
        ToolParameter(
            "username", "string",
            "Optional SOCKS5 username for authentication.", false
        ),
        ToolParameter(
            "password", "string",
            "Optional SOCKS5 password for authentication.", false
        ),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        if (ClawVpnService.currentConfig != null) {
            return ToolResult.error("VPN 已在运行中,请先 stop_vpn 再启动")
        }
        return runCatching {
            val host = requireString(params, "host").trim()
            val port = requireInt(params, "port")
            if (host.isBlank()) return@runCatching ToolResult.error("host 不能为空")
            if (port !in PORT_MIN..PORT_MAX) return@runCatching ToolResult.error("port 必须在 1-65535 之间")

            val username = optionalString(params, "username", "").trim().ifBlank { null }
            val password = optionalString(params, "password", "").trim().ifBlank { null }

            val ctx = ClawApplication.instance
            val config = ClawVpnService.VpnConfig(host, port, username, password)

            val ok = VpnPermissionActivity.requestPermissionAndStart(ctx, config)
            if (ok) {
                ToolResult.success("VPN 已启动:流量将经 $host:$port 转发(SOCKS5)")
            } else {
                ToolResult.error("VPN 启动失败:用户未授权或代理服务器不可达")
            }
        }.getOrElse { ToolResult.error("参数错误: ${it.message}") }
    }

    companion object {
        private const val PORT_MIN = 1
        private const val PORT_MAX = 65535
    }

    override fun isIdempotent() = false

    override fun getDescriptionEN() = """
        Start a SOCKS5 proxy VPN tunnel, routing device traffic through the specified proxy server.
        The user must authorize VPN permission via the system dialog first.
        Use when the user wants to enable a proxy/VPN connection for their device.
    """.trimIndent()

    override fun getDescriptionCN() = """
        启动 SOCKS5 代理 VPN 隧道,将设备流量经指定代理服务器转发。
        需用户先在系统弹窗中授权 VPN 权限。
        适用:用户想为设备开启代理/VPN 连接时。
    """.trimIndent()
}

/**
 * stop_vpn —— 停止 VPN 隧道。
 * 非幂等(有副作用),不自动重试。
 */
class StopVpnTool : BaseTool() {

    override fun getName() = "stop_vpn"
    override fun getDisplayName() = if (useChineseDescription) "停止代理" else "Stop VPN"

    override fun getParameters() = emptyList<ToolParameter>()

    override fun execute(params: Map<String, Any>): ToolResult {
        if (ClawVpnService.currentConfig == null) {
            return ToolResult.error("VPN 未在运行")
        }
        ClawVpnService.stop(ClawApplication.instance)
        return ToolResult.success("VPN 已停止")
    }

    override fun isIdempotent() = false

    override fun getDescriptionEN() = """
        Stop the active SOCKS5 VPN tunnel and restore normal network routing.
    """.trimIndent()

    override fun getDescriptionCN() = "停止运行中的 SOCKS5 VPN 隧道,恢复正常网络。"
}

/**
 * vpn_status —— 查询当前 VPN 连接状态。
 * 幂等,可安全重试。
 */
class VpnStatusTool : BaseTool() {

    override fun getName() = "vpn_status"
    override fun getDisplayName() = if (useChineseDescription) "代理状态" else "VPN Status"

    override fun getParameters() = emptyList<ToolParameter>()

    override fun execute(params: Map<String, Any>): ToolResult {
        val config = ClawVpnService.currentConfig
        return if (config != null) {
            val auth = if (config.username != null) "已认证" else "无认证"
            ToolResult.success("VPN 运行中 → ${config.host}:${config.port} (SOCKS5, $auth)")
        } else {
            ToolResult.success("VPN 未运行")
        }
    }

    override fun getDescriptionEN() = "Check the current SOCKS5 VPN tunnel status (running/stopped)."

    override fun getDescriptionCN() = "查询当前 SOCKS5 VPN 隧道状态(运行中/已停止)。"
}
