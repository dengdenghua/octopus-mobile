package com.apk.claw.android.octopus_mobile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校验 [AuditChain] 哈希链核心算法。
 *
 * 这是纯函数测试,不依赖 Android/KVUtils —— `AuditChain` 在 `app` 模块同包下,
 * 通过 `internal` 可见性直接测试。覆盖:
 *  - 签名确定性 + 链依赖
 *  - 校验 happy path
 *  - 攻击检测:改内容、删中间、调序、删最新(headAnchor 兜底)
 *  - 旧方案兼容(prevHash == null 走 signLegacy)
 *  - 边界:空链 / 单一锚点 / headAnchor=null / headAnchor=空串
 *  - 已知局限:删最旧条(裁剪边界)无法与正常 trim 区分
 */
class AuditChainTest {

    // 用一个固定 secret 测确定性;有效 base64(32 字节 → 44 字符带 padding)。
    private val secret = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="

    private fun payload(seed: Int) = "payload_$seed"

    // ── sign / signLegacy / generateSecret ──

    @Test
    fun `sign is deterministic for same payload and prevHash`() {
        val a = AuditChain.sign(secret, payload(1), AuditChain.GENESIS)
        val b = AuditChain.sign(secret, payload(1), AuditChain.GENESIS)
        assertEquals("相同输入必须产生相同签名", a, b)
    }

    @Test
    fun `sign differs when payload differs`() {
        val a = AuditChain.sign(secret, payload(1), AuditChain.GENESIS)
        val b = AuditChain.sign(secret, payload(2), AuditChain.GENESIS)
        assertNotEquals("不同 payload 应得不同签名", a, b)
    }

    @Test
    fun `sign differs when prevHash differs (chain dependency)`() {
        val a = AuditChain.sign(secret, payload(1), "prev_a")
        val b = AuditChain.sign(secret, payload(1), "prev_b")
        assertNotEquals("链式签名应受 prevHash 影响", a, b)
    }

    @Test
    fun `signLegacy differs from chained sign even for same payload`() {
        // signLegacy: HMAC(payload)
        // sign:        HMAC(payload | prevHash)
        // 即使 prevHash 设为空串,中间仍有 '|' 分隔符差异
        val legacy = AuditChain.signLegacy(secret, payload(1))
        val chained = AuditChain.sign(secret, payload(1), "")
        assertNotEquals("legacy 与 chained 不应等价(分隔符)", legacy, chained)
    }

    @Test
    fun `generateSecret returns unique 32-byte base64 values`() {
        val a = AuditChain.generateSecret()
        val b = AuditChain.generateSecret()
        assertNotNull(a); assertNotNull(b)
        assertNotEquals("两次随机应不同", a, b)
        // 32 字节 base64 = 44 字符(带末尾 padding '=')
        assertEquals("应为 32 字节 base64 编码(44 字符)", 44, a.length)
        // 能用 base64 解码回 32 字节
        val decoded = java.util.Base64.getDecoder().decode(a)
        assertEquals(32, decoded.size)
    }

    // ── verify: happy path ──

    @Test
    fun `verify on clean chain returns all clean`() {
        val (payloads, sigs, prevs) = buildChain(3)
        val flags = AuditChain.verify(secret, payloads, sigs, prevs, headAnchor = sigs[0])
        assertEquals(3, flags.size)
        assertTrue("干净链应全 false", flags.none { it })
    }

    @Test
    fun `verify on single-entry chain with GENESIS anchor returns clean`() {
        val sig = AuditChain.sign(secret, payload(1), AuditChain.GENESIS)
        val flags = AuditChain.verify(
            secret, listOf(payload(1)), listOf(sig), listOf(AuditChain.GENESIS),
            headAnchor = sig,
        )
        assertArrayEquals(BooleanArray(1), flags)
    }

    // ── verify: attack detection ──

    @Test
    fun `verify flags content tampering (own hash mismatch)`() {
        val (payloads, sigs, prevs) = buildChain(3)
        // 攻击者改了第 1 条(中间)的 payload
        val tamperedPayloads = payloads.toMutableList()
        tamperedPayloads[1] = "payload_999_attacker"
        val flags = AuditChain.verify(secret, tamperedPayloads, sigs, prevs, headAnchor = sigs[0])
        assertTrue("被改 payload 的条目应被标记", flags[1])
    }

