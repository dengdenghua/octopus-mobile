package com.apk.claw.android.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationContextTest {

    @Test
    fun `空历史返回 null`() {
        assertNull(ConversationContext.build(emptyList()))
    }

    @Test
    fun `全空白消息返回 null`() {
        assertNull(ConversationContext.build(listOf(true to "  ", false to "\n")))
    }

    @Test
    fun `角色标注与顺序`() {
        val out = ConversationContext.build(
            listOf(true to "打开淘宝搜索耳机", false to "已搜索,当前停在结果页"),
        )
        assertEquals("用户: 打开淘宝搜索耳机\n助手: 已搜索,当前停在结果页", out)
    }

    @Test
    fun `超过轮数上限只保留最新几轮`() {
        val turns = (1..20).map { (it % 2 == 1) to "消息$it" }
        val out = ConversationContext.build(turns, maxTurns = 4)!!
        val lines = out.lines()
        assertEquals(4, lines.size)
        assertTrue(lines.last().endsWith("消息20"))
        assertFalse(out.contains("消息16"))
    }

    @Test
    fun `单条超长截断并加省略号`() {
        val long = "a".repeat(500)
        val out = ConversationContext.build(listOf(true to long), maxCharsPerTurn = 100)!!
        assertTrue(out.startsWith("用户: " + "a".repeat(100)))
        assertTrue(out.endsWith("…"))
    }

    @Test
    fun `总量超限时丢最旧的轮次而不是最新的`() {
        val turns = listOf(
            true to "旧".repeat(80),
            false to "中".repeat(80),
            true to "新".repeat(80),
        )
        val out = ConversationContext.build(turns, maxCharsPerTurn = 100, maxTotalChars = 180)!!
        assertTrue(out.contains("新"))
        assertFalse(out.contains("旧"))
    }

    @Test
    fun `即使单条就超过总量上限也至少保留最新一条`() {
        val out = ConversationContext.build(
            listOf(true to "x".repeat(50)),
            maxCharsPerTurn = 100,
            maxTotalChars = 10,
        )
        assertTrue(out!!.contains("x"))
    }

    @Test
    fun `消息内换行压成空格保持一轮一行`() {
        val out = ConversationContext.build(listOf(false to "第一行\n\n  第二行"))!!
        assertEquals("助手: 第一行 第二行", out)
        assertEquals(1, out.lines().size)
    }
}
