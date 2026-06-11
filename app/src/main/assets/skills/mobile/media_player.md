---
name: media_player
description: Play video/audio with mpv engine. Full format support including MKV, MP4, Blu-ray ISO, HDR, ASS subtitles.
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
---

# Media Player

## Play
```
media_player(action="play", path="/sdcard/Movies/movie.mkv")
```

## Scan
```
media_player(action="scan", path="/sdcard/Movies")
```

## Control
```
media_player(action="pause")
media_player(action="seek", value="300000")
media_player(action="volume", value="80")
media_player(action="info")
```

## Cloud Drive
```
media_player(action="mount_cloud", value="start")
media_player(action="play_cloud", path="阿里云盘Open", value="/电影/阿凡达.mkv")
```
