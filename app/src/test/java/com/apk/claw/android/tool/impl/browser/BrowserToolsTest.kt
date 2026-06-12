package com.apk.claw.android.tool.impl.browser

import com.apk.claw.android.octopus_mobile.browser.BrowserEngine
import com.apk.claw.android.octopus_mobile.browser.EngineEvent
import com.apk.claw.android.octopus_mobile.browser.EngineInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * BrowserTools 单元测试 —— 覆盖 7 个浏览器工具.
 *
 * 使用 StubBrowserEngine 模拟引擎行为，验证：
 *  - 工具名 / 显示名 / 参数定义正确
 *  - 正常执行路径返回 success
 *  - 缺失必需参数返回 error
 *  - 引擎异常时返回 error
 *  - supportsExtensions=false 时 InstallExtensionTool 拒绝执行
 */
class BrowserToolsTest {

    private lateinit var stubEngine: StubBrowserEngine

    @Before
    fun setUp() {
        stubEngine = StubBrowserEngine()
    }

    // ── NavigateTool ──────────────────────────────────────

    @Test
    fun `NavigateTool name and display name`() {
        val tool = NavigateTool(stubEngine)
        assertEquals("browser_navigate", tool.getName())
        assertEquals("浏览器导航", tool.getDisplayName())
    }

    @Test
    fun `NavigateTool requires url parameter`() {
        val tool = NavigateTool(stubEngine)
        val params = tool.getParameters()
        assertEquals(1, params.size)
        assertEquals("url", params[0].name)
        assertTrue(params[0].isRequired)
    }

    @Test
    fun `NavigateTool executes successfully`() {
        val tool = NavigateTool(stubEngine)
        val result = tool.execute(mapOf("url" to "https://example.com"))
        assertTrue(result.isSuccess)
        assertTrue(result.data!!.contains("Navigated to"))
        assertEquals("https://example.com", stubEngine.lastNavigatedUrl)
    }

    @Test
    fun `NavigateTool fails without url`() {
        val tool = NavigateTool(stubEngine)
        assertThrows(IllegalArgumentException::class.java) {
            tool.execute(emptyMap())
        }
    }

    // ── GetDomTool ────────────────────────────────────────

    @Test
    fun `GetDomTool name and parameters`() {
        val tool = GetDomTool(stubEngine)
        assertEquals("browser_get_dom", tool.getName())
        val params = tool.getParameters()
        assertEquals("selector", params[0].name)
        assertEquals("attribute", params[1].name)
        assertFalse(params[0].isRequired)
        assertFalse(params[1].isRequired)
    }

    @Test
    fun `GetDomTool executes JS and returns result`() {
        stubEngine.jsResult = "\"Hello World\""
        val tool = GetDomTool(stubEngine)
        val result = tool.execute(mapOf("selector" to "h1", "attribute" to "innerText"))
        assertTrue(result.isSuccess)
        assertEquals("Hello World", result.data)
    }

    @Test
    fun `GetDomTool returns error on timeout`() {
        stubEngine.jsDelayMs = 6000  // 超过 5 秒 deadline
        val tool = GetDomTool(stubEngine)
        val result = tool.execute(mapOf("selector" to "body"))
        assertFalse(result.isSuccess)
        assertTrue(result.error!!.contains("timeout"))
    }

    // ── BrowserClickTool ──────────────────────────────────

    @Test
    fun `BrowserClickTool name and requires selector`() {
        val tool = BrowserClickTool(stubEngine)
        assertEquals("browser_click", tool.getName())
        val params = tool.getParameters()
        assertEquals("selector", params[0].name)
        assertTrue(params[0].isRequired)
    }

    @Test
    fun `BrowserClickTool executes and returns clicked message`() {
        stubEngine.jsResult = "\"Clicked: #btn\""
        val tool = BrowserClickTool(stubEngine)
        val result = tool.execute(mapOf("selector" to "#btn"))
        assertTrue(result.isSuccess)
        assertEquals("Clicked: #btn", result.data)
    }

