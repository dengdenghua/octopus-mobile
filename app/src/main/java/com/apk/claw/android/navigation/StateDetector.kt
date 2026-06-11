package com.apk.claw.android.navigation

import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.shizuku.ShizukuShellService
import java.security.MessageDigest

/**
 * UI 状态指纹计算器。
 *
 * 三重指纹组合判定"当前在哪个界面"：
 * 1. Package + Activity（粗定位）
 * 2. UI 树结构 hash（精确定位，忽略动态内容）
 * 3. 关键 UI 元素签名（语义锚点）
 */
object StateDetector {

    private const val TAG = "StateDetector"

    /**
     * 计算当前 UI 的状态指纹。
     *
     * @return StateFingerprint，AccessibilityService 不可用时返回 null
     */
    fun detectCurrentState(): StateFingerprint? {
        val service = ClawAccessibilityService.getInstance() ?: return null

        // 1. Package + Activity
        val packageActivity = getTopPackageActivity()

        // 2. UI 树结构 hash
        val uiTree = service.screenTreeFull ?: return null
        val treeHash = computeTreeHash(uiTree)

        // 3. 关键 UI 元素签名
        val keyElements = extractKeyElements(uiTree)

        return StateFingerprint(
            packageActivity = packageActivity,
            uiTreeHash = treeHash,
            keyElements = keyElements
        )
    }

    /**
     * 获取当前顶层的 Package + Activity。
     * 优先使用 Shizuku（更快），fallback 到 AccessibilityService。
     */
    private fun getTopPackageActivity(): String {
        // Shizuku 方式
        val topActivity = ShizukuShellService.getTopActivity()
        if (topActivity != null && !topActivity.startsWith("Error")) {
            return topActivity.trim()
        }
        // Fallback: AccessibilityService dump
        val service = ClawAccessibilityService.getInstance() ?: return "unknown"
        val tree = service.screenTreeFull ?: return "unknown"
        // 从 UI 树中提取第一个 pkg 属性
        val pkgMatch = Regex("pkg=\"([^\"]+)\"").find(tree)
        return pkgMatch?.groupValues?.get(1) ?: "unknown"
    }

    /**
     * 计算 UI 树的结构 hash。
     * 忽略动态内容（时间、推荐列表项等），只保留结构骨架。
     */
    fun computeTreeHash(uiTree: String): String {
        // 预处理：移除动态内容
        val normalized = normalizeTree(uiTree)
        val digest = MessageDigest.getInstance("MD5")
        val hash = digest.digest(normalized.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }.take(12)
    }

    /**
     * 归一化 UI 树 —— 移除动态元素，保留结构骨架。
     *
     * 移除规则：
     * - 时间/日期文本（如 "12:34"、"2026年6月"）
     * - 纯数字（如播放量、评分）
     * - 推荐列表项的具体标题（保留结构位置）
     * - text 属性中的具体值（保留是否有 text 属性）
     */
    private fun normalizeTree(tree: String): String {
        val lines = tree.lines()
        val sb = StringBuilder()
        for (line in lines) {
            // 保留 class/type 和 id，移除 text 内容
            val stripped = line
                .replace(Regex("text=\"[^\"]*\""), "text=\"*\"")
                .replace(Regex("content-desc=\"[^\"]*\""), "content-desc=\"*\"")
                .trim()
            if (stripped.isNotEmpty()) {
                sb.append(stripped).append('\n')
            }
        }
        return sb.toString()
    }

    /**
     * 提取关键 UI 元素签名 —— 作为语义锚点。
     *
     * 提取规则：
     * - 按钮文本（Button / ImageButton 的 content-desc 或 text）
     * - Tab 标签文本
     * - 搜索框 placeholder
     * - 导航栏项目
     *
     * 最多提取 10 个关键元素，总长度限制 200 字符。
     */
    fun extractKeyElements(uiTree: String): List<String> {
        val elements = mutableListOf<String>()

        // 提取 content-desc（通常是按钮/图标的语义描述）
        val descMatches = Regex("content-desc=\"([^\"]+)\"").findAll(uiTree)
        for (match in descMatches) {
            val desc = match.groupValues[1]
            if (desc.length in 1..20 && !desc.all { it.isDigit() }) {
                elements.add("desc:$desc")
            }
            if (elements.size >= 5) break
        }

        // 提取关键 class 类型（Button, Tab, EditText）
        val classMatches = Regex("class=\"[^\"]*\\.(Button|Tab|EditText|SearchView|TabLayout)\"").findAll(uiTree)
        for (match in classMatches) {
            elements.add("cls:${match.groupValues[1]}")
            if (elements.size >= 10) break
        }

        return elements.take(10)
    }

    /**
     * 计算两个指纹的相似度（0.0 ~ 1.0）。
     * 用于 checkpoint 校验和节点去重。
     */
    fun similarity(a: StateFingerprint, b: StateFingerprint): Double {
        var score = 0.0
        // Package 匹配：0.5 权重
        if (a.packageActivity == b.packageActivity) score += 0.5
        // Tree hash 匹配：0.3 权重
        if (a.uiTreeHash == b.uiTreeHash) score += 0.3
        // 关键元素交集：0.2 权重
        if (a.keyElements.isNotEmpty() && b.keyElements.isNotEmpty()) {
            val intersection = a.keyElements.intersect(b.keyElements.toSet()).size
            val union = (a.keyElements + b.keyElements).toSet().size
            if (union > 0) score += 0.2 * (intersection.toDouble() / union)
        }
        return score
    }
}

/**
 * UI 状态指纹 —— 唯一标识一个屏幕状态。
 */
data class StateFingerprint(
    val packageActivity: String,
    val uiTreeHash: String,
    val keyElements: List<String>
) {
    /** 指纹 ID —— 用于图的节点 key */
    val id: String
        get() {
            val digest = MessageDigest.getInstance("MD5")
            val input = "$packageActivity|$uiTreeHash|${keyElements.sorted().joinToString(",")}"
            val hash = digest.digest(input.toByteArray())
            return hash.joinToString("") { "%02x".format(it) }.take(16)
        }
}
