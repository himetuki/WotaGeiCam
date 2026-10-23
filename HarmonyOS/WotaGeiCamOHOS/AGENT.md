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

- `sync-upstream.sh` — 上游**只读对比**：以 `docs/UPSTREAM_BASE` 为基线，fetch 后列出
  新提交与语义锚点 diff，**绝不 merge**（harmony 是 OHOS-only 独立历史，与 origin/main
  无共同祖先）。上游源码用 `git show origin/main:WotaGeiCamApp/<path>` 读；
  `Android/` worktree 只是工作副本。评审/移植完成后 `--update-base` 更新基线。
- `pure-test.mjs` — 纯逻辑测试架：扫描 `test/pure/*.test.ets`，收集相对 import 闭包并转译到
  `.pure-test-tmp/`，`@ohos.*`/`@kit.*` 惰性桩；命令 `node scripts/pure-test.mjs`，当前 341 用例，
  退出码 0 才算全绿。
- `local-sign.mjs` — HAP 本地签名探针：hap-sign-tool ECC P-256 全链（keypair→CA→app/profile cert→
  sign-profile→sign-app→verify-app），材料落 `.local-signing/`（已 gitignore），产物
  `entry/build/default/outputs/default/entry-default-signed.hap`；命令 `node scripts/local-sign.mjs`。
  自签包仅作结构/签名链验证，真机安装用 DevEco Studio 自动签名。

## native 文件清单（entry/src/main/cpp/）

- `napi_init.cpp` — 模块注册（exports 表：GL 参数/GPU 录制/剪辑导出/离线修复四口）+ XComponent 回调
- `gl_render.*` — EGL/GLES 管线（上屏 + 编码面 + 光弧在线链 + 毛玻璃）
- `codec_recorder.*` — GPU 录制（OH_VideoEncoder surface + AVMuxer）
- `clip_exporter.*` — A/B 无损 remux（AVSource/AVDemuxer → AVMuxer）
- `arc_flow.*` / `arc_mend_pass.*` — 光弧在线判定与补弧离屏
- `arc_repair.*` — 离线修复 CPU 执行器（同步解码/合并/编码/mux + 音频直拷；T29/T30）
- `shaders.h` / `wota_math.h` — 着色器源码与共享角度归一
- `CMakeLists.txt` — 产物 `libentry.so`；链接 `libnative_media_{vdec,venc,avmuxer,avsource,avdemuxer,codecbase,core}`

## 纯逻辑模块速查（接线前先看这里）

- `core/`：`LevelMath`（水平仪/俯仰换算 + `LevelConst` 纯常量；显示帧换算是 roll 的唯一正确口径，
  不要在各处手写旋转）——平台面 `camera/LevelSensor`、UI 面 `ui/AttitudeMeter`。
- `player/`：`CompareTimeline`（几何+decide 内核）、`CompareAudio`（选轨计划/命令序）、`AudioSync`
  （DSP）、`ComparePractice`（练习桥）、`CompareHistory`（历史纯核心）、`TrackSync`（双轨偏移合成）、
  `PcmDecode`（PTS 落位/PCM16/24/F32 解码）、`PureLogicBarrel`（编译守卫，EntryAbility 启动调用）。
  `SyncPrefsStore`（平台件）：TrackSync 五键 ↔ `@ohos.data.preferences`；`Player` 导出/修复的
  音轨平移只消费它读到的快照（**拿不到 = 不平移**，绝不猜）。导出/修复平移的口径单一真源是
  `ClipRule.audioShiftUs` + `ClipRule.remuxAudioSampleWritable`，C++ 只是同式镜像。
- `record/`：`CaptureStateMachine`（内录授权态机）、`CaptureSilence`（静音判据+按场节流）、
  `AudioProbe`（采集算式/档位体检）、`CaptureSources`（OH_AudioCaptureSourceType 契约）、
  `ArcRepairPlan`/`ArcRepairFlow`/`ArcRepairMerge`/`ArcRepairVerdict`（离线光弧修复纯逻辑）、
  `ArcRepairSessionModel`（native 串解析/速率/NO_NEED/去重/归因/错误文案/音轨注记）、
  `ArcRepairRunner`（执行器门面：fd/轮询/取消宽限/失败 discard/可复用面板 probe 结果）。
- `pages/Player.ets`：「修复光弧」面板（T30）——probe 拦截、24/25 与死区 0/12/32、进度/取消、
  成功跳媒体库、忙碌拦返回、与导出 A/B 互斥。
- `ui/HudTokens.ets` / `ui/HudSpec.ets`：相机 HUD 令牌（深色色板、字阶 11/12/13/17、触控 48vp）与
  固定布局常量（顶栏两行 5 项、Dock 2×4）。**HUD 代码禁止 fontSize(10)**；底栏不设固定高。
- `ui/WindowChrome.ets`：沉浸式取景 + 系统栏配色 + AvoidArea 安全区；普通页恢复黑底系统栏。
  **所有外观切换经模块级 Promise 链串行**（EntryAbility.loadContent 前先 prime，禁止在页面里裸调
  window 外观接口）。
- `ui/HudPill.ets`：**@Component + @Prop** 的 HUD 胶囊（不要用按值 @Builder 传动态 label——
  值参数首帧冻结，真机表现为胶囊标签恒为 `—`）。胶囊字号 12fp + 左右 6vp，防 360vp 宽截字。
- `ui/HudMoreSheet.ets`：低频开关面板（闪光/效果/光弧/网格），复用 PillPopup 遮罩/量框。
- `update/`：应用内更新。纯逻辑 `UpdateChecker`（版本比较/镜像链/JSON 提取/说明整形/三段编排）、
  `UpdatePure`（渠道排序/URL 安全门槛/上限/过期文件判据）、`Sha256`（纯 SHA-256）；平台件
  `UpdateDownloader`（唯一允许 `@ohos.net.http` 的文件：测速选路 + 流式落盘 + 三重校验）、
  `UpdateInstaller`（结构核对 + 文件选择器另存 + 浏览器兜底）。**版本比较用 versionCode**
  （CI 注入公式），**HAP 校验值读同 tag 的 `SHA256SUMS`**（说明里的 SHA256 行是安卓 APK 的）。
- 设置页开发者入口默认隐藏：版本号 2s 内 7 连点显形（Index/GlPreview 仅此入口可达，均有页头+返回）。
- 以上模块**不得 import `@ohos.*`/`@kit.*`**；新增纯逻辑模块须进 `PureLogicBarrel`，否则 hvigor
  不可达、编译门失效。`test/pure/*.test.ets` 由 `scripts/pure-test.mjs` 转译执行。
