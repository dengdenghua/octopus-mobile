# Octopus Mobile · Web 前端资产 / Web Frontend Assets

应用内置 Web 资源，由 `WebView` 加载。Loaded by the in-app `WebView`.

---

## 1. 概览 / Overview

四个 HTML 入口 / Four entry points:

- **`index.html`** — 配置页：Bot 渠道（钉钉 / 飞书 / QQ / Discord / Telegram）+ LLM 凭证。/ Config page for bot channels and LLM credentials.
- **`console.html`** — 网页遥控台：左侧手机镜像 + 触控按键，右侧与任务助手对话。/ Live mirroring on the left, task-assistant chat on the right.
- **`debug.html`** — 调试控制台：工具调用、参数面板、执行历史。/ Remote tool calls, parameter panels, execution history.
- **`../pc_remote.html`** — 远程桌面（位于 `assets/` 根目录）：WebRTC 投屏 + 双向控制。/ WebRTC-powered remote desktop at `assets/` root.

---

## 2. 目录结构 / Directory Structure

本目录 + `assets/` 根目录共同构成 4 个页面：/ This folder plus the `assets/` root host the four pages.

- `tokens.css` — 全局设计令牌 / Global design tokens
- `style.css` / `console-style.css` / `debug-style.css` — `index` / `console` / `debug` 三页样式 / Per-page styles for the three pages in this folder
- `../pc_remote-style.css` — 远程桌面样式（位于 `assets/` 根目录） / Remote desktop styles
- `app.js` / `console-app.js` / `debug-app.js` — 三页业务脚本 / Per-page logic
- `../pc_remote-app.js` — 远程桌面业务脚本 / Remote desktop logic

每个 `*-style.css` 都依赖 `tokens.css`，**必须先 link**。
Every `*-style.css` depends on `tokens.css` — **link it first**.

---

## 3. 设计系统 tokens / Design Tokens

`tokens.css` 在 `:root` 声明变量，并在 `@media (prefers-color-scheme: dark)` 下自动覆盖暗色。
Declared on `:root`; dark overrides are automatic.

- **品牌色 / Brand**：`--oct-brand` / `--oct-brand-strong` / `--oct-brand-soft` / `--oct-accent`
- **语义色 / Semantic**：`--oct-success` / `--oct-warning` / `--oct-danger` / `--oct-info`
- **中性色 / Neutrals**：`--oct-bg`、`--oct-bg-elevated`、`--oct-surface`、`--oct-surface-2`、`--oct-border` / `--oct-border-strong`、`--oct-text` / `--oct-text-2` / `--oct-text-3`、`--oct-overlay`
- **字号 / Type**（6 档）：`--oct-fs-xs / sm / base / md / lg / xl`
- **间距 / Spacing**（4px 基线）：`--oct-sp-1` … `--oct-sp-8`
- **圆角 / Radius**（4 档）：`--oct-radius-sm / md / lg / pill`
- **阴影 / Shadow**（3 档）：`--oct-shadow-sm / md / lg`
- **动效 / Motion**：`--oct-transition-colors / -transform / -shadow / -all`，配 `--oct-ease`、`--oct-dur-fast / base / slow`
- 附加 / Also：字重 `--oct-fw-*`、层级 `--oct-z-*`、字体 `--oct-font-ui / -mono`

---

## 4. 样式开发规范 / Style Guidelines

- **链接顺序 / Order**：`tokens.css` 必须先于 `*-style.css`，否则变量取不到值。/ `tokens.css` must precede any `*-style.css`.
- **零硬编码 / No hard-coding**：颜色、字号、间距、圆角、阴影、动效一律 `var(--oct-*)`，禁止裸值。/ Always use `var(--oct-*)`.
- **暗色 / Dark mode**：跟随系统即可，**不要**在页面 CSS 里写 `prefers-color-scheme: dark`。/ Dark overrides live in `tokens.css`; never add dark blocks in page styles.
- **SVG 图标 / Icons**：统一 `viewBox="0 0 24 24"`、`fill="none"`、`stroke="currentColor"`、`stroke-width="2"`、`stroke-linecap/linejoin="round"`。/ Standardize on the 24×24 stroke-only spec.
- **CSP**：`script-src 'self'`，**禁止**内联 `<script>` 与 `onclick=` 等 handler 属性。/ Strict CSP forbids inline `<script>` and `on*` handler attributes.
- **事件绑定 / Events**：统一用 `addEventListener`，不写内联处理器。/ Bind events with `addEventListener` only.

---

## 5. i18n

- `index.html` 内置 `i18n.zh / en / ja`（在 `app.js` 中），按 `navigator.language` 切换。/ Trilingual dictionaries; auto-switches by `navigator.language`.
- `console.html` / `debug.html` / `pc_remote.html` 当前为单语 `zh-CN`。/ The other three pages are `zh-CN` only.
- 新增多语种时优先接入 `index.html` 的 `t(key)` + `[data-i18n]` 框架，避免散落字典。/ Reuse the `index.html` i18n framework for new locales.

---

## 6. JS 依赖 / JS Dependencies

文件名 / 路径由原生层硬编码加载，**不要改名**。
Hard-coded by the native loader — **do not rename**.

改样式 / DOM 前先在对应 JS 中确认以下关键 id/class 仍被引用：
Grep the matching JS before refactoring markup:

- `index.html` ⇄ `app.js`：`#saveBtn`、`.card`、`.nav-link`、`.loading`、`.toast`、`[data-i18n]`
- `console.html` ⇄ `console-app.js`：`#screen`、`#stage`、`#chat`、`#ta`、`#send`、`#st`、`#latency`、`.ltbar-btn`、`[data-action]`
- `debug.html` ⇄ `debug-app.js`：`#toolList`、`#toolPanel`、`#paramFields`、`#execBtn`、`#resultPanel`、`#historyList`、`[data-tool]`

---

## 7. 添加新页面 / Adding a New Page

1. 建 `xxx.html` / `xxx-style.css` / `xxx-app.js`，**先 link `tokens.css`**。/ Create the three files; **link `tokens.css` first**.
2. 复用现有 CSP `<meta>`；需要 WS 时把 `ws:` / `wss:` 加进 `connect-src`。/ Reuse the CSP `<meta>`; add `ws:` / `wss:` to `connect-src` only if needed.
3. `<html lang>` 填实际语种；多语沿用 `i18n` + `data-i18n` 模式。/ Set `<html lang>`; reuse the i18n pattern for multilingual UI.
4. 视觉一律 `var(--oct-*)`；SVG 遵循 §4 规范；事件用 `addEventListener`。/ Route visuals through tokens; follow icon and event-binding rules.
5. 跨页跳转用相对路径，并在原生路由中注册入口。/ Use relative links; register the route natively.
