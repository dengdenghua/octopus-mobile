package com.apk.claw.android.octopus_mobile

import com.apk.claw.android.octopus_mobile.safety.ToolRiskPolicy
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.utils.KVUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MobileActionTimelineTest {

    @Before
    fun reset() {
        MobileActionTimeline.clear()
        KVUtils.setToolDisabled("finish", false)
        ToolRegistry.guardrail.reset()
        ToolRegistry.safetyGate = null
        ToolRegistry.turnScorer = null
        ToolRegistry.eventBus = null
        ToolRegistry.circuitBreaker = null
        ToolRegistry.highRiskConfirmer = null
        ToolRegistry.registerAllTools(ToolRegistry.DeviceType.MOBILE)
    }

    @Test
    fun `record stores redacted durable entries`() {
        MobileActionTimeline.record(
            toolName = "send_sms",
            params = mapOf("phone_number" to "13800138000", "api_token" to "sk-secret-value"),
            success = false,
            resultText = "failed with token sk-secret-value",
            blockedBy = "policy",
            durationMs = 12,
            source = "remote",
        )

        val entry = MobileActionTimeline.all().first()
        assertEquals(MobileActionTimeline.SCHEMA, entry.schema)
        assertEquals("send_sms", entry.toolName)
        assertEquals(ToolRiskPolicy.RISK_HIGH, entry.risk)
        assertEquals("remote", entry.source)
        assertEquals("policy", entry.blockedBy)
        assertFalse(entry.params.contains("sk-secret-value"))
        assertFalse(entry.result.contains("sk-secret-value"))
        assertEquals(64, entry.resultFingerprint.length)
    }

    @Test
    fun `tool registry writes timeline for success and blocked calls`() {
        val ok = ToolRegistry.executeTool("finish", mapOf("summary" to "done"))
        assertTrue(ok.isSuccess)

        KVUtils.setToolDisabled("finish", true)
        val blocked = ToolRegistry.executeTool("finish", mapOf("summary" to "done again"))
        assertFalse(blocked.isSuccess)

        val entries = MobileActionTimeline.all()
        assertEquals(2, entries.size)
        assertEquals("settings", entries.first().blockedBy)
        assertEquals("finish", entries.first().toolName)
        assertEquals("finish", entries.last().toolName)

        val summary = MobileActionTimeline.summary()
        assertEquals(2, summary.total)
        assertEquals(1, summary.success)
        assertEquals(1, summary.blocked)
        assertEquals("finish:settings" to 1, summary.topFailures.first())
    }
}
