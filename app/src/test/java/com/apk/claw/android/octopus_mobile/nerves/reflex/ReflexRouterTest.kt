package com.apk.claw.android.octopus_mobile.nerves.reflex

import org.junit.Assert.*
import org.junit.Test

/**
 * ReflexRouter 测试 —— 关键词匹配 / 缓存 / 预置规则.
 */
class ReflexRouterTest {

    @Test
    fun `default rules match open wechat`() {
        val router = ReflexRouter()
        router.addRules(ReflexRouter.defaultRules())
        val match = router.tryMatchText("帮我打开微信")
        assertNotNull(match)
        assertEquals("open_wechat", match?.ruleId)
        @Suppress("UNCHECKED_CAST")
        val resp = match?.response as? Map<String, String>
        assertEquals("open_app", resp?.get("tool"))
        assertEquals("com.tencent.mm", resp?.get("package"))
    }

    @Test
    fun `default rules match screenshot`() {
        val router = ReflexRouter()
        router.addRules(ReflexRouter.defaultRules())
        val match = router.tryMatchText("截个图")
        assertNotNull(match)
        assertEquals("take_screenshot", match?.ruleId)
    }

    @Test
    fun `default rules match go back`() {
        val router = ReflexRouter()
        router.addRules(ReflexRouter.defaultRules())
        val match = router.tryMatchText("返回")
        assertNotNull(match)
        assertEquals("go_back", match?.ruleId)
    }

    @Test
    fun `default rules match install ublock`() {
        val router = ReflexRouter()
        router.addRules(ReflexRouter.defaultRules())
        val match = router.tryMatchText("帮我装个去广告的")
        assertNotNull(match)
        assertEquals("install_ublock", match?.ruleId)
    }

    @Test
    fun `no match returns null`() {
        val router = ReflexRouter()
        router.addRules(ReflexRouter.defaultRules())
        val match = router.tryMatchText("帮我写个复杂的报告分析一下市场趋势")
        assertNull(match)
    }

    @Test
    fun `cache matcher works`() {
        val router = ReflexRouter()
        val cache = ReflexRouter.CacheMatcher(ttlSeconds = 3600)
        router.addRule(cache)

        val intent = ReflexRouter.ParsedIntent(goal = "打开微信")
        cache.put(intent, mapOf("tool" to "open_app", "package" to "com.tencent.mm"))

        val match = router.tryMatch(intent)
        assertNotNull(match)
        assertEquals("cache", match?.kind)
    }

    @Test
    fun `cache expiry`() {
        val router = ReflexRouter()
        val cache = ReflexRouter.CacheMatcher(ttlSeconds = 0)  // 立即过期
        router.addRule(cache)

        val intent = ReflexRouter.ParsedIntent(goal = "打开微信")
        cache.put(intent, mapOf("tool" to "open_app"))

        Thread.sleep(10)
        val match = router.tryMatch(intent)
        assertNull(match)  // 已过期
    }

    @Test
    fun `hit rate tracking`() {
        val router = ReflexRouter()
        router.addRules(ReflexRouter.defaultRules())
        router.tryMatchText("打开微信")
        router.tryMatchText("打开微信")
        router.tryMatchText("不存在")
        assertEquals(2.0 / 3.0, router.hitRate, 0.001)
    }

    @Test
    fun `force deliberative intent returns null`() {
        val router = ReflexRouter()
        router.addRules(ReflexRouter.defaultRules())
        val intent = ReflexRouter.ParsedIntent(
            goal = "打开微信",
            intentType = "plan"  // plan 强制走 LLM
        )
        val match = router.tryMatch(intent)
        assertNull(match)
    }

    @Test
    fun `listRules`() {
        val router = ReflexRouter()
        router.addRules(ReflexRouter.defaultRules())
        val rules = router.listRules()
        assertTrue(rules.size >= 12)
    }
}
