# OpenHarmony Application Template Guide

This is an OpenHarmony (stage-model) application template. **Only read the 3 files listed below** — do NOT glob, search, or read any other files unless you need to modify labels/icons.

## Must-read files (1 batch read)

| File | Why |
|------|-----|
| `entry/src/main/resources/base/profile/main_pages.json` | Page routing list — must update when adding pages |
| `entry/src/main/ets/pages/Index.ets` | @Entry @Component — rewrite this for the user's feature |
| `entry/src/main/ets/entryability/EntryAbility.ets` | `windowStage.loadContent('pages/Index')` — must stay in sync with main_pages.json |

## On-demand files (only read when needed)

- `AppScope/resources/base/element/string.json` — key `"app_name"` → `"value": "MyApplication"`; only when renaming display name
- `entry/src/main/resources/base/element/string.json` — keys `"EntryAbility_label"` / `"EntryAbility_desc"`; only when changing ability label/description
- `AppScope/app.json5` — key `bundleName` under `app`; only when changing bundle ID

## Skip entirely

`EntryBackupAbility.ets`, `build-profile.json5`, `code-linter.json5`, `hvigor*`, `oh-package.json5`, `obfuscation-rules.txt`, `.gitignore`, `module.json5`, all media/color/float resources

## What to modify

1. `Index.ets` — rewrite page content; keep `@Entry @Component struct Index`
2. `main_pages.json` — add page paths as pages are created
3. `EntryAbility.ets` — update `loadContent()` if first page changes; must match main_pages.json first entry
4. `app_name` in `AppScope/resources/base/element/string.json` — if user wants a different display name
5. `bundleName` in `AppScope/app.json5` — if user wants a different bundle ID

## Invariants (do NOT change)

- `module.json5`: `module.type` = `"entry"`, `module.mainElement` = `"EntryAbility"` — no need to read this file, it rarely changes
- `module.json5`: `abilities[0].skills` must keep `entity.system.home` + `ohos.want.action.home`
- `EntryAbility.ets` must extend `UIAbility`
- `entry/build-profile.json5`: `apiType` must remain `"stageMode"`

## 工程脚本（WotaGeiCamOHOS/scripts/）

- `sync-upstream.sh` — 一条命令同步上游 main 到 harmony（只读上游镜像，改动只落 `WotaGeiCamOHOS/`）。
- `pure-test.mjs` — 纯逻辑测试架：扫描 `test/pure/*.test.ets`，收集相对 import 闭包并转译到
  `.pure-test-tmp/`，`@ohos.*`/`@kit.*` 惰性桩；命令 `node scripts/pure-test.mjs`，当前 200 用例，
  退出码 0 才算全绿。
- `local-sign.mjs` — HAP 本地签名探针：hap-sign-tool ECC P-256 全链（keypair→CA→app/profile cert→
  sign-profile→sign-app→verify-app），材料落 `.local-signing/`（已 gitignore），产物
  `entry/build/default/outputs/default/entry-default-signed.hap`；命令 `node scripts/local-sign.mjs`。
  自签包仅作结构/签名链验证，真机安装用 DevEco Studio 自动签名。

## 纯逻辑模块速查（接线前先看这里）

- `player/`：`CompareTimeline`（几何+decide 内核）、`CompareAudio`（选轨计划/命令序）、`AudioSync`
  （DSP）、`ComparePractice`（练习桥）、`CompareHistory`（历史纯核心）、`TrackSync`（双轨偏移合成）、
  `PcmDecode`（PTS 落位/PCM16/24/F32 解码）、`PureLogicBarrel`（编译守卫，EntryAbility 启动调用）。
- `record/`：`CaptureStateMachine`（内录授权态机）、`CaptureSilence`（静音判据+按场节流）、
  `AudioProbe`（采集算式/档位体检）、`CaptureSources`（OH_AudioCaptureSourceType 契约）、
  `ArcRepairPlan`/`ArcRepairFlow`/`ArcRepairMerge`/`ArcRepairVerdict`（离线光弧修复纯逻辑，
  执行器 T29）。
- 以上模块**不得 import `@ohos.*`/`@kit.*`**；新增纯逻辑模块须进 `PureLogicBarrel`，否则 hvigor
  不可达、编译门失效。`test/pure/*.test.ets` 由 `scripts/pure-test.mjs` 转译执行。
