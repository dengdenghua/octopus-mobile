---
name: android.detect_dialog
description: Detect system or app dialogs/popups on the current screen. Attempts to identify and close common dialogs (permissions, updates, ads, etc.). Returns the detected dialog type and whether it was successfully closed.
risk: low
timeout_ms: 15000
parameters: {"type": "object", "properties": {"auto_close": {"type": "boolean", "description": "Whether to automatically attempt to close detected dialogs", "default": true}, "close_strategy": {"type": "string", "description": "|", "default": "dismiss"}}}
---
