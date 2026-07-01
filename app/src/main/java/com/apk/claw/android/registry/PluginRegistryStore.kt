package com.apk.claw.android.registry

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Registry 下载插件的本地落地层。
 *
 * 插件体 = base64 ZIP(browser-script/tool 可仅含 manifest.json;mini-app 含 HTML 等资产)。
 * 安装后目录:filesDir/plugins/<slug>/manifest.json + 其他资产。
 * [PluginManager.discoverFilesPlugins] 在下次 loadAll() 时自动拾取该目录。
 *
 * 安全:sha256 校验在写盘前做(与 RegistrySkillStore 相同原则)。
 * 沙箱:只写 filesDir,不碰 assets(assets 才是 fail-closed 的内置面)。
 */
internal object PluginRegistryStore {

    private val gson = Gson()

    data class InstalledPlugin(
        val id: String,
        val slug: String,
        val version: String,
        val name: String,
        val description: String,
        val kind: String,
        val checksum: String,
        val installedAt: Long,
        val enabled: Boolean = true,
    )

    private data class InstalledPluginManifest(val plugins: MutableList<InstalledPlugin> = mutableListOf())

    private fun pluginsDir(context: Context): File =
        File(context.filesDir, "plugins").apply { mkdirs() }

    private fun manifestFile(context: Context): File =
        File(context.filesDir, "registry/plugins/.manifest.json")

    private fun readManifest(context: Context): InstalledPluginManifest {
        val f = manifestFile(context)
        if (!f.isFile) return InstalledPluginManifest()
        return runCatching { gson.fromJson(f.readText(), InstalledPluginManifest::class.java) }
            .getOrNull() ?: InstalledPluginManifest()
    }

    private fun writeManifest(context: Context, m: InstalledPluginManifest) {
        manifestFile(context).also { it.parentFile?.mkdirs() }
            .writeText(gson.toJson(m))
    }

    fun installed(context: Context): List<InstalledPlugin> = readManifest(context).plugins.toList()

    fun isInstalled(context: Context, slug: String): Boolean =
        readManifest(context).plugins.any { it.slug == slug }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /**
     * 安装一个插件:
     * 1. base64 解码 body → 校验 sha256
     * 2. 按内容类型解包:纯 manifest(无 ZIP magic bytes)直接写 manifest.json;否则 ZipInputStream 展开
     * 3. 更新 registry/plugins/.manifest.json
     *
     * 返回 null = 成功;非 null = 错误信息。
     */
    suspend fun install(context: Context, asset: RegistryAsset, data: RegistryDownloadData): String? {
        if (data.body.isBlank()) return "插件 body 为空,无法安装"
        return withContext(Dispatchers.IO) {
            runCatching {
                val rawBytes = android.util.Base64.decode(data.body, android.util.Base64.DEFAULT)

                // sha256 校验(fail-closed:checksum 缺失即拒绝,防服务端被攻破后下发无校验恶意插件)
                val expectedCs = (data.content?.checksum ?: asset.content?.checksum)?.removePrefix("sha256:")
                if (expectedCs.isNullOrBlank()) {
                    return@withContext "校验失败:服务端未提供 checksum,已拒绝安装"
                }
                val actual = sha256Hex(rawBytes)
                if (!actual.equals(expectedCs, ignoreCase = true))
                    return@withContext "校验失败:checksum 不符,已拒绝安装"

                val destDir = File(pluginsDir(context), asset.slug).apply { mkdirs() }

                // 判断是 ZIP 还是纯文本 manifest
                val isZip = rawBytes.size >= 4 && rawBytes[0] == 0x50.toByte() && rawBytes[1] == 0x4B.toByte()
                if (isZip) {
                    // ZipInputStream 展开:只允许写 destDir 内(防 path traversal)
                    ZipInputStream(rawBytes.inputStream()).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            val name = entry.name.trimStart('/', '\\')
                            if (name.contains("..")) { zip.closeEntry(); entry = zip.nextEntry; continue }
                            val target = File(destDir, name)
                            if (!target.canonicalPath.startsWith(destDir.canonicalPath))
                                return@withContext "非法路径: ${entry.name}"
                            if (entry.isDirectory) { target.mkdirs() }
                            else {
                                target.parentFile?.mkdirs()
                                FileOutputStream(target).use { out -> zip.copyTo(out) }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                } else {
                    // 纯 manifest JSON(browser-script / tool)
                    File(destDir, "manifest.json").writeBytes(rawBytes)
                }

                // 更新安装清单
                val m = readManifest(context)
                m.plugins.removeAll { it.slug == asset.slug }
                m.plugins.add(
                    InstalledPlugin(
                        id = asset.id, slug = asset.slug,
                        version = data.version.ifBlank { asset.version },
                        name = asset.name.ifBlank { data.name },
                        description = asset.description.ifBlank { data.description },
                        kind = asset.kind,
                        checksum = data.content?.checksum ?: asset.content?.checksum.orEmpty(),
                        installedAt = System.currentTimeMillis(),
                    )
                )
                writeManifest(context, m)
                null
            }.getOrElse { "安装失败:${it.message}" }
        }
    }

    /** 卸载:删目录 + 移出清单。重启后 PluginManager 不再加载。 */
    fun uninstall(context: Context, slug: String) {
        runCatching { File(pluginsDir(context), slug).deleteRecursively() }
        val m = readManifest(context)
        m.plugins.removeAll { it.slug == slug }
        writeManifest(context, m)
    }
}
