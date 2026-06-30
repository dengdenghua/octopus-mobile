package com.apk.claw.android.account

import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

/**
 * In-memory mock backend so the account + billing UX runs with no server.
 *
 * - the SMS code is always [MOCK_CODE]
 * - a brand-new user is granted [SIGNUP_BONUS] credits
 * - orders auto-settle as PAID on the first [queryOrder] and grant their credits
 * - daily-claim grants [DAILY_BONUS] once per calendar day
 *
 * Swap for [HttpAccountGateway] by setting [AccountConfig.baseUrl].
 */
class MockAccountGateway : AccountGateway {

    companion object {
        const val MOCK_CODE = "123456"
        private const val SIGNUP_BONUS = 100L
        private const val DAILY_BONUS = 20L
    }

    private data class User(
        var credits: Long = 0,
        var lastClaimDay: String = "",
        var memberExpireAt: Long = 0,
        var inviteCode: String = "",
        var invitedBy: String = "",
    )

    private val users = HashMap<String, User>()          // userId -> state
    private val tokenToUser = HashMap<String, String>()  // token  -> userId
    private val pendingOrders = HashMap<String, Goods>() // orderNo -> goods

    private val catalog = listOf(
        Goods(id = "m_month", title = "Monthly Pass", credits = 500, bonusCredits = 0,
              priceFen = 1900, tag = "Unlock built-in models", kind = "membership"),
        Goods(id = "g_100", title = "100 Credits", credits = 100, bonusCredits = 0, priceFen = 990),
        Goods(id = "g_500", title = "500 Credits", credits = 500, bonusCredits = 50, priceFen = 3990, tag = "Best value"),
        Goods(id = "g_1000", title = "1000 Credits", credits = 1000, bonusCredits = 200, priceFen = 6900, tag = "Super value"),
    )

    override suspend fun sendSmsCode(mobile: String): SmsSendResult {
        delay(150)
        return SmsSendResult(ok = true, ttlSeconds = 300, devCode = MOCK_CODE)
    }

    override suspend fun login(mobile: String, code: String): LoginResult {
        delay(150)
        require(code == MOCK_CODE) { "Invalid code (mock mode fixed: $MOCK_CODE)" }
        val userId = "mock-$mobile"
        val isNew = !users.containsKey(userId)
        users.getOrPut(userId) { User(credits = SIGNUP_BONUS) }
        val token = "mock-token-" + UUID.randomUUID().toString().take(8)
        tokenToUser[token] = userId
        return LoginResult(token = token, userId = userId, mobile = mobile, isNewUser = isNew,
                           nickname = "User$mobile")
    }

    override suspend fun sendEmailCode(email: String): SmsSendResult {
        delay(150)
        return SmsSendResult(ok = true, ttlSeconds = 300, devCode = MOCK_CODE)
    }

    override suspend fun loginEmail(email: String, code: String): LoginResult {
        delay(150)
        require(code == MOCK_CODE) { "Invalid code (mock mode fixed: $MOCK_CODE)" }
        val userId = "mock-email-$email"
        val isNew = !users.containsKey(userId)
        users.getOrPut(userId) { User(credits = SIGNUP_BONUS) }
        val token = "mock-token-" + UUID.randomUUID().toString().take(8)
        tokenToUser[token] = userId
        return LoginResult(token = token, userId = userId, email = email, isNewUser = isNew,
                           nickname = email.substringBefore("@"))
    }

    private fun userOf(token: String): User =
        users[tokenToUser[token]] ?: throw IllegalStateException("Not logged in or session expired")

    override suspend fun profile(token: String): AccountProfile {
        val uid = tokenToUser[token] ?: throw IllegalStateException("Not logged in")
        val mobile = uid.removePrefix("mock-")
        return AccountProfile(uid, mobile, "用户$mobile", null)
    }

    override suspend fun balance(token: String): BalanceResult {
        val u = userOf(token)
        val active = u.memberExpireAt > System.currentTimeMillis()
        return BalanceResult(
            credits = u.credits,
            membershipActive = active,
            membershipExpireAt = if (active) u.memberExpireAt else 0L,
        )
    }

    override suspend fun goods(token: String): GoodsList = GoodsList(catalog)

    override suspend fun createOrder(token: String, goodsId: String, currency: String): CreateOrderResult {
        userOf(token)
        val g = catalog.firstOrNull { it.id == goodsId }
            ?: throw IllegalArgumentException("Package not found: $goodsId")
        val orderNo = "MOCK" + System.currentTimeMillis()
        pendingOrders[orderNo] = g
        val normalized = currency.uppercase()
        val amountMinor = if (normalized == "USD" && g.priceUsdCents > 0) g.priceUsdCents else g.priceFen
        // payUrl null => no external cashier; client polls queryOrder directly.
        return CreateOrderResult(
            orderNo = orderNo,
            payUrl = null,
            amountFen = g.priceFen,
            currency = if (normalized == "USD" && g.priceUsdCents > 0) "USD" else "CNY",
            amountMinor = amountMinor,
            credits = g.credits + g.bonusCredits,
        )
    }

    override suspend fun queryOrder(token: String, orderNo: String): OrderStatusResult {
        delay(250)
        val g = pendingOrders.remove(orderNo)
            ?: return OrderStatusResult(orderNo, OrderStatus.FAILED, 0)
        val granted = g.credits + g.bonusCredits
        val u = userOf(token)
        u.credits += granted
        if (g.kind == "membership") {
            val base = maxOf(u.memberExpireAt, System.currentTimeMillis())
            u.memberExpireAt = base + 30L * 24 * 60 * 60 * 1000 // +30 天
        }
        return OrderStatusResult(orderNo, OrderStatus.PAID, granted)
    }

