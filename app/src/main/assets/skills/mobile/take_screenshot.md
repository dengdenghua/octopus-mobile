---
name: android.take_screenshot
description: Take a screenshot of the current screen, returned as base64 PNG. Use this when you need visual information that the accessibility tree cannot provide, such as images, icons, colors, or layout context.
risk: low
timeout_ms: 15000
parameters: {"type": "object", "properties": {"quality": {"type": "integer", "description": "Image quality (1-100), lower means smaller file size", "default": 80}, "scale": {"type": "string", "description": "Scale factor for the screenshot (0.5 = half resolution, reduces size)", "default": 0.5}}}
---
