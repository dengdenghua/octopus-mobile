package com.apk.claw.android.octopus_mobile.evolution

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * CanaryManager 测试 —— 灰度晋级 / 回滚.
 */
class CanaryManagerTest {

    private val tempDir = File(System.getProperty("java.io.tmpdir"), "canary_test_${System.currentTimeMillis()}")

    @Test
    fun `register starts at shadow`() {
        val cm = CanaryManager(tempDir)
        val state = cm.register("browser_install_extension")
        assertEquals(CanaryManager.Phase.SHADOW, state.phase)
    }

    @Test
    fun `shouldRoute shadow is false`() {
        val cm = CanaryManager(tempDir)
        cm.register("browser_install_extension")
        assertFalse(cm.shouldRoute("browser_install_extension"))
    }

    @Test
    fun `shouldRoute full is true`() {
        val cm = CanaryManager(tempDir)
        val state = cm.register("browser_install_extension")
        state.phase = CanaryManager.Phase.FULL
        assertTrue(cm.shouldRoute("browser_install_extension"))
    }

    @Test
    fun `promote on success`() {
        val cm = CanaryManager(tempDir)
        val state = cm.register("browser_install_extension")
        // Shadow → need 10 samples at 70%
        repeat(10) { cm.recordOutcome("browser_install_extension", success = true) }
        assertEquals(CanaryManager.Phase.CANARY_5, state.phase)
    }

    @Test
    fun `rollback on failure`() {
        val cm = CanaryManager(tempDir)
        val state = cm.register("browser_install_extension")
        // 5 failures → rate < 50% → rollback
        repeat(5) { cm.recordOutcome("browser_install_extension", success = false) }
        assertEquals(CanaryManager.Phase.ROLLED_BACK, state.phase)
    }

    @Test
    fun `full progression`() {
        val cm = CanaryManager(tempDir)
        val state = cm.register("browser_install_extension")

        // SHADOW → CANARY_5 (10 samples, 100%)
        repeat(10) { cm.recordOutcome("browser_install_extension", success = true) }
        assertEquals(CanaryManager.Phase.CANARY_5, state.phase)

        // CANARY_5 → CANARY_25 (20 samples, 100%)
        repeat(20) { cm.recordOutcome("browser_install_extension", success = true) }
        assertEquals(CanaryManager.Phase.CANARY_25, state.phase)

        // CANARY_25 → CANARY_50 (40 samples, 100%)
        repeat(40) { cm.recordOutcome("browser_install_extension", success = true) }
        assertEquals(CanaryManager.Phase.CANARY_50, state.phase)

        // CANARY_50 → FULL (60 samples, 100%)
        repeat(60) { cm.recordOutcome("browser_install_extension", success = true) }
        assertEquals(CanaryManager.Phase.FULL, state.phase)
    }

    @Test
    fun `forceRollback`() {
        val cm = CanaryManager(tempDir)
        val state = cm.register("browser_install_extension")
        state.phase = CanaryManager.Phase.FULL
        cm.forceRollback("browser_install_extension")
        assertEquals(CanaryManager.Phase.ROLLED_BACK, state.phase)
    }

    @Test
    fun `listActive excludes full and rolled_back`() {
        val cm = CanaryManager(tempDir)
        cm.register("skill_a")
        cm.register("skill_b")
        cm.getState("skill_b")?.phase = CanaryManager.Phase.FULL
        val active = cm.listActive()
        assertEquals(1, active.size)
        assertEquals("skill_a", active[0].skillName)
    }

    @Test
    fun `persistence roundtrip`() {
        val cm1 = CanaryManager(tempDir)
        cm1.register("skill_x")
        repeat(5) { cm1.recordOutcome("skill_x", success = true) }

        // 新建实例，应加载持久化状态
        val cm2 = CanaryManager(tempDir)
        val state = cm2.getState("skill_x")
        assertNotNull(state)
        assertEquals(5, state?.sampleCount)
    }
}
