---
name: android.set_clipboard
description: Set the Android clipboard content to the specified text. Uses ClipboardManager internally. Useful for preparing text to paste into input fields.
risk: medium
timeout_ms: 15000
parameters: {"type": "object", "properties": {"text": {"type": "string", "description": "Text to set as clipboard content"}}, "required": ["text"]}
---
