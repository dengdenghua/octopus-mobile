---
name: android.browser.screenshot
description: Take a screenshot of the current browser page as PNG base64. Supports full-page screenshots that capture content below the fold.
risk: low
timeout_ms: 30000
parameters: {"type": "object", "properties": {"full_page": {"type": "boolean", "description": "Whether to capture the entire page including scrolled content", "default": false}, "quality": {"type": "integer", "description": "Image quality (1-100)", "default": 80}}}
---
