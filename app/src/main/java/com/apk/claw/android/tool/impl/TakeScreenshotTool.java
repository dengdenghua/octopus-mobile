package com.apk.claw.android.tool.impl;

import android.graphics.Bitmap;
import android.util.Base64;

import com.apk.claw.android.ClawApplication;
import com.apk.claw.android.R;
import com.apk.claw.android.octopus_mobile.ControlTarget;
import com.apk.claw.android.octopus_mobile.DeviceInfo;
import com.apk.claw.android.octopus_mobile.RemoteActions;
import com.apk.claw.android.service.ClawAccessibilityService;
import com.apk.claw.android.tool.BaseTool;
import com.apk.claw.android.tool.ToolParameter;
import com.apk.claw.android.tool.ToolResult;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TakeScreenshotTool extends BaseTool {

    @Override
    public String getName() {
        return "take_screenshot";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_screenshot);
    }

    @Override
    public String getDescriptionEN() {
        return "Take a screenshot of the current screen. Returns the local file path of the saved PNG image. Requires Android 11+ (API 30).";
    }

    @Override
    public String getDescriptionCN() {
        return "对当前屏幕进行截图，保存为 PNG 文件并返回本地文件路径。需要 Android 11+（API 30）。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        List<ToolParameter> params = new ArrayList<>();
        params.add(new ToolParameter("return_base64", "boolean",
            "是否返回 base64 编码的图片数据，默认 true。设为 false 则只返回文件路径", false));
        return params;
    }

    /** base64 图片最大宽度，超过则等比缩放 */
    private static final int MAX_BASE64_WIDTH = 720;
    /** JPEG 压缩质量（0-100），用于 base64 编码 */
    private static final int JPEG_QUALITY = 50;

    @Override
    public ToolResult execute(Map<String, Object> params) {
        Bitmap bitmap;
        DeviceInfo remote = ControlTarget.remoteTarget();
        if (remote != null) {
            bitmap = RemoteActions.screenshot(remote);
            if (bitmap == null) {
                return ToolResult.error("Remote screenshot failed on " + remote.getDeviceName());
            }
        } else {
            ClawAccessibilityService service = ClawAccessibilityService.getInstance();
            if (service == null) {
                return ToolResult.error("Accessibility service is not running");
            }
            bitmap = service.takeScreenshot(5000);
            if (bitmap == null) {
                return ToolResult.error("Failed to take screenshot. Requires Android 11+ (API 30).");
            }
        }

        // 解析 return_base64 参数，默认为 true
        boolean returnBase64 = true;
        if (params != null && params.containsKey("return_base64")) {
            Object val = params.get("return_base64");
            if (val instanceof Boolean) {
                returnBase64 = (Boolean) val;
            } else if (val instanceof String) {
                returnBase64 = Boolean.parseBoolean((String) val);
            }
        }

        try {
            Bitmap softBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (softBitmap != null) {
                bitmap.recycle();
                bitmap = softBitmap;
            }

            // 保存 PNG 文件（保留现有逻辑）
            File dir = new File(ClawApplication.Companion.getInstance().getCacheDir(), "screenshots");
            if (!dir.exists()) dir.mkdirs();

            String filename = System.currentTimeMillis() + ".png";
            File file = new File(dir, filename);

            try (FileOutputStream fos = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }

            String filePath = file.getAbsolutePath();

            // 如果不需要 base64，直接返回文件路径
            if (!returnBase64) {
                bitmap.recycle();
                return ToolResult.success(filePath);
            }

            // 缩放图片以控制 base64 大小（最大宽度 720px）
            Bitmap scaledBitmap = bitmap;
            if (bitmap.getWidth() > MAX_BASE64_WIDTH) {
                float scale = (float) MAX_BASE64_WIDTH / bitmap.getWidth();
                int newHeight = Math.round(bitmap.getHeight() * scale);
                scaledBitmap = Bitmap.createScaledBitmap(bitmap, MAX_BASE64_WIDTH, newHeight, true);
                bitmap.recycle();
            }

            // 压缩为 JPEG 并转 base64
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos);
            scaledBitmap.recycle();
            byte[] jpegBytes = baos.toByteArray();
            String base64Str = Base64.encodeToString(jpegBytes, Base64.NO_WRAP);

            return ToolResult.successWithImage(filePath, base64Str);
        } catch (Exception e) {
            if (!bitmap.isRecycled()) {
                bitmap.recycle();
            }
            return ToolResult.error("Failed to save screenshot: " + e.getMessage());
        }
    }
}
