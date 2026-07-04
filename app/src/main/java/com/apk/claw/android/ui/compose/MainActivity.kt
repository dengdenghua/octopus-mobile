package com.apk.claw.android.ui.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import com.apk.claw.android.base.KeepAliveBack
import com.apk.claw.android.ui.compose.theme.OctopusTheme
import com.apk.claw.android.update.AppUpdater

/**
 * Compose 入口 Activity —— 只负责挂载 [OctopusApp] 到 window。
 * 真正的导航/UI 树在 [NavigationGraph.kt]。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 无障碍保活:根页滑动返回改退后台,别 finish 掉进程连带杀无障碍服务。
        KeepAliveBack.install(this)
        setContent {
            OctopusTheme {
                OctopusApp()
                // 在线更新:根部挂弹窗宿主 + 启动静默查一次(有新版才弹,已最新无感)。
                AppUpdateHost()
                LaunchedEffect(Unit) { runCatching { AppUpdater.check() } }
            }
        }
    }
}
