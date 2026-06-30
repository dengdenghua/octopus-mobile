package com.apk.claw.android.ui.featurescreens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.R
import com.apk.claw.android.octopus_mobile.browser.BrowserEngineFactory
import com.apk.claw.android.octopus_mobile.browser.SearchEngines
import com.apk.claw.android.utils.KVUtils

/**
 * 浏览器设置 —— 默认搜索引擎选择 + 内核信息 + 插件入口。
 * 视觉与「功能」中心一致(iOS 分组列表)。只暴露真实可用项,不放假开关。
 */
class BrowserSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFeatureContent { BrowserSettingsScreen(onBack = { finish() }) }
    }
}

private val ChipBg = Color(0xFF1A2029)

@Composable
private fun BrowserSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var engineId by remember { mutableStateOf(KVUtils.getSearchEngine()) }
    val kernel = remember { runCatching { BrowserEngineFactory.selectBest(ctx).name }.getOrDefault("—") }

    FeatureScaffold(title = stringResource(R.string.browser_settings_title), onBack = onBack) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            FSectionTitle(stringResource(R.string.browser_settings_search_engine))
            GroupCard {
                SearchEngines.ALL.forEachIndexed { i, e ->
                    EngineRow(
                        tag = e.tag,
                        name = e.label,
                        selected = e.id == engineId,
                        onClick = { KVUtils.setSearchEngine(e.id); engineId = e.id },
                    )
                    if (i != SearchEngines.ALL.lastIndex) RowDivider()
                }
            }

            FSectionTitle(stringResource(R.string.browser_settings_engine_kernel))
            GroupCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Text(stringResource(R.string.browser_settings_engine_kernel), color = FText, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Text(kernel, color = FMuted, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun GroupCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = FSurface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(content = content)
    }
}

@Composable
private fun RowDivider() {
    Box(modifier = Modifier.fillMaxWidth().padding(start = 16.dp).height(0.6.dp).background(FBorder.copy(alpha = 0.6f)))
}

@Composable
private fun EngineRow(tag: String, name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        Box(
            modifier = Modifier.size(28.dp).background(ChipBg, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(tag.take(1), color = FText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(12.dp))
        Text(name, color = FText, fontSize = 16.sp, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = FPrimary, modifier = Modifier.size(20.dp))
    }
}