    @Test
    fun `BrowserClickTool fails without selector`() {
        val tool = BrowserClickTool(stubEngine)
        assertThrows(IllegalArgumentException::class.java) {
            tool.execute(emptyMap())
        }
    }

    // ── BrowserTypeTool ───────────────────────────────────

    @Test
    fun `BrowserTypeTool name and parameters`() {
        val tool = BrowserTypeTool(stubEngine)
        assertEquals("browser_type", tool.getName())
        val params = tool.getParameters()
        assertEquals("selector", params[0].name)
        assertEquals("text", params[1].name)
        assertEquals("submit", params[2].name)
        assertTrue(params[0].isRequired)
        assertTrue(params[1].isRequired)
        assertFalse(params[2].isRequired)
    }

    @Test
    fun `BrowserTypeTool types text without submit`() {
        stubEngine.jsResult = "\"Typed into: #input\""
        val tool = BrowserTypeTool(stubEngine)
        val result = tool.execute(mapOf("selector" to "#input", "text" to "hello", "submit" to false))
        assertTrue(result.isSuccess)
        assertEquals("Typed into: #input", result.data)
    }

    @Test
    fun `BrowserTypeTool types text with submit`() {
        stubEngine.jsResult = "\"Typed into: #input\""
        val tool = BrowserTypeTool(stubEngine)
        val result = tool.execute(mapOf("selector" to "#input", "text" to "hello", "submit" to true))
        assertTrue(result.isSuccess)
    }

    @Test
    fun `BrowserTypeTool escapes single quotes in text`() {
        stubEngine.jsResult = "\"Typed into: #input\""
        val tool = BrowserTypeTool(stubEngine)
        val result = tool.execute(mapOf("selector" to "#input", "text" to "it's working"))
        assertTrue(result.isSuccess)
    }

    // ── BrowserScreenshotTool ─────────────────────────────

    @Test
    fun `BrowserScreenshotTool name and parameters`() {
        val tool = BrowserScreenshotTool(stubEngine)
        assertEquals("browser_screenshot", tool.getName())
        val params = tool.getParameters()
        assertEquals("max_length", params[0].name)
        assertFalse(params[0].isRequired)
    }

    @Test
    fun `BrowserScreenshotTool returns full base64 when no limit`() {
        stubEngine.screenshotResult = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        val tool = BrowserScreenshotTool(stubEngine)
        val result = tool.execute(emptyMap())
        assertTrue(result.isSuccess)
        assertTrue(result.data!!.startsWith("Screenshot:"))
    }

    @Test
    fun `BrowserScreenshotTool truncates when max_length set`() {
        stubEngine.screenshotResult = "a".repeat(10000)
        val tool = BrowserScreenshotTool(stubEngine)
        val result = tool.execute(mapOf("max_length" to 100))
        assertTrue(result.isSuccess)
        assertTrue(result.data!!.contains("truncated"))
    }

    @Test
    fun `BrowserScreenshotTool returns error when screenshot fails`() {
        stubEngine.screenshotResult = null
        val tool = BrowserScreenshotTool(stubEngine)
        val result = tool.execute(emptyMap())
        assertFalse(result.isSuccess)
        assertTrue(result.error!!.contains("Screenshot failed"))
    }

    // ── BrowserEvaluateTool ───────────────────────────────

    @Test
    fun `BrowserEvaluateTool name and requires script`() {
        val tool = BrowserEvaluateTool(stubEngine)
        assertEquals("browser_evaluate", tool.getName())
        val params = tool.getParameters()
        assertEquals("script", params[0].name)
        assertTrue(params[0].isRequired)
    }

    @Test
    fun `BrowserEvaluateTool executes JS and returns result`() {
        stubEngine.jsResult = "42"
        val tool = BrowserEvaluateTool(stubEngine)
        val result = tool.execute(mapOf("script" to "1+1"))
        assertTrue(result.isSuccess)
        assertEquals("42", result.data)
    }

    @Test
    fun `BrowserEvaluateTool returns null as string`() {
        stubEngine.jsResult = null
        val tool = BrowserEvaluateTool(stubEngine)
        val result = tool.execute(mapOf("script" to "void(0)"))
        assertTrue(result.isSuccess)
        assertEquals("(null)", result.data)
    }

