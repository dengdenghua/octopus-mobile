---
name: android.find_text
description: Find all nodes containing the target text on the current screen. Returns a list of matching nodes with their ref, bounds, and full text. Useful for locating specific content or verifying text presence.
risk: low
timeout_ms: 15000
parameters: {"type": "object", "properties": {"target": {"type": "string", "description": "Text to search for (case-insensitive, partial match)"}, "include_desc": {"type": "boolean", "description": "Also search in content description field", "default": true}}, "required": ["target"]}
---
