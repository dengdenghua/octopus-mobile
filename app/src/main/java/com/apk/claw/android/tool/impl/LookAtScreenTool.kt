package com.apk.claw.android.tool.impl

import android.graphics.Bitmap
import com.apk.claw.android.octopus_mobile.ControlTarget
import com.apk.claw.android.octopus_mobile.RemoteActions
import com.apk.claw.android.octopus_mobile.VisionAnalyzer
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * look_at_screen —— 视觉感知工具.
 *
 * 当无障碍树(get_screen_info)拿不到有用信息时（游戏画面、Canvas/自绘界面、图片/视频内容、
 * 需要核对视觉状态如颜色/图标/进度），调本工具：截当前屏 → 喂视觉模型 → 返回文字描述
 * 及元素的大致像素坐标，Agent 据此再用 tap/swipe 操作。
 *
 * 主 Agent 仍跑纯文本模型省钱；只有调本工具这一次走视觉模型（[VisionAnalyzer]，独立配置）。
 * 目标设备跟随 [ControlTarget]：本机用无障碍截图，远程用对端截图。
 */
class LookAtScreenTool : BaseTool() {

    override fun getName(): String = "look_at_screen"

    override fun getDisplayName(): String = "看屏幕"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "question",
            "string",
            "要让视觉模型回答的问题，例如「开始按钮在哪」「这张图里有什么」「当前在第几关」。留空则返回整屏可交互元素概览。",
            false,
        )
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        if (!VisionAnalyzer.isConfigured()) {
            return ToolResult.error("未配置视觉模型，请到 设置 → 模型配置 填写视觉模型")
        }

        val question = optionalString(
            params, "question",
            "请描述当前屏幕上有哪些可交互元素，以及它们的大致位置(坐标)。",
        )

        // 目标设备：本机走无障碍截图，远程走对端截图
        val remote = ControlTarget.remoteTarget()
        var bitmap: Bitmap? = null
        try {
            bitmap = if (remote != null) {
                RemoteActions.screenshot(remote)
            } else {
                ClawAccessibilityService.getInstance()?.takeScreenshot(5000)
            }
            if (bitmap == null) {
                return ToolResult.error(
                    if (remote != null) "远程设备截图失败" else "截图失败（无障碍服务未运行？）"
                )
            }

            val analysis = VisionAnalyzer.analyzeBlocking(bitmap, question)
            return ToolResult.success(analysis)
        } catch (e: Exception) {
            return ToolResult.error("视觉分析失败: ${e.message}")
        } finally {
            bitmap?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    override fun getDescriptionEN(): String =
        "Look at the current screen with a vision model. Use when the accessibility tree " +
        "(get_screen_info) is empty or insufficient — games, canvas/custom-drawn UIs, image/video " +
        "content, or to verify visual state (colors, icons, progress). Returns a textual description " +
        "and approximate pixel coordinates (in real screen resolution) you can then tap/swipe."

    override fun getDescriptionCN(): String =
        "用视觉模型看当前屏幕。当无障碍信息(get_screen_info)为空或不够用时使用 —— 游戏、" +
        "Canvas/自绘界面、图片/视频内容，或需要核对视觉状态(颜色/图标/进度)。返回文字描述和" +
        "元素的大致像素坐标(真实屏幕分辨率)，可据此再 tap/swipe。"
}
