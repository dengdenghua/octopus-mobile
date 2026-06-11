---
name: android.send_sms
description: Open the system SMS app with a pre-filled recipient and message body. The user must manually confirm and tap 'Send'. Does NOT send the message directly - only prepares it for user confirmation.
risk: high
timeout_ms: 30000
parameters: {"type": "object", "properties": {"phone_number": {"type": "string", "description": "Recipient phone number, e.g. '13800138000' or '+8613800138000'"}, "message": {"type": "string", "description": "The SMS body text to pre-fill"}}, "required": ["phone_number", "message"]}
---
