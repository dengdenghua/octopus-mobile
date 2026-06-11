---
name: android.find_and_tap
description: Find a node by text and tap it in one operation. This is the most commonly used composite skill — combines find_text + tap. Searches the accessibility tree for matching text and taps the center of the found node.
risk: low
timeout_ms: 15000
parameters: {"type": "object", "properties": {"text": {"type": "string", "description": "Text to search for (case-insensitive, partial match)"}, "exact_match": {"type": "boolean", "description": "Whether to require exact text match", "default": false}, "index": {"type": "integer", "description": "Which match to tap if multiple found (0 = first)", "default": 0}, "wait_after": {"type": "integer", "description": "Milliseconds to wait after tap", "default": 500}}, "required": ["text"]}
---
