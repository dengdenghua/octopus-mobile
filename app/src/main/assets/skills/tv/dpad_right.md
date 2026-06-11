---
name: dpad_right
description: Press D-pad Right key on TV remote
risk: low
timeout_ms: 3000
parameters:
  - name: repeat
    type: integer
    required: false
    description: "Number of times to press. Default 1."
---

Press the Right arrow key on the TV remote control.

**Example** - Move right 2 times:
```json
{ "repeat": 2 }
```
