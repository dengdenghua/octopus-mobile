package com.apk.claw.android.tool.impl

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 企业版 PM 工具(D①)单测:路由到正确端点 + 鉴权头 + 参数 + 未配置/错误处理。
 * 用 MockWebServer 模拟企业版,不需要真机/真实企业版。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PmTaskToolsTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun base(): String = server.url("/").toString().trimEnd('/')

    @Test
    fun `create_pm_task posts to correct endpoint with auth headers and body`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"t1"}"""))
        val tool =
            CreatePmTaskTool(
                baseUrl = { base() },
                token = { "tok-abc" },
                tenant = { "acme" },
                client = OkHttpClient(),
            )

        val r =
            tool.execute(
                mapOf("project_id" to "proj-9", "title" to "选型 BOM", "role" to "硬件"),
            )

        assertTrue(r.isSuccess)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/projects/proj-9/tasks", req.path)
        assertEquals("Bearer tok-abc", req.getHeader("Authorization"))
        assertEquals("acme", req.getHeader("X-Tenant-ID"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("选型 BOM"))
        assertTrue(body.contains("硬件"))
    }

    @Test
    fun `create_pm_task unconfigured returns error without network`() {
        val tool = CreatePmTaskTool(baseUrl = { "" })
        val r = tool.execute(mapOf("project_id" to "p", "title" to "t"))
        assertFalse(r.isSuccess)
        assertTrue((r.error ?: "").contains("未配置"))
    }

    @Test
    fun `list_pm_projects gets the projects endpoint`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""[{"id":"p1"}]"""))
        val tool =
            ListPmProjectsTool(baseUrl = { base() }, token = { "" }, tenant = { "" })

        val r = tool.execute(emptyMap())

        assertTrue(r.isSuccess)
        assertEquals("/api/v1/projects", server.takeRequest().path)
    }

    @Test
    fun `http error is surfaced`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("nope"))
        val tool = ListPmProjectsTool(baseUrl = { base() })
        val r = tool.execute(emptyMap())
        assertFalse(r.isSuccess)
        assertTrue((r.error ?: "").contains("401"))
    }
}
