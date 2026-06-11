---
name: android.open_app
description: Open an application by app name or package name. Supports Chinese/English app names with fuzzy matching. Has built-in alias mapping for common Chinese apps.
risk: medium
timeout_ms: 30000
parameters: {"type": "object", "properties": {"app_name": {"type": "string", "description": "|"}, "package_name": {"type": "string", "description": "Exact package name, e.g. \"com.tencent.mm\""}, "wait_after": {"type": "integer", "description": "Milliseconds to wait after launching the app", "default": 2000}}}
---
