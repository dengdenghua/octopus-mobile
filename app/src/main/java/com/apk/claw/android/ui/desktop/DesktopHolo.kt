package com.apk.claw.android.ui.desktop

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 「本地虚拟电脑」的科幻/全息皮肤 —— 参考 MiniMax OpenRoom 的"AI 操作的桌面",
 * 做成:壁纸背景 + 玻璃拟态面板 + 霓虹描边 + 顶部 HUD 直播条(● LIVE + 遥测)。
 *
 * 放在独立文件里,尽量少改并行编辑中的 [DesktopActivity]。
 */
internal object Holo {
    val Accent = Color(0xFF41E0FF)          // 霓虹青
    val AccentDim = Color(0xFF1E6E82)
    val Glass = Color(0xFF0A1626)           // 玻璃面板底色(配 alpha 用)
    val Border = Color(0x6641E0FF)          // 青色描边(40% alpha)
    val BorderDim = Color(0x2241E0FF)
    val TextHud = Color(0xFFBFEFFF)
    val Live = Color(0xFFFF4D6A)            // 直播红点

    val bgBrush = Brush.verticalGradient(
        listOf(Color(0xFF070B16), Color(0xFF0A1020), Color(0xFF05070E)),
    )
}

/** 玻璃拟态面板:半透明底 + 霓虹描边 + 圆角。 */
fun Modifier.holoGlass(corner: Dp = 14.dp, fillAlpha: Float = 0.55f): Modifier =
    this.clip(RoundedCornerShape(corner))
        .background(Holo.Glass.copy(alpha = fillAlpha))
        .border(1.dp, Holo.Border, RoundedCornerShape(corner))

/** 全屏科幻壁纸:深空渐变 + 淡青网格。 */
@Composable
fun HoloBackground(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize().background(Holo.bgBrush)) {
        val step = 46.dp.toPx()
        val line = Holo.Accent.copy(alpha = 0.045f)
        var x = 0f
        while (x < size.width) {
            drawLine(line, Offset(x, 0f), Offset(x, size.height), 1f)
            x += step
        }
        var y = 0f
        while (y < size.height) {
            drawLine(line, Offset(0f, y), Offset(size.width, y), 1f)
            y += step
        }
    }
}

/** 顶部 HUD 直播条:● LIVE(空闲=IDLE)+ 当前 URL + 连接态 + 时钟,等宽字体。 */
@Composable
fun HudStrip(
    urlOrIdle: String,
    live: Boolean,
    connLabel: String,
    connColor: Color,
    timeText: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Holo.Glass.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LiveDot(active = live)
        Text(
            if (live) "LIVE" else "IDLE",
            color = if (live) Holo.Live else Holo.AccentDim,
            fontSize = 10.sp, fontFamily = FontFamily.Monospace,
        )
        Text(
            urlOrIdle,
            color = Holo.TextHud, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        HoloDot(connColor)
        Text(connLabel, color = Holo.TextHud, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text(timeText, color = Holo.Accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun LiveDot(active: Boolean) {
    val alpha by rememberInfiniteTransition(label = "live").animateFloat(
        initialValue = 1f, targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "liveAlpha",
    )
    HoloDot(if (active) Holo.Live.copy(alpha = alpha) else Holo.AccentDim)
}

@Composable
fun HoloDot(color: Color) {
    Canvas(Modifier.size(7.dp)) { drawCircle(color) }
}
