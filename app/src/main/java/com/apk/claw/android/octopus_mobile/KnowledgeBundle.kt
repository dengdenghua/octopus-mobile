@file:Suppress("PackageNaming")   // 沿用既有 octopus_mobile 包(带下划线)

package com.apk.claw.android.octopus_mobile

import org.json.JSONArray
import org.json.JSONObject

/**
 * 知识包(Knowledge Bundle)—— 把用户教的操作规矩 + Agent 记住的关于用户的事,
 * 打成一段可分享的 JSON 文本,便于**备份 / 迁移到另一台设备 / 重装后恢复**。
 *
 * 是 fleet 同步(一台学会、多台受益)不需要服务器就能跑的**本地种子**:导出→剪贴板/文件→另一台导入。
 * 纯 org.json 编解码,不依赖 Android,可 JVM 单测。解析容错:坏 JSON/缺字段一律退空,绝不抛。
 */
object KnowledgeBundle {

    private const val VERSION = 1
    private const val APP_TAG = "octopus"

    /** 一条记忆的可移植形态(type 用字符串,跨版本稳)。 */
    data class MemItem(val content: String, val type: String)

    /** 解析结果。 */
    data class Parsed(val rules: List<String>, val memories: List<MemItem>)

    /** 打包:规矩列表 + 记忆列表 → 缩进 JSON 文本。 */
    fun export(rules: List<String>, memories: List<MemItem>): String {
        val root = JSONObject()
        root.put("app", APP_TAG)
        root.put("version", VERSION)
        root.put("rules", JSONArray(rules.filter { it.isNotBlank() }))
        val mems = JSONArray()
        memories.filter { it.content.isNotBlank() }.forEach {
            mems.put(JSONObject().put("content", it.content).put("type", it.type))
        }
        root.put("memories", mems)
        return root.toString(2)
    }

    /** 解析:JSON 文本 → {规矩, 记忆}。任何异常都退回空包(容错,便于粘贴任意文本不崩)。 */
    fun parse(json: String): Parsed = runCatching {
        val root = JSONObject(json)
        val rulesArr = root.optJSONArray("rules") ?: JSONArray()
        val rules = (0 until rulesArr.length())
            .map { rulesArr.optString(it) }
            .filter { it.isNotBlank() }
        val memsArr = root.optJSONArray("memories") ?: JSONArray()
        val mems = (0 until memsArr.length()).mapNotNull { i ->
            val o = memsArr.optJSONObject(i) ?: return@mapNotNull null
            val content = o.optString("content")
            if (content.isBlank()) null else MemItem(content, o.optString("type", "FACT"))
        }
        Parsed(rules, mems)
    }.getOrElse { Parsed(emptyList(), emptyList()) }

    /** 该文本是否像一个合法知识包(用于导入前粗判)。 */
    fun looksValid(json: String): Boolean = runCatching {
        val root = JSONObject(json)
        root.optString("app") == APP_TAG && root.has("version")
    }.getOrElse { false }
}
