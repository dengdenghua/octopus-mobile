package com.apk.claw.android.tool.impl

import com.apk.claw.android.utils.KVUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * 验证 ScriptSandbox 的会话持久化与新增宿主 API（crypto/datetime/uuid/文件管理/callToolAsync）。
 *
 * 纯 JVM 测试。文件类用例通过临时工作空间目录使 isSafePath 在 JVM 环境下通过校验
 * （生产环境用 /sdcard/Download 等,在 JVM/macOS 上不存在）。
 */
@Suppress("TooManyFunctions")
class ScriptSandboxSessionTest {

    private val tempWorkspace: java.io.File =
        Files.createTempDirectory("octopus-sandbox-test").toFile()

    @Before
    fun setUp() {
        KVUtils.resetForTest()
        KVUtils.setScriptWorkspace(tempWorkspace.absolutePath)
        // 清理可能残留的会话(单例跨测试类共享)
        ScriptSandbox.listSessions().forEach { ScriptSandbox.resetSession(it) }
    }

    @After
    fun tearDown() {
        ScriptSandbox.listSessions().forEach { ScriptSandbox.resetSession(it) }
        tempWorkspace.deleteRecursively()
    }

    // ── 会话持久化 ────────────────────────────────────────────────────────

    @Test
    fun `session persists variables across calls`() {
        val sid = "var-persist-${System.nanoTime()}"
        val r1 = ScriptSandbox.executeInSession(sid, "var counter = 10; print(counter);", 5_000)
        assertTrue("first call: ${r1.error}", r1.isSuccess)
        assertEquals("10", r1.data?.trim())
        val r2 = ScriptSandbox.executeInSession(sid, "counter += 5; print(counter);", 5_000)
        assertTrue("second call: ${r2.error}", r2.isSuccess)
        assertEquals("15", r2.data?.trim())
    }

    @Test
    fun `session persists function definitions across calls`() {
        val sid = "fn-persist-${System.nanoTime()}"
        val r1 = ScriptSandbox.executeInSession(
            sid, "function add(a,b){ return a+b; } print(add(2,3));", 5_000,
        )
        assertTrue("define: ${r1.error}", r1.isSuccess)
        assertEquals("5", r1.data?.trim())
        // 第二次调用复用上一次定义的 add()
        val r2 = ScriptSandbox.executeInSession(sid, "print(add(10,20));", 5_000)
        assertTrue("reuse: ${r2.error}", r2.isSuccess)
        assertEquals("30", r2.data?.trim())
    }

    @Test
    fun `session persists complex object state`() {
        val sid = "obj-persist-${System.nanoTime()}"
        val r1 = ScriptSandbox.executeInSession(
            sid,
            "var cache = {items:[1,2,3]}; cache.items.push(4); print(cache.items.length);",
            5_000,
        )
        assertTrue("build: ${r1.error}", r1.isSuccess)
        assertEquals("4", r1.data?.trim())
        val r2 = ScriptSandbox.executeInSession(
            sid, "cache.items.push(5); print(cache.items.join(','));", 5_000,
        )
        assertTrue("extend: ${r2.error}", r2.isSuccess)
        assertEquals("1,2,3,4,5", r2.data?.trim())
    }

    // ── 会话重置 ───────────────────────────────────────────────────────────

    @Test
    fun `reset clears session state`() {
        val sid = "reset-${System.nanoTime()}"
        ScriptSandbox.executeInSession(sid, "var x = 999; print(x);", 5_000)
        val existed = ScriptSandbox.resetSession(sid)
        assertTrue("reset should return true for existing session", existed)
        // 重置后变量消失 → 引用 x 应报错
        val r = ScriptSandbox.executeInSession(sid, "print(x);", 5_000)
        assertFalse("variable should be gone after reset", r.isSuccess)
    }

    @Test
    fun `resetSession returns false for unknown session`() {
        val existed = ScriptSandbox.resetSession("nonexistent-${System.nanoTime()}")
        assertFalse("should return false for unknown session", existed)
    }

    @Test
    fun `listSessions returns active sessions`() {
        val sid1 = "list-1-${System.nanoTime()}"
        val sid2 = "list-2-${System.nanoTime()}"
        ScriptSandbox.executeInSession(sid1, "var a = 1;", 5_000)
        ScriptSandbox.executeInSession(sid2, "var b = 2;", 5_000)
        val sessions = ScriptSandbox.listSessions()
        assertTrue("sid1 should be listed: $sessions", sid1 in sessions)
        assertTrue("sid2 should be listed: $sessions", sid2 in sessions)
    }

