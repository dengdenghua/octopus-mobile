package com.apk.claw.android.tool.impl.browser

import com.apk.claw.android.octopus_mobile.browser.BrowserEngine
import com.apk.claw.android.octopus_mobile.safety.UrlGuard
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.XLog

/**
 * WebView.evaluateJavascript 的回调返回 JSON 编码值（字符串带双引号、转义）。
 * 把 JSON 字符串还原为原始文本；非字符串（数字/对象/null 字面量）原样返回。
 */
private fun unwrapJsString(value: String?): String? {
    if (value == null) return null
    val trimmed = value.trim()
    if (trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
        return try {
            org.json.JSONTokener(trimmed).nextValue() as? String ?: value
        } catch (e: Exception) {
            XLog.w("BrowserTools", "unwrapJsString failed: $value", e)
            value
        }
    }
    return value
}

/**
 * 浏览器导航工具 —— 在内嵌浏览器中打开 URL.
 *
 * agent 调用：android.browser.navigate({"url": "https://example.com"})
 */
class BrowserNavigateTool(
    private val engine: BrowserEngine
) : BaseTool() {

    override fun getName() = "browser_navigate"
    override fun getDisplayName() = "浏览器导航"

    override fun getDescriptionEN() = "Navigate the embedded browser to a URL."
    override fun getDescriptionCN() = "在内嵌浏览器中导航到指定 URL。"

    override fun getParameters() = listOf(
        ToolParameter("url", "string", "The URL to navigate to", true),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val url = requireString(params, "url")
        // 仅允许 http/https：拦截 file:// / javascript: / data: / content: / intent: 等
        // 危险 scheme（本地文件读取、UXSS、Intent 跳转、SSRF 入口）。
        val lower = url.trim().lowercase()
        val blockedSchemes = listOf(
            "file:", "javascript:", "data:", "content:", "intent:",
            "about:", "blob:", "ftp:", "ws:", "wss:", "jar:", "resource:",
        )
        if (blockedSchemes.any { lower.startsWith(it) }) {
            return ToolResult.error("Blocked unsafe URL scheme; only http/https are allowed.")
        }
        // SSRF 防护：阻止内网 IP / 云元数据端点 / 本地域名
        val urlVerdict = UrlGuard.check(url)
        if (!urlVerdict.allow) {
            return ToolResult.error("URL blocked by SSRF guard: ${urlVerdict.reason}")
        }
        engine.navigate(url)
        return ToolResult.success("Navigated to: $url")
    }
}

/**
 * 获取 DOM 工具 —— 执行 JS 返回页面内容.
 */
