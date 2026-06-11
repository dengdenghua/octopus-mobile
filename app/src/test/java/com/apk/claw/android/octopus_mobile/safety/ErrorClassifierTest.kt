package com.apk.claw.android.octopus_mobile.safety

import org.junit.Assert.*
import org.junit.Test

/**
 * ErrorClassifier 测试 —— 7 类错误 + 自动修复策略.
 */
class ErrorClassifierTest {

    @Test
    fun `classify rate limit`() {
        val c = ErrorClassifier.classifyMessage("rate limit exceeded", statusCode = 429)
        assertEquals(ErrorClassifier.ErrorCategory.RATE_LIMIT, c.category)
        assertEquals(ErrorClassifier.RecoveryAction.RETRY_WITH_BACKOFF, c.action)
        assertTrue(c.isRetryable)
        assertEquals(30.0, c.backoffSec, 0.001)
    }

    @Test
    fun `classify auth error`() {
        val c = ErrorClassifier.classifyMessage("invalid api key", statusCode = 401)
        assertEquals(ErrorClassifier.ErrorCategory.AUTH, c.category)
        assertEquals(ErrorClassifier.RecoveryAction.SWITCH_KEY, c.action)
        assertTrue(c.isRetryable)
    }

    @Test
    fun `classify timeout`() {
        val c = ErrorClassifier.classifyMessage("connection timeout", statusCode = 504)
        assertEquals(ErrorClassifier.ErrorCategory.TIMEOUT, c.category)
        assertEquals(ErrorClassifier.RecoveryAction.RETRY, c.action)
        assertTrue(c.isRetryable)
    }

    @Test
    fun `classify content filter`() {
        val c = ErrorClassifier.classifyMessage("content filtered by safety policy")
        assertEquals(ErrorClassifier.ErrorCategory.CONTENT_FILTER, c.category)
        assertEquals(ErrorClassifier.RecoveryAction.ABORT, c.action)
        assertFalse(c.isRetryable)
    }

    @Test
    fun `classify context length`() {
        val c = ErrorClassifier.classifyMessage("maximum context length exceeded")
        assertEquals(ErrorClassifier.ErrorCategory.CONTEXT_LENGTH, c.category)
        assertEquals(ErrorClassifier.RecoveryAction.REDUCE_CONTEXT, c.action)
        assertTrue(c.isRetryable)
    }

    @Test
    fun `classify server error`() {
        val c = ErrorClassifier.classifyMessage("internal server error", statusCode = 500)
        assertEquals(ErrorClassifier.ErrorCategory.SERVER, c.category)
        assertEquals(ErrorClassifier.RecoveryAction.RETRY_WITH_BACKOFF, c.action)
        assertTrue(c.isRetryable)
    }

    @Test
    fun `classify network error`() {
        val c = ErrorClassifier.classifyMessage("ECONNREFUSED")
        assertEquals(ErrorClassifier.ErrorCategory.NETWORK, c.category)
        assertEquals(ErrorClassifier.RecoveryAction.RETRY, c.action)
        assertTrue(c.isRetryable)
    }

    @Test
    fun `classify unknown`() {
        val c = ErrorClassifier.classifyMessage("something weird happened")
        assertEquals(ErrorClassifier.ErrorCategory.UNKNOWN, c.category)
        assertEquals(ErrorClassifier.RecoveryAction.IGNORE, c.action)
        assertFalse(c.isRetryable)
    }

    @Test
    fun `classify Chinese rate limit`() {
        val c = ErrorClassifier.classifyMessage("请求过多，频率限制")
        assertEquals(ErrorClassifier.ErrorCategory.RATE_LIMIT, c.category)
    }

    @Test
    fun `classify Chinese auth`() {
        val c = ErrorClassifier.classifyMessage("密钥无效，认证失败")
        assertEquals(ErrorClassifier.ErrorCategory.AUTH, c.category)
    }

    @Test
    fun `classify from exception`() {
        val e = RuntimeException("timeout after 30s")
        val c = ErrorClassifier.classify(e)
        assertEquals(ErrorClassifier.ErrorCategory.TIMEOUT, c.category)
    }
}
