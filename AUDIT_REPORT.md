# Octopus Mobile 安全与代码质量审计报告

> ⚠️ **部分发现已过时** — GeckoView 已整体移除(-180MB),浏览器改用系统 WebView。
> 以下涉及 GeckoViewEngine 的发现(R9/B2/N8 等)已不适用,仅保留作历史参考。
> P0-6(BrowserNavigateTool SSRF)已修复(接入 UrlGuard)。
> P1-1(LLM 客户端重复)已修复(抽出 BaseLangChain4jLlmClient)。
> P1-2(短信验证码)已修复(改为 NOTIFY_USER)。
> P1-3(Shizuku 自伤命令)已修复(xargs + 拆分命令)。

> 范围：`com.apk.claw.android` Android 端 + `server/app.py` Python 中继服务器
> 方法：12 维度并行审计（166 个智能体）→ 双盲对抗式复核（每条结论经 2 名独立质疑者验证）→ 查漏补审
> 复核结果：76 条发现 → 70 条通过验证（64 confirmed / 6 contested），6 条被驳回
> 状态标注：**已确认 (confirmed)** / **待确认 (contested)** / **⚠️ 已废弃(GeckoView 已移除)**

---

## 1. 执行摘要 (Executive Summary)

整体安全态势：**高危**。本项目的核心定位是"让远程 LLM 代理控制一部 Android 手机"，但其**信任边界与权限闸门几乎全面失守**。多条可达路径（聊天渠道消息、LAN HTTP 服务器、母体 WebSocket、浏览器内嵌引擎、被注入的代理内容）都能在**无任何人工确认、无发送者鉴权、无能力裁剪**的情况下，触达手机最高权限工具集（短信、任意 Intent、文件读写删、无障碍点击、Shizuku shell UID 命令执行、浏览器任意 JS 注入）。

**单一最重要的系统性风险**：安全护栏层（`safety/*`）本质上是"安全剧场"——`PathGuard`、`UrlGuard`、`CircuitBreaker` 是完全无人调用的死代码；`ToolCallGuardrail` 的 `DANGEROUS` 分类、`ToolRiskPolicy` 的 `HIGH_RISK` 标签只用于审计日志，从不阻断；`SafetyGate` 默认 `judge=null`（恒放行）且 LLM 裁判"失败即放行"。**全链路唯一真正会拒绝的，只是一个易绕过的密钥正则匹配。** 因此，任何能与代理对话的一方（DM 机器人的陌生人、机器人所在群的任意成员、持有 LAN token 的同网段主机、母体服务器、或被网页/聊天内容注入的代理本身）即等同于完全控制设备。

叠加放大该风险的有：聊天渠道**零发送者白名单**（critical）、控制服务器 token 以**明文 UDP 广播**到全网段、母体 WebSocket **明文 ws 且无服务端身份校验**、Shizuku shell **命令注入** + 转义助手是死代码、relay 服务器 **JWT_SECRET 硬编码默认值**导致全账号伪造。此外调度器存在两处会导致并发任务级联失败的可靠性缺陷。

### 严重度统计

| 严重度 | 数量 |
|--------|------|
| Critical | 1 |
| High | 18 |
| Medium | 14 |
| Low | 18 |
| Info / 待确认 | 5 (其中 4 contested) |
| **合计** | **56** |

> 注：severity 为复核后调整值。下表"关键风险"覆盖 Critical + High；contested 项单列并标注"待确认"。

---

## 2. 关键风险 (Top Risks)

### R1 — 聊天渠道零发送者鉴权：任何能给机器人发消息的人即可完全控制手机 ⭐
**严重度：Critical（confirmed）**
**受影响文件**：`channel/ChannelSetup.kt:30-41`（+ 各 handler：`WeChatChannelHandler.kt:237`、`QBotWebSocketManager` `:448/487`、`DiscordGatewayClient.kt:251-269`、`TelegramChannelHandler.kt:101`、`FeiShuChannelHandler.kt:62`、`DingTalkChannelHandler.kt:71`）
**攻击场景/前置条件**：受害者配置并连接了任一渠道、授予了无障碍服务。`onMessageReceived` 仅检查无障碍是否运行，随后直接 `taskOrchestrator.startNewTask(channel, message, messageID)`。全渠道包 + KVUtils 内 **不存在任何 allowlist/owner/admin 概念**。攻击者只需能 DM 机器人（QQ/Telegram/Discord/微信），或成为机器人所在任意群的成员（QQ/Discord/Feishu/DingTalk 群）。
**影响**：远程、相对设备无需认证。攻击者可指挥代理读短信/2FA 验证码、发短信、外传文件、打开任意 App、发任意 Intent、执行任意屏幕操作——全部无闸门。
**修复建议**：在 `ChannelSetup.onMessageReceived`（或各 handler dispatch 前）强制发送者白名单，按渠道存储授权身份（微信 fromUserId、QQ openid、Discord author id、Telegram from.id、Feishu open_id、DingTalk senderStaffId）于 KVUtils；空列表默认拒绝，或用一次性配对码绑定首个用户。各 handler 已提取发送者 id 但当前丢弃，应将真实身份透传至 `dispatchMessage` 并校验。

### R2 — 安全护栏对高危工具从不阻断（DANGEROUS/HIGH_RISK 纯标签 + PathGuard/UrlGuard/CircuitBreaker 死代码）
**严重度：High（多条 confirmed 合并，根因相同）**
**受影响文件**：`safety/ToolCallGuardrail.kt:44-55,83-159`、`safety/PathGuard.kt:26-192`（零调用）、`safety/UrlGuard.kt:26-189`（零调用）、`safety/SafetyGate.kt:23-25,68-94,167-179` + `AppViewModel.kt:190`、`safety/CircuitBreaker.kt:35-208`（零调用）、`tool/ToolRegistry.kt:218-264`、`tool/impl/FileOpsTool.kt:47-101`、`tool/impl/browser/BrowserTools.kt:44-48`
**攻击场景/前置条件**：任何可达代理的一方（R1 的渠道、LAN `/api/agent/run` + token、母体 WS `tool/execute`、被注入的代理内容）。`precheck` 仅在**同一工具已重复失败**达阈值时阻断；首次/成功的危险调用一律放行。`FileOpsTool` 仅用字面前缀 `startsWith("/data/data/")` 检查、无 `..` 规范化，原应拦截的 `PathGuard` 从不运行；`NavigateTool`/`InstallExtensionTool` 同理绕过 `UrlGuard`。
**影响**：无任何执行前风险闸门。一次提示注入或一条恶意 LAN 请求即可触发 `send_sms`、`send_intent`（任意 Intent）、`install_app`、`browser_evaluate`（任意 JS）、`file_ops`（路径遍历读 `/proc`、`/system`、他 App 数据）等。
**修复建议**：在 `ToolRegistry.executeTool` 增加真正的强制分支：当 `classifyTool==DANGEROUS` 或 `ToolRiskPolicy.riskOf==HIGH` 时返回 HUMAN_GATE/BLOCK（除非存在显式逐次授权）。将 `PathGuard.check`（sandbox=`/sdcard`）接入 `FileOpsTool`/`AppBackupTool`/`SearchFilesTool`/`BrowseFilesTool`；将 `UrlGuard.check` 接入 `NavigateTool`/`InstallExtensionTool`/`ExtensionInstaller`。高危目标场景下 judge 不可用应**失败即拒**（HUMAN_GATE），而非放行。

### R3 — 母体 WebSocket `tool/execute` 直达全部高危工具，明文 ws 无 TLS（⚠️ 已修正：母体服务端已有鉴权）
**严重度：Medium（原 High，经母体项目复核后降级）**
**受影响文件**：`octopus_mobile/ToolCallDispatcher.kt:62-110`、`octopus_mobile/OctopusMobileClient.kt:112-130`、`res/xml/network_security_config.xml:3`、`tool/ToolRegistry.kt:224-230`
**⚠️ 修正说明（2026-06-25）**：原报告称"无服务端身份校验"经复核母体项目 `octopus-agent/runtime/tentacle/transport/ws_server.py` 后**不成立**。母体服务端已实现：(1) `OCTOPUS_TENTACLE_TOKEN` 环境变量鉴权 + `hmac.compare_digest` 常数时间比较；(2) **fail-closed** 设计——非 loopback 绑定且未设 token 时拒绝所有连接；(3) 暴力破解限速（5 次/60 秒滑动窗口）；(4) 预认证隔离——未认证前只接受 `device/hello`，二进制帧和其他方法全部拒绝。客户端 `OctopusMobileClient.kt:112` 通过 `device/hello` 消息体的 `params.auth_token` 发送 token（非 URL query string），UDP 发现广播不携带 token。
**实际剩余风险**：(1) **明文 ws:// 无 TLS**——token 虽在消息体内，但整个 WebSocket 流量（含 token、tool/execute 参数、屏幕帧）可被中间人嗅探；(2) **无证书 pinning**——即使 wss://，中间人用合法证书仍可 MITM；(3) `HELLO_SENT` 状态下任意入站帧即升级 ONLINE，无 nonce/重放保护；(4) `tool/execute` 经 `executeLocal → ToolRegistry.executeTool`，无远程通道工具白名单（此点已由 R2 工作树修复缓解）。
**影响**：中间人可嗅探 token 并注入 `tool/execute`，但需在网络路径上主动 MITM（非同网段被动监听即可）。严重度从"远程设备接管"降为"需 MITM 的中间人攻击"。
**修复建议**：(1) 母体 `ws_server.py` 的 `start()` 添加 `ssl_context` 参数支持 wss://（需在 octopus-agent 项目修改）；(2) 客户端 OkHttp 已原生支持 wss://，用户配置 wss:// URL 即可；(3) 可选：添加证书 pinning 防合法证书 MITM。

