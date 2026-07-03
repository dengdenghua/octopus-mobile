package com.apk.claw.android.octopus_mobile

import com.apk.claw.android.octopus_mobile.safety.ToolRiskPolicy
import com.apk.claw.android.tool.ToolRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileQualityGateTest {

    @Test
    fun `quality gate passes current mobile runtime contracts`() {
        val report = MobileQualityGate.evaluate(rpcUrl = "wss://runtime.example/ws")

        assertEquals(MobileQualityGate.SCHEMA, report.schema)
        assertTrue(report.ready)
        assertTrue(report.checks.first { it.id == "risk_policy_complete" }.passed)
        assertTrue(report.checks.first { it.id == "action_timeline_available" }.passed)
    }

    @Test
    fun `quality gate detects unclassified tool drift`() {
        val registered = ToolRegistry.getAllTools().map { it.getName() }.toSet() + "new_side_effect_tool"
        val report = MobileQualityGate.evaluate(
            registeredTools = registered,
            rpcUrl = "",
        )

        val drift = report.checks.first { it.id == "risk_policy_complete" }
        assertFalse(report.ready)
        assertFalse(drift.passed)
        assertTrue(drift.message.contains("new_side_effect_tool"))
    }

    @Test
    fun `quality gate warns on non local cleartext runtime`() {
        val classified = ToolRiskPolicy.HIGH_RISK_TOOLS +
            ToolRiskPolicy.MEDIUM_RISK_TOOLS +
            ToolRiskPolicy.KNOWN_LOW_RISK_TOOLS
        // 用公网 IP(203.0.113.0/24 文档示例段)验证:ws:// + 非私有网络 → 必须被拒绝。
        // LAN(10/8、172.16/12、192.168/16)已由 MobileRuntimeSecurity 视为本地开发,故不能用作"非本地"用例。
        val report = MobileQualityGate.evaluate(
            registeredTools = classified - ToolRiskPolicy.INTENTIONAL_UNREGISTERED,
            rpcUrl = "ws://203.0.113.42:8765",
            allowInsecureRuntime = false,
        )

        val transport = report.checks.first { it.id == "remote_runtime_transport" }
        assertFalse(transport.passed)
        assertFalse(report.ready)
    }

    @Test
    fun `quality gate allows lan cleartext for development but marks not production ready`() {
        val classified = ToolRiskPolicy.HIGH_RISK_TOOLS +
            ToolRiskPolicy.MEDIUM_RISK_TOOLS +
            ToolRiskPolicy.KNOWN_LOW_RISK_TOOLS
        // LAN ws:// 允许连接(本地开发),但仍不算生产就绪 —— transport 检查必须失败。
        val report = MobileQualityGate.evaluate(
            registeredTools = classified - ToolRiskPolicy.INTENTIONAL_UNREGISTERED,
            rpcUrl = "ws://192.168.1.2:8765",
            allowInsecureRuntime = false,
        )

        val transport = report.checks.first { it.id == "remote_runtime_transport" }
        assertFalse(transport.passed)
        assertFalse(report.ready)
    }

    private fun assertEquals(expected: Any, actual: Any) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
