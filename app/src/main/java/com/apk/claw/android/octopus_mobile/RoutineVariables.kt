package com.apk.claw.android.octopus_mobile

/**
 * 例程参数化 —— 让"示范一遍"录的例程带变量，输入一变不必重录。
 *
 * 模板写法：例程的 prompt 用 `{变量名}` 占位，例如「给{联系人}发{内容}」。
 * 运行时给定实际指令「给张三发你好」，[extract] 抽出 {联系人:张三, 内容:你好}，
 * 再由 [com.apk.claw.android.ui.compose.screen.FastReplay] 把缓存步骤里的占位符替换成实际值后重放。
 *
 * 这样一条示范就能复用到不同对象/内容，也能上广场当可参数化技能。
 */
object RoutineVariables {

    // 允许中英文 / 数字 / 下划线作变量名
    private val PLACEHOLDER = Regex("\\{([A-Za-z0-9_\\u4e00-\\u9fa5]+)\\}")

    /** 模板里声明了哪些变量。 */
    fun names(template: String): List<String> =
        PLACEHOLDER.findAll(template).map { it.groupValues[1] }.distinct().toList()

    fun hasVariables(template: String): Boolean = PLACEHOLDER.containsMatchIn(template)

    /**
     * 用模板从实际指令里抽变量值。
     * "给{联系人}发{内容}" + "给张三发你好" → {联系人:张三, 内容:你好}。
     * 模板与实际对不上（结构不同）→ 返回空 map（调用方据此回退到完整 Agent）。
     */
    fun extract(template: String, actual: String): Map<String, String> {
        val names = ArrayList<String>()
        val regex = StringBuilder("^")
        var last = 0
        for (m in PLACEHOLDER.findAll(template)) {
            regex.append(Regex.escape(template.substring(last, m.range.first)))
            regex.append("(.+?)")
            names.add(m.groupValues[1])
            last = m.range.last + 1
        }
        if (names.isEmpty()) return emptyMap()
        regex.append(Regex.escape(template.substring(last))).append("$")
        val match = runCatching { Regex(regex.toString()).find(actual.trim()) }.getOrNull()
            ?: return emptyMap()
        return names.withIndex().associate { (i, name) -> name to match.groupValues[i + 1].trim() }
    }

    /** 把字符串里的 `{var}` 占位符替换成实际值（FastReplay 重放每步前调）。 */
    fun substitute(text: String, vars: Map<String, String>): String {
        if (vars.isEmpty() || text.isEmpty()) return text
        var out = text
        for ((k, v) in vars) out = out.replace("{$k}", v)
        return out
    }
}
