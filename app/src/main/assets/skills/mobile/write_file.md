---
name: android.write_file
description: Write content to a file on the device's /sdcard/ storage. Creates the file if it doesn't exist, overwrites if it does. Can optionally append instead of overwrite.
risk: high
timeout_ms: 15000
parameters: {"type": "object", "properties": {"path": {"type": "string", "description": "Absolute file path to write (e.g., \"/sdcard/Download/output.txt\")"}, "content": {"type": "string", "description": "Content to write to the file"}, "append": {"type": "boolean", "description": "Whether to append to the file instead of overwriting", "default": false}, "encoding": {"type": "string", "description": "File encoding (e.g., \"utf-8\", \"gbk\", \"ascii\")", "default": "utf-8"}}, "required": ["path", "content"]}
---
