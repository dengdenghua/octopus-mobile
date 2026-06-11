package com.apk.claw.android.tool.impl;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.apk.claw.android.ClawApplication;
import com.apk.claw.android.R;
import com.apk.claw.android.tool.BaseTool;
import com.apk.claw.android.tool.ToolParameter;
import com.apk.claw.android.tool.ToolResult;
import com.apk.claw.android.utils.XLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 发送短信工具 —— 打开系统短信应用并预填内容，由用户手动确认发送。
 *
 * 安全设计：
 *  - 不直接调用 SmsManager.sendTextMessage()，避免绕过用户确认
 *  - 使用 Intent.ACTION_SENDTO + sms: URI 打开系统短信应用
 *  - 预填收件人和正文，用户需手动点击"发送"按钮
 *  - 对标 miclaw 的 ASK_EVERY_TIME 权限策略
 */
public class SendSmsTool extends BaseTool {

    private static final String TAG = "SendSmsTool";

    @Override
    public String getName() {
        return "send_sms";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_send_sms);
    }

    @Override
    public String getDescriptionEN() {
        return "Open the system SMS app with a pre-filled recipient and message body. "
                + "The user must manually confirm and tap 'Send'. "
                + "This tool does NOT send the message directly — it only prepares it for user confirmation. "
                + "Use this for sending SMS with user oversight.";
    }

    @Override
    public String getDescriptionCN() {
        return "打开系统短信应用，预填收件人和短信正文。"
                + "用户需手动确认并点击「发送」。"
                + "此工具不会直接发送短信，仅预填内容供用户确认。"
                + "用于需要用户监督的短信发送场景。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        List<ToolParameter> params = new ArrayList<>();
        params.add(new ToolParameter("phone_number", "string",
                "Recipient phone number (e.g. '13800138000' or '+8613800138000').",
                true));
        params.add(new ToolParameter("message", "string",
                "The SMS message body text to pre-fill.",
                true));
        return params;
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        Context context = ClawApplication.Companion.getInstance();

        String phoneNumber = requireString(params, "phone_number");
        String message = requireString(params, "message");

        if (phoneNumber.trim().isEmpty()) {
            return ToolResult.error("phone_number cannot be empty.");
        }
        if (message.trim().isEmpty()) {
            return ToolResult.error("message cannot be empty.");
        }

        // 使用 ACTION_SENDTO + sms: URI（只打开短信应用，不触发电话或其他）
        Uri smsUri = Uri.parse("sms:" + phoneNumber.trim());
        Intent intent = new Intent(Intent.ACTION_SENDTO, smsUri);
        intent.putExtra("sms_body", message);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            context.startActivity(intent);
            return ToolResult.success("SMS app opened with pre-filled message.\n"
                    + "recipient=" + phoneNumber + "\n"
                    + "body=" + message + "\n"
                    + "The user must manually tap 'Send' to confirm.");
        } catch (Exception e) {
            XLog.e(TAG, "send_sms (open SMS app) failed", e);
            return ToolResult.error("Failed to open SMS app: " + e.getMessage()
                    + ". Make sure a default SMS app is installed on this device.");
        }
    }
}
