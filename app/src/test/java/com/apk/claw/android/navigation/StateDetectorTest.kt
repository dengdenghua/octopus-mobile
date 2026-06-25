package com.apk.claw.android.navigation

import org.junit.Assert.*
import org.junit.Test

/**
 * StateDetector 测试 —— 屏幕指纹相似度与指纹 ID（纯逻辑，无 Android 依赖）。
 *
 * similarity 是「每步执行后校验屏幕是否变化」与导航图去重的判据，权重：
 *   package 0.5 + uiTreeHash 0.3 + keyElements 交并比 0.2。
 */
class StateDetectorTest {

    private fun fp(pkg: String, hash: String, keys: List<String>) =
        StateFingerprint(packageActivity = pkg, uiTreeHash = hash, keyElements = keys)

    private val DELTA = 1e-9

    @Test
    fun `identical fingerprints score 1`() {
        val a = fp("com.app/.Main", "h1", listOf("登录", "注册"))
        assertEquals(1.0, StateDetector.similarity(a, a), DELTA)
    }

    @Test
    fun `totally different fingerprints score 0`() {
        val a = fp("com.a/.X", "h1", listOf("A"))
        val b = fp("com.b/.Y", "h2", listOf("B"))
        assertEquals(0.0, StateDetector.similarity(a, b), DELTA)
    }

    @Test
    fun `same package and hash but disjoint keys scores 0_8`() {
        val a = fp("com.app/.Main", "h1", listOf("X"))
        val b = fp("com.app/.Main", "h1", listOf("Y"))
        assertEquals(0.8, StateDetector.similarity(a, b), DELTA)
    }

    @Test
    fun `same package only scores 0_5`() {
        val a = fp("com.app/.Main", "h1", listOf("X"))
        val b = fp("com.app/.Main", "h2", listOf("Y"))
        assertEquals(0.5, StateDetector.similarity(a, b), DELTA)
    }

    @Test
    fun `key elements contribute by jaccard intersection over union`() {
        // package + hash 都匹配 → 0.8 起；keys {A,B} vs {B,C}: 交1 并3 → 0.2 * 1/3
        val a = fp("com.app/.Main", "h1", listOf("A", "B"))
        val b = fp("com.app/.Main", "h1", listOf("B", "C"))
        assertEquals(0.8 + 0.2 * (1.0 / 3.0), StateDetector.similarity(a, b), DELTA)
    }

    @Test
    fun `empty key elements on one side skips the key term`() {
        val a = fp("com.app/.Main", "h1", emptyList())
        val b = fp("com.app/.Main", "h1", listOf("X"))
        // package 0.5 + hash 0.3, keys 项跳过
        assertEquals(0.8, StateDetector.similarity(a, b), DELTA)
    }

    @Test
    fun `similarity is symmetric`() {
        val a = fp("com.app/.Main", "h1", listOf("A", "B", "C"))
        val b = fp("com.app/.Main", "h2", listOf("B", "C", "D"))
        assertEquals(StateDetector.similarity(a, b), StateDetector.similarity(b, a), DELTA)
    }

    @Test
    fun `near-identical screens clear the 0_97 unchanged threshold`() {
        // DefaultAgentService 用 >= 0.97 判定「屏幕没变」。同包同树、键集高度重合应越过阈值。
        val a = fp("com.app/.Main", "h1", listOf("A", "B", "C", "D", "E"))
        val b = fp("com.app/.Main", "h1", listOf("A", "B", "C", "D", "E"))
        assertTrue(StateDetector.similarity(a, b) >= 0.97)
    }

    // ── StateFingerprint.id ─────────────────────────────
    @Test
    fun `fingerprint id is stable and 16 chars`() {
        val a = fp("com.app/.Main", "h1", listOf("A", "B"))
        assertEquals(16, a.id.length)
        assertEquals(a.id, fp("com.app/.Main", "h1", listOf("A", "B")).id)
    }

    @Test
    fun `fingerprint id is independent of key element order`() {
        val a = fp("com.app/.Main", "h1", listOf("A", "B", "C"))
        val b = fp("com.app/.Main", "h1", listOf("C", "A", "B"))
        assertEquals(a.id, b.id)
    }

    @Test
    fun `fingerprint id differs when package differs`() {
        val a = fp("com.a/.X", "h1", listOf("A"))
        val b = fp("com.b/.X", "h1", listOf("A"))
        assertNotEquals(a.id, b.id)
    }
}
