package com.apk.claw.android.plugin

import android.content.Context
import android.util.Log
import com.apk.claw.android.octopus_mobile.safety.SafetyGate
import com.apk.claw.android.tool.ToolRegistry
import java.io.File

/**
 * 插件管理器 —— 发现、加载、卸载插件。
 *
 * 插件来源：
 *  1. assets/plugins/<id>/manifest.json + <dex_file> — 内置插件
 *  2. filesDir/plugins/<id>/manifest.json + <dex_file> — 用户安装
 *
 * 加载流程：
 *  1. discoverPlugins() 扫描所有 manifest
 *  2. loadAndRegister(info) 安全检查 → DexClassLoader → ToolRegistry.register
 *  3. unload(pluginId) → ToolRegistry.unregister
 */
class PluginManager(private val context: Context) {

    companion object {
        private const val TAG = "PluginManager"
        private const val ASSETS_PLUGIN_DIR = "plugins"
        private const val FILES_PLUGIN_DIR = "plugins"
    }

    private val loader = PluginLoader(context)

    /** 已发现的所有插件（含未加载的） */
    private val discoveredPlugins = mutableMapOf<String, PluginInfo>()

    /** 已加载的插件 */
    private val loadedPlugins = mutableMapOf<String, PluginInfo>()

    /**
     * 发现所有可用插件（扫描 assets + filesDir）。
     * 应在 Application 启动时调用一次。
     */
    fun discoverPlugins(): List<PluginInfo> {
        discoveredPlugins.clear()

        // 1. 扫描 assets/plugins/
        discoverAssetsPlugins()

        // 2. 扫描 filesDir/plugins/
        discoverFilesPlugins()

        Log.i(TAG, "Discovered ${discoveredPlugins.size} plugin(s)")
        return discoveredPlugins.values.toList()
    }

    /**
     * 获取所有已发现的插件
     */
    fun getAllPlugins(): List<PluginInfo> = discoveredPlugins.values.toList()

    /**
     * 获取已加载的插件
     */
    fun getLoadedPlugins(): List<PluginInfo> = loadedPlugins.values.toList()

    /**
     * 获取未加载的插件
     */
    fun getAvailablePlugins(): List<PluginInfo> =
        discoveredPlugins.values.filter { !it.isLoaded }

    /**
     * 加载并注册指定插件。
     *
     * @param pluginId 插件 ID
     * @return true=加载成功
     */
    fun loadAndRegister(pluginId: String): Boolean {
        val info = discoveredPlugins[pluginId] ?: run {
            Log.e(TAG, "Plugin not found: $pluginId")
            return false
        }

        if (info.isLoaded) {
            Log.w(TAG, "Plugin already loaded: $pluginId")
            return true
        }

        // 安全检查：版本兼容
        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        } catch (_: Exception) { 0 }

        if (info.manifest.minAppVersion > appVersion) {
            info.error = "App version too old (need >= ${info.manifest.minAppVersion})"
            Log.e(TAG, info.error ?: "")
            return false
        }

        // 安全检查：SafetyGate 扫描插件清单（PII/Secret 检测）
        ToolRegistry.safetyGate?.let { gate ->
            val manifestText = "plugin:${info.manifest.id} entry:${info.manifest.entryClass} perms:${info.manifest.permissions}"
            val verdict = gate.check(manifestText, "plugin_load")
            if (verdict.isBlocked) {
                info.error = "SafetyGate blocked: ${verdict.reason}"
                Log.e(TAG, info.error ?: "")
                return false
            }
        }

        // 加载工具
        val tools = loader.loadTools(info.dexPath, info.manifest.entryClass)
        if (tools == null || tools.isEmpty()) {
            info.error = "No tools loaded from plugin"
            Log.e(TAG, info.error ?: "")
            return false
        }

        // 注册到 ToolRegistry
        val registeredNames = mutableListOf<String>()
        for (tool in tools) {
            try {
                ToolRegistry.registerPluginTool(tool)
                registeredNames.add(tool.getName())
                Log.i(TAG, "Registered plugin tool: ${tool.getName()}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register tool: ${tool.getName()}", e)
            }
        }

        info.isLoaded = true
        info.registeredTools = registeredNames
        info.error = null
        loadedPlugins[pluginId] = info

