package com.apk.claw.android.octopus_mobile.safety

import android.util.Log

/**
 * PII / Secret 扫描器 —— 从母体 runtime/safety/constitution/rules.py 移植.
 *
 * 两层扫描：
 *  1. **PII 扫描**（PRIV-2）：邮箱 / 手机号 / 身份证号 → 替换为占位符
 *  2. **Secret 扫描**（PRIV-4）：API Key / 私钥 → 直接阻止
 *
 * 用法：
 * ```kotlin
 * val scanner = PrivacyScanner()
 * val (cleanText, piiHits) = scanner.scrubPii(text)
 * val secretHits = scanner.scanSecrets(text)
 * if (secretHits.isNotEmpty()) { /* 阻止发送 */ }
 * ```
 */
object PrivacyScanner {

    private const val TAG = "PrivacyScanner"

    // ── PII 模式（替换，不阻止）──────────────────────

    private data class PiiPattern(
        val clauseId: String,
        val regex: Regex,
        val placeholder: String,
        val description: String,
    )

    private val piiPatterns = listOf(
        // 邮箱
        PiiPattern(
            "PRIV-2",
            Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"""),
            "[REDACTED:email]",
            "email address",
        ),
        // 中国手机号 11 位（13/14/15/16/17/18/19 开头）
        PiiPattern(
            "PRIV-2",
            Regex("""(?<!\d)1[3-9]\d{9}(?!\d)"""),
            "[REDACTED:phone]",
            "CN mobile",
        ),
        // 国际电话 +CC NNN...
        PiiPattern(
            "PRIV-2",
            Regex("""(?<!\d)\+\d{1,3}[\-\s]?\d{4,14}(?!\d)"""),
            "[REDACTED:phone]",
            "international phone",
        ),
        // 中国身份证号 18 位（最后一位可以是 X）
        PiiPattern(
            "PRIV-2",
            Regex("""(?<!\d)\d{17}[\dXx](?!\d)"""),
            "[REDACTED:cn-id]",
            "CN national ID",
        ),
        // 银行卡号（16-19 位纯数字）
        PiiPattern(
            "PRIV-2",
            Regex("""(?<!\d)\d{16,19}(?!\d)"""),
            "[REDACTED:bank-card]",
            "bank card number",
        ),
    )

    // ── Secret 模式（阻止，不替换）────────────────────

    private data class SecretPattern(
        val clauseId: String,
        val regex: Regex,
        val description: String,
    )

    private val secretPatterns = listOf(
        // Anthropic API Key
        SecretPattern(
            "PRIV-4",
            Regex("""\bsk-ant-[A-Za-z0-9_\-]{20,}"""),
            "Anthropic API key",
        ),
        // 新式 OpenAI 密钥(sk-proj-… / sk-svcacct-… / sk-admin-…):body 含连字符,
        // 通用 sk- 正则(字符类不含 '-')会在首个连字符处截断致 20+ 判定失败 → 漏检。
        // 单列专门模式(排在通用 sk- 之前),避免放宽通用模式而误吞 sk-ant-(Anthropic)。
        SecretPattern(
            "PRIV-4",
            Regex("""\bsk-(?:proj|svcacct|admin)-[A-Za-z0-9_-]{20,}"""),
            "OpenAI API key",
        ),
        // OpenAI / generic sk- API Key
        SecretPattern(
            "PRIV-4",
            Regex("""\bsk-[A-Za-z0-9]{20,}"""),
            "OpenAI API key",
        ),
        // AWS Access Key ID
        SecretPattern(
            "PRIV-4",
            Regex("""\bAKIA[0-9A-Z]{16}\b"""),
            "AWS access key",
        ),
        // GitHub PAT
        SecretPattern(
            "PRIV-4",
            Regex("""\bghp_[A-Za-z0-9]{30,}"""),
            "GitHub PAT",
        ),
        // Private Key
        SecretPattern(
            "PRIV-4",
            Regex("""-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"""),
            "private key material",
        ),
        // 腾讯云 SecretId
        SecretPattern(
            "PRIV-4",
            Regex("""\bAKID[A-Za-z0-9]{32}\b"""),
            "Tencent Cloud SecretId",
        ),
    )

    // ── 扫描结果 ──────────────────────────────────────

    data class RuleHit(
        val clauseId: String,
        val category: String,  // "pii" | "secret"
        val description: String,
        val matchedTextShort: String,  // 只保留前 6 字符 + 长度
    )

    data class ScrubResult(
        val text: String,
        val hits: List<RuleHit>,
    )

    // ── PII 扫描 ──────────────────────────────────────

    /**
     * 扫描 PII 并返回命中列表（不修改原文）.
     */
    fun scanPii(text: String): List<RuleHit> {
        val hits = mutableListOf<RuleHit>()
        for (pattern in piiPatterns) {
            for (match in pattern.regex.findAll(text)) {
                val matched = match.value
                hits.add(RuleHit(
                    clauseId = pattern.clauseId,
                    category = "pii",
                    description = pattern.description,
                    matchedTextShort = matched.take(6) + "…(len=${matched.length})",
                ))
            }
        }
        return hits
    }

    /**
     * 替换 PII 为占位符，返回 (清理后文本, 命中列表).
     */
    fun scrubPii(text: String): ScrubResult {
        var result = text
        val allHits = mutableListOf<RuleHit>()

        for (pattern in piiPatterns) {
            val hits = mutableListOf<Pair<Int, Int>>()  // (start, end)
            for (match in pattern.regex.findAll(result)) {
                hits.add(match.range.first to match.range.last + 1)
                allHits.add(RuleHit(
                    clauseId = pattern.clauseId,
                    category = "pii",
                    description = pattern.description,
                    matchedTextShort = match.value.take(6) + "…(len=${match.value.length})",
                ))
            }
            // 从后往前替换，避免索引偏移
            for ((start, end) in hits.sortedByDescending { it.first }) {
                result = result.substring(0, start) + pattern.placeholder + result.substring(end)
            }
        }

        if (allHits.isNotEmpty()) {
            Log.d(TAG, "PII scrubbed: ${allHits.size} hits (${allHits.map { it.description }.toSet()})")
        }

        return ScrubResult(text = result, hits = allHits)
    }

    // ── Secret 扫描 ───────────────────────────────────

    /**
     * 扫描 Secret 级别的敏感信息（API Key / 私钥）.
     * 这些不应该被替换后发送——应该直接阻止.
     */
    fun scanSecrets(text: String): List<RuleHit> {
        val hits = mutableListOf<RuleHit>()
        for (pattern in secretPatterns) {
            for (match in pattern.regex.findAll(text)) {
                val matched = match.value
                hits.add(RuleHit(
                    clauseId = pattern.clauseId,
                    category = "secret",
                    description = pattern.description,
                    // 永远不存储完整 secret
                    matchedTextShort = "${matched.take(6)}…(len=${matched.length})",
                ))
            }
        }
        if (hits.isNotEmpty()) {
            Log.w(TAG, "SECRET detected: ${hits.size} hits (${hits.map { it.description }.toSet()})")
        }
        return hits
    }

    /**
     * 一站式检查：先扫 Secret（阻止级），再扫 PII（替换级）.
     *
     * @return Triple(cleanText, secretHits, piiHits)
     *         如果 secretHits 非空，调用方应阻止发送
     */
    fun fullCheck(text: String): Triple<String, List<RuleHit>, List<RuleHit>> {
        val secretHits = scanSecrets(text)
        val (cleanText, piiHits) = scrubPii(text)
        return Triple(cleanText, secretHits, piiHits)
    }
}
