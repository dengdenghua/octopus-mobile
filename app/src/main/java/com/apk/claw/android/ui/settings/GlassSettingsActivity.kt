package com.apk.claw.android.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.R
import com.apk.claw.android.ui.compose.theme.*
import com.apk.claw.android.utils.KVUtils
import kotlin.math.roundToInt

private val PrimaryColor get() = OctopusColors.Primary
private val TextPrimary get() = OctopusColors.TextPrimary
private val TextSecondary get() = OctopusColors.TextSecondary
private val TextMuted get() = OctopusColors.TextMuted
private val BorderColor get() = OctopusColors.Border
private val OnPrimaryColor get() = OctopusColors.OnPrimary

class GlassSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OctopusTheme {
                GlassSettingsScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlassSettingsScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        androidx.compose.ui.res.stringResource(R.string.settings_glass_blur),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = OctopusType.title,
                        color = TextPrimary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        containerColor = Color.Transparent,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(OctopusBackground.pageBrush())
                .padding(innerPadding)
                .padding(horizontal = OctopusSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(OctopusSpacing.md),
        ) {
            item {
                QualitySelectorCard()
            }
            item {
                BlurRadiusCard()
            }
            item {
                TuningSlidersCard()
            }
            item {
                AnimationToggleCard()
            }
            item { Spacer(modifier = Modifier.height(OctopusSpacing.xl)) }
        }
    }
}

@Composable
private fun QualitySelectorCard() {
    var glassQuality by remember { mutableStateOf(OctopusGlassQuality.fromStorage(KVUtils.getGlassQuality())) }
    val shape = OctopusShape.large
    SurfaceCard {
        SectionHeader(
            icon = Icons.Filled.GraphicEq,
            title = androidx.compose.ui.res.stringResource(R.string.settings_glass_blur),
        )
        Spacer(Modifier.height(OctopusSpacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.xs),
        ) {
            listOf(
                OctopusGlassQuality.Low,
                OctopusGlassQuality.Medium,
                OctopusGlassQuality.High,
                OctopusGlassQuality.Ultra,
            ).forEach { quality ->
                val selected = glassQuality == quality
                Surface(
                    shape = OctopusShape.capsule,
                    color = if (selected) PrimaryColor.copy(alpha = 0.16f) else OctopusBackground.glassSurface,
                    border = BorderStroke(1.dp, if (selected) PrimaryColor.copy(alpha = 0.42f) else OctopusBackground.glassBorder),
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            glassQuality = quality
                            OctopusGlass.quality = quality
                            KVUtils.setGlassQuality(quality.name.lowercase())
                        },
                ) {
                    Text(
                        quality.name,
                        color = if (selected) PrimaryColor else TextSecondary,
                        fontSize = OctopusType.tag,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(vertical = OctopusSpacing.sm).fillMaxWidth(),
                    )
                }
            }
        }
        Spacer(Modifier.height(OctopusSpacing.md))
        Text(
            androidx.compose.ui.res.stringResource(R.string.settings_glass_blur_hint),
            color = TextMuted,
            fontSize = OctopusType.caption,
            lineHeight = 15.sp,
        )
    }
}

@Composable
private fun BlurRadiusCard() {
    var glassBlurRadius by remember { mutableFloatStateOf(KVUtils.getGlassBlurRadius()) }
    SurfaceCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                androidx.compose.ui.res.stringResource(R.string.settings_glass_blur),
                color = TextPrimary,
                fontSize = OctopusType.body,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${glassBlurRadius.roundToInt()}dp",
                color = PrimaryColor,
                fontSize = OctopusType.label,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = glassBlurRadius,
            onValueChange = { value ->
                glassBlurRadius = value
                OctopusGlass.blurRadius = value.dp
            },
            onValueChangeFinished = {
                KVUtils.setGlassBlurRadius(glassBlurRadius)
            },
            valueRange = 0f..48f,
            steps = 15,
        )
    }
}

@Composable
private fun TuningSlidersCard() {
    var glassRefraction by remember { mutableFloatStateOf(KVUtils.getGlassRefraction()) }
    var glassHighlight by remember { mutableFloatStateOf(KVUtils.getGlassHighlight()) }
    var glassNoise by remember { mutableFloatStateOf(KVUtils.getGlassNoise()) }
    SurfaceCard {
        GlassTuningSlider(
            title = androidx.compose.ui.res.stringResource(R.string.settings_glass_refraction),
            value = glassRefraction,
            valueText = "${(glassRefraction * 100).roundToInt()}%",
            onValueChange = {
                glassRefraction = it
                OctopusGlass.refraction = it
            },
            onValueChangeFinished = { KVUtils.setGlassRefraction(glassRefraction) },
        )
        Spacer(Modifier.height(OctopusSpacing.sm))
        GlassTuningSlider(
            title = androidx.compose.ui.res.stringResource(R.string.settings_glass_highlight),
            value = glassHighlight,
            valueText = "${(glassHighlight * 100).roundToInt()}%",
            onValueChange = {
                glassHighlight = it
                OctopusGlass.highlight = it
            },
            onValueChangeFinished = { KVUtils.setGlassHighlight(glassHighlight) },
        )
        Spacer(Modifier.height(OctopusSpacing.sm))
        GlassTuningSlider(
            title = androidx.compose.ui.res.stringResource(R.string.settings_glass_noise),
            value = glassNoise,
            valueText = "${(glassNoise * 100).roundToInt()}%",
            onValueChange = {
                glassNoise = it
                OctopusGlass.noise = it
            },
            onValueChangeFinished = { KVUtils.setGlassNoise(glassNoise) },
        )
    }
}

@Composable
private fun AnimationToggleCard() {
    var glassAnimation by remember { mutableStateOf(KVUtils.isGlassAnimationEnabled()) }
    SurfaceCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                androidx.compose.ui.res.stringResource(R.string.settings_glass_animation),
                color = TextPrimary,
                fontSize = OctopusType.body,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = glassAnimation,
                onCheckedChange = {
                    glassAnimation = it
                    OctopusGlass.animationEnabled = it
                    KVUtils.setGlassAnimationEnabled(it)
                },
            )
        }
    }
}

@Composable
private fun SurfaceCard(content: @Composable ColumnScope.() -> Unit) {
    val shape = OctopusShape.large
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(OctopusBackground.glassSurface, shape)
            .border(1.dp, OctopusBackground.glassBorder, shape),
    ) {
        Column(modifier = Modifier.padding(OctopusSpacing.lg)) {
            content()
        }
    }
}

@Composable
private fun SectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm)) {
        Icon(icon, contentDescription = null, tint = TextMuted, modifier = Modifier.size(OctopusIconSize.small))
        Text(title, fontSize = OctopusType.label, fontWeight = FontWeight.SemiBold, color = TextSecondary, letterSpacing = 0.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun GlassTuningSlider(
    title: String,
    value: Float,
    valueText: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                title,
                color = TextPrimary,
                fontSize = OctopusType.label,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                valueText,
                color = PrimaryColor,
                fontSize = OctopusType.tag,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = 0f..2f,
            steps = 15,
        )
    }
}
