---
name: android.send_intent
description: Launch an activity or trigger an action via standard Android Intent. Supports opening URLs, dialing phones, sharing text, or launching specific activities. Does NOT rely on AccessibilityService.
risk: medium
timeout_ms: 15000
parameters: {"type": "object", "properties": {"action": {"type": "string", "description": "Intent action: VIEW, DIAL, SEND, MAIN. Defaults based on other params."}, "uri": {"type": "string", "description": "URI: https://example.com, tel:123, mailto:a@b.com, sms:123"}, "component": {"type": "string", "description": "Target as 'package/activity', e.g. 'com.android.settings/.Settings'"}, "extra_text": {"type": "string", "description": "Intent.EXTRA_TEXT for sharing"}, "extra_subject": {"type": "string", "description": "Intent.EXTRA_SUBJECT"}, "type": {"type": "string", "description": "MIME type, e.g. 'text/plain'"}}}
---
