package com.apk.claw.android.octopus_mobile.browser

import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SystemWebViewEngine 单元测试.
 *
 * 覆盖：
 *  - createView() 返回 WebView 实例且配置正确
 *  - UA 伪装为桌面 Chrome
 *  - JS / DOM Storage / Cookie / 文件访问等设置项全开
 *  - navigate() / currentUrl() 状态同步
 *  - evaluateJs() / screenshot() 空视图保护
 *  - isAvailable() 始终 true
 *  - antiBotScore / supportsExtensions 常量
 *  - describe() 元信息结构
 *  - events() 返回非空 Flow
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SystemWebViewEngineTest {

    private lateinit var context: Context
    private lateinit var engine: SystemWebViewEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        engine = SystemWebViewEngine()
    }

    // ── 可用性与常量 ──────────────────────────────────────

    @Test
    fun `isAvailable always returns true`() {
        assertTrue(engine.isAvailable())
    }

    @Test
    fun `name is System WebView`() {
        assertEquals("System WebView", engine.name)
    }

    @Test
    fun `antiBotScore is 50`() {
        assertEquals(50, engine.antiBotScore)
    }

    @Test
    fun `supportsExtensions is false`() {
        assertFalse(engine.supportsExtensions)
    }

    @Test
    fun `supportsEval defaults to true`() {
        assertTrue(engine.supportsEval)
    }

    // ── createView 与配置 ─────────────────────────────────

    @Test
    fun `createView returns WebView instance`() {
        val view = engine.createView(context)
        assertTrue(view is WebView)
    }

    @Test
    fun `createView sets desktop Chrome UA`() {
        engine.createView(context)
        val info = engine.describe()
        assertTrue("UA should contain Windows NT", info.userAgent.contains("Windows NT 10.0"))
        assertTrue("UA should contain Chrome", info.userAgent.contains("Chrome/124"))
        assertTrue("UA should not contain Mobile", !info.userAgent.contains("Mobile"))
    }

    @Test
    fun `createView enables JavaScript`() {
        val view = engine.createView(context) as WebView
        assertTrue(view.settings.javaScriptEnabled)
    }

    @Test
    fun `createView enables DOM storage`() {
        val view = engine.createView(context) as WebView
        assertTrue(view.settings.domStorageEnabled)
    }

    @Test
    fun `createView enables database`() {
        val view = engine.createView(context) as WebView
        assertTrue(view.settings.databaseEnabled)
    }

    @Test
    fun `createView allows file access`() {
        val view = engine.createView(context) as WebView
        assertTrue(view.settings.allowFileAccess)
    }

    @Test
    fun `createView allows universal access from file URLs`() {
        val view = engine.createView(context) as WebView
        assertTrue(view.settings.allowUniversalAccessFromFileURLs)
    }

    @Test
    fun `createView disables media playback user gesture`() {
        val view = engine.createView(context) as WebView
        assertFalse(view.settings.mediaPlaybackRequiresUserGesture)
    }

    @Test
    fun `createView sets mixed content always allow`() {
        val view = engine.createView(context) as WebView
        assertEquals(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW, view.settings.mixedContentMode)
    }

    @Test
    fun `createView enables wide viewport`() {
        val view = engine.createView(context) as WebView
        assertTrue(view.settings.useWideViewPort)
        assertTrue(view.settings.loadWithOverviewMode)
    }

    // ── 导航 ──────────────────────────────────────────────

    @Test
    fun `navigate updates currentUrl`() {
        engine.createView(context)
        engine.navigate("https://example.com")
        assertEquals("https://example.com", engine.currentUrl())
    }

    @Test
    fun `navigate does not crash when no view`() {
        // 未调用 createView，activeWebView 为 null
        engine.navigate("https://example.com")
        assertEquals("https://example.com", engine.currentUrl())
    }

    // ── JS 执行 ───────────────────────────────────────────

    @Test
    fun `evaluateJs returns null when no view`() {
        var callbackResult: String? = "not-called"
        engine.evaluateJs("1+1") { callbackResult = it }
        assertNull(callbackResult)
    }

    // ── 截图 ──────────────────────────────────────────────

    @Test
    fun `screenshot returns null when no view`() {
        assertNull(engine.screenshot())
    }

    // ── 引擎信息 ──────────────────────────────────────────

    @Test
    fun `describe returns EngineInfo with correct fields`() {
        val info = engine.describe()
        assertEquals("System WebView", info.name)
        assertTrue("version should mention WebView", info.version.contains("WebView"))
        assertTrue("UA should contain Chrome", info.userAgent.contains("Chrome"))
        assertFalse(info.supportsExtensions)
        assertEquals(50, info.antiBotScore)
        assertTrue("notes should mention 兜底", info.notes.contains("兜底"))
    }

    // ── 事件流 ────────────────────────────────────────────

    @Test
    fun `events returns non null flow`() {
        val flow = engine.events()
        assertNotNull(flow)
    }

    // ── 多视图隔离 ────────────────────────────────────────

    @Test
    fun `second createView replaces active webview`() {
        val view1 = engine.createView(context)
        val view2 = engine.createView(context)
        assertNotSame(view1, view2)
    }
}
