package com.apk.claw.android.octopus_mobile

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * ActionCache 测试 —— 快路径动作缓存（纯 JVM，KVUtils 退回内存 map）。
 *
 * 关键不变量：缓存按 routineId 唯一；promptHash 与 prompt 不符即视作无缓存
 * （指令改了不能拿旧动作跑），避免「换了指令还重放老坐标」。
 */
class ActionCacheTest {

    @Before
    fun reset() {
        ActionCache.all().forEach { ActionCache.remove(it.routineId) }
    }

    private fun seq(id: String, prompt: String, hit: Int = 0) = ActionCache.Sequence(
        routineId = id,
        promptHash = prompt.hashCode(),
        steps = listOf(ActionCache.Step(tool = "tap", argsJson = "{}", anchorText = "x")),
        createdAt = 0L,
        hitCount = hit,
    )

    @Test
    fun `all is empty initially`() {
        assertTrue(ActionCache.all().isEmpty())
    }

    @Test
    fun `put then get by matching prompt`() {
        ActionCache.put(seq("r1", "打开微信"))
        val got = ActionCache.get("r1", "打开微信")
        assertNotNull(got)
        assertEquals("r1", got!!.routineId)
        assertTrue(ActionCache.has("r1", "打开微信"))
    }

    @Test
    fun `get returns null when prompt changed (stale cache invalidation)`() {
        ActionCache.put(seq("r1", "打开微信"))
        assertNull(ActionCache.get("r1", "打开支付宝"))
        assertFalse(ActionCache.has("r1", "打开支付宝"))
    }

    @Test
    fun `put replaces sequence with same routineId`() {
        ActionCache.put(seq("r1", "p", hit = 1))
        ActionCache.put(seq("r1", "p", hit = 9))
        assertEquals(1, ActionCache.all().count { it.routineId == "r1" })
        assertEquals(9, ActionCache.get("r1", "p")!!.hitCount)
    }

    @Test
    fun `remove deletes only that routineId`() {
        ActionCache.put(seq("r1", "p1"))
        ActionCache.put(seq("r2", "p2"))
        ActionCache.remove("r1")
        assertNull(ActionCache.get("r1", "p1"))
        assertNotNull(ActionCache.get("r2", "p2"))
    }

    @Test
    fun `bumpHit increments hit count`() {
        ActionCache.put(seq("r1", "p", hit = 0))
        ActionCache.bumpHit("r1")
        ActionCache.bumpHit("r1")
        assertEquals(2, ActionCache.get("r1", "p")!!.hitCount)
    }

    @Test
    fun `multiple routines coexist`() {
        ActionCache.put(seq("r1", "p1"))
        ActionCache.put(seq("r2", "p2"))
        ActionCache.put(seq("r3", "p3"))
        assertEquals(3, ActionCache.all().size)
    }
}
