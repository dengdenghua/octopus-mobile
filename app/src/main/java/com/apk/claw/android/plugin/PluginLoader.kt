package com.apk.claw.android.plugin

import android.content.Context
import android.util.Log
import com.apk.claw.android.tool.BaseTool
import dalvik.system.DexClassLoader
import java.io.File

/**
 * 插件加载器 —— 使用 DexClassLoader 加载 .dex 文件中的工具。
 *
 * Android 10+ 对匿名可执行内存 (W^X) 有限制，
 * 因此 dex 文件必须放在 filesDir 私有目录而非 code_cache。
 *
 * 使用方式：
 * ```
 * val loader = PluginLoader(context)
 * val tools = loader.loadFromDex(dexFile, entryClass)
 * ```
 */
class PluginLoader(private val context: Context) {

    companion object {
        private const val TAG = "PluginLoader"

        /** 插件 dex 优化目录（在 filesDir 下，绕过 W^X） */
        private fun getOptimizedDir(context: Context): File {
            val dir = File(context.filesDir, "plugins/optimized")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }
    }

    /**
     * 从 dex 文件加载工具实例。
     *
     * @param dexPath dex 文件绝对路径
     * @param entryClass 入口类全限定名（需实现 BaseTool）
     * @return 加载成功返回 BaseTool 列表，失败返回 null
     */
    fun loadTools(dexPath: String, entryClass: String): List<BaseTool>? {
        val dexFile = File(dexPath)
        if (!dexFile.exists()) {
            Log.e(TAG, "Dex file not found: $dexPath")
            return null
        }

        return try {
            val optimizedDir = getOptimizedDir(context)

            // 使用 DexClassLoader 加载
            // parent = context.classLoader，插件可访问 BaseTool 等类
            val classLoader = DexClassLoader(
                dexPath,
                optimizedDir.absolutePath,
                null,  // 无额外 native library
                context.classLoader
            )

            // 加载入口类
            val clazz = classLoader.loadClass(entryClass)
            Log.i(TAG, "Loaded class: ${clazz.name}")

            // 尝试实例化
            val instance = clazz.getDeclaredConstructor().newInstance()

            when (instance) {
                is BaseTool -> {
                    Log.i(TAG, "Loaded tool: ${instance.getName()}")
                    listOf(instance)
                }
                is PluginEntry -> {
                    // 插件入口接口：返回多个工具
                    val tools = instance.createTools()
                    Log.i(TAG, "Loaded ${tools.size} tools from PluginEntry")
                    tools
                }
                else -> {
                    Log.e(TAG, "Entry class ${clazz.name} does not implement BaseTool or PluginEntry")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load plugin from $dexPath", e)
            null
        }
    }

    /**
     * 从 assets 目录复制 dex 到 filesDir（首次启动时）。
     *
     * @param assetPath assets 中的 dex 相对路径
     * @param destDir 目标目录
     * @return 复制后的文件路径
     */
    fun copyDexFromAssets(assetPath: String, destDir: File): String? {
        return try {
            if (!destDir.exists()) destDir.mkdirs()
            val destFile = File(destDir, File(assetPath).name)

            // 只在目标文件不存在或更旧时复制
            context.assets.open(assetPath).use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            Log.i(TAG, "Copied dex from assets: $assetPath -> ${destFile.absolutePath}")
            destFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy dex from assets: $assetPath", e)
            null
        }
    }
}

/**
 * 插件入口接口 —— 插件可以返回多个工具。
 *
 * 插件的 entryClass 可以实现 BaseTool（单工具）或 PluginEntry（多工具）。
 */
interface PluginEntry {
    /**
     * 创建插件提供的所有工具实例。
     */
    fun createTools(): List<BaseTool>

    /**
     * 插件初始化（可选）。
     */
    fun init(context: Context) {}

    /**
     * 插件销毁（可选）。
     */
    fun destroy() {}
}
