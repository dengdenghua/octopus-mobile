---
name: android.read_calendar
description: Read calendar events from the device. Returns title, start/end time, location, description and calendar name. Defaults to next 7 days. Use filter_keyword to search by event title.
risk: medium
timeout_ms: 30000
parameters: {"type": "object", "properties": {"days_ahead": {"type": "integer", "description": "Days ahead to query (default 7, max 90)", "default": 7}, "days_behind": {"type": "integer", "description": "Days behind to include (default 0, max 30)", "default": 0}, "filter_keyword": {"type": "string", "description": "Filter events by keyword in title (case-insensitive)"}}}
---
