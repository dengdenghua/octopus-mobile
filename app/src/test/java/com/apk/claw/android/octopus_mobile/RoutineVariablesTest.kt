package com.apk.claw.android.octopus_mobile

import org.junit.Assert.*
import org.junit.Test

/**
 * RoutineVariables 测试 —— 例程参数化的纯逻辑层（无 Android 依赖）。
 *
 * 覆盖 names / hasVariables / extract / substitute 四个入口，重点验证：
 *  - 模板 ↔ 实际指令的「抽取 → 替换」往返一致；
 *  - 结构对不上时 extract 返回空 map（调用方据此回退完整 Agent）；
 *  - 中英文 / 数字 / 下划线变量名；非贪婪抽取相邻变量。
 */
class RoutineVariablesTest {

    // ── names ────────────────────────────────────────────
    @Test
    fun `names lists declared variables in order, deduped`() {
        assertEquals(listOf("联系人", "内容"), RoutineVariables.names("给{联系人}发{内容}"))
    }

    @Test
    fun `names dedupes repeated placeholder`() {
        assertEquals(listOf("x"), RoutineVariables.names("{x} 和 {x}"))
    }

    @Test
    fun `names empty when no placeholders`() {
        assertTrue(RoutineVariables.names("没有变量的普通指令").isEmpty())
    }

    @Test
    fun `names supports ascii digits underscore`() {
        assertEquals(listOf("user_1", "B2"), RoutineVariables.names("hi {user_1} and {B2}"))
    }

    // ── hasVariables ─────────────────────────────────────
    @Test
    fun `hasVariables true when placeholder present`() {
        assertTrue(RoutineVariables.hasVariables("给{人}发消息"))
    }

    @Test
    fun `hasVariables false for plain text and for empty braces`() {
        assertFalse(RoutineVariables.hasVariables("给张三发消息"))
        // 空花括号不符合变量名正则（至少一个合法字符）
        assertFalse(RoutineVariables.hasVariables("a {} b"))
    }

    // ── extract ──────────────────────────────────────────
    @Test
    fun `extract pulls values from actual via template`() {
        val vars = RoutineVariables.extract("给{联系人}发{内容}", "给张三发你好")
        assertEquals(mapOf("联系人" to "张三", "内容" to "你好"), vars)
    }

    @Test
    fun `extract trims surrounding whitespace of actual and values`() {
        val vars = RoutineVariables.extract("打开{应用}", "  打开 微信 ")
        assertEquals(mapOf("应用" to "微信"), vars)
    }

    @Test
    fun `extract returns empty when structure does not match`() {
        assertTrue(RoutineVariables.extract("给{人}发{内容}", "完全不同的一句话").isEmpty())
    }

    @Test
    fun `extract returns empty when template has no variables`() {
        assertTrue(RoutineVariables.extract("固定指令", "固定指令").isEmpty())
    }

    @Test
    fun `extract handles adjacent variables non-greedily`() {
        // 相邻变量靠中间字面量「发」切分
        val vars = RoutineVariables.extract("发{内容}给{人}", "发你好给你")
        assertEquals(mapOf("内容" to "你好", "人" to "你"), vars)
    }

    // ── substitute ───────────────────────────────────────
    @Test
    fun `substitute replaces all placeholders`() {
        val out = RoutineVariables.substitute("给{人}发{人}的{物}", mapOf("人" to "张三", "物" to "书"))
        assertEquals("给张三发张三的书", out)
    }

    @Test
    fun `substitute returns input unchanged when vars empty`() {
        assertEquals("给{人}发消息", RoutineVariables.substitute("给{人}发消息", emptyMap()))
    }

    @Test
    fun `substitute leaves unknown placeholders intact`() {
        assertEquals("给张三发{内容}", RoutineVariables.substitute("给{人}发{内容}", mapOf("人" to "张三")))
    }

    // ── round-trip ───────────────────────────────────────
    @Test
    fun `extract then substitute reconstructs the actual instruction`() {
        val template = "在{应用}里搜索{关键词}然后{动作}"
        val actual = "在淘宝里搜索耳机然后下单"
        val vars = RoutineVariables.extract(template, actual)
        assertEquals(actual, RoutineVariables.substitute(template, vars))
    }
}
