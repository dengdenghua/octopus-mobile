---
name: android.browser.get_dom
description: Get the current page's DOM tree in structured YAML/JSON format. This is the primary way for LLM to "see" web page content — analogous to android.get_screen_info. Internally filters: scripts, styles, comments, and display:none nodes.
risk: low
timeout_ms: 30000
parameters: {"type": "object", "properties": {"max_depth": {"type": "integer", "description": "Maximum DOM tree depth to traverse", "default": 20}, "include_hidden": {"type": "boolean", "description": "Whether to include hidden (display:none) elements", "default": false}}}
---
