package com.apk.claw.android.plugin

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val type: String = "dex",
    @SerializedName("entry_class")
    val entryClass: String = "",
    @SerializedName("dex_file")
    val dexFile: String = "plugin.dex",
    val permissions: List<String> = emptyList(),
    @SerializedName("min_app_version")
    val minAppVersion: Int = 0,
    val description: String = "",
    val author: String = "",

    // browser-script (inject/code): content script + host matching + block rules
    val js: String = "",
    @SerializedName("host_pattern")
    val hostPattern: String = "*",
    @SerializedName("block_rules")
    val blockRules: List<String> = emptyList(),

    // userscript extension fields (compatible with Tampermonkey/Greasemonkey .user.js format)
    val match: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    @SerializedName("run_at")
    val runAt: String = "document-idle",
    val grants: List<String> = emptyList(),
    val requires: List<String> = emptyList(),
    val resources: Map<String, String> = emptyMap(),
    val css: String = "",
    val script: String = "",

    // tool (declarative HTTP)
    @SerializedName("tool_name")
    val toolName: String = "",
    @SerializedName("tool_params")
    val toolParams: List<PluginToolParam> = emptyList(),
    val http: HttpRecipe? = null,

    // mini-app
    val page: String = "",
    val actions: List<PluginActionDef> = emptyList(),

    // structured permissions (new types)
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

        fun fromJson(json: String): PluginManifest? {
            return try {
                gson.fromJson(json, PluginManifest::class.java)?.let(::sanitizeNullLists)
            } catch (e: Exception) {
                null
            }
        }

        @Suppress("USELESS_ELVIS")
        private fun sanitizeNullLists(m: PluginManifest): PluginManifest = m.copy(
            permissions = m.permissions ?: emptyList(),
            blockRules = m.blockRules ?: emptyList(),
            toolParams = m.toolParams ?: emptyList(),
            allowHosts = m.allowHosts ?: emptyList(),
            allowTools = m.allowTools ?: emptyList(),
            allowDevice = m.allowDevice ?: emptyList(),
            actions = (m.actions ?: emptyList()).map { it.copy(params = it.params ?: emptyList()) },
            match = m.match ?: emptyList(),
            exclude = m.exclude ?: emptyList(),
            grants = m.grants ?: emptyList(),
            requires = m.requires ?: emptyList(),
            resources = m.resources ?: emptyMap(),
        )

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

data class PluginInfo(
    val manifest: PluginManifest,
    val source: String,
    val dexPath: String,
    var isLoaded: Boolean = false,
    var registeredTools: List<String> = emptyList(),
    var error: String? = null
)

data class PluginActionDef(
    val name: String = "",
    val description: String = "",
    val params: List<PluginToolParam> = emptyList(),
)

data class PluginToolParam(
    val name: String = "",
    val type: String = "string",
    val description: String = "",
    val required: Boolean = false
)

data class HttpRecipe(
    val method: String = "GET",
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null
)
