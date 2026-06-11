package com.apk.claw.android.octopus_mobile.browser

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * CrxToXpiConverter 单元测试.
 *
 * 覆盖：
 *  - isCrx3() 正确识别 CRX3 magic
 *  - isXpi() 正确识别 ZIP magic
 *  - convert() 剥 CRX3 头 + 解析 manifest + 适配 Firefox
 *  - convert() 对已 ZIP/XPI 输入直接透传
 *  - 无效输入抛出 IllegalArgumentException
 *  - manifest 适配：加 browser_specific_settings、background scripts 兼容
 */
class CrxToXpiConverterTest {

    companion object {
        // CRX3 magic "Cr24" little-endian
        private const val CRX3_MAGIC_LE = 0x43723234

        /** 构造一个最小有效的 CRX3 文件（magic + version + header_len + 空 header + ZIP） */
        private fun buildFakeCrx3(zipBytes: ByteArray): ByteArray {
            val headerLen = 0  // 空 protobuf header
            val out = ByteArrayOutputStream()
            // magic (4 bytes LE)
            out.write(byteArrayOf(
                (CRX3_MAGIC_LE ushr 0).toByte(),
                (CRX3_MAGIC_LE ushr 8).toByte(),
                (CRX3_MAGIC_LE ushr 16).toByte(),
                (CRX3_MAGIC_LE ushr 24).toByte()
            ))
            // version = 3 (uint32 LE)
            out.write(byteArrayOf(0x03, 0x00, 0x00, 0x00))
            // header_len (uint32 LE)
            out.write(byteArrayOf(
                (headerLen ushr 0).toByte(),
                (headerLen ushr 8).toByte(),
                (headerLen ushr 16).toByte(),
                (headerLen ushr 24).toByte()
            ))
            // ZIP data
            out.write(zipBytes)
            return out.toByteArray()
        }

        /** 构造一个最小 ZIP（含 manifest.json） */
        private fun buildZipWithManifest(manifestJson: JSONObject): ByteArray {
            val baos = ByteArrayOutputStream()
            val zos = ZipOutputStream(baos)
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifestJson.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.close()
            return baos.toByteArray()
        }
    }

    // ── 格式检测 ──────────────────────────────────────────

    @Test
    fun `isCrx3 returns true for valid CRX3`() {
        val manifest = JSONObject().apply {
            put("name", "TestExt")
            put("version", "1.0")
        }
        val zip = buildZipWithManifest(manifest)
        val crx = buildFakeCrx3(zip)
        assertTrue(CrxToXpiConverter.isCrx3(crx))
    }

    @Test
    fun `isCrx3 returns false for plain ZIP`() {
        val manifest = JSONObject().apply {
            put("name", "TestExt")
            put("version", "1.0")
        }
        val zip = buildZipWithManifest(manifest)
        assertFalse(CrxToXpiConverter.isCrx3(zip))
    }

    @Test
    fun `isCrx3 returns false for too short input`() {
        assertFalse(CrxToXpiConverter.isCrx3(byteArrayOf(0x43, 0x72)))
    }

    @Test
    fun `isXpi returns true for ZIP`() {
        val manifest = JSONObject().apply {
            put("name", "TestExt")
            put("version", "1.0")
        }
        val zip = buildZipWithManifest(manifest)
        assertTrue(CrxToXpiConverter.isXpi(zip))
    }

    @Test
    fun `isXpi returns false for random bytes`() {
        assertFalse(CrxToXpiConverter.isXpi(byteArrayOf(0x00, 0x01, 0x02, 0x03)))
    }

    // ── 转换流程 ──────────────────────────────────────────

