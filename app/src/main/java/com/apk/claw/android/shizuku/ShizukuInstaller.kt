package com.apk.claw.android.shizuku
import com.apk.claw.android.utils.OctoHttp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import com.apk.claw.android.R
import com.apk.claw.android.account.AccountConfig
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object ShizukuInstaller {
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val APK_FILE_NAME = "Shizuku.apk"

    private val main = Handler(Looper.getMainLooper())
    private val http = OctoHttp.shared.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun downloadAndInstall(
        context: Context,
        onStatus: (String) -> Unit,
        onDone: (Boolean) -> Unit,
    ) {
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !appContext.packageManager.canRequestPackageInstalls()) {
            onStatus(appContext.getString(R.string.advanced_install_unknown_sources_required))
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${appContext.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { appContext.startActivity(intent) }
            onDone(false)
            return
        }

        onStatus(appContext.getString(R.string.advanced_install_fetching))
        Thread {
            runCatching {
                val info = fetchInfo()
                val url = info.optString("downloadUrl")
                if (!info.optBoolean("available") || url.isBlank()) {
                    error(appContext.getString(R.string.advanced_install_not_configured))
                }
                postStatus(onStatus, appContext.getString(R.string.advanced_install_downloading))
                val apk = downloadApk(appContext, url)
                val expectedSha = info.optString("sha256")
                if (expectedSha.isNotBlank()) {
                    val actualSha = sha256(apk)
                    if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                        apk.delete()
                        error(appContext.getString(R.string.advanced_install_verify_failed))
                    }
                }
                main.post {
                    postStatus(onStatus, appContext.getString(R.string.advanced_install_opening))
                    openInstaller(appContext, apk)
                    onDone(true)
                }
            }.onFailure { e ->
                main.post {
                    onStatus(e.message ?: appContext.getString(R.string.advanced_install_failed))
                    onDone(false)
                }
            }
        }.start()
    }

    private fun fetchInfo(): JSONObject {
        val base = AccountConfig.baseUrl.trimEnd('/')
        val req = Request.Builder()
            .url("$base/downloads/shizuku/latest")
            .header("User-Agent", "OctopusMobile-ShizukuInstaller")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            return JSONObject(resp.body?.string().orEmpty())
        }
    }

    private fun downloadApk(context: Context, url: String): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
        if (!dir.exists()) dir.mkdirs()
        val out = File(dir, APK_FILE_NAME)
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "OctopusMobile-ShizukuInstaller")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body ?: error("empty response")
            out.outputStream().use { output ->
                body.byteStream().use { input -> input.copyTo(output) }
            }
        }
        return out
    }

    private fun openInstaller(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun postStatus(onStatus: (String) -> Unit, value: String) {
        main.post { onStatus(value) }
    }
}
