package com.apk.claw.android.shizuku.autosetup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 覆盖各家 ROM 的「使用配对码配对设备」弹窗文案，验证 [PairingDialogParser] 能稳定刮出
 * {ip, port, code}，且不会把 port/IP 段误当配对码。
 */
class PairingDialogParserTest {

    @Test
    fun aosp_english() {
        val texts = listOf(
            "Pair with device",
            "Wi‑Fi pairing code",
            "123456",
            "IP address & Port",
            "10.0.2.16:37755",
            "Cancel",
        )
        assertEquals(PairingInfo("10.0.2.16", 37755, "123456"), PairingDialogParser.parse(texts))
    }

    @Test
    fun aosp_chinese() {
        val texts = listOf(
            "与设备配对",
            "Wi‑Fi 配对码",
            "428913",
            "IP 地址和端口",
            "192.168.1.7：41234", // 全角冒号
            "取消",
        )
        assertEquals(PairingInfo("192.168.1.7", 41234, "428913"), PairingDialogParser.parse(texts))
    }

    /** 配对码带中缝（部分 ROM 显示为 "123 456"）。 */
    @Test
    fun spaced_code() {
        val texts = listOf("Wi‑Fi 配对码", "428 913", "IP 地址和端口", "10.0.0.5:44100")
        assertEquals(PairingInfo("10.0.0.5", 44100, "428913"), PairingDialogParser.parse(texts))
    }

    /** 全部挤在一行也要能解析。 */
    @Test
    fun single_blob() {
        val texts = listOf("配对码 654321  IP 地址和端口 127.0.0.1:5555")
        assertEquals(PairingInfo("127.0.0.1", 5555, "654321"), PairingDialogParser.parse(texts))
    }

    /** 不能把端口(37755)或 IP 段当成 6 位配对码——这里没有真正的配对码,应判失败。 */
    @Test
    fun no_code_returns_null() {
        val texts = listOf("IP 地址和端口", "10.0.2.16:37755", "取消")
        assertNull(PairingDialogParser.parse(texts))
    }

    @Test
    fun no_ip_port_returns_null() {
        val texts = listOf("Wi‑Fi 配对码", "123456")
        assertNull(PairingDialogParser.parse(texts))
    }

    @Test
    fun empty_returns_null() {
        assertNull(PairingDialogParser.parse(emptyList()))
    }
}
