---
name: dpad_down
description: Press D-pad Down key on TV remote
risk: low
timeout_ms: 3000
parameters:
  - name: repeat
    type: integer
    required: false
    description: "Number of times to press. Default 1."
---

Press the Down arrow key on the TV remote control.

**Example** - Move down 5 times:
```json
{ "repeat": 5 }
```
