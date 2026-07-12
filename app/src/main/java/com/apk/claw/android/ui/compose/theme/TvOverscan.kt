package com.apk.claw.android.ui.compose.theme

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.apk.claw.android.utils.DeviceUtils

/**
 * TV overscan 安全边距。
 * TV 屏幕边缘会裁切,关键 UI 需加 48dp padding。
 * 手机上无副作用(返回 Modifier)。
 */
@Composable
fun Modifier.tvOverscan(): Modifier {
    val context = LocalContext.current
    return if (DeviceUtils.isTvDevice(context)) {
        this.padding(48.dp)
    } else {
        this
    }
}
