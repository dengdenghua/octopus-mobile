package com.apk.claw.android

import com.apk.claw.android.agent.AgentCallback
import com.apk.claw.android.agent.AgentConfig
import com.apk.claw.android.agent.AgentService
import com.apk.claw.android.agent.AgentServiceFactory
import com.apk.claw.android.agent.TaskQueue
import com.apk.claw.android.channel.Channel
import com.apk.claw.android.channel.ChannelManager
import com.apk.claw.android.floating.FloatingCircleManager
import com.apk.claw.android.octopus_mobile.BrainModeSelector
import com.apk.claw.android.octopus_mobile.evolution.EvolutionEngine
import com.apk.claw.android.octopus_mobile.evolution.LessonStore
import com.apk.claw.android.octopus_mobile.memory.MemoryStore
import com.apk.claw.android.octopus_mobile.nerves.reflex.ReflexRouter
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.XLog

/**
 * 任务编排器，负责 Agent 生命周期管理、任务队列调度、任务执行与回调处理。
 *
 * 使用 TaskQueue 替代单任务锁模型：
 *  - 高优先级任务可以抢占正在执行的低优先级任务
 *  - 后台任务（如下载文件）不阻塞前台任务
 *  - 被抢占的任务可以暂停并稍后恢复
 *  - 任务完成后自动执行队列中的下一个任务
 *
 * @param agentConfigProvider 延迟获取最新 AgentConfig 的回调
 * @param onTaskFinished 每次任务结束（成功/失败/取消）后的通知，用于刷新用户信息等
 */
