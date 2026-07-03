@file:Suppress("PackageNaming")
package com.apk.claw.android.octopus_mobile

/**
 * 启动模式 —— Octopus Mobile 的集成入口模式枚举.
 *
 * 当前仅 LOCAL_ONLY 在生产中使用。RPC_ONLY 和 DUAL 为预留枚举，
 * 等待 Runtime 远程大脑实装后启用。
 */
enum class StartupMode {
    /** 纯本地模式：跟现有 Octopus Mobile 一样，跑 DefaultAgentService */
    LOCAL_ONLY,

    /** 纯远程模式：手机只做执行器，LLM 全在 Runtime */
    RPC_ONLY,

    /** 双模式（默认）：远程优先，断网降级到本地 */
    DUAL
}
