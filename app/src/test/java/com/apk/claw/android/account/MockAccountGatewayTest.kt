package com.apk.claw.android.account

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Core account+billing logic, exercised against the in-memory mock gateway
 * (no Android / KVUtils, so this runs as a plain JVM unit test).
 */
class MockAccountGatewayTest {

    @Test
    fun `send code returns the dev code`() = runBlocking {
        val g = MockAccountGateway()
        val r = g.sendSmsCode("13800000000")
        assertTrue(r.ok)
        assertEquals(MockAccountGateway.MOCK_CODE, r.devCode)
    }

    @Test
    fun `login with wrong code fails`() = runBlocking {
        val g = MockAccountGateway()
        var threw = false
        try {
            g.login("13800000000", "000000")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("wrong code must be rejected", threw)
    }

    @Test
    fun `new user gets signup bonus and is flagged new`() = runBlocking {
        val g = MockAccountGateway()
        val login = g.login("13800000001", MockAccountGateway.MOCK_CODE)
        assertTrue(login.isNewUser)
        assertNotNull(login.token)
        assertEquals(100L, g.balance(login.token).credits)

        // logging in again is not a new user
        val again = g.login("13800000001", MockAccountGateway.MOCK_CODE)
        assertFalse(again.isNewUser)
    }

    @Test
    fun `recharge order settles paid and grants credits`() = runBlocking {
        val g = MockAccountGateway()
        val token = g.login("13800000002", MockAccountGateway.MOCK_CODE).token
        val before = g.balance(token).credits

        val goods = g.goods(token).items.first { it.id == "g_500" }
        val order = g.createOrder(token, goods.id)
        assertTrue(order.orderNo.isNotEmpty())

        val status = g.queryOrder(token, order.orderNo)
        assertEquals(OrderStatus.PAID, status.status)

        val expected = before + goods.credits + goods.bonusCredits
        assertEquals(expected, g.balance(token).credits)
    }

    @Test
    fun `daily claim grants once per day`() = runBlocking {
        val g = MockAccountGateway()
        val token = g.login("13800000003", MockAccountGateway.MOCK_CODE).token
        val before = g.balance(token).credits

        val first = g.dailyClaim(token)
        assertTrue(first.claimed)
        assertEquals(before + first.credits, g.balance(token).credits)

        val second = g.dailyClaim(token)
        assertFalse("second claim same day must be a no-op", second.claimed)
    }

    @Test
    fun `membership is inactive for new user`() = runBlocking {
        val g = MockAccountGateway()
        val token = g.login("13800000004", MockAccountGateway.MOCK_CODE).token
        val m = g.membership(token)
        assertFalse(m.active)
        assertEquals(0L, m.expireAt)
    }

    @Test
    fun `device register and heartbeat roundtrip`() = runBlocking {
        val g = MockAccountGateway()
        val token = g.login("13800000005", MockAccountGateway.MOCK_CODE).token
        val reg = g.registerDevice(token, deviceName = "Test Device")
        assertTrue(reg.deviceId.isNotEmpty())
        val hb = g.sendDeviceHeartbeat(token, reg.deviceId, battery = 80, isCharging = true)
        assertTrue(hb.ok)
        assertEquals(80, hb.battery)
        val status = g.deviceStatus(token, reg.deviceId)
        assertEquals(reg.deviceId, status.deviceId)
    }
}
