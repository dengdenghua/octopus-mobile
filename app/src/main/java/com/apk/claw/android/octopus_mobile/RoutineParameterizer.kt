package com.apk.claw.android.octopus_mobile

/**
 * 例程参数化（录制端）—— 把一条"具体值写死"的例程升级成"带变量的模板"，
 * 补齐"示范一遍 → 可复用到不同输入"的最后一环。
 *
 * 用法：某例程原本由「给张三发你好」录成，缓存步骤里写死了 "张三"/"你好"。
 * 调 [templatize] 传入模板「给{联系人}发{内容}」：
 *   1. 用模板从原指令抽出 {联系人:张三, 内容:你好}；
 *   2. 把缓存步骤的 argsJson / 锚点文字里的 "张三"→`{联系人}`、"你好"→`{内容}`；
 *   3. 例程 prompt 改成模板 + 记下变量名（[RoutineStore.Routine.variables]）。
 * 之后用「给李四发再见」运行 → [RoutineVariables.extract] 抽出新值 → FastReplay 替换重放。
 */
object RoutineParameterizer {

    /**
     * 参数化一条例程。返回是否成功（模板与原指令对不上 / 没有缓存步骤 → false，不改任何东西）。
     */
    fun templatize(routineId: String, originalPrompt: String, templatePrompt: String): Boolean {
        val vars = RoutineVariables.extract(templatePrompt, originalPrompt)
        if (vars.isEmpty()) return false
        val seq = ActionCache.all().find { it.routineId == routineId } ?: return false

        // 长值优先替换，避免短值是长值子串导致替错（如先替 "你" 会破坏 "你好"）。
        val ordered = vars.entries.filter { it.value.isNotBlank() }.sortedByDescending { it.value.length }
        val newSteps = seq.steps.map { step ->
            var args = step.argsJson
            var anchor = step.anchorText
            for ((name, value) in ordered) {
                args = args.replace(value, "{$name}")
                anchor = anchor.replace(value, "{$name}")
            }
            step.copy(argsJson = args, anchorText = anchor)
        }
        // 缓存改用模板指令当 key（promptHash），步骤里换成占位符。
        ActionCache.put(seq.copy(promptHash = templatePrompt.hashCode(), steps = newSteps))

        RoutineStore.all().find { it.id == routineId }?.let { r ->
            RoutineStore.update(
                r.copy(prompt = templatePrompt, variables = RoutineVariables.names(templatePrompt)),
            )
        }
        return true
    }
}
