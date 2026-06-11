---
name: android.wait
description: Wait for a specified duration, or wait for a specific node to appear/disappear. Essential for handling loading states, animations, and async operations.
risk: low
timeout_ms: 15000
parameters: {"type": "object", "properties": {"ms": {"type": "integer", "description": "Milliseconds to wait (used when waiting by time)", "default": 1000}, "wait_for_node": {"type": "string", "description": "|", "default": ""}, "wait_for_node_gone": {"type": "string", "description": "|", "default": ""}, "poll_interval": {"type": "integer", "description": "Polling interval in ms when waiting for a node", "default": 500}}}
---
