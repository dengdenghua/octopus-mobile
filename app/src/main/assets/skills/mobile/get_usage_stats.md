---
name: android.get_usage_stats
description: Get app usage statistics - which apps were used, foreground time, and last used time. Useful for understanding user habits and app usage patterns. Requires 'Usage Access' permission.
risk: medium
timeout_ms: 30000
parameters: {"type": "object", "properties": {"limit": {"type": "integer", "description": "Max apps to return, sorted by usage time descending (default 15, max 50)", "default": 15}, "days": {"type": "integer", "description": "Days of history to include (default 1, max 7)", "default": 1}}}
---
