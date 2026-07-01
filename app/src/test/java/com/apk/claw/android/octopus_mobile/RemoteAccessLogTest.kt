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

    // ── 哈希链:删除 / 调序检测 ──

    private val gson = Gson()
    private val listType = object : TypeToken<List<RemoteAccessLog.Entry>>() {}.type
    private fun readRaw(): List<RemoteAccessLog.Entry> =
        gson.fromJson(KVUtils.getString("remote_access_log", ""), listType)
    private fun writeRaw(list: List<RemoteAccessLog.Entry>) =
        KVUtils.putString("remote_access_log", gson.toJson(list))

    @Test
    fun `deleting a middle entry breaks the chain`() {
        RemoteAccessLog.record(entry("a", 1L)) // oldest
        RemoteAccessLog.record(entry("b", 2L)) // middle
        RemoteAccessLog.record(entry("c", 3L)) // newest
        // 原始 newest-first = [c, b, a];删掉中间的 b
        val raw = readRaw()
        writeRaw(raw.filter { it.action != "b" }) // [c, a]

        val entries = RemoteAccessLog.all()
        assertEquals(2, entries.size)
        // c.prevHash 指向已删的 b.signature,现与 a.signature 不符 → c 断链
        assertTrue("删中间条应导致后继断链被标记", entries.first { it.action == "c" }.tampered)
    }

    @Test
    fun `deleting the newest entry is detected via head anchor`() {
        RemoteAccessLog.record(entry("a", 1L))
        RemoteAccessLog.record(entry("b", 2L)) // newest
        // 删掉最新的 b → 首条变 a,但 headAnchor 仍是 b 的签名
        val raw = readRaw()
        writeRaw(raw.filter { it.action != "b" }) // [a]

        val entries = RemoteAccessLog.all()
        assertEquals(1, entries.size)
        assertTrue("删最新条应经 headAnchor 检测到", entries[0].tampered)
    }

    @Test
    fun `reordering entries is detected`() {
        RemoteAccessLog.record(entry("a", 1L))
        RemoteAccessLog.record(entry("b", 2L))
        RemoteAccessLog.record(entry("c", 3L))
        val raw = readRaw().toMutableList() // [c, b, a]
        // 调换 b 与 a 的顺序 → [c, a, b]
        val reordered = listOf(raw[0], raw[2], raw[1])
        writeRaw(reordered)

        val entries = RemoteAccessLog.all()
        assertTrue("调序应被链接校验检测到", entries.any { it.tampered })
    }

    @Test
    fun `legacy entries without prevHash are not falsely flagged`() {
        // 模拟升级前写入的旧条目:无 prevHash,签名用旧方案 HMAC(payload)
        val secret = KVUtils.getString("remote_access_hmac_secret", "").ifEmpty {
            AuditChain.generateSecret().also { KVUtils.putString("remote_access_hmac_secret", it) }
        }
        val e = entry("legacy", 1L)
        val payload = "${e.id}|${e.ts}|${e.method}|${e.uri}|${e.source}|${e.action}|${e.success}|${e.summary}|${e.durationMs}"
        val legacy = e.copy(prevHash = null, signature = AuditChain.signLegacy(secret, payload))
        writeRaw(listOf(legacy))

        val entries = RemoteAccessLog.all()
        assertEquals(1, entries.size)
        assertFalse("旧方案条目不应被误判为篡改", entries[0].tampered)
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
