@file:Suppress("PackageNaming")   // 沿用既有 octopus_mobile 包(带下划线,存量已在 baseline)

package com.apk.claw.android.octopus_mobile

import com.apk.claw.android.utils.KVUtils

/**
 * 远程投屏画质档偏好(全局共享)—— 远程全控页、预览缩略图、设置默认都读这一份。
 *
 * 流畅=高帧率小画面(30fps);清晰=高分辨率高质量、帧率略降(24fps)。
 * 局域网直连带宽足,主要权衡采集/编码/解码开销。
 */
object RemoteStreamPrefs {
    const val SMOOTH = "smooth"
    const val SHARP = "sharp"
    private const val KEY = "REMOTE_STREAM_MODE"

    fun mode(): String = KVUtils.getString(KEY, SMOOTH)
    fun setMode(value: String) = KVUtils.putString(KEY, value)
    fun isSharp(): Boolean = mode() == SHARP

    /** /api/screen/stream 的查询参数(不含 token)。 */
    fun streamParams(): String =
        if (isSharp()) "quality=82&maxWidth=1080&fps=24" else "quality=60&maxWidth=720&fps=30"

    /** 控制条/设置里显示的当前档名。 */
    fun label(): String = if (isSharp()) "🔎 清晰" else "⚡ 流畅"
}
