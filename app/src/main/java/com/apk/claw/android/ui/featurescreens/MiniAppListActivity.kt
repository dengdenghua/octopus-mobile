package com.apk.claw.android.ui.featurescreens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.plugin.MiniAppRegistry
import com.apk.claw.android.plugin.PermissionGate
import com.apk.claw.android.plugin.PluginManifest

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
    var expandedId by remember { mutableStateOf<String?>(null) }

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
            apps.forEach { m ->
                MiniAppCard(
                    m = m,
                    expanded = expandedId == m.id,
                    onToggleExpand = { expandedId = if (expandedId == m.id) null else m.id },
                    onLaunch = { MiniAppRegistry.launch(ctx, m.id) },
                )
            }
        }
    }
}

@Composable
private fun MiniAppCard(
    m: PluginManifest,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onLaunch: () -> Unit,
) {
    // 每次 recompose 时从 KVUtils 读最新授权集(小程序数量少,可接受)
    val grantState = remember(m.id, expanded) {
        mutableStateMapOf<String, Boolean>().also { map ->
            val granted = PermissionGate.grantedCaps(m.id)
            m.allowDevice.forEach { map[it] = it in granted }
            if (m.allowPay) map["pay"] = "pay" in granted
        }
    }

    FCard {
        // 头部行:名称 + 展开箭头
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleExpand),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(m.name.ifBlank { m.id }, color = FText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (m.description.isNotBlank()) {
                    Text(m.description, color = FMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null, tint = FMuted, modifier = Modifier.size(20.dp)
            )
        }

        // 展开区:权限管理 + 启动
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(top = 12.dp)) {

                // 声明的 tools(只读展示)
                if (m.allowTools.isNotEmpty()) {
                    PermRow(label = "可用工具") {
                        Text(m.allowTools.joinToString(", "), color = FMuted, fontSize = 12.sp)
                    }
                }

                // 声明的 hosts(只读展示)
                if (m.allowHosts.isNotEmpty()) {
                    PermRow(label = "可访问域名") {
                        Text(m.allowHosts.joinToString(", "), color = FMuted, fontSize = 12.sp)
                    }
                }

                // 设备自动化能力(每个 cap 一个开关)
                m.allowDevice.forEach { cap ->
                    val granted = grantState[cap] ?: false
                    PermRow(label = "设备能力: $cap") {
                        Switch(
                            checked = granted,
                            onCheckedChange = { on ->
                                if (on) PermissionGate.grant(m.id, cap)
                                else PermissionGate.revoke(m.id, cap)
                                grantState[cap] = on
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = FPrimary,
                                checkedTrackColor = FPrimary.copy(alpha = 0.3f),
                            ),
                            modifier = Modifier.scale(0.8f)
                        )
                    }
                }

                // 计费权限
                if (m.allowPay) {
                    val payGranted = grantState["pay"] ?: false
                    PermRow(label = "积分支付") {
                        Switch(
                            checked = payGranted,
                            onCheckedChange = { on ->
                                if (on) PermissionGate.grant(m.id, "pay")
                                else PermissionGate.revoke(m.id, "pay")
                                grantState["pay"] = on
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = FPrimary,
                                checkedTrackColor = FPrimary.copy(alpha = 0.3f),
                            ),
                            modifier = Modifier.scale(0.8f)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 启动按钮
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        "打开",
                        color = FPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable(onClick = onLaunch)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PermRow(label: String, trailing: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = FSub, fontSize = 12.sp, modifier = Modifier.weight(1f))
        trailing()
    }
}
