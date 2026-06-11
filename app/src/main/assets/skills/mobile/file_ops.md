---
name: file_ops
description: Copy, move, delete files or read text content (AI NAS)
risk: medium
timeout_ms: 15000
parameters:
  - name: action
    type: string
    required: true
    description: "'copy', 'move', 'delete', 'mkdir', 'read' (read text file)"
  - name: source
    type: string
    required: false
    description: "Source file/directory path. Required for copy/move/delete/read."
  - name: destination
    type: string
    required: false
    description: "Destination path. Required for copy/move."
  - name: max_lines
    type: integer
    required: false
    description: "Max lines to return for 'read'. Default 100."
---

Perform file operations using Shizuku shell access. Can operate on any file under `/sdcard/` including `/sdcard/Android/data/`.

**Requirements**: Shizuku must be installed and permission granted.

**Safety**: `delete` is irreversible — always confirm with the user before deleting files.

**Example** - Copy a photo to backup:
```json
{ "action": "copy", "source": "/sdcard/DCIM/Camera/photo.jpg", "destination": "/sdcard/Backup/" }
```

**Example** - Rename a file:
```json
{ "action": "move", "source": "/sdcard/Download/old.pdf", "destination": "/sdcard/Download/new.pdf" }
```

**Example** - Read a text file:
```json
{ "action": "read", "source": "/sdcard/config.json", "max_lines": 50 }
```

**Example** - Create a directory:
```json
{ "action": "mkdir", "source": "/sdcard/OctopusBackup/photos" }
```

**Example** - Delete a file (ask user first!):
```json
{ "action": "delete", "source": "/sdcard/Download/temp_file.tmp" }
```
