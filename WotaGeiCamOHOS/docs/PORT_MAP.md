# 移植映射表（Android → HarmonyOS）

上游源码根：`WotaGeiCamApp/app/src/main/java/com/wotagei/cam/`（117 个 Kotlin 文件，下表省略该前缀）。

类型说明：
- **纯逻辑** —— 不依赖 Android API（或仅依赖可等价替换的薄层），可近乎逐行翻译成 ArkTS/TS，移植成本最低；
- **平台绑定** —— 依赖 Camera2 / MediaCodec / GL / 服务等平台能力，需按鸿蒙对应 Kit 重写；
- **UI** —— Jetpack Compose 页面，用 ArkUI 声明式重写（布局与交互语义照搬）。

类型为初判，随移植推进逐项确认并勾状态。

## bt — 蓝牙音箱控制

| 上游 | 类型 | 鸿蒙落点 | 状态 |
| --- | --- | --- | --- |
| `BtSpeakerController.kt`（媒体音量/播放控制/电量） | 平台绑定 | `@ohos.bluetooth.ble/constant` + A2DP，电量走 GATT 电池服务 | ☐ |

## camera — 相机引擎与 GL 渲染

| 上游 | 类型 | 鸿蒙落点 | 状态 |
| --- | --- | --- | --- |
| `Camera2Engine.kt`、`CameraController.kt`、`DualCameraProbe.kt` | 平台绑定 | `@kit.CameraKit`（ModeManager/CameraManager），能力探测从 `CameraDevice.formatInfos` 等运行时读取，沿用「不写死机型」原则。**最小接线已落（2026-10-21）**：`camera/GlCameraEngine.ets` 增加 listLenses/currentLens/currentSize/supportedPreviewSizes、按 slotKey 重开、按画幅选预览档（未命中回退 1080p）、applyZoom（会话 zoomRange 钳制）、applyFlash（CLOSE/ALWAYS_OPEN）、DeviceStatus 转移；Camera 主页面消费。登记偏离：PhotoSession 预览无 setFrameRate（该 API 属 VideoOutput），预览源可能仍为 30fps；录制帧率只在编码器/muxer 侧生效，24/25 光弧按编码器输出节奏。 | ☑ 部分 |
| `CameraAbility.from()`、`LensSlot.enumerateLenses/discoverCameraIds`、`VideoSizePicker.minFrameDurationNs/videoSizeTable` 的 CameraKit 探测版适配层 | 平台绑定（采集侧） | `entry/src/main/ets/camera/`：`KitAbility.ets`（能力快照工厂，逐字段映射表见文件头注释）、`KitLens.ets`（镜头枚举，复用 core 的 shouldHideLogicalHead/assignRearTiers/dumpLenses）、`VideoSizeKit.ets`（帧周期按 videoProfiles maxFps 折算注入）、`KitProbe.ets`（探测入口，pages/Index 按钮消费）。探测版落地 2026-10-20，对齐上游 38d712a；会话期字段已补 zoom/ev/flash/iso/ois（预览批）与 exposureNs/evStep/evRange(档数换算)/afModes/awbModes/maxRegions=1（参数下发批，`KitAbility.abilityRefineWithSession`）；留白仅剩 tint/逐帧 fps/帧周期/EIS 真值与 VideoSession 参数面（随录制批）；上游「按数字 id 探隐藏镜头」无 OHOS 等价物，不移植（见 KitLens.ets 头注释登记） | ☑ 探测版 |
| `CameraController` 的「默认开广角后摄 + 出画面」最小闭环（预览批） | 平台绑定（会话侧） | `entry/src/main/ets/camera/`：`PermGate.ets`（CAMERA 权限申请门）+ `KitPreview.ets`（PhotoSession 预览引擎：默认档 assignRearTiers 后首个 WIDE、`pickDirectPreviewSize` 选档、createSession/createPreviewOutput/start、stop 逐项收尾、`applyParams` 参数下发入口）+ `pages/Index.ets`（XComponent(SURFACE) 预览面 + 权限流按钮 + 参数自检八档，探测路径保留）。落地 2026-10-21，对齐上游 38d712a。留白项：切镜头 UI、GL/DIRECT 双路、参数下发 UI（本批只有自检入口）、VideoSession 能力（EIS 真值随录制批）、永久拒绝引导（随 HUD 批）。装机验证项（不阻塞）：授权框弹出与拒绝重试、预览画面出现/关页无崩溃、`hdc hilog \| grep -E "WotaAbility\|WotaLens"` 真值与镜头列表 | ☑ 部分 |
| `RequestApplier.kt`（参数 → 请求的纯决策层 + OHOS 会话下发） | 纯逻辑 + 平台绑定（下发侧） | `camera/RequestApplier.ets`：State/stateOf、Snapshot/snapshotOf、clampShutterNs（双钳制+强制档豁免）、frameDurationNs、pickFps、exposurePlan、flashPlan（五分支）、stabilizePlan（三门控）逐行直译；apply 段 `applySnapshot(session,…)` 逐键 try/catch 产出 applied/skipped/failed 报告（`ApplyReport`），`KitPreview.applyParams` + Index「参数自检」为真实消费者。落地 2026-10-21，对齐上游 38d712a。审查修复订正（2026-10-21）：曝光时长单位 OHOS=微秒、core=纳秒（采集 ×1000/下发 ÷1000）；曝光组按档语义（MANUAL 写全 时长+ISO+EV 归零；LOCK/AUTO 只写模式与补偿——LOCK 冻结当前 AE 曝光，不写 SENSOR 键，OHOS LOCKED 下 setIso 会实际生效，登记）；MANUAL 用 LOCKED（d.ts：setIso 仅 LOCKED 生效，不用 EXPOSURE_MODE_MANUAL）；闪光由纯 flashPlan 驱动（无灯/手动退化/TORCH 全走 plan，state.flashAvailable 已消费）；EIS skipped 按用户意图判定并如实标注原因（PhotoSession 未暴露 EIS 参数面 ≠ 镜头不支持；高帧率/4K 门控原因单独列出）；手动对焦距离经 refine 的 isFocusDistanceSupported 探针门控 + ManualFocus.setFocusDistance 归一化下发（API24；探针不可得/6.0 设备 → skipped 不是 failed，登记）；evStep≤0 不猜 1、补偿键进 skipped；白平衡 MANUAL 模式与色温分键报告；4K 下 EIS=不下发该键（非显式 false）；ParamMap 反向探针 FOCUS_MODE_AUTO→AfMode.MACRO(2) 近似、catch 清空集合。不移植项（无 OHOS 等价物）：RGB 增益四件套（kelvinTintToRgb255/gain255ToUnit/rggbFromRgb255/identityColorTransform，手动白平衡走 mode+kelvin，tint 留白）、meteringBox/fullFrameBox/meteringRect（改用归一化 setFocusPoint/setMeteringPoint）、cropBox（只有 setZoomRatio）、afTrigger（无 AF 触发语义）、逐帧 AE_TARGET_FPS_RANGE（预览档 profile 固定，FpsPick 仅 State/dump）。`camera/ParamMap.ets` 承载枚举映射（FocusMode/WhiteBalanceMode/ExposureMode/OIS/VideoStabilization ↔ Camera2 契约值 + 反向探针；Flash 语义经 RequestApplier.flashPlan 派生）。装机验证项（不阻塞）：开预览→参数自检（变焦/色温/补光）→hilog WotaAbility 见每档 apply 与 failed 项 | ☑ |
| `GlRenderEngine.kt`、`ArcMendPass.kt`、`FrostBlur.kt`、`FrostBlurChain.kt`、`NtscGrid.kt` | 平台绑定（GL 着色器本体是纯逻辑） | XComponent + NDK NativeWindow，OpenGL ES 着色器源码几乎原样复用；斑马纹/峰值对焦同在此链路。GL-0（构建骨架/EGL/OnFrame）、GL-1（NativeImage 相机帧 + 基对）、GL-2（旋转镜像/曲线 LUT/斑马/峰值）已落；**GL-3 已落（2026-10-21）**：FBO/RT 基础设施（frostRtSizeInto/FrostRtNeedsRebuild/fillFrostBlurOffset 逐行；RGBA8+LINEAR+CLAMP，glCheckFramebufferStatus 完整性出口）、三趟链（OES→2D 拷贝→横向高斯→纵向高斯，权重 0.17524/0.16577/0.14032/0.10629 逐值，highp→mediump 退避，错误连击 8 帧停链）、PASS_THROUGH_2D_FS 上屏、全屏毛玻璃开关（页面「毛玻璃」）；**GL-4 编码面已落（2026-10-21）**：EGL 第二个 window surface 挂编码器输入面（复用初始化缓存的 EGLConfig）、编码 pass 每帧第二趟且仅新相机帧驱动（vsync 重绘不重复送帧；上屏 swap 后）、编码 UV 口径 deg=stRotDeg 且不镜像（上游 buildTexCoords(encoderPass=true) 语义）、**编码 pass 绝不进入 frost 分支（上游红线：霜面是上屏专属）**、surface 丢失中止；编码 pass swap/绘制连续失败 3 次中止（encoder-pass-failed）；恢复上屏上下文失败 1 次即中止，g_displayCtxLost 每帧重试上限 60 帧（达上限 abort display-context-lost，首帧与上限各一条日志）、WotaGlDestroy 先 detach+abort；**上批 P3×6 修正（2026-10-21）**：帧账录制纪元（frameStart 只计录制窗口；begin 前入队、begin 后执行的残余 frameStart 登记为观测偏差，相机帧取 max(cameraDelta,glCbDelta) 上界，仅作到达上界/不得当精确丢帧数）、sidecar 诊断读取门控（skipped(diag)）、flush DrawPending 失败计 swapFail、display-context-lost 页面提示并禁用录制、实测源帧率整场口径（降级不重置，登记偏离）、MEND attach 最多 2 次重试后降 DROP；frost 开关只释放离屏资源/program（基础 g_idUvVbo 归 EGL 管线生命周期，开→关→开可用）；**GL-4 光弧链在线版已落（2026-10-21，对齐上游 38d712a）**：ARC_MERGE_FS（整像素选择+差分死区）逐 token、ArcMendPass 等价（cur+pending×2+acc×2 五张 unsized GL_RGBA FBO（ES2/ES3 通吃，对齐上游/frost）、两枚 program、完整性出口/Release 清零）、ArcKeepRule/ArcRepairFlow C++ 逐行对拍、MEND/DROP 只吃新相机帧、MEND 离屏补弧与停录 flush、MEND→DROP 降级（降级帧无条件直通补发、不记新 rule 位次）；drops 位次账与实测源帧率、原生五格帧账；**编码 pass/MEND 均不进入 frost 分支（上游红线）**。留白：ARC_MERGE_FS（A/B 修复链，Codec 批）、Frost 画板/圆角 SDF/卡片 UV/letterbox 几何（HUD 批；本批 RT 内容=整屏等比画面，登记与上游 letterbox 口径差异）、NtscGrid（ntscGridStepNs 纯函数，Codec 批）、独立渲染线程与性能控制。已知：全屏毛玻璃与监视效果互斥一帧；`.so` 导入 1 条 napi 未验证工具链提示（已实测无抑制通道）。装机验证项（不阻塞）：毛玻璃开关画面整体模糊、方向与直通一致、关闭恢复效果态；**毛玻璃开→关→开仍可用**；横竖屏下模糊方向与画面一致；`hdc hilog \| grep WotaGL` 见 frost RT/programs/错误日志 | ☑ 部分（GL-0/1/2/3/4） |
| `DirectSink.kt`、`GlRenderSuppression.kt`、`FrameRateFallback.kt` | 纯逻辑为主 | 直译 + 适配相机回调签名 | ☐ |
| `LevelMath.kt`（水平仪/俯仰数学） | 纯逻辑 | 直译；传感器输入改 `@ohos.sensor` | ☐ |

