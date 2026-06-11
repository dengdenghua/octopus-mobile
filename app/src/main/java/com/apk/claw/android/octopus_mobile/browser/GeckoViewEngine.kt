package com.apk.claw.android.octopus_mobile.browser

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.util.Log
import android.view.View
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * GeckoView 引擎 —— 主力实现.
 *
 * 优势：
 *  - Mozilla Firefox 内核（非 Chromium WebView 阉割版）
 *  - 反爬免疫度：✅ 高（Firefox 指纹，反爬系统不标记为 bot）
 *  - 装扩展：✅ WebExtension API（CRX 自动转 XPI 后安装）
 *  - 自包含：✅ Maven 一行依赖
 *  - 包大：+15-20 MB（arm64-v8a）
 *
 * 使用：
 *  - build.gradle.kts 加 implementation("org.mozilla.geckoview:geckoview:125.0.20240412")
 *  - BrowserEngineFactory.selectBest() 自动选择
 */
class GeckoViewEngine : BrowserEngine {

    override val name = "GeckoView"

    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    override fun events(): Flow<EngineEvent> = _events.asSharedFlow()

    private var runtime: GeckoRuntime? = null
    private var activeSession: GeckoSession? = null
    private var activeView: GeckoView? = null
    private var currentUrlValue: String = ""

    // ── 可用性检测 ────────────────────────────────────

    override fun isAvailable(): Boolean {
        return try {
            Class.forName("org.mozilla.geckoview.GeckoRuntime")
            true
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "GeckoView not on classpath. Add dependency to build.gradle.kts.")
            false
        }
    }

    // ── 创建视图 ──────────────────────────────────────

    override fun createView(context: Context): View {
        // 初始化 Runtime（全局单例）
        val rt = runtime ?: GeckoRuntime.getDefault(context).also {
            runtime = it
            configureRuntime(it)
        }

        // 创建 Session
        val session = GeckoSession()
        configureSession(session)
        session.open(rt)

        // 创建 View 并绑定 Session
        val view = GeckoView(context).apply {
            setSession(session)
        }

        activeSession = session
        activeView = view

        return view
    }

    // ── 导航 ──────────────────────────────────────────

    override fun navigate(url: String) {
        val session = activeSession ?: return
        currentUrlValue = url
        session.load(GeckoSession.Loader().uri(Uri.parse(url)))
        _events.tryEmit(EngineEvent.PageStarted)
    }

    override fun currentUrl(): String = currentUrlValue

    // ── JS 执行 ───────────────────────────────────────

    override fun evaluateJs(script: String, callback: ((String?) -> Unit)?) {
        val session = activeSession ?: run {
            callback?.invoke(null)
            return
        }
        // GeckoView 151+ removed GeckoSession.evaluateJavascript. The
        // replacement is to install a privileged WebExtension that uses
        // browser.tabs.executeScript / scripting.executeScript and
        // forwards results via runtime.sendMessage. That requires a small
        // companion extension shipped under app/src/main/assets and a
        // WebExtensionController setup. Out of scope for this commit —
        // stub to fail gracefully so callers don't crash.
        // TODO: port to WebExtension-based JS evaluation.
        Log.w("GeckoViewEngine", "evaluateJs() not yet ported to WebExtension on GeckoView 151+")
        callback?.invoke(null)
    }

    // ── 截图 ──────────────────────────────────────────

    override fun screenshot(): String? {
        val view = activeView ?: return null
        view.isDrawingCacheEnabled = true
        val bitmap = Bitmap.createBitmap(view.drawingCache)
        view.isDrawingCacheEnabled = false

        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
        bitmap.recycle()
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    // ── 扩展安装 ──────────────────────────────────────

    /**
     * 安装 XPI 扩展（Firefox 格式）.
     *
     * agent 调用链：ExtensionInstaller.install() → CrxToXpiConverter → 此方法
     */
    fun installXpi(xpiBytes: ByteArray, extensionId: String): GeckoResult<WebExtension> {
        val rt = runtime ?: throw IllegalStateException("GeckoRuntime not initialized")
        val tmpFile = java.io.File.createTempFile("ext_$extensionId", ".xpi")
        tmpFile.writeBytes(xpiBytes)
        return rt.webExtensionController.install("file://${tmpFile.absolutePath}")
    }

    /**
     * 从 URL 安装扩展（AMO / 自托管）.
     */
    fun installExtensionFromUrl(url: String): GeckoResult<WebExtension> {
        val rt = runtime ?: throw IllegalStateException("GeckoRuntime not initialized")
        return rt.webExtensionController.install(url)
    }

    /**
     * 列出已安装扩展.
     */
    fun listExtensions(): GeckoResult<List<WebExtension>> {
        val rt = runtime ?: return GeckoResult.fromValue(emptyList())
        return rt.webExtensionController.list()
    }

    /**
     * 卸载扩展.
     */
    fun uninstallExtension(extension: WebExtension): GeckoResult<Void> {
        val rt = runtime ?: throw IllegalStateException("GeckoRuntime not initialized")
        return rt.webExtensionController.uninstall(extension)
    }

    // ── 引擎信息 ──────────────────────────────────────

    override val antiBotScore: Int = 90  // Firefox 指纹，非 bot

    override val supportsExtensions: Boolean = true

    override fun describe(): EngineInfo {
        val rt = runtime
        return EngineInfo(
            name = name,
            version = "GeckoView 125.0.20240412",
            userAgent = "Mozilla/5.0 (Android ${Build.VERSION.RELEASE}; Mobile; rv:125.0) Gecko/125.0 Firefox/125.0",
            supportsExtensions = true,
            antiBotScore = antiBotScore,
            notes = "Firefox 内核，WebExtension API，CRX 自动转 XPI 安装"
        )
    }

    // ── 内部配置 ──────────────────────────────────────

    private fun configureRuntime(runtime: GeckoRuntime) {
        val settings = runtime.settings
        // GeckoView 125 GeckoRuntimeSettings 确认存在的属性
        settings.remoteDebuggingEnabled = true
        // settings.webNotificationsEnabled was removed in GeckoView 151.
        // Web notifications are now controlled via GeckoSession.PermissionDelegate.
        // settings.webNotificationsEnabled = true
        // javaScriptEnabled / trackingProtection / autoplay 已移至 GeckoSession.Settings
        // 或由 GeckoView 默认行为处理（125+ 默认启用 JS）
    }

    private fun configureSession(session: GeckoSession) {
        val settings = session.settings
        settings.userAgentOverride = null  // 用默认 Firefox UA
        settings.useTrackingProtection = false
        // JS 在 GeckoView 125 默认启用，无需显式设置

        // 事件监听
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                currentUrlValue = url
                _events.tryEmit(EngineEvent.PageStarted)
            }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                _events.tryEmit(EngineEvent.PageFinished(currentUrlValue, ""))
            }
            override fun onProgressChange(session: GeckoSession, progress: Int) {
                _events.tryEmit(EngineEvent.ProgressChanged(progress))
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                // title 变化
            }
            override fun onContextMenu(
                session: GeckoSession,
                screenX: Int, screenY: Int,
                contextElement: GeckoSession.ContentDelegate.ContextElement
            ) {}
        }

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError
            ): GeckoResult<String>? {
                _events.tryEmit(EngineEvent.Error(errorCode = error.code, description = uri ?: "unknown"))
                return null
            }
        }
    }

    companion object {
        private const val TAG = "GeckoViewEngine"
    }
}
