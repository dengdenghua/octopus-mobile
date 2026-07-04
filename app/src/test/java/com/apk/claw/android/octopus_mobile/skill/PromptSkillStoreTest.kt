package com.apk.claw.android.octopus_mobile.skill

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PromptSkillStore 的相关性注入(纯 JVM:KVUtils 未初始化时退回内存 map,可直接测)。
 */
class PromptSkillStoreTest {

    private fun mk(id: String, name: String, desc: String, enabled: Boolean = true) =
        PromptSkillStore.PromptSkill(id, name, desc, "步骤:...", enabled, 0L)

    @After
    fun cleanup() {
        PromptSkillStore.all().forEach { PromptSkillStore.delete(it.id) }
        // 清空 KVUtils 内存 fallback,避免本测试类写入的 stringFallback 泄漏到下一个测试类
        // (PromptSkillStore 通过 KVUtils.putString 持久化,落到 object 级静态 stringFallback)。
        com.apk.claw.android.utils.KVUtils.resetForTest()
    }

    @Test
    fun `no prompt injects all enabled`() {
        PromptSkillStore.add(mk("s1", "订会议", "当用户要预约会议时"))
        PromptSkillStore.add(mk("s2", "记账", "当用户要记一笔开销时"))
        val section = PromptSkillStore.buildPromptSection(null)
        assertTrue(section.contains("订会议"))
        assertTrue(section.contains("记账"))
    }

    @Test
    fun `prompt injects only relevant skill`() {
        PromptSkillStore.add(mk("s1", "订会议", "当用户要预约会议、安排日程时"))
        PromptSkillStore.add(mk("s2", "记账", "当用户要记一笔开销时"))
        val section = PromptSkillStore.buildPromptSection("帮我预约一个会议")
        assertTrue("命中的技能应注入", section.contains("订会议"))
        assertFalse("无关技能不应注入", section.contains("记账"))
    }

    @Test
    fun `prompt with no match injects nothing`() {
        PromptSkillStore.add(mk("s1", "订会议", "当用户要预约会议时"))
        val section = PromptSkillStore.buildPromptSection("今天天气怎么样")
        assertTrue("零命中应为空段", section.isEmpty())
    }

    @Test
    fun `disabled skill never injected`() {
        PromptSkillStore.add(mk("s1", "订会议", "预约会议", enabled = false))
        assertTrue(PromptSkillStore.buildPromptSection(null).isEmpty())
        assertTrue(PromptSkillStore.buildPromptSection("预约会议").isEmpty())
    }

    @Test
    fun `english keyword matches`() {
        PromptSkillStore.add(mk("s1", "invoice", "when the user asks to create an invoice"))
        assertTrue(PromptSkillStore.buildPromptSection("please make an invoice for me").contains("invoice"))
        assertTrue(PromptSkillStore.buildPromptSection("what's the weather").isEmpty())
    }
}
