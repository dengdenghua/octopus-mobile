package com.apk.claw.android.octopus_mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SkillManifest 解析器测试.
 *
 * 覆盖：
 *  - 30 个真实 SKILL.md 资产（assets/skills/mobile 目录下所有 .md）
 *  - 精简版（JSON Schema 单行）+ 长版（list-of-objects）两种格式
 *  - 错误输入（缺 name / 缺 frontmatter / 空文件）
 *  - 边界（description 块 / continuation / 极小文件）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SkillManifestTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    // ── 真实资产测试 ────────────────────────────────────────

    @Test
    fun `loadFromAssets returns 30 skills`() {
        val skills = SkillManifest.loadFromAssets(context, "skills/mobile")
        assertEquals("expected 30 SKILL.md, got ${skills.size}", 30, skills.size)
    }

    @Test
    fun `loadFromAssets has no duplicate names`() {
        val skills = SkillManifest.loadFromAssets(context, "skills/mobile")
        val names = skills.map { it.id }
        val unique = names.toSet()
        assertEquals(
            "duplicate skill names: ${names.groupBy { it }.filter { it.value.size > 1 }.keys}",
            unique.size, names.size
        )
    }

    @Test
    fun `loadFromAssets includes core 8 skills`() {
        val skills = SkillManifest.loadFromAssets(context, "skills/mobile")
        val names = skills.map { it.id }.toSet()
        val core = setOf(
            "android.tap", "android.swipe", "android.input_text",
            "android.open_app", "android.finish", "android.get_screen_info",
            "android.take_screenshot", "android.wait"
        )
        assertTrue("missing core: ${core - names}", core.all { it in names })
    }

    @Test
    fun `loadFromAssets includes all 30 expected skills`() {
        val skills = SkillManifest.loadFromAssets(context, "skills/mobile")
        val names = skills.map { it.id }.toSet()
        val required = setOf(
            // 基础操作
            "android.tap", "android.swipe", "android.input_text",
            "android.long_press", "android.system_key",
            // 屏幕感知
            "android.get_screen_info", "android.take_screenshot",
            "android.find_node", "android.find_text",
            // 应用管理
            "android.open_app", "android.install_app",
            "android.get_installed_apps", "android.wait",
            // 智能复合
            "android.scroll_to_find", "android.detect_dialog",
            "android.find_and_tap", "android.get_current_app",
            // 任务控制
            "android.finish", "android.fail",
            // 浏览器
            "android.browser.navigate", "android.browser.get_dom",
            "android.browser.click", "android.browser.type",
            "android.browser.screenshot", "android.browser.evaluate",
            "android.browser.install_extension",
            // 文件/剪贴板
            "android.read_file", "android.write_file",
            "android.get_clipboard", "android.set_clipboard",
        )
        val missing = required - names
        assertTrue("missing: $missing", missing.isEmpty())
    }

    @Test
    fun `all skills have non-empty description`() {
        val skills = SkillManifest.loadFromAssets(context, "skills/mobile")
        for (s in skills) {
            assertTrue("skill ${s.id} has empty description", s.description.isNotBlank())
        }
    }

    @Test
    fun `all skills have non-empty parameters_schema`() {
        val skills = SkillManifest.loadFromAssets(context, "skills/mobile")
        for (s in skills) {
            assertNotNull("skill ${s.id} has null parameters_schema", s.parametersSchema)
            assertEquals("object", s.parametersSchema.getString("type"))
            assertNotNull("skill ${s.id} missing properties", s.parametersSchema.optJSONObject("properties"))
        }
    }

    @Test
    fun `android_tap has correct schema`() {
        val skills = SkillManifest.loadFromAssets(context, "skills/mobile")
        val tap = skills.first { it.id == "android.tap" }
        val props = tap.parametersSchema.getJSONObject("properties")
        assertNotNull(props.getJSONObject("x"))
        assertNotNull(props.getJSONObject("y"))
        val required = tap.parametersSchema.getJSONArray("required")
        val reqList = (0 until required.length()).map { required.getString(it) }
        assertTrue("required should include x", "x" in reqList)
        assertTrue("required should include y", "y" in reqList)
    }

    // ── 解析器单文件测试 ────────────────────────────────────

    @Test
    fun `parseFrontmatter handles concise JSON Schema format`() {
        val text = """
            ---
            name: android.test
            description: 测试工具
            risk: low
            timeout_ms: 15000
            parameters: {"type": "object", "properties": {"x": {"type": "integer"}}, "required": ["x"]}
            ---
        """.trimIndent()
        val spec = SkillManifest.parseFrontmatter(text)
        assertNotNull(spec)
        assertEquals("android.test", spec!!.id)
        assertEquals("测试工具", spec.description)
        assertEquals("integer", spec.parametersSchema.getJSONObject("properties").getJSONObject("x").getString("type"))
    }

    @Test
    fun `parseFrontmatter handles long-form list-of-objects format`() {
        val text = """
            ---
            name: android.longform
            description: |
              Long description
              second line
            parameters:
              - name: url
                type: string
                required: true
                description: URL to navigate
            ---
        """.trimIndent()
        val spec = SkillManifest.parseFrontmatter(text)
        assertNotNull(spec)
        assertEquals("android.longform", spec!!.id)
        assertTrue("desc should merge pipe block", spec.description.contains("Long description"))
        val required = spec.parametersSchema.getJSONArray("required")
        assertEquals("url", required.getString(0))
    }

    @Test
    fun `parseFrontmatter returns null for missing frontmatter`() {
        assertNull(SkillManifest.parseFrontmatter("no frontmatter here"))
    }

    @Test
    fun `parseFrontmatter returns null for missing name`() {
        val text = "---\ndescription: 缺 name\n---\n"
        assertNull(SkillManifest.parseFrontmatter(text))
    }

    @Test
    fun `parseFrontmatter handles description continuation`() {
        val text = """
            ---
            name: android.cont
            description: 一句话
              后续缩进行
              再一行
            parameters: {"type": "object", "properties": {}}
            ---
        """.trimIndent()
        val spec = SkillManifest.parseFrontmatter(text)
        assertNotNull(spec)
        // continuation 应被合并到 description
        assertTrue(spec!!.description.contains("后续缩进行"))
    }

    @Test
    fun `parseFrontmatter handles empty parameters`() {
        val text = """
            ---
            name: android.noparams
            description: 无参数
            parameters: {"type": "object", "properties": {}}
            ---
        """.trimIndent()
        val spec = SkillManifest.parseFrontmatter(text)
        assertNotNull(spec)
        val props = spec!!.parametersSchema.getJSONObject("properties")
        assertEquals(0, props.length())
    }

    @Test
    fun `parseFrontmatter handles malformed JSON schema gracefully`() {
        val text = """
            ---
            name: android.bad
            description: 参数 JSON 坏
            parameters: {not valid json
            ---
        """.trimIndent()
        val spec = SkillManifest.parseFrontmatter(text)
        assertNotNull("should fallback to empty schema, not return null", spec)
        assertEquals("object", spec!!.parametersSchema.getString("type"))
    }

    // ── toJsonForRemote ─────────────────────────────────────

    @Test
    fun `toJsonForRemote serializes correctly`() {
        val skills = listOf(
            SkillSpec(id = "android.a", description = "first", parametersSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }),
            SkillSpec(id = "android.b", description = "second", parametersSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            })
        )
        val json = SkillManifest.toJsonForRemote(skills)
        assertTrue(json.contains("\"id\":\"android.a\""))
        assertTrue(json.contains("\"description\":\"first\""))
        assertTrue(json.contains("\"id\":\"android.b\""))
    }
}
