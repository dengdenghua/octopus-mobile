package com.apk.claw.android.octopus_mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ConnectionDiagnostics 单测 —— 重连/失败计数与人类可读摘要的逻辑契约。
 * 时间戳注入，断言确定。
 */
class ConnectionDiagnosticsTest {

    @Test
    fun `fresh diagnostics is offline with no failures`() {
        val d = ConnectionDiagnostics()
        val s = d.snapshot()
        assertFalse(s.online)
        assertEquals(0, s.totalReconnectAttempts)
        assertEquals(0, s.consecutiveFailures)
        assertNull(s.lastFailureReason)
        assertEquals("未连接", d.summary(1000))
    }

    @Test
    fun `connected clears consecutive failures and sets online`() {
        val d = ConnectionDiagnostics()
        d.onDisconnected("timeout", 1000)
        d.onDisconnected("503", 2000)
        assertEquals(2, d.snapshot().consecutiveFailures)

        d.onConnected(3000)
        val s = d.snapshot()
        assertTrue(s.online)
        assertEquals(0, s.consecutiveFailures)
        assertEquals(3000, s.lastConnectedAtMs)
        // 失败原因仍保留（历史），但连续失败已清零
        assertEquals("503", s.lastFailureReason)
    }

    @Test
    fun `normal close without reason does not count as failure`() {
        val d = ConnectionDiagnostics()
        d.onDisconnected(null, 1000)
        val s = d.snapshot()
        assertEquals(0, s.consecutiveFailures)
        assertNull(s.lastFailureReason)
        assertFalse(s.online)
    }

    @Test
    fun `reconnect attempts accumulate and never reset on connect`() {
        val d = ConnectionDiagnostics()
        d.onReconnectAttempt()
        d.onReconnectAttempt()
        d.onConnected(5000)
        d.onReconnectAttempt()
        assertEquals(3, d.snapshot().totalReconnectAttempts)
    }

    @Test
    fun `summary while reconnecting shows failures reason and offline duration`() {
        val d = ConnectionDiagnostics()
        d.onDisconnected("Connection failed: timeout", 10_000)
        // 已离线 90 秒后查询
        val summary = d.summary(100_000)
        assertTrue(summary.contains("断线重连中"))
        assertTrue(summary.contains("连续失败 1 次"))
        assertTrue(summary.contains("1分30秒"))
        assertTrue(summary.contains("timeout"))
    }

    @Test
    fun `summary online notes reconnect history when present`() {
        val d = ConnectionDiagnostics()
        d.onReconnectAttempt()
        d.onConnected(1000)
        assertEquals("在线（本会话重连过 1 次）", d.summary(2000))
    }

    @Test
    fun `human duration formats seconds minutes hours`() {
        val d = ConnectionDiagnostics()
        d.onDisconnected("x", 1_000) // 非零断线时刻（0 是"从未断线"哨兵）
        assertTrue(d.summary(31_000).contains("30秒"))
        assertTrue(d.summary(126_000).contains("2分5秒"))
        assertTrue(d.summary(3_701_000).contains("1小时1分"))
    }
}
