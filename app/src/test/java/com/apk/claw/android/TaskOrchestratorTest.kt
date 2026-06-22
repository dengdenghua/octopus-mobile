package com.apk.claw.android

import com.apk.claw.android.agent.AgentConfig
import com.apk.claw.android.agent.TaskQueue
import com.apk.claw.android.channel.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestClawApplication::class, sdk = [34])
class TaskOrchestratorTest {

    private val defaultConfig = AgentConfig(
        modelName = "test-model",
        apiKey = "test-key",
        baseUrl = "http://localhost",
    )

    private lateinit var orchestrator: TaskOrchestrator
    private var finishedCount = 0

    @Before
    fun setup() {
        finishedCount = 0
        orchestrator = TaskOrchestrator(
            agentConfigProvider = { defaultConfig },
            onTaskFinished = { finishedCount++ }
        )
    }

    // ==================== 任务入队与状态查询 ====================

    @Test
    fun `tryAcquireTask enqueues task and marks running`() {
        val acquired = orchestrator.tryAcquireTask("msg-1", Channel.DINGTALK)
        assertTrue(acquired)
        assertTrue(orchestrator.isTaskRunning())
        assertEquals("msg-1", orchestrator.inProgressTaskMessageId)
        assertEquals(Channel.DINGTALK, orchestrator.inProgressTaskChannel)
    }

    @Test
    fun `tryAcquireTask with different channels preserves channel`() {
        orchestrator.tryAcquireTask("msg-1", Channel.DINGTALK)
        assertEquals(Channel.DINGTALK, orchestrator.inProgressTaskChannel)

        val orchestrator2 = TaskOrchestrator(
            agentConfigProvider = { defaultConfig },
            onTaskFinished = {}
        )
        orchestrator2.tryAcquireTask("msg-2", Channel.FEISHU)
        assertEquals(Channel.FEISHU, orchestrator2.inProgressTaskChannel)
    }

    @Test
    fun `getTaskQueueInfo reflects current and queued tasks`() {
        orchestrator.tryAcquireTask("msg-1", Channel.DINGTALK)
        orchestrator.tryAcquireTask("msg-2", Channel.FEISHU)
        orchestrator.tryAcquireTask("msg-3", Channel.QQ)

        val info = orchestrator.getTaskQueueInfo()
        assertNotNull(info.currentTask)
        assertEquals(2, info.queuedTasks.size)
        assertEquals(2, info.queueSize)
        assertTrue(info.queuedTasks.any { it.messageId == "msg-2" })
        assertTrue(info.queuedTasks.any { it.messageId == "msg-3" })
    }

    @Test
    fun `tryAcquireTask rejects when queue is full`() {
        // 第一次入队会立即成为 currentTask，队列最多还能容纳 MAX_QUEUE_SIZE(10) 个任务，
        // 因此需要 12 次调用才会出现一次拒绝。
        repeat(12) { i ->
            val ok = orchestrator.tryAcquireTask("msg-$i", Channel.DINGTALK)
            if (i < 11) {
                assertTrue("task #$i should be accepted", ok)
            } else {
                assertFalse("12th task should be rejected", ok)
            }
        }
        assertTrue(orchestrator.isTaskRunning())
    }

    @Test
    fun `task priority is preserved in queued tasks`() {
        // 使用相同优先级，避免触发高优先级抢占（会重建 AgentService）
        orchestrator.tryAcquireTask("msg-1", Channel.DINGTALK, priority = TaskQueue.TaskPriority.HIGH)
        orchestrator.tryAcquireTask("msg-2", Channel.DINGTALK, priority = TaskQueue.TaskPriority.HIGH)
        orchestrator.tryAcquireTask("msg-3", Channel.DINGTALK, priority = TaskQueue.TaskPriority.HIGH)

        val info = orchestrator.getTaskQueueInfo()
        assertEquals(2, info.queuedTasks.size)
        assertTrue(info.queuedTasks.all { it.priority == TaskQueue.TaskPriority.HIGH })
    }

    @Test
    fun `background flag is preserved in queued tasks`() {
        orchestrator.tryAcquireTask("msg-1", Channel.DINGTALK, isBackground = true)
        orchestrator.tryAcquireTask("msg-2", Channel.DINGTALK, isBackground = true)
        orchestrator.tryAcquireTask("msg-3", Channel.DINGTALK, isBackground = true)

        val info = orchestrator.getTaskQueueInfo()
        assertEquals(2, info.queuedTasks.size)
        assertTrue(info.queuedTasks.all { it.isBackground })
    }

    @Test
    fun `inProgress accessors return empty values when idle`() {
        val fresh = TaskOrchestrator(
            agentConfigProvider = { defaultConfig },
            onTaskFinished = {}
        )
        assertFalse(fresh.isTaskRunning())
        assertEquals("", fresh.inProgressTaskMessageId)
        assertNull(fresh.inProgressTaskChannel)
    }

    @Test
    fun `task ids are unique across calls`() {
        orchestrator.tryAcquireTask("msg-1", Channel.DINGTALK)
        orchestrator.tryAcquireTask("msg-2", Channel.DINGTALK)

        val info = orchestrator.getTaskQueueInfo()
        val currentId = info.currentTask?.id
        val queuedId = info.queuedTasks.firstOrNull()?.id
        assertNotNull(currentId)
        assertNotNull(queuedId)
        assertTrue("expected different ids but got $currentId", currentId != queuedId)
    }
}