    @Test
    fun `verify flags reorder (link broken)`() {
        val (payloads, sigs, prevs) = buildChain(3)
        // 调换 [0] 和 [2]:newest-first 列表里把最早/最晚调换,prev 链必然断裂
        val reorderedSigs = listOf(sigs[2], sigs[1], sigs[0])
        val reorderedPrevs = listOf(prevs[2], prevs[1], prevs[0])
        val flags = AuditChain.verify(
            secret, payloads, reorderedSigs, reorderedPrevs, headAnchor = sigs[2]  // headAnchor 是调换后的"首条"
        )
        assertTrue("调序后至少应有条目被标记", flags.any { it })
    }

    @Test
    fun `verify flags deletion of middle entry (link broken for successor)`() {
        val (payloads, sigs, prevs) = buildChain(4)
        // newest-first = [p3, p2, p1, p0],删 p2 后 = [p3, p1, p0]
        // p3.prev 指向已删的 p2.sig,与 p1.sig 不等 → p3 断链
        val keptPayloads = listOf(payloads[0], payloads[2], payloads[3])
        val keptSigs = listOf(sigs[0], sigs[2], sigs[3])
        val keptPrevs = listOf(prevs[0], prevs[2], prevs[3])
        val flags = AuditChain.verify(secret, keptPayloads, keptSigs, keptPrevs, headAnchor = sigs[0])
        assertTrue("删中间条后,首条应被标记为断链", flags[0])
    }

    @Test
    fun `verify flags deletion of newest entry via headAnchor mismatch`() {
        val (payloads, sigs, prevs) = buildChain(2)
        // headAnchor 持久化的是 sigs[0](原最新条)的签名
        // 删了最新条后,首条变成 sigs[1] ≠ headAnchor → 标记
        val keptPayloads = listOf(payloads[1])
        val keptSigs = listOf(sigs[1])
        val keptPrevs = listOf(prevs[1])
        val flags = AuditChain.verify(
            secret,
            payloads = keptPayloads,
            sigs = keptSigs,
            prevHashes = keptPrevs,
            headAnchor = sigs[0],  // 仍指向已删的首条
        )
        assertArrayEquals("删最新条后首条应标记", booleanArrayOf(true), flags)
    }

    @Test
    fun `verify passes when headAnchor matches current head`() {
        val (payloads, sigs, prevs) = buildChain(2)
        val flags = AuditChain.verify(secret, payloads, sigs, prevs, headAnchor = sigs[0])
        assertFalse(flags[0])
    }

    // ── verify: legacy / mixed entries ──

    @Test
    fun `verify accepts legacy entries with null prevHash using signLegacy`() {
        val p = payload(1)
        val sig = AuditChain.signLegacy(secret, p)
        val flags = AuditChain.verify(
            secret, listOf(p), listOf(sig), listOf<String?>(null), headAnchor = null,
        )
        assertArrayEquals("旧方案条目应通过校验", BooleanArray(1), flags)
    }

    @Test
    fun `verify with mixed legacy and chained entries`() {
        // 1 旧(无 prevHash) + 1 新(链到旧的 sig)
        val legacyPayload = payload(0)
        val legacySig = AuditChain.signLegacy(secret, legacyPayload)

        val newPayload = payload(1)
        val newSig = AuditChain.sign(secret, newPayload, legacySig)
        val newPrev = legacySig

        val flags = AuditChain.verify(
            secret,
            payloads = listOf(newPayload, legacyPayload),  // newest-first
            sigs = listOf(newSig, legacySig),
            prevHashes = listOf<String?>(newPrev, null),
            headAnchor = newSig,
        )
        assertArrayEquals("新旧混合链应全通过", BooleanArray(2), flags)
    }

    // ── verify: 边界 / 健壮性 ──

    @Test
    fun `verify on empty list returns empty array`() {
        val flags = AuditChain.verify(secret, emptyList(), emptyList(), emptyList(), headAnchor = null)
        assertEquals(0, flags.size)
    }

