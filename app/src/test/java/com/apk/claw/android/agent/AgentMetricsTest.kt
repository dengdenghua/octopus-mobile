package com.apk.claw.android.agent

import com.apk.claw.android.utils.KVUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AgentMetrics 计数器单元测试(纯 JUnit,不依赖 Android).
 *
 * persist/load 依赖 KVUtils(MMKV),见同文件 [AgentMetricsPersistenceTest].
 */
class AgentMetricsTest {

    @After
    fun tearDown() {
        AgentMetrics.reset()
    }

    // ==================== 计数器 inc/get ====================

    @Test
    fun `inc increases counter by delta`() {
        AgentMetrics.inc("custom_key")
        assertEquals(1L, AgentMetrics.get("custom_key"))

        AgentMetrics.inc("custom_key", 4)
        assertEquals(5L, AgentMetrics.get("custom_key"))
    }

    @Test
    fun `get returns zero for unknown key`() {
        assertEquals(0L, AgentMetrics.get("never_used"))
    }

    @Test
    fun `inc on same key accumulates across calls`() {
        AgentMetrics.inc("counter")
        AgentMetrics.inc("counter")
        AgentMetrics.inc("counter")
        assertEquals(3L, AgentMetrics.get("counter"))
    }

    // ==================== reset ====================

    @Test
    fun `reset clears all counters`() {
        AgentMetrics.inc("a")
        AgentMetrics.inc("b", 10)
        AgentMetrics.reset()
        assertEquals(0L, AgentMetrics.get("a"))
        assertEquals(0L, AgentMetrics.get("b"))
    }

    // ==================== 便捷方法 ====================

    @Test
    fun `convenience methods increment corresponding keys`() {
        AgentMetrics.iteration()
        AgentMetrics.llmCall()
        AgentMetrics.llmFailure()
        AgentMetrics.toolCall()
        AgentMetrics.toolFailure()
        AgentMetrics.loopWarning()
        AgentMetrics.goalVerify()
        AgentMetrics.goalRepair()
        AgentMetrics.streamingDegraded()
        AgentMetrics.screenUnchanged()
        AgentMetrics.vlmCacheHit()

        assertEquals(1L, AgentMetrics.get(AgentMetrics.ITERATIONS))
        assertEquals(1L, AgentMetrics.get(AgentMetrics.LLM_CALLS))
        assertEquals(1L, AgentMetrics.get(AgentMetrics.LLM_FAILURES))
        assertEquals(1L, AgentMetrics.get(AgentMetrics.TOOL_CALLS))
        assertEquals(1L, AgentMetrics.get(AgentMetrics.TOOL_FAILURES))
        assertEquals(1L, AgentMetrics.get(AgentMetrics.LOOP_WARNINGS))
        assertEquals(1L, AgentMetrics.get(AgentMetrics.GOAL_VERIFIES))
        assertEquals(1L, AgentMetrics.get(AgentMetrics.GOAL_REPAIRS))
        assertEquals(1L, AgentMetrics.get(AgentMetrics.STREAMING_DEGRADED))
        assertEquals(1L, AgentMetrics.get(AgentMetrics.SCREEN_UNCHANGED))
        assertEquals(1L, AgentMetrics.get(AgentMetrics.VLM_CACHE_HITS))
    }

    // ==================== failureRate ====================

    @Test
    fun `llmFailureRate returns zero when no calls`() {
        assertEquals(0.0, AgentMetrics.llmFailureRate(), 0.0001)
    }

    @Test
    fun `llmFailureRate equals failures over calls`() {
        repeat(5) { AgentMetrics.llmCall() }
        repeat(2) { AgentMetrics.llmFailure() }
        assertEquals(0.4, AgentMetrics.llmFailureRate(), 0.0001)
    }

    @Test
    fun `toolFailureRate equals failures over calls`() {
        repeat(4) { AgentMetrics.toolCall() }
        repeat(1) { AgentMetrics.toolFailure() }
        assertEquals(0.25, AgentMetrics.toolFailureRate(), 0.0001)
    }

