package com.apk.claw.android.octopus_mobile.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * run_code(代码执行)风险分类断言。
 *
 * 代码执行以 shell UID 跑 Agent 生成代码,必须是 HIGH —— 这样才会自动获得
 * 「不可信来源审批闸门 + 全程审计」。若有人误把它降级,本测试 + 漂移守护测试一起拦住。
 */
class RunCodeRiskTest {

    @Test
    fun `run_code is classified HIGH risk`() {
        assertEquals(ToolRiskPolicy.RISK_HIGH, ToolRiskPolicy.riskOf("run_code"))
        assertTrue("run_code 必须在 HIGH_RISK_TOOLS 中", "run_code" in ToolRiskPolicy.HIGH_RISK_TOOLS)
    }

    @Test
    fun `run_code is audited`() {
        assertTrue("HIGH 风险工具必须进审计", ToolRiskPolicy.shouldAudit("run_code"))
    }
}
