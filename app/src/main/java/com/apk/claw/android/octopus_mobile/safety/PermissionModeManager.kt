package com.apk.claw.android.octopus_mobile.safety

import com.apk.claw.android.octopus_mobile.ToolAuditLog
import com.apk.claw.android.utils.KVUtils

/**
 * 权限模式管理器 —— 统一读取/切换权限模式，并通知各组件刷新策略。
 *
 * 底层存储复用 [KVUtils.isAdvancedAutomationMode]（向后兼容"满血模式"开关）。
 *
 * 用法：
 * ```kotlin
 * val policy = PermissionModeManager.getCurrentPolicy()
 * if (policy.highRiskAction == PermissionPolicy.RiskAction.CONFIRM) { /* 弹窗审批 */ }
 *
 * PermissionModeManager.switchMode(PermissionMode.FULL_POWER, "群控批量任务")
 * ```
 */
object PermissionModeManager {

    @Volatile
    private var currentPolicy: PermissionPolicy = loadPolicy()

    /** 当前生效的权限策略 */
    fun getCurrentPolicy(): PermissionPolicy = currentPolicy

    /** 当前权限模式 */
    fun getCurrentMode(): PermissionMode = currentPolicy.mode

    /** 是否处于完全权限模式 */
    fun isFullPowerMode(): Boolean = currentPolicy.mode == PermissionMode.FULL_POWER

    /** 是否处于审批模式 */
    fun isApprovalMode(): Boolean = currentPolicy.mode == PermissionMode.APPROVAL

    /**
     * 切换权限模式。
     *
     * @param mode 目标模式
     * @param reason 切换原因（记录到审计日志，便于事后追溯）
     */
    @Synchronized
    fun switchMode(mode: PermissionMode, reason: String) {
        if (currentPolicy.mode == mode) return

        // 1. 记录模式切换审计（复用 ToolAuditLog，blockedBy 字段标记为 mode_switch）
        runCatching {
            ToolAuditLog.record(
                ToolAuditLog.Entry(
                    id = "mode_switch_${System.currentTimeMillis()}",
                    ts = System.currentTimeMillis(),
                    toolName = "_permission_mode_",
                    risk = "high",
                    params = "from=${currentPolicy.mode} to=$mode reason=$reason",
                    success = true,
                    result = "mode_switched",
                    blockedBy = null,
                    durationMs = 0,
                )
            )
        }

        // 2. 更新底层存储
        KVUtils.setAdvancedAutomationMode(mode == PermissionMode.FULL_POWER)

        // 3. 刷新内存策略
        currentPolicy = PermissionPolicy.forMode(mode)

        // 4. 通知 ToolRegistry 刷新（延迟到下次工具调用时读取，无需显式通知）
        // ToolRegistry.executeTool() 每次都调用 getCurrentPolicy()，天然支持热切换
    }

    /**
     * 从存储重新加载策略（外部修改 KVUtils 后调用，如 Runtime 远程下发配置）。
     */
    @Synchronized
    fun reload() {
        currentPolicy = loadPolicy()
    }

    /** 紧急切回审批模式（Runtime 远程下发紧急停止命令时调用） */
    fun emergencyRevoke(reason: String = "remote_emergency_stop") {
        switchMode(PermissionMode.APPROVAL, reason)
    }

    private fun loadPolicy(): PermissionPolicy {
        return if (KVUtils.isAdvancedAutomationMode()) {
            PermissionPolicy.FULL_POWER
        } else {
            PermissionPolicy.APPROVAL
        }
    }
}
