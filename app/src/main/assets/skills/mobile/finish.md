---
name: android.finish
description: Signal that the current task has been completed successfully. Call this when the task goal has been achieved. Optionally include a summary of what was accomplished.
risk: low
timeout_ms: 15000
parameters: {"type": "object", "properties": {"summary": {"type": "string", "description": "Optional summary of what was accomplished", "default": ""}}}
---
