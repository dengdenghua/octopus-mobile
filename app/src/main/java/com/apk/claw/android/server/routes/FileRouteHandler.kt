package com.apk.claw.android.server.routes

import android.content.Context
import fi.iki.elonen.NanoHTTPD

private const val MIME_JSON = "application/json"

/**
 * AI NAS 文件管理 API 路由
 */
class FileRouteHandler(
    private val context: Context,
) : RouteHandler {

    override fun canHandle(uri: String, method: NanoHTTPD.Method): Boolean {
        return when (uri) {
            "/api/files/browse" -> method == NanoHTTPD.Method.GET
            "/api/files/search" -> method == NanoHTTPD.Method.GET
            "/api/files/storage" -> method == NanoHTTPD.Method.GET
            "/api/files/download" -> method == NanoHTTPD.Method.GET
            "/api/files/upload" -> method == NanoHTTPD.Method.POST
            "/api/files/delete" -> method == NanoHTTPD.Method.POST
            else -> false
        }
    }

    override fun handle(session: NanoHTTPD.IHTTPSession, ctx: RouteContext): NanoHTTPD.Response {
        return when {
            session.uri == "/api/files/browse" && session.method == NanoHTTPD.Method.GET -> handleFileBrowse(session, ctx)
            session.uri == "/api/files/search" && session.method == NanoHTTPD.Method.GET -> handleFileSearch(session, ctx)
            session.uri == "/api/files/storage" && session.method == NanoHTTPD.Method.GET -> handleStorageOverview(ctx)
            session.uri == "/api/files/download" && session.method == NanoHTTPD.Method.GET -> handleFileDownload(session, ctx)
            session.uri == "/api/files/upload" && session.method == NanoHTTPD.Method.POST -> handleFileUpload(session, ctx)
            session.uri == "/api/files/delete" && session.method == NanoHTTPD.Method.POST -> handleFileDelete(session, ctx)
            else -> ctx.corsResponse(
                NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.NOT_FOUND, MIME_JSON,
                    """{"code":-1,"message":"接口不存在"}"""
                )
            )
        }
    }

    /**
     * GET /api/files/browse?path=/sdcard/Download&hidden=false
     */
    private fun handleFileBrowse(session: NanoHTTPD.IHTTPSession, ctx: RouteContext): NanoHTTPD.Response {
        val startMs = System.currentTimeMillis()
        val path = session.parms["path"] ?: "/sdcard"
        val showHidden = session.parms["hidden"]?.toBoolean() ?: false
        if (!ctx.isAllowedUserPath(path)) {
            ctx.recordRemoteAccess(session, "file_browse", false, "path=$path,forbidden=true", startMs)
            return ctx.forbiddenPathResponse()
        }

        val shizuku = com.apk.claw.android.shizuku.ShizukuShellService
        val listing = shizuku.listFiles(path, showHidden)
            ?: run {
                ctx.recordRemoteAccess(session, "file_browse", false, "path=$path,shizuku=unavailable", startMs)
                return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK, MIME_JSON,
                    """{"code":-1,"message":"Shizuku 不可用"}"""
                ))
            }

        val json = ctx.gson.toJson(mapOf("code" to 0, "data" to mapOf(
            "path" to path,
            "listing" to listing
        )))
        ctx.recordRemoteAccess(session, "file_browse", true, "path=$path,hidden=$showHidden", startMs)
        return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, MIME_JSON, json))
    }

    /**
     * GET /api/files/search?path=/sdcard&pattern=*.jpg&max=30
     */
    private fun handleFileSearch(session: NanoHTTPD.IHTTPSession, ctx: RouteContext): NanoHTTPD.Response {
        val startMs = System.currentTimeMillis()
        val path = session.parms["path"] ?: "/sdcard"
        val pattern = session.parms["pattern"] ?: "*"
        val maxResults = session.parms["max"]?.toIntOrNull() ?: 30
        if (!ctx.isAllowedUserPath(path)) {
            ctx.recordRemoteAccess(session, "file_search", false, "path=$path,pattern=$pattern,forbidden=true", startMs)
            return ctx.forbiddenPathResponse()
        }

        val shizuku = com.apk.claw.android.shizuku.ShizukuShellService
        val result = shizuku.searchFiles(path, pattern, maxResults)
            ?: run {
                ctx.recordRemoteAccess(session, "file_search", false, "path=$path,pattern=$pattern,shizuku=unavailable", startMs)
                return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK, MIME_JSON,
                    """{"code":-1,"message":"Shizuku 不可用"}"""
                ))
            }

        val files = result.lines().filter { it.isNotBlank() }
        val json = ctx.gson.toJson(mapOf("code" to 0, "data" to mapOf(
            "path" to path,
            "pattern" to pattern,
            "count" to files.size,
            "files" to files
        )))
        ctx.recordRemoteAccess(session, "file_search", true, "path=$path,pattern=$pattern,count=${files.size}", startMs)
        return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, MIME_JSON, json))
    }

    /**
     * GET /api/files/storage
     */
    private fun handleStorageOverview(ctx: RouteContext): NanoHTTPD.Response {
        val shizuku = com.apk.claw.android.shizuku.ShizukuShellService
        val overview = shizuku.getStorageOverview()
            ?: return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, MIME_JSON,
                """{"code":-1,"message":"Shizuku 不可用"}"""
            ))

        val json = ctx.gson.toJson(mapOf("code" to 0, "data" to overview))
        return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, MIME_JSON, json))
    }

    /**
     * GET /api/files/download?path=/sdcard/Download/file.pdf
     *
     * 通过 Shizuku shell 读取文件并流式返回。
     */
    private fun handleFileDownload(session: NanoHTTPD.IHTTPSession, ctx: RouteContext): NanoHTTPD.Response {
        val startMs = System.currentTimeMillis()
        val path = session.parms["path"] ?: run {
            ctx.recordRemoteAccess(session, "file_download", false, "path=<missing>", startMs)
            return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"缺少 path 参数"}"""
            ))
        }

        if (!ctx.isAllowedUserPath(path)) {
            ctx.recordRemoteAccess(session, "file_download", false, "path=$path,forbidden=true", startMs)
            return ctx.forbiddenPathResponse()
        }

        val externalDir = context.getExternalFilesDir(null)
            ?: run {
                ctx.recordRemoteAccess(session, "file_download", false, "path=$path,external_storage=unavailable", startMs)
                return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.INTERNAL_ERROR, MIME_JSON,
                    """{"code":-1,"message":"外部存储不可用"}"""
                ))
            }
        val cacheFile = java.io.File(externalDir, "download_${System.currentTimeMillis()}")
        val shizuku = com.apk.claw.android.shizuku.ShizukuShellService
        // 原 `cp ... && chmod ...` 组合命令被 ShizukuShellService.exec 双重拦截:
        //   1) && 命中注入模式
        //   2) cacheFile 在 app 私有目录 /data/data/<pkg>/files/ 下,命中 FORBIDDEN_PATH_PATTERNS
        // 即使 sanitizeShellArg 单引号转义也无效——FORBIDDEN_PATH_PATTERNS 是对原始命令串做子串匹配,
        // 引号内的 /data/data/ 照样命中。
        //
        // 修复方案:把文件拷到 /sdcard/OctopusDownloadCache/ 下(shell 可读写、app 可读),
        // 再用 Java IO 复制到 app 私有目录的 cacheFile(随后 NanoHTTPD 从私有目录读返回客户端)。
        // 路径参数统一用 sanitizeShellArg 单引号转义。
        val sdcardCacheDir = "/sdcard/OctopusDownloadCache"
        shizuku.exec("mkdir -p ${shizuku.sanitizeShellArg(sdcardCacheDir)}")
        val tmpName = "dl_${System.currentTimeMillis()}_${path.substringAfterLast('/').take(40)}"
        val sdcardCachePath = "$sdcardCacheDir/$tmpName"
        val safeSrc = shizuku.sanitizeShellArg(path)
        val safeTmp = shizuku.sanitizeShellArg(sdcardCachePath)
        val cpResult = shizuku.exec("cp $safeSrc $safeTmp")
        if (cpResult == null || cpResult.exitCode != 0) {
            ctx.recordRemoteAccess(session, "file_download", false, "path=$path,copy_failed=true", startMs)
            return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR, MIME_JSON,
                """{"code":-1,"message":"复制文件失败: ${cpResult?.stderr?.trim() ?: "Shizuku 不可用"}"}"""
            ))
        }
        // 从 /sdcard 中转文件复制到 app 私有目录,再删除中转文件。
        try {
            java.io.File(sdcardCachePath).inputStream().use { input ->
                cacheFile.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            shizuku.exec("rm -f $safeTmp")
        }

        val mime = when (path.substringAfterLast('.').lowercase()) {
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "txt", "log" -> "text/plain"
            "json" -> "application/json"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }

        return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK, mime,
            cacheFile.inputStream(), cacheFile.length()
        ).also {
            it.addHeader("Content-Disposition", "attachment; filename=\"${path.substringAfterLast('/')}\"")
            ctx.recordRemoteAccess(session, "file_download", true, "path=$path,mime=$mime,bytes=${cacheFile.length()}", startMs)
        })
    }

    /**
     * POST /api/files/upload
     * Body: { "path": "/sdcard/Download/uploaded.pdf", "base64": "..." }
     */
    private fun handleFileUpload(session: NanoHTTPD.IHTTPSession, ctx: RouteContext): NanoHTTPD.Response {
        val startMs = System.currentTimeMillis()
        val params = ctx.readJsonBody(session)

        val path = params.get("path")?.asString ?: run {
            ctx.recordRemoteAccess(session, "file_upload", false, "path=<missing>", startMs)
            return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"缺少 path"}"""
            ))
        }
        val base64Data = params.get("base64")?.asString ?: run {
            ctx.recordRemoteAccess(session, "file_upload", false, "path=$path,base64=<missing>", startMs)
            return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"缺少 base64 数据"}"""
            ))
        }

        if (!ctx.isAllowedUserPath(path)) {
            ctx.recordRemoteAccess(session, "file_upload", false, "path=$path,forbidden=true", startMs)
            return ctx.forbiddenPathResponse()
        }

        try {
            val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
            val externalCache = context.getExternalFilesDir(null)
                ?: run {
                    ctx.recordRemoteAccess(session, "file_upload", false, "path=$path,external_storage=unavailable", startMs)
                    return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(
                        NanoHTTPD.Response.Status.INTERNAL_ERROR, MIME_JSON,
                        """{"code":-1,"message":"外部存储不可用"}"""
                    ))
                }
            val tempFile = java.io.File(externalCache, "upload_${System.currentTimeMillis()}")
            tempFile.writeBytes(bytes)
            val shizuku = com.apk.claw.android.shizuku.ShizukuShellService
            val ok = shizuku.copyFile(tempFile.absolutePath, path)
            tempFile.delete()

            val json = ctx.gson.toJson(mapOf(
                "code" to if (ok == true) 0 else -1,
                "message" to if (ok == true) "已上传到 $path" else "上传失败"
            ))
            ctx.recordRemoteAccess(session, "file_upload", ok == true, "path=$path,bytes=${bytes.size}", startMs)
            return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, MIME_JSON, json))
        } catch (e: Exception) {
            val json = ctx.gson.toJson(mapOf("code" to -1, "message" to "上传异常: ${e.message}"))
            ctx.recordRemoteAccess(session, "file_upload", false, "path=$path,error=${e.message}", startMs)
            return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, MIME_JSON, json))
        }
    }

    /**
     * POST /api/files/delete
     * Body: { "path": "/sdcard/Download/temp.txt" }
     */
    private fun handleFileDelete(session: NanoHTTPD.IHTTPSession, ctx: RouteContext): NanoHTTPD.Response {
        val startMs = System.currentTimeMillis()
        val params = ctx.readJsonBody(session)

        val path = params.get("path")?.asString ?: run {
            ctx.recordRemoteAccess(session, "file_delete", false, "path=<missing>", startMs)
            return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"缺少 path"}"""
            ))
        }

        if (!ctx.isAllowedUserPath(path)) {
            ctx.recordRemoteAccess(session, "file_delete", false, "path=$path,forbidden=true", startMs)
            return ctx.forbiddenPathResponse()
        }

        val shizuku = com.apk.claw.android.shizuku.ShizukuShellService
        val ok = shizuku.deleteFile(path)
        val json = ctx.gson.toJson(mapOf(
            "code" to if (ok == true) 0 else -1,
            "message" to if (ok == true) "已删除: $path" else "删除失败"
        ))
        ctx.recordRemoteAccess(session, "file_delete", ok == true, "path=$path", startMs)
        return ctx.corsResponse(NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, MIME_JSON, json))
    }
}
