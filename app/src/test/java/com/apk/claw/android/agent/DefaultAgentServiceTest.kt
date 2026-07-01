package com.apk.claw.android.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.LinkedList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultAgentServiceTest {

    // ==================== 反射辅助方法 ====================

    /** 通过反射创建私有数据类 RoundFingerprint 实例 */
    private fun createFingerprint(screenHash: Int, toolCall: String): Any {
        val clazz = DefaultAgentService::class.java
            .getDeclaredClasses()
            .first { it.simpleName == "RoundFingerprint" }
        val constructor = clazz.getDeclaredConstructor(
            Int::class.javaPrimitiveType,
            String::class.java
        )
        constructor.isAccessible = true
        return constructor.newInstance(screenHash, toolCall)
    }

    /** 通过反射调用私有方法 isStuckInLoop */
    private fun invokeIsStuckInLoop(service: DefaultAgentService, history: LinkedList<Any>): Boolean {
        val method = DefaultAgentService::class.java
            .getDeclaredMethod("isStuckInLoop", LinkedList::class.java)
        method.isAccessible = true
        return method.invoke(service, history) as Boolean
    }

    // ==================== RoundFingerprint 相等性测试 ====================

    @Test
    fun `RoundFingerprint equals when same screenHash and toolCall`() {
        val fp1 = createFingerprint(12345, "tap:100,200")
        val fp2 = createFingerprint(12345, "tap:100,200")
        assertEquals(fp1, fp2)
        assertEquals(fp1.hashCode(), fp2.hashCode())
    }

    @Test
    fun `RoundFingerprint not equals when different screenHash`() {
        val fp1 = createFingerprint(12345, "tap:100,200")
        val fp2 = createFingerprint(99999, "tap:100,200")
        assertNotEquals(fp1, fp2)
    }

    @Test
    fun `RoundFingerprint not equals when different toolCall`() {
        val fp1 = createFingerprint(12345, "tap:100,200")
        val fp2 = createFingerprint(12345, "swipe:up")
        assertNotEquals(fp1, fp2)
    }

    // ==================== isStuckInLoop 窗口判断测试 ====================

    @Test
    fun `isStuckInLoop returns false when history is empty`() {
        val service = DefaultAgentService()
        val history = LinkedList<Any>()
        assertFalse(invokeIsStuckInLoop(service, history))
    }

    @Test
    fun `isStuckInLoop returns false when window not full`() {
        val service = DefaultAgentService()
        val history = LinkedList<Any>()
        // LOOP_DETECT_WINDOW = 4，3 个相同指纹不够触发
        repeat(3) { history.add(createFingerprint(12345, "tap:100,200")) }
        assertFalse(invokeIsStuckInLoop(service, history))
    }

    @Test
    fun `isStuckInLoop returns true when all fingerprints equal within window`() {
        val service = DefaultAgentService()
        val history = LinkedList<Any>()
        repeat(4) { history.add(createFingerprint(12345, "tap:100,200")) }
        assertTrue(invokeIsStuckInLoop(service, history))
    }

    @Test
    fun `isStuckInLoop returns false when fingerprints differ`() {
        val service = DefaultAgentService()
        val history = LinkedList<Any>()
        history.add(createFingerprint(12345, "tap:100,200"))
        history.add(createFingerprint(12345, "tap:100,200"))
        history.add(createFingerprint(12345, "tap:100,200"))
        history.add(createFingerprint(99999, "swipe:up"))
        assertFalse(invokeIsStuckInLoop(service, history))
    }

    @Test
    fun `isStuckInLoop returns true when window exceeds size and all equal`() {
        val service = DefaultAgentService()
        val history = LinkedList<Any>()
        // 5 个相同指纹（超过窗口大小 4），应触发
        repeat(5) { history.add(createFingerprint(777, "observe:get_screen_info")) }
        assertTrue(invokeIsStuckInLoop(service, history))
    }
}
