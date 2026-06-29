package com.apk.claw.android.ui.compose.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.awaitEachGesture
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.ui.compose.theme.OctopusBackground
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusGlass
import com.apk.claw.android.ui.compose.theme.OctopusGlassMaterial
import com.apk.claw.android.ui.compose.theme.OctopusGlassQuality
import com.apk.claw.android.ui.compose.theme.OctopusIconSize
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusThemeStyle
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

@Composable
fun LiquidGlassLayer(
    shape: Shape,
    modifier: Modifier = Modifier,
    blurRadius: Dp = OctopusGlass.blurRadius,
    tint: Color = OctopusBackground.glassSurface,
    tintAlpha: Float = 1f,
    highlightIntensity: Float = OctopusGlass.highlightIntensity,
    refractionBoost: Float = 1f,
    focalX: Float = 0.24f,
    focalY: Float = 0.18f,
    material: OctopusGlassMaterial = OctopusGlassMaterial.Card,
) {
    if (OctopusThemeStyle.isStandard) {
        Box(
            modifier = modifier
                .clip(shape)
                .background(OctopusBackground.cardSurface, shape),
        )
        return
    }
    val transition = rememberInfiniteTransition(label = "liquid-glass")
    val shimmer by transition.animateFloat(
        initialValue = -0.35f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "liquid-glass-shimmer",
    )
    val ripple by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "liquid-glass-ripple",
    )
    val quality = OctopusGlass.quality
    val refraction = OctopusGlass.refractionOffset * refractionBoost
    val edgeGlow = OctopusGlass.edgeGlowAlpha * highlightIntensity
    val innerShadow = OctopusGlass.innerShadowAlpha
    val noiseAlpha = OctopusGlass.noiseAlpha
    val light = OctopusColors.isLight
    val useRefraction = OctopusGlass.useRefraction
    val useNoise = OctopusGlass.useNoise
    val useDynamic = OctopusGlass.useDynamicHighlight
    val surfaceScale = when (quality) {
        OctopusGlassQuality.Low -> 1.012f
        OctopusGlassQuality.Medium -> 1.024f
        OctopusGlassQuality.High -> 1.035f
        OctopusGlassQuality.Ultra -> 1.052f
    }
    val shimmerAlpha = when (quality) {
        OctopusGlassQuality.Low -> 0f
        OctopusGlassQuality.Medium -> 0.18f
        OctopusGlassQuality.High -> 0.32f
        OctopusGlassQuality.Ultra -> 0.42f
    } * highlightIntensity
    val causticAlpha = when (quality) {
        OctopusGlassQuality.Low -> 0f
        OctopusGlassQuality.Medium -> 0.05f
        OctopusGlassQuality.High -> 0.09f
        OctopusGlassQuality.Ultra -> 0.15f
    } * highlightIntensity
    val thickness = when (material) {
        OctopusGlassMaterial.Thin -> 0.70f
        OctopusGlassMaterial.Card -> 1.00f
        OctopusGlassMaterial.Dock -> 1.28f
        OctopusGlassMaterial.Sheet -> 1.18f
    }
    val readabilityMist = when (material) {
        OctopusGlassMaterial.Thin -> if (light) 0.05f else 0.08f
        OctopusGlassMaterial.Card -> if (light) 0.08f else 0.12f
        OctopusGlassMaterial.Dock -> if (light) 0.11f else 0.16f
        OctopusGlassMaterial.Sheet -> if (light) 0.14f else 0.20f
    }
    val topCutAlpha = (if (light) 0.58f else 0.26f) * highlightIntensity * thickness
    val bottomCutAlpha = (if (light) 0.18f else 0.42f) * thickness
    val outerStrokeWidth = when (material) {
        OctopusGlassMaterial.Thin -> 0.9.dp
        OctopusGlassMaterial.Card -> 1.2.dp
        OctopusGlassMaterial.Dock -> 1.55.dp
        OctopusGlassMaterial.Sheet -> 1.45.dp
    }
    val innerStrokeWidth = outerStrokeWidth * 0.62f

    Box(modifier = modifier.clip(shape)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    val px = if (useRefraction) refraction.toPx() else 0f
                    translationX = px
                    translationY = -px * 0.55f
                    scaleX = surfaceScale
                    scaleY = surfaceScale
                    // 复用 octopus-agent 液态玻璃透明度(surface≈0.54):更透。
                    alpha = if (light) 0.60f else 0.52f
                }
                .blur(blurRadius, edgeTreatment = BlurredEdgeTreatment(shape))
                .background(tint.copy(alpha = tint.alpha * tintAlpha), shape)
        )
        if (quality == OctopusGlassQuality.Ultra && useRefraction) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        val wave = if (useDynamic) ripple else 0.35f
                        translationX = kotlin.math.sin(wave * 6.2831855f) * refraction.toPx() * 0.62f
                        translationY = kotlin.math.cos(wave * 6.2831855f) * refraction.toPx() * 0.38f
                        scaleX = 1.018f
                        scaleY = 1.018f
                        alpha = 0.36f
                    }
                    .blur(blurRadius * 0.62f, edgeTreatment = BlurredEdgeTreatment(shape))
                    .background(
                        Brush.radialGradient(
                            listOf(
                                Color.White.copy(alpha = 0.18f * highlightIntensity),
                                Color.Transparent,
                                tint.copy(alpha = 0.10f),
                            )
                        ),
                        shape,
                    )
            )
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.linearGradient(
                        0f to Color.White.copy(alpha = 0.42f * highlightIntensity),
                        0.34f to Color.White.copy(alpha = if (light) 0.14f else 0.06f),
                        0.68f to Color.Transparent,
                        1f to Color.Black.copy(alpha = innerShadow),
                    ),
                    shape,
                )
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = readabilityMist),
                        0.45f to tint.copy(alpha = readabilityMist * 0.55f),
                        1f to Color.Black.copy(alpha = readabilityMist * 0.35f),
                    ),
                    shape,
                )
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { translationX = size.width * if (useDynamic) shimmer else 0.34f }
                .background(
                    Brush.linearGradient(
                        0f to Color.Transparent,
                        0.42f to Color.White.copy(alpha = 0.0f),
                        0.50f to Color.White.copy(alpha = shimmerAlpha),
                        0.58f to Color.White.copy(alpha = 0.0f),
                        1f to Color.Transparent,
                    ),
                    shape,
                )
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawWithCache {
                    val edgeBrush = Brush.linearGradient(
                        0f to Color.White.copy(alpha = edgeGlow),
                        0.45f to Color.White.copy(alpha = edgeGlow * 0.18f),
                        1f to Color.Black.copy(alpha = innerShadow),
                    )
                    val topCutBrush = Brush.verticalGradient(
                        0f to Color.White.copy(alpha = topCutAlpha),
                        0.18f to Color.White.copy(alpha = topCutAlpha * 0.20f),
                        1f to Color.Transparent,
                    )
                    val bottomCutBrush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.72f to Color.Black.copy(alpha = bottomCutAlpha * 0.08f),
                        1f to Color.Black.copy(alpha = bottomCutAlpha),
                    )
                    val causticBrush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = causticAlpha),
                            Color.Transparent,
                            Color.White.copy(alpha = causticAlpha * 0.45f),
                            Color.Transparent,
                        ),
                        center = androidx.compose.ui.geometry.Offset(size.width * focalX.coerceIn(0f, 1f), size.height * focalY.coerceIn(0f, 1f)),
                        radius = size.maxDimension * 0.72f,
                    )
                    val noiseBrush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = noiseAlpha),
                            Color.Transparent,
                            Color.Black.copy(alpha = noiseAlpha * 0.55f),
                            Color.Transparent,
                        ),
                        radius = size.maxDimension * 0.14f,
                        tileMode = TileMode.Repeated,
                    )
                    onDrawBehind {
                        drawRect(topCutBrush)
                        drawRect(bottomCutBrush)
                        drawRect(causticBrush)
                        if (useNoise) drawRect(noiseBrush)
                        drawRect(edgeBrush, style = Stroke(width = outerStrokeWidth.toPx()))
                        drawRect(Color.White.copy(alpha = topCutAlpha * 0.38f), style = Stroke(width = innerStrokeWidth.toPx()))
                    }
                }
        )
    }
}

