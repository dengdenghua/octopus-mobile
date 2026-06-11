---
name: search_files
description: Search files by name, content, or find duplicates (AI NAS)
risk: low
timeout_ms: 15000
parameters:
  - name: action
    type: string
    required: true
    description: "'name' (by filename pattern), 'content' (by file content), 'duplicates' (find duplicate files)"
  - name: path
    type: string
    required: true
    description: "Base directory to search in (e.g. '/sdcard', '/sdcard/Download')"
  - name: pattern
    type: string
    required: false
    description: "For 'name': filename glob (e.g. '*.jpg'). For 'content': text to search."
  - name: file_type
    type: string
    required: false
    description: "File type filter for content search (e.g. '*.txt'). Default '*'."
  - name: min_size_mb
    type: integer
    required: false
    description: "Minimum file size in MB for duplicate search. Default 1."
  - name: max_results
    type: integer
    required: false
    description: "Maximum number of results. Default 30."
---

Search files on the device using Shizuku shell access. Can search in `/sdcard/` and `/sdcard/Android/data/`.

**Requirements**: Shizuku must be installed and permission granted.

**Example** - Find all photos:
```json
{ "action": "name", "path": "/sdcard", "pattern": "*.jpg" }
```

**Example** - Find PDFs in Download:
```json
{ "action": "name", "path": "/sdcard/Download", "pattern": "*.pdf" }
```

**Example** - Search text in log files:
```json
{ "action": "content", "path": "/sdcard", "pattern": "error", "file_type": "*.log" }
```

**Example** - Find duplicate large files:
```json
{ "action": "duplicates", "path": "/sdcard", "min_size_mb": 5 }
```
