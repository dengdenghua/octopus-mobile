---
name: media_player
description: Play video/audio with mpv engine (FFmpeg + libplacebo + libass). Full format support, Blu-ray ISO, HDR, ASS subtitles.
risk: low
timeout_ms: 10000
parameters:
  - name: action
    type: string
    required: true
    description: "Action: 'play', 'pause', 'resume', 'stop', 'seek', 'seek_relative', 'subtitle', 'audio_track', 'volume', 'info', 'scan', 'find_subtitle'"
  - name: path
    type: string
    required: false
    description: "Media file path or URL for 'play', directory path for 'scan'"
  - name: value
    type: string
    required: false
    description: "Value for seek (ms), volume (0-150), track id, subtitle size, seek offset (ms)"
  - name: subtitle
    type: string
    required: false
    description: "External subtitle file path for 'play'"
  - name: type
    type: string
    required: false
    description: "Media type filter for 'scan': 'video', 'audio', 'subtitle', 'all'"
---

# Media Player — mpv Powered

## Play Media
```
media_player(action="play", path="/sdcard/Movies/Avatar.mkv")
```

## Scan Directory
```
media_player(action="scan", path="/sdcard/Movies", type="video")
```

## Playback Control
```
media_player(action="pause")
media_player(action="resume")
media_player(action="seek", value="900000")
media_player(action="seek_relative", value="30000")
```

## Subtitle Control
```
media_player(action="subtitle", value="1")
media_player(action="subtitle", value="/sdcard/Movies/Avatar.srt")
media_player(action="subtitle", value="size:48")
```

## Get Info
```
media_player(action="info")
```

## Cloud Drive (CloudDrive 2)

### Start CloudDrive2
```
media_player(action="mount_cloud", value="start")
```

### List Cloud Files
```
media_player(action="list_cloud", path="阿里云盘Open", value="/电影")
```

### Play from Cloud
```
media_player(action="play_cloud", path="阿里云盘Open", value="/电影/阿凡达.mkv")
```

### Cloud Status
```
media_player(action="cloud_status")
```
