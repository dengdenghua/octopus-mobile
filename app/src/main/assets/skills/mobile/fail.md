---
name: android.fail
description: Signal that the current task has failed. Call this when the task cannot be completed due to an error or unexpected state. Include error information to help with debugging and retry decisions.
risk: low
timeout_ms: 15000
parameters: {"type": "object", "properties": {"error": {"type": "string", "description": "Error message describing why the task failed"}, "recoverable": {"type": "boolean", "description": "Whether the task might succeed if retried", "default": true}}, "required": ["error"]}
---