class TaskOrchestrator(
    private val agentConfigProvider: () -> AgentConfig,
    private val onTaskFinished: () -> Unit
) {

    companion object {
        private const val TAG = "TaskOrchestrator"
    }

    private lateinit var agentService: AgentService

    /** 任务队列（替代 taskLock 单任务锁） */
    private val taskQueue = TaskQueue()

    /** 当前正在执行的任务 */
    @Volatile
    private var currentTask: TaskQueue.QueuedTask? = null

    /** 同步锁，保护 currentTask 和队列调度的原子性 */
    private val scheduleLock = Any()

    /** 反射路由器（关键词 → 直接执行工具，跳过 LLM） */
    private val reflexRouter = ReflexRouter().apply {
        addRules(ReflexRouter.defaultRules())
    }

    /** 方案 F 决策层切换器（远程/本地模式） */
    var brainSelector: BrainModeSelector? = null

    /** 自进化引擎（任务完成后触发反思） */
    var evolutionEngine: EvolutionEngine? = null

    /** 教训持久化存储（反思结果自动写入，并注入下次 System Prompt） */
    var lessonStore: LessonStore? = null

    /** 跨会话记忆存储（用户偏好/事实/上下文，自动注入 System Prompt） */
    var memoryStore: MemoryStore? = null

    /** 反思最小间隔（避免每次任务都调 LLM） */
    private var lastReflectTs: Long = 0
    private val REFLECT_INTERVAL_MS: Long = 5 * 60 * 1000  // 5分钟

    /** 任务完成计数器，每完成 N 个任务触发一次 B2 反思 */
    private var taskCompleteCount: Int = 0
    private val REFLECT_EVERY_N_TASKS: Int = 3

    // ==================== Agent 生命周期 ====================

    fun initAgent() {
        agentService = AgentServiceFactory.create()
        try {
            agentService.initialize(agentConfigProvider())
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to initialize AgentService", e)
        }
    }

    fun updateAgentConfig(): Boolean {
        return try {
            val config = agentConfigProvider()
            if (::agentService.isInitialized) {
                agentService.updateConfig(config)
                XLog.d(TAG, "Agent config updated: model=${config.modelName}, temp=${config.temperature}")
                true
            } else {
                XLog.w(TAG, "AgentService not initialized, initializing with new config")
                agentService = AgentServiceFactory.create()
                agentService.initialize(config)
                true
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to update agent config", e)
            false
        }
    }

    // ==================== 任务队列调度 ====================

    /**
     * 向任务队列添加一个新任务，并尝试调度执行。
     *
     * 如果当前无任务在执行，直接启动该任务；
     * 如果新任务优先级高于当前任务，抢占当前任务并启动新任务；
     * 否则排队等待。
     *
     * @return true 表示任务已入队（可能立即执行或排队）
     */
    fun tryAcquireTask(messageId: String, channel: Channel, priority: TaskQueue.TaskPriority = TaskQueue.TaskPriority.NORMAL, isBackground: Boolean = false): Boolean {
        val taskId = "${messageId}_${System.currentTimeMillis()}"
        val queuedTask = TaskQueue.QueuedTask(
            id = taskId,
            task = "",  // task 内容由 startNewTask 传入
            channel = channel,
            messageId = messageId,
            priority = priority,
            isBackground = isBackground,
        )
        return enqueueAndSchedule(queuedTask)
    }

    /**
     * 入队并调度：判断是否需要抢占当前任务，或直接启动。
     */
    private fun enqueueAndSchedule(queuedTask: TaskQueue.QueuedTask): Boolean {
        synchronized(scheduleLock) {
            val cur = currentTask
            // 判断是否需要抢占
            if (cur != null && taskQueue.shouldPreempt(queuedTask, cur)) {
                // 暂停当前任务，入队新任务并立即执行
                XLog.i(TAG, "Preempting current task: ${cur.id} with higher priority task: ${queuedTask.id}")
                pauseCurrentTask()
                if (!taskQueue.enqueue(queuedTask)) {
                    startNextTask()
                    return false
                }
                startNextTask()
                return true
            }
            // 无需抢占，入队
            if (!taskQueue.enqueue(queuedTask)) return false
            // 如果当前无任务在执行，立即启动
            if (cur == null) {
                startNextTask()
            }
            return true
        }
    }

    /**
     * 暂停当前正在执行的任务（被高优先级抢占时）。
     */
    private fun pauseCurrentTask() {
        val cur = currentTask ?: return
        // 取消旧 Agent 执行，并重建服务，避免新任务撞上 "Agent is already running"。
        if (::agentService.isInitialized) {
            agentService.shutdown()
        }
        agentService = AgentServiceFactory.create()
        try {
            agentService.initialize(agentConfigProvider())
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to reinitialize AgentService after preemption", e)
        }
        // 通知当前任务被暂停
        ChannelManager.sendMessage(cur.channel, ClawApplication.instance.getString(R.string.channel_msg_task_cancelled), cur.messageId)
        // 暂停当前任务：保持暂停态等待显式恢复，不立即 resume（否则 pause 形同虚设）
        taskQueue.pauseRunningTask(cur)
        currentTask = null
        FloatingCircleManager.setErrorState()
        XLog.i(TAG, "Current task paused: ${cur.id}")
    }

    /**
     * 从队列中取出下一个任务并执行。
     * 调用方需持有 scheduleLock。
     */
    private fun startNextTask() {
        var next = taskQueue.dequeue()
        if (next == null) {
            taskQueue.resumePausedTasksWhenIdle()
            next = taskQueue.dequeue()
        }
        if (next != null) {
            currentTask = next
            next.status = TaskQueue.TaskStatus.RUNNING
            XLog.i(TAG, "Starting next task: id=${next.id}, priority=${next.priority}, remainingQueue=${taskQueue.size()}")
        } else {
            currentTask = null
            XLog.d(TAG, "No more tasks in queue")
        }
    }

    /**
     * 释放当前任务，并自动调度队列中的下一个任务。
     * 返回释放前的 (channel, messageId) 供调用方使用。
     */
    private fun releaseTask(): Pair<Channel?, String> {
        synchronized(scheduleLock) {
            val cur = currentTask
            val ch = cur?.channel
            val id = cur?.messageId ?: ""
            currentTask = null
            // 自动调度下一个任务：先看主队列，空闲时恢复暂停任务
            var next = taskQueue.dequeue()
            if (next == null) {
                val resumed = taskQueue.resumePausedTasksWhenIdle()
                if (resumed > 0) {
                    XLog.i(TAG, "Queue idle, auto-resumed $resumed paused task(s)")
                    next = taskQueue.dequeue()
                }
            }
            if (next != null) {
                currentTask = next
                next.status = TaskQueue.TaskStatus.RUNNING
                XLog.i(TAG, "Auto-scheduling next task: id=${next.id}, priority=${next.priority}")
            }
            return ch to id
        }
    }

    fun isTaskRunning(): Boolean {
        synchronized(scheduleLock) {
            return currentTask != null
        }
    }

    /** 向后兼容：当前任务的 messageId */
    val inProgressTaskMessageId: String
        get() = synchronized(scheduleLock) { currentTask?.messageId ?: "" }

    /** 向后兼容：当前任务的 Channel */
    val inProgressTaskChannel: Channel?
        get() = synchronized(scheduleLock) { currentTask?.channel }

    /** 获取任务队列信息，供 UI 展示 */
    fun getTaskQueueInfo(): TaskQueueInfo {
        synchronized(scheduleLock) {
            return TaskQueueInfo(
                currentTask = currentTask,
                queuedTasks = taskQueue.getQueuedTasks(),
                pausedTasks = taskQueue.getPausedTasks(),
                queueSize = taskQueue.size(),
            )
        }
    }

    /** 任务队列状态信息 */
    data class TaskQueueInfo(
        val currentTask: TaskQueue.QueuedTask?,
        val queuedTasks: List<TaskQueue.QueuedTask>,
        val pausedTasks: List<TaskQueue.QueuedTask>,
        val queueSize: Int,
    )

    // ==================== 任务执行 ====================

    fun cancelCurrentTask() {
        synchronized(scheduleLock) {
            val cur = currentTask ?: return
            if (::agentService.isInitialized) {
                agentService.cancel()
            }
            cur.status = TaskQueue.TaskStatus.CANCELLED
            currentTask = null
            ChannelManager.sendMessage(cur.channel, ClawApplication.instance.getString(R.string.channel_msg_task_cancelled), cur.messageId)
            FloatingCircleManager.setErrorState()
            // 自动调度下一个任务
            var next = taskQueue.dequeue()
            if (next == null) {
                taskQueue.resumePausedTasksWhenIdle()
                next = taskQueue.dequeue()
            }
            if (next != null) {
                currentTask = next
                next.status = TaskQueue.TaskStatus.RUNNING
                XLog.i(TAG, "After cancel, auto-scheduling next task: id=${next.id}")
            }
        }
        onTaskFinished()
        XLog.d(TAG, "Current task cancelled by user")
    }

    fun startNewTask(channel: Channel, task: String, messageID: String) {
        startNewTask(channel, task, messageID, TaskQueue.TaskPriority.NORMAL, false)
    }

    /**
     * 启动新任务：入队 + 调度。
     * 支持指定优先级和是否为后台任务。
     */
    fun startNewTask(channel: Channel, task: String, messageID: String, priority: TaskQueue.TaskPriority, isBackground: Boolean) {
        // BrainModeSelector 路由感知日志
        brainSelector?.let {
            XLog.i(TAG, "Brain mode: ${it.currentMode()}, domain: ${it.currentDomain()}")
            // TODO: 当 currentMode() == EXECUTOR_ONLY 且母体可达时，
            //       可通过 WebSocket 将任务委托给远程 Runtime 而非本地 LLM
        }

        // ── ReflexRouter 快速通道：关键词命中直接执行，跳过 LLM ──
        val reflexMatch = reflexRouter.tryMatchText(task)
        if (reflexMatch != null) {
            val response = reflexMatch.response
            if (response is Map<*, *>) {
                val toolName = response["tool"] as? String
                if (toolName != null && toolName.isNotEmpty()) {
                    XLog.i(TAG, "ReflexRouter hit: ${reflexMatch.ruleId} → $toolName (${reflexMatch.latencyMs.toInt()}ms)")
                    try {
                        val params = response.entries
                            .filter { it.key != "tool" && it.value != null }
                            .associate { it.key.toString() to it.value as Any }
                        val toolResult = ToolRegistry.getInstance().executeTool(toolName, params)
                        val resultText = if (toolResult.isSuccess) {
                            "✓ " + (toolResult.data ?: "done")
                        } else {
                            "✗ " + (toolResult.error ?: "failed")
                        }
                        ChannelManager.sendMessage(channel, resultText, messageID)
                        ChannelManager.flushMessages(channel)
                        FloatingCircleManager.setSuccessState()
                        onTaskFinished()
                        return
                    } catch (e: Exception) {
                        XLog.w(TAG, "ReflexRouter tool execution failed, falling back to LLM: ${e.message}")
                    }
                }
            }
        }

        // ── 入队并调度 ──
        val taskId = "${messageID}_${System.currentTimeMillis()}"
        val queuedTask = TaskQueue.QueuedTask(
            id = taskId,
            task = task,
            channel = channel,
            messageId = messageID,
            priority = priority,
            isBackground = isBackground,
        )

        synchronized(scheduleLock) {
            val cur = currentTask
            // 判断是否需要抢占当前任务
            if (cur != null && taskQueue.shouldPreempt(queuedTask, cur)) {
                XLog.i(TAG, "Preempting current task: ${cur.id} with higher priority task: ${queuedTask.id}")
                pauseCurrentTask()
            }
            // 入队
            if (!taskQueue.enqueue(queuedTask)) {
                XLog.w(TAG, "Task queue is full, rejecting task: $taskId")
                ChannelManager.sendMessage(channel, "任务队列已满，请稍后重试", messageID)
                if (currentTask == null) {
                    startNextTask()
                }
                return
            }
            // 如果当前无任务在执行，立即调度
            if (cur == null || currentTask == null) {
                startNextTask()
            }
        }

        // ── 执行当前任务（在锁外执行以避免死锁） ──
        executeCurrentTask()
    }

    /**
     * 执行 currentTask 指向的任务。
     * 从 scheduleLock 外调用，确保不持锁执行耗时操作。
     */
    private fun executeCurrentTask() {
        val taskInfo: TaskQueue.QueuedTask?
        synchronized(scheduleLock) {
            taskInfo = currentTask
        }
        if (taskInfo == null) return

        val channel = taskInfo.channel
        val task = taskInfo.task
        val messageID = taskInfo.messageId
        val taskId = taskInfo.id

        fun isCurrentCallbackTask(): Boolean =
            synchronized(scheduleLock) { currentTask?.id == taskId }

        if (!::agentService.isInitialized) {
            XLog.e(TAG, "AgentService not initialized, attempting to initialize")
            try {
                agentService = AgentServiceFactory.create()
                agentService.initialize(agentConfigProvider())
            } catch (e: Exception) {
                XLog.e(TAG, "Failed to initialize AgentService", e)
                releaseTask()
                ChannelManager.sendMessage(channel, ClawApplication.instance.getString(R.string.channel_msg_service_not_ready), messageID)
                return
            }
        }

        ClawAccessibilityService.getInstance()?.pressHome()

        FloatingCircleManager.showTaskNotify(task, channel)

        // 每轮消息聚合缓冲：thinking + toolResult 攒成一条，减少发送次数
        val roundBuffer = StringBuilder()

        fun flushRoundBuffer() {
            if (roundBuffer.isNotEmpty()) {
                ChannelManager.sendMessage(channel, roundBuffer.toString().trim(), messageID)
                roundBuffer.clear()
            }
        }

        agentService.executeTask(task, object : AgentCallback {
            override fun onLoopStart(round: Int) {
                if (!isCurrentCallbackTask()) return
                // 新一轮开始前，flush 上一轮积攒的消息
                flushRoundBuffer()
                FloatingCircleManager.setRunningState(round, channel)
            }

            override fun onContent(round: Int, content: String) {
                if (!isCurrentCallbackTask()) return
                if (content.isNotEmpty()) {
                    roundBuffer.append(content)
                }
            }

            override fun onToolCall(round: Int, toolId: String, toolName: String, parameters: String) {
                if (!isCurrentCallbackTask()) return
                XLog.d(TAG, "onToolCall: $toolId($toolName), $parameters")
            }

            override fun onToolResult(round: Int, toolId: String, toolName: String, parameters: String, result: ToolResult) {
                if (!isCurrentCallbackTask()) return
                val app = ClawApplication.instance
                val status = if (result.isSuccess) app.getString(R.string.channel_msg_tool_success) else app.getString(R.string.channel_msg_tool_failure)
                var data = if (result.isSuccess) result.data else result.error
                if (data != null && data.length > 300) {
                    data = data.substring(0, 300) + "...(truncated)"
                }
                if (!result.isSuccess) {
                    XLog.e(TAG, "!!!!!!!!!!Fail: $toolName, $parameters $data")
                }
                XLog.e(TAG, "onToolResult: $toolName, $status $data")
                if (toolId == "finish" && (result.data?.isNotEmpty() ?: false)) {
                    // finish 的结果单独发，不合并（这是最终回复）
                    flushRoundBuffer()
                    ChannelManager.sendMessage(channel, result.data, messageID)
                } else {
                    // 追加到本轮缓冲
                    if (roundBuffer.isNotEmpty()) roundBuffer.append("\n")
                    roundBuffer.append(
                        app.getString(R.string.channel_msg_tool_execution, toolName + parameters, status)
                    )
                }
            }

            override fun onComplete(round: Int, finalAnswer: String, totalTokens: Int) {
                if (!isCurrentCallbackTask()) {
                    XLog.i(TAG, "Ignoring stale onComplete for task=$taskId")
                    return
                }
                XLog.i(TAG, "onComplete: 轮数=$round, totalTokens=$totalTokens, answer=$finalAnswer")
                flushRoundBuffer()
                // 标记当前任务完成
                synchronized(scheduleLock) {
                    currentTask?.status = TaskQueue.TaskStatus.COMPLETED
                }
                val (ch, _) = releaseTask()
                ChannelManager.flushMessages(channel)
                FloatingCircleManager.setSuccessState()
                // 从任务结果中提取用户偏好并更新记忆
                memoryStore?.let { store ->
                    store.extractFromTask(task, finalAnswer)
                    store.pruneExpiredContexts()
                    // 更新 agentConfig 的 memoryPromptSuffix
                    val memorySection = store.buildPromptSection()
                    if (memorySection.isNotEmpty()) {
                        val currentConfig = agentConfigProvider()
                        val updatedConfig = currentConfig.copy(memoryPromptSuffix = memorySection)
                        if (::agentService.isInitialized) {
                            agentService.updateConfig(updatedConfig)
                            XLog.i(TAG, "AgentConfig memoryPromptSuffix updated from MemoryStore")
                        }
                    }
                }
                onTaskFinished()
                triggerPostTaskReflect(success = true)
                // 任务完成后，如果有下一个任务，继续执行
                executeCurrentTask()
            }

            override fun onError(round: Int, error: Exception, totalTokens: Int) {
                if (!isCurrentCallbackTask()) {
                    XLog.i(TAG, "Ignoring stale onError for task=$taskId: ${error.message}")
                    return
                }
                XLog.e(TAG, "onError: ${error.message}, totalTokens=$totalTokens", error)
                flushRoundBuffer()
                synchronized(scheduleLock) {
                    currentTask?.status = TaskQueue.TaskStatus.FAILED
                }
                val (ch, _) = releaseTask()
                ChannelManager.sendMessage(channel, ClawApplication.instance.getString(R.string.channel_msg_task_error, error.message), messageID)
                ChannelManager.flushMessages(channel)
                FloatingCircleManager.setErrorState()
                onTaskFinished()
                triggerPostTaskReflect(success = false)
                // 任务失败后，如果有下一个任务，继续执行
                executeCurrentTask()
            }

            override fun onSystemDialogBlocked(round: Int, totalTokens: Int) {
                if (!isCurrentCallbackTask()) {
                    XLog.i(TAG, "Ignoring stale onSystemDialogBlocked for task=$taskId")
                    return
                }
                XLog.w(TAG, "onSystemDialogBlocked: round=$round, totalTokens=$totalTokens")
                flushRoundBuffer()
                synchronized(scheduleLock) {
                    currentTask?.status = TaskQueue.TaskStatus.FAILED
                }
                val (ch, _) = releaseTask()
                ChannelManager.sendMessage(channel, ClawApplication.instance.getString(R.string.channel_msg_system_dialog_blocked), messageID)
                try {
                    val service = ClawAccessibilityService.getInstance()
                    val bitmap = service?.takeScreenshot(5000)
                    if (bitmap != null) {
                        val stream = java.io.ByteArrayOutputStream()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 80, stream)
                        bitmap.recycle()
                        ChannelManager.sendImage(channel, stream.toByteArray(), messageID)
                    }
                } catch (e: Exception) {
                    XLog.e(TAG, "Failed to send screenshot for system dialog", e)
                }
                FloatingCircleManager.setErrorState()
                onTaskFinished()
                triggerPostTaskReflect(success = false)
                // 任务失败后，如果有下一个任务，继续执行
                executeCurrentTask()
            }
        }, untrusted = true)   // 聊天渠道来源：高危工具走来源闸门（默认拦截，满血/远程放行）
    }

    // ==================== 自进化反思钩子 ====================

    /**
     * 任务完成后异步触发 EvolutionEngine 反思.
     *
     * 策略：
     *  - B1（TurnScorer 打分）已在每次工具调用时自动执行，零开销
     *  - B2（deepReflect）每完成 N 个任务且距上次反思超过 5 分钟才触发
     *  - 反思结果写入 LessonStore，并更新 AgentConfig 的 dynamicPromptSuffix
     */
    private fun triggerPostTaskReflect(success: Boolean) {
        val engine = evolutionEngine ?: return
        taskCompleteCount++

        // B1 层始终可用：检查 TurnScorer 健康状态
        val scorer = ToolRegistry.turnScorer ?: return
        val fitness = scorer.computeFitness()
        XLog.d(TAG, "Post-task B1: score=${fitness.score}, trend=${fitness.trend}, verdict=${fitness.verdict}, successRate=${fitness.successRate}")

        // B2 层：条件触发
        val now = System.currentTimeMillis()
        val intervalPassed = (now - lastReflectTs) >= REFLECT_INTERVAL_MS
        val taskThresholdReached = taskCompleteCount >= REFLECT_EVERY_N_TASKS
        val degraded = fitness.verdict in listOf("degraded", "unhealthy", "critical")

        if (!intervalPassed || (!taskThresholdReached && !degraded)) return

        lastReflectTs = now
        taskCompleteCount = 0

        Thread({
            try {
                val result = engine.deepReflect(window = 20)
                if (result.ok) {
                    XLog.i(TAG, "EvolutionEngine B2 reflect: score=${result.overallScore}, trend=${result.trend}, action=${result.action}, detail=${result.actionDetail}")
                    if (result.action != "no_action") {
                        XLog.i(TAG, "EvolutionEngine recommends: ${result.action} — ${result.actionDetail} (${result.rationale})")
                    }
                    // 反思完成后，用 lessonStore 更新 agentConfig 的 dynamicPromptSuffix
                    val store = lessonStore
                    if (store != null) {
                        val promptSection = store.buildPromptSection()
                        if (promptSection.isNotEmpty()) {
                            val currentConfig = agentConfigProvider()
                            val updatedConfig = currentConfig.copy(dynamicPromptSuffix = promptSection)
                            if (::agentService.isInitialized) {
                                agentService.updateConfig(updatedConfig)
                                XLog.i(TAG, "AgentConfig dynamicPromptSuffix updated from LessonStore")
                            }
                        }
                    }
                } else {
                    XLog.w(TAG, "EvolutionEngine B2 reflect failed: ${result.error}")
                }
            } catch (e: Exception) {
                XLog.w(TAG, "EvolutionEngine reflect error: ${e.message}")
            }
        }, "evolution-reflect").start()
    }
}
