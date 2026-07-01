package com.apk.claw.android.octopus_mobile

import com.apk.claw.android.octopus_mobile.safety.PermissionPolicy
import com.apk.claw.android.octopus_mobile.safety.ToolRiskPolicy
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.utils.KVUtils

/**
 * Static release-readiness checks for the mobile/tentacle runtime.
 *
 * This is intentionally small and deterministic: it can run in JVM tests and in
 * diagnostic UI without touching Android services.
 */
object MobileQualityGate {

    const val SCHEMA = "octopus.mobile_quality_gate.v1"

    data class Check(
        val id: String,
        val passed: Boolean,
        val severity: String,
        val message: String,
    )

    data class Report(
        val schema: String = SCHEMA,
        val ready: Boolean,
        val passed: Int,
        val failed: Int,
        val checks: List<Check>,
    )

    fun evaluate(
        registeredTools: Set<String> = registeredToolNames(),
        rpcUrl: String = KVUtils.getOctopusRpcUrl(),
        allowInsecureRuntime: Boolean = KVUtils.isInsecureOctopusRuntimeAllowed(),
    ): Report {
        val classified = ToolRiskPolicy.HIGH_RISK_TOOLS +
            ToolRiskPolicy.MEDIUM_RISK_TOOLS +
            ToolRiskPolicy.KNOWN_LOW_RISK_TOOLS
        val unclassified = (registeredTools - classified).sorted()
        val staleRiskNames = (
            classified - registeredTools - ToolRiskPolicy.INTENTIONAL_UNREGISTERED
            ).sorted()
        val checks = listOf(
            Check(
                id = "approval_policy_default",
                passed = PermissionPolicy.APPROVAL.highRiskAction == PermissionPolicy.RiskAction.CONFIRM &&
                    !PermissionPolicy.APPROVAL.trustAllSources &&
                    PermissionPolicy.APPROVAL.privacyScannerEnabled &&
                    PermissionPolicy.APPROVAL.auditLogEnabled &&
                    PermissionPolicy.APPROVAL.circuitBreakerEnabled,
                severity = "critical",
                message = "APPROVAL must keep source gate, audit, privacy scanner, and circuit breaker enabled.",
            ),
            Check(
                id = "full_power_non_bypassable_guards",
                passed = PermissionPolicy.FULL_POWER.privacyScannerEnabled &&
                    PermissionPolicy.FULL_POWER.auditLogEnabled &&
                    PermissionPolicy.FULL_POWER.circuitBreakerEnabled,
                severity = "critical",
                message = "FULL_POWER may relax approval, but privacy scanner, audit, and circuit breaker stay on.",
            ),
            Check(
                id = "risk_policy_complete",
                passed = unclassified.isEmpty(),
                severity = "critical",
                message = if (unclassified.isEmpty()) {
                    "Every registered tool has explicit risk classification."
                } else {
                    "Unclassified tools: $unclassified"
                },
            ),
            Check(
                id = "risk_policy_no_stale_entries",
                passed = staleRiskNames.isEmpty(),
                severity = "warning",
                message = if (staleRiskNames.isEmpty()) {
                    "Risk policy does not contain stale registered-tool names."
                } else {
                    "Stale risk policy entries: $staleRiskNames"
                },
            ),
            Check(
                id = "remote_runtime_transport",
                passed = rpcUrl.isBlank() || MobileRuntimeSecurity.isProductionReadyTransport(rpcUrl),
                severity = "critical",
                message = "Production Runtime transport must use wss://; local ws:// is for development only.",
            ),
            Check(
                id = "insecure_runtime_override_disabled",
                passed = !allowInsecureRuntime,
                severity = "critical",
                message = "Explicit insecure Runtime override must stay disabled for production readiness.",
            ),
            Check(
                id = "action_timeline_available",
                passed = MobileActionTimeline.SCHEMA == "octopus.mobile_action_timeline.v1",
                severity = "critical",
                message = "Mobile tool executions have a durable timeline schema.",
            ),
        )
        return Report(
            ready = checks.none { !it.passed && it.severity == "critical" },
            passed = checks.count { it.passed },
            failed = checks.count { !it.passed },
            checks = checks,
        )
    }

    private fun registeredToolNames(): Set<String> {
        ToolRegistry.registerAllTools(ToolRegistry.DeviceType.MOBILE)
        return ToolRegistry.getAllTools().map { it.getName() }.toSet()
    }
}
