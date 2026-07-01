package com.apk.claw.android.octopus_mobile

import java.net.URI

/**
 * Runtime transport policy for the mother-brain WebSocket.
 *
 * Production deployments must use wss://. Cleartext ws:// is allowed only for
 * loopback/emulator development unless the user explicitly opts into insecure
 * runtime transport.
 */
object MobileRuntimeSecurity {

    private val LOCAL_CLEAR_TEXT_HOSTS = setOf(
        "localhost",
        "127.0.0.1",
        "::1",
        "[::1]",
        "10.0.2.2",
    )

    data class Decision(
        val allowed: Boolean,
        val localDevelopment: Boolean,
        val reason: String,
    )

    fun assess(runtimeUrl: String, allowInsecureRuntime: Boolean = false): Decision {
        val trimmed = runtimeUrl.trim()
        if (trimmed.isBlank()) {
            return Decision(allowed = false, localDevelopment = false, reason = "Runtime URL is empty")
        }
        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: return Decision(allowed = false, localDevelopment = false, reason = "Runtime URL is invalid")
        val scheme = uri.scheme?.lowercase()
        val host = (uri.host ?: "").lowercase()
        return when {
            scheme == "wss" -> Decision(allowed = true, localDevelopment = false, reason = "secure websocket")
            scheme != "ws" -> Decision(allowed = false, localDevelopment = false, reason = "Runtime URL must use wss:// or ws://")
            isLocalCleartextHost(host) -> Decision(allowed = true, localDevelopment = true, reason = "local development websocket")
            allowInsecureRuntime -> Decision(allowed = true, localDevelopment = false, reason = "explicit insecure runtime override")
            else -> Decision(
                allowed = false,
                localDevelopment = false,
                reason = "Cleartext ws:// runtime is blocked outside local development",
            )
        }
    }

    fun isProductionReadyTransport(runtimeUrl: String): Boolean {
        val decision = assess(runtimeUrl, allowInsecureRuntime = false)
        return decision.allowed && !decision.localDevelopment
    }

    private fun isLocalCleartextHost(host: String): Boolean =
        host in LOCAL_CLEAR_TEXT_HOSTS
}
