package com.apk.claw.android.tool.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LookAtScreenTool 单元测试.
 *
 * 只验证工具的元信息(名称/参数/描述),不测实际执行 ——
 * execute 依赖 AccessibilityService 截图与视觉模型网络请求,不在单测覆盖范围内。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LookAtScreenToolTest {

    private val tool = LookAtScreenTool()

    @Test
    fun `has correct name`() {
        assertEquals("look_at_screen", tool.getName())
    }

    @Test
    fun `has correct parameters`() {
        val params = tool.getParameters()
        val names = params.map { it.name }
        assertTrue("should declare 'question' parameter", "question" in names)
        // question 是可选的(留空返回整屏概览)
        val question = params.first { it.name == "question" }
        assertEquals("string", question.type)
        assertEquals(false, question.isRequired)
    }

    @Test
    fun `descriptions are not empty`() {
        assertTrue(tool.getDescriptionCN().isNotBlank())
        assertTrue(tool.getDescriptionEN().isNotBlank())
    }
}
