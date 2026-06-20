# Octopus Mobile 安全与代码质量审计报告

> 范围：`com.apk.claw.android` Android 端 + `server/app.py` Python 中继服务器
> 方法：12 维度并行审计（166 个智能体）→ 双盲对抗式复核（每条结论经 2 名独立质疑者验证）→ 查漏补审
> 复核结果：76 条发现 → 70 条通过验证（64 confirmed / 6 contested），6 条被驳回
> 状态标注：**已确认 (confirmed)** / **待确认 (contested)**

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

### R3 — 母体 WebSocket `tool/execute` 直达全部高危工具，无能力闸门、无服务端身份校验、明文 ws
**严重度：High / claimedCritical（多条 confirmed 合并）**
**受影响文件**：`octopus_mobile/ToolCallDispatcher.kt:62-110`、`octopus_mobile/OctopusMobileClient.kt:112-130`、`res/xml/network_security_config.xml:3`、`tool/ToolRegistry.kt:224-230`
**攻击场景/前置条件**：默认 URL `ws://10.0.2.2:8765`，`cleartextTrafficPermitted=true`，OkHttp 无 pinning / hostnameVerifier / wss 强制。客户端发 `device/hello`（含 auth_token）但**从不校验服务端**；`HELLO_SENT` 状态下任意入站帧即升级 ONLINE 并分发 `tool/execute`，无 ack/nonce/签名/重放保护。`tool/execute` 经 `executeLocal → ToolRegistry.executeTool`，仅有 `isToolEnabled` + `PrivacyScanner`（仅扫出站密钥）+ 失败计数器，**无 wire 白名单、无确认**。
**影响**：流氓/中间人服务器在首帧即被隐式信任并接管设备；同网段在途攻击者可捕获 token 并注入 `tool/execute` 与 config-sync。等同远程设备接管。
**修复建议**：强制 wss、移除全局明文、pin/校验证书；ONLINE 前要求可验证的服务端凭证，拒绝握手前的 `tool/execute`，加重放保护；对远程通道工具做白名单，HIGH_RISK 走强制人工确认。

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

### R9 — 代理可调用 browser_install_extension：从任意 URL 安装扩展，丢弃签名、无 URL 校验、明文 HTTP、安装提示自动批准全部权限
**严重度：High（多条 confirmed 合并）**
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
- `BrowserTools.kt:44-48` — `NavigateTool` 无 UrlGuard，可导航 SSRF（169.254.169.254、127.0.0.1:9527、file://）— 调用前过 UrlGuard（已并入 R2/R8）。
- `SystemWebViewEngine.kt:85` — release 构建无条件 `setWebContentsDebuggingEnabled(true)`，可经 adb chrome://inspect 注入已登录会话 — 用 `BuildConfig.DEBUG` 门控；`GeckoViewEngine.kt:191` 同理。

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
- [ ] **聊天渠道发送者白名单**（R1）：`ChannelSetup.onMessageReceived` 强制按渠道身份鉴权，空列表默认拒绝 / 一次性配对绑定。
- [ ] **高危工具强制闸门**（R2）：`ToolRegistry.executeTool` 对 `DANGEROUS`/`RISK_HIGH` 工具返回 HUMAN_GATE/BLOCK；接入 `PathGuard`（FileOps 等）与 `UrlGuard`（Navigate/InstallExtension）。
- [x] **Relay JWT_SECRET 启动断言**（R10）：已修复。生产环境未设 JWT_SECRET 时拒绝启动，非生产环境生成随机密钥。
- [ ] **Shizuku 命令注入**（R7）：`searchByContent`/`findDuplicateFiles`/`putSetting` 改 argv 或 `sanitizeShellArg`，`basePath` 强制 `/sdcard` 校验。
- [ ] **母体 WS 加固**（R3/R4）：强制 wss + 证书 pin、ONLINE 前服务端身份校验、拒绝握手前 `tool/execute`、config-sync 键白名单。

### P1 — 高优先（同网段 / 内嵌浏览器接管）
- [ ] **停止广播控制 token**（R5）：改配对握手；拒绝 ip 与 UDP 源不符的 beacon。
- [ ] **控制服务器绑定收敛**（R6）：默认 127.0.0.1；LAN 访问需逐会话显式开启 + IP 白名单 + TLS。
- [ ] **`browser_evaluate` 提级**（R8）：移出 IDEMPOTENT、标记 DANGEROUS/HIGH_RISK、逐次确认 + 审计；GetDom/Click/Type 用 `JSON.stringify` 传参。
- [ ] **扩展安装加固**（R9）：UrlGuard + https + AMO/CWS 白名单 + 验证 CRX3 签名；安装 PromptDelegate 改真实用户确认而非自动批准。
- [ ] **屏幕流同意闸**（R11）：截图/流端点要求用户授权 + 持久"被查看"指示。
- [ ] **修复并发调度缺陷**：`TaskOrchestrator.kt:339-362` 与 `:456-488`，仅在 idle→running 转变时调度，或回调外延迟调度；加两任务队列集成测试。

