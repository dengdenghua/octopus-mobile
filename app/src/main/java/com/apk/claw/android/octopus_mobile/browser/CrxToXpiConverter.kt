package com.apk.claw.android.octopus_mobile.browser

import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * CRX → XPI 转换器.
 *
 * Chrome 扩展 (.crx) 和 Firefox 扩展 (.xpi) 本质上都是 ZIP 文件，
 * 只是 CRX3 多了一个头部。WebExtensions API 是同一套。
 *
 * 转换流程：
 *   CRX3 文件 → 剥 CRX3 头 → 得到 ZIP → 适配 manifest.json → 重新打包为 XPI
 *
 * agent 调用链：
 *   用户说"装个 uBlock" → ExtensionInstaller.download() → CrxToXpiConverter.convert() → GeckoViewEngine.installXpi()
 *
 * CRX3 格式：
 *   magic(4B "Cr24") + version(4B uint32 LE) + header_len(4B uint32 LE) + header + ZIP data
 */
object CrxToXpiConverter {

    private const val TAG = "CrxToXpiConverter"
    private const val CRX3_MAGIC = 0x43723234  // "Cr24" in little-endian

    /**
     * 转换结果.
     */
    data class ConversionResult(
        val xpiBytes: ByteArray,
        val extensionId: String,
        val extensionName: String,
        val extensionVersion: String,
    )

    /**
     * 将 CRX3 字节转换为 XPI 字节.
     *
     * @param crxBytes CRX3 文件的原始字节
     * @return 转换后的 XPI 字节 + 扩展元信息
     * @throws IllegalArgumentException 如果不是有效的 CRX3 文件
     */
    fun convert(crxBytes: ByteArray): ConversionResult {
        Log.d(TAG, "Converting CRX3 (${crxBytes.size} bytes) → XPI")

        // 1. 剥 CRX3 头
        val zipBytes = stripCrx3Header(crxBytes)

        // 2. 解析 manifest.json
        val manifest = parseManifest(zipBytes)
        val extId = manifest.optString("id",
            manifest.optString("name", "unknown").replace(Regex("[^a-zA-Z0-9]"), "")
        )
        val extName = manifest.optString("name", "Unknown Extension")
        val extVersion = manifest.optString("version", "0.0.0")

        // 3. 适配 manifest（Chrome → Firefox 兼容）
        val adaptedZip = adaptManifest(zipBytes, manifest)

        Log.d(TAG, "Conversion done: $extName v$extVersion ($extId), XPI ${adaptedZip.size} bytes")

        return ConversionResult(
            xpiBytes = adaptedZip,
            extensionId = extId,
            extensionName = extName,
            extensionVersion = extVersion,
        )
    }

    /**
     * 检测是否是 CRX3 格式.
     */
    fun isCrx3(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val magic = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return magic == CRX3_MAGIC
    }

    /**
     * 检测是否已经是 XPI/ZIP 格式.
     */
    fun isXpi(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        // ZIP magic: PK\x03\x04
        return bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
    }

    // ── 内部实现 ──────────────────────────────────────

