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
  `.pure-test-tmp/`，`@ohos.*`/`@kit.*` 惰性桩；命令 `node scripts/pure-test.mjs`，当前 762 用例（73 文件），
  退出码 0 才算全绿。
- `device-check.mjs` — 真机预检/走查：`node scripts/device-check.mjs`（预检）、`log`（hilog 实时）、
  `check`（23 条待验清单操作索引，离线可打）。
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
  `RefLine`（参考线 11 档位掩码 + `inscribedBox`/`horizonEndpoints` 纯函数）——UI 面 `ui/RefLineOverlay`；
  掩码位值是持久化契约（`default_ref_lines`），只许追加不许改值。
  `DirectDisplay`（DIRECT 直显显示矩阵 `matrix`：顺时针旋转 + 等比居中信箱黑边 + 镜像作用在旋转之后的
  屏幕横轴；任一尺寸 ≤0 退单位阵）、`FrameRateFallback`（`ceilingFps` 只排除 cap 截断后不可用的范围，
  无退档目标返回 0）、`camera/GlRenderSuppression` 的 `degradeSuppressed`（停止序列 / 换段重挂两窗口析取）。
  **grep 不到接线方时不要伪造接线**：这三件在本侧暂无 ArkTS 消费方（没有 DIRECT 直显通道，也没有
  `Camera2Engine` 那套退档/降级窗口；抑制判定在 native `cpp/gl_render.cpp`），只交付纯函数 + 全表用例。
  `HudMetrics`（J1：`hudPerRowFor`/`hudRoomDp`/`hudChipHeightDp`/`hudStripHeightDp`，读数块换行档与高度预测）、
  `HudPageGrid`（J1：整页页格数学 `pageCellPx`/`pageGridBoxOf`/`pageCellAtPointer`/`pageCellsBlocked`，
  与 Dock 内格同源同尺）、`HudLayout`（J1 位置表类型/`hud_layout` 编解码/安全区钳制/`dropZoneOf`；
  J2 网格几何与拖放：`gridBoxOf`/`editableGridRowsOf`/`clampCellToBox`/`cellDropOf`/`freeCellNear`/
  `resolveCells`/`defaultCellsOf`/`cellGroupMatesOf`/`gridPitchPx`/`gridRowPitchPx`/`hudGridRowPitchPx`/
  `gridPlacementOf`/`cellAtPointer`/`entryHitIndex`/`reservedGridRowsOf`/`reservedGridColsOf`/
  `gridColFromEndOf`/`gridItems`/`defaultGridOf`/`occupiedCells`/`residentsOf`/`pinned`/`placeEntryAt`/
  `moveEntryTo`/`sourceZoneOf`/`droppedCellHeightPx`/`dockLowerY`/`dockUpperY`/`dockSnapY`/
  `dockDragBlocked`/`zoneAt`/`overlappingZones`/`dropIndexFor`/`areaForRightDock`/`hudRowGroups`/
  `rightDockPacksLevelAndVolume`/`planReadoutRow`/`readoutStripWidthDp`/`readoutRoomBesideDockDp`）。
  **三件同属 HUD 可编排布局地基，目前无 UI 消费方**（J3 编辑器 UI 与录制页消费）。
  **铁律（#69）**：跨度/实测高/格距/带边界一类形参（`cellRowSpan`、`clampCellToBox`、`freeCellNear`、
  `cellDropOf`、`resolveCells`、`reservedGridRowsOf`、`gridPlacementOf`、`planReadoutRow` 的
  `landscape`/`readoutWidthDp`…）**一律没有默认值**——给了默认值，新调用点漏挂就会静默退回旧行为；
  ArkTS 里同样必须由调用方显式喂入。
  `hud_layout` 串是**逐字节持久化契约**，改分隔符/后缀顺序等于改存量配置语义（J2 一个字符未动）。
- `player/`：`CompareTimeline`（几何+decide 内核）、`CompareAudio`（选轨计划/命令序）、`AudioSync`
  （DSP）、`ComparePractice`（练习桥）、`CompareHistory`（历史纯核心）、`TrackSync`（双轨偏移合成）、
  `PcmDecode`（PTS 落位/PCM16/24/F32 解码）、`PureLogicBarrel`（编译守卫，EntryAbility 启动调用）。
  `SyncPrefsStore`（平台件）：TrackSync 五键 ↔ `@ohos.data.preferences`；`Player` 导出/修复的
  音轨平移只消费它读到的快照（**拿不到 = 不平移**，绝不猜）。导出/修复平移的口径单一真源是
  `ClipRule.audioShiftUs` + `ClipRule.remuxAudioSampleWritable`，C++ 只是同式镜像。
