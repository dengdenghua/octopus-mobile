@file:Suppress("PackageNaming")   // 沿用既有 octopus_mobile 包(带下划线)

package com.apk.claw.android.octopus_mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * EvolutionMetrics 测试 —— 计数 / 命中率 / 告警率 / 报告格式。
 *
 * 单例跨测试累积,故每例 @Before reset() 保证确定性。
 */
class EvolutionMetricsTest {

    @Before
    fun setUp() {
        EvolutionMetrics.reset()
    }

    @Test
    fun `reflex hit rate computed from hits and misses`() {
        repeat(3) { EvolutionMetrics.reflexHit() }
        repeat(1) { EvolutionMetrics.reflexMiss() }
        // 3 命中 / 4 总 = 0.75
        assertEquals(0.75, EvolutionMetrics.reflexHitRate(), 1e-9)
        assertEquals(3L, EvolutionMetrics.get(EvolutionMetrics.REFLEX_HIT))
        assertEquals(1L, EvolutionMetrics.get(EvolutionMetrics.REFLEX_MISS))
    }

    @Test
    fun `hit rate is zero when no reflex activity`() {
        assertEquals(0.0, EvolutionMetrics.reflexHitRate(), 1e-9)
    }

    @Test
    fun `immune warn rate counts warns over calls`() {
        EvolutionMetrics.immuneCall(warned = true)
        EvolutionMetrics.immuneCall(warned = false)
        EvolutionMetrics.immuneCall(warned = false)
        EvolutionMetrics.immuneCall(warned = false)
        // 1 warn / 4 calls = 0.25
        assertEquals(0.25, EvolutionMetrics.immuneWarnRate(), 1e-9)
        assertEquals(1L, EvolutionMetrics.get(EvolutionMetrics.IMMUNE_WARN))
        assertEquals(4L, EvolutionMetrics.get(EvolutionMetrics.IMMUNE_CALL))
    }

    @Test
    fun `ledger counters increment independently`() {
        EvolutionMetrics.ledgerError()
        EvolutionMetrics.ledgerError()
        EvolutionMetrics.ledgerRepair()
        EvolutionMetrics.mitigationInjected()
        assertEquals(2L, EvolutionMetrics.get(EvolutionMetrics.LEDGER_ERROR))
        assertEquals(1L, EvolutionMetrics.get(EvolutionMetrics.LEDGER_REPAIR))
        assertEquals(1L, EvolutionMetrics.get(EvolutionMetrics.MITIGATION_INJECTED))
    }

    @Test
    fun `report contains the key rates`() {
        EvolutionMetrics.reflexHit()
        EvolutionMetrics.immuneCall(warned = true)
        val r = EvolutionMetrics.report()
        assertTrue(r, r.contains("ReflexArc 命中率"))
        assertTrue(r, r.contains("ImmuneSystem 告警率"))
        assertTrue(r, r.contains("经验账本"))
    }
}
