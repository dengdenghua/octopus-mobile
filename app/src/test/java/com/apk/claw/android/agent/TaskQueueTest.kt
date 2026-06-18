package com.apk.claw.android.agent

import com.apk.claw.android.channel.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TaskQueueTest {

    private fun makeTask(
        id: String,
        priority: TaskQueue.TaskPriority = TaskQueue.TaskPriority.NORMAL,
        isBackground: Boolean = false,
        createdAt: Long = System.currentTimeMillis(),
    ): TaskQueue.QueuedTask = TaskQueue.QueuedTask(
        id = id,
        task = "do something",
        channel = Channel.DINGTALK,
        messageId = "msg-$id",
        priority = priority,
        isBackground = isBackground,
        createdAt = createdAt,
    )

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

    // ==================== enqueue 超过 MAX_QUEUE_SIZE 拒绝 ====================

    @Test
    fun `enqueue rejects when queue is full`() {
        val queue = TaskQueue()
        // MAX_QUEUE_SIZE = 10，前 10 个应成功
        repeat(10) { i ->
            assertTrue("task #$i should be accepted", queue.enqueue(makeTask("task-$i")))
        }
        assertEquals(10, queue.size())
        // 第 11 个应被拒绝
        assertFalse("11th task should be rejected", queue.enqueue(makeTask("task-10")))
        assertEquals(10, queue.size())
    }

    // ==================== 优先级排序 ====================

    @Test
    fun `priority ordering HIGH before NORMAL`() {
        val queue = TaskQueue()
        val baseTime = System.currentTimeMillis()
        // 先入队一个 NORMAL，再入队一个 HIGH
        queue.enqueue(makeTask("normal", priority = TaskQueue.TaskPriority.NORMAL, createdAt = baseTime))
        queue.enqueue(makeTask("high", priority = TaskQueue.TaskPriority.HIGH, createdAt = baseTime + 100))

        val first = queue.dequeue()
        assertEquals("high", first?.id)
        val second = queue.dequeue()
        assertEquals("normal", second?.id)
    }

    @Test
    fun `priority ordering same priority FIFO`() {
        val queue = TaskQueue()
        val baseTime = System.currentTimeMillis()
        queue.enqueue(makeTask("first", priority = TaskQueue.TaskPriority.NORMAL, createdAt = baseTime))
        queue.enqueue(makeTask("second", priority = TaskQueue.TaskPriority.NORMAL, createdAt = baseTime + 100))
        queue.enqueue(makeTask("third", priority = TaskQueue.TaskPriority.NORMAL, createdAt = baseTime + 200))

        assertEquals("first", queue.dequeue()?.id)
        assertEquals("second", queue.dequeue()?.id)
        assertEquals("third", queue.dequeue()?.id)
    }

    // ==================== pauseTask 从队列暂停任务 ====================

    @Test
    fun `pauseTask removes task from queue and marks as paused`() {
        val queue = TaskQueue()
        queue.enqueue(makeTask("task-1"))
        queue.enqueue(makeTask("task-2"))

        val paused = queue.pauseTask("task-1")

        assertNotNull(paused)
        assertEquals("task-1", paused?.id)
        assertEquals(TaskQueue.TaskStatus.PAUSED, paused?.status)
        // 队列中只剩 task-2
        assertEquals(1, queue.size())
        assertEquals("task-2", queue.peek()?.id)
        // 暂停列表中有 task-1
        val pausedList = queue.getPausedTasks()
        assertEquals(1, pausedList.size)
        assertEquals("task-1", pausedList.first().id)
    }

    @Test
    fun `pauseTask returns null for non-existent task`() {
        val queue = TaskQueue()
        queue.enqueue(makeTask("task-1"))

        val paused = queue.pauseTask("non-existent")

        assertNull(paused)
        assertEquals(1, queue.size())
    }

    // ==================== cancelTask 同时处理队列中和暂停中的任务 ====================

    @Test
    fun `cancelTask removes queued task`() {
        val queue = TaskQueue()
        queue.enqueue(makeTask("task-1"))
        queue.enqueue(makeTask("task-2"))

        val result = queue.cancelTask("task-1")

        assertTrue(result)
        assertEquals(1, queue.size())
        assertEquals("task-2", queue.peek()?.id)
    }

    @Test
    fun `cancelTask removes paused task`() {
        val queue = TaskQueue()
        queue.enqueue(makeTask("task-1"))
        queue.pauseTask("task-1")

        // task-1 现在在 pausedTasks 中
        assertEquals(1, queue.getPausedTasks().size)
        val result = queue.cancelTask("task-1")

        assertTrue(result)
        assertEquals(0, queue.getPausedTasks().size)
    }

    @Test
    fun `cancelTask returns false for non-existent task`() {
        val queue = TaskQueue()
        val result = queue.cancelTask("non-existent")
        assertFalse(result)
    }

    // ==================== shouldPreempt 判断逻辑 ====================

    @Test
    fun `shouldPreempt returns false when current task is null`() {
        val queue = TaskQueue()
        val newTask = makeTask("new", priority = TaskQueue.TaskPriority.HIGH)
        assertFalse(queue.shouldPreempt(newTask, null))
    }

    @Test
    fun `shouldPreempt returns false when current task is background`() {
        val queue = TaskQueue()
        val current = makeTask("current", priority = TaskQueue.TaskPriority.LOW, isBackground = true)
        val newTask = makeTask("new", priority = TaskQueue.TaskPriority.URGENT)
        // 后台任务不抢占
        assertFalse(queue.shouldPreempt(newTask, current))
    }

    @Test
    fun `shouldPreempt returns false when new task is background`() {
        val queue = TaskQueue()
        val current = makeTask("current", priority = TaskQueue.TaskPriority.LOW)
        val newTask = makeTask("new", priority = TaskQueue.TaskPriority.URGENT, isBackground = true)
        // 后台任务不抢占前台
        assertFalse(queue.shouldPreempt(newTask, current))
    }

    @Test
    fun `shouldPreempt returns true when higher priority preempts lower`() {
        val queue = TaskQueue()
        val current = makeTask("current", priority = TaskQueue.TaskPriority.NORMAL)
        val newTask = makeTask("new", priority = TaskQueue.TaskPriority.HIGH)
        assertTrue(queue.shouldPreempt(newTask, current))
    }

    @Test
    fun `shouldPreempt returns false when lower priority does not preempt higher`() {
        val queue = TaskQueue()
        val current = makeTask("current", priority = TaskQueue.TaskPriority.HIGH)
        val newTask = makeTask("new", priority = TaskQueue.TaskPriority.NORMAL)
        assertFalse(queue.shouldPreempt(newTask, current))
    }

    @Test
    fun `shouldPreempt returns false when same priority`() {
        val queue = TaskQueue()
        val current = makeTask("current", priority = TaskQueue.TaskPriority.NORMAL)
        val newTask = makeTask("new", priority = TaskQueue.TaskPriority.NORMAL)
        assertFalse(queue.shouldPreempt(newTask, current))
    }

    // ==================== resumeTask 恢复后状态为 QUEUED ====================

    @Test
    fun `resumeTask sets status to QUEUED after pauseTask`() {
        val queue = TaskQueue()
        queue.enqueue(makeTask("task-1"))
        queue.pauseTask("task-1")

        // 暂停后状态应为 PAUSED
        assertEquals(TaskQueue.TaskStatus.PAUSED, queue.getPausedTasks().first().status)

        assertTrue(queue.resumeTask("task-1"))
        // 恢复后应回到队列，状态为 QUEUED
        val resumed = queue.dequeue()
        assertEquals("task-1", resumed?.id)
        assertEquals(TaskQueue.TaskStatus.QUEUED, resumed?.status)
    }

    @Test
    fun `resumeTask returns false for non-existent task`() {
        val queue = TaskQueue()
        assertFalse(queue.resumeTask("non-existent"))
    }

    // ==================== 并发安全测试 ====================

    @Test
    fun `concurrent enqueue and dequeue does not crash`() {
        val queue = TaskQueue()
        val threadCount = 8
        val tasksPerThread = 50
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())

        repeat(threadCount) { tid ->
            executor.submit {
                try {
                    repeat(tasksPerThread) { i ->
                        val task = makeTask("task-${tid}-$i")
                        queue.enqueue(task)
                        // 交替 dequeue，模拟消费
                        if (i % 2 == 0) {
                            queue.dequeue()
                        }
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Timed out waiting for threads", latch.await(30, TimeUnit.SECONDS))
        executor.shutdown()
        assertTrue("Concurrent operations threw: $errors", errors.isEmpty())
    }
}
