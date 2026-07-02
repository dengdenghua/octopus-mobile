# 设备端编程能力改进记录

> 本文档记录围绕「让 Agent 在设备上写代码/生成 App 更靠谱」的一轮改进:从一份编程能力自评出发,
> 逐条落地为**反馈闭环 → 视觉校验 → 上下文理解 → 结构化错误 → 异步执行**五项。
> 与 [AUDIT_FIXES.md](AUDIT_FIXES.md)(WIP 审计修复)、[AUDIT_REPORT.md](AUDIT_REPORT.md)(整体安全审计)互为补充。

## 1. 结论速览

- 自评点出的**四项短板 + 一项延伸**已全部落地并推 main,均带单测,构建 + `:app:testDebugUnitTest` 全绿。
- 核心思路:把「生成即完事」升级成「生成 → 运行 → 观察 → 修复」的闭环,并给 Agent **机器可读**的反馈信号(错误分类、视觉判定、真总结的历史)。
- 全部改动**向后兼容**:新通道未配置/调用失败一律退回旧行为,绝不阻断主循环。

## 2. 已落地(均在 origin/main)

| 提交 | 改进 | 类别 |
|---|---|---|
| `96f2db0` `feat(agent)` | generate_app 反馈闭环:离屏 WebView 抓 JS 控制台 ERROR → 自动修复循环(采纳更少报错的版本) | P0 |
| `a138e1d` `feat(agent)` | generate_app 视觉验证:截图 → VLM(GoalVerifier)判「实现了没」→ 一轮定向视觉修复 | P0 |
| `f28c61d` `feat(agent)` | ContextCompressor 加 LLM 真总结通道:更早历史不再一律硬截断,而是压成要点保住「试过 X 因 Y 失败」 | P3 |
| `d00a153` `feat(tool)`  | ToolResult 加机器可读错误分类(errorCode + 出错行号) | 反馈质量 |
| `3279adf` `feat(tool)`  | run_code 支持 Promise/setTimeout(给 Rhino 补单线程事件循环) | 执行能力 |

## 3. 逐项说明

### 3.1 写-跑-看-改反馈闭环(`96f2db0`)
- 新增 [HtmlLinter.kt](app/src/main/java/com/apk/claw/android/tool/impl/HtmlLinter.kt):离屏 headless WebView 加载生成的 HTML,`WebChromeClient.onConsoleMessage` 收 **ERROR** 级日志(过滤 `net::`/资源加载失败等噪声)。best-effort,创建/超时/失败一律降级不阻断。
- [GenerateAppTool.kt](app/src/main/java/com/apk/claw/android/tool/impl/GenerateAppTool.kt):plan → code → **console-error 修复循环**(`MAX_REPAIR=2`,只采纳报错更少的版本)。
- 意义:以前生成的 App 带 JS 错误也照样存下、用户点开才发现;现在生成期就自查自修。

### 3.2 视觉验证闭环(`a138e1d`)
- HtmlLinter 增 `capture=true`:手动 measure/layout/draw 抓离屏首屏截图(固定竖屏视口)。
- GenerateAppTool 在 console 修复后,若 `VisionAnalyzer.isConfigured()`:截图 → 复用 [GoalVerifier.kt](app/src/main/java/com/apk/claw/android/octopus_mobile/GoalVerifier.kt) 做 VLM「界面是否实现了用户需求」判定 → 未达成则一轮**定向视觉修复**(仅当 console 报错不增才采纳)。
- 意义:补上「代码不报错 ≠ 长得对」这一层——让模型自己看一眼再改。

### 3.3 上下文 LLM 真总结(`f28c61d`)
- [ContextCompressor.kt](app/src/main/java/com/apk/claw/android/octopus_mobile/memory/ContextCompressor.kt):加 `summarizer` 构造参数。更早的历史优先走一次极小 LLM 调用压成要点,prompt 明确要求保留**决定 / 失败原因 / 当前状态 / 关键数据**——正是硬截断最易丢、长编程任务最需要的。
- 新增 [ContextSummarizer.kt](app/src/main/java/com/apk/claw/android/agent/ContextSummarizer.kt):走 `LlmRouting.effective()` 的无状态一次调用(低温 / 限 token / 30s 超时),失败/未配置返回 null。
- 退回策略:未注入 summarizer / 调用失败 / 无 API → 退回旧的 500 字硬截断,`lastMethod` 如实上报走了哪条路。

