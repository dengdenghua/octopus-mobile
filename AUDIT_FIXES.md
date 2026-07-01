# Codex WIP 审计与修复记录

> 本文档记录对 Codex 留下的大型未提交 WIP(~55 文件,横跨 agent / 安全 / 浏览器 / 媒体 / 连接 / 工具)所做的
> **审计 → 分组入库 → 缺陷修复** 全过程。审计采用 6 个并行 agent 分维度复核,交叉核对了相互矛盾的结论。
> 与 [AUDIT_REPORT.md](AUDIT_REPORT.md)(项目整体安全审计)、[PROJECT_ANALYSIS.md](PROJECT_ANALYSIS.md)(项目分析)互为补充。

## 1. 结论速览

- WIP 大部分是**高质量改进**(MCP server 已鉴权+走闸门、渠道 ACL 默认拒绝且接线、大量节点泄漏修复、ApprovalFlow 加固)。
- 审计共发现 **10 余条问题**;其中"看着最吓人"的多为 **pre-existing**(非 Codex 引入)。
- **代码层可修项已全部修复并推送 main**;测试 573 → **590**(新增 17 个守护用例)。
- 余两项为**产品/运营决策**,非代码缺陷,见 §4。

## 2. 已修复(均在 origin/main)

| 提交 | 问题 | 严重度 |
|---|---|---|
| `98b1ccd` `fix(agent)` | 崩溃恢复重放"悬空 tool_call"致恢复任务即崩;`resumeTask` 用 get/set 非 CAS 致并发重复执行 | HIGH×2 |
| `bb2dbcf` `fix(safety)` | 审计日志记录工具结果时不脱敏(验证码/token 明文入库并在 UI 原样显示);`AuditLogActivity` 从不显示 `tampered` 篡改标志 | MED×2 |
| `c56c90d` `fix(connection)` | 母体不回 `heartbeat/ack` 时每设备每 ~90s 强制重连风暴(改默认关开关);无界重连退避移位溢出(指数封顶) | MED+LOW |
| `cd950d3` `fix(browser)` | ~~GeckoView 扩展 drive-by~~ ⚠️ GeckoView 已整体移除,此修复不再适用(浏览器改用系统 WebView) | ~~HIGH~~ → N/A |
| `9a1268a` `fix(browser)` | **evaluateJs 实际完全失效**(拼出非法 JS 致恒超时);共享状态并发串线;alert 桥可被页面伪造结果 | HIGH+MED×2 |
| `5693c7e` `fix(proactive)` | 短信验证码自动复制规则坏:工具名 `set_clipboard` 错(应 `clipboard`)+ 无验证码提取逻辑 | HIGH(功能) |
| `a8fbd68` `fix(media)` | H264 SPS 解析分辨率无范围校验,异常时可能用负/畸大值 configure | LOW |

> 注:`fix(browser) evaluateJs` 的"非法 JS 致 eval 恒失效"是修复期间新发现的、比原审计更严重的缺陷。

## 3. 审计确认扎实、无需改动的部分

- **内置 MCP server(`/mcp`)**:`serve()` 先做常量时间 Bearer 校验再分发;`tools/call` 经 `withUntrustedSource{}` 走安全闸门 —— 无未鉴权/绕闸门的工具执行。
- **渠道 ACL(`ChannelAclActivity`/`ChannelAccessControl`)**:默认拒绝、null 发送者 fail-closed、已接线于 `ChannelSetup`。
- **`SecretRedactor`**:已接入 `XLog`(logcat 脱敏);本次又接入审计日志路径并扩展 OTP/手机号/邮箱。
- 大量 `AccessibilityNodeInfo` 泄漏修复、`BrowserEngine.destroy()` 生命周期、`ApprovalFlow` 弹窗泄漏+双结算修复、WaitTool Java→Kotlin 迁移 —— 均正确。

## 4. 待决策项(非代码缺陷,需产品/运营拍板)

### 4.1 短信验证码自动复制 —— ✅ 已实现(opt-in,经产品决策后)
规则逻辑已修正确(`5693c7e`);本次进一步实现端到端:
- 新增 `SmsReceiver`(`octopus_mobile/proactive/`),manifest 注册并以 `android:permission="android.permission.BROADCAST_SMS"` 限定仅系统可投递,解析短信后调用 `onSmsReceived`。
- 新增 `RECEIVE_SMS` 权限(运行时授予)。
- TrustCenter 加「验证码短信自动复制」开关:引导授予 `RECEIVE_SMS` + 启用主动引擎。
- **三重前提默认关闭**:权限未授予 / 引擎未启用 / 规则未启用,任一不满足都不触发。
- 隐私提示已在开关处明示:验证码会进入系统剪贴板,可能被其他应用读取。

### 4.2 `isRemoteHighRiskAllowed` —— 远程高危工具免确认
默认 `false`、TrustCenter 有明确警告的 opt-in。开启后远程/LAN 不可信源可**无人工确认**执行高危工具(短信/文件/装应用等),直接扩大信任边界。
**为何保留**:这是"闲置/专用自动化设备满血"的有意设计;是否启用、在何种部署形态启用,属运营层认可,代码无需改动。

### 4.3 仍存在的更大隐患(已知,需另立项)
- **审计日志 HMAC 防篡改有限**:密钥与日志同存 KVUtils、按行签名无链式 —— 无法防本地攻击者重算签名,也无法检测删行。如需更强保证需重新设计(如外部不可变存储 / 链式签名)。

## 5. 验证方式