    override suspend fun dailyClaim(token: String): DailyClaimResult {
        val u = userOf(token)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(System.currentTimeMillis())
        if (u.lastClaimDay == today) {
            return DailyClaimResult(claimed = false, credits = 0, balance = u.credits)
        }
        u.lastClaimDay = today
        u.credits += DAILY_BONUS
        return DailyClaimResult(claimed = true, credits = DAILY_BONUS, balance = u.credits)
    }

    override suspend fun inviteInfo(token: String): InviteInfo {
        val uid = tokenToUser[token] ?: throw IllegalStateException("Not logged in")
        val u = users[uid]!!
        if (u.inviteCode.isEmpty()) {
            u.inviteCode = UUID.randomUUID().toString().replace("-", "").uppercase().take(6)
        }
        val count = users.values.count { it.invitedBy == uid }
        return InviteInfo(u.inviteCode, count, u.invitedBy.isNotEmpty(), 200, 200)
    }

    override suspend fun redeemInvite(token: String, code: String): RedeemResult {
        val uid = tokenToUser[token] ?: throw IllegalStateException("Not logged in")
        val u = users[uid]!!
        require(u.invitedBy.isEmpty()) { "Invite code already used" }
        val inviterId = users.entries.firstOrNull { it.value.inviteCode == code.uppercase() }?.key
            ?: throw IllegalArgumentException("Invalid invite code")
        require(inviterId != uid) { "Cannot use your own invite code" }
        u.invitedBy = inviterId
        u.credits += 200
        users[inviterId]!!.credits += 200
        return RedeemResult(ok = true, credits = 200, balance = u.credits)
    }

    override suspend fun membership(token: String): MembershipResult {
        val u = userOf(token)
        val active = u.memberExpireAt > System.currentTimeMillis()
        val remaining = maxOf(0L, u.memberExpireAt - System.currentTimeMillis())
        return MembershipResult(
            active = active,
            expireAt = if (active) u.memberExpireAt else 0L,
            remainingDays = remaining / (24 * 3600 * 1000),
            benefits = listOf("解锁自有模型(BYO)", "不消耗平台积分", "每日免费额度"),
            dailyFreeCredits = 2,
            dailyFreeRemaining = 2,
        )
    }

    override suspend fun creditTransactions(token: String, limit: Int, offset: Int): CreditTransactionsResult {
        val u = userOf(token)
        return CreditTransactionsResult(
            total = 1,
            items = listOf(
                CreditTransaction(
                    id = 1,
                    delta = u.credits,
                    balanceAfter = u.credits,
                    source = "mock",
                    detail = "mock mode",
                ),
            ),
        )
    }

    override suspend fun usage(token: String, limit: Int, offset: Int): UsageResult {
        return UsageResult()
    }

    override suspend fun billingEstimate(
        token: String,
        model: String?,
        messages: List<Map<String, String>>,
        maxTokens: Int?,
    ): BillingEstimateResult {
        userOf(token)
        return BillingEstimateResult(
            model = model ?: "agnes-2.0-flash",
            tier = "fast",
            multiplier = 0.2,
            worstCaseCredits = 1,
            dailyFreeCredits = 2,
            chargeableCredits = 0,
            estimatedRmb = 0.0,
        )
    }

    override suspend fun registerDevice(
        token: String,
        deviceId: String?,
        deviceName: String?,
        pushToken: String?,
        osVersion: String?,
        appVersion: String?,
        deviceModel: String?,
    ): DeviceRegisterResult {
        userOf(token)
        return DeviceRegisterResult(
            deviceId = deviceId ?: "mock-device-${System.currentTimeMillis()}",
            deviceToken = "mock-device-token",
            deviceName = deviceName ?: "Mock Device",
        )
    }

    override suspend fun sendDeviceHeartbeat(
        token: String,
        deviceId: String,
        battery: Int?,
        isCharging: Boolean,
        currentApp: String?,
        screenHash: String?,
    ): DeviceHeartbeatResult {
        userOf(token)
        return DeviceHeartbeatResult(
            ok = true,
            serverTs = System.currentTimeMillis(),
            battery = battery ?: -1,
            charging = isCharging,
            currentApp = currentApp ?: "",
            screenHash = screenHash ?: "",
        )
    }

    override suspend fun reportDeviceEvent(
        token: String,
        deviceId: String,
        type: String,
        payload: Map<String, Any>,
    ): DeviceReportResult {
        userOf(token)
        return DeviceReportResult(ok = true)
    }

    override suspend fun deviceStatus(token: String, deviceId: String): DeviceStatusResult {
        userOf(token)
        return DeviceStatusResult(deviceId = deviceId, deviceName = "Mock Device")
    }

    override suspend fun pluginPay(
        token: String, pluginId: String, item: String, credits: Int, description: String,
    ): PluginPayResult {
        val u = userOf(token)
        if (u.credits < credits) return PluginPayResult(success = false)
        // Mock: 只报成功,不真正扣(测试用)
        return PluginPayResult(success = true, data = PluginPayData(balanceAfter = u.credits - credits, pluginId = pluginId, item = item))
    }
}
