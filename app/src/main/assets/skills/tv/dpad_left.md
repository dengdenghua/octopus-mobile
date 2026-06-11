---
name: dpad_left
description: Press D-pad Left key on TV remote
risk: low
timeout_ms: 3000
parameters:
  - name: repeat
    type: integer
    required: false
    description: "Number of times to press. Default 1."
---

Press the Left arrow key on the TV remote control.

**Example** - Move left 2 times:
```json
{ "repeat": 2 }
```
