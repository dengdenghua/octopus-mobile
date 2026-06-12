package com.apk.claw.android.octopus_mobile.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.ByteArrayOutputStream

/**
 * 系统 WebView 引擎 —— 兜底实现.
 *
 * 优点：
 *  - 0 包大
 *  - 永远可用
 *  - 由 Google Play 自动更新
 *
 * 缺点：
 *  - 反爬免疫度：⚠️ 中（指纹是 WebView 不是 Chrome）
 *  - 不支持 CRX 扩展
 *
 * 优化措施：
 *  - UA 改为桌面 Chrome UA
 *  - 启用 Cookie / DOM Storage / IndexedDB
 *  - 启用 JS / 不阻止弹窗
 *  - 开启 WebView 调试（dev tools）
 */
class SystemWebViewEngine : BrowserEngine {

    override val name = "System WebView"

    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    override fun events(): Flow<EngineEvent> = _events.asSharedFlow()

    private var activeWebView: WebView? = null
    private var currentUrlValue: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun createView(context: Context): View {
        val webView = WebView(context)

        val settings: WebSettings = webView.settings.apply {
            javaScriptEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            domStorageEnabled = true
            databaseEnabled = true

            // setAcceptCookie 已迁移到 CookieManager（WebSettings.setAcceptCookie 在 API 36 弃用）
            android.webkit.CookieManager.getInstance().setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)

            cacheMode = WebSettings.LOAD_DEFAULT

            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            textZoom = 100

            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true

            mediaPlaybackRequiresUserGesture = false

            userAgentString = DESKTOP_CHROME_UA

            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        WebView.setWebContentsDebuggingEnabled(true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                currentUrlValue = url
                _events.tryEmit(EngineEvent.PageStarted)
            }
            override fun onPageFinished(view: WebView, url: String) {
                _events.tryEmit(EngineEvent.PageFinished(url, view.title ?: ""))
            }
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: android.webkit.WebResourceRequest
            ): Boolean = false
            override fun onReceivedError(
                view: WebView,
                request: android.webkit.WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                _events.tryEmit(EngineEvent.Error(
                    errorCode = error.errorCode,
                    description = error.description.toString()
                ))
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                _events.tryEmit(EngineEvent.ProgressChanged(newProgress))
            }
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                if (msg != null) {
                    _events.tryEmit(EngineEvent.ConsoleMessage(
                        level = msg.messageLevel().toString(),
                        message = msg.message()
                    ))
                }
                return true
            }
        }

        activeWebView = webView
        return webView
    }

    override fun navigate(url: String) {
        // 先记录导航意图（即使视图未就绪，currentUrl() 也反映最近一次 navigate）
        currentUrlValue = url
        val wv = activeWebView ?: return
        wv.loadUrl(url)
    }

    override fun currentUrl(): String = currentUrlValue

    override fun evaluateJs(script: String, callback: ((String?) -> Unit)?) {
        val wv = activeWebView ?: run {
            callback?.invoke(null)
            return
        }
        wv.evaluateJavascript(script) { result ->
            callback?.invoke(result)
        }
    }

    override fun screenshot(): String? {
        val wv = activeWebView ?: return null
        wv.isDrawingCacheEnabled = true
        val bitmap = Bitmap.createBitmap(wv.drawingCache)
        wv.isDrawingCacheEnabled = false

        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
        bitmap.recycle()
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    override fun isAvailable(): Boolean = true

    override val antiBotScore: Int = 50

    override val supportsExtensions: Boolean = false

    override fun describe(): EngineInfo {
        val chromiumVersion = try {
            android.webkit.WebView.getCurrentWebViewPackage()?.versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
        return EngineInfo(
            name = name,
            version = "WebView $chromiumVersion (Android ${Build.VERSION.RELEASE})",
            userAgent = DESKTOP_CHROME_UA,
            supportsExtensions = false,
            antiBotScore = antiBotScore,
            notes = "兜底引擎，0 包大。反爬免疫中（UA 已伪装为桌面 Chrome）"
        )
    }

    @SuppressLint("NewApi")
    private fun setAcceptThirdPartyCookies(webView: WebView, accept: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, accept)
        }
    }

    companion object {
        private const val DESKTOP_CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
