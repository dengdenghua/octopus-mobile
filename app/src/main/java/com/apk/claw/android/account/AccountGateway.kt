package com.apk.claw.android.account

/**
 * Backend contract for account + billing.
 *
 * Two implementations: [MockAccountGateway] (in-memory, default, no server) and
 * [HttpAccountGateway] (octopus's own account server). Every method is a
 * suspend function and may throw on transport/server error — callers go through
 * [AccountRepository], which wraps every call in a [Result].
 */
interface AccountGateway {
    suspend fun sendSmsCode(mobile: String): SmsSendResult
    suspend fun login(mobile: String, code: String): LoginResult
    suspend fun sendEmailCode(email: String): SmsSendResult
    suspend fun loginEmail(email: String, code: String): LoginResult
    suspend fun profile(token: String): AccountProfile
    /** 改昵称(社区/榜单/帖子作者名都读它)。返回更新后的 profile。 */
    suspend fun updateNickname(token: String, nickname: String): AccountProfile
    suspend fun balance(token: String): BalanceResult
    suspend fun goods(token: String): GoodsList
    suspend fun createOrder(token: String, goodsId: String, currency: String = "CNY"): CreateOrderResult
    suspend fun queryOrder(token: String, orderNo: String): OrderStatusResult
    suspend fun dailyClaim(token: String): DailyClaimResult
    suspend fun inviteInfo(token: String): InviteInfo
    suspend fun redeemInvite(token: String, code: String): RedeemResult

    // membership / credits ledger / usage
    suspend fun membership(token: String): MembershipResult
    suspend fun creditTransactions(token: String, limit: Int = 50, offset: Int = 0): CreditTransactionsResult
    suspend fun usage(token: String, limit: Int = 50, offset: Int = 0): UsageResult
    suspend fun billingEstimate(
        token: String,
        model: String? = null,
        messages: List<Map<String, String>> = emptyList(),
        maxTokens: Int? = null,
    ): BillingEstimateResult

    // device protocol (register / heartbeat / report / status)
    suspend fun registerDevice(
        token: String,
        deviceId: String? = null,
        deviceName: String? = null,
        pushToken: String? = null,
        osVersion: String? = null,
        appVersion: String? = null,
        deviceModel: String? = null,
    ): DeviceRegisterResult

    suspend fun sendDeviceHeartbeat(
        token: String,
        deviceId: String,
        battery: Int? = null,
        isCharging: Boolean = false,
        currentApp: String? = null,
        screenHash: String? = null,
    ): DeviceHeartbeatResult

    suspend fun reportDeviceEvent(
        token: String,
        deviceId: String,
        type: String,
        payload: Map<String, Any> = emptyMap(),
    ): DeviceReportResult

    suspend fun deviceStatus(token: String, deviceId: String): DeviceStatusResult

    /** 插件内购:从用户积分余额原子扣除 credits(余额不足时服务端返回 402 错误)。 */
    suspend fun pluginPay(
        token: String,
        pluginId: String,
        item: String,
        credits: Int,
        description: String = "",
    ): PluginPayResult

    // 创作者中心(分成收益看板 + 排行榜)
    suspend fun creatorDashboard(token: String): CreatorDashboardResult
    suspend fun creatorRanking(token: String): CreatorRankingResult
}
