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
    )

    private val users = HashMap<String, User>()          // userId -> state
    private val tokenToUser = HashMap<String, String>()  // token  -> userId
    private val pendingOrders = HashMap<String, Goods>() // orderNo -> goods

    private val catalog = listOf(
        Goods("m_month", "会员月卡", 500, 0, 1900, "解锁自有模型", "membership"),
        Goods("g_100", "100 积分", 100, 0, 990, null),
        Goods("g_500", "500 积分", 500, 50, 3990, "划算"),
        Goods("g_1000", "1000 积分", 1000, 200, 6900, "超值"),
    )

    override suspend fun sendSmsCode(mobile: String): SmsSendResult {
        delay(150)
        return SmsSendResult(ok = true, ttlSeconds = 300, devCode = MOCK_CODE)
    }

    override suspend fun login(mobile: String, code: String): LoginResult {
        delay(150)
        require(code == MOCK_CODE) { "验证码错误（mock 模式固定为 $MOCK_CODE）" }
        val userId = "mock-$mobile"
        val isNew = !users.containsKey(userId)
        users.getOrPut(userId) { User(credits = SIGNUP_BONUS) }
        val token = "mock-token-" + UUID.randomUUID().toString().take(8)
        tokenToUser[token] = userId
        return LoginResult(token, userId, mobile, isNew, nickname = "用户$mobile")
    }

    private fun userOf(token: String): User =
        users[tokenToUser[token]] ?: throw IllegalStateException("未登录或会话已失效")

    override suspend fun profile(token: String): AccountProfile {
        val uid = tokenToUser[token] ?: throw IllegalStateException("未登录")
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

    override suspend fun createOrder(token: String, goodsId: String): CreateOrderResult {
        userOf(token)
        val g = catalog.firstOrNull { it.id == goodsId }
            ?: throw IllegalArgumentException("套餐不存在: $goodsId")
        val orderNo = "MOCK" + System.currentTimeMillis()
        pendingOrders[orderNo] = g
        // payUrl null => no external cashier; client polls queryOrder directly.
        return CreateOrderResult(
            orderNo = orderNo,
            payUrl = null,
            amountFen = g.priceFen,
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
}
