package com.apk.claw.android.octopus_mobile

/**
 * 意图分类器 —— 基于关键词规则，零 LLM 调用，μs 级.
 *
 * 把用户自然语言输入分类为：
 *  - BROWSER：浏览器自动化任务（打开网页、搜索网站、提取页面内容等）
 *  - MOBILE：手机自动化任务（打开 App、点击按钮、滑动屏幕等）
 *  - MIXED：混合任务（可能同时涉及两者，如"在淘宝网页版搜索"）
 *  - AMBIGUOUS：无法判断，保持当前领域
 *
 * 设计原则：
 *  - 保守策略：不确定时返回 AMBIGUOUS，让系统保持当前领域
 *  - 关键词覆盖中文/英文常见表达
 *  - 权重累加：命中多个关键词时取最高分类
 */
object IntentClassifier {

    /** 意图类型 */
    enum class IntentType {
        BROWSER,    // 浏览器自动化
        MOBILE,     // 手机自动化
        MIXED,      // 混合
        AMBIGUOUS   // 不明确
    }

    /** 分类结果 */
    data class ClassificationResult(
        val primary: IntentType,
        val confidence: Double,  // 0.0 - 1.0
        val matchedKeywords: List<String> = emptyList()
    )

    // ── 关键词库 ──────────────────────────────────────────

    /** 浏览器意图关键词（权重 1.0） */
    private val BROWSER_KEYWORDS = setOf(
        // 中文
        "网页", "网站", "浏览器", "打开网页", "打开网站", "访问网页", "访问网站",
        "网址", "url", "链接", "页面", "网页版", "web版", "web版",
        "搜索网页", "网页搜索", "网上", "上网", "浏览网页",
        "chrome", "firefox", "gecko", "webview", "web视图",
        "html", "dom", "css", "javascript", "js执行", "执行js",
        "截图网页", "网页截图", "浏览器截图", "保存网页", "下载网页",
        // 英文
        "webpage", "website", "browser", "open url", "visit url", "go to url",
        "navigate to", "web page", "web site", "browse to", "surf",
        "chrome", "firefox", "gecko", "webview",
        "webpage screenshot", "website screenshot",
    )

    /** 手机意图关键词（权重 1.0） */
    private val MOBILE_KEYWORDS = setOf(
        // 中文
        "打开应用", "打开app", "打开软件", "启动应用", "启动app",
        "点击", "长按", "滑动", "拖拽", "划动",
        "返回键", "home键", "音量", "电源键", "菜单键",
        "截屏", "截图", "录屏", "屏幕",
        "输入文字", "输入文本", "打字", "粘贴",
        "找元素", "找按钮", "找文字", "找图标",
        "安装应用", "卸载应用", "更新应用",
        "系统设置", "通知栏", "状态栏",
        "桌面", "主屏幕", "应用列表", "最近任务",
        // 英文
        "open app", "launch app", "start app", "tap", "long press", "swipe",
        "scroll", "drag", "pinch", "zoom",
        "home button", "back button", "volume up", "volume down", "power button",
        "record screen", "screenshot", "take screenshot",
        "type text", "input text", "enter text", "paste",
        "find element", "find button", "find text",
        "install app", "uninstall app",
    )

    /** 混合意图关键词（同时命中浏览器+手机，或特定混合表达） */
    private val MIXED_KEYWORDS = setOf(
        "在淘宝网页版", "在京东网页版", "用浏览器打开app",
        "网页版淘宝", "网页版京东", "web版",
        "手机浏览器", "移动端网页", "mobile web",
        "app内网页", "app内浏览器", "内置浏览器",
    )

    // ── 核心分类逻辑 ──────────────────────────────────────

    /**
     * 分类用户输入.
     *
     * @param input 用户自然语言输入
     * @return ClassificationResult
     */
    fun classify(input: String): ClassificationResult {
        val lower = input.lowercase()

        // 1. 先检查混合关键词（最高优先级）
        val mixedHits = MIXED_KEYWORDS.filter { lower.contains(it.lowercase()) }
        if (mixedHits.isNotEmpty()) {
            return ClassificationResult(
                primary = IntentType.MIXED,
                confidence = 0.9,
                matchedKeywords = mixedHits
            )
        }

        // 2. 分别统计浏览器/手机关键词命中
        val browserHitsRaw = BROWSER_KEYWORDS.filter { lower.contains(it.lowercase()) }
        val mobileHitsRaw = MOBILE_KEYWORDS.filter { lower.contains(it.lowercase()) }

        // 2.5 重叠消解：若己方命中词只是对方更长命中词的子串
        //（如"截图" ⊂ "截图网页"、"screenshot" ⊂ "webpage screenshot"），
        // 视为被更具体的表达吸收，不计入己方得分
        val browserHits = browserHitsRaw.filter { b ->
            mobileHitsRaw.none { m -> m != b && m.lowercase().contains(b.lowercase()) }
        }
        val mobileHits = mobileHitsRaw.filter { m ->
            browserHitsRaw.none { b -> b != m && b.lowercase().contains(m.lowercase()) }
        }

        val browserScore = browserHits.size
        val mobileScore = mobileHits.size

        // 3. 决策
        return when {
            // 明确浏览器
            browserScore > 0 && mobileScore == 0 -> ClassificationResult(
                primary = IntentType.BROWSER,
                confidence = minOf(0.5 + browserScore * 0.15, 0.95),
                matchedKeywords = browserHits
            )
            // 明确手机
            mobileScore > 0 && browserScore == 0 -> ClassificationResult(
                primary = IntentType.MOBILE,
                confidence = minOf(0.5 + mobileScore * 0.15, 0.95),
                matchedKeywords = mobileHits
            )
            // 两者都有 → 混合
            browserScore > 0 && mobileScore > 0 -> ClassificationResult(
                primary = IntentType.MIXED,
                confidence = 0.8,
                matchedKeywords = browserHits + mobileHits
            )
            // 什么都没有 → 不明确
            else -> ClassificationResult(
                primary = IntentType.AMBIGUOUS,
                confidence = 0.0,
                matchedKeywords = emptyList()
            )
        }
    }

    /**
     * 快速判断是否是浏览器意图.
     */
    fun isBrowserIntent(input: String): Boolean {
        return classify(input).primary == IntentType.BROWSER
    }

    /**
     * 快速判断是否是手机意图.
     */
    fun isMobileIntent(input: String): Boolean {
        return classify(input).primary == IntentType.MOBILE
    }
}