### R4 — config/sync 将攻击者键值写入默认 MMKV，无白名单（可改写 runtime URL / LLM 端点 / 渠道密钥）
**严重度：High（confirmed）**
**受影响文件**：`octopus_mobile/DualConfigWriter.kt:196-238`
**攻击场景/前置条件**：`handleIncomingMessage` 对每条 change 执行 `kv.encode(change.key, change.value)`，仅以单调 version 为闸；`kv` 即 `MMKV.defaultMMKV()`——与 runtime URL、auth token、LLM key、渠道 token 同一存储。无 key 白名单。结合 R3（无服务端校验）即可被恶意服务器利用。
**影响**：远程改写配置/密钥——将手机钉死到攻击者端点、重定向 LLM 端点、覆盖渠道密钥。叠加 R3 的自动重连可跨重启持久化（见 `OctopusMobileClient.kt:161-178`）。
**修复建议**：可同步键白名单；禁止 runtime URL、auth token、LLM 端点/key、渠道密钥被同步；要求双向认证。

### R5 — 控制服务器 token 以明文 UDP 广播至全网段（启用"局域网控制"时）
**严重度：High（confirmed）**
**受影响文件**：`octopus_mobile/DeviceDiscoveryManager.kt:125-192`
**攻击场景/前置条件**：用户在 TrustCenterActivity 开启"被局域网控制"（默认关）后，`sendBeacon()` 每 `BEACON_INTERVAL_MS` 将含 `authToken` 的明文 JSON UDP 广播至 `255.255.255.255:9528`。同网段任意主机绑定 UDP 9528 即可被动嗅探取得 token。`processBeacon` 还逐字信任 beacon 内的 ip/authToken 并 upsert `DeviceRegistry`，不校验 UDP 源地址与广告 ip 是否一致（可投毒注册表）。
**影响**：被动监听即获完整控制 bearer token → 可调用全部 `/api/*`（输入注入、屏幕流、agent/run、/sdcard 文件外传/删除）。"启用局域网控制"实际等于"授权全网所有人"。
**修复建议**：绝不广播 token；改用配对握手（参考 RemoteConsoleGateway 配对模型）/扫码短码；拒绝 ip 与 UDP 源不符的 beacon。

