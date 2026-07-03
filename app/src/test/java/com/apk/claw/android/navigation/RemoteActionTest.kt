package com.apk.claw.android.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * RemoteAction 单元测试 —— 移动端触屏动作构造与工具调用映射。
 *
 * RemoteAction 是导航图中边的动作载体(定义在 NavigationGraph.kt),封装了
 * D-pad/触屏/系统键等操作。纯数据类无 Android 依赖,用 JUnit 4 直接测试。
 */
class RemoteActionTest {

    // ==================== tap 构造 ====================

    @Test
    fun `tap builds correct type and params`() {
        val action = RemoteAction.tap(100, 200)
        assertEquals("tap", action.type)
        assertEquals(100, action.params["x"])
        assertEquals(200, action.params["y"])
    }

    @Test
    fun `tap at zero coordinates is valid`() {
        val action = RemoteAction.tap(0, 0)
        assertEquals("tap", action.type)
        assertEquals(0, action.params["x"])
        assertEquals(0, action.params["y"])
    }

    // ==================== swipe 构造 ====================

    @Test
    fun `swipe builds correct type and params with default duration`() {
        val action = RemoteAction.swipe(100, 200, 300, 400)
        assertEquals("swipe", action.type)
        assertEquals(100, action.params["start_x"])
        assertEquals(200, action.params["start_y"])
        assertEquals(300, action.params["end_x"])
        assertEquals(400, action.params["end_y"])
        assertEquals(500, action.params["duration_ms"])  // 默认 500ms
    }

    @Test
    fun `swipe builds correct type and params with custom duration`() {
        val action = RemoteAction.swipe(10, 20, 30, 40, 1200)
        assertEquals("swipe", action.type)
        assertEquals(10, action.params["start_x"])
        assertEquals(20, action.params["start_y"])
        assertEquals(30, action.params["end_x"])
        assertEquals(40, action.params["end_y"])
        assertEquals(1200, action.params["duration_ms"])
    }

    // ==================== longPress 构造 ====================

    @Test
    fun `longPress builds correct type and params`() {
        val action = RemoteAction.longPress(250, 350)
        assertEquals("long_press", action.type)
        assertEquals(250, action.params["x"])
        assertEquals(350, action.params["y"])
    }

    // ==================== toToolCall 映射 ====================

    @Test
    fun `toToolCall maps tap to tap tool name and params`() {
        val action = RemoteAction.tap(100, 200)
        val (toolName, params) = action.toToolCall()
        assertEquals("tap", toolName)
        assertEquals(100, params["x"])
        assertEquals(200, params["y"])
    }

    @Test
    fun `toToolCall maps swipe to swipe tool name and params`() {
        val action = RemoteAction.swipe(1, 2, 3, 4, 600)
        val (toolName, params) = action.toToolCall()
        assertEquals("swipe", toolName)
        assertEquals(1, params["start_x"])
        assertEquals(2, params["start_y"])
        assertEquals(3, params["end_x"])
        assertEquals(4, params["end_y"])
        assertEquals(600, params["duration_ms"])
    }

    @Test
    fun `toToolCall maps long_press to long_press tool name and params`() {
        val action = RemoteAction.longPress(50, 60)
        val (toolName, params) = action.toToolCall()
        assertEquals("long_press", toolName)
        assertEquals(50, params["x"])
        assertEquals(60, params["y"])
    }

    @Test
    fun `toToolCall preserves dpad type and params`() {
        val action = RemoteAction.dpadUp(3)
        val (toolName, params) = action.toToolCall()
        assertEquals("dpad_up", toolName)
        assertEquals(3, params["repeat"])
    }

    @Test
    fun `toToolCall preserves input_text type and params`() {
        val action = RemoteAction.inputText("hello")
        val (toolName, params) = action.toToolCall()
        assertEquals("input_text", toolName)
        assertEquals("hello", params["text"])
    }

    // ==================== D-pad 与系统键构造(回归保护) ====================

    @Test
    fun `dpadUp builds correct action`() {
        val action = RemoteAction.dpadUp(2)
        assertEquals("dpad_up", action.type)
        assertEquals(2, action.params["repeat"])
    }

    @Test
    fun `back builds system_key with keycode 4`() {
        val action = RemoteAction.back()
        assertEquals("system_key", action.type)
        assertEquals(4, action.params["keycode"])
    }

    @Test
    fun `home builds system_key with keycode 3`() {
        val action = RemoteAction.home()
        assertEquals("system_key", action.type)
        assertEquals(3, action.params["keycode"])
    }

    @Test
    fun `toToolCall on system_key returns system_key name`() {
        val action = RemoteAction.back()
        val (toolName, params) = action.toToolCall()
        assertEquals("system_key", toolName)
        assertNotNull(params["keycode"])
    }
}
