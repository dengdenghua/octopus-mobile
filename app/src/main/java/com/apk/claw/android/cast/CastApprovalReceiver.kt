package com.apk.claw.android.cast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 接收投屏确认通知的"允许/拒绝"操作。
 */
class CastApprovalReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(CastApprovalManager.EXTRA_REQUEST_ID) ?: return
        val approved = intent.action == CastApprovalManager.ACTION_APPROVE
        CastApprovalManager.resolve(requestId, approved)
    }
}
