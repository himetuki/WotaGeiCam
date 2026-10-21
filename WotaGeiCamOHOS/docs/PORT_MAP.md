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
| `GlRenderEngine.kt`、`ArcMendPass.kt`、`FrostBlur.kt`、`FrostBlurChain.kt`、`NtscGrid.kt` | 平台绑定（GL 着色器本体是纯逻辑） | XComponent + NDK NativeWindow，OpenGL ES 着色器源码几乎原样复用；斑马纹/峰值对焦同在此链路 | ☐ |
| `DirectSink.kt`、`GlRenderSuppression.kt`、`FrameRateFallback.kt` | 纯逻辑为主 | 直译 + 适配相机回调签名 | ☐ |
| `LevelMath.kt`（水平仪/俯仰数学） | 纯逻辑 | 直译；传感器输入改 `@ohos.sensor` | ☐ |

## core — 参数与常量（全部直译）

| 上游 | 类型 | 鸿蒙落点 | 状态 |
| --- | --- | --- | --- |
| `Constants.kt`、`WotaParams.kt`、`CurveModel.kt`、`LensSlot.kt`、`UIOrientation.kt`、`VideoSizePicker.kt` | 纯逻辑 | `common/` 共享模块，逐文件直译 | ☐ |
| `CameraAbility.kt` | 纯逻辑 + 薄适配 | 判据逻辑直译，能力来源换成鸿蒙相机格式描述 | ☐ |
| `StorageAccess.kt` | 平台绑定 | `@ohos.file.fs` + `photoAccessHelper`（媒体库写入用安全控件/授权） | ☐ |

## diag — 诊断日志

| 上游 | 类型 | 鸿蒙落点 | 状态 |
| --- | --- | --- | --- |
| `Diag.kt` | 平台绑定（薄） | `@ohos.hilog` 封装同名 API | ☐ |

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
| `MrRecorder.kt`（MediaRecorder 引擎） | 平台绑定 | `@ohos.multimedia.media` AVRecorder | ☐ |
| `CodecRecorder.kt`（MediaCodec+MediaMuxer 引擎） | 平台绑定 | AVCodec + AVMuxer（NDK 侧），与 GL 链路衔接 | ☐ |
| `CaptureFgService.kt`（前台服务） | 平台绑定 | 长时任务（continuous task）+ 通知栏常驻 | ☐ |
| `PlaybackCaptureController.kt`（内录） | 平台绑定 | AVScreenCapture 音频内部录制（环境音+内录双轨） | ☐ |
| `Recorder.kt`、`CaptureStateMachine.kt`、`RecordProfile.kt`、`VideoStore.kt` | 纯逻辑为主 | 直译（状态机与接口不动，引擎实现注入） | ☐ |
| `ArcKeepRule.kt`、`ArcDropLog.kt`、`BitratePolicy.kt`、`AutoStopRules.kt`、`FrameLedger.kt`、`RecordHealth.kt` | 纯逻辑 | **逐行直译**——这些是与上游共享语义的锚点（见 UPSTREAM_SYNC.md），格式/判据/阈值必须与上游逐字段一致 | ☐ |
| `ArcRepairPlan.kt`、`ArcRepairRunner.kt`、`ArcRepairGl.kt`（离线修复） | 平台绑定（计划逻辑纯） | 计划直译；执行走 CPU（ArkTS/NDK）与 GPU（复用 GL 着色器）双路 | ☐ |
| `PreMuxVideoBuffer.kt`（起录缓冲）、`SeriesIds.kt`、`PendingName.kt`、`StalePendingSweep.kt`、`RotateGuard.kt`、`RotateFailLog.kt`、`OrientationExport.kt`、`AudioFeeder.kt` | 纯逻辑为主 | 直译 | ☐ |

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

1. `core` + 各包纯逻辑（锚点文件优先，`record` 纯逻辑组最优先）——打下语义地基；
2. DevEco 骨架 + `diag`；
3. 相机预览（XComponent）→ `ui` 基础 HUD；
4. `MrRecorder`（AVRecorder）最小可用录制 → `CodecRecorder`（GL + AVCodec）；
5. 光弧工具链（复用着色器与 ArcKeepRule）；
6. `media` 媒体库 → `player` 播放/剪辑 → 对比播放；
7. 内录双轨、蓝牙面板、更新检查。
