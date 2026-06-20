package com.apk.claw.android.ui.compose.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.ui.compose.theme.OctopusBackground
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusIconSize
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusType

// OctopusShapes 已统一到 OctopusShape（见 OctopusDesign.kt），以下为向后兼容别名
object OctopusShapes {
    val small get() = OctopusShape.small
    val medium get() = OctopusShape.medium
    val large get() = OctopusShape.large
    val xl get() = OctopusShape.xl
    val capsule get() = OctopusShape.capsule
}

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
    val fg = if (accent) OctopusColors.OnPrimary else OctopusColors.TextPrimary
    Row(
        modifier = modifier
            .clip(OctopusShape.capsule)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        icon?.let {
            Icon(it, contentDescription = null, tint = fg, modifier = Modifier.size(OctopusIconSize.small))
            Spacer(Modifier.width(OctopusSpacing.xs))
        }
        Text(text, color = fg, fontSize = OctopusType.bodyStrong, fontWeight = FontWeight.Medium)
    }
}

// ── Glass Card ──
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = OctopusShape.large
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (OctopusColors.isLight) 0.28f else 0.06f),
                        OctopusBackground.glassSurface,
                    )
                ),
                shape,
            )
            .border(1.dp, OctopusBackground.glassBorder, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        content = content,
    )
}

@Composable
fun GlassPill(
    icon: ImageVector,
    text: String,
    tint: Color,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .clip(OctopusShape.capsule)
            .background(
                if (selected) tint.copy(alpha = 0.18f)
                else Color.White.copy(alpha = if (OctopusColors.isLight) 0.42f else 0.08f),
            )
            .border(
                1.dp,
                if (selected) tint.copy(alpha = 0.42f) else OctopusBackground.glassBorder,
                OctopusShape.capsule,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(OctopusIconSize.small))
        Spacer(Modifier.width(OctopusSpacing.xs))
        Text(
            text,
            color = if (selected) tint else OctopusColors.TextSecondary,
            fontSize = OctopusType.label,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
fun GlassTextPill(
    text: String,
    tint: Color,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(OctopusShape.capsule)
            .background(
                if (selected) tint.copy(alpha = 0.20f)
                else tint.copy(alpha = 0.12f),
            )
            .border(
                1.dp,
                // 未选中也给清晰的描边，避免胶囊在渐变玻璃上"隐形"
                if (selected) tint.copy(alpha = 0.55f) else tint.copy(alpha = 0.45f),
                OctopusShape.capsule,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            // 选中=同色高亮；未选中=高对比深色文字（同色字压同色底对比太低、会看不清）
            color = if (selected) tint else OctopusColors.TextSecondary,
            fontSize = OctopusType.tag,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
fun GlassBottomSheet(
    modifier: Modifier = Modifier,
    maxHeight: Dp? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (maxHeight != null) Modifier.heightIn(max = maxHeight) else Modifier)
            .clip(OctopusShape.xl)
            .background(OctopusColors.SurfaceDeep, OctopusShape.xl)
            .border(1.dp, OctopusBackground.glassBorder, OctopusShape.xl),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(OctopusSpacing.lg),
            content = content,
        )
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
            .clip(OctopusShape.large)
            .background(OctopusColors.Surface)
            .clickable(onClick = onClick)
            .padding(OctopusSpacing.lg),
    ) {
        Text(emoji, fontSize = 28.sp)
        Spacer(Modifier.height(OctopusSpacing.sm))
        Text(title, color = OctopusColors.TextPrimary, fontSize = OctopusType.bodyLg, fontWeight = FontWeight.SemiBold)
        subtitle?.let {
            Spacer(Modifier.height(OctopusSpacing.xs))
            Text(it, color = OctopusColors.TextMuted, fontSize = OctopusType.label)
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
            fontSize = OctopusType.bodyLg,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        action?.let {
            Text(
                it,
                color = OctopusColors.Primary,
                fontSize = OctopusType.label,
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
            .clip(OctopusShape.capsule)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.xs),
    ) {
        Text(text, color = color, fontSize = OctopusType.tag, fontWeight = FontWeight.Medium)
    }
}

// ── Loading Dot Animation ──
@Composable
fun LoadingDots(
    modifier: Modifier = Modifier,
    color: Color = OctopusColors.Primary,
) {
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
                    .clip(OctopusShape.capsule)
                    .background(color),
            )
        }
    }
}
