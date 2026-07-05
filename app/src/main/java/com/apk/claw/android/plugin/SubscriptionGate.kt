package com.apk.claw.android.plugin

import com.apk.claw.android.ui.compose.screen.SquarePostApi
import com.apk.claw.android.utils.KVUtils

/**
 * mini-app 订阅门控:记录哪些已安装 mini-app 是「按月订阅」制,打开前校验订阅有效。
 *
 * - 订阅成功时 [mark] 其 slug;打开时 [isGated] 命中则 [checkActive] 查服务端有效期,失效即拦。
 * - [checkActive] 网络失败 fail-open(返 true),不因抖动锁死付费用户;仅服务端明确 active=false 才拦。
 *   （纯本地 mini-app 的订阅本就是软门控——真正硬锁要靠连服务器的插件自身校验,见 VPN。）
 */
internal object SubscriptionGate {
    private const val KEY = "SUBSCRIPTION_GATED_SLUGS"

    private fun gatedSet(): MutableSet<String> =
        KVUtils.getString(KEY, "").split(",").filter { it.isNotBlank() }.toMutableSet()

    /** 订阅成功后标记该 mini-app 为订阅制(打开时会校验)。 */
    fun mark(ref: String) {
        if (ref.isBlank()) return
        val s = gatedSet()
        if (s.add(ref)) KVUtils.putString(KEY, s.joinToString(","))
    }

    /** 该 mini-app 是否订阅制(需打开前校验)。 */
    fun isGated(ref: String): Boolean = ref.isNotBlank() && ref in gatedSet()

    /** 查订阅是否有效。网络失败 fail-open(返 true),避免抖动锁死付费用户。 */
    suspend fun checkActive(ref: String): Boolean =
        runCatching { SquarePostApi.subscriptionStatus(ref).active }.getOrDefault(true)
}
