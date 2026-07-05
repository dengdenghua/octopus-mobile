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
            override fun onMessageReceived(channel: Channel, message: String, messageID: String, senderId: String?) {
                val app = ClawApplication.instance
                if (!ClawAccessibilityService.isRunning()) {
                    ChannelManager.sendMessage(channel, app.getString(R.string.channel_msg_no_accessibility), messageID)
                    ChannelManager.flushMessages(channel)
                    return
                }
                // 安全:发送者鉴权(ACL)—— 仅授权用户可驱动 Agent 控制设备。
                // 配对码方案:空白名单时不自动绑定,用户需发送 /pair <6位码> 完成绑定。
                // senderId 随消息原子传入(作者标识),不再回读 getLastSenderId 共享字段 → 消除 TOCTOU。
                val decision = ChannelAccessControl.authorize(channel, senderId)
                if (decision == ChannelAccessControl.Decision.DENY) {
                    // 未授权 —— 检查是否为配对命令
                    val pairCode = ChannelAccessControl.extractPairCode(message)
                    if (pairCode != null) {
                        // 配对命令
                        if (ChannelAccessControl.tryPair(channel, senderId, pairCode)) {
                            ChannelManager.sendMessage(
                                channel,
                                "✅ 配对成功,你已被绑定为该通道授权用户。现在可以发送指令控制设备了。",
                                messageID,
                            )
                        } else {
                            ChannelManager.sendMessage(
                                channel,
                                "❌ 配对失败:配对码错误或已过期。请在 App「设置 → 通道访问控制」查看当前配对码。" +
                                    "格式:/pair <6位码>",
                                messageID,
                            )
                        }
                    } else {
                        ChannelManager.sendMessage(
                            channel,
                            "⚠️ 你没有该机器人的控制授权。若这是你本人的机器人,请在 App「设置 → 通道访问控制」" +
                                "查看配对码,然后发送 /pair <6位码> 完成绑定。",
                            messageID,
                        )
                    }
                    ChannelManager.flushMessages(channel)
                    return
                }
                // 直接通过 startNewTask 入队并调度,无需先 tryAcquireTask
                taskOrchestrator.startNewTask(channel, message, messageID)
            }
        })
    }
}