    @Test
    fun `toolFailureRate returns zero when no calls`() {
        assertEquals(0.0, AgentMetrics.toolFailureRate(), 0.0001)
    }

    @Test
    fun `goalRepairRate equals repairs over verifies`() {
        repeat(10) { AgentMetrics.goalVerify() }
        repeat(3) { AgentMetrics.goalRepair() }
        assertEquals(0.3, AgentMetrics.goalRepairRate(), 0.0001)
    }

    @Test
    fun `goalRepairRate returns zero when no verifies`() {
        assertEquals(0.0, AgentMetrics.goalRepairRate(), 0.0001)
    }

    // ==================== report ====================

    @Test
    fun `report contains key sections`() {
        AgentMetrics.iteration()
        AgentMetrics.llmCall()
        AgentMetrics.toolCall()
        AgentMetrics.goalVerify()

        val report = AgentMetrics.report()

        assertTrue(report.contains("迭代"))
        assertTrue(report.contains("LLM"))
        assertTrue(report.contains("工具"))
        assertTrue(report.contains("目标校验"))
        assertTrue(report.contains("死循环告警"))
        assertTrue(report.contains("流式降级"))
        assertTrue(report.contains("截图去重"))
        assertTrue(report.contains("VLM缓存命中"))
    }

    @Test
    fun `report formats failure rate as percentage`() {
        AgentMetrics.llmCall()
        AgentMetrics.llmFailure()
        val report = AgentMetrics.report()
        // 1/1 = 100% → "100.0%"
        assertTrue(report.contains("100.0%"))
    }

    @Test
    fun `report shows zero values when counters empty`() {
        val report = AgentMetrics.report()
        assertTrue(report.contains("迭代 0次"))
        assertTrue(report.contains("LLM 0次"))
        assertTrue(report.contains("工具 0次"))
    }
}

/**
 * AgentMetrics persist/load 测试(依赖 KVUtils,需要 Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentMetricsPersistenceTest {

    private val metricsKey = "AGENT_METRICS"

    @Before
    fun setUp() {
        AgentMetrics.reset()
        KVUtils.putString(metricsKey, "")
    }

    @After
    fun tearDown() {
        AgentMetrics.reset()
    }

    @Test
    fun `persist writes counters to KVUtils and load restores them`() {
        AgentMetrics.iteration()
        AgentMetrics.llmCall()
        AgentMetrics.llmFailure()
        AgentMetrics.toolCall()
        val originalReport = AgentMetrics.report()

        AgentMetrics.persist()

        AgentMetrics.reset()
        assertEquals(0L, AgentMetrics.get(AgentMetrics.ITERATIONS))

        AgentMetrics.load()

        assertEquals(1L, AgentMetrics.get(AgentMetrics.ITERATIONS))
        assertEquals(1L, AgentMetrics.get(AgentMetrics.LLM_CALLS))
        assertEquals(1L, AgentMetrics.get(AgentMetrics.LLM_FAILURES))
        assertEquals(1L, AgentMetrics.get(AgentMetrics.TOOL_CALLS))
        assertEquals(originalReport, AgentMetrics.report())
    }

    @Test
    fun `load is no-op when KVUtils empty`() {
        AgentMetrics.load()
        assertEquals(0L, AgentMetrics.get(AgentMetrics.ITERATIONS))
        assertEquals(0L, AgentMetrics.get(AgentMetrics.LLM_CALLS))
    }

    @Test
    fun `persist then load preserves failure rates`() {
        repeat(4) { AgentMetrics.llmCall() }
        repeat(1) { AgentMetrics.llmFailure() }
        val rateBefore = AgentMetrics.llmFailureRate()

        AgentMetrics.persist()
        AgentMetrics.reset()
        AgentMetrics.load()

        assertEquals(rateBefore, AgentMetrics.llmFailureRate(), 0.0001)
    }
}
