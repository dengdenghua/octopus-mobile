package com.apk.claw.android.octopus_mobile.skill

import com.apk.claw.android.utils.KVUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 提示词技能库 —— 把「Claude skill」那套移植到端侧:一个技能 = 一段 markdown 指令包,
 * 相关时注入 Agent 的 System Prompt,Agent 再用**它自己的工具**去执行。
 *
 * 与 Claude Code 的区别在于「loader 在哪」:Claude 把加载器内建在 harness 里,这里由本 store +
 * [com.apk.claw.android.AppViewModel] 的 `dynamicPromptSuffix` 注入充当加载器。技能本体是纯
 * markdown(不编译/不进沙箱),所以简单的 Claude skill 内容改改工具名就能直接放进来用。
 *
 * v1:注入所有 **enabled** 的技能(封顶 [MAX_SECTION_CHARS]),由用户在技能页开关控制哪些生效;
 * 按 prompt 相关性只注命中项是 v1.1 的事。
 */
object PromptSkillStore {

    data class PromptSkill(
        val id: String,
        val name: String,
        /** 触发描述:一句话说明「什么时候该用这个技能」(给人看,也帮以后做相关性匹配)。 */
        val description: String,
        /** 指令正文(markdown):告诉 Agent 具体怎么做,可引用已注册工具名。 */
        val body: String,
        val enabled: Boolean = true,
        val createdAt: Long = 0L,
        val source: String = "generated",  // generated / imported / manual
    )

    private const val KEY = "prompt_skills"
    private const val MAX_SKILLS = 40
    /** 注入 System Prompt 的技能段总字数上限,防止把上下文撑爆。 */
    private const val MAX_SECTION_CHARS = 6000
    private val GSON = Gson()

    private const val SEED_FLAG = "prompt_skills_seeded_v5"

    private const val DESIGN_SKILL_NAME = "产品设计工作流"
    private const val DESIGN_SKILL_DESC = "做/生成好看的 App、页面、小程序、海报页,或对成品有视觉质感要求时"

    private const val MOBILE_SKILL_NAME = "手机自动化编排"
    private const val MOBILE_SKILL_DESC = "打开某 app、点按钮、连续/批量操作,或抓取网页时的可靠执行方法"

    private const val STYLE_SKILL_NAME = "设计风格库"
    private const val STYLE_SKILL_DESC =
        "想要 vercel / apple / linear / stripe / notion / claude / supabase / airbnb 等大厂同款风格 / style 质感时"

    private const val SCRAPE_SKILL_NAME = "网页抓取"
    private const val SCRAPE_SKILL_DESC = "抓取/爬取网页数据、采集列表或详情、批量抠内容(scrape / crawl)时"