- `media/CustomTags`（在 MediaTag.ets 内）：自定义页签派生（createdAt 升序+去重）——
  Gallery 页签行与标签弹层消费；加/删走 MediaRepo.addTag/removeTag（normalize 守卫）。
- `media/` 决策钉子：**重命名不迁**（媒体 ID=相对路径 32 位 FNV-1a，改名即换 id——标签/回收站/
  系列成员失联；前置 id 架构升级，守卫钉住 Gallery 无重命名入口）；组卡长按开分段清单、
  「分段合并」开关关 = `galleryEntries(clips, false)` 扁平基线。
- `media/`：`MediaRepo`（扫描/联表/探测）、`MediaTag`（页签与过滤器）、`MediaFormat`（时长/大小/分组）、
  `SeriesGroup`（分段归组：`groupSpans`/`galleryEntries`/`expandSeriesTargets`）。**组级动作必须经
  `expandSeriesTargets` 扇出**，否则会出现「只动首段」的半截状态。`MimeMap`（MIME/UTD/title 纯映射，
  平台件不许手写类型串）、`WotaShare`（systemShare 面板）、`ThumbLru`+`ThumbLoader`（缩略图，
  **驱逐不 release**——PixelMap 可能正在显示）、`AlbumSaver`（showAssetsCreationDialog，零权限）。
- `bt/`：`BtSpeakerModel`（纯逻辑：合并/排序/文案）+ `BtSpeakerController`（平台：配对/A2DP/电量）。
  **减法红线**（接线守卫钉住）：不得引入 setVolume / avsession / startBluetoothDiscovery——
  音量写、播控、扫描发现在 OHOS 无三方等价物（登记 PORT_MAP BT 节），不许"补齐"。
- `record/AvScreenProbeModel.ets`（纯）+ `AvScreenProbeRunner.ets` + `cpp/avscreen_probe.*`：
  T27-1 平台探针（AVScreenCapture 双源取证）。**链接库名是 sysroot 实名
  `libnative_avscreen_capture.so`**（不是 libnative_media_*）；错误码不解读、原样上报——
  真机取证原则。设备到位后：`node scripts/device-check.mjs` 预检 → Index 页点探针。
- `record/RecordTask.ets`：录制长时任务（启停经 `RecordTaskGate` 幂等闸；失败只记日志不阻塞录制；
  BackgroundMode 用 TASK_KEEPING——无视频录制专属档，T27-3 内录后切 AUDIO_RECORDING）。
- `record/`：`CaptureStateMachine`（内录授权态机）、`CaptureSilence`（静音判据+按场节流）、
  `AudioProbe`（采集算式/档位体检）、`CaptureSources`（OH_AudioCaptureSourceType 契约）、
  `ArcRepairPlan`/`ArcRepairFlow`/`ArcRepairMerge`/`ArcRepairVerdict`（离线光弧修复纯逻辑）、
  `ArcRepairSessionModel`（native 串解析/速率/NO_NEED/去重/归因/错误文案/音轨注记）、
  `ArcRepairRunner`（执行器门面：fd/轮询/取消宽限/失败 discard/可复用面板 probe 结果）。
- `pages/Player.ets`：「修复光弧」面板（T30）——probe 拦截、24/25 与死区 0/12/32、进度/取消、
  成功跳媒体库、忙碌拦返回、与导出 A/B 互斥。
- `ui/HudTokens.ets` / `ui/HudSpec.ets`：相机 HUD 令牌（深色色板、字阶 11/12/13/17、触控 48vp）与
  固定布局常量（顶栏固定两行 = 镜头/画幅/电量/空间 + 时长、Dock 2×4；**HudItem 集的读数
  快门/ISO/EV/WB/帧率/码率/变焦只出现在 hud_items 读数条**，键 `hud_items`、位运算只经
  `HudItems.isOn/toggle/maskOrDefault`——换行档/分组消费 J1/J2 的 `hudPerRowFor`/`hudRoomDp`/
  `hudRowGroups(READOUT)`，可用宽必须与换行档同一份实测宽；**顺序来自 `hud_layout` 位置表**
  （`readoutTable().visibleOrderOf`，编辑页 `pages/HudEditor` 经 `withZoneOrder`→`encode` 落盘；
  默认表 = 枚举声明序）。**HUD 代码禁止 fontSize(10)**；底栏不设固定高。
