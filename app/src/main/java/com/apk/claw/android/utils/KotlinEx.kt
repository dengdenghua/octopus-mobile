package com.apk.claw.android.utils

/**
 * 带日志的 runCatching 扩展，用于治理静默吞异常。
 *
 * 与标准 [runCatching] 行为一致，但在发生异常时会通过 [XLog.w] 记录，
 * 便于排查问题。适用于可降级但不应被完全忽略的场景。
 */
inline fun <T> runCatchingLog(tag: String, block: () -> T): Result<T> =
    runCatching(block).onFailure { XLog.w(tag, it) }

/**
 * 带日志的 runCatching，异常时返回 [default]。
 */
inline fun <T> runCatchingOrDefault(tag: String, default: T, block: () -> T): T =
    runCatching(block).onFailure { XLog.w(tag, it) }.getOrDefault(default)

/**
 * 带日志的 runCatching，异常时返回 null。
 */
inline fun <T> runCatchingOrNull(tag: String, block: () -> T): T? =
    runCatching(block).onFailure { XLog.w(tag, it) }.getOrNull()
