package com.apk.claw.android.media

import com.apk.claw.android.shizuku.ShizukuShellService
import com.apk.claw.android.utils.XLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * 媒体文件扫描器 —— 基于 Shizuku shell 扫描指定目录的媒体文件。
 *
 * 支持格式：
 * - 视频：mkv, mp4, avi, ts, m2ts, flv, wmv, mov, webm, iso, vob
 * - 音频：flac, wav, aac, mp3, ogg, dts, truehd
 * - 字幕：srt, ass, ssa, sub, vtt, idx, sup
 *
 * 蓝光结构识别：
 * - ISO 镜像：*.iso
 * - BDMV 目录：BDMV/index.bdmv
 * - DVD 目录：VIDEO_TS/VIDEO_TS.IFO
 */
object MediaScanner {

    private const val TAG = "MediaScanner"

    /** 支持的视频扩展名 */
    val VIDEO_EXTENSIONS = setOf(
        "mkv", "mp4", "avi", "ts", "m2ts", "flv", "wmv", "mov", "webm",
        "iso", "vob", "mpg", "mpeg", "3gp", "rmvb", "rm", "asf", "divx"
    )

    /** 支持的音频扩展名 */
    val AUDIO_EXTENSIONS = setOf(
        "flac", "wav", "aac", "mp3", "ogg", "m4a", "wma", "ape",
        "dts", "truehd", "eac3", "ac3", "opus", "alac"
    )

    /** 支持的字幕扩展名 */
    val SUBTITLE_EXTENSIONS = setOf(
        "srt", "ass", "ssa", "sub", "vtt", "idx", "sup", "pgs", "smi"
    )

    /**
     * 扫描目录中的媒体文件。
     *
     * @param path 目录路径（如 /sdcard/Movies）
     * @param recursive 是否递归扫描子目录
     * @param type 过滤类型："video", "audio", "subtitle", "all"
     * @return 媒体文件列表
     */
    fun scan(path: String, recursive: Boolean = true, type: String = "all"): List<MediaFile> {
        val result = mutableListOf<MediaFile>()

        try {
            // 使用 Shizuku shell 列出文件
            val depth = if (recursive) "" else " -maxdepth 1"
            val filesOutput = ShizukuShellService.listFiles(path, showHidden = false)
                ?: return emptyList()

            val lines = filesOutput.lines().filter { it.isNotBlank() }
            for (line in lines) {
                val parts = line.split("\t")
                if (parts.size < 4) continue

                val name = parts[0]
                val isDir = parts[1] == "d"
                val sizeStr = parts.getOrNull(2) ?: "0"
                val modTime = parts.getOrNull(3) ?: ""

                if (isDir) {
                    // 检查是否是蓝光/DVD 目录结构
                    val bdmvCheck = checkBluRayStructure(path, name)
                    if (bdmvCheck != null) {
                        result.add(bdmvCheck)
                    }
                    continue
                }

                val ext = name.substringAfterLast('.', "").lowercase()
                val sizeBytes = sizeStr.toLongOrNull() ?: 0

                // 按类型过滤
                val mediaType = when (ext) {
                    in VIDEO_EXTENSIONS -> MediaType.VIDEO
                    in AUDIO_EXTENSIONS -> MediaType.AUDIO
                    in SUBTITLE_EXTENSIONS -> MediaType.SUBTITLE
                    else -> continue
                }

                if (type != "all" && mediaType.name.lowercase() != type) continue

                // 检查蓝光 ISO
                val isBluRay = ext == "iso" && sizeBytes > 1_000_000_000  // > 1GB 的 ISO 大概率是蓝光

                result.add(MediaFile(
                    name = name,
                    path = "$path/$name",
                    type = mediaType,
                    sizeBytes = sizeBytes,
                    extension = ext,
                    modifiedTime = modTime,
                    isBluRay = isBluRay
                ))
            }

            // 递归扫描子目录
            if (recursive) {
                val subdirs = lines.filter { it.split("\t").let { p -> p.size >= 2 && p[1] == "d" } }
                for (subdir in subdirs) {
                    val dirName = subdir.split("\t")[0]
                    if (dirName.startsWith('.')) continue  // 跳过隐藏目录
                    result.addAll(scan("$path/$dirName", recursive = true, type = type))
                }
            }

            XLog.i(TAG, "Scanned $path: found ${result.size} media files")
        } catch (e: Exception) {
            XLog.e(TAG, "Scan error: ${e.message}")
        }

        return result
    }

    /**
     * 检查目录是否是蓝光/DVD 结构。
     */
    private fun checkBluRayStructure(parentPath: String, dirName: String): MediaFile? {
        val dirPath = "$parentPath/$dirName"
        val entries = ShizukuShellService.listFiles(dirPath, showHidden = false) ?: return null

        // BDMV 结构
        if (entries.contains("BDMV") || entries.contains("bdmv")) {
            val indexPath = "$dirPath/BDMV/index.bdmv"
            return MediaFile(
                name = "$dirName (Blu-ray BDMV)",
                path = indexPath,
                type = MediaType.VIDEO,
                sizeBytes = 0,  // BDMV 目录大小需要单独计算
                extension = "bdmv",
                modifiedTime = "",
                isBluRay = true
            )
        }

        // DVD 结构
        if (entries.contains("VIDEO_TS")) {
            return MediaFile(
                name = "$dirName (DVD)",
                path = "$dirPath/VIDEO_TS/VIDEO_TS.IFO",
                type = MediaType.VIDEO,
                sizeBytes = 0,
                extension = "ifo",
                modifiedTime = "",
                isBluRay = false
            )
        }

        return null
    }

    /**
     * 查找视频文件的匹配字幕（同目录 + 文件名模糊匹配）。
     */
    fun findMatchingSubtitle(videoPath: String): String? {
        val dir = videoPath.substringBeforeLast('/')
        val videoName = videoPath.substringAfterLast('/').substringBeforeLast('.')

        val files = ShizukuShellService.listFiles(dir, showHidden = false) ?: return null
        val lines = files.lines()

        for (line in lines) {
            val parts = line.split("\t")
            if (parts.size < 2 || parts[1] == "d") continue
            val name = parts[0]
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in SUBTITLE_EXTENSIONS) continue

            val subName = name.substringBeforeLast('.')
            // 模糊匹配：字幕文件名包含视频文件名
            if (subName.startsWith(videoName) || videoName.startsWith(subName)) {
                return "$dir/$name"
            }
        }
        return null
    }

    /**
     * 格式化文件大小。
     */
    fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        }
    }

    /**
     * 将扫描结果转为 JSON（用于 Agent 返回）。
     */
    fun toJson(files: List<MediaFile>): String {
        val arr = JSONArray()
        for (f in files) {
            arr.put(JSONObject().apply {
                put("name", f.name)
                put("path", f.path)
                put("type", f.type.name.lowercase())
                put("size", formatSize(f.sizeBytes))
                put("size_bytes", f.sizeBytes)
                put("extension", f.extension)
                put("bluray", f.isBluRay)
                put("modified", f.modifiedTime)
            })
        }
        return arr.toString(2)
    }
}

/**
 * 媒体文件。
 */
data class MediaFile(
    val name: String,
    val path: String,
    val type: MediaType,
    val sizeBytes: Long,
    val extension: String,
    val modifiedTime: String,
    val isBluRay: Boolean
)

/**
 * 媒体类型。
 */
enum class MediaType {
    VIDEO, AUDIO, SUBTITLE
}
