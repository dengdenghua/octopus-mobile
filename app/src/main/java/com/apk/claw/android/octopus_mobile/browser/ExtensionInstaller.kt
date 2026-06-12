package com.apk.claw.android.octopus_mobile.browser

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 扩展安装器 —— agent 调用入口.
 *
 * agent 说"装个 uBlock Origin" → 此类负责：
 *   1. 下载 CRX（从 Chrome Web Store 或自托管 URL）
 *   2. 转 XPI（CrxToXpiConverter）
 *   3. 安装到 GeckoView（GeckoViewEngine.installXpi）
 *
 * 也支持直接装 XPI / 从 AMO 安装.
 */
class ExtensionInstaller(
    private val engine: GeckoViewEngine,
    private val cacheDir: File,
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 安装结果.
     */
    sealed class InstallResult {
        data class Success(
            val extensionId: String,
            val extensionName: String,
            val extensionVersion: String,
        ) : InstallResult()

        data class Failed(val reason: String) : InstallResult()
    }

    /**
     * 从 Chrome Web Store 下载并安装 CRX 扩展.
     *
     * @param extensionId Chrome Web Store 扩展 ID（如 "cjpalhdlnbpafiamejdnhcphjbkeiagm" = uBlock Origin）
     */
    suspend fun installFromChromeWebStore(extensionId: String): InstallResult {
        return withContext(Dispatchers.IO) {
            try {
                // Chrome Web Store CRX 下载 URL
                val crxUrl = "https://clients2.google.com/service/update2/crx?response=redirect&prodversion=125.0.0.0&acceptformat=crx3&x=id%3D$extensionId%26uc"
                Log.d(TAG, "Downloading CRX from Chrome Web Store: $extensionId")

                val crxBytes = download(crxUrl)
                Log.d(TAG, "Downloaded CRX: ${crxBytes.size} bytes")

                installCrx(crxBytes)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to install from Chrome Web Store: $extensionId", e)
                InstallResult.Failed("Download failed: ${e.message}")
            }
        }
    }

    /**
     * 从 URL 下载并安装（CRX 或 XPI 自动检测）.
     *
     * @param url 扩展下载 URL（.crx 或 .xpi）
     */
    suspend fun installFromUrl(url: String): InstallResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Downloading extension from: $url")
                val bytes = download(url)

                when {
                    CrxToXpiConverter.isCrx3(bytes) -> installCrx(bytes)
                    CrxToXpiConverter.isXpi(bytes) -> installXpi(bytes)
                    else -> InstallResult.Failed("Unknown file format (not CRX3 or XPI)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to install from URL: $url", e)
                InstallResult.Failed("Download failed: ${e.message}")
            }
        }
    }

    /**
     * 从本地文件安装（CRX 或 XPI）.
     */
    suspend fun installFromFile(file: File): InstallResult {
        return withContext(Dispatchers.IO) {
            try {
                val bytes = file.readBytes()
                when {
                    CrxToXpiConverter.isCrx3(bytes) -> installCrx(bytes)
                    CrxToXpiConverter.isXpi(bytes) -> installXpi(bytes)
                    else -> InstallResult.Failed("Unknown file format: ${file.name}")
                }
            } catch (e: Exception) {
                InstallResult.Failed("File read failed: ${e.message}")
            }
        }
    }

    /**
     * 从 AMO (addons.mozilla.org) URL 安装.
     *
     * GeckoView 原生支持 AMO URL，不需要转换.
     */
    suspend fun installFromAmo(amoUrl: String): InstallResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Installing from AMO: $amoUrl")
                val result = engine.installExtensionFromUrl(amoUrl)
                val ext = result.await()  // GeckoResult → suspend
                InstallResult.Success(
                    extensionId = ext.id,
                    extensionName = ext.metaData?.name ?: "Unknown",
                    extensionVersion = ext.metaData?.version ?: "0",
                )
            } catch (e: Exception) {
                InstallResult.Failed("AMO install failed: ${e.message}")
            }
        }
    }

    /**
     * 列出已安装的扩展.
     */
    suspend fun listInstalled(): List<InstalledExtension> {
        return withContext(Dispatchers.IO) {
            try {
                val result = engine.listExtensions()
                val extensions = result.await()
                extensions.map { ext ->
                    InstalledExtension(
                        id = ext.id,
                        name = ext.metaData?.name ?: "Unknown",
                        version = ext.metaData?.version ?: "0",
                        enabled = ext.metaData?.enabled ?: true,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to list extensions", e)
                emptyList()
            }
        }
    }

    /**
     * 卸载扩展.
     */
    suspend fun uninstall(extensionId: String): InstallResult {
        return withContext(Dispatchers.IO) {
            try {
                val list = try {
                    engine.listExtensions().await()
                } catch (e: Exception) {
                    // Runtime 不可用 / 列表失败 → 视作没有已安装扩展
                    emptyList()
                }
                val target = list.find { it.id == extensionId }
                    ?: return@withContext InstallResult.Failed("Extension not found: $extensionId")
                engine.uninstallExtension(target).await()
                InstallResult.Success(extensionId, target.metaData?.name ?: "", target.metaData?.version ?: "")
            } catch (e: Exception) {
                InstallResult.Failed("Uninstall failed: ${e.message}")
            }
        }
    }

    // ── 内部方法 ──────────────────────────────────────

    private suspend fun installCrx(crxBytes: ByteArray): InstallResult {
        return try {
            val conversion = CrxToXpiConverter.convert(crxBytes)
            installXpiInternal(conversion.xpiBytes, conversion.extensionId)
        } catch (e: Exception) {
            Log.e(TAG, "CRX conversion failed", e)
            InstallResult.Failed("CRX→XPI conversion failed: ${e.message}")
        }
    }

    private suspend fun installXpi(xpiBytes: ByteArray): InstallResult {
        return try {
            // XPI 直接装，但需要提取 ID
            val id = "ext-${System.currentTimeMillis()}"
            installXpiInternal(xpiBytes, id)
        } catch (e: Exception) {
            InstallResult.Failed("XPI install failed: ${e.message}")
        }
    }

    private suspend fun installXpiInternal(xpiBytes: ByteArray, extensionId: String): InstallResult {
        return try {
            val result = engine.installXpi(xpiBytes, extensionId)
            val ext = result.await()
            InstallResult.Success(
                extensionId = ext.id,
                extensionName = ext.metaData?.name ?: extensionId,
                extensionVersion = ext.metaData?.version ?: "0",
            )
        } catch (e: Exception) {
            InstallResult.Failed("Install failed: ${e.message}")
        }
    }

    private fun download(url: String): ByteArray {
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw RuntimeException("HTTP ${response.code}: ${response.message}")
        }
        val body = response.body ?: throw RuntimeException("Empty response body")
        return body.bytes()
    }

    companion object {
        private const val TAG = "ExtensionInstaller"

        /**
         * 常用扩展 ID（Chrome Web Store）.
         */
        object PopularExtensions {
            const val UBLOCK_ORIGIN = "cjpalhdlnbpafiamejdnhcphjbkeiagm"
            const val TAMPERMONKEY = "dhdgffkkebhmkfjojejmpbldmpobfkfo"
            const val COOKIE_EDITOR = "hlkenndednhfkekhgcdicdfddnkalmdm"
            const val DARK_READER = "eimadpbcbfnmbkopoojfekhnkhdbieeh"
            const val VIOLENTMONKEY = "jinjaccalgkegednnccohejagnlnfdag"
        }
    }
}

/**
 * 已安装扩展信息.
 */
data class InstalledExtension(
    val id: String,
    val name: String,
    val version: String,
    val enabled: Boolean,
)

/**
 * GeckoResult suspend 扩展 —— 把回调式 API 变成协程友好.
 */
private suspend fun <T> GeckoResult<T>.await(): T {
    return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        this.then({ value ->
            if (value != null) {
                cont.resume(value) {}
            } else {
                cont.cancel(IllegalStateException("GeckoResult returned null"))
            }
            GeckoResult<Void>()
        }, { throwable ->
            cont.cancel(throwable)
            GeckoResult<Void>()
        })
    }
}

// GeckoResult import（编译期占位，实际由 GeckoView AAR 提供）
private typealias GeckoResult<T> = org.mozilla.geckoview.GeckoResult<T>
