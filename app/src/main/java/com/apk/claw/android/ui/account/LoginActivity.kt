package com.apk.claw.android.ui.account

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.EditText
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.apk.claw.android.R
import com.apk.claw.android.account.AccountRepository
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.widget.CommonToolbar
import com.apk.claw.android.widget.KButton
import kotlinx.coroutines.launch

/**
 * Phone + SMS-code sign-in / sign-up. Talks only to [AccountRepository], which
 * runs against the mock backend by default (the code is auto-filled in mock).
 */
class LoginActivity : BaseActivity() {

    private var countdown: CountDownTimer? = null
    private var mode = "email" // 先只用邮箱登录;手机号 tab 暂隐藏("phone" 仍可用,服务端端点保留)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle(getString(R.string.account_login_title))
            showBackButton(true) { finish() }
        }

        val etAccount = findViewById<EditText>(R.id.etMobile)
        val etCode = findViewById<EditText>(R.id.etCode)
        val btnSendCode = findViewById<KButton>(R.id.btnSendCode)
        val btnLogin = findViewById<KButton>(R.id.btnLogin)
        val tabPhone = findViewById<android.widget.TextView>(R.id.tvTabPhone)
        val tabEmail = findViewById<android.widget.TextView>(R.id.tvTabEmail)
        tabPhone.visibility = android.view.View.GONE // 先不用手机号登录,只留邮箱

        fun applyMode() {
            val phone = mode == "phone"
            tabPhone.setTextColor(getColor(if (phone) R.color.colorBrandPrimary else R.color.colorTextSecondary))
            tabEmail.setTextColor(getColor(if (phone) R.color.colorTextSecondary else R.color.colorBrandPrimary))
            etAccount.hint = getString(if (phone) R.string.account_mobile_hint else R.string.account_email_hint)
            etAccount.inputType = if (phone) {
                android.text.InputType.TYPE_CLASS_PHONE
            } else {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            }
            etAccount.setText("")
            etCode.setText("")
        }
        tabPhone.setOnClickListener { mode = "phone"; applyMode() }
        tabEmail.setOnClickListener { mode = "email"; applyMode() }
        applyMode()

        btnSendCode.setOnClickListener {
            val acct = etAccount.text.toString().trim()
            if (!isValidAccount(acct)) {
                toast(invalidAccountMsg())
                return@setOnClickListener
            }
            btnSendCode.isEnabled = false
            lifecycleScope.launch {
                val res = if (mode == "phone") AccountRepository.sendSmsCode(acct)
                else AccountRepository.sendEmailCode(acct)
                res.onSuccess { r ->
                    startCountdown(btnSendCode)
                    val code = r.devCode
                    if (!code.isNullOrEmpty()) {
                        etCode.setText(code)
                        toast(getString(R.string.account_mock_code_filled, code))
                    } else {
                        toast(getString(R.string.account_code_sent))
                    }
                }.onFailure {
                    btnSendCode.isEnabled = true
                    toast(it.message ?: getString(R.string.account_code_send_failed))
                }
            }
        }

        btnLogin.setOnClickListener {
            val acct = etAccount.text.toString().trim()
            val code = etCode.text.toString().trim()
            if (!isValidAccount(acct)) {
                toast(invalidAccountMsg())
                return@setOnClickListener
            }
            if (code.isEmpty()) {
                toast(getString(R.string.account_invalid_code))
                return@setOnClickListener
            }
            btnLogin.isEnabled = false
            lifecycleScope.launch {
                val r = if (mode == "phone") AccountRepository.login(acct, code)
                else AccountRepository.loginEmail(acct, code)
                btnLogin.isEnabled = true
                r.onSuccess {
                    toast(getString(R.string.account_login_success))
                    startActivity(Intent(this@LoginActivity, AccountActivity::class.java))
                    finish()
                }.onFailure {
                    toast(it.message ?: getString(R.string.account_login_failed))
                }
            }
        }
    }

    private fun isValidAccount(s: String): Boolean =
        if (mode == "phone") isValidMobile(s)
        else s.contains("@") && s.substringAfterLast("@").contains(".")

    private fun invalidAccountMsg(): String =
        getString(if (mode == "phone") R.string.account_invalid_mobile else R.string.account_invalid_email)

    private fun startCountdown(btn: KButton) {
        countdown?.cancel()
        countdown = object : CountDownTimer(60_000, 1_000) {
            override fun onTick(msUntilFinished: Long) {
                btn.text = getString(R.string.account_resend_in, (msUntilFinished / 1000).toInt())
            }

            override fun onFinish() {
                btn.isEnabled = true
                btn.text = getString(R.string.account_send_code)
            }
        }.start()
    }

    private fun isValidMobile(m: String): Boolean = m.length == 11 && m.all { it.isDigit() }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        countdown?.cancel()
        super.onDestroy()
    }
}
