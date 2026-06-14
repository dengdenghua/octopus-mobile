package com.apk.claw.android.ui.splash

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import com.apk.claw.android.R
import com.apk.claw.android.account.AccountStore
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.ui.account.LoginActivity
import com.apk.claw.android.ui.compose.MainActivity

/**
 * 启动页 - 始终进入首页，未配置 LLM 也可进入，可在设置中配置
 */
class SplashActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* 启动页不允许返回 */ }
        })

        // 未登录强制先登录,不准进入主界面
        val loggedIn = AccountStore.isLoggedIn
        val intent = Intent(this, if (loggedIn) MainActivity::class.java else LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        if (!loggedIn) intent.putExtra(LoginActivity.EXTRA_GATE, true)
        startActivity(intent)
        finish()
    }
}
