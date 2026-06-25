# EXTENDING · 100 行加新工具

> **目标**：100 行 Kotlin 代码，加一个能在 Agent 流程里调用的新工具。
> **示例**：做一个 `current_time` 工具，让 LLM 知道"现在几点"。

---

## 1. 复制模板（30 秒）

```bash
cd app/src/main/java/com/apk/claw/android/tool/impl
cp WaitTool.java CurrentTimeTool.java
```

---

## 2. 改 4 个方法（5 分钟）

打开 `CurrentTimeTool.java`，把以下 4 个方法改了就行：

```java
package com.apk.claw.android.tool.impl;

import com.apk.claw.android.R;
import com.apk.claw.android.ClawApplication;
import com.apk.claw.android.tool.BaseTool;
import com.apk.claw.android.tool.ToolParameter;
import com.apk.claw.android.tool.ToolResult;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class CurrentTimeTool extends BaseTool {

    @Override
    public String getName() {
        return "current_time";  // ← 工具 ID（LLM 用这个调你）
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_current_time);
    }

    @Override
    public String getDescriptionEN() {
        return "Get the current device time. Returns ISO-8601 format. " +
               "Use this when the task depends on 'now' or 'today' (e.g., 'what's today's date').";
    }

    @Override
    public String getDescriptionCN() {
        return "获取设备当前时间，ISO-8601 格式。当任务依赖"现在"或"今天"时使用（如'今天几号'）。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        // 可选参数：format，可不传
        return Collections.singletonList(
                new ToolParameter("format", "string",
                        "Output format: 'iso' (default) | 'date' | 'time' | 'timestamp'", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String format = getStringParam(params, "format", "iso");
        long now = System.currentTimeMillis();
        String result;
        switch (format) {
            case "date":
                result = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date(now));
                break;
            case "time":
                result = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(now));
                break;
            case "timestamp":
                result = String.valueOf(now);
                break;
            case "iso":
            default:
                result = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault()).format(new Date(now));
                break;
        }
        return ToolResult.success("Current time: " + result);
    }
}
```

**统计**：~50 行 + 模板复制，总 **~70 行**。

---

## 3. 注册到 ToolRegistry（2 分钟）

打开 [ToolRegistry.kt](app/src/main/java/com/apk/claw/android/tool/ToolRegistry.kt)，找到 `registerCommonTools()` 方法，加一行：

```kotlin
private fun registerCommonTools() {
    register(GetScreenInfoTool())
    register(FindNodeInfoTool())
    register(TakeScreenshotTool())
    register(TapTool())
    // ... 原有工具
    register(CurrentTimeTool())  // ← 加这行
}
```

---

## 4. 加本地化字符串（30 秒）

打开 `app/src/main/res/values/strings.xml`，加：

```xml
<string name="tool_name_current_time">当前时间</string>
```

---

## 5. 重新编译 + 跑（2 分钟）

```bash
./gradlew :app:installDebug
adb shell am start -n com.octopus.mobile/.ui.MainActivity
```

---

## 6. 跑测试（30 秒）

新建 `app/src/test/java/com/apk/claw/android/tool/impl/CurrentTimeToolTest.kt`：

```kotlin
package com.apk.claw.android.tool.impl

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CurrentTimeToolTest {

    @Test
    fun `name is current_time`() {
        assertEquals("current_time", CurrentTimeTool().name)
    }

    @Test
    fun `iso format returns ISO-8601`() {
        val r = CurrentTimeTool().execute(mapOf("format" to "iso"))
        assertTrue(r.isSuccess)
        // ISO-8601 形如 2026-06-08T15:30:00+08:00
        assertTrue((r.data ?: "").contains("Current time: 20"))
    }

    @Test
    fun `date format returns yyyy-MM-dd`() {
        val r = CurrentTimeTool().execute(mapOf("format" to "date"))
        assertTrue(r.isSuccess)
        assertTrue((r.data ?: "").matches(Regex(".*\\d{4}-\\d{2}-\\d{2}.*")))
    }

    @Test
    fun `default format is iso`() {
        val r = CurrentTimeTool().execute(emptyMap())
        assertTrue(r.isSuccess)
        // 包含 'T' 是 ISO 格式特征
        assertTrue((r.data ?: "").contains("T"))
    }

    @Test
    fun `timestamp format returns epoch ms`() {
        val r = CurrentTimeTool().execute(mapOf("format" to "timestamp"))
        assertTrue(r.isSuccess)
        val ts = (r.data ?: "").removePrefix("Current time: ").toLongOrNull()
        assertNotNull(ts)
        assertTrue("timestamp should be recent", (System.currentTimeMillis() - ts!!) < 10_000)
    }
}
```

跑：
```bash
./gradlew :app:testDebugUnitTest --tests "*CurrentTimeToolTest*"
```

---

## 7. 在 SKILL.md 中导出（可选，1 分钟）

你的工具会自动出现在 [SkillExporter.kt](app/src/main/java/com/apk/claw/android/octopus_mobile/SkillExporter.kt) 的导出列表里——**无需任何改动**。

启动 App 时，会自动推送到母体，LLM 就能看到新工具了。

---

## 8. 进阶：加权限（如果需要）

如果工具要发短信、读联系人等敏感操作：

1. AndroidManifest.xml 加权限
2. [SafetyGate.kt](app/src/main/java/com/apk/claw/android/octopus_mobile/SafetyGate.kt) 加规则
3. 在 `execute()` 里检查

参考 [SendSmsTool.java](app/src/main/java/com/apk/claw/android/tool/impl/SendSmsTool.java)。

---

## 完整清单

| 步骤 | 行数 | 难度 |
|------|------|------|
| 1. 复制模板 | 0 | ⭐ |
| 2. 改 4 个方法 | ~50 | ⭐ |
| 3. 注册到 Registry | 1 | ⭐ |
| 4. 加 strings.xml | 1 | ⭐ |
| 5. 编译 + 跑 | 0 | ⭐ |
| 6. 写测试 | ~30 | ⭐⭐ |
| **总计** | **~80 行** | **5 分钟** |

---

## 真正难的扩展

| 场景 | 工作量 |
|------|--------|
| **加新工具**（无外部依赖） | 80 行 + 5 分钟 |
| **加新工具**（需权限） | +30 行 + 5 分钟 |
| **加新工具**（需异步） | +50 行 + 10 分钟 |
| **加新浏览器引擎** | 200+ 行 + 1 天 |
| **加新渠道（钉钉/微信/...）** | 500+ 行 + 1 周 |
| **加新 LLM 适配器** | 100 行 + 1 小时 |

---

## 常见问题

**Q1: 工具加了但 LLM 不调用**
→ 检查 `description` 是否清楚。LLM 决定调不调你，主要看 description 写得好不好。

**Q2: 工具调用失败**
→ 看 App 日志：`adb logcat -s OctopusMobile`，或 ToolRegistry 里的 `error` 字段。

**Q3: 想让 LLM "必调用"这个工具**
→ 把 `risk: high` 改成必选，或在 system prompt 里强制要求。

**Q4: 想加异步回调**
→ 继承 `BaseTool.executeAsync()` 而不是 `execute()`，用 `CompletableFuture` 或 `suspend`。

**Q5: 工具需要"上下文"（当前 Activity 等）**
→ 在 `execute()` 里用 `ClawApplication.getInstance()` 或 `ClawAccessibilityService.getInstance()`。

---

## 一句话

> **加新工具就像写一个 REST endpoint**：
> 1. 写 handler（70 行）
> 2. 注册路由（1 行）
> 3. 写测试（30 行）
> 4. Done

比 LangChain、AutoGen 都简单。
