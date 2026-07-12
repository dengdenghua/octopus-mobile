package com.apk.claw.android.tool.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * EditFileTool 单元测试.
 *
 * 验证工具元信息(名称/参数/描述)以及路径安全守卫:
 *  - 工作空间/Download/Documents 之外的路径(含 `../` 穿越)必须被拒。
 * 实际文件读写分支由集成测试覆盖,这里只锁死前置守卫。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditFileToolTest {

    private val tool = EditFileTool()

    @Test
    fun `has correct name`() {
        assertEquals("edit_file", tool.getName())
    }

    @Test
    fun `has correct parameters`() {
        val params = tool.getParameters()
        val byName = params.associateBy { it.name }

        // 三个必填参数
        listOf("path", "old_text", "new_text").forEach { name ->
            val p = byName[name]
            assertTrue("missing required param: $name", p != null)
            assertEquals("$name should be required", true, p?.isRequired)
            assertEquals("$name should be string type", "string", p?.type)
        }
        // 可选参数
        val createIfMissing = byName["create_if_missing"]
        assertTrue("missing optional param: create_if_missing", createIfMissing != null)
        assertEquals(false, createIfMissing?.isRequired)
        assertEquals("boolean", createIfMissing?.type)
    }

    @Test
    fun `descriptions are not empty`() {
        assertTrue(tool.getDescriptionCN().isNotBlank())
        assertTrue(tool.getDescriptionEN().isNotBlank())
    }

    @Test
    fun `rejects path traversal`() {
        // `../` 穿越出 Download 后规范化为根目录下的路径,不在任何安全前缀内
        val r = tool.execute(mapOf(
            "path" to "/sdcard/Download/../../evil.txt",
            "old_text" to "x",
            "new_text" to "y",
        ))
        assertFalse("traversal path should be rejected", r.isSuccess)
        assertTrue(
            "should report access denied",
            (r.error ?: "").contains("Access denied"),
        )
    }

    @Test
    fun `rejects path outside workspace`() {
        // 系统目录,明确不在工作空间/Download/Documents 内
        val r = tool.execute(mapOf(
            "path" to "/system/build.prop",
            "old_text" to "x",
            "new_text" to "y",
        ))
        assertFalse(r.isSuccess)
        assertTrue((r.error ?: "").contains("Access denied"))
    }
}
