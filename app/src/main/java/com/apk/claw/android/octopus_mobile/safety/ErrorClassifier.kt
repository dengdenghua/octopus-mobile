package com.apk.claw.android.octopus_mobile.safety

import android.util.Log

/**
 * 错误分类器 —— 从母体 runtime/memory/error_classifier.py 移植.
 *
 * 把错误分成 7 类，每类对应一个自动修复策略：
 *
 * | 类别 | 策略 |
 * |---|---|
 * | rate_limit | 退避重试 |
 * | auth | 换凭证 |
 * | timeout | 重试 |
 * | content_filter | 终止 |
 * | context_length | 压缩上下文后重试 |
 * | server | 退避重试 |
 * | network | 重试 |
 * | tool | 忽略/换方法 |
 *
 * 用法：
 * ```kotlin
 * val classification = ErrorClassifier.classify(exception, statusCode = 429)
 * if (classification.isRetryable) {
 *     Thread.sleep((classification.backoffSec * 1000).toLong())
 *     retry()
 * }
 * ```
 */
object ErrorClassifier {

    private const val TAG = "ErrorClassifier"

    // ── 枚举 ──────────────────────────────────────────

    enum class ErrorCategory {
        RATE_LIMIT, AUTH, TIMEOUT, CONTENT_FILTER,
        CONTEXT_LENGTH, SERVER, NETWORK, TOOL, UNKNOWN
    }

    enum class RecoveryAction {
        RETRY, RETRY_WITH_BACKOFF, SWITCH_KEY, SWITCH_MODEL,
        REDUCE_CONTEXT, ABORT, IGNORE
    }

    // ── 分类结果 ──────────────────────────────────────

    data class ErrorClassification(
        val category: ErrorCategory,
        val action: RecoveryAction,
        val isRetryable: Boolean,
        val backoffSec: Double,
        val message: String,
    )

    // ── 模式 ──────────────────────────────────────────

    private val RATE_LIMIT_PATTERNS = listOf(
        Regex("rate.?limit", RegexOption.IGNORE_CASE),
        Regex("too many requests", RegexOption.IGNORE_CASE),
        Regex("429"),
        Regex("quota exceeded", RegexOption.IGNORE_CASE),
        Regex("requests per minute", RegexOption.IGNORE_CASE),
        Regex("频率限制|请求过多|限流", RegexOption.IGNORE_CASE),
    )

    private val AUTH_PATTERNS = listOf(
        Regex("invalid.?api.?key", RegexOption.IGNORE_CASE),
        Regex("unauthorized", RegexOption.IGNORE_CASE),
        Regex("401"),
        Regex("authentication", RegexOption.IGNORE_CASE),
        Regex("invalid.?credential", RegexOption.IGNORE_CASE),
        Regex("密钥无效|未授权|认证失败", RegexOption.IGNORE_CASE),
    )

    private val TIMEOUT_PATTERNS = listOf(
        Regex("timeout", RegexOption.IGNORE_CASE),
        Regex("timed?out", RegexOption.IGNORE_CASE),
        Regex("deadline exceeded", RegexOption.IGNORE_CASE),
        Regex("504"),
        Regex("gateway.?timeout", RegexOption.IGNORE_CASE),
        Regex("超时|超时了", RegexOption.IGNORE_CASE),
    )

    private val CONTENT_FILTER_PATTERNS = listOf(
        Regex("content.?filter", RegexOption.IGNORE_CASE),
        Regex("safety", RegexOption.IGNORE_CASE),
        Regex("policy", RegexOption.IGNORE_CASE),
        Regex("flagged", RegexOption.IGNORE_CASE),
        Regex("refused", RegexOption.IGNORE_CASE),
        Regex("内容过滤|安全策略|被标记", RegexOption.IGNORE_CASE),
    )

    private val CONTEXT_LENGTH_PATTERNS = listOf(
        Regex("context.?length", RegexOption.IGNORE_CASE),
        Regex("maximum.?context", RegexOption.IGNORE_CASE),
        Regex("token.?limit", RegexOption.IGNORE_CASE),
        Regex("too many tokens", RegexOption.IGNORE_CASE),
        Regex("上下文过长|token超限", RegexOption.IGNORE_CASE),
    )

