package com.apk.claw.android.tool.impl.mobile;

import com.apk.claw.android.ClawApplication;
import com.apk.claw.android.R;
import com.apk.claw.android.octopus_mobile.ControlTarget;
import com.apk.claw.android.octopus_mobile.DeviceInfo;
import com.apk.claw.android.octopus_mobile.RemoteActions;
import com.apk.claw.android.service.ClawAccessibilityService;
import com.apk.claw.android.tool.BaseTool;
import com.apk.claw.android.tool.ToolParameter;
import com.apk.claw.android.tool.ToolResult;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class LongPressTool extends BaseTool {

    @Override
    public String getName() {
        return "long_press";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_long_press);
    }

    @Override
    public String getDescriptionEN() {
        return "Perform a long press at the specified screen coordinates (x, y) for a given duration.";
    }

    @Override
    public String getDescriptionCN() {
        return "在指定的屏幕坐标 (x, y) 处执行长按操作，持续指定时长。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("x", "integer", "X coordinate on screen", true),
                new ToolParameter("y", "integer", "Y coordinate on screen", true),
                new ToolParameter("duration_ms", "integer", "Duration of long press in milliseconds (default 1000)", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        int x = requireInt(params, "x");
        int y = requireInt(params, "y");
        long duration = optionalLong(params, "duration_ms", 1000);
        DeviceInfo remote = ControlTarget.remoteTarget();
        if (remote != null) {
            boolean ok = RemoteActions.longPress(remote, x, y, duration);
            return ok ? ToolResult.success("Long pressed at (" + x + ", " + y + ") on " + remote.getDeviceName())
                    : ToolResult.error("Remote long press failed on " + remote.getDeviceName());
        }
        ClawAccessibilityService service = ClawAccessibilityService.getInstance();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }
        String boundsError = validateCoordinates(x, y);
        if (boundsError != null) return ToolResult.error(boundsError);
        boolean success = service.performLongPress(x, y, duration);
        return success ? ToolResult.success("Long pressed at (" + x + ", " + y + ") for " + duration + "ms")
                : ToolResult.error("Failed to long press at (" + x + ", " + y + ")");
    }
}
