---
name: android.tap
description: Tap at coordinate (x, y). Use this to click buttons, links, icons. Coordinates come from android.get_screen_info's `bounds` field. Always get_screen_info first to find the right coordinate.
risk: low
timeout_ms: 15000
parameters: {"type": "object", "properties": {"x": {"type": "integer", "description": "X coordinate in pixels"}, "y": {"type": "integer", "description": "Y coordinate in pixels"}, "wait_after": {"type": "integer", "description": "Milliseconds to wait after tap", "default": 500}}, "required": ["x", "y"]}
---