### R6 — 控制服务器绑定 0.0.0.0，全套设备控制 API 暴露给整个 LAN
**严重度：High（confirmed）**
**受影响文件**：`server/ConfigServer.kt:25-28`
**攻击场景/前置条件**：`ConfigServer` 用单参 `NanoHTTPD(port)` 构造，绑定 0.0.0.0 而非 127.0.0.1；`ConfigServerManager.start()` 还广告 LAN URL。`/api/*`（tap/swipe/text/key、screenshot、stream、tree、agent/run、files/*）对同网段任意主机可达，仅以单一 bearer token 防护，无 IP 白名单、无逐请求确认、无限速。
**影响**：完整远程控制 API 暴露于全 LAN，是所有 token 相关弱点得以远程触发的前置条件。开放/访客 Wi-Fi 上攻击者即网内任意设备。
**修复建议**：若仅 RemoteConsoleGateway + 本机消费者需要，则用 `NanoHTTPD(hostname, port)` 绑定 127.0.0.1；若 LAN 访问为必需功能，则按会话显式选择性开启 + IP/子网白名单 + 考虑 TLS。

### R7 — Shizuku shell 命令注入（searchByContent 未校验参数；hasInjectionPattern 漏掉 `& | > <`）+ 转义助手是死代码
**严重度：High（多条 confirmed 合并）**
**受影响文件**：`shizuku/ShizukuShellService.kt:738-742`（注入点）、`:82-94`（死代码转义助手）、`:115-124`（盲过滤）；触达链 `tool/impl/SearchFilesTool.kt:90`
**攻击场景/前置条件**：`searchByContent` 将 `text/basePath/filePattern` 三参全部无校验插入 `grep -rl "$text" "$basePath" --include="$filePattern" ...`。唯一过滤 `hasInjectionPattern()` 只拦 `; && || \` $( 换行`，**漏掉单字符 `& | > <` 与 `${...}`**。`text` 形如 `x" /sdcard & am start ... #` 即可破引号并追加独立命令。类 `sanitizeShellArg/containsShellMetachar`（正确的单引号转义）**全代码库零调用**——本应的转义层从未接线。数据流：母体代理 → `tool/execute` → `ToolCallDispatcher` → `ToolRegistry.executeTool("search_files",{action:"content"})` → `searchByContent`，路径上无 shell 感知护栏。
**影响**：以 shell UID(2000) 任意命令执行——可截屏(READ_FRAME_BUFFER)、全局注入输入(INJECT_EVENTS)、改系统设置、force-stop/clear 任意 App、读所有 App 的 /sdcard/Android/data。
**修复建议**：禁止字符串拼接构命令；改用 `Runtime.exec(String[])` argv（不经 `sh -c`），或对每个不可信参数套 `sanitizeShellArg()`；`basePath` 用 `isValidPath()`+`/sdcard` 前缀校验；将 `& | > < ${` 加入过滤作纵深防御。

### R8 — 母体/LAN/聊天可达浏览器 `browser_evaluate`，在任意已登录页面执行任意 JS（会话/Cookie/CSRF 窃取），却被当作只读/低危
**严重度：High（confirmed）+ Medium 误分类（confirmed）合并**
**受影响文件**：`tool/impl/browser/BrowserTools.kt:247-283`（+ `:76-101` GetDom/Click/Type 选择器 JS 注入）；`octopus_mobile/safety/ToolCallGuardrail.kt:28-33`（误列入 IDEMPOTENT_TOOLS）；`SystemWebViewEngine.kt:138-146`
**攻击场景/前置条件**：`BrowserEvaluateTool` 将 `script` 原样传 `evaluateJavascript()`。可达入口：母体 WS、LAN `/api/agent/run`（console.html:132）、`BrowserActivity.kt:1828-1841` 的页面内容驱动。内嵌浏览器共享 Cookie 存储。`browser_evaluate` 被误列入 `IDEMPOTENT_TOOLS` 且不在 HIGH/MEDIUM_RISK，故 `riskOf` 返回 RISK_LOW、`shouldAudit` 为 false——最强工具既不限闸也不审计。`GetDom/Click/Type` 还通过未转义 selector/attribute 拼接实现等价任意 JS。
**影响**：远程操作者/同网段 token 持有者/被注入代理可在任意已登录页面执行任意 JS——窃取会话 Cookie/token、读取私有页面内容、代用户执行已认证操作。无取证痕迹。
**修复建议**：将 `browser_evaluate` 标记 DANGEROUS/HIGH_RISK、移出 IDEMPOTENT_TOOLS、强制逐次人工确认与审计；selector/attribute/text 以 `JSON.stringify` 作数据传入而非拼接；考虑代理浏览自动化使用独立 cookie jar。

### R9 — ~~代理可调用 browser_install_extension~~ ⚠️ 已废弃(GeckoView 已移除)
**严重度：~~High~~ → N/A(GeckoView 已移除,WebExtension API 不再可用)**
**受影响文件**：`tool/impl/browser/BrowserTools.kt:293-369`、`octopus_mobile/browser/ExtensionInstaller.kt:187-219`、`octopus_mobile/browser/CrxToXpiConverter.kt:108-131`、`octopus_mobile/browser/GeckoViewEngine.kt:188-211`
**攻击场景/前置条件**：`source=url:<任意URL>` → `installFromUrl` → 明文 OkHttp GET（无 UrlGuard、无 pinning、无 scheme/host 校验）→ CRX3 头（含发布者签名）被**切掉丢弃**、重打包为未签名 XPI → `webExtensionController.install`。`configureRuntime` 设 `extensionsWebAPIEnabled=true` 且 `onInstallPromptRequest` **无条件返回 `PermissionPromptResponse(true,…)`**，自动批准所有请求权限。可达母体 WS / LAN agent / 提示注入；且任意被访问网页亦可经 WebAPI 触发自动批准的 drive-by 安装。
**影响**：远程/LAN/被注入代理可从任意 URL 安装未签名、全权限 WebExtension，对用户在内嵌浏览器所浏览的每个页面持久读写。
**修复建议**：安装前过 `UrlGuard.check`、仅允许 https、限制到 AMO/官方 CWS 来源白名单；验证 CRX3 签名而非丢弃；安装走真正的 HUMAN_GATE 人工确认；`onInstallPromptRequest` 改为弹出真实权限确认而非自动批准；将 `extensionsWebAPIEnabled` 限定仅对 addons.mozilla.org 生效或关闭。

### R10 — Relay 服务器 JWT_SECRET 硬编码默认值，导致全账号伪造 / 完全鉴权绕过 ✅ FIXED
**严重度：High（confirmed，含 2 条 server 端重复项合并）**
**状态**：已修复。`server/app.py:94-103` 现在生产环境（`ENV=production`）未设 `JWT_SECRET` 时 `raise RuntimeError` 拒绝启动；非生产环境生成一次性随机密钥（`secrets.token_urlsafe(48)`），不再回退到源码占位符。`server/.env.example` 的 `JWT_SECRET` 也已改为空值。
**受影响文件**：`server/app.py:94,172-187,318-330,346`
**攻击场景/前置条件**：`JWT_SECRET = os.environ.get("JWT_SECRET", "dev-insecure-change-me")`。若运维未设环境变量（应用会"正常工作"故极易遗漏），任何读过公开源码者即知签名 key，可离线为任意 user_id 伪造 JWT（含 UNLIMITED_EMAILS 无限额度账号），完全远程、无需认证。同一 secret 还 HMAC 设备 token（:346）。
**影响**：远程未认证攻击者伪造任意用户会话 token——冒充成员获取免费 LLM 中继、耗尽他人积分、读改计费状态、下单兑换。
**修复建议**：导入期断言——`JWT_SECRET` 为空或等于占位符时拒绝启动（`sys.exit`）；或首次启动生成并持久化随机密钥。绝不为签名 key 提供可用默认值。

### R11 — 屏幕截图 / MJPEG 实时流无逐会话用户同意、无"被查看"指示
**严重度：Medium（confirmed，列入关键风险因隐私影响显著）**
**受影响文件**：`server/ConfigServer.kt:696-785`
**攻击场景/前置条件**：`handleScreenshot/handleScreenStream` 经 `ClawAccessibilityService.takeScreenshot()`（无障碍 API，无系统同意框、无录制指示）返回全屏 JPEG/MJPEG(~12fps)。任意持 token 的同网段客户端可静默拉取连续镜像（含银行 App、OTP、消息）。`handleScreenTree` 同样返回全屏无障碍文本树。仅有 100ms 节流 + 2 路信号量。
**影响**：静默、连续外传屏上内容（凭据、OTP、私信）至持 token 的 LAN 对端，设备端零提示。
**修复建议**：serve 前要求显式可撤销的用户授权；流活动时显示持久化设备端指示（通知/悬浮）；将这些端点置于比配置端点更严格的能力之后。

> **关联的其余 High 项**（不再单列攻击面，归并入上）：`ws-mother-control` 的安全层失效（`ToolRegistry.kt:224-230`，judge=null/HUMAN_GATE 被忽略，已并入 R2/R3）；`safety-guardrails` 的 UrlGuard/ToolCallGuardrail 死代码（已并入 R2）；`accessibility-and-tools` 的 send_sms 确认可被代理自身 tap 工具绕过（`SendSmsTool.java:83-94`，Medium）；屏幕树不脱敏密码字段（`ClawAccessibilityService.java:285-378`，Medium）。

---

## 3. 中低风险 (Medium / Low)

**HTTP 控制服务器**
- `ConfigServer.kt:66-73` — token 经 `?token=` 查询串接受并分发，泄漏于浏览器历史/代理日志/Referer/云中继 — 仅用 Authorization 头；console 改用 `#token=` + `history.replaceState` 剥离。
- `ConfigServer.kt:84-90` — 审计源 IP 取自攻击者可控转发头(X-Forwarded-For 等)，可伪造审计日志 — 记录真实 socket 对端 `session.remoteIpAddress`。
- `ConfigServer.kt:75-82` — constantTimeEquals 长度不符即早返回，泄漏 token 长度且循环长度随输入变 — 改用 `MessageDigest.isEqual`。
- `ConfigServer.kt:260-278` — 持 token 的 LAN 对端可驱动完整代理 + 输入/文件操作，无第二因子 — agent/run、control/input 视为最高敏感，时限授权 + 设备端横幅。
- `ConfigServer.kt:906-953` — `handleControlInput` 的 tap/swipe/key/open_app 直达无障碍，绕过 ToolRegistry/护栏/审计/禁用开关 — 改走 `ToolRegistry.executeTool`。

**母体 WebSocket / 协议**
- `OctopusMobileClient.kt:161-178` — 自动重连对配置端点反复重攻、跨重启无再确认 — runtime URL 变更或重复失败时再确认 + 持久指示 + kill switch。
- `Protocol.kt:23-28` — 手写 JSON 信封对 method/id/key 未转义插值（当前为常量，潜在缺陷）— 改用 Gson/Moshi 或统一转义。
- `OctopusMobileClient.kt:137-178` — 用户显式 disconnect 后仍自动重连（OFFLINE 状态被复用）— 引入 `@Volatile userDisconnected` 标志门控 `scheduleReconnect`。
- `OctopusMobileClient.kt:83-178` — `connect()` 无单连接守卫，重连竞态产生重叠 WebSocket 泄漏 — 用 AtomicBoolean/状态校验，开新连接前关旧。

**Shizuku shell**
- `ShizukuShellService.kt:751-756` — `findDuplicateFiles` basePath 未校验注入 find/awk（当前被自身 `;` 自阻）— `isValidPath()`+`/sdcard` 前缀 + `sanitizeShellArg()`。
- `SearchFilesTool.kt:69-97` — searchByContent/findDuplicateFiles 的 basePath 可逃逸 /sdcard 沙箱经 grep 读他 App 数据 — 在 service 层 fail-closed 应用 `/sdcard` 前缀闸。
- `ShizukuShellService.kt:321-328` — `putSetting` key/value 未校验未引号插入 `settings put`（当前仅常量调用方，潜在）— key 校验 `^[A-Za-z0-9_.]+$`，value 引号化。
- `ShizukuShellService.kt:115-124` — 注入过滤误伤含 `&&`/`;` 的合法模板（CloudDrive 生命周期、handleFileDownload 的 cp、getStorageOverview、findDuplicateFiles 全部静默失效）— 改为固定 argv 模板，仅校验变量 token。

**插件 / 扩展加载**
- `PluginManager.kt:182-204` — `installFromFile` 用未净化 `manifest.id/dexFile` 构路径，可写出预期目录外（需用户手选恶意插件）— 校验为安全 basename + 规范路径包含性检查。
- `CrxToXpiConverter.kt:108-131` — CRX3 头长按有符号 Int 解析，畸形 CRX 抛 IndexOutOfBounds / 产畸形 ZIP — 按无符号读并对文件大小做边界校验。
- `ExtensionInstaller.kt:187-209` — 直 XPI 安装路径跳过 manifest 校验、合成时间戳 ID — 解析校验内嵌 manifest，由 `browser_specific_settings.gecko.id` 派生稳定 ID。

**安全护栏（已并入 R2 的次要项）**
- `PathGuard.kt:98-110`（**待确认**）— 沙箱逃逸检查用 `startsWith` 无分隔符边界，同前缀兄弟目录可绕过；`ConfigServer.kt:577` 同样缺陷 — 比较 `base + File.separator`。
- `ToolRiskPolicy.kt:9-12,50-56` + `ToolAuditLog.kt:31-47` — 风险标签仅审计不阻断；审计日志明文 KV、可被进程内代码 `clear()`、按 key 名而非值脱敏 — 加风险拒绝分支 + 防篡改追加式存储 + 按值脱敏。
- `CircuitBreaker.kt:35-208` — 完整实现但未接入工具执行失败路径，无 volume/cost 熔断 — 在 `ToolRegistry` 工具执行前后接入，或删除以免暗示其有覆盖。

**密钥存储**
- `KVUtils.kt:61-80,240-302` — 全部客户端密钥（bot token、渠道 app-secret、LLM key、账号会话 token、CloudDrive 密码）明文 MMKV 无加密 — 用 Keystore 封装的 cryptKey 或 EncryptedSharedPreferences。
- `FileLoggingInterceptor.java:126-128` — debug 构建将 LLM/账号 bearer token 明文写入缓存文件（且可经 /api/debug/file 经 LAN 取回）— header 循环中脱敏 Authorization/Cookie/x-api-key。
- `QBotApiClient.java:147` — debug logcat 全量打印含 access_token 的 QQ bot 响应体 — 仅打印 expires_in 等非敏感字段；`XLog.DEBUG` 默认改 false。

**渠道 / Webhook**
- `WeChatChannelHandler.kt:385-418` — context-token 反查未命中时回退全局 `lastFromUserId`，可将代理输出投递给错误用户（多用户跨用户信息泄漏）— 要求正向 token→userId 匹配，否则丢弃。
- `QBotWebSocketManager.java:53-60` — 去重集为非同步 LinkedHashMap，重连期跨线程访问可损坏/放过重复消息 — 用同步包装或并发结构。

**WebView / 浏览器**
- `BrowserTools.kt:44-48` — ~~`NavigateTool` 无 UrlGuard，可导航 SSRF~~ **已修复**:`BrowserNavigateTool` 已接入 `UrlGuard.check()`,阻止内网 IP / 云元数据 / 本地域名。
- `SystemWebViewEngine.kt:85` — release 构建无条件 `setWebContentsDebuggingEnabled(true)`，可经 adb chrome://inspect 注入已登录会话 — 用 `BuildConfig.DEBUG` 门控；~~`GeckoViewEngine.kt:191` 同理~~ ⚠️ GeckoView 已移除。

**Manifest / IPC**
- `AndroidManifest.xml:209-217` — `BootReceiver` 无必要 `exported="true"`，同设备恶意 App 可发显式 Intent 唤起前台服务 — 改 `exported="false"`。
- `network_security_config.xml:3-9` — 全局明文流量许可，削弱 LAN HTTP/WS 控制面 — 限缩为仅 localhost/链路本地，远程端点强制 HTTPS。

**Python Relay**
- `app.py:993-1027` — mock 支付模式（默认）自助授予免费会员/积分，会员分支无上限 — mock 模式硬失败除非显式 dev 标志；会员分支加上限。
- `app.py:970-987` — 订单创建无限速，PENDING 订单无界增长 — 加每用户限速 + 未结订单上限。

**并发 / 可靠性**
- `ClipboardReaderActivity.java:18-46` — 静态 result/latch 在并发剪贴板读间竞态，可读到他人值 — 改 per-request token+map 或加锁串行。
- `ConfigServer.kt:730-785`（**待确认**）— MJPEG 流 setup 在 tryAcquire 后抛异常则信号量 permit 泄漏，两次后永久禁用屏幕流 — setup 套 try/catch，异常时 release。

---

## 4. 系统性问题 (Systemic / Architectural)

1. **信任边界全面缺失——"能对话即等于完全控制"。** 五条独立入口（聊天渠道、LAN HTTP、母体 WS、内嵌浏览器、被注入代理）都直通同一最高权限工具集，且**没有任何一条要求发送者鉴权或人工确认**。这是本项目的主导风险，单点修复无法覆盖——需要在 `ToolRegistry.executeTool` 这一汇聚点统一加入"调用来源信任级别 + 工具风险级别"的强制矩阵。

2. **护栏即装饰（security theater）。** `safety/` 包 7 个护栏类中，仅 `PrivacyScanner`（密钥正则）与失败/循环计数器真正接线；`PathGuard`、`UrlGuard`、`CircuitBreaker` 是零调用死代码；`DANGEROUS`/`HIGH_RISK` 分类、PII 脱敏、`ToolRiskPolicy` 全部只用于审计标签，从不阻断；`SafetyGate` 默认 judge=null 且 LLM 裁判失败即放行。代码给人"已有三级危险工具审批"的错觉，实际**唯一真正会拒绝的只是一个易绕过的密钥格式匹配**。建议要么删除误导性的危险分级，要么真正接线为阻断/确认。

3. **凭据明文化贯穿全栈。** 客户端：全部密钥明文 MMKV、控制 token 明文 UDP 广播 + URL 查询串、debug 日志明文 token、cleartext ws/http 全局许可。服务端：JWT_SECRET 硬编码默认值。整套体系把"全权 token"当普通字符串到处传播与持久化。

4. **"标签 vs 执法"系统性脱节。** `DANGEROUS_TOOLS`、`HIGH_RISK_TOOLS`、`IDEMPOTENT_TOOLS`、转义助手、各 Guard——大量正确实现的安全原语存在却**从未在执行路径上被调用**。这强烈提示这些安全检查从未被真实命令演练过（佐证：注入过滤误伤自身合法模板导致多个功能静默死亡）。

5. **shell 命令构造模式不安全。** `ShizukuShellService` 一律用字符串插值 + `sh -c` 构命令，仅靠"前缀白名单 + 漏洞百出的元字符黑名单"防护；正确的 argv/引号化层（`sanitizeShellArg`）存在却零调用。应整体迁移到固定 argv 模板 + 仅校验变量 token 的结构化模型。

6. **单线程代理调度器自毁不变量。** `TaskOrchestrator` 的两处缺陷（无条件 `executeCurrentTask()`、回调内在 running 标志清除前启动下一任务）使并发任务级联失败、队列功能性失效——日常两条消息即可触发，说明该路径缺乏并发集成测试。

---

## 5. 优先修复清单 (Prioritized Remediation)

### P0 — 立即（远程接管 / 全账号伪造级）
- [x] **聊天渠道发送者白名单**（R1）：已修复（ACL 默认启用 + 空 senderId fail-closed；TOFU 首个发送者绑定 owner，待后续配对码方案）。
- [x] **高危工具强制闸门**（R2）：已修复（`ToolRegistry.executeTool` 对不可信来源 + HIGH 风险走 BLOCK/CONFIRM/ALLOW；MEDIUM 在 ProactiveRuleEngine 中走 CONFIRM；`PathGuard` 已修分隔符边界）。
- [x] **Relay JWT_SECRET 启动断言**（R10）：已修复。生产环境未设 JWT_SECRET 时拒绝启动，非生产环境生成随机密钥。
- [ ] **Shizuku 命令注入**（R7）：`searchByContent`/`findDuplicateFiles`/`putSetting` 改 argv 或 `sanitizeShellArg`，`basePath` 强制 `/sdcard` 校验。
- [ ] **母体 WS 加固**（R3/R4）：强制 wss + 证书 pin、ONLINE 前服务端身份校验、拒绝握手前 `tool/execute`、config-sync 键白名单。
- [x] **主动规则引擎闸门**（R12）：已修复。`executeRule` 按风险分层（HIGH 拒绝 / MEDIUM 确认 / LOW 放行）；`sms_code_copy` 改为 `NOTIFY_USER` 消除验证码外泄面。

### P1 — 高优先（同网段 / 内嵌浏览器接管）
- [ ] **停止广播控制 token**（R5）：改配对握手；拒绝 ip 与 UDP 源不符的 beacon。
- [ ] **控制服务器绑定收敛**（R6）：默认 127.0.0.1；LAN 访问需逐会话显式开启 + IP 白名单 + TLS。
- [x] **`browser_evaluate` 提级**（R8）：已修复（`browser_evaluate` 已移入 `DANGEROUS_TOOLS`；`GetDom/Click/Type` 通过 `JSONObject.quote()` 传参；skill .md risk 已改 high）。逐次人工确认闸待 UI 层落地。
- [ ] **扩展安装加固**（R9）：UrlGuard + https + AMO/CWS 白名单 + 验证 CRX3 签名；安装 PromptDelegate 改真实用户确认而非自动批准。
- [ ] **屏幕流同意闸**（R11）：截图/流端点要求用户授权 + 持久"被查看"指示。
- [x] **修复并发调度缺陷**：E1/E2 已修复（`pauseCurrentTask` 不再立即 resume；回调末尾改 `Handler.post { executeCurrentTask() }` 避免递归）。集成测试仍待补充。

### P2 — 中等（纵深防御 / 泄漏面收敛 / 可靠性）
- [x] token 仅走 Authorization 头，禁用 `?token=`，console 改 `#token=` + 剥离（`ConfigServer.kt:66-73`）：已修复。
- [x] 审计源 IP 取真实 socket 对端；`constantTimeEquals` 改 `MessageDigest.isEqual`；修复 `startsWith` 沙箱边界（**待确认**）：已修复（源 IP 优先 `session.remoteIpAddress`；`MessageDigest.isEqual` 已使用；B3 `PathGuard` 分隔符边界已修复）。
- [ ] 客户端密钥迁移至加密 MMKV/EncryptedSharedPreferences；脱敏 debug 日志中的 token（FileLoggingInterceptor、QBotApiClient）：未修复（工作量大）。
- [ ] 网络配置收敛 cleartext 至仅 localhost/链路本地；release 关闭 WebView 远程调试；`BootReceiver` 改 `exported=false`：WebView 远程调试与 `BootReceiver` 已修复；~~GeckoView 远程调试~~ 已废弃(GeckoView 已移除);cleartext 仍依赖动态 LAN IP / 母体 ws://，需待 R3 完成后收敛。
- [x] 微信回复路由去除全局 `lastFromUserId` 回退；QQ 去重集改并发结构；用户 disconnect 后抑制自动重连：微信回退已移除；QQ 去重集已实现同步包装；disconnect 后重连抑制当前代码已处理，待压测验证。
- [x] Relay：mock 支付硬失败 + 会员上限；订单创建限速：已修复。
- [ ] `local.properties` 签名口令拆分为独立强口令、移出仓库工作树（`release-old-weakpass-backup.keystore`）；密钥材料迁至 CI 密钥库（**待确认 / info**：经核实 keystore 与 local.properties 均已 git-ignore，未入版本库）。

> **待确认项（contested，需复核）**：`ConfigServer.kt:1192-1197` 通配 CORS + DNS-rebinding（info）；~~`GeckoViewEngine.kt:188-211` WebAPI 自动批准 drive-by 安装~~ ⚠️ 已废弃(GeckoView 已移除)；`PathGuard.kt:98-110` 同前缀沙箱逃逸（info）；`ConfigServer.kt:730-785` MJPEG permit 泄漏（info）；`local.properties` 签名口令复用（info，且证伪了"keystore 已提交"的初始假设）。

---

## 6. 审计盲区与补录发现 (Completeness Critic)

查漏阶段对 12 维度未覆盖的子系统做了抽查，发现 **1 条被完全漏审的高危路径**，应补入 P0/P1：

### R12 — 主动规则引擎：不可信触发（通知/短信/屏幕文本）→ 无防护直接执行任意工具 ⭐ 补录 ✅ FIXED
**严重度：High～Critical（confirmed via spot-check）**
**状态**：已修复。`ProactiveRuleEngine.executeRule` 现在按风险分层闸门：
- HIGH 风险工具（send_sms / send_intent / file_ops 等）在非高级自动化模式下直接拒绝（已有）
- MEDIUM 风险工具（tap / swipe / input_text / clipboard / open_app 等）在非高级自动化模式下走 `ApprovalFlow.requestApproval` 人工确认（30s 超时拒绝）
- LOW 风险工具（只读/观察类）自动放行
- 高级自动化模式开启时全部放行
- 所有执行路径仍包在 `ToolRegistry.withUntrustedSource{}` 中走不可信来源闸门
- 内置规则 `sms_code_copy` 从 `EXECUTE_AND_NOTIFY clipboard set` 改为 `NOTIFY_USER`：不再自动复制验证码到剪贴板（消除验证码外泄面），仅通知用户验证码内容由用户手动复制
**受影响文件**：`octopus_mobile/proactive/ProactiveRuleEngine.kt:218-287`（executeRule 风险分层）、`:129-168`（onSmsReceived sms_code_copy 特殊处理）、`:327-340`（内置规则定义）

### 其余抽查结论（需补审 / 基本干净）
- **`media/CloudDriveManager.kt` + `WebDAVScanner.kt`（部分真实，需补审）**：CD2 密码与 WebDAV basic-auth 凭据明文存 MMKV（secrets 维度只统计了 channel/LLM token，**漏了这两类**）；`WebDAVScanner` `followRedirects(true)` + 配置驱动的 `baseUrl` + 全局 cleartext = 一条未被 `UrlGuard` 覆盖的 SSRF/重定向面，`scanRecursive` 无 host 校验可指向内网。`start()` 的 `cd ... && nohup ... &` 命令受 R7 误杀 filter 影响，提示该路径从未真正执行验证。
- **`octopus_mobile/evolution/EvolutionEngine.kt`（基本干净，残留中低危）**：不改文件/不执行代码，`dryRun` 默认 true；残留风险是 `deepReflect` 把 LLM 输出自动落库为 lesson 并回灌后续 prompt → 可被 prompt-injection 污染持久化记忆（中低危）。
- **`octopus_mobile/nerves/reflex/ReflexRouter.kt`（基本干净）**：regex→tool 映射不直接执行，仍走主链路；规则为代码内置非外部反序列化，注入面小于 ProactiveRuleEngine。
- **`cast/ScreenCastService.kt`、`octopus_mobile/VoiceInput.kt`（干净）**：本地 MediaProjection / SpeechRecognizer，无网络监听、无 token 化镜像端点，无独立提权面。
- **`server/RemoteConsoleGateway.kt` 的 QR/pairing（建议补一眼）**：token 进二维码/配对码的生成与时效未单独展开，应确认是否一次性/可过期。

> **补录后修正总览**：关键风险升至 R1–R12。R12 应与 R1/R2 并列 P0（同属"汇聚点无强制风险矩阵"根因，且触发面最隐蔽）。

---

## 附录 A：复核与补充审计 (2026-06-25)

> 方法：4 路并行子代理（修复验证 / Android 新发现 / Python+Web / 工程质量）→ 汇总
> 范围：验证 R10/R4/R1/R6 修复状态 + 复核 4 项 contested + 未覆盖子系统（navigation/cast/floating/media/plugin/skills）+ server/app.py 深审 + 工程质量/依赖/CI
> 工作树状态：大量未提交修改（PathGuard/ToolRiskPolicy/ConfigServer/ToolRegistry/ClawAccessibilityService 等），均已审查

### A.1 修复验证结果

| 编号 | 项 | 状态 | 证据 |
|------|-----|------|------|
| R10 | Relay JWT_SECRET 硬编码 | ✅ 已修复 | [app.py:103-112](file:///Users/dangbei/Public/octopus/octopus-mobile/server/app.py#L103) 生产未设则 `raise RuntimeError`；非生产 `secrets.token_urlsafe(48)`；`.env.example` JWT_SECRET 为空 |
| R4 | config/sync 投毒 | ✅ 已修复 | [DualConfigWriter.kt:53-90](file:///Users/dangbei/Public/octopus/octopus-mobile/app/src/main/java/com/apk/claw/android/octopus_mobile/DualConfigWriter.kt#L53) 新增 `SYNC_BLOCKED_EXACT` + `SYNC_BLOCKED_SUBSTRINGS` 双重黑名单，三处应用点全覆盖（本地写/推送/入站） |
| R1 | 聊天渠道发送者白名单 | ⚠️ 部分修复 | [ChannelAccessControl.kt:35-58](file:///Users/dangbei/Public/octopus/octopus-mobile/app/src/main/java/com/apk/claw/android/channel/ChannelAccessControl.kt#L35) 已加入 ACL，但：① 空白名单采用 TOFU（首个发送者自动绑定 owner）非默认拒绝；② senderId 为空时放行（见 A3-N1）；③ ACL 可在设置中关闭 |
| R6 | 控制服务器绑定 0.0.0.0 | ✅ 已修复 | [ConfigServer.kt:16-20](file:///Users/dangbei/Public/octopus/octopus-mobile/app/src/main/java/com/apk/claw/android/server/ConfigServer.kt#L16) 改 `NanoHTTPD(hostname, port)`；[ConfigServerManager.kt:54-65](file:///Users/dangbei/Public/octopus/octopus-mobile/app/src/main/java/com/apk/claw/android/server/ConfigServerManager.kt#L54) 绑定 WiFi 接口 IP；`validateAuth` 用 `MessageDigest.isEqual` 恒定时间比较 |
| BootReceiver exported | (低危项) | ✅ 已修复 | [AndroidManifest.xml:209-217](file:///Users/dangbei/Public/octopus/octopus-mobile/app/src/main/AndroidManifest.xml#L209) 改 `exported="false"` |
| 订单创建无限速 | (server 项) | ✅ 已修复 | [app.py:1369-1387](file:///Users/dangbei/Public/octopus/octopus-mobile/server/app.py#L1369) 用户限速 + IP 限速 + PENDING 上限 |

### A.2 争议项（contested）复核结论

| 编号 | 项 | 结论 | 说明 |
|------|-----|------|------|
| B1 | ConfigServer 通配 CORS + DNS-rebinding | **confirmed（降为低危）** | [RouteContext.kt:22-25](file:///Users/dangbei/Public/octopus/octopus-mobile/app/src/main/java/com/apk/claw/android/server/routes/RouteContext.kt#L22) `Access-Control-Allow-Origin: *` + 无 Host 校验确认；但所有 `/api/*` 强制 token 鉴权，浏览器对 `*` 不发凭证，实际可利用性低 |
| B2 | ~~GeckoView WebAPI 自动批准 drive-by 安装~~ | **⚠️ 已废弃** | GeckoView 已整体移除,WebExtension API 不再可用。此项不再适用。 |
| B3 | PathGuard 同前缀沙箱逃逸 | **rejected（已修复）** | [PathGuard.kt:104-111](file:///Users/dangbei/Public/octopus/octopus-mobile/app/src/main/java/com/apk/claw/android/octopus_mobile/safety/PathGuard.kt#L104) 已用 `base + File.separator` 做分隔符边界检查；`git blame` 确认在提交 `0c6270f`（2026-06-20）中落地 |
| B4 | MJPEG permit 泄漏 | **confirmed（低危）** | [ScreenHandler.kt:85-140](file:///Users/dangbei/Public/octopus/octopus-mobile/app/src/main/java/com/apk/claw/android/server/routes/ScreenHandler.kt#L85) `tryAcquire` 与线程 `finally { release() }` 之间无外层 try/finally，异常路径下信号量泄漏可致屏幕流 DoS |

### A.3 新发现 — Android App

#### N1 — ShizukuShellService 实际以 APP UID 执行命令，颠覆 R7 的 shell UID 假设 ⭐
**严重度：High（安全假设失效，双向影响）**
**受影响文件**：[ShizukuShellService.kt:184-186](file:///Users/dangbei/Public/octopus/octopus-mobile/app/src/main/java/com/apk/claw/android/shizuku/ShizukuShellService.kt#L184)
**发现**：代码注释自称"通过 Shizuku Binder 在 shell 进程执行命令"，但实际用 `Runtime.getRuntime().exec(arrayOf("sh","-c",command))`，命令以 **APP UID** 执行而非 shell UID 2000。`ShizukuManager` 从未调用 `Shizuku.bindUserService()` 或 `Shizuku.newProcess()`。
**影响（双向）**：
- **降低 R7 影响**：`screencap`/`input tap`/`am force-stop` 等需 shell 权限的命令在 APP UID 下被 SELinux 拒绝，R7 声称的"shell UID 任意命令执行"实际不成立。
- **新风险**：文档/实现脱节造成"安全幻觉"——开发者以为有 shell 权限增益，实际没有；`backup_app` 等 skill 声称的能力（访问 `/sdcard/Android/data/`）实际不可达。
**修复建议**：接入 `Shizuku.bindUserService(IUserService)` 真正在 shell UID 执行；或修正所有文档注释明确 APP UID 限制，移除不可达路径白名单。

#### N2 — WebDavMounts.playUrl 将明文凭据嵌入 URL
**严重度：Medium**
**受影响文件**：[WebDavMounts.kt:51-59](file:///Users/dangbei/Public/octopus/octopus-mobile/app/src/main/java/com/apk/claw/android/media/WebDavMounts.kt#L51)
**发现**：WebDAV 用户名密码以 `scheme://user:pass@host` 拼入播放 URL，传给 mpv/ffmpeg。凭据泄漏到 mpv 日志、crash report、`/proc/<pid>/cmdline`、重定向 Referer。
**修复建议**：改用 HTTP 头 `Authorization: Basic <base64>`。

#### N3 — WebDAVScanner followRedirects(true) + 无 host 校验 = SSRF
**严重度：Medium**
**受影响文件**：[WebDAVScanner.kt:37-41](file:///Users/dangbei/Public/octopus/octopus-mobile/app/src/main/java/com/apk/claw/android/media/WebDAVScanner.kt#L37)、[:208-247](file:///Users/dangbei/Public/octopus/octopus-mobile/app/src/main/java/com/apk/claw/android/media/WebDAVScanner.kt#L208)
**发现**：`followRedirects(true)` + PROPFIND 响应的 `href` 直接拼接为新 baseUrl，无 host 白名单。恶意 WebDAV 服务器可重定向到 `169.254.169.254` 或 `127.0.0.1:9527` 探测内网。
**修复建议**：`followRedirects(false)` + `URI.resolve()` 规范化 + host 白名单 + 禁止内网段。

#### N4 — NavigationGraph 持久化 UI 指纹，keyElements 在脱敏前提取敏感按钮文本
**严重度：Medium（隐私）**
**受影响文件**：[StateDetector.kt:110-131](file:///Users/dangbei/Public/octopus/octopus-mobile/app/src/main/java/com/apk/claw/android/navigation/StateDetector.kt#L110)、[NavigationGraph.kt:206-220](file:///Users/dangbei/Public/octopus/octopus-mobile/app/src/main/java/com/apk/claw/android/navigation/NavigationGraph.kt#L206)
**发现**：`extractKeyElements` 在 `normalizeTree` **之前**提取 `content-desc`，保留长度 1-20 的原始按钮文本（如"转账"、"支付密码"），序列化到 MMKV。结合 R4/R6 可外传，形成行为画像。
**修复建议**：`extractKeyElements` 在 `normalizeTree` 之后运行，或对 desc 做 hash 化。

#### N5 — ScreenCastService /api/cast/start 无用户同意闸
**严重度：Medium**
**受影响文件**：[CastRouteHandler.kt:53-62](file:///Users/dangbei/Public/octopus/octopus-mobile/app/src/main/java/com/apk/claw/android/server/routes/CastRouteHandler.kt#L53)、[ScreenCastService.kt:86-94](file:///Users/dangbei/Public/octopus/octopus-mobile/app/src/main/java/com/apk/claw/android/cast/ScreenCastService.kt#L86)
**发现**：`/api/cast/start` 与 `/api/cast/launch` 仅 token 鉴权即开始投屏/在外接屏启动任意 App，无设备端横幅/确认。
**修复建议**：要求设备端显式确认（通知或对话框）。

#### N6 — SemanticSkillRanker 明文 HTTP 请求母体网关
**严重度：Medium**
**受影响文件**：[SemanticSkillRanker.kt:38-47](file:///Users/dangbei/Public/octopus/octopus-mobile/app/src/main/java/com/apk/claw/android/octopus_mobile/SemanticSkillRanker.kt#L38)
**发现**：从 WS URL 推导 `http://$host:$port`，强制明文 HTTP，无 Authorization。POST body 含用户任务描述与设备能力清单。中间人可嗅探或篡改排序结果影响 LLM 决策。
**修复建议**：从 `wss://` 推导 `https://`，携带母体配对 token。

#### N7 — ChannelAccessControl null-sender 绕过 + TOFU 抢跑竞态
**严重度：Medium**
**受影响文件**：[ChannelAccessControl.kt:38-50](file:///Users/dangbei/Public/octopus/octopus-mobile/app/src/main/java/com/apk/claw/android/channel/ChannelAccessControl.kt#L38)
**发现**：① `senderId.isNullOrBlank()` → `ALLOW` + 告警，不上报发送者的通道可绕过 ACL；② 空白名单时首个发送者自动绑定为 owner（TOFU），攻击者抢在合法用户前发首条消息即获永久控制权（代码注释已承认此局限）。
**修复建议**：对支持但未上报 senderId 的通道默认 DENY；TOFU 改为配对码/扫码绑定。

#### N8 — ~~GeckoView remoteDebuggingEnabled 全局开启~~ ⚠️ 已废弃
**严重度：~~Medium~~ → N/A**
**受影响文件**：~~[GeckoViewEngine.kt:191](file:///Users/dangbei/Public/octopus/octopus-mobile/app/src/main/java/com/apk/claw/android/octopus_mobile/browser/GeckoViewEngine.kt#L191)~~
**发现**：~~`settings.remoteDebuggingEnabled = true`~~ GeckoView 已整体移除,此项不再适用。SystemWebViewEngine 的远程调试已修复(仅 DEBUG 构建)。
**修复建议**：N/A(GeckoView 已移除)。

#### N9 — console-app.js token 从 URL 查询参数获取（AUDIT_REPORT 建议项未落地）
**严重度：Medium**
**受影响文件**：[console-app.js:8-9](file:///Users/dangbei/Public/octopus/octopus-mobile/app/src/main/assets/web/console-app.js#L8)、[:24-26](file:///Users/dangbei/Public/octopus/octopus-mobile/app/src/main/assets/web/console-app.js#L24)
**发现**：`const TOKEN = q.get('token')` 从 `location.search` 取 token；所有 API 调用拼 `?token=` 到 URL。token 泄漏于浏览器历史、nginx 日志、Referer、MJPEG 流 URL。原报告 P2 建议"console 改用 `#token=`"**未在此文件落地**。
**修复建议**：改 `location.hash` + `history.replaceState` 剥离；API 改 `Authorization` header。

#### N10 — browser.evaluate skill risk 标签错误 + JSON Schema 畸形
**严重度：Low（放大 R8）**
**受影响文件**：[browser.evaluate.md:4-6](file:///Users/dangbei/Public/octopus/octopus-mobile/app/src/main/assets/skills/mobile/browser.evaluate.md#L4)
**发现**：`risk: medium`（应为 high）；JSON Schema `properties` 混入 `"\"document.title\""`、`"\"localStorage.getItem('token')\""` 等以 JS 代码片段作为属性名的畸形条目。.md 会被 SkillExporter 上传给母体，导致母体端也低估该工具风险。
**修复建议**：`risk: high`；清理 Schema 只保留 `expression`/`await_promise`。

#### N11 — install_app skill 声明但无 Tool 实现
**严重度：Low（info）**
**受影响文件**：[install_app.md](file:///Users/dangbei/Public/octopus/octopus-mobile/app/src/main/assets/skills/mobile/install_app.md)
**发现**：`tool/impl/` 下无 `InstallAppTool` 类，LLM 调用 `install_app` 会返回"工具未找到"，浪费对话轮次。
**修复建议**：删除 `install_app.md` 或补 Tool 实现并接入 R2 闸门。

#### N12 — Plugin installFromFile 残留 dex 文件
**严重度：Low**
**受影响文件**：[PluginManager.kt:185-207](file:///Users/dangbei/Public/octopus/octopus-mobile/app/src/main/java/com/apk/claw/android/plugin/PluginManager.kt#L185)
**发现**：`installFromFile` 把外部 dex 复制到 filesDir，但 `loadAndRegister`（[:116-121](file:///Users/dangbei/Public/octopus/octopus-mobile/app/src/main/java/com/apk/claw/android/plugin/PluginManager.kt#L116)）拒绝加载 `source != "assets"` 的插件。dex 残留可被未来漏洞利用。
**修复建议**：加载被拒时清理已复制文件。

#### N13 — NavigationRecorder 被动监听用户按键行为
**严重度：Low（隐私）**
**受影响文件**：[NavigationRecorder.kt:21-46](file:///Users/dangbei/Public/octopus/octopus-mobile/app/src/main/java/com/apk/claw/android/navigation/NavigationRecorder.kt#L21)
**发现**：`passiveMode` 开启后后台持续监听 D-pad/Back/Home 按键，记录 `beforeState → action → afterState` 到 MMKV。结合 N4 的 keyElements 形成完整行为画像。
**修复建议**：开启时显示持久通知；提供"排除 App 包名"列表（默认排除银行/支付）。

### A.4 新发现 — Python Server + Web 控制台

#### S1 — /device/register 设备 IDOR 劫持
**严重度：High**
**受影响文件**：[app.py:1085-1109](file:///Users/dangbei/Public/octopus/octopus-mobile/server/app.py#L1085)
**发现**：`ON CONFLICT(device_id) DO UPDATE SET user_id=excluded.user_id`——攻击者知道他人 deviceId 即可重新注册夺取设备所有权（含 deviceToken 重置），可冒充设备接收控制指令、心跳上报污染。deviceId 为 `d_`+16 hex（64 bit 熵），内部人员或日志泄漏即可利用。
**修复建议**：INSERT 前校验现有 device 的 user_id 与当前用户一致；冲突时返回 409。

#### S2 — JWT_SECRET 复用为设备 token HMAC 密钥
**严重度：High**
**受影响文件**：[app.py:463-464](file:///Users/dangbei/Public/octopus/octopus-mobile/server/app.py#L463)
**发现**：`_remote_secret_hash` 用 `JWT_SECRET` 作 HMAC key 签设备 token。JWT_SECRET 一旦泄漏，攻击者既可伪造任意用户 JWT，也可伪造任意设备 token 直连 WebSocket 控制手机。密钥应分离。
**修复建议**：设备 token 用独立 `DEVICE_TOKEN_SECRET` 环境变量。

#### S3 — mock 模式 subscription_renew 会员天数无上限
**严重度：High**
**受影响文件**：[app.py:1507-1535](file:///Users/dangbei/Public/octopus/octopus-mobile/server/app.py#L1507)
**发现**：`PAYMENT_PROVIDER=mock` 时，任何登录用户调 `/billing/subscription/renew` 即加 30 天会员 + 积分，可循环调用 N 次 → 会员期 N×30 天。积分受 `FREE_CAP` 约束，但**会员天数无上限**。生产 `PAYMENT_PROVIDER != mock` 时返回 403，但 staging 忘切即被薅。
**修复建议**：mock 模式也对会员天数设上限（如 365 天）。

#### S4 — WebSocket 无 Origin 校验（CSWSH）+ token 走 query string
**严重度：Medium**
**受影响文件**：[app.py:1020-1081](file:///Users/dangbei/Public/octopus/octopus-mobile/server/app.py#L1020)
**发现**：WS 握手未校验 `Origin` header；`?device_token=`/`?token=` 出现在 URL，被 nginx/uvicorn 日志记录。结合 token 泄漏可形成 Cross-Site WebSocket Hijacking。
**修复建议**：`ws.accept()` 前校验 Origin 白名单；token 改用 `Sec-WebSocket-Protocol`。

#### S5 — update_device_info 接受任意 http:// URL（SSRF/钓鱼）
**严重度：Medium**
**受影响文件**：[app.py:501-518](file:///Users/dangbei/Public/octopus/octopus-mobile/server/app.py#L501)
**发现**：设备上报 `lanBaseUrl`/`lanConsoleUrl` 仅校验 `startswith("http://")`，可指向内网（169.254.169.254）或攻击者域。这些值广播给控制台，控制台可能自动 `window.open`。
**修复建议**：校验 IP 为私网段且与设备上报的 LAN 一致；控制台打开前需用户确认。

#### S6 — debug.html 无鉴权，依赖网络层
**严重度：Medium**
**受影响文件**：[debug-app.js:20](file:///Users/dangbei/Public/octopus/octopus-mobile/app/src/main/assets/web/debug-app.js#L20)、[:133](file:///Users/dangbei/Public/octopus/octopus-mobile/app/src/main/assets/web/debug-app.js#L133)
**发现**：`/api/debug/tools` 和 `/api/debug/execute` 不带 token。若 ConfigServer 暴露 debug 路由，同网段任意主机可调用全部工具（take_screenshot、send_sms、file_ops）。当前依赖 ConfigServer 绑定 WiFi IP（R6 已修复）+ debug 路由仅 DEBUG 构建可用。
**修复建议**：debug 路由强制鉴权；release 构建移除 debug.html。

#### S7 — nginx 透传客户端 Host，/config Host 注入
**严重度：Medium**
**受影响文件**：[club.octoapk.com.conf:23](file:///Users/dangbei/Public/octopus/octopus-mobile/server/deploy/club.octoapk.com.conf#L23)、[app.py:1322-1334](file:///Users/dangbei/Public/octopus/octopus-mobile/server/app.py#L1322)
**发现**：`proxy_set_header Host $host;` 透传客户端 Host，`/config` 用 `request.headers.get("host")` 派生 `club.<root>`。攻击者发 `Host: evil.com` 可让 App 拿到 `squareBaseUrl: https://club.evil.com`。若设了 `SQUARE_BASE_URL` 环境变量则覆盖此逻辑。
**修复建议**：nginx 改用 `$server_name` 或显式 `proxy_set_header Host club.octoapk.com;`；或优先用环境变量。

#### S8 — 部署文档泄漏服务器 IP
**严重度：Low（info）**
**受影响文件**：[SETUP_club_subdomain.md:3](file:///Users/dangbei/Public/octopus/octopus-mobile/server/deploy/SETUP_club_subdomain.md#L3)
**发现**：文档内含真实服务器 IP `32.185.238.217`。仓库公开则 IP 暴露。
**修复建议**：移到内部 wiki 或用占位符。

### A.5 新发现 — 工程质量与依赖

#### E1 — TaskOrchestrator 暂停后立即恢复（并发缺陷）
**严重度：High（可靠性）**
**受影响文件**：[TaskOrchestrator.kt:178-180](file:///Users/dangbei/Public/octopus/octopus-mobile/app/src/main/java/com/apk/claw/android/TaskOrchestrator.kt#L178)
**发现**：`pauseCurrentTask()` 先 `pauseRunningTask(cur)` 又立即 `resumeTask(cur.id)`，pause 形同虚设，被抢占任务重新进入队列而非保持暂停态。
**修复建议**：移除立即 resume，保持暂停态直到显式恢复。

#### E2 — TaskOrchestrator 回调内递归调用
**严重度：Medium（可靠性）**
**受影响文件**：[TaskOrchestrator.kt:488](file:///Users/dangbei/Public/octopus/octopus-mobile/app/src/main/java/com/apk/claw/android/TaskOrchestrator.kt#L488)、[:508](file:///Users/dangbei/Public/octopus/octopus-mobile/app/src/main/java/com/apk/claw/android/TaskOrchestrator.kt#L508)、[:539](file:///Users/dangbei/Public/octopus/octopus-mobile/app/src/main/java/com/apk/claw/android/TaskOrchestrator.kt#L539)
**发现**：`onComplete`/`onError`/`onSystemDialogBlocked` 末尾均调用 `executeCurrentTask()`，形成递归。长任务队列下可能栈深度增长。
**修复建议**：改为循环调度或 `handler.post` 延迟调度。

#### E3 — FileLoggingInterceptor 响应体明文落盘
**严重度：Medium**
**受影响文件**：[FileLoggingInterceptor.java:159](file:///Users/dangbei/Public/octopus/octopus-mobile/app/src/main/java/com/apk/claw/android/agent/langchain/http/FileLoggingInterceptor.java#L159)
**发现**：请求/响应 body 完整写入文件（仅 header 做了 redact）。body 可能包含 API key、用户 PII、聊天内容。虽仅 DEBUG 启用，但缓存目录在 root 设备可被读取，且可经 `/api/debug/file` 经 LAN 取回。
**修复建议**：body 也需 redact 或加密；release 构建禁用。

#### E4 — 过时依赖（lifecycle / securityCrypto alpha）
**严重度：Medium**
**受影响文件**：[libs.versions.toml](file:///Users/dangbei/Public/octopus/octopus-mobile/gradle/libs.versions.toml)
**发现**：
- `lifecycle 2.6.2`（落后 3 个大版本，最新 2.9.x），与 compose-bom 2025.05 潜在不匹配
- `securityCrypto 1.1.0-alpha06`（**alpha 版用于生产**，API 不稳定）
- `coroutines 1.7.3`（落后 2 个小版本，1.8+ 有重要 bug 修复）
- `appcompat 1.6.1` / `coreKtx 1.10.1`（落后）
**修复建议**：升级到稳定版；securityCrypto 至少升到 beta01。

#### E5 — CI 缺安全扫描与并行优化
**严重度：Medium**
**受影响文件**：[.github/workflows/ci.yml](file:///Users/dangbei/Public/octopus/octopus-mobile/.github/workflows/ci.yml)
**发现**：无 SAST/CodeQL/依赖扫描/Dependabot；lint/test/assemble 三步串行单 job（可并行拆分缩短 ~60%）；无 APK 产物上传；无 release workflow。
**修复建议**：加 CodeQL + Dependabot；拆分并行 job；上传 APK 产物。

#### E6 — channel/ 全部 6 个 ChannelHandler 零测试覆盖
**严重度：Medium（测试缺口）**
**发现**：`channel/` 约 20 个源文件，0 个测试文件。关键业务路径（钉钉/飞书/QQ/Discord/Telegram/WeChat）无单元测试。`ClawAccessibilityService`、`AppViewModel`、`navigation/` 同样无测试。
**修复建议**：补 ChannelHandler 测试，至少覆盖 token 刷新与消息分发。

#### E7 — AppViewModel 滥用 `!!`（9 处）+ 上帝类倾向
**严重度：Low**
**受影响文件**：[AppViewModel.kt:162-179](file:///Users/dangbei/Public/octopus/octopus-mobile/app/src/main/java/com/apk/claw/android/AppViewModel.kt#L162)
**发现**：`initOctopusMobile()` 中连续 `octopusClient!!`/`brainSelector!!`/`heartbeatReporter!!` 等 9 处 `!!`，若构造失败则后续全部 NPE。该类持有 9 个组件引用，`initOctopusMobile()` 113 行，呈上帝类倾向。
**修复建议**：改用局部变量 + early return；拆分为独立初始化器。

#### E8 — EXTENDING.md 含硬编码 Windows 路径
**严重度：Low（info）**
**受影响文件**：[EXTENDING.md](file:///Users/dangbei/Public/octopus/octopus-mobile/EXTENDING.md)（第 100、199、210、213 行）
**发现**：含 `file:///f:/新建文件夹/octopus-mobile/...` 硬编码路径，在其他平台失效。
**修复建议**：改为相对路径。

### A.6 严重度统计（本次新增）

| 严重度 | 数量 | 编号 |
|--------|------|------|
| High | 4 | N1, S1, S2, S3 |
| Medium | 13 | N2-N9, S4-S7, E1-E3 |
| Low | 9 | N10-N13, S8, E4-E8 |
| **合计** | **26** | — |

### A.7 更新后的优先修复清单

> 原报告 P0/P1/P2 项状态已更新，新增项以 ➕ 标注。

#### P0 — 立即（远程接管 / 全账号伪造级）
- [ ] **聊天渠道发送者白名单**（R1）：⚠️ 部分修复,null-sender 已改为默认拒绝(A3-N7 已修复),TOFU 仍保留(待改配对码)
- [x] **高危工具强制闸门**（R2）：已在工作树实现(PermissionModeManager + ApprovalFlow + ToolRegistry 接入,默认 APPROVAL 模式)
- [x] **Relay JWT_SECRET 启动断言**（R10）：已修复
- [x] **Shizuku 命令注入**（R7）：已修复(searchByContent/putSetting 已用 sanitizeShellArg;findDuplicateFiles 已改用 sanitizeShellArg;hasInjectionPattern 补充 `${` 检测)
- [ ] **母体 WS 加固**（R3/R4）：R4 已修复（config-sync 黑名单）；**R3 经母体项目复核后降级为 Medium**——母体 `ws_server.py` 已有完善鉴权（token + fail-closed + 限速 + 预认证隔离），原报告"无服务端身份校验"不成立；剩余风险仅为明文 ws:// 无 TLS（需母体项目添加 ssl_context 参数）+ 无证书 pinning。R2 工作树修复已缓解"远程通道工具无白名单"问题
- ➕ [x] **设备 IDOR 劫持**（S1）：已修复(/device/register 和 remote_pair_claim 冲突时检查 user_id 一致性,不一致返回 409)
- ➕ [x] **JWT_SECRET 密钥分离**（S2）：已修复(新增 DEVICE_TOKEN_SECRET 环境变量,默认回退 JWT_SECRET 向后兼容)
- ➕ [x] **mock 模式会员上限**（S3）：已修复(新增 MEMBER_MAX_DAYS=365 上限,两处续费逻辑均接入)

#### P1 — 高优先（同网段 / 内嵌浏览器接管）
- [ ] **停止广播控制 token**（R5）：未修复
- [x] **控制服务器绑定收敛**（R6）：已修复（绑定 WiFi IP）
- [x] **`browser_evaluate` 提级**（R8）：已修复（`ToolCallGuardrail` 中 `browser_evaluate` 已移入 `DANGEROUS_TOOLS`；`GetDom/Click/Type` 通过 `JSONObject.quote()` 传参；skill .md risk 已改 high）
- [ ] **扩展安装加固**（R9）：未修复，B2 确认自动批准仍存在
- [ ] **屏幕流同意闸**（R11）：未修复，B4 确认 permit 泄漏仍存在
- [x] **修复并发调度缺陷 E1/E2**：已修复（E1：pauseCurrentTask 移除立即 resume；E2：回调末尾改 `Handler.post { executeCurrentTask() }`，消除递归栈增长）
- ➕ [x] **WebDavMounts 凭据改 HTTP 头**（N2）：已添加 authHeader() 方法 + 风险注释(mpv stub 未实现,待 mpv 接入后切换)
- ➕ [x] **WebDAVScanner 关闭重定向**（N3）：已修复(followRedirects=false)
- ➕ [x] **/api/cast/start 用户确认闸**（N5）：已修复（新增 `CastApprovalManager` + `CastApprovalReceiver`，`/api/cast/start` 与 `/api/cast/launch` 需设备端通知确认，30s 超时或拒绝返回 403）
- ➕ [x] **WebSocket Origin 校验**（S4）：已修复(见 P2)
- ➕ [x] **debug.html 鉴权**（S6）：已修复(从 isPublic 移除 debug.html,所有访问需鉴权;DebugRouteHandler 已有 BuildConfig.DEBUG 门控)
- ➕ [x] **FileLoggingInterceptor body redact**（E3）：已修复(添加 redactBody 对 JSON/form 中的敏感字段脱敏)

