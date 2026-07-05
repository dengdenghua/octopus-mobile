package com.apk.claw.android

/**
 * 前端特性开关 —— 未完成/暂不放量的功能在这里统一屏蔽,做好后翻一行放开。
 * 只做「入口隐藏」,不删功能代码:路由与实现保留,方便随时恢复与内部调试。
 */
object FeatureFlags {

    /**
     * Echo Universe「绑定角色 / 数字生命(My Ghost)」。
     * 功能未完成,所有用户可见入口隐藏(2026-07-05 用户拍板)。屏蔽范围:
     * 发现页动作胶囊(服务端下发的 action=universe 同样被过滤)。
     * UniverseScreen 路由保留但无入口可达;已存在的 ghost 会话不受影响。
     */
    const val UNIVERSE_ENABLED = false
}
