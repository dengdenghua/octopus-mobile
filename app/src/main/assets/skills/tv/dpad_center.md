---
name: dpad_center
description: Press D-pad Center/OK/Enter key on TV remote
risk: low
timeout_ms: 3000
parameters:
  - name: repeat
    type: integer
    required: false
    description: "Number of times to press. Default 1."
---

Press the Center/OK/Enter button on the TV remote. Used to select items, confirm actions, and enter menus.

**Example** - Select current item:
```json
{}
```

**Example** - Double-click OK:
```json
{ "repeat": 2 }
```
