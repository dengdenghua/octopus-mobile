package com.apk.claw.android.octopus_mobile.nerves

import org.junit.Assert.*
import org.junit.Test

/**
 * EventBus 测试 —— 订阅 / 发布 / 异常隔离.
 */
class EventBusTest {

    @Test
    fun `subscribe and publish`() {
        val bus = EventBus()
        var received = false
        bus.on<EventBus.ToolCallEvent> { received = true }
        bus.publish(EventBus.ToolCallEvent("tap", true))
        assertTrue(received)
    }

    @Test
    fun `multiple subscribers receive`() {
        val bus = EventBus()
        var count = 0
        bus.on<EventBus.ToolCallEvent> { count++ }
        bus.on<EventBus.ToolCallEvent> { count++ }
        bus.publish(EventBus.ToolCallEvent("tap", true))
        assertEquals(2, count)
    }

    @Test
    fun `unsubscribe stops receiving`() {
        val bus = EventBus()
        var count = 0
        val handler: (EventBus.ToolCallEvent) -> Unit = { count++ }
        bus.subscribe(EventBus.ToolCallEvent::class.java, handler)
        bus.publish(EventBus.ToolCallEvent("tap", true))
        assertEquals(1, count)

        bus.unsubscribe(EventBus.ToolCallEvent::class.java, handler)
        bus.publish(EventBus.ToolCallEvent("tap", true))
        assertEquals(1, count)  // 不再增加
    }

    @Test
    fun `different event types isolated`() {
        val bus = EventBus()
        var toolCalled = false
        var navCalled = false
        bus.on<EventBus.ToolCallEvent> { toolCalled = true }
        bus.on<EventBus.NavigationEvent> { navCalled = true }

        bus.publish(EventBus.ToolCallEvent("tap", true))
        assertTrue(toolCalled)
        assertFalse(navCalled)
    }

    @Test
    fun `exception in subscriber does not crash others`() {
        val bus = EventBus(crashResilient = true)
        var secondReceived = false
        bus.on<EventBus.ToolCallEvent> { throw RuntimeException("boom") }
        bus.on<EventBus.ToolCallEvent> { secondReceived = true }

        bus.publish(EventBus.ToolCallEvent("tap", true))
        assertTrue(secondReceived)
    }

    @Test(expected = RuntimeException::class)
    fun `exception propagates when crashResilient false`() {
        val bus = EventBus(crashResilient = false)
        bus.on<EventBus.ToolCallEvent> { throw RuntimeException("boom") }
        bus.publish(EventBus.ToolCallEvent("tap", true))
    }

    @Test
    fun `subscriberCount`() {
        val bus = EventBus()
        assertEquals(0, bus.subscriberCount(EventBus.ToolCallEvent::class.java))
        bus.on<EventBus.ToolCallEvent> { }
        assertEquals(1, bus.subscriberCount(EventBus.ToolCallEvent::class.java))
    }

    @Test
    fun `clear removes all`() {
        val bus = EventBus()
        bus.on<EventBus.ToolCallEvent> { }
        bus.on<EventBus.NavigationEvent> { }
        bus.clear()
        assertEquals(0, bus.subscriberCount(EventBus.ToolCallEvent::class.java))
        assertEquals(0, bus.subscriberCount(EventBus.NavigationEvent::class.java))
    }

    @Test
    fun `publish returns subscriber count`() {
        val bus = EventBus()
        bus.on<EventBus.ToolCallEvent> { }
        bus.on<EventBus.ToolCallEvent> { }
        val count = bus.publish(EventBus.ToolCallEvent("tap", true))
        assertEquals(2, count)
    }

    @Test
    fun `publish returns 0 when no subscribers`() {
        val bus = EventBus()
        val count = bus.publish(EventBus.ToolCallEvent("tap", true))
        assertEquals(0, count)
    }
}