#### P2 — 中等（纵深防御 / 泄漏面收敛 / 可靠性）
- [x] **PathGuard 沙箱边界**（B3）：已修复（分隔符检查）
- [x] **token 仅走 Authorization 头**（N9 / ConfigServer）：已修复（console-app.js 改 `#token=` fragment + Authorization 头 + `replaceState` 剥离；ConfigServer 已禁用 `?token=` 查询参数鉴权，仅接受 `Authorization: Bearer`）
- [x] **审计源 IP 取真实 socket 对端 + constantTimeEquals 改 `MessageDigest.isEqual`**：已修复（`RouteContext.sourceOf` 优先使用 `session.remoteIpAddress`，constantTimeEquals 已使用 `MessageDigest.isEqual`）
- [ ] **客户端密钥迁移至加密存储**：未修复
- [ ] **网络配置收敛 cleartext**：未修复（仍依赖动态 LAN IP / 母体 ws://，需待 R3 母体 wss + LAN TLS 完成后方可收敛）
- ➕ [x] **NavigationGraph keyElements 脱敏**（N4）：已修复(extractKeyElements 在 normalizeTree 之后提取,避免敏感按钮文本持久化)
- ➕ [x] **SemanticSkillRanker 强制 https + token**（N6）：已修复(wss://→https:// 映射 + Authorization: Bearer 头)
- ➕ [x] ~~**GeckoView remoteDebuggingEnabled 仅 DEBUG**（N8）~~ ⚠️ GeckoView 已移除,N/A。SystemWebViewEngine 远程调试已改 BuildConfig.DEBUG。
- ➕ [x] **browser.evaluate risk 改 high + 清理 Schema**（N10）：已修复(risk: high + 清理畸形 JSON Schema 属性名)
- ➕ [x] **WebSocket Origin 校验**（S4）：已修复(console WS 添加 _is_allowed_origin 校验 + WS_ALLOWED_ORIGINS 环境变量)
- ➕ [x] **nginx Host 改 $server_name + 安全响应头**（S7）：已修复（`club.octoapk.com.conf` 改为 `proxy_set_header Host $server_name`，加 `server_tokens off` 与基础安全响应头）
- ➕ [ ] **升级 lifecycle/securityCrypto/coroutines**（E4）：需全面回归测试
- ➕ [x] **CI 加 CodeQL + Dependabot + 并行 job**（E5）：已修复(新增 codeql.yml + dependabot.yml)
- ➕ [ ] **补 channel/ 测试**（E6）：工作量大

#### P3 — 低优先
- ➕ [x] **install_app.md 删除或补实现**（N11）：已修复(删除无对应实现的 skill 文件)
- ➕ [x] **Plugin installFromFile 清理残留 dex**（N12）：已修复(installFromFile 前置检查直接返回 null,不复制 dex)
- ➕ [x] **NavigationRecorder 被动模式加通知 + 排除 App**（N13）：已修复(添加常驻通知 CHANNEL_ID + showPassiveNotification/cancelPassiveNotification)
- ➕ [x] **SETUP 文档移除真实 IP**（S8）：已修复(替换为 <your-server-ip>)
- ➕ [ ] **AppViewModel 消除 `!!` + 拆分**（E7）：工作量大
- ➕ [x] **EXTENDING.md 改相对路径**（E8）：已修复(file:///f:/新建文件夹/ → 相对路径)

### A.8 整体态势更新

本次复核确认原报告的核心判断仍然成立：**"能对话即等于完全控制"** 的系统性风险未被根除——R2（高危工具强制闸门）仍未接线，R1 仅部分修复（TOFU + null-sender 偏差）。但 4 项关键修复（R10/R4/R6/BootReceiver）已落地，且 **N1 的发现表明 R7 的实际严重度被高估**（Shizuku 命令实际以 APP UID 执行，shell 权限命令会失败）。

新发现的 High 项集中在 server 端：**S1 设备 IDOR 劫持** 与 **S2 JWT_SECRET 密钥复用** 构成新的远程接管路径，应与 R10 同等优先处理。工程质量方面，**E1 TaskOrchestrator 暂停/恢复缺陷** 是原报告并发问题的新变种，channel/ 零测试覆盖是最大的回归风险面。

> **复核后总览**：原 56 条发现中 5 项已修复（R10/R4/R6/BootReceiver/订单限速）、1 项争议被驳回（B3 已修复）、3 项争议确认（B1/B2/B4）。新增 26 条发现（4 High / 13 Medium / 9 Low）。累计未修复的 Critical/High 风险仍达 20+ 项，**整体安全态势仍为高危**。