package com.apk.claw.android.ui.compose.theme

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp

/**
 * TV/大屏焦点高亮修饰器。
 * 聚焦时 1.05x 放大 + 2dp 高亮边框,适配遥控器导航。
 * 手机上无副作用(仍可正常点击)。
 */
@Composable
fun Modifier.tvFocusable(
    cornerRadius: Int = 12,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    return this
        .focusable(interactionSource = interactionSource)
        .then(
            if (isFocused) {
                Modifier
                    .scale(1.05f)
                    .border(2.dp, OctopusColors.Primary, RoundedCornerShape(cornerRadius.dp))
            } else {
                Modifier
            }
        )
}

/**
 * TV 焦点恢复占位:当焦点丢失时,自动恢复到指定的默认焦点元素。
 *
 * 当前为简化实现(BaseActivity 的 dispatchKeyEvent 已做兜底),
 * 后续可在此处接入 FocusRequester + LaunchedEffect 监听焦点丢失。
 */
@Composable
fun rememberFocusRestorer(): Modifier {
    return Modifier
}
