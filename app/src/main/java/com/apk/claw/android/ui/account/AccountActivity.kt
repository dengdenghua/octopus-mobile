package com.apk.claw.android.ui.account

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.apk.claw.android.R
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
    private lateinit var llGoods: LinearLayout

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
        llGoods = findViewById(R.id.llGoods)
        tvMobile.text = AccountStore.mobile

        findViewById<KButton>(R.id.btnDailyClaim).setOnClickListener { claimDaily() }
        findViewById<KButton>(R.id.btnLogout).setOnClickListener {
            AccountRepository.logout()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        observeState()
        loadGoods()
        lifecycleScope.launch { AccountRepository.refreshBalance() }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AccountRepository.state.collect { s ->
                    tvCredits.text = s.credits.toString()
                    if (s.mobile.isNotEmpty()) tvMobile.text = s.mobile
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
                text = formatPrice(g.priceFen)
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

    private fun formatPrice(fen: Long): String =
        if (fen % 100 == 0L) "¥" + (fen / 100) else "¥" + String.format(Locale.US, "%.2f", fen / 100.0)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
