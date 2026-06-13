package com.apk.claw.android.ui.compose.screen

import android.content.Context
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.octopus_mobile.ActionCache
import com.apk.claw.android.octopus_mobile.ControlTarget
import com.apk.claw.android.octopus_mobile.RoutineStore
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.utils.XLog

/**
 * 例程重放执行器 —— 手动「运行」与定时触发共用同一套逻辑：
 * 恢复目标设备 → 配置/可用性守卫 → 先试「快路径」确定性重放（[FastReplay]），对不上再回退给
 * 完整 Agent（[ChatAgentBridge.run]，语义重放）。本机目标的 Agent 运行会顺带录制动作，供下次快路径。
 *
 * 返回一条面向用户的状态文案（toast / 通知都能用）；实际执行在后台线程，本函数立即返回。
 */
object RoutineRunner {

    /** 是否具备执行条件（已配置模型）。无障碍由 Agent 自身预检兜底。 */
    fun canRun(): Boolean = ChatAgentBridge.isConfigured()

    fun run(ctx: Context, r: RoutineStore.Routine): String {
        // 1. 恢复目标设备
        var note = ""
        if (r.targetId.isBlank() || r.targetId == "local") {
            ControlTarget.setLocal()
        } else {
            val dev = ClawApplication.instance.deviceRegistry.getDevice(r.targetId)
            if (dev != null && dev.online) {
                ControlTarget.setRemote(dev)
            } else {
                ControlTarget.setLocal()
                note = "（目标「${r.targetLabel}」不在线，改用本机）"
            }
        }

        // 2. 配置守卫
        if (!ChatAgentBridge.isConfigured()) {
            return "未配置模型，请到 设置 → 模型配置"
        }

        // 3. 执行
        RoutineStore.touch(r.id)
        val isRemote = ControlTarget.isRemote()
        val hasFastPath = !isRemote && ActionCache.has(r.id, r.prompt)

        // FastReplay 含 sleep，且 ChatAgentBridge.run 可能从广播接收器（主线程）调用 —— 一律下到后台线程
        Thread {
            if (hasFastPath && ClawAccessibilityService.isRunning()) {
                val outcome = runCatching { FastReplay.tryReplay(r.id, r.prompt) }.getOrNull()
                if (outcome == FastReplay.Outcome.SUCCESS) {
                    XLog.i("RoutineRunner", "fast-path success for ${r.id}")
                    return@Thread
                }
                XLog.i("RoutineRunner", "fast-path $outcome → fall back to agent for ${r.id}")
            }
            // 回退 / 首次：完整 Agent（本机目标顺带录制，供下次快路径）
            ChatAgentBridge.run(
                prompt = r.prompt,
                onTool = { _, _, _, _ -> },
                onText = { },
                onDone = { },
                onError = { },
                recordKey = if (isRemote) null else r.id,
            )
        }.start()

        val via = if (hasFastPath) "⚡ 快路径" else "开始运行"
        return "$via：${r.name}$note"
    }
}
