package com.apk.claw.android.octopus_mobile.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.apk.claw.android.BuildConfig
import android.util.Base64
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import org.mozilla.geckoview.WebRequestError
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GeckoViewEngine : BrowserEngine {

    override val name = "GeckoView"

    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    override fun events(): Flow<EngineEvent> = _events.asSharedFlow()

    private var runtime: GeckoRuntime? = null
    private var activeSession: GeckoSession? = null
    private var activeView: GeckoView? = null
    private var currentUrlValue: String = ""

    @Volatile private var destroyed = false

    /** 每次 evaluateJs 一个独立结果占位,按随机 nonce 索引:并发调用互不串线,页面无法伪造结果。 */
    private class EvalHolder {
        @Volatile var result: String? = null
        @Volatile var done = false
    }
    private val pendingEvals = java.util.concurrent.ConcurrentHashMap<String, EvalHolder>()

    override fun isAvailable(): Boolean {
        return try {
            Class.forName("org.mozilla.geckoview.GeckoRuntime")
            true
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "GeckoView not on classpath.")
            false
        }
    }

    override fun createView(context: Context): View {
        val rt = runtime ?: GeckoRuntime.getDefault(context).also {
            runtime = it
            configureRuntime(it)
        }

        val session = GeckoSession()
        configureSession(session)
        session.open(rt)

        val view = GeckoView(context).apply {
            setSession(session)
        }

        activeSession = session
        activeView = view

        return view
    }

    override fun navigate(url: String) {
        currentUrlValue = url
        val session = activeSession ?: return
        session.load(GeckoSession.Loader().uri(Uri.parse(url)))
        _events.tryEmit(EngineEvent.PageStarted)
    }

    override fun currentUrl(): String = currentUrlValue

    override fun evaluateJs(script: String, callback: ((String?) -> Unit)?) {
        val session = activeSession ?: run {
            callback?.invoke(null)
            return
        }

        // GeckoView 151 removed evaluateJavascript()。改用 javascript: URI 让脚本 alert() 一个带
        // 唯一前缀的结果,经 PromptDelegate 拦截;void() 包裹避免页面被导航替换。
        // 前缀含每次调用的随机 nonce:① 页面无法伪造结果(不知道 nonce);② 并发调用互不串线。
        val nonce = java.util.UUID.randomUUID().toString().replace("-", "")
        val holder = EvalHolder()
        pendingEvals[nonce] = holder
        // 用 JSONObject.quote 生成合法的 JS 字符串字面量(带引号+转义)——
        // 旧实现直接拼裸前缀,产出的是非法 JS(alert(__OCTOPUS...: + ...)),会语法错误致 eval 恒超时。
        val markerJs = org.json.JSONObject.quote(JS_RESULT_PREFIX + nonce + ":")
        val wrappedScript = """
            (function(){
                try {
                    var r = ($script);
                    void(alert($markerJs + JSON.stringify({"v": r === undefined ? null : r})));
                } catch(e) {
                    void(alert($markerJs + JSON.stringify({"e": String(e && e.message || e)})));
                }
            })();
        """.trimIndent()

        try {
            val jsUri = "javascript:" + Uri.encode(wrappedScript)
            session.load(GeckoSession.Loader().uri(jsUri))
        } catch (e: Exception) {
            Log.e(TAG, "evaluateJs failed to load script", e)
            pendingEvals.remove(nonce)
            callback?.invoke(null)
            return
        }

        val startMs = SystemClock.elapsedRealtime()
        val timeoutMs = 5000L
        val checkTask = object : Runnable {
            override fun run() {
                if (destroyed) {
                    pendingEvals.remove(nonce)
                    callback?.invoke(null)
                    return
                }
                val h = pendingEvals[nonce]
                if (h == null || h.done) {
                    pendingEvals.remove(nonce)
                    callback?.invoke(h?.result)
                    return
                }
                if (SystemClock.elapsedRealtime() - startMs > timeoutMs) {
                    pendingEvals.remove(nonce)
                    Log.w(TAG, "evaluateJs timed out for: ${script.take(80)}")
                    callback?.invoke(null)
                    return
                }
                mainHandler.postDelayed(this, 50)
            }
        }
        mainHandler.postDelayed(checkTask, 50)
    }

    private fun handleJsAlert(message: String): Boolean {
        if (!message.startsWith(JS_RESULT_PREFIX)) return false
        // 解析 nonce:PREFIX + nonce + ":" + json(nonce 为十六进制,不含冒号,故第一个冒号即分隔符)
        val afterPrefix = message.substring(JS_RESULT_PREFIX.length)
        val sep = afterPrefix.indexOf(':')
        if (sep <= 0) return false
        val nonce = afterPrefix.substring(0, sep)
        // 防伪造:nonce 必须是本次发起的;页面无法猜中随机 nonce → 命中不到 holder,按普通 alert 处理(不注入结果)。
        val holder = pendingEvals[nonce] ?: return false
        val json = afterPrefix.substring(sep + 1)
        holder.result = try {
            val obj = org.json.JSONObject(json)
            when {
                obj.has("e") -> { Log.w(TAG, "JS eval error: ${obj.getString("e")}"); null }
                obj.isNull("v") -> null
                else -> obj.getString("v")
            }
        } catch (e: Exception) {
            json
        }
        holder.done = true
        return true
    }

    override fun screenshot(): String? {
        val view = activeView ?: return null
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return null

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        var copySuccess = false

        val runnable = Runnable {
            try {
                val surfaceView = findSurfaceView(view)
                if (surfaceView != null && surfaceView.holder?.surface?.isValid == true) {
                    val loc = IntArray(2)
                    surfaceView.getLocationInWindow(loc)
                    val svWidth = surfaceView.width
                    val svHeight = surfaceView.height
                    if (svWidth > 0 && svHeight > 0) {
                        val cropBitmap = Bitmap.createBitmap(svWidth, svHeight, Bitmap.Config.ARGB_8888)
                        PixelCopy.request(
                            surfaceView.holder.surface,
                            cropBitmap,
                            { result ->
                                if (result == PixelCopy.SUCCESS) {
                                    val canvas = Canvas(bitmap)
                                    val vLoc = IntArray(2)
                                    view.getLocationInWindow(vLoc)
                                    canvas.drawBitmap(cropBitmap, (loc[0] - vLoc[0]).toFloat(), (loc[1] - vLoc[1]).toFloat(), null)
                                    copySuccess = true
                                } else {
                                    Log.w(TAG, "PixelCopy failed: $result, falling back to draw")
                                    val canvas = Canvas(bitmap)
                                    view.draw(canvas)
                                    copySuccess = true
                                }
                                cropBitmap.recycle()
                                latch.countDown()
                            },
                            mainHandler
                        )
                        return@Runnable
                    }
                }
                val canvas = Canvas(bitmap)
                view.draw(canvas)
                copySuccess = true
                latch.countDown()
            } catch (e: Exception) {
                Log.e(TAG, "screenshot failed", e)
                latch.countDown()
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run()
        } else {
            mainHandler.post(runnable)
        }

        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                Log.w(TAG, "screenshot timed out")
                bitmap.recycle()
                return null
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            bitmap.recycle()
            return null
        }

        if (!copySuccess) {
            bitmap.recycle()
            return null
        }

        return try {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, baos)
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } finally {
            bitmap.recycle()
        }
    }

    private fun findSurfaceView(view: View): SurfaceView? {
        if (view is SurfaceView) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findSurfaceView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    fun installXpi(xpiBytes: ByteArray, extensionId: String): GeckoResult<WebExtension> {
        val rt = runtime ?: throw IllegalStateException("GeckoRuntime not initialized")
        val tmpFile = java.io.File.createTempFile("ext_$extensionId", ".xpi")
        tmpFile.writeBytes(xpiBytes)
        markAppInitiatedInstall()
        return rt.webExtensionController.install("file://${tmpFile.absolutePath}")
    }

    fun installExtensionFromUrl(url: String): GeckoResult<WebExtension> {
        val rt = runtime ?: throw IllegalStateException("GeckoRuntime not initialized")
        markAppInitiatedInstall()
        return rt.webExtensionController.install(url)
    }

    fun listExtensions(): GeckoResult<List<WebExtension>> {
        val rt = runtime ?: return GeckoResult.fromValue(emptyList())
        return rt.webExtensionController.list()
    }

    fun uninstallExtension(extension: WebExtension): GeckoResult<Void> {
        val rt = runtime ?: throw IllegalStateException("GeckoRuntime not initialized")
        return rt.webExtensionController.uninstall(extension)
    }

    override val antiBotScore: Int = 90
    override val supportsExtensions: Boolean = true

    override fun describe(): EngineInfo {
        return EngineInfo(
            name = name,
            version = "GeckoView 151.0.20260513195118",
            userAgent = "Mozilla/5.0 (Android ${Build.VERSION.RELEASE}; Mobile; rv:151.0) Gecko/151.0 Firefox/151.0",
            supportsExtensions = true,
            antiBotScore = antiBotScore,
            notes = "Firefox kernel, WebExtension API, PixelCopy screenshot, alert-bridge JS eval"
        )
    }

    private fun configureRuntime(runtime: GeckoRuntime) {
        val settings = runtime.settings
        settings.remoteDebuggingEnabled = BuildConfig.DEBUG
        // 关闭网页可触发的扩展安装 Web API:防止任意访问的页面静默触发扩展安装(drive-by)。
        // App 自己的安装走 webExtensionController.install() 直接调用,不依赖此开关,
        // 且受 browser_install_extension 高危工具闸门约束。
        runCatching { settings.extensionsWebAPIEnabled = false }

        runCatching {
            runtime.webExtensionController.promptDelegate = object : WebExtensionController.PromptDelegate {
                override fun onInstallPromptRequest(
                    extension: WebExtension,
                    permissions: Array<out String>,
                    origins: Array<out String>,
                    dataCollectionPermissions: Array<out String>,
                ): GeckoResult<WebExtension.PermissionPromptResponse> {
                    val name = extension.metaData?.name ?: "unknown"
                    // 纵深防御:仅放行 App 主动发起的安装。即便将来有人重新打开 Web API,
                    // 非 App 发起的安装提示(drive-by)也一律拒绝。
                    if (!isAppInitiatedInstall()) {
                        Log.w(TAG, "Rejected unsolicited extension install prompt: $name")
                        _events.tryEmit(EngineEvent.ConsoleMessage("warn", "Blocked unsolicited extension install: $name"))
                        return GeckoResult.fromValue(WebExtension.PermissionPromptResponse(false, false, false))
                    }
                    Log.i(TAG, "Install prompt (app-initiated): $name -> ALLOW")
                    _events.tryEmit(EngineEvent.ConsoleMessage("info", "Installing extension: $name"))
                    return GeckoResult.fromValue(WebExtension.PermissionPromptResponse(true, false, false))
                }
            }
        }
    }

    private fun configureSession(session: GeckoSession) {
        val settings = session.settings
        settings.userAgentOverride = null
        settings.useTrackingProtection = false

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
            override fun onTitleChange(session: GeckoSession, title: String?) {}
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

        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onAlertPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AlertPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val msg = prompt.message ?: ""
                if (handleJsAlert(msg)) {
                    return GeckoResult.fromValue(prompt.dismiss())
                }
                return GeckoResult.fromValue(prompt.dismiss())
            }

            override fun onButtonPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ButtonPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                return GeckoResult.fromValue(prompt.dismiss())
            }

            override fun onTextPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.TextPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                return GeckoResult.fromValue(prompt.dismiss())
            }

            override fun onAuthPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AuthPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                return GeckoResult.fromValue(prompt.dismiss())
            }

            override fun onChoicePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ChoicePrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                return GeckoResult.fromValue(prompt.dismiss())
            }

            override fun onColorPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ColorPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                return GeckoResult.fromValue(prompt.dismiss())
            }

            override fun onDateTimePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.DateTimePrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                return GeckoResult.fromValue(prompt.dismiss())
            }

            override fun onFilePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.FilePrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                return GeckoResult.fromValue(prompt.dismiss())
            }

            override fun onPopupPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.PopupPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY))
            }

            override fun onBeforeUnloadPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))
            }
        }
    }

    override fun destroy() {
        destroyed = true
        // 各 per-call checkTask 会在下次 run 检测到 destroyed 后自行结束并清理。
        pendingEvals.clear()
        try {
            activeSession?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing GeckoSession: ${e.message}")
        }
        activeSession = null
        val view = activeView
        if (view != null) {
            mainHandler.post {
                view.releaseSession()
                view.removeAllViews()
            }
        }
        activeView = null
    }

    companion object {
        private const val TAG = "GeckoViewEngine"
        private val mainHandler = Handler(Looper.getMainLooper())
        private const val JS_RESULT_PREFIX = "__OCTOPUS_JS_RESULT__:"

        // 扩展安装防 drive-by:仅在 App 主动发起安装后的短窗口内,才放行 onInstallPromptRequest。
        // GeckoRuntime 是进程单例,故用静态窗口(任一引擎实例的安装与 delegate 共享一份)。
        private const val APP_INSTALL_WINDOW_MS = 120_000L
        @Volatile private var appInstallWindowUntil = 0L
        private fun markAppInitiatedInstall() {
            appInstallWindowUntil = SystemClock.elapsedRealtime() + APP_INSTALL_WINDOW_MS
        }
        private fun isAppInitiatedInstall(): Boolean =
            SystemClock.elapsedRealtime() <= appInstallWindowUntil
    }
}