## core — 参数与常量（第一轮直译完成 2026-10-21，对齐上游 38d712a）

| 上游 | 类型 | 鸿蒙落点 | 状态 |
| --- | --- | --- | --- |
| `Constants.kt`、`WotaParams.kt`、`CurveModel.kt`、`UIOrientation.kt` | 纯逻辑 | `entry/src/main/ets/core/` 同名 .ets，StateFlow 用轻量等价类，UIOrientation 的方向映射换 `window.Orientation.AUTO_ROTATION_*`（语义对齐 SENSOR_*） | ☑ |
| `LensSlot.kt` | 纯逻辑 + 平台绑定 | `core/LensSlot.ets`：LensType/LensSlot/assignRearTiers/shouldHideLogicalHead/dumpLenses 已直译；`enumerateLenses/discoverCameraIds` 适配层探测版已落 `camera/KitLens.ets`（会话期字段待预览批） | ☑ 部分 |
| `CameraAbility.kt` | 纯逻辑 + 薄适配 | `core/CameraAbility.ets`：RangeI/RangeF/能力快照/纯函数族（pickFpsRange、shutterCeilingNs、eqFocalMm、hFovDeg…）已直译；`from()` 适配层探测版已落 `camera/KitAbility.ets`（会话期字段待预览批） | ☑ 部分 |
| `VideoSizePicker.kt` | 纯逻辑 + 薄适配 | `core/VideoSizePicker.ets`：候选表/求交/落档/直显预览挑选已直译；`minFrameDurationNs` 注入适配层探测版已落 `camera/VideoSizeKit.ets`（会话期字段待预览批） | ☑ 部分 |
| `StorageAccess.kt` | 平台绑定 | `@ohos.file.fs` + `photoAccessHelper`（媒体库写入用安全控件/授权），与文件层一起移植 | ☐ |

## diag — 诊断日志

| 上游 | 类型 | 鸿蒙落点 | 状态 |
| --- | --- | --- | --- |
| `Diag.kt` | 平台绑定（薄） | `diag/DiagPure.ets`（纯函数层：diagLine/diagStamp/头部文案/轮转命名/丢弃自曝/帧账段，逐字段锚定）+ `diag/Diag.ets`（完整版：总开关默认关+级别档位门控、ring 3000/pending 3000 封顶丢最老并自曝、setInterval 1s 拍落盘 ≤500 行、512KB×3 轮转、崩溃件同步直写留 3、导出报告 2MB 预算）+ EntryAbility onCreate/onDestroy 接线。完整版落地 2026-10-21，对齐上游 38d712a。有意偏离（登记于 Diag.ets 头注释）：hilog 恒写双通道、setInterval 替代写盘线程（导出于 UI 线程同步读拼写有阻塞风险，TaskPool 化留待后续）、errorManager 'error' 替代 UncaughtExceptionHandler（无上一处理器链）、last-session.txt 替代 SharedPreferences、导出只写应用目录、时区 ±HH:MM/线程恒 main、deviceSummary 的硬件行用 hardwareModel 近似且设备行用 deviceType（语义与 Android Build.HARDWARE/Build.DEVICE 不同）、board/fingerprint 省行、ABI 用 deviceInfo.abiList。留白：公共目录导出、exportForShare 分享面板、总开关/详细档持久化（上游 WotaSettings diagEnabled/diagVerbose）与详细档 UI 入口（随设置页批）。装机验证项：开日志→导出→hdc file recv 看行格式与轮转；人为崩溃→crash-*.txt；清除后 logs 目录内本系统日志件（当前件/轮转件/崩溃件）清空，last-session.txt 保留，且日志关闭时不会立即新建当前件 | ☑ |

