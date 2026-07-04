package com.apk.claw.android.agent

/**
 * 对话页多轮上下文的纯逻辑构建器。
 *
 * 背景:对话页此前每条消息都是一次全新 [DefaultAgentService.executeTask],Agent 完全
 * 不知道上一轮说过什么 ——「搜索耳机」做完后接一句「换成蓝牙的」,Agent 只收到后四个字,
 * 无从理解指代。这里把最近若干轮 user/assistant 消息压成一段带角色标注的摘要文本,
 * 由 ChatAgentBridge 拼进任务 prompt 的「对话背景」区。
 *
 * 纯函数、无 Android 依赖,可直接 JVM 单测(见 ConversationContextTest)。
 */
object ConversationContext {

    /** 最多带多少条历史消息(user/assistant 各算一条)。 */
    const val MAX_TURNS = 8

    /** 单条消息截断长度 —— Agent 回答可能很长,背景里只需要开头的结论部分。 */
    const val MAX_CHARS_PER_TURN = 220

    /** 背景区总字符上限,超出时优先保留**最新**的轮次。 */
    const val MAX_TOTAL_CHARS = 1600

    /**
     * @param turns 按时间正序的历史消息(不含本次正要发送的指令),first=是否用户消息,second=文本。
     * @return 格式化的背景文本;没有可用历史时返回 null(调用方不拼背景区)。
     */
    fun build(
        turns: List<Pair<Boolean, String>>,
        maxTurns: Int = MAX_TURNS,
        maxCharsPerTurn: Int = MAX_CHARS_PER_TURN,
        maxTotalChars: Int = MAX_TOTAL_CHARS,
    ): String? {
        val lines = turns.asSequence()
            .map { (isUser, text) -> isUser to text.trim() }
            .filter { it.second.isNotEmpty() }
            .toList()
            .takeLast(maxTurns)
            .map { (isUser, text) ->
                val role = if (isUser) "用户" else "助手"
                val clipped = if (text.length > maxCharsPerTurn) text.take(maxCharsPerTurn) + "…" else text
                // 压掉换行,一轮一行,背景区保持紧凑
                "$role: ${clipped.replace(Regex("\\s*\\n+\\s*"), " ")}"
            }
        if (lines.isEmpty()) return null

        // 总量兜底:从最新往回装,装不下的旧轮次丢弃
        val kept = ArrayList<String>(lines.size)
        var total = 0
        for (line in lines.asReversed()) {
            if (total + line.length > maxTotalChars && kept.isNotEmpty()) break
            kept.add(line)
            total += line.length
        }
        return kept.asReversed().joinToString("\n")
    }
}