        Log.i(TAG, "Plugin loaded: ${info.manifest.name} (${registeredNames.size} tools)")
        return true
    }

    /**
     * 卸载指定插件。
     *
     * @param pluginId 插件 ID
     * @return true=卸载成功
     */
    fun unload(pluginId: String): Boolean {
        val info = loadedPlugins.remove(pluginId) ?: run {
            Log.w(TAG, "Plugin not loaded: $pluginId")
            return false
        }

        // 从 ToolRegistry 移除
        for (toolName in info.registeredTools) {
            ToolRegistry.unregister(toolName)
            Log.i(TAG, "Unregistered plugin tool: $toolName")
        }

        info.isLoaded = false
        info.registeredTools = emptyList()
        discoveredPlugins[pluginId] = info

        Log.i(TAG, "Plugin unloaded: ${info.manifest.name}")
        return true
    }

    /**
     * 安装外部插件（从文件复制 dex + manifest 到 filesDir）。
     *
     * @param dexFile dex 文件
     * @param manifestFile manifest.json 文件
     * @return 安装成功的 PluginInfo，失败返回 null
     */
    fun installFromFile(dexFile: File, manifestFile: File): PluginInfo? {
        val manifest = PluginManifest.fromStream(manifestFile.inputStream()) ?: run {
            Log.e(TAG, "Invalid manifest file")
            return null
        }

        val pluginDir = File(context.filesDir, "$FILES_PLUGIN_DIR/${manifest.id}")
        if (!pluginDir.exists()) pluginDir.mkdirs()

        // 复制文件
        dexFile.copyTo(File(pluginDir, manifest.dexFile), overwrite = true)
        manifestFile.copyTo(File(pluginDir, "manifest.json"), overwrite = true)

        val info = PluginInfo(
            manifest = manifest,
            source = "files",
            dexPath = File(pluginDir, manifest.dexFile).absolutePath
        )

        discoveredPlugins[manifest.id] = info
        Log.i(TAG, "Plugin installed: ${manifest.name} v${manifest.version}")
        return info
    }

    /**
     * 自动加载所有发现的插件。
     */
    fun loadAll() {
        discoverPlugins()
        for (pluginId in discoveredPlugins.keys.toList()) {
            loadAndRegister(pluginId)
        }
    }

    // ── 内部扫描方法 ─────────────────────────────────

    private fun discoverAssetsPlugins() {
        try {
            val dirs = context.assets.list(ASSETS_PLUGIN_DIR) ?: return
            for (dir in dirs) {
                val manifestPath = "$ASSETS_PLUGIN_DIR/$dir/manifest.json"
                try {
                    val manifest = context.assets.open(manifestPath).use { stream ->
                        PluginManifest.fromStream(stream)
                    } ?: continue

                    // assets 中的 dex 需要先复制到 filesDir
                    val pluginDir = File(context.filesDir, "$FILES_PLUGIN_DIR/${manifest.id}")
                    val dexPath = loader.copyDexFromAssets(
                        "$ASSETS_PLUGIN_DIR/$dir/${manifest.dexFile}",
                        pluginDir
                    ) ?: continue

                    discoveredPlugins[manifest.id] = PluginInfo(
                        manifest = manifest,
                        source = "assets",
                        dexPath = dexPath
                    )
                } catch (e: Exception) {
                    Log.d(TAG, "Skipping assets plugin dir: $dir (${e.message})")
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "No assets plugins directory: ${e.message}")
        }
    }

    private fun discoverFilesPlugins() {
        val pluginsDir = File(context.filesDir, FILES_PLUGIN_DIR)
        if (!pluginsDir.exists() || !pluginsDir.isDirectory) return

        pluginsDir.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val manifestFile = File(dir, "manifest.json")
            if (!manifestFile.exists()) return@forEach

            val manifest = PluginManifest.fromStream(manifestFile.inputStream()) ?: return@forEach

            // 不覆盖已发现的 assets 插件
            if (discoveredPlugins.containsKey(manifest.id)) return@forEach

            val dexFile = File(dir, manifest.dexFile)
            if (!dexFile.exists()) {
                Log.w(TAG, "Dex file missing for plugin ${manifest.id}")
                return@forEach
            }

            discoveredPlugins[manifest.id] = PluginInfo(
                manifest = manifest,
                source = "files",
                dexPath = dexFile.absolutePath
            )
        }
    }
}
