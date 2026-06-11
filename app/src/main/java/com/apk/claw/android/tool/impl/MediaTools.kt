package com.apk.claw.android.tool.impl

import com.apk.claw.android.media.MediaScanner
import com.apk.claw.android.media.MpvController
import com.apk.claw.android.media.PlayerActivity
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.ClawApplication

/**
 * 媒体播放工具 —— AI Agent 控制 mpv 播放器。
 *
 * 支持操作：
 * - play: 播放媒体文件（本地/网络/蓝光 ISO）
 * - pause / resume / stop: 播放控制
 * - seek: 跳转到指定位置
 * - seek_relative: 快进/快退
 * - subtitle: 字幕控制（切换/加载/大小）
 * - audio_track: 切换音轨
 * - volume: 调节音量
 * - info: 获取当前播放信息
 * - scan: 扫描目录中的媒体文件
 * - find_subtitle: 查找匹配字幕
 * - mount_cloud: 启动/管理 CloudDrive2 网盘服务
 * - list_cloud: 列出网盘目录中的媒体文件
 * - play_cloud: 播放网盘中的媒体文件
 * - cloud_status: 获取网盘服务状态
 */
class MediaTools : BaseTool() {

    override fun getName(): String = "media_player"

    override fun getDisplayName(): String = if (useChineseDescription) "媒体播放器" else "Media Player"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "action",
            "string",
            "Action: 'play', 'pause', 'resume', 'stop', 'seek', 'seek_relative', 'subtitle', 'audio_track', 'volume', 'info', 'scan', 'find_subtitle', 'mount_cloud', 'list_cloud', 'play_cloud', 'cloud_status'.",
            true
        ),
        ToolParameter(
            "path",
            "string",
            "Media file path or URL for 'play', directory path for 'scan', or cloud drive name for 'list_cloud'/'play_cloud'.",
            false
        ),
        ToolParameter(
            "value",
            "string",
            "Value for seek (ms), volume (0-150), subtitle track, audio track, subtitle size, seek_relative offset, or cloud file path for 'play_cloud'.",
            false
        ),
        ToolParameter(
            "subtitle",
            "string",
            "External subtitle file path for 'play'.",
            false
        ),
        ToolParameter(
            "type",
            "string",
            "Media type filter for 'scan': 'video', 'audio', 'subtitle', 'all' (default).",
            false
        )
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val action = requireString(params, "action")

        return when (action) {
            "play" -> {
                val path = requireString(params, "path")
                val subtitle = optionalString(params, "subtitle", "")
                val value = optionalString(params, "value", "0")
                val startMs = value.toLongOrNull() ?: 0

                // 自动查找匹配字幕
                val subPath = subtitle.ifEmpty { MediaScanner.findMatchingSubtitle(path) }

                // 初始化 mpv
                MpvController.initialize(ClawApplication.instance)

                // 启动 PlayerActivity
                val context = ClawApplication.instance
                val intent = PlayerActivity.intent(context, path, subPath, startMs)
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)

                val subtitleInfo = if (subPath != null) " with subtitle: ${subPath.substringAfterLast('/')}" else ""
                ToolResult.success("Playing: ${path.substringAfterLast('/')}$subtitleInfo")
            }

            "pause" -> {
                MpvController.pause()
                ToolResult.success("Paused at ${MpvController.getPositionMs() / 1000}s")
            }

            "resume" -> {
                MpvController.resume()
                ToolResult.success("Resumed playback")
            }

            "stop" -> {
                MpvController.stop()
                ToolResult.success("Playback stopped")
            }

            "seek" -> {
                val value = requireString(params, "value")
                val positionMs = value.toLongOrNull()
                    ?: return ToolResult.error("Invalid seek position. Use milliseconds.")
                MpvController.seekTo(positionMs)
                ToolResult.success("Seeked to ${positionMs / 1000}s (${positionMs / 60000}min)")
            }

            "seek_relative" -> {
                val value = requireString(params, "value")
                val offsetMs = value.toLongOrNull()
                    ?: return ToolResult.error("Invalid offset. Use milliseconds (positive=forward, negative=backward).")
                MpvController.seekRelative(offsetMs)
                val direction = if (offsetMs > 0) "forward" else "backward"
                ToolResult.success("Seeked $direction ${Math.abs(offsetMs) / 1000}s")
            }

            "subtitle" -> {
                val value = requireString(params, "value")
                // 数字=切换轨道，路径=加载外挂字幕，"size"前缀=调整大小
                when {
                    value.toIntOrNull() != null -> {
                        val trackId = value.toInt()
                        MpvController.setSubtitleTrack(trackId)
                        if (trackId == 0) ToolResult.success("Subtitle turned off")
                        else ToolResult.success("Subtitle switched to track $trackId")
                    }
                    value.startsWith("size:") -> {
                        val size = value.substringAfter("size:").toIntOrNull()
                            ?: return ToolResult.error("Invalid subtitle size. Use 'size:40'.")
                        MpvController.setSubtitleSize(size)
                        ToolResult.success("Subtitle size set to $size")
                    }
                    value.endsWith(".srt") || value.endsWith(".ass") || value.endsWith(".ssa")
                    || value.endsWith(".vtt") || value.endsWith(".sub") -> {
                        MpvController.addSubtitle(value)
                        ToolResult.success("Subtitle loaded: ${value.substringAfterLast('/')}")
                    }
                    else -> ToolResult.error("Subtitle value must be: track number (0=off), file path, or 'size:N'.")
                }
            }

            "audio_track" -> {
                val value = requireString(params, "value")
                val trackId = value.toIntOrNull()
                    ?: return ToolResult.error("Audio track must be a number (1, 2, 3...).")
                MpvController.setAudioTrack(trackId)
                ToolResult.success("Audio track switched to $trackId")
            }

            "volume" -> {
                val value = requireString(params, "value")
                val vol = value.toIntOrNull()
                    ?: return ToolResult.error("Volume must be a number (0-150).")
                MpvController.setVolume(vol)
                ToolResult.success("Volume set to ${vol.coerceIn(0, 150)}%")
            }

            "info" -> {
                val info = MpvController.getPlaybackInfo()
                val sb = StringBuilder("Playback info:\n")
                sb.append("  File: ${info["file"]}\n")
                sb.append("  Playing: ${info["playing"]}\n")
                sb.append("  Position: ${info["position_formatted"]}\n")
                sb.append("  Duration: ${info["duration_formatted"]}\n")
                sb.append("  Volume: ${info["volume"]}%\n")
                ToolResult.success(sb.toString())
            }

            "scan" -> {
                val path = requireString(params, "path")
                val type = optionalString(params, "type", "all")
                val files = MediaScanner.scan(path, recursive = true, type = type)
                if (files.isEmpty()) {
                    ToolResult.success("No media files found in $path")
                } else {
                    val videoCount = files.count { it.type == com.apk.claw.android.media.MediaType.VIDEO }
                    val audioCount = files.count { it.type == com.apk.claw.android.media.MediaType.AUDIO }
                    val subCount = files.count { it.type == com.apk.claw.android.media.MediaType.SUBTITLE }
                    val summary = "Found ${files.size} files ($videoCount video, $audioCount audio, $subCount subtitle):\n"
                    val details = files.take(30).joinToString("\n") { f ->
                        val blurayTag = if (f.isBluRay) " [Blu-ray]" else ""
                        "  ${f.name} (${MediaScanner.formatSize(f.sizeBytes)})$blurayTag"
                    }
                    ToolResult.success("$summary$details")
                }
            }

            "find_subtitle" -> {
                val path = requireString(params, "path")
                val subtitle = MediaScanner.findMatchingSubtitle(path)
                if (subtitle != null) {
                    ToolResult.success("Matching subtitle found: $subtitle")
                } else {
                    ToolResult.success("No matching subtitle found for: ${path.substringAfterLast('/')}")
                }
            }

            // ======================== CloudDrive 2 网盘操作 ========================

            "mount_cloud" -> {
                val subAction = optionalString(params, "value", "start")
                when (subAction) {
                    "start" -> {
                        val result = com.apk.claw.android.media.CloudDriveManager.start()
                        if (result.contains("success") || result.contains("already")) {
                            ToolResult.success(result)
                        } else {
                            ToolResult.error(result)
                        }
                    }
                    "stop" -> ToolResult.success(com.apk.claw.android.media.CloudDriveManager.stop())
                    "drives" -> {
                        val drives = com.apk.claw.android.media.CloudDriveManager.listMountedDrives()
                        if (drives.isEmpty()) {
                            ToolResult.success("No cloud drives mounted. Configure drives at ${com.apk.claw.android.media.CloudDriveManager.getServerUrl()}")
                        } else {
                            val lines = drives.map { "  ${it.name} -> ${it.url}" }
                            ToolResult.success("Mounted cloud drives:\n${lines.joinToString("\n")}")
                        }
                    }
                    else -> ToolResult.error("mount_cloud value must be 'start', 'stop', or 'drives'.")
                }
            }

            "list_cloud" -> {
                val driveName = requireString(params, "path")
                val subPath = optionalString(params, "value", "/")
                val type = optionalString(params, "type", "all")

                if (!com.apk.claw.android.media.CloudDriveManager.isRunning()) {
                    return ToolResult.error("CloudDrive2 is not running. Call mount_cloud(action='mount_cloud', value='start') first.")
                }

                val files = com.apk.claw.android.media.CloudDriveManager.listFiles(driveName, subPath)
                val filtered = if (type == "all") files else files.filter { it.type.name.lowercase() == type }

                if (filtered.isEmpty()) {
                    ToolResult.success("No media files found in $driveName$subPath")
                } else {
                    val lines = filtered.take(30).map { f ->
                        val blurayTag = if (f.isBluRay) " [Blu-ray]" else ""
                        "  ${f.name} (${com.apk.claw.android.media.MediaScanner.formatSize(f.sizeBytes)})$blurayTag"
                    }
                    ToolResult.success("Found ${filtered.size} files in $driveName$subPath:\n${lines.joinToString("\n")}")
                }
            }

            "play_cloud" -> {
                val driveName = requireString(params, "path")
                val filePath = requireString(params, "value")

                if (!com.apk.claw.android.media.CloudDriveManager.isRunning()) {
                    return ToolResult.error("CloudDrive2 is not running. Call mount_cloud first.")
                }

                val url = com.apk.claw.android.media.CloudDriveManager.buildPlayUrl(driveName, filePath)
                val videoName = filePath.substringAfterLast('/').substringBeforeLast('.')
                val dirPath = filePath.substringBeforeLast('/')
                val subtitleUrl = com.apk.claw.android.media.CloudDriveManager.findSubtitle(driveName, dirPath, videoName)

                com.apk.claw.android.media.MpvController.initialize(ClawApplication.instance)
                val context = ClawApplication.instance
                val intent = com.apk.claw.android.media.PlayerActivity.intent(context, url, subtitleUrl, 0)
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)

                val subtitleInfo = if (subtitleUrl != null) " with subtitle" else ""
                ToolResult.success("Playing from cloud: ${filePath.substringAfterLast('/')}$subtitleInfo\nURL: $url")
            }

            "cloud_status" -> {
                val status = com.apk.claw.android.media.CloudDriveManager.getStatus()
                val sb = StringBuilder("CloudDrive2 status:\n")
                sb.append("  Server: ${com.apk.claw.android.media.CloudDriveManager.getServerUrl()}\n")
                sb.append("  Mode: ${if (com.apk.claw.android.media.CloudDriveManager.isLocalMode()) "local" else "remote"}\n")
                sb.append("  Installed: ${status["installed"]}\n")
                sb.append("  Running: ${status["running"]}\n")
                sb.append("  WebDAV: ${status["webdav_url"]}\n")
                sb.append("  Web UI: ${status["web_ui"]}\n")
                if (status["running"] == true) {
                    val drives = com.apk.claw.android.media.CloudDriveManager.listMountedDrives()
                    if (drives.isNotEmpty()) {
                        sb.append("  Mounted drives:\n")
                        drives.forEach { sb.append("    - ${it.name}\n") }
                    }
                }
                ToolResult.success(sb.toString())
            }

            "set_server" -> {
                val url = requireString(params, "path")
                com.apk.claw.android.media.CloudDriveManager.setServerUrl(url)
                val username = optionalString(params, "value", "")
                if (username.isNotEmpty()) {
                    com.apk.claw.android.media.CloudDriveManager.setCredentials(username, "")
                }
                ToolResult.success("CloudDrive2 server set to: $url\nMode: ${if (com.apk.claw.android.media.CloudDriveManager.isLocalMode()) "local (Shizuku managed)" else "remote (connect only)"}")
            }

            else -> ToolResult.error("Unknown action: $action. Available: play, pause, resume, stop, seek, seek_relative, subtitle, audio_track, volume, info, scan, find_subtitle, mount_cloud, list_cloud, play_cloud, cloud_status, set_server.")
        }
    }

    override fun getDescriptionEN(): String = """
        Media player powered by mpv (FFmpeg + libplacebo + libass).
        Supports all video formats, Blu-ray ISO/BDMV, HDR tone mapping, ASS subtitles.
        
        Actions:
        - play(path, subtitle?, value=start_ms): Play media file
        - pause / resume / stop: Playback control
        - seek(value=ms): Jump to position
        - seek_relative(value=ms): Forward/backward
        - subtitle(value=track_id|file_path|size:N): Subtitle control
        - audio_track(value=id): Switch audio track
        - volume(value=0-150): Set volume
        - info: Current playback status
        - scan(path, type=video|audio|subtitle|all): Scan media files
        - find_subtitle(path=video_path): Find matching subtitle file
        - mount_cloud(value=start|stop|drives): Start/stop CloudDrive2 cloud service
        - list_cloud(path=drive_name, value=/subpath): List cloud drive files
        - play_cloud(path=drive_name, value=/file.mkv): Play from cloud drive
        - cloud_status: CloudDrive2 status
        
        Example: play(path="/sdcard/Movies/Avatar.mkv")
        Cloud: play_cloud(path="阿里云盘Open", value="/电影/阿凡达.mkv")
    """.trimIndent()

    override fun getDescriptionCN(): String = """
        基于 mpv 的媒体播放器（FFmpeg + libplacebo + libass）。
        支持所有视频格式、蓝光 ISO/BDMV、HDR 色调映射、ASS 字幕。
        
        操作：
        - play(path, subtitle?, value=起始毫秒): 播放媒体文件
        - pause / resume / stop: 播放控制
        - seek(value=毫秒): 跳转到指定位置
        - seek_relative(value=毫秒): 快进/快退
        - subtitle(value=轨道号|字幕路径|size:大小): 字幕控制
        - audio_track(value=轨道号): 切换音轨
        - volume(value=0-150): 调节音量
        - info: 当前播放状态
        - scan(path, type=video|audio|subtitle|all): 扫描媒体文件
        - find_subtitle(path=视频路径): 查找匹配字幕
        - mount_cloud(value=start|stop|drives): 启动/停止 CloudDrive2 网盘服务
        - list_cloud(path=网盘名, value=/子目录): 列出网盘文件
        - play_cloud(path=网盘名, value=/文件.mkv): 播放网盘文件
        - cloud_status: 网盘服务状态
        
        示例：play(path="/sdcard/Movies/阿凡达.mkv")
        网盘：play_cloud(path="阿里云盘Open", value="/电影/阿凡达.mkv")
    """.trimIndent()
}
