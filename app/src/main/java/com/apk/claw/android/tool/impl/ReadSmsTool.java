package com.apk.claw.android.tool.impl;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony;

import androidx.core.content.ContextCompat;

import com.apk.claw.android.ClawApplication;
import com.apk.claw.android.R;
import com.apk.claw.android.tool.BaseTool;
import com.apk.claw.android.tool.ToolParameter;
import com.apk.claw.android.tool.ToolResult;
import com.apk.claw.android.utils.XLog;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 读取短信工具 —— 通过 ContentResolver 读取设备短信/彩信。
 *
 * 需要运行时权限：READ_SMS
 * 返回结果按时间倒序排列（最新在前）。
 */
public class ReadSmsTool extends BaseTool {

    private static final String TAG = "ReadSmsTool";
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    @Override
    public String getName() {
        return "read_sms";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_read_sms);
    }

    @Override
    public String getDescriptionEN() {
        return "Read SMS/MMS messages from the device. Returns sender address, body text, "
                + "timestamp and message type (inbox/sent/draft). Requires READ_SMS permission. "
                + "Use filter_address to read messages from a specific contact, "
                + "or filter_keyword to search by content.";
    }

    @Override
    public String getDescriptionCN() {
        return "读取设备上的短信/彩信。返回发件人地址、正文内容、时间戳和消息类型（收件箱/已发送/草稿）。"
                + "需要 READ_SMS 权限。"
                + "使用 filter_address 读取特定联系人的短信，"
                + "或使用 filter_keyword 按内容关键词搜索。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        List<ToolParameter> params = new ArrayList<>();
        params.add(new ToolParameter("limit", "integer",
                "Maximum number of messages to return (default 20, max 100).",
                false));
        params.add(new ToolParameter("filter_address", "string",
                "Optional: filter messages by sender/recipient address (phone number, partial match).",
                false));
        params.add(new ToolParameter("filter_keyword", "string",
                "Optional: filter messages by keyword in body text (case-insensitive, partial match).",
                false));
        params.add(new ToolParameter("box", "string",
                "Optional: which box to read. Values: 'inbox' (received), 'sent', 'draft', 'all' (default: 'all').",
                false));
        return params;
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        Context context = ClawApplication.Companion.getInstance();

        // 权限检查
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            return ToolResult.error("READ_SMS permission not granted. "
                    + "Please grant SMS permission in app settings to use this tool.");
        }

        int limit = optionalInt(params, "limit", DEFAULT_LIMIT);
        if (limit < 1) limit = 1;
        if (limit > MAX_LIMIT) limit = MAX_LIMIT;

        String filterAddress = optionalString(params, "filter_address", null);
        String filterKeyword = optionalString(params, "filter_keyword", null);
        String box = optionalString(params, "box", "all").toLowerCase();

        // 确定 URI
        Uri uri;
        switch (box) {
            case "inbox":
                uri = Telephony.Sms.Inbox.CONTENT_URI;
                break;
            case "sent":
                uri = Telephony.Sms.Sent.CONTENT_URI;
                break;
            case "draft":
                uri = Telephony.Sms.Draft.CONTENT_URI;
                break;
            default:
                uri = Telephony.Sms.CONTENT_URI; // all
                break;
        }

        // 构建查询
        String selection = null;
        String[] selectionArgs = null;

        if (filterAddress != null) {
            selection = "address LIKE ?";
            selectionArgs = new String[]{"%" + filterAddress + "%"};
        }

        // 按时间倒序
        String sortOrder = "date DESC";

        StringBuilder result = new StringBuilder();
        int count = 0;

        try {
            ContentResolver resolver = context.getContentResolver();
            Cursor cursor = resolver.query(uri, null, selection, selectionArgs, sortOrder);
            if (cursor == null) {
                return ToolResult.error("Failed to query SMS. ContentResolver returned null.");
            }

            try {
                int addressIdx = cursor.getColumnIndex("address");
                int bodyIdx = cursor.getColumnIndex("body");
                int dateIdx = cursor.getColumnIndex("date");
                int typeIdx = cursor.getColumnIndex("type");

                while (cursor.moveToNext() && count < limit) {
                    String address = addressIdx >= 0 ? cursor.getString(addressIdx) : "unknown";
                    String body = bodyIdx >= 0 ? cursor.getString(bodyIdx) : "";
                    long dateMs = dateIdx >= 0 ? cursor.getLong(dateIdx) : 0L;
                    int type = typeIdx >= 0 ? cursor.getInt(typeIdx) : 0;

                    // 关键词过滤
                    if (filterKeyword != null && body != null) {
                        if (!body.toLowerCase().contains(filterKeyword.toLowerCase())) {
                            continue;
                        }
                    }

                    String typeLabel;
                    switch (type) {
                        case 1: typeLabel = "inbox"; break;
                        case 2: typeLabel = "sent"; break;
                        case 3: typeLabel = "draft"; break;
                        default: typeLabel = "type_" + type; break;
                    }

                    String dateStr = dateMs > 0 ? DATE_FORMAT.format(new Date(dateMs)) : "unknown";

                    result.append("[").append(count + 1).append("] ")
                            .append("type=").append(typeLabel)
                            .append(", address=").append(address != null ? address : "unknown")
                            .append(", date=").append(dateStr)
                            .append("\nbody=").append(body != null ? body : "(empty)")
                            .append("\n\n");
                    count++;
                }
            } finally {
                cursor.close();
            }

        } catch (Exception e) {
            XLog.e(TAG, "read_sms query failed", e);
            return ToolResult.error("Failed to read SMS: " + e.getMessage());
        }

        if (count == 0) {
            return ToolResult.success("No SMS messages found"
                    + (filterAddress != null ? " from " + filterAddress : "")
                    + (filterKeyword != null ? " matching '" + filterKeyword + "'" : "")
                    + ".");
        }

        return ToolResult.success("Found " + count + " SMS message(s):\n\n" + result.toString().trim());
    }
}
