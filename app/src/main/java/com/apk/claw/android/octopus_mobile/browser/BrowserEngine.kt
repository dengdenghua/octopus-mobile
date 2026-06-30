package com.apk.claw.android.octopus_mobile.browser

import android.content.Context
import android.view.View
import kotlinx.coroutines.flow.Flow

/**
 * 浏览器引擎抽象.
 *
 * 当前唯一实现：**SystemWebViewEngine**（Android 系统 WebView，基于 Chromium，0 包体）。
 *   - 反爬免疫度：⚠️ 中（WebView 指纹）。提升路径:WebViewCompat.addDocumentStartJavaScript
 *     在页面脚本执行前注入反检测脚本(navigator.webdriver / UA / WebGL / plugins …),
 *     可把分数从 ~50 拉到 ~70;TLS/JA3 这层 JS 够不着,严防站走服务端匿名抓取兜底。
 *   - 装扩展：❌ WebView 无 WebExtension。扩展能力改由**自建注入式插件生态**承载
 *     (registry 的 mode=inject/kind=code 资产 = 内容脚本 JS + 拦截规则),宿主用
 *     evaluateJavascript + shouldInterceptRequest 运行。
 *
 * 历史:曾有 GeckoViewEngine(Firefox 内核,反爬 90 + 真 WebExtension),因 ~180MB 体积
 * (libxul.so 144MB)被移除。抽象层保留,便于未来按需下载引擎 / 第三方实现;不用 `sealed`。
 */
interface BrowserEngine {

    /** 引擎名（用于日志 / 状态展示） */
    val name: String

    /**
     * 创建一个新浏览器视图实例.
     *
     * GeckoView 返回 org.mozilla.geckoview.GeckoView
     * SystemWebView 返回 android.webkit.WebView
     * 两者都是 android.view.View 子类
     */
    fun createView(context: Context): View

    /**
     * 当前引擎是否可用.
     * - GeckoViewEngine：GeckoRuntime 可初始化
     * - SystemWebViewEngine：始终 true
     */
    fun isAvailable(): Boolean

    /**
     * 引擎反爬免疫度 0-100.
     * 0 = 100% 被检测
     * 100 = 完美伪装真实浏览器
     */
    val antiBotScore: Int

    /**
     * 引擎支持扩展.
     */
    val supportsExtensions: Boolean

    /**
     * 引擎支持 evaluateJavascript.
     */
    val supportsEval: Boolean get() = true

    /**
     * 引擎启动信息 —— 用于设置页 / 状态栏展示.
     */
    fun describe(): EngineInfo

    /**
     * 引擎全局事件流 —— UI 监听（下载进度 / 导航完成 / 错误）.
     */
    fun events(): Flow<EngineEvent>

    /**
     * 导航到指定 URL.
     */
    fun navigate(url: String)

    /**
     * 获取当前页面 URL.
     */
    fun currentUrl(): String

    /**
     * 执行 JavaScript 并返回结果.
     */
    fun evaluateJs(script: String, callback: ((String?) -> Unit)? = null)

    /**
     * 截图（返回 Base64 PNG）.
     */
    fun screenshot(): String?

    /**
     * 销毁引擎，释放所有资源（Session、WebView、Handler回调等）.
     * Activity/Fragment 销毁时必须调用以避免内存泄漏。
     */
    fun destroy() {}
}

/**
 * 引擎元信息 —— UI 展示用.
 */
data class EngineInfo(
    val name: String,
    val version: String,
    val userAgent: String,
    val supportsExtensions: Boolean,
    val antiBotScore: Int,
    val notes: String = "",
)

/**
 * 引擎事件 —— BrowserTool 观察 + UI 展示.
 */
sealed class EngineEvent {
    object PageStarted : EngineEvent()
    data class PageFinished(val url: String, val title: String) : EngineEvent()
    data class ProgressChanged(val percent: Int) : EngineEvent()
    data class ConsoleMessage(val level: String, val message: String) : EngineEvent()
    data class DownloadStart(val url: String, val suggestedFilename: String) : EngineEvent()
    data class JsAlert(val message: String, val onResult: (Boolean) -> Unit) : EngineEvent()
    data class Error(val errorCode: Int, val description: String) : EngineEvent()
}

/**
 * 引擎选择器 —— 启动时一次性探测.
 */
object BrowserEngineFactory {

    /**
     * 返回浏览器引擎.
     *
     * 已移除 GeckoView(Firefox 内核)以瘦身 APK(约 -180MB:libxul.so 144MB + omni.ja
     * 13MB + 一众 mozilla .so)。统一用系统 WebView(Chromium,0 包体)。扩展能力改由
     * 自建注入式插件生态承载,反爬靠 document-start 注入 + 服务端兜底,见 BrowserEngine 顶注。
     */
    fun selectBest(context: Context): BrowserEngine = SystemWebViewEngine()

    /** 列出所有可用的引擎（用于设置页让用户切换；当前仅系统 WebView 一种） */
    fun listAvailable(context: Context): List<BrowserEngine> = listOf(SystemWebViewEngine())
}
