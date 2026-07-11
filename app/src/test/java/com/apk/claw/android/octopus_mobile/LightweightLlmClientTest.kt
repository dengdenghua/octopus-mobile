package com.apk.claw.android.octopus_mobile

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * LightweightLlmClient 测试 —— 用 MockWebServer 模拟 OpenAI API.
 *
 * 覆盖：
 *  - 基础 chat 调用（解析 content / usage / finish_reason）
 *  - 工具调用响应（解析 tool_calls，arguments JSON）
 *  - 请求体格式（Authorization / model / tools）
 *  - 错误响应（HTTP 500 / 4xx）
 *  - 网络异常（关闭 server 模拟）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LightweightLlmClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: LightweightLlmClient

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        val baseUrl = server.url("/v1/").toString().trimEnd('/')
        val config = LlmConfig(
            apiUrl = "$baseUrl/chat/completions",
            apiKey = "test-key-123",
            model = "test-model",
            temperature = 0.5,
            maxTokens = 1024
        )
        client = LightweightLlmClient(
            config = config,
            httpClient = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build()
        )
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    // ── 基础 chat ────────────────────────────────────────────

    @Test
    fun `chat parses text response`() = runBlocking {
        enqueueOpenAiResponse(
            content = "Hello! How can I help?",
            finishReason = "stop",
            promptTokens = 10,
            completionTokens = 8,
        )
        val resp = client.chat(
            messages = listOf(ChatMessage.User(content = "hi")),
            skills = emptyList()
        )
        assertEquals("Hello! How can I help?", resp.content)
        assertEquals("stop", resp.finishReason)
        assertEquals(10, resp.usage!!.promptTokens)
        assertEquals(8, resp.usage!!.completionTokens)
        assertTrue(resp.toolCalls.isEmpty())
    }

    @Test
    fun `chat parses tool call response`() = runBlocking {
        enqueueOpenAiResponse(
            content = "",
            finishReason = "tool_calls",
            promptTokens = 100,
            completionTokens = 30,
            toolCalls = listOf(
                mapOf(
                    "id" to "call_1",
                    "name" to "android.tap",
                    "arguments" to """{"x": 540, "y": 1200}"""
                )
            )
        )
        val resp = client.chat(
            messages = listOf(ChatMessage.User(content = "tap 540,1200")),
            skills = listOf(skill("android.tap", "点击屏幕坐标"))
        )
        assertEquals("tool_calls", resp.finishReason)
        assertEquals(1, resp.toolCalls.size)
        val tc = resp.toolCalls[0]
        assertEquals("call_1", tc.id)
        assertEquals("android.tap", tc.name)
        assertEquals(540, (tc.args["x"] as Number).toInt())
        assertEquals(1200, (tc.args["y"] as Number).toInt())
    }

    @Test
    fun `chat handles empty arguments string`() = runBlocking {
        enqueueOpenAiResponse(
            content = "",
            finishReason = "tool_calls",
            toolCalls = listOf(
                mapOf("id" to "c1", "name" to "android.tap", "arguments" to "")
            )
        )
        val resp = client.chat(
            messages = listOf(ChatMessage.User(content = "tap")),
            skills = listOf(skill("android.tap", ""))
        )
        assertEquals(1, resp.toolCalls.size)
        assertEquals(0, resp.toolCalls[0].args.size)
    }

    // ── 请求体格式 ─────────────────────────────────────────

    @Test
    fun `request body includes auth and model`() = runBlocking {
        enqueueOpenAiResponse("ok", "stop")
        client.chat(listOf(ChatMessage.User(content = "hi")), emptyList())

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer test-key-123", request.getHeader("Authorization"))
        assertTrue("Content-Type should start with application/json",
            request.getHeader("Content-Type")?.startsWith("application/json") == true)

        val body = JSONObject(request.body.readUtf8())
        assertEquals("test-model", body.getString("model"))
        assertEquals(0.5, body.getDouble("temperature"), 0.001)
        assertEquals(1024, body.getInt("max_tokens"))
    }

    @Test
    fun `request body includes tools in OpenAI format`() = runBlocking {
        enqueueOpenAiResponse("ok", "stop")
        client.chat(
            listOf(ChatMessage.User(content = "hi")),
            listOf(
                skill(
                    id = "android.tap",
                    description = "点击屏幕坐标",
                    schema = JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("x", JSONObject().put("type", "integer"))
                            put("y", JSONObject().put("type", "integer"))
                        })
                    }
                )
            )
        )
        val body = JSONObject(server.takeRequest().body.readUtf8())
        val tools = body.getJSONArray("tools")
        assertEquals(1, tools.length())
        val tool = tools.getJSONObject(0).getJSONObject("function")
        assertEquals("android.tap", tool.getString("name"))
        assertEquals("点击屏幕坐标", tool.getString("description"))
        val params = tool.getJSONObject("parameters")
        assertEquals("object", params.getString("type"))
        assertEquals("auto", body.getString("tool_choice"))
    }

    @Test
    fun `request body serializes assistant tool_calls correctly`() = runBlocking {
        enqueueOpenAiResponse("ok", "stop")
        client.chat(
            messages = listOf(
                ChatMessage.User(content = "do something"),
                ChatMessage.Assistant(
                    content = "calling tool",
                    toolCalls = listOf(
                        ToolCall(id = "tc1", name = "android.tap", args = mapOf("x" to 1, "y" to 2))
                    )
                ),
                ChatMessage.Tool(toolCallId = "tc1", content = "ok"),
            ),
            skills = listOf(skill("android.tap", ""))
        )
        val body = JSONObject(server.takeRequest().body.readUtf8())
        val msgs = body.getJSONArray("messages")
        val assistantMsg = msgs.getJSONObject(1)
        assertEquals("assistant", assistantMsg.getString("role"))
        assertEquals("calling tool", assistantMsg.getString("content"))
        val tcArr = assistantMsg.getJSONArray("tool_calls")
        assertEquals(1, tcArr.length())
        val tc = tcArr.getJSONObject(0)
        assertEquals("tc1", tc.getString("id"))
        assertEquals("android.tap", tc.getJSONObject("function").getString("name"))
        // arguments 应是 JSON 字符串
        val argsStr = tc.getJSONObject("function").getString("arguments")
        val argsObj = JSONObject(argsStr)
        assertEquals(1, argsObj.getInt("x"))
        assertEquals(2, argsObj.getInt("y"))

        val toolMsg = msgs.getJSONObject(2)
        assertEquals("tool", toolMsg.getString("role"))
        assertEquals("tc1", toolMsg.getString("tool_call_id"))
    }

    // ── 错误响应 ─────────────────────────────────────────────

    @Test(expected = LlmException::class)
    fun `chat throws on HTTP 500`(): Unit = runBlocking {
        // HTTP 500 是 transient 错误,client 会重试 4 次(retryAttempts=3),每次都返回 500
        repeat(4) { server.enqueue(MockResponse().setResponseCode(500).setBody("server error")) }
        client.chat(listOf(ChatMessage.User(content = "x")), emptyList())
    }

    @Test(expected = LlmException::class)
    fun `chat throws on HTTP 401`(): Unit = runBlocking {
        // HTTP 401 非 transient 但仍会重试,需匹配 retryAttempts+1 个响应
        repeat(4) { server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"unauthorized"}}""")) }
        client.chat(listOf(ChatMessage.User(content = "x")), emptyList())
    }

    @Test
    fun `chat handles malformed response gracefully`() = runBlocking {
        // 200 成功响应不重试,但解析失败会重试,所以需要多个响应
        repeat(4) { server.enqueue(MockResponse().setBody("not json at all")) }
        try {
            client.chat(listOf(ChatMessage.User(content = "x")), emptyList())
            assert(false) { "should have thrown" }
        } catch (e: LlmException) {
            // LlmException 也包 JSONException - 接受任意 LlmException
        }
    }

    @Test
    fun `chat returns empty finish_reason on unknown value`() = runBlocking {
        // 服务器返回未知的 finish_reason（如 "sensitive"）→ fallback 到 STOP
        enqueueOpenAiResponse("hi", "sensitive_unmapped")
        val resp = client.chat(listOf(ChatMessage.User(content = "x")), emptyList())
        assertEquals("stop", resp.finishReason)
    }

    // ── 工具 ─────────────────────────────────────────────────

    private fun enqueueOpenAiResponse(
        content: String,
        finishReason: String,
        promptTokens: Int = 5,
        completionTokens: Int = 5,
        toolCalls: List<Map<String, Any>> = emptyList()
    ) {
        val msg = JSONObject().apply {
            put("role", "assistant")
            put("content", content)
            if (toolCalls.isNotEmpty()) {
                val arr = org.json.JSONArray()
                for ((i, tc) in toolCalls.withIndex()) {
                    val obj = JSONObject().apply {
                        put("id", tc["id"] ?: "call_$i")
                        put("type", "function")
                        put("function", JSONObject().apply {
                            put("name", tc["name"])
                            put("arguments", tc["arguments"])
                        })
                    }
                    arr.put(obj)
                }
                put("tool_calls", arr)
            }
        }
        val choice = JSONObject().apply {
            put("message", msg)
            put("finish_reason", finishReason)
            put("index", 0)
        }
        val response = JSONObject().apply {
            put("id", "chatcmpl-test")
            put("object", "chat.completion")
            put("created", 1234567890)
            put("model", "test-model")
            put("choices", org.json.JSONArray().put(choice))
            put("usage", JSONObject().apply {
                put("prompt_tokens", promptTokens)
                put("completion_tokens", completionTokens)
                put("total_tokens", promptTokens + completionTokens)
            })
        }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(response.toString())
        )
    }

    private fun skill(
        id: String,
        description: String,
        schema: JSONObject = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject())
        }
    ) = SkillSpec(
        id = id,
        description = description,
        parametersSchema = schema
    )
}
