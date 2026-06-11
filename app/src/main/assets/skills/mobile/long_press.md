---
name: android.long_press
description: Long press at coordinate (x, y) for specified duration. Used for context menus, selecting items, dragging, etc.
risk: low
timeout_ms: 15000
parameters: {"type": "object", "properties": {"x": {"type": "integer", "description": "X coordinate in pixels"}, "y": {"type": "integer", "description": "Y coordinate in pixels"}, "duration_ms": {"type": "integer", "description": "Duration of long press in milliseconds", "default": 1000}, "wait_after": {"type": "integer", "description": "Milliseconds to wait after long press", "default": 500}}, "required": ["x", "y"]}
---
