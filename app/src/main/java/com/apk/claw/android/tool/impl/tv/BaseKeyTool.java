package com.apk.claw.android.tool.impl.tv;

import com.apk.claw.android.service.ClawAccessibilityService;
import com.apk.claw.android.tool.BaseTool;
import com.apk.claw.android.tool.ToolParameter;
import com.apk.claw.android.tool.ToolResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Base class for simple TV remote key tools that send a single key event.
 */
public abstract class BaseKeyTool extends BaseTool {

    /**
     * Returns the Android KeyEvent keycode to send.
     */
    protected abstract int getKeyCode();

    /**
     * Returns a human-readable label for logging (e.g. "D-pad Up").
     */
    protected abstract String getKeyLabel();

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter("repeat", "integer",
                        "重复按键次数,默认 1,最大 20", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = ClawAccessibilityService.getInstance();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }
        int repeat;
        try {
            repeat = optionalInt(params, "repeat", 1);
        } catch (Exception e) {
            repeat = 1;
        }
        repeat = Math.max(1, Math.min(repeat, 20)); // 限制 1-20 次

        int success = 0;
        for (int i = 0; i < repeat; i++) {
            checkCancelled();
            if (service.sendKeyEvent(getKeyCode())) {
                success++;
            }
            if (i < repeat - 1) {
                sleepInterruptible(100); // 按键间隔 100ms,可被取消中断
            }
        }
        return success == repeat
                ? ToolResult.success("Pressed " + getKeyLabel() + " x" + repeat)
                : ToolResult.error("Failed to press " + getKeyLabel()
                        + " (" + success + "/" + repeat + " succeeded)");
    }
}