@Composable
fun rememberGlassPressState(
    enabled: Boolean = true,
): GlassPressState {
    val interactionSource = remember { MutableInteractionSource() }
    var focalX by remember { mutableStateOf(0.5f) }
    var focalY by remember { mutableStateOf(0.28f) }
    val pressed by interactionSource.collectIsPressedAsState()
    val targetScale = if (enabled && pressed) 0.982f else 1f
    val targetBoost = if (enabled && pressed) 1.22f else 1f
    val animatedFocalX by animateFloatAsState(
        targetValue = if (enabled && pressed) focalX else 0.5f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 360f),
        label = "glass-focal-x",
    )
    val animatedFocalY by animateFloatAsState(
        targetValue = if (enabled && pressed) focalY else 0.28f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 360f),
        label = "glass-focal-y",
    )
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 520f),
        label = "glass-press-scale",
    )
    val boost by animateFloatAsState(
        targetValue = targetBoost,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 420f),
        label = "glass-press-boost",
    )
    return GlassPressState(
        interactionSource = interactionSource,
        pressed = pressed,
        scale = scale,
        boost = boost,
        focalX = animatedFocalX,
        focalY = animatedFocalY,
        touchModifier = if (enabled) Modifier.trackGlassTouch { x, y ->
            focalX = x
            focalY = y
        } else Modifier,
    )
}

