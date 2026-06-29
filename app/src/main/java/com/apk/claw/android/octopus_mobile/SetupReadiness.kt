package com.apk.claw.android.octopus_mobile

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.shizuku.ShizukuManager
import com.apk.claw.android.utils.KVUtils

/**
 * 统一的"准备就绪度"聚合：把分散在各处的权限/配置就绪状态汇成一个完成度，
 * 供信任中心的就绪卡片（及后续首启引导向导）复用。
 *
 * 纯领域逻辑、无 Compose 依赖，每项检查都用 runCatching 兜底，绝不抛出。
 */
object SetupReadiness {

    /** critical=true 的项未就绪时，Agent 基本无法工作（核心前置）。 */
    data class Item(val label: String, val ready: Boolean, val critical: Boolean)

    data class Result(val items: List<Item>) {
        val total: Int get() = items.size
        val readyCount: Int get() = items.count { it.ready }
        val percent: Int get() = if (total == 0) 100 else readyCount * 100 / total
        val missingCritical: List<Item> get() = items.filter { it.critical && !it.ready }
        val isReady: Boolean get() = missingCritical.isEmpty()
    }

    fun check(ctx: Context): Result = Result(
        listOf(
            Item("无障碍服务", isAccessibilityReady(), critical = true),
            Item("LLM 已配置", runCatching { KVUtils.hasLlmConfig() }.getOrDefault(false), critical = true),
            Item("通知权限", isNotificationReady(ctx), critical = false),
            Item("后台保活（电池白名单）", isBatteryReady(ctx), critical = false),
            Item("悬浮窗", isOverlayReady(ctx), critical = false),
            Item("Shizuku 高级权限", runCatching { ShizukuManager.isAvailable() }.getOrDefault(false), critical = false),
        )
    )

    private fun isAccessibilityReady(): Boolean =
        runCatching { ClawAccessibilityService.isRunning() }.getOrDefault(false)

    private fun isNotificationReady(ctx: Context): Boolean =
        runCatching { NotificationManagerCompat.from(ctx).areNotificationsEnabled() }.getOrDefault(false)

    private fun isBatteryReady(ctx: Context): Boolean = runCatching {
        (ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.isIgnoringBatteryOptimizations(ctx.packageName) ?: false
    }.getOrDefault(false)

    private fun isOverlayReady(ctx: Context): Boolean =
        runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false)

    @Suppress("unused")
    private fun isStorageReady(ctx: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) true
        else androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)
}
