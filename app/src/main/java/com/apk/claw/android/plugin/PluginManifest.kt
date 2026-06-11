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

    /** 入口类全限定名（必须实现 BaseTool 或 PluginEntry 接口） */
    @SerializedName("entry_class")
    val entryClass: String,

    /** dex 文件名（相对于插件目录） */
    @SerializedName("dex_file")
    val dexFile: String = "plugin.dex",

    /** 插件声明的权限（如 "network", "storage", "accessibility"） */
    val permissions: List<String> = emptyList(),

    /** 最低 App 版本要求 */
    @SerializedName("min_app_version")
    val minAppVersion: Int = 0,

    /** 插件描述 */
    val description: String = "",

    /** 作者 */
    val author: String = ""
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
