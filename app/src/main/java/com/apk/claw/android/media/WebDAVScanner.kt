package com.apk.claw.android.media
import com.apk.claw.android.utils.OctoHttp

import com.apk.claw.android.utils.XLog
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * WebDAV 目录扫描器 —— 通过 HTTP PROPFIND 协议列出 WebDAV 服务器上的文件。
 *
 * 支持：
 * - CloudDrive 2（http://127.0.0.1:19798）
 * - AList（http://127.0.0.1:5244）
 * - 标准 WebDAV 服务器（Synology WebDAV、NextCloud 等）
 *
 * PROPFIND 请求格式：
 * ```xml
 * <?xml version="1.0"?>
 * <d:propfind xmlns:d="DAV:">
 *     <d:prop>
 *         <d:displayname/>
 *         <d:getcontentlength/>
 *         <d:getlastmodified/>
 *         <d:resourcetype/>
 *     </d:prop>
 * </d:propfind>
 * ```
 */
object WebDAVScanner {

    private const val TAG = "WebDAVScanner"

    /** HTTP 客户端（WebDAV 需要较长超时）。
     *  followRedirects=false：防止恶意 WebDAV 服务器通过 301/302 重定向到内网地址
     *  （如 169.254.169.254 元数据服务）实施 SSRF。 */
    private val httpClient = OctoHttp.shared.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    /**
     * 列出 WebDAV 目录中的文件和子目录。
     *
     * @param baseUrl WebDAV 服务器基础 URL（如 http://127.0.0.1:19798）
     * @param path 目录路径（如 /dav/阿里云盘/电影）
     * @param username 用户名（可选）
     * @param password 密码（可选）
     * @return 目录条目列表
     */
    fun listDirectory(
        baseUrl: String,
        path: String,
        username: String = "",
        password: String = ""
    ): List<WebDAVEntry> {
        val url = "$baseUrl$path"
        XLog.d(TAG, "PROPFIND $url")

        // 构建 PROPFIND 请求体
        val propfindBody = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:">
    <d:prop>
        <d:displayname/>
        <d:getcontentlength/>
        <d:getlastmodified/>
        <d:resourcetype/>
    </d:prop>
</d:propfind>"""

        val requestBuilder = Request.Builder()
            .url(url)
            .method("PROPFIND", propfindBody.toRequestBody("application/xml".toMediaType()))
            .header("Depth", "1")
            .header("Content-Type", "application/xml; charset=utf-8")

        // 添加认证头
        if (username.isNotEmpty()) {
            requestBuilder.header("Authorization", Credentials.basic(username, password))
        }

        val request = requestBuilder.build()

        return try {
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                XLog.w(TAG, "PROPFIND failed: ${response.code} ${response.message}")
                return emptyList()
            }

            val body = response.body?.string() ?: return emptyList()
            parseMultiStatus(body, path)
        } catch (e: Exception) {
            XLog.e(TAG, "PROPFIND error: ${e.message}")
            emptyList()
        }
    }

    /**
     * 解析 WebDAV Multi-Status (207) XML 响应。
     *
     * 响应格式示例：
     * ```xml
     * <d:multistatus xmlns:d="DAV:">
     *   <d:response>
     *     <d:href>/dav/ali/movie.mkv</d:href>
     *     <d:propstat>
     *       <d:prop>
     *         <d:displayname>movie.mkv</d:displayname>
     *         <d:getcontentlength>1234567890</d:getcontentlength>
     *         <d:getlastmodified>Mon, 01 Jan 2024 00:00:00 GMT</d:getlastmodified>
     *         <d:resourcetype/>
     *       </d:prop>
     *     </d:propstat>
     *   </d:response>
     * </d:multistatus>
     * ```
     *
     * 不使用 XML 解析库（避免额外依赖），用正则提取关键字段。
     */
    private fun parseMultiStatus(xml: String, requestPath: String): List<WebDAVEntry> {
        val entries = mutableListOf<WebDAVEntry>()

        // 命名空间前缀无关 + 大小写无关：不同服务器用 d:/D:/ns0: 等前缀
        val ci = setOf(RegexOption.IGNORE_CASE)
        fun tag(name: String) = Regex("<(?:\\w+:)?$name>(.*?)</(?:\\w+:)?$name>", ci + RegexOption.DOT_MATCHES_ALL)

        val responsePattern = Regex(
            "<(?:\\w+:)?response>(.*?)</(?:\\w+:)?response>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val responses = responsePattern.findAll(xml)

        for (response in responses) {
            val block = response.groupValues[1]

            // 提取 href（URL 编码的路径）
            val href = tag("href").find(block)
                ?.groupValues?.get(1)?.trim()
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                ?: continue

            // 跳过自身目录条目（href == requestPath）
            val normalizedHref = href.trimEnd('/')
            val normalizedRequest = requestPath.trimEnd('/')
            if (normalizedHref == normalizedRequest) continue

            // 提取 displayname
            val displayName = tag("displayname").find(block)
                ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                ?: href.trimEnd('/').substringAfterLast('/')

            // 提取 contentlength（文件大小）
            val sizeStr = tag("getcontentlength").find(block)?.groupValues?.get(1)?.trim()
            val size = sizeStr?.toLongOrNull() ?: 0

            // 提取 lastmodified
            val lastModified = tag("getlastmodified").find(block)?.groupValues?.get(1)?.trim() ?: ""

            // 判断是否为目录（resourcetype 包含 collection）
            val isDirectory = block.contains("collection", ignoreCase = true)

            if (displayName.isNotEmpty()) {
                entries.add(WebDAVEntry(
                    name = displayName,
                    href = href,
                    isDirectory = isDirectory,
                    size = size,
                    lastModified = lastModified
                ))
            }
        }

        XLog.d(TAG, "Parsed ${entries.size} entries from PROPFIND response")
        return entries
    }

    /**
     * 检查 WebDAV 服务器是否可访问。
     *
     * @param baseUrl WebDAV 服务器 URL
     * @return true 如果服务器响应正常
     */
    fun isServerAvailable(baseUrl: String): Boolean {
        return try {
            val request = Request.Builder()
                .url(baseUrl)
                .method("OPTIONS", null)
                .build()
            val response = httpClient.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 递归列出目录中所有媒体文件（深度优先）。
     *
     * @param baseUrl WebDAV 服务器 URL
     * @param path 起始路径
     * @param maxDepth 最大递归深度
     * @param type 过滤类型："video", "audio", "subtitle", "all"
     * @return 所有匹配的媒体文件
     */
    fun scanRecursive(
        baseUrl: String,
        path: String,
        maxDepth: Int = 5,
        type: String = "all"
    ): List<MediaFile> {
        if (maxDepth <= 0) return emptyList()

        val results = mutableListOf<MediaFile>()
        val entries = listDirectory(baseUrl, path)

        for (entry in entries) {
            if (entry.isDirectory) {
                // 递归子目录
                results.addAll(scanRecursive(baseUrl, entry.href, maxDepth - 1, type))
            } else {
                val ext = entry.name.substringAfterLast('.', "").lowercase()
                val mediaType = when (ext) {
                    in MediaScanner.VIDEO_EXTENSIONS -> MediaType.VIDEO
                    in MediaScanner.AUDIO_EXTENSIONS -> MediaType.AUDIO
                    in MediaScanner.SUBTITLE_EXTENSIONS -> MediaType.SUBTITLE
                    else -> continue
                }

                if (type != "all" && mediaType.name.lowercase() != type) continue

                results.add(MediaFile(
                    name = entry.name,
                    path = "$baseUrl${entry.href}",
                    type = mediaType,
                    sizeBytes = entry.size,
                    extension = ext,
                    modifiedTime = entry.lastModified,
                    isBluRay = ext == "iso" && entry.size > 1_000_000_000
                ))
            }
        }

        return results
    }
}

/**
 * WebDAV 目录条目。
 */
data class WebDAVEntry(
    val name: String,
    val href: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: String
)
