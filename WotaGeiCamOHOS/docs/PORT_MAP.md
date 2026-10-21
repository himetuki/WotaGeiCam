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
| `Camera2Engine.kt`、`CameraController.kt`、`DualCameraProbe.kt` | 平台绑定 | `@kit.CameraKit`（ModeManager/CameraManager），能力探测从 `CameraDevice.formatInfos` 等运行时读取，沿用「不写死机型」原则 | ☐ |
| `CameraAbility.from()`、`LensSlot.enumerateLenses/discoverCameraIds`、`VideoSizePicker.minFrameDurationNs/videoSizeTable` 的 CameraKit 探测版适配层 | 平台绑定（采集侧） | `entry/src/main/ets/camera/`：`KitAbility.ets`（能力快照工厂，逐字段映射表见文件头注释）、`KitLens.ets`（镜头枚举，复用 core 的 shouldHideLogicalHead/assignRearTiers/dumpLenses）、`VideoSizeKit.ets`（帧周期按 videoProfiles maxFps 折算注入）、`KitProbe.ets`（探测入口，pages/Index 按钮消费）。探测版落地 2026-10-20，对齐上游 38d712a；会话期字段已补 zoom/ev/flash/iso/ois（预览批）与 exposureNs/evStep/evRange(档数换算)/afModes/awbModes/maxRegions=1（参数下发批，`KitAbility.abilityRefineWithSession`）；留白仅剩 tint/逐帧 fps/帧周期/EIS 真值与 VideoSession 参数面（随录制批）；上游「按数字 id 探隐藏镜头」无 OHOS 等价物，不移植（见 KitLens.ets 头注释登记） | ☑ 探测版 |
| `CameraController` 的「默认开广角后摄 + 出画面」最小闭环（预览批） | 平台绑定（会话侧） | `entry/src/main/ets/camera/`：`PermGate.ets`（CAMERA 权限申请门）+ `KitPreview.ets`（PhotoSession 预览引擎：默认档 assignRearTiers 后首个 WIDE、`pickDirectPreviewSize` 选档、createSession/createPreviewOutput/start、stop 逐项收尾、`applyParams` 参数下发入口）+ `pages/Index.ets`（XComponent(SURFACE) 预览面 + 权限流按钮 + 参数自检八档，探测路径保留）。落地 2026-10-21，对齐上游 38d712a。留白项：切镜头 UI、GL/DIRECT 双路、参数下发 UI（本批只有自检入口）、VideoSession 能力（EIS 真值随录制批）、永久拒绝引导（随 HUD 批）。装机验证项（不阻塞）：授权框弹出与拒绝重试、预览画面出现/关页无崩溃、`hdc hilog \| grep -E "WotaAbility\|WotaLens"` 真值与镜头列表 | ☑ 部分 |
| `RequestApplier.kt`（参数 → 请求的纯决策层 + OHOS 会话下发） | 纯逻辑 + 平台绑定（下发侧） | `camera/RequestApplier.ets`：State/stateOf、Snapshot/snapshotOf、clampShutterNs（双钳制+强制档豁免）、frameDurationNs、pickFps、exposurePlan、flashPlan（五分支）、stabilizePlan（三门控）逐行直译；apply 段 `applySnapshot(session,…)` 逐键 try/catch 产出 applied/skipped/failed 报告（`ApplyReport`），`KitPreview.applyParams` + Index「参数自检」为真实消费者。落地 2026-10-21，对齐上游 38d712a。审查修复订正（2026-10-21）：曝光时长单位 OHOS=微秒、core=纳秒（采集 ×1000/下发 ÷1000）；曝光组按档语义（MANUAL 写全 时长+ISO+EV 归零；LOCK/AUTO 只写模式与补偿——LOCK 冻结当前 AE 曝光，不写 SENSOR 键，OHOS LOCKED 下 setIso 会实际生效，登记）；MANUAL 用 LOCKED（d.ts：setIso 仅 LOCKED 生效，不用 EXPOSURE_MODE_MANUAL）；闪光由纯 flashPlan 驱动（无灯/手动退化/TORCH 全走 plan，state.flashAvailable 已消费）；EIS skipped 按用户意图判定并如实标注原因（PhotoSession 未暴露 EIS 参数面 ≠ 镜头不支持；高帧率/4K 门控原因单独列出）；手动对焦距离经 refine 的 isFocusDistanceSupported 探针门控 + ManualFocus.setFocusDistance 归一化下发（API24；探针不可得/6.0 设备 → skipped 不是 failed，登记）；evStep≤0 不猜 1、补偿键进 skipped；白平衡 MANUAL 模式与色温分键报告；4K 下 EIS=不下发该键（非显式 false）；ParamMap 反向探针 FOCUS_MODE_AUTO→AfMode.MACRO(2) 近似、catch 清空集合。不移植项（无 OHOS 等价物）：RGB 增益四件套（kelvinTintToRgb255/gain255ToUnit/rggbFromRgb255/identityColorTransform，手动白平衡走 mode+kelvin，tint 留白）、meteringBox/fullFrameBox/meteringRect（改用归一化 setFocusPoint/setMeteringPoint）、cropBox（只有 setZoomRatio）、afTrigger（无 AF 触发语义）、逐帧 AE_TARGET_FPS_RANGE（预览档 profile 固定，FpsPick 仅 State/dump）。`camera/ParamMap.ets` 承载枚举映射（FocusMode/WhiteBalanceMode/ExposureMode/OIS/VideoStabilization ↔ Camera2 契约值 + 反向探针；Flash 语义经 RequestApplier.flashPlan 派生）。装机验证项（不阻塞）：开预览→参数自检（变焦/色温/补光）→hilog WotaAbility 见每档 apply 与 failed 项 | ☑ |
| `GlRenderEngine.kt`、`ArcMendPass.kt`、`FrostBlur.kt`、`FrostBlurChain.kt`、`NtscGrid.kt` | 平台绑定（GL 着色器本体是纯逻辑） | XComponent + NDK NativeWindow，OpenGL ES 着色器源码几乎原样复用；斑马纹/峰值对焦同在此链路。GL-0 已落（CMake+napi entry 模块、EGL/GLES3、surface 生命周期、OnFrame 渲染循环）；**GL-1 已落（2026-10-21）**：`cpp/shaders.h` 上游首批基对逐字移植（TEXTURE_VS + PASS_THROUGH_FS，ES 1.00/OES/强制不透明）、quad 管线（VBO/uTexMatrix/采样单元 0）、`OH_NativeImage` 相机帧→GL_TEXTURE_EXTERNAL_OES（listener 只置原子位、UpdateSurfaceImage/GetTransformMatrix）、napi `getInputSurfaceId`（types/libentry 桥）、ES3 失败退 ES2、EGL 去 alpha fallback、OnFrame 注销与重进页补注册、swap/destroy 错误日志与 eglReleaseThread、`camera/GlCameraEngine.ets` + `pages/GlPreview` 闭环。留白：PASS_THROUGH_2D_FS/CURVE_FS/ARC_MERGE_FS/zebra/peaking/frost、Curve LUT 上传、旋转与镜像烘 UV（buildTexCoords）、独立渲染线程与性能控制、CodecRecorder 对接。已知：`.so` 导入在当前 SDK（COMPATIBLE linter 模式）下产生 1 条「napi module 未验证」工具链提示（非代码缺陷，后续 SDK 会接管校验）；**抑制通道已实测**：`// @ts-ignore` 被 ArkTS 以 arkts-strict-typing-required（10605146）硬拒（构建红），`// @ts-nocheck` 会牺牲整文件类型检查不可取，故保留该提示；方向未校正属 GL-2 项。装机验证项（不阻塞）：GL 页显示相机画面（方向未校正已知）；`hdc hilog \| grep WotaGL` 见 native image 创建/egl ready/first frame 日志；返回再进页恢复 | ☑ 部分（GL-0/GL-1） |
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
| `WotaDb.kt` | 平台绑定（schema 纯逻辑） | `@ohos.data.relationalStore`；表结构字段与上游保持一致，便于跨平台素材互认 | ☐ |
| `MediaRepo.kt`、`SeriesStore.kt`、`SeriesGroup.kt`、`MediaTag.kt` | 纯逻辑为主 | 直译，存储层换 relStore/首选项 | ☐ |
| `MediaActions.kt`、`Share.kt` | 平台绑定 | `photoAccessHelper` + `@ohos.app.ability.shareData`（系统分享） | ☐ |
| `GalleryScreen.kt` | UI | ArkUI 页面重写 | ☐ |

