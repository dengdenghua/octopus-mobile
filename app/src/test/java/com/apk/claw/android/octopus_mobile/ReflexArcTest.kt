@file:Suppress("PackageNaming")   // 沿用既有 octopus_mobile 包(带下划线)

package com.apk.claw.android.octopus_mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ReflexArc 测试 —— 规则命中 / 未命中 / 生成缓存复用 / 重做判定。
 *
 * ReflexArc 是单例、缓存跨测试累积,故用例采用**高辨识度且互不相似的查询**避免相似度串扰。
 */
class ReflexArcTest {

    @Test
    fun `greeting hits a rule`() {
        val m = ReflexArc.tryMatch("你好")
        assertNotNull(m)
        assertEquals("rule", m!!.source)
        assertTrue(m.confidence >= 0.85)
    }

    @Test
    fun `complex generation request does not reflex-match`() {
        val m = ReflexArc.tryMatch("帮我做一个带柱状图和分类统计的记账小程序")
        assertNull(m)
    }

    @Test
    fun `remembered app is served from cache`() {
        val q = "我要一个番茄钟专注计时器带白噪音功能"
        ReflexArc.remember(q, "番茄钟", "<html>pomodoro</html>", "app_pomo_test")
        val m = ReflexArc.tryMatch(q)
        assertNotNull(m)
        assertEquals("cache", m!!.source)
        assertEquals("<html>pomodoro</html>", m.cachedHtml)
        assertEquals("app_pomo_test", m.cachedAppId)
    }

    @Test
    fun `regenerate request is detected`() {
        assertTrue(ReflexArc.isRegenerateRequest("重做"))
        assertTrue(ReflexArc.isRegenerateRequest("重新生成"))
        assertFalse(ReflexArc.isRegenerateRequest("你好"))
    }

    @Test
    fun `lastEntry returns the most recent remember`() {
        ReflexArc.remember("独一无二的关键字ZZZ最近一次生成", "最近应用", "<html>last</html>", "app_last_test")
        val e = ReflexArc.lastEntry()
        assertNotNull(e)
        assertEquals("app_last_test", e!!.appId)
    }
}