    @Test
    fun `convert strips CRX3 header and returns XPI bytes`() {
        val manifest = JSONObject().apply {
            put("name", "uBlock Origin")
            put("version", "1.50.0")
            put("manifest_version", 2)
        }
        val zip = buildZipWithManifest(manifest)
        val crx = buildFakeCrx3(zip)

        val result = CrxToXpiConverter.convert(crx)

        assertEquals("ublockorigin", result.extensionId)
        assertEquals("uBlock Origin", result.extensionName)
        assertEquals("1.50.0", result.extensionVersion)
        assertTrue("XPI should be valid ZIP", CrxToXpiConverter.isXpi(result.xpiBytes))
    }

    @Test
    fun `convert passes through plain ZIP input`() {
        val manifest = JSONObject().apply {
            put("name", "PlainZip")
            put("version", "2.0")
        }
        val zip = buildZipWithManifest(manifest)

        val result = CrxToXpiConverter.convert(zip)

        assertEquals("PlainZip", result.extensionName)
        assertTrue(CrxToXpiConverter.isXpi(result.xpiBytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `convert throws for invalid format`() {
        CrxToXpiConverter.convert(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `convert throws when manifest missing`() {
        // ZIP 里没有 manifest.json
        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos)
        zos.putNextEntry(ZipEntry("readme.txt"))
        zos.write("hello".toByteArray())
        zos.closeEntry()
        zos.close()
        CrxToXpiConverter.convert(baos.toByteArray())
    }

    // ── manifest 适配 ─────────────────────────────────────

    @Test
    fun `convert adds browser_specific_settings for Firefox`() {
        val manifest = JSONObject().apply {
            put("name", "CompatTest")
            put("version", "1.0")
        }
        val zip = buildZipWithManifest(manifest)
        val result = CrxToXpiConverter.convert(zip)

        // 重新解析结果中的 manifest
        val parsed = parseManifestFromZip(result.xpiBytes)
        assertTrue("should add browser_specific_settings", parsed.has("browser_specific_settings"))
        val bss = parsed.getJSONObject("browser_specific_settings")
        assertTrue(bss.has("gecko"))
        val gecko = bss.getJSONObject("gecko")
        assertEquals("113.0", gecko.getString("strict_min_version"))
    }

    @Test
    fun `convert adds background scripts fallback for service_worker`() {
        val manifest = JSONObject().apply {
            put("name", "SWTest")
            put("version", "1.0")
            put("background", JSONObject().apply {
                put("service_worker", "bg.js")
            })
        }
        val zip = buildZipWithManifest(manifest)
        val result = CrxToXpiConverter.convert(zip)

        val parsed = parseManifestFromZip(result.xpiBytes)
        val bg = parsed.getJSONObject("background")
        assertTrue("should add scripts fallback", bg.has("scripts"))
    }

    @Test
    fun `convert does not duplicate existing browser_specific_settings`() {
        val manifest = JSONObject().apply {
            put("name", "AlreadyHasBSS")
            put("version", "1.0")
            put("browser_specific_settings", JSONObject().apply {
                put("gecko", JSONObject().apply {
                    put("id", "test@example.com")
                })
            })
        }
        val zip = buildZipWithManifest(manifest)
        val result = CrxToXpiConverter.convert(zip)

        val parsed = parseManifestFromZip(result.xpiBytes)
        val bss = parsed.getJSONObject("browser_specific_settings")
        val gecko = bss.getJSONObject("gecko")
        // 原始 id 应保留，不应被覆盖
        assertTrue(gecko.has("id"))
        assertEquals("test@example.com", gecko.getString("id"))
    }

    // ── 工具方法 ──────────────────────────────────────────

    private fun parseManifestFromZip(zipBytes: ByteArray): JSONObject {
        val zis = java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(zipBytes))
        var result: JSONObject? = null
        generateSequence { zis.nextEntry }
            .filter { it.name == "manifest.json" }
            .forEach {
                val content = zis.readBytes().toString(Charsets.UTF_8)
                result = JSONObject(content)
            }
        zis.close()
        return result ?: throw AssertionError("manifest.json not found in converted XPI")
    }
}
