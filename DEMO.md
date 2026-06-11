# Octopus Mobile · 5 分钟上手 DEMO

> **目标**：5 分钟内看到"AI 控制 Android 设备"的完整链路。
> **场景**：你在钉钉发条消息，AI 自动打开淘宝搜了 iPhone 15。

---

## 0. 前置条件（30 秒检查）

```bash
# ✅ JDK 17
java -version
# ✅ Android SDK (build-tools 34+, platforms android-34)
echo $ANDROID_HOME
# ✅ Python 3.11+ (要跑母体)
python --version
# ✅ 一台 Android 手机（开发者模式 + USB 调试）
adb devices
# ✅ 一个 LLM API Key（OpenAI 或 Anthropic）
```

如果缺任何一项，先去 [TROUBLESHOOTING.md](TROUBLESHOOTING.md) 查。

---

## 1. 安装手机端（1 分钟）

```bash
cd ../octopus-mobile
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

打开 App → 设置（右上角）→ 填入：
- **LLM API Key**: `sk-xxx`（你的 OpenAI/Anthropic key）
- **LLM Base URL**: `https://api.openai.com/v1`（或 `https://api.anthropic.com/v1`）
- **Model**: `gpt-4o-mini`（或 `claude-3-5-sonnet-latest`）
- **钉钉机器人 Token**（可选，留空也能跑 LOCAL_ONLY 模式）

> **关键步骤**：开启**无障碍服务**
> 设置 → 无障碍 → Octopus Mobile → 开启

---

## 2. 启动电脑端母体（30 秒）

```bash
cd ../octopus-agent
pip install -e ".[dev,serve,anthropic]"   # 或 openai
cp .env.example .env
# 编辑 .env 填入 ANTHROPIC_API_KEY / OPENAI_API_KEY
python -m runtime quickstart --non-interactive --serve
```

看到 `Uvicorn running on http://0.0.0.0:8000` 即可。

---

## 3. 启动 WebSocket Server（30 秒）

```bash
# 另一个终端
cd ../octopus-agent
python -m runtime tentacle serve --host 0.0.0.0 --port 8765
```

看到 `WebSocket server listening on 0.0.0.0:8765` 即可。

---

## 4. 配对手机到母体（1 分钟）

回到手机 App：
- **RPC URL**: `ws://<你电脑的IP>:8765`（如 `ws://192.168.1.100:8765`）
- **Tentacle ID**: `android-myphone`（自动生成也行）
- **Auth Token**: `demo-token`（母体端如果配了 `OCTOPUS_TENTACLE_TOKEN=demo-token`）

点 **连接** 按钮。

**看母体端日志**：
```
[INFO] Tentacle connected: android-myphone (Xiaomi Mi 14)
[INFO] Device hello received: 30 capabilities registered
```

---

## 5. 发第一条消息（30 秒）

手机 App 主页 → 选 **钉钉** 或 **直接控制台** → 输入：

```
打开淘宝，搜索 iPhone 15，截图发给我
```

**会发生什么**（按时间顺序）：

```
1. 消息 → ChannelManager (LOCAL_ONLY 或 RPC_ONLY 模式)
2. → TaskOrchestrator 拿锁
3. → BrainModeSelector 决策（健康检查）
4. → DefaultAgentService 开始
5. 5-10s 后: get_screen_info → "屏幕显示桌面"
6. → LLM 决定: "先 open_app com.taobao.taobao"
7. → open_app Tool → 无障碍服务启动淘宝
8. → wait 2000ms
9. → get_screen_info → "看到淘宝首页"
10. → LLM 决定: "点搜索框 → 输入 iPhone 15"
11. → 连续几个 Tool 调用
12. → take_screenshot → 上传到 LLM
13. → 找到 iPhone 15 商品列表
14. → 任务完成
15. → 截图发回钉钉
```

**全程 30-90 秒**（取决于 LLM 速度）。

---

## 6. 看 PC 端实时面板（可选）

浏览器打开 `http://<你电脑的IP>:8000/ui/`

你将看到：
- Tentacle 状态（在线/忙碌/电量）
- 当前任务进度
- LLM 思考链
- 工具调用历史
- 屏幕流（5s 延迟）

---

## 常见问题

**Q1: 手机显示"无障碍服务未启用"**
→ 必须开启。设置路径：`设置 → 辅助功能 → 无障碍 → Octopus Mobile`

**Q2: 母体收不到手机连接**
→ 检查防火墙：`sudo ufw allow 8765/tcp`（Linux）/ 允许 Windows Defender 通过

**Q3: LLM 不响应**
→ 检查 API Key 是否有效；先在终端 `curl` 测试

**Q4: 任务一直"进行中"不结束**
→ 看 App 日志（`adb logcat -s OctopusMobile`），可能是死循环被检测但未退出

**Q5: 想不依赖 LLM 跑 demo**
→ 用 LOCAL_ONLY 模式（启动时不连母体），App 自己会调轻量 LLM

---

## 下一步

- [ ] 加一个新工具 → 参考 [EXTENDING.md](EXTENDING.md)（**100 行能搞定**）
- [ ] 自定义提示词 → 设置 → 提示词 → 编辑
- [ ] 部署到云 → 参考 [DEPLOYMENT.md](DEPLOYMENT.md)
- [ ] 看架构 → 参考 [octopus-agent/docs/architecture.md](../octopus-agent/docs/architecture.md)
- [ ] 看协议 → 参考 [PROTOCOL.md](docs/mobile/protocol.md)

---

## 60 秒精简版（如果时间紧）

```bash
# 1. 装手机
./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk

# 2. 启动母体
cd ../octopus-agent && python -m runtime quickstart --non-interactive --serve &

# 3. 启动 WS server
python -m runtime tentacle serve --host 0.0.0.0 --port 8765 &

# 4. 开 App, 配 LLM, 配 RPC URL

# 5. 发消息："打开淘宝搜 iPhone 15"
```

搞定。
