@file:Suppress("PackageNaming")   // 沿用既有 octopus_mobile 包(带下划线)

package com.apk.claw.android.octopus_mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KnowledgeBundle 测试 —— 纯 JVM。round-trip 保真、过滤空项、容错解析。
 */
class KnowledgeBundleTest {

    @Test
    fun `export then parse roundtrips rules and memories`() {
        val rules = listOf("打开淘宝先关弹窗", "别点广告")
        val mems = listOf(
            KnowledgeBundle.MemItem("我用饿了么点外卖", "PREFERENCE"),
            KnowledgeBundle.MemItem("我住朝阳区", "FACT"),
        )
        val json = KnowledgeBundle.export(rules, mems)
        assertTrue(KnowledgeBundle.looksValid(json))

        val parsed = KnowledgeBundle.parse(json)
        assertEquals(rules, parsed.rules)
        assertEquals(2, parsed.memories.size)
        assertEquals("我用饿了么点外卖", parsed.memories[0].content)
        assertEquals("PREFERENCE", parsed.memories[0].type)
    }

    @Test
    fun `export filters blank entries`() {
        val json = KnowledgeBundle.export(
            listOf("有效规矩", "   ", ""),
            listOf(KnowledgeBundle.MemItem("有效记忆", "FACT"), KnowledgeBundle.MemItem("  ", "FACT")),
        )
        val parsed = KnowledgeBundle.parse(json)
        assertEquals(1, parsed.rules.size)
        assertEquals(1, parsed.memories.size)
    }

    @Test
    fun `parse garbage returns empty and does not throw`() {
        val parsed = KnowledgeBundle.parse("这不是 JSON {{{")
        assertTrue(parsed.rules.isEmpty())
        assertTrue(parsed.memories.isEmpty())
        assertFalse(KnowledgeBundle.looksValid("random text"))
    }

    @Test
    fun `parse tolerates missing fields`() {
        val parsed = KnowledgeBundle.parse("""{"app":"octopus","version":1}""")
        assertTrue(parsed.rules.isEmpty())
        assertTrue(parsed.memories.isEmpty())
        assertTrue(KnowledgeBundle.looksValid("""{"app":"octopus","version":1}"""))
    }
}
