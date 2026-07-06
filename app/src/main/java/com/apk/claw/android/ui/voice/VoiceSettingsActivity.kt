@file:Suppress("TooManyFunctions", "MagicNumber", "MaxLineLength", "LongMethod")

package com.apk.claw.android.ui.voice

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apk.claw.android.ui.compose.theme.OctopusBackground
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusType
import com.apk.claw.android.ui.compose.theme.OctopusTheme
import com.apk.claw.android.voice.realtime.VoicePrefsApi
import kotlinx.coroutines.launch

/** 语音个性化设置:选音色 + 设人设。对接 server /voice/prefs;下次通话生效。 */
class VoiceSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { OctopusTheme { VoiceSettingsScreen(onClose = { finish() }) } }
    }
}

@Composable
private fun VoiceSettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var presets by remember { mutableStateOf(listOf<String>()) }
    var hasCloned by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf("") }
    var persona by remember { mutableStateOf("") }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        runCatching { VoicePrefsApi.get() }.getOrNull()?.let { p ->
            presets = p.presets
            hasCloned = p.hasCloned
            selected = p.voice.ifBlank { p.default }
            persona = p.persona
        }
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OctopusBackground.pageBrush())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = OctopusSpacing.lg),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = OctopusSpacing.md), verticalAlignment = Alignment.CenterVertically) {
            Text("语音设置", color = OctopusColors.TextPrimary, fontSize = OctopusType.titleLg, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "关闭", tint = OctopusColors.TextSecondary)
            }
        }
        if (loading) {
            Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = OctopusColors.Primary)
            }
            return@Column
        }
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text("音色", color = OctopusColors.TextSecondary, fontSize = OctopusType.label, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(OctopusSpacing.sm))
            val options = presets + if (hasCloned) listOf("cloned") else emptyList()
            options.forEach { v ->
                VoiceRow(label = voiceLabel(v), selected = v == selected, onClick = { selected = v })
                Spacer(Modifier.height(OctopusSpacing.sm))
            }
            Spacer(Modifier.height(OctopusSpacing.lg))
            Text("人设(可选)", color = OctopusColors.TextSecondary, fontSize = OctopusType.label, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(OctopusSpacing.sm))
            OutlinedTextField(
                value = persona,
                onValueChange = { if (it.length <= 500) persona = it },
                modifier = Modifier.fillMaxWidth().height(120.dp),
                placeholder = { Text("例如:你是我的健身教练,说话简短有力,多鼓励我。", color = OctopusColors.TextMuted, fontSize = OctopusType.body) },
                shape = OctopusShape.large,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = OctopusColors.Primary,
                    unfocusedBorderColor = OctopusColors.Border,
                    focusedTextColor = OctopusColors.TextPrimary,
                    unfocusedTextColor = OctopusColors.TextPrimary,
                ),
            )
            Text("${persona.length}/500", color = OctopusColors.TextTertiary, fontSize = OctopusType.caption, modifier = Modifier.fillMaxWidth().padding(top = OctopusSpacing.xs))
        }
        SaveButton(saving = saving) {
            saving = true
            scope.launch {
                val ok = runCatching { VoicePrefsApi.save(selected, persona) }.getOrDefault(false)
                saving = false
                Toast.makeText(context, if (ok) "已保存,下次通话生效" else "保存失败", Toast.LENGTH_SHORT).show()
                if (ok) onClose()
            }
        }
        Spacer(Modifier.height(OctopusSpacing.xl))
    }
}

@Composable
private fun VoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(OctopusShape.large)
            .background(if (selected) OctopusColors.PrimaryContainer else OctopusBackground.cardSurface)
            .clickable { onClick() }
            .padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = OctopusColors.TextPrimary, fontSize = OctopusType.body, modifier = Modifier.weight(1f))
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = OctopusColors.Primary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun SaveButton(saving: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(OctopusShape.capsule)
            .background(OctopusColors.Primary)
            .clickable(enabled = !saving) { onClick() }
            .padding(vertical = OctopusSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        if (saving) {
            CircularProgressIndicator(color = OctopusColors.OnPrimary, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Text("保存", color = OctopusColors.OnPrimary, fontSize = OctopusType.bodyLg, fontWeight = FontWeight.Medium)
        }
    }
}

private fun voiceLabel(name: String): String = when (name) {
    "Serena" -> "赛琳娜 · 女声"
    "Ethan" -> "伊森 · 男声"
    "Dylan" -> "迪伦 · 男声"
    "Tina" -> "蒂娜 · 女声"
    "Sunny" -> "阳阳 · 女声"
    "Jada" -> "洁达 · 女声"
    "cloned" -> "我的声音（复刻）"
    else -> name
}