    // ── 会话隔离 ───────────────────────────────────────────────────────────

    @Test
    fun `different session ids are isolated`() {
        val sidA = "iso-a-${System.nanoTime()}"
        val sidB = "iso-b-${System.nanoTime()}"
        ScriptSandbox.executeInSession(sidA, "var shared = 'A'; print(shared);", 5_000)
        ScriptSandbox.executeInSession(sidB, "var shared = 'B'; print(shared);", 5_000)
        // 互不影响
        val rA = ScriptSandbox.executeInSession(sidA, "print(shared);", 5_000)
        val rB = ScriptSandbox.executeInSession(sidB, "print(shared);", 5_000)
        assertEquals("A", rA.data?.trim())
        assertEquals("B", rB.data?.trim())
    }

    // ── TTL 过期重建 ──────────────────────────────────────────────────────

    @Test
    fun `session rebuilds after TTL expiry`() {
        val sid = "ttl-${System.nanoTime()}"
        ScriptSandbox.executeInSession(sid, "var persist = 'before'; print(persist);", 5_000)
        // 用反射把 lastAccess 倒拨 31 分钟,模拟 TTL 过期
        val sessionsField = ScriptSandbox::class.java.getDeclaredField("sessions")
        sessionsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val sessions = sessionsField.get(ScriptSandbox) as Map<String, Any>
        val session = sessions[sid] ?: error("session not found in sessions map")
        val lastAccessField = session.javaClass.getDeclaredField("lastAccess")
        lastAccessField.isAccessible = true
        lastAccessField.setLong(session, System.currentTimeMillis() - 31 * 60 * 1000L)
        // 过期后再次执行 → 应重建 scope,旧变量 persist 不存在
        val r = ScriptSandbox.executeInSession(sid, "print(typeof persist);", 5_000)
        assertTrue("rebuild: ${r.error}", r.isSuccess)
        assertEquals("undefined", r.data?.trim())
    }

    // ── crypto 模块 ───────────────────────────────────────────────────────

