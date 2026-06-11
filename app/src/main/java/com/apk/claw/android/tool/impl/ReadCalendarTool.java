package com.apk.claw.android.tool.impl;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

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
 * 读取日历工具 —— 通过 ContentResolver 读取设备日历事件。
 *
 * 需要运行时权限：READ_CALENDAR
 * 支持按时间范围查询，默认查询未来 7 天的事件。
 */
public class ReadCalendarTool extends BaseTool {

    private static final String TAG = "ReadCalendarTool";
    private static final int DEFAULT_DAYS_AHEAD = 7;
    private static final int MAX_EVENTS = 50;

    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
    private static final SimpleDateFormat DATE_ONLY_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    @Override
    public String getName() {
        return "read_calendar";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_read_calendar);
    }

    @Override
    public String getDescriptionEN() {
        return "Read calendar events from the device. Returns event title, start/end time, "
                + "location, description and calendar name. Requires READ_CALENDAR permission. "
                + "By default reads events for the next 7 days. Use days_ahead to adjust the range, "
                + "or filter_keyword to search by event title.";
    }

    @Override
    public String getDescriptionCN() {
        return "读取设备上的日历事件。返回事件标题、起止时间、地点、描述和所属日历名称。"
                + "需要 READ_CALENDAR 权限。"
                + "默认查询未来 7 天的事件。使用 days_ahead 调整时间范围，"
                + "或使用 filter_keyword 按标题关键词搜索。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        List<ToolParameter> params = new ArrayList<>();
        params.add(new ToolParameter("days_ahead", "integer",
                "Number of days ahead to query events for (default 7, max 90).",
                false));
        params.add(new ToolParameter("days_behind", "integer",
                "Number of days behind (past) to include (default 0, max 30).",
                false));
        params.add(new ToolParameter("filter_keyword", "string",
                "Optional: filter events by keyword in title (case-insensitive, partial match).",
                false));
        return params;
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        Context context = ClawApplication.Companion.getInstance();

        // 权限检查
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
                != PackageManager.PERMISSION_GRANTED) {
            return ToolResult.error("READ_CALENDAR permission not granted. "
                    + "Please grant calendar permission in app settings to use this tool.");
        }

        int daysAhead = optionalInt(params, "days_ahead", DEFAULT_DAYS_AHEAD);
        if (daysAhead < 1) daysAhead = 1;
        if (daysAhead > 90) daysAhead = 90;

        int daysBehind = optionalInt(params, "days_behind", 0);
        if (daysBehind < 0) daysBehind = 0;
        if (daysBehind > 30) daysBehind = 30;

        String filterKeyword = optionalString(params, "filter_keyword", null);

        long nowMs = System.currentTimeMillis();
        long startMs = nowMs - (daysBehind * 24L * 60 * 60 * 1000);
        long endMs = nowMs + (daysAhead * 24L * 60 * 60 * 1000);

        // 查询事件
        Uri eventsUri = CalendarContract.Events.CONTENT_URI;
        String[] projection = new String[]{
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.CALENDAR_DISPLAY_NAME,
                CalendarContract.Events.ALL_DAY,
        };

        String selection = CalendarContract.Events.DTSTART + " >= ? AND "
                + CalendarContract.Events.DTSTART + " <= ? AND "
                + CalendarContract.Events.DELETED + " = 0";
        String[] selectionArgs = new String[]{String.valueOf(startMs), String.valueOf(endMs)};
        String sortOrder = CalendarContract.Events.DTSTART + " ASC";

        StringBuilder result = new StringBuilder();
        int count = 0;

        try {
            ContentResolver resolver = context.getContentResolver();
            Cursor cursor = resolver.query(eventsUri, projection, selection, selectionArgs, sortOrder);
            if (cursor == null) {
                return ToolResult.error("Failed to query calendar. ContentResolver returned null.");
            }

            try {
                int idIdx = cursor.getColumnIndex(CalendarContract.Events._ID);
                int titleIdx = cursor.getColumnIndex(CalendarContract.Events.TITLE);
                int startIdx = cursor.getColumnIndex(CalendarContract.Events.DTSTART);
                int endIdx = cursor.getColumnIndex(CalendarContract.Events.DTEND);
                int locationIdx = cursor.getColumnIndex(CalendarContract.Events.EVENT_LOCATION);
                int descIdx = cursor.getColumnIndex(CalendarContract.Events.DESCRIPTION);
                int calNameIdx = cursor.getColumnIndex(CalendarContract.Events.CALENDAR_DISPLAY_NAME);
                int allDayIdx = cursor.getColumnIndex(CalendarContract.Events.ALL_DAY);

                while (cursor.moveToNext() && count < MAX_EVENTS) {
                    String title = titleIdx >= 0 ? cursor.getString(titleIdx) : "(no title)";

                    // 关键词过滤
                    if (filterKeyword != null && title != null) {
                        if (!title.toLowerCase().contains(filterKeyword.toLowerCase())) {
                            continue;
                        }
                    }

                    long startEventMs = startIdx >= 0 ? cursor.getLong(startIdx) : 0L;
                    long endEventMs = endIdx >= 0 ? cursor.getLong(endIdx) : 0L;
                    String location = locationIdx >= 0 ? cursor.getString(locationIdx) : null;
                    String description = descIdx >= 0 ? cursor.getString(descIdx) : null;
                    String calName = calNameIdx >= 0 ? cursor.getString(calNameIdx) : "unknown";
                    int allDay = allDayIdx >= 0 ? cursor.getInt(allDayIdx) : 0;

                    String startTime = startEventMs > 0 ? DATE_FORMAT.format(new Date(startEventMs)) : "unknown";
                    String endTime = endEventMs > 0 ? DATE_FORMAT.format(new Date(endEventMs)) : "unknown";

                    result.append("[").append(count + 1).append("] ")
                            .append("title=").append(title != null ? title : "(no title)")
                            .append("\n  start=").append(startTime)
                            .append(", end=").append(endTime);

                    if (allDay == 1) {
                        result.append(" (all day)");
                    }

                    result.append("\n  calendar=").append(calName);

                    if (location != null && !location.isEmpty()) {
                        result.append("\n  location=").append(location);
                    }
                    if (description != null && !description.isEmpty()) {
                        // 截断过长描述
                        String desc = description.length() > 200
                                ? description.substring(0, 200) + "..."
                                : description;
                        result.append("\n  description=").append(desc);
                    }

                    result.append("\n\n");
                    count++;
                }
            } finally {
                cursor.close();
            }

        } catch (Exception e) {
            XLog.e(TAG, "read_calendar query failed", e);
            return ToolResult.error("Failed to read calendar: " + e.getMessage());
        }

        if (count == 0) {
            return ToolResult.success("No calendar events found"
                    + (filterKeyword != null ? " matching '" + filterKeyword + "'" : "")
                    + " in the range ["
                    + DATE_ONLY_FORMAT.format(new Date(startMs)) + " ~ "
                    + DATE_ONLY_FORMAT.format(new Date(endMs)) + "].");
        }

        return ToolResult.success("Found " + count + " calendar event(s) ["
                + DATE_ONLY_FORMAT.format(new Date(startMs)) + " ~ "
                + DATE_ONLY_FORMAT.format(new Date(endMs)) + "]:\n\n"
                + result.toString().trim());
    }
}
