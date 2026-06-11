---
name: android.browser.install_extension
description: Install a browser extension package into the built-in mobile browser. Use this for anti-bot workflows, scraper helpers, custom AI bridge extensions, or site-specific automation extensions that must run inside the Android browser.
risk: high
timeout_ms: 30000
parameters: {"type": "object", "properties": {"crx_url": {"type": "string", "description": "URL of the .crx extension package to download and install"}, "xpi_url": {"type": "string", "description": "URL of the .xpi extension package to download and install"}, "local_path": {"type": "string", "description": "Local extension package path on the Android device"}, "extension_id": {"type": "string", "description": "Optional expected extension id for verification after install"}, "enabled": {"type": "boolean", "description": "Whether to enable the extension immediately after installation", "default": true}}}
---
