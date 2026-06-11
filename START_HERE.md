# octopus-mobile · 5 分钟上手 + 100 行扩展

> 给"想 5 分钟跑通 / 想 100 行加新工具"的人看。

## 快速链接

| 文档 | 适合谁 | 阅读时间 |
|------|--------|----------|
| [DEMO.md](DEMO.md) | 5 分钟跑通 | 5 分钟 |
| [EXTENDING.md](EXTENDING.md) | 100 行加新工具 | 5 分钟 |
| [examples/tentacle_hello_world_demo.py](examples/tentacle_hello_world_demo.py) | 协议层 demo | 1 分钟跑 |
| [quickstart.sh](quickstart.sh) | 一键安装+测试 | 30 秒 |
| [README.md](README.md) | 全貌 | 10 分钟 |
| [README_CN.md](README_CN.md) | 中文全貌 | 10 分钟 |

## 一句话

```bash
# 编译 + 装到手机 + 跑测试 + 跑 demo
./quickstart.sh all
```

## Hello World 实际示例

我们已经在 [HelloWorldTools.kt](app/src/main/java/com/apk/claw/android/tool/impl/HelloWorldTools.kt) 实现了 3 个示例工具：

1. **hello_world** —— 打个招呼
2. **current_time** —— 报时间
3. **device_info** —— 报设备信息

每个 ~50 行，加注册 1 行，总共 **< 100 行** 演示了如何加新工具。

## 测试

```bash
./gradlew :app:testDebugUnitTest --tests "*HelloWorldToolsTest*"
```

覆盖：6 个测试用例，验证 3 个工具的输入/输出/异常路径。

## 下一步

- 加更多工具 → 参考 [EXTENDING.md](EXTENDING.md)
- 自定义提示词 → 设置 → 提示词 → 编辑
- 部署到云 → 参考 [octopus-agent/docs/deployment.md](../octopus-agent/docs/deployment.md)
- 写文档/改 bug → 开 issue
