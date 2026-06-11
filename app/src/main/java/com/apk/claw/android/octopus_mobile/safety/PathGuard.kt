package com.apk.claw.android.octopus_mobile.safety

import android.util.Log
import java.io.File

/**
 * 路径安全守卫 —— 从母体 runtime/safety/immunity/path_guard.py 移植.
 *
 * 防止 agent 读写敏感文件：
 *  - SSH 密钥（~/.ssh）
 *  - 云凭证（~/.aws, ~/.gcp, ~/.kube）
 *  - 系统文件（/etc/passwd, /etc/shadow）
 *  - Docker 配置（~/.docker）
 *  - GPG 密钥（~/.gnupg）
 *  - 沙箱逃逸（路径跳出指定目录）
 *
 * 用法：
 * ```kotlin
 * val verdict = PathGuard.check("/data/data/com.app/files/config.json")
 * if (!verdict.allow) { /* 阻止访问 */ }
 *
 * val verdict2 = PathGuard.check("/etc/passwd")
 * // verdict2.allow = false, reason = "sensitive_abs_path: /etc/passwd"
 * ```
 */
object PathGuard {

    private const val TAG = "PathGuard"

    // ── 敏感路径 ──────────────────────────────────────

    private val SENSITIVE_HOME_SUFFIXES = listOf(
        ".ssh",
        ".aws",
        ".gcp",
        ".kube",
        ".docker",
        ".gnupg",
        ".config/gh",
        ".netrc",
        ".pgpass",
    )

    private val SENSITIVE_ABS_PREFIXES = listOf(
        "/etc/passwd",
        "/etc/shadow",
        "/etc/sudoers",
        "/root",
        "/proc/self/environ",
        "/sys/class/",
        "/var/log/auth",
    )

    private val DOS_DEVICE_NAMES = setOf(
        "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
    )

    // ── 结果 ──────────────────────────────────────────

    data class PathVerdict(
        val allow: Boolean,
        val path: String,
        val resolved: String? = null,
        val reason: String = "",
    )

    // ── 检查 ──────────────────────────────────────────

    fun check(
        path: String,
        sandboxDir: String? = null,
        allowSensitive: Boolean = false,
        mustExist: Boolean = false,
    ): PathVerdict {
        if (path.isBlank()) {
            return PathVerdict(false, path, reason = "empty_path")
        }

        // DOS 设备名检查（Windows）
        if (hasDosDevice(path)) {
            return PathVerdict(false, path, reason = "dos_device_name")
        }

        // 解析路径
        val resolved = try {
            if (sandboxDir != null && !File(path).isAbsolute) {
                File(sandboxDir).resolve(path).canonicalPath
            } else {
                File(path).canonicalPath
            }
        } catch (e: Exception) {
            return PathVerdict(false, path, reason = "resolve_error: ${e.message}")
        }

        // 沙箱逃逸检查
        if (sandboxDir != null) {
            val base = try {
                File(sandboxDir).canonicalPath
            } catch (e: Exception) {
                sandboxDir
            }
            if (!resolved.startsWith(base)) {
                return PathVerdict(
                    false, path, resolved = resolved,
                    reason = "escapes_sandbox: not under $base",
                )
            }
        }

        // 敏感路径检查
        if (!allowSensitive) {
            val sensitiveReason = checkSensitive(resolved)
            if (sensitiveReason != null) {
                return PathVerdict(
                    false, path, resolved = resolved,
                    reason = sensitiveReason,
                )
            }
        }

        // 存在性检查
        if (mustExist && !File(resolved).exists()) {
            return PathVerdict(
                false, path, resolved = resolved,
                reason = "not_found",
            )
        }

        return PathVerdict(true, path, resolved = resolved)
    }

    fun isSafePath(
        path: String,
        sandboxDir: String? = null,
        allowSensitive: Boolean = false,
    ): Boolean {
        return check(path, sandboxDir = sandboxDir, allowSensitive = allowSensitive).allow
    }

    // ── 内部 ──────────────────────────────────────────

    private fun hasDosDevice(pathStr: String): Boolean {
        val normalized = pathStr.replace("\\", "/").lowercase()
        for (part in normalized.split("/")) {
            if (part.isBlank()) continue
            val stem = part.substringBefore(".")  // con.txt → con
            if (stem in DOS_DEVICE_NAMES) return true
        }
        return false
    }

    private fun checkSensitive(resolved: String): String? {
        val normalized = resolved.replace("\\", "/").lowercase()

        // 绝对路径前缀检查
        for (prefix in SENSITIVE_ABS_PREFIXES) {
            if (normalized.startsWith(prefix.lowercase())) {
                return "sensitive_abs_path: $prefix"
            }
        }

        // 用户目录下的敏感路径
        val home = System.getProperty("user.home") ?: return null
        val homeNormalized = home.replace("\\", "/").lowercase()
        if (normalized.startsWith(homeNormalized)) {
            val relPath = normalized.removePrefix(homeNormalized).removePrefix("/")
            for (suffix in SENSITIVE_HOME_SUFFIXES) {
                val suffixLc = suffix.lowercase()
                if (relPath == suffixLc || relPath.startsWith("$suffixLc/")) {
                    return "sensitive_home_path: ~/$suffix"
                }
            }
        }

        // Android 特有敏感路径
        val androidSensitive = listOf(
            "/data/data/",     // 其他 App 私有目录（不是自己的）
            "/data/system/",
            "/system/",
        )
        // 允许自己的 data 目录
        // (这个检查在 Android 上比较复杂，暂时只做基本检查)

        return null
    }
}
