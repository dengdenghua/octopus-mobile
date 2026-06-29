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
}
