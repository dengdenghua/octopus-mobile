@file:Suppress("PackageNaming")   // 沿用既有 octopus_mobile.safety 包(带下划线)

package com.apk.claw.android.octopus_mobile.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IrreversibleActions 测试 —— 纯 JVM(仅字符串逻辑,不依赖 Android)。
 * 覆盖:不可逆分类正确;describe 摘要含关键信息且敏感键脱敏。
 */
class IrreversibleActionsTest {

    @Test
    fun `classifies irreversible external side-effects`() {
        assertTrue(IrreversibleActions.isIrreversible("send_sms"))
        assertTrue(IrreversibleActions.isIrreversible("send_intent"))
        assertTrue(IrreversibleActions.isIrreversible("share_to_square"))
        assertTrue(IrreversibleActions.isIrreversible("send_file"))
    }

    @Test
    fun `reversible or local-only tools are not gated`() {
        assertFalse(IrreversibleActions.isIrreversible("tap"))
        assertFalse(IrreversibleActions.isIrreversible("generate_app"))
        assertFalse(IrreversibleActions.isIrreversible("take_screenshot"))
        assertFalse(IrreversibleActions.isIrreversible("input_text"))
    }

    @Test
    fun `describe send_sms shows recipient and body`() {
        val desc = IrreversibleActions.describe(
            "send_sms",
            mapOf("phone" to "13800138000", "message" to "验证码是 6789"),
        )
        assertTrue(desc.contains("13800138000"))
        assertTrue(desc.contains("验证码是 6789"))
        assertTrue(desc.contains("发短信"))
    }

    @Test
    fun `describe share_to_square shows content`() {
        val desc = IrreversibleActions.describe("share_to_square", mapOf("title" to "我的作品"))
        assertTrue(desc.contains("公开广场"))
        assertTrue(desc.contains("我的作品"))
    }

    @Test
    fun `describe clips overly long content`() {
        val long = "x".repeat(200)
        val desc = IrreversibleActions.describe("send_sms", mapOf("to" to "A", "content" to long))
        assertTrue("超长应截断带省略号", desc.contains("…"))
        assertTrue("截断后整体长度受控", desc.length < 160)
    }

    @Test
    fun `describe redacts sensitive keys in fallback dump`() {
        // send_intent 无已知正文键时走 redactedDump,敏感键应脱敏
        val desc = IrreversibleActions.describe(
            "send_intent",
            mapOf("password" to "hunter2", "note" to "hello"),
        )
        assertFalse("密码不应明文出现", desc.contains("hunter2"))
        assertTrue(desc.contains("<redacted>"))
        assertTrue(desc.contains("hello"))
    }
}
