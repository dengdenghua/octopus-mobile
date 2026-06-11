---
name: resize_window
description: Resize and reposition a task window using Shizuku
risk: low
timeout_ms: 5000
parameters:
  - name: task_id
    type: integer
    required: false
    description: "Task ID, use -1 for the current top window (default)"
  - name: x
    type: integer
    required: true
    description: "Window left edge X coordinate (pixels)"
  - name: y
    type: integer
    required: true
    description: "Window top edge Y coordinate (pixels)"
  - name: width
    type: integer
    required: true
    description: "Window width (pixels)"
  - name: height
    type: integer
    required: true
    description: "Window height (pixels)"
---

Resize and reposition a window. Works on both freeform popup windows and fullscreen apps.

**Requirements**: Shizuku must be installed and permission granted.

**How to find task_id**:
- Use `get_window_info` to list all running tasks and their IDs
- Use `-1` (default) to target the currently active/top window

**Example** - Move the top window to top-left corner at 540x960:
```json
{
  "x": 0,
  "y": 0,
  "width": 540,
  "height": 960
}
```

**Example** - Resize a specific task:
```json
{
  "task_id": 42,
  "x": 100,
  "y": 200,
  "width": 600,
  "height": 800
}
```
