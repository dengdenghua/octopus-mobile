package com.apk.claw.android.octopus_mobile.proactive

import com.apk.claw.android.TestClawApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ProactiveRuleEngine.extractVerificationCode 单测:验证短信验证码提取。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestClawApplication::class)
class ProactiveRuleEngineTest {

    private val engine = ProactiveRuleEngine()

    @Test
    fun `extracts code with chinese context`() {
        assertEquals("482913", engine.extractVerificationCode("【银行】您的验证码是 482913，请勿告诉他人"))
    }

    @Test
    fun `extracts english otp`() {
        assertEquals("738291", engine.extractVerificationCode("Your code: 738291 expires in 5 min"))
    }

    @Test
    fun `falls back to standalone digits when no context word`() {
        assertEquals("5678", engine.extractVerificationCode("登录码 5678"))
    }

    @Test
    fun `returns null when no digits`() {
        assertNull(engine.extractVerificationCode("您好，欢迎光临，祝您购物愉快"))
    }
}
