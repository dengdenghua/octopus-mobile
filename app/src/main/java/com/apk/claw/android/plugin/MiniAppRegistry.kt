package com.apk.claw.android.plugin

import android.content.Context
import android.content.Intent

/**
 * 小程序注册表 —— 持有已安装的 `type=mini-app` 插件,供宫格 UI 列举 + 启动.
 *
 * 由 [PluginManager] 在 loadAll 时填充。启动走 [MiniAppActivity]。
 */
object MiniAppRegistry {

    @Volatile
    private var apps: List<PluginManifest> = emptyList()

    fun set(list: List<PluginManifest>) { apps = list }

    fun all(): List<PluginManifest> = apps

    fun get(id: String): PluginManifest? = apps.firstOrNull { it.id == id }

    /** 启动一个小程序。 */
    fun launch(context: Context, id: String) {
        val intent = Intent(context, MiniAppActivity::class.java).apply {
            putExtra(MiniAppActivity.EXTRA_PLUGIN_ID, id)
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
