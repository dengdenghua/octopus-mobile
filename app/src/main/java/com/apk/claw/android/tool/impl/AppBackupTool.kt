package com.apk.claw.android.tool.impl

import com.apk.claw.android.shizuku.ShizukuShellService
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * App 数据备份工具 —— AI NAS 核心功能。
 *
 * 利用 shell UID 访问 /sdcard/Android/data/<package>/ 目录，
 * 将指定 App 的 sdcard 数据备份到指定位置。
 *
 * 可备份任何 App 的 sdcard 数据（微信聊天记录、支付宝缓存等）。
 */
class AppBackupTool : BaseTool() {

    companion object {
        private const val DEFAULT_BACKUP_DIR = "/sdcard/OctopusBackup"
    }

    override fun getName(): String = "backup_app"

    override fun getDisplayName(): String = if (useChineseDescription) "App备份" else "App Backup"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "action",
            "string",
            "Action: 'backup' (backup app data), 'restore' (restore from backup), 'list' (list available backups).",
            true
        ),
        ToolParameter(
            "package",
            "string",
            "App package name (e.g. 'com.tencent.mm' for WeChat). Required for backup/restore.",
            false
        ),
        ToolParameter(
            "backup_dir",
            "string",
            "Optional: custom backup directory. Default: /sdcard/OctopusBackup",
            false
        )
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val action = requireString(params, "action")
        val backupDir = optionalString(params, "backup_dir", DEFAULT_BACKUP_DIR)

        return when (action) {
            "backup" -> {
                val pkg = requireString(params, "package")
                val result = ShizukuShellService.backupAppData(pkg, backupDir)
                    ?: return ToolResult.error("Shizuku not available. This tool requires Shizuku shell access.")
                if (result.startsWith("Backup failed:")) {
                    ToolResult.error(result)
                } else {
                    ToolResult.success(result)
                }
            }
            "restore" -> {
                val pkg = requireString(params, "package")
                val srcDir = "$backupDir/$pkg"
                val dstDir = "/sdcard/Android/data/$pkg"

                // 检查备份是否存在
                val checkResult = ShizukuShellService.getFileInfo(srcDir)
                if (checkResult == null || checkResult.startsWith("Error:")) {
                    return ToolResult.error("No backup found for $pkg at $srcDir")
                }

                // 恢复数据
                val result = ShizukuShellService.copyFile(srcDir, dstDir)
                    ?: return ToolResult.error("Shizuku not available.")
                if (result == true) {
                    ToolResult.success("Restored $pkg from $srcDir to $dstDir")
                } else {
                    ToolResult.error("Failed to restore $pkg from $srcDir")
                }
            }
            "list" -> {
                val listing = ShizukuShellService.listFiles(backupDir)
                    ?: return ToolResult.error("Shizuku not available.")
                if (listing.startsWith("Error:") || listing.isBlank()) {
                    ToolResult.success("No backups found in $backupDir")
                } else {
                    ToolResult.success("Available backups in $backupDir:\n$listing")
                }
            }
            else -> ToolResult.error("Unknown action: $action. Use 'backup', 'restore', or 'list'.")
        }
    }

    override fun getDescriptionEN(): String = """
        Backup and restore app data using Shizuku shell access.
        Can access /sdcard/Android/data/<package>/ for any app (not possible for normal apps on Android 11+).
        
        Actions:
        - backup: Copy app data from /sdcard/Android/data/<package>/ to backup directory
        - restore: Restore app data from backup directory
        - list: List available backups
        
        Examples:
        - Backup WeChat: action="backup", package="com.tencent.mm"
        - Backup to custom dir: action="backup", package="com.eg.android.AlipayGphone", backup_dir="/sdcard/MyBackup"
        - List backups: action="list"
        - Restore WeChat: action="restore", package="com.tencent.mm"
        
        Note: Only backs up sdcard data, not internal /data/data/ (requires root).
    """.trimIndent()

    override fun getDescriptionCN(): String = """
        使用 Shizuku shell 权限备份和恢复 App 数据。
        可以访问 /sdcard/Android/data/<package>/ 下的任何 App 数据（Android 11+ 普通 App 无法做到）。
        
        操作：
        - backup: 从 /sdcard/Android/data/<package>/ 复制到备份目录
        - restore: 从备份目录恢复 App 数据
        - list: 列出可用备份
        
        示例：
        - 备份微信: action="backup", package="com.tencent.mm"
        - 自定义目录备份: action="backup", package="com.eg.android.AlipayGphone", backup_dir="/sdcard/MyBackup"
        - 列出备份: action="list"
        - 恢复微信: action="restore", package="com.tencent.mm"
        
        注意：只备份 sdcard 数据，不含内部 /data/data/（需要 root）。
    """.trimIndent()
}
