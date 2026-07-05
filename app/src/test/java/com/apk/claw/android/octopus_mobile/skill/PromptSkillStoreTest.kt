package com.apk.claw.android.octopus_mobile.skill

import org.junit.After
import org.junit.Assert.assertEquals
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

    @Test
    fun `ensureSeeded seeds both builtins and they trigger on relevant prompts`() {
        PromptSkillStore.ensureSeeded()
        val names = PromptSkillStore.all().map { it.name }
        assertTrue("应种下产品设计工作流", names.contains("产品设计工作流"))
        assertTrue("应种下手机自动化编排", names.contains("手机自动化编排"))
        assertTrue(
            "设计 prompt 应命中设计技能",
            PromptSkillStore.buildPromptSection("帮我做个好看的页面").contains("产品设计工作流"),
        )
        assertTrue(
            "自动化 prompt 应命中手机技能",
            PromptSkillStore.buildPromptSection("打开微信点一下发送按钮").contains("手机自动化编排"),
        )
    }

    @Test
    fun `ensureSeeded is idempotent`() {
        PromptSkillStore.ensureSeeded()
        PromptSkillStore.ensureSeeded()
        assertEquals(1, PromptSkillStore.all().count { it.name == "产品设计工作流" })
    }

    @Test
    fun `design style library triggers on brand name`() {
        PromptSkillStore.ensureSeeded()
        assertTrue("应种下设计风格库", PromptSkillStore.all().any { it.name == "设计风格库" })
        val section = PromptSkillStore.buildPromptSection("帮我做个 vercel 风格的落地页")
        assertTrue("提到品牌名应命中风格库", section.contains("设计风格库"))
        assertTrue("命中后应带出该风格 token", section.contains("Geist"))
    }

    @Test
    fun `web scrape skill triggers on crawl intent`() {
        PromptSkillStore.ensureSeeded()
        assertTrue("应种下网页抓取", PromptSkillStore.all().any { it.name == "网页抓取" })
        val section = PromptSkillStore.buildPromptSection("帮我抓取这个网站的商品列表")
        assertTrue("抓取意图应命中网页抓取技能", section.contains("网页抓取"))
        assertTrue("命中后应带出先轻后重策略", section.contains("先轻后重"))
    }
}
