---
name: dpad_up
description: Press D-pad Up key on TV remote
risk: low
timeout_ms: 3000
parameters:
  - name: repeat
    type: integer
    required: false
    description: "Number of times to press. Default 1."
---

Press the Up arrow key on the TV remote control. Used to navigate menus, lists, and channel lists on TV interfaces.

**Example** - Move up 3 times:
```json
{ "repeat": 3 }
```
