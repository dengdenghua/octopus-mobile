package com.apk.claw.android.channel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ④-3 回归:验证授权主体(senderId)随消息**原子**穿过 [ChannelManager.dispatchMessage] 送达监听器,
 * 而不是由监听器事后回读共享字段(`getLastSenderId`)——后者在并发消息下存在 TOCTOU:
 * 授权时读到的可能是另一条消息刚覆盖的发送者(群内任意成员借此过 ACL)。
 *
 * 纯 JVM:dispatchMessage 只是把参数转发给监听器,不触发 Android。
 */
class ChannelDispatchSenderIdTest {

    @After
    fun tearDown() {
        ChannelManager.setOnMessageReceivedListener(null)
        com.apk.claw.android.utils.KVUtils.resetForTest()
    }

    private data class Received(val message: String, val messageId: String, val senderId: String?)

    private fun captureNext(): MutableList<Received> {
        val got = mutableListOf<Received>()
        ChannelManager.setOnMessageReceivedListener(object : ChannelManager.OnMessageReceivedListener {
            override fun onMessageReceived(channel: Channel, message: String, messageID: String, senderId: String?) {
                got += Received(message, messageID, senderId)
            }
        })
        return got
    }

    @Test
    fun `senderId travels with the message to the listener`() {
        val got = captureNext()
        ChannelManager.dispatchMessage(Channel.TELEGRAM, "hello", "mid-1", "author-42")
        assertEquals(1, got.size)
        assertEquals("author-42", got[0].senderId)
        assertEquals("hello", got[0].message)
        assertEquals("mid-1", got[0].messageId)
    }

    @Test
    fun `interleaved messages keep their own senderId (no cross-contamination)`() {
        val got = captureNext()
        // 两条来自不同作者的消息:各自的 senderId 必须一一对应,不能被后一条覆盖。
        ChannelManager.dispatchMessage(Channel.DISCORD, "msg-A", "a", "owner-1")
        ChannelManager.dispatchMessage(Channel.DISCORD, "msg-B", "b", "intruder-2")
        assertEquals(listOf("owner-1", "intruder-2"), got.map { it.senderId })
        assertEquals(listOf("msg-A", "msg-B"), got.map { it.message })
    }

    @Test
    fun `null senderId is forwarded as null (fail-closed handled downstream)`() {
        val got = captureNext()
        // 无法识别发送者时传 null,由 ChannelAccessControl.authorize 负责 fail-closed 拒绝。
        ChannelManager.dispatchMessage(Channel.WECHAT, "anon", "mid", null)
        assertEquals(1, got.size)
        assertNull(got[0].senderId)
    }
}
