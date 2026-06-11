package com.apk.claw.android.octopus_mobile.memory

import android.util.Log

/**
 * 上下文压缩器 —— 从母体 runtime/memory/context_compressor.py 移植.
 *
 * 手机版策略（省 token）：
 *  1. system 消息 → 始终保留
 *  2. 最近 N 条消息 → 始终保留
 *  3. 更早的消息 → 截断到 500 字 + 汇总为 [Context Summary]
 *
 * 用法：
 * ```kotlin
 * val compressor = ContextCompressor()
 * val (compressed, report) = compressor.compressWithReport(messages)
 * // report.ratio ≈ 0.3 表示压缩到 30%
 * ```
 */
class ContextCompressor(
    val config: CompressorConfig = CompressorConfig(),
) {
    companion object {
        private const val TAG = "ContextCompressor"
    }

    data class CompressorConfig(
        val maxChars: Int = 80000,
        val preserveSystem: Boolean = true,
        val preserveRecentN: Int = 4,
        val summaryMaxChars: Int = 2000,
        val chunkTruncateChars: Int = 500,
    )

    data class CompressionResult(
        val originalChars: Int,
        val compressedChars: Int,
        val ratio: Float,
        val method: String,
        val sectionsPreserved: Int,
        val sectionsSummarized: Int,
    )

    data class ChatMessage(
        val role: String,   // "system" / "user" / "assistant" / "tool"
        val content: String,
    )

    /**
     * 压缩消息列表.
     */
    fun compress(messages: List<ChatMessage>): List<ChatMessage> {
        val totalChars = messages.sumOf { it.content.length }
        if (totalChars <= config.maxChars) {
            return messages
        }

        val systemMsgs = mutableListOf<ChatMessage>()
        val recentMsgs = mutableListOf<ChatMessage>()
        val olderMsgs = mutableListOf<ChatMessage>()

        for (m in messages) {
            if (m.role == "system" && config.preserveSystem) {
                systemMsgs.add(m)
            } else {
                olderMsgs.add(m)
            }
        }

        if (olderMsgs.size > config.preserveRecentN) {
            recentMsgs.addAll(olderMsgs.takeLast(config.preserveRecentN))
            repeat(config.preserveRecentN) { olderMsgs.removeLastOrNull() }
        } else {
            recentMsgs.addAll(olderMsgs)
            olderMsgs.clear()
        }

        if (olderMsgs.isEmpty()) {
            return systemMsgs + recentMsgs
        }

        val summary = summarizeOlder(olderMsgs)
        val summaryMsg = ChatMessage(
            role = "system",
            content = "[Context Summary]\n$summary",
        )

        return systemMsgs + listOf(summaryMsg) + recentMsgs
    }

    /**
     * 压缩并返回报告.
     */
    fun compressWithReport(messages: List<ChatMessage>): Pair<List<ChatMessage>, CompressionResult> {
        val originalChars = messages.sumOf { it.content.length }
        val compressed = compress(messages)
        val compressedChars = compressed.sumOf { it.content.length }
        val ratio = if (originalChars > 0) compressedChars.toFloat() / originalChars else 1.0f

        val sectionsPreserved = compressed.count { !it.content.startsWith("[Context Summary]") }
        val sectionsSummarized = maxOf(0, messages.size - sectionsPreserved)

        val result = CompressionResult(
            originalChars = originalChars,
            compressedChars = compressedChars,
            ratio = (ratio * 1000).toInt() / 1000f,
            method = "truncate_older",
            sectionsPreserved = sectionsPreserved,
            sectionsSummarized = sectionsSummarized,
        )

        Log.d(TAG, "Compressed: ${originalChars}→${compressedChars} chars (${(ratio * 100).toInt()}%)")

        return compressed to result
    }

    private fun summarizeOlder(messages: List<ChatMessage>): String {
        val parts = messages.map { m ->
            val chunk = if (m.content.length > config.chunkTruncateChars) {
                m.content.take(config.chunkTruncateChars) + "..."
            } else {
                m.content
            }
            "[${m.role}] $chunk"
        }

        val full = parts.joinToString("\n")
        return if (full.length > config.summaryMaxChars) {
            full.take(config.summaryMaxChars) + "\n...[truncated]"
        } else {
            full
        }
    }
}
