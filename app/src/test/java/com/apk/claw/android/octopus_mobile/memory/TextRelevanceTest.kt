@file:Suppress("PackageNaming")   // 沿用既有 octopus_mobile.memory 包(带下划线)

package com.apk.claw.android.octopus_mobile.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TextRelevance 测试 —— 纯 JVM,验证关键词抽取(ASCII + 中文 2-gram + 停用词)与重叠计数。
 */
class TextRelevanceTest {

    @Test
    fun `extracts ascii words over min length and drops stopwords`() {
        val kw = TextRelevance.keywords("Please book a flight to Beijing")
        assertTrue(kw.contains("book"))
        assertTrue(kw.contains("flight"))
        assertTrue(kw.contains("beijing"))
        assertFalse("停用词 please 应剔除", kw.contains("please"))
        assertFalse("过短的 to/a 不入", kw.contains("to"))
    }

    @Test
    fun `extracts chinese 2-grams and drops stopwords`() {
        val kw = TextRelevance.keywords("帮我订一张去北京的机票")
        assertTrue(kw.contains("机票"))
        assertTrue(kw.contains("北京"))
        assertFalse("停用词 帮我 应剔除", kw.contains("帮我"))
    }

    @Test
    fun `overlap counts shared keywords`() {
        val q = TextRelevance.keywords("订一张去北京的机票")
        // 相关文本:含"机票""北京"
        assertTrue(TextRelevance.overlap(q, "用户常订国际机票,偏好靠窗") > 0)
        // 无关文本:咖啡偏好
        assertEquals(0, TextRelevance.overlap(q, "用户喜欢喝美式咖啡不加糖"))
    }

    @Test
    fun `empty query yields zero overlap`() {
        assertEquals(0, TextRelevance.overlap(emptySet(), "任意文本"))
    }
}
