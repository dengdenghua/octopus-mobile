package com.apk.claw.android.ui.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.apk.claw.android.ui.compose.theme.OctopusTheme

/**
 * Compose 入口 Activity —— 只负责挂载 [OctopusApp] 到 window。
 * 真正的导航/UI 树在 [NavigationGraph.kt]。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OctopusTheme {
                OctopusApp()
            }
        }
    }
}