    private val SERVER_PATTERNS = listOf(
        Regex("500"), Regex("502"), Regex("503"),
        Regex("internal.?server.?error", RegexOption.IGNORE_CASE),
        Regex("service.?unavailable", RegexOption.IGNORE_CASE),
        Regex("服务器错误|服务不可用", RegexOption.IGNORE_CASE),
    )

    private val NETWORK_PATTERNS = listOf(
        Regex("connection", RegexOption.IGNORE_CASE),
        Regex("network", RegexOption.IGNORE_CASE),
        Regex("ECONNREFUSED", RegexOption.IGNORE_CASE),
        Regex("ENOTFOUND", RegexOption.IGNORE_CASE),
        Regex("dns", RegexOption.IGNORE_CASE),
        Regex("连接失败|网络错误|DNS", RegexOption.IGNORE_CASE),
    )

    // ── 分类 ──────────────────────────────────────────

    fun classify(
        error: Throwable,
        statusCode: Int? = null,
        provider: String = "",
    ): ErrorClassification {
        val msg = error.message ?: error.toString()
        return classifyMessage(msg, statusCode, provider)
    }

    fun classifyMessage(
        msg: String,
        statusCode: Int? = null,
        provider: String = "",
    ): ErrorClassification {
        // 429 限流
        if (statusCode == 429 || matches(msg, RATE_LIMIT_PATTERNS)) {
            return ErrorClassification(
                category = ErrorCategory.RATE_LIMIT,
                action = RecoveryAction.RETRY_WITH_BACKOFF,
                isRetryable = true,
                backoffSec = 30.0,
                message = "限流，退避重试或换凭证",
            )
        }

        // 401 认证
        if (statusCode == 401 || matches(msg, AUTH_PATTERNS)) {
            return ErrorClassification(
                category = ErrorCategory.AUTH,
                action = RecoveryAction.SWITCH_KEY,
                isRetryable = true,
                backoffSec = 0.0,
                message = "认证错误，换凭证",
            )
        }

        // 超时
        if (statusCode in listOf(504, 408) || matches(msg, TIMEOUT_PATTERNS)) {
            return ErrorClassification(
                category = ErrorCategory.TIMEOUT,
                action = RecoveryAction.RETRY,
                isRetryable = true,
                backoffSec = 5.0,
                message = "超时，重试",
            )
        }

        // 内容过滤
        if (matches(msg, CONTENT_FILTER_PATTERNS)) {
            return ErrorClassification(
                category = ErrorCategory.CONTENT_FILTER,
                action = RecoveryAction.ABORT,
                isRetryable = false,
                backoffSec = 0.0,
                message = "内容被过滤，终止",
            )
        }

        // 上下文过长
        if (matches(msg, CONTEXT_LENGTH_PATTERNS)) {
            return ErrorClassification(
                category = ErrorCategory.CONTEXT_LENGTH,
                action = RecoveryAction.REDUCE_CONTEXT,
                isRetryable = true,
                backoffSec = 0.0,
                message = "上下文过长，压缩后重试",
            )
        }

        // 服务器错误
        if (statusCode in listOf(500, 502, 503) || matches(msg, SERVER_PATTERNS)) {
            return ErrorClassification(
                category = ErrorCategory.SERVER,
                action = RecoveryAction.RETRY_WITH_BACKOFF,
                isRetryable = true,
                backoffSec = 10.0,
                message = "服务器错误，退避重试",
            )
        }

        // 网络错误
        if (matches(msg, NETWORK_PATTERNS)) {
            return ErrorClassification(
                category = ErrorCategory.NETWORK,
                action = RecoveryAction.RETRY,
                isRetryable = true,
                backoffSec = 3.0,
                message = "网络错误，重试",
            )
        }

        return ErrorClassification(
            category = ErrorCategory.UNKNOWN,
            action = RecoveryAction.IGNORE,
            isRetryable = false,
            backoffSec = 0.0,
            message = "未知错误",
        )
    }

    private fun matches(text: String, patterns: List<Regex>): Boolean {
        return patterns.any { it.containsMatchIn(text) }
    }
}
