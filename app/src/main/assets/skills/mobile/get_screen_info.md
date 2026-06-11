---
name: android.get_screen_info
description: Get the current screen's accessibility tree including nodes, text, bounds, and class names. This is the MOST IMPORTANT skill — always call this first to understand the screen state. Returns current_app, current_activity, screen_size, keyboard state, and the node tree.
risk: low
timeout_ms: 15000
parameters: {"type": "object", "properties": {"filter_empty": {"type": "boolean", "description": "Filter out nodes with no text/desc and not interactive", "default": true}, "simplify_class": {"type": "boolean", "description": "Simplify class names (e.g., android.widget.TextView → TextView)", "default": true}, "max_depth": {"type": "integer", "description": "Maximum depth of the accessibility tree to traverse", "default": 30}}}
---
