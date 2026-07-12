package com.apk.claw.android.agent

/**
 * 省流感知判定 —— 决定每轮该注入「无障碍树文字」(便宜)还是「vision 截图」(贵)。
 *
 * 背景:DefaultAgentService 默认每轮无条件注入一张 vision 截图(约上千 token),
 * 而多数标准 Android 界面的无障碍树已能完整描述屏幕,截图是冗余开销。
 * 省流模式开启时:树够丰富 → 注入树文字(约省 10× token);树太稀疏(游戏/Canvas/
 * 自绘/空窗)→ 回退截图,不牺牲这类必须靠视觉的场景。
 *
 * 这是**成本换可靠性**的显式取舍(纯文本转述弱于视觉),故默认关,适合群控/无人值守省流。
 * 纯逻辑,不依赖 Android,可 JVM 单测。
 */
object FrugalPerception {

    /** 树文字短于此阈值视为稀疏(不足以替代视觉)→ 回退截图。 */
    const val MIN_TREE_CHARS = 200

    /** 无障碍树是否丰富到可替代本轮 vision 截图。 */
    fun isTreeRichEnough(tree: String?): Boolean =
        tree != null && tree.trim().length >= MIN_TREE_CHARS
}
