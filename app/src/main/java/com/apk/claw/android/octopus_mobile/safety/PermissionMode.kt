package com.apk.claw.android.octopus_mobile.safety

/**
 * 权限模式 —— 区分"日常主力机"与"闲置/群控机"两种用途。
 *
 * - [APPROVAL]：审批模式（默认）。日常主力机，安全优先。
 *   高危工具调用需弹窗人工确认；来源闸门、路径沙箱、宪法法官全部开启。
 *
 * - [FULL_POWER]：完全权限模式。闲置/群控机，释放最大能力。
 *   高危工具自动放行；来源闸门、路径沙箱、宪法法官全部旁路。
 *   但 PrivacyScanner / AuditLog / CircuitBreaker 三项不可关闭（防失控）。
 *
 * 底层存储复用 [com.apk.claw.android.utils.KVUtils.isAdvancedAutomationMode]，
 * 向后兼容现有的"满血模式"开关。
 */
enum class PermissionMode {
    APPROVAL,
    FULL_POWER,
}
