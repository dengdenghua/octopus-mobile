# Octopus 插件生态 —— 架构与分期落地蓝图

> 目标:让 octopus 拥有**自己的生态** —— 既有"给 agent 加能力的插件",也有"自带界面的迷你 app(你的小程序)"。
> 一句话定位:**分发与能力已是你的(registry + 工具/无障碍/WebView/计费),生态 = 用你的分发把"单元"喂给你的能力。**

---

## 0. 四条核心原则

1. **分发和能力是你的,"单元格式"才是产品。**
   registry(`api.octoapk.com`)= 发现 / 签名 / 分发 / 计费;能力 = ToolRegistry + 无障碍 + WebView + 积分。生态就是把"单元"接进这两端。
2. **一份 manifest,多种 type。** 所有单元统一成一个插件清单 + `type` 字段,复用 registry 已有的 `mode(inject|tool) × kind(data|code)`。
3. **最小权限 + 签名 + 用户确认是脊梁,不是补丁。** 单元会碰**登录态页面**、能**驱动手机** → 一个恶意单元就是用户设备上的 RCE。结合安全审计定位(R12 闸门、验证码外泄面),权限模型必须先行。
4. **不跑任意原生代码。** "可执行" = **声明式**(HTTP 配方 / 串现有工具)或**受控 WebView**(H5 + 权限网关桥)。永不加载第三方 `.dex/.so`(安全 + 商店政策双红线)。

---

## 1. 单元分类:4 种 type,一份 manifest

一个**插件** = `manifest.json` + 资产,按 `type` 分四类:

| type | mode/kind | 是什么 | 运行时 | UI | 现状 |
|---|---|---|---|---|---|
| `knowledge` | inject/data | markdown 指令 → 喂 agent 上下文 | `RegistrySkillStore.knowledgeBlock` | 无 | ✅ 已有 |
| `browser-script` | inject/code | 内容脚本 JS + 域名匹配 + 拦截规则 | `BrowserPluginHost` | 无(跑在页面) | ✅ 运行时已建 |
| `tool` | tool/code | 声明式 agent 工具(HTTP 配方 / 工具串联) | `DeclarativeToolEngine`(待建) | 无 | ⬜ 待建 |
| `mini-app` | app/code | H5 + `octopus.*` 桥,自带界面("你的小程序") | `MiniAppHost`(待建) | 全屏 | ⬜ 待建 |

> **插件 vs 小程序** = 同一谱系两端:插件让 **agent** 多能力;mini-app 是**自带 UI 的迷你 app**,用户/agent 打开它。

---

## 2. 统一 manifest schema

```jsonc
{
  "id": "weather-pro",
  "type": "tool",                  // knowledge | browser-script | tool | mini-app
  "name": "天气 Pro",
  "version": "1.2.0",
  "description": "...",
  "author": "octo-dev",
  "platforms": ["mobile"],

  "permissions": {                 // 权限声明,默认 deny,最小授予
    "hosts": ["api.weather.com"],  // 能访问的域名(browser-script 注入域 / tool HTTP 目标)
    "tools": ["http.get"],         // 能调用的内置工具白名单
    "device": [],                  // ["automate","screen","sms",...] 敏感,装机需用户确认
    "pay": false                   // 能否走积分/计费
  },

  "entry": {                       // 按 type 不同
    // knowledge:      { "body": "body.md" }
    // browser-script: { "js": "content.js", "hostPattern": "weather\\.com", "blockRules": ["..."] }
    // tool:           { "toolName": "get_weather", "params": [...], "recipe": { ... } }
    // mini-app:       { "page": "index.html" }
  },

  "content": { "checksum": "sha256:..." }   // 资产签名(registry 已有 sha256 校验)
}
```

---

## 3. 权限模型(脊梁)

- **声明**:每个单元在 manifest 里声明所需权限。
- **签名**:registry 对 manifest + 资产签名(现有 sha256;后续加作者签名链)。
- **装机确认**:敏感能力(`device.*` 自动化 / `sms` / `pay` / 在登录态域名注入)**必须用户显式授权**,普通能力静默。
- **运行时网关 `PermissionGate`**:每一次桥调用 / 工具调用 / 注入都过网关,**默认拒绝**,只放行已授予项。
- **敏感域 denylist**:银行 / 支付 / 验证码页**一律禁注入 browser-script**(对接审计历史)。
- **审计日志**:插件的每个敏感动作落日志(对接 R12 主动规则引擎)。

---

## 4. `octopus.*` 桥(tool + mini-app 共用)