data class GlassPressState(
    val interactionSource: MutableInteractionSource,
    val pressed: Boolean,
    val scale: Float,
    val boost: Float,
    val focalX: Float,
    val focalY: Float,
    val touchModifier: Modifier,
)

private fun Modifier.trackGlassTouch(onTouch: (Float, Float) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitPointerEvent().changes.firstOrNull { it.pressed } ?: return@awaitEachGesture
        if (size.width > 0 && size.height > 0) {
            onTouch(
                (down.position.x / size.width).coerceIn(0f, 1f),
                (down.position.y / size.height).coerceIn(0f, 1f),
            )
        }
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: break
            if (!change.pressed) break
            if (change.positionChange() != androidx.compose.ui.geometry.Offset.Zero && size.width > 0 && size.height > 0) {
                onTouch(
                    (change.position.x / size.width).coerceIn(0f, 1f),
                    (change.position.y / size.height).coerceIn(0f, 1f),
                )
            }
        }
    }
}

// ── Glass Card ──
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    blurRadius: Dp = OctopusGlass.blurRadius,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = OctopusShape.large
    val press = rememberGlassPressState(enabled = onClick != null)
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = press.scale
                scaleY = press.scale
            }
            .then(press.touchModifier)
            .clip(shape)
            .border(1.dp, OctopusBackground.glassBorder, shape)
            .then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = press.interactionSource,
                    indication = null,
                    onClick = onClick,
                ) else Modifier
            ),
    ) {
        LiquidGlassLayer(
            shape = shape,
            blurRadius = blurRadius,
            highlightIntensity = OctopusGlass.highlightIntensity * press.boost,
            refractionBoost = press.boost,
            focalX = press.focalX,
            focalY = press.focalY,
            material = OctopusGlassMaterial.Card,
            modifier = Modifier.matchParentSize(),
        )
        content()
    }
}

@Composable
fun GlassPill(
    icon: ImageVector,
    text: String,
    tint: Color,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    blurRadius: Dp = OctopusGlass.subtleBlurRadius,
    onClick: () -> Unit,
) {
    val press = rememberGlassPressState()
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = press.scale
                scaleY = press.scale
            }
            .then(press.touchModifier)
            .clip(OctopusShape.capsule)
            .border(
                1.dp,
                if (selected) tint.copy(alpha = 0.42f) else OctopusBackground.glassBorder,
                OctopusShape.capsule,
            )
            .clickable(
                interactionSource = press.interactionSource,
                indication = null,
                onClick = onClick,
            ),
    ) {
        LiquidGlassLayer(
            shape = OctopusShape.capsule,
            blurRadius = blurRadius,
            tint = if (selected) tint.copy(alpha = 0.20f)
            else Color.White.copy(alpha = if (OctopusColors.isLight) 0.46f else 0.10f),
            highlightIntensity = (if (selected) OctopusGlass.highlightIntensity * 1.1f else OctopusGlass.highlightIntensity) * press.boost,
            refractionBoost = press.boost,
            focalX = press.focalX,
            focalY = press.focalY,
            material = OctopusGlassMaterial.Thin,
            modifier = Modifier.matchParentSize(),
        )
        Row(
            modifier = Modifier.padding(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.sm),
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
}

@Composable
fun GlassTextPill(
    text: String,
    tint: Color,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val press = rememberGlassPressState()
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = press.scale
                scaleY = press.scale
            }
            .then(press.touchModifier)
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
            .clickable(
                interactionSource = press.interactionSource,
                indication = null,
                onClick = onClick,
            )
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
    blurRadius: Dp = OctopusGlass.liquidBlurRadius,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (maxHeight != null) Modifier.heightIn(max = maxHeight) else Modifier)
            .clip(OctopusShape.xl)
            .border(1.dp, OctopusBackground.glassBorder, OctopusShape.xl),
    ) {
        LiquidGlassLayer(
            shape = OctopusShape.xl,
            blurRadius = blurRadius,
            tint = OctopusColors.SurfaceDeep.copy(alpha = if (OctopusColors.isLight) 0.86f else 0.78f),
            highlightIntensity = OctopusGlass.highlightIntensity * 1.08f,
            material = OctopusGlassMaterial.Sheet,
            modifier = Modifier.matchParentSize(),
        )
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