    /**
     * 内置「产品设计工作流」技能 —— 把生图/搜图/生视频/generate_app 编排成设计流水线,
     * 并提炼了母本 product-design / frontend-app-builder 的设计守则 + 验收清单,
     * 及 frontend-design 的可直接套用数值(字号/间距/动效/可访问性基线)。
     */
    private val DESIGN_SKILL_BODY = """
        用户要「做/生成好看的 App / 页面 / 小程序 / 海报页」,或对成品有视觉质感要求时,走这条流水线,
        别一步到位硬写 HTML(纯 CSS 简笔画质感差):

        【流水线】
        1) 定设计系统:先想清 版式 / 配色(主色+中性阶)/ 字体层级 / 组件风格 / 调性(极简·科技·国潮·杂志)。
           只做设计决策,不写码。有参照物(截图/网址/某产品风格)时照着匹配,别自作主张"改良"它的
           配色、首屏、卡片、图标、圆角。想要大厂同款(vercel/apple/linear/claude…)→ 见[设计风格库]挑一套,token 原样用。
        2) 备真实素材(能并行就并行,绝不用占位):
           · AI 插画/图标/首屏大图 → generate_image(写清 主体+风格+光影,首屏尺寸 1280x720)
           · 真实照片/品牌 logo → search_image(type=photo 图库照片;type=logo 取品牌 logo)
           · 明确要动态首屏 → generate_video 提交 + check_video 取回(1-3 分钟、耗积分,非必要不做)
        3) 生成:调 generate_app,图片 URL 用 assets 传入(格式 "hero=URL; icon1=URL"),
           description 带上第 1 步的设计系统。
        4) 验收+迭代:看推送到控制台的预览,按下面清单挑毛病,有问题就把要改的点补进 description 再走一遍。

        【质量守则(直接决定档次)】
        - 绝不用假素材:emoji / 纯色方块 / ASCII / CSS 画的图 / 手搓 SVG 都不能当图片。
          要图就 search_image / generate_image,量好位置尺寸再放,让图自然融入,不硬裁不拉伸。
        - 不虚造装饰:没要求别加 hero 小标签/胶囊/徽章/光晕/渐变蒙层;别把白底改米白;首屏图上别盖半透明色层。
        - 设计系统一致:同类元素用同一套组件/token;按钮/输入/标签的字号字重显式定义,别吃浏览器默认 16px。
        - 保留容器模型:该留白/通栏的地方别硬塞卡片和边框面板。
        - 真交互+真数据:筛选/标签页/表单/选中态/成功态要真能用,填真实感示例数据;别上死控件,别拿静态图当界面。
        - 图标忠实:箭头/折叠/翻页用 SVG 图标别用文字符号;描边/填充/粗细/隐喻与整体一致。

        【设计基线(拿不准就用这套数值)】
        - 字号阶梯:Display 48 / H1 36 / H2 24 / H3 20 / 正文 16 / 小字 14 / 注释 12(px),标题 600-700、正文 400。
        - 间距阶梯(8pt 节奏):4 / 8 / 16 / 24 / 32 / 48 / 64,别在节奏外乱跳。
        - 移动优先,触摸目标 ≥ 44px;断点 手机<640 / 平板 640-1024 / 桌面>1024。
        - 动效:微交互 150ms、常规 300ms;进场 ease-out、退场 ease-in;只动 transform/opacity,尊重 prefers-reduced-motion。
        - 可访问性:正文对比度 ≥ 4.5:1;可点元素要有 hover/active/disabled/loading 态与清晰焦点。

        【生成后验收清单(逐条过,别只看"能跑")】对着预览至少核 5 点:
        文案逐字对齐 · 字号字重行高一致 · 配色渐变忠实不软化 · 间距圆角贴合(该通栏不塞卡片) ·
        图标风格一致且图真加载出来 · 窄屏不溢出不坍塌。
        自问:一线设计公司会给这成品签字吗?有一眼可见的毛病就继续改,别带着可修的视觉问题收工。

        【硬停(出现即必须修)】内容裁切 / 窄屏溢出坍塌 / 字号跳变 / 图裂或缺失 / 假占位块 / 点了没反馈 /
        白底被改暖白 / 首屏图被盖色层 / 图标缺失或换通用符号 / 图片与背景割裂。
    """.trimIndent()

    /**
     * 内置「手机自动化编排」技能 —— 提炼母本 mobile-automate / mobile-browser 的方法论
     * (感知→定位→操作→验证),用 octopus-mobile 自己的工具名落地(YAML 不照搬)。
     */
    private val MOBILE_SKILL_BODY = """
        用户要手机自动化(打开某 app、点某按钮、连续操作、批量任务、抓网页)时,按
        "感知→定位→操作→验证"循环做,别盲操:

        1) 感知:先 take_screenshot / look_at_screen 看清当前界面和可交互元素,别凭记忆假设界面长啥样。
        2) 定位:要点哪个先用 find_node_info / tap_by_vision 定位目标控件(拿坐标或节点),别硬编坐标。
        3) 操作:一次一个原子动作 —— tap / swipe / input_text / system_key / open_app;
           复杂流程拆成多个"感知→操作→验证"小循环。
        4) 验证:操作后再截一次屏,确认界面真变了(出现预期结果)。没生效就重试一次或换定位方式;
           连续失败 / 网络超时 / 加载不出 / 找不到元素就停下报错,别机械重试十几次。

        网页自动化(抓取/填表/反爬)用浏览器工具:browser_navigate 打开 → browser_get_dom 读结构 →
        browser_click / browser_type 交互 → browser_screenshot 存证;遇 Cloudflare/验证码先截图看清再决策。

        要点:每步必验、单步原子、遇阻即停;涉及支付/发送/删除等不可逆操作前,先跟用户确认。
    """.trimIndent()

