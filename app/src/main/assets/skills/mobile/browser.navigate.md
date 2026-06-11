---
name: android.browser.navigate
description: Navigate to a URL in the built-in Chromium browser and wait for the page to load. Returns the current URL, page title, and a screenshot reference. The integrated Chromium provides real browser fingerprints for anti-bot evasion.
risk: low
timeout_ms: 30000
parameters: {"type": "object", "properties": {"url": {"type": "string", "description": "URL to navigate to"}, "wait_until": {"type": "string", "description": "When to consider navigation complete", "default": "networkidle"}, "timeout_ms": {"type": "integer", "description": "Navigation timeout in milliseconds", "default": 30000}}, "required": ["url"]}
---
