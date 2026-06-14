package com.apk.claw.android.agent

import com.apk.claw.android.channel.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskQueueTest {

    @Test
    fun `pauseRunningTask stores current task as paused`() {
        val queue = TaskQueue()
        val running = TaskQueue.QueuedTask(
            id = "task-1",
            task = "do something",
            channel = Channel.DINGTALK,
            messageId = "msg-1",
            status = TaskQueue.TaskStatus.RUNNING,
        )

        val paused = queue.pauseRunningTask(running)

        assertEquals(TaskQueue.TaskStatus.PAUSED, paused.status)
        assertEquals(listOf(paused), queue.getPausedTasks())
    }

    @Test
    fun `resumeTask requeues paused running task`() {
        val queue = TaskQueue()
        val running = TaskQueue.QueuedTask(
            id = "task-1",
            task = "do something",
            channel = Channel.DINGTALK,
            messageId = "msg-1",
            status = TaskQueue.TaskStatus.RUNNING,
        )

        queue.pauseRunningTask(running)
        assertTrue(queue.resumeTask("task-1"))

        val resumed = queue.dequeue()
        assertEquals("task-1", resumed?.id)
        assertEquals(TaskQueue.TaskStatus.QUEUED, resumed?.status)
    }
}
