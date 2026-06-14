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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle(getString(R.string.account_login_title))
            showBackButton(true) { finish() }
        }

        val etMobile = findViewById<EditText>(R.id.etMobile)
        val etCode = findViewById<EditText>(R.id.etCode)
        val btnSendCode = findViewById<KButton>(R.id.btnSendCode)
        val btnLogin = findViewById<KButton>(R.id.btnLogin)

        btnSendCode.setOnClickListener {
            val mobile = etMobile.text.toString().trim()
            if (!isValidMobile(mobile)) {
                toast(getString(R.string.account_invalid_mobile))
                return@setOnClickListener
            }
            btnSendCode.isEnabled = false
            lifecycleScope.launch {
                AccountRepository.sendSmsCode(mobile)
                    .onSuccess { res ->
                        startCountdown(btnSendCode)
                        val code = res.devCode
                        if (!code.isNullOrEmpty()) {
                            etCode.setText(code)
                            toast(getString(R.string.account_mock_code_filled, code))
                        } else {
                            toast(getString(R.string.account_code_sent))
                        }
                    }
                    .onFailure {
                        btnSendCode.isEnabled = true
                        toast(it.message ?: getString(R.string.account_code_send_failed))
                    }
            }
        }

        btnLogin.setOnClickListener {
            val mobile = etMobile.text.toString().trim()
            val code = etCode.text.toString().trim()
            if (!isValidMobile(mobile)) {
                toast(getString(R.string.account_invalid_mobile))
                return@setOnClickListener
            }
            if (code.isEmpty()) {
                toast(getString(R.string.account_invalid_code))
                return@setOnClickListener
            }
            btnLogin.isEnabled = false
            lifecycleScope.launch {
                val r = AccountRepository.login(mobile, code)
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
