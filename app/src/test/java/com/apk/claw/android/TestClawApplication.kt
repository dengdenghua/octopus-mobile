package com.apk.claw.android

/**
 * Robolectric 单元测试专用 Application。
 *
 * 跳过 ClawApplication 的全量初始化（MMKV/mpv 原生库、前台服务、Shizuku、通道），
 * 这些在 JVM 上不可用（如 MMKV 触发 android.os.Process.is64Bit 的 NoSuchMethodError）。
 * 保留 instance 赋值，使依赖 Context/资源的代码（如工具的 getDisplayName）可用。
 *
 * 用法：@Config(application = TestClawApplication::class)
 */
class TestClawApplication : ClawApplication() {
    override fun initializeApp() {
        // 单元测试不做全量初始化
    }
}
