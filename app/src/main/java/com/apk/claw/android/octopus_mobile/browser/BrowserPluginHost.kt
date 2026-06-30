package com.apk.claw.android.octopus_mobile.browser

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File

/**
 * 浏览器插件宿主 —— 自建注入式插件生态的运行时.
 *
 * 替代 GeckoView 的 WebExtension:一个"插件"= 一段在页面里运行的内容脚本(content script)
 * + 可选的网络拦截规则。宿主把它们跑在系统 WebView 上:
 *   - 内容脚本   → SystemWebViewEngine 在 onPageFinished 用 evaluateJavascript 注入(按域名匹配)
 *   - 反检测脚本 → 在「文档开始前」用 WebViewCompat.addDocumentStartJavaScript 注入([STEALTH_JS])
 *   - 拦截规则   → SystemWebViewEngine.shouldInterceptRequest 调 [shouldBlock] 拦广告/跟踪
 *
 * 插件来源:registry 的 `mode=inject / kind=code` 资产(内容脚本 JS + 域名匹配 + 拦截规则)。
 * registry 消费层下载后调用 [setPlugins] / [setBlockRules] 把规则推进来;本对象只管运行,不管下载。
 *
 * 线程模型:写入(set*)与读取(contentScriptsFor/shouldBlock,在 WebView 线程)用 @Volatile +
 * 不可变列表整体替换,读侧无锁、无 ConcurrentModification。
 */
object BrowserPluginHost {

    private const val TAG = "BrowserPluginHost"

    /** 一个注入式插件。[hostPattern] 为正则字符串(匹配 URL 的 host),"*" 或空 = 所有站点。 */
    data class InjectPlugin(
        val id: String,
        val name: String,
        val hostPattern: String,
        val js: String,
        val enabled: Boolean = true,
    ) {
        // 预编译;非法正则降级为「永不匹配」并记日志,不让一个坏插件拖垮注入。
        internal val matcher: Regex? = when {
            hostPattern.isBlank() || hostPattern == "*" -> null   // null = 匹配所有
            else -> runCatching { Regex(hostPattern, RegexOption.IGNORE_CASE) }
                .onFailure { Log.w(TAG, "bad hostPattern for plugin $id: $hostPattern") }
                .getOrNull() ?: Regex("(?!)")  // 编译失败 → 永不匹配
        }
    }

    @Volatile
    private var plugins: List<InjectPlugin> = emptyList()

    @Volatile
    private var blockRules: List<Regex> = emptyList()

    /** 反检测脚本总开关(默认开)。关掉用于排查"注入导致页面异常"。 */
    @Volatile
    var stealthEnabled: Boolean = true

    // ── registry 消费层调用的写入口 ──────────────────────────────

    /** 设置当前启用的注入插件(整体替换)。 */
    fun setPlugins(list: List<InjectPlugin>) {
        plugins = list.filter { it.enabled && it.js.isNotBlank() }
        Log.i(TAG, "plugins set: ${plugins.size} active")
    }

    /**
     * 设置拦截规则(广告/跟踪)。每条是一个匹配整条 URL 的正则字符串;
     * 非法正则被丢弃。传空清空拦截。
     */
    fun setBlockRules(patterns: List<String>) {
        blockRules = patterns.mapNotNull { p ->
            runCatching { Regex(p, RegexOption.IGNORE_CASE) }
                .onFailure { Log.w(TAG, "bad block rule: $p") }
                .getOrNull()
        }
        Log.i(TAG, "block rules set: ${blockRules.size}")
    }

    fun clear() {
        plugins = emptyList()
        blockRules = emptyList()
    }

    // ── 本地清单加载(端到端可用的投递入口)─────────────────────
    //
    // 在 registry 服务端 inject/code 资产契约定下来之前,先用一个本地 JSON 清单作为投递点:
    // `filesDir/registry/browser_plugins.json`。registry 消费层 / agent(可写文件)/ 手动配置
    // 都能写它;浏览器打开时由 [BrowserActivity] 调 [loadFromFile] 读入并生效。
    // 格式:{ "stealthEnabled": true,
    //        "plugins": [ {"id","name","hostPattern","js","enabled"} ],
    //        "blockRules": ["<匹配整条URL的正则>", ...] }

    private data class PluginFile(
        val stealthEnabled: Boolean = true,
        val plugins: List<PluginDef> = emptyList(),
        val blockRules: List<String> = emptyList(),
    )

    private data class PluginDef(
        val id: String = "",
        val name: String = "",
        val hostPattern: String = "*",
        val js: String = "",
        val enabled: Boolean = true,
    )

    private val gson = Gson()

    fun manifestFile(context: Context): File =
        File(File(context.filesDir, "registry").apply { mkdirs() }, "browser_plugins.json")

    /** 从本地清单加载插件 + 拦截规则 + stealth 开关。文件不存在/解析失败则保持当前状态。 */
    fun loadFromFile(context: Context) {
        val f = manifestFile(context)
        if (!f.isFile) return
        val parsed = runCatching { gson.fromJson(f.readText(), PluginFile::class.java) }
            .onFailure { Log.w(TAG, "browser_plugins.json parse failed: ${it.message}") }
            .getOrNull() ?: return
        stealthEnabled = parsed.stealthEnabled
        setPlugins(parsed.plugins.map {
            InjectPlugin(id = it.id, name = it.name, hostPattern = it.hostPattern, js = it.js, enabled = it.enabled)
        })
        setBlockRules(parsed.blockRules)
    }

