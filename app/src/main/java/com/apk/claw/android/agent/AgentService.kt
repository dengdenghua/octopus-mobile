package com.apk.claw.android.agent

interface AgentService {
    fun initialize(config: AgentConfig)
    fun updateConfig(config: AgentConfig)
    /** @param untrusted 本次运行来自不可信来源（LAN 网页控制台 / 聊天渠道）时为 true，工具调用走来源闸门。 */
    fun executeTask(userPrompt: String, callback: AgentCallback, untrusted: Boolean = false)
    /** 从检查点恢复未完成的任务（App 崩溃/被杀后重启）。无检查点时返回 false。 */
    fun resumeTask(callback: AgentCallback): Boolean
    fun cancel()
    fun shutdown()
    fun isRunning(): Boolean
    /**
     * 设置本次运行的会话级工作空间(类似 Codex --cd 选定项目目录)。
     * 非空时覆盖全局脚本工作空间,run_code/run_python 的 WORKSPACE 全局变量切到此处。
     * 在 [executeTask] 之前调用;为 null/空时回退全局默认。
     */
    fun setWorkspace(workspace: String?)
}
