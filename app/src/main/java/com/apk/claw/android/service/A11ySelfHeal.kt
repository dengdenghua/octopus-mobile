package com.apk.claw.android.service

import android.content.ComponentName
import android.content.Context
import com.apk.claw.android.shizuku.ShizukuManager
import com.apk.claw.android.shizuku.ShizukuShellService
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog

/**
 * 无障碍服务被系统杀掉后的「自愈」—— 本 App 独有、唯一能在服务被关之后主动复活的路径。
 *
 * 四层前台保活(自托管前台 / ForegroundService / AlarmManager 重启 / JobScheduler 守护)本质都只能
 * **防止被杀**;一旦 MIUI 等 force-stop 把无障碍开关直接扳回关,前台/闹钟/Job 全部失效
 * (force-stop 会清掉 pending 闹钟、暂停 Job、直接关无障碍)。但只要 Shizuku(shell uid)还在,
 * 就能用 `settings put secure` 把无障碍服务**写回启用列表** —— 普通 app 无 WRITE_SECURE_SETTINGS
 * 做不到这件事,这是本 App 借 Shizuku 才有的能力。
 *
 * 严格约束(避免「跟用户对着干」或「误伤别的 app 的无障碍服务」):
 *  1. 只在「本 App 的无障碍**曾成功连接过**」([KEY_A11Y_WANTED]=true,在 onServiceConnected 里置位)时
 *     才自愈 —— 从不主动开启用户从未开过的服务。
 *  2. **追加而非覆盖** enabled_accessibility_services —— 保留其它 app 已启用的无障碍服务,只把自己加回去。
 *  3. Shizuku 不可用 / 自己已在启用列表里 → 直接跳过,不做多余写入。
 */
object A11ySelfHeal {

    private const val TAG = "A11ySelfHeal"
    const val KEY_A11Y_WANTED = "a11y_keepalive_wanted"

    /** 无障碍成功连接时调用 —— 记录「用户要这个服务」,作为日后自愈的前置门。 */
    fun markWanted() {
        runCatching { KVUtils.putBoolean(KEY_A11Y_WANTED, true) }
    }

    /**
     * 若「曾启用过 + 当前实际关闭 + Shizuku 可用」→ 用 shell 把无障碍写回启用列表并置 accessibility_enabled=1。
     * @return true 仅当这次确实写回了启用列表;其余情况(无需自愈/无能力/已在列表)一律 false。
     */
    fun healIfNeeded(context: Context): Boolean {
        return runCatching {
            // 三个前置门任一不满足就不自愈:从没开过 / 还活着 / 没 shell 能力。
            val eligible = KVUtils.getBoolean(KEY_A11Y_WANTED, false) &&
                !ClawAccessibilityService.isConnected() &&
                ShizukuManager.isAvailable()
            if (!eligible) {
                false
            } else {
                val self = ComponentName(context, ClawAccessibilityService::class.java).flattenToString()
                // `settings get` 在值为空时会返回字面量 "null",要当空串处理,否则会污染启用列表。
                val raw = ShizukuShellService.getSetting("secure", "enabled_accessibility_services").orEmpty()
                val current = if (raw.equals("null", ignoreCase = true)) "" else raw
                val entries = current.split(':').filter { it.isNotBlank() }

                if (entries.any { it.equals(self, ignoreCase = true) }) {
                    // 已在启用列表里但 isRunning=false —— 多半是进程刚被杀还没重连,补个总开关兜底,不算「写回」。
                    ShizukuShellService.putSetting("secure", "accessibility_enabled", "1")
                    false
                } else {
                    val merged = (entries + self).joinToString(":")
                    val listOk =
                        ShizukuShellService.putSetting("secure", "enabled_accessibility_services", merged) == true
                    val flagOk = ShizukuShellService.putSetting("secure", "accessibility_enabled", "1") == true
                    XLog.i(TAG, "self-heal a11y via shizuku: enabledList=$listOk flag=$flagOk")
                    listOk && flagOk
                }
            }
        }.getOrElse {
            XLog.w(TAG, "self-heal failed", it)
            false
        }
    }
}
