package com.apk.claw.android.tool.impl.mobile

import com.apk.claw.android.TestClawApplication
import com.apk.claw.android.tool.ToolParameter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MobileTools 单元测试 —— 参数验证层.
 *
 * 覆盖：
 *  - 工具名 / 显示名 / 参数定义正确
 *  - 必需参数缺失时抛异常
 *  - 可选参数有默认值
 *  - AccessibilityService 未运行时返回 error（集成测试环境无法模拟）
 *
 * 注意：这些工具依赖 ClawAccessibilityService（系统级 Accessibility 服务），
 * 在 Robolectric 单元测试环境中无法真正执行点击/滑动/输入操作。
 * 此处只验证参数契约和基础行为。
 * getDisplayName() 读取字符串资源，因此需要 Robolectric 提供的真实 Resources。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestClawApplication::class)
class MobileToolsTest {

    // ── TapTool ───────────────────────────────────────────

    @Test
    fun `TapTool name is tap`() {
        val tool = TapTool()
        assertEquals("tap", tool.getName())
    }

    @Test
    fun `TapTool requires x and y parameters`() {
        val tool = TapTool()
        val params = tool.getParameters()
        assertEquals(2, params.size)
        assertEquals("x", params[0].name)
        assertEquals("y", params[1].name)
        assertTrue(params[0].isRequired)
        assertTrue(params[1].isRequired)
    }

    @Test
    fun `TapTool fails without accessibility service`() {
        val tool = TapTool()
        val result = tool.execute(mapOf("x" to 100, "y" to 200))
        assertFalse(result.isSuccess)
        assertTrue(result.error!!.contains("Accessibility"))
    }

    @Test
    fun `TapTool returns error when x missing`() {
        val tool = TapTool()
        val result = tool.execute(mapOf("y" to 200))
        // 在无障碍服务未运行时，参数验证之前的可访问性检查会先返回 error
        assertFalse(result.isSuccess)
    }

    // ── SwipeTool ─────────────────────────────────────────

    @Test
    fun `SwipeTool name is swipe`() {
        val tool = SwipeTool()
        assertEquals("swipe", tool.getName())
    }

    @Test
    fun `SwipeTool requires start and end coordinates`() {
        val tool = SwipeTool()
        val params = tool.getParameters()
        assertEquals(5, params.size)
        assertEquals("start_x", params[0].name)
        assertEquals("start_y", params[1].name)
        assertEquals("end_x", params[2].name)
        assertEquals("end_y", params[3].name)
        assertEquals("duration_ms", params[4].name)
        assertTrue(params[0].isRequired)
        assertTrue(params[1].isRequired)
        assertTrue(params[2].isRequired)
        assertTrue(params[3].isRequired)
        assertFalse(params[4].isRequired)
    }

    @Test
    fun `SwipeTool fails without accessibility service`() {
        val tool = SwipeTool()
        val result = tool.execute(mapOf(
            "start_x" to 100, "start_y" to 200,
            "end_x" to 300, "end_y" to 400
        ))
        assertFalse(result.isSuccess)
        assertTrue(result.error!!.contains("Accessibility"))
    }

    // ── LongPressTool ─────────────────────────────────────

    @Test
    fun `LongPressTool name is long_press`() {
        val tool = LongPressTool()
        assertEquals("long_press", tool.getName())
    }

    @Test
    fun `LongPressTool requires x and y`() {
        val tool = LongPressTool()
        val params = tool.getParameters()
        assertTrue(params.size >= 2)
        assertTrue(params.any { it.name == "x" && it.isRequired })
        assertTrue(params.any { it.name == "y" && it.isRequired })
    }

    @Test
    fun `LongPressTool fails without accessibility service`() {
        val tool = LongPressTool()
        val result = tool.execute(mapOf("x" to 100, "y" to 200))
        assertFalse(result.isSuccess)
    }

    // ── ScrollToFindTool ──────────────────────────────────

    @Test
    fun `ScrollToFindTool name is scroll_to_find`() {
        val tool = ScrollToFindTool()
        assertEquals("scroll_to_find", tool.getName())
    }

    @Test
    fun `ScrollToFindTool has text parameter`() {
        val tool = ScrollToFindTool()
        val params = tool.getParameters()
        assertTrue(params.any { it.name == "text" })
    }

    // ── 通用参数验证 ──────────────────────────────────────

    @Test
    fun `all mobile tools have non-empty descriptions`() {
        val tools = listOf(TapTool(), SwipeTool(), LongPressTool(), ScrollToFindTool())
        for (tool in tools) {
            assertTrue("${tool.getName()} EN desc empty", tool.getDescriptionEN().isNotBlank())
            assertTrue("${tool.getName()} CN desc empty", tool.getDescriptionCN().isNotBlank())
        }
    }

    @Test
    fun `all mobile tools have display names`() {
        val tools = listOf(TapTool(), SwipeTool(), LongPressTool(), ScrollToFindTool())
        for (tool in tools) {
            assertTrue("${tool.getName()} display name empty", tool.getDisplayName().isNotBlank())
        }
    }
}
