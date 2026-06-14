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
    suspend fun balance(token: String): BalanceResult
    suspend fun goods(token: String): GoodsList
    suspend fun createOrder(token: String, goodsId: String): CreateOrderResult
    suspend fun queryOrder(token: String, orderNo: String): OrderStatusResult
    suspend fun dailyClaim(token: String): DailyClaimResult
    suspend fun inviteInfo(token: String): InviteInfo
    suspend fun redeemInvite(token: String, code: String): RedeemResult
}
