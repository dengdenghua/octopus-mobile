package com.apk.claw.android.octopus_mobile.safety

/**
 * 权限策略 —— 把散落在 KVUtils 的多个安全开关收敛为一个统一的策略对象。
 *
 * 由 [PermissionMode] 驱动，两种预设：
 * - [APPROVAL]：审批模式策略（日常主力机）
 * - [FULL_POWER]：完全权限策略（闲置/群控机）
 *
 * 不可关闭项（两种模式都 ON，防止设备失控）：
 * - [privacyScannerEnabled]：PII/Secret 扫描
 * - [auditLogEnabled]：审计日志
 * - [circuitBreakerEnabled]：断路器（防死循环烧钱）
 * - [pathSandboxEnabled]：/sdcard 路径沙箱（防越界访问系统/私有目录）
 */
data class PermissionPolicy(
    val mode: PermissionMode,

    // —— SafetyGate 宪法法官 ——
    val safetyGateEnabled: Boolean,

    // —— 工具风险策略 ——
    val highRiskAction: RiskAction,
    val mediumRiskAction: RiskAction,

    // —— 来源闸门 ——
    val trustAllSources: Boolean,

    // —— 护栏阈值 ——
    val maxConsecutiveFailures: Int,
    val maxNoProgressSteps: Int,

    // —— 路径沙箱（不可关闭，两种模式均 true）——
    val pathSandboxEnabled: Boolean = true,

    // —— 不可关闭项 ——
    val privacyScannerEnabled: Boolean = true,
    val auditLogEnabled: Boolean = true,
    val circuitBreakerEnabled: Boolean = true,
) {
    /** 高危工具的风险处置动作 */
    enum class RiskAction { ALLOW, CONFIRM, BLOCK }

    companion object {
        /** 审批模式策略：日常主力机，安全优先 */
        val APPROVAL = PermissionPolicy(
            mode = PermissionMode.APPROVAL,
            safetyGateEnabled = true,
            highRiskAction = RiskAction.CONFIRM,
            mediumRiskAction = RiskAction.ALLOW,
            trustAllSources = false,
            maxConsecutiveFailures = 3,
            maxNoProgressSteps = 5,
            pathSandboxEnabled = true,
        )

        /** 完全权限模式策略：闲置/群控机，释放最大能力（路径沙箱仍不可关闭） */
        val FULL_POWER = PermissionPolicy(
            mode = PermissionMode.FULL_POWER,
            safetyGateEnabled = false,
            highRiskAction = RiskAction.ALLOW,
            mediumRiskAction = RiskAction.ALLOW,
            trustAllSources = true,
            maxConsecutiveFailures = 10,
            maxNoProgressSteps = 20,
        )

        /** 根据模式获取预设策略 */
        fun forMode(mode: PermissionMode): PermissionPolicy = when (mode) {
            PermissionMode.APPROVAL -> APPROVAL
            PermissionMode.FULL_POWER -> FULL_POWER
        }
    }
}
