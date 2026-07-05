package com.apk.claw.android.plugin

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.ref.WeakReference

/**
 * mini-app WebView 沙箱的**单一实现** —— [MiniAppActivity](全屏)与桌面模式的浮动窗口共用,
 * 避免各写一套导致安全设置发散。安全收口(与原 MiniAppActivity 一致):
 *  - 锁定本地 file:// 源,禁止导航去远端(`shouldOverrideUrlLoading`);
 *  - 拦截非 file:// 且非图片的子资源(`shouldInterceptRequest`)—— 远程图片素材(<img>/CSS 背景)放行
 *    (生成的 mini-app 用真实图片 URL),其余对外 I/O 只能走受网关的 `octopus.*` 桥;
 *  - 禁 file 跨源越权读;页面 URL 经 canonicalPath 防 path traversal。
 */
object MiniAppHost {

    private const val TAG = "MiniAppHost"
    private val REMOTE_IMAGE_SCHEMES = setOf("http", "https", "data")
    private val IMAGE_EXTS = listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg", ".bmp", ".ico", ".avif")

    /**
     * 为 [manifest] 创建一个装好 [OctopusBridge]、锁定本地源、已开始加载页面的 WebView。
     * @param activity 宿主 Activity(OctopusBridge 的 pay 弹窗需要;桌面窗口传 DesktopActivity)
     * @return 页面无法解析时返回 null
     */
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun createWebView(activity: Activity, manifest: PluginManifest): WebView? {
        val pageUrl = resolvePageUrl(activity, manifest) ?: return null
        val wv = WebView(activity)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            // 禁 file:// 页面跨源读其它本地文件
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            // 允许 file:// 页面加载远程图片素材(<img>/CSS 背景);兼容模式兜底 http 图。
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        wv.addJavascriptInterface(OctopusBridge(manifest, WeakReference(activity)), "octopusNative")
        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                view.evaluateJavascript(OctopusBridge.SHIM_JS, null)
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url?.toString() ?: return true
                return !u.startsWith("file://")
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url?.toString().orEmpty()
                return when {
                    u.startsWith("file://") -> null
                    // 远程/内联图片素材放行(生成的 mini-app 用真实图片 URL);其余非本地子资源仍拦死。
                    isRemoteImageRequest(request) -> null
                    else -> WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
            }
        }
        wv.loadUrl(pageUrl)
        return wv
    }

    /** 销毁 WebView 并解绑桥(窗口/Activity 关闭时调用)。 */
    fun destroyWebView(webView: WebView?) {
        runCatching {
            webView?.removeJavascriptInterface("octopusNative")
            webView?.destroy()
        }
    }

    /**
     * 是否为远程/内联「图片」子请求 —— 生成的 mini-app 用真实素材 URL(<img src>/CSS 背景)时放行。
     * 只放行图片:图片子请求只泄露请求本身(URL/时序),读不到响应体;脚本/XHR/fetch 等能读回数据的
     * 强外泄通道仍走默认拦截。主框架导航不算图片(仍锁本地,由 shouldOverrideUrlLoading 兜)。
     */
    private fun isRemoteImageRequest(request: WebResourceRequest): Boolean {
        val scheme = request.url?.scheme?.lowercase()
        if (request.isForMainFrame || scheme !in REMOTE_IMAGE_SCHEMES) return false
        val url = request.url?.toString()?.lowercase().orEmpty()
        val accept = request.requestHeaders?.entries
            ?.firstOrNull { it.key.equals("Accept", ignoreCase = true) }?.value?.lowercase().orEmpty()
        val path = request.url?.path?.lowercase().orEmpty()
        return url.startsWith("data:image/") || accept.contains("image/") || IMAGE_EXTS.any { path.endsWith(it) }
    }

    /**
     * 解析页面 URL:filesDir/plugins → filesDir/generated_apps → assets/plugins,都找不到返回 null。
     * filesDir 查找做 canonicalPath 防 path traversal;assets 由 APK 签名保护。
     */
    fun resolvePageUrl(context: Context, manifest: PluginManifest): String? {
        val candidates = linkedSetOf(manifest.id, manifest.id.substringAfterLast('/'))
        for (baseDir in listOf("plugins", "generated_apps")) {
            for (slug in candidates) {
                try {
                    val pluginDir = File(context.filesDir, "$baseDir/$slug")
                    if (!pluginDir.isDirectory) continue
                    val page = File(pluginDir, manifest.page)
                    if (!page.isFile) continue
                    val pageCanon = page.canonicalPath
                    val dirCanon = pluginDir.canonicalPath
                    if (!pageCanon.startsWith(dirCanon + File.separator) && pageCanon != dirCanon) continue
                    return page.toURI().toString()
                } catch (e: Exception) {
                    Log.w(TAG, "resolvePageUrl $baseDir error for $slug: ${e.message}")
                }
            }
        }
        val assetRel = "plugins/${manifest.id}/${manifest.page}"
        val existsInAssets = runCatching { context.assets.open(assetRel).use { true } }.getOrDefault(false)
        if (existsInAssets) return "file:///android_asset/$assetRel"
        return null
    }
}
