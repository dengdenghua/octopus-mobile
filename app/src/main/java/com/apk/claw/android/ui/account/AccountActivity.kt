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
    private lateinit var tvMobile: TextView
    private lateinit var tvMember: TextView
    private lateinit var llGoods: LinearLayout
    private lateinit var tvInviteCode: TextView
    private lateinit var tvInviteSub: TextView
    private lateinit var etInviteCode: EditText
    private lateinit var llRedeem: LinearLayout
    private lateinit var btnTierFast: KButton
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
        tvMobile = findViewById(R.id.tvMobile)
        tvMember = findViewById(R.id.tvMember)
        llGoods = findViewById(R.id.llGoods)
        tvInviteCode = findViewById(R.id.tvInviteCode)
        tvInviteSub = findViewById(R.id.tvInviteSub)
        etInviteCode = findViewById(R.id.etInviteCode)
        llRedeem = findViewById(R.id.llRedeem)
        btnTierFast = findViewById(R.id.btnTierFast)
        btnTierPremium = findViewById(R.id.btnTierPremium)
        tvTierHint = findViewById(R.id.tvTierHint)
        tvMobile.text = AccountStore.mobile

        btnTierFast.setOnClickListener { setTier(AccountConfig.TIER_FAST) }
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
        goods.forEach { g ->
            val row = layoutInflater.inflate(R.layout.item_goods, llGoods, false)
            row.findViewById<TextView>(R.id.tvGoodsTitle).text = g.title
            val bonus = if (g.bonusCredits > 0) getString(R.string.account_goods_bonus, g.bonusCredits) else ""
            row.findViewById<TextView>(R.id.tvGoodsSub).text =
                getString(R.string.account_goods_credits, g.credits) + bonus
            row.findViewById<KButton>(R.id.btnBuy).apply {
                text = formatPrice(g)
                setOnClickListener { buy(g) }
            }
            llGoods.addView(row)
        }
    }

    private fun buy(g: Goods) {
        lifecycleScope.launch {
            val r = AccountRepository.createOrder(g.id)
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
        val premium = AccountConfig.modelTier == AccountConfig.TIER_PREMIUM
        val brand = getColor(R.color.colorBrandPrimary)
        val muted = getColor(R.color.colorContainerBrighten)
        val onBrand = getColor(android.R.color.white)
        val onMuted = getColor(R.color.colorTextPrimary)
        btnTierFast.setBgColor(if (!premium) brand else muted)
        btnTierFast.setTextColor(if (!premium) onBrand else onMuted)
        btnTierPremium.setBgColor(if (premium) brand else muted)
        btnTierPremium.setTextColor(if (premium) onBrand else onMuted)
        tvTierHint.text = getString(
            if (premium) R.string.account_tier_premium_hint else R.string.account_tier_fast_hint
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
    private fun formatPrice(g: Goods): String {
        val english = Locale.getDefault().language == "en"
        val (sym, cents) = if (english && g.priceUsdCents > 0) "$" to g.priceUsdCents else "¥" to g.priceFen
        return if (cents % 100 == 0L) sym + (cents / 100) else sym + String.format(Locale.US, "%.2f", cents / 100.0)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