class GetDomTool(
    private val engine: BrowserEngine
) : BaseTool() {

    override fun getName() = "browser_get_dom"
    override fun getDisplayName() = "获取页面DOM"

    override fun getDescriptionEN() = "Get the current page DOM content via JavaScript."
    override fun getDescriptionCN() = "通过 JavaScript 获取当前页面的 DOM 内容。"

    override fun getParameters() = listOf(
        ToolParameter("selector", "string", "CSS selector to extract (default: 'body')", false),
        ToolParameter("attribute", "string", "Attribute to extract (default: 'innerText')", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val selector = optionalString(params, "selector", "body")
        val attribute = optionalString(params, "attribute", "innerText")

        var result: String? = null
        var completed = false

        val selJson = org.json.JSONObject.quote(selector)
        val attrJson = org.json.JSONObject.quote(attribute)
        val script = """
            (function() {
                var sel = $selJson;
                var el = document.querySelector(sel);
                if (!el) return 'Element not found: ' + sel;
                return el[$attrJson] || el.outerHTML;
            })()
        """.trimIndent()

        engine.evaluateJs(script) { value ->
            result = unwrapJsString(value)
            completed = true
        }

        val deadline = System.currentTimeMillis() + 5000
        while (!completed && System.currentTimeMillis() < deadline) {
            if (!sleepInterruptible(50)) return ToolResult.error("Task cancelled during DOM query")
        }

        val finalResult = result
        return if (completed && finalResult != null) {
            ToolResult.success(finalResult)
        } else {
            ToolResult.error("Failed to get DOM (timeout or engine error)")
        }
    }
}

/**
 * 点击工具 —— 在页面中点击指定元素.
 */
class BrowserClickTool(
    private val engine: BrowserEngine
) : BaseTool() {

    override fun getName() = "browser_click"
    override fun getDisplayName() = "浏览器点击"

    override fun getDescriptionEN() = "Click an element in the browser by CSS selector. Verifies element exists and checks post-click state."
    override fun getDescriptionCN() = "通过 CSS 选择器在浏览器中点击元素。会验证元素存在并检查点击后状态变化。"

    override fun getParameters() = listOf(
        ToolParameter("selector", "string", "CSS selector of the element to click", true),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val selector = requireString(params, "selector")

        val selJson = org.json.JSONObject.quote(selector)

        val beforeUrl = engine.currentUrl()

        var result: String? = null
        var completed = false

        val script = """
            (function() {
                var sel = $selJson;
                var el = document.querySelector(sel);
                if (!el) return 'ERROR: Element not found: ' + sel;
                var tag = el.tagName;
                var text = (el.innerText || el.value || '').substring(0, 50);
                el.click();
                return 'OK: Clicked <' + tag + '> \"' + text + '\"';
            })()
        """.trimIndent()

        engine.evaluateJs(script) { value ->
            result = unwrapJsString(value)
            completed = true
        }

        val deadline = System.currentTimeMillis() + 3000
        while (!completed && System.currentTimeMillis() < deadline) {
            if (!sleepInterruptible(50)) return ToolResult.error("Task cancelled during click")
        }

        val finalResult = result ?: return ToolResult.error("Click failed (timeout)")

        if (finalResult.startsWith("ERROR:")) {
            return ToolResult.error(finalResult.removePrefix("ERROR:").trim())
        }

        sleepInterruptible(300)
        val afterUrl = engine.currentUrl()
        val urlChanged = afterUrl != beforeUrl && afterUrl.isNotEmpty()

        return if (urlChanged) {
            ToolResult.success("$finalResult (page navigated to: $afterUrl)")
        } else {
            ToolResult.success("$finalResult")
        }
    }
}

/**
 * 输入文本工具 —— 在页面输入框中输入文本.
 */
class BrowserTypeTool(
    private val engine: BrowserEngine
) : BaseTool() {

    override fun getName() = "browser_type"
    override fun getDisplayName() = "浏览器输入"

    override fun getDescriptionEN() = "Type text into an input element in the browser."
    override fun getDescriptionCN() = "在浏览器的输入框中输入文本。"

    override fun getParameters() = listOf(
        ToolParameter("selector", "string", "CSS selector of the input element", true),
        ToolParameter("text", "string", "Text to type into the element", true),
        ToolParameter("submit", "boolean", "Whether to press Enter after typing (default: false)", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val selector = requireString(params, "selector")
        val text = requireString(params, "text")
        val submit = optionalBoolean(params, "submit", false)

        val submitCode = if (submit) {
            "el.form ? el.form.submit() : el.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter'}));"
        } else ""

        var result: String? = null
        var completed = false

        val selJson = org.json.JSONObject.quote(selector)
        val textJson = org.json.JSONObject.quote(text)
        val script = """
            (function() {
                var sel = $selJson;
                var el = document.querySelector(sel);
                if (!el) return 'ERROR: Element not found: ' + sel;
                var tag = el.tagName;
                el.value = $textJson;
                el.dispatchEvent(new Event('input', {bubbles: true}));
                el.dispatchEvent(new Event('change', {bubbles: true}));
                $submitCode
                return 'OK: Typed into <' + tag + '> \"' + $textJson.substring(0, 30) + '\"';
            })()
        """.trimIndent()

        engine.evaluateJs(script) { value ->
            result = unwrapJsString(value)
            completed = true
        }

        val deadline = System.currentTimeMillis() + 3000
        while (!completed && System.currentTimeMillis() < deadline) {
            if (!sleepInterruptible(50)) return ToolResult.error("Task cancelled during type")
        }

        val finalResult = result ?: return ToolResult.error("Type failed (timeout)")

        if (finalResult.startsWith("ERROR:")) {
            return ToolResult.error(finalResult.removePrefix("ERROR:").trim())
        }

        return ToolResult.success(finalResult)
    }
}

/**
 * 截图工具 —— 截取当前浏览器页面.
 */
class BrowserScreenshotTool(
    private val engine: BrowserEngine
) : BaseTool() {

    override fun getName() = "browser_screenshot"
    override fun getDisplayName() = "浏览器截图"

    override fun getDescriptionEN() = "Take a screenshot of the current browser page (returns Base64 PNG)."
    override fun getDescriptionCN() = "截取当前浏览器页面截图（返回 Base64 PNG）。"

    override fun getParameters() = listOf(
        ToolParameter("max_length", "integer", "Max Base64 length to return (default: 4096). Set 0 for full.", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val maxLength = optionalInt(params, "max_length", 4096)
        val base64 = engine.screenshot()
            ?: return ToolResult.error("Screenshot failed (engine may not have an active view)")

        return if (maxLength > 0 && base64.length > maxLength) {
            ToolResult.success("Screenshot: ${base64.take(maxLength)}... (truncated, total ${base64.length} chars)")
        } else {
            ToolResult.success("Screenshot: $base64")
        }
    }
}

/**
 * JS 执行工具 —— 在浏览器中执行任意 JavaScript.
 */
class BrowserEvaluateTool(
    private val engine: BrowserEngine
) : BaseTool() {

    override fun getName() = "browser_evaluate"
    override fun getDisplayName() = "浏览器执行JS"

    override fun getDescriptionEN() = "Execute JavaScript in the browser and return the result."
    override fun getDescriptionCN() = "在浏览器中执行 JavaScript 并返回结果。"

    override fun getParameters() = listOf(
        ToolParameter("script", "string", "JavaScript code to execute", true),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val script = requireString(params, "script")

        var result: String? = null
        var completed = false

        engine.evaluateJs(script) { value ->
            result = unwrapJsString(value)
            completed = true
        }

        val deadline = System.currentTimeMillis() + 10000
        while (!completed && System.currentTimeMillis() < deadline) {
            if (!sleepInterruptible(50)) return ToolResult.error("Task cancelled during JS evaluation")
        }

        return if (completed) {
            ToolResult.success(result ?: "(null)")
        } else {
            ToolResult.error("JS evaluation timeout (10s)")
        }
    }
}

/**
 * 扩展安装工具 —— agent 说"装个 uBlock"时调用.
 *
 * 支持三种来源：
 *  - Chrome Web Store ID（自动下载 CRX → 转 XPI → 装）
 *  - URL（自动检测 CRX/XPI）
 *  - AMO URL（Firefox 原生安装）
 */
class InstallExtensionTool(
    private val engine: BrowserEngine
) : BaseTool() {

    override fun getName() = "browser_install_extension"
    override fun getDisplayName() = "安装浏览器扩展"

    override fun getDescriptionEN() = "Install a browser extension. Supports Chrome Web Store ID, CRX/XPI URL, or AMO URL."
    override fun getDescriptionCN() = "安装浏览器扩展。支持 Chrome Web Store ID、CRX/XPI URL 或 AMO URL。"

    override fun getParameters() = listOf(
        ToolParameter("source", "string", "Extension source: 'cws:<id>' for Chrome Web Store, 'url:<url>' for direct download, 'amo:<url>' for Firefox Add-ons", true),
        ToolParameter("name", "string", "Extension name (for display, optional)", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        // WebExtension 安装能力随 GeckoView 一并移除(系统 WebView 无扩展机制)。
        // 引擎统一不支持扩展,直接返回不支持;扩展类能力请走自建注入式插件生态。
        return ToolResult.error(
            "Browser extensions are not supported by the current engine (${engine.name}). " +
                "WebExtension support was removed with GeckoView to shrink the app."
        )
    }
}
