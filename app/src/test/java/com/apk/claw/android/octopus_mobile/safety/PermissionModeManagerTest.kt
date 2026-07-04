package com.apk.claw.android.octopus_mobile.safety

import com.apk.claw.android.utils.KVUtils
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * PermissionModeManager 测试 —— 模式切换 + 热加载（纯 JVM，KVUtils 退回内存 map）。
 *
 * 依赖 KVUtils 的 boolean 内存兜底(本分支同时补的健壮性修复),故无需 Robolectric。
 */
class PermissionModeManagerTest {

    @Before
    fun reset() {
        // 回到默认审批模式基线
        KVUtils.setAdvancedAutomationMode(false)
        PermissionModeManager.reload()
    }

    @After
    fun tearDown() {
        KVUtils.resetForTest()
    }

    @Test
    fun `default mode is APPROVAL`() {
        assertEquals(PermissionMode.APPROVAL, PermissionModeManager.getCurrentMode())
        assertTrue(PermissionModeManager.isApprovalMode())
        assertFalse(PermissionModeManager.isFullPowerMode())
        assertEquals(PermissionPolicy.APPROVAL, PermissionModeManager.getCurrentPolicy())
    }

    @Test
    fun `switchMode to FULL_POWER updates policy and storage`() {
        PermissionModeManager.switchMode(PermissionMode.FULL_POWER, "测试")
        assertEquals(PermissionMode.FULL_POWER, PermissionModeManager.getCurrentMode())
        assertTrue(PermissionModeManager.isFullPowerMode())
        assertEquals(PermissionPolicy.RiskAction.ALLOW, PermissionModeManager.getCurrentPolicy().highRiskAction)
        // 落到底层存储
        assertTrue(KVUtils.isAdvancedAutomationMode())
    }

    @Test
    fun `switchMode back to APPROVAL restores safety-first`() {
        PermissionModeManager.switchMode(PermissionMode.FULL_POWER, "up")
        PermissionModeManager.switchMode(PermissionMode.APPROVAL, "down")
        assertTrue(PermissionModeManager.isApprovalMode())
        assertEquals(PermissionPolicy.RiskAction.CONFIRM, PermissionModeManager.getCurrentPolicy().highRiskAction)
        assertFalse(KVUtils.isAdvancedAutomationMode())
    }

    @Test
    fun `emergencyRevoke forces APPROVAL`() {
        PermissionModeManager.switchMode(PermissionMode.FULL_POWER, "up")
        PermissionModeManager.emergencyRevoke()
        assertEquals(PermissionMode.APPROVAL, PermissionModeManager.getCurrentMode())
    }

    @Test
    fun `reload picks up external storage change`() {
        KVUtils.setAdvancedAutomationMode(true) // 模拟 Runtime 远程下发
        PermissionModeManager.reload()
        assertTrue(PermissionModeManager.isFullPowerMode())
    }

    @Test
    fun `switchMode to same mode is a no-op`() {
        // 已是 APPROVAL,再切 APPROVAL 不应抛异常、保持原状
        PermissionModeManager.switchMode(PermissionMode.APPROVAL, "noop")
        assertEquals(PermissionMode.APPROVAL, PermissionModeManager.getCurrentMode())
    }
}
