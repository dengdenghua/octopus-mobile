package com.apk.claw.android.octopus_mobile

/**
 * 启动模式决策 —— Octopus Mobile 的 Octopus Mobile 集成入口.
 *
 * Phase 0 占位 —— Phase 1 实装 LOCAL_ONLY / RPC_ONLY / DUAL 三种模式.
 *
 * 设计原则（docs/mobile/architecture.md 第 3.2 节）：
 *  - LOCAL_ONLY：跟现在 Octopus Mobile 完全一致
 *  - RPC_ONLY：纯远程，Runtime 不可用时彻底失能
 *  - DUAL（默认）：远程优先，断网透明降级到本地
 */
enum class StartupMode {
    /** 纯本地模式：跟现有 Octopus Mobile 一样，跑 DefaultAgentService */
    LOCAL_ONLY,

    /** 纯远程模式：手机只做执行器，LLM 全在 Runtime */
    RPC_ONLY,

    /** 双模式（默认）：远程优先，断网降级到本地 */
    DUAL
}

object StartupModeResolver {

    /**
     * 根据 KV 配置和 Runtime 连通性，决定当前应使用的启动模式.
     *
     * 决策流程：
     *  1. 读 KV 中的 startup_mode 配置（用户偏好）
     *  2. 探测 Runtime 连通性（HTTP HEAD）
     *  3. 综合决策：
     *     - 配置 LOCAL_ONLY  → LOCAL_ONLY（不连）
     *     - 配置 RPC_ONLY    → RPC_ONLY（不通则拒绝启动）
     *     - 配置 DUAL 或未设 → DUAL（不通则降级 LOCAL）
     */
    fun resolve(
        configuredMode: String?,
        isRuntimeReachable: Boolean
    ): StartupMode {
        val mode = configuredMode?.let { runCatching { StartupMode.valueOf(it) }.getOrNull() }
            ?: StartupMode.DUAL

        return when (mode) {
            StartupMode.LOCAL_ONLY -> StartupMode.LOCAL_ONLY
            StartupMode.RPC_ONLY -> {
                require(isRuntimeReachable) {
                    "RPC_ONLY mode requires Runtime; not reachable"
                }
                StartupMode.RPC_ONLY
            }
            StartupMode.DUAL -> {
                if (isRuntimeReachable) StartupMode.DUAL else StartupMode.LOCAL_ONLY
            }
        }
    }
}
