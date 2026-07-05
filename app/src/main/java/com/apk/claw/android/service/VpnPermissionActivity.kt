package com.apk.claw.android.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.apk.claw.android.utils.XLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class VpnPermissionActivity : ComponentActivity() {

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnAndFinish()
        } else {
            XLog.i(TAG, "用户拒绝了VPN授权")
            Toast.makeText(this, "VPN 权限被拒绝，无法启动代理", Toast.LENGTH_SHORT).show()
            reportResult(false)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = intent.getStringExtra(EXTRA_HOST)
        val port = intent.getIntExtra(EXTRA_PORT, 0)
        if (host.isNullOrBlank() || port <= 0) {
            reportResult(false)
            finish()
            return
        }
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent == null) {
            startVpnAndFinish()
        } else {
            runCatching { vpnLauncher.launch(prepareIntent) }
                .onFailure {
                    XLog.e(TAG, "拉起VPN授权失败: ${it.message}")
                    reportResult(false)
                    finish()
                }
        }
    }

    private fun startVpnAndFinish() {
        val host = intent.getStringExtra(EXTRA_HOST) ?: return
        val port = intent.getIntExtra(EXTRA_PORT, 0)
        val username = intent.getStringExtra(EXTRA_USERNAME)?.takeIf { it.isNotBlank() }
        val password = intent.getStringExtra(EXTRA_PASSWORD)?.takeIf { it.isNotBlank() }
        val config = ClawVpnService.VpnConfig(host, port, username, password)
        val ok = ClawVpnService.start(this, config)
        reportResult(ok)
        if (!ok) {
            Toast.makeText(this, "VPN 启动失败，请检查代理服务器", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun reportResult(success: Boolean) {
        pendingResult?.set(success)
        pendingLatch?.countDown()
        pendingResult = null
        pendingLatch = null
    }

    companion object {
        private const val TAG = "VpnPermissionAct"
        private const val EXTRA_HOST = "vpn_host"
        private const val EXTRA_PORT = "vpn_port"
        private const val EXTRA_USERNAME = "vpn_user"
        private const val EXTRA_PASSWORD = "vpn_pass"
        private const val PERMISSION_TIMEOUT_MS = 60_000L

        @Volatile private var pendingLatch: CountDownLatch? = null
        @Volatile private var pendingResult: AtomicReference<Boolean>? = null

        fun requestPermissionAndStart(
            context: Context,
            config: ClawVpnService.VpnConfig,
        ): Boolean {
            val latch = CountDownLatch(1)
            val result = AtomicReference(false)
            synchronized(VpnPermissionActivity::class.java) {
                pendingLatch = latch
                pendingResult = result
            }
            val intent = Intent(context, VpnPermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_HOST, config.host)
                putExtra(EXTRA_PORT, config.port)
                putExtra(EXTRA_USERNAME, config.username)
                putExtra(EXTRA_PASSWORD, config.password)
            }
            context.startActivity(intent)
            return try {
                val completed = latch.await(PERMISSION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (!completed) {
                    XLog.w(TAG, "VPN授权超时")
                    false
                } else {
                    result.get()
                }
            } catch (e: InterruptedException) {
                XLog.e(TAG, "VPN授权等待被中断", e)
                false
            } finally {
                synchronized(VpnPermissionActivity::class.java) {
                    pendingLatch = null
                    pendingResult = null
                }
            }
        }
    }
}
