package com.apk.claw.android.navigation

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * NavigationRecorder 单元测试 —— 工具调用到 RemoteAction 的映射。
 *
 * toolCallToRemoteAction 是 private 方法,通过反射直接测试映射逻辑。
 * NavigationGraph 依赖 MMKV(JVM 不可用),用 Mockito mock 替代。
 * NavigationRecorder 构造时启动 HandlerThread,需 Robolectric 提供 Looper 支持。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavigationRecorderTest {

    private lateinit var graph: NavigationGraph
    private lateinit var recorder: NavigationRecorder

    @Before
    fun setUp() {
        // mock NavigationGraph 避免触发 MMKV 初始化(JVM 上不可用)
        graph = Mockito.mock(NavigationGraph::class.java)
        recorder = NavigationRecorder(graph, context = null)
    }

    @After
    fun tearDown() {
        // 停止 HandlerThread,避免线程泄漏
        recorder.destroy()
    }

    /** 通过反射调用 private toolCallToRemoteAction */
    private fun invokeToolCallToRemoteAction(
        toolName: String,
        params: Map<String, Any>
    ): RemoteAction? {
        val method = NavigationRecorder::class.java.getDeclaredMethod(
            "toolCallToRemoteAction",
            String::class.java,
            Map::class.java
        )
        method.isAccessible = true
        return method.invoke(recorder, toolName, params) as? RemoteAction
    }

    // ==================== 触屏动作映射 ====================

    @Test
    fun `tap maps to RemoteAction tap with x y`() {
        val action = invokeToolCallToRemoteAction("tap", mapOf("x" to 100, "y" to 200))
        assertNotNull(action)
        assertEquals("tap", action!!.type)
        assertEquals(100, action.params["x"])
        assertEquals(200, action.params["y"])
    }

    @Test
    fun `long_press maps to RemoteAction long_press with x y`() {
        val action = invokeToolCallToRemoteAction("long_press", mapOf("x" to 50, "y" to 60))
        assertNotNull(action)
        assertEquals("long_press", action!!.type)
        assertEquals(50, action.params["x"])
        assertEquals(60, action.params["y"])
    }

    @Test
    fun `swipe maps to RemoteAction swipe with all coordinates and duration`() {
        val action = invokeToolCallToRemoteAction(
            "swipe",
            mapOf(
                "start_x" to 10, "start_y" to 20,
                "end_x" to 30, "end_y" to 40,
                "duration_ms" to 800
            )
        )
        assertNotNull(action)
        assertEquals("swipe", action!!.type)
        assertEquals(10, action.params["start_x"])
        assertEquals(20, action.params["start_y"])
        assertEquals(30, action.params["end_x"])
        assertEquals(40, action.params["end_y"])
        assertEquals(800, action.params["duration_ms"])
    }

    @Test
    fun `swipe without duration defaults to 500ms`() {
        val action = invokeToolCallToRemoteAction(
            "swipe",
            mapOf("start_x" to 1, "start_y" to 2, "end_x" to 3, "end_y" to 4)
        )
        assertNotNull(action)
        assertEquals("swipe", action!!.type)
        assertEquals(500, action.params["duration_ms"])
    }

    // ==================== 输入与系统键映射 ====================

    @Test
    fun `input_text maps to RemoteAction input_text with text`() {
        val action = invokeToolCallToRemoteAction("input_text", mapOf("text" to "hello world"))
        assertNotNull(action)
        assertEquals("input_text", action!!.type)
        assertEquals("hello world", action.params["text"])
    }

    @Test
    fun `system_key back maps to keycode 4`() {
        val action = invokeToolCallToRemoteAction("system_key", mapOf("key" to "back"))
        assertNotNull(action)
        assertEquals("system_key", action!!.type)
        assertEquals(4, action.params["keycode"])
    }

    @Test
    fun `system_key home maps to keycode 3`() {
        val action = invokeToolCallToRemoteAction("system_key", mapOf("key" to "home"))
        assertNotNull(action)
        assertEquals("system_key", action!!.type)
        assertEquals(3, action.params["keycode"])
    }

    @Test
    fun `system_key menu maps to keycode 82`() {
        val action = invokeToolCallToRemoteAction("system_key", mapOf("key" to "menu"))
        assertNotNull(action)
        assertEquals("system_key", action!!.type)
        assertEquals(82, action.params["keycode"])
    }

    @Test
    fun `system_key volume_up maps to keycode 24`() {
        val action = invokeToolCallToRemoteAction("system_key", mapOf("key" to "volume_up"))
        assertNotNull(action)
        assertEquals("system_key", action!!.type)
        assertEquals(24, action.params["keycode"])
    }

    @Test
    fun `system_key volume_down maps to keycode 25`() {
        val action = invokeToolCallToRemoteAction("system_key", mapOf("key" to "volume_down"))
        assertNotNull(action)
        assertEquals("system_key", action!!.type)
        assertEquals(25, action.params["keycode"])
    }

    @Test
    fun `system_key power maps to keycode 26`() {
        val action = invokeToolCallToRemoteAction("system_key", mapOf("key" to "power"))
        assertNotNull(action)
        assertEquals("system_key", action!!.type)
        assertEquals(26, action.params["keycode"])
    }

    @Test
    fun `system_key unknown key returns null`() {
        val action = invokeToolCallToRemoteAction("system_key", mapOf("key" to "unknown_key"))
        assertNull(action)
    }

    // ==================== D-pad 映射 ====================

    @Test
    fun `dpad_up maps with repeat`() {
        val action = invokeToolCallToRemoteAction("dpad_up", mapOf("repeat" to 3))
        assertNotNull(action)
        assertEquals("dpad_up", action!!.type)
        assertEquals(3, action.params["repeat"])
    }

    @Test
    fun `dpad_down maps with repeat`() {
        val action = invokeToolCallToRemoteAction("dpad_down", mapOf("repeat" to 2))
        assertNotNull(action)
        assertEquals("dpad_down", action!!.type)
        assertEquals(2, action.params["repeat"])
    }

    @Test
    fun `dpad_left maps with repeat`() {
        val action = invokeToolCallToRemoteAction("dpad_left", mapOf("repeat" to 1))
        assertNotNull(action)
        assertEquals("dpad_left", action!!.type)
        assertEquals(1, action.params["repeat"])
    }

    @Test
    fun `dpad_right maps with repeat`() {
        val action = invokeToolCallToRemoteAction("dpad_right", mapOf("repeat" to 4))
        assertNotNull(action)
        assertEquals("dpad_right", action!!.type)
        assertEquals(4, action.params["repeat"])
    }

    @Test
    fun `dpad_center maps with repeat`() {
        val action = invokeToolCallToRemoteAction("dpad_center", mapOf("repeat" to 1))
        assertNotNull(action)
        assertEquals("dpad_center", action!!.type)
        assertEquals(1, action.params["repeat"])
    }

    @Test
    fun `dpad without repeat defaults to 1`() {
        val action = invokeToolCallToRemoteAction("dpad_up", emptyMap())
        assertNotNull(action)
        assertEquals("dpad_up", action!!.type)
        assertEquals(1, action.params["repeat"])
    }

    // ==================== 异常与边界 ====================

    @Test
    fun `unknown tool name returns null`() {
        val action = invokeToolCallToRemoteAction("get_screen_info", emptyMap())
        assertNull(action)
    }

    @Test
    fun `take_screenshot is not recorded returns null`() {
        val action = invokeToolCallToRemoteAction("take_screenshot", emptyMap())
        assertNull(action)
    }

    @Test
    fun `tap missing x returns null`() {
        val action = invokeToolCallToRemoteAction("tap", mapOf("y" to 200))
        assertNull(action)
    }

    @Test
    fun `tap missing y returns null`() {
        val action = invokeToolCallToRemoteAction("tap", mapOf("x" to 100))
        assertNull(action)
    }

    @Test
    fun `swipe missing end coordinates returns null`() {
        val action = invokeToolCallToRemoteAction(
            "swipe",
            mapOf("start_x" to 1, "start_y" to 2)
        )
        assertNull(action)
    }

    @Test
    fun `input_text missing text returns null`() {
        val action = invokeToolCallToRemoteAction("input_text", emptyMap())
        assertNull(action)
    }

    @Test
    fun `system_key missing key returns null`() {
        val action = invokeToolCallToRemoteAction("system_key", emptyMap())
        assertNull(action)
    }

    // ==================== onToolExecuted 间接验证 ====================

    @Test
    fun `onToolExecuted returns false when not recording and not passive`() {
        // 默认 recording=false, passiveMode=false
        val accepted = recorder.onToolExecuted("tap", mapOf("x" to 1, "y" to 2))
        assertFalse(accepted)
    }

    @Test
    fun `onToolExecuted returns false for unknown tool even in passive mode`() {
        recorder.setPassiveMode(true)
        val accepted = recorder.onToolExecuted("unknown_tool", emptyMap())
        assertFalse(accepted)
    }

    @Test
    fun `onToolExecuted returns false for missing params even in passive mode`() {
        recorder.setPassiveMode(true)
        // tap 缺少 x/y,toolCallToRemoteAction 返回 null
        val accepted = recorder.onToolExecuted("tap", emptyMap())
        assertFalse(accepted)
    }

    @Test
    fun `onToolExecuted accepts tap in passive mode`() {
        recorder.setPassiveMode(true)
        val accepted = recorder.onToolExecuted("tap", mapOf("x" to 100, "y" to 200))
        assertTrue(accepted)
    }

    @Test
    fun `onToolExecuted accepts swipe in passive mode`() {
        recorder.setPassiveMode(true)
        val accepted = recorder.onToolExecuted(
            "swipe",
            mapOf("start_x" to 1, "start_y" to 2, "end_x" to 3, "end_y" to 4, "duration_ms" to 300)
        )
        assertTrue(accepted)
    }

    @Test
    fun `onToolExecuted accepts input_text in passive mode`() {
        recorder.setPassiveMode(true)
        val accepted = recorder.onToolExecuted("input_text", mapOf("text" to "test"))
        assertTrue(accepted)
    }

    @Test
    fun `onToolExecuted accepts system_key in passive mode`() {
        recorder.setPassiveMode(true)
        val accepted = recorder.onToolExecuted("system_key", mapOf("key" to "back"))
        assertTrue(accepted)
    }

    @Test
    fun `onToolExecuted accepts dpad_up in passive mode`() {
        recorder.setPassiveMode(true)
        val accepted = recorder.onToolExecuted("dpad_up", mapOf("repeat" to 2))
        assertTrue(accepted)
    }

    @Test
    fun `onToolExecuted rejects read-only tools in passive mode`() {
        recorder.setPassiveMode(true)
        // 纯读取类工具不录制
        assertFalse(recorder.onToolExecuted("get_screen_info", emptyMap()))
        assertFalse(recorder.onToolExecuted("take_screenshot", emptyMap()))
        assertFalse(recorder.onToolExecuted("find_node_info", emptyMap()))
    }
}
