package com.apk.claw.android.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 轻量「阶段进度」总线。
 *
 * 让耗时的工具(如 generate_app)把当前阶段冒泡给对话 UI 的「思考中」气泡 —— 用户能看见
 * 「规划中 → 写代码 → 自检修复 → 视觉验收 → 保存」这些过程,而不是干等十几秒只出一个结果。
 *
 * 单值、线程安全:工具在后台线程 [set],UI 主线程 `collectAsState()` 观察;每轮结束 `set(null)` 清掉
 * (由 ChatScreen 在工具完成/回合结束时清)。这是「实时预览」落地前的轻量可见性方案。
 */
object AgentProgressBus {
    private val _stage = MutableStateFlow<String?>(null)
    val stage: StateFlow<String?> = _stage

    fun set(text: String?) {
        _stage.value = text?.takeIf { it.isNotBlank() }
    }
}
