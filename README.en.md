<div align="center">

# WotaGeiCam

**An immature personal open-source camera project for daily WOTA-gei practice** — film beautiful light arcs, practice standard moves

[![Build Release APKs](https://github.com/himetuki/WotaGeiCam/actions/workflows/build-apk.yml/badge.svg)](https://github.com/himetuki/WotaGeiCam/actions/workflows/build-apk.yml)
[![Build Release HAPs](https://github.com/himetuki/WotaGeiCam/actions/workflows/build-hap.yml/badge.svg)](https://github.com/himetuki/WotaGeiCam/actions/workflows/build-hap.yml)
![Release](https://img.shields.io/github/v/release/himetuki/WotaGeiCam?color=7f3a9c&label=Release)
![Platform](https://img.shields.io/badge/Platform-Android%20·%20HarmonyOS-e42b9f)
![License](https://img.shields.io/github/license/himetuki/WotaGeiCam?color=9c1f7b)

[中文](README.md) · [Download Releases](https://github.com/himetuki/WotaGeiCam/releases/latest) · [Feedback Issue](.github/ISSUE_TEMPLATE/bug-report-feature-request.md)

</div>

## Community translation

[@mayday112](https://github.com/mayday112) created and keeps maintaining a translated build of the app with an English / Indonesian (Bahasa Indonesia) interface: [mayday112/WotaGeiCam](https://github.com/mayday112/WotaGeiCam), which fills exactly the gap one person cannot cover alone — sincere thanks for his work.

If you need an English / Indonesian (Bahasa Indonesia) interface, his build is recommended; issues and suggestions about the translation are welcome to be reported to him directly, and issues or suggestions about the app itself are welcome via the [issue template](.github/ISSUE_TEMPLATE/bug-report-feature-request.md). His build and this repository are built and signed independently; switching between them requires uninstalling and reinstalling (which wipes in-app settings and favourites). What the translated build includes is described in his repository.

**Contents**

- [✨ Highlights](#-highlights)
- [Features](#features)（[Shooting controls](#shooting-controls) · [Framing aids](#framing-aids) · [Interface and motion](#interface-and-motion) · [Recording](#recording) · [Light-arc tool chain](#light-arc-tool-chain) · [Playback and library](#playback-and-library)）
- [Roadmap](#roadmap)
- [Known Issues and Limitations](#known-issues-and-limitations)
- [Download](#download)
- [Installation and upgrade](#installation-and-upgrade)
- [Building from source](#building-from-source)
- [CI](#ci)
- [Diagnostic logs](#diagnostic-logs)
- [Privacy](#privacy)
- [License](#license)

> The number of devices verified long-term is small, and vendor camera HAL differences are far more than one person can fully explore. Known issues are listed honestly under [Known Issues and Limitations](#known-issues-and-limitations); if you hit odd behavior, please open an issue via the [issue template](.github/ISSUE_TEMPLATE/bug-report-feature-request.md) with your device model and symptoms (a diagnostic log helps). **For security matters, use the private channel in the [security policy](SECURITY.md), not a public issue.** Your feedback is the main way this project moves forward; please bear with the defects and rough edges.

## ✨ Highlights

**🌟 Light-arc tool chain · film beautiful light arcs**
- Forced slow shutter **1/24 · 1/25** full-window long exposure keeps arcs continuous; **equal-tier exposure correction** automatically leaves readout headroom to prevent frame drops.
- **Afterimage (highlight trailing)**: multi-frame echo lengthens arcs, "keep brightness / brightened trail" dual modes, multiplier 2–10×, across preview / recording / re-rendered export. (Algorithm concept referenced from Bilibili creator 蒙德·究史尼's open-source 光弧倍增器 script — with thanks; see Light-arc tool chain below)
- **In-recording extract + arc merge + offline arc repair**: devices without a native slow tier still keep continuous arcs.

**🎯 Practice comparison · practice standard moves**
- **Split-screen reference**: one side plays a reference video (using its audio directly), the other records the subject, so the recorder can compare the subject's practice against the reference; the subject is being recorded and cannot watch-while-practicing, so the reference audio guides the rhythm.
- **Dual-track comparison**: compare your practice side-by-side with the reference, switchable audio tracks, auto-aligned by audio.

**🎵 Audio · play along with the accompaniment**
- **Dual audio tracks** (ambient + internal capture), **audio denoise**, **Bluetooth speaker** volume / play control.

**🎨 Real-time picture effects · baked into the recording**
- **Glow**, **real-time RGB curve**, **picture denoise** (GPU bilateral, for night shadows).

**🔍 Professional monitoring**
- **Zebras / focus peaking** · **reference lines / grid** · **position lines** (separate from reference lines) · **level / pitch gauges**.

> Single-tree monorepo: `Android/WotaGeiCamApp/` is the Android project (the functional definition), `HarmonyOS/WotaGeiCamOHOS/` is the HarmonyOS port of the same product (still in progress, not yet installable for daily use). Features and UI iterate quickly; the latest version is on [Releases](https://github.com/himetuki/WotaGeiCam/releases/latest).

## Features

Listed by category below, each with its **location**, **how to open it**, and **usage**. Based on the Android version; the HarmonyOS version is a port of the same product, still in progress and not yet installable for daily use — see [`HarmonyOS/WotaGeiCamOHOS/docs/PORT_MAP.md`](HarmonyOS/WotaGeiCamOHOS/docs/PORT_MAP.md) for the map and progress.

### Shooting controls

| Feature | Location · How to open | Usage |
|---|---|---|
| Manual exposure (shutter / ISO / EV) | Recording page, bottom-right readout block; tap a readout to open its panel | Drag sliders; during recording ISO / EV stay adjustable, shutter is read-only |
| Forced slow shutter 1/24 · 1/25 | Shutter panel | Pick 1/24 or 1/25 (long exposure keeps arcs continuous); availability validated against the current frame period |
| Equal-tier exposure correction | Shutter panel (shown at 1/24 / 1/25) | "Auto exposure trim" on by default to stop frame-period stretch dropping frames; manual slider 0–+8 ms when off; top-left measured readout line (manual exposure only) |
| Frame rate | Bottom-right readout "frame rate"; tap | Pick a tier; ※ = device-approximate; non-exact tiers auto-extract during recording |
| White balance | Bottom-right readout "white balance"; tap | Pick a preset or adjust color temperature / tint |
| Focus / zoom | Right dock focus / zoom pills; or tap the viewfinder | Tap to focus + meter, double-tap to lock, long-press to unlock; half-second long-press locks / unlocks AE in auto mode |
| Real-time RGB curve | Left dock "curve" pill | Drag nodes; dialog scales by dragging its right edge; "reset" restores identity |
| Graduated sliders | Each parameter panel; Settings → Slider snapping | Snap to capability tiers by default (readout shows the true value); switch each parameter to continuous |
| Parameter persistence | Automatic | Parameters changed on the recording page survive restarts; sliders save on release |
| Gesture-navigation adaptation | Automatic | Bottom dock clears the gesture-bar area; parameters are read from device capabilities at runtime, no hard-coded model values |

### Framing aids

| Feature | Location · How to open | Usage |
|---|---|---|
| Zebras / focus peaking | Left dock "monitor" pill | Pick the effect, adjust threshold / density / intensity / color; GPU rendering only |
| Glow | Left dock "glow" pill | On/off + threshold / strength / blend; color modes "pixel's own / palette only"; preview and recording; GPU only |
| Reference lines / grid | Left dock "reference line" button | Multi-select line types (thirds / diagonals / safe frame / grid, …); defaults in Settings → Reference lines |
| Position lines | Top-bar right round button (left of the split button) | Tap to open the overlay for count / spacing, long-press to pick color; preview overlay only, not recorded, usable during recording |
| Level / pitch gauges | Right dock | Light vibration when level; toggle in Settings → Framing aids |
| Readout block | Bottom-right readout block | Shutter / frame rate / bitrate / ISO / EV / white balance / zoom; per-item show/hide in Settings → Viewfinder persistent controls |
| Custom control layout | Settings → Viewfinder persistent controls → Edit controls | Drag to place the docks and readout block; panels follow each control's anchor |

### Interface and motion

| Feature | Location · How to open | Usage |
|---|---|---|
| Motion style | Settings → Motion style | None / Fluent (default) / Liquid; Liquid uses physics springs and resumes from the current velocity after a gesture interrupts |
| Liquid gesture switch | Switches in the Liquid style | Drag the thumb past the midpoint to flip; release settles from the release velocity |
| Favourite heart animation | Player top bar / library favourite entry | Ring + coloured dots bloom on favourite; with system "reduce motion" or None, only the state changes |
| Brightness | Settings → Brightness | Startup screen-on delay; player start fade-in |
| Orientation / text size | Settings → Orientation / Text size | Landscape / portrait; readout and panel text size |

### Recording

| Feature | Location · How to open | Usage |
|---|---|---|
| Start / stop | Bottom dock center shutter | Tap to start / stop; two engines, bitrate in Settings → Startup defaults |
| Dual audio (ambient + capture) | Right dock "audio" pill, opens the source panel | Switch ambient / capture; capture records system playback audio, needs notification permission; ambient by default on cold start |
| Bluetooth speaker | Source panel | Media volume / play control / battery; pairing in system Bluetooth settings |
| Split-screen reference | Top-bar right split button | One side plays a reference video (using its audio directly), the other records the subject, so the recorder can compare the subject's practice; the subject is being recorded and cannot watch-while-practicing, guided by the reference audio; open before recording, locked during recording |
| Automatic segment rotation | Automatic | Rotates at 3.5 GiB per segment, names stay sequential; top-bar pre-warning at 85% |
| Honest frame-rate / WB notices | Top-bar warning line | Warns when the achieved rate stays too low, or manual WB gains are not adopted (see [Known Issues](#known-issues-and-limitations)) |
| Ramp-up buffer / rotation retry | Automatic | Samples at record start are written, not dropped; a failed rotation reclaims resources before retrying |
| Frame ledger | Diagnostic log export | Per-segment camera → render → encoder → muxer frame totals, to locate the dropping stage |

### Light-arc tool chain

| Feature | Location · How to open | Usage |
|---|---|---|
| In-recording extract + arc merge | Frame-rate readout panel (auto when no native 24/25 tier) | "Auto-extract with arc merging" keeps arcs continuous / "extract only"; dropped slots logged to a `.drops.json` sidecar |
| Afterimage (highlight trailing) | Bottom dock left "afterimage" pill (hidden by default) | Enable in Settings → Viewfinder persistent controls; pick mode (keep brightness / brightened trail) and multiplier 2–10×; preview / recording / re-rendered export; GPU only |
| Offline arc repair | Player → Arc repair | Pick the target rate, GPU or CPU; writes a new file, never overwrites the original |
| Merge threshold | Frame-rate panel / arc-repair panel / Settings → Startup defaults | Default 12 (0–32); higher is more conservative, lower merges noise; same rule for recording and offline, CPU and GPU |

> The afterimage feature's **algorithm concept and product semantics** (multi-frame echo to lengthen arcs, keep-brightness / brightened-trail dual modes, multiplier tiers) reference the open-source script 光弧倍增器 (Light-arc Multiplier) by Bilibili creator [蒙德·究史尼](https://space.bilibili.com/8205741) — with sincere thanks. What was borrowed is the idea and semantics; **no source code was reused** — the shaders and implementation here were written independently, on a different technical approach (real-time GL feedback accumulation with arcs trailing behind the motion, rather than its offline Python sliding window that placed arcs ahead of it).

### Playback and library

| Feature | Location · How to open | Usage |
|---|---|---|
| Media library | Recording page bottom dock thumbnail | All / Favorites / Trash; long-press multi-select to rename / share / delete |
| Player | Tap a video in the library | A-B loop, frame stepping, speed 0.1x–1x, pinch zoom/pan, horizontal mirror |
| Clip export | Player bottom dock A/B marks → Clip | Lossless (no re-encode) / re-rendered (re-encode with denoise + glow + afterimage, configured in-sheet, this export only); audio track selectable; leaving the player does not interrupt |
| Dual-track comparison | Library long-press → Compare | Two video tracks + audio switching, audio-based auto alignment; comparison history kept |
| Cold-start maintenance | Automatic | Cleans temp leftovers, keeps the library index consistent; archiving never overwrites existing files |

## Roadmap

Planned / under research, not yet landed:

- [ ] **R1 · Metering mode selection**: selectable metering modes such as evaluative / center-weighted / spot; whether the effect is visible depends on the device HAL.
- [ ] **Arc-repair small model**: repairs dead zones / breaks in light arcs, extending and filling from adjacent frames for more continuous arcs.

## Known Issues and Limitations

An honest list of the currently known problem surface (verified through v0.0.12-r01; updated as feedback comes in):

- **Full-screen black after tapping back repeatedly on the settings page (fixed in r31–r41; upgrade if you are on an older build)**: device logs confirmed the cause is **not the camera** — with repeated back taps on the settings page, the second tap lands on the recording page that has just returned and pops it, and since it is the navigation start destination, the whole back stack is emptied; the UI framework then has nothing to draw: the screen is black with no controls, while the app stays alive and the back gesture still works. Since r40/r41 an in-page back can no longer pop the start destination (plus a 400 ms debounce), and any page whose UI gets torn down for more than 3 seconds recovers automatically; the camera pipeline now belongs to the window scope, so navigation races cannot touch camera state. On older builds (≤ v0.0.11-r30) please upgrade first.

- **The achieved frame rate is decided by the camera HAL; the app can only negotiate, never guarantee it.** The frame-rate decision only guarantees "the value we send was declared by the device"; low light, slow shutters, or vendor policy can push the achieved rate below the selected tier. The app warns with the measured value once the rate stays below ~70% of the target, but **does not force-lock the tier** — forcing it freezes the viewfinder on some devices (the lesson of another, already-fixed defect). Some vendor HALs also widen the range beyond a requested fixed tier: on one Huawei device a requested `[24,24]` came back as `[15,24]` on the result side.
- **Old versions (≤ v0.0.10-r03) have a severe frame-drop defect on devices with "wide" frame-rate ranges**: in manual exposure the sensor frame duration was sent as the range's lower bound, physically locking the achieved rate to a low value (measured 4.98 fps into a 25 fps container on one vivo device; lowering resolution / bitrate does not avoid it — it has nothing to do with encoding load). Fixed in v0.0.11-r01 — if you see "it stutters as soon as I record / as soon as the show starts" on an old version, upgrade first.
- **Manual white balance depends on the device**: color-temperature gains require the `TRANSFORM_MATRIX` capability, and some devices (notably HarmonyOS phones running Android apps through a compatibility layer) declare it without actually adopting the gains. Two-stage fix since v0.0.11-r28: (1) the root cause of "enabling manual mode turns the whole frame green and the sliders do nothing" is fixed (gains were sent together with an identity color matrix; the app now freezes the matrix the camera's own auto white balance last computed); (2) devices that cannot adopt gains fall back to a frame-layer algorithm with an honest notice ("this device does not accept manual WB gains"); the fallback is a relative offset from auto white balance, varies by device, and still has no closed-loop calibration data. Known residual: right after returning from the settings page, some devices may show a single transitional blended frame (the camera re-attaching on the compatibility layer); it does not affect subsequent frames.
- **High-bitrate long recordings will rotate segments**: the per-segment cap is 3.5 GiB (FAT32-safe); at 50 Mbps that is ~9 minutes, so any recording over 10 minutes **will** rotate once (with a pre-warning at 85%). The rotation window is bounded, a blocked rotation fails loudly and is accounted — but the brief hitch at that moment is expected.
- **Framing aids and scene effects depend on the GPU render path**: zebras, focus peaking, glow and afterimage do not work in DIRECT (non-GPU) mode (the app says so in place).
- **HarmonyOS installation barrier**: the unsigned .hap is for structure verification only and will not install on retail devices; a signed profile must be issued by AGC/DevEco and include the target device's UDID.
- **Limited test coverage**: long-term on-device verification covers only WIKO GAR-AN60, Huawei ALN-AL00 / ADY-AL00, vivo V2505A, and one emulator; low-end devices and niche ROMs are essentially untested, and the capability-negotiation code almost surely still has unseen behaviors there. If you hit one, the diagnostic log below is a huge help.


### Updates

| Feature | Location · How to open | Usage |
|---|---|---|
| Check / download update | Settings → About → Check update | Networks only on tap; reads the latest GitHub Release; speed-tests routes and picks the fastest, falling back in speed order; no auto-check, no background networking |
| Release notes | New-version dialog | Shows that version's Release text, display only |
| Safety checks | Automatic | HTTPS only, intranet / loopback targets rejected; SHA-256 compared locally, mismatches discarded; package name and signature checked against the installed app before install |

## Download

Download from [Releases](https://github.com/himetuki/WotaGeiCam/releases/latest). Pushing a `vX.X.X` or `vX.X.X-rYY` tag produces both platforms' packages into the same Release, distinguished by the platform suffix before the extension (`-Android` / `-Harmony`):

| File | Platform / fit |
|---|---|
| `WotaGeiCam-vX.X.X-rYY-release-Android.apk` / `WotaGeiCam-vX.X.X-rYY-universal-release-Android.apk` | Android: all devices (pick universal if unsure) |
| `WotaGeiCam-vX.X.X-rYY-arm64-v8a-release-Android.apk` | Android: 64-bit ARM (most phones since 2016) |
| `WotaGeiCam-vX.X.X-rYY-armeabi-v7a-release-Android.apk` | Android: 32-bit ARM older devices |
| `WotaGeiCam-vX.X.X-rYY-unsigned-Harmony.hap` | HarmonyOS (produced when signing Secrets are not configured; see installation notes below) |

With HAP signing Secrets configured, the Release also includes `vX.X.X-rYY-signed-Harmony.hap` and a `SHA256SUMS`.

The `X.X.X-rYY` in the file name is the full version name; the number after `r` is the checkpoint index, higher is newer; the in-app "check update" compares by the same rule. Historical versions (`v0.0.9-r05` and earlier) used the old naming `WotaGeiCam-vX.Y.Z-*.apk` and only had the Android four-piece set.

## Installation and upgrade

**Android**
- Requirement: Android 10 (API 29) or later
- Upgrades install over the top; **do not uninstall first** — uninstalling wipes in-app settings and the media-library archive
- You can also check for updates and download in-app
- Pure Kotlin, no native libraries; the four APKs are identical in content; a Baseline Profile is bundled (startup hot paths precompiled for faster cold starts)
- "All files access" is available since Android 11; on Android 10 the app honestly marks it unsupported (delete / rename then only applies to videos recorded by this app)

**HarmonyOS**
- `*-unsigned-Harmony.hap` carries no release signature and is for structure verification only, not a formal install channel
- The `profile.p7b` in `*-signed-Harmony.hap` must be issued by AGC/DevEco and include the target device's UDID, or it will not install on retail devices; for development you can auto-sign in DevEco Studio and install

## Building from source

**Android** (requires JDK 17 and the Android SDK, Platform 34 / Build-Tools 33.0.1; provide the SDK location the standard way: `ANDROID_HOME` or `sdk.dir` in `Android/WotaGeiCamApp/local.properties`):

```bash
git clone https://github.com/himetuki/WotaGeiCam.git
cd WotaGeiCam/Android/WotaGeiCamApp
./gradlew :app:assembleDebug      # day-to-day build
./gradlew :app:assembleRelease    # release build (signing notes below)

# Build with a checkpoint number (versionName 0.0.10-rNN, shown in settings; the property accepts 05 or r05)
./gradlew :app:assembleRelease -Prbuild=05
```

```bash
# Run in Android/WotaGeiCamApp/. The CI-matching "four-piece set"
# (plain / universal / arm64-v8a / armeabi-v7a).
# Two passes: build the three split APKs, copy them out, then build the non-split one —
# the second pass clears the first pass's outputs from the output directory.
./gradlew :app:assembleRelease -PabiSplits=true -PapkBaseName=WotaGeiCam -Prbuild=05
mkdir -p ../../apk-out && cp app/build/outputs/apk/release/*.apk ../../apk-out/
./gradlew :app:assembleRelease -PapkBaseName=WotaGeiCam -Prbuild=05
cp app/build/outputs/apk/release/*.apk ../../apk-out/
```

Signing notes: the repository contains no signing material. To produce a properly signed release, provide `keystore.properties` (with `storeFile` / `storePassword` / `keyAlias` / `keyPassword`) and the keystore file under `Android/WotaGeiCamApp/`; when missing, the release variant falls back to debug signing for local testing only. `org.gradle.java.home` in `Android/WotaGeiCamApp/gradle.properties` records the build machine's JDK location; if yours differs, edit that line or remove it and use `JAVA_HOME` (CI does the same).

**HarmonyOS** (requires DevEco Studio's hvigor and the HarmonyOS SDK):

```bash
cd HarmonyOS/WotaGeiCamOHOS
export DEVECO_SDK_HOME="$DEVECO_HOME/sdk"   # Windows Git Bash example
./hvigorw --mode module -p product=default assembleHap
```

The output is at `HarmonyOS/WotaGeiCamOHOS/entry/build/default/outputs/default/entry-default-unsigned.hap`.
Pure-logic tests: `cd HarmonyOS/WotaGeiCamOHOS && node scripts/pure-test.mjs` (762 cases).

### Repository layout

```
WotaGeiCam/
├── Android/WotaGeiCamApp/     # Android Gradle project (committed)
├── HarmonyOS/WotaGeiCamOHOS/  # HarmonyOS DevEco project (committed)
├── .github/workflows/         # build-apk.yml + build-hap.yml (v* tag, two pipelines)
├── tools/                     # local toolchain: android-sdk (SDK/emulator), avd (VMs),
│   │                          # trace_processor, etc.; together with node_modules/ docs/ research/
│   │                          # deliverable/ agent-memory/ camera-knowledge/ .zcode/
│   │                          # .mimosa/ these are local assets (gitignored, not committed)
└── README.md LICENSE .gitignore .gitattributes
```

The two projects never import each other; they share only product semantics and the release process. The HarmonyOS side tracks changes from the Android version via "read-only diff + manual porting" — see [`HarmonyOS/WotaGeiCamOHOS/docs/UPSTREAM_SYNC.md`](HarmonyOS/WotaGeiCamOHOS/docs/UPSTREAM_SYNC.md).

## CI

Pushing a `v*` tag triggers both pipelines (you can also trigger manually from the Actions page with a checkpoint number); they build in parallel and the assets land in the same GitHub Release:

- [build-apk.yml](.github/workflows/build-apk.yml): derives the checkpoint number from the tag, builds in two passes to produce the four versioned APKs (plain / universal / arm64-v8a / armeabi-v7a) and mapping files, verifies all four are present, then publishes
- [build-hap.yml](.github/workflows/build-hap.yml): builds the HAP with Huawei's DevEco Linux command-line tools (bundled HarmonyOS 6.1.1 / API 24 SDK), including pure-logic tests and optional signing; the toolchain version and SHA256 are pinned in the workflow, overridable via the repo variables `HARMONY_CLI_TOOLS_URL` / `HARMONY_CLI_TOOLS_SHA256`

When the tag carries a checkpoint number (e.g. `v0.0.10-r05`): the APK's versionName carries `-r05` and so does the artifact name; the HAP platform only allows a `digit.digit.digit` versionName, so the package has `versionName=0.0.10` with the r number folded into `versionCode` (formula `1000000 + major*1e6 + minor*1e4 + patch*100 + r`), while the artifact name still carries `-r05`.

### Actions Secrets

| Secret | Purpose |
| --- | --- |
| `WOTA_KEYSTORE_BASE64` / `WOTA_KEYSTORE_PROPS` | Android release signing (p12/jks + properties) |
| `HAP_KEYSTORE_BASE64` / `HAP_APP_CERT_BASE64` / `HAP_PROFILE_BASE64` | HAP signing trio (exported from DevEco auto-signing) |
| `HAP_KEY_ALIAS` / `HAP_KEY_PASSWORD` / `HAP_KEYSTORE_PASSWORD` | HAP key alias and passwords |

Without HAP signing Secrets configured, only the unsigned package is produced.

## Diagnostic logs

When something is "not working but hard to describe" (e.g. internal capture rejected by the system, or recording misbehaving mid-way), you can export the app log for the developer to locate the issue (described for Android below):

- The app **has built-in logging, off by default** (takes no space; crash records are unaffected by this switch and still leave a scene on crash)
- To capture an issue: Settings → "Diagnostic logs" → turn on "Record logs" → reproduce once → tap "Share directly" to open the system share sheet; or tap "Export log" to write the merged report to `Documents/WotaGeiCam/`; turn on "Verbose log" for finer detail
- "Clear logs" empties them in one tap; log files rotate at 512 KB × 3 (~1.5 MB cap), and the exported report is capped at 2 MB overall
- The exported report includes the recording frame-ledger summary (independent of the "Record logs" switch) and crash records; logs are **generated only on your device and exported only by you**, contain no picture or sound, and are never uploaded
- Note: off by default means if you enable logging after a problem appears, the earlier scene is not kept — for unstable reproductions, enable logging first, then reproduce

## Privacy

The same principle on both platforms: the permission and purpose details below are listed for Android; the HarmonyOS version implements them per the HarmonyOS permission model.

- **The only network use is the in-app "check update / download update"**, and only when you tap it (reads the latest version from GitHub Releases and downloads the package, falling back along an HTTPS mirror chain on direct failure; after download the SHA-256 is recomputed locally and mismatches are discarded) — no auto-check, no background networking, no account system
- No footage or usage data is uploaded: no analytics, no ads, no third-party analysis
- Footage is written only to local storage; sharing exposes only three file types — finished videos, update packages, diagnostic logs — never handing the whole storage to the system share component
- The internal-capture track uses the system audio-capture mechanism, active only while recording (foreground service + persistent notification, one-tap stop)
- Permissions and purposes (Android): camera / microphone (filming), media read (library), all-files access (so deleting or renaming footage not recorded by this app does not trigger a system authorization dialog), Bluetooth (discover and control an external speaker; on Android 10/11 the system requires location permission for Bluetooth device scanning, used only for that), notifications (Android 13+ internal-capture persistent notification), install unknown apps (in-app update install), network (only for update check and download; plus the playback component's own read-only "network state" permission, which produces no network traffic)

## License

The whole project is released under the [GNU GPL v3](LICENSE) (SPDX: `GPL-3.0-only`). It started as Android-only and is now dual-version (Android + HarmonyOS); both projects are published under the same license.

Copyright © 2026 himetuki
