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
    @SerializedName("paidCredits") val paidCredits: Long = 0,
    @SerializedName("giftCredits") val giftCredits: Long = 0,
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
    @SerializedName("usdCredits") val usdCredits: Long = 0,
    @SerializedName("usdBonusCredits") val usdBonusCredits: Long = 0,
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
    val currency: String = "CNY",
    @SerializedName("amountMinor") val amountMinor: Long = 0,
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

// ── membership / credits ledger / usage ───────────────────────────────
data class MembershipResult(
    val active: Boolean = false,
    @SerializedName("expireAt") val expireAt: Long = 0,
    @SerializedName("remainingDays") val remainingDays: Long = 0,
    val benefits: List<String> = emptyList(),
    @SerializedName("dailyFreeCredits") val dailyFreeCredits: Long = 0,
    @SerializedName("dailyFreeRemaining") val dailyFreeRemaining: Long = 0,
)

data class CreditTransaction(
    val id: Long = 0,
    val delta: Long = 0,
    @SerializedName("balanceAfter") val balanceAfter: Long = 0,
    val source: String = "",
    val detail: String = "",
    @SerializedName("refId") val refId: String = "",
    val ts: Long = 0,
)

data class CreditTransactionsResult(
    val total: Int = 0,
    val items: List<CreditTransaction> = emptyList(),
)

data class UsageSummary(
    @SerializedName("tokensIn") val tokensIn: Long = 0,
    @SerializedName("tokensOut") val tokensOut: Long = 0,
    val credits: Long = 0,
    val calls: Long = 0,
)

data class UsageItem(
    val id: Long = 0,
    val model: String = "",
    @SerializedName("tokens_in") val tokensIn: Long = 0,
    @SerializedName("tokens_out") val tokensOut: Long = 0,
    val credits: Long = 0,
    val ts: Long = 0,
)

data class UsageResult(
    val total: Int = 0,
    val summary: UsageSummary = UsageSummary(),
    val items: List<UsageItem> = emptyList(),
)

// ── device protocol (register / heartbeat / report / status) ──────────
data class DeviceRegisterResult(
    @SerializedName("deviceId") val deviceId: String = "",
    @SerializedName("deviceToken") val deviceToken: String = "",
    @SerializedName("deviceName") val deviceName: String = "",
)

data class DeviceHeartbeatResult(
    val ok: Boolean = false,
    @SerializedName("serverTs") val serverTs: Long = 0,
    val battery: Int = -1,
    val charging: Boolean = false,
    @SerializedName("currentApp") val currentApp: String = "",
    @SerializedName("screenHash") val screenHash: String = "",
)

data class DeviceReportResult(
    val ok: Boolean = false,
)

data class BillingEstimateResult(
    val model: String = "",
    val tier: String = "",
    val multiplier: Double = 0.0,
    @SerializedName("promptTokensEstimated") val promptTokensEstimated: Long = 0,
    @SerializedName("maxTokens") val maxTokens: Long = 0,
    @SerializedName("worstCaseCredits") val worstCaseCredits: Long = 0,
    @SerializedName("dailyFreeCredits") val dailyFreeCredits: Long = 0,
    @SerializedName("chargeableCredits") val chargeableCredits: Long = 0,
    @SerializedName("estimatedRmb") val estimatedRmb: Double = 0.0,
)

data class DeviceStatusResult(
    @SerializedName("deviceId") val deviceId: String = "",
    @SerializedName("deviceName") val deviceName: String = "",
    @SerializedName("createdAt") val createdAt: Long = 0,
    @SerializedName("lastSeen") val lastSeen: Long = 0,
    @SerializedName("lastHeartbeatAt") val lastHeartbeatAt: Long = 0,
    @SerializedName("batteryLevel") val batteryLevel: Int = -1,
    @SerializedName("osVersion") val osVersion: String = "",
    @SerializedName("appVersion") val appVersion: String = "",
    @SerializedName("deviceModel") val deviceModel: String = "",
    val revoked: Boolean = false,
)
