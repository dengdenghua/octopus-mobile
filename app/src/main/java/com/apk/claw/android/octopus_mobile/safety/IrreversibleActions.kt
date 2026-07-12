@file:Suppress("PackageNaming")   // 沿用既有 octopus_mobile.safety 包(带下划线)

package com.apk.claw.android.octopus_mobile.safety

/**
 * 不可逆动作分类 —— 标记「一旦执行就收不回」的外部副作用工具。
 *
 * 与 [ToolRiskPolicy] 的风险分级正交:风险分级管"该不该拦/审计",这里管"该不该给撤销窗"。
 * 只收**真正不可逆的外部副作用**(发出去就到了别人那儿),不含可回滚/纯本地的操作:
 * - send_sms：短信发出即达运营商,无法撤回
 * - send_intent：通过其他应用发送/分享,离开本应用即失控
 * - share_to_square：发布到公开广场,他人可见
 * - send_file：文件发给外部,收不回
 *
 * 供 [UndoWindow] 在本地在场场景给一个可撤销倒计时窗(Gmail undo-send 式)。
 */
object IrreversibleActions {

    private const val MAX_DESC_CHARS = 80

    private val IRREVERSIBLE: Set<String> = setOf(
        "send_sms",
        "send_intent",
        "share_to_square",
        "send_file",
    )

    /** 该工具是否为不可逆外部副作用(值得给撤销窗)。 */
    fun isIrreversible(toolName: String): Boolean = toolName in IRREVERSIBLE

    /**
     * 生成给用户看的动作摘要(用于撤销窗)——让用户一眼看清"AI 要发什么给谁",好判断是否误操作。
     * 敏感键(密码/token 等)脱敏;正文内容如实展示(展示正文正是撤销窗的价值)。
     */
    fun describe(toolName: String, params: Map<String, Any>): String = when (toolName) {
        "send_sms" -> {
            val to = firstNonBlank(params, "phone", "to", "number", "address")
            val body = firstNonBlank(params, "message", "content", "text", "body")
            "发短信给 ${to.ifBlank { "?" }}:${clip(body)}"
        }
        "share_to_square" -> {
            val title = firstNonBlank(params, "title", "content", "text", "html")
            "发布到公开广场:${clip(title)}"
        }
        "send_file" -> {
            val path = firstNonBlank(params, "path", "file", "uri")
            val to = firstNonBlank(params, "to", "target", "channel")
            "发送文件 ${path.ifBlank { "?" }}${if (to.isNotBlank()) " 给 $to" else ""}"
        }
        "send_intent" -> {
            val data = firstNonBlank(params, "extra_text", "data", "text", "content")
            "通过其他应用发送/分享:${clip(data.ifBlank { redactedDump(params) })}"
        }
        else -> "$toolName:${clip(redactedDump(params))}"
    }

    private fun firstNonBlank(params: Map<String, Any>, vararg keys: String): String {
        for (k in keys) {
            val v = params[k]?.toString()
            if (!v.isNullOrBlank()) return v
        }
        return ""
    }

    private fun redactedDump(params: Map<String, Any>): String =
        params.entries.joinToString(", ") { (k, v) ->
            val shown = if (ToolRiskPolicy.isSensitiveKey(k)) "<redacted>" else v.toString()
            "$k=$shown"
        }

    private fun clip(s: String): String =
        if (s.length > MAX_DESC_CHARS) s.take(MAX_DESC_CHARS) + "…" else s
}
