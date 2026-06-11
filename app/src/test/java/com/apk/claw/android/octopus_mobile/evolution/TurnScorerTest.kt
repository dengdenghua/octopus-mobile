package com.apk.claw.android.octopus_mobile.evolution

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * TurnScorer 测试 —— 打分 / 适应度 / 趋势.
 */
class TurnScorerTest {

    private val tempDir = File(System.getProperty("java.io.tmpdir"), "turn_scorer_test_${System.currentTimeMillis()}")

    @Test
    fun `record and read back`() {
        val scorer = TurnScorer(tempDir)
        scorer.record("tap", success = true, rounds = 1, reason = "ok")
        val scores = scorer.readRecentScores(10)
        assertEquals(1, scores.size)
        assertEquals("tap", scores[0].toolName)
        assertEquals(1.0, scores[0].score, 0.001)
    }

    @Test
    fun `record partial`() {
        val scorer = TurnScorer(tempDir)
        scorer.recordPartial("tap", rounds = 2, reason = "partial")
        val scores = scorer.readRecentScores(10)
        assertEquals(0.5, scores[0].score, 0.001)
    }

    @Test
    fun `fitness healthy`() {
        val scorer = TurnScorer(tempDir)
        repeat(10) { scorer.record("tap", success = true) }
        val fitness = scorer.computeFitness()
        assertEquals("healthy", fitness.verdict)
        assertEquals(1.0, fitness.successRate, 0.001)
        assertEquals("stable", fitness.trend)
    }

    @Test
    fun `fitness critical`() {
        val scorer = TurnScorer(tempDir)
        repeat(10) { scorer.record("tap", success = false) }
        val fitness = scorer.computeFitness()
        assertEquals("critical", fitness.verdict)
        assertEquals(0.0, fitness.successRate, 0.001)
    }

    @Test
    fun `fitness trend improving`() {
        val scorer = TurnScorer(tempDir)
        // 前半失败，后半成功 → improving
        repeat(5) { scorer.record("tap", success = false) }
        repeat(5) { scorer.record("tap", success = true) }
        val fitness = scorer.computeFitness()
        assertEquals("improving", fitness.trend)
    }

    @Test
    fun `fitness trend regressing`() {
        val scorer = TurnScorer(tempDir)
        // 前半成功，后半失败 → regressing
        repeat(5) { scorer.record("tap", success = true) }
        repeat(5) { scorer.record("tap", success = false) }
        val fitness = scorer.computeFitness()
        assertEquals("regressing", fitness.trend)
    }

    @Test
    fun `fitness top failure`() {
        val scorer = TurnScorer(tempDir)
        repeat(5) { scorer.record("tap", success = false) }
        repeat(3) { scorer.record("swipe", success = false) }
        val fitness = scorer.computeFitness()
        assertEquals("tap", fitness.topFailure)
    }

    @Test
    fun `tool stats`() {
        val scorer = TurnScorer(tempDir)
        repeat(5) { scorer.record("tap", success = true) }
        repeat(3) { scorer.record("tap", success = false) }
        repeat(2) { scorer.record("swipe", success = true) }
        val stats = scorer.toolStats()
        assertEquals(8, stats["tap"]?.totalCalls)
        assertEquals(5, stats["tap"]?.successCount)
        assertEquals(3, stats["tap"]?.failureCount)
        assertEquals(2, stats["swipe"]?.totalCalls)
    }

    @Test
    fun `empty scores returns degraded`() {
        val scorer = TurnScorer(tempDir)
        val fitness = scorer.computeFitness()
        assertEquals("degraded", fitness.verdict)
        assertEquals(0, fitness.totalSamples)
    }
}
