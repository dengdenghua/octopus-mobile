package com.apk.claw.android.octopus_mobile.safety

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * CircuitBreaker 测试 —— 三态机 + 熔断.
 */
class CircuitBreakerTest {

    @Test
    fun `starts closed`() {
        val cb = CircuitBreaker()
        assertEquals(CircuitBreaker.CircuitState.CLOSED, cb.getState())
    }

    @Test
    fun `check passes when closed`() {
        val cb = CircuitBreaker()
        val state = cb.check()
        assertEquals(CircuitBreaker.CircuitState.CLOSED, state)
    }

    @Test
    fun `opens on max errors`() {
        val cb = CircuitBreaker(maxErrorsPerWindow = 3, windowSeconds = 60.0)
        cb.record(success = false)
        cb.record(success = false)
        cb.record(success = false)
        cb.record(success = false)  // 第 4 次超过 3
        assertEquals(CircuitBreaker.CircuitState.OPEN, cb.getState())
    }

    @Test(expected = CircuitBreaker.CircuitOpenException::class)
    fun `check throws when open`() {
        val cb = CircuitBreaker(maxErrorsPerWindow = 1)
        cb.record(success = false)
        cb.record(success = false)
        cb.check()
    }

    @Test
    fun `half open after cooldown`() {
        val cb = CircuitBreaker(
            maxErrorsPerWindow = 1,
            cooldownSeconds = 0.01  // 10ms
        )
        cb.record(success = false)
        cb.record(success = false)
        Thread.sleep(20)
        val state = cb.check()
        assertEquals(CircuitBreaker.CircuitState.HALF_OPEN, state)
    }

    @Test
    fun `closes on success in half open`() {
        val cb = CircuitBreaker(
            maxErrorsPerWindow = 1,
            cooldownSeconds = 0.01
        )
        cb.record(success = false)
        cb.record(success = false)
        Thread.sleep(20)
        cb.check()  // → HALF_OPEN
        cb.record(success = true)
        assertEquals(CircuitBreaker.CircuitState.CLOSED, cb.getState())
    }

    @Test
    fun `opens again on failure in half open`() {
        val cb = CircuitBreaker(
            maxErrorsPerWindow = 1,
            cooldownSeconds = 0.01
        )
        cb.record(success = false)
        cb.record(success = false)
        Thread.sleep(20)
        cb.check()  // → HALF_OPEN
        cb.record(success = false)
        assertEquals(CircuitBreaker.CircuitState.OPEN, cb.getState())
    }

    @Test
    fun `opens on max calls`() {
        val cb = CircuitBreaker(maxCallsPerWindow = 2)
        cb.record(success = true)
        cb.record(success = true)
        cb.record(success = true)  // 第 3 次超过 2
        assertEquals(CircuitBreaker.CircuitState.OPEN, cb.getState())
    }

    @Test
    fun `reset restores closed`() {
        val cb = CircuitBreaker(maxErrorsPerWindow = 1)
        cb.record(success = false)
        cb.record(success = false)
        assertEquals(CircuitBreaker.CircuitState.OPEN, cb.getState())
        cb.reset()
        assertEquals(CircuitBreaker.CircuitState.CLOSED, cb.getState())
    }

    @Test
    fun `snapshot includes stats`() {
        val cb = CircuitBreaker()
        cb.record(success = true)
        cb.record(success = false)
        val snap = cb.snapshot()
        assertEquals("CLOSED", snap["state"])
        assertEquals(2, snap["calls_in_window"])
        assertEquals(1, snap["errors"])
    }
}
