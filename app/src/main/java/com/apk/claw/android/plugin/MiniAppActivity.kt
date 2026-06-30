package com.apk.claw.android.plugin

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import java.io.ByteArrayInputStream
import java.lang.ref.WeakReference

/**
 * 小程序宿主 —— 跑你自己的 mini-app(H5 + `octopus.*` 桥).
 *
 * 这是"你的小程序"的运行时:WebView 加载插件本地页面([PluginManifest.page]),挂上
 * [OctopusBridge](`octopusNative` + 注入 `window.octopus` shim)。**安全收口:**
 *  - 锁定本地 file:// 源,禁止导航去远端(`shouldOverrideUrlLoading`);
 *  - **拦截所有非 file:// 子资源**(`shouldInterceptRequest`)——小程序的一切对外 I/O
 *    只能走受网关的 `octopus.*` 桥,而不是 WebView 自行联网,避免桥暴露给远端内容。
 *  - 禁 file 跨源越权读。
 */
class MiniAppActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PLUGIN_ID = "plugin_id"
    }

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val id = intent.getStringExtra(EXTRA_PLUGIN_ID)
        val manifest = id?.let { MiniAppRegistry.get(it) }
        if (manifest == null || manifest.page.isBlank()) { finish(); return }

        // 当前仅信任 assets 源插件(fail-closed),小程序页面从 assets 加载,
        // 用 WebView 的 file:///android_asset/ 方案,无需复制到 filesDir。
        // (未来 registry 下载、经校验的小程序改为从 filesDir 读 + 标记 source。)
        val assetRel = "plugins/${manifest.id}/${manifest.page}"
        val pageExists = runCatching { assets.open(assetRel).use { true } }.getOrDefault(false)
        if (!pageExists) { finish(); return }
        val pageUrl = "file:///android_asset/$assetRel"

        val wv = WebView(this)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            // 禁 file:// 页面跨源读其它本地文件(只允许读自身目录由 baseUrl 决定)
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
        }
        wv.addJavascriptInterface(OctopusBridge(manifest, WeakReference(this)), "octopusNative")
        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                view.evaluateJavascript(OctopusBridge.SHIM_JS, null)
            }
            // 锁定在本地源:禁止小程序导航去远端
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url?.toString() ?: return true
                return !u.startsWith("file://")
            }
            // 拦截一切非 file:// 子资源:对外 I/O 只能走 octopus.* 桥
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url?.toString().orEmpty()
                return if (u.startsWith("file://")) null
                else WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
        }
        setContentView(wv)
        webView = wv
        wv.loadUrl(pageUrl)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching {
            webView?.removeJavascriptInterface("octopusNative")
            webView?.destroy()
        }
        webView = null
    }
}
