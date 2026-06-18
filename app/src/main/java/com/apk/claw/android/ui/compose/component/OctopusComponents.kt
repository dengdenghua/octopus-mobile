package com.apk.claw.android.ui.compose.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.ui.compose.theme.OctopusColors

// ── Spacing Tokens ──
object OctopusSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
}

// ── Shape Tokens ──
// 已统一到 OctopusShape（见 OctopusDesign.kt），保留 OctopusShapes 作为别名向后兼容
object OctopusShapes {
    val small get() = com.apk.claw.android.ui.compose.theme.OctopusShape.small
    val medium get() = com.apk.claw.android.ui.compose.theme.OctopusShape.medium
    val large get() = com.apk.claw.android.ui.compose.theme.OctopusShape.large
    val xl get() = com.apk.claw.android.ui.compose.theme.OctopusShape.xl
    val capsule get() = com.apk.claw.android.ui.compose.theme.OctopusShape.capsule
}

// ── Typography Tokens ──
// 对齐 XML type.xml 的 TS.* 层级，补充 lineHeight（之前缺失）
object OctopusTypography {
    // 标题级（对齐 TS.Headline / TS.Title）
    val h1 = TextStyle(22.sp, FontWeight.Bold, lineHeight = 28.sp)
    val h2 = TextStyle(18.sp, FontWeight.Bold, lineHeight = 24.sp)
    val h3 = TextStyle(15.sp, FontWeight.SemiBold, lineHeight = 20.sp)
    // 正文级（对齐 TS.Body）
    val body = TextStyle(14.sp, FontWeight.Normal, lineHeight = 20.sp)
    val bodyEmphasized = TextStyle(14.sp, FontWeight.Medium, lineHeight = 20.sp)
    // 辅助级（对齐 TS.Caption / TS.Label）
    val caption = TextStyle(12.sp, FontWeight.Normal, lineHeight = 16.sp)
    val captionEmphasized = TextStyle(12.sp, FontWeight.Medium, lineHeight = 16.sp)
    val tiny = TextStyle(10.sp, FontWeight.Normal, lineHeight = 14.sp)

    /** 旧 API 兼容：Pair<sp, FontWeight> */
    val h1Pair get() = h1.size to h1.weight
    val h2Pair get() = h2.size to h2.weight
    val h3Pair get() = h3.size to h3.weight
    val bodyPair get() = body.size to body.weight
    val captionPair get() = caption.size to caption.weight
    val tinyPair get() = tiny.size to tiny.weight
}

/** Typography 文本样式数据类 */
data class TextStyle(
    val size: androidx.compose.ui.unit.TextUnit,
    val weight: FontWeight,
    val lineHeight: androidx.compose.ui.unit.TextUnit,
)

// ── Capsule Button ──
@Composable
fun CapsuleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    icon: ImageVector? = null,
) {
    val bg = if (accent) OctopusColors.Primary else OctopusColors.SurfaceVariant
    val fg = if (accent) Color.White else OctopusColors.TextPrimary
    Row(
        modifier = modifier
            .clip(OctopusShapes.capsule)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        icon?.let {
            Icon(it, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(OctopusSpacing.xs))
        }
        Text(text, color = fg, fontSize = OctopusTypography.body.size, fontWeight = FontWeight.Medium)
    }
}

// ── Glass Card ──
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(OctopusShapes.large)
            .background(OctopusColors.Surface.copy(alpha = 0.7f))
            .padding(OctopusSpacing.lg),
    ) {
        content()
    }
}

// ── App Card (FeatureHub / Browser 通用) ──
@Composable
fun AppCard(
    emoji: String,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(OctopusShapes.large)
            .background(OctopusColors.Surface)
            .clickable(onClick = onClick)
            .padding(OctopusSpacing.lg),
    ) {
        Text(emoji, fontSize = 28.sp)
        Spacer(Modifier.height(OctopusSpacing.sm))
        Text(title, color = OctopusColors.TextPrimary, fontSize = OctopusTypography.h3.size, fontWeight = OctopusTypography.h3.weight)
        subtitle?.let {
            Spacer(Modifier.height(OctopusSpacing.xs))
            Text(it, color = OctopusColors.TextMuted, fontSize = OctopusTypography.caption.size)
        }
    }
}

// ── Section Header ──
@Composable
fun SectionHeader(
    title: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = OctopusColors.TextPrimary,
            fontSize = OctopusTypography.h3.size,
            fontWeight = OctopusTypography.h3.weight,
            modifier = Modifier.weight(1f),
        )
        action?.let {
            Text(
                it,
                color = OctopusColors.Primary,
                fontSize = OctopusTypography.caption.size,
                modifier = Modifier.clickable { onAction?.invoke() },
            )
        }
    }
}

// ── Animated List Item ──
@Composable
fun <T> AnimatedListItem(
    index: Int,
    item: T,
    content: @Composable (T) -> Unit,
) {
    val alpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 300, delayMillis = index * 50),
        label = "list_item_alpha",
    )
    Box(modifier = Modifier.alpha(alpha)) {
        content(item)
    }
}

// ── Top Bar Back Button (统一风格) ──
@Composable
fun TopBarBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = OctopusColors.TextPrimary,
        )
    }
}

// ── Status Chip ──
@Composable
fun StatusChip(
    text: String,
    color: Color = OctopusColors.Primary,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(OctopusShapes.capsule)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.xs),
    ) {
        Text(text, color = color, fontSize = OctopusTypography.tiny.size, fontWeight = FontWeight.Medium)
    }
}

// ── Loading Dot Animation ──
@Composable
fun LoadingDots(
    modifier: Modifier = Modifier,
    color: Color = OctopusColors.Primary,
) {
    // 修复：原 animateFloatAsState(targetValue=0f) 恒为 0 无可见动画
    // 改用 rememberInfiniteTransition 实现真正的循环跳动
    val transition = rememberInfiniteTransition(label = "loading_dots")
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.xs)) {
        repeat(3) { i ->
            val scale by transition.animateFloat(
                initialValue = 0.6f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = i * 150),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot_scale_$i",
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .scale(scale)
                    .clip(OctopusShapes.capsule)
                    .background(color),
            )
        }
    }
}
