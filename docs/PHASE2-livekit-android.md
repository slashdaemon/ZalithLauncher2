# Phase 2 — StreamCraft LiveKit video RECEIVE on Android (the Beam Pro)

Implementation plan for the next session. Maps to `../../ROADMAP.md` Phase 2. Approved 2026-06-19.

## Goal

**Viewer-only**: the Beam Pro receives and displays a remote LiveKit video track in-world. Capturing
*from* the device (camera/screen) is Phases 4–5; voice is Phase 3 — out of scope here.

## Problem (diagnosed 2026-06-19)

The launcher runs MC 26.1.2 + Fabric + StreamCraft 0.12.1 and connects to theblocksurvival.com, but
the Beam can't *see* a stream: StreamCraft's **LiveKit FFI native fails to load**, so there's no
decoder for the remote track (the "Initializing StreamCraft" menu is the media subsystem stuck on it).

Why it fails today:
- StreamCraft's `NativeLoader` extracts `/natives/<platform>/liblivekit_ffi.so` from the mod JAR to the
  Fabric **config dir** (on `/storage/emulated/…`) and `Native.load(absolutePath)`. Android's **Bionic
  linker namespace blocks `dlopen` from `/storage`** (`not accessible for the namespace "clns-4"`).
- The bundled natives are **GNU/glibc**, not `aarch64-linux-android` (Bionic).
- `PlatformUtil`'s `linux-x86_64` mislabel is **telemetry only** — not a media gate, so not the blocker.

## Why this is tractable (no cross-compile)

- **LiveKit ships prebuilt Android FFI** — `livekit/rust-sdks` releases `ffi-android-arm64.zip`
  (`liblivekit_ffi.so`, Bionic) for **0.12.57–0.12.64**. Download, don't compile.
- **Receive-only needs only `liblivekit_ffi.so`** (+ JNA 5.18.1 + protobuf 4.28.2) — NOT the capture
  native `libstreamcraft_linux.so`.
- The launcher already bundles natives via **`jniLibs/arm64-v8a/` → APK `nativeLibraryDir`**
  (`/data/app/.../lib/arm64-v8a`), which **Bionic allows `dlopen` from**, and **`jna.boot.library.path`
  already points there** (`ZalithLauncher/.../game/launch/Launcher.kt:162`). So JNA
  `Native.load("livekit_ffi")` *by name* finds it — no `/storage`, no namespace bypass.
  `libjnidispatch.so` (android) is already handled by the launcher.

## Steps

### A. Get the Android FFI native
Download `ffi-android-arm64.zip` from `https://github.com/livekit/rust-sdks/releases`, tag
`livekit-ffi/v0.12.57` (closest to StreamCraft's pinned 0.12.48; fallback 0.12.64). Extract
`liblivekit_ffi.so`.

### B. StreamCraft — patch the loader (repo: `StreamCraft`)
File `versions/26.1/shared-mc-26/src/client/java/com/streamcraft/client/livekit/NativeLoader.java`,
`load()` (lines ~31–74): when `Boolean.getBoolean("streamcraft.livekit.systemLib")`, skip the
JAR-extract block (~36–62) and call `Native.load("livekit_ffi", LiveKitFfi.class)` (by name) so JNA
resolves it from `jna.boot.library.path` (= `nativeLibraryDir`). Desktop path unchanged.
`detectPlatform()`/`getLibraryName()` untouched. `PlatformUtil` needs no change.

Rebuild from the **`v0.12.1`** tag (so `PROTOCOL_VERSION` matches the server) with only this delta, via
the quarantined 26.1 toolchain (JDK 25 + the band's own Gradle 9.4.1 wrapper):
`cd versions/26.1 && JAVA_HOME=<jdk25> ./gradlew :band:build` → `streamcraft-0.12.1+mc26.1.2*.jar`
(any variant — the FFI now loads by name, not from the JAR). Install that jar into the device profile.

### C. Launcher — bundle the native (this repo)
Add `ZalithLauncher/src/main/jniLibs/arm64-v8a/liblivekit_ffi.so`. The existing
`ZalithLauncher/build.gradle.kts` `packaging.jniLibs` packages it into the APK automatically.
Rebuild + reinstall: `cd launcher && JAVA_HOME=<jdk17> ./gradlew ZalithLauncher:assembleDebug -Darch=arm64`.

> `nativeLibraryDir` is read-only at runtime, so the `.so` MUST ship in the APK (jniLibs) — it can't be
> `adb push`ed in. A launcher rebuild is unavoidable.

### D. JVM flag
First test: set `-Dstreamcraft.livekit.systemLib=true` in the **profile's JVM arguments** (ZL2 instance
settings — no code change). Once confirmed, bake it into the launcher's default JVM args (args assembly
near `Launcher.kt:162`).

## Risks
- **FFI version skew** — android prebuilt 0.12.57+ vs StreamCraft's 0.12.48 protobuf/JNA binding. The FFI
  C ABI (4 functions: `initialize`/`request`/`drop_handle`/`dispose`) is stable across 0.12.x and proto
  changes are additive, so it should interop. If `livekit_ffi_request` returns incompatible responses,
  fall back to **bumping StreamCraft's FFI to 0.12.57** (update `proto/ffi.proto` + the desktop natives +
  regenerate protobuf).
- The patched jar replaces production 0.12.1 on the device — building from the `v0.12.1` tag keeps the
  protocol identical; LiveKit FFI talks to the LiveKit SFU, not the MC server, so server interop is fine.

## Verification
1. **Native loads** — relaunch; logcat shows `LiveKit FFI native library loaded successfully`, no
   `Native library not found … liblivekit_ffi.so` / `dlopen … clns-4`.
   `adb -s <dev> logcat | grep -iE 'livekit|FFI|UnsatisfiedLink|clns-4'`.
2. **Stream visible** — Mac streams desktop into the room; on the Beam the menu leaves "Initializing"
   and the track renders on the in-world display block / billboard (`VideoStreamManager` →
   `StreamTextureManager` → `DisplayBlockRenderer`). Confirm via `adb exec-out screencap`.
3. **No regression** — patched jar still connects to theblocksurvival.com (handshake passes).
