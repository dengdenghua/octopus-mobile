package com.apk.claw.android.tool.impl;

import android.content.ComponentName;
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
 * 发送 Intent 工具 —— 通过标准 Android Intent 机制启动 Activity / 触发 Action。
 *
 * 支持场景：
 *  - 启动指定 Activity（component + action）
 *  - 打开网页（action=VIEW, uri=https://...）
 *  - 拨打电话界面（action=DIAL, uri=tel:xxx）
 *  - 打开发送界面（action=SEND, extra_text=xxx）
 *  - 自定义 Intent（action / uri / extra_* 参数）
 *
 * 与 open_app 的区别：
 *  - open_app 通过 AccessibilityService 上下文调用 getLaunchIntentForPackage
 *  - send_intent 使用标准 Intent 机制，不依赖 AccessibilityService，
 *    且支持 URI、自定义 Action、Extra 等更丰富的 Intent 构造
 */
public class SendIntentTool extends BaseTool {

    private static final String TAG = "SendIntentTool";

    @Override
    public String getName() {
        return "send_intent";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_send_intent);
    }

    @Override
    public String getDescriptionEN() {
        return "Launch an activity or trigger an action via standard Android Intent. "
                + "Supports: opening URLs (action=VIEW, uri=https://...), "
                + "dialing (action=DIAL, uri=tel:xxx), sharing text (action=SEND, extra_text=xxx), "
                + "or custom intents. Does NOT rely on AccessibilityService.";
    }

    @Override
    public String getDescriptionCN() {
        return "通过标准 Android Intent 启动 Activity 或触发 Action。"
                + "支持：打开网址（action=VIEW, uri=https://...）、"
                + "拨打电话界面（action=DIAL, uri=tel:xxx）、分享文本（action=SEND, extra_text=xxx）、"
                + "或自定义 Intent。不依赖无障碍服务。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        List<ToolParameter> params = new ArrayList<>();
        params.add(new ToolParameter("action", "string",
                "Intent action. Common values: VIEW (open URL), DIAL (dial phone), SEND (share), MAIN (launch). "
                        + "If omitted, defaults to ACTION_VIEW when uri is provided, or ACTION_MAIN when component is provided.",
                false));
        params.add(new ToolParameter("uri", "string",
                "URI for the intent. Examples: https://example.com, tel:1234567890, mailto:a@b.com, sms:12345",
                false));
        params.add(new ToolParameter("component", "string",
                "Target component in format 'package/activity' (e.g. 'com.android.settings/.Settings'). "
                        + "If provided, the intent will be directed to this specific component.",
                false));
        params.add(new ToolParameter("extra_text", "string",
                "Optional text extra (Intent.EXTRA_TEXT). Used with action=SEND for sharing.",
                false));
        params.add(new ToolParameter("extra_subject", "string",
                "Optional subject extra (Intent.EXTRA_SUBJECT).",
                false));
        params.add(new ToolParameter("type", "string",
                "Optional MIME type for the intent (e.g. 'text/plain').",
                false));
        return params;
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        Context context = ClawApplication.Companion.getInstance();

        String action = optionalString(params, "action", null);
        String uri = optionalString(params, "uri", null);
        String component = optionalString(params, "component", null);
        String extraText = optionalString(params, "extra_text", null);
        String extraSubject = optionalString(params, "extra_subject", null);
        String type = optionalString(params, "type", null);

        // 推断 action
        if (action == null) {
            if (uri != null) {
                action = Intent.ACTION_VIEW;
            } else if (component != null) {
                action = Intent.ACTION_MAIN;
            } else {
                return ToolResult.error("Must provide at least one of: uri, component, or action");
            }
        }

        Intent intent = new Intent(action);

        // 设置 URI
        if (uri != null) {
            intent.setData(Uri.parse(uri));
        }

        // 设置 component
        if (component != null) {
            String[] parts = component.split("/", 2);
            if (parts.length == 2) {
                intent.setComponent(new ComponentName(parts[0], parts[1]));
            } else {
                return ToolResult.error("Invalid component format: '" + component
                        + "'. Expected 'package/activity' (e.g. 'com.android.settings/.Settings')");
            }
        }

        // 设置 MIME type
        if (type != null) {
            intent.setType(type);
        }

        // 设置 extras
        if (extraText != null) {
            intent.putExtra(Intent.EXTRA_TEXT, extraText);
        }
        if (extraSubject != null) {
            intent.putExtra(Intent.EXTRA_SUBJECT, extraSubject);
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            context.startActivity(intent);
            StringBuilder result = new StringBuilder("Intent sent successfully.");
            result.append(" action=").append(action);
            if (uri != null) result.append(", uri=").append(uri);
            if (component != null) result.append(", component=").append(component);
            if (extraText != null) result.append(", extra_text=").append(extraText);
            return ToolResult.success(result.toString());
        } catch (Exception e) {
            XLog.e(TAG, "send_intent failed", e);
            return ToolResult.error("send_intent failed: " + e.getMessage()
                    + ". Make sure the target app/activity exists and is enabled.");
        }
    }
}
