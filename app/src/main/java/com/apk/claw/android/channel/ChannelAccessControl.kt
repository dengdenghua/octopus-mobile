package com.apk.claw.android.channel

import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog

/**
 * 通道发送者访问控制(ACL)。
 *
 * 安全背景:在此之前,**任何能给机器人发消息的人**(私聊机器人的陌生人、机器人所在群的
 * 任意成员)发一句话即可驱动 Agent 完全控制手机(读短信、发短信、外传文件、任意操作)。
 * 本控制在消息进入 Agent 之前做发送者身份校验。
 *
 * 默认策略(安全默认,可在设置里关闭):
 *  - 默认启用 ACL。
 *  - 每个通道维护一份授权发送者白名单。
 *  - **配对码绑定**:白名单为空时,**不再自动绑定首个发送者**(旧 TOFU 方案有抢首条竞态风险)。
 *    用户需在 App 内查看 6 位配对码,通过 IM 发送 `/pair <code>` 完成绑定。配对码 10 分钟过期,
 *    配对成功后立即失效(防重放)。
 *  - 当通道无法提供发送者标识(senderId 为空)时**默认拒绝**——所有已实现通道均能提供 senderId,
 *    null 表示异常状态或未鉴权通道,不应放行。若确有不上报 senderId 的合法通道,可在设置中关闭 ACL。
 *
 * 重新配对:在设置里清空某通道白名单([KVUtils.clearChannelAllowedSenders])即重新进入待配对状态,
 * 用户刷新配对码后通过 IM 发送 `/pair <code>` 绑定新 owner。
 */
object ChannelAccessControl {

    private const val TAG = "ChannelACL"

    enum class Decision { ALLOW, DENY }

    /** 配对命令前缀,大小写不敏感。格式:`/pair <6位码>`。 */
    const val PAIR_COMMAND_PREFIX = "/pair "

    /**
     * 判定某通道的某发送者是否被授权驱动 Agent。
     *
     * 注意:此方法**不再自动绑定首个发送者**(废弃 TOFU)。空白名单时返回 [Decision.DENY],
     * 调用方应检查消息是否为配对命令并调用 [tryPair]。
     */
    fun authorize(channel: Channel, senderId: String?): Decision {
        if (!KVUtils.isChannelAclEnabled()) return Decision.ALLOW

        if (senderId.isNullOrBlank()) {
            // 无法识别发送者:默认拒绝(fail-closed)。所有已实现通道均能提供 senderId,
            // null 表示异常状态或未鉴权通道。若确有不上报 senderId 的合法通道,可在设置中关闭 ACL。
            XLog.w(TAG, "[${channel.displayName}] 无法识别发送者,拒绝(fail-closed)")
            return Decision.DENY
        }

        val allowed = KVUtils.getChannelAllowedSenders(channel.name)
        return if (senderId in allowed) {
            Decision.ALLOW
        } else {
            XLog.w(TAG, "[${channel.displayName}] 拒绝未授权发送者:${mask(senderId)}")
            Decision.DENY
        }
    }

    /**
     * 尝试用配对码绑定发送者。
     *
     * @param channel 通道
     * @param senderId 发送者标识(非空)
     * @param code 用户输入的 6 位配对码
     * @return true=配对成功(已加入白名单,配对码已失效);false=配对码错误/过期/senderId 非法
     */
    fun tryPair(channel: Channel, senderId: String?, code: String): Boolean {
        if (senderId.isNullOrBlank() || code.isBlank()) return false
        val ok = KVUtils.consumeChannelPairingCode(channel.name, senderId, code)
        if (ok) {
            XLog.i(TAG, "[${channel.displayName}] 配对成功,已绑定发送者:${mask(senderId)}")
        } else {
            XLog.w(TAG, "[${channel.displayName}] 配对失败(码错误/过期)")
        }
        return ok
    }

    /** 获取某通道当前有效配对码(过期或不存在返回 null)。 */
    fun getPairingCode(channel: Channel): String? = KVUtils.getChannelPairingCode(channel.name)

    /** 强制刷新配对码,返回新码。 */
    fun refreshPairingCode(channel: Channel): String =
        KVUtils.refreshChannelPairingCode(channel.name)

    /** 判断消息是否为配对命令,是则返回码本身,否则返回 null。 */
    fun extractPairCode(message: String): String? {
        val trimmed = message.trim()
        if (!trimmed.startsWith(PAIR_COMMAND_PREFIX, ignoreCase = true)) return null
        val code = trimmed.substring(PAIR_COMMAND_PREFIX.length).trim()
        return code.ifBlank { null }
    }

    /** 脱敏发送者标识,避免把用户 id 明文写进日志。 */
    private fun mask(id: String): String =
        if (id.length <= 6) "***" else id.take(4) + "***" + id.takeLast(2)
}
