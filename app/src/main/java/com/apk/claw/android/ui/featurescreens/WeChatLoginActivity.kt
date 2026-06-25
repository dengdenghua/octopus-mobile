package com.apk.claw.android.ui.featurescreens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.R
import com.apk.claw.android.channel.ChannelManager
import com.apk.claw.android.channel.wechat.WeChatApiClient
import com.apk.claw.android.utils.KVUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 微信扫码登录页。复用既有 [WeChatApiClient] 的扫码登录接口:
 *   getQrCode() → 展示二维码 → pollQrCodeStatus()(长轮询)→ confirmed 后拿到 botToken/baseUrl
 *   → 存入 MMKV → ChannelManager.reinitWeChatFromStorage() 启动消息长轮询。
 */
class WeChatLoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFeatureContent { WeChatLoginScreen(onBack = { finish() }) }
    }
}

private sealed class QrUi {
    object Idle : QrUi()
    object Loading : QrUi()
    data class Show(val bmp: Bitmap) : QrUi()
    object Success : QrUi()
    object Failed : QrUi()
}

@Composable
private fun WeChatLoginScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val alreadyLinked = remember { KVUtils.getWechatBotToken().isNotEmpty() }
    var ui by remember { mutableStateOf<QrUi>(QrUi.Idle) }

    fun startLogin() {
        scope.launch {
            ui = QrUi.Loading
            val client = WeChatApiClient()
            val qr = withContext(Dispatchers.IO) { client.getQrCode() }
            val bmp = qr?.let { withContext(Dispatchers.IO) { decodeQr(it.qrcodeImgContent) } }
            if (qr == null || bmp == null) { ui = QrUi.Failed; return@launch }
            ui = QrUi.Show(bmp)
            // 长轮询(每次超时返回 null),最多约 40 轮
            var auth = withContext(Dispatchers.IO) { client.pollQrCodeStatus(qr.qrcode) }
            var tries = 1
            while (auth == null && tries < 40 && isActive && ui is QrUi.Show) {
                auth = withContext(Dispatchers.IO) { client.pollQrCodeStatus(qr.qrcode) }
                tries++
            }
            val confirmed = auth
            if (confirmed != null) {
                KVUtils.setWechatBotToken(confirmed.botToken)
                KVUtils.setWechatApiBaseUrl(confirmed.baseUrl)
                withContext(Dispatchers.IO) { runCatching { ChannelManager.reinitWeChatFromStorage() } }
                ui = QrUi.Success
            } else if (ui is QrUi.Show) {
                ui = QrUi.Failed
            }
        }
    }

    FeatureScaffold(title = stringResource(R.string.wechat_login_title), onBack = onBack) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.wechat_login_hint),
                color = FSub, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 19.sp,
            )
            Spacer(Modifier.height(20.dp))

            when (val s = ui) {
                is QrUi.Idle -> {
                    if (alreadyLinked) {
                        Text(stringResource(R.string.wechat_login_linked), color = FSuccess, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(16.dp))
                    }
                    PrimaryButton(stringResource(if (alreadyLinked) R.string.wechat_login_relogin else R.string.wechat_login_get_qr)) { startLogin() }
                }
                is QrUi.Loading -> CircularProgressIndicator(color = FPrimary)
                is QrUi.Show -> {
                    Surface(shape = RoundedCornerShape(12.dp), color = androidx.compose.ui.graphics.Color.White) {
                        Image(bitmap = s.bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.size(240.dp).padding(8.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = FPrimary, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.wechat_login_waiting), color = FMuted, fontSize = 13.sp)
                    }
                }
                is QrUi.Success -> {
                    Text(stringResource(R.string.wechat_login_success), color = FSuccess, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    PrimaryButton(stringResource(R.string.wechat_login_done)) { onBack() }
                }
                is QrUi.Failed -> {
                    Text(stringResource(R.string.wechat_login_failed), color = FWarning, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 19.sp)
                    Spacer(Modifier.height(20.dp))
                    PrimaryButton(stringResource(R.string.wechat_login_get_qr)) { startLogin() }
                }
            }
        }
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = FPrimary,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(text, color = androidx.compose.ui.graphics.Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp))
    }
}

/** 把服务端返回的二维码图片内容(可能带 data:image;base64, 前缀)解成 Bitmap。 */
private fun decodeQr(content: String): Bitmap? {
    if (content.isBlank()) return null
    val b64 = if (content.contains("base64,")) content.substringAfter("base64,") else content
    return try {
        val bytes = android.util.Base64.decode(b64.trim(), android.util.Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) {
        null
    }
}
