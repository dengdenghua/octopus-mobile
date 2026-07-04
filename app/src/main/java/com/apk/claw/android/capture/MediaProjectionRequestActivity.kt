package com.apk.claw.android.capture

import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.apk.claw.android.utils.XLog

/**
 * 无界面的透明中转 Activity —— 只为拉起系统「投屏/录屏」同意框。
 * MediaProjection 的授权令牌必须经由 Activity 结果拿到,不能从 Service 直接申请。
 *
 * 用户同意 → 把令牌交给 [ScreenCaptureService] 启动采集;取消 → 直接结束,不留痕。
 */
class MediaProjectionRequestActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            ScreenCaptureService.startWithToken(this, result.resultCode, data)
        } else {
            XLog.i(TAG, "用户取消了投屏授权")
            Toast.makeText(this, "已取消高清采集授权", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        runCatching { launcher.launch(mpm.createScreenCaptureIntent()) }
            .onFailure {
                XLog.e(TAG, "拉起投屏授权失败: ${it.message}")
                finish()
            }
    }

    companion object {
        private const val TAG = "MediaProjectionRequest"
    }
}