一个**权限网关化**的 JS↔host 桥,把能力按 manifest 授权暴露给单元:

```js
octopus.callTool(name, args)   // → ToolRegistry,受 permissions.tools 限制
octopus.http(req)              // → OkHttp,受 permissions.hosts 限制(可经你服务端代理)
octopus.pay(order)             // → 你的积分/计费,受 permissions.pay + 用户确认
octopus.device.automate(...)   // → 无障碍/Shizuku,敏感,需授权 + 用户确认
octopus.device.screen()        // → 读屏,敏感
octopus.ui.*                   // mini-app 专用:导航 / toast / 关闭
```

实现:mini-app 用 `addJavascriptInterface`,但**每个插件一个权限上下文**,桥内部先过 `PermissionGate` 再执行。**绝不裸暴露全量桥。**

---

## 5. 分发与生命周期(复用 registry)

- registry 扩展 `?type=plugin`(现为 `?type=skill`),manifest 带 `type`。
- 安装:下载 → **checksum 校验(已有)** → 落 `filesDir/registry/plugins/<id>/` → 注册进对应运行时。
- 启停 / 卸载:沿用 `RegistrySkillStore` 模式(已有)。
- 统一 `PluginManager`:启动 / 变更时加载已装插件,按 type 派发:
  - `knowledge` → `knowledgeBlock`
  - `browser-script` → `BrowserPluginHost.setPlugins`
  - `tool` → `DeclarativeToolEngine.register` → `ToolRegistry`
  - `mini-app` → `MiniAppRegistry`(供"小程序"宫格启动)

---

## 6. 要新建的组件

| 组件 | 职责 | 依赖现有 |
|---|---|---|
| `PluginManifest` (DTO) | 统一清单解析 | Gson |
| `PluginManager` | 安装/启停/按 type 派发 | RegistryClient / RegistrySkillStore |
| `PermissionGate` | 运行时权限网关 + 用户确认 | — |
| `DeclarativeToolEngine` | HTTP 配方 / 工具串联 → 注册成 agent 工具 | ToolRegistry / OkHttp |
| `OctopusBridge` | `octopus.*` 受控桥 | ToolRegistry / 计费 / 无障碍 |
| `MiniAppHost` + `MiniAppActivity` | WebView 跑 H5 + 挂桥 | SystemWebViewEngine / BrowserPluginHost |
| `MiniAppRegistry` + 宫格 UI | 小程序列表/启动 | Compose |

`BrowserPluginHost`(已建)、`SystemWebViewEngine`(已建 stealth/注入/拦截)直接复用。

---

## 7. 分期落地(从便宜到贵)

- **Stage 0 ✅**:knowledge 技能(已上线)。
- **Stage 1 — 脊梁(先做)**:统一 `PluginManifest` + `PermissionGate` + `PluginManager` + registry `type` 接线 + 签名/校验。**一次投入,4 类共用。**
- **Stage 2 — browser-script 接线**:registry → `BrowserPluginHost`(运行时已建,最便宜的能力收益)。
- **Stage 3 — 声明式 tool 插件**:manifest HTTP 配方 / 工具串联 → `DeclarativeToolEngine` → ToolRegistry。强大且零沙箱风险。
- **Stage 4 — mini-app 宿主("你的小程序")**:`MiniAppHost`(WebView + `octopus.*` 网关桥)+ 小程序宫格 + 桥权限网关。
- **Stage 5 — 开发者套件 + 门户**:manifest 规范 + 本地测试器 + `api.octoapk.com` 提交/签名流 → 第三方开发者入场。**这才是生态飞轮。**

---

## 8. 安全必备(横切,任何 type 都要)

1. 最小权限,默认 deny。
2. registry 签名 + checksum(扩展到作者签名)。
3. 注入侧敏感域 denylist(银行/支付/验证码)。
4. `device.*` / `pay` / 敏感能力**用户确认**。
5. 不加载任意原生代码 —— 声明式或受控 WebView。
6. 插件动作审计日志(对接 R12)。

---

## 9. 已有 vs 待建 一图

```
已有:  registry(发现/签名/分发/计费)  ToolRegistry  无障碍  SystemWebViewEngine(stealth/注入/拦截)  BrowserPluginHost  RegistrySkillStore
待建:  PluginManifest → PluginManager → PermissionGate           ← Stage 1 脊梁
        DeclarativeToolEngine                                     ← Stage 3
        OctopusBridge + MiniAppHost + MiniAppRegistry             ← Stage 4
        开发者门户 / 提交签名流(服务端)                            ← Stage 5
```
