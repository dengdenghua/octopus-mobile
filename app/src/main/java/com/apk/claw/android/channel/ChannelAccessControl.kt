package com.apk.claw.android.channel

import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog

/**
 * 通道发送者访问控制（ACL）。
 *
 * 安全背景：在此之前，**任何能给机器人发消息的人**（私聊机器人的陌生人、机器人所在群的
 * 任意成员）发一句话即可驱动 Agent 完全控制手机（读短信、发短信、外传文件、任意操作）。
 * 本控制在消息进入 Agent 之前做发送者身份校验。
 *
 * 默认策略（安全默认，可在设置里关闭）：
 *  - 默认启用 ACL。
 *  - 每个通道维护一份授权发送者白名单。
 *  - **TOFU（Trust On First Use）**：白名单为空时，首个发送者被自动绑定为该通道 owner 并放行；
 *    其后非白名单发送者一律拒绝。这样不破坏 owner 的首次使用，又能挡住其后的攻击者。
 *  - 当通道无法提供发送者标识（senderId 为空）时**默认拒绝**——所有已实现通道均能提供 senderId，
 *    null 表示异常状态或未鉴权通道，不应放行。若确有不上报 senderId 的合法通道，可在设置中关闭 ACL。
 *
 * 重新配对：在设置里清空某通道白名单（[KVUtils.clearChannelAllowedSenders]）即可让下一个
 * 发送者重新成为 owner。
 *
 * 已知局限（待后续）：TOFU 存在"攻击者抢先发首条消息"的竞态窗口；更强方案是配对码/扫码绑定。
 */
object ChannelAccessControl {

    private const val TAG = "ChannelACL"

    enum class Decision { ALLOW, ALLOW_PAIRED, DENY }

    /**
     * 判定某通道的某发送者是否被授权驱动 Agent。
     */
    fun authorize(channel: Channel, senderId: String?): Decision {
        if (!KVUtils.isChannelAclEnabled()) return Decision.ALLOW

        if (senderId.isNullOrBlank()) {
            // 无法识别发送者：默认拒绝（fail-closed）。所有已实现通道均能提供 senderId，
            // null 表示异常状态或未鉴权通道。若确有不上报 senderId 的合法通道，可在设置中关闭 ACL。
            XLog.w(TAG, "[${channel.displayName}] 无法识别发送者，拒绝（fail-closed）")
            return Decision.DENY
        }

        val allowed = KVUtils.getChannelAllowedSenders(channel.name)
        if (allowed.isEmpty()) {
            // TOFU：首个发送者绑定为 owner。
            KVUtils.addChannelAllowedSender(channel.name, senderId)
            XLog.i(TAG, "[${channel.displayName}] 首个发送者已绑定为授权用户：${mask(senderId)}")
            return Decision.ALLOW_PAIRED
        }

        return if (senderId in allowed) {
            Decision.ALLOW
        } else {
            XLog.w(TAG, "[${channel.displayName}] 拒绝未授权发送者：${mask(senderId)}")
            Decision.DENY
        }
    }

    /** 脱敏发送者标识，避免把用户 id 明文写进日志。 */
    private fun mask(id: String): String =
        if (id.length <= 6) "***" else id.take(4) + "***" + id.takeLast(2)
}
