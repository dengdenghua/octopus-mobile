package com.apk.claw.android.ui.featurescreens

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.apk.claw.android.R
import com.apk.claw.android.ui.settings.ChannelConfigActivity
import com.apk.claw.android.utils.KVUtils

/**
 * 消息渠道中心 —— 列出全部 IM 渠道并进入各自配置。
 *
 * 修复点:旧版「设置 → 消息渠道」直接 startActivity(ChannelConfigActivity) 却不带
 * EXTRA_CHANNEL_TYPE,导致配置页 onCreate 立刻 finish()(渠道配置完全打不开)。
 * 这里做一个选择器:钉钉/飞书/QQ/Discord/Telegram → 带类型进入 ChannelConfigActivity;
 * 微信 → 进入扫码登录页 [WeChatLoginActivity]。
 */
class ChannelsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ChannelsScreen(onBack = { finish() }) }
        runCatching { window.statusBarColor = android.graphics.Color.BLACK }
    }
}

@Composable
private fun ChannelsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    // 从配置页返回后刷新「已连接/未配置」状态
    val lifecycleOwner = LocalLifecycleOwner.current
    var rev by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) rev++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val wechatOk = remember(rev) { KVUtils.getWechatBotToken().isNotEmpty() }
    val dingtalkOk = remember(rev) { KVUtils.getDingtalkAppKey().isNotEmpty() && KVUtils.getDingtalkAppSecret().isNotEmpty() }
    val feishuOk = remember(rev) { KVUtils.getFeishuAppId().isNotEmpty() && KVUtils.getFeishuAppSecret().isNotEmpty() }
    val telegramOk = remember(rev) { KVUtils.getTelegramBotToken().isNotEmpty() }
    val discordOk = remember(rev) { KVUtils.getDiscordBotToken().isNotEmpty() }
    val qqOk = remember(rev) { KVUtils.getQqAppId().isNotEmpty() && KVUtils.getQqAppSecret().isNotEmpty() }

    FeatureScaffold(title = stringResource(R.string.channels_hub_title), onBack = onBack) {
        Text(
            stringResource(R.string.channels_hub_desc),
            color = FMuted, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        ChannelRow(R.string.channel_wechat, wechatOk) {
            ctx.startActivity(Intent(ctx, WeChatLoginActivity::class.java))
        }
        ChannelRow(R.string.channel_dingtalk, dingtalkOk) {
            ChannelConfigActivity.start(ctx, ChannelConfigActivity.ChannelType.DINGTALK)
        }
        ChannelRow(R.string.channel_feishu, feishuOk) {
            ChannelConfigActivity.start(ctx, ChannelConfigActivity.ChannelType.FEISHU)
        }
        ChannelRow(R.string.channel_telegram, telegramOk) {
            ChannelConfigActivity.start(ctx, ChannelConfigActivity.ChannelType.TELEGRAM)
        }
        ChannelRow(R.string.channel_discord, discordOk) {
            ChannelConfigActivity.start(ctx, ChannelConfigActivity.ChannelType.DISCORD)
        }
        ChannelRow(R.string.channel_qq, qqOk) {
            ChannelConfigActivity.start(ctx, ChannelConfigActivity.ChannelType.QQ)
        }
    }
}

@Composable
private fun ChannelRow(nameRes: Int, configured: Boolean, onClick: () -> Unit) {
    FCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        ) {
            Text(stringResource(nameRes), color = FText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            FPill(
                if (configured) stringResource(R.string.status_connected) else stringResource(R.string.status_not_configured),
                if (configured) FSuccess else FMuted,
            )
            Text("›", color = FMuted, fontSize = 18.sp, modifier = Modifier.padding(start = 10.dp))
        }
    }
}
