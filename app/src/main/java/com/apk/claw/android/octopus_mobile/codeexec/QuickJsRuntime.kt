package com.apk.claw.android.octopus_mobile.codeexec

import android.os.Build
import android.util.Log
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.shizuku.ShizukuManager
import com.apk.claw.android.shizuku.ShizukuShellService
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * QuickJS 沙箱 runtime 的分发与就绪管理。
 *
 * 模型(见 [[octopus-code-exec-feature]] 设计):
 *  - runner 是自建的 `qjs-runner`(纯 C QuickJS,编译期砍掉 os/std,只能纯计算),
 *    以 APK 资产 `octopus/qjs-runner-arm64-v8a` 形式随包分发(~781KB,体积可忽略)。
 *  - 安装路径:资产 → App 外部目录(App 可写、shell 经 ext_data_rw 可读)
 *            → Shizuku `cp` 进 [ShizukuShellService.RUNTIME_DIR](shell 域可 exec)→ chmod 755。
 *  - App(untrusted_app)在 targetSdk≥29 下不能 exec 自己私有目录,但 Shizuku 跑在 shell 域,
 *    可 exec `/data/local/tmp`(`shell_data_file`)——这正是「编程」只能是 Shizuku 高级功能的原因。
 *
 * 完整性:资产在已签名 APK 内,签名即保完整性;这里再做一次 SHA-256 纵深防御,
 *        防止「解出到外部目录 → cp 进沙箱」之间被替换。
 */
object QuickJsRuntime {

    private const val TAG = "QuickJsRuntime"

    const val RUNNER_NAME = "qjs-runner"
    /** 沙箱内 runner 的绝对路径。 */
    val RUNNER_PATH = "${ShizukuShellService.RUNTIME_DIR}/$RUNNER_NAME"

    private const val ASSET_NAME = "octopus/qjs-runner-arm64-v8a"

    /** 期望 SHA-256(随资产更新需同步;构建脚本会校验)。 */
    private const val EXPECTED_SHA256 = "96a4315b95ceae53420aa2df57161cde05d6bafe4c650c8cc9a99d75aeeb6697"

    @Volatile
    private var ready = false

    /** v1 仅提供 arm64-v8a runner;其他架构暂不支持(优雅降级)。 */
    fun isSupportedAbi(): Boolean = Build.SUPPORTED_ABIS?.firstOrNull() == "arm64-v8a"

    /**
     * 确保 runner 已装好并可执行(幂等)。
     *
     * **必须在后台线程调用**——内部会跑 Shizuku shell。返回 false 表示当前不可用
     * (Shizuku 未授权 / 架构不支持 / 安装失败)。
     */
    @Synchronized
    fun ensureReady(): Boolean {
        if (ready) return true
        if (!ShizukuManager.isAvailable()) {
            Log.d(TAG, "Shizuku unavailable, runtime not ready")
            return false
        }
        if (!isSupportedAbi()) {
            Log.w(TAG, "unsupported ABI: ${Build.SUPPORTED_ABIS?.joinToString()}")
            return false
        }
        return try {
            val ctx = ClawApplication.instance
            // 1) 资产解出到 App 外部目录(App 可写、shell 可读)
            val extFile = File(ctx.getExternalFilesDir(null), "octopus/$RUNNER_NAME")
            extFile.parentFile?.mkdirs()
            ctx.assets.open(ASSET_NAME).use { input ->
                FileOutputStream(extFile).use { out -> input.copyTo(out) }
            }
            // 2) 资产哈希纵深防御
            val actual = sha256(extFile)
            if (actual != EXPECTED_SHA256) {
                Log.e(TAG, "runner hash mismatch: expected $EXPECTED_SHA256 got $actual")
                extFile.delete()
                return false
            }
            // 3) Shizuku cp 进沙箱 + chmod 755
            val ok = ShizukuShellService.installRuntimeBinary(extFile.absolutePath, RUNNER_PATH)
            runCatching { extFile.delete() }
            ready = ok
            if (!ok) Log.w(TAG, "installRuntimeBinary failed")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "ensureReady failed", e)
            false
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } != -1) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
