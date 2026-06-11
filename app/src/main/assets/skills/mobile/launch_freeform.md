---
name: launch_freeform
description: Launch an app in freeform (popup window) mode, optionally on external display (async casting)
risk: medium
timeout_ms: 15000
parameters:
  - name: package_name
    type: string
    required: true
    description: "App package name, e.g. com.tencent.mm"
  - name: display_id
    type: integer
    required: false
    description: "Display ID. 0=phone screen (default), >0=external display. Use get_window_info to find available displays."
  - name: x
    type: integer
    required: false
    description: "Window X position (pixels), default 100"
  - name: y
    type: integer
    required: false
    description: "Window Y position (pixels), default 200"
  - name: width
    type: integer
    required: false
    description: "Window width (pixels), default 600"
  - name: height
    type: integer
    required: false
    description: "Window height (pixels), default 900"
---

Launch an app in **freeform (popup window) mode**. Supports launching on the phone screen or on an external display (async casting).

**Requirements**: Shizuku must be installed and permission granted.

**Use cases**:
- Run multiple apps simultaneously in separate windows
- **Async casting**: Launch apps on external display while using phone normally
- Agent operates apps on external screen, user uses phone screen

**Example** - Launch on phone screen:
```json
{
  "package_name": "com.tencent.mm",
  "x": 50,
  "y": 100,
  "width": 540,
  "height": 960
}
```

**Example** - Launch on external display (async casting):
```json
{
  "package_name": "com.tencent.mm",
  "display_id": 2,
  "x": 100,
  "y": 100,
  "width": 800,
  "height": 600
}
```

**Notes**:
- Not all apps support freeform mode
- Use `get_window_info` to find available display IDs
- For async casting, the phone must be connected to an external display (USB-C/HDMI/wireless)
- Use `/api/cast/start` endpoint to initialize the presentation window on external display
