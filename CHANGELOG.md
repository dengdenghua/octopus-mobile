# Changelog

All notable changes to Octopus Mobile are documented here.
Format loosely follows [Keep a Changelog](https://keepachangelog.com/).

## [0.0.2] — 2026-06-14

A round focused on **messaging channels, feature discoverability, an iOS-flavored
visual pass, plain-language wording, and APK slimming / 32-bit support**.

### Added
- **Messaging channels hub** (`ChannelsActivity`): a single screen listing all
  channels with connect status. Fixes the previous bug where Settings → Channels
  opened a config page that instantly closed (it was launched without a channel
  type). DingTalk / Feishu / QQ / Discord / Telegram all configurable again.
- **WeChat QR login** (`WeChatLoginActivity`): scan-to-login flow wired to the
  existing `WeChatApiClient` (`getQrCode` → poll → save token → reconnect).
- **"Features" bottom tab** (`FeatureHubScreen`): restores access to screens that
  became unreachable when Discover turned into the browser home — Skills, Browser
  Plugins, Routines, Cloud Drive, Memory, Video Library, Multi-Window,
  Self-Evolution, Trust Center.
- **Browser Settings page** (`BrowserSettingsActivity`): default search engine
  picker, engine/kernel info, and a shortcut to browser plugins. Honest controls
  only (no placeholder toggles).
- **Trilingual strings (EN / ZH / JA)** for all new and previously-hardcoded
  user-facing screens.

### Changed
- **iOS-flavored visual pass.** Consistent solid rounded-square colored icon
  tiles (white glyphs) across the Features hub, Browser Settings, and Settings
  rows. Browser favorites now render as white "app-icon" tiles, fixing the
  GitHub (and other dark-mark) logos being invisible on the dark surface.
- **Simplified remote-connection settings** for non-technical users: a single
  plain "Remote-control your PC" entry, with the host connection / RPC modes /
  LAN remote desktop tucked into a collapsed **Advanced** group. Nothing removed,
  just hidden by default.
- **Plain-language wording.** Replaced internal jargon in user-facing text —
  「母体」/ "Parent" / "Remote Brain" / "mother device" → 「主机」/ "host" /
  "Remote Execution" — consistently across EN / ZH / JA.

### Fixed
- **Security (ConfigServer / web console):**
  - Path traversal on `/api/files/browse` and `/api/files/search` (now restricted
    to `/sdcard`, with `..` and injection chars rejected); unified the same guard
    across download / upload / delete.
  - Plaintext secret echo: `GET /api/llm` and `/api/channels` now mask secrets;
    the POST handlers already skip masked values, so saving via the web UI no
    longer overwrites a real key with the mask.
- **Crashes / races:** `H264Decoder.stop()` now joins the worker before releasing
  the codec; `DefaultAgentService` guards a null executor; `ChatAgentBridge` uses
  a shared busy lock so the web console and the in-app chat can't clobber each
  other; `PcRemoteWebrtcActivity` survives a missing system WebView.
- **i18n:** all user-facing Chinese that was hardcoded in Kotlin now resolves per
  locale (previously stuck on Chinese regardless of device language).
- **Release-only favicon bug:** added Coil R8 keep rules so browser favorites and
  search-engine icons load in the minified release build (they were blank).

### Build
- **Slimmed the APK ~40 MB** by dropping the bundled mpv/ffmpeg native libs — the
  player was a non-functional stub, so the ~25 MB of `.so` (plus its dex) was dead
  weight. Video playback, when implemented, will fetch the libs on demand.
- **ABI splits** (`arm64-v8a` + `armeabi-v7a`) so 32-bit devices are supported
  without bloating the 64-bit package:
  - `arm64-v8a` ≈ **237 MB** (most phones / TV boxes)
  - `armeabi-v7a` ≈ **201 MB** (older 32-bit devices)
  - No combined "universal" APK. For store distribution, ship an AAB instead.

### Notes
- ~~The remaining APK size is dominated by the bundled **GeckoView** engine~~
  **GeckoView has been removed** (saved ~180 MB: `libxul.so` 144 MB + `omni.ja`
  13 MB + various `.so` files). The browser now uses the system WebView
  (Chromium engine, 0 APK size impact). Extension support has been replaced
  with a custom injectable plugin system (`evaluateJavascript` +
  `shouldInterceptRequest`).
- Account email/SMS-code login and the hosted model-relay backend landed in the
  same period (separate workstream).