每次修复均经:`./gradlew :app:compileDebugKotlin`(JAVA_HOME 指向 Android Studio JBR)编译绿 + `:app:testDebugUnitTest` 全量通过;关键修复补了守护单测(`TaskCheckpointTest` / `SecretRedactorTest` / `ProactiveRuleEngineTest`)。

---

## 6. 2026-07-01 深度审计批次(多 agent workflow;commit `74d5c3a` + `4e17f64`)

41-agent 深度审计(插件桥/沙箱/浏览器/服务端/母体/安全网关/并发/SMS/工具/近期改动 12 维,每条 finding 经对抗性验证)确认 **19 项**,验证中另发现 **1 项 CRITICAL**。全部修复 + 回归测试;`:app:testDebugUnitTest` 全绿,`server` 112/116(4 失败为本地未配 agnes provider 的既有环境依赖)。

### 6.1 HIGH
- **小程序 JS 桥来源闸门缺失** `plugin/OctopusBridge.kt`:`callTool`/`deviceAutomate` 是唯一未包 `withUntrustedSource` 的不可信入口,registry mini-app 声明 `allow_tools:["*"]` 即零审批调 send_sms/run_code/file_ops。两入口均包裹 → 高危工具重新走来源闸门。
- **Rhino 沙箱 `fetch()` SSRF** `tool/impl/ScriptSandbox.kt`:新增 `octopus_mobile/safety/SsrfSafeHttp.kt`(逐跳 `UrlGuard` + 禁自动重定向 + 手动跟随 + 可选逐跳白名单)。ScriptSandbox 与 DeclarativePluginTool 均改走它;并加 `callTimeout` 硬上限。
- **声明式插件重定向 SSRF** `plugin/DeclarativePluginTool.kt`:`allowHost` 只校首跳 → 改逐跳重跑 allowHost + 禁跟随重定向。
- **config-sync 投毒 `KEY_SCRIPT_WORKSPACE`** `octopus_mobile/DualConfigWriter.kt`:母体可把沙箱白名单改 `/`。加入 `SYNC_BLOCKED_EXACT` + 子串 `WORKSPACE`/`SANDBOX`;`ScriptSandbox.isSafePath` 加危险根拦截 + 分隔符边界。
- **`UrlGuard` IPv4-mapped IPv6 绕过** `octopus_mobile/safety/UrlGuard.kt`:`[::ffff:169.254.169.254]` 等绕过私网判定 → 改 `InetAddress` 规范化 + 内建 isLoopback/LinkLocal/SiteLocal/AnyLocal 分类 + 拆内嵌 IPv4。
- **服务端 `n`/`best_of` 成本放大** `server/app.py`:透传致上游 n× 计费而用户扣费封顶单份 → 钳 `n∈[1,MAX_COMPLETIONS]` 并计入预扣、丢弃 `best_of`。

### 6.2 CRITICAL(审计外新发现)
- **`server/app.py` 整个模块 import 即 `NameError`**:上一批新增的 `/admin/api/plugins*`、`/admin/api/profit/timeseries` 路由 `Depends(admin_guard)` 早于 `admin_guard` 定义 → **服务端根本起不来**。已将 `admin_guard` 定义上移至首个 admin 路由之前。(静态审计漏检,`pytest` 收集期暴露。)

### 6.3 MEDIUM / LOW / INFO
- `server/routes/DebugRouteHandler.kt` 路径穿越:`absolutePath`→`canonicalPath` + 分隔符边界。
- `safety/PrivacyScanner.kt` 漏检新式 OpenAI key:单列 `sk-proj-`/`sk-svcacct-`/`sk-admin-` 模式(不放宽通用 `sk-` 以免误吞 `sk-ant-`)。
- `octopus_mobile/ScreenStreamer.kt`:屏幕树(含 OTP/短信/手机号)明文外发 → 过 `SecretRedactor` 脱敏。
- `server/RemoteConsoleGateway.kt`:上报 `RemoteControlIndicator` + `input_text` 走 `withUntrustedSource`。
- `server/routes/ScreenHandler.kt` MJPEG:客户端停读致 `write` 永久阻塞占死信号量 → 加卡死看门狗 + 大缓冲。
- `tool/ToolRegistry.kt`:`registerPluginTool` 拒绝覆盖同名内置工具。
- `plugin/OctopusBridge.kt`:`pay()` 无超时 latch → 加超时 + Activity 销毁保护。
- `safety/PathGuard.kt`:接通此前死代码的 Android 敏感路径拦截。
- `server/app.py`:profit timeseries `orders.ts`→`created_at`(否则恒 500)。

### 6.4 R3 追加加固(此前 deferred;commit `4e17f64`)
- **母体 WS 握手前门控** `octopus_mobile/OctopusMobileClient.kt`:握手确认(`ONLINE`)前即派发 `tool/execute` / 应用 `config/sync_pull_response` → 未鉴权服务端或握手前 MITM 可驱动工具。两者加 `state==ONLINE` 门 + 回归测试。

### 6.5 新增回归测试
- `UrlGuardTest`:IPv4-mapped IPv6(loopback/metadata/private)+ `[::1]` 全阻断。
- `PrivacyScannerTest`:`sk-proj-`/`sk-svcacct-` 命中。
- `OctopusMobileClientHandshakeTest`:握手前 `tool/execute` 不被派发。

### 6.6 仍 deferred(需产品/协议/重设计)
母体 WS 强制 wss + 服务端身份校验、ConfigServer loopback 绑定(会破坏 LAN 控制台)、KVUtils 静态加密、审计日志 HMAC 链式防删、群聊 ACL 按人非按会话。
