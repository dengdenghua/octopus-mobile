package com.apk.claw.android.octopus_mobile.browser

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * GeckoViewEngine 单元测试.
 *
 * 覆盖：
 *  - isAvailable()（GeckoView 作为 implementation 依赖在 test classpath 上 → true）
 *  - BrowserEngineFactory.selectBest() 优先选择 GeckoView
 *  - describe() 元信息结构
 *  - antiBotScore / supportsExtensions / supportsEval 常量
 *  - events() 返回非空 Flow
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GeckoViewEngineTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    // ── isAvailable ───────────────────────────────────────

    @Test
    fun `isAvailable returns true when GeckoView on classpath`() {
        val engine = GeckoViewEngine()
        // GeckoView 是 implementation 依赖，单元测试 classpath 也包含它
        assertTrue("GeckoView should be available in unit test", engine.isAvailable())
    }

    // ── 引擎元信息 ─────────────────────────────────────────

    @Test
    fun `name is GeckoView`() {
        val engine = GeckoViewEngine()
        assertEquals("GeckoView", engine.name)
    }

    @Test
    fun `antiBotScore is 90`() {
        val engine = GeckoViewEngine()
        assertEquals(90, engine.antiBotScore)
    }

    @Test
    fun `supportsExtensions is true`() {
        val engine = GeckoViewEngine()
        assertTrue(engine.supportsExtensions)
    }

    @Test
    fun `supportsEval defaults to true`() {
        val engine = GeckoViewEngine()
        assertTrue(engine.supportsEval)
    }

    @Test
    fun `describe returns EngineInfo with correct fields`() {
        val engine = GeckoViewEngine()
        val info = engine.describe()
        assertEquals("GeckoView", info.name)
        assertTrue("version should not be blank", info.version.isNotBlank())
        assertTrue("UA should contain Firefox", info.userAgent.contains("Firefox"))
        assertTrue(info.supportsExtensions)
        assertEquals(90, info.antiBotScore)
        assertTrue("notes should mention WebExtension", info.notes.contains("WebExtension"))
    }

    // ── events ────────────────────────────────────────────

    @Test
    fun `events returns non null flow`() {
        val engine = GeckoViewEngine()
        val flow = engine.events()
        assertNotNull(flow)
    }

    // ── 导航（无真实 GeckoView 时只测状态不 crash）────────────

    @Test
    fun `navigate does not crash when no session`() {
        val engine = GeckoViewEngine()
        // activeSession 为 null，应静默返回
        engine.navigate("https://example.com")
        assertEquals("https://example.com", engine.currentUrl())
    }

    @Test
    fun `evaluateJs does not crash when no session`() {
        val engine = GeckoViewEngine()
        var callbackResult: String? = "not-called"
        engine.evaluateJs("1+1") { callbackResult = it }
        assertNull(callbackResult)
    }

    @Test
    fun `screenshot returns null when no view`() {
        val engine = GeckoViewEngine()
        assertNull(engine.screenshot())
    }

    // ── Factory 选择策略 ──────────────────────────────────

    @Test
    fun `selectBest prefers GeckoView when available`() {
        // GeckoView 在 classpath 上（implementation 依赖），应优先选择
        val best = BrowserEngineFactory.selectBest(context)
        assertTrue(
            "Expected GeckoViewEngine when available, got ${best.name}",
            best is GeckoViewEngine
        )
    }

    @Test
    fun `listAvailable always includes SystemWebView`() {
        val list = BrowserEngineFactory.listAvailable(context)
        val names = list.map { it.name }
        assertTrue("System WebView should always be available", names.contains("System WebView"))
    }

    @Test
    fun `listAvailable includes GeckoView only when available`() {
        val list = BrowserEngineFactory.listAvailable(context)
        val gecko = list.filterIsInstance<GeckoViewEngine>()
        if (gecko.isNotEmpty()) {
            assertTrue(gecko.first().isAvailable())
        }
        assertTrue("list should have at least 1 engine", list.size >= 1)
    }
}