    /**
     * 剥 CRX3 头部，返回纯 ZIP 数据.
     *
     * CRX3 结构：
     *   offset 0:  magic (4 bytes, "Cr24")
     *   offset 4:  version (4 bytes, uint32 LE, should be 3)
     *   offset 8:  header_len (4 bytes, uint32 LE)
     *   offset 12: header (header_len bytes, protobuf)
     *   offset 12+header_len: ZIP data
     */
    private fun stripCrx3Header(crxBytes: ByteArray): ByteArray {
        if (!isCrx3(crxBytes)) {
            // 可能已经是 ZIP/XPI，直接返回
            if (isXpi(crxBytes)) {
                Log.d(TAG, "Input is already ZIP/XPI format, no conversion needed")
                return crxBytes
            }
            throw IllegalArgumentException("Not a valid CRX3 file (magic mismatch)")
        }

        val buf = ByteBuffer.wrap(crxBytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.int       // 0-3: magic
        val version = buf.int     // 4-7: version
        val headerLen = buf.int   // 8-11: header length

        Log.d(TAG, "CRX3 header: magic=0x${magic.toString(16)}, version=$version, headerLen=$headerLen")

        val zipOffset = 12 + headerLen
        if (zipOffset >= crxBytes.size) {
            throw IllegalArgumentException("CRX3 header extends beyond file (offset=$zipOffset, size=${crxBytes.size})")
        }

        return crxBytes.copyOfRange(zipOffset, crxBytes.size)
    }

    /**
     * 从 ZIP 中解析 manifest.json.
     */
    private fun parseManifest(zipBytes: ByteArray): JSONObject {
        val zis = ZipInputStream(ByteArrayInputStream(zipBytes))
        var manifestJson: JSONObject? = null

        generateSequence { zis.nextEntry }
            .filter { it.name == "manifest.json" }
            .forEach {
                val content = zis.readBytes().toString(Charsets.UTF_8)
                manifestJson = JSONObject(content)
            }
        zis.close()

        return manifestJson
            ?: throw IllegalArgumentException("manifest.json not found in extension package")
    }

    /**
     * 适配 manifest.json 以兼容 Firefox.
     *
     * 主要改动：
     *  1. 如果没有 `browser_specific_settings.gecko`，加上（Firefox 要求）
     *  2. `background.service_worker` → `background.scripts`（Firefox MV2 兼容）
     *  3. `action` → `browser_action`（Firefox MV2 兼容）
     *
     * 注意：大部分 Chrome 扩展的 manifest.json 不需要改就能在 Firefox 跑。
     * 这里只处理最常见的兼容性问题。
     */
    private fun adaptManifest(zipBytes: ByteArray, originalManifest: JSONObject): ByteArray {
        val manifest = JSONObject(originalManifest.toString())  // 深拷贝

        // 1. 加 browser_specific_settings（Firefox 要求）
        if (!manifest.has("browser_specific_settings")) {
            val bss = JSONObject()
            val gecko = JSONObject()
            gecko.put("strict_min_version", "113.0")  // GeckoView 125 对应 Firefox 125
            bss.put("gecko", gecko)
            manifest.put("browser_specific_settings", bss)
        }

        // 2. MV3 的 background.service_worker → background.scripts（如果 Firefox 不支持）
        //    Firefox 126+ 已支持 MV3 service_worker，但加个 fallback
        if (manifest.has("background")) {
            val bg = manifest.getJSONObject("background")
            if (bg.has("service_worker") && !bg.has("scripts")) {
                // Firefox MV3 支持 service_worker，但加个兼容
                val scripts = org.json.JSONArray()
                scripts.put(bg.getString("service_worker"))
                bg.put("scripts", scripts)
            }
        }

        // 3. 重新打包 ZIP，替换 manifest.json
        return repackZip(zipBytes, manifest)
    }

    /**
     * 重新打包 ZIP，替换 manifest.json.
     */
    private fun repackZip(zipBytes: ByteArray, newManifest: JSONObject): ByteArray {
        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos)
        val zis = ZipInputStream(ByteArrayInputStream(zipBytes))

        // 先写新的 manifest.json
        zos.putNextEntry(ZipEntry("manifest.json"))
        zos.write(newManifest.toString(2).toByteArray(Charsets.UTF_8))
        zos.closeEntry()

        // 复制其他文件（跳过原 manifest.json）
        generateSequence { zis.nextEntry }
            .filter { it.name != "manifest.json" }
            .forEach { entry ->
                zos.putNextEntry(ZipEntry(entry.name))
                zis.copyTo(zos)
                zos.closeEntry()
            }

        zis.close()
        zos.close()
        return baos.toByteArray()
    }
}
