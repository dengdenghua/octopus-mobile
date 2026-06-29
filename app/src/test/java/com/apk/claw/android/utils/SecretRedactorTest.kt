package com.apk.claw.android.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SecretRedactor 脱敏单测(纯 JVM,仅依赖 java.util.regex)。
 * 覆盖:API key / sk-proj / Bearer / 验证码 / 手机号 / 邮箱,以及"普通数字不误伤"。
 */
class SecretRedactorTest {

    @Test
    fun `redacts openai sk key`() {
        val out = SecretRedactor.redact("key=sk-ABCDEFGHIJKLMNOPQRSTUVWX1234")!!
        assertTrue(out.contains("[API_KEY_REDACTED]"))
        assertFalse(out.contains("sk-ABCDEFGHIJKLMNOPQRSTUVWX1234"))
    }

    @Test
    fun `redacts openai sk-proj key not shadowed by sk-`() {
        val out = SecretRedactor.redact("token sk-proj-abcdefghij_KLMNOPQRST-uvwx1234 end")!!
        assertTrue(out.contains("[API_KEY_REDACTED]"))
        assertFalse(out.contains("uvwx1234"))
    }

    @Test
    fun `redacts bearer token`() {
        val out = SecretRedactor.redact("Authorization: Bearer abcdef123456.tokenpart")!!
        assertTrue(out.contains("[TOKEN_REDACTED]"))
    }

    @Test
    fun `redacts chinese verification code`() {
        val out = SecretRedactor.redact("【银行】您的验证码是 482913，请勿告诉他人")!!
        assertTrue(out.contains("[CODE_REDACTED]"))
        assertFalse(out.contains("482913"))
    }

    @Test
    fun `redacts english otp`() {
        val out = SecretRedactor.redact("Your OTP: 738291 expires soon")!!
        assertTrue(out.contains("[CODE_REDACTED]"))
        assertFalse(out.contains("738291"))
    }

    @Test
    fun `redacts cn phone number`() {
        val out = SecretRedactor.redact("联系人 13812345678 已保存")!!
        assertTrue(out.contains("[PHONE_REDACTED]"))
        assertFalse(out.contains("13812345678"))
    }

    @Test
    fun `redacts email`() {
        val out = SecretRedactor.redact("from alice.test@example.com to bob")!!
        assertTrue(out.contains("[EMAIL_REDACTED]"))
        assertFalse(out.contains("alice.test@example.com"))
    }

    @Test
    fun `leaves plain digits and text untouched`() {
        // 坐标/计数/耗时等普通数字无验证码上下文,不应被误伤
        val input = "tapped at x=540 y=1200, count=3, took 250ms"
        assertEquals(input, SecretRedactor.redact(input))
    }

    @Test
    fun `null returns null`() {
        assertNull(SecretRedactor.redact(null))
    }
}