- `ui/WindowChrome.ets`：沉浸式取景 + 系统栏配色 + AvoidArea 安全区；普通页恢复黑底系统栏；
  **applyOrientation**（UO 批）：界面方向落窗（竖屏固定默认，`ui_orientation` 键；冷启
  EntryAbility 落一次、设置页点选即落——窗口级 sticky 全页生效）。
- `core/UserSettings.ets`：设置数值档纯算式（compareTickMsOf 钳 100..1000 缺 200、
  textScalePctOf 钳 80..120 缺 100、倍率换算）——Compare/Camera/Index 消费。
- `core/FocusTap.ets`：tap-to-focus 归一化纯算式（窗口 vp → [0,1]，钳 2%..98%）——
  Camera 页 HUD 列 onClick 消费；**XComponent(SURFACE) 的 onClick 不触发**（平台行为，
  别往它上面挂手势）。
- `core/SheetSpec.ets`：半模态尺寸纪律纯算式（480 上限/320 最小高/短边 90%/8 信号栏/
  600 居中断点/48 顶栏兜底）——inset 由调用方运行时读入，半模态面板批消费。
- `core/UIOrientation.ets`：方向两档纯逻辑（产出 window.Orientation 契约数值 6/7，
  零平台 import；本侧默认 PORTRAIT 偏离上游 LANDSCAPE——T31 竖屏取证设计）。
  **所有外观切换经模块级 Promise 链串行**（EntryAbility.loadContent 前先 prime，禁止在页面里裸调
  window 外观接口）。
- `ui/HudPill.ets`：**@Component + @Prop** 的 HUD 胶囊（不要用按值 @Builder 传动态 label——
  值参数首帧冻结，真机表现为胶囊标签恒为 `—`）。胶囊字号 12fp + 左右 6vp，防 360vp 宽截字。
- `ui/HudMoreSheet.ets`：低频开关面板（闪光/效果/光弧/网格），复用 PillPopup 遮罩/量框。
- `ui/ParamsPersist.ets`（纯逻辑）+ `ui/ParamsStore.ets`（平台件）：录制页高级参数持久化（I 批）。
  `PARAMS_KEYS` 24 键白名单——**键名是持久化契约**（逐字等于上游 `WotaSettings.KEY_*`，只许追加/改值
  语义，不许改名）；`restore` 冷启只写 value（range/enabled/exact 与机型域钳制归 `applyAbility`）；
  `ParamsPersistSession` 影子只写与影子不同的字段（防录制页陈旧总线覆盖设置页）。平台读写
  （`getPreferencesSync` / `putSync`+`flushSync`）只许在 `ParamsStore`，与 `SettingsStore` 共用
  `wota_camera_settings`。排除清单（renderMode/sampleRate/audioEnabled/focusPoint/curve/
  arc_convert_mode）不许"补齐"。`arcMode`/`grid` 留在 SettingsStore（登记偏离，不折进 24 键）。
- `update/`：应用内更新。纯逻辑 `UpdateChecker`（版本比较/镜像链/JSON 提取/说明整形/三段编排）、
  `UpdatePure`（渠道排序/URL 安全门槛/上限/过期文件判据）、`Sha256`（纯 SHA-256）；平台件
  `UpdateDownloader`（唯一允许 `@ohos.net.http` 的文件：测速选路 + 流式落盘 + 三重校验）、
  `UpdateInstaller`（结构核对 + 文件选择器另存 + 浏览器兜底）。**版本比较用 versionCode**
  （CI 注入公式），**HAP 校验值读同 tag 的 `SHA256SUMS`**（说明里的 SHA256 行是安卓 APK 的）。
- `ui/ParamPills.ets` 的弹层滑杆现为 **5 根**（快门/ISO/色温/对焦屈光度 + EV 整数域 null）——
  slider-snap 接线守卫按调用点计数 5 钉住；**tint 副轴不迁**（setWhiteBalance 无 RGB 增益接口，
  WbPillPopup 里不许出现 tint 控件，守卫钉住）。
- 设置页开发者入口默认隐藏：版本号 2s 内 7 连点显形（Index/GlPreview 仅此入口可达，均有页头+返回）。
- 以上模块**不得 import `@ohos.*`/`@kit.*`**；新增纯逻辑模块须进 `PureLogicBarrel`，否则 hvigor
  不可达、编译门失效。`test/pure/*.test.ets` 由 `scripts/pure-test.mjs` 转译执行。
