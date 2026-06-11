package com.apk.claw.android.octopus_mobile.browser

import android.content.Context
import android.view.View
import kotlinx.coroutines.flow.Flow

/**
 * 浏览器引擎抽象 —— 方案 F "反爬免疫" 核心.
 *
 * 三种实现：
 *
 * 1. **GeckoViewEngine**（主力，Maven 一行依赖）
 *    - Mozilla GeckoView（Firefox 内核）
 *    - 反爬免疫度：✅ 高（Firefox 指纹，非 bot）
 *    - 装扩展：✅ WebExtension API（CRX 自动转 XPI）
 *    - 自包含：✅
 *
 * 2. **SystemWebViewEngine**（兜底，0 包大）
 *    - 用 Android System WebView（基于 Chromium）
 *    - 反爬免疫度：⚠️ 中（指纹是 WebView 不是 Chrome）
 *    - 装扩展：❌
 *
 * 3. **ChromiumWebViewEngine**（后备，需编译 AAR）
 *    - 自编译 Chromium WebView AAR
 *    - 反爬免疫度：✅ 高（完整 Chromium）
 *    - 装扩展：✅ CRX 直接 load
 *    - 编译见 runtime/chromium/AGENT_BUILD_BRIEF.md
 *
 * 引擎选择策略：ClawApplication 启动时按优先级探测：
 * 引擎实现可以位于任何模块 —— 不使用 `sealed` 是为了允许测试 / 第三方实现.
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
     * 按优先级探测可用引擎，返回第一个.
     *
     * 探测顺序：
     *  1. GeckoViewEngine（Maven 依赖可用）
     *  2. SystemWebViewEngine（保底）
     */
    fun selectBest(context: Context): BrowserEngine {
        // 1. GeckoView（主力）
        val gecko = GeckoViewEngine()
        if (gecko.isAvailable()) {
            return gecko
        }

        // 2. 保底
        return SystemWebViewEngine()
    }

    /** 列出所有可用的引擎（用于设置页让用户切换） */
    fun listAvailable(context: Context): List<BrowserEngine> {
        val list = mutableListOf<BrowserEngine>()
        val gecko = GeckoViewEngine()
        if (gecko.isAvailable()) {
            list.add(gecko)
        }
        list.add(SystemWebViewEngine())  // 永远保底
        return list
    }
}
