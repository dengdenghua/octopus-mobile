---
name: android.input_text
description: Input text into the currently focused input field. Make sure to tap on an input field first to focus it before calling this. Uses adb shell input text internally.
risk: medium
timeout_ms: 15000
parameters: {"type": "object", "properties": {"text": {"type": "string", "description": "Text to input"}, "clear_first": {"type": "boolean", "description": "Whether to clear existing text before input", "default": true}, "wait_after": {"type": "integer", "description": "Milliseconds to wait after input", "default": 500}}, "required": ["text"]}
---
