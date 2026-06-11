---
name: android.get_installed_apps
description: Get a list of all installed applications on the device. Returns app names, package names, and version info. Useful for checking if an app is installed or finding the right package name.
risk: low
timeout_ms: 15000
parameters: {"type": "object", "properties": {"filter": {"type": "string", "description": "Optional filter string to search app names or package names", "default": ""}, "include_system": {"type": "boolean", "description": "Whether to include system apps in the results", "default": false}}}
---