    @Test
    fun `BrowserEvaluateTool returns error on timeout`() {
        stubEngine.jsDelayMs = 15000  // 超过 10 秒 deadline
        val tool = BrowserEvaluateTool(stubEngine)
        val result = tool.execute(mapOf("script" to "while(true){}"))
        assertFalse(result.isSuccess)
        assertTrue(result.error!!.contains("timeout"))
    }

    // ── InstallExtensionTool ──────────────────────────────

    @Test
    fun `InstallExtensionTool name and parameters`() {
        val tool = InstallExtensionTool(stubEngine)
        assertEquals("browser_install_extension", tool.getName())
        val params = tool.getParameters()
        assertEquals("source", params[0].name)
        assertTrue(params[0].isRequired)
        assertEquals("name", params[1].name)
        assertFalse(params[1].isRequired)
    }

    @Test
    fun `InstallExtensionTool rejects when engine does not support extensions`() {
        stubEngine.supportsExtensionsValue = false
        val tool = InstallExtensionTool(stubEngine)
        val result = tool.execute(mapOf("source" to "cws:test-id"))
        assertFalse(result.isSuccess)
        assertTrue(result.error!!.contains("does not support extensions"))
    }

    @Test
    fun `InstallExtensionTool rejects non-GeckoView engine`() {
        // stubEngine 默认不是 GeckoViewEngine 实例
        val tool = InstallExtensionTool(stubEngine)
        val result = tool.execute(mapOf("source" to "cws:test-id"))
        assertFalse(result.isSuccess)
        assertTrue(result.error!!.contains("requires GeckoView"))
    }

    @Test
    fun `InstallExtensionTool fails on unknown source format`() {
        // 让 stubEngine 伪装成 supportsExtensions + is GeckoViewEngine
        stubEngine.supportsExtensionsValue = true
        stubEngine.isGeckoView = true
        val tool = InstallExtensionTool(stubEngine)
        val result = tool.execute(mapOf("source" to "invalid:format"))
        assertFalse(result.isSuccess)
        assertTrue(result.error!!.contains("Unknown source format"))
    }

    @Test
    fun `InstallExtensionTool fails without source`() {
        val tool = InstallExtensionTool(stubEngine)
        assertThrows(IllegalArgumentException::class.java) {
            tool.execute(emptyMap())
        }
    }

    // ── StubBrowserEngine ─────────────────────────────────

    class StubBrowserEngine : BrowserEngine {
        override val name = "StubEngine"
        override val antiBotScore = 50
        override var supportsExtensions: Boolean = true
            get() = supportsExtensionsValue
        override val supportsEval = true

        var supportsExtensionsValue = true
        var isGeckoView = false

        var lastNavigatedUrl: String = ""
        var jsResult: String? = null
        var jsDelayMs: Long = 0
        var screenshotResult: String? = null

        override fun createView(context: android.content.Context): android.view.View {
            throw NotImplementedError()
        }

        override fun isAvailable(): Boolean = true

        override fun describe(): EngineInfo = EngineInfo(
            name = name,
            version = "1.0",
            userAgent = "Stub/1.0",
            supportsExtensions = supportsExtensionsValue,
            antiBotScore = antiBotScore
        )

        override fun events(): Flow<EngineEvent> = emptyFlow()

        override fun navigate(url: String) {
            lastNavigatedUrl = url
        }

        override fun currentUrl(): String = lastNavigatedUrl

        override fun evaluateJs(script: String, callback: ((String?) -> Unit)?) {
            // 模拟真实引擎的异步回调：延迟在后台线程发生，
            // 否则同步 sleep 会让工具的超时等待循环永远观察不到超时
            if (jsDelayMs > 0) {
                Thread {
                    Thread.sleep(jsDelayMs)
                    callback?.invoke(jsResult)
                }.start()
            } else {
                callback?.invoke(jsResult)
            }
        }

        override fun screenshot(): String? = screenshotResult
    }
}
