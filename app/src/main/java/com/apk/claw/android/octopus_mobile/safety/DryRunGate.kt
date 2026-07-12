@file:Suppress("PackageNaming")   // 沿用既有 octopus_mobile.safety 包(带下划线)

package com.apk.claw.android.octopus_mobile.safety

import com.apk.claw.android.tool.ToolResult

/**
 * 演示/只读模式(dry-run)门 —— 让用户**安全预览** Agent 会怎么做。
 *
 * 开启后:改动型工具(tap/input/发消息/装应用/跑代码…)全部跳过、返回「演示」结果,
 * 只读工具(截图/看屏/查节点等 [ToolKind.IDEMPOTENT])照常执行——于是 Agent 能完整
 * 看屏、规划、"走完流程",但不真正改动设备/账号。适合首次上手、给怀疑的用户吃定心丸。
 *
 * **fail-safe:只增拦截、绝不放行**——只可能把「本会执行」变「跳过」,不可能开出安全口子。
 * 与 [PermissionMode]/[UndoWindow] 正交:那些管"要不要拦/给反悔窗",这里管"整台设备只读预览"。
 */
object DryRunGate {

    /** 演示模式开启 且 该工具是改动型(非只读)→ 应跳过执行。 */
    fun shouldSkip(toolName: String, enabled: Boolean): Boolean =
        enabled && ToolCallGuardrailController.classifyTool(toolName) != ToolKind.IDEMPOTENT

    /** 跳过时返回给 Agent 的结果:标记为成功(让 Agent 继续走完流程预览),但注明未真正执行。 */
    fun skipResult(toolName: String): ToolResult =
        ToolResult.success(
            "【演示模式】本应执行「$toolName」,当前为只读预览已跳过,未真正改动设备/账号。" +
                "如需真正执行,请到信任中心关闭「演示模式」。",
        )
}