## player — 播放器 / 对比 / 剪辑

| 上游 | 类型 | 鸿蒙落点 | 状态 |
| --- | --- | --- | --- |
| `PlayerEngine.kt` | 平台绑定 | `@ohos.multimedia.media` AVPlayer + XComponent(Surface) | ☐ |
| `AudioSync.kt`、`CompareAudio.kt`（对齐数学） | 纯逻辑 | 直译 | ☐ |
| `FrameStep.kt`、`TapArbiter.kt`、`CompareTimeline.kt`、`ComparePractice.kt`、`CompareHistory.kt` | 纯逻辑为主 | 直译 | ☐ |
| `ClipExport.kt`、`RemuxOrientation.kt` | 平台绑定（封装逻辑纯） | NDK demux/remux 或 `@ohos.multimedia.media` AVMuxer/AVExtractor | ☐ |
| `PlayerScreen.kt`、`CompareScreen.kt` | UI | ArkUI 重写（AB 循环/逐帧/倍速/双轨对比交互照搬） | ☐ |

## record — 录制链路（移植重心）

| 上游 | 类型 | 鸿蒙落点 | 状态 |
| --- | --- | --- | --- |
| `MrRecorder.kt`（MediaRecorder 引擎） | 平台绑定 | `@ohos.multimedia.media` AVRecorder + CameraKit VideoSession：`camera/KitRecorder.ets` 最小录制（VIDEO_SOURCE_TYPE_SURFACE_YUV 面输入、NORMAL_VIDEO 会话、**videoProfiles + frameRateRange 覆盖选档**（VideoSizeTable 留待后续）、BitratePolicy 复用、SeriesIds 接线、MIN_KEEP_MS 判废）。落地 2026-10-21，对齐上游 38d712a。输出契约：`AVRecorderConfig.url = fd://<fd>`（fileIo.openSync 建文件取 fd，fd 保留到 AVRecorder reset/release 后关闭）；起录失败统一回收资源并删除已创建/半写文件；判废顺序=先 MIN_KEEP_MS（短段含 engineErr 一律 TOO_SHORT），唯一例外 av.stop 失败保留文件 triage（STOP_FAILED）；start/stop 有 starting/stopping 重入保护。留白：3.5GiB 分段轮转/起录健康三格/AutoStop 巡检/FrameLedger 接线/HEVC 重试/.drops.json/参数下发（VideoSession 无 ManualExposure/ManualIso/OIS）/orientationHint 与 AVRecorderConfig.rotation 未接线（显示旋转源随 HUD 批）/sampleRate 恒 48k 未探测/mirrored/useGpu 恒 false。装机验证项（不阻塞）：录 5 秒文件可播放有声音、停录后预览恢复、拒绝 MIC 录无声视频、<1 秒判废删除、fd:// 契约真机验证 | ☑ 部分 |
| `CodecRecorder.kt`（MediaCodec+MediaMuxer 引擎） | 平台绑定 | AVCodec + AVMuxer（NDK 侧），与 GL 链路衔接 | ☐ |
| `CaptureFgService.kt`（前台服务） | 平台绑定 | 长时任务（continuous task）+ 通知栏常驻 | ☐ |
| `PlaybackCaptureController.kt`（内录） | 平台绑定 | AVScreenCapture 音频内部录制（环境音+内录双轨） | ☐ |
| `Recorder.kt`、`CaptureStateMachine.kt`、`RecordProfile.kt`、`VideoStore.kt` | 纯逻辑为主 | 直译（状态机与接口不动，引擎实现注入）。最小契约已落：`record/RecordResult.ets`（RecordError 机器码 + RecordResult 首段字段/summary，VideoSegment/health/parts 留待分段批）、`record/RecordProfile.ets`（纯字段子集 + CODEC_H264/CODEC_HEVC/MIN_KEEP_MS + effectiveBitrate，captureAudio/arcConvert/fpsNtsc 留白）、`record/VideoStore.ets`（VID_yyyyMMdd_HHmmss_###.mp4 命名/MIN_FREE_MB 余量/sizeOf/discard；MediaStore pending 与相册入库留白）。落地 2026-10-21，对齐上游 38d712a | ☑ 部分 |
| `ArcKeepRule.kt`、`ArcDropLog.kt`、`BitratePolicy.kt`、`AutoStopRules.kt`、`FrameLedger.kt` | 纯逻辑 | **逐行直译**——这些是与上游共享语义的锚点（见 UPSTREAM_SYNC.md），格式/判据/阈值必须与上游逐字段一致。直译完成 2026-10-20，对齐上游 38d712a，落点 `entry/src/main/ets/record/` 同名 .ets；BitratePolicy 的 `resolve(RecordProfile)` 重载待 RecordProfile 移植后补 | ☑ |
| `RecordHealth.kt` | 纯逻辑 | 同上锚点纪律，下一批 | ☐ |
| `ArcRepairPlan.kt`、`ArcRepairRunner.kt`、`ArcRepairGl.kt`（离线修复） | 平台绑定（计划逻辑纯） | 计划直译；执行走 CPU（ArkTS/NDK）与 GPU（复用 GL 着色器）双路 | ☐ |
| `PreMuxVideoBuffer.kt`（起录缓冲）、`SeriesIds.kt`、`PendingName.kt`、`StalePendingSweep.kt`、`RotateGuard.kt`、`RotateFailLog.kt`、`OrientationExport.kt`、`AudioFeeder.kt` | 纯逻辑为主 | 直译。`SeriesIds.ets`、`PendingName.ets` 直译完成 2026-10-20（对齐上游 38d712a，落点 `entry/src/main/ets/record/` 同名 .ets）；其余待后续 | ☑ 部分 |

