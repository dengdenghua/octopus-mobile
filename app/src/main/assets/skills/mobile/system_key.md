---
name: android.system_key
description: Simulate system key presses: Home, Back, Recents, Power, Volume Up/Down. Uses adb shell input keyevent internally.
risk: medium
timeout_ms: 15000
parameters: {"type": "object", "properties": {"key": {"type": "string", "description": "|"}, "wait_after": {"type": "integer", "description": "Milliseconds to wait after key press", "default": 500}}, "required": ["key"]}
---
