package com.apk.claw.android.server.routes

import com.apk.claw.android.tool.ToolParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP 工具参数 → JSON Schema 映射单测。这是 tools/list 暴露给外部 MCP 客户端的契约，
 * type 映射错或 required 漏了，客户端就会传错参数。
 */
class McpSchemaTest {

    @Test
    fun `type mapping covers common kotlin-ish type names`() {
        assertEquals("integer", McpSchema.jsonSchemaType("integer"))
        assertEquals("integer", McpSchema.jsonSchemaType("int"))
        assertEquals("integer", McpSchema.jsonSchemaType("Long"))
        assertEquals("number", McpSchema.jsonSchemaType("double"))
        assertEquals("boolean", McpSchema.jsonSchemaType("Boolean"))
        assertEquals("array", McpSchema.jsonSchemaType("list"))
        assertEquals("object", McpSchema.jsonSchemaType("map"))
        assertEquals("string", McpSchema.jsonSchemaType("string"))
    }

    @Test
    fun `unknown type falls back to string`() {
        assertEquals("string", McpSchema.jsonSchemaType("whatever"))
        assertEquals("string", McpSchema.jsonSchemaType(""))
    }

    @Test
    fun `inputSchema builds object with properties and required`() {
        val schema = McpSchema.inputSchema(
            listOf(
                ToolParameter("x", "integer", "x 坐标", true),
                ToolParameter("y", "integer", "y 坐标", true),
                ToolParameter("wait_after", "integer", "等待毫秒", false),
            )
        )
        assertEquals("object", schema["type"])

        @Suppress("UNCHECKED_CAST")
        val props = schema["properties"] as Map<String, Any>
        assertEquals(setOf("x", "y", "wait_after"), props.keys)

        @Suppress("UNCHECKED_CAST")
        val xProp = props["x"] as Map<String, Any>
        assertEquals("integer", xProp["type"])
        assertEquals("x 坐标", xProp["description"])

        @Suppress("UNCHECKED_CAST")
        val required = schema["required"] as List<String>
        // 只有 isRequired=true 的进 required，可选的 wait_after 不进
        assertEquals(listOf("x", "y"), required)
        assertTrue("wait_after" !in required)
    }

    @Test
    fun `empty params yields empty properties and required`() {
        val schema = McpSchema.inputSchema(emptyList())
        assertEquals("object", schema["type"])

        @Suppress("UNCHECKED_CAST")
        val props = schema["properties"] as Map<String, Any>
        assertTrue(props.isEmpty())

        @Suppress("UNCHECKED_CAST")
        val required = schema["required"] as List<String>
        assertTrue(required.isEmpty())
    }
}