    // ── SystemWebViewEngine 调用的读出口 ─────────────────────────

    /** 文档开始前要注入的脚本(目前仅 stealth;返回 null 表示不注入)。 */
    fun documentStartScript(): String? = STEALTH_JS.takeIf { stealthEnabled }

    /** 给定页面 URL,返回应注入的内容脚本(按 host 匹配;在 onPageFinished 调)。 */
    fun contentScriptsFor(url: String?): List<String> {
        if (url.isNullOrBlank()) return emptyList()
        val host = hostOf(url) ?: return emptyList()
        val current = plugins  // 读快照
        if (current.isEmpty()) return emptyList()
        return current.filter { it.matcher == null || it.matcher.containsMatchIn(host) }.map { it.js }
    }

    /** 该请求 URL 是否应被拦截(广告/跟踪)。 */
    fun shouldBlock(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val rules = blockRules
        if (rules.isEmpty()) return false
        return rules.any { it.containsMatchIn(url) }
    }

    private fun hostOf(url: String): String? = runCatching {
        android.net.Uri.parse(url).host
    }.getOrNull()

    /**
     * 反检测(stealth)脚本 —— 在「文档开始前」注入,抹掉系统 WebView 暴露的 bot 指纹.
     *
     * 覆盖:navigator.webdriver / window.chrome / plugins / languages / hardwareConcurrency /
     * deviceMemory / permissions(Notification 不一致)/ WebGL vendor·renderer(SwiftShader 泄漏)。
     * 每块独立 try/catch,且对 patch 过的函数做 toString 伪装([native code]),避免被反向检测。
     * 移植自 puppeteer-extra-plugin-stealth 的核心 evasion;只动指纹属性,不暴露任何 app 内部桥。
     *
     * 天花板:JS 够不到 TLS/JA3 + HTTP2 指纹,顶级 WAF(Cloudflare/Akamai)仍可能识别 —— 那类
     * 走服务端真浏览器匿名抓取兜底。本脚本把"是不是 WebView/headless"这类检测打穿,反爬约 50→70。
     */
    const val STEALTH_JS = """
(function () {
  'use strict';
  var asNative = function (fn, name) {
    try {
      Object.defineProperty(fn, 'toString', {
        value: function () { return 'function ' + (name || fn.name || '') + '() { [native code] }'; },
        configurable: true
      });
    } catch (e) {}
    return fn;
  };
  // 1. navigator.webdriver → false
  try {
    Object.defineProperty(Object.getPrototypeOf(navigator), 'webdriver', { get: function () { return false; }, configurable: true });
  } catch (e) {
    try { Object.defineProperty(navigator, 'webdriver', { get: function () { return false; }, configurable: true }); } catch (e2) {}
  }
  // 2. window.chrome(WebView/headless 常缺)
  try {
    if (!window.chrome) { window.chrome = { runtime: {}, app: {}, loadTimes: function () {}, csi: function () {} }; }
  } catch (e) {}
  // 3. plugins / mimeTypes(WebView 为空 → 造常见插件)
  try {
    var mk = function (n, d, f) { return { name: n, description: d, filename: f, length: 1 }; };
    var fake = [
      mk('Chrome PDF Plugin', 'Portable Document Format', 'internal-pdf-viewer'),
      mk('Chrome PDF Viewer', '', 'mhjfbmdgcfjbbpaeojofohoefgiehjai'),
      mk('Native Client', '', 'internal-nacl-plugin')
    ];
    Object.defineProperty(navigator, 'plugins', { get: function () { return fake; }, configurable: true });
  } catch (e) {}
  // 4. languages
  try {
    Object.defineProperty(navigator, 'languages', { get: function () { return ['zh-CN', 'zh', 'en-US', 'en']; }, configurable: true });
  } catch (e) {}
  // 5. hardwareConcurrency / deviceMemory(避免 0/异常值)
  try { Object.defineProperty(navigator, 'hardwareConcurrency', { get: function () { return 8; }, configurable: true }); } catch (e) {}
  try { Object.defineProperty(navigator, 'deviceMemory', { get: function () { return 8; }, configurable: true }); } catch (e) {}
  // 6. permissions.query:Notification 状态不一致是经典 headless tell
  try {
    var pq = navigator.permissions && navigator.permissions.query;
    if (pq) {
      navigator.permissions.query = asNative(function (p) {
        if (p && p.name === 'notifications') { return Promise.resolve({ state: Notification.permission, onchange: null }); }
        return pq.call(navigator.permissions, p);
      }, 'query');
    }
  } catch (e) {}
  // 7. WebGL vendor/renderer(模拟器/WebView 常露 SwiftShader / Google)
  try {
    var patchGL = function (proto) {
      if (!proto || !proto.getParameter) return;
      var og = proto.getParameter;
      proto.getParameter = asNative(function (p) {
        if (p === 37445) return 'Intel Inc.';                // UNMASKED_VENDOR_WEBGL
        if (p === 37446) return 'Intel Iris OpenGL Engine';  // UNMASKED_RENDERER_WEBGL
        return og.call(this, p);
      }, 'getParameter');
    };
    if (window.WebGLRenderingContext) patchGL(WebGLRenderingContext.prototype);
    if (window.WebGL2RenderingContext) patchGL(WebGL2RenderingContext.prototype);
  } catch (e) {}
})();
"""
}
