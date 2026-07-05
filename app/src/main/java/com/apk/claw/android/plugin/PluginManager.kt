package com.apk.claw.android.plugin

import android.content.Context
import android.util.Log
import com.apk.claw.android.octopus_mobile.browser.BrowserPluginHost
import com.apk.claw.android.octopus_mobile.browser.UserscriptParser
import com.apk.claw.android.octopus_mobile.safety.SafetyGate
import com.apk.claw.android.registry.PluginRegistryStore
import com.apk.claw.android.tool.ToolRegistry
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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
        private const val GENERATED_APPS_DIR = "generated_apps"
    }

    private val loader = PluginLoader(context)

    /** 已发现的所有插件（含未加载的）。ConcurrentHashMap:discover/load 可能与查询并发。 */
    private val discoveredPlugins = ConcurrentHashMap<String, PluginInfo>()

    /** 已加载的插件 */
    private val loadedPlugins = ConcurrentHashMap<String, PluginInfo>()

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
     * 只重扫非 dex 插件(browser-script/tool/mini-app),不重跑 dex 扫描/加载。
     * 供 [com.apk.claw.android.tool.impl.GenerateAppTool] 现场生成小程序后立即刷新
     * [MiniAppRegistry],不需要用户重启 App 就能在「小程序」列表里看到、打开。
     */
    fun refreshNonDexPlugins() = loadNonDexPlugins()

    /**
     * 卸载一个小程序:删 filesDir/plugins 与 filesDir/generated_apps 下对应目录 + 移出 registry 清单,
     * 再刷新一次 [MiniAppRegistry](桌面/列表立即消失)。id 可能带前缀(如 plugin/<slug>),两种候选都试。
     */
    fun uninstallMiniApp(id: String) {
        val candidates = linkedSetOf(id, id.substringAfterLast('/'))
        for (slug in candidates) {
            runCatching { File(context.filesDir, "$FILES_PLUGIN_DIR/$slug").deleteRecursively() }
            runCatching { File(context.filesDir, "$GENERATED_APPS_DIR/$slug").deleteRecursively() }
            runCatching { PluginRegistryStore.uninstall(context, slug) }
        }
        refreshNonDexPlugins()
    }

    /**
     * 加载非 dex 类型插件:browser-script / tool / mini-app。
     *
     * 信任规则(fail-closed):
     *  - assets 源 — 随签名 APK 打包,无条件信任。
     *  - files 源(registry 安装) — 仅限经 [PluginRegistryStore] sha256 校验安装的插件。
     *    外部旁加载的 files 源注入脚本/小程序不加载(无 sha256 记录则不在 installedSlugs 中)。
     *  - generated 源 — [GENERATED_APPS_DIR] 只有本 App 自己的 generate_app 工具会写入,
     *    这是「自己生成给自己用」而非「第三方发布插件」，跟 registry 的 sha256 供应链校验是
     *    两个不同的信任场景，故不经 installedSlugs 那道闸门。桥的能力面本身仍受
     *    [PermissionGate] + [ToolRegistry.withUntrustedSource] 管控(生成的 manifest 默认
     *    不声明任何 allow_tools/allow_device/allow_pay，等同零桥权限)。
     */
    private fun loadNonDexPlugins() {
        // assets 来源:无条件信任
        val assetsManifests = scanAssetsManifests().filter { it.type != "dex" }

        // files 来源:只信任 PluginRegistryStore 已校验安装的 slug(目录名即 slug)
        val installedSlugs = PluginRegistryStore.installed(context).map { it.slug }.toSet()
        val filesManifests = scanFilesManifests()
            .filter { (slug, m) -> m.type != "dex" && slug in installedSlugs }
            .map { (_, m) -> m }

        // 自生成来源:见上方信任规则注释
        val generatedManifests = scanGeneratedAppManifests().filter { it.type != "dex" }

        // assets 优先,其次 files(registry),同 id 冲突一律保留更早枚举的来源
        val assetIds = assetsManifests.map { it.id }.toSet()
        val filesIds = filesManifests.map { it.id }.toSet()
        val allManifests = assetsManifests +
            filesManifests.filter { it.id !in assetIds } +
            generatedManifests.filter { it.id !in assetIds && it.id !in filesIds }

        if (allManifests.isEmpty()) {
            BrowserPluginHost.setScripts(emptyList())
            BrowserPluginHost.setBlockRules(emptyList())
            MiniAppRegistry.set(emptyList())
            return
        }

        // browser-script → BrowserPluginHost
        val scriptEntries = allManifests
            .filter { it.type == "browser-script" }
            .mapNotNull { m ->
                val dir = when {
                    assetIds.contains(m.id) -> findAssetsDir(m.id)
                    filesIds.contains(m.id) -> File(File(context.filesDir, FILES_PLUGIN_DIR), m.id)
                    else -> findGeneratedDir(m.id)
                }
                BrowserPluginHost.buildEntryFromManifest(m, dir, context, idPrefix = "plugin_")
            }

        // 扫描独立 .user.js 文件（在 plugins/ 和 generated_apps/ 目录下）
        val userJsEntries = scanStandaloneUserScripts(assetsManifests + filesManifests + generatedManifests)

        val allScripts = scriptEntries + userJsEntries
        BrowserPluginHost.setScripts(allScripts)
        BrowserPluginHost.setBlockRules(allManifests.filter { it.type == "browser-script" }.flatMap { it.blockRules })

        // 同时从 browser_plugins.json 加载旧格式脚本（addLocalScripts 合并进来）
        BrowserPluginHost.loadFromFile(context)

        // tool → 声明式工具注册进 ToolRegistry(files 源 registry 插件也可贡献工具)
        allManifests.filter { it.type == "tool" }.forEach { m ->
            runCatching { ToolRegistry.registerPluginTool(DeclarativePluginTool(m)) }
                .onFailure { Log.e(TAG, "register declarative tool failed: ${m.id}", it) }
        }

        // mini-app → 注册表(assets + registry-verified files 均可启动)
        MiniAppRegistry.set(allManifests.filter { it.type == "mini-app" })

        Log.i(TAG, "Non-dex plugins: ${allScripts.size} userscripts, " +
            "${allManifests.count { it.type == "tool" }} tool, " +
            "${allManifests.count { it.type == "mini-app" }} mini-app " +
            "(assets=${assetsManifests.size} files=${filesManifests.size} generated=${generatedManifests.size})")
    }

    private fun findAssetsDir(pluginId: String): File? {
        val dir = File(context.cacheDir, "assets_plugins/$pluginId")
        if (dir.isDirectory && dir.listFiles()?.isNotEmpty() == true) return dir
        runCatching {
            dir.mkdirs()
            val assetDir = "$ASSETS_PLUGIN_DIR/$pluginId"
            val names = context.assets.list(assetDir) ?: return@runCatching
            for (name in names) {
                val out = File(dir, name)
                context.assets.open("$assetDir/$name").use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }.onFailure { Log.w(TAG, "copy assets plugin $pluginId failed: ${it.message}") }
        return dir.takeIf { it.isDirectory && it.listFiles()?.isNotEmpty() == true }
    }

    private fun findGeneratedDir(pluginId: String): File? {
        return File(context.filesDir, "$GENERATED_APPS_DIR/$pluginId").takeIf { it.isDirectory }
    }

    private fun scanStandaloneUserScripts(manifests: List<PluginManifest>): List<BrowserPluginHost.UserScriptEntry> {
        val entries = mutableListOf<BrowserPluginHost.UserScriptEntry>()
        val dirsToScan = mutableListOf<File>()
        dirsToScan.add(File(context.filesDir, FILES_PLUGIN_DIR))
        dirsToScan.add(File(context.filesDir, GENERATED_APPS_DIR))
        for (baseDir in dirsToScan) {
            if (!baseDir.isDirectory) continue
            baseDir.listFiles()?.forEach { dir ->
                if (!dir.isDirectory) return@forEach
                dir.listFiles()?.filter { it.isFile && it.name.endsWith(".user.js") }?.forEach { jsFile ->
                    runCatching {
                        val source = jsFile.readText()
                        val parsed = UserscriptParser.parse(source, fallbackId = jsFile.nameWithoutExtension)
                        if (parsed != null) {
                            val entry = BrowserPluginHost.buildEntryFromUserscript(parsed, context, idPrefix = "us_")
                            if (entry != null) entries.add(entry)
                        }
                    }.onFailure { Log.w(TAG, "failed to parse userscript ${jsFile.absolutePath}: ${it.message}") }
                }
            }
        }
        return entries
    }

    /**
     * 扫描 filesDir/generated_apps 下所有 manifest —— [com.apk.claw.android.tool.impl.GenerateAppTool]
     * 现场生成的小程序。只有本 App 自己的进程会写入这个目录，见 [loadNonDexPlugins] 顶部的信任规则注释。
     */
    private fun scanGeneratedAppManifests(): List<PluginManifest> {
        val out = mutableListOf<PluginManifest>()
        val dir = File(context.filesDir, GENERATED_APPS_DIR)
        if (!dir.isDirectory) return out
        dir.listFiles()?.forEach { sub ->
            if (!sub.isDirectory) return@forEach
            runCatching {
                File(sub, "manifest.json").takeIf { it.isFile }?.inputStream()?.use { s ->
                    PluginManifest.fromStream(s)?.let { out.add(it) }
                }
            }
        }
        return out
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
