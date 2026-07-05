// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // Chaquopy —— Android 上的 Python 解释器集成(17.0.0 已完全开源免费,Maven Central)。
    // 仅在 app 模块 apply,此处只声明版本。
    alias(libs.plugins.chaquopy) apply false
}