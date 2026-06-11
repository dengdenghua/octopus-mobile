---
name: android.install_app
description: Install an APK from a URL or local file path. Uses Android PackageInstaller internally. The APK will be installed and ready to launch.
risk: high
timeout_ms: 30000
parameters: {"type": "object", "properties": {"source": {"type": "string", "description": "|"}, "wait_after": {"type": "integer", "description": "Milliseconds to wait after installation completes", "default": 5000}}, "required": ["source"]}
---
