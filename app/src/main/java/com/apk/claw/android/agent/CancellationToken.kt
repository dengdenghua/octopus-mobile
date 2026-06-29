package com.apk.claw.android.agent

import java.util.concurrent.atomic.AtomicBoolean

class CancellationToken {

    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var reason: String? = null

    fun cancel(reason: String? = null) {
        this.reason = reason
        cancelled.set(true)
        synchronized(lock) {
            lock.notifyAll()
        }
    }

    fun isCancelled(): Boolean = cancelled.get()

    fun getReason(): String? = reason

    fun checkCancelled() {
        if (cancelled.get()) {
            throw InterruptedException(reason ?: "Task cancelled")
        }
    }

    private val lock = Object()

    fun sleepInterruptible(ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        synchronized(lock) {
            var remaining = ms
            while (remaining > 0 && !cancelled.get()) {
                try {
                    lock.wait(remaining.coerceAtMost(200))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
                remaining = end - System.currentTimeMillis()
            }
        }
        return !cancelled.get()
    }

    companion object {
        @JvmStatic
        fun neverCancelled(): CancellationToken {
            return CancellationToken()
        }
    }
}
