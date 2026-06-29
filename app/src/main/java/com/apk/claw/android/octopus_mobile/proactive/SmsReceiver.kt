package com.apk.claw.android.octopus_mobile.proactive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * 系统短信接收器 —— 把收到的短信(发件人 + 正文)交给 [ProactiveRuleEngine.onSmsReceived],
 * 驱动"验证码短信自动复制"等 SMS_RECEIVED 类主动规则。
 *
 * 安全/隐私前提(三重,默认都不满足):
 *  1. 已在系统授予 RECEIVE_SMS 危险权限(由 TrustCenter「验证码短信自动复制」开关引导授予);
 *  2. 主动规则引擎已启用([ProactiveRuleEngine.isEnabled]);
 *  3. 对应规则启用(默认有内建 sms_code_copy 规则)。
 *
 * Manifest 中以 android:permission="android.permission.BROADCAST_SMS" 限定仅系统可投递此广播,
 * 防止第三方应用伪造短信广播触发规则。
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        // 引擎未就绪(进程刚被广播唤醒、尚未 init)或未启用时直接返回,不解析短信。
        val engine = NotificationRelayService.proactiveEngine ?: return
        if (!engine.isEnabled()) return
        try {
            val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
            if (parts.isEmpty()) return
            val sender = parts[0].displayOriginatingAddress ?: parts[0].originatingAddress ?: ""
            // 多段长短信:拼接各段正文还原完整内容。
            val body = parts.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }
            if (body.isBlank()) return
            val results = engine.onSmsReceived(sender, body)
            results.forEach { r -> if (r.actionTaken) Log.i(TAG, "Proactive SMS rule fired: ${r.message}") }
        } catch (e: Exception) {
            Log.w(TAG, "SMS receive handling failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "SmsReceiver"
    }
}
