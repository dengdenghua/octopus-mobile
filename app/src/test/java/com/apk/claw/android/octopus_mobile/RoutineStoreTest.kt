package com.apk.claw.android.octopus_mobile

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * RoutineStore 测试 —— 例程库 CRUD + 上限裁剪 + 可空定时字段的 Gson 往返（纯 JVM）。
 */
class RoutineStoreTest {

    @Before
    fun reset() {
        RoutineStore.all().forEach { RoutineStore.remove(it.id) }
    }

    private fun routine(
        id: String,
        name: String = "n",
        prompt: String = "p",
        createdAt: Long = 0L,
        variables: List<String> = emptyList(),
        hour: Int? = null,
        minute: Int? = null,
    ) = RoutineStore.Routine(
        id = id, name = name, prompt = prompt,
        targetId = "local", targetLabel = "本机", createdAt = createdAt,
        scheduleHour = hour, scheduleMinute = minute, variables = variables,
    )

    @Test
    fun `all is empty initially`() {
        assertTrue(RoutineStore.all().isEmpty())
    }

    @Test
    fun `add inserts newest first`() {
        RoutineStore.add(routine("a"))
        RoutineStore.add(routine("b"))
        assertEquals(listOf("b", "a"), RoutineStore.all().map { it.id })
    }

    @Test
    fun `add trims to MAX_KEEP keeping newest 100`() {
        for (i in 0..100) RoutineStore.add(routine("r$i")) // 101 条
        val all = RoutineStore.all()
        assertEquals(100, all.size)
        assertEquals("r100", all.first().id)         // 最新在前
        assertTrue(all.none { it.id == "r0" })        // 最旧被裁掉
    }

    @Test
    fun `remove deletes by id`() {
        RoutineStore.add(routine("a"))
        RoutineStore.add(routine("b"))
        RoutineStore.remove("a")
        assertEquals(listOf("b"), RoutineStore.all().map { it.id })
    }

    @Test
    fun `update replaces matching id only`() {
        RoutineStore.add(routine("a", name = "old"))
        RoutineStore.add(routine("b", name = "keep"))
        RoutineStore.update(routine("a", name = "new"))
        assertEquals("new", RoutineStore.all().first { it.id == "a" }.name)
        assertEquals("keep", RoutineStore.all().first { it.id == "b" }.name)
    }

    @Test
    fun `touch increments runCount and sets lastRunAt`() {
        RoutineStore.add(routine("a"))
        RoutineStore.touch("a")
        val r = RoutineStore.all().first { it.id == "a" }
        assertEquals(1, r.runCount)
        assertTrue(r.lastRunAt > 0L)
    }

    @Test
    fun `isScheduled and isParameterized getters`() {
        RoutineStore.add(routine("plain"))
        RoutineStore.add(routine("timed", hour = 8, minute = 30))
        RoutineStore.add(routine("param", variables = listOf("人")))
        val byId = RoutineStore.all().associateBy { it.id }
        assertFalse(byId["plain"]!!.isScheduled)
        assertFalse(byId["plain"]!!.isParameterized)
        assertTrue(byId["timed"]!!.isScheduled)
        assertTrue(byId["param"]!!.isParameterized)
    }

    @Test
    fun `null schedule survives gson round-trip and stays null not zero`() {
        RoutineStore.add(routine("a")) // hour/minute = null
        val r = RoutineStore.all().first { it.id == "a" }
        // 关键：Gson 不能把未定时误填 0（否则会被当成 00:00 已定时）
        assertNull(r.scheduleHour)
        assertNull(r.scheduleMinute)
        assertFalse(r.isScheduled)
    }
}
