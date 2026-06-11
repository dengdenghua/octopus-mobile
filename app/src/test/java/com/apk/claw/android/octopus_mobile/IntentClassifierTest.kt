package com.apk.claw.android.octopus_mobile

import org.junit.Assert.*
import org.junit.Test

/**
 * IntentClassifier 单元测试.
 *
 * 覆盖：
 *  - 浏览器意图识别（中文/英文）
 *  - 手机意图识别（中文/英文）
 *  - 混合意图识别
 *  - 不明确意图（无关键词）
 *  - 置信度计算
 *  - isBrowserIntent / isMobileIntent 快捷方法
 */
class IntentClassifierTest {

    // ── 浏览器意图 ────────────────────────────────────────

    @Test
    fun `classify browser intent Chinese`() {
        val inputs = listOf(
            "打开网页 https://example.com",
            "访问网站",
            "浏览器打开百度",
            "搜索网页",
            "截图网页",
            "用chrome打开",
            "执行js",
            "提取页面内容",
        )
        for (input in inputs) {
            val result = IntentClassifier.classify(input)
            assertTrue("'$input' should be BROWSER, got ${result.primary}",
                result.primary == IntentClassifier.IntentType.BROWSER)
            assertTrue("confidence should be > 0", result.confidence > 0)
            assertTrue("should have matched keywords", result.matchedKeywords.isNotEmpty())
        }
    }

    @Test
    fun `classify browser intent English`() {
        val inputs = listOf(
            "open url https://example.com",
            "navigate to google.com",
            "browse to the website",
            "webpage screenshot",
            "visit this url",
            "go to url",
        )
        for (input in inputs) {
            val result = IntentClassifier.classify(input)
            assertTrue("'$input' should be BROWSER, got ${result.primary}",
                result.primary == IntentClassifier.IntentType.BROWSER)
        }
    }

    @Test
    fun `isBrowserIntent returns true for browser tasks`() {
        assertTrue(IntentClassifier.isBrowserIntent("打开网页"))
        assertTrue(IntentClassifier.isBrowserIntent("visit url"))
    }

    @Test
    fun `isBrowserIntent returns false for mobile tasks`() {
        assertFalse(IntentClassifier.isBrowserIntent("点击屏幕"))
        assertFalse(IntentClassifier.isBrowserIntent("open app"))
    }

    // ── 手机意图 ──────────────────────────────────────────

    @Test
    fun `classify mobile intent Chinese`() {
        val inputs = listOf(
            "打开应用微信",
            "点击按钮",
            "滑动屏幕",
            "长按图标",
            "输入文字你好",
            "截图",
            "返回键",
            "音量加",
            "找元素",
            "安装应用",
            "系统设置",
        )
        for (input in inputs) {
            val result = IntentClassifier.classify(input)
            assertTrue("'$input' should be MOBILE, got ${result.primary}",
                result.primary == IntentClassifier.IntentType.MOBILE)
            assertTrue("confidence should be > 0", result.confidence > 0)
        }
    }

    @Test
    fun `classify mobile intent English`() {
        val inputs = listOf(
            "open app WeChat",
            "tap the button",
            "swipe left",
            "long press icon",
            "type text hello",
            "take screenshot",
            "press home button",
            "volume up",
            "find element",
            "install app",
        )
        for (input in inputs) {
            val result = IntentClassifier.classify(input)
            assertTrue("'$input' should be MOBILE, got ${result.primary}",
                result.primary == IntentClassifier.IntentType.MOBILE)
        }
    }

    @Test
    fun `isMobileIntent returns true for mobile tasks`() {
        assertTrue(IntentClassifier.isMobileIntent("点击屏幕"))
        assertTrue(IntentClassifier.isMobileIntent("open app"))
    }

    @Test
    fun `isMobileIntent returns false for browser tasks`() {
        assertFalse(IntentClassifier.isMobileIntent("打开网页"))
        assertFalse(IntentClassifier.isMobileIntent("visit url"))
    }

    // ── 混合意图 ──────────────────────────────────────────

    @Test
    fun `classify mixed intent`() {
        val inputs = listOf(
            "在淘宝网页版搜索",
            "用浏览器打开app",
            "手机浏览器",
            "app内网页",
            "网页版京东",
        )
        for (input in inputs) {
            val result = IntentClassifier.classify(input)
            assertTrue("'$input' should be MIXED, got ${result.primary}",
                result.primary == IntentClassifier.IntentType.MIXED)
            assertTrue("confidence should be high", result.confidence >= 0.8)
        }
    }

    @Test
    fun `classify mixed when both browser and mobile keywords present`() {
        // 同时包含浏览器和手机关键词（但不含混合关键词）
        val result = IntentClassifier.classify("打开网页然后点击按钮")
        assertEquals(IntentClassifier.IntentType.MIXED, result.primary)
    }

    // ── 不明确意图 ────────────────────────────────────────

    @Test
    fun `classify ambiguous intent`() {
        val inputs = listOf(
            "你好",
            "帮忙",
            "怎么做",
            "test",
            "hello world",
            "",
        )
        for (input in inputs) {
            val result = IntentClassifier.classify(input)
            assertTrue("'$input' should be AMBIGUOUS, got ${result.primary}",
                result.primary == IntentClassifier.IntentType.AMBIGUOUS)
            assertEquals(0.0, result.confidence, 0.001)
            assertTrue(result.matchedKeywords.isEmpty())
        }
    }

    // ── 置信度 ────────────────────────────────────────────

    @Test
    fun `confidence increases with more keywords`() {
        val r1 = IntentClassifier.classify("打开网页")
        val r2 = IntentClassifier.classify("打开网页访问网站浏览器截图")
        assertTrue("more keywords should give higher confidence", r2.confidence > r1.confidence)
        assertTrue("confidence should not exceed 0.95", r2.confidence <= 0.95)
    }

    @Test
    fun `confidence is at least half for clear intent`() {
        val result = IntentClassifier.classify("打开网页")
        assertTrue("minimum confidence for clear intent", result.confidence >= 0.5)
    }

    // ── 大小写不敏感 ──────────────────────────────────────

    @Test
    fun `classify is case insensitive`() {
        val lower = IntentClassifier.classify("open url")
        val upper = IntentClassifier.classify("OPEN URL")
        val mixed = IntentClassifier.classify("Open Url")
        assertEquals(lower.primary, upper.primary)
        assertEquals(upper.primary, mixed.primary)
    }
}
