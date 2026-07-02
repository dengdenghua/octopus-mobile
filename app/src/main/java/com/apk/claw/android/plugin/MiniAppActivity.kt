package com.apk.claw.android.plugin

import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity

/**
 * 小程序宿主 Activity(全屏)—— WebView 沙箱由 [MiniAppHost] 统一提供(与桌面浮动窗口共用)。
 * 挂 [OctopusBridge](`octopusNative` + `window.octopus` shim),锁本地 file:// 源、拦截非 file 子资源。
 */
class MiniAppActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MiniAppActivity"
        const val EXTRA_PLUGIN_ID = "plugin_id"
    }

    private var webView: WebView? = null
    private var appId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val id = intent.getStringExtra(EXTRA_PLUGIN_ID)
            val manifest = id?.let { MiniAppRegistry.get(it) }
            if (manifest == null || manifest.page.isBlank()) { finish(); return }
            appId = manifest.id

            val wv = MiniAppHost.createWebView(this, manifest) ?: run { finish(); return }
            setContentView(wv)
            webView = wv
        } catch (e: Exception) {
            Log.e(TAG, "MiniApp launch failed: ${e.message}", e)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        // 前台运行 → 注册到动作总线,Agent 的 app_action 可派发到本 mini-app
        val id = appId; val wv = webView
        if (id != null && wv != null) MiniAppActionBus.registerLive(id, this, wv)
    }

    override fun onPause() {
        super.onPause()
        appId?.let { MiniAppActionBus.unregister(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        appId?.let { MiniAppActionBus.unregister(it) }
        MiniAppHost.destroyWebView(webView)
        webView = null
    }
}
