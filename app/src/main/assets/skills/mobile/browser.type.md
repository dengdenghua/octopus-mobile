---
name: android.browser.type
description: Type text into the currently focused browser element. Optionally clear existing text before typing and press Enter after.
risk: low
timeout_ms: 30000
parameters: {"type": "object", "properties": {"text": {"type": "string", "description": "Text to type into the focused element"}, "clear_first": {"type": "boolean", "description": "Whether to clear existing text before typing", "default": true}, "press_enter": {"type": "boolean", "description": "Whether to press Enter after typing", "default": false}}, "required": ["text"]}
---
