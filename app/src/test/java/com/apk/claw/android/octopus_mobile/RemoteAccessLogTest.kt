package com.apk.claw.android.octopus_mobile

import com.apk.claw.android.utils.KVUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAccessLogTest {

    @After
    fun tearDown() {
        RemoteAccessLog.clear()
    }

    @Test
    fun `untampered entries verify clean`() {
        RemoteAccessLog.record(entry("a", 1L))
        RemoteAccessLog.record(entry("b", 2L))
        assertTrue(RemoteAccessLog.all().none { it.tampered })
    }

    @Test
    fun `mutating a persisted entry is flagged as tampered`() {
        RemoteAccessLog.record(entry("legit", 1L))

        // 直接改底层 JSON(模拟攻击者编辑 SharedPreferences)——签名不再匹配
        val gson = Gson()
        val type = object : TypeToken<List<RemoteAccessLog.Entry>>() {}.type
        val stored: List<RemoteAccessLog.Entry> = gson.fromJson(KVUtils.getString("remote_access_log", ""), type)
        val forged = stored.map { it.copy(summary = "tampered-by-attacker") }
        KVUtils.putString("remote_access_log", gson.toJson(forged))

        val entries = RemoteAccessLog.all()
        assertEquals(1, entries.size)
        assertTrue("被改内容的条目应标记 tampered", entries[0].tampered)
    }

    @Test
    fun `signature is stored on record`() {
        RemoteAccessLog.record(entry("x", 1L))
        val gson = Gson()
        val type = object : TypeToken<List<RemoteAccessLog.Entry>>() {}.type
        val stored: List<RemoteAccessLog.Entry> = gson.fromJson(KVUtils.getString("remote_access_log", ""), type)
        assertFalse("写入应带 HMAC 签名", stored[0].signature.isNullOrEmpty())
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