### P2 — 中等（纵深防御 / 泄漏面收敛 / 可靠性）
- [ ] token 仅走 Authorization 头，禁用 `?token=`，console 改 `#token=` + 剥离（`ConfigServer.kt:66-73`）。
- [ ] 审计源 IP 取真实 socket 对端；`constantTimeEquals` 改 `MessageDigest.isEqual`；修复 `startsWith` 沙箱边界（**待确认**）。
- [ ] 客户端密钥迁移至加密 MMKV/EncryptedSharedPreferences；脱敏 debug 日志中的 token（FileLoggingInterceptor、QBotApiClient）。
- [ ] 网络配置收敛 cleartext 至仅 localhost/链路本地；release 关闭 WebView 远程调试；`BootReceiver` 改 `exported=false`。
- [ ] 微信回复路由去除全局 `lastFromUserId` 回退；QQ 去重集改并发结构；用户 disconnect 后抑制自动重连。
- [ ] Relay：mock 支付硬失败 + 会员上限；订单创建限速。
- [ ] `local.properties` 签名口令拆分为独立强口令、移出仓库工作树（`release-old-weakpass-backup.keystore`）；密钥材料迁至 CI 密钥库（**待确认 / info**：经核实 keystore 与 local.properties 均已 git-ignore，未入版本库）。

> **待确认项（contested，需复核）**：`ConfigServer.kt:1192-1197` 通配 CORS + DNS-rebinding（info）；`GeckoViewEngine.kt:188-211` WebAPI 自动批准 drive-by 安装（low，依赖具体 GeckoView 构建是否仍对非 AMO 源履行 WebAPI）；`PathGuard.kt:98-110` 同前缀沙箱逃逸（info）；`ConfigServer.kt:730-785` MJPEG permit 泄漏（info）；`local.properties` 签名口令复用（info，且证伪了"keystore 已提交"的初始假设）。

---

## 6. 审计盲区与补录发现 (Completeness Critic)

查漏阶段对 12 维度未覆盖的子系统做了抽查，发现 **1 条被完全漏审的高危路径**，应补入 P0/P1：

### R12 — 主动规则引擎：不可信触发（通知/短信/屏幕文本）→ 无防护直接执行任意工具 ⭐ 补录
**严重度：High～Critical（confirmed via spot-check）**
**受影响文件**：`octopus_mobile/proactive/ProactiveRuleEngine.kt:195`（执行）、`:254`（规则反序列化）、`:235`（内置 `sms_code_copy` 规则）、`octopus_mobile/proactive/NotificationRelayService.kt`（触发源）
**攻击场景**：`executeRule()` 直接 `toolRegistry.executeTool(rule.action.toolName, rule.action.toolParams)`，**绕过 agent、SafetyGate、人工确认**。规则经 Gson 从 MMKV 反序列化，`toolName/toolParams` 完全可控；触发文本来自**不可信源**——任意 App 推送的通知（`onNotification`）、收到的短信（`onSmsReceived`）、屏幕文本（`onScreenChanged`）。即「攻击者发一条匹配某 regex 的通知/短信 → 自动执行任意已注册工具（`send_sms`/`send_intent`/`file_ops`…）」。这是一条无需任何渠道/网络入口、单条推送即可触发的工具执行链，与 R1/R2 同根（汇聚点缺强制矩阵）但触发面更隐蔽。
**附带隐患**：内置规则 `sms_code_copy` 把任意验证码短信自动 `set_clipboard`，本身即验证码外泄面（其他读剪贴板的 App 可取）。
**修复建议**：`ProactiveRuleEngine.executeRule` 走与 R2 相同的强制风险闸门；规则动作工具限定到安全只读白名单；不可信触发源驱动的高危工具必须人工确认；移除/收敛 `sms_code_copy` 自动复制验证码。

### 其余抽查结论（需补审 / 基本干净）
- **`media/CloudDriveManager.kt` + `WebDAVScanner.kt`（部分真实，需补审）**：CD2 密码与 WebDAV basic-auth 凭据明文存 MMKV（secrets 维度只统计了 channel/LLM token，**漏了这两类**）；`WebDAVScanner` `followRedirects(true)` + 配置驱动的 `baseUrl` + 全局 cleartext = 一条未被 `UrlGuard` 覆盖的 SSRF/重定向面，`scanRecursive` 无 host 校验可指向内网。`start()` 的 `cd ... && nohup ... &` 命令受 R7 误杀 filter 影响，提示该路径从未真正执行验证。
- **`octopus_mobile/evolution/EvolutionEngine.kt`（基本干净，残留中低危）**：不改文件/不执行代码，`dryRun` 默认 true；残留风险是 `deepReflect` 把 LLM 输出自动落库为 lesson 并回灌后续 prompt → 可被 prompt-injection 污染持久化记忆（中低危）。
- **`octopus_mobile/nerves/reflex/ReflexRouter.kt`（基本干净）**：regex→tool 映射不直接执行，仍走主链路；规则为代码内置非外部反序列化，注入面小于 ProactiveRuleEngine。
- **`cast/ScreenCastService.kt`、`octopus_mobile/VoiceInput.kt`（干净）**：本地 MediaProjection / SpeechRecognizer，无网络监听、无 token 化镜像端点，无独立提权面。
- **`server/RemoteConsoleGateway.kt` 的 QR/pairing（建议补一眼）**：token 进二维码/配对码的生成与时效未单独展开，应确认是否一次性/可过期。

> **补录后修正总览**：关键风险升至 R1–R12。R12 应与 R1/R2 并列 P0（同属"汇聚点无强制风险矩阵"根因，且触发面最隐蔽）。