    /**
     * 内置「设计风格库」技能 —— 十来套最认得出的大厂设计系统压成紧凑 token。
     * apple / vercel 取自母本 awesome-design-md 的真实 DESIGN.md;其余按各家公开设计语言凝练。
     * 触发词带品牌名,用户提到某大厂或"XX 风格"时命中,配合[产品设计工作流]把 token 焊进 generate_app。
     */
    private val STYLE_SKILL_BODY = """
        想要某大厂"同款质感"时(用户提到 vercel/apple/linear/stripe/notion/claude 等,或要"XX 风格"),
        从下面挑一套,把它的 配色/字体/圆角 **原样**写进 generate_app 的 description,严格用这些 token,别近似替换。
        (apple、vercel 取自母本设计规范;其余按各家公开设计语言凝练。)

        ### Vercel — 开发者工具/SaaS
        极简黑白、高对比、精密沉浸。底 #000 / 卡 #1a1a1a / 主字 #fff / 次字 #888 / 强调蓝 #3b82f6(仅主操作)。
        字体 Geist(等宽 Geist Mono),标题 700-800、字距 -0.02em,标签大写+宽字距;圆角 6px,阴影极克制,大留白;白底黑字主按钮。

        ### Linear — 开发者工具
        超极简、精准。近黑底 #0d0e10 / 面 #16171a / 主字 #f7f8f8 / 次字 #8a8f98 / 紫强调 #5e6ad2。
        Inter 字体,细腻分割线,圆角 8px,微妙渐变,克制动效。

        ### Apple — 消费级
        磨砂玻璃、大留白、精致。浅底 #f5f5f7 / 白面 #fff / 主字 #1d1d1f / 次字 #6e6e73 / 蓝 #0071e3。
        SF Pro / -apple-system,大圆角 18-20px,柔和阴影,backdrop-blur 玻璃层,大标题细体。

        ### Stripe — 消费级/金融
        优雅、标志性紫渐变。白底 / 主字 #0a2540 / 次字 #425466 / 紫 #635bff / 渐变 #635bff→#00d4ff。
        Inter,圆角 8px,柔和大阴影,斜切渐变背景带。

        ### Claude — AI 产品
        温暖陶土、干净编辑感。米底 #f5f4ee / 面 #fff / 主字 #1f1e1d / 陶土强调 #d97757 / 次字 #6b6a67。
        衬线标题 + 无衬线正文,圆角 8-12px,极淡边框,阅读留白充足。

        ### Notion — 生产力
        呼吸感、图标克制、近黑白。白底 / 主字 #37352f / 次字 #9b9a97 / 蓝强调 #2383e2。
        系统字体,细线分隔,圆角 4-6px,几乎无阴影,大量留白。

        ### Supabase — 开发者工具
        深色 + 翠绿、代码优先。底 #1c1c1c / 面 #2a2a2a / 主字 #ededed / 绿强调 #3ecf8e。
        等宽友好,圆角 6px,霓虹绿点缀,深色代码块。

        ### Airbnb — 消费级
        温暖友好、大图。白底 / 主字 #222 / 次字 #717171 / 珊瑚红 #ff385c。
        无衬线圆润,大圆角 12-16px,大图卡片,柔和投影。

        ### Raycast — 开发者工具
        深色 Chrome + 鲜艳渐变。底 #1a1a1a / 主字 #fff / 红橙渐变 #ff6363→#ff9f43。
        圆角 10px,玻璃层,鲜亮点缀,紧凑高效。

        ### Framer — 设计/落地页
        大胆黑蓝、动效优先。底 #0a0a0a / 主字 #fff / 蓝 #0099ff。
        超大标题字重 800,圆角 10px,强动效(尊重 prefers-reduced-motion),高对比。

        ### Figma — 创意工具
        鲜艳多彩、playful 但专业。白底 / 主字 #1e1e1e / 多彩(蓝 #0d99ff·紫 #a259ff·绿 #0fa958·红 #f24e1e)。
        Inter,圆角 8px,彩色徽记,活泼不杂乱。

        ### Mistral — AI 产品
        法式极简、暖橙。浅底 / 主字 #1a1a1a / 橙 #ff7000 / 紫点缀。极简排版,大留白,圆角 8px,克制强调。
    """.trimIndent()

