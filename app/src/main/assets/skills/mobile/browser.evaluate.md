---
name: android.browser.evaluate
description: Execute arbitrary JavaScript code in the browser and return the result. This gives the AI full power to run any frontend code. WARNING: May modify page state, bypass frontend validation, or read sensitive data.
risk: medium
timeout_ms: 30000
parameters: {"type": "object", "properties": {"expression": {"type": "string", "description": "|"}, "\"document.title\"": {"type": "string"}, "\"Array.from(document.querySelectorAll('.price')).map(e => e.textContent)\"": {"type": "string"}, "\"localStorage.getItem('token')\"": {"type": "string"}, "await_promise": {"type": "boolean", "description": "Whether to await the returned Promise before returning result", "default": false}}, "required": ["expression"]}
---
