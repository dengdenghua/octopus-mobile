package com.apk.claw.android.ui.featurescreens

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import com.apk.claw.android.appViewModel
import com.apk.claw.android.octopus_mobile.ConnectionState
import kotlin.math.min

/**
 * 母体远程桌面 —— 手机看 PC 屏幕、触控/键盘控制 PC（类似 ToDesk）。
 *
 * 复用现有那条 [com.apk.claw.android.octopus_mobile.OctopusMobileClient] WebSocket：
 *  - 订阅 pc_screen/subscribe，母体通过 push_pc_frame 推 JPEG 帧 → 解码渲染
 *  - 触摸 → 归一化坐标 → remote/input（母体还原为 PC 鼠键）
 *
 * 前提：先在 设置 → 母体连接 配好地址并连接（DUAL / RPC_ONLY）。
 */
class PcRemoteActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PcRemoteScreen(onBack = { finish() }) }
        runCatching { window.statusBarColor = android.graphics.Color.BLACK }
    }
}

@Composable
private fun PcRemoteScreen(onBack: () -> Unit) {
    val client = appViewModel.octopusClient
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var fps by remember { mutableStateOf(0) }
    var showKeyboard by remember { mutableStateOf(false) }
    val connected = client?.currentState() == ConnectionState.ONLINE ||
        client?.currentState() == ConnectionState.HELLO_SENT

    // 帧计数（粗略 fps 显示）
    var frameAcc by remember { mutableStateOf(0) }

    DisposableEffect(client) {
        if (client != null) {
            client.onPcFrame = { bytes ->
                decodeFrame(bytes)?.let {
                    frame = it.asImageBitmap()
                    frameAcc++
                }
            }
            client.subscribePcScreen()
        }
        onDispose {
            client?.unsubscribePcScreen()
            client?.onPcFrame = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { boxSize = it }
            .pointerInput(boxSize, frame) {
                detectTapGestures(
                    onTap = { off -> sendAt(client, frame, boxSize, off.x, off.y, "tap") },
                    onLongPress = { off -> sendAt(client, frame, boxSize, off.x, off.y, "rightclick") },
                )
            }
            .pointerInput(boxSize, frame) {
                // 一指拖动=鼠标拖拽(down/move/up)；两指纵向滑动=滚动。tap/长按 由上面的探测器处理。
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    var twoFinger = false
                    var dragEmitted = false
                    var centroidInit = false
                    var lastCentroidY = 0f
                    var lastPos = first.position
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            twoFinger = true
                            val cy = pressed.map { it.position.y }.average().toFloat()
                            if (!centroidInit) { lastCentroidY = cy; centroidInit = true }
                            val dy = cy - lastCentroidY
                            lastCentroidY = cy
                            if (kotlin.math.abs(dy) > 1f && boxSize.height > 0) {
                                client?.sendRemoteInput("scroll", 0f, dy / boxSize.height)
                            }
                            pressed.forEach { it.consume() }
                        } else if (pressed.size == 1 && !twoFinger) {
                            val p = pressed[0]
                            val pos = p.position
                            if (dragEmitted || (pos - first.position).getDistance() > slop) {
                                if (!dragEmitted) {
                                    sendDrag(client, frame, boxSize, first.position.x, first.position.y, "down")
                                    dragEmitted = true
                                }
                                sendDrag(client, frame, boxSize, pos.x, pos.y, "move")
                                p.consume()
                                lastPos = pos
                            }
                        }
                    }
                    if (dragEmitted) sendDrag(client, frame, boxSize, lastPos.x, lastPos.y, "up")
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val f = frame
        if (f != null) {
            Image(bitmap = f, contentDescription = "PC 屏幕", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                if (connected) "已连接母体，等待 PC 画面…\n（确认母体侧采集驱动已运行）"
                else "未连接母体。\n请先在 设置 → 母体连接 配好地址并连接。",
                color = Color.White, fontSize = 14.sp,
            )
        }

        // 顶栏
        Row(
            modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color(0xCC000000)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("‹ 返回", color = Color.White, fontSize = 14.sp, modifier = Modifier.pointerInput(Unit) { detectTapGestures { onBack() } })
            Spacer(Modifier.width(14.dp))
            Text("🖥 母体远程桌面", color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Text(if (frame != null) "▶ ${f?.width}×${f?.height}" else if (connected) "● 已连接" else "○ 未连接", color = Color(0xFF8AB4F8), fontSize = 11.sp)
            Spacer(Modifier.width(12.dp))
            Text("⌫", color = Color.White, fontSize = 16.sp, modifier = Modifier.pointerInput(Unit) { detectTapGestures { client?.sendRemoteInput("key", text = "backspace") } })
            Spacer(Modifier.width(12.dp))
            Text("Esc", color = Color.White, fontSize = 13.sp, modifier = Modifier.pointerInput(Unit) { detectTapGestures { client?.sendRemoteInput("key", text = "esc") } })
            Spacer(Modifier.width(12.dp))
            Text("⌨", color = Color.White, fontSize = 18.sp, modifier = Modifier.pointerInput(Unit) { detectTapGestures { showKeyboard = true } })
        }
    }

    if (showKeyboard) {
        var input by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showKeyboard = false },
            title = { Text("输入文字到 PC") },
            text = {
                BasicTextField(
                    value = input, onValueChange = { input = it },
                    textStyle = TextStyle(color = Color.Black, fontSize = 16.sp),
                    modifier = Modifier.fillMaxWidth().background(Color(0xFFEEEEEE)).padding(10.dp),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (input.isNotEmpty()) client?.sendRemoteInput("type", text = input)
                    showKeyboard = false
                }) { Text("发送") }
            },
            dismissButton = {
                TextButton(onClick = { client?.sendRemoteInput("key", text = "enter"); showKeyboard = false }) { Text("回车") }
            },
        )
    }
}