    /**
     * 内置「网页抓取」技能 —— 提炼 GitHub 主流爬虫 skill(web-scraper / BrowserAct / mobile-browser)
     * 的"先轻后重、每步验证"策略,用 octopus-mobile 自己的工具(run_code fetch + browser_*)落地。
     * 外部 Firecrawl/Crawl4AI 那类需 Node/Python/付费 API,端上跑不了,故只搬方法论。
     */
    private val SCRAPE_SKILL_BODY = """
        用户要抓取/爬取网页数据(采集列表、抠详情、批量取内容)时,按"先轻后重、每步验证"来,
        别一上来就开浏览器硬刚:

        1) 先轻后重:先用 run_code 里的 fetch 拉静态 HTML;能直接拿到目标数据就别开浏览器——快、省、稳。
        2) 拿不到再升级:内容靠 JS 渲染(fetch 回来是空壳/骨架)→ browser_navigate 打开、
           browser_get_dom 取渲染后的结构化数据,必要时 browser_evaluate 跑一小段 JS 抠数据。
        3) 稳选择器:定位优先用 id/class/语义标签等结构选择器,别靠"第几个/某段文本位置"这种一改版就废的方式。
        4) 反爬别硬刚:遇 Cloudflare/验证码/登录墙 → 先 browser_screenshot 看清拦的是什么;
           能等就 wait 后重试一次,过不去就停下告诉用户(可能要人工过验证/登录),别机械重试。
        5) 分页/批量:一页抓完先验数据对不对再翻下一页;盯住"到底了/重复了"及时停;单条失败/超时即跳过,别卡死。
        6) 悠着点:合理间隔别高频轰炸站点;只取用户要的字段,拿到就 finish。

        产出:把抓到的数据整理成结构化结果(列表/表格/JSON 文本)回给用户,别丢一堆原始 HTML。
    """.trimIndent()

