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
