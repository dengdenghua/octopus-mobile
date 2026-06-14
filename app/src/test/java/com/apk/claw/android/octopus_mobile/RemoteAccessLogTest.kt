package com.apk.claw.android.octopus_mobile

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAccessLogTest {

    @After
    fun tearDown() {
        RemoteAccessLog.clear()
    }

    @Test
    fun `record stores newest entry first`() {
        RemoteAccessLog.record(entry("first", 1L))
        RemoteAccessLog.record(entry("second", 2L))

        val entries = RemoteAccessLog.all()

        assertEquals(listOf("second", "first"), entries.map { it.action })
    }

    @Test
    fun `record keeps latest 300 entries`() {
        repeat(305) { index ->
            RemoteAccessLog.record(entry("action_$index", index.toLong()))
        }

        val entries = RemoteAccessLog.all()

        assertEquals(300, entries.size)
        assertEquals("action_304", entries.first().action)
        assertEquals("action_5", entries.last().action)
        assertTrue(entries.none { it.action == "action_0" })
    }

    private fun entry(action: String, ts: Long): RemoteAccessLog.Entry =
        RemoteAccessLog.Entry(
            id = "remote_$ts",
            ts = ts,
            method = "POST",
            uri = "/api/test",
            source = "127.0.0.1",
            action = action,
            success = true,
            summary = "ok",
            durationMs = 1,
        )
}
