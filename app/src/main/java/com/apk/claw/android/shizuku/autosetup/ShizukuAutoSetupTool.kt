package com.apk.claw.android.shizuku.autosetup

import com.apk.claw.android.ClawApplication
import com.apk.claw.android.shizuku.ShizukuManager
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import kotlinx.coroutines.runBlocking

/**
 * 「全自动配置 Shizuku」工具 —— 视觉 Agent 的「手」。
 *
 * 分工:UI 导航(开无线调试、点「使用配对码配对设备」、读屏上的 6 位码与 IP:端口、点授权)由 Agent
 * 用自己的 **look_at_screen / 截屏 / 点击** 工具搞定(能自适应各家 ROM);本工具只负责那步机器活 ——
 * 用 libadb(纯 JVM,无需电脑)与本机 adbd 完成 **ADB 配对 + 拉起 Shizuku**。配一次身份落盘,
 * 之后 `action=start` 免配对(见 [OctopusAdbManager])。仅 Android 11+ 可单机配对。
 *
 * 具体剧本见内置技能「自动配置 Shizuku」([ShizukuAutoSetupSkill])。
 */
class ShizukuAutoSetupTool : BaseTool() {

    override fun getName() = "shizuku_auto_setup"

    override fun getDisplayName() = if (useChineseDescription) "自动配置Shizuku" else "Auto-setup Shizuku"

    override fun getDescriptionCN() =
        "自动配置 Shizuku 的执行工具。你先用 look_at_screen 从系统「无线调试·使用配对码配对设备」弹窗读出 " +
            "IP、端口、6 位配对码,再调本工具:action=pair 完成配对;action=start 连接并拉起 Shizuku;" +
            "action=status 查是否就绪。仅 Android 11+ 可单机配对。"

    override fun getDescriptionEN() =
        "Executor for auto-configuring Shizuku. First read the IP, port and 6-digit code from the system " +
            "'Wireless debugging · Pair device with pairing code' dialog via look_at_screen, then call: " +
            "action=pair to pair; action=start to connect and launch Shizuku; action=status to check readiness. " +
            "On-device pairing needs Android 11+."

    override fun getParameters() = listOf(
        ToolParameter(
            "action", "string",
            "'pair' (pair with the 6-digit code), 'start' (connect and launch Shizuku), or 'status'.",
            true,
        ),
        ToolParameter(
            "host", "string",
            "For 'pair': IP from the pairing sub-dialog. For 'start': optional IP from the wireless-debugging " +
                "main screen (omit to auto-discover via mDNS). e.g. 192.168.1.7",
            false,
        ),
        ToolParameter(
            "port", "number",
            "For 'pair': the pairing sub-dialog port. For 'start': optional connect port from the main screen.",
            false,
        ),
        ToolParameter("code", "string", "The 6-digit Wi‑Fi pairing code (required for 'pair').", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val ctx = ClawApplication.instance
        return when (val action = requireString(params, "action").trim().lowercase()) {
            "pair" -> {
                val host = requireString(params, "host").trim()
                val port = requireInt(params, "port")
                val code = requireString(params, "code").trim().filter { it.isDigit() }
                if (code.length != PAIRING_CODE_LEN) {
                    return ToolResult.error("配对码应为 6 位数字,收到「$code」;重新用 look_at_screen 看清弹窗里的配对码")
                }
                runBlocking { ShizukuAdbStarter.pair(ctx, host, port, code) }.fold(
                    onSuccess = { ToolResult.success("已与 $host:$port 配对成功。下一步调 action=start 拉起 Shizuku。") },
                    onFailure = {
                        ToolResult.error(
                            "配对失败:${it.message}。核对:①无线调试已开 ②用的是「配对」子弹窗里的端口和码(不是主页那个端口) " +
                                "③配对码是否已过期(重开弹窗取新码)。",
                        )
                    },
                )
            }

            "start" -> {
                val host = optionalString(params, "host", "").trim()
                val port = optionalInt(params, "port", 0)
                val result = runBlocking {
                    if (host.isNotEmpty() && port > 0) {
                        ShizukuAdbStarter.connectAndStartShizuku(ctx, host, port)
                    } else {
                        ShizukuAdbStarter.autoConnectAndStartShizuku(ctx)
                    }
                }
                runBlocking { ShizukuAdbStarter.disconnect(ctx) }
                result.fold(
                    onSuccess = { out ->
                        ToolResult.success(
                            "已连接并执行 Shizuku 启动脚本,输出:${out.ifBlank { "(无输出,通常即成功)" }}。" +
                                "过 1-2 秒调 action=status 确认;若 Shizuku 弹授权框,请点「允许」。",
                        )
                    },
                    onFailure = {
                        ToolResult.error(
                            "连接/启动失败:${it.message}。若 mDNS 自动发现失败,请从无线调试主页读出 IP:端口后带 host/port 重试 start。",
                        )
                    },
                )
            }

            "status" -> {
                val ready = runCatching { ShizukuManager.isAvailable() }.getOrDefault(false)
                ToolResult.success(
                    if (ready) {
                        "Shizuku 已就绪,高级权限可用。"
                    } else {
                        "Shizuku 尚未就绪:可能刚启动(等 1-2 秒重查)、或已运行但还没授权 octopus(去 Shizuku 里授权)。"
                    },
                )
            }

            else -> ToolResult.error("未知 action「$action」。可用:pair / start / status。")
        }
    }

    companion object {
        private const val PAIRING_CODE_LEN = 6
    }
}
