package com.apk.claw.android.media

import com.apk.claw.android.shizuku.ShizukuShellService
import com.apk.claw.android.utils.XLog
import com.tencent.mmkv.MMKV

/**
 * CloudDrive 2 (CD2) 管理器 —— 管理 CD2 服务连接。
 *
 * 两种部署模式：
 *
 * 1. **本机模式**（TV 盒子）：CD2 二进制跑在同一台设备上，通过 Shizuku 管理进程
 *    - 默认地址：http://127.0.0.1:19798
 *    - Shizuku 启动/停止 CD2 进程
 *
 * 2. **远程模式**（手机连 NAS/PC）：CD2 跑在另一台设备上，手机通过局域网 WebDAV 访问
 *    - 地址示例：http://192.168.1.100:19798
 *    - 只需配置 URL，不管理进程
 *
 * 支持的网盘：
 * - 阿里云盘（Open / Scan）
 * - 115 网盘
 * - 百度网盘
 * - 夸克网盘
 * - PikPak
 * - OneDrive / Google Drive
 * - 天翼云盘 / 迅雷网盘
 *
 * WebDAV 地址：http(s)://<host>:19798/dav/<网盘名>/<路径>
 */
object CloudDriveManager {

    private const val TAG = "CloudDriveManager"
    private const val MMKV_ID = "cloud_drive"
    private const val KEY_SERVER_URL = "cd2_server_url"
    private const val KEY_USERNAME = "cd2_username"
    private const val KEY_PASSWORD = "cd2_password"

    /** CD2 二进制路径（本机模式） */
    private const val CD2_DIR = "/sdcard/CloudDrive2"
    private const val CD2_BIN = "$CD2_DIR/clouddrive"
    private const val CD2_DATA = "$CD2_DIR/data"

    /** 默认本机 CD2 端口 */
    const val DEFAULT_PORT = 19798
    const val DEFAULT_URL = "http://127.0.0.1:$DEFAULT_PORT"

    private val mmkv: MMKV by lazy { MMKV.mmkvWithID(MMKV_ID, MMKV.SINGLE_PROCESS_MODE) }

    // ======================== 服务器配置 ========================

    /**
     * 获取当前配置的 CD2 服务器 URL。
     * 默认 http://127.0.0.1:19798（本机模式）。
     */
    fun getServerUrl(): String {
        return mmkv.getString(KEY_SERVER_URL, DEFAULT_URL) ?: DEFAULT_URL
    }

    /**
     * 设置 CD2 服务器 URL。
     *
     * 示例：
     * - 本机 TV 盒子：http://127.0.0.1:19798
     * - 远程 NAS：http://192.168.1.100:19798
     * - HTTPS：https://cd2.example.com
     *
     * @param url 服务器 URL（不含路径，如 http://host:port）
     */
    fun setServerUrl(url: String) {
        val normalized = url.trimEnd('/')
        mmkv.putString(KEY_SERVER_URL, normalized)
        XLog.i(TAG, "CD2 server URL set to: $normalized")
    }

    /**
     * 设置认证信息（可选，CD2 默认无需认证）。
     */
    fun setCredentials(username: String, password: String) {
        mmkv.putString(KEY_USERNAME, username)
        mmkv.putString(KEY_PASSWORD, password)
    }

    fun getUsername(): String = mmkv.getString(KEY_USERNAME, "") ?: ""
    fun getPassword(): String = mmkv.getString(KEY_PASSWORD, "") ?: ""

    // ======================== 进程管理 ========================

    /**
     * 检查 CD2 服务是否可访问。
     * 通过 WebDAV OPTIONS 请求检测服务器是否响应。
     */
    fun isRunning(): Boolean {
        return WebDAVScanner.isServerAvailable(getServerUrl())
    }

    /**
     * 判断是否为本机模式（127.0.0.1 或 localhost）。
     */
    fun isLocalMode(): Boolean {
        val url = getServerUrl()
        return url.contains("127.0.0.1") || url.contains("localhost")
    }

    /**
     * 检查 CD2 二进制是否存在。
     */
    fun isInstalled(): Boolean {
        val result = ShizukuShellService.exec("test -f $CD2_BIN && echo yes || echo no")
        return result?.stdout?.trim() == "yes"
    }

    /**
     * 启动 CD2 服务。
     * - 本机模式：通过 Shizuku 启动本地进程
     * - 远程模式：仅检测远程服务器是否可访问
     */
    fun start(): String {
        if (isRunning()) {
            return "CloudDrive2 is already running at ${getServerUrl()}"
        }

        // 远程模式：不管理进程，只检测连通性
        if (!isLocalMode()) {
            return "Remote CloudDrive2 at ${getServerUrl()} is not reachable. " +
                "Please ensure the CD2 server is running on the remote host."
        }

        // 本机模式：通过 Shizuku 启动

        if (!isInstalled()) {
            return "CloudDrive2 binary not found at $CD2_BIN. " +
                "Please download CloudDrive2 for Android ARM64 from https://www.clouddrive2.com " +
                "and place the binary at $CD2_BIN"
        }

        // 创建数据目录
        ShizukuShellService.exec("mkdir -p $CD2_DATA")

        // 后台启动 CD2
        val result = ShizukuShellService.exec(
            "cd $CD2_DIR && nohup $CD2_BIN --data $CD2_DATA > $CD2_DIR/clouddrive.log 2>&1 & echo \$!"
        )

        if (result == null) {
            return "Failed to start CloudDrive2: Shizuku not available"
        }

        if (result.exitCode != 0) {
            return "Failed to start CloudDrive2: ${result.stderr}"
        }

        // 等待服务启动（最多 5 秒）
        for (i in 1..10) {
            Thread.sleep(500)
            if (isRunning()) {
                XLog.i(TAG, "CloudDrive2 started at ${getServerUrl()}")
                return "CloudDrive2 started successfully at ${getServerUrl()}/dav/"
            }
        }

        return "CloudDrive2 process started but not yet responding. Check ${getServerUrl()} in a few seconds."
    }