### 3.4 结构化错误(`d00a153`)
- [ToolResult.kt](app/src/main/java/com/apk/claw/android/tool/ToolResult.kt):加可选 `errorCode`(`ToolErr` 8 类:`INVALID_PARAM / NOT_FOUND / PERMISSION / TIMEOUT / BLOCKED / SCRIPT_ERROR / UPSTREAM / INTERNAL`)+ `errorLine`(脚本出错行号)。
- 分类落点:
  - [ScriptSandbox.kt](app/src/main/java/com/apk/claw/android/tool/impl/ScriptSandbox.kt):JS 错误 → `SCRIPT_ERROR` + **具体第几行**;超时 → `TIMEOUT`。
  - [ToolRegistry.kt](app/src/main/java/com/apk/claw/android/tool/ToolRegistry.kt):缺参 → `INVALID_PARAM`、未知工具 → `NOT_FOUND`、熔断/停用/安全/护栏拦截 → `BLOCKED`、审批被拒 → `PERMISSION`。
- 喂给 LLM 的观测里 `errorCode/errorLine` **有值才带**(省 token);旧的纯文本 `error` 照旧。意义:自动修复循环靠稳定的判据决定「改参数还是先探测」,不必正则解析人类文本。

### 3.5 run_code 异步支持(`3279adf`)
- Rhino 1.7.15 有原生 Promise 但**没有事件循环** → `setTimeout` 未定义、带延迟的 Promise 链推不动。
- ScriptSandbox 补单线程事件循环:注入 `setTimeout/setInterval/clearTimeout/clearInterval/queueMicrotask`;主脚本跑完 → `processMicrotasks()` → 按到期时间跑定时器,每个回调后再清微任务。全程受同一 `timeout_ms` 约束,超时即停。
- **诚实的边界**:`async/await` 语法 Rhino 1.7.15 的解析器不认(引擎限制,非垫片可解)。工具描述已注明「改用 Promise + .then」,并说明 `fetch/readFile/callTool` 本就同步、无需 await——避免 LLM 生成会解析报错的 `async` 代码。

## 4. 测试

- [ScriptSandboxTest.kt](app/src/test/java/com/apk/claw/android/tool/ScriptSandboxTest.kt) 新增 6 例覆盖异步:promise `.then`/链序、setTimeout 时序、clearTimeout 取消、setInterval 重复后 clear、timeout 内嵌套 promise 清空。
- 事件循环上线前先用纯 Rhino 探针验证:确认 `Context.processMicrotasks/enqueueMicrotask` 存在、async/await 确实解析不了、微任务顺序正确(同步 → 微任务 → 定时器)。
- 每次改动均跑 `:app:assembleDebug` + `:app:testDebugUnitTest`,全绿。

## 5. 已知边界 / 后续可做

- **async/await 语法**:如需真正支持,只能换引擎(QuickJS + native,见 memory「设备上编程」条),体积/集成成本高,暂不做。
- **视觉/console 修复轮数**:目前各 1–2 轮,足够多数场景;更难的 App 可让轮数随失败信号自适应。
- **总结通道命中率**:仅在历史超阈值触发压缩时才走;可按任务类型(长编程 vs 短问答)调整触发点。

## 6. 生成生态:一句话生成 小程序 / 网页脚本 / 工具 / 技能

> 从「有没有生成 skill / 插件 / 小程序」这个问题出发,把「一句话造能力」补全。这条线的价值不在
> 追全栈 codegen(红海),而在**端侧 + 会干活的产物 + Agent 给自己扩能力**——云端无设备的
> Base44 类产品结构上做不到。全部改动均在 origin/main,带单测,`:app:testDebugUnitTest` 全绿。

### 6.1 已落地(均在 origin/main)

