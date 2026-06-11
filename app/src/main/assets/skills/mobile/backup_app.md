---
name: backup_app
description: Backup and restore app data from /sdcard/Android/data/ (AI NAS)
risk: medium
timeout_ms: 30000
parameters:
  - name: action
    type: string
    required: true
    description: "'backup' (backup app data), 'restore' (restore from backup), 'list' (list available backups)"
  - name: package
    type: string
    required: false
    description: "App package name (e.g. 'com.tencent.mm'). Required for backup/restore."
  - name: backup_dir
    type: string
    required: false
    description: "Custom backup directory. Default: /sdcard/OctopusBackup"
---

Backup and restore app data using Shizuku shell access. Can access `/sdcard/Android/data/<package>/` for any app — not possible for normal apps on Android 11+ (Scoped Storage restriction).

**Requirements**: Shizuku must be installed and permission granted.

**Note**: Only backs up sdcard data, not internal `/data/data/` (requires root).

**Example** - Backup WeChat data:
```json
{ "action": "backup", "package": "com.tencent.mm" }
```

**Example** - Backup to custom directory:
```json
{ "action": "backup", "package": "com.eg.android.AlipayGphone", "backup_dir": "/sdcard/MyBackup" }
```

**Example** - List available backups:
```json
{ "action": "list" }
```

**Example** - Restore WeChat data:
```json
{ "action": "restore", "package": "com.tencent.mm" }
```
