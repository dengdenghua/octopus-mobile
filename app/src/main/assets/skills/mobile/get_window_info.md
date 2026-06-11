---
name: get_window_info
description: Get information about all running task windows
risk: low
timeout_ms: 5000
parameters: []
---

Get information about all running task windows including screen size, task IDs, and package names.

**Requirements**: Shizuku must be installed and permission granted.

**Use cases**:
- Find task IDs for use with `resize_window`
- See what apps are currently running
- Check the screen resolution for calculating window positions

**Example**:
```json
{}
```

**Output format**:
```
Screen: 1080x2400

Running tasks:
taskId=42 com.tencent.mm
taskId=43 com.taobao.taobao
```

Use the `taskId` values with `resize_window` to adjust specific windows.