| 提交 | 改进 | 类别 |
|---|---|---|
| `f477217` `feat(codegen)` | generate_app 生成物可**自声明低危设备能力**(agentic mini-app):OCTOPUS_TOOLS 声明 ∩ 白名单 → manifest.allow_tools | 会干活 |
| `b2fc06c` `feat(skill)` | 端侧**提示词技能 v1**:PromptSkillStore(markdown 技能库)+ generate_skill + 注入 System Prompt(补上 octopus 缺的 loader) | 技能 |
| `a07c045` `feat(skill)` | 技能 **v1.1**:按任务相关性注入(关键词命中,省 token)+ 接进主对话链路(ChatAgentBridge) | 技能 |
| `9065894` `feat(skill)` | **导入 Claude skill**(import_skill:解析 SKILL.md + 适配提示)+ 技能页管理(看/开关/删) | 技能 |
| `054c453` `feat(skill)` | generate_skill **自评自优化一轮**(拿真实工具清单 + 规则让 LLM 复核改一版) | 技能 |
| `9bb827a` `feat(codegen)` | **generate_plugin**:一句话生成浏览器脚本插件(注入网页的 content script) | 插件 |
| `b6b4418` `feat(codegen)` | **generate_tool**:一句话把 HTTP API 变成 Agent 可调用的声明式工具(type=tool) | 工具 |

### 6.2 四类插件的生成矩阵

| 插件类型 | 能否一句话生成 | 工具 | 产物「会干活」在哪 |
|---|---|---|---|
| mini-app(小程序) | ✅ | `generate_app` | 可调白名单设备能力(generate_image/list_apps/app_action…) |
| browser-script(网页脚本) | ✅ | `generate_plugin` | 注入匹配网站,页面加载后自动运行 |
| tool(声明式 HTTP) | ✅ | `generate_tool` | 落 manifest → PluginManager 注册进 ToolRegistry,Agent 直接可调 |
| dex(原生代码) | ✖ | —— | 原生字节码,不适合 LLM 生成 |

### 6.3 提示词技能 = 把 Claude skill 模型移植到端侧

- **关键认知**:SKILL.md 本质是「给 LLM 的指令包」,LLM 无关 → 内容可移植;搬不动的只有 Claude Code 的
  **harness plumbing**(Skill 工具、`.claude/skills` 加载器、指向 CLI 自身工具/脚本的引用)。
- 所以 octopus 需要的是**自己的 loader**:[PromptSkillStore] 存 + `AppViewModel`/`ChatAgentBridge` 把命中技能
  注入 `dynamicPromptSuffix`。有了它,简单 Claude skill 改改工具名就能 `import_skill` 直接用。
- skill-creator 本身**最不能照搬**(它 spawn Claude 子 Agent、跑 python eval、打包 .skill,全依赖 CLI harness);
  能借的只是它的**方法论**(捕获意图→草稿→自评优化),已折进 generate_skill 的自评轮。

### 6.4 安全姿态(生成物各过各的闸门)

- **generate_app / generate_skill / import_skill** → MEDIUM(纳入审计)。技能只影响 Agent 自身推理,不外泄。
- **generate_plugin / generate_tool** → **HIGH**(不可信来源走来源闸门/审批/BLOCK)。前者注入真实网页能读页面数据、
  后者对外发 HTTP 可能带用户数据——防远端静默植入「偷数据脚本」或「把数据 POST 到攻击者域名的工具」。
- mini-app 自声明的 allow_tools 只授白名单低危项;运行时 `OctopusBridge.callTool` 仍过不可信来源闸门,高危照拦。
- 新增 4 个工具全部在 [ToolRiskPolicy] 归类(HIGH/MEDIUM),`ToolRiskPolicyCoverageTest` 跑活注册表通过——
  堵住「新工具漏分类 → 静默默认 LOW → 绕过审计/闸门」的漂移。

### 6.5 已知边界 / 后续可做

- **真·eval(v3,未做)**:现在 generate_skill 的「自评」是**静态复核**(按工具清单 + 规则),不是「跑测试
  prompt、让 Agent 实际执行一遍打分」。真跑一遍要执行真实设备动作、有副作用和成本,是更大的独立立项——
  可用端侧 run_code(JS)搭一个自测闭环(呼应端侧执行强项)。
- **import 是逐字导入 + 适配提示**,没做「LLM 自动改写工具名」;需要更贴合时可加一轮 LLM 改写。
- **相关性注入是关键词级**(ASCII 词 / 中文 2-gram + 停用词过滤);技能多到一定量后可升级为向量/语义匹配。
- **整机验收**:构建/安装/冷启动/工具注册/风险分类/技能页渲染均已在真机(模拟器)核对;每个 `generate_*`
  背后的**实际 LLM 生成**需配好模型 + 在对话里驱动,会扣积分,未纳入自动化验收。
