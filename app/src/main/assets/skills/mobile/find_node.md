---
name: android.find_node
description: Find accessibility nodes by text, content description, or class name. Searches the current screen's accessibility tree and returns matching nodes with their ref, bounds, text, and other properties.
risk: low
timeout_ms: 15000
parameters: {"type": "object", "properties": {"text": {"type": "string", "description": "Match node text (case-insensitive, partial match)"}, "desc": {"type": "string", "description": "Match node content description (case-insensitive, partial match)"}, "class_name": {"type": "string", "description": "Match node class name (e.g., \"Button\", \"TextView\")"}, "clickable_only": {"type": "boolean", "description": "Only return clickable nodes", "default": false}, "exact_match": {"type": "boolean", "description": "Require exact text match instead of partial", "default": false}}}
---
