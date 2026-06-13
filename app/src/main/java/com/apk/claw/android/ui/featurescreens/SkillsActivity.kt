package com.apk.claw.android.ui.featurescreens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.utils.KVUtils

/**
 * 技能 / 能力 —— 本地管理 Agent 可调用的工具:列出全部,按需启停。
 * 核心控制/感知工具(点击/滑动/截屏/看屏…)锁定不可关,以免关掉就没法自动化。
 * 停用通过 [KVUtils] 持久化,Agent 的工具规格在 LangChain4jToolBridge 据此过滤。
 */
class SkillsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SkillsScreen(onBack = { finish() }) }
        runCatching { window.statusBarColor = android.graphics.Color.BLACK }
    }
}

/** 核心工具:Agent 自动化的地基,不允许停用。 */
private val CORE = setOf(
    "tap", "long_press", "swipe", "input_text", "text_input", "find_node_info",
    "scroll_to_find", "open_app", "take_screenshot", "get_screen_info", "system_key",
    "look_at_screen", "wait", "finish", "finish_task",
)

@Composable
private fun SkillsScreen(onBack: () -> Unit) {
    val tools = remember {
        ToolRegistry.getInstance().getAllTools().sortedWith(
            compareBy({ it.getName() !in CORE }, { it.getName() })   // 核心在前
        )
    }
    var rev by remember { mutableStateOf(0) }

    FeatureScaffold(title = "技能 / 能力", onBack = onBack) {
        Text(
            "Agent 能调用的工具共 ${tools.size} 个。核心控制/感知工具锁定;其余可按需停用(停用后 Agent 不再使用该能力,降低误操作/隐私面)。",
            color = FMuted, fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(tools, key = { it.getName() }) { t ->
                val name = t.getName()
                val core = name in CORE
                val enabled = remember(rev, name) { ToolRegistry.getInstance().isToolEnabled(name) }
                FCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(runCatching { t.getDisplayName() }.getOrDefault(name), color = FText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.width(6.dp))
                                Text(name, color = FMuted, fontSize = 10.sp)
                            }
                            val desc = runCatching { t.getDescription() }.getOrDefault("")
                            if (desc.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(desc, color = FSub, fontSize = 12.sp, maxLines = 2)
                            }
                        }
                        if (core) {
                            Text("核心", color = FMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp))
                        } else {
                            Text(
                                if (enabled) "停用" else "启用",
                                color = if (enabled) FPrimary else FMuted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clickable { KVUtils.setToolDisabled(name, enabled); rev++ }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
