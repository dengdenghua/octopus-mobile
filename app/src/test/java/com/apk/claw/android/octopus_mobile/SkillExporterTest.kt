package com.apk.claw.android.octopus_mobile

import com.apk.claw.android.tool.ToolRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SkillExporter 单元测试.
 *
 * 覆盖：
 *  - 导出 34 个 MOBILE 工具的 SKILL.md
 *  - 工具名前缀 android.
 *  - 风险等级（low/medium/high）
 *  - 参数 JSON Schema 正确性
 *  - 与 SkillManifest 双向兼容（能解析自己生成的）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SkillExporterTest {

    @Before
    fun setUp() {
        // 清掉其他测试类可能遗留的 browserEngine（ToolRegistry 是单例），
        // 否则 registerAllTools 会附带注册 7 个 browser 工具导致数量不确定
        ToolRegistry.clearBrowserEngine()
        ToolRegistry.registerAllTools(ToolRegistry.DeviceType.MOBILE)
    }

    @Test
    fun `exportAllFromRegistry returns 34 skills for MOBILE`() {
        val skills = SkillExporter.exportAllFromRegistry()
        assertEquals("expected 34 tools for MOBILE", 34, skills.size)
    }

    @Test
    fun `each skill name has android prefix`() {
        val skills = SkillExporter.exportAllFromRegistry()
        for ((name, _) in skills) {
            assertTrue("name=$name should start with android.", name.startsWith("android."))
        }
    }

    @Test
    fun `each skill has frontmatter with name description risk timeout parameters`() {
        val skills = SkillExporter.exportAllFromRegistry()
        for ((name, content) in skills) {
            assertTrue("missing ---\n$content", content.startsWith("---"))
            assertTrue("missing second ---\n$content", content.contains("\n---\n"))
            assertTrue("missing name: $name\n$content", content.contains("name: $name"))
            assertTrue("missing description in $name\n$content", content.contains("description:"))
            assertTrue("missing risk in $name\n$content", content.contains("risk:"))
            assertTrue("missing timeout_ms in $name\n$content", content.contains("timeout_ms:"))
            assertTrue("missing parameters in $name\n$content", content.contains("parameters:"))
        }
    }

    @Test
    fun `risk is one of low medium high`() {
        val skills = SkillExporter.exportAllFromRegistry()
        for ((name, content) in skills) {
            val riskLine = content.lines().first { it.startsWith("risk:") }
            val risk = riskLine.substringAfter(":").trim()
            assertTrue(
                "skill=$name has invalid risk=$risk",
                risk in setOf("low", "medium", "high"),
            )
        }
    }

    @Test
    fun `send_sms is high risk`() {
        val skills = SkillExporter.exportAllFromRegistry()
        val sendSms = skills.firstOrNull { it.first == "android.send_sms" }
        assertNotNull("send_sms should be exported", sendSms)
        val content = sendSms!!.second
        assertTrue("send_sms should be high risk:\n$content", content.contains("risk: high"))
    }

    @Test
    fun `finish is low risk`() {
        val skills = SkillExporter.exportAllFromRegistry()
        val finish = skills.firstOrNull { it.first == "android.finish" }
        assertNotNull("finish should be exported", finish)
        assertTrue("finish should be low risk", finish!!.second.contains("risk: low"))
    }

    @Test
    fun `parameters JSON is parseable`() {
        val skills = SkillExporter.exportAllFromRegistry()
        for ((name, content) in skills) {
            val paramsLine = content.lines().first { it.trimStart().startsWith("parameters:") }
            val json = paramsLine.substringAfter("parameters:").trim()
            try {
                val obj = org.json.JSONObject(json)
                assertEquals("object", obj.getString("type"))
                assertTrue("properties missing in $name", obj.has("properties"))
            } catch (e: Exception) {
                fail("invalid JSON in $name: $json\n$e")
            }
        }
    }

    @Test
    fun `SkillManifest can parse exported SKILL md`() {
        val skills = SkillExporter.exportAllFromRegistry()
        // 抽查 finish 工具 —— 它必须能被 SkillManifest 解析回来
        val finishMd = skills.first { it.first == "android.finish" }.second
        val spec = SkillManifest.parseFrontmatter(finishMd)
        assertNotNull("SkillManifest failed to parse:\n$finishMd", spec)
        assertEquals("android.finish", spec!!.id)
        assertTrue("description should be non-empty", spec.description.isNotEmpty())
    }
}
