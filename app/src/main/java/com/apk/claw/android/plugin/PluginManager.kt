package com.apk.claw.android.plugin

import android.content.Context
import android.util.Log
import com.apk.claw.android.octopus_mobile.browser.BrowserPluginHost
import com.apk.claw.android.octopus_mobile.safety.SafetyGate
import com.apk.claw.android.registry.PluginRegistryStore
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
        } catch (e: Exception) {
            Log.w(TAG, "getPackageInfo failed", e)
            0
        }

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

        // 安全(fail-closed):外部安装的 dex(source=files,经 installFromFile 从任意外部
        // 文件复制)未经代码签名校验,DexClassLoader 会以 app 全权限执行其代码(shell/文件/
        // 短信等)。上面的 SafetyGate 只扫 manifest 文本、不验代码真伪。在实现 APK 证书签名
        // 校验 + 能力白名单之前,只信任随签名 APK 打包的 assets 插件,拒绝加载外部安装的 dex。
        if (info.source != "assets") {
            info.error = "Untrusted plugin source '${info.source}': loading external dex is " +
                "disabled until code-signature verification is implemented (security)."
            Log.e(TAG, info.error ?: "")
            return false
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

        // 前置检查:外部安装的 dex 会被 loadAndRegister 拒绝加载(source != "assets"),
        // 提前返回避免残留 dex 文件被未来漏洞利用。
        // (loadAndRegister 中的 fail-closed 门控:只信任随签名 APK 打包的 assets 插件)
        Log.w(TAG, "installFromFile: external plugin dex will be rejected by loadAndRegister "
            + "(source='files' != 'assets'). Skipping copy to avoid residual dex.")
        return null
    }

    /**
     * 自动加载所有发现的插件（dex 工具 + 非 dex 的 browser-script/tool/mini-app）。
     */
    fun loadAll() {
        discoverPlugins()
        for (pluginId in discoveredPlugins.keys.toList()) {
            loadAndRegister(pluginId)
        }
        loadNonDexPlugins()
    }

    /**
     * 加载非 dex 类型插件:browser-script / tool / mini-app。
     *
     * 信任规则(fail-closed):
     *  - assets 源 — 随签名 APK 打包,无条件信任。
     *  - files 源 — 仅限经 [PluginRegistryStore] sha256 校验安装的插件(registry 下载路径)。
     *    外部旁加载的 files 源注入脚本/小程序不加载(无 sha256 记录则不在 installedSlugs 中)。
     */
    private fun loadNonDexPlugins() {
        // assets 来源:无条件信任
        val assetsManifests = scanAssetsManifests().filter { it.type != "dex" }

        // files 来源:只信任 PluginRegistryStore 已校验安装的 slug(目录名即 slug)
        val installedSlugs = PluginRegistryStore.installed(context).map { it.slug }.toSet()
        val filesManifests = scanFilesManifests()
            .filter { (slug, m) -> m.type != "dex" && slug in installedSlugs }
            .map { (_, m) -> m }

        // assets 优先:id 冲突时保留 assets 版本
        val assetIds = assetsManifests.map { it.id }.toSet()
        val allManifests = assetsManifests + filesManifests.filter { it.id !in assetIds }

        if (allManifests.isEmpty()) {
            BrowserPluginHost.setPlugins(emptyList())
            BrowserPluginHost.setBlockRules(emptyList())
            MiniAppRegistry.set(emptyList())
            return
        }

        // browser-script → BrowserPluginHost(只信任 assets 源,注入脚本安全边界更严格)
        val scripts = assetsManifests.filter { it.type == "browser-script" }
        BrowserPluginHost.setPlugins(scripts.map { m ->
            BrowserPluginHost.InjectPlugin(
                id = m.id, name = m.name, hostPattern = m.hostPattern, js = m.js, enabled = true
            )
        })
        BrowserPluginHost.setBlockRules(scripts.flatMap { it.blockRules })

        // tool → 声明式工具注册进 ToolRegistry(files 源 registry 插件也可贡献工具)
        allManifests.filter { it.type == "tool" }.forEach { m ->
            runCatching { ToolRegistry.registerPluginTool(DeclarativePluginTool(m)) }
                .onFailure { Log.e(TAG, "register declarative tool failed: ${m.id}", it) }
        }

        // mini-app → 注册表(assets + registry-verified files 均可启动)
        MiniAppRegistry.set(allManifests.filter { it.type == "mini-app" })

        Log.i(TAG, "Non-dex plugins: ${scripts.size} browser-script, " +
            "${allManifests.count { it.type == "tool" }} tool, " +
            "${allManifests.count { it.type == "mini-app" }} mini-app " +
            "(assets=${assetsManifests.size} files=${filesManifests.size})")
    }

    /**
     * 扫描 filesDir/plugins 下所有 manifest(不要求 dex 文件存在)。
     * 返回 Pair(目录名, manifest),目录名即 registry slug,调用方用它过滤可信来源。
     */
    private fun scanFilesManifests(): List<Pair<String, PluginManifest>> {
        val out = mutableListOf<Pair<String, PluginManifest>>()
        val pluginsDir = File(context.filesDir, FILES_PLUGIN_DIR)
        if (!pluginsDir.isDirectory) return out
        pluginsDir.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            runCatching {
                File(dir, "manifest.json").takeIf { it.isFile }?.inputStream()?.use { s ->
                    PluginManifest.fromStream(s)?.let { out.add(dir.name to it) }
                }
            }
        }
        return out
    }

    /** 扫描 assets/plugins 下所有 manifest（不要求 dex 文件存在）。 */
    private fun scanAssetsManifests(): List<PluginManifest> {
        val out = mutableListOf<PluginManifest>()
        try {
            context.assets.list(ASSETS_PLUGIN_DIR)?.forEach { dir ->
                runCatching {
                    context.assets.open("$ASSETS_PLUGIN_DIR/$dir/manifest.json").use { s ->
                        PluginManifest.fromStream(s)?.let { out.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "No assets plugins dir: ${e.message}")
        }
        return out
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

                    // 非 dex 类型(browser-script/tool/mini-app)不走 dex 加载,由 loadNonDexPlugins 处理
                    if (manifest.type != "dex") continue

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

            // 非 dex 类型由 loadNonDexPlugins 处理(但当前仅信任 assets 源,files 非 dex 不加载)
            if (manifest.type != "dex") return@forEach

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
