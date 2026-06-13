package com.apk.claw.android.ui.compose.screen

import android.content.Context
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.octopus_mobile.ControlTarget
import com.apk.claw.android.octopus_mobile.RoutineStore

/**
 * 例程重放执行器 —— 手动「运行」与定时触发共用同一套逻辑：
 * 恢复目标设备 → 配置/可用性守卫 → 调起 Agent（[ChatAgentBridge.run]，语义重放）。
 *
 * 返回一条面向用户的状态文案（toast / 通知都能用）。
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

        // 3. 重放（进度走悬浮控制层，结果落活动审计）
        RoutineStore.touch(r.id)
        ChatAgentBridge.run(
            prompt = r.prompt,
            onTool = { _, _, _, _ -> },
            onText = { },
            onDone = { },
            onError = { },
        )
        return "开始运行：${r.name}$note"
    }
}
