package com.apk.claw.android.server

import android.os.Handler
import android.os.Looper
import com.apk.claw.android.ui.compose.screen.ChatAgentBridge
import com.apk.claw.android.utils.XLog
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 网页聊天桥 —— 让网页遥控台右侧的对话框驱动同一个 Agent([ChatAgentBridge])。
 *
 * 设计:Agent 回调是异步流式的,网页用轮询取。把每个事件按**绝对序号**缓冲,
 * 网页带 `since=游标` 拉增量。类型:user / text(流式增量) / tool / done / error。
 * 同一时刻只跑一个任务(单 Agent 服务),并发请求返回 busy。
 */
object AgentWebBridge {
    data class Ev(val i: Int, val type: String, val data: String)

    private const val MAX_KEEP = 2000
    private val events = CopyOnWriteArrayList<Ev>()
    private val seq = AtomicInteger(0)
    private val running = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    fun isRunning(): Boolean = running.get()
    fun total(): Int = seq.get()
    fun eventsSince(since: Int): List<Ev> = events.filter { it.i >= since }

    private fun add(type: String, data: String) {
        events.add(Ev(seq.getAndIncrement(), type, data))
        while (events.size > MAX_KEEP) {
            try { events.removeAt(0) } catch (e: Exception) { XLog.w("AgentWebBridge", "trim events failed", e) }
        }
    }

    /** 网页发来一条指令。已在跑→false(busy);未配置模型→记错误并 false。 */
    fun run(prompt: String): Boolean {
        if (!ChatAgentBridge.isConfigured()) { add("error", "未配置模型,请到 设置 → 模型配置"); return false }
        if (ChatAgentBridge.isBusy()) return false   // App 端或其它请求正在跑→busy
        if (!running.compareAndSet(false, true)) return false
        add("user", prompt)
        main.post {
            runCatching {
                ChatAgentBridge.run(
                    prompt = prompt,
                    untrusted = true,   // LAN 网页控制台来源：高危工具走来源闸门
                    onTool = { icon, name, _, result ->
                        // "完成任务/Finish" 的结果即最终答案,交给 done 渲染成回复气泡,不再当工具步骤
                        if (!name.contains("Finish", true) && !name.contains("完成"))
                            add("tool", "$icon $name ${result ?: ""}".trim())
                    },
                    onText = { t -> if (t.isNotEmpty()) add("text", t) },
                    onDone = { ans -> add("done", ans); running.set(false) },
                    onError = { e -> add("error", e); running.set(false) },
                )
            }.onFailure { add("error", it.message ?: "运行失败"); running.set(false) }
        }
        return true
    }
}
