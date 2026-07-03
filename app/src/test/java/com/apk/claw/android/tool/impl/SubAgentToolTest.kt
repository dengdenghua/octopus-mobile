package com.apk.claw.android.tool.impl

import com.apk.claw.android.TaskOrchestrator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SubAgentTool 单元测试 —— 子 Agent 工具的元信息与执行守卫。
 *
 * 覆盖:
 *  - getName() 返回 "spawn_subagent"
 *  - isIdempotent() 返回 false(派生子 Agent 有副作用,不可自动重试)
 *  - getParameters() 包含 task(必填) 和 max_iterations(选填)
 *  - execute() 在 TaskOrchestrator.current 为 null 时返回 error(不创建子 Agent)
 *
 * execute() 调用 XLog,需 Robolectric 提供 android.util.Log 的 shadow。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubAgentToolTest {

    private lateinit var tool: SubAgentTool
    private var savedCurrent: TaskOrchestrator? = null

    @Before
    fun setUp() {
        tool = SubAgentTool()
        // 保存原始 current 值,测试后恢复
        savedCurrent = TaskOrchestrator.current
    }

    @After
    fun tearDown() {
        // 恢复 TaskOrchestrator.current 原始值,避免影响其他测试
        setTaskOrchestratorCurrent(savedCurrent)
    }

    /** 通过反射设置 TaskOrchestrator.current(companion object var,private set)。 */
    private fun setTaskOrchestratorCurrent(value: TaskOrchestrator?) {
        val field = TaskOrchestrator::class.java.getDeclaredField("current")
        field.isAccessible = true
        field.set(null, value)
    }

    // ==================== 元信息 ====================

    @Test
    fun `getName returns spawn_subagent`() {
        assertEquals("spawn_subagent", tool.getName())
    }

    @Test
    fun `isIdempotent returns false`() {
        // 子 Agent 派生有副作用(创建 Agent 实例、消耗迭代预算),不应自动重试
        assertFalse(tool.isIdempotent())
    }

    @Test
    fun `getParameters contains task as required string`() {
        val params = tool.getParameters()
        val taskParam = params.find { it.name == "task" }
        assertNotNull("parameters must include 'task'", taskParam)
        assertEquals("string", taskParam!!.type)
        assertTrue("task must be required", taskParam.isRequired)
    }

    @Test
    fun `getParameters contains max_iterations as optional integer`() {
        val params = tool.getParameters()
        val maxIterParam = params.find { it.name == "max_iterations" }
        assertNotNull("parameters must include 'max_iterations'", maxIterParam)
        assertEquals("integer", maxIterParam!!.type)
        assertFalse("max_iterations must be optional", maxIterParam.isRequired)
    }

    @Test
    fun `getParameters has exactly two parameters`() {
        val params = tool.getParameters()
        assertEquals(2, params.size)
    }

    // ==================== execute 守卫 ====================

    @Test
    fun `execute returns error when TaskOrchestrator is not initialized`() {
        // 确保 TaskOrchestrator.current 为 null
        setTaskOrchestratorCurrent(null)

        val result = tool.execute(mapOf("task" to "test sub-task"))

        assertFalse("should fail when orchestrator is null", result.isSuccess)
        val error = result.error ?: ""
        assertTrue(
            "error should mention TaskOrchestrator: got '$error'",
            error.contains("TaskOrchestrator")
        )
    }

    @Test
    fun `execute returns error mentioning Agent config when orchestrator is null`() {
        setTaskOrchestratorCurrent(null)

        val result = tool.execute(mapOf("task" to "another task"))

        assertFalse(result.isSuccess)
        assertTrue((result.error ?: "").contains("Agent"))
    }
}
