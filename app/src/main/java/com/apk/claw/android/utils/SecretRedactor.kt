package com.apk.claw.android.utils

import java.util.regex.Pattern

object SecretRedactor {

    private val PATTERNS: List<Pair<Pattern, String>> = listOf(
        Pattern.compile("(sk-[A-Za-z0-9]{20,})") to "[API_KEY_REDACTED]",
        Pattern.compile("(sk-proj-[A-Za-z0-9_-]{20,})") to "[API_KEY_REDACTED]",
        Pattern.compile("(Bearer\\s+[A-Za-z0-9._-]{10,})") to "Bearer [TOKEN_REDACTED]",
        Pattern.compile("(api[_-]?key[\"'=:\\s]+[A-Za-z0-9._-]{10,})") to "api_key=[KEY_REDACTED]",
        Pattern.compile("(token[\"'=:\\s]+[A-Za-z0-9._-]{10,})") to "token=[TOKEN_REDACTED]",
        Pattern.compile("(password[\"'=:\\s]+\\S+)") to "password=[PASSWORD_REDACTED]",
        Pattern.compile("(secret[\"'=:\\s]+[A-Za-z0-9._-]{10,})") to "secret=[SECRET_REDACTED]",
        Pattern.compile("(Authorization:\\s*Bearer\\s+[A-Za-z0-9._-]+)", Pattern.CASE_INSENSITIVE) to "Authorization: Bearer [TOKEN_REDACTED]",
        Pattern.compile("(\"api_key\"\\s*:\\s*\"[^\"]+\")") to "\"api_key\":\"[KEY_REDACTED]\"",
        Pattern.compile("(\"token\"\\s*:\\s*\"[^\"]+\")") to "\"token\":\"[TOKEN_REDACTED]\"",
        Pattern.compile("(\"password\"\\s*:\\s*\"[^\"]+\")") to "\"password\":\"[PASSWORD_REDACTED]\"",
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
