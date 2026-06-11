---
name: browse_files
description: Browse and inspect files on the device (AI NAS)
risk: low
timeout_ms: 10000
parameters:
  - name: action
    type: string
    required: true
    description: "'list' (directory contents), 'info' (file details), 'size' (directory size), 'storage' (overview)"
  - name: path
    type: string
    required: false
    description: "File or directory path. Required for list/info/size."
  - name: show_hidden
    type: boolean
    required: false
    description: "Show hidden files when listing. Default false."
---

Browse and inspect files on the device using Shizuku shell access. Can access `/sdcard/` and `/sdcard/Android/data/` (other apps' data directories that normal apps can't reach on Android 11+).

**Requirements**: Shizuku must be installed and permission granted.

**Actions**:
- `list`: List directory contents (path required)
- `info`: Get detailed file/directory info (path required)
- `size`: Calculate directory size (path required)
- `storage`: Get overall storage overview (no path needed)

**Example** - List downloads:
```json
{ "action": "list", "path": "/sdcard/Download" }
```

**Example** - Check WeChat data usage:
```json
{ "action": "list", "path": "/sdcard/Android/data/com.tencent.mm" }
```

**Example** - Get storage overview:
```json
{ "action": "storage" }
```

**Example** - Check directory size:
```json
{ "action": "size", "path": "/sdcard/Android/data/com.tencent.mm" }
```
