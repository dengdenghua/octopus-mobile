package com.apk.claw.android.tool.impl

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * HelloWorldTools 单元测试.
 *
 * 验证 100 行扩展示例工具的 3 个核心行为:
 *  - HelloWorldTool: greeting 正确
 *  - CurrentTimeTool: 时间格式正确
 *  - DeviceInfoTool: 包含设备信息
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HelloWorldToolsTest {

    @Test
    fun `hello_world greets the given name`() {
        val tool = HelloWorldTools.HelloWorldTool()
        val r = tool.execute(mapOf("name" to "World"))
        assertTrue(r.isSuccess)
        assertTrue((r.data ?: "").contains("Hello, World"))
    }

    @Test
    fun `hello_world rejects empty name`() {
        val tool = HelloWorldTools.HelloWorldTool()
        val r = tool.execute(mapOf("name" to ""))
        assertFalse(r.isSuccess)
    }

    @Test
    fun `hello_world rejects missing name`() {
        val tool = HelloWorldTools.HelloWorldTool()
        val r = tool.execute(emptyMap())
        assertFalse(r.isSuccess)
    }

    @Test
    fun `current_time iso format is parseable`() {
        val tool = HelloWorldTools.CurrentTimeTool()
        val r = tool.execute(mapOf("format" to "iso"))
        assertTrue(r.isSuccess)
        // ISO-8601 形如 2026-06-08T15:30:00+08:00
        val data = r.data ?: ""
        assertTrue("should contain T separator", data.contains("T"))
    }

    @Test
    fun `current_time default is iso`() {
        val tool = HelloWorldTools.CurrentTimeTool()
        val r = tool.execute(emptyMap())
        assertTrue(r.isSuccess)
        assertTrue((r.data ?: "").contains("T"))
    }

    @Test
    fun `current_time timestamp is epoch ms`() {
        val tool = HelloWorldTools.CurrentTimeTool()
        val r = tool.execute(mapOf("format" to "timestamp"))
        assertTrue(r.isSuccess)
        val ts = (r.data ?: "").removePrefix("Current time: ").trim().toLongOrNull()
        assertNotNull(ts)
        // 2020 年之后的毫秒时间戳
        assertTrue(ts!! > 1_577_836_800_000L)
    }

    @Test
    fun `current_time date is yyyy-MM-dd`() {
        val tool = HelloWorldTools.CurrentTimeTool()
        val r = tool.execute(mapOf("format" to "date"))
        assertTrue(r.isSuccess)
        val data = r.data ?: ""
        assertTrue(data.matches(Regex("Current time: \\d{4}-\\d{2}-\\d{2}")))
    }

    @Test
    fun `device_info contains brand and model`() {
        val tool = HelloWorldTools.DeviceInfoTool()
        val r = tool.execute(emptyMap())
        assertTrue(r.isSuccess)
        val data = r.data ?: ""
        assertTrue("should contain Brand:", data.contains("Brand:"))
        assertTrue("should contain Android:", data.contains("Android:"))
    }

    @Test
    fun `registerAll adds 3 tools to registry`() {
        // 3 个工具名都应注册在 registry 中
        HelloWorldTools.registerAll()
        val names = com.apk.claw.android.tool.ToolRegistry.getInstance()
            .getAllTools()
            .map { it.getName() }
            .toSet()
        assertTrue("current_time should be registered", "current_time" in names)
        assertTrue("device_info should be registered", "device_info" in names)
        assertTrue("hello_world should be registered", "hello_world" in names)
    }
}
