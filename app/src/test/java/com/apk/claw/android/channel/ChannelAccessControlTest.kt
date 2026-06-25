package com.apk.claw.android.channel

import com.apk.claw.android.utils.KVUtils
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * ChannelAccessControl 测试 —— 通道发送者 ACL 的 TOFU 绑定与 fail-closed（纯 JVM）。
 *
 * 安全要点:
 *  - 默认启用 ACL;无法识别发送者(null/空)一律拒绝(fail-closed)。
 *  - 白名单为空时首个发送者绑为 owner(TOFU),其后非白名单一律拒。
 *  - 各通道白名单互相隔离。
 */
class ChannelAccessControlTest {

    @Before
    fun reset() {
        KVUtils.setChannelAclEnabled(true)
        Channel.values().forEach { KVUtils.clearChannelAllowedSenders(it.name) }
    }

    @Test
    fun `acl disabled allows anyone`() {
        KVUtils.setChannelAclEnabled(false)
        assertEquals(ChannelAccessControl.Decision.ALLOW,
            ChannelAccessControl.authorize(Channel.TELEGRAM, "stranger"))
    }

    @Test
    fun `null or blank sender is denied (fail-closed)`() {
        assertEquals(ChannelAccessControl.Decision.DENY,
            ChannelAccessControl.authorize(Channel.TELEGRAM, null))
        assertEquals(ChannelAccessControl.Decision.DENY,
            ChannelAccessControl.authorize(Channel.TELEGRAM, "   "))
    }

    @Test
    fun `first sender binds as owner (TOFU) then is allowed`() {
        // 空白名单 → 首个发送者绑定
        assertEquals(ChannelAccessControl.Decision.ALLOW_PAIRED,
            ChannelAccessControl.authorize(Channel.TELEGRAM, "owner123"))
        assertTrue(KVUtils.getChannelAllowedSenders(Channel.TELEGRAM.name).contains("owner123"))
        // 同一发送者再来 → 普通放行
        assertEquals(ChannelAccessControl.Decision.ALLOW,
            ChannelAccessControl.authorize(Channel.TELEGRAM, "owner123"))
    }

    @Test
    fun `non-owner is denied after binding`() {
        ChannelAccessControl.authorize(Channel.TELEGRAM, "owner123") // 绑定 owner
        assertEquals(ChannelAccessControl.Decision.DENY,
            ChannelAccessControl.authorize(Channel.TELEGRAM, "attacker"))
    }

    @Test
    fun `clearing allowlist allows re-pairing`() {
        ChannelAccessControl.authorize(Channel.TELEGRAM, "owner123")
        KVUtils.clearChannelAllowedSenders(Channel.TELEGRAM.name)
        // 重新配对 → 下一个发送者成为新 owner
        assertEquals(ChannelAccessControl.Decision.ALLOW_PAIRED,
            ChannelAccessControl.authorize(Channel.TELEGRAM, "newowner"))
    }

    @Test
    fun `channels are isolated`() {
        ChannelAccessControl.authorize(Channel.TELEGRAM, "tg_owner") // 只绑 Telegram
        // Discord 白名单仍为空 → 它的首个发送者独立绑定
        assertEquals(ChannelAccessControl.Decision.ALLOW_PAIRED,
            ChannelAccessControl.authorize(Channel.DISCORD, "dc_owner"))
        // Telegram 的 owner 在 Discord 上不被授权(Discord 已绑 dc_owner)
        assertEquals(ChannelAccessControl.Decision.DENY,
            ChannelAccessControl.authorize(Channel.DISCORD, "tg_owner"))
    }
}
