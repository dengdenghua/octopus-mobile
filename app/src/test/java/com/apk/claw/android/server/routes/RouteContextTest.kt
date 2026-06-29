package com.apk.claw.android.server.routes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.apk.claw.android.TestClawApplication
import com.google.gson.Gson
import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestClawApplication::class, sdk = [34])
class RouteContextTest {

    private lateinit var context: Context
    private lateinit var routeContext: RouteContext
    private val gson = Gson()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        routeContext = RouteContext(context, gson)
    }

    // ==================== 脱敏 ====================

    @Test
    fun `maskSecret hides all but last 4 characters`() {
        assertEquals("******abcd", routeContext.maskSecret("123456abcd"))
    }

    @Test
    fun `maskSecret returns short secrets unchanged`() {
        assertEquals("1234", routeContext.maskSecret("1234"))
        assertEquals("abc", routeContext.maskSecret("abc"))
    }

    @Test
    fun `maskSecret returns empty for empty input`() {
        assertEquals("", routeContext.maskSecret(""))
    }

    @Test
    fun `isMaskedValue detects masked strings`() {
        assertTrue(routeContext.isMaskedValue("******abcd"))
        assertFalse(routeContext.isMaskedValue("123456abcd"))
    }

    // ==================== 路径白名单（安全关键） ====================

    @Test
    fun `isAllowedUserPath accepts sdcard paths`() {
        assertTrue(routeContext.isAllowedUserPath("/sdcard"))
        assertTrue(routeContext.isAllowedUserPath("/sdcard/Download"))
        assertTrue(routeContext.isAllowedUserPath("/sdcard/Download/file.txt"))
    }

    @Test
    fun `isAllowedUserPath rejects path traversal`() {
        assertFalse(routeContext.isAllowedUserPath("/sdcard/../data/data/com.example"))
        assertFalse(routeContext.isAllowedUserPath("/sdcard/Download/../../data"))
    }

    @Test
    fun `isAllowedUserPath rejects private app directories`() {
        assertFalse(routeContext.isAllowedUserPath("/data/data/com.example"))
        assertFalse(routeContext.isAllowedUserPath("/data/data/com.example/shared_prefs"))
    }

    @Test
    fun `isAllowedUserPath rejects shell injection characters`() {
        assertFalse(routeContext.isAllowedUserPath("/sdcard/Download;rm -rf /"))
        assertFalse(routeContext.isAllowedUserPath("/sdcard/Download/$(id)"))
        assertFalse(routeContext.isAllowedUserPath("/sdcard/Download/`id`"))
    }

    @Test
    fun `isAllowedUserPath rejects paths outside sdcard`() {
        assertFalse(routeContext.isAllowedUserPath("/system/etc/hosts"))
        assertFalse(routeContext.isAllowedUserPath("/storage/emulated/0/Download"))
    }

    // ==================== JSON 安全转换 ====================

    @Test
    fun `jsonToSafeMap redacts specified keys`() {
        val json = JsonObject().apply {
            addProperty("username", "alice")
            addProperty("password", "secret123")
            addProperty("age", 30)
        }
        val map = routeContext.jsonToSafeMap(json, setOf("password"))

        assertEquals("alice", map["username"])
        assertEquals("<redacted>", map["password"])
        assertEquals("30", map["age"])
    }

    @Test
    fun `jsonToSafeMap handles nested objects as strings`() {
        val json = JsonObject().apply {
            addProperty("action", "tap")
            add("coords", JsonObject().apply {
                addProperty("x", 100)
                addProperty("y", 200)
            })
        }
        val map = routeContext.jsonToSafeMap(json)
        assertEquals("tap", map["action"])
        assertTrue((map["coords"] as String).contains("100"))
    }

    // ==================== CORS ====================

    @Test
    fun `corsResponse adds required headers`() {
        val response = NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK, "text/plain", "ok"
        )
        val cors = routeContext.corsResponse(response)

        assertEquals("*", cors.getHeader("Access-Control-Allow-Origin"))
        assertNotNull(cors.getHeader("Access-Control-Allow-Methods"))
        assertNotNull(cors.getHeader("Access-Control-Allow-Headers"))
    }

    // ==================== 来源提取 ====================

    @Test
    fun `sourceOf prefers real remoteIpAddress`() {
        val session = mock(NanoHTTPD.IHTTPSession::class.java)
        `when`(session.remoteIpAddress).thenReturn("192.168.1.5")
        `when`(session.headers).thenReturn(mapOf("x-forwarded-for" to "203.0.113.1"))
        assertEquals("192.168.1.5", routeContext.sourceOf(session))
    }

    @Test
    fun `sourceOf falls back to remote-addr header`() {
        val session = mock(NanoHTTPD.IHTTPSession::class.java)
        `when`(session.remoteIpAddress).thenReturn(null)
        `when`(session.headers).thenReturn(mapOf("remote-addr" to "192.168.1.5"))
        assertEquals("192.168.1.5", routeContext.sourceOf(session))
    }

    @Test
    fun `sourceOf returns unknown when no remote ip available`() {
        val session = mock(NanoHTTPD.IHTTPSession::class.java)
        `when`(session.remoteIpAddress).thenReturn(null)
        `when`(session.headers).thenReturn(emptyMap())
        assertEquals("unknown", routeContext.sourceOf(session))
    }
}
