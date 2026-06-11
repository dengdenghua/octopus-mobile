---
name: android.read_sms
description: Read SMS/MMS messages from the device. Returns sender address, body, timestamp, and type (inbox/sent/draft). Use filter_address for a specific contact or filter_keyword to search by content.
risk: high
timeout_ms: 30000
parameters: {"type": "object", "properties": {"limit": {"type": "integer", "description": "Max messages to return (default 20, max 100)", "default": 20}, "filter_address": {"type": "string", "description": "Filter by phone number (partial match)"}, "filter_keyword": {"type": "string", "description": "Filter by keyword in body (case-insensitive)"}, "box": {"type": "string", "description": "Which box: inbox, sent, draft, all", "default": "all"}}}
---
