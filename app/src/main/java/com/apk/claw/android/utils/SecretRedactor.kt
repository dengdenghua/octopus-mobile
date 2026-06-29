package com.apk.claw.android.utils

import java.util.regex.Pattern

/**
 * 在日志 / 审计文本中按正则脱敏密钥、令牌、密码与常见 PII（验证码 / 手机号 / 邮箱）。
 *
 * 已接入 [XLog]（logcat 脱敏）与 ToolRiskPolicy.summarizeParams/summarizeResult（审计日志脱敏）。
 * 注意：基于值的正则脱敏是尽力而为，覆盖常见格式，无法保证捕获所有秘密。
 */
object SecretRedactor {

    private val PATTERNS: List<Pair<Pattern, String>> = listOf(
        // OpenAI 项目密钥（sk-proj-…，含 - / _）。排在 sk- 之前；与下面的 sk- 针对不同格式，
        // 并不冗余：sk-[A-Za-z0-9]{20,} 遇到 sk-proj- 的连字符会在第 4 字符断开（<20）而不匹配。
        Pattern.compile("(sk-proj-[A-Za-z0-9_-]{20,})") to "[API_KEY_REDACTED]",
        Pattern.compile("(sk-[A-Za-z0-9]{20,})") to "[API_KEY_REDACTED]",
        Pattern.compile("(Bearer\\s+[A-Za-z0-9._-]{10,})") to "Bearer [TOKEN_REDACTED]",
        Pattern.compile("(api[_-]?key[\"'=:\\s]+[A-Za-z0-9._-]{10,})") to "api_key=[KEY_REDACTED]",
        Pattern.compile("(token[\"'=:\\s]+[A-Za-z0-9._-]{10,})") to "token=[TOKEN_REDACTED]",
        Pattern.compile("(password[\"'=:\\s]+\\S+)") to "password=[PASSWORD_REDACTED]",
        Pattern.compile("(secret[\"'=:\\s]+[A-Za-z0-9._-]{10,})") to "secret=[SECRET_REDACTED]",
        Pattern.compile("(Authorization:\\s*Bearer\\s+[A-Za-z0-9._-]+)", Pattern.CASE_INSENSITIVE) to "Authorization: Bearer [TOKEN_REDACTED]",
        Pattern.compile("(\"api_key\"\\s*:\\s*\"[^\"]+\")") to "\"api_key\":\"[KEY_REDACTED]\"",
        Pattern.compile("(\"token\"\\s*:\\s*\"[^\"]+\")") to "\"token\":\"[TOKEN_REDACTED]\"",
        Pattern.compile("(\"password\"\\s*:\\s*\"[^\"]+\")") to "\"password\":\"[PASSWORD_REDACTED]\"",
        // 验证码 / OTP：上下文触发（紧跟"验证码/校验码/code/otp"等的 4–8 位数字），避免误伤普通数字。
        // group 1 保留标签前缀，group 2 为被脱敏的数字。
        Pattern.compile(
            "((?:验证码|校验码|动态码|verification\\s*code|verify\\s*code|otp|one[-\\s]?time\\s*(?:code|password))\\D{0,8})(\\d{4,8})",
            Pattern.CASE_INSENSITIVE,
        ) to "$1[CODE_REDACTED]",
        // 中国大陆手机号（前后非数字，避免命中长数字串的子串）
        Pattern.compile("(?<![0-9])(1[3-9]\\d{9})(?![0-9])") to "[PHONE_REDACTED]",
        // 邮箱
        Pattern.compile("([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})") to "[EMAIL_REDACTED]",
    )

    @JvmStatic
    fun redact(input: String?): String? {
        if (input == null) return null
        var result: String = input
        for ((pattern, replacement) in PATTERNS) {
            result = pattern.matcher(result).replaceAll(replacement)
        }
        return result
    }
}
