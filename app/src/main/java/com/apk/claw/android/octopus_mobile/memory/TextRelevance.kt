@file:Suppress("PackageNaming")   // 沿用既有 octopus_mobile.memory 包(带下划线)

package com.apk.claw.android.octopus_mobile.memory

/**
 * 轻量本地文本相关性 —— 纯关键词重叠,零网络、零依赖,可 JVM 单测。
 *
 * 用于 [MemoryStore] 按当前任务给记忆排序:记忆一多,无差别注入会让"喜欢深色模式"
 * 挤进"订机票"任务的 prompt。这里用与 PromptSkillStore 一致的手法(ASCII 词 + 中文 2-gram +
 * 停用词)算 query↔记忆内容的词项交集,把相关记忆顶到前面。
 *
 * 与 SemanticSkillRanker 的区别:那个走母体网关(需配对、有网络延迟),这个是本地兜底级、
 * 永远可用——记忆召回是每个任务的热路径,不该依赖外部服务。
 */
object TextRelevance {

    private const val MIN_ASCII_WORD = 4

    // 太泛的英文词 / 中文 2-gram,做关键词会造成大量误命中,剔掉。
    private val EN_STOP = setOf(
        "when", "that", "this", "with", "from", "your", "user", "please", "make", "want",
        "need", "will", "what", "about", "some", "into", "then", "than", "they", "have",
        "been", "asks", "create", "does", "would", "should", "help", "the",
    )
    private val CN_STOP = setOf(
        "用户", "一个", "一笔", "怎么", "什么", "时候", "的时", "当用", "户要", "要用",
        "可以", "帮我", "我要", "我想", "如果", "这个", "那个", "一下", "进行", "需要",
    )

    private val ASCII = Regex("[a-z][a-z0-9]{${MIN_ASCII_WORD - 1},}")
    private val CJK = Regex("[\\u4e00-\\u9fa5]{2,}")

    /** 抽取关键词:ASCII 词(≥4 字符、去停用词) + 中文 2-gram(去停用词)。 */
    fun keywords(s: String): Set<String> {
        val out = mutableSetOf<String>()
        ASCII.findAll(s.lowercase()).forEach { if (it.value !in EN_STOP) out.add(it.value) }
        CJK.findAll(s).forEach { seg ->
            val t = seg.value
            for (i in 0..t.length - 2) {
                val g = t.substring(i, i + 2)
                if (g !in CN_STOP) out.add(g)
            }
        }
        return out
    }

    /** query 的关键词集合 与 text 的关键词交集数量(越大越相关;0 = 不相关)。 */
    fun overlap(queryKeywords: Set<String>, text: String): Int {
        if (queryKeywords.isEmpty()) return 0
        return keywords(text).count { it in queryKeywords }
    }
}
