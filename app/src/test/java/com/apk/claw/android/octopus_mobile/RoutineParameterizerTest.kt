package com.apk.claw.android.octopus_mobile

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * RoutineParameterizer 测试 —— 把"写死值"的例程升级成"带变量"的模板。
 *
 * 走真实的 [ActionCache] + [RoutineStore]：单测里 MMKV 未初始化，[com.apk.claw.android.utils.KVUtils]
 * 自动退回内存 map，故无需 Robolectric。每个用例前清空两个 store 防止串扰。
 */
class RoutineParameterizerTest {

    @Before
    fun reset() {
        RoutineStore.all().forEach { RoutineStore.remove(it.id) }
        ActionCache.all().forEach { ActionCache.remove(it.routineId) }
    }

    private fun seedRoutine(id: String, prompt: String) {
        RoutineStore.add(
            RoutineStore.Routine(
                id = id, name = "n", prompt = prompt,
                targetId = "local", targetLabel = "本机", createdAt = 0L,
            ),
        )
    }

    private fun seedCache(id: String, prompt: String, steps: List<ActionCache.Step>) {
        ActionCache.put(ActionCache.Sequence(id, prompt.hashCode(), steps, createdAt = 0L))
    }

    @Test
    fun `templatize replaces concrete values with placeholders and updates routine`() {
        val orig = "给张三发你好"
        seedRoutine("r1", orig)
        seedCache(
            "r1", orig,
            listOf(
                ActionCache.Step(tool = "tap", argsJson = """{"target":"张三"}""", anchorText = "张三"),
                ActionCache.Step(tool = "input_text", argsJson = """{"text":"你好"}""", anchorText = ""),
            ),
        )

        val ok = RoutineParameterizer.templatize("r1", orig, "给{联系人}发{内容}")
        assertTrue(ok)

        // 例程 prompt 改成模板，记下变量名
        val r = RoutineStore.all().first { it.id == "r1" }
        assertEquals("给{联系人}发{内容}", r.prompt)
        assertEquals(listOf("联系人", "内容"), r.variables)
        assertTrue(r.isParameterized)

        // 缓存改用模板当 key，步骤里写死值换成占位符
        val seq = ActionCache.get("r1", "给{联系人}发{内容}")
        assertNotNull(seq)
        assertEquals("""{"target":"{联系人}"}""", seq!!.steps[0].argsJson)
        assertEquals("{联系人}", seq.steps[0].anchorText)
        assertEquals("""{"text":"{内容}"}""", seq.steps[1].argsJson)

        // 旧 prompt 不再命中缓存（promptHash 已换）
        assertNull(ActionCache.get("r1", orig))
    }

    @Test
    fun `templatize is a no-op returning false when template does not match original`() {
        val orig = "给张三发你好"
        seedRoutine("r1", orig)
        seedCache("r1", orig, listOf(ActionCache.Step(tool = "tap", argsJson = """{"target":"张三"}""", anchorText = "张三")))

        val ok = RoutineParameterizer.templatize("r1", orig, "完全不同的{模板}")
        assertFalse(ok)

        // 什么都不该改
        val r = RoutineStore.all().first { it.id == "r1" }
        assertEquals(orig, r.prompt)
        assertTrue(r.variables.isEmpty())
        assertNotNull(ActionCache.get("r1", orig))
    }

    @Test
    fun `templatize returns false when no cached sequence exists`() {
        val orig = "给张三发你好"
        seedRoutine("r1", orig) // 无 ActionCache 缓存

        val ok = RoutineParameterizer.templatize("r1", orig, "给{联系人}发{内容}")
        assertFalse(ok)
        assertEquals(orig, RoutineStore.all().first { it.id == "r1" }.prompt)
    }

    @Test
    fun `templatize replaces long values before short to avoid substring corruption`() {
        // "你" 是 "你好" 的子串：必须先替长值，否则先替 "你" 会破坏 "你好"
        val orig = "发你好给你"
        seedRoutine("r1", orig)
        seedCache(
            "r1", orig,
            listOf(ActionCache.Step(tool = "input_text", argsJson = """{"text":"你好","to":"你"}""", anchorText = "你好")),
        )

        val ok = RoutineParameterizer.templatize("r1", orig, "发{内容}给{人}")
        assertTrue(ok)

        val seq = ActionCache.get("r1", "发{内容}给{人}")!!
        assertEquals("""{"text":"{内容}","to":"{人}"}""", seq.steps[0].argsJson)
        assertEquals("{内容}", seq.steps[0].anchorText)
    }
}
