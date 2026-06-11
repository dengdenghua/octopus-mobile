---
name: android.scroll_to_find
description: Scroll the current screen up/down until the target text/element appears. Combines get_screen_info + swipe in a loop. Returns when found or max scrolls reached. Anti-pattern: if 3 consecutive scrolls find nothing, suggests the target may not be on this screen.
risk: low
timeout_ms: 15000
parameters: {"type": "object", "properties": {"target": {"type": "string", "description": "Text to find (or desc/class with prefix)"}, "direction": {"type": "string", "description": "Scroll direction", "default": "down"}, "max_scrolls": {"type": "integer", "description": "Maximum number of scroll attempts", "default": 10}, "scroll_duration_ms": {"type": "integer", "description": "Duration of each scroll swipe in milliseconds", "default": 300}}, "required": ["target"]}
---