    @Test
    fun `verify with null headAnchor is safe (no crash)`() {
        val (payloads, sigs, prevs) = buildChain(2)
        val flags = AuditChain.verify(secret, payloads, sigs, prevs, headAnchor = null)
        assertFalse("null headAnchor 不应让任何条目被标记", flags.any { it })
    }

    @Test
    fun `verify with empty-string headAnchor is safe`() {
        val (payloads, sigs, prevs) = buildChain(2)
        val flags = AuditChain.verify(secret, payloads, sigs, prevs, headAnchor = "")
        assertFalse("空 headAnchor 不应让任何条目被标记", flags.any { it })
    }

    @Test
    fun `verify with wrong secret flags all entries`() {
        val (payloads, sigs, prevs) = buildChain(2)
        val otherSecret = "ERITu0FCQ0FfQUNBREJBRENEQ0FEQ0FEQ0FEQ0FEQ0FEQ0FEQ0FEQ0FEQA=="
        val flags = AuditChain.verify(otherSecret, payloads, sigs, prevs, headAnchor = sigs[0])
        assertTrue("密钥错配应让所有条目被标记", flags.all { it })
    }

    @Test
    fun `verify with null signature on legacy entry is flagged`() {
        // 旧条目但 sig 为 null(数据损坏) → ownValid=false → 标记
        val p = payload(1)
        val flags = AuditChain.verify(
            secret, listOf(p), listOf<String?>(null), listOf<String?>(null), headAnchor = null,
        )
        assertArrayEquals(booleanArrayOf(true), flags)
    }

    /**
     * 已知局限:删最旧条(裁剪边界)无法与正常 trim 区分。
     * 此测试钉死行为,避免后续实现把这条"该放过"误改成"该报"。
     */
    @Test
    fun `deleting the oldest entry is NOT detected (known limitation)`() {
        val (payloads, sigs, prevs) = buildChain(3)
        // newest-first = [p2, p1, p0],删最旧的 p0 后 = [p2, p1]
        // p1 的 prev 仍指 p0.sig,校验逻辑要求 i < n-1 才检查 linkBroken
        // → 删最旧条时无任何条目被标记
        val keptPayloads = listOf(payloads[0], payloads[1])
        val keptSigs = listOf(sigs[0], sigs[1])
        val keptPrevs = listOf(prevs[0], prevs[1])
        val flags = AuditChain.verify(secret, keptPayloads, keptSigs, keptPrevs, headAnchor = sigs[0])
        assertTrue("删最旧条为已知局限,应不被检出", flags.none { it })
    }

    // ── helper ──

    /**
     * 构造 newest-first 链,返回 (payloads, sigs, prevs)。
     * 时间序为 p0 < p1 < ... < p(n-1);newest-first 列表首元素是 p(n-1)。
     *
     * 链路规则(对齐 ToolAuditLog/RemoteAccessLog 的 wrapper):
     *  - entry[i] (newest-first) 的 prev = entry[i+1] 的 sig
     *  - 最末一条(最旧)的 prev = GENESIS
     *  - forward 链:p_k.prev = s_(k-1) (k≥1);p0.prev = GENESIS
     */
    private fun buildChain(n: Int): Triple<List<String>, List<String>, List<String>> {
        require(n >= 1)
        val payloads = (0 until n).map { payload(it) }
        val sigsNewestFirst = ArrayList<String>(n)
        val prevsNewestFirst = ArrayList<String>(n)
        // 正向算 sig(p_k, prev=s_(k-1))
        var prev = AuditChain.GENESIS
        val orderedSigs = ArrayList<String>(n)
        for (p in payloads) {
            val s = AuditChain.sign(secret, p, prev)
            orderedSigs.add(s)
            prev = s
        }
        // 倒置成 newest-first,prevs[i] = s_(i-1) (forward 中 p_i 的前驱),
        // 最末一条(i=0 in forward,即 newest-first 的 index n-1)→ GENESIS
        for (i in n - 1 downTo 0) {
            sigsNewestFirst.add(orderedSigs[i])
            prevsNewestFirst.add(if (i == 0) AuditChain.GENESIS else orderedSigs[i - 1])
        }
        return Triple(payloads.reversed(), sigsNewestFirst, prevsNewestFirst)
    }
}
