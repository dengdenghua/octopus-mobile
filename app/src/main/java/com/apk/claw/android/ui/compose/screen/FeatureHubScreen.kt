package com.apk.claw.android.ui.compose.screen

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.R
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.featurescreens.BrowserSettingsActivity
import com.apk.claw.android.ui.featurescreens.CloudDriveActivity
import com.apk.claw.android.ui.featurescreens.EvolutionActivity
import com.apk.claw.android.ui.featurescreens.ExtensionsActivity
import com.apk.claw.android.ui.featurescreens.MemoryActivity
import com.apk.claw.android.ui.featurescreens.MultiWindowActivity
import com.apk.claw.android.ui.featurescreens.RoutinesActivity
import com.apk.claw.android.ui.featurescreens.SkillsActivity
import com.apk.claw.android.ui.featurescreens.TrustCenterActivity
import com.apk.claw.android.ui.featurescreens.VideoLibraryActivity

/**
 * 「功能」中心 —— 把被发现页改版挤掉的子页入口重新汇集到一个底部 Tab。
 * 视觉参考 iOS「设置」分组列表:大标题 + 分组圆角卡 + 实色圆角方形图标 + 右箭头。
 */
private data class FeatureItem(
    val labelRes: Int,
    val icon: ImageVector,
    val tint: Color,
    val target: Class<*>,
)

private val SkillTint = Color(0xFF74A7FF)
private val PluginTint = Color(0xFFEAB85C)
private val RoutineTint = Color(0xFFFF9F5A)
private val CloudTint = Color(0xFF42C893)
private val MemoryTint = Color(0xFF9B8CFF)
private val VideoTint = Color(0xFFFF6B6B)
private val WindowTint = Color(0xFF5AA0FF)
private val EvolveTint = Color(0xFF34C7A8)
private val TrustTint = Color(0xFF8FD8B1)
private val BrowserTint = Color(0xFF5DBCD8)

@Composable
fun FeatureHubScreen() {
    val ctx = LocalContext.current
    val sections = listOf(
        R.string.feat_section_automation to listOf(
            FeatureItem(R.string.feat_skills, Icons.Filled.Bolt, SkillTint, SkillsActivity::class.java),
            FeatureItem(R.string.feat_extensions, Icons.Filled.Extension, PluginTint, ExtensionsActivity::class.java),
            FeatureItem(R.string.feat_routines, Icons.Filled.Schedule, RoutineTint, RoutinesActivity::class.java),
        ),
        R.string.feat_section_data to listOf(
            FeatureItem(R.string.feat_clouddrive, Icons.Filled.CloudQueue, CloudTint, CloudDriveActivity::class.java),
            FeatureItem(R.string.feat_memory, Icons.Filled.Psychology, MemoryTint, MemoryActivity::class.java),
            FeatureItem(R.string.feat_video, Icons.Filled.Movie, VideoTint, VideoLibraryActivity::class.java),
        ),
        R.string.feat_section_advanced to listOf(
            FeatureItem(R.string.feat_browser_settings, Icons.Filled.Public, BrowserTint, BrowserSettingsActivity::class.java),
            FeatureItem(R.string.feat_multiwindow, Icons.Filled.GridView, WindowTint, MultiWindowActivity::class.java),
            FeatureItem(R.string.feat_evolution, Icons.Filled.TrendingUp, EvolveTint, EvolutionActivity::class.java),
            FeatureItem(R.string.feat_trust, Icons.Filled.Shield, TrustTint, TrustCenterActivity::class.java),
        ),
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(OctopusColors.Background).statusBarsPadding(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 112.dp),
    ) {
        item {
            Text(
                stringResource(R.string.nav_features),
                color = OctopusColors.TextPrimary,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
            )
        }
        sections.forEach { (headerRes, items) ->
            item { SectionHeader(stringResource(headerRes)) }
            item { FeatureGroup(items) { ctx.open(it.target) } }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        color = OctopusColors.TextMuted,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun FeatureGroup(items: List<FeatureItem>, onClick: (FeatureItem) -> Unit) {
    Surface(
        color = OctopusColors.Surface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column {
            items.forEachIndexed { i, item ->
                FeatureRow(item) { onClick(item) }
                if (i != items.lastIndex) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 64.dp)
                            .height(0.6.dp)
                            .background(OctopusColors.Border.copy(alpha = 0.6f)),
                    )
                }
            }
        }
    }
}

@Composable
private fun FeatureRow(item: FeatureItem, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Box(
            modifier = Modifier.size(34.dp).background(item.tint, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(item.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(
            stringResource(item.labelRes),
            color = OctopusColors.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = OctopusColors.TextMuted.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp),
        )
    }
}

private fun Context.open(target: Class<*>) {
    runCatching { startActivity(Intent(this, target)) }
}
