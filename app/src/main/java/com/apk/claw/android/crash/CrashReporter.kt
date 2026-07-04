package com.apk.claw.android.crash

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import com.apk.claw.android.BuildConfig
import com.apk.claw.android.account.AccountConfig
import com.apk.claw.android.utils.OctoHttp
import com.apk.claw.android.utils.XLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 低成本崩溃上报 —— 捕获未处理异常,落盘 + 下次启动时后台补传,不依赖登录态/设备配对。
 *
 * 背景:低内存设备排查发现,现有 `/device/report` 需要已登录 + 预注册的 remote_devices 行,
 * 崩溃恰恰最可能发生在登录前/配对完成前,会被那条链路整个吞掉。这里单开一条公开、无鉴权、
 * 限流的上报通路(服务端 `POST /crash/report`),客户端只做两件事:
 *
 * 1. [install] —— 尽可能早地安装 [Thread.setDefaultUncaughtExceptionHandler],在崩溃线程内
 *    **同步**把崩溃信息写本地文件(不做网络 I/O,崩溃线程里网络请求不可靠且可能挂住进程),
 *    然后链式转交给安装前的默认 handler(不存在则退化为 `Process.killProcess`,与系统默认行为一致)。
 * 2. [uploadPending] —— App 下次启动时,后台协程扫描本地崩溃目录,逐个 POST 上传,成功即删,
 *    失败留给下次重试;同时按「最多保留 N 个 + 最长保留 M 天」做兜底清理,避免服务器长期不可达时
 *    在本就内存/存储紧张的设备上无限堆积。
 *
 * 全文件每个入口都用 try/catch 兜底 —— 崩溃上报器自己再抛一次异常(甚至在 handler 内递归)会是
 * 这个功能最荒谬的失败模式,这是整个功能的第一约束。
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private const val DIR_NAME = "crash_reports"
    private const val MAX_KEEP_FILES = 20
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000 // 7 天
    private const val STACK_TRACE_MAX_CHARS = 8000

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val http = OctoHttp.shared.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile private var installed = false

    /**
     * 安装全局未捕获异常兜底处理器。应在 [android.app.Application.onCreate]/`initializeApp()`
     * 中尽可能早调用(甚至早于同步初始化链本身),这样初始化链自身崩溃也能被捕获。
     * 幂等:重复调用只安装一次。
     */
    @JvmStatic
    fun install(context: Context) {
        if (installed) return
        try {
            val appContext = context.applicationContext ?: context
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    writeCrashFile(appContext, thread, throwable)
                } catch (inner: Throwable) {
                    // 崩溃上报器自身绝不能吞掉或替换真正的崩溃 —— 这里只记日志(XLog 内部已捕获,
                    // 但仍整体包一层防御),然后无论如何都要继续走链式转交/系统默认行为。
                    try {
                        XLog.e(TAG, "writeCrashFile failed", inner)
                    } catch (_: Throwable) {
                        // 连日志都不信任:彻底静默,绝不能在异常处理器里再抛异常。
                    }
                }
                try {
                    if (previousHandler != null) {
                        previousHandler.uncaughtException(thread, throwable)
                    } else {
                        Process.killProcess(Process.myPid())
                    }
                } catch (_: Throwable) {
                    // 转交/兜底 kill 本身失败也不能再抛 —— 没有更多手段了,静默结束。
                }
            }
            installed = true
        } catch (t: Throwable) {
            try {
                XLog.e(TAG, "install failed", t)
            } catch (_: Throwable) {
            }
        }
    }

    /** 崩溃线程内同步执行:只做纯内存拼接 + 文件写入,不做网络 I/O。 */
    private fun writeCrashFile(context: Context, thread: Thread, throwable: Throwable) {
        val payload = buildPayload(context, thread, throwable)
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        val file = File(dir, "crash_${System.currentTimeMillis()}_${Process.myPid()}.json")
        file.writeText(payload.toString())
    }

    private fun buildPayload(context: Context, thread: Thread, throwable: Throwable): JSONObject {
        val stackTrace = runCatching { android.util.Log.getStackTraceString(throwable) }.getOrDefault("")
        val (availMb, totalMb) = readMemoryInfoMb(context)
        return JSONObject().apply {
            put("deviceModel", runCatching { android.os.Build.MODEL }.getOrDefault(""))
            put("manufacturer", runCatching { android.os.Build.MANUFACTURER }.getOrDefault(""))
            put("osVersion", runCatching { android.os.Build.VERSION.RELEASE }.getOrDefault(""))
            put("sdkInt", runCatching { android.os.Build.VERSION.SDK_INT }.getOrDefault(0))
            put("appVersion", runCatching { BuildConfig.VERSION_NAME }.getOrDefault(""))
            put("appVersionCode", runCatching { BuildConfig.VERSION_CODE }.getOrDefault(0))
            put("stackTrace", stackTrace.take(STACK_TRACE_MAX_CHARS))
            put("threadName", runCatching { thread.name }.getOrDefault(""))
            put("availableMemMb", availMb)
            put("totalMemMb", totalMb)
            put("occurredAt", System.currentTimeMillis())
        }
    }

    /** 崩溃瞬间的可用/总内存(MB)。任何一步失败都返回 0/0,不影响其余字段落盘。 */
    private fun readMemoryInfoMb(context: Context): Pair<Long, Long> {
        return runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return@runCatching 0L to 0L
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            (info.availMem / (1024 * 1024)) to (info.totalMem / (1024 * 1024))
        }.getOrDefault(0L to 0L)
    }

    /**
     * 后台补传:扫描本地崩溃目录,逐个上传,成功删除、失败留待下次重试。
     * 无论上传结果如何都执行「最多保留 N 个最近文件 + 最长保留 M 天」的兜底清理。
     * 整个函数不向调用方抛出任何异常 —— 这是 App 启动路径的一部分,不能反过来拖垮启动。
     */
    suspend fun uploadPending(context: Context) {
        try {
            withContext(Dispatchers.IO) {
                val dir = File(context.filesDir, DIR_NAME)
                if (!dir.isDirectory) return@withContext

                val files = runCatching { dir.listFiles()?.toList() }.getOrNull().orEmpty()
                for (file in files) {
                    uploadOne(file)
                }

                enforceRetention(dir)
            }
        } catch (t: Throwable) {
            try {
                XLog.w(TAG, "uploadPending failed", t)
            } catch (_: Throwable) {
            }
        }
    }

    private fun uploadOne(file: File) {
        try {
            val base = AccountConfig.squareBaseUrl.trim().trimEnd('/')
            if (base.isEmpty()) return

            val body = runCatching { file.readText() }.getOrNull()
            if (body.isNullOrBlank()) {
                // 空文件/读不出来,没有重试价值,直接清理掉。
                runCatching { file.delete() }
                return
            }
            // 校验一遍是合法 JSON 再上传,避免半写坏文件反复占位重试。
            runCatching { JSONObject(body) }.getOrElse {
                runCatching { file.delete() }
                return
            }

            val req = Request.Builder()
                .url("$base/crash/report")
                .post(body.toRequestBody(JSON))
                .build()

            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    runCatching { file.delete() }
                }
                // 非 2xx:留给下次启动重试(受 enforceRetention 的数量/时长兜底约束)。
            }
        } catch (t: Throwable) {
            // 单个文件上传失败(网络错误/文件 I/O/解析)不影响其余文件继续处理。
            try {
                XLog.w(TAG, "uploadOne failed: ${file.name}", t)
            } catch (_: Throwable) {
            }
        }
    }

    /** 无论上传是否成功都执行:超过 [MAX_AGE_MS] 的文件直接删;超过 [MAX_KEEP_FILES] 个只保留最新的。 */
    private fun enforceRetention(dir: File) {
        try {
            val now = System.currentTimeMillis()
            val remaining = (dir.listFiles()?.toList().orEmpty())
                .filter { it.isFile }
                .filter { f ->
                    val tooOld = now - f.lastModified() > MAX_AGE_MS
                    if (tooOld) runCatching { f.delete() }
                    !tooOld
                }
                .sortedByDescending { it.lastModified() }

            if (remaining.size > MAX_KEEP_FILES) {
                remaining.drop(MAX_KEEP_FILES).forEach { runCatching { it.delete() } }
            }
        } catch (t: Throwable) {
            try {
                XLog.w(TAG, "enforceRetention failed", t)
            } catch (_: Throwable) {
            }
        }
    }
}
