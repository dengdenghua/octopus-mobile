package com.apk.claw.android.octopus_mobile

import org.junit.Assert.*
import org.junit.Test

/**
 * StartupModeResolver 单元测试.
 *
 * 覆盖：
 *  - LOCAL_ONLY：始终返回 LOCAL_ONLY
 *  - RPC_ONLY：可达 → RPC_ONLY；不可达 → 抛异常
 *  - DUAL：可达 → DUAL；不可达 → LOCAL_ONLY
 *  - 未配置（null）→ 默认 DUAL
 *  - 非法字符串 → 默认 DUAL
 */
class StartupModeResolverTest {

    @Test
    fun `LOCAL_ONLY returns LOCAL_ONLY regardless of reachability`() {
        assertEquals(
            StartupMode.LOCAL_ONLY,
            StartupModeResolver.resolve("LOCAL_ONLY", isRuntimeReachable = true),
        )
        assertEquals(
            StartupMode.LOCAL_ONLY,
            StartupModeResolver.resolve("LOCAL_ONLY", isRuntimeReachable = false),
        )
    }

    @Test
    fun `RPC_ONLY returns RPC_ONLY when reachable`() {
        assertEquals(
            StartupMode.RPC_ONLY,
            StartupModeResolver.resolve("RPC_ONLY", isRuntimeReachable = true),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `RPC_ONLY throws when not reachable`() {
        StartupModeResolver.resolve("RPC_ONLY", isRuntimeReachable = false)
    }

    @Test
    fun `DUAL returns DUAL when reachable`() {
        assertEquals(
            StartupMode.DUAL,
            StartupModeResolver.resolve("DUAL", isRuntimeReachable = true),
        )
    }

    @Test
    fun `DUAL returns LOCAL_ONLY when not reachable`() {
        assertEquals(
            StartupMode.LOCAL_ONLY,
            StartupModeResolver.resolve("DUAL", isRuntimeReachable = false),
        )
    }

    @Test
    fun `null configured mode defaults to DUAL`() {
        assertEquals(
            StartupMode.DUAL,
            StartupModeResolver.resolve(null, isRuntimeReachable = true),
        )
        assertEquals(
            StartupMode.LOCAL_ONLY,
            StartupModeResolver.resolve(null, isRuntimeReachable = false),
        )
    }

    @Test
    fun `invalid configured mode defaults to DUAL`() {
        assertEquals(
            StartupMode.DUAL,
            StartupModeResolver.resolve("INVALID", isRuntimeReachable = true),
        )
    }

    @Test
    fun `lowercase string not accepted - defaults to DUAL`() {
        // 注意：enumOf 是大小写敏感的，"dual" 不会被识别
        assertEquals(
            StartupMode.DUAL,
            StartupModeResolver.resolve("dual", isRuntimeReachable = true),
        )
    }
}
