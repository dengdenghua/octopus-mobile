package com.apk.claw.android.ui.featurescreens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.plugin.MiniAppRegistry

/**
 * 小程序列表 —— 列出已安装的 `type=mini-app` 插件,点击经 [MiniAppRegistry.launch] 启动.
 *
 * 这是自建插件生态里"你的小程序"的用户入口(Stage 4)。小程序运行时在
 * [com.apk.claw.android.plugin.MiniAppActivity](WebView + octopus.* 受控桥)。
 */
class MiniAppListActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFeatureContent { MiniAppListScreen(onBack = { finish() }) }
    }
}

@Composable
private fun MiniAppListScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val apps = remember { MiniAppRegistry.all() }
    FeatureScaffold(title = "小程序", onBack = onBack) {
        if (apps.isEmpty()) {
            Text(
                "还没有安装小程序。安装 type=mini-app 的插件后会出现在这里。",
                color = FMuted, fontSize = 14.sp,
                modifier = Modifier.padding(20.dp)
            )
            return@FeatureScaffold
        }
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            FCard {
                apps.forEach { m ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { MiniAppRegistry.launch(ctx, m.id) }
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Text(m.name.ifBlank { m.id }, color = FText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        if (m.description.isNotBlank()) {
                            Text(m.description, color = FMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }
        }
    }
}
