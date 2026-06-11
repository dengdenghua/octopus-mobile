---
name: android.read_file
description: Read a file from the device's storage. Supports reading from /sdcard/ (public storage) and /data/data/ (app-private, requires root). Returns the file content as a string.
risk: low
timeout_ms: 15000
parameters: {"type": "object", "properties": {"path": {"type": "string", "description": "Absolute file path to read (e.g., \"/sdcard/Download/report.txt\")"}, "encoding": {"type": "string", "description": "File encoding (e.g., \"utf-8\", \"gbk\", \"ascii\")", "default": "utf-8"}, "max_size": {"type": "integer", "description": "Maximum file size to read in bytes (default 1MB)", "default": 1048576}}, "required": ["path"]}
---