    @Test
    fun `crypto md5 and sha256 produce known digests`() {
        val r = ScriptSandbox.execute(
            """
            print(crypto.md5("abc"));
            print(crypto.sha256("abc"));
            """.trimIndent(),
            5_000,
        )
        assertTrue(r.error ?: "", r.isSuccess)
        val lines = r.data?.trim()?.lines() ?: emptyList()
        assertEquals(2, lines.size)
        assertEquals("900150983cd24fb0d6963f7d28e17f72", lines[0])
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            lines[1],
        )
    }

    @Test
    fun `crypto hmacSha256 matches known vector`() {
        val r = ScriptSandbox.execute(
            """print(crypto.hmacSha256("key", "The quick brown fox jumps over the lazy dog"));""",
            5_000,
        )
        assertTrue(r.error ?: "", r.isSuccess)
        assertEquals(
            "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
            r.data?.trim(),
        )
    }

    @Test
    fun `crypto base64 encode and decode roundtrip`() {
        val r = ScriptSandbox.execute(
            """
            var enc = crypto.base64Encode("hello world");
            print(enc);
            print(crypto.base64Decode(enc));
            """.trimIndent(),
            5_000,
        )
        assertTrue(r.error ?: "", r.isSuccess)
        val lines = r.data?.trim()?.lines() ?: emptyList()
        assertEquals("aGVsbG8gd29ybGQ=", lines[0])
        assertEquals("hello world", lines[1])
    }

    // ── datetime 模块 ─────────────────────────────────────────────────────

    @Test
    fun `datetime now returns a number and format parse roundtrip`() {
        val r = ScriptSandbox.execute(
            """
            var t = datetime.now();
            print(typeof t);
            var s = datetime.format(t, "yyyy-MM-dd");
            var t2 = datetime.parse(s, "yyyy-MM-dd");
            print(t2 > 0);
            """.trimIndent(),
            5_000,
        )
        assertTrue(r.error ?: "", r.isSuccess)
        val lines = r.data?.trim()?.lines() ?: emptyList()
        assertEquals("number", lines[0])
        assertEquals("true", lines[1])
    }

    // ── uuid ──────────────────────────────────────────────────────────────

    @Test
    fun `uuid returns a valid uuid string`() {
        val r = ScriptSandbox.execute(
            """var u = uuid(); print(typeof u + ':' + u);""",
            5_000,
        )
        assertTrue(r.error ?: "", r.isSuccess)
        val out = r.data?.trim() ?: ""
        assertTrue("expected string prefix: $out", out.startsWith("string:"))
        val uuidRegex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        val value = out.removePrefix("string:")
        assertTrue("not a valid uuid: $value", uuidRegex.matches(value))
    }

    // ── 文件管理扩展 ─────────────────────────────────────────────────────

    @Test
    fun `file management mkdir exists writeFile listFiles deleteFile work`() {
        val r = ScriptSandbox.execute(
            """
            mkdir(WORKSPACE + "sub/");
            print(exists(WORKSPACE + "sub/"));
            writeFile(WORKSPACE + "sub/a.txt", "hello");
            writeFile(WORKSPACE + "sub/b.txt", "world");
            var names = listFiles(WORKSPACE + "sub/");
            names.sort();
            print(names.join(","));
            print(readFile(WORKSPACE + "sub/a.txt"));
            deleteFile(WORKSPACE + "sub/a.txt");
            print(exists(WORKSPACE + "sub/a.txt"));
            print(exists(WORKSPACE + "sub/b.txt"));
            """.trimIndent(),
            5_000,
        )
        assertTrue(r.error ?: "", r.isSuccess)
        val lines = r.data?.trim()?.lines() ?: emptyList()
        assertEquals("mkdir+exists", "true", lines[0])
        assertEquals("a.txt,b.txt", lines[1])
        assertEquals("hello", lines[2])
        assertEquals("false", lines[3])
        assertEquals("true", lines[4])
    }

    @Test
    fun `deleteFile refuses to delete directory`() {
        ScriptSandbox.execute(
            """mkdir(WORKSPACE + "dir/");""", 5_000,
        )
        val r = ScriptSandbox.execute(
            """deleteFile(WORKSPACE + "dir/");""", 5_000,
        )
        assertFalse("deleting directory should fail", r.isSuccess)
        val err = r.error ?: ""
        assertTrue(
            "error should mention directory refused: $err",
            err.contains("directory") || err.contains("目录"),
        )
    }

    @Test
    fun `unsafe path is rejected for file operations`() {
        // /etc/passwd 不在安全路径前缀内
        val r = ScriptSandbox.execute(
            """readFile("/etc/passwd");""", 5_000,
        )
        assertFalse("unsafe path should be rejected", r.isSuccess)
        assertNotNull(r.error)
    }

    // ── 会话模式兼容新 API ───────────────────────────────────────────────

    @Test
    fun `session mode supports crypto datetime uuid and files`() {
        val sid = "compat-${System.nanoTime()}"
        val r = ScriptSandbox.executeInSession(
            sid,
            """
            var h = crypto.md5("session");
            var u = uuid();
            var t = datetime.format(datetime.now(), "yyyy");
            writeFile(WORKSPACE + "s.txt", h);
            print(h.length === 32);
            print(u.length === 36);
            print(t.length === 4);
            print(readFile(WORKSPACE + "s.txt") === h);
            """.trimIndent(),
            5_000,
        )
        assertTrue(r.error ?: "", r.isSuccess)
        val lines = r.data?.trim()?.lines() ?: emptyList()
        assertEquals(listOf("true", "true", "true", "true"), lines)
    }

    @Test
    fun `async function syntax is rejected with friendly hint`() {
        val code = "async function load() { return 1; }"
        val r = ScriptSandbox.execute(code, 5_000L)
        assertFalse(r.isSuccess)
        assertNotNull(r.error)
        assertTrue(
            "error should mention async/await: ${r.error}",
            r.error!!.contains("async/await", ignoreCase = true),
        )
        assertTrue(
            "error should suggest Promise + .then: ${r.error}",
            r.error!!.contains("Promise", ignoreCase = true),
        )
    }

    @Test
    fun `await syntax is rejected with friendly hint`() {
        val code = "function load() { const r = await fetch('x'); return r; }"
        val r = ScriptSandbox.execute(code, 5_000L)
        assertFalse(r.isSuccess)
        assertTrue(
            "error should mention async/await: ${r.error}",
            r.error!!.contains("async/await", ignoreCase = true),
        )
    }

    @Test
    fun `async arrow function syntax is rejected`() {
        val code = "const load = async () => 1;"
        val r = ScriptSandbox.execute(code, 5_000L)
        assertFalse(r.isSuccess)
        assertTrue(r.error!!.contains("async/await", ignoreCase = true))
    }
}
