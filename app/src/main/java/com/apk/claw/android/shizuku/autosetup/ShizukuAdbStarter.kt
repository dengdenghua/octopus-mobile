package com.apk.claw.android.shizuku.autosetup

import android.content.Context
import com.apk.claw.android.utils.XLog
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「全自动配置 Shizuku」的传输层门面 —— 把 libadb-android 的配对/连接/跑 shell 收成三个挂起函数。
 *
 * 典型编排(Stage 3 的无障碍层调用):
 *  1. 无障碍读「配对码」子弹窗 → [pair] (host, 配对端口, 6位码)
 *  2. [autoConnectAndStartShizuku] (mDNS 自动发现连接端口) 或 [connectAndStartShizuku] (显式端口)
 *  3. [disconnect] —— Shizuku 已独立常驻,ADB 连接可断
 *
 * 全部走 IO 线程;每步返回 [Result],失败由上层决定回退(手填配对码 / 提示插电脑)。
 */
object ShizukuAdbStarter {

    private const val TAG = "ShizukuAdbStarter"

    /** Shizuku 官方「通过 ADB 启动」命令(见 shizuku.rikka.app/guide/setup)。 */
    const val SHIZUKU_START_CMD = "sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh"

    private const val DEFAULT_TIMEOUT_MS = 10_000L

    /** 日志输出尾部截断长度,避免 Shizuku 启动脚本的长输出刷屏。 */
    private const val LOG_OUT_TAIL = 200

    /**
     * 用配对码把本机与 adbd 配对。host/port/code 取自无线调试「使用配对码配对设备」子弹窗
     * (见 [PairingDialogParser])。配对成功后身份被 adbd 记住,下次免配对。
     */
    suspend fun pair(context: Context, host: String, port: Int, code: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                OctopusAdbManager.getInstance(context).pair(host, port, code)
                XLog.i(TAG, "ADB 配对成功 @ $host:$port")
            }.onFailure { XLog.w(TAG, "ADB 配对失败 @ $host:$port", it) }
        }

    /** mDNS 自动发现连接端口并连接,再拉起 Shizuku。多数 Android 11+ 局域网可用。 */
    suspend fun autoConnectAndStartShizuku(
        context: Context,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val mgr = OctopusAdbManager.getInstance(context)
            mgr.autoConnect(context, timeoutMs)
            mgr.startShizuku()
        }.onFailure { XLog.w(TAG, "autoConnect 拉起 Shizuku 失败", it) }
    }

    /** 显式 host/port(无线调试主页那个「连接」端口,非配对端口)连接并拉起 Shizuku。 */
    suspend fun connectAndStartShizuku(context: Context, host: String, port: Int): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val mgr = OctopusAdbManager.getInstance(context)
                mgr.connect(host, port)
                mgr.startShizuku()
            }.onFailure { XLog.w(TAG, "connect 拉起 Shizuku 失败 @ $host:$port", it) }
        }

    /** 拉起 Shizuku 后断开 ADB 连接(Shizuku 已作为独立进程常驻)。 */
    suspend fun disconnect(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { OctopusAdbManager.getInstance(context).close() }
    }

    private fun AbsAdbConnectionManager.startShizuku(): String {
        val out = shell(SHIZUKU_START_CMD)
        XLog.i(TAG, "Shizuku 启动输出: ${out.take(LOG_OUT_TAIL)}")
        return out
    }

    /** 在已建立的连接上跑一条 shell,读全部输出到 EOF。 */
    private fun AbsAdbConnectionManager.shell(cmd: String): String {
        val stream = openStream("shell:$cmd")
        return try {
            stream.openInputStream().readBytes().toString(Charsets.UTF_8)
        } finally {
            stream.close()
        }
    }
}