/** 把触摸点映射到 PC 画面的归一化坐标并发送（ContentScale.Fit 的居中letterbox 还原）。 */
private fun sendAt(
    client: com.apk.claw.android.octopus_mobile.OctopusMobileClient?,
    frame: ImageBitmap?, box: IntSize, tx: Float, ty: Float, action: String,
) {
    if (client == null || frame == null || box.width == 0 || box.height == 0) return
    val bw = box.width.toFloat(); val bh = box.height.toFloat()
    val iw = frame.width.toFloat(); val ih = frame.height.toFloat()
    val scale = min(bw / iw, bh / ih)
    val dw = iw * scale; val dh = ih * scale
    val ox = (bw - dw) / 2f; val oy = (bh - dh) / 2f
    val nx = (tx - ox) / dw; val ny = (ty - oy) / dh
    if (nx in 0f..1f && ny in 0f..1f) client.sendRemoteInput(action, nx, ny)
}

/** 拖拽用：钳制到 [0,1] 并始终发送（避免 down 之后 move/up 落到画面外导致鼠标按住不放）。 */
private fun sendDrag(
    client: com.apk.claw.android.octopus_mobile.OctopusMobileClient?,
    frame: ImageBitmap?, box: IntSize, tx: Float, ty: Float, action: String,
) {
    if (client == null || frame == null || box.width == 0 || box.height == 0) return
    val bw = box.width.toFloat(); val bh = box.height.toFloat()
    val iw = frame.width.toFloat(); val ih = frame.height.toFloat()
    val scale = min(bw / iw, bh / ih)
    val dw = iw * scale; val dh = ih * scale
    val ox = (bw - dw) / 2f; val oy = (bh - dh) / 2f
    val nx = ((tx - ox) / dw).coerceIn(0f, 1f)
    val ny = ((ty - oy) / dh).coerceIn(0f, 1f)
    client.sendRemoteInput(action, nx, ny)
}

/** 解析帧头（2B id长度 + 类型 + 标志 + id + 数据），JPEG/WebP 解码为 Bitmap。 */
private fun decodeFrame(data: ByteArray): android.graphics.Bitmap? {
    if (data.size < 4) return null
    val idLen = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
    val type = data[2].toInt() and 0xFF
    val start = 4 + idLen
    if (start >= data.size) return null
    if (type != 0x02 && type != 0x03) return null  // 只处理 JPEG / WebP
    return runCatching { BitmapFactory.decodeByteArray(data, start, data.size - start) }.getOrNull()
}