    /**
     * 停止 CD2 服务（仅本机模式有效）。
     */
    fun stop(): String {
        if (!isLocalMode()) {
            return "Cannot stop remote CloudDrive2. Manage it on the remote host."
        }
        if (!isRunning()) {
            return "CloudDrive2 is not running"
        }

        val result = ShizukuShellService.exec("pkill -f clouddrive")
        return if (result != null && result.exitCode == 0) {
            XLog.i(TAG, "CloudDrive2 stopped")
            "CloudDrive2 stopped"
        } else {
            "Failed to stop CloudDrive2: ${result?.stderr ?: "unknown error"}"
        }
    }

    /**
     * 获取 CD2 状态信息。
     */
    fun getStatus(): Map<String, Any> {
        val installed = isInstalled()
        val running = isRunning()
        return mapOf(
            "installed" to installed,
            "running" to running,
            "binary_path" to CD2_BIN,
            "data_dir" to CD2_DATA,
            "webdav_url" to "${getServerUrl()}/dav/",
            "web_ui" to getServerUrl()
        )
    }

    // ======================== 网盘目录操作 ========================

    /**
     * 列出已挂载的网盘。
     *
     * @return 网盘名称列表
     */
    fun listMountedDrives(): List<CloudDrive> {
        val drives = mutableListOf<CloudDrive>()

        try {
            // 通过 WebDAV PROPFIND 根目录获取已挂载的网盘
            val serverUrl = getServerUrl()
            val entries = WebDAVScanner.listDirectory(serverUrl, "/dav", getUsername(), getPassword())
            for (entry in entries) {
                if (entry.isDirectory) {
                    drives.add(CloudDrive(
                        name = entry.name,
                        webdavPath = "/dav/${entry.name}",
                        url = "$serverUrl/dav/${entry.name}"
                    ))
                }
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to list drives: ${e.message}")
        }

        return drives
    }

    /**
     * 列出网盘目录中的文件。
     *
     * @param driveName 网盘名称（如 "阿里云盘Open"）
     * @param path 网盘内路径（如 "/电影"）
     * @return 文件列表
     */
    fun listFiles(driveName: String, path: String = "/"): List<MediaFile> {
        val webdavPath = "/dav/$driveName$path"
        val serverUrl = getServerUrl()
        val entries = WebDAVScanner.listDirectory(serverUrl, webdavPath, getUsername(), getPassword())

        return entries.mapNotNull { entry ->
            if (entry.isDirectory) return@mapNotNull null  // 跳过目录，只返回媒体文件

            val ext = entry.name.substringAfterLast('.', "").lowercase()
            val mediaType = when (ext) {
                in MediaScanner.VIDEO_EXTENSIONS -> MediaType.VIDEO
                in MediaScanner.AUDIO_EXTENSIONS -> MediaType.AUDIO
                in MediaScanner.SUBTITLE_EXTENSIONS -> MediaType.SUBTITLE
                else -> return@mapNotNull null
            }

            MediaFile(
                name = entry.name,
                path = "$serverUrl$webdavPath/${entry.name}",
                type = mediaType,
                sizeBytes = entry.size,
                extension = ext,
                modifiedTime = entry.lastModified,
                isBluRay = ext == "iso" && entry.size > 1_000_000_000
            )
        }
    }

    /**
     * 构建网盘文件的播放 URL。
     *
     * @param driveName 网盘名称
     * @param filePath 网盘内文件路径
     * @return mpv 可播放的 WebDAV URL
     */
    fun buildPlayUrl(driveName: String, filePath: String): String {
        return "${getServerUrl()}/dav/$driveName$filePath"
    }

    /**
     * 在网盘目录中搜索匹配的字幕文件。
     *
     * @param driveName 网盘名称
     * @param dirPath 目录路径
     * @param videoName 视频文件名（不含扩展名）
     * @return 字幕文件 URL，未找到返回 null
     */
    fun findSubtitle(driveName: String, dirPath: String, videoName: String): String? {
        val webdavPath = "/dav/$driveName$dirPath"
        val entries = WebDAVScanner.listDirectory(getServerUrl(), webdavPath, getUsername(), getPassword())

        for (entry in entries) {
            if (entry.isDirectory) continue
            val ext = entry.name.substringAfterLast('.', "").lowercase()
            if (ext !in MediaScanner.SUBTITLE_EXTENSIONS) continue

            val subName = entry.name.substringBeforeLast('.')
            if (subName.startsWith(videoName) || videoName.startsWith(subName)) {
                return "${getServerUrl()}$webdavPath/${entry.name}"
            }
        }
        return null
    }
}

/**
 * 云盘挂载信息。
 */
data class CloudDrive(
    val name: String,
    val webdavPath: String,
    val url: String
)
