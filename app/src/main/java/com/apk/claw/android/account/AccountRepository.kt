package com.apk.claw.android.account

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** UI-facing snapshot of the signed-in account. */
data class AccountState(
    val loggedIn: Boolean = false,
    val mobile: String = "",
    val credits: Long = 0,
)

/**
 * Single entry point for account + billing.
 *
 * Picks the mock or http gateway from [AccountConfig], persists the session via
 * [AccountStore], exposes an observable [state] for the UI, and wraps every call
 * in a [Result] so callers never deal with raw exceptions.
 */
object AccountRepository {

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<AccountState> = _state

    // The mock keeps balance in memory, so it must be a single shared instance.
    private val sharedMock by lazy { MockAccountGateway() }

    private fun gateway(): AccountGateway =
        if (AccountConfig.mockMode) sharedMock else HttpAccountGateway(AccountConfig.baseUrl)

    private fun snapshot() =
        AccountState(AccountStore.isLoggedIn, AccountStore.mobile, AccountStore.credits)

    private fun publish() {
        _state.value = snapshot()
    }

    suspend fun sendSmsCode(mobile: String): Result<SmsSendResult> =
        runCatching { gateway().sendSmsCode(mobile) }

    suspend fun login(mobile: String, code: String): Result<LoginResult> {
        val r = runCatching { gateway().login(mobile, code) }
        r.getOrNull()?.let {
            AccountStore.saveLogin(it)
            refreshBalance()
        }
        publish()
        return r
    }

    suspend fun refreshBalance(): Result<Long> {
        if (!AccountStore.isLoggedIn) return Result.success(0L)
        val r = runCatching { gateway().balance(AccountStore.token).credits }
        r.getOrNull()?.let {
            AccountStore.credits = it
            publish()
        }
        return r
    }

    suspend fun loadGoods(): Result<List<Goods>> =
        runCatching { gateway().goods(AccountStore.token).items }

    suspend fun createOrder(goodsId: String): Result<CreateOrderResult> =
        runCatching { gateway().createOrder(AccountStore.token, goodsId) }

    suspend fun queryOrder(orderNo: String): Result<OrderStatusResult> {
        val r = runCatching { gateway().queryOrder(AccountStore.token, orderNo) }
        if (r.getOrNull()?.status == OrderStatus.PAID) refreshBalance()
        return r
    }

    suspend fun dailyClaim(): Result<DailyClaimResult> {
        val r = runCatching { gateway().dailyClaim(AccountStore.token) }
        r.getOrNull()?.let {
            if (it.claimed) {
                AccountStore.credits = it.balance
                publish()
            }
        }
        return r
    }

    fun logout() {
        AccountStore.clear()
        publish()
    }
}