## media — 媒体库

| 上游 | 类型 | 鸿蒙落点 | 状态 |
| --- | --- | --- | --- |
| `WotaDb.kt` | 平台绑定（schema 纯逻辑） | `media/WotaDb.ets`：RDB（wota_media.db，version 1）四表逐字段对齐上游——`media_tag(id,mediaId,path,tag,createdAt)` + UNIQUE(mediaId,tag) + INDEX(tag)、`trash_item(uri PK,mediaId,name,deletedAt,origin)` + INDEX(mediaId)、`media_series(seriesId,startedAt,partCount)`、`series_part(seriesId,partIndex,mediaId,uri,durationMs)` + PK(seriesId,partIndex)；DAO 全 Promise、ResultSet 必 close、SQL 失败 Diag.e 后上抛。落地 2026-10-21，对齐上游 38d712a。版本不匹配按 Room fallbackToDestructiveMigration 等价实现 deleteRdbStore 重建（收藏/标签/回收站一起丢，登记）；schema 导出 JSON/Migration 未做 | ☑ 部分 |
| `MediaRepo.kt`、`SeriesStore.kt`、`SeriesGroup.kt`、`MediaTag.kt` | 纯逻辑为主 | `media/MediaRepo.ets`：扫描私有 filesDir/videos/*.mp4（stat size/mtime + AVMetadataExtractor 1.5s 超时补时长/分辨率，失败归零）、WotaDb 联表标签/回收站/系列、dateAddedSec 降序；ID=相对路径 32 位 FNV-1a（0→1；碰撞概率 1 万条约 1.2%/10 万条约 69%，升级 64 位或 (mediaId,path) 二次确认，登记）；探测 1.5s 超时只放弃结果、fd/extractor 延后到 fetch settle 回收；正在录制的半成品排除在列表外且动作层拒绝；收藏/移入回收站/还原/彻底删除（unlink + .drops.json + 清账）。`media/MediaTag.ets`：TagSpace（like/trash/长度 24/保留词大小写不敏感）、MediaScope/ListMode、ClipFilter/GalleryTabs（All→Like→Trash→自定义）/clampedSelection 逐行。`media/MediaFormat.ets`：groupTimeline（今天/昨天/同年/跨年）、formatDuration、formatSize、formatSignedDuration（T23 新增：负轴带符号时长，对比页时间线左端标签消费）。落地 2026-10-21，对齐上游 38d712a。留白：SeriesStore/SeriesGroup 归组 UI、自定义标签管理、相册 AllVideos、缩略图、外部改名（ID 脱钩，登记）。**P3 修复（2026-10-21）**：探测缓存/在途/负缓存提模块级 + 512 上限 + 陈旧剔除；未 settle 任务上限 4（stale 剔除不释放计数，真正挂起 open/fetch 的 fd/extractor 泄漏上界=4，登记）；满载时写 3s 负缓存并在 Gallery 提示“元数据暂不可用”；DB 重试入口 retryDb；switchTab 失败回退；sidecar stat 区分 13900002 与 IO 错误；open/stat 进超时任务；帧账 cameraFrames 明确为到达上界（非精确值） | ☑ 部分 |
| `MediaActions.kt`、`Share.kt` | 平台绑定 | 本批只落**本地**收藏/回收站/彻底删除（MediaRepo 动作，RDB 清账）；`photoAccessHelper` 相册入库、系统分享（shareData）、重命名、删除授权回路留白 | ☑ 部分 |
| `GalleryScreen.kt` | UI | `pages/Gallery.ets`：全部/收藏/回收站三页签（含数量）、卡片列表（文件名/时长/大小/日期/收藏星标）、空态/加载态/刷新、收藏切换、移入回收站、还原、彻底删除二次确认（AlertDialog）、元信息面板；卡片点击进入播放页。Index/GlPreview 均有「媒体库」入口。落地 2026-10-21。留白：缩略图、全屏手势锁定、时间线/网格/全屏滑动切换、自定义标签页、系列折叠、分享、重命名、MediaStore 相册（对比播放已由 Player 顶部「对比」入口提供；T20 剪辑成片落私有 videos，Gallery 下次刷新可见） | ☑ 部分 |

## player — 播放器 / 对比 / 剪辑

| 上游 | 类型 | 鸿蒙落点 | 状态 |
| --- | --- | --- | --- |
| `PlayerEngine.kt` | 平台绑定 | `player/PlayerEngine.ets` + `pages/Player.ets` 最小版：AVPlayer + XComponent(SURFACE) + fdSrc、prepare/play/pause/seek、逐帧（SEEK_CLOSEST + FrameStep 栅格/pending 锚点）、A/B 会话内循环（position 轮询回绕 + abWrapInFlight）、倍速（平台档 0.125/0.25/0.5/0.75/1.0，上游 0.1/0.3 偏离登记）、120ms 位置轮询、释放先 off 再 release；T19 增加 setVolume（0..1 夹紧），两实例各持自身 AVPlayer/fd/tick，无静态共享（Compare 页双路验证）；T20 音量 P3：Number.isFinite 防护（NaN/Infinity → 0）、删除无调用的 setMuted。落地 2026-10-21，对齐上游 38d712a。平台偏离：AVPlayer 无帧率读数（.drops.json dstFps→30→页面 24/25/30 手动）、无 SeekParameters（SEEK_CLOSEST，真机若落同步帧退 SEEK_PREV_SYNC 登记）。留白：播放器内双轨/选轨、自动音频同步、AB 持久化、旋转观看、缩略图、音轨菜单 | ☑ 部分 |
| `AudioSync.kt`、`CompareAudio.kt`（对齐数学） | 纯逻辑 | 直译 | ☐ |
| `FrameStep.kt`、`TapArbiter.kt`、`CompareTimeline.kt`、`ComparePractice.kt`、`CompareHistory.kt`、`ClipExport.kt`（ClipRule） | 纯逻辑为主 | `player/FrameStep.ets`（SNAP_TOL_MS=1.5、floor+1/ceil−1、duration 钳制）、`player/PlayerModel.ets`（AbRange valid、倍速档/最近档、clampPan 半域）、`player/TapArbiter.ets`（280ms 双击窗口）逐行直译；T19 新增 `player/CompareTimeline.ets`：TimelineGeometry（tMin=min(0,−offset)/tMax=max(leftDur,rightDur−offset)/leftCovers/rightCovers/leftTarget/rightTarget park 规则）+ normalizeOffset（[−rightDur,leftDur] 夹紧）+ rightTargetFor（leftPos+offset 夹到 0..rightDur）+ timelineSpan/timelineEnd，仅手动偏移子集；T22 订正：tMin/normalizeOffset/rightTargetFor 的 -0 归一 +0（纯逻辑测试发现，对齐上游 Long 语义，文件头登记）。T20 新增 `player/ClipRule.ets`：MIN_KEEP_MS=300、ClipRange、normalize（0..duration 夹紧，恰等 300ms 通过）、keepSample（relUs>=0）、clipNameOf（edit_<stem>_<yyyyMMdd_HHmmss>.mp4）、keyFrameIndexOf（四舍五入）、remapDrops（k>=shift 平移到 k−shift，segments 清空登记）、TrackChoice(-1/0/1)+audioOrdinalOf。**T22 纯逻辑单测落地（见 §纯逻辑测试）**：compare-timeline.test.ets（9 用例，off=0 回归红线两条独立钉死）+ clip-rule.test.ets（6 用例）。ComparePractice/CompareHistory、DecideCommands/AudioSync 自动对齐仍留白 | ☑ 部分 |
| `ClipExport.kt`、`RemuxOrientation.kt` | 平台绑定（封装逻辑纯） | **T20 无损 remux 最小版（2026-10-21）**：`cpp/clip_exporter.h/.cpp`（OH_AVSource/OH_AVDemuxer 逐样本读 → OH_AVMuxer 原样写 MPEG_4，不解码/不重编码；worker 线程 + atomic 进度/取消，状态 `idle/running:<pct>/done:<base>:<v>:<a>[:audio=skipped]/failed:<code>/cancelled`；入点 Seek PREVIOUS_SYNC 取首个 SYNC 帧 basePts，视频/音频 PTS 同减 base，负 rel 丢弃，逐轨 monotonicPts 桥；出点按 PTS 截止，视频 32/音频 8 连续越界容忍 B 帧重排；源/视频轨旋转经 WotaNormalizeQuarter 透传，源格式优先；音轨可选 -1/0/1，预扫区间内零可写样本不建轨，AddTrack 失败退纯视频；先视频整轨后音频整轨；muxer Start 后取消 best-effort Stop）+ NAPI `startClipExport/getClipExportStatus/cancelClipExport/probeClipTracks` + `player/ClipExporter.ets`（fd 生命周期、200ms 轮询、10min 超时、失败/取消删除半成品、done 后按 basePts 关键帧序号 remapDrops 写输出 sidecar、同名追加 _N）+ Player 页「导出 A/B」面板（A/B≥300ms 才显示、暂停、音轨选择、进度/取消、成功路径/大小/媒体库跳转）。错误码 source/track-format/no-video/no-sync/muxer/add-track/rotation/muxer-start/read/sample-too-large/write/empty-range。**T21 修复 T20 复审 P3×4（2026-10-21）**：① done 日志打实际生效 rotation（与 `OH_AVMuxer_SetRotation` 同读归一后的 `appliedRotation`，源格式缺旋转键、视频轨带键时不再打错 sourceRotation）；② 音频拷贝阶段 NO_MEMORY 不判死——跳过剩余音频样本继续视频、done 状态追加 `audio=skipped`（首枚即超限=已 AddTrack 零样本 → WARN + 既有 audioSamples==0/Stop 失败兜底；视频轨 NO_MEMORY 维持 sample-too-large 判死，口径登记 clip_exporter.h）；③ ArkTS uniqueOutputPath 的 accessSync 异常按「被占用」继续 _N 循环、1..99 穷尽回退 `${stem}_${Date.now()}.mp4`（绝不返回 access 异常原候选名，防 TRUNC 覆盖旧成片）；④ 超时 fd 延迟关闭——CANCEL_GRACE_MS(5s) 耗尽进 linger 轮询（NATIVE_LINGER_MS=30s 上限）等 native 离开 running:，linger 内退出照常 close+discard、耗尽挂模块级 pendingFds（exportClip 入口/轮询 reap，Diag.w 登记泄漏），不关正被阻塞调用写的 fd。已知差异：逐轨顺序写入=非交织（真机播放器兼容性待验）、音频只选一条、无 MediaStore pending/commit（导出中直接写目标 mp4，极端并发刷新可能看到半成品）、无分享、clipName 追加时间戳（OHOS 私有目录防覆盖）、remapDrops 清空 segments（剪辑单文件不再适用源段账）。RemuxOrientation 只做旋转透传，不单独移植平台读源。留白：交织写入、双音轨、自动音频同步、剪辑进度持久化/后台保活；T18/T19 其余 P3 分别登记到健康/对比收口批（本批仅 PlayerEngine 音量防护） | ☑ 部分 |
| `PlayerScreen.kt`、`CompareScreen.kt` | UI | `pages/Player.ets` 最小版：播放画面/进度条/播放暂停/逐帧/A-B/倍速/帧率/镜像、单击显隐、双击启停、双指缩放平移（clampPan）；Gallery 卡片进入、返回刷新，顶部「对比」带 {leftId,leftPath} 进 Compare。T19 `pages/Compare.ets` 对比播放最小版：双普通 XComponent(SURFACE)+双 PlayerEngine、右片 MediaRepo 现选（排除左片）、共享播放/暂停/seek/倍速、手动偏移 ±0.5s/归零（park 门控：无覆盖侧停首/末帧，进入覆盖区由 tick 起播）、声音侧左/右/静音（默认左）、120ms tick 漂移校正（阈值 250ms/冷却 500ms）、两侧独立 attach/retry 与 surface 重建换代、页面隐藏暂停/销毁双 release。T20 在 A/B 合法（≥300ms）时增加「导出 A/B」入口与导出面板（音轨选择/进度/取消/成功路径与大小/媒体库跳转，导出期间拦截返回）。**T22 对比收口（2026-10-22）四项关闭 + 平台限制登记**：① 时间线双轴消费——进度 Slider min/max=TimelineGeometry.tMin()/tMax()（off>0 负轴 pre-roll 可拖）、拖动值 t 即时间线坐标、seekBoth 走 leftTarget/rightTarget park 规则、左端标签 tMin/右端 timelineEnd；off=0 在旧单左轴域逐像素等价（回归红线）；timelineSpan 无 UI 消费留白；进度读数仍随左钟（pre-roll 段显 0，最小版不派生真 T，登记）；② 右片 surface 重建/重试恢复播放位置（resumeRightPosMs 快照 rightTargetFor 或引擎实位，tryAttachRight 成功路径 seekTo 恢复；换选右片不恢复；左片既有恢复路径零改动）；③ attach/retry/surface 隐藏期门控——onSurfaceLoaded 重建分支在 !pageVisible 只置 deferred 标志（left/right 各一）由 onPageShow 消费执行、retryLeft/retryRight 入口拒 pageVisible=false、rebuild then 链 attach 前再查 pageVisible；④ 右片列表 id+path 双排除左片（leftClip 未就绪用路由参数原始 path=paramsLeftPath 兜底，防 FNV-1a ID 碰撞）。**漂移"速度微调"以平台限制登记关闭**：AVPlayer setSpeed 仅枚举档（player/PlayerEngine.ets speedToEnum 0.125/0.25/0.5/0.75/1.0 为证，无任意 ratio 接口），上游 AudioSync 的 setPlaybackParams 微调无 OHOS 等价物，邻域纠偏维持 250ms 硬 seek/500ms 冷却。**T23 P3 关销（2026-10-22）**：时间线左端标签负轴改用 `media/MediaFormat.formatSignedDuration`（off>0 的 pre-roll 显示 `-m:ss`；亚秒负值下限 `-00:01`，避免 500ms 偏移步进时与 0 混淆），进度读数仍随左钟口径（不派生真 T，登记保留）；formatDuration 负值归零语义不变，新增 media-format 3 用例钉住正/负/0（含 `2:00:50` 手写字面量）。留白：自动对齐/DecideCommands/AudioSync、全屏手势锁定/字幕/音轨菜单/AB 持久化、剪辑进度持久化 | ☑ 部分 |

## record — 录制链路（移植重心）

| 上游 | 类型 | 鸿蒙落点 | 状态 |
| --- | --- | --- | --- |
| `MrRecorder.kt`（MediaRecorder 引擎） | 平台绑定 | `@ohos.multimedia.media` AVRecorder + CameraKit VideoSession：`camera/KitRecorder.ets` 最小录制（VIDEO_SOURCE_TYPE_SURFACE_YUV 面输入、NORMAL_VIDEO 会话、**videoProfiles + frameRateRange 覆盖选档**（VideoSizeTable 留待后续）、BitratePolicy 复用、SeriesIds 接线、MIN_KEEP_MS 判废）。落地 2026-10-21，对齐上游 38d712a。输出契约：`AVRecorderConfig.url = fd://<fd>`（fileIo.openSync 建文件取 fd，fd 保留到 AVRecorder reset/release 后关闭）；起录失败统一回收资源并删除已创建/半写文件；判废顺序=先 MIN_KEEP_MS（短段含 engineErr 一律 TOO_SHORT），唯一例外 av.stop 失败保留文件 triage（STOP_FAILED）；start/stop 有 starting/stopping 重入保护。留白：3.5GiB 分段轮转/起录健康三格/AutoStop 巡检（GPU 已接，AVRecorder 留白）/FrameLedger 接线/HEVC 重试/.drops.json/参数下发（VideoSession 无 ManualExposure/ManualIso/OIS）/orientationHint 与 AVRecorderConfig.rotation 未接线（显示旋转源随 HUD 批）/sampleRate 恒 48k 未探测/mirrored/useGpu 恒 false。装机验证项（不阻塞）：录 5 秒文件可播放有声音、停录后预览恢复、拒绝 MIC 录无声视频、<1 秒判废删除、fd:// 契约真机验证 | ☑ 部分 |
| `CodecRecorder.kt`（MediaCodec+MediaMuxer 引擎） | 平台绑定 | AVCodec + AVMuxer（NDK 侧），与 GL 链路衔接。**最小切片已落（2026-10-21，对齐上游 38d712a）**：`cpp/codec_recorder.h/.cpp`（OH_VideoEncoder CreateByMime→RegisterCallback→Configure→GetSurface（Prepare 前）→Prepare→muxer Create/AddTrack/Start→encoder Start；surface 输入、输出回调直写 OH_AVMuxer，PTS 一律 wall-clock 相对段基准（NowUs−startUs，µs）并经 OH_AVBuffer_SetBufferAttr 回写、lastPts 单调守卫（前提：无 B 帧，登记）；容器旋转 OH_AVMuxer_SetRotation(normalizeQuarter(sensor−display))（0/90/180/270 四舍五入，非 90 倍数 sensor 按上游归一；须在 muxer Start 前；编码 UV 仅送缓冲原样帧）；I 帧间隔 1000ms；FRAME_RATE/BITRATE 按 SDK 类型（double/int64）写入并检查；EOS 等待 ≤3s；错误机器码 busy/encoder-create/register-callback/format-create/format-key/configure/get-surface/prepare/muxer/track-format/track/muxer-rotation/muxer-start/start/encoder-surface；运行期 muxer-write/pts-set/muxer-missing/engine-error/surface-lost/encoder-pass-failed；muxer 写失败/时间轴回写失败即停止送样（samples 仅成功自增），错误经 getGpuRecordError/stop 带回；native 不 close fd）+ `camera/GlRecorder.ets`（fd 归 ArkTS、VideoStore 命名/落盘与 MIN_FREE_MB 起录预检、停录 close+stat 摘要；napi-failed 先 best-effort stop 再 close）+ GlRecorder（起录弧档/dstFps/deadband 传递；停录顺序 flushArcPending→（阻塞 stop 前冻结帧账 end 快照）→stop→逐项 drainArcDrops/帧计数/实测源帧率→close+stat→FrameLedger 成文→写 `.drops.json` sidecar（segments.count=muxer 视频样本数（part.samples，上游 VideoSegment.count；与首段旁 sidecar 同口径）；frameStart 不可用时相机帧退原生 GL 回调上界））+ GlPreview「录 GPU/停录」与三态转换按钮（关/补弧24/抽帧24，固定 1920×1080@30 或 24、BitratePolicy.resolve；500ms 监视 isGpuRecording/getGpuRecordError 校正录制态）+ **AutoStopMonitor（2026-10-21）**：1s 相机设备态转移（启动时以当前设备态预置 watch；session error 直连兜底）、首个 1s tick 立即查电量 batterySOC≤3% 与可用空间 <MIN_FREE_MB（其后 30s 节拍），一次录制至多一发，命中提示“成片已保存”；手动停录/相机丢失兜底统一按 AutoStopRules.stopGraceDelayMs 做 500ms 宽限（stopPending 防重入，首个停录原因合并）。相机丢失后 lensInfo/deviceStatus 失效，需重新进入本页起镜后才允许再次录制。**3.5GiB 分段轮转已落（2026-10-21）**：native `getGpuWrittenBytes`（成功写入 attr.size 累计）+ ArkTS 500ms 轮询 maybeRotate；SegmentPolicy（MAX_FILE_BYTES=3_758_096_384、ROTATE_SAFETY=0.98）；轮转=停段（EOS）→新文件→重建编码器/EGL 面（段间 lostMs 记账），每段独立 MP4/时间轴、各自 displayName；逐段 FrameLedger（partIndex/rotateLostMs/rotateFramesUpper）与 `frameSessionSummary`；首段旁 `.drops.json` 的 segments 覆盖全部段（first 前缀和/part.samples/lostMs），drops 只保留最后一段（上游跨段失真登记）；8MB 测试阈值入口（离开页面恢复默认）；summary 的 measuredSrcFps 标注为最后一段口径；**T22（2026-10-22）**：SegmentPolicy 无自动化单测登记撤销（test/pure/segment-policy.test.ets 5 用例，见 §纯逻辑测试）、stop 摘要追加末段账 lastPath/lastBytes/lastSamples（健康收口丢段专用）、被丢弃零样本段在首段 sidecar segments 留 count=0 条目（无害，登记——首段 sidecar 不回写）。上批 P3×4：起录后 1s 冷却（迟到点击不覆盖摘要）、删除 lastStopHadReason 死字段、Player metaNotice 移出控制层、stopReason 起录清理。本批 P3×5：轮转错误先 finish 再追加摘要、onPageHide/离开页面复位 8MB 测试阈值、起录清 lastStopAtMs、napi-failed 摘要带 path 保留 triage、~~SegmentPolicy 无自动化单测登记~~（T22 撤销）。 T18 P3×8：bootstrap/权限 pageVisible 门控、重试相机按钮 disabled(startingCamera)、SettingsStore.save 返回 boolean + 保存串行化、变焦实际值/最近档、HUD_READOUTS 驱动顶栏、appendNotice 冷却/错误追加、相机丢失清镜头/画幅标签、预览/录制帧率分显。留白：音频轨、参数下发、PTS 网格化、成片名前缀、MediaStore、NV12 直通与 DIRECT 路、系列归组 UI。已知：容器旋转 hint 在起录时定格（录制中转屏不更新，单段口径，登记）。装机验证项（不阻塞）：GL 页录 5s→hdc file recv 取回 mp4 可播放且时长 5s 量级；ES2 回退下 MEND 不降级（unsized GL_RGBA）；时间戳单位 ns/µs/ms 归一与实测源帧率合理；竖屏成片方向正确（muxer SetRotation）；停录后再次起录正常；录制中返回页面→录制中止、无崩溃；`hdc hilog \| grep -E "WotaGL\|WotaRecord"` 见编码面建立/首帧/EOS 日志 | ☑ 部分 |
| `CaptureFgService.kt`（前台服务） | 平台绑定 | 长时任务（continuous task）+ 通知栏常驻 | ☐ |
| `PlaybackCaptureController.kt`（内录） | 平台绑定 | AVScreenCapture 音频内部录制（环境音+内录双轨） | ☐ |
| `Recorder.kt`、`CaptureStateMachine.kt`、`RecordProfile.kt`、`VideoStore.kt` | 纯逻辑为主 | 直译（状态机与接口不动，引擎实现注入）。最小契约已落：`record/RecordResult.ets`（RecordError 机器码 + RecordResult 首段字段/summary，VideoSegment/health/parts 留待分段批）、`record/RecordProfile.ets`（纯字段子集 + CODEC_H264/CODEC_HEVC/MIN_KEEP_MS + effectiveBitrate，captureAudio/arcConvert/fpsNtsc 留白）、`record/VideoStore.ets`（VID_yyyyMMdd_HHmmss_###.mp4 命名/MIN_FREE_MB 余量/sizeOf/discard；MediaStore pending 与相册入库留白）。分段轮转每段各自 displayName/文件，段序号只在 parts/segments/日志；系列归组（MediaSeries/SeriesPart）留白。落地 2026-10-21，对齐上游 38d712a | ☑ 部分 |
| `ArcKeepRule.kt`、`ArcDropLog.kt`、`BitratePolicy.kt`、`AutoStopRules.kt`、`FrameLedger.kt` | 纯逻辑 | **逐行直译**——这些是与上游共享语义的锚点（见 UPSTREAM_SYNC.md），格式/判据/阈值必须与上游逐字段一致。直译完成 2026-10-20，对齐上游 38d712a，落点 `entry/src/main/ets/record/` 同名 .ets；BitratePolicy 的 `resolve(RecordProfile)` 重载待 RecordProfile 移植后补。在线消费已接线（2026-10-21）：ArcKeepRule/ArcRepairFlow 的 C++ 等价物（`cpp/arc_flow.*`）跑在编码支路，ArcDropLog 由 GlRecorder 停录写 sidecar，FrameLedger 合成原生五格+相机 frameStart（分段时逐段成文）；ArcDropLog segments 已随分段写全段清单（drops 只保留最后一段，跨段失真登记）；AutoStopRules/CameraLostWatch 由 `record/AutoStopMonitor.ets` 消费（GPU 主路径：1s 相机/30s 电量存储、一次一发、停止宽限 500ms；AVRecorder 路径留白） | ☑ |
| `RecordHealth.kt` | 纯逻辑 | `record/RecordHealth.ets`：GPU 三格（trackAdded/muxStarted/firstVideoSample）+ 判定顺序（错误码→三格→视频短窗 1.2s→完整窗 3s→WAIT）、retryActionOf（attempts<maxAttempts）、MIN_OUTPUT_BYTES=64KiB、resolveOutputBytes/streamHealthVerdict（AVRecorder 路纯函数留白）。**GPU 主路径已接（2026-10-21）**：native CodecRecorder 暴露 trackAdded/muxStarted/samples（getGpuHealth），Camera 500ms 巡检、最多自动重录 2 次、仅重开录制段不重开相机。T18 P3×4（T19）：GlRecorder stop 摘要补 samples、空段仅 samples=0 且 bytes=0 丢弃、onPageHide/Show 续跑巡检、GIVE_UP 文案常量生成、native HealthSnapshot 单锁快照。**T22 健康收口（2026-10-22，对齐上游 38d712a）四项关闭**：① 三格真信号——native `formatChanged_`（OnStreamChangedCb 置位，Start/FailStart/Stop 复位）= 上游 MediaCodec format-changed 等价物，getGpuHealth 扩 4 字段 `track,mux,samples,fmt`，Camera checkHealth 容 4 字段（3 字段旧序防御），healthVerdict 的 videoTrackAdded 实参 = `fmt || samples>0`（samples 兜底防回调时序不触），muxer AddTrack 位降为 Diag 诊断字段（三格语义对照表见 Camera.ets 头注释）；② 轮转重启健康窗——上一段已健康停巡检后轮转新段重启巡检（幂等），**healthAttempts 会话级不清零（与上游"每次起录清零"口径差异登记：防轮转循环无限重录）**，隐藏页不重启只置 healthPausedByHide 由 resumeHealthAfterShow 消费；③ 健康重录三残差——restartSegmentAfterHealthFailure 重置 recordStartedAtMs（宽限期口径）、GlRecorder.stop 摘要追加末段账 `lastPath/lastBytes/lastSamples`、健康重录与 GIVE_UP 丢段读末段账（仅末段 samples=0 且 bytes=0 才删，**保留已完成前段**；被丢弃零样本段在首段 sidecar segments 留 count=0 条目，无害登记）；④ 纯逻辑单测落地（test/pure/record-health.test.ets，21 用例，判定顺序五条各被点名，见 §纯逻辑测试）。AVRecorder 健康检查/stream 活性路留白（Index 未接） | ☑ 部分 |
| `ArcRepairPlan.kt`、`ArcRepairRunner.kt`、`ArcRepairGl.kt`（离线修复） | 平台绑定（计划逻辑纯） | 计划直译；执行走 CPU（ArkTS/NDK）与 GPU（复用 GL 着色器）双路。**在线 ArcRepairFlow 已落（2026-10-21）**：`cpp/arc_flow.h/.cpp` 的 Hold/Fold/Emit/Merge 指令序列 + `cpp/arc_mend_pass.*` 执行；离线修复（ArcRepairRunner/ArcRepairGl）仍待后续批 | ☑ 部分 |
| `PreMuxVideoBuffer.kt`（起录缓冲）、`SeriesIds.kt`、`PendingName.kt`、`StalePendingSweep.kt`、`RotateGuard.kt`、`RotateFailLog.kt`、`OrientationExport.kt`、`AudioFeeder.kt` | 纯逻辑为主 | 直译。`SeriesIds.ets`、`PendingName.ets` 直译完成 2026-10-20（对齐上游 38d712a，落点 `entry/src/main/ets/record/` 同名 .ets）；其余待后续 | ☑ 部分 |

## ui — 界面（全部 ArkUI 重写）

| 上游 | 类型 | 鸿蒙落点 | 状态 |
| --- | --- | --- | --- |
| `CameraScreen.kt`、`MainActivity.kt`、`HudLayer/HudLayout/HudLayoutEditor/HudMetrics/HudPageGrid.kt`、`CameraPills.kt`、`SettingsScreen.kt`、`SettingsLayout.kt`、`ParamsPersist.kt`、`PreviewCoords.kt` | UI | **最小主 UI/HUD 已落（2026-10-21，对齐上游 38d712a）**：`pages/Camera.ets` 全屏 GL 预览 + 固定 HUD（顶栏两行读数/3×3 网格/底栏/录制计时）+ 参数胶囊（镜头/画幅/帧率/变焦/闪光/效果/光弧/网格点按循环；快门/ISO/EV/WB 点按开 PillPopup 就近浮层）+ GlRecorder/AutoStop/宽限/分段接线；**T21 高级参数批（2026-10-21）**：`ui/ParamPills.ets` 五件直译（对拍 CameraPills.kt:236-628——AeModeRow（AUTO/MANUAL/LOCK，manualExposurePossible 门控）、ShutterPill（梯=supported 档滑杆吸附、Float 往返经梯值反查原始 ns、仅 MANUAL 显示滑杆、fpsHi=pick.lo 与下发同源）、IsoPill（isoLadder 仅 MANUAL 可调）、EvPill（evLadder+evText、MANUAL 只读注记）、WbPill（预设网格+MANUAL 色温滑杆 kelvinLadder，**tint 副轴留白**））、WotaParams 参数总线接线（起镜成功 `applyAbility(engine.refinedAbility())`、fps 档位联动重算）；顶栏第二行 快门/ISO/EV/WB 读数（AE AUTO 时 ISO 显 AUTO、EV 仅非 MANUAL 显示、wbShort 预设缩写+色温、能力缺失 `-`；`HudSpec.HUD_READOUTS_ROW2`）——**偏离登记：上游 READOUT 是可编排网格（hud_items 位掩码 + HudLayoutEditor），本批固定两行**；胶囊行追加 快门/ISO/EV/WB 四颗（点按开浮层，录制门控 `ParamPills.RECORDING_EDITABLE_PILLS` = {ISO,EV,WB}——**上游含 ZOOM/FOCUS，两颗胶囊本批未移植，差异登记**；快门录制中开窗只读 Note）；每次起录前与弹层改动 `engine.applyParams` 下发（RequestApplier 逐键报告，failed 项 Diag.w + appendNotice）；`pages/Settings.ets` 六项设置（画幅/帧率/码率/光弧/效果/网格）经 `ui/SettingsStore.ets`（preferences）持久化；`ui/HudSpec.ets` 固定布局常量；主入口改为 Camera（EntryAbility loadContent），Index/GlPreview 保留开发者测试入口；pageVisible 门控隐藏页 async 起镜链、启动失败/session error 提供「重试相机」、applyZoom 按实际生效值显示、Settings 保存串行化/类型白名单、HUD 读数顺序常量与顶栏一致、媒体库入口去重；效果槽同时作用于预览与编码（`gl_render DrawScene` 同一 GL 效果 program）。**T21 复审 P3×3 收口（T22，2026-10-22）**：① 快门读数 ※——syncParamLabels 在 MANUAL 且 ability 在册且 forcedShutterOutOfRange 时追加 `※`（上游 CameraScreen.kt:1195-1203 口径，与快门弹层/下发侧同源判据）；② applyParamsFromPills 的 skipped 摘要改 Diag.i（上游只进日志不占用户提示位），仅 failed 留 appendNotice；③ 相机丢失/session error 处置路径统一 closePill()（onCameraLost 回调首行收口弹层）。留白：HudLayout/HudLayoutEditor 自定义布局、ZOOM/FOCUS 胶囊与弹层、高级参数持久化（上游 ParamsPersist；本批会话内有效）、tint 副轴、PreviewCoords、设计 Tokens、SheetSpec/Widgets 全量 | ☑ 部分 |
| `design/Tokens.kt`、`PillPopup.kt`、`SheetSpec.kt`、`Widgets.kt` | UI（设计令牌近纯逻辑） | **PillPopup 最小实现已落（T21，2026-10-21）**：`ui/PillPopup.ets`——WotaPillPopup（半透明遮罩点击关 + 量框后定位面板 + 标题/收起钮；锚点取 `getUIContext().getComponentUtils().getRectangleById`（窗口 px），**取不到回退底贴屏居中**）、PillAnchor.place 纯函数直译（margin/right/bottom/above/below 钳制，MAX_HEIGHT_RATIO=0.62、gap 8vp/margin 6vp/面板 max 400vp 照搬）、PillChoices（网格单选 + disabled 灰显且点不动）、PillSlider（ladder 刻度滑杆：索引空间运行+nearestLadderIndex 吸附+梯不足 2 点禁用占位不退回连续）、PillNote。**T21 复审 P3（T22，2026-10-22）**：撤销 shown 封口——onMeasured 记录 lastW/lastH，onAreaChange 报告尺寸与上次放置尺寸不同时重跑 PillAnchor.place 更新位置态（内容增删导致面板尺寸变化时重排；同尺寸跳过 + place 纯函数 ⇒ 无重入环），shown 首测语义保留（首量显形）。未迁（登记）：LocalMotion 动效（淡入/0.94 长开）、TextScaleLayer、HDS 阴影/令牌色（静态深色近似）、WotaChip 视觉令牌、PillToggles/PillRowList（无消费方）。Tokens/SheetSpec/Widgets 其余仍待后续 | ☑ 部分 |
| `dialog/`（音源/蓝牙/相机/曲线弹层） | UI | **相机弹层数值段已落（T21）**：`ui/ParamFormat.ets` 直译 CameraDialogs.kt 数值段——shutterText（`1/` 与 `%.1fs` 分支）、evText（`%+.1fEV`）、wbShort/wbLabel/aeLabel（zh 档名取上游 strings_camera.xml）、shutterItems（supported 只绑设备判定 + 强制档 `※`；可选上限 shutterCeilingNs 按 fpsHi 现算与下发同源）。其余弹层（音源/蓝牙/曲线）待后续 | ☑ 部分 |
| `widget/`（CameraSurface、ParamSlider、SliderLadder 刻度滑杆、RefLineOverlay 参考线、AttitudeMeter、AudioChip） | 平台绑定 + UI | **SliderLadder 已直译（T21，2026-10-21）**：`ui/SliderLadder.ets` 纯函数族 nearestLadderIndex/isoLadder（25 档含 50/12800）/evLadder/kelvinLadder（500K 网格）/tintLadder/zoomLadder（含 0.5/1.5/2.5）/shutterLadder（过滤/去重/升序）/steppedLadder/alignUp（Math.floor 复刻 floorDiv 负数语义）；**T22（2026-10-22）补齐 diopterLadder（lo..hi 等分 10 段 11 点）与 percentLadder**（上游直译，消费方仍留待对焦/斑马胶囊批），九用例单测见 §纯逻辑测试。未移植（无消费方，登记）：deadbandLadder（光弧死区滑杆未进本批）。CameraSurface/ParamSlider/RefLineOverlay/AttitudeMeter/AudioChip 待后续 | ☑ 部分 |
| `anim/`、`theme/`、`WindowKeepOn.kt`、`FrostSpace.kt`、`FrostProbe.kt`、`CompareProbe.kt`、`GalleryEntryScreen.kt`、`RecordResultNotice.kt` | UI / 薄绑定 | ArkUI 动效与 `window.setWindowKeepScreenOn`。**WindowKeepOn 最小接线（2026-10-21）**：Camera 页 getLastWindow + setWindowKeepScreenOn(true/false)，进页常亮、隐藏/销毁恢复 | ☑ 部分 |

## update — 应用内更新

| 上游 | 类型 | 鸿蒙落点 | 状态 |
| --- | --- | --- | --- |
| `UpdateChecker.kt`、`UpdateDownloader.kt`、`UpdateInstaller.kt` | 平台绑定（策略纯） | `@ohos.net.http` 读 GitHub Releases；**手动联网、SHA-256 校验、不自动检查**等隐私承诺照搬；安装侧需改为鸿蒙包体（hap/hsp）或引导下载页 | ☐ |

## 交付批（T23，2026-10-22）

- **T22 P3×2 关销**：① `scripts/pure-test.mjs` 死常量 `SRC_ANCHOR` 删除（仅注释保留目录文字），
  50 用例回归不变；② `pages/Compare.ets` 时间线左端标签负轴改 `media/MediaFormat.formatSignedDuration`
  （`-m:ss`；亚秒负值下限 `-00:01`，避免 500ms 偏移步进时与 0 混淆；-1.5s 仍按 floor 截断为
  `-00:01`），进度读数仍随左钟；`test/pure/media-format.test.ets` 3 用例，合计 53。
- **本地签名探针** `scripts/local-sign.mjs`：hap-sign-tool 全链 ECC P-256（keypair×4 → root/sub CA →
  app-cert/profile-cert → sign-profile(debug, bundleName `com.wotagei.cam`) → sign-app → verify-app
  自动回验）；材料落 `.local-signing/`（gitignore，仅缺失时生成，可重复重签）。平台事实登记：
  HarmonyOS NEXT 新式签名块位于 ZIP 中央目录前（`<hap sign block>` 标记，本快照 60374B），不产生
  `META-INF/` 条目；`unzip -l` 为 16 业务文件 + `.pages.info`（签名工具生成）；签名块+profile 的
  可还原性以 `verify-app` 取证（实测 3 级证书链 + 3254B profile）。审查修复后：证书链/profile
  空输出直接失败、签名先写 `.tmp` 且任一步失败只删临时件（既有 signed HAP 不动）、口令可由
  `LOCAL_SIGN_PASS` 覆盖、POSIX p12 0600。
- **HAP 交付口径**：clean `assembleHap` 退出 0、WARN=2 基线（napi 未验证 + 未配置签名跳过）。
  **hash 不可复现**：zip 条目时间戳（signed 另加签名时间戳）使同一源码重建/重签得到不同 sha256；
  T23 审查修复批单次快照 = unsigned 5067199B / `b58e3ca5…de5462`、signed 5166613B /
  `c0f1bcb6…1292b2`（全文见 README「交付」节，**勿作复验判据**）。内容级复验：解包后按路径排序
  逐文件 sha256 比对（unsigned 16 项；signed 排除 `.pages.info`），或字节数 + `verify-app` 输出。
- **文档终稿**：README 当前进度收敛 + 交付节（产物/安装/签名指引/真机清单/内容级校验示例）、
  本表交付批与「交付后路线」、AGENT.md 补工程脚本清单。
- **审查修复（2026-10-22）**：P2 交付 hash 口径（README/PORT_MAP 标明单次快照、不可复现、给出
  内容级校验）；P3 一并处理 `.pages.info` 描述、亚秒负轴显示、签名探针断言/失败清理/口令与权限、
  模拟器签名口径、media-format 期望值手写字面量。
- **回归**：`node scripts/pure-test.mjs` → files=6 tests=53 passed=53 failed=0（exit 0）；
  clean assembleHap exit 0；`git diff --name-only 851347a..HEAD` 全部落在 `WotaGeiCamOHOS/`。

## 纯逻辑测试（T22 落地，T23 扩展，2026-10-22）

自研轻量测试架（不用 hypium/ohosTest，无真机可跑）：

- **执行器** `scripts/pure-test.mjs`：扫描 `test/pure/*.test.ets` → 正则收集 import 依赖闭包
  （`entry/src/main/ets/` 起）→ `typescript.transpileModule(CommonJS/ES2020)` 转译到
  `.pure-test-tmp/` 镜像相对路径 → require 钩子把 `@ohos.*`/`@kit.*` 桩成惰性 Proxy
  （顶层 import 不炸、未触达的平台 API 不真调）→ 注入迷你断言 API
  （test/assertEq/assertTrue/assertFalse/assertNull）→ 汇总 passed/failed，失败退出码非 0；
  零测试退出 0。typescript 复用 DevEco 自带
  `$DEVECO_HOME/tools/hvigor/hvigor/node_modules/typescript`（`PURE_TEST_TS_PATH` 可覆盖）。
  **T23 P3 关销**：删除死常量 `SRC_ANCHOR`（仅保留注释里的目录文字），执行器输出与用例数不变。
- **命令**：`node scripts/pure-test.mjs`（仓库根 WotaGeiCamOHOS/ 下）。
- **测试目录** `test/pure/`：entry 模块外，hvigor 不扫描、不进 HAP；临时目录 `.pure-test-tmp/`
  已进 .gitignore。
- **已落用例（53）**：record-health（21，直译上游 RecordHealthTest.kt 已移植面，判定顺序五条
  各被点名）、compare-timeline（9，对拍上游 CompareTimelineTest.kt 裁剪到已移植面，off=0
  回归红线两条独立）、slider-ladder（9，上游 SliderLadderTest.kt 直译）、segment-policy（5，
  常量/阈值边界/前缀和/丢帧上界/摘要）、clip-rule（6，MIN_KEEP/normalize/命名/关键帧/平移/音轨序数）、
  media-format（3，T23 新增：formatSignedDuration 正值/负值/0 与 formatDuration 口径对齐）。
- **降级路径（预登记）**：若某测试文件的依赖闭包被 Diag 等顶层副作用卡死，只保零依赖三件
  （record-health/compare-timeline/slider-ladder）并显式打印 skip——本批未触发，
  segment-policy/clip-rule/media-format 经 @kit stub 链（ArcDropLog 顶层 import fileIo）正常执行。
- **对拍口径**：期望值一律手写字面量（不用实现反推）；上游用例中依赖未移植面
  （MR 专用段判定/DecideCommands 等）的条目不进本侧文件，随对应批补。

## 真机待验（T22 收口批 + T23 交付批新增，不阻塞）

- **onStreamChanged 触发时序**：surface 输入下首帧到达是否可靠触发 format-changed
  （健康第一格真信号）；个别 ROM 不触发时 `fmt || samples>0` 兜底已在，但
  `hdc hilog | grep WotaRecord` 观察 "gpu record stream changed" 出现时机（应在首样本前后）。
- **隐藏期 surface 重建 deferred 路径**：对比页隐藏（切后台/分屏）触发 XComponent 重建 →
  回页后左右画面恢复且位置不回 0（resumeLeftPosMs/resumeRightPosMs 生效）。
- **滑杆负轴手感**：off>0 时进度条左端为负轴（pre-roll），拖到 T<0 左片应 park 首帧、
  右片落在 t+off；数值读数 pre-roll 段显 0（随左钟，登记的最小版口径）。
- **轮转后健康窗重启**：8MB 分段测试阈值下跨段录制，第二段起若编码器异常应仍触发自动重录
  （attempts 会话级不清零——第 2 段起可用重录次数少于首段，属登记的上游口径差异）。
- **本地签名包安装边界（T23）**：自签 ECC 证书 + debug profile 只证明签名链/包结构可验证，
  未经华为云颁发；零售机安装预期被拒属正常。真机安装验证改用 DevEco Studio 自动签名产物
  （AGC 账号）执行 `hdc install`，并回看首启权限弹窗/HUD 出画面。

## 交付后路线（T23 定稿，2026-10-22）

- **T24：纯逻辑直译批（无真机依赖，优先）**——`player/AudioSync.kt`、`player/CompareAudio.kt`
  （对齐数学）、`player/ComparePractice.kt`、`player/CompareHistory.kt`、`player/DecideCommands.kt`
  逐行直译 + `test/pure/` 单测对拍；顺手消费 T23 登记的 Compare 真 T 派生、AB 持久化等纯逻辑留白。
- **T25：内录双轨（全量需真机；真机前先做纯逻辑降级版）**——`record/CaptureStateMachine.kt`、
  静音/提示门控（SilencePromptGate 等价物）、`record/AudioFeeder.kt` 的纯决策面先直译 + 单测；
  `PlaybackCaptureController.kt`（AVScreenCapture 内录+环境音双轨）、`MrRecorder` 音轨布局、
  `ClipExport` 双音轨需真机验证后接。
- **T26+：长尾批**——蓝牙音箱面板（`BtSpeakerController.kt`）、应用内更新检查
  （`UpdateChecker/Downloader/Installer.kt`）、离线光弧修复（`ArcRepairPlan/Runner/Gl.kt`）、
  HUD 布局编辑器（`HudLayout/HudLayoutEditor/HudMetrics/HudPageGrid/ParamsPersist.kt`）、
  相册入库/分享/缩略图/系列归组等媒体长尾。

## 建议移植顺序

1. ~~`core` + 各包纯逻辑~~（core 第一轮 ☑ 2026-10-21，`diag` 最小移植 ☑，`record` 锚点七件 ☑ 2026-10-20）——打下语义地基；
2. DevEco 骨架 + `diag`；
3. 相机预览（XComponent）→ `ui` 基础 HUD；
4. `MrRecorder`（AVRecorder）最小可用录制 → `CodecRecorder`（GL + AVCodec）；
5. 光弧工具链（复用着色器与 ArcKeepRule）；
6. `media` 媒体库 → `player` 播放/剪辑 → 对比播放；
7. T23 后按「交付后路线」推进：T24 纯逻辑直译（AudioSync/CompareAudio/ComparePractice/
   CompareHistory/DecideCommands）→ T25 内录双轨（真机前先做 CaptureStateMachine/静音门控等
   纯逻辑降级版）→ T26+ 蓝牙/更新检查/离线修复/HUD 编辑器等长尾。