    fun all(): List<PromptSkill> {
        val json = KVUtils.getString(KEY, "")
        if (json.isEmpty()) return emptyList()
        return runCatching {
            GSON.fromJson<List<PromptSkill>>(json, object : TypeToken<List<PromptSkill>>() {}.type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun save(list: List<PromptSkill>) {
        KVUtils.putString(KEY, GSON.toJson(list.takeLast(MAX_SKILLS)))
    }

    /** 添加/覆盖(同名则替换,视为「更新技能」)。返回最终 id。 */
    fun add(skill: PromptSkill): String {
        val list = all().toMutableList()
        val idx = list.indexOfFirst { it.name == skill.name }
        if (idx >= 0) list[idx] = skill.copy(id = list[idx].id) else list.add(skill)
        save(list)
        return if (idx >= 0) list[idx].id else skill.id
    }

    fun delete(id: String) = save(all().filterNot { it.id == id })

    /**
     * 种下/更新内置技能(按 [SEED_FLAG] 版本号只跑一次;由 App 启动 [com.apk.claw.android.ClawApplication]
     * 调用,不放在 [buildPromptSection] 这类读路径里以免给它带副作用)。
     * 升级正文时 bump SEED_FLAG 版本即可让老装机重新对齐 —— 但只覆盖仍是内置(source=builtin)的技能,
     * 用户自己改过同名技能(source≠builtin)或已删除的一律不动。
     */
    fun ensureSeeded() {
        if (KVUtils.getString(SEED_FLAG, "").isNotEmpty()) return
        KVUtils.putString(SEED_FLAG, "1")
        seedBuiltin("builtin_design_workflow", DESIGN_SKILL_NAME, DESIGN_SKILL_DESC, DESIGN_SKILL_BODY)
        seedBuiltin("builtin_mobile_automation", MOBILE_SKILL_NAME, MOBILE_SKILL_DESC, MOBILE_SKILL_BODY)
        seedBuiltin("builtin_design_styles", STYLE_SKILL_NAME, STYLE_SKILL_DESC, STYLE_SKILL_BODY)
        seedBuiltin("builtin_web_scrape", SCRAPE_SKILL_NAME, SCRAPE_SKILL_DESC, SCRAPE_SKILL_BODY)
    }

    /** 不存在→种;仍是内置→更新到最新(保留用户的启用/停用);用户改过(source≠builtin)→不动。 */
    private fun seedBuiltin(id: String, name: String, desc: String, body: String) {
        val existing = all().firstOrNull { it.name == name }
        if (existing != null && existing.source != "builtin") return
        add(
            PromptSkill(
                id = existing?.id ?: id,
                name = name,
                description = desc,
                body = body,
                enabled = existing?.enabled ?: true,
                source = "builtin",
            ),
        )
    }

    fun setEnabled(id: String, enabled: Boolean) {
        save(all().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    /**
     * 注入 System Prompt 的技能段。
     * [prompt] 非空时只注入**与当前任务相关**的技能(按 name/description 关键词命中,省 token、
     * 触发更准);为空(或没有任何命中)时——[prompt] 为 null 退回注入全部 enabled;[prompt] 非空
     * 但零命中则不注入(避免无关技能污染上下文)。
     */
    fun buildPromptSection(prompt: String? = null): String {
        val on = all().filter { it.enabled }
        if (on.isEmpty()) return ""
        val selected = when {
            prompt.isNullOrBlank() -> on                 // 无 prompt(如恢复任务)→ 全量 enabled
            else -> on.filter { relevant(prompt, it) }   // 有 prompt → 只留命中项(可能为空)
        }
        if (selected.isEmpty()) return ""
        val sb = StringBuilder("\n\n## 已启用技能（相关时遵循其步骤，用你已有的工具执行）\n")
        for (s in selected) {
            val block = "\n### ${s.name}\n适用：${s.description}\n${s.body}\n"
            if (sb.length + block.length > MAX_SECTION_CHARS + 200) break
            sb.append(block)
        }
        return sb.toString().take(MAX_SECTION_CHARS + 400)
    }

    /** 轻量相关性:prompt 含技能名,或与「名+描述」的关键词有交集(ASCII 词 / 中文 2-gram)。 */
    private fun relevant(prompt: String, skill: PromptSkill): Boolean {
        val p = prompt.lowercase()
        if (skill.name.isNotBlank() && p.contains(skill.name.lowercase())) return true
        return keywords("${skill.name} ${skill.description}").any { p.contains(it) }
    }

    // 停用词:太泛的英文词 / 中文 2-gram,做关键词会造成大量误命中,剔掉。
    private val EN_STOP = setOf(
        "when", "that", "this", "with", "from", "your", "user", "please", "make", "want",
        "need", "will", "what", "about", "some", "into", "then", "than", "they", "have",
        "been", "asks", "create", "does", "would", "should", "when", "then",
    )
    private val CN_STOP = setOf(
        "用户", "一个", "一笔", "怎么", "什么", "时候", "的时", "当用", "户要", "要用",
        "可以", "帮我", "我要", "我想", "如果", "这个", "那个", "一下", "进行", "需要",
    )

    private fun keywords(s: String): Set<String> {
        val out = mutableSetOf<String>()
        Regex("[a-z][a-z0-9]{3,}").findAll(s.lowercase()).forEach { if (it.value !in EN_STOP) out.add(it.value) }
        Regex("[\\u4e00-\\u9fa5]{2,}").findAll(s).forEach { seg ->
            val t = seg.value
            for (i in 0..t.length - 2) {
                val g = t.substring(i, i + 2)  // 中文 2-gram
                if (g !in CN_STOP) out.add(g)
            }
        }
        return out
    }
}
