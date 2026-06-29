package com.apk.claw.android.server.routes

import com.apk.claw.android.tool.ToolParameter
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 离线 MCP 握手验证 —— 用 mock 工具后端在纯 JVM 里完整驱动 [McpServerCore]，
 * 覆盖 initialize / tools/list / tools/call(成功+失败+缺名) / ping / 未知方法。
 *
 * 这相当于一个跑在单测里的"mock MCP 客户端"，无需真机即可验证 JSON-RPC 信封与工具协议契约。
 */
class McpServerCoreTest {

    /** 两个假工具：tap(x,y 必填) 正常返回；boom 永远报错。 */
    private val provider = object : McpToolProvider {
        var lastCall: Pair<String, Map<String, Any>>? = null
        override fun list(): List<McpToolSpec> = listOf(
            McpToolSpec(
                "tap", "点击屏幕坐标",
                listOf(
                    ToolParameter("x", "integer", "x 坐标", true),
                    ToolParameter("y", "integer", "y 坐标", true),
                ),
            ),
            McpToolSpec("boom", "总是失败", emptyList()),
        )

        override fun call(name: String, args: Map<String, Any>): McpCallOutcome {
            lastCall = name to args
            return if (name == "boom") McpCallOutcome("炸了", isError = true)
            else McpCallOutcome("tapped ${args["x"]},${args["y"]}", isError = false)
        }
    }

    private fun dispatch(method: String, params: JsonObject = JsonObject()) =
        McpServerCore.dispatch(method, JsonPrimitive(1), params, provider, "octopus-mobile", "9.9.9")

    @Test
    fun `initialize returns protocol version and server info`() {
        val env = dispatch("initialize")
        assertEquals("2.0", env["jsonrpc"])
        @Suppress("UNCHECKED_CAST")
        val result = env["result"] as Map<String, Any>
        assertEquals(McpServerCore.PROTOCOL_VERSION, result["protocolVersion"])
        @Suppress("UNCHECKED_CAST")
        val serverInfo = result["serverInfo"] as Map<String, Any>
        assertEquals("octopus-mobile", serverInfo["name"])
        assertEquals("9.9.9", serverInfo["version"])
        assertTrue(result.containsKey("capabilities"))
    }

    @Test
    fun `tools list exposes tools with json schema`() {
        val env = dispatch("tools/list")
        @Suppress("UNCHECKED_CAST")
        val result = env["result"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val tools = result["tools"] as List<Map<String, Any>>
        assertEquals(2, tools.size)

        val tap = tools.first { it["name"] == "tap" }
        assertEquals("点击屏幕坐标", tap["description"])
        @Suppress("UNCHECKED_CAST")
        val schema = tap["inputSchema"] as Map<String, Any>
        assertEquals("object", schema["type"])
        @Suppress("UNCHECKED_CAST")
        val required = schema["required"] as List<String>
        assertEquals(listOf("x", "y"), required)
    }

    @Test
    fun `tools call routes args to backend and returns text content`() {
        val params = JsonObject().apply {
            addProperty("name", "tap")
            add("arguments", JsonObject().apply {
                addProperty("x", 100)
                addProperty("y", 200)
            })
        }
        val env = dispatch("tools/call", params)
        @Suppress("UNCHECKED_CAST")
        val result = env["result"] as Map<String, Any>
        assertEquals(false, result["isError"])
        @Suppress("UNCHECKED_CAST")
        val content = result["content"] as List<Map<String, Any>>
        assertEquals("text", content[0]["type"])
        assertEquals("tapped 100.0,200.0", content[0]["text"])
        // 后端确实收到了 tap + 参数
        assertEquals("tap", provider.lastCall?.first)
    }

    @Test
    fun `tools call surfaces tool error as result not jsonrpc error`() {
        val params = JsonObject().apply { addProperty("name", "boom") }
        val env = dispatch("tools/call", params)
        // MCP 语义：工具失败仍是成功的 JSON-RPC 响应，靠 result.isError 区分
        assertTrue(env.containsKey("result"))
        assertTrue(!env.containsKey("error"))
        @Suppress("UNCHECKED_CAST")
        val result = env["result"] as Map<String, Any>
        assertEquals(true, result["isError"])
    }

    @Test
    fun `tools call without name yields isError content`() {
        val env = dispatch("tools/call", JsonObject())
        @Suppress("UNCHECKED_CAST")
        val result = env["result"] as Map<String, Any>
        assertEquals(true, result["isError"])
        assertNull(provider.lastCall) // 没有真正调后端
    }

    @Test
    fun `ping returns empty result`() {
        val env = dispatch("ping")
        @Suppress("UNCHECKED_CAST")
        val result = env["result"] as Map<String, Any>
        assertTrue(result.isEmpty())
    }

    @Test
    fun `unknown method returns method not found error`() {
        val env = dispatch("does/not/exist")
        assertTrue(!env.containsKey("result"))
        @Suppress("UNCHECKED_CAST")
        val error = env["error"] as Map<String, Any>
        assertEquals(-32601.0, (error["code"] as Number).toDouble(), 0.0)
    }
}
