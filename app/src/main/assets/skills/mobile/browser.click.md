---
name: android.browser.click
description: Click an element in the browser by CSS selector or ref from get_dom. Supports both selector-based and ref-based clicking for flexibility.
risk: low
timeout_ms: 30000
parameters: {"type": "object", "properties": {"ref": {"type": "string", "description": "Element ref from get_dom result (e.g., \"b1\")"}, "selector": {"type": "string", "description": "CSS selector (e.g., \".buy-btn\" or \"#submit\")"}, "wait_after": {"type": "integer", "description": "Milliseconds to wait after click", "default": 500}}}
---
