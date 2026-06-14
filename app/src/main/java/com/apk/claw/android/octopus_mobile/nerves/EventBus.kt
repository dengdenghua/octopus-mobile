package com.apk.claw.android.octopus_mobile.nerves

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 类型化事件总线 —— 从母体 runtime/core/nerves/bus.py 移植.
 *
 * 发布/订阅模式，模块间解耦：
 *  - 安全模块发布 ToolBlockedEvent
 *  - 进化模块订阅 ToolBlockedEvent 做打分
 *  - UI 订阅 NavigationEvent 做页面更新
 *  - 不需要模块间直接引用
 *
 * 用法：
 * ```kotlin
 * val bus = EventBus()
 *
 * // 订阅
 * bus.subscribe(ToolCallEvent::class.java) { event ->
 *     Log.d("TAG", "Tool ${event.toolName} called")
 * }
 *
 * // 发布
 * bus.publish(ToolCallEvent(toolName = "tap", success = true))
 * ```
 */
class EventBus(
    private val crashResilient: Boolean = true,
) {
    companion object {
        private const val TAG = "EventBus"
    }

    private val subs = ConcurrentHashMap<Class<*>, CopyOnWriteArrayList<(NervesEvent) -> Unit>>()

    // ── 事件基类 ──────────────────────────────────────

    open class NervesEvent

    // ── 预定义事件 ────────────────────────────────────

    /** 工具调用事件 */
    data class ToolCallEvent(
        val toolName: String,
        val success: Boolean,
        val reason: String = "",
    ) : NervesEvent()

    /** 工具被拦截事件 */
    data class ToolBlockedEvent(
        val toolName: String,
        val reason: String,
        val gate: String,  // "safety" / "guardrail" / "canary"
    ) : NervesEvent()

    /** 中/高风险工具审计事件 */
    data class ToolAuditEvent(
        val toolName: String,
        val risk: String,
        val success: Boolean,
        val blockedBy: String?,
        val durationMs: Long,
    ) : NervesEvent()

    /** 远程 HTTP/LAN 强能力入口审计事件 */
    data class RemoteAccessAuditEvent(
        val method: String,
        val uri: String,
        val source: String,
        val action: String,
        val success: Boolean,
        val durationMs: Long,
    ) : NervesEvent()

    /** 导航事件 */
    data class NavigationEvent(
        val url: String,
        val title: String = "",
    ) : NervesEvent()

    /** 扩展安装事件 */
    data class ExtensionInstalledEvent(
        val extensionId: String,
        val extensionName: String,
        val source: String,  // "cws" / "url" / "amo" / "file"
    ) : NervesEvent()

    /** 适应度变化事件 */
    data class FitnessChangedEvent(
        val score: Double,
        val verdict: String,
        val trend: String,
    ) : NervesEvent()

    /** 金丝雀阶段变更事件 */
    data class CanaryPhaseChangedEvent(
        val skillName: String,
        val fromPhase: String,
        val toPhase: String,
    ) : NervesEvent()

    /** 引擎切换事件 */
    data class EngineSwitchedEvent(
        val fromEngine: String,
        val toEngine: String,
    ) : NervesEvent()

    /** 错误事件 */
    data class ErrorEvent(
        val category: String,
        val message: String,
        val action: String,
    ) : NervesEvent()

    // ── 订阅/退订 ────────────────────────────────────

    fun <E : NervesEvent> subscribe(
        eventType: Class<E>,
        handler: (E) -> Unit,
    ) {
        val list = subs.getOrPut(eventType) { CopyOnWriteArrayList() }
        @Suppress("UNCHECKED_CAST")
        list.add(handler as (NervesEvent) -> Unit)
    }

    fun <E : NervesEvent> unsubscribe(
        eventType: Class<E>,
        handler: (E) -> Unit,
    ): Boolean {
        val list = subs[eventType] ?: return false
        @Suppress("UNCHECKED_CAST")
        return list.remove(handler as (NervesEvent) -> Unit)
    }

    fun <E : NervesEvent> subscriberCount(eventType: Class<E>): Int {
        return subs[eventType]?.size ?: 0
    }

    fun clear() {
        subs.clear()
    }

    // ── 发布 ──────────────────────────────────────────

    fun publish(event: NervesEvent): Int {
        val list = subs[event.javaClass] ?: return 0
        var called = 0
        for (handler in list) {
            try {
                handler(event)
                called++
            } catch (e: Exception) {
                if (crashResilient) {
                    Log.w(TAG, "Subscriber raised on ${event.javaClass.simpleName}: $e")
                } else {
                    throw e
                }
            }
        }
        return called
    }

    // ── 便捷 DSL ──────────────────────────────────────

    inline fun <reified E : NervesEvent> on(noinline handler: (E) -> Unit) {
        subscribe(E::class.java, handler)
    }

    // ── 统计 ──────────────────────────────────────────

    fun stats(): String {
        val totalTypes = subs.size
        val totalSubs = subs.values.sumOf { it.size }
        return "EventBus(event_types=$totalTypes, subscribers=$totalSubs)"
    }
}
