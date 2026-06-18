package com.apk.claw.android.account

import com.google.gson.annotations.SerializedName

/**
 * Wire DTOs for the octopus account + billing backend.
 *
 * Shape mirrors the octopus-agent Molili integration (phone/SMS login ->
 * token -> credits wallet -> goods -> recharge order -> daily-claim) but is
 * octopus's own. Which SMS / payment vendor sits behind the server is a
 * server-side decision; the app stays vendor-agnostic by only speaking this
 * JSON contract. Money is always carried in *fen* (1/100 元) to avoid floats.
 */

// ── auth ──────────────────────────────────────────────────────────────
data class SmsSendResult(
    val ok: Boolean = false,
    @SerializedName("ttlSeconds") val ttlSeconds: Int = 60,
    /** dev/mock only: the code, so devs can sign in without a real SMS gateway. */
    @SerializedName("devCode") val devCode: String? = null,
)

data class LoginResult(
    val token: String = "",
    val userId: String = "",
    val mobile: String = "",
    val email: String = "",
    @SerializedName("isNewUser") val isNewUser: Boolean = false,
    val nickname: String? = null,
    val avatar: String? = null,
)

data class AccountProfile(
    val userId: String = "",
    val mobile: String = "",
    val nickname: String? = null,
    val avatar: String? = null,
)

data class BalanceResult(
    val credits: Long = 0,
    /** Whether the user has an active monthly membership (unlocks BYO own-model). */
    @SerializedName("membershipActive") val membershipActive: Boolean = false,
    /** Membership expiry, epoch millis; 0 = not a member. */
    @SerializedName("membershipExpireAt") val membershipExpireAt: Long = 0,
)

// ── billing ───────────────────────────────────────────────────────────
data class Goods(
    val id: String = "",
    val title: String = "",
    val credits: Long = 0,
    @SerializedName("bonusCredits") val bonusCredits: Long = 0,
    /** price in fen (1/100 元). */
    @SerializedName("priceFen") val priceFen: Long = 0,
    val tag: String? = null,
    /** "credits" = top up balance; "membership" = unlock BYO own-model for the month. */
    val kind: String = "credits",
    /** price in US cents (1/100 USD); shown in English locale. 0 = fall back to RMB. */
    @SerializedName("priceUsdCents") val priceUsdCents: Long = 0,
)

data class GoodsList(
    val items: List<Goods> = emptyList(),
)

enum class OrderStatus { PENDING, PAID, FAILED, CANCELLED }

data class CreateOrderResult(
    @SerializedName("orderNo") val orderNo: String = "",
    /**
     * URL to open for payment (WeChat/Alipay H5 cashier). When null the order
     * settles without an external cashier (mock, or balance-only flows) and the
     * client should poll [queryOrder] directly.
     */
    @SerializedName("payUrl") val payUrl: String? = null,
    @SerializedName("amountFen") val amountFen: Long = 0,
    val credits: Long = 0,
)

data class OrderStatusResult(
    @SerializedName("orderNo") val orderNo: String = "",
    val status: OrderStatus = OrderStatus.PENDING,
    /** credits granted once PAID. */
    val credits: Long = 0,
)

// ── invite(拉新返利) ──
data class InviteInfo(
    val code: String = "",
    @SerializedName("invitedCount") val invitedCount: Int = 0,
    val redeemed: Boolean = false,
    @SerializedName("redeemerBonus") val redeemerBonus: Long = 0,
    @SerializedName("inviterBonus") val inviterBonus: Long = 0,
)

data class RedeemResult(
    val ok: Boolean = false,
    val credits: Long = 0,
    val balance: Long = 0,
)

data class DailyClaimResult(
    val claimed: Boolean = false,
    /** credits granted by this claim (0 if already claimed today). */
    val credits: Long = 0,
    /** balance after the claim. */
    val balance: Long = 0,
    @SerializedName("nextClaimAt") val nextClaimAt: Long = 0L,
)
