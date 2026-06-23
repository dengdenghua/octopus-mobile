package com.apk.claw.android.ui.account

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.apk.claw.android.R
import com.apk.claw.android.account.AccountConfig
import com.apk.claw.android.account.AccountRepository
import com.apk.claw.android.account.AccountStore
import com.apk.claw.android.account.CreateOrderResult
import com.apk.claw.android.account.Goods
import com.apk.claw.android.account.OrderStatus
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.widget.CommonToolbar
import com.apk.claw.android.widget.KButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Wallet screen: credits balance, daily check-in, recharge packs, sign out.
 * Recharge: create an order, open the cashier URL when the server provides one
 * (mock settles without one), then poll the order until paid and refresh the
 * balance. All backend calls go through [AccountRepository].
 */
class AccountActivity : BaseActivity() {

    private lateinit var tvCredits: TextView
    private lateinit var tvCreditsBreakdown: TextView
    private lateinit var tvMobile: TextView
    private lateinit var tvMember: TextView
    private lateinit var llGoods: LinearLayout
    private lateinit var tvInviteCode: TextView
    private lateinit var tvInviteSub: TextView
    private lateinit var etInviteCode: EditText
    private lateinit var llRedeem: LinearLayout
    private lateinit var btnTierFast: KButton
    private lateinit var btnTierFlash: KButton
    private lateinit var btnTierPremium: KButton
    private lateinit var tvTierHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AccountStore.isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_account)

        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle(getString(R.string.account_title))
            showBackButton(true) { finish() }
        }
        tvCredits = findViewById(R.id.tvCredits)
        tvCreditsBreakdown = findViewById(R.id.tvCreditsBreakdown)
        tvMobile = findViewById(R.id.tvMobile)
        tvMember = findViewById(R.id.tvMember)
        llGoods = findViewById(R.id.llGoods)
        tvInviteCode = findViewById(R.id.tvInviteCode)
        tvInviteSub = findViewById(R.id.tvInviteSub)
        etInviteCode = findViewById(R.id.etInviteCode)
        llRedeem = findViewById(R.id.llRedeem)
        btnTierFast = findViewById(R.id.btnTierFast)
        btnTierFlash = findViewById(R.id.btnTierFlash)
        btnTierPremium = findViewById(R.id.btnTierPremium)
        tvTierHint = findViewById(R.id.tvTierHint)
        tvMobile.text = AccountStore.mobile

        btnTierFast.setOnClickListener { setTier(AccountConfig.TIER_FAST) }
        btnTierFlash.setOnClickListener { setTier(AccountConfig.TIER_FLASH) }
        btnTierPremium.setOnClickListener { setTier(AccountConfig.TIER_PREMIUM) }
        renderTier()

        findViewById<KButton>(R.id.btnDailyClaim).setOnClickListener { claimDaily() }
        findViewById<KButton>(R.id.btnCopyCode).setOnClickListener { copyInviteCode() }
        findViewById<KButton>(R.id.btnRedeem).setOnClickListener { redeemInvite() }
        findViewById<KButton>(R.id.btnLogout).setOnClickListener {
            AccountRepository.logout()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        observeState()
        loadGoods()
        loadInvite()
        lifecycleScope.launch { AccountRepository.refreshBalance() }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AccountRepository.state.collect { s ->
                    tvCredits.text = s.credits.toString()
                    tvCreditsBreakdown.text = getString(
                        R.string.account_credits_breakdown,
                        s.paidCredits,
                        s.giftCredits,
                    )
                    if (s.mobile.isNotEmpty()) tvMobile.text = s.mobile
                    tvMember.text = if (s.byoUnlocked) {
                        getString(R.string.account_member_active, formatDate(s.memberExpireAt))
                    } else {
                        getString(R.string.account_member_inactive)
                    }
                }
            }
        }
    }

    private fun loadGoods() {
        lifecycleScope.launch {
            AccountRepository.loadGoods().onSuccess { renderGoods(it) }
        }
    }

    private fun renderGoods(goods: List<Goods>) {
        llGoods.removeAllViews()
        val usd = selectedCurrency() == "USD"
        goods.forEach { g ->
            val row = layoutInflater.inflate(R.layout.item_goods, llGoods, false)
            row.findViewById<TextView>(R.id.tvGoodsTitle).text = g.title
            val credits = if (usd && g.usdCredits > 0) g.usdCredits else g.credits
            val bonus = if (usd && g.usdBonusCredits > 0) g.usdBonusCredits else g.bonusCredits
            val isSub = g.kind == "subscription"
            row.findViewById<TextView>(R.id.tvGoodsSub).text = if (isSub) {
                // 订阅:区分两桶 + 月清 + BYO 权益
                getString(R.string.account_goods_sub_subscription, credits, bonus)
            } else {
                getString(R.string.account_goods_credits, credits) +
                    (if (bonus > 0) getString(R.string.account_goods_bonus, bonus) else "")
            }
            row.findViewById<KButton>(R.id.btnBuy).apply {
                text = formatPrice(g) + (if (isSub) getString(R.string.account_per_month_suffix) else "")
                setOnClickListener { buy(g) }
            }
            llGoods.addView(row)
        }
    }

    private fun buy(g: Goods) {
        lifecycleScope.launch {
            val r = AccountRepository.createOrder(g.id, selectedCurrency())
            val order = r.getOrNull()
            if (order == null) {
                toast(r.exceptionOrNull()?.message ?: getString(R.string.account_order_failed))
                return@launch
            }
            handleOrder(order)
        }
    }

    private suspend fun handleOrder(order: CreateOrderResult) {
        // A real server returns a WeChat/Alipay cashier URL; open it. Mock
        // returns null and settles on its own, so we poll directly.
        order.payUrl?.takeIf { it.isNotEmpty() }?.let { url ->
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            toast(getString(R.string.account_pay_opened))
        }
        repeat(10) {
            when (AccountRepository.queryOrder(order.orderNo).getOrNull()?.status) {
                OrderStatus.PAID -> {
                    toast(getString(R.string.account_recharge_success, order.credits))
                    return
                }
                OrderStatus.FAILED, OrderStatus.CANCELLED -> {
                    toast(getString(R.string.account_recharge_failed))
                    return
                }
                else -> delay(1000)
            }
        }
        toast(getString(R.string.account_recharge_pending))
    }

    private var myInviteCode: String = ""

    private fun loadInvite() {
        lifecycleScope.launch {
            AccountRepository.inviteInfo().onSuccess { info ->
                myInviteCode = info.code
                tvInviteCode.text = info.code.ifEmpty { "------" }
                tvInviteSub.text = getString(
                    R.string.account_invite_sub, info.invitedCount, info.inviterBonus,
                )
                llRedeem.visibility = if (info.redeemed) LinearLayout.GONE else LinearLayout.VISIBLE
            }
        }
    }

    private fun copyInviteCode() {
        if (myInviteCode.isEmpty()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("invite", myInviteCode))
        toast(getString(R.string.account_invite_copied))
    }

    private fun redeemInvite() {
        val code = etInviteCode.text.toString().trim().uppercase()
        if (code.isEmpty()) {
            toast(getString(R.string.account_invite_redeem_hint))
            return
        }
        lifecycleScope.launch {
            AccountRepository.redeemInvite(code)
                .onSuccess {
                    toast(getString(R.string.account_invite_redeem_success, it.credits))
                    etInviteCode.setText("")
                    loadInvite()
                }
                .onFailure { toast(it.message ?: getString(R.string.account_invite_redeem_failed)) }
        }
    }

    private fun setTier(tier: String) {
        AccountConfig.modelTier = tier
        renderTier()
    }

    /** 高亮当前档位按钮 + 更新说明文案(只显示档位,不暴露底层模型名)。 */
    private fun renderTier() {
        val tier = AccountConfig.modelTier
        val brand = getColor(R.color.colorBrandPrimary)
        val muted = getColor(R.color.colorContainerBrighten)
        val onBrand = getColor(android.R.color.white)
        val onMuted = getColor(R.color.colorTextPrimary)

        fun apply(button: KButton, selected: Boolean) {
            button.setBgColor(if (selected) brand else muted)
            button.setTextColor(if (selected) onBrand else onMuted)
        }

        apply(btnTierFast, tier == AccountConfig.TIER_FAST)
        apply(btnTierFlash, tier == AccountConfig.TIER_FLASH)
        apply(btnTierPremium, tier == AccountConfig.TIER_PREMIUM)
        tvTierHint.text = getString(
            when (tier) {
                AccountConfig.TIER_FLASH -> R.string.account_tier_flash_hint
                AccountConfig.TIER_PREMIUM -> R.string.account_tier_premium_hint
                else -> R.string.account_tier_fast_hint
            }
        )
    }

    private fun claimDaily() {
        lifecycleScope.launch {
            AccountRepository.dailyClaim()
                .onSuccess {
                    if (it.claimed) toast(getString(R.string.account_claim_success, it.credits))
                    else toast(getString(R.string.account_claim_already))
                }
                .onFailure { toast(it.message ?: getString(R.string.account_claim_failed)) }
        }
    }

    private fun formatDate(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(epochMillis)

    /** 英文区显示美元价(priceUsdCents),其余显示人民币(priceFen)。 */
    private fun selectedCurrency(): String = if (Locale.getDefault().language == "en") "USD" else "CNY"

    private fun formatPrice(g: Goods): String {
        val (sym, cents) = if (selectedCurrency() == "USD" && g.priceUsdCents > 0) "$" to g.priceUsdCents else "¥" to g.priceFen
        return if (cents % 100 == 0L) sym + (cents / 100) else sym + String.format(Locale.US, "%.2f", cents / 100.0)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
