---
name: android.swipe
description: Swipe from (x1, y1) to (x2, y2) with specified duration. Used for scrolling, swiping between pages, pulling down notifications, etc.
risk: low
timeout_ms: 15000
parameters: {"type": "object", "properties": {"x1": {"type": "integer", "description": "Start X coordinate in pixels"}, "y1": {"type": "integer", "description": "Start Y coordinate in pixels"}, "x2": {"type": "integer", "description": "End X coordinate in pixels"}, "y2": {"type": "integer", "description": "End Y coordinate in pixels"}, "duration_ms": {"type": "integer", "description": "Duration of swipe in milliseconds", "default": 300}, "wait_after": {"type": "integer", "description": "Milliseconds to wait after swipe", "default": 500}}, "required": ["x1", "y1", "x2", "y2"]}
---
