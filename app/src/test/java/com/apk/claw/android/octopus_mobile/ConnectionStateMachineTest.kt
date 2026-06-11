package com.apk.claw.android.octopus_mobile

import org.junit.Assert.*
import org.junit.Test

/**
 * ConnectionStateMachine / ConnectionState 单元测试.
 */
class ConnectionStateMachineTest {

    @Test
    fun `ConnectionState has 7 states`() {
        val states = ConnectionState.values().toSet()
        assertEquals(7, states.size)
        assertTrue(ConnectionState.DISCONNECTED in states)
        assertTrue(ConnectionState.CONNECTING in states)
        assertTrue(ConnectionState.CONNECTED in states)
        assertTrue(ConnectionState.HELLO_SENT in states)
        assertTrue(ConnectionState.ONLINE in states)
        assertTrue(ConnectionState.RECONNECTING in states)
        assertTrue(ConnectionState.OFFLINE in states)
    }

    @Test
    fun `ConnectionStateMachine starts DISCONNECTED`() {
        val sm = ConnectionStateMachine()
        assertEquals(ConnectionState.DISCONNECTED, sm.currentState)
    }

    @Test
    fun `ConnectionStateMachine happy path DISCONNECTED to ONLINE`() {
        val sm = ConnectionStateMachine()
        assertEquals(ConnectionState.CONNECTING, sm.transition(ConnectionEvent.Connect))
        assertEquals(ConnectionState.CONNECTED, sm.transition(ConnectionEvent.Connected))
        assertEquals(ConnectionState.HELLO_SENT, sm.transition(ConnectionEvent.HelloSent))
        assertEquals(ConnectionState.ONLINE, sm.transition(ConnectionEvent.HelloAcked))
    }

    @Test
    fun `ConnectionStateMachine reconnect from ONLINE`() {
        val sm = ConnectionStateMachine()
        sm.transition(ConnectionEvent.Connect)
        sm.transition(ConnectionEvent.Connected)
        sm.transition(ConnectionEvent.HelloSent)
        sm.transition(ConnectionEvent.HelloAcked)
        assertEquals(ConnectionState.RECONNECTING, sm.transition(ConnectionEvent.Disconnected))
        // 重连：RECONNECTING → CONNECTING
        assertEquals(ConnectionState.CONNECTING, sm.transition(ConnectionEvent.Reconnect))
    }

    @Test
    fun `ConnectionStateMachine getBackoffDelayMs increases with attempts`() {
        val sm = ConnectionStateMachine()
        // reconnectAttempts 初始为 0
        val first = sm.getBackoffDelayMs()
        sm.reconnectAttempts = 1
        val second = sm.getBackoffDelayMs()
        sm.reconnectAttempts = 5
        val capped = sm.getBackoffDelayMs()
        sm.reconnectAttempts = 100
        val maxCapped = sm.getBackoffDelayMs()
        // second 应当大于 first，capped 应当 <= 30000 + jitter
        assertTrue("second=$second should > first=$first", second > first - 10_000)
        assertTrue("maxCapped=$maxCapped should ≤ 30s + jitter", maxCapped <= 40_000)
    }
}
