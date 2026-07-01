package com.apk.claw.android.octopus_mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileRuntimeSecurityTest {

    @Test
    fun `wss runtime is allowed and production ready`() {
        val decision = MobileRuntimeSecurity.assess("wss://runtime.example/ws")
        assertTrue(decision.allowed)
        assertFalse(decision.localDevelopment)
        assertTrue(MobileRuntimeSecurity.isProductionReadyTransport("wss://runtime.example/ws"))
    }

    @Test
    fun `local cleartext runtime is allowed for development only`() {
        val decision = MobileRuntimeSecurity.assess("ws://10.0.2.2:8765")
        assertTrue(decision.allowed)
        assertTrue(decision.localDevelopment)
        assertFalse(MobileRuntimeSecurity.isProductionReadyTransport("ws://10.0.2.2:8765"))
    }

    @Test
    fun `remote cleartext runtime is blocked by default`() {
        val decision = MobileRuntimeSecurity.assess("ws://192.168.1.2:8765")
        assertFalse(decision.allowed)
    }

    @Test
    fun `remote cleartext runtime requires explicit override and still is not production ready`() {
        val decision = MobileRuntimeSecurity.assess(
            "ws://192.168.1.2:8765",
            allowInsecureRuntime = true,
        )
        assertTrue(decision.allowed)
        assertFalse(MobileRuntimeSecurity.isProductionReadyTransport("ws://192.168.1.2:8765"))
    }
}