## ui — 界面（全部 ArkUI 重写）

| 上游 | 类型 | 鸿蒙落点 | 状态 |
| --- | --- | --- | --- |
| `CameraScreen.kt`、`MainActivity.kt`、`HudLayer/HudLayout/HudLayoutEditor/HudMetrics/HudPageGrid.kt`、`CameraPills.kt`、`SettingsScreen.kt`、`SettingsLayout.kt`、`ParamsPersist.kt`、`PreviewCoords.kt` | UI | ArkUI 页面；HUD 自定义布局编辑器语义照搬 | ☐ |
| `design/Tokens.kt`、`PillPopup.kt`、`SheetSpec.kt`、`Widgets.kt` | UI（设计令牌近纯逻辑） | Tokens 直译为 ArkUI 资源，组件用 ArkUI 重写 | ☐ |
| `dialog/`（音源/蓝牙/相机/曲线弹层） | UI | ArkUI CustomSheet/弹窗 | ☐ |
| `widget/`（CameraSurface、ParamSlider、SliderLadder 刻度滑杆、RefLineOverlay 参考线、AttitudeMeter、AudioChip） | 平台绑定 + UI | XComponent 预览 + ArkUI 自定义组件；滑杆分档语义照搬 | ☐ |
| `anim/`、`theme/`、`WindowKeepOn.kt`、`FrostSpace.kt`、`FrostProbe.kt`、`CompareProbe.kt`、`GalleryEntryScreen.kt`、`RecordResultNotice.kt` | UI / 薄绑定 | ArkUI 动效与 `window.setWindowKeepScreenOn` | ☐ |

## update — 应用内更新

| 上游 | 类型 | 鸿蒙落点 | 状态 |
| --- | --- | --- | --- |
| `UpdateChecker.kt`、`UpdateDownloader.kt`、`UpdateInstaller.kt` | 平台绑定（策略纯） | `@ohos.net.http` 读 GitHub Releases；**手动联网、SHA-256 校验、不自动检查**等隐私承诺照搬；安装侧需改为鸿蒙包体（hap/hsp）或引导下载页 | ☐ |

## 建议移植顺序

1. ~~`core` + 各包纯逻辑~~（core 第一轮 ☑ 2026-10-21，`diag` 最小移植 ☑，`record` 锚点七件 ☑ 2026-10-20）——打下语义地基；
2. DevEco 骨架 + `diag`；
3. 相机预览（XComponent）→ `ui` 基础 HUD；
4. `MrRecorder`（AVRecorder）最小可用录制 → `CodecRecorder`（GL + AVCodec）；
5. 光弧工具链（复用着色器与 ArcKeepRule）；
6. `media` 媒体库 → `player` 播放/剪辑 → 对比播放；
7. 内录双轨、蓝牙面板、更新检查。
