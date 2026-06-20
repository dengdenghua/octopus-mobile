package com.apk.claw.android.channel

import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.TaskOrchestrator
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.utils.KVUtils

/**
 * 通道初始化与消息路由。
 * 负责读取本地配置初始化各通道，并将收到的消息分发给 [TaskOrchestrator]。
 */
class ChannelSetup(
    private val taskOrchestrator: TaskOrchestrator
) {

    fun setup() {
        ChannelManager.init(
            dingtalkAppKey = KVUtils.getDingtalkAppKey().ifEmpty { null },
            dingtalkAppSecret = KVUtils.getDingtalkAppSecret().ifEmpty { null },
            feishuAppId = KVUtils.getFeishuAppId().ifEmpty { null },
            feishuAppSecret = KVUtils.getFeishuAppSecret().ifEmpty { null },
            qqAppId = KVUtils.getQqAppId().ifEmpty { null },
            qqAppSecret = KVUtils.getQqAppSecret().ifEmpty { null },
            discordBotToken = KVUtils.getDiscordBotToken().ifEmpty { null },
            telegramBotToken = KVUtils.getTelegramBotToken().ifEmpty { null },
            wechatBotToken = KVUtils.getWechatBotToken().ifEmpty { null },
            wechatApiBaseUrl = KVUtils.getWechatApiBaseUrl().ifEmpty { null }
        )
        ChannelManager.setOnMessageReceivedListener(object : ChannelManager.OnMessageReceivedListener {
            override fun onMessageReceived(channel: Channel, message: String, messageID: String) {
                val app = ClawApplication.instance
                if (!ClawAccessibilityService.isRunning()) {
                    ChannelManager.sendMessage(channel, app.getString(R.string.channel_msg_no_accessibility), messageID)
                    ChannelManager.flushMessages(channel)
                    return
                }
                // 安全：发送者鉴权（ACL）—— 仅授权用户可驱动 Agent 控制设备。
                // 默认 TOFU：首个发送者自动绑定为该通道 owner，其后未授权发送者一律拒绝。
                val senderId = ChannelManager.getLastSenderId(channel)
                if (ChannelAccessControl.authorize(channel, senderId) == ChannelAccessControl.Decision.DENY) {
                    ChannelManager.sendMessage(
                        channel,
                        "⚠️ 你没有该机器人的控制授权（仅授权用户可操作设备）。" +
                            "若这是你本人的机器人，请在 App 设置中清空该通道白名单后重新发送以重新配对。",
                        messageID,
                    )
                    ChannelManager.flushMessages(channel)
                    return
                }
                // 直接通过 startNewTask 入队并调度，无需先 tryAcquireTask
                taskOrchestrator.startNewTask(channel, message, messageID)
            }
        })
    }
}
