package com.apk.claw.android.plugin

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * 插件清单 —— 描述一个插件的元信息。
 *
 * 对应 assets/plugins/<pluginId>/manifest.json 或 filesDir/plugins/<pluginId>/manifest.json
 */
data class PluginManifest(
    /** 插件唯一 ID（也是目录名） */
    val id: String,

    /** 插件显示名称 */
    val name: String,

    /** 版本号 */
    val version: String,

    /**
     * 插件类型:`dex`(默认,加载 .dex 工具,沿用旧行为)| `browser-script` | `tool` | `mini-app`。
     * 非 dex 类型不需要 entry_class/dex_file。详见 repo PLUGIN_ECOSYSTEM.md。
     */
    val type: String = "dex",

    /** 入口类全限定名（dex 类型必填，实现 BaseTool 或 PluginEntry） */
    @SerializedName("entry_class")
    val entryClass: String = "",

    /** dex 文件名（相对于插件目录） */
    @SerializedName("dex_file")
    val dexFile: String = "plugin.dex",

    /** 插件声明的权限（旧 dex 用，如 "network", "storage", "accessibility"） */
    val permissions: List<String> = emptyList(),

    /** 最低 App 版本要求 */
    @SerializedName("min_app_version")
    val minAppVersion: Int = 0,

    /** 插件描述 */
    val description: String = "",

    /** 作者 */
    val author: String = "",

    // ── browser-script(inject/code):内容脚本 + 域名匹配 + 拦截规则 ──
    /** 内容脚本 JS（inline） */
    val js: String = "",
    /** 注入域名匹配（正则,"*"=所有；见 BrowserPluginHost.InjectPlugin.hostPattern） */
    @SerializedName("host_pattern")
    val hostPattern: String = "*",
    /** 拦截规则（匹配整条 URL 的正则，广告/跟踪） */
    @SerializedName("block_rules")
    val blockRules: List<String> = emptyList(),

    // ── tool(声明式 HTTP）──
    /** 工具名（agent 据此调用；空则用 plugin_<id>） */
    @SerializedName("tool_name")
    val toolName: String = "",
    /** 工具参数声明 */
    @SerializedName("tool_params")
    val toolParams: List<PluginToolParam> = emptyList(),
    /** HTTP 配方 */
    val http: HttpRecipe? = null,

    // ── mini-app ──
    /** 入口页面文件名（相对插件目录，如 index.html） */
    val page: String = "",

    // ── 结构化权限（新类型用；默认 deny，敏感能力需用户授予，见 PermissionGate） ──
    @SerializedName("allow_hosts")
    val allowHosts: List<String> = emptyList(),
    @SerializedName("allow_tools")
    val allowTools: List<String> = emptyList(),
    @SerializedName("allow_device")
    val allowDevice: List<String> = emptyList(),
    @SerializedName("allow_pay")
    val allowPay: Boolean = false
) {
    companion object {
        private val gson = Gson()

        /**
         * 从 JSON 字符串解析
         */
        fun fromJson(json: String): PluginManifest? {
            return try {
                gson.fromJson(json, PluginManifest::class.java)
            } catch (e: Exception) {
                null
            }
        }

        /**
         * 从 InputStream 解析
         */
        fun fromStream(stream: java.io.InputStream): PluginManifest? {
            return try {
                val json = stream.bufferedReader().readText()
                fromJson(json)
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * 运行时插件信息 —— 加载后的插件状态。
 */
data class PluginInfo(
    val manifest: PluginManifest,

    /** 插件来源: "assets" 内置 / "files" 用户安装 */
    val source: String,

    /** dex 文件绝对路径 */
    val dexPath: String,

    /** 是否已加载到 ToolRegistry */
    var isLoaded: Boolean = false,

    /** 加载的工具名称列表 */
    var registeredTools: List<String> = emptyList(),

    /** 加载错误信息 */
    var error: String? = null
)

/** 声明式工具的参数声明（type=tool）。 */
data class PluginToolParam(
    val name: String = "",
    val type: String = "string",
    val description: String = "",
    val required: Boolean = false
)

/** 声明式工具的 HTTP 配方（type=tool）。url/headers/body 支持 `{{param}}` 占位。 */
data class HttpRecipe(
    val method: String = "GET",
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null
)
