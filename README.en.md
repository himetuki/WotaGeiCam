# WotaGeiCam

[![Build Release APKs](https://github.com/himetuki/WotaGeiCam/actions/workflows/build-apk.yml/badge.svg)](https://github.com/himetuki/WotaGeiCam/actions/workflows/build-apk.yml)
[![Build Release HAPs](https://github.com/himetuki/WotaGeiCam/actions/workflows/build-hap.yml/badge.svg)](https://github.com/himetuki/WotaGeiCam/actions/workflows/build-hap.yml)

**[中文说明](README.md)**

A personal open-source camera project aimed at WOTA-gei / live-show filming: manual exposure and focus control, real-time RGB curve grading, customizable framing aids, and a tool chain built around light-stick arc shooting — forced slow shutter, frame-dropping with arc merging plus offline arc repair, ambient + internal-capture dual-audio recording, and dual-track comparison playback.

This is a hobby project by a single developer, not a mature product: the list of long-term-verified devices is short, and the differences between vendors' camera HALs far exceed what one person can explore. Known issues are listed honestly under [Known Issues and Limitations](#known-issues-and-limitations). If you run into odd behavior, please open an issue with your device model and symptoms (a diagnostic log is even better) — user feedback is the main way this project moves forward; patience with the rough edges is equally appreciated.

This repository is a single-tree monorepo: `Android/WotaGeiCamApp/` is the original Android project (the functional definition of the product), and `HarmonyOS/WotaGeiCamOHOS/` is the HarmonyOS port of the same product.

> The project is in an early stage; features and UI iterate quickly. The latest version is whatever the [Releases](https://github.com/himetuki/WotaGeiCam/releases/latest) page says.

## Features

The feature list below is based on the Android version (the functional definition of the product); the HarmonyOS version is a port of the same product and is being aligned item by item — see [`HarmonyOS/WotaGeiCamOHOS/docs/PORT_MAP.md`](HarmonyOS/WotaGeiCamOHOS/docs/PORT_MAP.md) for the porting map and progress; new Android features are ported over in batches.

### Shooting controls
- Manual exposure: ISO / shutter / EV; the shutter supports forced 1/24 and 1/25 slow tiers (long exposure keeps light arcs continuous), and available tiers are validated against the current frame period
- Frame-rate control (with frame-extraction conversion for non-exact tiers), white-balance color temperature, manual focus and zoom
- Real-time RGB curve editing: the editor dialog can be scaled by dragging its right edge (proportionally; the default size is the minimum and only grows; nodes and line widths scale with the canvas), and the "reset" fan button restores the identity curve and collapses the dialog back to minimum size
- Viewfinder gestures: tap to focus and meter; double-tap to focus and lock at that point; while locked, a single tap does not refocus (a hint is shown the first time); long-press to unlock and refocus there; in auto-exposure modes, a half-second long-press locks / unlocks AE; the focus panel also has lock / unlock entries
- Parameter persistence: parameters changed on the recording page (ISO, shutter, frame rate, white balance, zoom, resolution, …) are remembered across restarts; sliders save on release
- Graduated sliders: sliders run on per-device capability tiers while the readout shows the true value; the "slider snapping" settings page can switch each parameter to a continuous scale (e.g. zoom to 1.2×, 1.7×)
- Tapping or long-pressing a parameter pill opens the nearest adjustment panel; during recording only ISO, exposure compensation, white balance, zoom and focus stay adjustable, everything else is read-only
- Gesture-navigation adaptation: on devices with a bottom gesture bar ("the white pill"), the recording page's bottom dock automatically clears the gesture area (the shutter / knobs no longer fight the system bar), and the settings page and built-in player hide the navigation bar (no white band); 3-button devices are unchanged
- Camera parameters are always read from device capabilities at runtime and adapt per device — no per-model values are hard-coded

### Framing aids
- Zebras and focus peaking (adjustable threshold / density / intensity / color; these two do not work when not using GPU rendering)
- Real-time glow compositing: highlight extraction + diffusion; two color modes — "pixel's own color" (highlights glow in their own hue) and "palette color only" (highlights glow in the single picked color); brightness threshold 60–100% (100% = only the brightest pixels of the frame glow); double-tap the palette to reset it; applied to both the preview monitor and the recording
- Reference lines / grids: rule of thirds, diagonals, crosshair, broadcast safe frame, safe areas, grid, center markers, horizon line, upper / middle / lower red lines; multi-select
- Level and pitch gauges (toggleable, with a light vibration when the device levels)
- Audio volume meter and persistent readouts (shutter / frame rate / bitrate / ISO / EV / white balance / zoom), each individually hideable
- Custom control layout: drag to arrange in the editor page; the dock and the readout block can be placed freely, and the nearest-panel popups follow each control's anchor

### Recording
- Two recording engines; adjustable bitrate
- Honest frame-rate reporting: the achieved frame rate is decided by the camera HAL — the app can only negotiate, not guarantee it. When the measured rate stays below ~70% of the selected tier, a top-bar warning shows the actual value ("the footage will stutter"); when the tier is a device-approximate tier (※) and manual exposure is on, the viewfinder pre-warns that low light may drop it further (see [Known Issues](#known-issues-and-limitations))
- Honest white-balance fallback: when a device's HAL ignores manual color-temperature gains (capability not declared, or detected at runtime that the gains are not adopted), the app switches to a frame-layer algorithm and says so at runtime
- Internal-capture audio track (optional): besides the ambient microphone track, audio being played by apps / the system can be captured into a second track; foreground service with a stop button in the notification; notification permission requested on first use on newer Android
- Audio source and Bluetooth: the source panel switches ambient / capture, and offers media volume, play/pause and battery readouts for an external Bluetooth speaker (pairing happens in system settings); the "audio" control carries a pale-blue ring hinting the panel, turning accent-colored while capturing
- The audio source is not remembered across cold starts: ambient by default, so the capture permission isn't requested every launch
- Automatic segment rotation: when a single file reaches 3.5 GiB (FAT32-safe limit) a new segment starts automatically — names stay sequential and no content is lost; a top-bar pre-warning appears at 85% of the cap ("about to rotate"); if the platform stops recording on its own at the cap, already-finished segments are kept
- Split-screen reference: the main view keeps recording while the right half loads a reference video for side-by-side shooting (the split toggle locks during recording; open it before you start)
- Recording ramp-up buffering: video samples already encoded in the instant recording starts (before encoder / muxer are ready) are written in arrival order instead of being dropped
- Frame ledger: per-stage frame counters (camera → render → encoder → muxer) summarized in one line at each segment end, visible in the diagnostic log export, to locate which stage drops frames
- If segment re-build fails, the encoders / muxers built so far are reclaimed before retrying, so later recordings can still start

### Light-arc tool chain
- In-recording frame-rate conversion: when the device has no native 24 / 25 tier, recording extracts frames down to the selected rate — "auto-extract with arc merging" merges dropped frames into their neighbors to keep arcs continuous, "extract only" keeps the plain result; dropped frame slots are recorded in a `.drops.json` sidecar next to the output
- Extraction phase aligns timestamps with a tolerance of 10% of the slot width to absorb jitter; long-run frame counts stay exact
- Arc merging: whole-pixel selection + differential dead band, shared by recording and offline repair, CPU and GPU; the "merge threshold" slider (default 12, range 0–32) is adjustable in three places — the frame-rate panel, the player's arc-repair panel, and the settings defaults; higher is more conservative, lower merges noise in too
- Offline arc repair: extraction + merging for existing footage (same semantics as the frame-rate panel), GPU-accelerated or pure CPU; results are written as new files and never overwrite the original; failed half-products are recycled and never enter the media library

### Playback and library
- Built-in media library: tags, favorites, trash, rename, share, long-press multi-select
- Player: A-B loop, frame stepping, speed (0.1x–1x), pinch zoom/pan, horizontal mirror
- Clip export: export by A/B range; audio track selectable (ambient by default; dual-track footage can switch to capture; single-track footage has ambient only), only the selected track is written; if the selected track has no samples in the range, the export falls back to video-only. Two export modes: **lossless** (no re-encode; the picture is exactly what was recorded) or **re-rendered** (re-encode with optional bilateral denoise and glow — choosing it reveals in-sheet controls for glow on/off, brightness threshold, strength, blend and denoise on/off, initialised from the current settings and applied to this export only; its quality cost and denoise strength vary by footage and device, with no quantified conclusion on high-noise material yet); re-render is greyed out automatically when there is nothing to apply
- Dual-track comparison playback: one timeline + two video tracks (a side without footage stays black); audio switches between sides and between ambient / capture tracks, one track per side at a time, with audio-based alignment; comparison history is kept
- Cold-start maintenance: cleans legacy temp-name leftovers and keeps the library index consistent with the files on disk; before archiving, target names are checked and existing files are never overwritten

## Known Issues and Limitations

An honest list of the currently known problem surface (verified through v0.0.11-r28; updated as feedback comes in):

- **The achieved frame rate is decided by the camera HAL; the app can only negotiate, never guarantee it.** The frame-rate decision only guarantees "the value we send was declared by the device"; low light, slow shutters, or vendor policy can push the achieved rate below the selected tier. The app warns with the measured value once the rate stays below ~70% of the target, but **does not force-lock the tier** — forcing it freezes the viewfinder on some devices (the lesson of another, already-fixed defect). Some vendor HALs also widen the range beyond a requested fixed tier: on one Huawei device a requested `[24,24]` came back as `[15,24]` on the result side.
- **Old versions (≤ v0.0.10-r03) have a severe frame-drop defect on devices with "wide" frame-rate ranges**: in manual exposure the sensor frame duration was sent as the range's lower bound, physically locking the achieved rate to a low value (measured 4.98 fps into a 25 fps container on one vivo device; lowering resolution / bitrate does not avoid it — it has nothing to do with encoding load). Fixed in v0.0.11-r01 — if you see "it stutters as soon as I record / as soon as the show starts" on an old version, upgrade first.
- **Manual white balance depends on the device**: color-temperature gains require the `TRANSFORM_MATRIX` capability, and some devices (notably HarmonyOS phones running Android apps through a compatibility layer) declare it without actually adopting the gains. Two-stage fix since v0.0.11-r28: (1) the root cause of "enabling manual mode turns the whole frame green and the sliders do nothing" is fixed (gains were sent together with an identity color matrix; the app now freezes the matrix the camera's own auto white balance last computed); (2) devices that cannot adopt gains fall back to the frame-layer algorithm with an honest notice ("this device does not accept manual WB gains"); the fallback is a relative offset from auto white balance, varies by device, and still has no closed-loop calibration data. Known residual: right after returning from the settings page, some devices may show a single transitional blended frame (the camera re-attaching on the compatibility layer); it does not affect subsequent frames.
- **High-bitrate long recordings will rotate segments**: the per-segment cap is 3.5 GiB (FAT32-safe); at 50 Mbps that is ~9 minutes, so any recording over 10 minutes **will** rotate once (with a pre-warning at 85%). The rotation window is bounded, a blocked rotation fails loudly and is accounted — but the brief hitch at that moment is expected.
- **Framing aids depend on the GPU render path**: zebras and focus peaking do not work in DIRECT (non-GPU) mode (the app says so in place).
- **HarmonyOS installation barrier**: the unsigned .hap is for structure verification only and will not install on retail devices; a signed profile must be issued by AGC/DevEco and include the target device's UDID.
- **Limited test coverage**: long-term on-device verification covers only WIKO GAR-AN60, Huawei ALN-AL00 / ADY-AL00, vivo V2505A, and one emulator; low-end devices and niche ROMs are essentially untested, and the capability-negotiation code almost surely still has unseen behaviors there. If you hit one, the diagnostic log below is a huge help.

### Updates
- In-app "check updates / download update": **the only network use, and only on your tap** — reads the latest version from GitHub Releases and downloads the package (with an HTTPS mirror chain fallback); no auto-check, no auto-download, no background networking
- Before each download, every route (direct + mirrors) is speed-tested (256 KiB samples) and the fastest is picked, falling back in speed order; if all speed tests fail, the old "direct first, then mirror list" order is used
- When a new version is found, the dialog shows that version's release notes (the GitHub Release text with Markdown stripped; notes read via direct or mirror are both shown; display only, never part of the install decision)
- Download URLs are always rebuilt from repo constants with verified asset names over HTTPS; encoded IP literals and targets pointing at intranet / loopback are rejected; SHA-256 is read only from official GitHub APIs and compared locally after download — mismatches are discarded; before install, the package name and signature are checked against the installed app and mismatches are never handed to the system installer

## Download

Grab it from [Releases](https://github.com/himetuki/WotaGeiCam/releases/latest). Pushing a `vX.X.X` or `vX.X.X-rYY` tag produces both platforms' packages into the same Release, distinguished by the platform suffix before the extension (`-Android` / `-Harmony`):

| File | Platform / fit |
|---|---|
| `WotaGeiCam-vX.X.X-rYY-release-Android.apk` / `WotaGeiCam-vX.X.X-rYY-universal-release-Android.apk` | Android: all devices (pick universal if unsure) |
| `WotaGeiCam-vX.X.X-rYY-arm64-v8a-release-Android.apk` | Android: 64-bit ARM (most phones after 2016) |
| `WotaGeiCam-vX.X.X-rYY-armeabi-v7a-release-Android.apk` | Android: 32-bit ARM legacy devices |
| `WotaGeiCam-vX.X.X-rYY-unsigned-Harmony.hap` | HarmonyOS (produced when signing secrets are absent; see install notes) |

With HAP signing secrets configured, the Release also carries `vX.X.X-rYY-signed-Harmony.hap` plus `SHA256SUMS`.

The `X.X.X-rYY` in file names is the full version name; the number after `r` is the checkpoint index — bigger is newer. The in-app update check compares by the same rule. Historical versions (≤ `v0.0.9-r05`) use the old naming `WotaGeiCam-vX.Y.Z-*.apk` with only the Android four-piece set.

## Install & upgrade

**Android**
- Requires Android 10 (API 29) or later
- Upgrades install over the top; **do not uninstall first** — uninstalling wipes in-app settings and the media-library archive
- You can also check updates in-app and download there
- Pure Kotlin, no native libraries — all four APKs are equivalent; packages embed a Baseline Profile (hot paths precompiled at install; faster cold start)
- "All files access" exists from Android 11; on Android 10 the app labels it unsupported in-app (there, delete / rename only affects footage recorded by this app)

**HarmonyOS**
- `*-unsigned-Harmony.hap` carries no release signature; structure verification only, not an install channel
- For `*-signed-Harmony.hap`, the `profile.p7b` must be issued by AGC/DevEco and include the target device's UDID, otherwise retail devices will refuse it; development can use DevEco Studio auto-signing

## Build from source

**Android** (JDK 17 + Android SDK, Platform 34 / Build-Tools 33.0.1; SDK location provided the standard way: `ANDROID_HOME`, or `sdk.dir` in `Android/WotaGeiCamApp/local.properties`):

```bash
git clone https://github.com/himetuki/WotaGeiCam.git
cd WotaGeiCam/Android/WotaGeiCamApp
./gradlew :app:assembleDebug      # everyday build
./gradlew :app:assembleRelease    # release build (signing below)

# A checkpoint-numbered build (version name 0.0.10-rNN, visible in-app; the property accepts 05 or r05)
./gradlew :app:assembleRelease -Prbuild=05
```

```bash
# Run inside Android/WotaGeiCamApp/. The CI-equivalent "four-piece set"
# (plain / universal / arm64-v8a / armeabi-v7a), built in two passes:
# the second pass cleans the first pass's outputs, so copy them out in between.
./gradlew :app:assembleRelease -PabiSplits=true -PapkBaseName=WotaGeiCam -Prbuild=05
mkdir -p ../../apk-out && cp app/build/outputs/apk/release/*.apk ../../apk-out/
./gradlew :app:assembleRelease -PapkBaseName=WotaGeiCam -Prbuild=05
cp app/build/outputs/apk/release/*.apk ../../apk-out/
```

Signing: the repo contains no signing material. For properly signed release packages, provide `keystore.properties` (`storeFile` / `storePassword` / `keyAlias` / `keyPassword`) and a keystore under `Android/WotaGeiCamApp/`; when absent, the release variant falls back to debug signing for local testing only. `org.gradle.java.home` in `Android/WotaGeiCamApp/gradle.properties` records the packaging machine's JDK path — change or delete it (falling back to `JAVA_HOME`) on machines with a different JDK; CI does the same.

**HarmonyOS** (DevEco Studio's hvigor + HarmonyOS SDK):

```bash
cd HarmonyOS/WotaGeiCamOHOS
export DEVECO_SDK_HOME="$DEVECO_HOME/sdk"   # Windows Git Bash example
./hvigorw --mode module -p product=default assembleHap
```

Output: `HarmonyOS/WotaGeiCamOHOS/entry/build/default/outputs/default/entry-default-unsigned.hap`.
Pure-logic tests: `cd HarmonyOS/WotaGeiCamOHOS && node scripts/pure-test.mjs` (762 cases).

### Repository layout

```
WotaGeiCam/
├── Android/WotaGeiCamApp/     # Android Gradle project (tracked)
├── HarmonyOS/WotaGeiCamOHOS/  # HarmonyOS DevEco project (tracked)
├── .github/workflows/         # build-apk.yml + build-hap.yml (v* tag dual pipeline)
├── tools/                     # local toolchain: android-sdk (SDK/emulators), avd (VMs),
│   │                          # trace_processor, etc.; together with node_modules/ docs/ research/
│   │                          # deliverable/ agent-memory/ camera-knowledge/ .zcode/
│   │                          # .mimosa/ these are local assets (gitignored)
└── README.md LICENSE .gitignore .gitattributes
```

The two projects never import each other; they share only product semantics and the release process. The HarmonyOS side tracks Android upstream changes via "read-only diff + manual porting" — see [`HarmonyOS/WotaGeiCamOHOS/docs/UPSTREAM_SYNC.md`](HarmonyOS/WotaGeiCamOHOS/docs/UPSTREAM_SYNC.md).

## CI

Pushing a `v*` tag triggers both pipelines (manual runs with a checkpoint number also work); they build in parallel and upload assets to the same GitHub Release:

- [build-apk.yml](.github/workflows/build-apk.yml): derives the checkpoint from the tag, builds the four versioned APKs (plain / universal / arm64-v8a / armeabi-v7a) plus mapping files in two passes, verifies all four and publishes
- [build-hap.yml](.github/workflows/build-hap.yml): builds the HAP with Huawei's DevEco Linux CLI (bundled HarmonyOS 6.1.1 / API 24 SDK), including pure-logic tests and optional signing; toolchain version and SHA256 are pinned in the workflow, overridable via repo variables `HARMONY_CLI_TOOLS_URL` / `HARMONY_CLI_TOOLS_SHA256`

Tags with a checkpoint (e.g. `v0.0.10-r05`): APK versionName carries `-r05`, and so do artifact names; HAP versionName only allows `number.number.number`, so the package keeps `versionName=0.0.10` while the r-number folds into `versionCode` (`1000000 + major*1e6 + minor*1e4 + patch*100 + r`); artifact names still carry `-r05`.

### Actions Secrets

| Secret | Purpose |
| --- | --- |
| `WOTA_KEYSTORE_BASE64` / `WOTA_KEYSTORE_PROPS` | Android release signing (p12/jks + properties) |
| `HAP_KEYSTORE_BASE64` / `HAP_APP_CERT_BASE64` / `HAP_PROFILE_BASE64` | HAP signing trio (from DevEco auto-signing) |
| `HAP_KEY_ALIAS` / `HAP_KEY_PASSWORD` / `HAP_KEYSTORE_PASSWORD` | HAP key alias and passwords |

Without HAP signing secrets, only unsigned .hap packages are produced.

## Diagnostic logs

When "something is off but I can't describe it" (e.g. capture rejected by the system, mid-recording failure), the app's log can be exported for triage (Android wording below):

- The app **ships with logging, off by default** (no storage cost; crash records bypass this switch and are kept regardless)
- To capture an issue: Settings → "Diagnostic log" → enable "Record log" → reproduce once → "Share directly" opens the system share sheet; or "Export log" writes the merged report to `Documents/WotaGeiCam/`; enable "Verbose log" for more detail
- "Clear log" wipes everything; the log rotates at 512 KB × 3 (~1.5 MB cap) and the exported report is capped at 2 MB overall
- The report includes the recording frame-ledger summary (independent of the "Record log" switch) and crash records; logs **are generated locally and exported only by you**, contain no picture or sound, and are never uploaded
- Note: because it is off by default, a problem that already happened leaves no trace — for flaky issues, turn logging on first, then reproduce

## Privacy

One principle on both platforms; the permission list below is the Android wording, the HarmonyOS port maps it to the HarmonyOS permission model.

- **The only networking is the in-app "check updates / download update", and only on your tap** (reads the latest version from GitHub Releases and downloads the package, with mirror fallback; SHA-256 recomputed locally after download, mismatches discarded) — no auto-check, no background traffic, no accounts
- Nothing you shoot and no usage data is uploaded: no analytics, no ads, no third-party SDKs
- Footage is written to local storage only; sharing exposes only finished videos, update packages, and diagnostic logs — never whole-storage access to the system share sheet
- The internal-capture track uses the system audio-capture mechanism and only exists while recording (foreground service + persistent notification, one-tap stop)
- Permissions and purposes (Android): camera / microphone (filming), media reading (library), all-files access (avoids the system picker when deleting or renaming footage not recorded by this app), Bluetooth (discovering and controlling an external speaker; on Android 10/11 the system requires location permission for Bluetooth scans, for that purpose only), notifications (Android 13+ capture foreground service), install-unknown-apps (in-app updates), network (update check/download only; a "network state" read-only permission comes with the playback component and performs no networking)

## License

The project is released under the [GNU GPL v3](LICENSE) (SPDX: `GPL-3.0-only`): the `Android/` subtree inherits the upstream Android project's GPL v3, and the HarmonyOS port is published under the same license.

Copyright © 2026 himetuki
