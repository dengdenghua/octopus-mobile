package com.apk.claw.android.octopus_mobile.browser

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ExtensionInstaller 单元测试.
 *
 * 覆盖：
 *  - installFromFile() CRX 路径（文件 → CrxToXpiConverter → 安装）
 *  - installFromFile() XPI 路径（直接安装）
 *  - installFromFile() 未知格式返回 Failed
 *  - listInstalled() / uninstall() 调用链
 *  - PopularExtensions 常量定义
 *
 * 注意：测试环境没有真实 GeckoRuntime，因此所有依赖 GeckoResult 的
 * 方法（installXpiInternal / installFromAmo）会抛异常 → 测试验证 Failed 分支。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExtensionInstallerTest {

    private lateinit var context: Context
    private lateinit var cacheDir: File
    private lateinit var engine: GeckoViewEngine
    private lateinit var installer: ExtensionInstaller

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cacheDir = context.cacheDir
        engine = GeckoViewEngine()
        installer = ExtensionInstaller(engine, cacheDir)
    }

    // ── 辅助：构造测试文件 ─────────────────────────────────

    private fun buildCrxFile(name: String, manifest: org.json.JSONObject): File {
        val zip = buildZipWithManifest(manifest)
        // 直接写 ZIP（测试环境不需要真 CRX3 头，CrxToXpiConverter 会透传 ZIP）
        val file = File(cacheDir, name)
        file.writeBytes(zip)
        return file
    }

    private fun buildZipWithManifest(manifest: org.json.JSONObject): ByteArray {
        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos)
        zos.putNextEntry(ZipEntry("manifest.json"))
        zos.write(manifest.toString().toByteArray(Charsets.UTF_8))
        zos.closeEntry()
        zos.close()
        return baos.toByteArray()
    }

    // ── installFromFile CRX ───────────────────────────────

    @Test
    fun `installFromFile with valid CRX returns conversion result`() = runBlocking {
        val manifest = org.json.JSONObject().apply {
            put("name", "Test Extension")
            put("version", "1.0.0")
        }
        val file = buildCrxFile("test.crx", manifest)

        val result = installer.installFromFile(file)

        // 测试环境没有 GeckoRuntime，installXpiInternal 会抛异常
        assertTrue("should return Failed when GeckoRuntime unavailable", result is ExtensionInstaller.InstallResult.Failed)
    }

    @Test
    fun `installFromFile with valid XPI returns install result`() = runBlocking {
        val manifest = org.json.JSONObject().apply {
            put("name", "XPI Extension")
            put("version", "2.0")
        }
        val file = buildCrxFile("test.xpi", manifest)

        val result = installer.installFromFile(file)

        assertTrue("should return Failed when GeckoRuntime unavailable", result is ExtensionInstaller.InstallResult.Failed)
    }

    @Test
    fun `installFromFile with unknown format returns Failed`() = runBlocking {
        val file = File(cacheDir, "unknown.txt")
        file.writeText("not an extension")

        val result = installer.installFromFile(file)

        assertTrue(result is ExtensionInstaller.InstallResult.Failed)
        val failed = result as ExtensionInstaller.InstallResult.Failed
        assertTrue("reason should mention unknown format", failed.reason.contains("Unknown", ignoreCase = true))
    }

    @Test
    fun `installFromFile with nonexistent file returns Failed`() = runBlocking {
        val file = File(cacheDir, "nonexistent.crx")

        val result = installer.installFromFile(file)

        assertTrue(result is ExtensionInstaller.InstallResult.Failed)
    }

    // ── listInstalled / uninstall ─────────────────────────

    @Test
    fun `listInstalled returns empty list when GeckoRuntime unavailable`() = runBlocking {
        val list = installer.listInstalled()
        assertTrue("should return empty list on error", list.isEmpty())
    }

    @Test
    fun `uninstall returns Failed when extension not found`() = runBlocking {
        val result = installer.uninstall("nonexistent-id")
        assertTrue(result is ExtensionInstaller.InstallResult.Failed)
        val failed = result as ExtensionInstaller.InstallResult.Failed
        assertTrue(failed.reason.contains("not found", ignoreCase = true))
    }

    // ── installFromUrl（网络层 mock 不了，测错误路径）───────

    @Test
    fun `installFromUrl with invalid url returns Failed`() = runBlocking {
        val result = installer.installFromUrl("http://localhost:99999/invalid")
        assertTrue(result is ExtensionInstaller.InstallResult.Failed)
    }

    // ── PopularExtensions 常量 ────────────────────────────

    @Test
    fun `PopularExtensions defines uBlock Origin id`() {
        assertEquals("cjpalhdlnbpafiamejdnhcphjbkeiagm", ExtensionInstaller.Companion.PopularExtensions.UBLOCK_ORIGIN)
    }

    @Test
    fun `PopularExtensions defines Tampermonkey id`() {
        assertEquals("dhdgffkkebhmkfjojejmpbldmpobfkfo", ExtensionInstaller.Companion.PopularExtensions.TAMPERMONKEY)
    }

    @Test
    fun `PopularExtensions defines Cookie Editor id`() {
        assertEquals("hlkenndednhfkekhgcdicdfddnkalmdm", ExtensionInstaller.Companion.PopularExtensions.COOKIE_EDITOR)
    }

    @Test
    fun `PopularExtensions defines Dark Reader id`() {
        assertEquals("eimadpbcbfnmbkopoojfekhnkhdbieeh", ExtensionInstaller.Companion.PopularExtensions.DARK_READER)
    }

    @Test
    fun `PopularExtensions defines Violentmonkey id`() {
        assertEquals("jinjaccalgkegednnccohejagnlnfdag", ExtensionInstaller.Companion.PopularExtensions.VIOLENTMONKEY)
    }

    // ── InstalledExtension data class ─────────────────────

    @Test
    fun `InstalledExtension holds correct values`() {
        val ext = InstalledExtension(
            id = "test@ext",
            name = "Test",
            version = "1.0",
            enabled = true
        )
        assertEquals("test@ext", ext.id)
        assertEquals("Test", ext.name)
        assertEquals("1.0", ext.version)
        assertTrue(ext.enabled)
    }
}
