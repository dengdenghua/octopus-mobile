package com.apk.claw.android.octopus_mobile.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
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
            // 安全:禁止 file:// 页面跨源读取本地文件 / 其它 file:// 源。
            // 开着这两项时,恶意本地页面可越权读取 app 私有文件,浏览器场景必须关。
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false

            mediaPlaybackRequiresUserGesture = false

            userAgentString = DESKTOP_CHROME_UA

            // 安全:HTTPS 页面只放行被动混合内容(图片等),拦截 HTTP 脚本/iframe,
            // 防中间人注入。ALWAYS_ALLOW 会让"安全"连接被降级,改用 COMPATIBILITY。
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        // 仅 DEBUG 构建开启 WebView 远程调试，避免 release 版被 adb chrome://inspect 注入已登录会话。
        WebView.setWebContentsDebuggingEnabled(com.apk.claw.android.BuildConfig.DEBUG)

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
        val width = wv.width
        val height = wv.height
        if (width <= 0 || height <= 0) return null
        return fallbackCanvasScreenshot(wv)
    }

    private fun fallbackCanvasScreenshot(wv: WebView): String? {
        val width = wv.width
        val height = wv.height
        if (width <= 0 || height <= 0) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        wv.draw(canvas)
        return bitmapToBase64(bitmap).also { bitmap.recycle() }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String? {
        return try {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    override fun destroy() {
        val wv = activeWebView
        activeWebView = null
        if (wv != null) {
            val mainHandler = Handler(Looper.getMainLooper())
            mainHandler.post {
                try {
                    wv.stopLoading()
                    wv.webChromeClient = null
                    wv.webViewClient = WebViewClient()
                    wv.loadUrl("about:blank")
                    wv.clearHistory()
                    wv.removeAllViews()
                    wv.destroy()
                } catch (e: Exception) {
                    Log.w("SystemWebViewEngine", "Error destroying WebView: ${e.message}")
                }
            }
        }
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
