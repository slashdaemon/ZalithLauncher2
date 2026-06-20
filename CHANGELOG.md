# TBS Launcher — Changelog

TBS Launcher is a fork of [ZalithLauncher2](https://github.com/ZalithLauncher/ZalithLauncher2)
(GPL-3.0). This file tracks the TBS-specific delta; upstream history lives in git.

## tbs-2.4.8 — 2026-06-19

**Phase 0–1 complete: launcher runs MC 26.1.2 + Fabric + StreamCraft on the XREAL Beam Pro,
with working Microsoft login.**

### Rebrand (fork identity)
- `applicationId` → `as.papp.tbs.launcher` (`.v2` suffix) so it installs alongside stock
  ZalithLauncher2; Kotlin/R namespace intentionally kept `com.movtery.zalithlauncher`.
- Branding via `BuildKeys`: name "TBS Launcher", short "TBS", `url_home` → slashdaemon fork.
- Startup shows the GPL-required **"Unofficial Modified Versions"** notice under the launcher name.

### Microsoft login fixed (the blocker)
- Device-code login was failing with **HTTP 400** ("The server could not understand the request")
  because `BuildKeys.OAUTH_CLIENT_ID` was **empty** (gradle property commented out → Base64 of an
  empty array). The OAuth `/devicecode` request was sending `client_id=` blank.
- Now sourced from a local **`.oauth_client_id.txt`** (gitignored) via `getKeyFromLocal`, reusing
  **TheBlockAcademy's Azure app** (`1aac66aa-…`) which already holds the **`XboxLive.signin`**
  permission and has **"Allow public client flows" = Yes** (required for the device-code flow).
- Verified end-to-end on the Beam Pro: device-code → Xbox → XSTS → Minecraft profile → in-game.
- Known follow-up: the device code is silently copied to the clipboard and the WebView opens the
  bare `verification_uri`, so the user lands on a blank "Enter code" box. Should open
  `verification_uri_complete` (code pre-filled) instead — small change in `AccountUtils`.

### On-device validation (Phase 1)
- Wireless adb pair/connect to the Beam Pro (X4200, Android 14, arm64-v8a).
- Launcher auto-installs MC 26.1.2 + Fabric loader 0.19.3 + Fabric API 0.152.1+26.1.2.
- StreamCraft client installed into the profile: `streamcraft-0.12.1+mc26.1.2` (matches the
  theblocksurvival.com production server's protocol). Mod **loads, connects, and runs voice
  signaling**; the server license/protocol handshake passes.

### Phase 2 gap identified (StreamCraft media on Android — not yet done)
The Beam can't **receive/display a stream** yet: LiveKit FFI native fails to load. Root causes:
1. StreamCraft's `PlatformUtil` (early `static final os.arch` read, wrong under Pojav) reports
   `linux-x86_64` while `NativeLoader` (live read) correctly wants `linux-aarch64` — the detectors
   disagree, so no existing variant jar satisfies both.
2. Android's **Bionic linker namespace** blocks JNA `dlopen` of a `.so` extracted to
   `/storage/emulated/…` (`not accessible for the namespace "clns-4"`).
3. No **`aarch64-linux-android`** (Bionic) native exists — only GNU/glibc builds — and StreamCraft's
   loaders have no Android branch.

Tracked as `ROADMAP.md` Phase 2 (LiveKit transport on Android).
