package com.apk.claw.android.octopus_mobile.safety

import org.junit.Assert.*
import org.junit.Test

/**
 * PrivacyScanner 测试 —— Secret 阻止层 + PII 替换层.
 */
class PrivacyScannerTest {

    // ── Secret：Anthropic / OpenAI ──────────────────────

    @Test
    fun `scanSecrets detects anthropic key`() {
        val hits = PrivacyScanner.scanSecrets("key: sk-ant-api03-AbCdEfGhIjKlMnOpQrSt")
        assertEquals(1, hits.size)
        assertEquals("Anthropic API key", hits[0].description)
        assertEquals("PRIV-4", hits[0].clauseId)
        assertEquals("secret", hits[0].category)
    }

    @Test
    fun `scanSecrets ignores anthropic key shorter than 20 chars`() {
        // sk-ant- 后只有 19 个字符 → Anthropic 不命中；sk- 后是 ant 加连字符 → OpenAI 也不命中
        val hits = PrivacyScanner.scanSecrets("sk-ant-abcdefghij123456789")
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `scanSecrets detects openai key`() {
        val hits = PrivacyScanner.scanSecrets("my key is sk-abc1234567890abcdefghij")
        assertEquals(1, hits.size)
        assertEquals("OpenAI API key", hits[0].description)
    }

    @Test
    fun `scanSecrets detects modern openai project key`() {
        // 新式 sk-proj-… 含连字符,旧通用 sk- 正则会漏检
        val hits = PrivacyScanner.scanSecrets("key: sk-proj-AbCdEf12345_gHiJkLmNoPqR-stUv")
        assertEquals(1, hits.size)
        assertEquals("OpenAI API key", hits[0].description)
    }

    @Test
    fun `scanSecrets detects openai service account key`() {
        val hits = PrivacyScanner.scanSecrets("key: sk-svcacct-AbCdEf12345gHiJkLmNoPqRstUv")
        assertEquals(1, hits.size)
        assertEquals("OpenAI API key", hits[0].description)
    }

    @Test
    fun `scanSecrets ignores sk- followed by fewer than 20 chars`() {
        // sk- 后只有 19 个字母数字 → 不命中
        val hits = PrivacyScanner.scanSecrets("my key is sk-abc1234567890abcdef")
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `scanSecrets requires word boundary before sk-`() {
        // risk- 里的 sk- 前面是字母，不在词边界上 → 不误报
        val hits = PrivacyScanner.scanSecrets("risk-managementplan20260101review")
        assertTrue(hits.isEmpty())
    }

    // ── Secret：AWS / GitHub / 腾讯云 ───────────────────

    @Test
    fun `scanSecrets detects aws access key`() {
        val hits = PrivacyScanner.scanSecrets("aws: AKIAIOSFODNN7EXAMPLE done")
        assertEquals(1, hits.size)
        assertEquals("AWS access key", hits[0].description)
    }

    @Test
    fun `scanSecrets ignores malformed aws key`() {
        // AKIA 后只有 15 位
        assertTrue(PrivacyScanner.scanSecrets("AKIAIOSFODNN7EXAMPL").isEmpty())
        // 小写不在 [0-9A-Z] 字符类内
        assertTrue(PrivacyScanner.scanSecrets("AKIAiosfodnn7example").isEmpty())
        // 16 位之后还有大写字母 → 尾部词边界不成立
        assertTrue(PrivacyScanner.scanSecrets("AKIAIOSFODNN7EXAMPLEZZ").isEmpty())
    }

    @Test
    fun `scanSecrets detects github pat`() {
        val hits = PrivacyScanner.scanSecrets("token ghp_AbCdEfGhIj0123456789KlMnOpQrSt987654")
        assertEquals(1, hits.size)
        assertEquals("GitHub PAT", hits[0].description)
    }

    @Test
    fun `scanSecrets ignores ghp_ shorter than 30 chars`() {
        // ghp_ 后只有 29 个字符
        val hits = PrivacyScanner.scanSecrets("token ghp_abcdefghij0123456789ABCDEFGHI")
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `scanSecrets detects tencent secret id`() {
        val hits = PrivacyScanner.scanSecrets("AKIDz8Lc9vXq2tR5mYw3nB7dF1gH4jK6pQsA")
        assertEquals(1, hits.size)
        assertEquals("Tencent Cloud SecretId", hits[0].description)
    }

    @Test
    fun `scanSecrets ignores akid shorter than 32 chars`() {
        // AKID 后只有 31 个字符
        val hits = PrivacyScanner.scanSecrets("AKIDz8Lc9vXq2tR5mYw3nB7dF1gH4jK6pQs")
        assertTrue(hits.isEmpty())
    }

    // ── Secret：私钥块 ──────────────────────────────────

    @Test
    fun `scanSecrets detects private key headers`() {
        for (header in listOf(
            "-----BEGIN PRIVATE KEY-----",
            "-----BEGIN RSA PRIVATE KEY-----",
            "-----BEGIN EC PRIVATE KEY-----",
            "-----BEGIN OPENSSH PRIVATE KEY-----",
        )) {
            val hits = PrivacyScanner.scanSecrets("$header\nMIIEowIBAAKCAQEA\n-----END PRIVATE KEY-----")
            assertEquals(header, 1, hits.size)
            assertEquals("private key material", hits[0].description)
        }
    }

    @Test
    fun `scanSecrets ignores public key header`() {
        val hits = PrivacyScanner.scanSecrets("-----BEGIN PUBLIC KEY-----\nMFwwDQ\n-----END PUBLIC KEY-----")
        assertTrue(hits.isEmpty())
    }

    // ── Secret：多命中 / 截断 ───────────────────────────

    @Test
    fun `scanSecrets reports multiple secrets`() {
        val text = "openai=sk-abc1234567890abcdefghij aws=AKIAIOSFODNN7EXAMPLE"
        val hits = PrivacyScanner.scanSecrets(text)
        assertEquals(2, hits.size)
        assertEquals(setOf("OpenAI API key", "AWS access key"), hits.map { it.description }.toSet())
    }

    @Test
    fun `scanSecrets never stores full secret`() {
        val hits = PrivacyScanner.scanSecrets("key sk-abc1234567890abcdefghij")
        assertEquals("sk-abc…(len=26)", hits[0].matchedTextShort)
        assertFalse(hits[0].matchedTextShort.contains("1234567890abcdefghij"))
    }

    // ── PII：scanPii（只报告，不修改）───────────────────

    @Test
    fun `scanPii detects email`() {
        val hits = PrivacyScanner.scanPii("contact alice@example.com now")
        assertEquals(1, hits.size)
        assertEquals("email address", hits[0].description)
        assertEquals("pii", hits[0].category)
        assertEquals("PRIV-2", hits[0].clauseId)
        assertEquals("alice@…(len=17)", hits[0].matchedTextShort)
    }

    @Test
    fun `scanPii reports overlapping patterns on 18-digit id`() {
        // 18 位纯数字同时落入身份证与银行卡（16-19 位）两个模式
        val hits = PrivacyScanner.scanPii("110101199003077575")
        assertEquals(2, hits.size)
        assertEquals(setOf("CN national ID", "bank card number"), hits.map { it.description }.toSet())
    }

    @Test
    fun `scanPii ignores out-of-range numbers`() {
        // 手机号嵌在更长数字串中 → 前后数字断言挡住
        assertTrue(PrivacyScanner.scanPii("21380013800").isEmpty())
        // 15 位数字不足银行卡下限
        assertTrue(PrivacyScanner.scanPii("622202001234567").isEmpty())
    }

    // ── PII：scrubPii（替换为占位符）────────────────────

    @Test
    fun `scrubPii redacts email`() {
        val result = PrivacyScanner.scrubPii("contact alice@example.com now")
        assertEquals("contact [REDACTED:email] now", result.text)
        assertEquals(1, result.hits.size)
        assertEquals("email address", result.hits[0].description)
    }

    @Test
    fun `scrubPii redacts cn mobile`() {
        val result = PrivacyScanner.scrubPii("call 13812345678 now")
        assertEquals("call [REDACTED:phone] now", result.text)
        assertEquals("CN mobile", result.hits[0].description)
    }

    @Test
    fun `scrubPii redacts international phone`() {
        val result = PrivacyScanner.scrubPii("dial +1 4155551234 now")
        assertEquals("dial [REDACTED:phone] now", result.text)
        assertEquals("international phone", result.hits[0].description)
    }

    @Test
    fun `scrubPii redacts cn id once despite bank card overlap`() {
        // 身份证模式在列表中先于银行卡执行并完成替换，18 位数字只产生一次命中
        val result = PrivacyScanner.scrubPii("id 110101199003077575 end")
        assertEquals("id [REDACTED:cn-id] end", result.text)
        assertEquals(1, result.hits.size)
        assertEquals("CN national ID", result.hits[0].description)
    }

    @Test
    fun `scrubPii redacts cn id ending with X`() {
        val result = PrivacyScanner.scrubPii("id 11010119900307757X end")
        assertEquals("id [REDACTED:cn-id] end", result.text)
        assertEquals(1, result.hits.size)
        assertEquals("CN national ID", result.hits[0].description)
    }

    @Test
    fun `scrubPii redacts bank card`() {
        val result = PrivacyScanner.scrubPii("card 6222020012345678 ok")
        assertEquals("card [REDACTED:bank-card] ok", result.text)
        assertEquals("bank card number", result.hits[0].description)
    }

    @Test
    fun `scrubPii redacts multiple pii in one text`() {
        val result = PrivacyScanner.scrubPii("发给 alice@example.com 或 13812345678")
        assertEquals("发给 [REDACTED:email] 或 [REDACTED:phone]", result.text)
        assertEquals(2, result.hits.size)
        assertEquals("email address", result.hits[0].description)
        assertEquals("CN mobile", result.hits[1].description)
    }

    @Test
    fun `scrubPii returns original text when nothing matches`() {
        val text = "今天天气不错, hello world 123"
        val result = PrivacyScanner.scrubPii(text)
        assertEquals(text, result.text)
        assertTrue(result.hits.isEmpty())
    }

    // ── fullCheck：一站式 ───────────────────────────────

    @Test
    fun `fullCheck returns secrets and scrubbed pii`() {
        val (cleanText, secretHits, piiHits) =
            PrivacyScanner.fullCheck("key sk-abc1234567890abcdefghij from alice@example.com")
        assertEquals(1, secretHits.size)
        assertEquals("OpenAI API key", secretHits[0].description)
        assertEquals(1, piiHits.size)
        assertEquals("email address", piiHits[0].description)
        // Secret 是阻止级，不做替换 —— 只有 PII 被脱敏
        assertEquals("key sk-abc1234567890abcdefghij from [REDACTED:email]", cleanText)
    }

    @Test
    fun `fullCheck passes clean text through untouched`() {
        val text = "hello, 一切正常"
        val (cleanText, secretHits, piiHits) = PrivacyScanner.fullCheck(text)
        assertEquals(text, cleanText)
        assertTrue(secretHits.isEmpty())
        assertTrue(piiHits.isEmpty())
    }
}
