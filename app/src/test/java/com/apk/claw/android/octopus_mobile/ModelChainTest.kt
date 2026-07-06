@file:Suppress("PackageNaming")   // 沿用既有 octopus_mobile 包(带下划线)

package com.apk.claw.android.octopus_mobile

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * ModelChain 测试 —— primary 成功不转移 / primary 连续失败自动故障转移到 fallback。
 *
 * 用 MockWebServer 起真实 HTTP:两个端点同 baseUrl、不同 model 名,按 FIFO 消费入队响应。
 */
class ModelChainTest {

    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun endpoint(model: String) =
        ModelChain.ModelEndpoint(server.url("/v1").toString(), "test-key", model)

    @Test
    fun `primary success returns content without failover`() {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"PRIMARY_OK"}}]}"""))
        val chain = ModelChain(endpoint("model-a"), emptyList(), http)
        val out = chain.call("hi", temperature = 0.0, retriesPerEndpoint = 0, baseRetryMs = 1)
        assertEquals("PRIMARY_OK", out)
    }

    @Test
    fun `fails over to fallback when primary keeps 5xx`() {
        // primary 连续 500(retriesPerEndpoint=2 → 3 次尝试),然后 fallback 返回 200
        repeat(3) { server.enqueue(MockResponse().setResponseCode(500).setBody("upstream error")) }
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"FALLBACK_OK"}}]}"""))
        val chain = ModelChain(endpoint("model-a"), listOf(endpoint("model-b")), http)
        val out = chain.call("hi", temperature = 0.0, retriesPerEndpoint = 2, baseRetryMs = 1)
        assertEquals("FALLBACK_OK", out)
    }
}
