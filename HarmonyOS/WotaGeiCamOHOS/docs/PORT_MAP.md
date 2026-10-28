# 移植映射表（Android → HarmonyOS）

> 注（2026-10-22 单树重组）：表中上游路径多为重组前的写法（`WotaGeiCamApp/...`）。
> 重组后上游工程位于 main 的 `Android/WotaGeiCamApp/` 下（读法
> `git show origin/main:Android/WotaGeiCamApp/...`），鸿蒙侧落点路径不变。

上游源码根：`WotaGeiCamApp/app/src/main/java/com/wotagei/cam/`（117 个 Kotlin 文件，下表省略该前缀）。

> **2026-10-22 历史剥离**：harmony 已重写为 OHOS-only 独立历史，`WotaGeiCamApp/`
> （与 `WotaGeiCam_Android_Icons/`、`.github/`、根 `README.md`）不再入库，仅在本机磁盘保留、
> 未跟踪（被 `.gitignore` 忽略）供对拍，**权威读法改为**
> `git show main:WotaGeiCamApp/app/src/main/java/com/wotagei/cam/<文件>`（main = origin/main 镜像）。
> 与 origin/main 无共同祖先，pull/merge 废用；同步方式见 [UPSTREAM_SYNC.md](UPSTREAM_SYNC.md)。

> README 的同步表述待 CI 会话收尾后更新；HAP CI workflow（`.github/`）未入库，待其所有者决定。

类型说明：
- **纯逻辑** —— 不依赖 Android API（或仅依赖可等价替换的薄层），可近乎逐行翻译成 ArkTS/TS，移植成本最低；
- **平台绑定** —— 依赖 Camera2 / MediaCodec / GL / 服务等平台能力，需按鸿蒙对应 Kit 重写；
- **UI** —— Jetpack Compose 页面，用 ArkUI 声明式重写（布局与交互语义照搬）。

类型为初判，随移植推进逐项确认并勾状态。

## bt — 蓝牙音箱控制

| 上游 | 类型 | 鸿蒙落点 | 状态 |
| --- | --- | --- | --- |
| `BtSpeakerController.kt`、`ui/dialog/BtSpeakerPanel.kt`（媒体音量/播放控制/电量） | 平台绑定 | **BT 批已落（2026-10-24，减法定版）**：`bt/BtSpeakerModel.ets`（纯逻辑：配对表+A2DP 连接表合并去重、已连接置前其余稳定保输入序、电量/状态文案——未上报显「—」不编 0%）+ `bt/BtSpeakerController.ets`（`connection.getPairedDevices`/`getRemoteDeviceName` + `a2dp.createA2dpSrcProfile().getConnectedDevices` + `getRemoteDeviceBatteryInfo`(since 12) + `on('batteryChange')` 实时刷新；异常全折成「蓝牙未开」引导或空表+日志）+ `pages/BtPanel.ets`（列表/连接徽标/电量/刷新/引导态；页脚一行减法说明）+「更多」面板入口。**登记**：① 电量**不走 GATT 电池服务**（原登记作废，`getRemoteDeviceBatteryInfo` 直取）；② `BatteryInfo` **不带 deviceId**（SDK 核实，只有 batteryLevel+左右耳/仓三组）——回执按「首个已连接设备」合并，双连接另一台靠手动刷新；③ **不迁**（调研报告 §1，无三方等价物）：STREAM_MUSIC 音量写（AudioVolumeGroupManager 无 setVolume）、播放/上一曲/下一曲（avsession 控制器需 system 权限）、扫描发现（startBluetoothDiscovery 需定位权限，连接管理交系统设置）；④ 权限 `ACCESS_BLUETOOTH`（normal，只声明不弹窗）。真机待验：801 能力不支持机型、电量实时性、双连接场景 | ☑ 部分（减法版） |

## camera — 相机引擎与 GL 渲染

| 上游 | 类型 | 鸿蒙落点 | 状态 |
| --- | --- | --- | --- |
| `Camera2Engine.kt`、`CameraController.kt`、`DualCameraProbe.kt` | 平台绑定 | `@kit.CameraKit`（ModeManager/CameraManager），能力探测从 `CameraDevice.formatInfos` 等运行时读取，沿用「不写死机型」原则。**最小接线已落（2026-10-21）**：`camera/GlCameraEngine.ets` 增加 listLenses/currentLens/currentSize/supportedPreviewSizes、按 slotKey 重开、按画幅选预览档（未命中回退 1080p）、applyZoom（会话 zoomRange 钳制）、applyFlash（CLOSE/ALWAYS_OPEN）、DeviceStatus 转移；Camera 主页面消费。登记偏离：PhotoSession 预览无 setFrameRate（该 API 属 VideoOutput），预览源可能仍为 30fps；录制帧率只在编码器/muxer 侧生效，24/25 光弧按编码器输出节奏。 | ☑ 部分 |
| `CameraAbility.from()`、`LensSlot.enumerateLenses/discoverCameraIds`、`VideoSizePicker.minFrameDurationNs/videoSizeTable` 的 CameraKit 探测版适配层 | 平台绑定（采集侧） | `entry/src/main/ets/camera/`：`KitAbility.ets`（能力快照工厂，逐字段映射表见文件头注释）、`KitLens.ets`（镜头枚举，复用 core 的 shouldHideLogicalHead/assignRearTiers/dumpLenses）、`VideoSizeKit.ets`（帧周期按 videoProfiles maxFps 折算注入）、`KitProbe.ets`（探测入口，pages/Index 按钮消费）。探测版落地 2026-10-20，对齐上游 342dd99；会话期字段已补 zoom/ev/flash/iso/ois（预览批）与 exposureNs/evStep/evRange(档数换算)/afModes/awbModes/maxRegions=1（参数下发批，`KitAbility.abilityRefineWithSession`）；留白仅剩 tint/逐帧 fps/帧周期/EIS 真值与 VideoSession 参数面（随录制批）；上游「按数字 id 探隐藏镜头」无 OHOS 等价物，不移植（见 KitLens.ets 头注释登记） | ☑ 探测版 |
| `CameraController` 的「默认开广角后摄 + 出画面」最小闭环（预览批） | 平台绑定（会话侧） | `entry/src/main/ets/camera/`：`PermGate.ets`（CAMERA 权限申请门）+ `KitPreview.ets`（PhotoSession 预览引擎：默认档 assignRearTiers 后首个 WIDE、`pickDirectPreviewSize` 选档、createSession/createPreviewOutput/start、stop 逐项收尾、`applyParams` 参数下发入口）+ `pages/Index.ets`（XComponent(SURFACE) 预览面 + 权限流按钮 + 参数自检八档，探测路径保留）。落地 2026-10-21，对齐上游 342dd99。留白项：切镜头 UI、GL/DIRECT 双路、参数下发 UI（本批只有自检入口）、VideoSession 能力（EIS 真值随录制批）、永久拒绝引导（随 HUD 批）。装机验证项（不阻塞）：授权框弹出与拒绝重试、预览画面出现/关页无崩溃、`hdc hilog \| grep -E "WotaAbility\|WotaLens"` 真值与镜头列表 | ☑ 部分 |
| `RequestApplier.kt`（参数 → 请求的纯决策层 + OHOS 会话下发） | 纯逻辑 + 平台绑定（下发侧） | `camera/RequestApplier.ets`：State/stateOf、Snapshot/snapshotOf、clampShutterNs（双钳制+强制档豁免）、frameDurationNs、pickFps、exposurePlan、flashPlan（五分支）、stabilizePlan（三门控）逐行直译；apply 段 `applySnapshot(session,…)` 逐键 try/catch 产出 applied/skipped/failed 报告（`ApplyReport`），`KitPreview.applyParams` + Index「参数自检」为真实消费者。落地 2026-10-21，对齐上游 342dd99。审查修复订正（2026-10-21）：曝光时长单位 OHOS=微秒、core=纳秒（采集 ×1000/下发 ÷1000）；曝光组按档语义（MANUAL 写全 时长+ISO+EV 归零；LOCK/AUTO 只写模式与补偿——LOCK 冻结当前 AE 曝光，不写 SENSOR 键，OHOS LOCKED 下 setIso 会实际生效，登记）；MANUAL 用 LOCKED（d.ts：setIso 仅 LOCKED 生效，不用 EXPOSURE_MODE_MANUAL）；闪光由纯 flashPlan 驱动（无灯/手动退化/TORCH 全走 plan，state.flashAvailable 已消费）；EIS skipped 按用户意图判定并如实标注原因（PhotoSession 未暴露 EIS 参数面 ≠ 镜头不支持；高帧率/4K 门控原因单独列出）；手动对焦距离经 refine 的 isFocusDistanceSupported 探针门控 + ManualFocus.setFocusDistance 归一化下发（API24；探针不可得/6.0 设备 → skipped 不是 failed，登记）；evStep≤0 不猜 1、补偿键进 skipped；白平衡 MANUAL 模式与色温分键报告；4K 下 EIS=不下发该键（非显式 false）；ParamMap 反向探针 FOCUS_MODE_AUTO→AfMode.MACRO(2) 近似、catch 清空集合。不移植项（无 OHOS 等价物）：RGB 增益四件套（kelvinTintToRgb255/gain255ToUnit/rggbFromRgb255/identityColorTransform，手动白平衡走 mode+kelvin，tint 留白）、meteringBox/fullFrameBox/meteringRect（改用归一化 setFocusPoint/setMeteringPoint）、cropBox（只有 setZoomRatio）、afTrigger（无 AF 触发语义）、逐帧 AE_TARGET_FPS_RANGE（预览档 profile 固定，FpsPick 仅 State/dump）。`camera/ParamMap.ets` 承载枚举映射（FocusMode/WhiteBalanceMode/ExposureMode/OIS/VideoStabilization ↔ Camera2 契约值 + 反向探针；Flash 语义经 RequestApplier.flashPlan 派生）。装机验证项（不阻塞）：开预览→参数自检（变焦/色温/补光）→hilog WotaAbility 见每档 apply 与 failed 项 | ☑ |
| `GlRenderEngine.kt`、`ArcMendPass.kt`、`FrostBlur.kt`、`FrostBlurChain.kt`、`NtscGrid.kt` | 平台绑定（GL 着色器本体是纯逻辑） | XComponent + NDK NativeWindow，OpenGL ES 着色器源码几乎原样复用；斑马纹/峰值对焦同在此链路。GL-0（构建骨架/EGL/OnFrame）、GL-1（NativeImage 相机帧 + 基对）、GL-2（旋转镜像/曲线 LUT/斑马/峰值）已落；**GL-3 已落（2026-10-21）**：FBO/RT 基础设施（frostRtSizeInto/FrostRtNeedsRebuild/fillFrostBlurOffset 逐行；RGBA8+LINEAR+CLAMP，glCheckFramebufferStatus 完整性出口）、三趟链（OES→2D 拷贝→横向高斯→纵向高斯，权重 0.17524/0.16577/0.14032/0.10629 逐值，highp→mediump 退避，错误连击 8 帧停链）、PASS_THROUGH_2D_FS 上屏、全屏毛玻璃开关（页面「毛玻璃」）；**GL-4 编码面已落（2026-10-21）**：EGL 第二个 window surface 挂编码器输入面（复用初始化缓存的 EGLConfig）、编码 pass 每帧第二趟且仅新相机帧驱动（vsync 重绘不重复送帧；上屏 swap 后）、编码 UV 口径 deg=stRotDeg 且不镜像（上游 buildTexCoords(encoderPass=true) 语义）、**编码 pass 绝不进入 frost 分支（上游红线：霜面是上屏专属）**、surface 丢失中止；编码 pass swap/绘制连续失败 3 次中止（encoder-pass-failed）；恢复上屏上下文失败 1 次即中止，g_displayCtxLost 每帧重试上限 60 帧（达上限 abort display-context-lost，首帧与上限各一条日志）、WotaGlDestroy 先 detach+abort；**上批 P3×6 修正（2026-10-21）**：帧账录制纪元（frameStart 只计录制窗口；begin 前入队、begin 后执行的残余 frameStart 登记为观测偏差，相机帧取 max(cameraDelta,glCbDelta) 上界，仅作到达上界/不得当精确丢帧数）、sidecar 诊断读取门控（skipped(diag)）、flush DrawPending 失败计 swapFail、display-context-lost 页面提示并禁用录制、实测源帧率整场口径（降级不重置，登记偏离）、MEND attach 最多 2 次重试后降 DROP；frost 开关只释放离屏资源/program（基础 g_idUvVbo 归 EGL 管线生命周期，开→关→开可用）；**GL-4 光弧链在线版已落（2026-10-21，对齐上游 342dd99）**：ARC_MERGE_FS（整像素选择+差分死区）逐 token、ArcMendPass 等价（cur+pending×2+acc×2 五张 unsized GL_RGBA FBO（ES2/ES3 通吃，对齐上游/frost）、两枚 program、完整性出口/Release 清零）、ArcKeepRule/ArcRepairFlow C++ 逐行对拍、MEND/DROP 只吃新相机帧、MEND 离屏补弧与停录 flush、MEND→DROP 降级（降级帧无条件直通补发、不记新 rule 位次）；drops 位次账与实测源帧率、原生五格帧账；**编码 pass/MEND 均不进入 frost 分支（上游红线）**。留白：ARC_MERGE_FS（A/B 修复链已落 shaders.h；离线 GPU 消费 T29）、Frost 画板/圆角 SDF/卡片 UV/letterbox 几何（HUD 批；本批 RT 内容=整屏等比画面，登记与上游 letterbox 口径差异）、NtscGrid（ntscGridStepNs 纯函数，Codec 批）、独立渲染线程与性能控制。已知：全屏毛玻璃与监视效果互斥一帧；`.so` 导入 1 条 napi 未验证工具链提示（已实测无抑制通道）。装机验证项（不阻塞）：毛玻璃开关画面整体模糊、方向与直通一致、关闭恢复效果态；**毛玻璃开→关→开仍可用**；横竖屏下模糊方向与画面一致；`hdc hilog \| grep WotaGL` 见 frost RT/programs/错误日志 | ☑ 部分（GL-0/1/2/3/4） |
| `DirectSink.kt`、`GlRenderSuppression.kt`、`FrameRateFallback.kt` | 纯逻辑为主 | **H 批已落（2026-10-23，纯函数三件）**：`core/DirectDisplay.ets`（`DirectDisplay.matrix` ← 上游 `directDisplayMatrix` 逐行等价——按施加角顺时针旋转 + 等比居中信箱黑边 + 镜像乘在旋转**之后**的屏幕横轴（否则成了传感器左右反）；任一尺寸 ≤0 退单位阵；非 90 倍数的 sensorOrientation 按旋转后包围盒 `bw·|c|+bh·|s|` 算黑边；语义与 GL 路「纹理旋转 + 等比居中 contentRect」等价，两模式因此同角度）、`camera/GlRenderSuppression.ets`（`degradeSuppressed(recordTearingDown, segmentRotating)`：停止序列窗口 / GPU 分段轮转重挂窗口，任一为真即抑制转换降级——窗口内丢几帧好过 MEND→DROP 清掉位次账、DROP→直通把换段点之后的成片降成慢放）、`core/FrameRateFallback.ets`（`FrameRateFallback.ceilingFps(ranges, cap)`：只排除被 cap 截断后不可用的范围 `min(hi,cap) >= lo`，固定档 `[f,f]` 不再被误杀；修复旧口径 `usable > r.lo` 排除全部固定档 + `minOfOrNull{hi}` 兜底取全表**最小**上界、用户选 120fps 却退到 15fps 的 S3-01 缺陷；一个可用范围都没有返回 0 = 无退档目标）。三模块零平台 import，均已进 `PureLogicBarrel` 编译探针。**登记（不移植/无接线点）**：① `DirectSink` 类体的平台胶水（`TextureView`/`SurfaceTexture`/`Surface`/`Matrix`/`Handler` 线程切回与选流重算）本侧无等价物——ArkUI 只有 XComponent surface 即 GL 路径，没有「相机帧直落承载视图 + 读回 SurfaceTexture 变换矩阵」通道；② `PreviewOrientation` 未移植，因此 `directDisplayMatrix` 在本侧**没有调用方**，本批只交付纯函数 + 全表用例；③ 上游 `Camera2Engine.ceilingFps`/`syncFpsPick`/`degradeArcConvert` 的源码结构守卫臂在本侧无对应接线点（相机引擎是 `GlCameraEngine`，帧率下发走 `RequestApplier.pickFps`，抑制判定在 native `cpp/gl_render.cpp` 的 C++ 分支，不在 ArkTS 可扫描面），不伪造接线。 | ☑ 部分（纯函数三件；接线待 C++ 共用口径） |
| `LevelMath.kt`（水平仪/俯仰数学）、`SensorLevel.kt`、`ui/widget/AttitudeMeter.kt` | 纯逻辑 + 平台绑定 + UI | **已落（2026-10-23，E 批）**：`core/LevelMath.ets`（displayVector 四象限/rollOf 折半圆/pitchOf/foldToHalfCircle/toDegrees/isLevelOf + `LevelConst` 全部纯常量）、`camera/LevelSensor.ets`（`@ohos.sensor` 优先 GRAVITY、无融合回退 ACCELEROMETER，`interval:'game'`≈50Hz；分量低通 0.30、NaN 入口丢弃、`MIN_VALID_G`/`FLAT_GUARD` 丢帧闸、0.1° 发布节流、回正两路独立 `vibrator` 轻震、**首启静默自愈 watchdog**（600ms 零样本重挂，上限 2 次，stop 复位预算）；显示方向经 `setDisplayDegrees` 喂入）、`ui/AttitudeMeter.ets`（Canvas 人工地平仪：满量程 roll±30/pitch±45、天地双色 16% 透明、±10/±20 刻度、固定机标；点按切「气泡/数值」两模式，会话态不持久化；状态行「已回正/偏斜中」）。接线：`pages/Camera.ets` 右上角浮层（`levelOn && levelAvailable` 才显示），进页面建源、`onPageShow`/`onPageHide`/`aboutToDisappear` 启停（传感器常开很费电），显示旋转变化即重喂；`SettingsStore` 增 `level_enabled`/`level_buzz_enabled`（键名与上游逐字一致，默认 true）+ 设置页「取景辅助」分组两开关；`module.json5` 增 `ohos.permission.VIBRATE`（normal） | ☑ 完整（真机待验） |

## core — 参数与常量（第一轮直译完成 2026-10-21，对齐上游 342dd99）

| 上游 | 类型 | 鸿蒙落点 | 状态 |
| --- | --- | --- | --- |
| `Constants.kt`、`WotaParams.kt`、`CurveModel.kt`、`UIOrientation.kt` | 纯逻辑 | `entry/src/main/ets/core/` 同名 .ets，StateFlow 用轻量等价类，UIOrientation 的方向映射换 `window.Orientation.AUTO_ROTATION_*`（语义对齐 SENSOR_*） | ☑ |
| `LensSlot.kt` | 纯逻辑 + 平台绑定 | `core/LensSlot.ets`：LensType/LensSlot/assignRearTiers/shouldHideLogicalHead/dumpLenses 已直译；`enumerateLenses/discoverCameraIds` 适配层探测版已落 `camera/KitLens.ets`（会话期字段待预览批） | ☑ 部分 |
| `CameraAbility.kt` | 纯逻辑 + 薄适配 | `core/CameraAbility.ets`：RangeI/RangeF/能力快照/纯函数族（pickFpsRange、shutterCeilingNs、eqFocalMm、hFovDeg…）已直译；`from()` 适配层探测版已落 `camera/KitAbility.ets`（会话期字段待预览批） | ☑ 部分 |
| `VideoSizePicker.kt` | 纯逻辑 + 薄适配 | `core/VideoSizePicker.ets`：候选表/求交/落档/直显预览挑选已直译；`minFrameDurationNs` 注入适配层探测版已落 `camera/VideoSizeKit.ets`（会话期字段待预览批） | ☑ 部分 |
| `StorageAccess.kt` | 平台绑定 | **决策：不移植（2026-10-24，MS 批随调研定版；2026-10-25 补证定版）**——「所有文件访问」在 OHOS **无对应权限/API**（本机 SDK API 面搜 `MANAGE_*`/`ALL_FILES`/`FILE_ACCESS` 零命中；OHOS 的 `MANAGE_*` 类权限都是 `ohos.permission.MANAGE_LOCAL_ACCOUNTS` 一类系统能力，不由应用申请）；本仓媒体库只扫应用沙箱 `filesDir/videos`（增删改名零权限零弹窗），上游该文件的存在理由（跨应用静默删除别人的媒体行/规避系统授权框）在沙箱模型下不成立（调研报告 §2.3/§2.8）。**ALB 批的相册导入**是该需求的合规替代形态（选择器临时授权）。photoAccessHelper 面归入相册入库（MS 批已落 `AlbumSaver`） | ☑ 不移植（登记） |

## diag — 诊断日志

| 上游 | 类型 | 鸿蒙落点 | 状态 |
| --- | --- | --- | --- |
| `Diag.kt` | 平台绑定（薄） | `diag/DiagPure.ets`（纯函数层：diagLine/diagStamp/头部文案/轮转命名/丢弃自曝/帧账段，逐字段锚定）+ `diag/Diag.ets`（完整版：总开关默认关+级别档位门控、ring 3000/pending 3000 封顶丢最老并自曝、setInterval 1s 拍落盘 ≤500 行、512KB×3 轮转、崩溃件同步直写留 3、导出报告 2MB 预算）+ EntryAbility onCreate/onDestroy 接线。完整版落地 2026-10-21，对齐上游 342dd99。有意偏离（登记于 Diag.ets 头注释）：hilog 恒写双通道、setInterval 替代写盘线程（导出于 UI 线程同步读拼写有阻塞风险，TaskPool 化留待后续）、errorManager 'error' 替代 UncaughtExceptionHandler（无上一处理器链）、last-session.txt 替代 SharedPreferences、导出只写应用目录、时区 ±HH:MM/线程恒 main、deviceSummary 的硬件行用 hardwareModel 近似且设备行用 deviceType（语义与 Android Build.HARDWARE/Build.DEVICE 不同）、board/fingerprint 省行、ABI 用 deviceInfo.abiList。留白：~~公共目录导出 / exportForShare 分享面板~~（**DIAG-SHARE 批 2026-10-25 已落**：分享走 systemShare、另存走 DocumentViewPicker 用户选址=沙箱模型下公共目录的零权限等价路径，见交付批节）、~~总开关/详细档持久化与 UI 入口~~（ST 批持久化 + DIAG 批设置页区块已落）。装机验证项：开日志→导出→hdc file recv 看行格式与轮转；人为崩溃→crash-*.txt；清除后 logs 目录内本系统日志件（当前件/轮转件/崩溃件）清空，last-session.txt 保留，且日志关闭时不会立即新建当前件 | ☑ |

## media — 媒体库

| 上游 | 类型 | 鸿蒙落点 | 状态 |
| --- | --- | --- | --- |
| `WotaDb.kt` | 平台绑定（schema 纯逻辑） | `media/WotaDb.ets`：RDB（wota_media.db，version 1）四表逐字段对齐上游——`media_tag(id,mediaId,path,tag,createdAt)` + UNIQUE(mediaId,tag) + INDEX(tag)、`trash_item(uri PK,mediaId,name,deletedAt,origin)` + INDEX(mediaId)、`media_series(seriesId,startedAt,partCount)`、`series_part(seriesId,partIndex,mediaId,uri,durationMs)` + PK(seriesId,partIndex)；DAO 全 Promise、ResultSet 必 close、SQL 失败 Diag.e 后上抛。落地 2026-10-21，对齐上游 342dd99。版本不匹配按 Room fallbackToDestructiveMigration 等价实现 deleteRdbStore 重建（收藏/标签/回收站一起丢，登记）；schema 导出 JSON/Migration 未做 | ☑ 部分 |
| `MediaRepo.kt`、`SeriesStore.kt`、`SeriesGroup.kt`、`MediaTag.kt` | 纯逻辑为主 | `media/MediaRepo.ets`：扫描私有 filesDir/videos/*.mp4（stat size/mtime + AVMetadataExtractor 1.5s 超时补时长/分辨率，失败归零）、WotaDb 联表标签/回收站/系列、dateAddedSec 降序；ID=相对路径 32 位 FNV-1a（0→1；碰撞概率 1 万条约 1.2%/10 万条约 69%，升级 64 位或 (mediaId,path) 二次确认，登记）；探测 1.5s 超时只放弃结果、fd/extractor 延后到 fetch settle 回收；正在录制的半成品排除在列表外且动作层拒绝；收藏/移入回收站/还原/彻底删除（unlink + .drops.json + 清账）。`media/MediaTag.ets`：TagSpace（like/trash/长度 24/保留词大小写不敏感）、MediaScope/ListMode、ClipFilter/GalleryTabs（All→Like→Trash→自定义）/clampedSelection 逐行。`media/MediaFormat.ets`：groupTimeline（今天/昨天/同年/跨年）、formatDuration、formatSize、formatSignedDuration（T23 新增：负轴带符号时长，对比页时间线左端标签消费）。落地 2026-10-21，对齐上游 342dd99。**G 批（2026-10-23）补 `media/SeriesGroup.ets`**：`groupSpans`（seriesId 归组、≥2 段才成组、partIndex 升序含脏数据排尾、首见位定序、`collapse=false` 逐字等价基线）、`galleryEntries`（代表段 = 首段）、`expandSeriesTargets`（组级动作扇出 + 按 id 去重）。留白：~~SeriesStore 落库侧的 UI 展示开关~~（GX 批「分段合并」Toggle 已落）、~~自定义标签管理~~（CT 批已落）、~~相册 AllVideos~~（**ALB 批 2026-10-25 以「导入」形态落地**：上游是浏览全机视频（需读权限），本侧零权限姿态 + 沙箱模型不管理别人的文件 → 改用系统选择器临时授权导入，见交付批节）、~~缩略图~~（MS 批已落）、~~外部改名~~（本仓改名 RN 批已落；**系统文件管理器改的名**仍会脱钩，平台事实登记）。**P3 修复（2026-10-21）**：探测缓存/在途/负缓存提模块级 + 512 上限 + 陈旧剔除；未 settle 任务上限 4（stale 剔除不释放计数，真正挂起 open/fetch 的 fd/extractor 泄漏上界=4，登记）；满载时写 3s 负缓存并在 Gallery 提示“元数据暂不可用”；DB 重试入口 retryDb；switchTab 失败回退；sidecar stat 区分 13900002 与 IO 错误；open/stat 进超时任务；帧账 cameraFrames 明确为到达上界（非精确值） | ☑ 部分 |
| `MediaActions.kt`、`Share.kt` | 平台绑定 | 本批只落**本地**收藏/回收站/彻底删除（MediaRepo 动作，RDB 清账）；`photoAccessHelper` 相册入库、系统分享（shareData）、重命名、删除授权回路留白 | ☑ 部分 |
| `GalleryScreen.kt` | UI | `pages/Gallery.ets`：全部/收藏/回收站三页签（含数量）、卡片列表（文件名/时长/大小/日期/收藏星标）、空态/加载态/刷新、收藏切换、移入回收站、还原、彻底删除二次确认（AlertDialog）、元信息面板；卡片点击进入播放页。Index/GlPreview 均有「媒体库」入口。落地 2026-10-21。**G 批（2026-10-23）分段折叠**：列表改吃 `SeriesGroup.galleryEntries`——同一次录制的多段折成一张卡（角标「N 段」、副行「N 段 · 总时长」），代表段 = 首段；收藏/移入回收站/还原/彻底删除经 `expandSeriesTargets` 扇出到整组（提示补「（含 N 段）」，彻底删除的确认框也写明段数）。**MS 批（2026-10-24）**：缩略图（`media/ThumbLoader.ets`：AVImageGenerator 中点帧 CLOSEST、并发限 2、ThumbLru 缓存 32 驱逐不回收交 GC；`GalleryThumb` 组件占位回退 + @Watch path 重载）、分享（`media/WotaShare.ets`：systemShare 面板 SINGLE/BATCH、UTD 经 MimeMap、沙箱路径 fileUri.getUriFromPath；**FileProvider/FLAG_GRANT_READ 不需要**——面板自带目标应用授权）、存入相册（`media/AlbumSaver.ets`：`showAssetsCreationDialog` 系统确认框，**无权限**；title 去扩展名防双后缀、fileNameExtension+photoType 按 d.ts 实名）。三动作全走 `expandSeriesTargets` 组级扇出；**零新增权限**（红线用例钉住）。**GX 批（2026-10-24）**：分段展开（组卡**长按**开「分段清单」面板——只钉 seriesId 按当前 clips 重解析、点按任一段直接开播放页，上游 SeriesSheet 对应交互）+ 折叠回退开关（「分段合并」Toggle，会话态，关 = 上游 `collapse=false` 的逐字扁平基线）。**重命名（RN 批 2026-10-25 改判：已迁）**——历史登记是「不迁」，理由是媒体 ID = 相对路径 32 位 FNV-1a（`MediaIds.ofRelativePath`）改名即换 id、标签/回收站/系列成员失联。RN 批不再等 id 架构升级，而是把改名做成**成套迁移**：磁盘文件 → `.drops.json` sidecar（`ArcDropLog.renameFor`）→ 三表单事务换键（`WotaDb.renameMedia`：media_tag/trash_item/series_part）→ 同步键随搬（`TrackSync.renameFor`），任一硬步失败即把文件改回去（不留半迁移态）。**取舍登记**：CompareHistory 按数值 ID 记账，改名后旧条目失效——沿用其既有「缺失标记」语义（用户重选右片即可），不额外整表重写。守卫从「不许有改名入口」改为「入口在册 && 迁移面在册」。留白：~~全屏手势锁定~~（幽灵留白裁决 HM7-1：上游全仓无此功能，`git grep -i "手势锁定"` 零命中，见交付批 HM7-1 节）、时间线/网格/全屏滑动（G3a/G3b 批 2026-10-24 全落，见交付批节）、~~自定义标签页~~（CT 批已落）、上游 `StorageAccess` 不移植（见该行）；外部改名（系统文件管理器改）仍会脱钩——登记平台事实 | ☑ 部分 |

## player — 播放器 / 对比 / 剪辑

| 上游 | 类型 | 鸿蒙落点 | 状态 |
| --- | --- | --- | --- |
| `PlayerEngine.kt` | 平台绑定 | `player/PlayerEngine.ets` + `pages/Player.ets` 最小版：AVPlayer + XComponent(SURFACE) + fdSrc、prepare/play/pause/seek、逐帧（SEEK_CLOSEST + FrameStep 栅格/pending 锚点）、A/B 会话内循环（position 轮询回绕 + abWrapInFlight）、倍速（平台档 0.125/0.25/0.5/0.75/1.0，上游 0.1/0.3 偏离登记）、120ms 位置轮询、释放先 off 再 release；T19 增加 setVolume（0..1 夹紧），两实例各持自身 AVPlayer/fd/tick，无静态共享（Compare 页双路验证）；T20 音量 P3：Number.isFinite 防护（NaN/Infinity → 0）、删除无调用的 setMuted。落地 2026-10-21，对齐上游 342dd99。平台偏离：AVPlayer 无帧率读数（.drops.json dstFps→30→页面 24/25/30 手动）、无 SeekParameters（SEEK_CLOSEST，真机若落同步帧退 SEEK_PREV_SYNC 登记）。留白：播放器内双轨/选轨、自动音频同步、AB 持久化、~~旋转观看~~（RV 批 2026-10-24 已落，见交付批节）、缩略图、音轨菜单（本行为其 2026-10-21 最小版历史记录；后续批已陆续收口——选轨/音轨菜单 T25、缩略图 MS、AB 持久化 ABP，最新状态以 Player 行后段为准） | ☑ 部分 |
| `AudioSync.kt`、`CompareAudio.kt`（对齐数学） | 纯逻辑 | **T24 已落（2026-10-22，对齐上游 342dd99）**：`player/AudioSync.ets` —— 常量（TARGET_SR=8000/MAX_SECONDS=30/ENV_WIN_MS=10/SEARCH_MS=10000/REFINE_MS=50/PEAK_GUARD_MS=150/MIN_CONFIDENCE=1.5）+ resample（线性插值、按目标帧数×maxSeconds 封顶）+ envelope（10ms 窗 RMS 去均值归一）+ coarseAlign（±10s 前缀能量归一化互相关）+ 迭代 radix-2 fft/inverseFft + lowPass1k/meanRemove/nextPowerOfTwo/score + refine（±50ms、1ms 步进、次峰排除 ±150ms、置信度主峰/次峰比）；平台解码（MediaExtractor/MediaCodec→8kHz PCM、按容器轨序取第 N 条音轨）留待内录/自动对齐批。`player/CompareAudio.ets` —— Side/AudioTrackKind/audioGroupIndexOf（环境=组0/内录=组1 唯一映射）、SplitAudioPlan+splitAudioPlanOf+splitAudioEffectOf（分屏单引擎音量恒 1，不误静音）、CompareAudioPlan+audioPlanOf（单音轨 CAP 回落 ENV 清 override）+applyOrderOf（静音另一侧→设/清 override→选中侧音量 1）+selectionEffectOf、EngineCommand（kind 判别 + 静态工厂）/OverrideTarget/SelectionEffect。**T25 已接线**：Compare 页 applyAudioPlan 按固定命令序真实下发（setVolume/selectAudioGroup），双音轨门控用 ClipExporter.probe 的 audioCount≥2，静音保留本侧三态扩展；AudioSync 平台解码（MediaExtractor/MediaCodec→8kHz PCM）留 T26 | ☑ 部分（T25 音频接线；自动对齐解码留 T26） |
| `FrameStep.kt`、`TapArbiter.kt`、`CompareTimeline.kt`、`ComparePractice.kt`、`CompareHistory.kt`、`ClipExport.kt`（ClipRule） | 纯逻辑为主 | `player/FrameStep.ets`（SNAP_TOL_MS=1.5、floor+1/ceil−1、duration 钳制）、`player/PlayerModel.ets`（AbRange valid、倍速档/最近档、clampPan 半域）、`player/TapArbiter.ets`（280ms 双击窗口）逐行直译；T19 新增 `player/CompareTimeline.ets`：TimelineGeometry（tMin=min(0,−offset)/tMax=max(leftDur,rightDur−offset)/leftCovers/rightCovers/leftTarget/rightTarget park 规则）+ normalizeOffset（[−rightDur,leftDur] 夹紧）+ rightTargetFor（leftPos+offset 夹到 0..rightDur）+ timelineSpan/timelineEnd，仅手动偏移子集；T22 订正：tMin/normalizeOffset/rightTargetFor 的 -0 归一 +0（纯逻辑测试发现，对齐上游 Long 语义，文件头登记）。T20 新增 `player/ClipRule.ets`：MIN_KEEP_MS=300、ClipRange、normalize（0..duration 夹紧，恰等 300ms 通过）、keepSample（relUs>=0）、clipNameOf（edit_<stem>_<yyyyMMdd_HHmmss>.mp4）、keyFrameIndexOf（四舍五入）、remapDrops（k>=shift 平移到 k−shift，segments 清空登记）、TrackChoice(-1/0/1)+audioOrdinalOf。**T22 纯逻辑单测落地（见 §纯逻辑测试）**：compare-timeline.test.ets（9 用例，off=0 回归红线两条独立钉死）+ clip-rule.test.ets（6 用例）。**T24 全量纯逻辑（2026-10-22，对齐上游 342dd99，接线留 T25）**：① `player/CompareTimeline.ets` 补齐 CompareDomain/DecideInput/DecideCommands/domainOf/resolveT/decide——域判定（黑层回灌消歧/pre-roll 安全网/off<0 头段含边界交接/末态优先）、主钟随域（双素材与左独播跟左钟、右独播跟右钟、末取 Tmax）、双覆盖纠偏（250ms 硬 seek / 邻域 nudge，公式逐字）、独播域 dormant pause+黑层、交接不 seek、时间线末循环开回绕 Tmin（材料感知：park 侧带 pause+黑层，off=0 退化为双 resume）与循环关停末帧、AB 激活不抢回绕、拖动压 seek/nudge/wrap；几何函数零改动。② `player/ComparePractice.ets`：primeWith/takeLeftId/deliver + armed/pendingRightId 状态语义（导出类与模块单例 comparePractice）；**分屏练拍页面未移植，动线接线阻塞**。③ `player/CompareHistory.ets`：Entry(rightId,leftId,ts)、MAX_ENTRIES=12、entriesOf（缺键/坏 JSON 空表、坏行跳过、id≤0 跳过）、recordEntry（rightId 去重+插队首+截 12，返回新数组不改入参）、removeEntry、encodeEntries（键序 rightId/leftId/ts）；preferences 适配留 T25。单测：compare-timeline 25 条（+16）/compare-practice 6 条/compare-history 10 条（见 §纯逻辑测试）。**T25 接线（2026-10-22）**：Compare.ets onTick 交 decide（应用顺序 pause/resume→seek→nudge→黑层写回）、主钟读数 resolveT（负轴 pre-roll）、seekTimeline 统一 park/黑层、setPlaying 末态回 Tmin + wantPlaying 续播、domBlack 常驻层、CompareHistory preferences 适配（CompareHistoryStore，store=wota_player_ab）与历史面板。留白：~~AB 持久化~~（ABP 批 2026-10-24 已落，见交付批节）、练习页分屏（ComparePractice 纯逻辑已落但无消费者，T26） | ☑ 部分 |
| `ClipExport.kt`、`RemuxOrientation.kt` | 平台绑定（封装逻辑纯） | **T20 无损 remux 最小版（2026-10-21）**：`cpp/clip_exporter.h/.cpp`（OH_AVSource/OH_AVDemuxer 逐样本读 → OH_AVMuxer 原样写 MPEG_4，不解码/不重编码；worker 线程 + atomic 进度/取消，状态 `idle/running:<pct>/done:<base>:<v>:<a>[:audio=skipped]/failed:<code>/cancelled`；入点 Seek PREVIOUS_SYNC 取首个 SYNC 帧 basePts，视频/音频 PTS 同减 base，负 rel 丢弃，逐轨 monotonicPts 桥；出点按 PTS 截止，视频 32/音频 8 连续越界容忍 B 帧重排；源/视频轨旋转经 WotaNormalizeQuarter 透传，源格式优先；音轨可选 -1/0/1，预扫区间内零可写样本不建轨，AddTrack 失败退纯视频；先视频整轨后音频整轨；muxer Start 后取消 best-effort Stop）+ NAPI `startClipExport/getClipExportStatus/cancelClipExport/probeClipTracks` + `player/ClipExporter.ets`（fd 生命周期、200ms 轮询、10min 超时、失败/取消删除半成品、done 后按 basePts 关键帧序号 remapDrops 写输出 sidecar、同名追加 _N）+ Player 页「导出 A/B」面板（A/B≥300ms 才显示、暂停、音轨选择、进度/取消、成功路径/大小/媒体库跳转）。错误码 source/track-format/no-video/no-sync/muxer/add-track/rotation/muxer-start/read/sample-too-large/write/empty-range。**T21 修复 T20 复审 P3×4（2026-10-21）**：① done 日志打实际生效 rotation（与 `OH_AVMuxer_SetRotation` 同读归一后的 `appliedRotation`，源格式缺旋转键、视频轨带键时不再打错 sourceRotation）；② 音频拷贝阶段 NO_MEMORY 不判死——跳过剩余音频样本继续视频、done 状态追加 `audio=skipped`（首枚即超限=已 AddTrack 零样本 → WARN + 既有 audioSamples==0/Stop 失败兜底；视频轨 NO_MEMORY 维持 sample-too-large 判死，口径登记 clip_exporter.h）；③ ArkTS uniqueOutputPath 的 accessSync 异常按「被占用」继续 _N 循环、1..99 穷尽回退 `${stem}_${Date.now()}.mp4`（绝不返回 access 异常原候选名，防 TRUNC 覆盖旧成片）；④ 超时 fd 延迟关闭——CANCEL_GRACE_MS(5s) 耗尽进 linger 轮询（NATIVE_LINGER_MS=30s 上限）等 native 离开 running:，linger 内退出照常 close+discard、耗尽挂模块级 pendingFds（exportClip 入口/轮询 reap，Diag.w 登记泄漏），不关正被阻塞调用写的 fd。已知差异：逐轨顺序写入=非交织（真机播放器兼容性待验）、音频只选一条、无 MediaStore pending/commit（导出中直接写目标 mp4，极端并发刷新可能看到半成品）、无分享、clipName 追加时间戳（OHOS 私有目录防覆盖）、remapDrops 清空 segments（剪辑单文件不再适用源段账）。RemuxOrientation 只做旋转透传，不单独移植平台读源。留白：交织写入、双音轨、自动音频同步、~~剪辑进度持久化~~（幽灵留白裁决 ABP：上游播放域无进度持久化，见 Player 行 ABP 裁决）/后台保活；T18/T19 其余 P3 分别登记到健康/对比收口批（本批仅 PlayerEngine 音量防护） | ☑ 部分 |
| `PlayerScreen.kt`、`CompareScreen.kt` | UI | `pages/Player.ets` 最小版：播放画面/进度条/播放暂停/逐帧/A-B/倍速/帧率/镜像、单击显隐、双击启停、双指缩放平移（clampPan）；Gallery 卡片进入、返回刷新，顶部「对比」带 {leftId,leftPath} 进 Compare。T19 `pages/Compare.ets` 对比播放最小版：双普通 XComponent(SURFACE)+双 PlayerEngine、右片 MediaRepo 现选（排除左片）、共享播放/暂停/seek/倍速、手动偏移 ±0.5s/归零（park 门控：无覆盖侧停首/末帧，进入覆盖区由 tick 起播）、声音侧左/右/静音（默认左）、120ms tick 漂移校正（阈值 250ms/冷却 500ms）、两侧独立 attach/retry 与 surface 重建换代、页面隐藏暂停/销毁双 release。T20 在 A/B 合法（≥300ms）时增加「导出 A/B」入口与导出面板（音轨选择/进度/取消/成功路径与大小/媒体库跳转，导出期间拦截返回）。**T22 对比收口（2026-10-22）四项关闭 + 平台限制登记**：① 时间线双轴消费——进度 Slider min/max=TimelineGeometry.tMin()/tMax()（off>0 负轴 pre-roll 可拖）、拖动值 t 即时间线坐标、seekBoth 走 leftTarget/rightTarget park 规则、左端标签 tMin/右端 timelineEnd；off=0 在旧单左轴域逐像素等价（回归红线）；timelineSpan 无 UI 消费留白；进度读数仍随左钟（pre-roll 段显 0，最小版不派生真 T，登记）；② 右片 surface 重建/重试恢复播放位置（resumeRightPosMs 快照 rightTargetFor 或引擎实位，tryAttachRight 成功路径 seekTo 恢复；换选右片不恢复；左片既有恢复路径零改动）；③ attach/retry/surface 隐藏期门控——onSurfaceLoaded 重建分支在 !pageVisible 只置 deferred 标志（left/right 各一）由 onPageShow 消费执行、retryLeft/retryRight 入口拒 pageVisible=false、rebuild then 链 attach 前再查 pageVisible；④ 右片列表 id+path 双排除左片（leftClip 未就绪用路由参数原始 path=paramsLeftPath 兜底，防 FNV-1a ID 碰撞）。**漂移"速度微调"以平台限制登记关闭**：AVPlayer setSpeed 仅枚举档（player/PlayerEngine.ets speedToEnum 0.125/0.25/0.5/0.75/1.0 为证，无任意 ratio 接口），上游 AudioSync 的 setPlaybackParams 微调无 OHOS 等价物，邻域纠偏维持 250ms 硬 seek/500ms 冷却。**T23 P3 关销（2026-10-22）**：时间线左端标签负轴改用 `media/MediaFormat.formatSignedDuration`（off>0 的 pre-roll 显示 `-m:ss`；亚秒负值下限 `-00:01`，避免 500ms 偏移步进时与 0 混淆），进度读数仍随左钟口径（不派生真 T，登记保留）；formatDuration 负值归零语义不变，新增 media-format 3 用例钉住正/负/0（含 `2:00:50` 手写字面量）。**T25 对比页接线（2026-10-22）**：onTick 双片就绪且无错误时交 decide（域判定/主钟/交接/纠偏/末段回绕/拖动让路）；应用顺序 pause/resume→seek→nudge→黑层写回；主钟读数 resolveT（pre-roll 为负 T）；seekTimeline 统一 leftTarget/rightTarget park + 无覆盖侧暂停 + atEnd 撤黑 + 会话黑层写回；setPlaying 末态先回 Tmin、wantPlaying 续播意图（换右片/右片晚挂 attach 后补一次）；左右恢复位置快照改素材位；domBlack 常驻黑层（BLACK_LAYER_ENABLED 总开关）；底栏「循环:开/关」；音频计划真实下发（静音另一侧→设/清 override→选中侧音量 1，双音轨 chip，静音三态保留）；历史面板（回填右槽/缺失标记/删除）+ 选右片记账。**T25 审查修复（2026-10-22）**：① surface 重建/重试先记 wantPlaying（旧引擎 release 后 playing 归零），attach 成功走 seekAndPlayIfPaused（seekDone 落地再 play），maybeResumeWanted 不再以「任一侧在播」早退；② seekTimeline 写黑层后按 wantPlaying 统一 resumeDomainAt——拖回双覆盖区 dormant 侧随松手复活（不再无黑层冻结）；③ 换右片/左右 attach 成功后均按当前音频侧重探 audioSideDual，静音态也落轨选择（静默生效，解除静音不复位）；④ 右片未选/报错时 positionMs/sliderValue 跟左钟（重建中保留 T，预录态不回落）；⑤ 历史写入串行队列、number/string 统一 safe integer。进页起播仍为手点播放（上游 attach 起播，登记偏离）；nudge 为平台空操作（250ms 硬 seek 保留）。留白：AudioSync 平台解码与自动对齐入口、练习动线（分屏录制页，T26）、~~全屏手势锁定/字幕~~（幽灵留白裁决 HM7-1：上游全仓无此功能——`git grep -i "手势锁定"` 零命中；`git grep -i subtitle` 仅命中 CameraDialogs/GalleryScreen 的弹层副标题参数 `subtitle: String?`，非视频字幕；PlayerScreen 手势清单只有 点按显隐/TapArbiter 双击/双指缩放平移）、~~AB 持久化~~（ABP 批 2026-10-24 已落，见交付批节）、~~剪辑进度持久化~~（幽灵留白裁决 ABP：上游播放域持久化全量枚举只有三处——PlayerEngine.kt:401-403 的 AB 三键 `ab_a/ab_b/ab_loop:<uri>`、CompareHistory 单键、TrackSync 五键；`putLong/putBoolean/putString/putFloat/putInt` 在 player/ 全目录仅命中上述三类，无剪辑/播放进度持久化——播放位置刻意会话态，surface 重建走内存 restoreSession）。**D 批（2026-10-23）同步来源 UI**：`player/SyncPrefsStore.ets` 读 TrackSync 五键快照，导出面板显示「已同步 +Xms（自动校准/手动）」/「未同步：导出按 0 平移」+ 诚实边界行「原片分享不重写容器；只有本页导出/光弧修复会应用同步残差」；导出与光弧修复都在起跑时取偏移（`audioShiftUs` / `capOffsetMs`）。**未迁**：手动「同步音轨」按钮与 `PostStopSync.running` 重读（均依赖偏移计算平台面，T27-4） | ☑ 部分 |
| `player/AudioAlign.kt`（e950328 新拆出的 DSP 单一真源） | 纯逻辑 | **已落（2026-10-23，对齐 e950328 纯逻辑层）**：`core/AudioAlign.ets`——resample/envelope/coarseAlign/fft/inverseFft/refine/lowPass1k/meanRemove 自 `player/AudioSync.ets` 迁出为单一真源（按采样率参数化，非 8k 写死），新增 `estimateOffsetMs(envPcm, capPcm, sr, searchMs, refineMs, minConfidence)`：**三红线**=时长不足 200ms / 任一路静音 / 置信度低于门槛 → 一律返回 null（不硬凑偏移）。`player/AudioSync.ets` 改为 59 行薄壳同名 API 委托（常量原名转发，既有 10 例经委托零回归）；decodeMono 平台解码仍留 T27-4 | ☑ 纯逻辑层 |
| `player/TrackSync.kt`（e950328 扩容） | 纯逻辑 + 平台绑定 | **已落纯逻辑层（2026-10-23）**：`player/TrackSync.ets` 62→257 行——`Source`(AUTO/MANUAL)、`AutoDecision` 四态（ManualProtected/LowConfidence/OutOfRange/Write，静态工厂 + sameAs）、`SyncSnapshot`、`autoDecision`/`sourceOfStored`、prefs 五键编解码（sync_done/offset/source/conf/at，键名与上游逐字；`SyncPrefs` 最小 get/put 注入接口，不把 `@ohos.data.preferences` 拖进纯模块）、`writeAutoPrefs`/`writeSyncPrefs`（写前复核 MANUAL，对应上游锁内复核的 TOCTOU 语义）。平台面 `countAudioTracks`（可先用 ClipExporter probe 替代）与 autoRun 解码留 T27-4 | ☑ 部分 |
| `player/PostStopSync.kt` | 纯逻辑（调度属平台） | **已落决策面（2026-10-23）**：`player/PostStopSyncPlan.ets`——`requestedKeys`（段筛选）/`actualDualKeys`（双轨谓词注入，异常按非双轨）/`planFor`（MAX_EXACT_SEGMENTS=4：≤4 段逐段，超 4 段测首段+复制并记「近似」），异常隔离。taskpool 调度与 Mutex 串行化留 T27（文件尾接口注释） | ☑ 部分 |
| `player/ClipExport.kt` 的 `ClipRule.audioShiftUs` + 导出/修复两条链的平移接线 | 纯逻辑 + 平台绑定 | **已落（2026-10-23，对齐 e950328 完整）**：① 纯逻辑 `player/ClipRule.ets`——`audioShiftUs(trackChoice, offsetMs)`（CAP=`0 - offsetMs*1000` 避免 JS `-0`，ENV/NO_AUDIO=0）与 `remuxAudioSampleWritable(relUs, shiftUs, endRelUs)`（平移后负值丢弃、出点吃平移后时刻）；② `player/ClipExporter.ets` 的 `exportClip(..., audioShiftUs, onProgress)` 把平移传给 native；`cpp/clip_exporter.cpp` 的 Start/Job 增 `audioShiftUs`，**预扫（`attr.pts - basePtsUs + job.audioShiftUs`）与拷贝循环（`audioRel`）同口径**，视频轨仍只减公共基准；③ 光弧修复 `record/ArcRepairRunner.ets` 的 `run(..., capOffsetMs, ...)` → native `arc_repair.cpp` 只对「确有第二轨」的 lane 1 平移，并新增**平移后可写性预检**（按视频首样本 PTS 判，防零样本轨崩 muxer）；④ `player/SyncPrefsStore.ets`（`@ohos.data.preferences` 适配 TrackSync 五键，读不到=未同步）。接线守卫见 `test/pure/clip-export-shift.test.ets`（含 C++ 源码结构检查）。**留白**：同步偏移的产生（手动「同步音轨」与停止后自动校准）仍挂 T27-4——本侧只消费已落盘的结果 | ☑ 完整（偏移来源留 T27-4） |

## record — 录制链路（移植重心）

| 上游 | 类型 | 鸿蒙落点 | 状态 |
| --- | --- | --- | --- |
| `MrRecorder.kt`（MediaRecorder 引擎） | 平台绑定 | `@ohos.multimedia.media` AVRecorder + CameraKit VideoSession：`camera/KitRecorder.ets` 最小录制（VIDEO_SOURCE_TYPE_SURFACE_YUV 面输入、NORMAL_VIDEO 会话、**videoProfiles + frameRateRange 覆盖选档**（VideoSizeTable 留待后续）、BitratePolicy 复用、SeriesIds 接线、MIN_KEEP_MS 判废）。落地 2026-10-21，对齐上游 342dd99。输出契约：`AVRecorderConfig.url = fd://<fd>`（fileIo.openSync 建文件取 fd，fd 保留到 AVRecorder reset/release 后关闭）；起录失败统一回收资源并删除已创建/半写文件；判废顺序=先 MIN_KEEP_MS（短段含 engineErr 一律 TOO_SHORT），唯一例外 av.stop 失败保留文件 triage（STOP_FAILED）；start/stop 有 starting/stopping 重入保护。留白：3.5GiB 分段轮转/起录健康三格/AutoStop 巡检（GPU 已接，AVRecorder 留白）/FrameLedger 接线/HEVC 重试/.drops.json/参数下发（VideoSession 无 ManualExposure/ManualIso/OIS）/orientationHint 与 AVRecorderConfig.rotation 未接线（显示旋转源随 HUD 批）/sampleRate 恒 48k 未探测/mirrored/useGpu 恒 false。装机验证项（不阻塞）：录 5 秒文件可播放有声音、停录后预览恢复、拒绝 MIC 录无声视频、<1 秒判废删除、fd:// 契约真机验证 | ☑ 部分 |
| `CodecRecorder.kt`（MediaCodec+MediaMuxer 引擎） | 平台绑定 | AVCodec + AVMuxer（NDK 侧），与 GL 链路衔接。**最小切片已落（2026-10-21，对齐上游 342dd99）**：`cpp/codec_recorder.h/.cpp`（OH_VideoEncoder CreateByMime→RegisterCallback→Configure→GetSurface（Prepare 前）→Prepare→muxer Create/AddTrack/Start→encoder Start；surface 输入、输出回调直写 OH_AVMuxer，PTS 一律 wall-clock 相对段基准（NowUs−startUs，µs）并经 OH_AVBuffer_SetBufferAttr 回写、lastPts 单调守卫（前提：无 B 帧，登记）；容器旋转 OH_AVMuxer_SetRotation(normalizeQuarter(sensor−display))（0/90/180/270 四舍五入，非 90 倍数 sensor 按上游归一；须在 muxer Start 前；编码 UV 仅送缓冲原样帧）；I 帧间隔 1000ms；FRAME_RATE/BITRATE 按 SDK 类型（double/int64）写入并检查；EOS 等待 ≤3s；错误机器码 busy/encoder-create/register-callback/format-create/format-key/configure/get-surface/prepare/muxer/track-format/track/muxer-rotation/muxer-start/start/encoder-surface；运行期 muxer-write/pts-set/muxer-missing/engine-error/surface-lost/encoder-pass-failed；muxer 写失败/时间轴回写失败即停止送样（samples 仅成功自增），错误经 getGpuRecordError/stop 带回；native 不 close fd）+ `camera/GlRecorder.ets`（fd 归 ArkTS、VideoStore 命名/落盘与 MIN_FREE_MB 起录预检、停录 close+stat 摘要；napi-failed 先 best-effort stop 再 close）+ GlRecorder（起录弧档/dstFps/deadband 传递；停录顺序 flushArcPending→（阻塞 stop 前冻结帧账 end 快照）→stop→逐项 drainArcDrops/帧计数/实测源帧率→close+stat→FrameLedger 成文→写 `.drops.json` sidecar（segments.count=muxer 视频样本数（part.samples，上游 VideoSegment.count；与首段旁 sidecar 同口径）；frameStart 不可用时相机帧退原生 GL 回调上界））+ GlPreview「录 GPU/停录」与三态转换按钮（关/补弧24/抽帧24，固定 1920×1080@30 或 24、BitratePolicy.resolve；500ms 监视 isGpuRecording/getGpuRecordError 校正录制态）+ **AutoStopMonitor（2026-10-21）**：1s 相机设备态转移（启动时以当前设备态预置 watch；session error 直连兜底）、首个 1s tick 立即查电量 batterySOC≤3% 与可用空间 <MIN_FREE_MB（其后 30s 节拍），一次录制至多一发，命中提示“成片已保存”；手动停录/相机丢失兜底统一按 AutoStopRules.stopGraceDelayMs 做 500ms 宽限（stopPending 防重入，首个停录原因合并）。相机丢失后 lensInfo/deviceStatus 失效，需重新进入本页起镜后才允许再次录制。**3.5GiB 分段轮转已落（2026-10-21）**：native `getGpuWrittenBytes`（成功写入 attr.size 累计）+ ArkTS 500ms 轮询 maybeRotate；SegmentPolicy（MAX_FILE_BYTES=3_758_096_384、ROTATE_SAFETY=0.98）；轮转=停段（EOS）→新文件→重建编码器/EGL 面（段间 lostMs 记账），每段独立 MP4/时间轴、各自 displayName；逐段 FrameLedger（partIndex/rotateLostMs/rotateFramesUpper）与 `frameSessionSummary`；首段旁 `.drops.json` 的 segments 覆盖全部段（first 前缀和/part.samples/lostMs），drops 只保留最后一段（上游跨段失真登记）；8MB 测试阈值入口（离开页面恢复默认）；summary 的 measuredSrcFps 标注为最后一段口径；**T22（2026-10-22）**：SegmentPolicy 无自动化单测登记撤销（test/pure/segment-policy.test.ets 5 用例，见 §纯逻辑测试）、stop 摘要追加末段账 lastPath/lastBytes/lastSamples（健康收口丢段专用）、被丢弃零样本段在首段 sidecar segments 留 count=0 条目（无害，登记——首段 sidecar 不回写）。上批 P3×4：起录后 1s 冷却（迟到点击不覆盖摘要）、删除 lastStopHadReason 死字段、Player metaNotice 移出控制层、stopReason 起录清理。本批 P3×5：轮转错误先 finish 再追加摘要、onPageHide/离开页面复位 8MB 测试阈值、起录清 lastStopAtMs、napi-failed 摘要带 path 保留 triage、~~SegmentPolicy 无自动化单测登记~~（T22 撤销）。 T18 P3×8：bootstrap/权限 pageVisible 门控、重试相机按钮 disabled(startingCamera)、SettingsStore.save 返回 boolean + 保存串行化、变焦实际值/最近档、HUD_READOUTS 驱动顶栏、appendNotice 冷却/错误追加、相机丢失清镜头/画幅标签、预览/录制帧率分显。留白：音频轨、参数下发、~~PTS 网格化~~（**PTS-GRID 批 2026-10-25 纯逻辑已落**：`core/EncoderPts.ets` 五件直译上游 NtscGrid/GlRenderEngine——ntscGridStepNs 三档表 + integerStepNs + 私有 slotGridPtsNs 单一内核 + encoderSlotPtsNs + encoderAdaptivePtsNs（形态 B 逐帧接管）+ encoderSlotGap 观测 + measuredFpsCenti；**native 逐帧打戳接线待真机批**：现 `cpp/codec_recorder.cpp` 用墙钟戳+单调守卫，改网格后必须录一段看 `r_frame_rate`/帧长分布才能验收，不可静态验证故不落地）、成片名前缀、MediaStore、NV12 直通与 DIRECT 路、系列归组 UI。已知：容器旋转 hint 在起录时定格（录制中转屏不更新，单段口径，登记）。装机验证项（不阻塞）：GL 页录 5s→hdc file recv 取回 mp4 可播放且时长 5s 量级；ES2 回退下 MEND 不降级（unsized GL_RGBA）；时间戳单位 ns/µs/ms 归一与实测源帧率合理；竖屏成片方向正确（muxer SetRotation）；停录后再次起录正常；录制中返回页面→录制中止、无崩溃；`hdc hilog \| grep -E "WotaGL\|WotaRecord"` 见编码面建立/首帧/EOS 日志 | ☑ 部分 |
| `CaptureFgService.kt`（前台服务） | 平台绑定 | **RT 批已落（2026-10-24）**：`record/RecordTaskGate.ets`（纯逻辑闸：起/停只有状态迁移那一 True 才碰系统接口——健康重录段不重复申请、finishRecord 多终态汇流不重复释放）+ `record/RecordTask.ets`（平台：`backgroundTaskManager.startBackgroundRunning`（TASK_KEEPING）+ wantAgent 指回 EntryAbility + `stopBackgroundRunning`；**失败只记日志不阻塞录制**——9800005 校验失败/9800006 通知关闭/801 都只降级「切后台续录」）+ Camera 接线（起录成功 `RecordTask.start`、`finishRecord` 收口 `RecordTask.stop`）。**登记**：① OHOS 无前台 Service，对应形态 = 长时任务 + 系统代发通知（无自定义停止钮）；② BackgroundMode **无「视频录制」专属档**，视频-only 用 TASK_KEEPING（T27-3 内录落地后切 AUDIO_RECORDING——现在冒领会被 9800005 打回）；③ 权限 `KEEP_BACKGROUND_RUNNING`（normal，只声明不弹窗）。真机待验：通知开关关闭时的降级、切后台续录实测、TASK_KEEPING 模式核验 | ☑ 部分（纯视频档） |
| `PlaybackCaptureController.kt`（内录） | 平台绑定 | **T26 纯逻辑已落（2026-10-22）**：`record/CaptureStateMachine.ets`（Idle→Authorizing→Active/Failed→Revoked 全转移 + 状态/事件短名）、`record/CaptureSilence.ets`（SilencePromptGate 按场 60s 节流 + captureSilenceVerdict 2s/5s 双门槛 + fedMsOf）、`record/AudioProbe.ets`（frameBytes/chunkBytes/recordBufferBytes/dbOf + captureParamMismatch 档位体检）、`record/CaptureSources.ets`（OH_AudioCaptureSourceType 契约 MIC=1/ALL_PLAYBACK=2/APP_PLAYBACK=3 + 内录源优先级 [ALL_PLAYBACK, APP_PLAYBACK]）；**平台面（AVScreenCapture 会话 + 隐私授权 + 双源采集 + AAC 三轨合流）T27 真机批**，调研结论见 §T26 平台调研 | ☑ 部分（T26 纯逻辑，平台 T27） |
| `Recorder.kt`、`CaptureStateMachine.kt`、`RecordProfile.kt`、`VideoStore.kt` | 纯逻辑为主 | 直译（状态机与接口不动，引擎实现注入）。最小契约已落：`record/RecordResult.ets`（RecordError 机器码 + RecordResult 首段字段/summary，VideoSegment/health/parts 留待分段批）、`record/RecordProfile.ets`（纯字段子集 + CODEC_H264/CODEC_HEVC/MIN_KEEP_MS + effectiveBitrate，captureAudio/arcConvert/fpsNtsc 留白）、`record/VideoStore.ets`（VID_yyyyMMdd_HHmmss_###.mp4 命名/MIN_FREE_MB 余量/sizeOf/discard；MediaStore pending 与相册入库留白）。分段轮转每段各自 displayName/文件，段序号只在 parts/segments/日志；系列归组（MediaSeries/SeriesPart）留白。落地 2026-10-21，对齐上游 342dd99。**T26 追加**：`record/CaptureStateMachine.ets` 直译上游授权状态机（16 用例）；`record/CaptureSources.ets` 因 Android usage 白名单无 OHOS 等价物，改以源枚举优先列表承载（差异登记于文件头）；`record/AudioProbe.ets` / `record/CaptureSilence.ets` 落可测算式与判据（消耗方留 T27） | ☑ 部分 |
| `ArcKeepRule.kt`、`ArcDropLog.kt`、`BitratePolicy.kt`、`AutoStopRules.kt`、`FrameLedger.kt` | 纯逻辑 | **逐行直译**——这些是与上游共享语义的锚点（见 UPSTREAM_SYNC.md），格式/判据/阈值必须与上游逐字段一致。直译完成 2026-10-20，对齐上游 342dd99，落点 `entry/src/main/ets/record/` 同名 .ets；BitratePolicy 的 `resolve(RecordProfile)` 重载待 RecordProfile 移植后补。在线消费已接线（2026-10-21）：ArcKeepRule/ArcRepairFlow 的 C++ 等价物（`cpp/arc_flow.*`）跑在编码支路，ArcDropLog 由 GlRecorder 停录写 sidecar，FrameLedger 合成原生五格+相机 frameStart（分段时逐段成文）；ArcDropLog segments 已随分段写全段清单（drops 只保留最后一段，跨段失真登记）；AutoStopRules/CameraLostWatch 由 `record/AutoStopMonitor.ets` 消费（GPU 主路径：1s 相机/30s 电量存储、一次一发、停止宽限 500ms；AVRecorder 路径留白） | ☑ |
| `RecordHealth.kt` | 纯逻辑 | `record/RecordHealth.ets`：GPU 三格（trackAdded/muxStarted/firstVideoSample）+ 判定顺序（错误码→三格→视频短窗 1.2s→完整窗 3s→WAIT）、retryActionOf（attempts<maxAttempts）、MIN_OUTPUT_BYTES=64KiB、resolveOutputBytes/streamHealthVerdict（AVRecorder 路纯函数留白）。**GPU 主路径已接（2026-10-21）**：native CodecRecorder 暴露 trackAdded/muxStarted/samples（getGpuHealth），Camera 500ms 巡检、最多自动重录 2 次、仅重开录制段不重开相机。T18 P3×4（T19）：GlRecorder stop 摘要补 samples、空段仅 samples=0 且 bytes=0 丢弃、onPageHide/Show 续跑巡检、GIVE_UP 文案常量生成、native HealthSnapshot 单锁快照。**T22 健康收口（2026-10-22，对齐上游 342dd99）四项关闭**：① 三格真信号——native `formatChanged_`（OnStreamChangedCb 置位，Start/FailStart/Stop 复位）= 上游 MediaCodec format-changed 等价物，getGpuHealth 扩 4 字段 `track,mux,samples,fmt`，Camera checkHealth 容 4 字段（3 字段旧序防御），healthVerdict 的 videoTrackAdded 实参 = `fmt || samples>0`（samples 兜底防回调时序不触），muxer AddTrack 位降为 Diag 诊断字段（三格语义对照表见 Camera.ets 头注释）；② 轮转重启健康窗——上一段已健康停巡检后轮转新段重启巡检（幂等），**healthAttempts 会话级不清零（与上游"每次起录清零"口径差异登记：防轮转循环无限重录）**，隐藏页不重启只置 healthPausedByHide 由 resumeHealthAfterShow 消费；③ 健康重录三残差——restartSegmentAfterHealthFailure 重置 recordStartedAtMs（宽限期口径）、GlRecorder.stop 摘要追加末段账 `lastPath/lastBytes/lastSamples`、健康重录与 GIVE_UP 丢段读末段账（仅末段 samples=0 且 bytes=0 才删，**保留已完成前段**；被丢弃零样本段在首段 sidecar segments 留 count=0 条目，无害登记）；④ 纯逻辑单测落地（test/pure/record-health.test.ets，21 用例，判定顺序五条各被点名，见 §纯逻辑测试）。AVRecorder 健康检查/stream 活性路留白（Index 未接） | ☑ 部分 |
| `ArcRepairPlan.kt`、`ArcRepairRunner.kt`、`ArcRepairGl.kt`（离线修复） | 平台绑定（计划逻辑纯） | 计划直译；执行走 CPU（ArkTS/NDK）与 GPU（复用 GL 着色器）双路。**在线 ArcRepairFlow 已落（2026-10-21）**：`cpp/arc_flow.h/.cpp` 的 Hold/Fold/Emit/Merge 指令序列 + `cpp/arc_mend_pass.*` 执行。**T28 纯逻辑已落（2026-10-22）**：`record/ArcRepairPlan.ets`（有理数抽帧计划 dstFrameCount/isKept/progressOf/of，29.97 与 25.4 真值位次锚点；不移植旧 Int 外壳）、`record/ArcRepairFlow.ets`（Hold/Fold/Emit/Merge 流式状态机 + 计划路 onSourceFrame + 在线分类路，PTS=keptCount·1e6/dstFps，补上 cpp/arc_flow.h 所引的 ArkTS 锚点）、`record/ArcRepairMerge.ets`（CPU 4:2:0 2×2 块整像素选择，Uint8Array 无符号 Δ、块内最大 Δ、4Y+U+V 整块搬运不造色）、`record/ArcRepairVerdict.ets`（arcRepairOk 三格 + arcRepairKeep 的 stop 门槛）；单测 41 条（plan 15/flow 9/merge 12/verdict 5）。**T29 CPU 执行器核心已落（2026-10-22）**：`cpp/arc_repair.h/.cpp`（单例+worker 状态机、probe：宽高/旋转/容器 fps/时长/音轨数/帧距中位数（≤2M 样本）、同步模式解码 → 紧打包 I420（NV12/NV21/I420 + stride/sliceHeight，未知格式 ARC_CPU_YUV）、ArcRepairFlow 指令执行（cur/pending/acc 三面整像素合并）、同步模式编码（YUVI420 缓冲输入、显式 PTS=j/dstFps）→ 首枚输出后 AddTrack/SetRotation/Start → 逐样本写 muxer、取消/deadline/状态串）、NAPI 四口（probeArcRepairSource/startArcRepair/getArcRepairStatus/cancelArcRepair）、`record/ArcRepairRunner.ets`（fd 生命周期/200ms 轮询/取消宽限+linger/失败 discard/成功 stat/`contract()` 启动日志）、`record/ArcRepairSessionModel.ets`（24 用例：错误码/probe 与状态串解析/速率回退/NO_NEED/去重门限/帧数估算/结果归因/note）。**T30 音频直拷 + Player 面板已落（2026-10-22）**：运行期保留全部 `audio/` 轨（`audioTrackIndices/audioFormats/audioDstTracks` 并行表），首枚视频输出时在 SetRotation/Start 前逐轨 AddTrack（失败逐轨降级）；视频 EOS 排空后用同一 fd 新建 audio source/demuxer，逐轨 select→SeekToTime(0,PREVIOUS_SYNC)→逐样本读，PTS 按视频首帧归一（视频基准缺失退回本轨首样）、负值丢弃、`MonotonicPts` 逐轨兜底后写 muxer；done 串 `audioSamples` = 全轨成功样本合计；`record/ArcRepairSessionModel.ets` 增 `describeRepairError`/`audioLaneNote`（29 用例）、`ArcRepairRunner.run` 可复用面板 probe 结果；Player 页「修复光弧」面板（probe 拦截 NO_NEED/无视频/失败、目标 24/25、死区 0/12/32、进度/取消/成功跳媒体库、与导出 A/B 互斥、忙碌拦返回）。**GPU 路线（ArcRepairGl）与真机调优 T31** | ☑ 部分（T30 音频+面板 / GPU T31） |
| `PreMuxVideoBuffer.kt`（起录缓冲）、`SeriesIds.kt`、`PendingName.kt`、`StalePendingSweep.kt`、`RotateGuard.kt`、`RotateFailLog.kt`、`OrientationExport.kt`、`AudioFeeder.kt` | 纯逻辑为主 | 直译。`SeriesIds.ets`、`PendingName.ets` 直译完成 2026-10-20（对齐上游 342dd99，落点 `entry/src/main/ets/record/` 同名 .ets）；其余待后续 | ☑ 部分 |
| `record/AudioPreflightPolicy.kt`（e950328 新增） | 纯逻辑 | **已落（2026-10-23，对齐 e950328）**：`record/AudioPreflightPolicy.ets`——MIN/MAX_PRE_SHIFT_MS=20..600、MAX_AUTO_OFFSET_MS=1000、MIN_CONFIDENCE=2.0（比对比页手动 1.5 更严：无人值守自动写盘）、`capPreShiftUs`（低置信 → 0；`-offset<20ms` 即内录不早 → 0；>600 钳到 600）、`shouldPreflight(envOn,capOn,a2dp)`、`autoOffsetWithinRange`；上游 `a2dpFromOutputTypes` 的 Android 常量比对改为**注入布尔组合面**（activeA2dp ‖ listHasA2dp），平台比对移到 AudioRoute | ☑ 纯逻辑层 |
| `record/PreflightPcm.kt`（e950328 新增） | 纯逻辑 | **已落（2026-10-23）**：`record/PreflightPcm.ets`——`PreflightMonoPcm`：交织 PCM16→单声道 float、按两路共同 t0 落位（早于 t0 钳 0、不带负前导）、空洞补零、静音锁存与 peak | ☑ |
| `record/AudioRoute.kt`（e950328 新增） | 平台绑定 | **已落（2026-10-23，降级实现）**：`record/AudioRoute.ets`——`isA2dpLikelyOn()` 取 `audio.getAudioManager().getRoutingManager().getDevicesSync(OUTPUT_DEVICES_FLAG)`，出现 `DeviceType.BLUETOOTH_A2DP` 即 true，异常按 false。**降级登记**：OHOS 无 `isBluetoothA2dpOn`「活动路由」API（本机 SDK d.ts 已查证：仅 BLUETOOTH_A2DP=8 与 getDevices/getDevicesSync，无 getActiveOutputDevice），本判据等价上游的**兜底路径**（设备列表含 A2DP）——A2DP 已连但输出切回扬声器时会多跑一次预校准（无害：预校准失败路径恒 shift=0，不写错偏移）。真机待验见对应小节 | ☑ 降级 |
| `record/AudioPreflight.kt`（预校准采集）、`record/CodecRecorder.kt` 的 `AudioChannel.ptsShiftUs` 与 `preflightAudioSync` 挂点、`record/Recorder.kt` 的引擎契约 | 平台绑定 | **T27-1 平台探针已落（P27 批 2026-10-24，见交付批 P27 节）；T27-2/3/4 真机批（无设备不盲落）**——native 侧 AVScreenCapture 头文件已在 sysroot 核实在册（`native_avscreen_capture.h`，API 10+：Create/StartScreenRecording/SetMicrophoneEnabled 等），API 面不缺；缺的是隐私确认弹窗的实际形态与双源采集行为，只能真机取证（本仓纪律：不可验证的平台面不落地）。前置条件 = 一台 HarmonyOS 设备 + 隐私弹窗 UX 定版（预校准二次弹窗问题维持既有结论：须复用同一 capture 会话或砍预校准）：① 预校准需两条临时 AudioFeeder 双源同采 800ms，OHOS 内录原始 PCM 只有 AVScreenCapture NDK 一条路且**每次会话拉隐私确认**——预校准+正式录制=二次弹窗（体验不可接受，须 T27-3 复用同一 capture 会话，或砍预校准只留后校准）；② ptsShiftUs 需内录音轨，而 `cpp/codec_recorder.cpp` 现无音频轨（留白）；③ Recorder 契约随 T27-3 录制链改造一并落。策略与 PCM 落位算式已在纯逻辑层备好（上两行） | ☐ |

## ui — 界面（全部 ArkUI 重写）

| 上游 | 类型 | 鸿蒙落点 | 状态 |
| --- | --- | --- | --- |
| `CameraScreen.kt`、`MainActivity.kt`、`HudLayer/HudLayout/HudLayoutEditor/HudMetrics/HudPageGrid.kt`、`CameraPills.kt`、`SettingsScreen.kt`、`SettingsLayout.kt`、`ParamsPersist.kt`、`PreviewCoords.kt` | UI | **最小主 UI/HUD 已落（2026-10-21，对齐上游 342dd99）**：`pages/Camera.ets` 全屏 GL 预览 + 固定 HUD（顶栏两行读数/3×3 网格/底栏/录制计时）+ 参数胶囊（镜头/画幅/帧率/变焦/闪光/效果/光弧/网格点按循环；快门/ISO/EV/WB 点按开 PillPopup 就近浮层）+ GlRecorder/AutoStop/宽限/分段接线；**T21 高级参数批（2026-10-21）**：`ui/ParamPills.ets` 五件直译（对拍 CameraPills.kt:236-628——AeModeRow（AUTO/MANUAL/LOCK，manualExposurePossible 门控）、ShutterPill（梯=supported 档滑杆吸附、Float 往返经梯值反查原始 ns、仅 MANUAL 显示滑杆、fpsHi=pick.lo 与下发同源）、IsoPill（isoLadder 仅 MANUAL 可调）、EvPill（evLadder+evText、MANUAL 只读注记）、WbPill（预设网格+MANUAL 色温滑杆 kelvinLadder，**tint 副轴留白**））、WotaParams 参数总线接线（起镜成功 `applyAbility(engine.refinedAbility())`、fps 档位联动重算）；顶栏第二行 快门/ISO/EV/WB 读数（AE AUTO 时 ISO 显 AUTO、EV 仅非 MANUAL 显示、wbShort 预设缩写+色温、能力缺失 `-`；`HudSpec.HUD_ROW2`）——~~偏离登记：上游 READOUT 是可编排网格（hud_items 位掩码 + HudLayoutEditor），本批固定两行~~（**J3a 2026-10-24 已接**：快门/ISO/EV/WB/帧率迁入 `hud_items` 掩码驱动的读数条，固定行只留镜头/画幅/电量/空间/时长，见交付批 J3a 节；zone 化与拖拽编辑器仍留 J3b）；胶囊行追加 快门/ISO/EV/WB 四颗（点按开浮层，录制门控 `ParamPills.RECORDING_EDITABLE_PILLS` = {ISO,EV,WB}——**上游含 ZOOM/FOCUS，两颗胶囊本批未移植，差异登记**；快门录制中开窗只读 Note）；每次起录前与弹层改动 `engine.applyParams` 下发（RequestApplier 逐键报告，failed 项 Diag.w + appendNotice）；`pages/Settings.ets` 六项设置（画幅/帧率/码率/光弧/效果/网格）经 `ui/SettingsStore.ets`（preferences）持久化；`ui/HudSpec.ets` 固定布局常量；主入口改为 Camera（EntryAbility loadContent），Index/GlPreview 保留开发者测试入口；pageVisible 门控隐藏页 async 起镜链、启动失败/session error 提供「重试相机」、applyZoom 按实际生效值显示、Settings 保存串行化/类型白名单、HUD 读数顺序常量与顶栏一致、媒体库入口去重；效果槽同时作用于预览与编码（`gl_render DrawScene` 同一 GL 效果 program）。**T21 复审 P3×3 收口（T22，2026-10-22）**：① 快门读数 ※——syncParamLabels 在 MANUAL 且 ability 在册且 forcedShutterOutOfRange 时追加 `※`（上游 CameraScreen.kt:1195-1203 口径，与快门弹层/下发侧同源判据）；② applyParamsFromPills 的 skipped 摘要改 Diag.i（上游只进日志不占用户提示位），仅 failed 留 appendNotice；③ 相机丢失/session error 处置路径统一 closePill()（onCameraLost 回调首行收口弹层）。**T31 取景页真机取证与修复（2026-10-22，HUAWEI ALN-AL00 / HarmonyOS 7.0.0.109 / API 26 / 1260x2720）**：`ui/HudTokens.ets`（深色色板/字阶 11-12-13-17/触控 48/圆角间距/遮罩令牌）、`ui/WindowChrome.ets`（沉浸式 setWindowLayoutFullScreen + 透明系统栏白图标 + AvoidArea TYPE_SYSTEM|TYPE_CUTOUT 转 vp 安全区；普通页恢复黑底系统栏）、`ui/HudPill.ets`（@Component+@Prop 替 @Builder 值参数——真机「顶栏正确而胶囊恒为 镜头:—/画幅:—」的根因）、`ui/HudMoreSheet.ets`（复用 PillPopup 遮罩/量框：闪光/效果/光弧/网格）；Camera 顶栏两行各 5 项（label 11fp/value 13fp）+ 右侧 48vp 深色入口胶囊、顶部/底部渐变遮罩、Dock 2×4 主胶囊（镜头/画幅/帧率/变焦 + 快门/ISO/EV/WB，48vp、均分、无横向 Scroll）、更多+72vp 圆形快门（44vp 红点/录制中 24vp 方块/收尾禁用）、确定性 3×3 网格（2 横 2 竖 Divider 替代百分比 position——真机旧实现网格不可见）、删除「预览—fps」死读数（只显「Nfps」）；Settings 重做（List 顶对齐消除顶部 539px 空白、48vp 选项、保存提示定高不推动、开发者入口默认隐藏→版本号 7 连点显形）；Index/GlPreview 补页头+返回、Index 长文案改 13fp 可滚动日志区；EntryAbility 强制 COLOR_MODE_DARK；Gallery 恢复黑底系统栏。**T31 复审收尾（2026-10-22，P3×5+清理）**：底栏去固定高（相机断开/权限拒绝时状态行与重试按钮不裁 8 胶囊与 72vp 快门）、Dock 胶囊 12fp+左右 6vp、顶栏 fps 读数去「录」前缀（360vp 宽 4/5 等分不省略）、快门/ISO/EV/WB 录制中恢复可点开窗（只读由弹层 editable 承担，不再整颗禁用）、PillNote 11fp（HUD 弹层无 10fp）、WindowChrome 模块级 Promise 链串行 + EntryAbility.loadContent 前 prime（冷启动首帧无白条、设置/相机往返不乱序）；删死代码（GlCameraEngine.currentPreviewFps/previewFps 字段、Camera.fpsLabel、HudSpec.PILL_ROW_HEIGHT/gridFractions、HudTouch.MIN/HudShape.PANEL_RADIUS/HudColor.SUCCESS、SafeInsets.left/right 留 T32 横屏）。**真机复验未执行：已安装包签名指纹 48A3BDCE… 与本仓 `.local-signing` 自签证书 C8D36043… 不匹配，按安全红线不安装，交付源码/HAP 由用户以 DevEco 同证书重装后验证**（待验项见「真机待验」T31 节）。留白：~~HudLayout/HudLayoutEditor 自定义布局~~（J1/J2 2026-10-23 纯逻辑全落；**J3a/J3b 2026-10-24 已接 UI**——hud_items 读数条 + 顺序编辑器（`hud_layout` 真编码）；**自由拖拽（HDG 批 2026-10-25 落一半）**：同容器（READOUT 读数条）内的长按拖拽换位已落地；**跨容器自由布局**（上游五容器之间换区 + 格网落格）仍无渲染面——本侧取景 HUD 是 T31 真机取证后的固定结构（顶栏两行 + Dock 2×4 + 更多面板），要做等于重做取景 HUD，属产品决策且必须真机验收，见交付批 HDG 节）、~~ZOOM/FOCUS 胶囊与弹层~~（ZOOM 为 Dock 直循环无弹层；**FOCUS 已落（FC 批 2026-10-24）**：`FocusPillPopup`（AF 连续/手动 + MANUAL 屈光度滑杆 `diopterLadder`/snapKey DIOPTER，range 缺失=定焦头只切模式注记；录制中可调 `RECORDING_EDITABLE_PILLS`={iso,ev,wb,focus}）；Dock 第二行第 5 颗「对焦」；~~tap-to-focus 留 T32~~（**TFC 批 2026-10-24 已落**：HUD 列 onClick + FocusTapMath 归一化，模拟器实证归一化与边缘钳制））、~~tint 副轴~~（**FC 批决策：不迁；2026-10-25 补证定版**——对本机 SDK `@ohos.multimedia.camera.d.ts` 全文件搜 `colormatrix|colorcorrection|colorgain|rgbgain|tint|saturation|chroma` **零命中**；`WhiteBalance` 接口面只有「模式 + `getWhiteBalanceRange()` 区间」与 `setWhiteBalance(number)` （色温轴），**没有绿-品红副轴或 RGB 增益**。色温与 tint 正交，缺轴就是做不出效果——放一根拖了没反应的滑杆比没有更糟。守卫钉住 WbPillPopup 无 tint 控件）、PreviewCoords、~~SheetSpec~~（SPEC-T 裁决：系统映射，纯算式已落 SP 批）、~~letterbox 几何~~（**WLB 批 2026-10-25 已落**：`core/PreviewFit.ets` + native `uFit` 等比收缩，参考线/网格与点按对焦统一到同一画面矩形，见交付批节）、~~Widgets/CurvePopup~~（**CURVE 批 2026-10-25 已落**：RGB 曲线编辑弹窗整体移植（含上游 eb956f5 右缘缩放修订），见交付批 CURVE 节；Widgets 其余件（暗化挂件等）仍为留白）、~~UIOrientation 接线~~（UO 批已落）、~~text_scale_camera~~（ST 批已落）、~~compare_tick_ms~~（ST 批已落）。**高级参数持久化已落（本批 I，2026-10-23，对齐上游 `ui/ParamsPersist.kt`）**：`ui/ParamsPersist.ets`（纯逻辑：24 键白名单 `PARAMS_KEYS`，键名/顺序逐字等于上游 `WotaSettings.KEY_*`；`restore` 冷启恢复只写 value、range/enabled/exact 不动，机型域钳制仍交给 `applyAbility`；`ParamsPersistSession` 进页冻结影子、动作完成点 `flush` 只写与影子不同的字段、写失败不推进影子；枚举编解码与尺寸串 `"WxH"`；零 `@ohos.`/`@kit.` import）+ `ui/ParamsStore.ets`（平台件：`getPreferencesSync` 同步门面 + `putSync`/`flushSync`，写失败只 `Diag.w`，与 SettingsStore 共用 `wota_camera_settings`）；`pages/Camera.ets`：`bootstrap` 读完设置后 `restore`，flush 点 = 弹层提交 / 帧率 / 画幅 / 变焦 / 闪光循环 + `onPageHide` + `aboutToDisappear`。**排除清单照搬**：renderMode/sampleRate（设置页独占的自动降级）、audioEnabled（内录授权）、focusPoint（瞬时态）、curve/arc_convert_mode（专属直写链）。**键名迁移**：`fps→default_fps`、`bitrate→default_bitrate`、`sizeW/sizeH→default_size`（迁移读新键优先、回退旧键；save 只写新键，旧键保留不删），`level_enabled`/`default_ref_lines` 本来同名；`arcMode`/`grid` 留在 SettingsStore 原地（本侧已登记行为偏离，不折进 24 键/参考线掩码）。**与上游差异登记**：① 变焦/闪光循环走 engine 直写、不更新参数总线，flush 不会把 zoom/flash 入档（上游由 ZOOM/FOCUS 弹层写总线）；② 弹层滑杆每 tick 都走 `applyParamsFromPills`（上游按松手 `onValueChangeFinished`）；落盘侧已用 300ms 尾随节流 + 一次 commit 批写对齐（首请求立即、尾部补一次、关键点立即落盘） | ☑ 部分 |
| `design/Tokens.kt`、`PillPopup.kt`、`SheetSpec.kt`、`Widgets.kt` | UI（设计令牌近纯逻辑） | **PillPopup 最小实现已落（T21，2026-10-21）**：`ui/PillPopup.ets`——WotaPillPopup（半透明遮罩点击关 + 量框后定位面板 + 标题/收起钮；锚点取 `getUIContext().getComponentUtils().getRectangleById`（窗口 px），**取不到回退底贴屏居中**）、PillAnchor.place 纯函数直译（margin/right/bottom/above/below 钳制，MAX_HEIGHT_RATIO=0.62、gap 8vp/margin 6vp/面板 max 400vp 照搬）、PillChoices（网格单选 + disabled 灰显且点不动）、PillSlider（ladder 刻度滑杆：索引空间运行+nearestLadderIndex 吸附+梯不足 2 点禁用占位不退回连续）、PillNote。**T21 复审 P3（T22，2026-10-22）**：撤销 shown 封口——onMeasured 记录 lastW/lastH，onAreaChange 报告尺寸与上次放置尺寸不同时重跑 PillAnchor.place 更新位置态（内容增删导致面板尺寸变化时重排；同尺寸跳过 + place 纯函数 ⇒ 无重入环），shown 首测语义保留（首量显形）。未迁（登记）：LocalMotion 动效（淡入/0.94 长开）、TextScaleLayer、HDS 阴影/令牌色（静态深色近似）、WotaChip 视觉令牌、PillToggles/PillRowList（无消费方）。**T31**：新增 `ui/HudTokens.ets`（相机 HUD 自有令牌：深色色板/字阶 LABEL11-PILL12-VALUE13-TITLE17/触控 48/圆角/间距/遮罩渐变），是上游 HDS Tokens 的**静态子集等价**（非逐值移植，登记）。Tokens/SheetSpec/Widgets 其余仍待后续 | ☑ 部分 |
| `dialog/`（音源/蓝牙/相机/曲线弹层） | UI | **相机弹层数值段已落（T21）**：`ui/ParamFormat.ets` 直译 CameraDialogs.kt 数值段——shutterText（`1/` 与 `%.1fs` 分支）、evText（`%+.1fEV`）、wbShort/wbLabel/aeLabel（zh 档名取上游 strings_camera.xml）、shutterItems（supported 只绑设备判定 + 强制档 `※`；可选上限 shutterCeilingNs 按 fpsHi 现算与下发同源）。其余弹层（音源/蓝牙/曲线）待后续 | ☑ 部分 |
| `widget/`（CameraSurface、ParamSlider、SliderLadder 刻度滑杆、RefLineOverlay 参考线、AttitudeMeter、AudioChip） | 平台绑定 + UI | **SliderLadder 已直译（T21，2026-10-21）**：`ui/SliderLadder.ets` 纯函数族 nearestLadderIndex/isoLadder（25 档含 50/12800）/evLadder/kelvinLadder（500K 网格）/tintLadder/zoomLadder（含 0.5/1.5/2.5）/shutterLadder（过滤/去重/升序）/steppedLadder/alignUp（Math.floor 复刻 floorDiv 负数语义）；**T22（2026-10-22）补齐 diopterLadder（lo..hi 等分 10 段 11 点）与 percentLadder**（上游直译，消费方仍留待对焦/斑马胶囊批），九用例单测见 §纯逻辑测试。未移植（无消费方，登记）：deadbandLadder（光弧死区滑杆未进本批）。**RefLineOverlay 已落（F 批，2026-10-23）**：`core/RefLine.ets`（11 档位掩码 + inscribedBox + horizonEndpoints 纯函数）+ `ui/RefLineOverlay.ets`（Canvas 绘制 11 档，红线 2vp 最后画）；相机页用 `refLineMask() = default_ref_lines | (gridOn ? 三分法 : 0)` 消费，T31 的 Divider 版 3×3 网格删除。**AttitudeMeter 已落（E 批）**。CameraSurface/ParamSlider/AudioChip 待后续 | ☑ 部分 |
| `anim/`、`theme/`、`WindowKeepOn.kt`、`FrostSpace.kt`、`FrostProbe.kt`、`CompareProbe.kt`、`GalleryEntryScreen.kt`、`RecordResultNotice.kt` | UI / 薄绑定 | ArkUI 动效与 `window.setWindowKeepScreenOn`。**WindowKeepOn 最小接线（2026-10-21）**：Camera 页 getLastWindow + setWindowKeepScreenOn(true/false)，进页常亮、隐藏/销毁恢复 | ☑ 部分 |
| `widget/SliderSnap.kt`（0dee86d 新增）、`PillPopup.kt`/`CameraPills.kt`/`SettingsScreen.kt`/`SettingsLayout.kt` 的滑杆吸附段 | 纯逻辑 + UI | **已落（2026-10-23，对齐 0dee86d 完整）**：`ui/SliderSnap.ets`——`SliderKey` 七键（shutter/iso/kelvin/tint/zoom/diopter/peaking，**id 字符串为持久化契约、与上游逐字一致**）、`encode/decodeContinuousSliders`（坏串/未知 id=空集=全部吸附）、`effectiveLadder(ladder, continuous)` 桥（continuous→null→退连续区间）。`ui/PillPopup.ets` 的 PillSlider 增 `snapKey`/`step` 与连续模式：弹窗打开时经 `SettingsStore.sliderContinuousKeys` 读开关（坏读=吸附），关吸附走 lo..hi 连续区间按 step 量化（`lo + round((v-lo)/step)*step` 钳回），**开吸附路径与梯<2 点禁用占位行为不变**。`ui/ParamPills.ets` 4 处挂 snapKey（快门 1000ns/ISO 1/色温 50；EV 显式 null=整数域不可连续化）。`pages/Settings.ets` 新增「滑杆吸附」分组 + `ui/SettingsStore.ets` 键 `slider_continuous`——**只列有消费方的 3 键**（快门/ISO/色温），ZOOM/TINT/DIOPTER/PEAKING 四键键表存在但弹层留白（无消费方，接线守卫禁止无消费方键进 UI）。**偏离登记**：① 上游 snapKey 形参编译期必传，ArkTS struct 成员须默认值，故缺省 null=恒吸附=开关前现行为（漏挂由接线单测逐调用点计数守卫）；② PlayerScreen 并入门槛滑杆无落点（本侧死区为 0/12/32 档而非滑杆）；③ 设置页样式为 HudTokens 卡片+chip，非上游 Card/FlowRow | ☑ 完整（四键弹层留白） |

## update — 应用内更新

| 上游 | 类型 | 鸿蒙落点 | 状态 |
| --- | --- | --- | --- |
| `UpdateChecker.kt`、`UpdateDownloader.kt`、`UpdateInstaller.kt` | 平台绑定（策略纯） | **已落（2026-10-23，对齐上游 971874a + 376b3ef + 82943cf 的双平台资产命名）**：`update/UpdateChecker.ets`（纯逻辑：UpdateConfig 常量与镜像表、CompareVersions（上游 versionName 口径 + 本侧 versionCode 口径）、MirrorNode/MirrorChain、ReleaseJson 手写提取器、ReleaseNotes 整形、RedirectProbe、`probeLatestRelease` 三段编排 + 取消探针）；`update/UpdatePure.ets`（downloadCandidates/rankChannelsBySpeed/staleFilesOf/sha256Matches/isSafeRemoteUrl/isPublicHost（IPv4+IPv6 私网判据）/响应体上限）；`update/Sha256.ets`（纯 SHA-256，NIST 向量钉住）；`update/UpdateDownloader.ets`（`@ohos.net.http`：fetchJson 短读超时、并发测速选路、requestInStream 流式落盘（.part 按代唯一）、PK 魔数 + size + SHA-256 三重校验）；`update/UpdateInstaller.ets`（结构核对 + DocumentViewPicker 另存 + 浏览器兜底）；`pages/Settings.ets`「检查更新」行 + 说明/下载两个弹层（**纯手动**：联网只由行点击触发，不挂生命周期；下载取消按代际隔离）。**偏离登记**：① 段 3 跳过——http 自动跟随重定向且拿不到中间 `Location`，实跑只有段 1（官方 API）+ 段 2（镜像 API）；② 无法手写逐跳重定向 → 每渠道 URL 发请求前过 `isSafeRemoteUrl`（拒环回/私有），落盘后仍有三道校验；③ 包体上限 100MiB（`maxLimit` 平台硬上限，上游 2GiB）；④ **Release 说明里的 `SHA256:` 行是安卓 universal APK 的哈希**（build-apk.yml 回填），HAP 改读同 tag 的 `SHA256SUMS` 直连资产（build-hap.yml 产出；老 Release 无此文件则退回 PK + size），说明文本仍两段都读、只用于弹窗展示；⑤ OHOS 不开放三方拉起安装器 → 下载校验后经文件选择器另存，由用户在文件管理里交给系统安装器（无同包名/同签名的程序化核对，补偿是 SHA-256 与系统 profile 校验）；⑥ 版本比较用 **versionCode**：CI 注入公式 `1000000 + major*1e6 + minor*1e4 + patch*100 + r`，因为鸿蒙 versionName 不允许 `-rNN` 后缀 | ☑ 完整（真机待验） |

> **2026-10-23 收口（原「暂缓至 T30+」评审结论作废）**：`971874a` + `376b3ef` 已按上表落地为 `update/`
> 整块新模块；`82943cf` 的产物命名在本侧体现为 `-signed-Harmony.hap` / `-unsigned-Harmony.hap`
> 与 `SHA256SUMS` 消费。两项待设计问题当时的结论：① redirect 无等价物 → 放弃段 3，不手写跟随；
> ② hap 无系统安装器等价物 → 应用内只保证「下对、下全、没被换」，安装交给系统安装器。

## 交付批（DIAG：设置页诊断区块 UI 入口，2026-10-24）

上游 `SettingsScreen.BlockDiag`（总开关默认关 + 详细档层级置灰闸 + 清除 + 一键导出）。
§diag 行留白的「详细档 UI 入口（随设置页批）」收口——ST 批只落了持久化
（`diagEnabled/diagVerbose` + `persistDiagFlags`），UI 一直缺位。

- **`pages/Settings.ets`**：新增「诊断日志」区块（独立 ListItem）：① 记录日志 Toggle
  （默认关；运行期 `Diag.setEnabled` 立即生效，上游不重启应用同口径；关闸级联关
  详细档）；② 详细日志 Toggle（`enabled` 随总开关置灰——上游「没开着记录，记多细
  无从谈起」层级闸）；③ 清除日志/导出日志双钮（busy 互斥 + 状态行；消费 Diag
  既有 `clear()/export()` API，不新造文件面）。
- **落盘**：走 `SettingsStore.persistDiagFlags`（putSync+flushSync 专用同步口，
  与通用 persist() 的异步批写分离）；下次进页 `load()` 回读（ST 批已接）。
- **留白维持（UI 注记）**：导出落**应用沙箱目录**（`ExportResult.inPublicDir=false`
  既有口径）；公共 Documents + 分享面板（上游 exportForShare）维持留白并在 UI
  注记——公共目录写与系统分享面板为独立平台面，随分享批。
- **运行时走查待办**：开关生效链（改设置→日志文件增减）需装机/模拟器走查。
- **守卫**：`diag-settings.test.ets` +2（开关运行期生效与级联/置灰闸/专用落盘口/
  双动作互斥与留白 UI 注记）。共 +2（684 → 686）。
- **回归**：pure-test 64 files / 686 tests 全绿；`assembleHap`（release）
  BUILD SUCCESSFUL。

## 交付批（ABP2：对比页左引擎 A/B 段与持久化，2026-10-24）

上游 `CompareScreen` 底栏 AbRow（onA/onB/onLoop 落 `leftEngine`；pre-roll（T<0）
不可打点，复用 invalid 横幅通道）+ 引擎级 AB 持久化（attach 时 restoreAb）。
本侧 Compare 此前**无 AB 打点 UI**——Player 行 T25 历史留白的最后一角。

- **`pages/Compare.ets`**：底栏控制行加 `A:<秒|—>`/`B:<秒|—>`/`AB循环:开|关`/`清AB`
  四钮（读数 = 引擎 AB 会话镜像 `cmpAbA/B/Loop`；A/B 钮带 accessibilityText）；
  `cmpMarkA/cmpMarkB` 闸 `currentTimelineT() < 0`（pre-roll park 位打点无意义，
  上游同闸）；`cmpToggleAbLoop`/`cmpClearAb` 语义与 Player 页同名族。
- **持久化**：复用 ABP 批的 `AbPersistStore`（同 `wota_player_ab` 键域、键 = 左片
  路径——与播放页同片互通：播放页打的 A/B 对比页可见可续）；左挂成功即
  `cmpHydrateAb`（每次 attach 都灌：新引擎恒空 + `applyPersistedAb` 不覆盖闸，
  surface 重建后 AB 随 prefs 回来——上游 `restoreAb(uri)` 在每次 attach 调用的
  同口径，顺带补齐本侧重建丢 AB 的旧行为）。回写 = markA/markB 成功支/
  toggle 成功支/clearAb 四处即写（失败支不写）。
- **登记（标签适应）**：上游 AbRow 用资源字符串迷你胶囊（A/B/循环/清除），本侧
  按钮读数带秒值（`A:12s`）——信息增量（读屏与视觉都直接念出点位），动作语义逐字。
- **运行时走查待办**：打点→回绕→重建恢复链路需模拟器/真机走查（本轮静态接线）。
- **守卫**：`ab-persist.test.ets` +1（pre-roll 闸/左引擎落点/水化护栏/四处即写/
  左片路径回写）。共 +1（683 → 684）。
- **回归**：pure-test 63 files / 684 tests 全绿；`assembleHap`（release）
  BUILD SUCCESSFUL。

## 交付批（A11Y：无障碍全量审计，2026-10-24）

HM7-UI-02 步骤 3。审计口径：文本按钮（`Button('文案')`）读屏原生可读，不列；
缺口 = **图形化/无文本交互控件**。全量盘点（7 页 XComponent/Slider/Toggle/纯图形钮）：

- **取景页（Camera）**：72vp 图形快门（纯色环+红点，无文本）补 `accessibilityText`
  随录制态切换（开始录制/停止录制）；取景 XComponent 补 画面语义 + 手势描述
  （点按对焦，长按锁定对焦）。
- **播放页（Player）**：播放面补 手势描述（单击显隐/双击启停/双指缩放平移）；
  进度 Slider 补「播放进度」。
- **对比页（Compare）**：左右双画面补可区分语义（左片画面/右片画面 + 控制见底栏）；
  时间线 Slider 补「对比时间线进度」。
- **媒体库（Gallery）**：全屏 Swiper 面补 滑动语义；「分段合并」Toggle 旁挂标签补
  `accessibilityText`；时间线 78x58 缩略（纯图）补「打开播放页：<片名>」。
- **登记（不改）**：Index/GlPreview 为开发者入口页（默认隐藏），XComponent 不补
  语义——登记为豁免；G3 网格卡/时间线卡为文本容器（子文本可读），动作语义已在
  G3 批的 ⋯/＋/－/分段行补齐；HudEditor 开发者页同豁免。
- **运行时走查待办**：读屏实际焦点序与播报需模拟器/真机 walkie（本轮静态接线，
  不写运行时验收）。
- **守卫**：`ui-a11y.test.ets` +4（快门态切换与取景面描述/播放面与进度/双面与
  时间线/全屏面与 Toggle 与缩略）。共 +4（679 → 683）。
- **回归**：pure-test 63 files / 683 tests 全绿；`assembleHap`（release）
  BUILD SUCCESSFUL。

## 交付批（TOK：页面语义色令牌统一，2026-10-24）

HM7-UI-02 步骤 2（静态令牌审计）。全仓硬编码色清点（pages/ui/player/media/record/
update/bt）：偏离面收敛为 Gallery(30)/Player(47)/Compare(30)/Camera(2) 四页共 109 处
——Settings/Index/GlPreview 及全部非 pages 目录已在用 HudTokens（Settings T31 起即
全令牌化）。

- **`ui/HudTokens.ets`**：charter 扩为「深色 UI 设计令牌真源（两族色板）」，新增
  `PageColor` 页面语义色板 11 档（CARD/PANEL/CHIP/TEXT_SECONDARY/TEXT_DIM/
  TEXT_FAINT/TEXT_HINT/LIKED/OVERLAY/OVERLAY_SOFT/PANEL_COMPARE）——取值 =
  迁移前各页在用字面量逐字（只收敛不重设计）。
- **notation 归一**：`#FFCC80`(15)/`#FF8A80`(10) 与 `HudColor.WARN/DANGER` 值等
  （8 位补 FF alpha，视觉恒等），页面统一消费 HUD 令牌；`'#FFFFFF'` 归
  `HudColor.TEXT_PRIMARY`。
- **字阶偏离登记（不改码）**：页内显示档 9fp（角标）/15/16/18fp（节标题）/28fp
  （空态字形）为一次性展示尺寸，不入档；HUD 主档 11-17 不变，HUD 浮层仍禁 10fp
  （T31 取证口径）；`HudTokens` 头注登记。
- **守卫**：`ui-tokens.test.ets` +3（PageColor 11 档值逐字 / WARN·DANGER 值等 /
  四页零残留字面量 + 消费令牌 + import 真源）。共 +3（676 → 679）。
- **回归**：pure-test 62 files / 679 tests 全绿；`assembleHap`（release）
  BUILD SUCCESSFUL。

## 上游基线推进（2026-10-25，b462fad → 4998ce4）

`UPSTREAM_BASE` 推进到 4998ce4（= 含本轮全部上游提交的 main）。区间内上游提交逐条评审：

| 上游提交 | 内容 | 鸿蒙处置 |
|---|---|---|
| `5bc8b2d` | RGB 曲线拖动不跟手（手势期禁 morph 补间） | CURVE 批「morph 直切」简化天然覆盖（无 morph 即无不跟手），见 CURVE 批简化偏离② |
| `eb956f5` | 曲线弹窗右缘拖拽等比缩放 | CURVE 批整体移植（含该修订） |
| `f941799` | 高置信风险扫描修复七项 | RISK 批：3 项落地（IP 门槛/SHA 短路/clearFor）、4 项 N/A 登记 |
| `0e73ecb` / `af38019` | Android Baseline Profile 模块与产物 | Android-only（BP 工作流域），鸿蒙无对应面 |
| `376ffbb` / `b8f81c5` | 录制泵空闲退避 / StrictMode 收集 | Android 平台面（OHOS 无 StrictMode、泵模型不同），N/A |
| `322b080` / `518bc9c` 等 | 本仓自身提交（release 说明/NAPI 修复） | 已在本仓 |

锚点 diff（ClipExport.kt / PlayerEngine.kt）两项均属 f941799，已按 N/A 登记（无 pendingUri
救援模型 / 无阻塞帧率探测）。

## 交付批（FPS：帧率档位决议面统一出口 + 钳制基准目标档，2026-10-28）

上游 `74d4fb2`（档位可用性按普通∪高速并集判定）+ `313e8e6`（帧周期/快门钳制基准改为
目标档位）+ `2f30ad5` 的 decideFps 边界用例。追赶基线 4998ce4 → 9cc36c5 的第一批。

- **`core/CameraAbility.ets`**：补齐上游 2026-10-26 决议面全家——`pickHighSpeedFpsRange`
  （高速表专用选档：固定档优先 → 下界==目标里上界最小 → 覆盖目标里上界最小；**只用机器
  声明过的区间，绝不自造 [f,f]**——自造区间必被拒且无退路 ⇒ 取景器假死，上游 P1）、
  `fpsRangesUnion` / `needsHighSpeedFps`（模块级纯函数）+ `FpsDecision` + `decideFps`
  （一档的完整决议：普通表先来、覆盖不了才看高速表、两张都不覆盖 = 灰显不硬发），类方法
  `fpsRangesUnion(size)` / `needsHighSpeed(size, fps)` / `decideFps(size, fps)` 按尺寸分流。
- **`core/WotaParams.ets`** `applyAbility`：帧率落档改走 `a.decideFps(size, targetFps)`
  ——与 UI 可用性/※、会话选择、下发钳制**四处同源**，不再各写一份（「界面说可选、
  请求发的是非法值」那类分叉的根治）。两表都不覆盖维持 `inexactDisabled`（灰显）。
- **`camera/RequestApplier.ets`**：① `pickFps` 高速分支不再自造 `FpsPick(target,target,true)`，
  改走 `pickHighSpeedFpsRange`；② `Snapshot` 增 `fpsTarget`（用户档位，snapshotOf 传总线
  档位值）；③ `exposurePlan` 帧周期/快门钳制基准 = `max(fpsTarget, fps.lo)`——普通档
  [24,30] 选 24 时 lo==目标行为不变（r10 锚），可变高速档（vivo 50 → [30,120]，lo=30）
  必须回到目标 50，否则帧周期 33.3ms > 20ms 放出死档；两个来源全缺才退 HIGH_SPEED_FPS。
- **`ui/ParamPills.ets`** ShutterPill：`fpsHi = max(pick.lo, 目标档)`，与引擎同一条线
  （此前 UI 梯与引擎在可变档上互相矛盾）。
- **`camera/KitPreview.applyParams` + `camera/GlCameraEngine.applyParams`**：会话
  highSpeed 判据改 `ability.needsHighSpeed(size, fps)`（普通表先来），不按「档位数值 > 30」
  猜——厂商把 50/60 放普通表时不该被当成高速会话。
- **守卫**：`test/pure/fps-decision.test.ets` 13 例——vivo V2520A fixtures（普通流仅
  [24,24]/[30,30] + 高速 1080p 六区间）全表：并集可选性/精确性口径/needsHighSpeed 判定/
  决议与下发同支/两表同覆盖必选普通/固定档恒优先/边界 0·负·空表；帧基准 3 例（50→[30,120]
  帧周期 1/50、1/30 被钳回 1/50、[24,30] 选 24 的 r10 回归锚）+ 兜底链。
- **登记（不迁/留白）**：① **UI 高帧率档位（50/60/120/240）本侧无消费方**——取景引擎是
  PhotoSession（预览无 setFrameRate 面），录制帧率只落在编码器/muxer profile；没有
  `VideoOutput.setFrameRate` / `VideoSession.setFrameRateRange` 接线前开 UI 档也不能真值
  下发（能力协商而非能力假设），随平台面批；② `0f713a2`（退档兜底不再退化到高速表）N/A——
  本侧 `FrameRateFallback.ceilingFps` 无引擎调用方（H 批已登记无接线点），无可修点。
- **回归**：pure-test 74 files / 775 tests 全绿；`assembleHap`（release）BUILD SUCCESSFUL。

## 交付批（LENS：出厂默认镜头能力协商 + 起镜取证日志，2026-10-28）

上游 `0525fa2`（默认镜头改能力协商）+ `9b33272` 的取证日志部分。追赶基线第二批。

- **`core/LensSlot.ets`**：新增纯函数 `maxVideoPixels` + `pickCapabilityDefault`——用户从未
  显式选过镜头时，默认打开**后摄里最大视频档最大的那颗**，不认档位标签（marble 形态：
  HAL 把小底定焦头归进「广角」档、最大 1600x1200，旧口径首启即被钉在 720p）。前置一律
  排除、并列取先见者（对齐 Kotlin maxByOrNull）、全空/全零返回 undefined 走既有链路。
- **`camera/GlCameraEngine.start`**：默认链改 `pickCapabilityDefault(slots) ?? pickDefaultLens(slots)`
  （slotKey 空串同缺省处理）；起镜补「slot key / 档位 / 等效焦距 / 最大视频档 / afModes /
  minFocusD」一行取证日志（上游 9b33272 口径：ability.dump 的 label 只是档位名，同档位
  多颗/归档存疑时只有这行能定位现场）。
- **`ui/SettingsStore.ets`**：增 `lens_user_picked` 位 + `lens_key` 槽位键两键（对齐上游
  LensPickPreference 语义：显式切换过一次后永久尊重用户选择，冷启恢复该槽位键，设备不在
  了引擎再回能力默认；位丢失的最大代价只是退回能力默认）。
- **`pages/Camera.ets`**：冷启 startKey = `lensUserPicked ? settings.lensKey : 能力默认`；
  镜头胶囊显式切换即落「选过」位 + 槽位键并 persistSettings。
- **守卫**：`test/pure/lens-capability-default.test.ets` 6 例（marble 形态不认标签/正常机型
  行为不变/前置排除/并列先见/全空全零 undefined/maxVideoPixels）。
- **登记（不迁/偏离）**：① `9b33272` 的「胶囊在场禁用」主体 **本侧结构性不适用**——上游
  缺陷是「visibleEntries 摘除能力给不了的胶囊 + 其余条目钉在显式格子上 ⇒ 摘除即留洞」；
  本侧 Dock 是固定 2×4 网格（镜头/画幅/帧率/变焦/快门/ISO/EV/WB/对焦九颗恒在场），
  不存在按能力摘条目的路径，无洞可留。能力给不了的点击解释本侧已有：对焦定焦头弹层
  （FC 批「range 缺失=定焦头只切模式注记」）、闪光不支持时 toggleFlash 落「该镜头不支持
  闪光/常亮」状态行。② KitPreview/KitRecorder（Index/GlPreview 开发者探针页）默认链
  维持 pickDefaultLens——探针页要的是「归档标签首颗」的确定性，不随本裁决改。
- **回归**：pure-test 75 files / 781 tests 全绿；`assembleHap`（release）BUILD SUCCESSFUL。

## 交付批（SLOT：双 program 槽——监看叠加只上屏，2026-10-28）

上游 `46d8d91` + `5742c0f`（释放顺序关注点）+ `5035b09`（附A/C·附B/D 的 N/A 评定）。
追赶基线第三批。**这是本侧真实缺陷修复**：GL-4 起「效果槽同时作用于预览与编码」
（DrawEncoderPass/DrawEncoderMended 都吃上屏槽 program），斑马/峰值一直会进成片——
与 2026-10-27 产品裁决「监看叠加只上屏、绝不进成片」直接冲突（安卓侧 r02 已修，本侧补齐）。

- **`cpp/gl_render.cpp`**：
  - 新增**编码槽** `g_encSlotLocs`/`g_encSlotCurve`（对齐上游 24↔25 槽位口径：DISPLAY/MOVIE
    两槽；**槽位不进键**——编码槽只有 curve 一个自由度，effect 恒 NONE）；
  - `SyncEncodeSlot(wantCurve)`：由 `SyncEffect` 的同一份曲线纹理门控结果驱动（两槽同源同帧），
    放在上屏槽早退路径**之前**；只允许 `WOTA_CURVE_FS` 变体，构建失败退 `g_passLocs` 直通
    （成片丢曲线优于成片带监看叠加，且不阻塞录制）并落日志；
  - `DrawScene` 增 `encodeSlot` 尾参（默认 false，既有调用零改动）：编码分支恒无斑马/峰值
    uniform、只按 `g_encSlotCurve` 绑曲线；上屏分支原样；
  - `DrawEncoderPass` / `DrawEncoderMended`（直发 + MEND 离屏 cur 两条编码支路）改吃编码槽；
  - `DestroyGlObjects` 回收编码槽（在 EGL 拆除**之前**——5742c0f 释放顺序口径：删 GL 对象
    需现存当前上下文）。
- **`cpp/gl_render.h`**：`WotaGlSetEffect` 注释改「上屏专属」并登记编码槽自动跟随。
- **守卫**：`test/pure/monitor-slot-guard.test.ets` 4 例（C++ 源码结构扫描，与
  clip-export-shift 同口径）：编码槽存在且由 SyncEffect 同源驱动/只许曲线变体、两条编码支路
  都吃编码槽且 `encodeSlot=true`、DestroyGlObjects 回收、上屏槽保留效果全集。突变自证：
  编码支路换回上屏槽或删 encodeSlot 分支即红。
  `test/pure/preview-fit.test.ets` 的 DrawScene 签名锚同步更新（尾参加 `encodeSlot = false`，
  语义不变：编码恒满幅）。
- **登记（N/A）**：`5035b09` 附A/C（补弧 sidecar 走 MediaStore 兜底）——Android 共享目录
  非媒体文件写被拒的兜底，本侧沙箱直写无此通道；附B/D（帧龄改段内漂移）——本侧 native 无
  「墙钟 − 相机 SOF 戳」帧龄观测面（FrameLedger 只计帧数，NativeImage 戳只进光弧 keep-rule
  且已有防倒退钳制），无可修点。
- **真机待验（不阻塞收口）**：开斑马/峰值录制一段，成片应无叠加、预览有叠加；开曲线录制，
  成片应有曲线；MEND 档录制成片口径同。
- **回归**：pure-test 76 files / 785 tests 全绿；`assembleHap`（release）BUILD SUCCESSFUL
  （libentry.so 已含新槽代码）。

## 交付批（WB3A：手动白平衡探测门控 + diag 拆闸 + AF 遥测，2026-10-28）

上游 `ab73ebf` + `ee9aa0e`（WB 门控/运行时探测）+ `eefccc9`（日志通道拆闸）+ `4457e4a`
（AE/AF 遥测 + 峰值提示）。追赶基线第四批。**含本侧三处真实缺陷修复**（同根：手动档
「能不能用」没走运行时探测）：① `WotaParams.applyWhiteBalance` 兜底 `?? WbPreset.MANUAL`
会把总线钉到设备探测表里没有的档；② `WbPillPopup` 的 MANUAL 选项被 `p === WbPreset.MANUAL ||`
短路恒可选；③ apply 对 MANUAL 无探测守卫。

- **`core/CameraAbility.ets`**：新增 `manualWhiteBalancePossible()`——探测表声明 MANUAL 才可用；
  空表（未探测/无法判定）视为可用，不因「查不到」弹掉用户已选手动档（上游口径逐字）。
  探测产物本身已在本侧（KitAbility 会话期 `isWhiteBalanceModeSupported` 逐档探测 → awbModes），
  本次是把三个消费方真正接到探测上。
- **`core/WotaParams.ets`** `applyWhiteBalance`：fallback 兜底不再裸落 MANUAL（manualPossible
  才允许）；已选手动但探测不可能 → 降到第一个受支持预设。
- **`ui/ParamPills.ets`** `WbPillPopup`：MANUAL 选项改经 `manualWhiteBalancePossible()`——
  设备不声明就灰显点不动（不摆点不动的选项骗用户）。
- **`camera/RequestApplier.ets`**：`State` 增 `wbManualSupported`（stateOf 从能力探测装配）；
  apply 的 MANUAL 分支先过门——不支持则 `skipped`（带原因）不硬发（总线侧已降档，此处防御纵深）。
- **`diag/Diag.ets`**（上游 eefccc9 拆闸）：hilog 通道 **i/w/e 恒写**（取证通道与磁盘占用解耦，
  「总开关默认关」不再连 hilog 一起关）；**d/v 收详细档**（逐帧日志会冲掉系统 hilog 环形缓冲）；
  文件落盘仍受总开关（先总开关后级别的次序不变）。头注与 record() 注释同步。
- **AF 遥测 + 峰值提示**（上游 4457e4a 的 OHOS 对应面）：`GlCameraEngine.start` 挂
  `session.on('focusStateChange')`（SDK since 11，PhotoSession 在册）——1s 一拍、d 级
  （详细档才记），`SCAN/FOCUSED/UNFOCUSED` 短名；`FocusPillPopup` 增 `onManualSelected` 钩子
  （切入手动档一次性触发），Camera 页消费：峰值未开（effect≠3）时提示「手动对焦建议开
  『峰值』辅助（更多面板）」。
- **登记（N/A）**：① 上游 4457e4a 的 **AE 状态遥测**——SDK 无 PhotoSession 的 AE 状态事件
  （`CONTROL_AE_STATE` 无 OHOS 对应，本机 d.ts 全文核过），曝光归因仍靠下发报告 + AF 遥测；
  ② `79e30a7`/`4155c83`（AE 自动档停写 SENSOR 三键 / AE 翻转重建 builder）——Camera2 请求
  模型专属，OHOS 参数面无 SENSOR 三键、会话重建模型不同；本侧曝光组键语义（MANUAL 写全
  时长+ISO、LOCK/AUTO 只写模式与补偿）在 RequestApplier.ets 已有同构实现并有登记；③
  `ee9aa0e` 的 colorCorrection.availableModes 隐藏键问题——OHOS 无此键面（WB 能力经
  isWhiteBalanceModeSupported 公开探测），「运行时探测」精神本侧天然成立。
- **守卫**：`test/pure/wb-manual-gate.test.ets` 8 例——探测三态（有 MANUAL/无 MANUAL/空表）+
  五条接线源码守卫（PillPopup 探测/总线兜底降档/apply skip/State 装配/diag d-v 门控/AF 遥测与
  峰值提示）。突变自证：裸兜底回归、短路恒可选回归即红。
- **真机待验（不阻塞收口）**：不支持手动 WB 的机型上 WB 弹层「手动」灰显、不开详细档时
  hilog 仍见 i/w/e、开详细档见 AF遥测 行。
- **回归**：pure-test 77 files / 793 tests 全绿；`assembleHap`（release）BUILD SUCCESSFUL。

## 交付批（HIST：RGB 曲线框背景直方图，2026-10-28）

上游 `f31d18c` + `5742c0f`（释放顺序口径落进回收点）。追赶基线第五批。

- **`core/HistogramMath.ets`**：纯函数直译——`binRgba`（3×64 档，bin = v>>2）+
  `normalizedHeights`（各通道内按各自最大值归一，偏色不压扁小通道；全零返回全 0）。
- **`cpp/gl_render.cpp`**：直方图数据路——64×64 迷你 FBO（RGBA + NEAREST + CLAMP，完整性
  出口对齐 frost RT 口径）懒建；上屏绘制尾部 `MaybeSampleHistogram(locs)` 250ms 一拍把当前帧
  经**当前 program**（上屏槽 = 曲线输出后的画面，「边调边看分布」口径）画进 FBO 并
  glReadPixels 16KB；`BinHistogramRgba` 近地分箱（与 core `binRgba` **同式镜像**，接线守卫
  钉两侧公式文本；像素住在 GL 线程，192 int 跨 napi 带宽是 1/85）。快照锁发布（Android 用
  @Volatile 引用替换；本侧 napi getter 直接出 Int32Array，192 int 量级锁可忽略，登记偏离）。
  只在弹窗可见时采（`WotaGlSetHistogramEnabled`，关侧停采清快照）；16KB/4Hz 读回停顿远低于
  既有霜板/补弧 pass，只发生在目标场景（录制中开弹窗调曲线，掉一拍观测帧可接受）。
  `DestroyGlObjects` 回收迷你 RT（在 EGL 拆除**之前**——5742c0f 释放顺序口径，本侧回收点
  天然满足）。
- **`cpp/gl_render.h` + `napi_init.cpp` + `types/libentry/index.d.ts`**：`setHistogramEnabled`
  / `getHistogramSnapshot` 两口全链声明（NAPI-DECL 守卫对账过）。
- **`camera/GlLibBridge.ets`**：两口转发；**`pages/Camera.ets`**：开曲线弹窗
  `setHistogramEnabled(true)`、onDismiss `false`；`histogramProvider` 注入 CurvePopup
  （**UI 层不静态 import 桥**——load-order 契约与 Camera 页同款，provider 闭包持有动态模块引用）。
- **`ui/CurvePopup.ets`**：`visible` 挂 `@Watch` 启停 150ms 轮询；`drawCurve` 在网格/曲线
  **之下**画三通道 R/G/B 半透明 0x30 条形（条宽 = side/64、条高 = 归一计数 × side——
  随框缩放是几何自然结果）；弹窗不可见/无样本不画。归一必须走 core 纯函数（守卫钉）。
- **守卫**：`test/pure/histogram-math.test.ets` 8 例——纯函数 5 例（纯色单档[G=0 落 bin 0
  本侧补钉]/灰阶三通道全等/缓冲不足不抛/各自最大归一/全零）+ 接线源码守卫 3 例（native 采样
  与分箱同式镜像/250ms/当前 program/完整性出口/回收清快照；napi-桥-页面两开关；
  弹窗轮询与 0x30 背景）。
- **真机待验（不阻塞收口）**：开曲线弹窗背景出直方图、调红曲线红分布右移、关弹窗采样停
  （hilog 无新读回）、录制中开弹窗不炸成片。
- **回归**：pure-test 78 files / 801 tests 全绿；`assembleHap`（release）BUILD SUCCESSFUL。

## 交付批（GLOW：光晕参数模型纯逻辑层，2026-10-28）

上游 `ed59285` + `f381eee` 的 **core 纯逻辑层**。追赶基线第六批。按不迁三档纪律拆分：
纯逻辑全量落地（全表用例），**native Glow 链 + 胶囊 UI 留白登记**——Glow 是 2500+ 行的
GPU 特性（GlowBlurChain 507 行 + Shaders 167 行 + GlRenderEngine 298 行），链路性能必须
真机取证（帧率影响/负载分档实测）才可收口，且「不落地 UI 就不摆点不动的开关」（能力协商
而非能力假设——宁可没有也不做摆设）。

- **`core/Glow.ets`**：直译上游 core/Glow.kt（340 行）——`GlowParams`（enabled 默认关 =
  零代价口径；域钳在 sanitize：NaN/Infinity 回落出厂值、颜色掩 32 位；两档出厂预设 A 品红→青
  / B 橙→绿）、`encodeGlow`/`decodeGlow`（单串持久化键，坏串整串回出厂、未知键前向兼容）、
  `glowScaleFor`（像素×帧率三档：≤40M=1 / ≤150M=2 / 否则 4——与降噪 kernel 同一魔数
  `DENOISE_FULL_KERNEL_PIXEL_RATE` 不造第二份）、`glowRtSizeInto`/`glowRawLongestEdge`
  （按最长边等比夹取不破宽高比；夹取判据用夹取前原始最长边）、`fillGlowBlurOffset`
  （RT 短边单位、横纵各向同性）、`glowRgb`（只取 RGB，alpha 不参与）、`GlowColor` 调色板、
  `glowStatusNote`（状态行五级优先：DIRECT > 关着不显示 > 停用 > 夹取需真 RT > 分档句）。
- **平台偏离登记**：① 编码浮点无 Kotlin ".0" 后缀（JS String(1)="1"）——编码串只在本平台
  自产自销（Glow 参数不跨端互认），往返由 parseFloat 保证；② glowStatusNote 返回文案键
  （core 不引资源）；③ DENOISE_FULL_KERNEL_PIXEL_RATE 暂住本文件（降噪批移植时收口单源）；
  ④ **本文件当前无平台消费方**——native Glow 链（编码槽再扩 glow 一维）与胶囊 UI 随后续
  真机批，与 H 批 DirectSink 同款「只交付纯函数 + 全表用例、不伪造接线」。
- **守卫**：`test/pure/glow-pure.test.ets` 28 例——参数模型 5（默认/预设 B/钳域幂等/
  NaN 回落/颜色掩码）、编码解码 6（往返/坏串 9 形态/未知键/先钳再写/开关默认关/ARGB→RGB）、
  分档尺寸 6（三档/边界内侧/信息不全/按档缩保底/等比夹取/原始最长边/非法不写数组）、
  模糊偏移 4（横竖屏各向同性/方形/非法）、状态行 6（优先级全表）。
- **留白（登记，随真机批）**：① native `GlowBlurChain` 等价（两槽带 glow、阈值亮部提取、
  每帧一次）；② Glow 胶囊 + HUD 登记 + 持久化（f381eee；含 `glow` 键进 24 键白名单的
  追加裁决——键名契约只许追加）；③ ProgramKey 四维键（effect,curve,denoise,glow）——
  本侧编码槽现为 curve 单自由度，扩 glow 时一并升级。
- **回归**：pure-test 79 files / 830 tests 全绿；`assembleHap`（release）BUILD SUCCESSFUL。

## 交付批（DSCALE：上屏降档纯函数层，2026-10-28）

上游 `1e61e15` + `58464fc` 的 **core 纯逻辑层**。追赶基线第七批。与 Glow 同款拆分纪律。

- **`core/DisplayScaleRt.ets`**：`DisplayScale` 五档枚举（ORIG/HALF/QUARTER/EIGHTH/SIXTEENTH，
  除数 2 的幂）+ `resolveDisplayRtInto`/`resolveDisplayRtOrNull`（floor 缩放、任一维 < 128
  不可用且**不写数组**——三处共用同一判据不做 1×1 兜底）+ `nearestDisplayScale`（回落从
  **下一档**开始找——把 current 算进来会在「失败原因非尺寸」时无限循环；ORIG 恒兜底）+
  `displayScaleReadoutLabel`（读数 = 档位标签，UI 只套「预览：%s」）+ `displayRtNeedsRebuild`
  （只看尺寸与开关无关；目标非法 false 不在重建判据里拆资源）。
- **登记（native 链随真机批）**：OHOS 预览链**没有中间取景器 RT**（效果直接画上屏 surface；
  唯一离屏是毛玻璃专属）。等价落法只有两条：降相机流尺寸（连成片一起降，违反「成片不变」
  红线，不可取）或新建「效果 → 缩小 RT → 放大 blit 上屏」链（GPU 新路径，帧率/观感必须真机
  取证）。UI 档位/「预览：<档位值>」读数（58464fc）依赖该面，随同一批。
- **守卫**：`test/pure/display-scale-rt.test.ets` 9 例（floor 缩放/128 边界含恰等/非法不写数组/
  out 不足抛/Pair 版同判/回落不回 current/读数标签/重建判据/fromDivisor 回 ORIG）。
- **回归**：pure-test 80 files / 839 tests 全绿；`assembleHap`（release）BUILD SUCCESSFUL。

## 交付批（DENOISE：降噪参数模型纯逻辑层 + 平台裁定，2026-10-28）

上游 `8646b05` + `316516f` 的 **core 纯逻辑层 + 三路平台裁定**。追赶基线第八批。

- **`core/Denoise.ets`**：`AudioDenoise`（OFF/SYSTEM）/`VideoDenoise`（OFF/HAL_FAST/
  HAL_HIGH_QUALITY/GPU_BILATERAL）枚举 + 持久化键往返（坏值回 OFF）+ `effectiveVideoDenoise`
  （DIRECT 下 GPU 双边退 HAL 快速——返回实际档不静默失效）+ `videoDenoiseActive`
  （退档仍算有降噪——否则胶囊选中态跟渲染模式乱闪；**成片恒按所选档降噪**，上游 2026-10-27
  双槽改口）+ `audioDenoiseNeedsCodec` + `denoiseKernelLite`（像素×帧率 > 150M 用轻量核；
  自动降档 ≠ 关档）。`DENOISE_FULL_KERNEL_PIXEL_RATE` 单一真源落此，Glow.ets 改导入消费
  （此前暂住的常量收口）。
- **平台裁定（登记，不迁）**：① **音频降噪（SYSTEM）**——上游挂 NoiseSuppressor（采集链），
  OHOS `setAudioEffectMode` 是**输出端**（Renderer）音效 API，采集/录制输入链无应用侧等价物；
  且本侧 GPU 录制链 native 无音频轨（T27-3 留白）→ 枚举与判据保留作契约，**无下发面**
  （不摆没反应的开关）；② **HAL 降噪**（halNoiseReductionMode/noiseReductionModeToSend）——
  Camera2 NOISE_REDUCTION_MODE 专属，OHOS 相机 API 面无此键（d.ts 全文核过零命中），
  这两个发值函数不移植（移植了也永远没有消费方）；③ **GLSL 双边（GPU_BILATERAL）**——
  GPU 链特性随真机批（帧率/观感实测；同 Glow 纪律），本批不落 UI/native。
- **`PureLogicBarrel`**：Glow/DisplayScaleRt/Denoise/HistogramMath 四模块进编译探针
  （`cameraCatchupCompileProbe`）——Glow/DisplayScale 暂无平台消费方，由 barrel 保住编译门
  （#69 铁律：新增纯逻辑模块必须进 barrel，否则 hvigor 不可达）。
- **守卫**：`test/pure/denoise-pure.test.ets` 6 例（实际档/生效判据/Codec 需求/负载分档含
  1440p30 留完整核/档位编号常量/键往返坏值回 OFF）。
- **回归**：pure-test 81 files / 845 tests 全绿；`assembleHap`（release）BUILD SUCCESSFUL。

## 交付批（ORIENT：方向链路诊断日志 + 恒等族裁定，2026-10-28）

上游 `81cb3a5`（方向链路取证日志）+ `9cc36c5`（前摄方向分族修正）。追赶基线第九批。

- **`cpp/gl_render.cpp`**：首帧成功上屏的方向链汇总行——`orient sensor/display/mirror/fit/
  enc尺寸/encRot` 一行打全（真机排查「前摄方向不对」第一眼核这行的量与编码容器角是否自洽）；
  与上游 81cb3a5「首帧/换镜头打 stMatrix、stK、残角、applied、镜像、帧尺寸」的取证密度对齐。
  换镜头侧 `open slot` 取证行已在 LENS 批落地。
- **登记（N/A，后由 FRONT180 批部分订正）**：`9cc36c5` 恒等族**矩阵识别**——Android
  SurfaceTexture 平台面专属（`getTransformMatrix` 两族解析），本侧 NativeImage 不暴露变换
  矩阵，结构性不成立；但该提交的**前摄 +180** 是独立的镜像语义修正，本侧同样需要——见
  FRONT180 批（2026-10-28 模拟器横屏前摄实测差 180° 后补）。
- **回归**：pure-test 81 files / 845 tests 全绿（无 ArkTS 改动）；`assembleHap`（release）
  BUILD SUCCESSFUL（libentry.so 已含 orient 行）。

## 走查批（EMU-WALK：Pura 900 模拟器运行时走查，2026-10-28）

宿主 webcam 作相机输入，unsigned 包装机（模拟器不验签）。**成片侧验证被模拟器挡住**：
`gpu record start failed code=encoder-create`（OH_VideoEncoder 建档失败 ×2，模拟器无对应
编码器）——「斑马/峰值不入成片」的成片侧断言留真机；上屏侧全部实测通过。

- **LENS 批（open slot 行）**：全新安装两轮复现——`open slot=lcam001 type=WIDE eq=24mm
  videoMax=921600 afModes= minFocusD=0`（能力协商默认 + 归档/AF/定焦取证字段齐）。
- **ORIENT 批（首帧 orient 行）**：`first frame 1320x2856 swap=1 orient sensor=90 display=0
  mirror=0 fit=1.0000/0.8217 enc=0x0 encRot=90` 一行打齐；换前摄后 `texcoords deg=0/90
  mirror=1`、模拟器显示旋转时 deg 正确跟随——方向链在换镜头/旋转两场景留痕且自洽。
- **SLOT 批上屏侧**：效果循环后 `program slot effect=1 curve=0`；截图红条纹像素 4.2%
  （0xE53935 斜纹，阈值 0.6 之上区域），斑马叠加在上屏渲染确认。
- **HIST 批**：曲线弹窗 Canvas 700×700 框内 77% 纯黑 + 三通道 0x30 条形（B 2155 > R 1698 >
  G 728，与暗场景直方图形状一致，条形自底向上）——采样→分箱→归一→绘制全链活。
- **WB3A 批**：① WB 弹层「手动色温」`enabled=true`——模拟器探测**声明**支持 MANUAL，
  门控按探测放行（灰显路径需不声明 MANUAL 的机型，由守卫测试钉）；② 起录前逐键报告把
  模拟器拒掉的 `exposureMode(Operation not allowed.)` 如实打进 `pre-record apply failed`
  （W 级）——逐键 try/catch + 拆闸后的取证通道按设计工作。
- **观察（存量，非本批引入，登记不修）**：① `CameraSettings` 对象字段改值不触发 Dock 胶囊
  重渲染——出现顶栏读数 `24fps` 与 Dock `帧率:30` 并存（两处同读 settings.fps；@State 对象
  字段变更不通知，读数条因他态重绘偶先刷新）。
- **方向事件还原（用户报「横屏 HUD 控件巨大/录制键出屏/画面压扁」的结论）**：非方向锁缺陷——
  沙箱持久化里 `ui_orientation=landscape`（设置页方向控件是唯一写入方；本 walkers 的越界坐标
  点击最可疑，无法完全归因），应用忠实落了 `preferred orientation -> landscape`，而**横屏 HUD
  属 T32 留白**（T31 只做了竖屏布局），于是渲染出那套未设计的横屏形态。**方向锁本身有效**：
  设置页点回「竖屏固定」→ `preferred orientation -> portrait` → 显示立即弹回竖屏（尽管模拟器
  传感器仍报横屏）。横屏保真度维持 T32 留白登记；「横屏档在 T32 前是否隐藏」属产品决策，
  待用户裁决。
- **录制启动失败的用户可见性**：状态行如实显示 `录制启动失败: encoder-create`（健康三格
  路径），失败不被吞。

## 修复批（FRONT180：前摄方向 +180——横屏实测缺陷，2026-10-28）

EMU-WALK 中用户实测：横屏 + 前摄，取景画面差 180°。**订正 ORIENT 批的 N/A 过判**：
「恒等族矩阵识别」确实 N/A（本侧无 stMatrix 反解面），但 9cc36c5 的**前摄 +180** 是独立的
语义修正——前摄 sensorOrientation 的参考系与后摄相反（镜像语义），转正公式比后摄多 180°
（上游 RecordProfile 前摄容器角官方公式 360−((sensor+display)%360) = sensor−display+180），
与矩阵族无关。

- **取证链**（EMU-WALK 日志）：后摄 sensor=90、前摄 sensor=270（首帧 orient 行直读）、横屏
  display=270 ⇒ 旧公式后摄 `deg=180`（正确）、前摄 `deg=0`（差 180，正是用户所见）。
- **`cpp/gl_render.cpp`** 两处同修（g_mirrored 即 front 标志）：① 上屏 UV 复合角
  `RebuildTexCoords` 加 `(g_mirrored ? 180 : 0)`；② 编码容器角 `WotaGlEncoderRotation`
  同加——修正后同屏向下两颗镜头容器角一致（竖屏均 90、横屏均 0，佐证这是把前摄镜像
  参考系扳平的语义修正）。+180 不改变 90/270 性质，letterbox 轴交换（PreviewFit）不受影响。
- **复验**（模拟器横屏 + 前摄重装后）：`texcoords deg=180 mirror=1` + 首帧
  `orient sensor=270 display=270 mirror=1 ... encRot=180`——画面转正由用户目视确认。
- **守卫**：`monitor-slot-guard.test.ets` 增「前摄方向修正」例（两处公式源码钉 + 突变自证）。
- **回归**：pure-test 81 files / 846 tests 全绿；`assembleHap`（release）BUILD SUCCESSFUL。

## 交付批（LAND：横屏 HUD + 方向常数 v2 + 默认横屏，2026-10-28）

用户裁决：「横屏才是常用状态，做好」+ 两条画面修正（前摄再左右镜像；后摄顺时针 90° 后
左右镜像）。T32 横屏保真度提前落地。

- **方向常数 v2（`cpp/gl_render.cpp` 两处同式）**：`deg = sensor − display + (front ? 0 : 180)`、
  `mirror = !front`（后摄镜像、前摄不镜像）。由用户对模拟器竖屏实景的修正标定反推：后摄目标
  （270, 镜像）、前摄目标（270, 不镜像）——两组与公式吻合。根因：**OHOS 预览缓冲的方向语义
  与 Camera2 SurfaceTexture 不同**，Camera2 公式在本侧整体差这组常数（登记；真机复测若冲突
  再按机型域分流）。ArkTS 侧 `applyGeometry` 传 `mirror = !info.front`；编码容器角同式。
  横屏后摄稳态 = `deg=180 mirror=1`（90−270）、前摄 `deg=0 mirror=0`——首帧 orient 行
  与 texcoords 实测自洽。
- **默认方向改横屏**（`UIOrientations.DEFAULT = LANDSCAPE`）：撤销 T31 的竖屏默认偏离，
  回到上游口径；竖屏仍在设置页可切（T31 布局逐字保留）。
- **横屏 HUD（`pages/Camera.ets`）**：`isLandscape` 由窗口 vp 尺寸驱动（`refreshHudReadoutMetrics`）；
  横屏 Dock 为**根 Stack 底部右侧浮层**（全屏容器 End/End 对齐 + 透明命中），锚点 id 与竖屏
  同名（弹层量框定位复用）——**首版曾做成 HUD 纵向流内分支，实测被顶部读挤压出屏**
  （「更多」被裁、快门完全出屏），改浮层后全部落在屏内（更多 y 1047-1215、快门行
  1026-1278 < 1320）。预览不再被压缩（浮层盖在画面上，行扫描非黑占比 82-91% 全高）。
- **守卫**：`monitor-slot-guard.test.ets` 方向常数例改 v2（两处公式钉 + v1 写法必须消失的
  突变自证 + ArkTS `mirror = !front` 接线）；`ui-orientation-wiring.test.ets` 默认档钉子改
  LANDSCAPE（2026-10-28 裁决口径）。
- **模拟器实测**：全新安装默认横屏生效（`preferred orientation -> landscape`）；横屏首帧
  `orient sensor=90 display=270 mirror=1 fit=0.8217/1.0000 encRot=180`；Dock 浮层几何全在屏内。
  竖屏切换路径（设置页「竖屏固定」）此前已验证锁有效。
- **回归**：pure-test 81 files / 846 tests 全绿；`assembleHap`（release）BUILD SUCCESSFUL。

## 修复批（LAND2：方向 v3 + 横屏单行底栏，2026-10-28）

用户第二轮反馈（后摄再逆时针 90°；HUD 文字截断/控件重叠、占用录制页过多）两轮迭代后收口。

- **方向标定 v3（`cpp/gl_render.cpp` 两处同式）**：`deg = display + (rear ? 180 : 90)`、
  `mirror = !front`。四锚点拟合（用户两轮实测）：竖屏后摄 180/前摄 90、横屏后摄 90
  （v2 的 180 再逆时针 90）/前摄 0。**sensor 不参与**——模拟器 webcam 的 HAL 缓冲随显示
  旋转（登记：真机必须按 SENSOR 语义重新标定；v1→v3 三轮均为模拟器域标定）。实测
  `texcoords deg=90 mirror=1`、首帧 `encRot=90` ✓。
- **横屏 HUD 两轮迭代**：① 侧栏版（2×4 网格 + 底行）→ 用户否决：占用过多、设/库与读数条
  仍有交叉；② **定版=单行底栏**：9 颗短标签胶囊（dockLabel 冒号后值：广角/1080p/30/1x/
  AUTO×2/-/5500K/连续）+ 更多 + 72vp 快门一行放下，整条 168px（屏高 6%），`HudPill` 宽度
  由 layoutWeight 均分（约 68vp）文字完整；**dump 全量核对：文本重叠 0 对、越界 0 个**。
  读数区（顶栏两行 + 读数条）回归全宽、与底栏无交叠；竖屏分支逐字未动。
- **测量脚本口径**（本轮新增工作法）：uitest dump 全文本节点 + 两两包围盒交叠检查
  （x/y 交叠 >10px 记重叠）+ 右/下缘越界检查——「多截图多比对」的机器化版本。
- **v4 追加（同日第三轮用户实测）**：前后摄**各再 +180°**（两路同时颠倒）——
  `deg = display + (rear ? 0 : 270)`、`mirror = !front` 不变；横屏锚点 后摄 270/前摄 180。
  实测 `texcoords deg=270 mirror=1`、`encRot=270` ✓。四轮 v1→v4 均为模拟器标定，真机仍待
  按 SENSOR 语义复测（登记）。
- **回归**：pure-test 81 files / 846 tests 全绿；`assembleHap`（release）BUILD SUCCESSFUL。

## 交付批（UI 复刻：安卓横屏 HUD + 双栏设置页，2026-10-28）

用户指令「让子代理并行修订界面，复刻安卓侧界面截图」。两子代理分文件域并行（互不冲突），
完成后合并验证。

- **取景页横屏 HUD（`pages/Camera.ets`）**：照 r03 截图复刻——左上分辨率胶囊 + 红字
  「预览：原片」；右上三圆钮（网格/更多/设置）；左侧 float 容器（圆形网格钮 + RGB 曲线 +
  光晕/色调禁用态芯片〔功能未实现，点击提示，不造假面板〕）；右侧水平仪 + 对焦蓝钮；
  底部中央录制簇（图库 + 72vp 快门 + 镜头胶囊）；左下 降噪（禁用态）/码率；右下 快门/
  帧率/感光度读数胶囊；白线安全框。锚点 id 全保留（pill_ev/wb/zoom 无入口留 1×1 占位）；
  竖屏 T31 分支逐字未动。几何自检：控件两两相交 0、出屏 0。
- **设置页（`pages/Settings.ets`）**：横屏双栏（两 Scroll 各半 + 32vp 列距）、24fp 蓝色
  分组标题、卡片组（#1E1E22 圆角 16 + 发丝分隔线）、蓝选中胶囊/蓝 Toggle、右值蓝字；
  竖屏单列保持原行为；权限状态用 abilityAccessCtrl 真查（查不到显「未知」）；功能与持久化
  全保留（文案「横屏使用/竖屏使用」）。
- **登记**：更多面板在本模拟器横屏下内容行空白（HudMoreSheet 未改动，疑 ArkUI Scroll+
  旋转窗口问题，待查）；左挖孔 avoidArea 未消费（真机须核）；截图未照抄参考图的假读数
  （码率/帧率/镜头按真实状态显示）。
- **回归**：pure-test 81 files / 846 tests 全绿；`assembleHap`（release）BUILD SUCCESSFUL。

## 交付批（UI 功能对齐：移除「更多」+ 面板/控件按安卓重接，2026-10-28）

用户裁决：**安卓侧没有「更多」面板**；剩余面板/控件按已有布局修订到与安卓同功能。三子代理
并行（文件域互斥）+ 主线程收口去重。

- **移除「更多」**（Camera.ets）：按钮/状态/渲染块全删（HudMoreSheet.ets 文件保留供守卫读取）；
  原承载功能按安卓位重接——闪光→右侧竖排胶囊（对焦下方，无闪光灯禁用态+提示）；效果四档→
  设置页「屏幕监看」组（写 settings.effect，Camera onPageShow 重读并 applyEffect）；
  蓝牙→设置页入口（pages/BtPanel）；光弧/网格本就在设置页；右上中圆钮→**pages/HudEditor**
  （恢复安卓「HUD 位置编辑」语义）。
- **码率胶囊→真弹层**：自动/5M/10M/20M/35M/50M，点选写 settings.bitrate + persist
  （横屏左下读数、竖屏独立胶囊同弹层）。
- **光晕面板落真参数**（新 `ui/GlowPanel.ets` + `ui/GlowStore.ets`）：照 r03 截图（关/开、
  预设 A/B、亮度阈值/软边滑杆）；参数经 core/Glow 的 encode/decode 存 prefs `glow_params`；
  面板内注明「渲染链随 native 真机批生效」——**参数真实、渲染未接，不冒充已生效**。
  （ArkTS 坑：组件成员不能命名 `enabled`，与基类属性冲突 → `glowOn`。）
- **设置页「屏幕监看」组 + 蓝牙入口**（Settings.ets）：与既有双栏/蓝标题/胶囊样式同源；
  去重：录制卡旧「效果」行删除、过期脚注改「监看只上屏不入成片」口径。
- **守卫**：bt-speaker.test.ets 蓝牙入口锚点从 Camera.ets 移到 Settings.ets（用户裁决注明）。
- **遗留**：竖屏曲线编辑入口随更多移除暂无（横屏左侧有）；音频电平表未接；色调/降噪维持
  禁用态（功能本体未移植）；更多面板横屏内容空白问题随其移除而消失。
- **回归**：pure-test 81 files / 846 tests 全绿；`assembleHap`（release）BUILD SUCCESSFUL；
  模拟器实测：更多消失、闪光/光晕/色调/码率落位正确、光晕面板可开合。

### 补齐批（三缺口收口，2026-10-28）

- **竖屏曲线入口回归**：竖屏顶栏右上 40vp 圆钮「曲」→ openCurveEditor（id `pill_curve`）；
  横屏左侧 float 原入口不变。
- **色调实面板**：`core/Tone.ets`（黑点/白点 ±0.35、阴影 -1..1；公式与安卓 ToneParams.kt
  逐点对拍 0 偏差）+ `ui/TonePanel.ets`（照 r03 截图三滑杆）+ `ui/ToneStore.ets`（键
  `tone_params`）；Camera applyCurve 合成链 `bakeBytes → applyToneToLut → uploadCurveLut`
  （effect=1 与 applyEffect 同口径）。**登记**：安卓烘焙顺序为 curve(tone(x))，本侧为
  tone(curve(x))——Tone.ets 头注已记，真机校色批决定是否前置；高光轴本批未移植（接口只留三参）。
- **音量表**：`ui/AudioLevelProbe.ets`（AudioCapturer MIC 16k 单声道 S16LE，RMS→dBFS→0..1，
  100ms 节流自愈，失败静默不与录制抢麦）；横屏右侧水平仪下方一行（细柱图标 + NN%），
  启停由 Camera `syncAudioProbe()` 统一裁决（8 个启停点覆盖显隐/起镜/录制/停录）。
- **守卫**：`tone-pure.test.ets` 12 例（往返/坏串/钳制/恒等逐字节/单调/方向/尾字节防御）。
- **回归**：pure-test 82 files / 858 tests 全绿；`assembleHap`（release）BUILD SUCCESSFUL；
  模拟器实测：色调面板可开合、竖屏曲线钮在册、音量表出读数。

### 曲线独立层修正（2026-10-28，回应「RGB 曲线徒有表象」）

- 旧接线：曲线只在「效果档=曲线(1)」时 `setCurveEnabled(true)`，直通/斑马/峰值下烘焙的 LUT
  被丢弃——RGB 曲线面板在多数态下是摆设（安卓为独立层、可与斑马/峰值叠加）。
- 修正（`pages/Camera.ets` 两处）：`applyEffect` 统一「合成 LUT → 上传 → setEffect →
  `setCurveEnabled(!isPassthrough)`」；`applyCurve` 同口径。曲线非恒等即生效，与效果档解耦；
  设置页「屏幕监看」的「曲线」档保留为遗留兼容档（效果槽恒 NONE，曲线由独立开关承担）。
- 回归：pure-test 82 files / 858 tests 全绿；assembleHap 绿。

### 「真功能 vs 表象」盘点（2026-10-28 诚实对账，供后续批排期）

| 功能 | 状态 |
|---|---|
| RGB 曲线 | ✅ 真：面板编辑 → LUT 烘焙（含色调合成）→ native 曲线 program；本批起独立层生效；直方图参考已落 |
| 色调 | ✅ 真（LUT 链）：黑/白/阴影三参；**高光轴未移植**；烘焙顺序与安卓相反（登记） |
| 斑马/峰值 | ✅ 真（上屏专属，双槽口径真机待验成片侧） |
| 闪光电平表音量表/码率弹层 | ✅ 真 |
| 光晕 | 🟡 **仅参数与持久化**（native GlowBlurChain 未移植——最大表象，随真机批） |
| 降噪 | 🟡 禁用态（音频无平台等价、GLSL 双边随真机批） |
| HUD 位置编辑（HudEditor） | 🟡 仅读数条顺序（完整五容器自由布局未移植，T32 面） |
| 高帧率档位 | 🟡 等 VideoSession 帧率平台面 |
| 前摄/方向 | ✅ 模拟器标定 v1→v4；真机须按 SENSOR 语义复测 |

## 修复批（LOGICFIX：逻辑漏洞与功能错位，2026-10-28）

deveco 知识检索（云端不可达已登记；改以本地 `devecocli docs` + 本机 SDK d.ts 为权威）驱动的
全量对账修复。

- **fps 数据源死锁（逻辑漏洞，P1）**：`abilityOfDevice` 只取 `NORMAL_PHOTO` 场景能力，
  该表 videoProfiles 常为空（模拟器实证 `fpsRanges=` 空）⇒ `decideFps` 全档 undefined ⇒
  帧率弹层全灰、参数总线 `inexactDisabled`、`applyPreviewFps` 无据。**修复**：并行取
  `NORMAL_VIDEO` 能力表并把 videoProfiles 并入（预览/照片仍用 PHOTO 表）。实测
  `fpsRanges=[30,60]`——30/50/60 可选（※ 近似档），24/25/120/240 诚实灰显。
- **右上第一圆钮功能错位**：原误接「网格」与左侧圆形网格钮重复；按安卓位语义改**参考线
  快切**（外框图标，全开 2047/全关，与设置页多选同源），网格只归左侧圆钮。
- **Glow 持久化跨重启验证**：重装后 `glow params enabled=1` 自动生效（glow_params → 参数→链）。
- **降噪变体运行时验证**：`program slot effect=0 curve=0 denoise=1`（full 核）。
- **登记（待真机/产品面）**：① 音量表在 MIC 权限未授予前恒 `—`（探针静默；GPU 录制链无
  音频轨，不主动弹麦克风权限——与 Android「未授权录音时电平表无读数」同姿态）；
  ② 模拟器 HAL 拒 `exposureMode` 导致状态行常驻「参数下发失败」噪声（真机无此现象）；
  ③ DisplayScale「预览：原片」读数仍为常量（缩放上屏链留白）；④ Glow RT 以显示面尺寸
  定档（本侧无相机帧原生尺寸观测面，登记偏离安卓 wantFrameWidth）。
- **回归**：pure-test 82 files / 862 tests 全绿；`assembleHap`（release）BUILD SUCCESSFUL。

## 上游基线推进（2026-10-28，4998ce4 → 9cc36c5）

追赶轮收口：基线一次推进到本地 main `9cc36c5`（推送后即 origin/main）。区间 39 提交逐条处置
汇总（详单见上方各交付批）：

| 上游提交 | 鸿蒙处置 |
|---|---|
| `74d4fb2`/`313e8e6`/`0f713a2`/`2f30ad5`(decideFps) | FPS 批（572a1b1） |
| `0525fa2`/`9b33272` | LENS 批（b5fbbd5；胶囊禁用主体结构性不适用已登记） |
| `46d8d91`/`5742c0f`(释放顺序)/`5035b09` | SLOT 批（c67a458；附A/C·附B/D N/A 已登记） |
| `ab73ebf`/`ee9aa0e`/`eefccc9`/`4457e4a` | WB3A 批（cab8ace；AE 遥测等三件 N/A 已登记） |
| `79e30a7`/`4155c83` | N/A（Camera2 请求模型专属，WB3A 批登记） |
| `f31d18c` | HIST 批（218e725） |
| `ed59285`/`f381eee` | GLOW 批纯逻辑层（e148891；native 链+UI 留真机批） |
| `1e61e15`/`58464fc` | DSCALE 批纯函数层（31b347a；native 链+UI 留真机批） |
| `8646b05`/`316516f` | DENOISE 批纯逻辑层+平台裁定（8d9a346） |
| `81cb3a5` | ORIENT 批（本轮） |
| `9cc36c5` | ORIENT 批 N/A 登记（SurfaceTexture 平台面专属） |
| `346ef79` | N/A——右槽选片三根因（Photo Picker URI/权限自省）是 Android 存储面问题；
  本侧 Compare 选右片走沙箱 MediaRepo（相对路径直读），无 URI 权限通道 |
| `eefccc9`(Camera2Engine 部分)/`2f30ad5`(sidecar 名) | Android 平台面细节，对应面 N/A 或已覆盖 |
| `67748e6`/`8e68486`/`c0d6cfa`/`e4ef241`/`62fa57d`/`640f04c` | 文档口径/发版杂务/merge 节点，
  无移植动作（新口径已随各批落 PORT_MAP） |
| `a9b0cae`..`8c6603e`（站位线族） | 本侧已在 main（harmony/* 提交），非上游增量 |

## 交付批（HDG：HUD 读数顺序拖拽换位，2026-10-25）

**范围裁决（本条只落一半，且如实标注）**：上游 `HudLayoutEditor` 的完整自由布局是
「五容器之间拖拽换区 + 格网落格」。本侧**没有对应渲染面**——取景 HUD 是 T31 真机取证后的
固定结构（顶栏两行 + Dock 2×4 + 更多面板），只有 READOUT 区是有序列表。跨容器拖拽等于
重做取景 HUD，属**产品决策**且必须真机验收，不在本批。本批落地有落点的那半：
**同容器内长按拖拽换位**。

- **`core/HudDragOrder.ets`**（纯逻辑零平台 import）：`targetIndex`（位移折算行数 + 夹取；
  **半行死区**——拖过邻居一半才换位，避免抖动来回换；行高未量到/NaN 时**返回原下标**
  ——不动作胜过按错误行高乱跳）；`moveTo`（其余元素整体让位、长度不变、**返回副本**不改入参）；
  `changed`（没换位不写盘，免无谓保存与 IO）。
- **`pages/HudEditor.ets`**：行首加拖拽把手 `≡`（长按 400ms 拾起，`PanGesture` 纵向跟手，
  释放落位），拖拽中其余行降透明度、把手高亮；**保留既有 ↑/↓ 按钮**（两种操作并存，不是替换）。
  顺序改写仍走 `HudLayoutTable.withZoneOrder`（同容器、无格子钉扎）——本页不手写任何位运算/
  串拼接，编解码单一真源不变。
- **命名坑登记**：内部方法最初取名 `onDragStart/onDragMove/onDragEnd` 与 ArkUI `CustomComponent`
  的内建拖拽生命周期**同名冲突**（编译期报 not assignable）——改名 `beginRowDrag/updateRowDrag/
  finishRowDrag`。
- **测试**：`hud-drag-order.test.ets` +7（位移折算与上下夹取、原地与半行死区两侧、异常输入
  四态（行高 0/负/NaN、起下标越界、空表）、换位与副本语义、变化判据、接线三条（纯逻辑真源/
  长按与纵向手势/按钮并存）、范围声明在册）。共 +7（752 → 759）。
- **运行时走查待办**：真机/模拟器上长按拖动的手感（半行死区是否合适）、释放后取景页读数条
  即时反映新顺序。
- **回归**：pure-test 73 files / 759 tests 全绿；clean `assembleHap`（release）
  BUILD SUCCESSFUL。

## 交付批（ALB：系统相册导入，2026-10-25）

**改判说明**：此前的登记把「相册 AllVideos」与 StorageAccess 打包成「不迁」。复核后：上游
`MediaScope.AllVideos` 是**浏览全机视频**的 scope（需要 `READ_MEDIA_VIDEO` 一类读权限，并且
对别人的文件做收藏/回收站/删除）。本侧一贯姿态是**零媒体权限**（MS 批红线用例钉住），沙箱
模型下管理别人的文件也必须逐次弹系统授权框——那正是上游 StorageAccess 那条线要规避的东西。
故取**等价且合规的形态**落地：系统相册选择器（用户显式选择 = 临时授权读，**零权限声明**）
→ 把选中视频**拷进应用库**；导入后即为本应用正常成片，改名/标签/系列/分享/同步等既有能力
自动可用。

- **`core/ImportNaming.ets`**（纯逻辑零平台 import）：`fileNameFromUri`（取 URI 尾段、去
  查询串与 fragment、百分号解码，解码失败原样）；`preferredName`（走 `RenameSpec.sanitize`
  同一道闸 + 缺扩展名补 `.mp4`）；`stampName`（兜底名 `IMP_yyyyMMdd_HHmmss.mp4`）；
  `withOrdinal`（`a.mp4` → `a (2).mp4`，基名与扩展名分别保留）；`uniqueName`（库内撞名递增，
  **大小写不敏感**——部分文件系统 `A.mp4`/`a.mp4` 会互相覆盖；用满 `MAX_TRIES=200` 返回空串，
  **绝不硬写覆盖**）。
- **`media/AlbumImport.ets`**（平台件）：`PhotoViewPicker.select`（`VIDEO_TYPE` 过滤、
  单次上限 20）→ 逐个 `fileIo.openSync(uri, READ_ONLY)` 流式拷贝进 `filesDir/videos`
  （选择器 URI 临时可读；读取路径按本机 d.ts 实证——`PhotoAsset.open` 自 API 11 起
  `@deprecated` 且 `@useinstead ohos.file.fs/fileIo#open`）→ 成功/失败/取消三态回报。
  拷贝失败**必须清残留**（0 字节幽灵条目防线）。
- **UI**：媒体库顶栏工具胶囊组加「导入」（`enabled` 受 loading/busy/dbOk 门控），完成后刷新
  并提示「已导入 N 个视频，M 个失败（原因）」。
- **权限姿态（红线）**：`module.json5` **不新增任何媒体读权限**——守卫直接对权限清单断言
  `READ_IMAGEVIDEO`/`WRITE_IMAGEVIDEO`/`READ_MEDIA` 零命中。
- **测试**：`album-import.test.ets` +8（URI 尾段与解码五态/目标名合法化与补后缀/兜底名格式/
  撞名递增三态/去重（含大小写不敏感与空名）/上限耗尽不覆盖/权限姿态与平台读法守卫/页面
  入口守卫）。共 +8（744 → 752）。
- **运行时走查待办**：真机/模拟器上拉起相册选择器、选多个视频、核对库内新条目可播放、
  撞名递增与失败清理。
- **回归**：pure-test 72 files / 752 tests 全绿；clean `assembleHap`（release）
  BUILD SUCCESSFUL。

## 交付批（RN：媒体改名 + 成套迁移，2026-10-25）

**改判说明**：此项此前裁决「不迁」，理由是「ID = 相对路径 FNV-1a，改名即换 id，标签/回收站/
系列成员全部失联，前置 = id 升级 64 位」。经复核，**前置不是必须的**——上游 MediaStore 的
`_ID` 与名字无关所以改名天然安全，而本侧只要把「按路径/ID 记账的每一处」在改名时一起搬，
就能在不改 ID 架构的前提下安全改名。故本批按功能落地。

- **`core/RenameSpec.ets`**（纯逻辑零平台 import）：`sanitize`（拒空/`.`/`..`/含 `/` `\`/
  控制字符/超长——上游靠 MediaStore 消毒，本侧直接拼路径必须自己拦，与 f941799 给 sidecar
  补的那道闸同源理由）；`ensureSuffix`（上游逐字：旧名有后缀而新名没带则补，大小写不敏感）；
  `stemOf`/`extOf`；`resolve`（合法化+补后缀，与原名逐字相同则拒绝=不动作）；`newPathOf`/
  `newRelativePathOf`（同目录）。
- **`MediaRepo.rename`**（编排）：合法化 → 同名文件先拒 → 录制中文件拒 → 磁盘改名 →
  sidecar 跟着搬 → **DB 三表单事务换键** → **同步键随搬**（`TrackSync.renameFor`：五键按新
  路径写回、旧键清空；未校准段不留记录）。任一硬步失败 → `revertRename` 把文件改回去，
  不留「文件已改名、账未迁移」的半态。返回空串=成功，非空=可展示的拒绝/失败原因。
- **`WotaDb.renameMedia`**：`beginTransaction` + 三条 UPDATE（media_tag 按 path、
  trash_item 按 uri、series_part 按 uri）+ commit/rollBack。
- **`TrackSync.renameFor`**（纯逻辑）：与 `clearFor` 的区别是「改名不是删片，记录该留」；
  done 非 true 时只清旧键不搬（无记录可搬）。来源（auto/manual）随搬，不被误降级。
- **UI**：Gallery 卡片动作行加「改名」→ `bindSheet` 改名弹层（`sheetKind='rename'`，预填
  基名、提交走仓库侧、成功即刷新并提示新名）。
- **取舍登记**：① CompareHistory 按数值 ID 记账，改名后旧条目失效——沿用其「缺失标记」
  既有语义，不整表重写；② 外部改名（系统文件管理器）仍会脱钩，登记为平台事实。
- **测试**：`media-rename.test.ets` +12（合法化拒绝面/去空白与常见字符/后缀补齐三态/基名与
  扩展名/`resolve` 拒绝同名的两条/同目录拼装/同步键迁移三例（随搬+旧键清空、未校准不搬、
  自动记录来源保持）/接线守卫三条（仓库成套迁移、DB 三表单事务、页面入口与弹层））。
  另**改写两条被本批推翻的旧钉子**：`media-share-wiring` 的「不许出现重命名入口」→
  「入口在册 && 迁移面在册」；`gallery-forms` 的菜单收口计数 9 → 10（新增改名动作方法）。
  共 +12（732 → 744）。
- **运行时走查待办**：真机/模拟器上改名一条 → 标签/系列/同步记录仍在、`drops.json` 跟名、
  撞名与非法名提示。
- **回归**：pure-test 71 files / 744 tests 全绿；clean `assembleHap`（release）
  BUILD SUCCESSFUL。

## 交付批（WLB：取景预览 letterbox 几何，2026-10-25）

**改判说明**：此项此前被登记为「平台偏离（XComponent 全屏直出，未做 letterbox 几何）」，
经静态核对后认定为**未修的缺陷**而非有意偏离——native 上屏是全屏四边形、UV 只做旋转/镜像，
**整条管线没有任何按宽高比收缩的算式**，而预览档取的是录制尺寸（`Camera.ets` 传
`settings.sizeW/H`，典型 1920×1080）。宽高比不一致时画面被拉伸铺满（竖屏手机 16:9 内容
拉进 ≈9:19.4 的屏）。上游 Compose 侧用 `aspectRatio` 盒做 letterbox，本侧此前无对应物。
**故本批按缺陷修复**，不是新增功能。

- **`core/PreviewFit.ets`**（纯逻辑零平台 import）：`swapsAxes`（90/270 互换，与 native UV
  旋转同源）/ `aspectOf`（含旋转取向）/ `rectOf`（在视口内按画面比例**内接 + 居中**，
  与 `RefLine.inscribedBox` 同一套语义）/ `ndcScaleX|Y`（native 收缩因子）/
  `mapToContent`（点按映射，黑边点夹到最近边缘——保留既有「钳制而非无响应」语义）。
  不可用（尺寸/视口缺失或首帧未量到）一律**退回铺满 = 旧行为逐字相同**，调用方零改动兼容。
- **native**：`WOTA_TEXTURE_VS` 与 `WOTA_FROST_QUAD_VS` 各加 `uniform vec2 uFit`
  （`gl_Position = vec4(aPosition.xy * uFit, aPosition.zw)`，**默认 1,1**）；
  `WotaGlSetDisplayFit(fitX, fitY)` + NAPI `setDisplayFit` + d.ts + `GlLibBridge` 转发。
  上屏两路（常规 + 毛玻璃 `g_frostDisplay`）吃 `g_fit`；毛玻璃中间三趟（拷贝/横/纵高斯）
  恒传 (1,1)（RT 尺寸=表面尺寸）；**编码面经 `DrawScene(..., applyFit=false)` 恒满幅**
  ——成片不带黑边。会话复位回满幅。
- **页面接线（三处共用同一份矩形，禁各算一遍）**：`applyPreviewFit` 由几何变化与视口实测
  （`onAreaChange`）触发，算完下发 native；`RefLineOverlay.aspect` 从恒 0 改为画面比例
  （三分法网格与 11 档参考线一并内接）；点按对焦先把窗口坐标映射进画面再走既有
  `FocusTapMath → 三击仲裁 → 下发链`。
- **测试**：`preview-fit.test.ets` +11——取向互换（含负角/超界）/ 比例含旋转 / 内接居中
  （竖屏上下留黑、横屏两侧留黑、比例一致铺满、不可用铺满）/ 旋转参与内接 / NDC 因子 /
  点按映射与黑边夹取 / 不可用时与旧行为逐字同 / 接线守卫（两个 VS 都乘 uFit、默认 1,1、
  编码面恒满幅、上屏两路吃 g_fit、中间三趟恒等、会话复位、三处共用矩形、d.ts 与桥同步）。
  共 +11（721 → 732）。
- **运行时走查待办**：画面是否还有拉伸（若平台本就按屏幕比例交付帧，则 fit≈1、视觉零变化
  ——本批通过日志 `display fit x/y` 可直接读出实际值）；黑边上滑动手势不触发对焦（当前夹到
  边缘，与旧钳制语义一致，是否改成忽略留给走查结论）。
- **回归**：pure-test 70 files / 732 tests 全绿；clean `assembleHap`（release）
  BUILD SUCCESSFUL（含 native 编译）。

### WLB 真机取证修正（2026-10-26，HUAWEI ALN-AL00 / HarmonyOS 7.0.0.109）

装机 v0.0.10-r03 实测两处缺陷，均已修复：

1. **画面压成全宽横带**（≈1260×709px，严重纵横比失真）：`applyPreviewFit` 旋转输入取了
   `displayDeg()` 单侧（竖屏=0）→ `swapsAxes(0)=false` → 按 16:9 横档内接出横带。native 上屏
   UV 旋转是复合角 `normalize(sensorDeg − displayDeg + stRotDeg)`（`gl_render buildTexCoords`），
   竖屏典型 90−0=90 → 内容轴交换。修正：ArkTS 侧用同一复合角（`info.sensorDeg − displayDeg`
   归一到 [0,360)；**stRotDeg 缓冲矩阵分量 ArkTS 不可读，按 0 处理，登记**——本机 stRot=0，
   实测横带尺寸与 16:9 未交换内接逐像素吻合）。守卫：`接线_画面矩形旋转用复合角`。
2. **姿态仪浮层落到屏幕中部**：包装 Row 上的 `.align(Alignment.TopEnd)` 是无效调用（alignContent
   只对 Stack 生效），浮层被页面 Stack 按 Center 居中。修正：改 `Stack({alignContent: TopEnd})`
   + 全屏 + `hitTestBehavior(None)`（全屏包装层自身退出命中树）。守卫：`接线_姿态仪浮层Stack对齐且不拦触摸`。

同轮顺带修复（非 WLB，CURVE 批回归）：**CurvePopup 根 Stack 全屏常驻吞掉整页触摸**——
内容虽 `if(visible)` 门控，但根层全屏且 Default 命中，作为最顶层兄弟把录制页所有控件
（胶囊/快门/设/库/更多）全部挡死（真机复现：uitest 注入点击「更多」无任何反应，Home 键
注入正常排除通道问题）。修正：根层加 `.visibility(this.visible ? Visible : None)`，
隐藏档整体退出布局与命中树。守卫：`curve-resize.test.ets 隐藏档根层退出命中树`。
共 +3（759 → 762）。

## 交付批（PTS-GRID：编码器 PTS 网格纯逻辑，2026-10-25）

§recorder 行留白「PTS 网格化」的**纯逻辑面**收口（platform 面按本仓纪律留真机批）。
上游缺陷口径（真机取证）：不落网格直接用换帧墙钟 → 帧长不齐、`r_frame_rate` 报成
90000/1；按帧序号取网格 → 隐含「源实到 = 目标帧率」，半精确档（24※）真实 31.85s
录成 34.21s 视频轨。

- **`core/EncoderPts.ets`**（纯逻辑，零平台 import）：
  - `ntscGridStepNs`：24/30/60 → 41708333/33366667/16683333ns（`round(1e9×1001/(1000×fps))`）；
    其余档返回 **0**，调用方必须退回 `1e9/fps`（把 0 当步长会让整段戳并成一枚）。
  - `integerStepNs` / 私有 `slotGridPtsNs`（**落网格算式唯一真源**：退基准 + `elapsed/step` 取整
    + 夹地板；上游「禁两处各写一遍」的同源纪律）。
  - `encoderSlotPtsNs`（名义网格：帧长齐 + 网格位置取自真实时钟 → 总时长真实）。
  - `encoderAdaptivePtsNs`（**形态 B 逐帧接管**：源慢 → `max(base+elapsed, lastPts)` 帧长恒定无空洞；
    源快 → 走名义网格与旧口径逐字相同；段首 `frameIndex<=0` 严格退化）。
  - `encoderSlotGap`（观测：只数不改）+ `measuredFpsCenti`（厘帧/秒，零边界报 0）。
- **登记（真机前置）**：`cpp/codec_recorder.cpp` 现用墙钟戳 + 单调守卫；改网格必须录一段看
  `r_frame_rate` 与帧长分布才能验收 → 平台接线不落地，本批只保证算式层正确且被编译门覆盖
  （`PureLogicBarrel` 探针，不改 signature 串）。
- **测试**：`encoder-pts.test.ets` +12——NTSC 三档逐字与「不进表返回 0」/ NTSC 步长贴真周期
  （683 帧累积差 ≈28.5ms）/ 落网格与夹地板/ 时钟回退与不可用档/ 自适应首帧退化与逐字等价/
  源慢接管（帧长恒定、空档 0）/ 源快走名义网格/ 空档五态/ 实测帧率零边界与 23.7fps 量级/
  落网格算式唯一性守卫。共 +11（710 → 721）。
- **回归**：pure-test 69 files / 721 tests 全绿；clean `assembleHap`（release）
  BUILD SUCCESSFUL。

## 交付批（RISK：同步上游 f941799 风险修复 + DIAG-SHARE 诊断分享，2026-10-25）

上游 `f941799`「高置信风险扫描修复七项」逐项对照鸿蒙面的落地结果，外加 §diag 行
「公共目录导出 / 分享面板」留白收口。

**RISK 逐项对照**：

| 上游项 | 鸿蒙面 | 处置 |
|---|---|---|
| update 请求目标安全门槛（非规范 IP 编码 / IPv6 严格解析） | `UpdatePure.ets` 旧判据（`::ffff:` 只认点分十进制、非 4 段纯数字一律当域名） | **已修**：新增 `isNumericLiteralHost`/`parseIpv4`（禁前导零）/`isBlockedIpv4`/`parseIpv6`（16 字节严格解析、fail-closed）+ `isPublicIpv6` 重写（ULA/链路本地/组播/文档/discard + `::ffff:`/`::/96`/`64:ff9b::` 三种内嵌 IPv4） |
| update SHA-256 读盘异常兜底 + 无期望值短路 | `sha256HexOfFile` 已 try/catch 折空串 | **补短路**：`expectedSha === undefined` 时不算摘要（省 ~5MB 全量读） |
| update 测速剩余预算超时 | 已有墙钟 `setTimeout(BUDGET_MS)` + 读循环 deadline | **已具备**（守卫钉存在性） |
| camera MEND 离屏资源（5 张编码尺寸纹理）两条路释放 | `WotaGlDetachEncoder` 释放 `g_arcMend`；`WotaGlDestroy` 首行转调 Detach | **已具备**（守卫钉两条路） |
| camera `encoderNative` @Volatile | 本侧编码面 attach 走**同步 NAPI 返回 bool**，无跨线程轮询谓词 | **N/A**（架构差异，登记） |
| player 帧率兜底探测移出主线程 | 本侧 `attach(surfaceId, clip, fpsHint)` 直接吃 clip 的 fpsHint，**无阻塞探测** | **N/A**（架构差异，登记） |
| player/TrackSync 删片清同步结果 | `TrackSync` 无 clearFor；`MediaRepo.deleteForever` 不清 sync 键 | **已修**：`TrackSync.clearFor`（纯逻辑，五键清空）+ `SyncPrefsStore.clearFor` + 删片收尾调用 |
| ClipExport/TrackExport 取消也写 sidecar | 本侧取消即 `VideoStore.discard` 删成片（无 pendingUri 冷启救援模型） | **N/A**（模型差异，登记） |
| MediaActions 改名显示名消毒（`/`、`..` 穿越） | 本侧重命名**已裁决不移植**（ID 脱钩），无该路径 | **N/A**（既有裁决） |

**DIAG-SHARE**：`Diag.exportForShare`（落 `cacheDir/share-out`，先清旧分享件）+ `Diag.reportText`
+ `Diag.exportName`；新 `diag/DiagShare.ets`——分享走 `systemShare`（沙箱 URI 经 `fileUri`，
无需 FileProvider 等价物，与 MS 批 `WotaShare` 同口径）、另存走 `DocumentViewPicker.save`
（**用户选址 = 上游「公共 Documents」在沙箱模型下的零权限等价路径**）；设置页诊断区块加
「分享日志 / 另存为」两钮（busy 互斥），全程不联网。

**测试**：`risk-fix.test.ets` +9（clearFor 三例：五键全清回未同步 / 只影响本段 / 键全集与
读写同源；接线守卫六条：SHA 短路、测速预算兜底、补弧两条释放路、删片抹同步记录、
诊断分享与另存）。共 +10（700 → 710）。

**回归**：pure-test 68 files / 710 tests 全绿；clean `assembleHap`（release）
BUILD SUCCESSFUL。运行时走查待办：分享面板拉起、保存选择器、删除后重录同名片的同步清零。

## 交付批（VIS：WIKO 真机安卓版走查 + 视觉对齐，2026-10-25）

WIKO GAR-AN60（Android 11 / 720×1600@320）ADB 实机走查安卓版 WotaGeiCam
（取景/曲线弹窗/媒体库/播放页四屏截图，本地留档 `baseline/wiko-ref/`，gitignore
资产不入库），提炼信息架构差异后以**鸿蒙视觉语言**修订鸿蒙版对应界面（横屏/
Material 元素不照抄，竖屏固定 UO 裁决不动）。

- **修订项**：① 媒体库页签从绿底按钮改**鸿蒙 Tabs 形态**（选中系统蓝强调字 +
  40×2 下划线指示条，`PageColor.ACCENT_BLUE` 新令牌 = 品牌蓝）——对齐安卓页签的
  选中语义；② 媒体库顶栏「返回/时间线/全屏/N列/刷新」裸文本钮改 **× 圆形钮 +
  CHIP 令牌工具胶囊组**（对齐安卓右上工具胶囊组）；③ 取景页右上「设置/媒体库」
  文本入口改 **40vp 描边圆形单字钮**（设/库，对齐安卓右上双圆钮）；④ 「更多」面板
  效果行加**「编辑」可见入口**（点按直开曲线弹窗，与长按等价——对齐安卓取景页
  独立「RGB 曲线」按钮的可发现性）。
- **走查差异登记（不照抄项）**：① 安卓横屏布局（左画面右控制栏）——鸿蒙竖屏固定
  （UO 裁决），Dock/读数条结构保留；② 安卓参数可调值为蓝色——鸿蒙读数条（展示）
  与胶囊（交互）职责已分，不引入蓝色值；③ 安卓右上「分屏」钮——鸿蒙无分屏功能
  （练习动线冻结），不迁；④ 安卓播放页动作图标列——鸿蒙播放页顶栏文本钮保留
  （图标化留后续视觉批，信息架构已一致）。
- **测试**：`vis-align.test.ets` +4（页签指示条与旧绿底移除/× 圆钮与工具胶囊/
  取景双圆钮成对/编辑入口与长按并存）。共 +4（696 → 700）。
- **回归**：pure-test 67 files / 700 tests 全绿；clean `assembleHap`（release）
  BUILD SUCCESSFUL。运行时目检待办（模拟器/真机）。

## 交付批（CURVE：RGB 曲线编辑弹窗 + eb956f5 缩放增强，2026-10-25）

同步上游 eb956f5「RGB 曲线弹窗右缘拖拽等比缩放」。判定：该修订的宿主
（`ui/dialog/CurvePopup.kt` 编辑弹窗）在鸿蒙侧整体未迁（留白「Widgets 全量」），
单独同步缩放无叠加面——本批以**含缩放增强的上游当前形态**为目标态整体移植。
前置盘点：纯逻辑层（CurveModel.ets：ColorCurve/CurveStack encode/decode/bakeBytes +
CurveEdit 编辑器五件）此前批次已全部就绪，GL 侧 native（768B 交错 RGB→纹理 +
setCurveEnabled）完整，缺口 = UI 弹窗 + 持久化 + 接线三件。

- **`ui/CurvePopup.ets`**（新，直译上游 633 行）：全屏透明关闭捕获层（预览不暗化，
  面板 hitTestBehavior(Block) 吞噬）→ 面板卡（surface 底+描边+圆角）＝[通道扇钮左槽 |
  曲线框]：① ChannelFan（中心钮 + 五卫星钮（白/红/绿/蓝/重置），角度 -90/-45/0/45/90、
  半径 64vp、命中盒 48 包 40 视觉钮；选完自动收扇）；② CurveCanvas（ArkUI Canvas 直译
  绘制：网格 2 横 2 竖/y=x 基准/ColorCurve.table(64) 折线/空心点，gpuMode 灰化 dim）；
  ③ 拖点协议（ArkUI onTouch 直译上游 awaitEachGesture：Down 命中/插入 + selected、
  Move moved、Up 删点（二次点按同一点，端点不可删）/落盘；Down 未认领整手不做）；
  ④ **eb956f5 缩放**：panelScale 会话态 1=默认最小只增不减，右缘 22vp 命中条横向
  PanGesture 1:1 增量 coerceIn(1, maxSide/baseSide)，side=baseSide×panelScale 夹
  maxSide，节点（6.5/9）与线宽（1/1.5/2）乘 scale，扇面重置连尺寸复位；尺寸会话级
  不落盘。
- **持久化**：`curve_stack` 键（上游 WotaSettings.KEY_CURVE_STACK 逐字）入
  SettingsStore（CameraSettings.curveStackRaw + load/save）；编码格式 = CurveStack.encode
  四段 `x:y` 分号串（守卫钉住）。
- **接线**：`pages/Camera.ets`——bootstrap 水化 decode；曲线效果档
  `uploadCurveLut(bakeBytes().buffer)` + `setCurveEnabled(!isPassthrough)`（编辑提交即
  重传）；入口 = 「更多」面板效果钮**长按**（上游 onCurveClick 同位；上游曲线与
  frameEffect 正交，本侧效果槽四档循环为 T21 既有形态，登记），切到曲线档一次性
  提示 discoverability；onCommit → 总线态 + applyCurve + persist=true 落盘。
- **简化偏离（登记）**：① 扇钮弹簧/逐枚错峰 → ArkUI 属性动画近似（位置+透明度+缩放，
  无过冲弹簧与 stagger）；② 通道切换曲线 morph 补间不做（直切）；③ 上游逐指针
  isConsumed 让路语义以 hitTestBehavior(Block) 命中隔离替代；④ gpuMode 恒 true
  （本侧预览无 CPU 渲染档）。
- **测试**：`curve-resize.test.ets` +6——上游四例的鸿蒙源码守卫（缩放下限 1/重置复位
  panelScale/side 缩放夹 maxSide/节点线宽乘 scale）+ CurveStack 编解码往返与坏串回
  恒等（curve_stack 持久化格式钉住）。共 +6（690 → 696）。
- **回归**：pure-test 66 files / 696 tests 全绿；clean `assembleHap`（release）
  BUILD SUCCESSFUL。运行时走查待办：拖点跟手/缩放手感/模拟器或真机目检（静态守卫
  已钉全部接线）。

## 修复批（NAPI-DECL：index.d.ts 漂移修复 + 声明守卫，2026-10-25）

v0.0.10-r02 CI HAP 构建失败修复。根因：`entry/src/main/cpp/types/libentry/index.d.ts`
（手写声明）未随 native 加口/签名演进——P27 批给 napi_init exports 表加了探针三口、
T20/T30 给 startClipExport/startArcRepair 挂了可选尾参，声明侧三处没跟上。

- **修复**：补探针三口声明（`startAvScreenProbe(durationMs?: number)`——native 兼容
  0/1 参、缺省 2500 / `getAvScreenProbeStatus()` / `cancelAvScreenProbe()`）；
  `startClipExport` 第 7 位 `audioShiftUs?` 改可选（native 兼容老 6 参调用点，桥即
  6 参）；`startArcRepair` 第 13 位 `capOffsetMs?` 改可选（native 兼容老 12 参，桥即
  12 参）——三方对齐：napi_init.cpp:545-577 描述符表 ↔ index.d.ts ↔ GlLibBridge.ets。
- **守卫**：`napi-decl.test.ets` +4——native exports 表逐名对账（声明缺名即红）、
  幽灵声明反向闸（声明了 native 没有的名字即红）、尾参可选写法逐字、桥调用点元数。
  漂移类缺陷从此有机器闸。
- **口径登记**：本地 hvigor 对 .d.ts 互操作类型检查不触发 CI 的严格口径（本地
  clean 全量构建对新旧声明均绿）——CI 是这类缺陷的唯一裁判；守卫把检查前移到
  pure-test 层补位。
- **回归**：pure-test 65 files / 690 tests 全绿；clean `assembleHap`（release）
  BUILD SUCCESSFUL。

## 走查批（WALK-HM7UI03：模拟器运行时走查，Pura 900，2026-10-24）

**口径**：本节全部结论为「模拟器走查」，不构成真机验收；真机冻结项（T27-2/3/4、
练习动线/SplitPanel、Android BP generateBaselineProfile）不受影响。
**环境**：Pura 900 模拟器（HarmonyOS 6.1.0.126 / API 24 系像 / x86_64 / 1320×2856 竖屏
手机形态），本地签名 HAP（com.wotagei.camohos debug profile）覆盖安装。走查截图本地
留存 `baseline/walk-hm7ui03/`（gitignore 资产区，不入库——仓库无截图入库惯例）。

**逐项判定**：

| 项 | 判定 | 证据/说明 |
|---|---|---|
| DIAG 链（W2-4） | **模拟器走查通过** | 总开关 ON→详细置灰解除（enabled false→true）；关总闸→级联关详细+置灰回归；导出→状态行出现沙箱路径；清除→「日志已清除（当前件/轮转件/崩溃件）」；busy 互斥为静态守卫复核（ui-a11y/diag-settings 守卫，模拟器无法制造竞态） |
| 深色底（W1-7） | **模拟器走查通过**（Camera/Gallery/Settings 三页截图留档） | COLOR_MODE_DARK 强制下三页黑底/深色面板；Player/Compare 不可达（无素材，见差异①）——两页迁移为字面量等值替换（ui-tokens 守卫钉值），登记差异② |
| a11y 语义（W2-2） | **静态守卫复核通过；读屏焦点序待真机复核** | uitest dumpLayout 树不含 accessibilityText 字段（平台限制），模拟器无读屏服务可驱动；ui-a11y.test.ets 四条守卫钉住接线，焦点序与播报顺序留真机 |
| SPEC-T 平板形态（W2-5） | **模拟器不可覆盖，登记差异③** | Pura 900 短边 1320px（≈411vp）< 600vp 断点，系统 bindSheet 居中/480vp 上限的宽形态表现无法在此模拟器产生；裁决不推翻 |
| 翻页起播/RV 旋转/AB 水化回写/离屏 Fit→Cover/翻页窗口期/bindSheet 形态（W1-1/2/3/4/5/6） | **静态守卫复核通过；模拟器走查不可达，登记差异①** | 全部依赖库内视频素材；素材通道三重不可达：① GL 录制 `GPU record failed: encoder-create`（模拟器无 H264 编码器，失败点实测）② shell uid 2000 无提权（su 不存在、setenforce 拒绝）③ 沙箱 DAC 拒绝 hdc file send（drwxrwx--- 属主 20020062）。相关静态守卫：ab-persist/ab-persist(ABP2)/gallery-forms/player-rotate/ui-tokens/sheet-spec 全绿 |
| ABP2 链（W2-3） | **静态守卫复核通过；走查不可达（同差异①）** | 打点→回绕→水化→重建恢复链需素材 |
| 竖屏固定（W1-8） | **静态守卫复核通过；模拟器差异④** | 模拟器无加速度计（既有登记），无旋转激励源；UO 守卫（契约值 6/7 + portrait 默认）钉住实现，运行时复核随真机批 |
| C 更新链路（顺带） | **模拟器走查通过（复证）** | 设置页现场拉起「发现新版本 v0.0.10-r01」弹层与 Release 说明渲染，取消路径正常（此前批次已验，本轮复证） |

**差异项去向**：① 素材通道缺失 → 真机批复核（真机可录制产素材；或后续模拟器镜像带
编码器时补走查）；② Player/Compare 深色底目检 → 静态守卫承担（字面量等值），随真机批
复核；③ 平板/分屏形态 → 平板真机/可变形态模拟器（当前无此环境，平台差异登记）；
④ 旋转激励 → 真机批。**FIX 批：空过**（走查暴露的均为环境限制，非应用缺陷；无缺陷
不造空 commit）。**模拟器运行险情登记**：宿主空闲内存 1.6GB 时 qemu 冷启后死亡
（OOM，与 baseline-env 已录指纹同源）；gradle daemon 停止 + 走查期间不并行宿主构建
后稳定——后续模拟器作业沿用此协议。

## 交付批（SPEC-T：SheetSpec 平板/分屏系统映射裁决，2026-10-24）

HM7-UI-02 步骤 4。把 HM7-1 批「系统面板形态承担」的登记升格为**正式裁决**，防止后续
平板/分屏批重复实现：

- **裁决内容**：`sheetMaxHeight` 的避让语义与 `clampSheetWidth` 的 480vp 宽上限由系统
  `bindSheet` 面板形态承担（`SheetSize.MEDIUM` = 系统半高档，拖拽/避让/居中行为内置；
  手机形态 <480vp 天然合规，平板/折叠屏展开态由系统面板自居中——与 SheetSpec 的
  >600vp 短边断点同语义）。**不二次实现**；消费纪律 = 半模态面板一律走系统
  bindSheet + 尺寸档，禁自绘假面板（守卫钉住 Gallery 消费面 + 60% 假面板不回潮）。
- **SheetSpec 定位**：保留为**规格真源**（上游口径对账/未来自绘面板的唯一依据）；
  裁决写入 `SheetSpec.ets` 头注，任何「补一层数学实现」前必须先推翻裁决并在
  PORT_MAP 登记。
- **测试**：`sheet-spec.test.ets` 消费纪律钉住改写（原「暂无消费方」登记已过时，
  改为钉 Gallery 消费系统 bindSheet + 裁决头注存在）。+0 用例（改写既有 1 条）。
- **回归**：pure-test 61 files / 676 tests 全绿；`assembleHap`（release）
  BUILD SUCCESSFUL。

## 交付批（RV：播放页旋转观看，2026-10-24）

上游 `PlayerScreen` 的旋转观看辅助（竖屏参考片转个角度看；纯视图变换，不碰播放状态）。
PlayerEngine 行 2026-10-21 最小版留白「旋转观看」收口。

- **`player/PlayerModel.ets`**：`nextCcwQuarter` 直译（逆时针四分之一圈计数
  0→3→2→1→0；负数/超界先按 4 折回等价角再走一步——TS `%` 与 Kotlin 同为保号余数，
  语义逐字；四连按必回原角）。
- **`pages/Player.ets`**：`@State quarterTurns`（会话态：不持久化、换片不清零，
  与镜像开关同口径）；镜像钮旁加「旋转:N°」钮（读数 = 计数 × 90，上游度数换算）；
  XComponent 变换链插 `.rotate({ z: 1, angle: quarterTurns*90, centerX/centerY: 50% })`
  （绕图层中心，上游 rotationZ 口径）。
- **登记（平台差异）**：上游 graphicsLayer 的应用序 scale → rotationZ → translation
  （平移最外层，镜像/旋转后拖动方向依然跟手）；ArkUI 图形变换组合序同为 translate
  最外层，链序 scale→rotate→translate 守卫钉住——绘制期行为 JVM/pure-test 桩测不到，
  上游同条注释亦载「只能真机旋转验收实证」，随真机批走查。
- **测试**：`player-rotate.test.ets` +3（主环 0→3→2→1→0、越界/负数折回与四连按回原、
  接线守卫：纯逻辑步进/读数换算/中心旋转/链序/会话态不落盘不重置）。共 +3
  （673 → 676）。
- **回归**：pure-test 61 files / 676 tests 全绿；clean `assembleHap`（release）
  BUILD SUCCESSFUL。

## 交付批（G3b：媒体库全屏滑动形态，2026-10-24）

上游 `GalleryScreen.FullscreenBrowse`（HorizontalPager + 单播放引擎挂当前页）。
需求 29 行三形态的最后一块；G3a 的接续。

- **`pages/Gallery.ets`**：头部三态（非全屏 = 时间线/网格切换 + 全屏入口；全屏 =
  退出全屏）；全屏分支 = `Stack(Bottom)` 底部控制条覆盖 `Swiper`——`ForEach(this.clips)`
  每页一态，**仅 `idx === fullscreenIndex` 的页挂 XComponent(SURFACE)**（Swiper 预渲染
  邻页只出缩略图，全局恒一枚表面，上游「离屏只出缩略图、不绑播放器」同构）；
  `Swiper.index(fullscreenIndex)` 受控 + `onChange` 翻页。
- **单引擎换代**：翻页/表面重建/离开形态/页面销毁统一 `fsAttachSeq++`（在途 attach
  回执作废）+ `fsEngine.release()` + 新实例（引擎 release 后拒绝复活）；新页
  `onLoad → fsSurfaceLoaded` 拿新 surfaceId 挂当前片，attach 成功 `playPause()` 起播
  （上游 `softPause(false)`；新引擎恒不在播，语义等价）。500ms 状态钟回填
  播放态/位置/时长（越界钳末位——回收站动作后列表变短）。
- **控制条**（仅当前片非空时渲染）：播放/暂停、`pos / dur` 读数、收藏（★/☆）、
  分享、回收站/还原（按页签）、对比（`pages/Compare` 同参路由，与播放页顶栏
  `leftId/leftPath` 一致）、播放页。动作复用既有方法（组级扇出/横幅语义不变）。
- **登记（平台差异）**：① 上游翻页是 Compose HorizontalPager 的 page-recycling，
  本侧 Swiper 预渲染 + 条件挂载，翻页瞬间新页短暂显缩略图再上屏（surface 建立窗口）；
  ② 上游离屏页缩略图 `ContentScale.Fit`，本侧 GalleryThumb `Cover`（与库内其余缩略图
  同一组件，构图裁切差异登记）；③ 上游自动播放从翻页即起，本侧从 surface onLoad 起
  （同一时序的 ArkUI 表达，实测路径一致）。
- **测试**：`gallery-forms.test.ets` +2（全屏常量上游同序；单引擎换代/受控 Swiper/
  当前页门控 XComponent/aboutToDisappear 拆解/越界钳位守卫）。共 +2（671 → 673）。
- **回归**：pure-test 60 files / 673 tests 全绿；clean `assembleHap`（release）
  BUILD SUCCESSFUL。

## 交付批（G3a：媒体库列表形态——时间线 + 网格列数，2026-10-24）

上游需求 29 行「按时间线/网格/全屏滑动查看」（`MediaTag.ListMode`，顶栏三键，默认
Grid）。本批落时间线与网格两形态及列数档位；全屏滑动（`FullscreenBrowse`，Swiper +
内嵌播放器）留 G3b。

- **`media/GalleryForms.ets`**（纯逻辑零平台 import）：形态常量（`timeline/grid/
  fullscreen`，默认 grid 上游逐字）；`GALLERY_COLUMN_TIERS=[2,3,4,5]` 缺 2 校验
  （`gallery_columns` 键，上游 `WotaSettings.galleryColumns` 逐字）；
  `groupTimeline` 直译（今天/昨天/同年 `M 月 D 日`/跨年 `YYYY 年 M 月 D 日`，文案
  strings_gallery.xml 逐字；epochDay 走 days-from-civil 纯算式取**本地日历日**，
  上游 Calendar 口径——跨年不误判「昨天」；输入序即组内序不重排，LinkedHashMap
  插入序同构）。
- **`pages/Gallery.ets`**：头部行加「时间线/网格」切换 + 网格态「N 列」循环钮
  （2→3→4→5→2，经 `ParamsStore` 落 `wota_camera_settings` 同盘——上游 WotaSettings
  单文件口径）。时间线 = 日头 List + 78x58 缩略行（信息列点按 = 系列内联展开/收起，
  `＋/－` 徽标带 accessibilityText；展开列 `parts.slice(1)` 缩进子段行，首段不重复
  出行——上游「组内其余成员不单独出行」）+ 动作沿用 `actionRow`；网格 = N 列
  `Grid` + 16:9 紧凑卡（角标 段数/★，`⋯` 钮 + 单卡长按开**动作菜单 bindSheet**）。
- **动作菜单 sheet**（`sheetKind='menu'`）：新分支复用 `actionRow` 原样渲染（组级
  提示行按 `seriesTargets>1` 显隐），7 个动作方法（信息/收藏/回收站/还原/彻底删除/
  分享/存相册）入口统一「菜单打开即收面板」；标签动作本就切 `tags` 分支。
- **登记（视觉适应）**：上游卡片动作走 `[⋯]` 弹出菜单（WotaMenuPopup），本侧
  时间线保留平铺 `actionRow`、网格走菜单 sheet——动作集合逐字一致，承载不同。
  全屏滑动形态见 G3b。
- **测试**：`gallery-forms.test.ets` +8（默认形态/列数档位校验/days-from-civil
  锚点 1970-01-01=0、2000-03-01=11017、2026-10-24=20750/本地日历日 23:30 归当日/
  四类文案/同日合组与空表/页面双形态与列数持久化守卫/菜单 sheet 复用 actionRow
  与 9 处 menu 判定守卫）。共 +8（663 → 671）。
- **回归**：pure-test 60 files / 671 tests 全绿；clean `assembleHap`（release）
  BUILD SUCCESSFUL。

## 交付批（ABP：播放页 A-B 段持久化，2026-10-24）

上游 `PlayerEngine.restoreAb/persistAb`（attach 按 uri 水化 AB 段，打点/循环/清除即写）。
Player 行留白「AB 持久化」收口。

- **`player/AbPersist.ets`**（纯逻辑零平台 import，直译三条算式）：键名上游逐字
  （store `wota_player_ab`、键 `ab_a/ab_b/ab_loop`）；`abKeyOf` = `$prefix:$媒体标识`
  （上游 `$prefix:$uri` 同构，本侧标识=相对路径）；`sanitizeAb` = restoreAb 收整直译
  （损坏/旧版残留 A>B 按 markA 修复口径清 B，loop 只在 A/B 合法时生效）；
  `hasAbRecord` 三键全空=无记录不灌引擎。
- **`player/AbPersistStore.ets`**（平台薄封装）：get → sanitize → 灌引擎、引擎态 →
  put → flush；失败只 Diag.w（load 折 undefined、save 不抛，与 CompareHistoryStore
  同口径）。与 CompareHistoryStore 同文件异键并存（上游同仓先例）。
- **接线**：`PlayerEngine.applyPersistedAb`（会话已有合法 AB 不覆盖）；`Player.ets`
  attach 成功且非 surface 重建（`restoreAb === undefined`）走 `loadPersistedAb`
  （引擎换代/销毁护栏）；markA / markB 成功支 / toggleAbLoop 成功支 / clearAb 四处
  `persistAb()` 即写（失败支不写——状态未变；清除也写，空值落盘防旧段复显）。
- **幽灵留白裁决**：Player 行「剪辑进度持久化」划除——上游播放域持久化全量枚举仅
  AB 三键 + CompareHistory 单键 + TrackSync 五键（`put*` 在 player/ 目录仅命中这三处），
  无剪辑/播放进度持久化；播放位置刻意会话态（surface 重建走内存 restoreSession）。
- **测试**：`ab-persist.test.ets` +10（键名逐字/键编码/收整六态：全空、只有 A、合法、
  A>B 损坏、B==A 边界、循环缺范围钳回关 + 接线守卫两条：页面四处即写与失败支不写、
  引擎不覆盖闸门与 store 薄封装纪律）。共 +10（653 → 663）。
- **回归**：pure-test 59 files / 663 tests 全绿；clean `assembleHap`（release）
  BUILD SUCCESSFUL。

## 交付批（HM7-1：半模态面板迁移 + 鸿蒙 7 原生界面对齐，2026-10-24）

上游对应面：iOS/Material 的 BottomSheet 交互（GalleryScreen 组卡「分段清单」弹层、
ClipCard「标签」弹层）——上游以 ModalBottomSheet 承担，本侧原为内嵌 60% 自绘 Column。
本批把两处弹层迁到鸿蒙原生 `bindSheet` 半模态（SheetSpec 消费面收口）。

- **`pages/Gallery.ets`**：删两个内嵌假面板（`.height('60%')` 自绘 Column），root Column
  挂单条 `.bindSheet(this.sheetKind !== 'none', builder, options)`——`sheetKind` 三态
  （`none/series/tags`）驱动 @Builder `gallerySheetBuilder()` 渲染分段清单或标签管理，
  内容与 CT/GX 批逻辑零改动（只换容器）。Options：`height: SheetSize.MEDIUM`（系统
  半高档）、`dragBar`/`showClose`/`title` 全走系统件、`maskColor` 点遮罩即收、
  `backgroundColor: '#263238'` 显式深色底（与卡片行一致）。
- **SheetSpec 消费面登记**：`height` 档位与拖拽行为由系统 bindSheet 承担（SP 批
  `sheetMaxHeight` 的「屏高避让顶栏」语义即系统默认）；`clampSheetWidth` 的 480vp 宽上限
  在手机形态天然满足、平板形态系统面板自居中——纯算式不再二次实现，守卫钉住消费纪律
  （见下）。
- **鸿蒙 7 原生界面对齐**：深浅色 = 深 only（EntryAbility 强制 `COLOR_MODE_DARK`，
  面板深色底显式声明）；字号沿用页内字阶 10/11/12fp（HudTokens 同源口径）；圆角/间距
  沿用 6vp/12vp 页内节距；**无障碍**：分段行与「移除」按钮补 `accessibilityText`
  （读屏念出段序号/名称/时长与标签名——原来 Row/Button 子文本拼读不出动作语义）；
  竖屏策略沿用 UO 批（portrait 固定，半模态天然竖屏形态）。
- **幽灵留白裁决（登记两处转录误植）**：Gallery 行「全屏手势锁定」、Player 行
  「全屏手势锁定/字幕」——上游全仓无此功能：`git grep -i "手势锁定"` 零命中；
  `git grep -i subtitle` 仅命中 CameraDialogs.kt:228 / GalleryScreen.kt:848 的弹层
  副标题参数 `subtitle: String?`（UI 文案样式），非视频字幕；PlayerScreen 手势清单
  只有 点按显隐 / TapArbiter 双击启停 / 双指缩放平移，无锁定手势。已从两行留白划除；
  Gallery 行「时间线/网格/全屏滑动切换」核实为真实功能（`MediaTag.ListMode` +
  `FullscreenBrowse`），维持留白。
- **测试**：`custom-tags.test.ets` +1 守卫（bindSheet 接线纪律：原生 bindSheet 存在、
  MEDIUM/dragBar/showClose/深色底、分段行与移除按钮 accessibilityText、旧 60% 假面板
  必须移除）。共 +1（652 → 653）。
- **回归**：pure-test 58 files / 653 tests 全绿；clean `assembleHap`（release）
  BUILD SUCCESSFUL。

## 交付批（SP：半模态尺寸纪律 SheetSpec，2026-10-24）

上游 `ui/design/SheetSpec.kt`（半模态面板尺寸口径单一真源；§ui 行「SheetSpec 全量留白」
的纯算式面收口）。**零平台 import**：全部算式参数化（shortSide/windowHeight/topInset 由
调用方运行时读入），可在 test/pure 钉住。

- **常量**（上游逐字）：宽 480vp 上限、最小高 320vp、最大高=短边 90%、距信号栏 8vp、
  弹窗内间距 12vp、>600vp 短边转居中、顶栏兜底 48vp。
- **`sheetMaxHeight(shortSideDp, windowHeightDp, topInsetDp)`**：短边 90% 与
  「窗口高 − max(inset+8, 48)」取小、下限 0。上游真机实测锚点（横屏 1600x720 density 2 →
  ≈278dp）以参数化形式钉住（shortSide=windowHeight=326）。
- **`clampSheetWidth` / `centeredForShortSide`**：宽上限与 >600vp 短边居中断点
  （2026-10-02 裁决：输入是短边、运行时取，不按方向写死）。
- **消费方登记**：本侧暂无半模态面板消费方（Gallery 分段清单是内嵌 Column）——纯逻辑
  先行，半模态面板批（含 BottomPanel 对应物）落地时直接消费。
- **测试**：`sheet-spec.test.ets`（6：常量逐字、横屏真机 ≈278 锚点、竖屏短边 90% 绑定、
  顶避让 max 语义、退化到 0、断点判定）。共 +7（645 → 652）。
- **回归**：pure-test 58 files / 652 tests 全绿；clean `assembleHap`（release）BUILD SUCCESSFUL。

## 交付批（CT：自定义标签页，2026-10-24）

上游 Gallery 自定义标签（需求「标记 tag 无数量限制，需要有按分类的标签页」）的接线：
`media/MediaTag.TagSpace`（normalize/isCustom，T20 期已落）+ `MediaRepo` 通用 tag 变异
（insertTag/deleteTag，DB 批已落）早已备齐——缺的只是 Gallery 的派生页签与标签弹层。

- **派生**（`media/MediaTag.ets` 增 `CustomTags.ofRows`）：media_tag 行 → 过滤系统保留
  （like/trash 及大小写变体）→ 按 createdAt 升序 → 去重。页签顺序 = 首次使用时间（稳定，
  不随刷新跳位）。
- **MediaRepo**：`addTag(clip, rawTag)`（TagSpace.normalize 守卫：去空白/≤24 字/不含保留词；
  非法返回提示不静默截断；insertTag 带 createdAt）/ `removeTag(clip, tag)` / `customTags()`
  （经 `CustomTags.ofRows` 派生）。
- **Gallery**：
  - 页签行 = 系统 3 页签 + 自定义页签（`customTags` 派生；「分段合并」开关旁的页签行同一
    ForEach 渲染）；自定义页签缩后 `clampedSelection` 矫正选中（上游调用点同款）；
  - 卡片动作行加「标签」按钮 → **标签弹层**（上游 MediaTagDialog 对应物）：TextInput 加标签
    （normalize 守卫）+ 现有自定义标签清单逐条移除；刷新后按路径找回 clip 更新清单；
  - 加/移除后 `refreshAll`（页签表随 media_tag 行即时增减）。
- **决策（登记）**：标签重命名不迁（同重命名决策——ID=路径哈希，改名必坏账）；自定义页签
  的删除（无片引用的空页签随 media_tag 清理自动消失，无需手动管理页签表）。
- **测试**：`custom-tags.test.ets`（4：派生排序/过滤/去重、空表、Gallery 派生页签与弹层
  接线守卫、三上游键名/函数守卫）。共 +4（641 → 645）。
- **回归**：pure-test 57 files / 645 tests 全绿；clean `assembleHap`（release）BUILD SUCCESSFUL。
- **模拟器待验**：加自定义标签后页签即时出现、切页签过滤正确、移除标签后空页签自动消失。

## 交付批（TFC：取景页 tap-to-focus，2026-10-24）

上游「点按预览 = 对焦 + 测光」的手势接线（§ui 行「tap-to-focus 留 T32 手势分派批」收口）。
下发链零改动：`params.focusPoint` → RequestApplier → `setFocusPoint`/`setMeteringPoint` 双驱动。

- **`core/FocusTap.ets`**（纯逻辑，零平台 import）：`pointFromWindowTap(windowXVp, windowYVp,
  winWvp, winHvp)`——窗口 vp 坐标归一化 [0,1]，钳到 [2%, 98%] 边缘余量（焦点标记不被
  系统栏裁掉一半）；窗口尺寸非法/坐标非有限返回 undefined（不造伪点）。
- **Camera 页**：onClick 挂在 **HUD 列**（Transparent）上——空白区点击由列消费、
  按钮子节点各自消费（内层手势优先，无双触发）；**XComponent(SURFACE) 的 onClick 不触发
  （平台行为，模拟器实证，登记）**——初版挂它无效后移到 HUD 列。门控
  `cameraStarted && glFatal===''`；点按写 `params.focusPoint` → `applyParamsFromPills` 下发；
  Diag 留痕归一化坐标；56vp 圆环标记在点按处 1200ms 自动隐（position 定位 + hitTest None）。
- **手势冲突分析（回应登记）**：相机页**没有** Player 那套「单击显隐 HUD/双击启停」——
  预览区此前零点击处理，无仲裁冲突；实际冲突是与兄弟浮层（姿态仪模式切换）的 z-order
  竞争，由 HUD 列 Transparent + 内层手势优先裁决（姿态仪点击=切模式，不触发对焦）。
- **tapPoint 生命周期**：applyAbility 换镜头即清（activeArray 变化坐标语义失效，既有语义）；
  点按对焦在录制中保持可用（AF 连续对焦不因录制中断）。
- **测试**：`focus-tap.test.ets`（4：归一化中心/边缘钳制/非法尺寸 undefined + 接线守卫——
  XComponent→HUD 列挂载点、focusPoint 写总线、门控、标记自动隐、HUD 列 Transparent 与
  标记 z 序）。共 +4（630 → 634）。
- **回归**：pure-test 55 files / 634 tests 全绿；clean `assembleHap`（release）BUILD SUCCESSFUL。
- **模拟器验证 ✓（hilog 实证）**：点按预览中心 → `focus tap norm=(0.500,0.420)`；右下角外
  点按 → `norm=(0.980,0.980)`（边缘钳制生效）。真机待验：setFocusPoint 对真实镜头的
  对焦效果与测光联动（模拟器镜头为虚拟源）。

## 交付批（ST：设置数值档收口，2026-10-24）

上游 WotaSettings 里剩下的**数值档设置**一次收口：`compare_tick_ms`（对比页编排节拍）、
`text_scale_camera`（取景字体缩放）、`diag_enabled`/`diag_verbose`（诊断两开关持久化）。

- **`core/UserSettings.ets`**（纯逻辑，零平台 import）：`compareTickMsOf`（缺键 200、钳
  100..1000——上游口径逐字；非有限数走缺键语义）、`textScalePctOf`/`textScaleFactorOf`
  （缺键 100、钳 80..120、产出 pct/100 倍率；档位 [80,90,100,110,120] 与上游逐字）。
- **SettingsStore**：四新字段（`compareTickMs`/`textScaleCameraPct`/`diagEnabled`/
  `diagVerbose`，键名与上游逐字）+ load/save + 三个冷启同步读口
  （`compareTickMsSync`/`textScaleCameraSync`/`diagPersistSync`）+ `persistDiagFlags` 写口。
- **消费方**：
  - Compare 页：`tickMs` 字段替代写死 `TICK_MS=120`（**缺键默认登记偏离**：上游 200、
    本侧缺键回历史口径 120），aboutToAppear 同步读；
  - Camera 页：`hudFontScale` = 系统 fontSizeScale × 用户倍率（textScaleFactorOf）——
    J3a 的 hudPerRowFor 宽度账自动随档位进退；
  - Index 页：详细档按钮（`详细开/关`）+ 冷启回填诊断两开关 + `persistDiagFlags` 落盘
    （Diag.install 改吃持久化两开关——T16 期「诊断持久化留白」收口）。
- **设置页**：「对比页节拍」四 chip + 「取景字体缩放」五 chip（界面方向之前）。
- **决策（登记）**：① `text_scale_settings`/`text_scale_dialog` 不迁——两页字号是 T31
  真机取证的固定值（11/12/13/17fp），逐页缩放=重排验证成本大、收益低；② `motion_mode`
  不迁（本侧无 LiquidMerge 动效链）；③ `gallery_columns` 不迁（媒体库是列表非网格，
  与 ID/缩略图交互绑定，随网格画廊批再议）；④ compare_tick_ms 缺键 120 偏离上游 200
  （保留本侧既有实测口径）。
- **测试**：`user-settings.test.ets`（5：节拍钳制/档位字面量/缩放钳制/倍率换算 + 接线守卫——
  四键名、三同步读口、三消费方、旧常量替换）。共 +5（625 → 630）。
- **回归**：pure-test 54 files / 630 tests 全绿；clean `assembleHap`（release）BUILD SUCCESSFUL。
- **模拟器待验**：节拍/缩放档切换后对比页与取景页的实际变化（纯 UI 观察，低风险）。

## 交付批（FL：对焦锁定，2026-10-24）

上游「对焦锁定」（用户反馈第 2 条：双击取景=该点锁定、长按半秒=解锁重对焦、对焦胶囊
副控制条目、锁定期轻触/双击不重焦）的接线。前置 TapPoint/focusPoint 双驱动链已备（TFC）。

- **下发**（`camera/RequestApplier.ets`）：`focusLocked` → OHOS 原生
  **`FOCUS_MODE_LOCKED`**（OHOS 有直接等价物——镜头停在合焦位不排恢复，**非**上游
  Camera2 的「AUTO 覆写 + TRIGGER 单发」三段式；语义对齐：镜头锁定直到显式解除）。
  能力闸进闸现读：`isFocusModeSupported(LOCKED)` 为 false → skipped（拒绝假开关，上游 R3a）。
  锁定时锚点经 `setFocusPoint` 下发；未锁路径 `focusModeFor(afMode)` 不变。
- **生命周期**（`core/WotaParams.applyAbility`）：相机整套重开（换镜头/切渲染/离开取景页
  再回）= **清锁唯一落点**（锚点随锁一起失效，未锁定时不触碰——上游 Q4 同口径）。
- **Camera 页三击派发**：单击=对焦、**双击=该点锁定**、**长按半秒=解锁**；锁定期轻触/双击
  不重焦、首次轻触给提示（`lockedTapTipShown`，每次上锁重置）；仲裁态机
  `FocusTapArbiter.onTapUp`（280ms 窗口，长按后 reset 不误判）。
- **对焦胶囊**：「锁定对焦/解除锁定」条目经宿主页 `onToggleLock` 过闸（胶囊不直写总线绕过
  门禁）；MANUAL 门：手动对焦档本身即锁定（提示不置锁）；录制门：录制中不锁（已锁可解锁）。
- **平台偏离登记**：① OHOS LongPressGesture/GestureEvent **不含坐标字段**（SDK d.ts
  核实）→ 长按解锁**不重对焦**（上游在长按点 focusTapAt）——解锁后用户连续对焦档自动
  接管，语义损失登记；② 锁定下发用原生 LOCKED 而非上游三段式。
- **测试**：`focus-lock.test.ets`（7：派发语义 2/长按重置/WotaParams 清锁唯一落点 +
  24 键排除清单/LOCKED 能力闸与锚点下发/相机页三击与胶囊条目守卫）。共 +7（634 → 641）。
- **回归**：pure-test 56 files / 641 tests 全绿；clean `assembleHap`（release）BUILD SUCCESSFUL。
- **模拟器验证 ✓（hilog 实证）**：双击锁定后轻触不产生新对焦日志（不重焦生效）；长按解锁
  后点按重新出现对焦日志（连续对焦接管）。真机待验：FOCUS_MODE_LOCKED 对真实镜头的合焦
  保持、录制约束、锁定期提示文案的视觉呈现。

## 交付批（UO：界面方向/竖屏固定，2026-10-24）

上游 `core/UIOrientation.kt` + `WotaSettings.KEY_UI_ORIENTATION` + 设置页 BlockUiOrientation 的
接线（§ui 行「~~UIOrientation 接线~~（UO 批已落）、~~text_scale_camera~~（ST 批已落）、~~compare_tick_ms~~（ST 批已落）」留白收口；用户点名项）。

- **core/UIOrientation.ets 重写为纯逻辑**（原版 import window——违反 core 纪律）：
  `preferredOrientationCodeOf` 产出 **OHOS window.Orientation 契约数值**
  （AUTO_ROTATION_PORTRAIT=6 / AUTO_ROTATION_LANDSCAPE=7，SDK d.ts 核实）——与上游
  「产出 ActivityInfo 编译期 Int」同构；窗口调用在 WindowChrome 串行链里
  （`setPreferredOrientation(code as window.Orientation)`）。
- **默认偏离决策**：上游出厂 LANDSCAPE（横屏优先）→ 本侧 **PORTRAIT（竖屏固定）**——
  鸿蒙 HUD 是 T31 真机取证后的竖屏优先设计，默认横屏会破坏 T31 布局；用户可切横屏
  （横屏 HUD 保真度属 T32）。守卫钉住默认档防"顺手对齐上游"。
- **SettingsStore**：`uiOrientation` 字段（键 `ui_orientation` 与上游逐字一致；
  load 经 `fromPersistValue` 收口坏串/大小写；`uiOrientationSync` 冷启同步读）。
- **WindowChrome**：`applyOrientation(win, o)` 进串行链（与 prime/camera/standardDark
  同链防竞态）；窗口级 sticky 一次设置全页生效；失败只记日志。
- **EntryAbility**：`onWindowStageCreate` 在 prime 后按持久化档位落方向（`uiOrientationSync`）。
- **设置页**：「界面方向」分组（取景读数之前）：竖屏固定 / 横屏两 chip，点选即写 prefs +
  立刻落当前窗口。
- **测试**：`ui-orientation-wiring.test.ets`（5：persistValue 契约串/fromPersistValue 大小写
  与坏串收口/**默认档 PORTRAIT 钉子**/契约数值 6/7 手写字面量 + 三条源码接线守卫——键名、
  冷启链、设置页落窗）。共 +5（620 → 625）。
- **回归**：pure-test 53 files / 625 tests 全绿；clean `assembleHap`（release）BUILD SUCCESSFUL。
- **模拟器验证 ✓（hdc+uitest）**：冷启窗口 1320×2856（竖屏固定默认生效）→ 设置点「横屏」→
  **窗口翻转为 2856×1320** → 点「竖屏固定」→ 回到 1320×2856。双向即时生效实证。

## 交付批（P27：T27-1 平台探针 + 真机预检脚本，2026-10-24）

T27 真机批的第一步：**取证工具先于被取证的功能落地**（探针本身就是为了拿真机证据）。

- **`cpp/avscreen_probe.h/.cpp`**（native，链接 `libnative_avscreen_capture.so`——sysroot
  实名，`libnative_media_avscreen_capture` 是编译期纠正过的错名）：Create → SetCallback
  （since-10 三件套：onError/onAudio/onVideo）→ **SetMicrophoneEnabled(true) + 内录源
  OH_ALL_PLAYBACK（双源同采取证配置）** → Init → StartScreenCapture → 计数 500..10000ms
  （默认 2.5s）→ Stop → Release。音频缓冲取后立刻 ReleaseAudioBuffer（不还卡死回调）、
  视频缓冲只取还不计数（探针只要音频）。每步错误码原样进状态串，**不解读**（真机取证原则：
  失败形态本身就是结论）。
- **napi 三口**（`startAvScreenProbe/getAvScreenProbeStatus/cancelAvScreenProbe`）+
  `camera/GlLibBridge.ets` 三桥 + `record/AvScreenProbeRunner.ets`（轮询到终态、20s 上限、
  页面销毁取消兜底）。
- **`record/AvScreenProbeModel.ets`**（纯逻辑）：状态串解析 + 三条取证结论判读——
  `DUAL_SOURCE`（双源都出数据，T27-3 可行）/ `SINGLE_SOURCE`（单轨）/ `SILENT_WINDOW`
  （窗口无回调，要求带声源复测再下结论）/ `BLOCKED`（Init/Start 失败 = 隐私弹窗拒绝路径的
  错误码形态，取证有效）/ `CANCELLED`/`FAILED`。错误码与 `OH_SCREEN_CAPTURE_STATE_*` 序列
  原样保留（真机日志对表用）；`summary()` 一行读数含复测指引。
- **入口**：Index 开发者页「AVScreenCapture 探针」按钮（默认隐藏，版本号 7 连点可达）；
  运行前提示「可能弹系统确认框——弹窗形态正是取证目标」。
- **`scripts/device-check.mjs`**：真机预检（hdc 发现/设备信息/安装态/签名包存在性/签名冲突
  处置指引 a|b）+ `log` 模式（hilog 实时）+ `check` 模式（23 条待验清单的操作索引，离线可打）。
- **测试**：`avscreen-probe.test.ets`（6：解析全表四态/坏段容错/summary 读数 + 接线守卫——
  native 三口、双源配置、缓冲取还、CMake 实名链接、页面按钮与销毁兜底）。共 +6（614 → 620）。
- **回归**：pure-test 52 files / 620 tests 全绿；clean `assembleHap`（release）BUILD SUCCESSFUL
  （native 重编通过，链接库实名纠正由守卫钉住）。
- **真机取证步骤**（设备到位后 turnkey）：`node scripts/device-check.mjs` 预检 → 装包 →
  Index 页点探针 → 读 `summary()`（双源/单源/被拒三态其一即 T27-2/3 定案依据）→
  `hdc shell hilog` 对错误码表。

## 交付批（GX：媒体库收尾 + 两项平台裁决收口，2026-10-24）

媒体库三条留白的收口（两条代码 + 一条决策），并把 §core/StorageAccess、T27 两条 ☐ 行
落成显式裁决——PORT_MAP 不再有悬而未决的「暂缓」项。

- **分段展开**（组卡长按 500ms →「分段清单」面板）：只钉 seriesId、按当前 clips 重解析
  （上游 SeriesSheet 同款纪律：不吃快照）；每段一行（第 N 段/文件名/时长·大小），点按直接
  开该段播放页；面板注记「组级动作作用于全部段」。
- **折叠回退开关**：「分段合并」Toggle（会话态不持久化，关 = `galleryEntries(clips, false)`
  的上游扁平基线，`SeriesGroup` 的 collapse 参数终于有 UI 消费方）。
- **重命名决策：不迁**——媒体 ID = 相对路径 32 位 FNV-1a，改名即换 id（标签/回收站/系列
  成员失联）；前置 = id 架构升级（MediaRepo 既登记），升级前做必坏账；守卫钉住无重命名入口。
- **StorageAccess 决策：不移植**（MS 批调研定版，行状态 ☐→☑ 不移植）。
- **T27 决策：真机批定版**——native AVScreenCapture 头文件在册（sysroot 核实），缺口是
  隐私弹窗形态与双源行为（只能真机取证）；行内写入前置条件，不再是无期挂起。
- **测试**：media-share-wiring.test.ets 增 2（折叠开关/长按清单/重解析接线守卫 +
  重命名不迁决策守卫——FNV-1a 代码面为据）。共 +2（612 → 614）。
- **回归**：pure-test 51 files / 614 tests 全绿；clean `assembleHap`（release）BUILD SUCCESSFUL。
- **真机待验**：长按手势与列表滚动的冲突；分段清单在多段（>4）组内的滚动。

## 交付批（FC：对焦胶囊 + tint 决策，2026-10-24）

上游 `CameraPills` 的 FOCUS 弹层移植 + tint 副轴的平台裁决（一起收口 §ui 行两条留白）。

- **FocusPillPopup**（`ui/ParamPills.ets`）：AF 模式 PillChoices（连续=CONTINUOUS_VIDEO /
  手动=MANUAL，手动档在 range 缺失且 ability 在册时禁选）+ MANUAL 时屈光度 PillSlider
  （`diopterLadder(0, maxDiopter)`——T22 备好的梯首次有消费方；`snapKey: SliderKey.DIOPTER`
  挂吸附开关；能力闸：`manualFocusDiopter.value.range` 缺失（定焦头/探针不支持）不显示滑杆、
  只留注记——与 RequestApplier 的 `focusDistance: not supported` skipped 桶同源）。
- **Dock**：第二行第 5 颗「对焦」（`HudSpec.DOCK_ROW2` +focus；标签随 afMode 显 连续/手动）；
  `RECORDING_EDITABLE_PILLS` = {iso, ev, wb, **focus**}（对齐上游 {ISO,EV,WB,ZOOM,FOCUS}
  减无弹层的 ZOOM——本侧变焦是 Dock 直循环，录制门控不适用，登记）。
- **下发链已备**（零改动）：RequestApplier 的 `setFocusMode(focusModeFor(afMode))` +
  MANUAL 时 `setFocusDistance(1 - diopter/max)`（归一化域）。
- **tint 副轴决策：不迁**——OHOS 手动白平衡 `setWhiteBalance(kelvin)` 单值、无 RGB 增益
  （rggb）接口（RequestApplier 头注登记在案）；造假滑杆比没有更糟。守卫钉住
  WbPillPopup 里不许出现 tint 控件（focus-pill.test.ets）。
- **tap-to-focus 留白（登记）**：`setFocusPoint` 下发链已备（tapPoint 同时驱动 focus/测光），
  但取景页单击手势当前无空闲（HUD 显隐/双击启停的仲裁链），点按对焦需先做手势分派裁决，
  留 T32 相机交互批（与捏合变焦同批）。
- **测试**：`focus-pill.test.ets`（5：AfMode 契约值、diopterLadder 档、dock/录制可调集接线、
  弹层能力闸接线、**tint 不迁守卫**）+ slider-snap 的调用点计数 4→5（第五根滑杆点名 DIOPTER）。
  共 +5（607 → 612）。
- **回归**：pure-test 51 files / 612 tests 全绿；clean `assembleHap`（release）BUILD SUCCESSFUL。
- **真机待验**：手动对焦距离在 API24 机型的实际生效（探针门）、定焦头（前摄）的降级注记、
  对焦弹层在录制中的可调性。

## 交付批（RT：录制长时任务，2026-10-24）

上游 `record/CaptureFgService.kt` 的 OHOS 对应物（调研报告 §三：长时任务 + 系统代发通知；
本质是录制链的保活包装件，提前随视频录制落地，不等 T27-3）。

- `record/RecordTaskGate.ets`（纯逻辑）：起/停状态机——`onStart/onStop` 返回**是否迁移**，
  重复调用（健康重录段、finishRecord 多终态汇流）一律不再碰系统接口（上游守卫同族）。
- `record/RecordTask.ets`（平台）：`startBackgroundRunning(ctx, TASK_KEEPING, wantAgent)` /
  `stopBackgroundRunning`；wantAgent 指回 EntryAbility（通知点击回取景页）；全部失败只
  `Diag.w`（**绝不阻塞录制**——前台录制不需要长时任务，退化的只是切后台续录）。
  WantAgent 为模块具名类型导出（编译期踩中 `wantAgent.WantAgent` 不存在，改为 kit 具名导入）。
- Camera 接线：手动起录成功 → `RecordTask.start`；`finishRecord`（所有停录终态的汇流点）→
  `RecordTask.stop`；健康重录段走闸的幂等分支（不起第二个任务）。
- **测试**：`record-task.test.ets`（5：闸迁移语义 3 + 平台件/接线守卫 2——TASK_KEEPING 判据、
  起停两处接线、权限声明、无 ForegroundService 仿写）。共 +5（603 → 607）。
- **回归**：pure-test 50 files / 607 tests 全绿；clean `assembleHap`（release）BUILD SUCCESSFUL。
- **真机待验**：应用通知关闭时 9800006 的降级表现；录制中切后台是否真续录（TASK_KEEPING 模式
  核验）；长时任务通知形态（系统代发，无自定义停止钮）。

## 交付批（BT：蓝牙音箱面板减法版，2026-10-24）

上游 `bt/BtSpeakerController.kt`（516 行）+ `ui/dialog/BtSpeakerPanel.kt`（376 行）的**减法移植**
（调研报告 §1 定版：列表/连接/电量有等价甚至更优，音量写与播控无三方等价）。

- `bt/BtSpeakerModel.ets`（纯逻辑）：`mergeEntries`（配对表 × A2DP 连接表：去重按 deviceId、
  连接表有而配对表无的补入、**已连接置前其余稳定保输入序**——中文名字节序无意义且重排会跳位）、
  `batteryText`（-1/越界 =「电量 —」，0% 是合法低电不许混写）、`stateText`。
- `bt/BtSpeakerController.ets`（平台）：配对/名字/A2DP 连接/电量全链 + `batteryChange` 实时刷新；
  一切异常（蓝牙关 2900003 / 能力 801 / 服务未起）折成引导态或空表。**BatteryInfo 无 deviceId**
  （编译期抓获，SDK 只有 batteryLevel + 耳塞三仓组）→ 回执按「首个已连接设备」合并（登记）。
- `pages/BtPanel.ets`：列表（名/电量/连接徽标）、刷新、蓝牙未开引导、页脚减法说明；
  订阅挂/解与页面生命周期同进退。入口：「更多」面板「蓝牙 → 音箱…」（先收面板再跳页）。
- **测试**：`bt-speaker.test.ets`（8：合并去重/补入/连接置前/稳定序、电量文案边界、
  两条源码接线守卫——含「不许出现 setVolume/avsession/startBluetoothDiscovery」的减法红线
  与 ACCESS_BLUETOOTH 声明检查）。共 +8（595 → 603）。
- **回归**：pure-test 49 files / 603 tests 全绿；clean `assembleHap`（release）BUILD SUCCESSFUL。
- **真机待验**：无蓝牙能力机型（801）的引导态、电量刷新实时性、双 A2DP 连接场景的合并行为。

## 交付批（MS：分享/缩略图/存入相册，2026-10-24）

上游 `media/Share.kt` + `MediaRepo.ThumbLoader` + `VideoStore` 入库面在 OHOS 的对应物
（调研报告 §二的 MS-1/2/3 三批一次落地；`StorageAccess.kt` 改判不移植）。

- **MimeMap**（`media/MimeMap.ets`，纯逻辑）：`extOf/mimeOf/utdOf/titleOf`——上游手写 MIME 表
  逐条直译（OHOS 无全局 MimeTypeMap，只用表，登记）；UTD 是 systemShare 的类型语言
  （SDK 核实：mp4 一族 `general.mpeg-4`、其余 `general.video`；**没有** `general.mp4`）；
  `titleOf` 去扩展名（`PhotoCreationConfig.title` 与 `fileNameExtension` 分开拼，防双后缀）。
- **分享**（`media/WotaShare.ets`）：`@kit.ShareKit` 的 systemShare 面板——单发/多发映射
  `SelectionMode.SINGLE/BATCH`，record = {utd, uri(fileUri.getUriFromPath), title}。
  与上游差异（登记）：FileProvider 白名单与 `FLAG_GRANT_READ` 不需要（面板接管目标应用授权）。
- **缩略图**（`media/ThumbLru.ets` 纯逻辑 + `media/ThumbLoader.ets` 平台）：
  `AVImageGenerator.fetchFrameByTime(中点帧, CLOSEST, {w,h})`（since 12 在兼容面内），
  fd 生命周期 finally 收口、单条 6s 超时退占位、并发限 2（上游 Semaphore(2)）、
  LRU 32 **驱逐不 release**（PixelMap 可能正在 Image 上显示，显式 release 会让在显图变空——
  登记偏离，交 GC）。上游主路径 `loadThumbnail`/`PhotoAsset.getThumbnail`（since 22）不可用。
- **存入相册**（`media/AlbumSaver.ets`）：`showAssetsCreationDialog`（since 12、**无权限**、
  系统确认框）——与上游「入库不打扰」有差异，故做成 **Gallery 显式动作**而非录制收尾自动入库
  （决策）；上游 IS_PENDING 两段式在「沙箱写完→整文件入库」模型下无对应问题域（登记）。
  字段名按 d.ts 实名（`fileNameExtension`/`photoType: PhotoType.VIDEO`，调研稿写的 `extension`
  有误，编译期抓获）。
- **Gallery 接线**：卡片缩略图（`GalleryThumb` @Component：占位块回退、`@Prop @Watch` path
  重载防列表复用串图、按代序丢弃迟到回执）；动作行加「分享」「存入相册」，与收藏/回收站同走
  `expandSeriesTargets` 组级扇出；`this.ctx` 字段化（分享面板要 UIAbilityContext）。
- **测试**：`media-mime-thumb.test.ets`（12：MIME 表逐条/兜底、UTD、title、LRU 命中提升/
  容量/重写不占位/clear/容量夹紧）+ `media-share-wiring.test.ets`（4：三条链单一真源与调用
  形态、Gallery 入口与扇出、**零新增权限红线**、createAsset since 22 禁用）。共 +16（583 → 595）。
- **回归**：pure-test 48 files / 595 tests 全绿；clean `assembleHap`（release）BUILD SUCCESSFUL。
- **真机待验**：systemShare 面板对沙箱 file:// URI 的目标应用可读性（单发/多发）；
  `fetchFrameByTime` 对 24/25fps 光弧成片的中点帧精度与耗时；确认框取消/失败的实际形态。

## 交付批（J3b：取景读数顺序编辑器，2026-10-24）

读数条的**顺序**接进 `hud_layout` 位置表——顺序用上游同一份 v2 契约串持久化，
将来若做完整编辑器，存量数据直接可用。显隐仍走 `hud_items`（J3a，不造第二套）。

- `core/HudLayout.ets` 增 `HudLayoutTable.withZoneOrder(zone, order)`：只改单容器条目顺序
  （位置/格子不动），重建 ZoneState 后过 `normalize`（去重/丢非法/缺项按 home 区补尾）。
  编辑器只有同容器上下移，没有跨容器搬运与格子钉扎，不走 `moveEntryTo` 的完整四步（登记）。
  同步订正文件头注释（J1 期写的「placeEntryAt/moveEntryTo 未移植」已过时——J2 已落）。
- `ui/SettingsStore.ets` 增 `hudLayout: string`（键 `hud_layout`，与上游逐字一致；空串=默认表；
  串本身的收口在 `HudLayoutTable.decode`：坏段丢弃/整表回默认）。
- `pages/HudEditor.ets`（新页，main_pages.json 登记）：READOUT 七档列表，每行 上移/下移 +
  显隐 Toggle + 「恢复默认顺序」；顺序经 `withZoneOrder`→`encode` 落盘，显隐经 `HudItems.toggle`；
  保存串行化与设置页同款。设置页「取景读数」分组加「调整顺序 →」入口。
- `pages/Camera.ets`：读数条顺序改由 `readoutTable().visibleOrderOf(READOUT, 可见 id 集)` 出
  （表按原文串缓存，onPageShow 重读设置即自动失效）；**默认表（空串/坏串）的 READOUT 顺序
  = HudItem 枚举声明序 ⇒ 与 J3a 行为逐字等价**（用例钉住）。换行档/分组算式不变
  （`hudPerRowFor` 喂可见条目数 `order.length`）。
- **设计性偏离（决策）**：上游「编辑控件」页的自由拖拽（换区/落格/`cellDropOf` 一族消费方）
  不在本侧复刻——固定 HUD 是 T31 真机取证后的设计；`hud_pills` 胶囊显隐掩码同理不接
  （本侧 Dock 2×4 是适配结构，13 颗 CamPill 的左右竖 Dock 未复刻）。
- **测试**：`test/pure/hud-layout-order.test.ets`（6 例：`withZoneOrder` 经真编解码往返且不动
  其它容器 / 残缺+重复顺序的 normalize 收口 / `visibleOrderOf` 顺序跟表走（与枚举序区分）/
  默认表=枚举序回归线 / 编辑页与相机页源码接线守卫 ×2）。共 +6（577 → 583）。
- **回归**：pure-test 46 files / 583 tests 全绿；clean `assembleHap`（release）BUILD SUCCESSFUL。
- **真机待验**：编辑页上下移后返回相机页顺序生效；隐藏再显示位置保持；恢复默认顺序。

## 交付批（J3a：hud_items 可编排读数，2026-10-24）

上游读数块语义（`HudItem.typesOf(mask)` 驱动、设置页 BlockHud 的 hud_items 段）接到真实 UI——
这是 J1/J2 纯逻辑地基的第一个消费者。**拖拽编辑器仍留 J3b。**

- **持久化**：`SettingsStore` 增 `hudItems`（键 `hud_items`，与上游 `WotaSettings.KEY_HUD_ITEMS`
  逐字一致；不在 24 键白名单——纯 UI 设置，设置页直读直写）。坏值收口抽成纯函数
  `HudItems.maskOrDefault`（非有限数/负数回 `DEFAULT_MASK=15`；**0 是合法的「全部取消」**，
  不回默认——回默认会让这一用户动作永远存不下来；高位未知 bit 前向兼容不裁）。
- **设置页**：「取景读数」分组（参考线之后），7 颗多选 chip；位运算只经 `HudItems.isOn/toggle`
  （本批新增，与 `RefLine` 同形态），调用点无手写位运算（接线守卫钉住）。
- **相机页**：固定两行之下新增「读数条」（**临时落位**，J3b 挪进 READOUT zone 并换
  `planReadoutRow` 的同行裁决）：条目 = `HudItems.typesOf(mask)` 枚举声明序；换行档走 J1
  `hudPerRowFor(count, fontScale, hudRoomDp(实测宽, HUD_EDGE_PAD))`、分组走 J2
  `hudRowGroups(HudZone.READOUT, order, perRow)`；可用宽首帧按屏幕宽兜底、`onAreaChange`
  实测接管（J1 审查口径：喂 `hudRoomDp` 的必须与换行档同一份实测宽）；字体档读
  `UIAbilityContext.config.fontSizeScale`（**Display 无 fontScale**，SDK 核实；读不到 → 1）。
  值全走单一真源：快门/ISO/EV/WB/帧率复用既有 `readoutText/readoutText2`；码率新增
  （`BitratePolicy.resolve` 现解，档位 0=自动）；变焦与 `zoomLabel` 同源。掩码 0 整条不渲染。
- **去重红线**：`fps` 移出 `HUD_ROW1`、`shutter/iso/ev/wb` 移出 `HUD_ROW2`（HudItem 集合的
  读数只出现在读数条一处）；`lens/size/battery/free/duration` 留固定行。
- **默认档登记**：新装用户 = 上游 `DEFAULT_MASK`（快门/帧率/码率/感光度），比 J3a 前的固定行
  少显 EV/WB——与上游对齐的故意行为，设置页可勾回；已存过掩码的老用户不补档（用例钉住）。
- **文案登记**：EV 档 chip 标签用「曝光补偿」（上游 strings 为「EV」），产品文案口径。
- **测试**：`test/pure/hud-items-wiring.test.ets`（8 例：`maskOrDefault` 坏值表、`isOn/toggle`
  往返、默认档经生产算式到「第几行第几列」（对拍上游 HudDefaultIsoTest 的计划→行为）、
  全七档/极窄宽分组、去重红线、三条源码接线守卫）。共 +8（569 → 577）。
- **回归**：pure-test 45 files / 577 tests 全绿；clean `assembleHap`（release）BUILD SUCCESSFUL
  （子代理初版用了不存在的 `Display.fontScale`，复核时以编译错误抓获、改读
  `UIAbilityContext.config.fontSizeScale`）。
- **真机待验**（并入 T31 待验清单）：默认档四颗的读数条排版（360vp 宽 3 颗一行）；字体放大档
  （120%）换行是否如 `hudPerRowFor` 预测退档；设置页全取消后读数条消失、重开恢复；录制中
  时长读数仍红色。

## 交付批（J2：HUD 网格几何与拖放，2026-10-23）

对齐上游 `ui/HudLayout.kt` 的 J2 子集 + `ui/HudMetrics.kt` / `ui/HudLayout.kt` 的读数行规划。
全部追加在 J1 的 `core/HudLayout.ets`（同文件，J1 函数签名与语义一字未动）与既有测试文件里；
**零 `@ohos.`/`@kit.` import**，`encode`/`decode` 一个字符未改。

- **几何/格子**：`gridBoxOf`、`editableGridRowsOf`、`GRID_ROW_GROWTH_ROWS`、`clampCellToBox`、
  `gridPitchPx`、`gridRowPitchPx`、`hudGridRowPitchPx`（上游 UI 层 `hudGridRowPitchPx(zone, Density)`
  的纯逻辑等价物，吃 `fontScale`/`density`）、`cellContentWidthPx`、`cellSlotLeftPx`、
  `cellChildLeftsPx`、`gridPlacementOf`、`GridPlacement`、`gridSizePx`、`gridPitchOf`、
  `snapGridPitchPx`、`cellOffsetPx`、`cellAtPointer`、`cellPlaceOffsetPx`、`entryHitIndex`、
  `HudSizePx`、`HudGridPlan`、`GridAnchor`、`HudGridItem`、`reservedGridRowsOf`、
  `reservedGridColsOf`、`gridColFromEndOf`。
- **推导/占用**：`resolveCells`、`defaultCellsOf`、`cellGroupMatesOf`。
- **拖放**：`cellDropOf`、`freeCellNear`、`CellDrop`。
- **表方法**：`gridItems`、`defaultGridOf`、`occupiedCells`、`residentsOf`、`pinned`、
  `placeEntryAt`、`moveEntryTo`、`sourceZoneOf`、`droppedCellHeightPx`。
- **底栏吸附/重叠提示**：`dockLowerY`、`dockUpperY`、`dockSnapY`、`dockDragBlocked`（吃
  [HudDockStatus]，镜像上游 `RecordStatus` 五档）、`zoneAt`、`overlappingZones`（返回
  `HudZonePair`）、`dropIndexFor`（含私有 `clusterRows`）、`areaForRightDock`、`hudRowGroups`、
  `rightDockPacksLevelAndVolume`。
- **读数行规划**（上游 `HudLayout.kt` 内，不在 `LiquidMerge.kt`）：`planReadoutRow`、
  `ReadoutRowPlan`、`readoutStripWidthDp`、`readoutRoomBesideDockDp`、`DOCK_ROW_PER_ROW_CAP`。
- **#69 铁律**：跨度/实测高/格距一类形参（`clampCellToBox` 的 `contentHeightPx`/`rowPitchPx`、
  `freeCellNear` 的两条、`cellDropOf` 的 `draggedHeightPx`/`cellHeightPx`/`rowPitchPx`、
  `resolveCells` 的 `cellHeightPx`/`rowPitchPx`、`reservedGridRowsOf` 的两条、
  `gridPlacementOf` 的 `rowPitchPx`、`planReadoutRow` 的 `landscape`/`readoutWidthDp`）
  **一律没有默认值**，漏挂必须编译不过。
- **ArkTS 适配（与上游的结构差异，如实登记）**：
  1. 无结构相等 ⇒ 条目键用 id、格子键用 token；`occupiedCells` 返回 **token 集**（`Set<string>`）
     而不是 `Set<GridCell>`；`CellDrop.moved` 是 `Map<条目 id, GridCell>`；`HudGridPlan.visible` 是
     **id 集**（与 J1 `visibleOrderOf` 同口径）。
  2. `HudLayoutTable.pinned(plan, zones)` 收**数组**（上游是 `vararg`）。
  3. `dockDragBlocked` 吃本文件新增的 `HudDockStatus`（上游 `camera.RecordStatus`）；本侧录制管线
     没有同名状态枚举，J3 在调用点做映射。
  4. `gridPlacementOf` 的「颗数与尺寸数不等」用 `throw new Error`（上游 `require`），没有 `GridBox`
     构造门（J1 的 `GridBox` 保持宽松，`gridBoxOf` 保证行列 ≥ 1）。
  5. `overlappingZones` 返回 `HudZonePair`（上游 `Pair`）；`entryHitIndex` 直接复用 J1 的 `IntRange`。
- **测试**：新增 `hud-layout-grid.test.ets`（38 条：上游 `HudLayoutGridTest` 27 + `HudLayoutPairCellTest` 11）、
  `hud-layout-rowpitch.test.ets`（11 条：`HudLayoutRowPitchTest` 全量）、
  `hud-layout-drag.test.ets`（24 条：`HudLayoutDragTest` 13 + `HudLayoutSwapGuardTest` 行为面 9 +
  上游三条源码钉扎臂的行为半段 2）、`hud-readout-row-plan.test.ets`（9 条：`planReadoutRow` 部分）；
  `hud-layout.test.ets` 35 → 49 条（补 `HudLayoutClampTest` 的底栏吸附/重叠/最内层命中 7 条，
  `HudLayoutCodecTest` 的 v1 迁移逐颗落点/配对同格往返/隐藏态/坏后缀 7 条）。
  **473 → 569**，failed=0。
- **突变自证（真跑过并还原）**：把 `CHIP_VERTICAL_PADDING_DP` 12→10（胶囊档 30→28）⇒ 13 条红；
  把 `cellDropOf` 的 occupant 过滤去掉 `!mates.has(e.id)`（同格配对规则删掉）⇒ 合对两条红。
- **剩余（J3）**：编辑器 UI + 录制页消费（`hud_layout` 键、预览与写表接线、真机走查）。

## 交付批（J1：HUD 可编排布局地基，2026-10-23）

对齐上游 `ui/HudMetrics.kt` / `ui/HudPageGrid.kt` / `ui/HudLayout.kt` 的**纯逻辑地基**（HUD 布局编辑器
大功能的第 1 批；**只做纯逻辑，不做 UI**）。

- **模块**（三个，全部零 `@ohos.`/`@kit.` import，已进 `PureLogicBarrel` 编译探针，
  `signature()` 返回串不变）：
  - `core/HudMetrics.ets` ← `ui/HudMetrics.kt` **全量**：`hudPerRowFor`（换行档 3/2/1）、`hudRoomDp`
    （宽度账）、`hudChipHeightDp`、`hudStripHeightDp`（高度预测）；同文件七个私有常量逐字照搬，
    公开 `HUD_ROW_GAP_DP` / `HUD_BLOCK_PAD_DP`。KDoc 保留「宽度账」推导与 §58/§73/§70 三次翻车教训。
  - `core/HudPageGrid.ets` ← `ui/HudPageGrid.kt` **全量**：`HudPageCell`（+ `pageCellToGridCell` /
    `gridCellToPageCell` 桥）、`pageCellPx`、`pageGridBoxOf`、`pageCellAtPointer`、`pageCellOffsetPx`、
    `pageCellsBlocked`、`pageGridColCap`、`pageGridRowCap`。
  - `core/HudLayout.ets` ← `ui/HudLayout.kt` 的**类型 + 序列化 + 夹取/落区**子集：
    `HudAxis`/`HudZone`（key/axis/isGrid + 五枚区域常量）/`HudEntryKind`/`HudEntry`（ALL/序数/id/
    homeZone）/`ZonePlacement`/`GridCell`/`ZoneState`/`HudLayoutTable`/`GridBox`/`editorVisibleEntries`；
    `HudAreaDp`/`HudRectDp`/`HudPointDp`/`HudPointPx`/`IntRange`、`clampZoneX`/`clampZoneYRange`/
    `zoneBandYRange`/`clampZonePos`/`chromeBandBottomDp`/`pxToDp`/`gridColsCapOf`/`gridRowHardCap`/
    `clampStoredCell`/`dropZoneOf`；`HudLayoutTable.encode()` / `decode()`（含 `parseCell`）+ 表内
    访问器（`posOf`/`orderOf`/`visibleOrderOf`/`cellsOf`/`withZonePos`/`normalizedPosOf`/`normalize`/
    `default`/`defaultOrderOf`/`allEntries`）。
  - **预置依赖闭包（登记）**：`cellRowSpan`/`spannedCells`/`blockingCells` 本属 J2 网格几何，但
    `pageCellsBlocked`（J1 必移植）在上游就是**复用**这三件的桥；为避免第二份占用判据，
    J1 一并落地，J2 直接在其上加 `gridBoxOf`/`clampCellToBox`/`cellDropOf`/`freeCellNear`/
    `resolveCells`/`defaultCellsOf`。
- **持久化契约（`hud_layout`）**：`encode` 与上游**逐字节一致**（分号分版本段/五容器段，
  逗号分 x/y/id 串，网格容器每颗带 `:格子`，哨兵 `-`）。默认表真实串：
  `v2;T,-1,-1,P11,P12;L,-1,-1,P6:-,P7:-,P8:-,P9:-;R,-1,-1,P0:-,P1:-,P2:-,P3:-,P4:-,P5:-;D,-1,-1,H0:-,H1:-,H2:-,H3:-,H4:-,H5:-,H6:-;B,-1,-1,P10`。
  手写字面量往返用例钉住它（不由实现反推期望串）。
- **ArkTS 适配（唯一结构差异）**：无结构相等 ⇒ Map/Set 键用持久化里已有的串（容器 `'T'`…、
  条目 id `'P3'`/`'H6'`、格子 token `'0.2'`/`'-'`）；`pageCellsBlocked`/`blockingCells` 返回
  token 集而不是对象集。语义与上游一一对应。
- **J2 已完成（2026-10-23）**：网格几何、拖放落点、表方法与读数行规划已全部落在同一批文件里，
  清单见下节「交付批（J2…）」；~~J2 留网格几何与拖拽落点~~ 已关闭。
- **剩余（J3）**：编辑器 UI（编辑页/录制页对位置表的消费、预览与写表、`hud_layout` 键接线）；
  本批仍**无 UI 消费方**，没有伪造接线。
- **复用既有件**：`core/Constants.ets` 的 `HudItem`/`CamPill` 位值已经齐备，`HudItems.typesOf`/
  `maskOf`/`DEFAULT_MASK`（15 = 快门|帧率|码率|ISO）与上游同值——本批只加契约用例，不需要新增编码器。
  `ui/HudTokens.ets`/`ui/HudSpec.ets` 是**本侧自有令牌**（上游 HDS 令牌的静态子集），与 `HudMetrics`
  的 dp 表不是同一份真源，模块头已登记关系，不混用。
- **测试**：`test/pure/hud-metrics.test.ets` 11 条、`hud-page-grid.test.ets` 14 条、`hud-layout.test.ets`
  35 条，纯逻辑用例 **413 → 473**；每条注释写「为什么存在」，关键处带突变自证
  （删安全余量、改块内边距、改 codec 分隔符/后缀、把带容器当有序列表都必须红）。
- **回归**：`node scripts/pure-test.mjs` → `files=40 tests=473 passed=473 failed=0`（exit 0）；
  clean `assembleHap`（release）BUILD SUCCESSFUL（WARN 基线不变：napi 未验证 + 未配置签名）。
- **真机待验（不阻塞）**：本批没有 UI，无可点项；`hud_layout` 串的跨进程回读在 J3 接线后按
  「编辑→杀进程→重进」走查。

## 交付批（I：录制页参数持久化 ParamsPersist，2026-10-23）

- **模块**：`ui/ParamsPersist.ets`（纯逻辑，零 `@ohos.`/`@kit.` import：24 键白名单 + `restore` 冷启链 +
  `ParamsPersistSession` 影子落盘链 + 枚举/尺寸编解码 + `ParamsMigrate` 迁移判定）、`ui/ParamsStore.ets`
  （平台件：`getPreferencesSync` 同步门面 + `putSync` 批写 + `commit()` 一次 `flushSync` 落盘，与
  `SettingsStore` 共用 `wota_camera_settings`）、`ui/ParamsFlushThrottle.ets`（纯逻辑：时钟/定时器注入的
  300ms 尾随节流器）；`PureLogicBarrel` 纳入编译探针（`signature()` 返回串不变）。
- **接线**：`pages/Camera.ets` 的 `bootstrap` 冷启 `restore`（读完设置、起镜/initNativeParams 之前；
  机型域仍由 `applyAbility` 收口）；flush 点分两档——**立即**（`onPageHide`/`aboutToDisappear`/起录前
  `applyParamsBeforeRecord`/`cycleFps`/`cycleSize`/`cycleZoom`/`toggleFlash`/`closePill`，先 cancel 尾随再落盘）
  与**延后**（`applyParamsFromPills` 的滑杆逐帧路径走 300ms 尾随节流）；会话在 `aboutToAppear` 建、
  `aboutToDisappear` 取消定时器并清（restore 后 `resync` 影子，恢复值不算用户动作）。
- **落盘频率**：批写一次 commit + 300ms 尾随节流，关键点立即落盘（与上游 onValueChangeFinished/退后台口径对齐）——
  一次 flush 无论改几个字段只 `flushSync` 一次，拖动期不逐帧同步 IO。
- **键名迁移**：`SettingsStore` 的 `fps`/`bitrate`/`sizeW+sizeH` 改以上游键名 `default_fps`/
  `default_bitrate`/`default_size` 为唯一权威（迁移读新键优先、回退旧键；save 只写新键，旧键保留不删）；
  `arcMode`/`grid` 留在 SettingsStore 原地（本侧已登记行为偏离）。
- **排除清单**：renderMode/sampleRate/audioEnabled/focusPoint/curve/arc_convert_mode 一律不入档
  （照搬上游，测例逐键断言）。
- **测试**：`test/pure/params-persist.test.ets` 28 条（白名单契约 3 / restore 正反例 6 / 尺寸与枚举 2 /
  会话影子 8 + 批写 3 / 键名迁移 2 / 落盘节流 4），纯逻辑用例 385 → 413。
- **回归**：`node scripts/pure-test.mjs` → `files=37 tests=413 passed=413 failed=0`（exit 0）；
  clean `assembleHap` exit 0（BUILD SUCCESSFUL；WARN 基线不变：napi 未验证 + 未配置签名）。
- **真机待验（不阻塞）**：① 改快门/ISO/EV/WB 后杀进程冷启，读数回到上次值
  （`hdc hilog | grep WotaParamsStore` 查有无写失败）；② 设置页改帧率/码率/画幅 → 回录制页退后台再进，
  值不回默认且不被录制页陈旧总线覆盖；③ 音源（内录/环境音）任何路径都不跨进程记住（排除清单）；
  ④ 旧版本升级（只有 `fps`/`bitrate`/`sizeW/sizeH` 旧键）首启读到旧值，进设置页保存一次后改由新键承载。

## 交付批（T23，2026-10-22）

- **T22 P3×2 关销**：① `scripts/pure-test.mjs` 死常量 `SRC_ANCHOR` 删除（仅注释保留目录文字），
  50 用例回归不变；② `pages/Compare.ets` 时间线左端标签负轴改 `media/MediaFormat.formatSignedDuration`
  （`-m:ss`；亚秒负值下限 `-00:01`，避免 500ms 偏移步进时与 0 混淆；-1.5s 仍按 floor 截断为
  `-00:01`），进度读数仍随左钟；`test/pure/media-format.test.ets` 3 用例，合计 53。
- **本地签名探针** `scripts/local-sign.mjs`：hap-sign-tool 全链 ECC P-256（keypair×4 → root/sub CA →
  app-cert/profile-cert → sign-profile(debug, bundleName `com.wotagei.cam`) → sign-app → verify-app
  自动回验）；材料落 `.local-signing/`（gitignore，仅缺失时生成，可重复重签）。
  （2026-10-23 注：应用 bundleName 已改 `com.wotagei.camohos`（与安卓版 `com.wotagei.cam` 双端同装）；
  本探针的 `BUNDLE` 已同步改名并重签 profile——删 `wota-debug-profile.json`/`wota-debug.p7b` 后复跑，
  `verify-app` 通过（3 级链 + 3259B profile），p12 与证书沿用原指纹。）平台事实登记：
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

## 交付批（T24，2026-10-22）

- **纯逻辑直译（无真机依赖，纯函数 + 单测先落）**：
  - `player/CompareTimeline.ets` 补齐 decide 全量内核（域判定/主钟/交接/纠偏/末段回绕/拖动让路），
    TimelineGeometry 与 normalizeOffset/rightTargetFor/timelineSpan/timelineEnd 零改动；
  - `player/CompareAudio.ets`：轨序单一映射、双引擎选轨计划与固定命令序、分屏单引擎计划
    （音量恒 1）、EngineCommand 判别类与静态工厂；
  - `player/AudioSync.ets`：DSP 全量（重采样/包络/粗对齐/radix-2 FFT/1kHz 低通/精修与置信度）；
    平台解码适配留待内录/自动对齐批；
  - `player/ComparePractice.ets`：练习桥状态语义（模块单例；分屏练拍页面未移植，接线阻塞）；
  - `player/CompareHistory.ets`：历史纯核心（解析/去重/截断/编码；preferences 适配留 T25）。
- **测试**：新增 58 用例（53→111）——compare-timeline +16（总 25）、compare-audio 16、
  audio-sync 10、compare-practice 6、compare-history 10；期望值手写字面量（浮点用上游容差）。
- **范围**：`pages/` 零改动（Compare.ets 接线留 T25）；新增模块不 import `@ohos.*`/`@kit.*`。
- **回归**：`node scripts/pure-test.mjs` → files=10 tests=111 passed=111 failed=0（exit 0）；
  clean assembleHap 退出 0、WARN=2 基线；临时编译探针验证新模块进 ArkTS program 后构建无新增告警
  （探针已删，player/PlayerModel 无残留）。
- **交付表刷新（T24 快照，hash 易变口径同 T23）**：unsigned 5079359B / `a4251aba…a96e5`、
  signed 5177081B / `d6bb2266…c1fcb`（`local-sign` 成功，verify-app 3 级链 + 3254B profile；
  签名块 62314B；全文见 README「交付」节）。

## 交付批（T25，2026-10-22）

- **对比页接线**：见 `PlayerScreen.kt/CompareScreen.kt` 行 T25 段。范围限定在 `pages/Compare.ets`
  与 `player/PlayerEngine.ets`（能力补齐）+ 新 `player/PureLogicBarrel.ets`、
  `player/CompareHistoryStore.ets`；`WotaGeiCamApp/` 零改动。
- **PlayerEngine 增量**（additive，旧 playPause/seek/AB 零改动）：pauseIfPlaying / playIfPaused
  （completed 按 positionMs 补 seek，**seekDone 落地后再 play**）/ seekAndPlayIfPaused（重建续播
  的 seek→play 原子路径，800ms 兜底）/ nudgeSpeedForSync（平台空操作）/ selectAudioGroup
  （prepare 后 getTrackDescription 枚举音轨序 → selectTrack；undefined=回第 1 条；越界/状态不符
  Diag.w 不抛）。
- **P3×6**：① PureLogicBarrel 编译守卫（EntryAbility.onCreate 记录 `pure logic barrel sr=8000
  max=12 vol=1/0 armed=false`，把 T24 四模块拉进 abc）；② CompareHistory 数字串容错
  （`^-?\d+$` + safe integer，对齐上游 getLong；+2 用例）；③ 文档口径（测试计数/对拍口径）；
  ④ signed 校验限定 unsigned（内容级清单只对 unsigned；signed 走 verify-app）；⑤ 真机项登记；
  ⑥ 历史面板缺失/删除边界。
- **审查修复（P1×1 + P2×3 + P3×5）**：① P1 重建续播（wantPlaying 6 处采集 + seekAndPlayIfPaused
  + maybeResumeWanted 去「任一侧在播」早退）；② P2 独播域拖回双覆盖区 dormant 侧复活
  （seekTimeline→resumeDomainAt）；③ P2 换片/attach 无条件重探 audioSideDual；④ P2 右片未选/
  报错时进度跟左钟；⑤ P3 playIfPaused 的 completed seek→play 改 seekDone 后起播、历史写入串行
  队列、number 分支 safe-integer、静音态音轨静默生效、汇报改用符号锚点。静态检查脚本 11 项全 PASS
  （见 T25 汇报）。
- **回归**：pure-test files=10 tests=113 passed=113 failed=0；clean assembleHap 退出 0、WARN=2；
  local-sign 成功（verify-app 3 级链 + 3254B profile；签名块 61946B）。交付快照：unsigned
  5227659B / `0be7fb6e…af8434`、signed 5324045B / `e389c836…33d3d1`（hash/字节均易变，
  内容级校验只对 unsigned，signed 走 verify-app；全文见 README「交付」节）。
- **遗留**：AudioSync 平台解码/自动对齐入口、ComparePractice 练习动线（分屏录制页）、
  AB 持久化、nudge 真机空操作验证 → T26。

## 交付批（T26，2026-10-22）

- **内录纯逻辑降级版**（无真机可验证的平台面不落实现）：`record/CaptureStateMachine.ets`（16 用例）、
  `record/CaptureSilence.ets`（5）、`record/AudioProbe.ets`（3）、`record/CaptureSources.ets`（3）、
  `player/TrackSync.ets`（11）、`player/PcmDecode.ets`（7）——新增 46 用例（113→159，含审查修复
  1 条「右片重建恢复目标四域臂」；`rightResumeTargetOf` 纯函数）。
- **T25 P3×4 清账（T29 按最终行为改写 ②）**：① 自然播完（TIMELINE_END + loop 关 + 双引擎停）清
  wantPlaying，maybeResumeWanted 末态改严格 `t >= tMax`（100ms 容差只留 setPlaying）；
  ② 右片重建恢复位的**最终行为**：以重建前素材位快照为准，`rightResumeTargetOf(geo, leftLive,
  snapshot, blackL, blackR)` 用快照当右钟做域判定得到目标（右独播尾段/pre-roll/左独播/末态 park
  各落正确位），`wantPlaying` 时 `seekAndPlayIfPaused`（seek 落地即播），随后 maybeResumeWanted
  复算 T 只做域内唤醒、不二次 seek；**左片未就绪（双表面重建右先挂）直接用快照位**（左引擎
  positionMs=0 不参与域判定）；已播态偏差 >1ms 改 `seekTo` 纠偏；③ audioProbePending 在途
  （2s 陈旧兜底）跳过 selectAudioGroup、音量照发；④ 删除 hasPendingPlayAfterSeek 死 API。
- **审查修复（P2×1 + P3×3）**：① P2 右片重建保留素材位——`rightResumeTargetOf(geo, leftLive,
  rightSnapshot, blackL, blackR)` 用**重建前快照**当右钟做域判定，右独播尾段/pre-roll/左独播/
  末态 park 都落正确素材位（新引擎 attach 后 positionMs=0 不再参与 T 计算）；`tryAttachRight`
  始终按该目标发一次 seek（wantPlaying 时 seek 落地即播），`maybeResumeWanted` 复算 T 只做域内
  唤醒。四域臂用例见 compare-timeline（26 条）。② P3 `captureStateLabel/captureEventLabel` 去掉
  default 改显式 throw + 枚举成员数断言；③ P3 AudioProbe 补 8191/100/三声道/负 minBuffer 断言
  与负值掩蔽注释；④ P3 `probeAudioSide` 早退/then/catch 统一经 `clearAudioProbePending()`。
- **口径订正**：上游 TrackSyncTest 实为 **11 条纯函数用例 + 1 条 extractor 源码守卫**（无
  MIN_CONFIDENCE 断言；本侧那条为额外断言），已在测试头与本表如实登记。
- **编译守卫**：PureLogicBarrel 纳入六新模块，EntryAbility 启动日志签名扩展为
  `… cap=未授权 src=2 sync=+120 chunk=64 silence=2000 pcm=S16LE`。
- **明确不做**：不建 AVScreenCapture 会话/不请求隐私授权/不写 native 音频编码或三轨 mux/不写无实现
  的解码接口骨架/不接线 ComparePractice/不动 codec_recorder.cpp 与 GlRecorder。
- **回归**：pure-test files=16 tests=159 passed=159 failed=0；clean assembleHap 退出 0、WARN=2；
  local-sign 成功（verify-app 3 级链 + 3254B profile）。交付快照：unsigned 5271587B /
  `f81211d1…d43bbd`、signed 5371800B / `d1ff7488…a748eb`（hash/字节均易变，内容级校验只对
  unsigned，signed 走 verify-app；全文见 README「交付」节）。
- **遗留**：平台面全部转 T27（见「T26 平台调研」四步计划）。

## T26 平台调研（AVScreenCapture，2026-10-22）

结论先行：**内录双轨在当前公开 API 下只有 NDK 一条路可行**，ArkTS 侧无法同时拿环境音+内录原始
PCM；且内录会话必须与视频采集配置同场（没有音频-only 的原始流形态）。四步计划见「交付后路线」。

- **ArkTS 侧（AVScreenCaptureRecorder）单轨限制**：`AVScreenCaptureRecordConfig` 只有一组
  `audioSampleRate/audioChannelCount/audioBitrate`（注释明确「used for both internal capture and
  external capture」），**没有 audioSourceType 字段**，输出是编码后的 MP4（`fd` + preset
  H264/AAC/MP4），拿不到分源 PCM；因此不能作为「环境轨 + 内录轨」双轨原料（SDK d.ts：
  `@ohos.multimedia.media.d.ts` AVScreenCaptureRecordConfig / createAVScreenCaptureRecorder）。
- **NDK 双源能力**：`OH_AVScreenCaptureConfig.audioInfo` 同时含 `micCapInfo` 与 `innerCapInfo`
  （各自 `OH_AudioCaptureInfo{audioSampleRate,audioChannels,audioSource}`），
  `OH_AVScreenCapture_AcquireAudioBuffer(capture, &buf, type)` 按 `OH_AudioCaptureSourceType`
  分别取原始 PCM；`OH_AudioCaptureSourceType` 数值为 `OH_MIC=1 / OH_ALL_PLAYBACK=2 /
  OH_APP_PLAYBACK=3`（与 `record/CaptureSources.ets` 逐值对齐）。`dataType=OH_ORIGINAL_STREAM`
  时音频不需要编码器配置（`audioEncInfo` 注释「no need to set」）。
- **隐私授权**：AVScreenCapture 启动会拉起系统投屏/录制隐私确认（每次会话用户确认），授权态
  与上游 MediaProjection 语义对齐——落到本侧状态机即 ConsentRequested → Authorizing → Active；
  取消/系统停止回 ConsentCancelled / ProjectionStopped（→Revoked 回退环境音）。
  SDK 接口注释未声明额外 `ohos.permission.CAPTURE_SCREEN` 运行时权限，**以真机弹窗实测为准**。
- **视频必选**：`OH_AVScreenCaptureConfig` 同时要求 `videoInfo`（`OH_VideoCaptureInfo`）与
  `audioInfo`；即使只要音频，也要给一份屏幕视频配置（可丢弃其回调），没有纯音频配置形态。
  这与上游「内录独立于屏幕视频」的模型不同——本侧录制视频仍走 GL 编码面，AVScreenCapture 仅取音频。
- **三轨方案（T27 目标）**：视频轨 = 现有 GPU 编码面（不变）；音频轨 0 = 环境音（MIC，现有
  AVRecorder/AudioCapturer 路线）；音频轨 1 = 内录（AVScreenCapture 的 inner PCM）。三轨合流在
  native muxer：环境/内录各编 AAC 后 `OH_AVMuxer` 加两轨（轨序 0/1 = CompareAudio 的唯一映射），
  或直接写 PCM 由 muxer 编码（视 SDK 能力二选一，T27-1 探针定）。时间戳统一 wall-clock µs，
  偏移合成用 `player/TrackSync.ets`（内容差 + 首样本容器差）。

## 交付批（T28，2026-10-22）

- **离线光弧修复纯逻辑**（执行器不落）：`record/ArcRepairPlan.ets`（15 用例）、
  `record/ArcRepairFlow.ets`（9）、`record/ArcRepairMerge.ets`（12）、
  `record/ArcRepairVerdict.ets`（5）——新增 41 用例（159→200）。四模块零平台依赖，
  纳入 `PureLogicBarrel`（启动签名追加 `arc=36/0/1`）与 `RecordAnchors` 编译门。
- **T26 P3×3**：① `tryAttachRight` 在 `!leftReady`（双表面重建右先挂）时直接用快照作恢复位，
  左引擎 positionMs=0 不参与域判定；`maybeResumeWanted` 已播且偏差>1ms 时改 `seekTo` 纠偏
  （`seekAndPlayIfPaused` 在播态会早退）。② 本表恢复位口径统一为最终行为：始终按重建前快照
  算恢复目标（T26 审查）；P3-a 再收紧为左片未就绪时不查域。③ 提交主题不再写死用例数。
- **明确不做**：不碰 native 解码/编码与页面（T29 执行器 + Player 面板）；不移植上游旧 Int
  兼容外壳（统一有理数签名，整数源用 num/1）。
- **回归**：pure-test files=20 tests=200 passed=200 failed=0；clean assembleHap 退出 0、WARN=2；
  local-sign 成功（verify-app 3 级链 + 3254B profile；签名块 61049B）。交付快照：unsigned
  5297714B / `c933ad06…c9cfc0c`、signed 5394157B / `51cb0bd9…7a02c84`（hash/字节均易变，
  内容级校验只对 unsigned，signed 走 verify-app；全文见 README「交付」节）。
- **遗留**：执行器（ArcRepairRunner/ArcRepairGl：AVDemuxer/AVCodec/EGL/Player 面板）转 T29。

## 交付批（T29，2026-10-22）

- **native CPU 执行器核心**：`cpp/arc_repair.h/.cpp` + NAPI 四口 + CMake（追加 `arc_repair.cpp` 与
  `libnative_media_vdec.so`）；`record/ArcRepairSessionModel.ets`（24 用例）+
  `record/ArcRepairRunner.ets`（fd 生命周期/轮询/归因/contract，EntryAbility 启动日志进编译门）。
  新增 28 用例（200→228）。
- **T28 P3×4**：① Flow 状态字段私有化 + `keptCount()/hasPending()/hasAcc()` 只读方法、构造器对
  `dstFps<=0` 抛错（+3 用例）；② merge 测试补 GPU 逐通道取大突变反向断言（+1 用例，珊瑚粉/中间色
  字面量）；③ 本表恢复位口径统一为最终行为；④ 提交主题不写死用例数。
- **本批不做（已登记）**：音频直拷与 Player「修复光弧」面板 → T30（**开放按钮前必须先补音频**，
  否则成片无声）；GPU 路线 ArcRepairGl 与真机调优 → T31。
- **审查修复（P2×2 + P3×8）**：① P2 linger 挂起路径**先无条件 unlink 半成品**（`parkOrDiscard`），
  `reapPendingFds` 对 `pending.path` 兜底再 discard 一次（幂等）；文件头描述改为真实行为。
  ② P2 编码器 CSD 缓冲：建轨后 `AVCODEC_BUFFER_FLAGS_CODEC_DATA` 直接 Free **不写样**（避免
  PTS=0 撞首帧与 videoSamples 虚增），done 日志与 ArkTS 侧记录 `videoSamples - dstFrames` 偏差。
  ③ 取消终态：native 取消发 `cancelled`（不再 `failed:ARC_CANCELLED`），契约两处一致。
  ④ 零样本归因改 `ARC_NO_VIDEO`（上游同口径，测试钉码）。⑤ probe 时长不可得返回 `ok=false`。
  ⑥ 统一收尾 `parkOrDiscard`（失败/异常同口径，异常路径不再抢关 native 正在写的 fd）。
  ⑦（T30 已由音频直拷取代，硬闸删除）。⑧ 成片命名走
  `ClipExporter.uniqueOutputPath`（防 1/1000 碰撞 TRUNC 覆盖）。⑨ native 卫生：删未赋值
  `outBuffer`、解码 Query 非 TRY_AGAIN/STREAM_CHANGED 即 ARC_DECODER、drain 空转有界（50 拍）
  且每拍查取消/deadline、解码输出尺寸与容器宽高不符即 ARC_CPU_YUV。
- **回归**：pure-test files=21 tests=230 passed=230 failed=0；clean assembleHap 退出 0，
  hvigor WARN=2（基线；新增 `arc_repair.cpp` 这个 TU **未新增任何代码告警**）；local-sign 成功
  （verify-app 3 级链 + 3254B profile；签名块 62480B）。交付快照：unsigned 5506096B /
  `a5094b48…b074b8`、signed 5610286B / `ac289b5c…0e5fc5`（hash/字节均易变，内容级校验只对
  unsigned，signed 走 verify-app；全文见 README「交付」节）。
- **遗留**：同步模式解码/编码只能在真机验证（见「真机待验」T29 节）。

## T29 native 契约（与 record/ArcRepairSessionModel.ets 逐字段对齐）

- **NAPI 四口**：`probeArcRepairSource(inFd,inSize)` / `startArcRepair(inFd,inSize,outFd,dstFps,
  bitrate,deadband,srcNum,srcDen,srcFrames,dstFrames,rotation,durationMs)` /
  `getArcRepairStatus()` / `cancelArcRepair()`；fd 全部归 ArkTS（native 绝不 close）。
- **probe 串**：`ok:<w>:<h>:<rotation>:<containerFps>:<durationMs>:<cleanCount>:<medianUs>:<audioCount>:<mime>`
  或 `err:<code>`（ARC_SOURCE / ARC_NO_VIDEO / ARC_PROBE）。
- **状态串**：`idle` | `running:<pct>` | `done:<videoSamples>:<audioSamples>:<mux0/1>:<track0/1>:<stop0/1>`
  | `failed:<code>` | `cancelled`。取消走**独立终态** `cancelled`（不再发 `failed:ARC_CANCELLED`）。
  **T30 起 `audioSamples` = 全部音轨成功写入 muxer 的样本合计**（无音轨源 0；逐轨降级只计成功
  lane），串仍 6 段。
- **CSD 口径**：编码器输出里 `AVCODEC_BUFFER_FLAGS_CODEC_DATA` 的缓冲在 AddTrack 后直接 Free，
  **不写 muxer**（SPS/PPS 已随 AddTrack 的 trackFormat 登记）；`videoSamples` 必须与
  `dstFrames` 相等，done 状态与日志均带偏差值。
- **音频直拷（T30）**：源全部 `audio/` 轨按容器轨序 AddTrack 并逐轨直拷（不做偏移合成，
  TrackSync 归播放期选轨语义 T27-4）；AddTrack 失败/读失败逐轨降级 + WARN，`audioLaneNote`
  向用户说明「有音轨但零样本 → 成片无音频」。**原 T29 的音轨硬闸已删除**（T30 起改为真实直拷）。
- **半成品清理**：失败/取消**先 unlink 成片路径**（native 写已解除链接 inode，安全），fd 等
  native 离开 `running:` 后关闭；linger 耗尽挂 `pendingFds` 并在 reap 时兜底再 discard。
- **错误码**：ARC_SOURCE / ARC_DECODER / ARC_ENCODER / ARC_MUXER / ARC_CPU_YUV / ARC_CPU_FEED /
  ARC_NO_VIDEO / ARC_CANCELLED / ARC_TIMEOUT / ARC_ENGINE（+ UI 侧 ARC_NO_NEED / ARC_PROBE）。
- **同步模式 API 依据**：`OH_VideoDecoder_QueryInputBuffer/QueryOutputBuffer`、
  `OH_VideoEncoder_QueryInputBuffer/QueryOutputBuffer` 均为 **API 20** 引入
  （`@since 20`，本工程 compatibleSdk 6.0.0(20)）；`OH_VideoEncoder_GetOutputDescription` 为 API 10。
- **收尾顺序**：encoder Stop → muxer Stop（结果进状态串 stop 位）→ muxer Destroy → encoder Destroy →
  decoder Stop/Destroy → buffers → demuxer → source；失败路径同序 best-effort。
- **C++ 镜像**：`DstFrameCount/IsKept` 与 `record/ArcRepairPlan.ets` 三式逐字同形
  （`srcNum <= dstFps * srcDen` / `(s * dstFps * srcDen + srcNum - 1) / srcNum` /
  `j * srcNum / (dstFps * srcDen) == s`）；ArkTS 为已测规格。

## 交付批（T30，2026-10-22）

- **音频直拷（native）**：Run 保留全部音频轨并行表；首枚视频输出后、SetRotation/Start 前逐轨
  AddTrack（失败逐轨降级）；视频 EOS 排空后同 fd 新建 audio source/demuxer 逐轨直拷（PTS 按视频
  首帧归一、负值丢弃、逐轨单调兜底、逐样本取消/deadline）；done 串 audioSamples 为全轨合计；
  per-lane 摘要日志（track/samples/skipped/read error）。
- **会话模型**：删除 T29 临时音轨硬闸码；新增 `describeRepairError`（全码中文文案 + ENGINE 后缀 +
  busy/args/worker + 未知码兜底）与 `audioLaneNote`（无音轨/成功/降级三态）；session 26→29。
- **Runner**：移除音轨硬闸；`run()` 可传 `preProbed` 复用面板探测；结果带 `audioSamples`；
  contract 与 d.ts 注释同步（状态串 6 段不变、audioSamples 语义升级）。
- **Player 面板**：底栏「修复光弧」按钮 + 面板（打开即 probe：源实测帧率/计划源帧/音轨数；
  NO_NEED/无视频/探测失败直接拦；目标 24/25、死区 0/12/32；开始/取消/进度/成功路径+大小+音轨
  注记/打开媒体库）；忙碌时拦返回；与导出 A/B 互斥（各自忙碌禁用对方入口）。
- **T29 P3×7**：① `VideoStore.discardQuiet`（ENOENT 13900002 视为已达成、不记 ERROR），
  reap/park 两处改用；② 排空截尾：`encoderEos` 未到或 `videoSamples != dstFrames` 打 WARN，
  header 明示 delta 仅诊断、不改变成功判定；③ 解码输出缺 `OH_MD_KEY_PIXEL_FORMAT` 即
  ARC_CPU_YUV（不再默认猜 I420），stride/sliceHeight 缺失每任务仅 WARN 一次并回落宽高；
  ④ `run()` 增 `nativeStarted`（start 成功才 park）；⑤ 会话测试头注计数订正；⑥
  `resolveRepairOutcome` JSDoc 补「无轨或零样本 → ARC_NO_VIDEO」；⑦ 真机节计数措辞订正。
- **本批不做（已登记）**：TrackSync 偏移合成（播放期选轨语义 T27-4）；GPU 路线与真机调优 T31。
- **审查修复（P1×1 + P2×1 + P3×5）**：① P1 `probe()` 补赋 `out.dstFrames`（默认 24 档，经
  `planForTarget`）；面板守卫改为源侧事实 `probed.srcFrames <= 0` + 逐档可行性，默认档不可行时
  自动落到可行档；新增纯函数 `planForTarget`/`targetFeasible` 与 2 条用例（探测→计划→NO_NEED）。
  ② P2 成片说明按**实际** dstFps/deadband 在 `run()` 重算（`actualNote`），面板选项切换时
  `refreshRepairNote` 同步刷新预览；成功 note 与实际参数一致。③ P3 零样本音轨：AddTrack 前
  预读一枚样本（`hasData`），零样本轨不建轨（避免 muxer Stop 失败连坐）；④ 逐轨成功数进
  native done 日志（`audio lanes ok=M/N`），`audioLaneNote` 多音轨改为「至少 1 条已直拷」不再
  宣称全部；⑤ lane 中途读失败记 `readError` 并在摘要 WARN；⑥ `SeekToTime(0)` 失败回退为从头读；
  ⑦ 面板按目标档分别判 NO_NEED，不可行档按钮禁用并提示。
- **回归**：pure-test files=21 tests=235 passed=235 failed=0；clean assembleHap 退出 0、WARN=2；
  local-sign 成功（verify-app 3 级链 + 3254B profile；签名块随包长浮动）。交付快照：unsigned
  5799217B / `9482c55e…aa3d2acc40`、signed 5919986B / `87fec437…02cd3c00`（hash/字节均易变，内容级
  校验只对 unsigned，signed 走 verify-app；全文见 README「交付」节）。

## 交付批（上游对齐 A/B，2026-10-23）

上游基线 `342dd99` 之后 4 提交的评审与移植（不推进 UPSTREAM_BASE，理由见 UPSTREAM_SYNC.md「推进规则」）。

- **批 A：`0dee86d` 滑杆吸附——完整移植**。`ui/SliderSnap.ets`（七键 id 契约 + 编解码 + effectiveLadder 桥）、
  `ui/PillPopup.ets` PillSlider 连续模式、`ui/ParamPills.ets` 4 处挂 snapKey、`pages/Settings.ets` +
  `ui/SettingsStore.ets` 新分组与 `slider_continuous` 键、`test/pure/slider-snap.test.ets`（上游
  SliderSnapTest 全量 7 例，接线臂为本侧调用点计数）。红线：坏串/缺键=全部吸附=现行为不变；EV 等整数域不受开关影响。
- **批 B：`e950328` 蓝牙内录音画同步——纯逻辑层直译，平台面挂 T27**。新建 `core/AudioAlign.ets`
  （DSP 单一真源 + estimateOffsetMs 三红线）、`record/AudioPreflightPolicy.ets`、`record/PreflightPcm.ets`、
  `player/PostStopSyncPlan.ets`、`record/AudioRoute.ets`（唯一平台件，A2DP 降级判据）；`player/AudioSync.ets`
  改薄壳委托（407→59 行）；`player/TrackSync.ets` 扩容（62→257 行）；`player/ClipRule.ets` 增 audioShiftUs；
  `player/PureLogicBarrel.ets` 登记 5 个新纯模块（编译门覆盖）。新测试 4 件 + track-sync/clip-rule 扩容，
  共 +34 例，期望值手写字面量对拍上游。
- **批 C（未做，并入 T27-4）**：ArcRepair `capOffsetMs` 车道平移与 native 传参——无偏移来源前恒 0、单独价值低。
- **批 C（更新器，原暂缓项）：已完成，见下节「交付批（C：应用内更新，2026-10-23）」**。
- **批 D（`ClipExport` 音轨平移 + `PlayerScreen` 同步来源 UI）：已完成，见下节「交付批（D，2026-10-23）」**。
- **回归**：pure-test files=26 tests=276 passed=276 failed=0（基线 235 + A 批 7 + B 批 34）；
  `hvigorw --mode module -p product=default -p buildMode=release assembleHap` BUILD SUCCESSFUL（barrel
  登记后编译门覆盖全部新模块；仅既有 GlLibBridge napi 未验证 WARN）。
- **跳过未移植的守卫测试**（平台接线不存在，随 T27）：`PreflightWiringGuardTest`、`PostStopSyncGuardTest`、
  `ArcRepairZeroSampleLaneGuardTest`、`ClipExportShiftTest.导出接线`、`TrackSyncAutoTest.AUTO 落盘锁内复核`
  （其语义已由 writeAutoPrefs 行为用例覆盖）、`AudioAlignTest.core 纯度`（本侧纯度由 pure-test 架构保证）。

## 交付批（H：相机性能三件纯逻辑，2026-10-23）

上游 `camera/DirectSink.kt`（只取纯函数 `directDisplayMatrix`）、`camera/GlRenderSuppression.kt`、
`camera/FrameRateFallback.kt`。

- `core/DirectDisplay.ets`（纯逻辑）：`DirectDisplay.matrix(viewW, viewH, bufferW, bufferH,
  rotationDegrees, mirrored)`——行主序 3x3（`Array<number>`，长度 9）；顺时针旋转 + 等比居中信箱
  黑边 + 镜像作用在**旋转之后**的屏幕横轴；非 90 倍数 sensorOrientation 按旋转后包围盒
  `bw·|c|+bh·|s|` 算黑边；任一尺寸 ≤0 退单位阵。与 GL 路径「纹理旋转 + 等比 contentRect」等价，
  两渲染模式因此同角度。
- `camera/GlRenderSuppression.ets`（纯逻辑）：`degradeSuppressed(recordTearingDown, segmentRotating)`
  ——停止序列窗口 / 分段轮转重挂窗口的析取；窗口内丢帧好过 MEND→DROP 清账与 DROP→直通慢放。
- `core/FrameRateFallback.ets`（纯逻辑）：`ceilingFps(ranges, cap)` 只排除被 cap 截断后不可用的范围
  （`min(hi,cap) >= lo`），修复旧口径「`usable > r.lo` 排除全部固定档 + 兜底取全表最小上界」的
  120fps→15fps 缺陷；一个可用范围都没有返回 0 = 无退档目标。
- 三模块零平台 import，已登记 `PureLogicBarrel` 编译探针（不改变 `signature()` 输出）。
- **测试**：`test/pure/direct-display.test.ets`（7 例：上游 DirectRulesTest 矩阵 5 + 直显预览选档 2；
  此前 test/pure 无 `pickDirectPreviewSize` 覆盖，属新增而非重复）、
  `test/pure/frame-rate-fallback.test.ets`（7 例：纯函数全表 6 + 突变自证 1，含旧口径突变体
  `usable > r.lo` + 最小 hi 兜底，S3-01 本体断言旧口径 15 / 新口径 60）、
  `test/pure/gl-render-suppression.test.ets`（2 例：真值表 4 组合 + `||`→`&&` 突变自证）。
  共 +16（369 → 385）。
- **登记（跳过）**：① `DirectSink` 平台胶水（TextureView/SurfaceTexture/Surface/Matrix/Handler）
  本侧无等价物不移植——ArkUI 只有 XComponent surface = GL 路径；② `PreviewOrientation` 未移植，
  因此 `directDisplayMatrix` 在本侧没有调用方；③ 上游的引擎源码结构守卫臂
  （`Camera2Engine.ceilingFps`/`syncFpsPick`/`degradeArcConvert`、`CameraScreen.beginSession`
  重挂 mark/await/clear 顺序）在鸿蒙侧无对应接线点——抑制判定在 native `cpp/gl_render.cpp`
  的 C++ 分支，不在 ArkTS 可扫描面，本批只交付纯函数与其全表用例，不伪造接线。
- **回归**：pure-test 36 files / 385 tests / failed=0；clean `assembleHap`（release）BUILD SUCCESSFUL。
- **真机待验/留白**：三件当前都无 ArkTS 调用方（DIRECT 直显通道与引擎退档/降级窗口在本侧不存在），
  真机无可观测面；等 C++ 侧需要与 ArkTS 共用抑制口径时经 napi 导出回接。

## 交付批（G：媒体库分段归组，2026-10-23）

上游 `media/SeriesGroup.kt`。本侧录制早已按段写 `series_part`（WotaDb），媒体层也已把
`seriesId/partIndex` 带回 `VideoClip`——缺的只是展示层的归组。

- `media/SeriesGroup.ets`（纯逻辑）：`groupSpans`（seriesId 归组；≥2 段才成组，某段被删/回收
  剩 1 段自动退化；partIndex 升序、缺失排尾不散组；**站位取组内首见位**；`collapse=false` 逐字等价
  扁平基线）、`galleryEntries`（代表段 = partIndex 最小者，组总时长由 parts 求和）、
  `expandSeriesTargets`（组级动作扇出，按 id 去重）。
- `pages/Gallery.ets`：列表改吃 entries——组卡带「N 段」角标与「N 段 · 总时长」；收藏/移入回收站/
  还原/彻底删除全部扇出到整组（提示与二次确认框写明段数），避免「只动了首段」的半截状态。
- **测试**：`test/pure/series-group.test.ets`（15 例：上游 SeriesGroupTest 10 + 扇出 3 + 突变自证 2）。
  共 +15（354 → 369）。
- **回归**：pure-test 33 files / 369 tests 全绿；clean `assembleHap`（release）BUILD SUCCESSFUL。
- **留白（登记）**：分段逐个展开（上游有 SeriesSheet；本侧点组卡进首段）、折叠回退开关（恒折叠）。
- **真机待验**（T37）：跨段录制的两条/三条成组显示、角标段数正确、组内动作一次动到全部段、
  回收站/还原后组自动退化或恢复。

## 交付批（F：取景参考线 11 档，2026-10-23）

上游 `core/Constants.kt` 的 `RefLineType` + `ui/widget/RefLineOverlay.kt`；同时把 T31 那版
Divider 3×3 网格并进同一套绘制（旧 builder 删除）。

- `core/RefLine.ets`（纯逻辑）：11 档位表（bit 与上游逐值一致、中文名照搬）、`fullMask`=2047、
  `typesOf/maskOf/isOn/toggle`；`inscribedBox`（画面内接框）与 `horizonEndpoints`（水平线端点，
  符号约定「正 roll = 右侧偏低 → 右端抬起」）。
- `ui/RefLineOverlay.ets`（UI）：Canvas 一次画完全部命中档位——三分法/对角线（含中心圆）/十字准线
  （中心 4vp 缺口）/广播安全框（70% + 四角加粗）/安全区域（95%+90%）/6 分网格/中心标记（小方框 +
  四角括号）/水平线（吃 roll）；三条红线最后画、2vp 加粗压在其余线之上。
- 接线：`SettingsStore` 增 `default_ref_lines`（键名同上游）+ 设置页「取景参考线」11 颗多选 chip；
  相机页 `refLineMask() = prefs 掩码 | (gridOn ? 三分法 : 0)`，`aspect` 传 0（全屏直出，未做
  letterbox——登记），水平线受「显示水平仪」总开关门控。
- **测试**：`test/pure/ref-line.test.ets`（13 例：上游 RefLineMaskTest 5 + 位值契约 1 +
  RefLineHorizonTest 5 + 内接框 2）。共 +13（341 → 354）。
- **回归**：pure-test 32 files / 354 tests 全绿；clean `assembleHap`（release）BUILD SUCCESSFUL。
- **真机待验**（T36）：11 档逐档看清（尤其三分法/网格线是否可见——T31 曾因百分比定位整片不可见）；
  水平线方向与姿态仪一致；红线压在最上层；横屏下线条仍横跨画面。

## 交付批（E：水平仪/俯仰仪，2026-10-23）

上游「需求 24、25 行必须」的姿态仪（`LevelMath` + `SensorLevel` + `AttitudeMeter`）；此前 PORT_MAP
里 `LevelMath` 只有 ☐、界面完全没有。

- `core/LevelMath.ets`（纯逻辑）：`displayVector`（整数倍平面旋转）、`rollOf`（`atan2(-ux,uy)` 后
  折到 (-90,90]——真机 −97.9° 的回归点）、`pitchOf`、`foldToHalfCircle`、`isLevelOf`；`LevelConst`
  收全部纯常量（容差 1.5°/低通 0.30/节流 0.1°/丢帧闸/震动 100ms/watchdog 600ms×2）。
- `camera/LevelSensor.ets`（平台）：GRAVITY 优先、ACCELEROMETER 回退；分量级低通（滤波不在角度上做，
  避免 ±90 绕回跳变）；NaN 与近似失重帧丢弃；平放时 roll 保持上次值；俯仰/横滚两路独立的
  回正震动（挂在「落值」闸门内侧，防慢速跨线时逐帧连震）；首启静默自愈重挂。
- `ui/AttitudeMeter.ets`（UI）：Canvas 人工地平仪 + 两模式切换 + 状态行。
- 接线与设置：相机页右上浮层；`level_enabled` / `level_buzz_enabled` 两个 prefs 键 + 设置页「取景辅助」分组；
  `ohos.permission.VIBRATE`。
- **测试**：`test/pure/level-math.test.ets`（7 例，上游 LevelMathTest 全量 + 常量口径）。共 +7（334 → 341）。
- **回归**：pure-test 31 files / 341 tests 全绿；clean `assembleHap`（release）BUILD SUCCESSFUL。
- **真机待验**（T35）：竖屏/横屏各看一次水平仪方向（横屏不换算会读成 ±90）；快速转动跟手度；
  平放时读数不跳；回正震动只在跨入容差带时震一次（关掉开关两路都不震）；设置页两开关即时生效。

## 交付批（D：ClipExport 音轨平移 + PlayerScreen 同步来源 UI，2026-10-23）

上游 `e950328` 剩下两个语义锚点（同步脚本 `[3/4]` 一直列着的 `ClipExport.kt` / `PlayerScreen.kt`）
的接线；此前只落了纯逻辑（audioShiftUs / TrackSync 扩容），导出链与播放页没接。

- **导出侧平移（端到端）**：`player/ClipRule.ets` 补 `remuxAudioSampleWritable(relUs, shiftUs,
  endRelUs)`（上游同名纯判据）；`ClipExporter.exportClip` 增 `audioShiftUs` 形参并传 native；
  `cpp/clip_exporter.h/.cpp` 的 `Start`/`Job` 增 `audioShiftUs`，**预扫与拷贝循环吃同一枚值**
  （`audioRel = rel + shift`，封轨/负值/单调桥一致），无音轨时归零；视频轨不污染。
- **修复侧平移**：`record/ArcRepairRunner.ets` 的 `run(..., capOffsetMs, ...)`；`cpp/arc_repair`
  的 `Start`/`Job` 增 `capOffsetMs`，只对「确有第二轨」的 lane 1（轨序 0=环境/1=内录）平移，
  并新增**平移后可写性预检**（用视频首样本 PTS 作保守基准，防「内录被整体前移成零样本轨」崩 muxer）。
- **播放页同步来源 UI**：新建 `player/SyncPrefsStore.ets`（`@ohos.data.preferences` 适配
  TrackSync 五键；读不到 = 未同步，绝不补 0 冒充）；`pages/Player.ets` 进页面/打开导出面板时读快照，
  导出面板显示「已同步 +Xms（自动校准/手动）」或「未同步：导出按 0 平移」，并加诚实边界行
  「原片分享不重写容器；只有本页导出/光弧修复会应用同步残差」；导出与修复都在起跑那一刻取偏移。
- **接线守卫**：`test/pure/clip-export-shift.test.ets`（8 例）——纯判据三条（上游表全量）+ 结构守卫
  （ArkTS 门面收 shift、播放页用 `audioShiftUs(...)` 与 `SyncPrefsStore.read`、native 预扫/拷贝
  同吃 `job.audioShiftUs`、视频不污染、`capOffsetMs` 只作用 lane 1 且预检在）。
- **仍留白（T27-4）**：偏移本身还没人算（手动「同步音轨」与停止后自动校准需要 AudioAlign 平台
  解码 + 内录音轨）——本侧目前只会消费已落盘的结果。
- **回归**：pure-test files=30 tests=334 passed=334 failed=0（328 + 6：新增 `clip-export-shift.test.ets`
  6 例——3 条纯判据对拍上游表 + 3 条接线守卫）；clean `assembleHap`（release）BUILD SUCCESSFUL（native 重编通过）。

## 交付批（C：应用内更新，2026-10-23）

上游 `971874a`（更新弹窗展示 Release 说明）+ `376b3ef`（说明镜像也读 / 扩充镜像表 / 下载前测速选路）
的完整移植，并把 `82943cf` 的双平台产物命名接进来。**本侧原先没有 `update/` 目录，本批是整块新建。**

- **新模块（4 件）**：
  - `update/UpdateChecker.ets`（纯逻辑，零平台 import）：UpdateConfig（仓库常量 + 5 枚 HTTPS 镜像 +
    2 枚 API 镜像）、CompareVersions（上游 versionName 口径 + 本侧 **versionCode** 口径）、
    MirrorNode/MirrorChain、ReleaseAsset/ReleaseInfo、ReleaseJson（手写 JSON 提取器 + 资产名安全判据 +
    `parseSha256Sums`）、ReleaseNotes（去 Markdown/隐哈希行/4000 字按行截断）、RedirectProbe、
    ProbeResult + `probeLatestRelease`（三段编排、取消探针在每个节点前检查）。
  - `update/UpdatePure.ets`（纯判据）：probeBytesAllowed/bytesOverLimit/downloadCandidates/
    rankChannelsBySpeed/staleFilesOf/sha256Matches/isHttpsUrl/isSafeRemoteUrl/isPublicHost（IPv4/IPv6 私网判据）、
    自带 `parseUrl`（不依赖平台 URL 实现，userinfo 不能绕过主机判据）。
  - `update/Sha256.ets`（纯 SHA-256）：流式 update + digestHex，NIST 四条标准向量钉住。
  - `update/UpdateDownloader.ets`（平台）：`@ohos.net.http` 的 fetchJson（短读超时 10s）、**并发测速选路**
    （每渠道 256KiB / 3s 读超时 / 6s 预算）、`requestInStream` 流式落盘（.part 按代唯一）、
    连接 10s/读 30s、Content-Length 与探测 size 一致性、PK 魔数、磁盘帽、SHA-256 端到端校验。
  - `update/UpdateInstaller.ets`（平台）：结构核对 + `DocumentViewPicker.save` 另存到用户可见目录 +
    浏览器打开 Release 页兜底。
- **UI**：`pages/Settings.ets` 关于卡新增「检查更新」行（Idle/Checking/Cancelling/Latest/Failed 五态，
  检查中再点 = 取消），更新可用弹层（版本号 + 包体大小 + 说明滚动区 max 260vp）与下载弹层
  （选路 → 进度 → 完成「保存到文件/打开下载页」→ 失败）；版本号读数改为 `bundleManager` 实读，
  不再硬编码。**红线保持**：联网只由行点击触发（无 LaunchedEffect/生命周期自动检查）；取消标志按代际隔离
  （旧代进度与回执一律丢弃）。
- **权限**：`module.json5` 增 `ohos.permission.INTERNET`（normal，无需运行时授权）。
- **测试**：`test/pure/update-logic.test.ets`（版本比较/versionCode/镜像链/JSON/资产名/redirect/
  三段编排含取消 + ReleaseNotes 整形 + SHA256SUMS 解析）、`test/pure/update-channel-speed.test.ets`
  （排序/候选/URL 门槛/上限/过期文件/SHA 判据）、`test/pure/update-sha256.test.ets`（NIST 向量 + 流式
  等价 + 64KiB 边界）。共 +52 例（276 → 328），全绿。
- **两处「照搬会坏」的坑（本批发现并修正）**：
  ① 上游按资产名挑包用 `contains("universal")`；鸿蒙资产是 `-signed-Harmony.hap`/`-unsigned-Harmony.hap`，
    且 `unsigned` 里含 `signed` 子串——裸 `indexOf('signed')` 会误中 unsigned 包，改用 `-signed-harmony` 判据
    （单测钉住）。
  ② 上游校验值来自 Release 说明的 `SHA256:` 行，而本仓库那行由 build-apk.yml 回填的是**安卓 APK** 哈希；
     拿它校验 HAP 必然失败。改为读同 tag 的 `SHA256SUMS` 直连资产（HAP 工作流产出），并加单测钉住
     「探测结论的 sha256 恒为 undefined」。
- **回归**：pure-test files=29 tests=328 passed=328 failed=0；`assembleHap`（release）BUILD SUCCESSFUL，
  新增 update 模块无告警。
- **真机待验**（T33）：① 有网/断网各点一次「检查更新」的文案与取消；② 有新版时说明区排版与滚动；
  ③ 下载进度/取消（确认取消后不再弹回对话框）；④ 另存到文件管理后点按安装（需 signed HAP 且 profile
  含本机 UDID；unsigned 包应被系统拒绝——这是预期）；⑤ SHA256SUMS 缺失的老 Release 走 PK+size 兜底。

## 纯逻辑测试（T22 落地，T23/T24/T25/T26/T28/T29/T30 扩展，I 批 2026-10-23 扩展）

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
- **已落用例（652）**：record-health（21，直译上游 RecordHealthTest.kt 已移植面，判定顺序五条
  各被点名）、compare-timeline（26，T22 几何 9 + T24 内核 16 + T26 审查修复 1（右片重建恢复目标四域臂），对拍上游 CompareTimelineTest.kt
  18 个方法，off=0 回归红线三条独立）、slider-ladder（9，上游 SliderLadderTest.kt 直译）、
  segment-policy（5，常量/阈值边界/前缀和/丢帧上界/摘要）、clip-rule（6，MIN_KEEP/normalize/
  命名/关键帧/平移/音轨序数）、media-format（3，formatSignedDuration 正/负/0 与 formatDuration
  口径对齐）、compare-audio（16，T24：上游 CompareAudioTest 14 个 @Test 全量断言（两条命令顺序臂合一条）+ SplitAudioPickTest 3 条分屏臂，含命令顺序与桥函数）、audio-sync（10，T24：重采样 3/包络 2/粗对齐 2/FFT 2/精修 1）、compare-practice
  （6，T24：armed 门/take 一次性/普通录制不污染）、compare-history（12，T24 10 + T25 2：容错解析/数字串宽松解析（对齐上游 getLong）/去重插首/
  截 12/编码键序与往返）、capture-state-machine（16，T26：上游 14 条转移 + 状态/事件短名逐字）、
  capture-silence（5，T26：常量/verdict 全表/fedMsOf/gate 语义/判据×门控桥）、audio-probe（3，T26：
  档位体检全表 + chunk/buffer 字面量 + dbOf）、capture-sources（3，T26：源枚举契约/优先级/短名）、
  track-sync（11，T26：合成 4/resolve 2/signed 3/失败分类 2；上游 11 条纯函数用例全量，
  另 1 条 extractor 源码守卫随 T27；MIN_CONFIDENCE 同源为本侧额外断言）、pcm-decode（7，T26：PTS 落位/补零/
  PCM16/24/F32LE/降单声道/多段 append）、arc-repair-plan（15，T28：帧数/抽帧点/进度/整数源
  oracle 扫描/29.97 与 25.4 真值位次）、arc-repair-flow（9，T28：指令序列/PTS/逐帧不变量）、
  arc-repair-merge（12，T28：不造色/死区三分支/块整体性/多块索引/奇数宽高）、
  arc-repair-verdict（5，T28：三格判据 + keep 四组合）、arc-repair-session（31，T29：parseProbe 3/
  速率 4/NO_NEED 3/去重 3/帧数 3/状态 4/归因 3/note 1 + 取消终态 1；T30：describeRepairError 3 +
  audioLaneNote 1；T30 审查修复：planForTarget 接线 1 + 逐档 NO_NEED 1）、
  update-logic（35，C 批：版本比较 4/versionCode 2/镜像链 3/JSON 4/资产名 1/redirect 1/编排 9（含取消 2）
  + ReleaseNotes 6 + SHA256SUMS 1 + 「APK 哈希不得当 HAP 校验值」1）、update-channel-speed（12，C 批：
  排序 3/候选 2/URL 门槛 2/解析 1/上限 1/过期文件 1/SHA 判据 1/突变体 1）、update-sha256（5，C 批：NIST 四向量
  2 组 + 流式等价 + 64KiB 边界 + digest 后封口）、clip-export-shift（6，D 批：上游 ClipExportShiftTest
  三条纯判据全量 + 接线守卫 3（ArkTS 门面/播放页/store 接线、C++ 预扫与拷贝同口径、修复 lane 1 与预检））、
  level-math（7，E 批：上游 LevelMathTest 全量六条 + 容差/常量口径一条）、
  ref-line（13，F 批：上游 RefLineMaskTest 5 + 位值契约 1 + RefLineHorizonTest 5 + 内接框 2）、
  series-group（15，G 批：上游 SeriesGroupTest 10 + 扇出 3 + 突变自证 2）、
  direct-display（7，H 批：上游 DirectRulesTest 矩阵 5 + 直显预览选档 2——此前无 pickDirectPreviewSize 覆盖）、
  frame-rate-fallback（7，H 批：上游纯函数全表 6 + 突变自证 1，S3-01 旧口径 15 / 新口径 60）、
  gl-render-suppression（2，H 批：抑制真值表 4 组合 + `||`→`&&` 突变自证；上游接线守卫臂本侧无对应点，跳过登记）、
  params-persist（28，I 批：白名单契约 3（24 条/无重复/逐字顺序/排除清单）+ restore 正反例 6
  （24 键逐项/坏值韧性/fps 两端钳制/>0 门槛/LOCK 归一/状态位不受改）+ 尺寸与枚举 2 + 会话影子 8
  （只写变化键/不覆盖设置页/无差异 no-op/影子前进幂等/写失败重试/resync/意图画幅）+ 批写 3
  （多字段只 commit 一次/无变化不 commit/commit 失败不推进影子）+ 键名迁移 2
  + 落盘节流 4（首请求立即/窗口内合并尾部补一次/尾随后重新起算/cancel 后不触发）；
  上游 ParamsPersistWiringGuardTest 的 Compose 源码扫描臂本侧无对应（接线点在 Camera.ets，人工核对）；
  ParamsPersistSessionTest 的 AF 能力保留臂属 WotaParams.applyAbility，本侧已有 core 测试面覆盖）、
  hud-metrics（11，J1 批：上游 `LiquidMergeTest` 的 hud* 三组全量 + 宽度账/安全余量反面对照 + 120%
  字体档与高度预测；手写字面量）、hud-page-grid（14，J1 批：上游 `HudPageGridTest` 页格数学 5 组
  （cellPx 68/76、box 22×10 与 20×9、指针往返 440 点、下界钳上界不钳、占用整块/搭档/哨兵、存储护栏）；
  落点分支属 J2，`placeEntryAt`/`freeCellNear` 两臂不测）、hud-layout（49，J1 批 35 + J2 批 14：上游
  `HudLayoutClampTest` 全量（横向/纵向/退化带/两轴/TOP 绝对支/操作栏下缘/px→dp/矩形/列行上限/
  格子钳制/落区 + **J2 补的底栏两档吸附 4 条、重叠成对 1、最内层命中 1、操作栏交叠 1**）+
  `HudLayoutCodecTest` 全量（默认串手写字面量/五段格式/默认顺序/命名空间/手写已知串往返/坏串与未来版本/
  v1 迁移/半截串/重复/半吊子位置/底栏 x 抹除/未知 id/坏格子后缀/隐藏保留/重置撤销/home 一致性/
  全条目覆盖 + **J2 补的编辑后往返/显式格只落网格串/v1 逐颗推导落点/v1 编辑顺序迁移/v1 拖过+隐藏整串/
  配对同格往返/坏后缀回推导格 7 条**）+ 编辑页可见集与 hud_items 掩码契约 2 条）、
  hud-layout-grid（38，J2 批：上游 `HudLayoutGridTest` 27 + `HudLayoutPairCellTest` 11 全量）、
  hud-layout-rowpitch（11，J2 批：上游 `HudLayoutRowPitchTest` 全量，含两个文字缩放旋钮的角落矩阵）、
  hud-layout-drag（24，J2 批：上游 `HudLayoutDragTest` 13 + `HudLayoutSwapGuardTest` 行为面 9 +
  T9/T9 孪生的行为半段 2；上游的三处 Compose 源码钉扎（扫 `HudLayoutEditor.kt` 的 `snapOf`）属 J3）、
  hud-readout-row-plan（9，J2 批：上游 `HudReadoutRowPlanTest` 的 `planReadoutRow` 部分；
  `chipTierFor`/`WotaChipTier` 两条属 UI 令牌层，留 J3 接线）、
  hud-items-wiring（8，J3a 批：`maskOrDefault` 坏值表 + `isOn/toggle` 往返 + 默认档经生产算式
  到行列（对拍 HudDefaultIsoTest）+ 全七档/极窄宽分组 + 去重红线 + 源码接线守卫 3）、
  hud-layout-order（6，J3b 批：`withZoneOrder` 真编解码往返/normalize 收口/`visibleOrderOf`
  顺序跟表走/默认表=枚举序回归线/编辑页与相机页接线守卫 2）、
  media-mime-thumb（12，MS 批：MIME/UTD/title 映射表 + ThumbLru 驱逐序）、
  media-share-wiring（4，MS 批：三条链单一真源 + Gallery 入口与扇出 + 零新增权限红线 +
  createAsset since 22 禁用）、bt-speaker（8，BT 批：合并/排序/电量文案 6 + 接线守卫 2
  （含减法红线 setVolume/avsession/startBluetoothDiscovery 禁用与 ACCESS_BLUETOOTH 声明））、
  record-task（5，RT 批：闸迁移语义 3 + 接线守卫 2（TASK_KEEPING 判据、起停接线、
  KEEP_BACKGROUND_RUNNING 声明、无 ForegroundService 仿写））、
  focus-pill（5，FC 批：AfMode 契约值/diopterLadder 档/dock 与录制可调集接线/弹层能力闸/
  tint 不迁守卫）、media-share-wiring 增 2（GX 批：分段展开/折叠开关接线守卫 +
  重命名不迁决策守卫）、avscreen-probe（6，P27 批：状态串解析全表 + 接线守卫——双源配置/
  缓冲取还/CMake 实名链接/页面按钮与销毁兜底）。
- **降级路径（预登记）**：若某测试文件的依赖闭包被 Diag 等顶层副作用卡死，只保零依赖三件
  （record-health/compare-timeline/slider-ladder）并显式打印 skip——本批未触发，
  segment-policy/clip-rule/media-format 经 @kit stub 链（ArcDropLog 顶层 import fileIo）正常执行；
  T24 五件均为零平台依赖（无 @ohos/@kit import），不经 stub。
- **对拍口径（T28 复核）**：期望值一律手写字面量（不用实现反推）；上游用例中依赖**平台文件/源码
  结构守卫**的条目（如 CaptureSilenceVerdictTest 的 bodyOf/KotlinSourceScan 守卫、CaptureParamAuditTest
  的平台调用点守卫、TrackSyncTest 的 extractor 守卫、ArcRepairOkTest 的 ArcRepairRunner 守卫、
  ArcRepairMergeParityTest 的 ARC_MERGE_FS 源扫描）不进本侧文件——纯函数断言面已全量移植，
  平台守卫随 T27/T29 以 ArkTS 结构检查补；T24 起 DecideCommands/域判定已在本侧。

## 真机待验（T22 收口批 + T23 交付批新增，不阻塞）

### 模拟器走查已验（2026-10-24，Pura 900 / HarmonyOS 6.1.1 API 24 / x86_64，hdc+uitest 自动化）

- **C 更新链路端到端 ✓**：检查更新 → GitHub API 拉取 → 「发现新版本 v0.0.10-r01 · HAP 3.8 MB」
  弹窗（ReleaseNotes 去 Markdown 整形生效、真实说明展示）→ 下载更新 → **「下载完成，已校验通过」**
  （PK 魔数 + 体积 + SHA256SUMS 端到端校验全过）→ 保存到文件 → 系统 DocumentViewPicker 打开
  （默认名 v0.0.10-r01-unsigned-Harmony.hap、目录导航正常）。真机上仅需复测真实证书链场景。
- **P27 探针端到端 ✓**：探针全生命周期（Create→SetCallback→SetMicrophoneEnabled(true)+
  ALL_PLAYBACK→Init→Start→2.7s 计数→Stop→Release）**零错误码、零隐私拦截**，窗口内两类
  音频缓冲均 0 → `SILENT_WINDOW`（模拟器无声源，判读与复测指引按设计给出）。
  双源缓冲送达结论 → 真机（有声源环境）。
- **BT 降级态 ✓**：模拟器无蓝牙适配器 → `蓝牙未开启` 引导态 + 减法页脚说明（BtPanel 设计点）。
- **J3a/J3b/E/F/G 渲染 ✓**：相机页 hud_items 读数条默认四档（快门/帧率/码率/感光度）、
  姿态仪「已回正」、Dock 八颗、设置页参考线 11 chip/取景读数 7 chip/调整顺序入口、
  版本 7 连点解锁开发者入口、媒体库组卡。
- **J3b 顺序编辑链路 ✓**：编辑页初始序 = 枚举序（默认表）→ 点「帧率」行 ↑ → 编辑页变为
  帧率 > 快门 > … → 返回相机页**读数条按新序渲染**（帧率 > 快门 > 码率 > 感光度）——
  `withZoneOrder`→`encode`→`hud_layout`→`decode`→`visibleOrderOf`→渲染 全链路设备级打通，
  v2 契约串持久化往返实证。
- **模拟器限制（证据）**：AVRecorder `PREPARE_FAILED`（录制链在模拟器不可用 → RT 切后台续录、
  MS 分享/存相册（需成片）、水平仪真实传感器方向、T27 双源缓冲 → **真机批**）；
  Index 开发者页按钮行横向溢出已修（7f914d9）。
- **取证操作**：`node scripts/device-check.mjs` 预检 / `log` hilog / `check` 清单；
  UI 自动化 = `hdc shell uitest uiInput click/swipe` + `dumpLayout`（版本 7 连点需单条 shell
  链式执行压进 2s 窗口）。


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

### T25 对比页接线（6 项）

- **起播与交接**：进页双片 attach 后按「播放」——off=0 双素材同步起播；off>0 拖到负轴后
  左窗应黑层遮挡且左引擎停、右片独播，T 过 0 自动唤醒左片（无跳帧/无 seek 抖动）。
- **尾段与循环**：off=0 时较短片播尽 → 另一侧独播、短侧黑层；到 Tmax 且「循环:开」时回绕
  Tmin 并恢复该处有素材的一侧；「循环:关」到末帧停驻，再点「播放」从 Tmin 重播。
- **拖动让路**：播放中拖进度条（含负轴）不触发自动 seek/nudge，松手落位后由 decide 接管；
  pre-roll 读数显示负值（左端标签与滑杆值）。
- **音轨切换**：单音轨片不显示「音轨」钮；双音轨片切「内录」应有声、切回「环境」恢复；
  换右片后 chip 应按当前音频侧重新出现（左片为双音轨时不应消失）；静音中切内录/环境，
  解除静音后应生效（`hdc hilog | grep WotaPlayer` 看 audio group/trackIndex 选择日志）。
- **续播与历史**：播放中换右片/左或右 surface 重建后应自动续播且位置不回 0（重建侧复播，
  另一侧不停）；历史面板条目名正确、删除后不再出现；缺失条目（删掉右片文件后刷新）标红且
  点击不换片。
- **单侧与域复活（审查修复项）**：只选左片（未选右片）播放时滑杆/读数随左钟前进；
  右片报错态同样不冻结。播放中从独播域（pre-roll 或尾段）拖回双覆盖区，松手后 dormant 侧
  应立即复播（无黑层、无冻结帧）；用户暂停状态下拖动则保持双停（播放权归用户）。
- **右独播域重建（T26 审查必验）**：播放到左片已播尽、右片独播（尾段）时触发右片 surface
  重建（切后台/分屏/旋转），回页后右窗必须从**重建前位置**继续（不回跳左片时长、不回 0）；
  off>0 的 pre-roll 段重建同理（不得回素材 0）。检查 `hdc hilog | grep WotaPlayer` 的
  attach/seek 位置与判定 T。
- **离线修复 CPU 合并性能/色彩（T28 交付项，T29 执行器落地后验）**：1080p 素材离线修复
  需在可接受时长内完成（T29 决定 ArkTS 或 native）；成片弧边缘不得出现原片不存在的颜色
  （极橙+黑不得变珊瑚粉），死区 0/12/32 三档叠加强度可辨、相同亮度场景不叠加；29.97/25.4
  源成片帧率与时长按真值位次（721 帧 / 720·1e6/24 末 PTS）。

### T29 离线修复执行器（7 项，真机首验）

1. **probe 串正确性**：正常/mov/mp4/无音轨/无视频四类源的 probe 串与 `parseProbe` 一致
   （宽高/旋转/容器 fps/时长/音轨数/mime）；无视频源返回 `err:ARC_NO_VIDEO`。
2. **同步解码输出布局**：`hdc hilog | grep WotaArcRepair` 看 stride/sliceHeight/pixelFormat
   实际值；NV12/NV21/I420 三档都能解（ARC_CPU_YUV 不得出现在普通 1080p 素材上）。
3. **抽帧位次与 PTS**：30→24 成片 45 帧源出 36 帧、末 PTS=35·1e6/24；29.97→24 出 721 帧、
   末 PTS=720·1e6/24；状态串 `videoSamples` 必须**严格等于 dstFrames**（CSD 不得计样；
   偏差值见 done 日志 `delta=`）。
4. **不造色**：极橙+纯黑素材成片弧边缘无珊瑚粉 (255,109,158)；死区 0/12/32 三档叠加强度可辨、
   相同亮度场景不叠加。
5. **旋转**：竖拍源成片方向正确（解码 format 剥旋转、muxer 写原始旋转归一值）。
6. **取消/超时/失败**：中途取消 → 状态 **`cancelled`** 终态、媒体库无半成品残留（含 linger
   超时路径：取消后立刻 `hdc shell ls files/videos` 确认新成片已消失）；盘满/坏源 → `failed:<code>`、
   fd 全关（`hdc shell ls -l /proc/<pid>/fd` 抽查无泄漏）。
7. **音频直拷**：带音轨的源 → 成片含与源同轨序的音频轨（`done` 的 audioSamples > 0）；
   无音轨源 audioSamples=0 且面板无音轨提示。

### T31 取景页 HUD（16 项，因签名不匹配待用户重装后验证）

> 取证基线（旧包，2026-10-22 17:09）：XComponent `[0,124][1260,2629]`（顶部状态栏 124px、
> 底部露白条 91px）；胶囊 `镜头:—/画幅:—/EV:-` 而顶栏已为「广角/1080p/+0.0EV」；单行 Scroll
> 仅 6 颗可见（EV 被裁）；录制键 1195×144px 绿色圆角矩形；网格完全不可见；系统栏白底黑图标。
> **已安装包签名指纹 `48A3BDCE…` ≠ `.local-signing` 自签证书 `C8D36043…`，未执行安装**，
> 以下各项需用户在 DevEco 以同证书重装新 HAP 后逐项走查（复审新增 12-16 项，均为真机可见差异）。
>
> 设备临时文件：本轮取证在设备留下 `/data/local/tmp/t31_before.json` 与
> `/data/local/tmp/t31_before.jpeg`（本地副本已删，设备随后断连未能在场清理）。
> **重连后执行 `hdc shell rm -f /data/local/tmp/t31_before.json /data/local/tmp/t31_before.jpeg`**；
> 若重连时两文件仍在，先只读 `hdc shell ls -l` 确认路径，再仅删除这两个文件，不触碰其它文件。

1. **沉浸式**：XComponent 覆盖 `[0,0][1260,2720]`；状态栏/导航条透明、图标白色；底部无白条；
   顶栏读数不压挖孔（safeTop 留边生效）；**冷启动首帧无白色系统栏闪条**（EntryAbility
   loadContent 前 prime），从设置页返回相机后系统栏恢复透明、无可见重排。
2. **胶囊标签**：起镜后 `镜头:广角 / 画幅:1080p / 帧率:30 / 变焦:1x / 快门:AUTO / ISO:AUTO /
   EV:+0.0EV / WB:自动`；UI 树不再出现 `—`；切镜头/画幅/闪光等后一帧内更新。
3. **Dock 布局与 360vp 防截断**：8 颗胶囊 2 行×4 全在屏内（左右边距 12vp、间距 6vp）、单行 48vp、
   无横向滚动；本机 1260px / density≈3.5 即 360vp 宽：Dock 4 等分每颗约 79.5vp，旧左右 10vp 内边距下
   文本区仅约 59.5vp（`画幅:1080p`≈65vp 必截），本批胶囊 12fp + 左右 6vp 后文本区约 67.5vp；
   顶栏 5 等分约 45.6vp（`录30fps`≈49-52vp 必截，去「录」后 `30fps`≈35vp）——**UI 树
   （`uitest dumpLayout`）里 8 颗胶囊的 text 必须是全文**（`画幅:1080p`、`ISO:12800` 等不出现
   省略号），顶栏 `30fps` 读数同样不截断。若仍截断，按 3/5（Dock/顶栏）自适应分宽处理。
4. **更多面板**：点「更多」弹出闪光/效果/光弧/网格；点遮罩或收起关闭；网格开→关立即反映到画面。
5. **录制键**：圆形 72vp 白环 + 44vp 红点；录制中变 24vp 红方块；收尾中灰显且不可点（不真录，
   只按一次即停或仅观察静态样式亦可）。
6. **网格**：3×3 两条横线两条竖线可见且等分；关掉后消失（旧包网格全不可见）。
7. **去死读数**：顶栏只显示「30fps」，无「录30fps」也无「预览—fps」。
8. **设置页**：首行卡片紧接页头（不再 y≈827 大空白）；选项 48vp 可点；返回为深色胶囊；
   保存提示出现/消失不推动布局；返回相机后画幅/帧率读数与设置一致。
9. **开发者入口**：设置页默认无「开发者: Index / GL 试验」；版本号 2s 内 7 连点后显形；
   Index/GlPreview 有页头+返回，Index 文案 13fp 且可滚动。
10. **字体放大**：系统字体 1.0 与 1.3 两档下胶囊/读数/快门文案不裁字（`maxLines(1)`+省略）。
11. **方向**：横屏走查 HUD 是否裁切；本批保持系统方向（未接线 UIOrientation），
    若裁切则在 T32 锁竖屏并登记（`SafeInsets` 左右横屏留白同批，T32）。
12. **录制中弹层（复审 P3-3）**：录制中快门/ISO/EV/WB 四颗**都不是灰显禁用态**，点按均能开窗；
    ISO/EV/WB 可编辑，快门弹层显示只读 Note（不出现可拖滑杆）；非录制态四颗均可编辑。
13. **降级态底栏（复审 P3-1）**：相机断开（提示「相机已断开」）与相机权限被拒（提示+「重试」
    按钮）两态下，底栏内容出现后 8 颗胶囊与快门 72vp 全高仍完整可见、快门不被裁切；
    底栏无固定高度（内容多时向上长，Blank 收起）。
14. **弹层字号（复审 P3-4）**：快门/ISO/EV/WB/更多弹层内所有 text 的 fontSize ≥11fp
    （PillNote 由 10fp 提为 11fp），UI 树无 10fp 节点。
15. **系统栏串行（复审 P3-5）**：设置页↔取景页快速往返 ≥10 次后终态正确（相机透明栏、
    设置/媒体库黑底栏），无「黑栏残留/透明栏带到设置页」乱序；杀进程冷启首帧同样无白条。
16. **清理项（复审 P3-6/P3-7，源码侧）**：`GlCameraEngine.currentPreviewFps()/previewFps`、
    `Camera.fpsLabel`、`HudSpec.PILL_ROW_HEIGHT/gridFractions`、
    `HudTouch.MIN/HudShape.PANEL_RADIUS/HudColor.SUCCESS`、`SafeInsets.left/right` 已删；
    设备临时文件按上方命令重连后清理。

### T30 音频直拷 + 面板（8 项，真机首验）

1. **音频存在**：带音轨源修复后成片有声；`done` 串 audioSamples > 0，与 `ffprobe -show_packets`
   的音频包数一致（±0 逐样本计数）。
2. **双轨**：双音轨源成片含 2 条音频轨（轨序 0/1 与源一致）；`hdc hilog | grep "audio lane"`
   两条 per-lane 摘要 samples>0。
3. **同步**：音频起点与视频首帧对齐（`ffprobe` 首包 PTS 均 ≈0）；早于视频首帧的音频段被丢弃
   （skipped>0 时 start_time 仍 ≥0），无负 PTS 报警。
4. **顺序写兼容**：视频整轨后写音频的非交织 MP4 在各播放器（系统相册/第三方）可正常播放且
   音画同步；若有播放器拒播，T31 改 PTS 轮转写。
5. **面板取消**：修复中点「取消修复」→ 面板文案「已取消，半成品已删除」、媒体库无新成片；
   忙碌时按返回被拦。
6. **无音轨源**：纯视频源修复成功、`audioLaneNote` 不出现、面板不显示音轨数异常；
   不支持编码的音轨源 → 逐轨降级且 note 明示「成片将无音频」。
7. **面板可运行性（T30 审查必验）**：任意可修源进入面板后「开始修复」按钮**必须出现**并可点
   （守卫只看源侧事实）；源 24fps 时 24/25 两档按钮均置灰并给提示、30fps 源两档全亮。
8. **说明随档位一致**：选 25fps 与死区 32 后开始修复，成功 note 必须写「目标 25fps…死区 D=32」；
   面板切换档位时预览说明立即刷新（不得残留 24/12）。

### 上游对齐批（2026-10-23，随 T27 平台面一起验）

1. **A2DP 已连接但输出切回扬声器时预校准会误跑**——本侧 A2DP 判据是「设备列表含 A2DP」而非上游
   「活动路由」（OHOS 无对应 API，降级已登记）。误跑只多花约 800ms、不写错偏移（失败路径恒 shift=0），
   验收标准=不崩、不断录、偏移为 0。
2. **预校准双源采集若走 AVScreenCapture 将二次弹隐私确认**——T27-3 必须复用同一 capture 会话，
   或降级为「砍预校准、只留后校准」；验收标准=起录只出现一次系统确认。
3. 滑杆吸附：关掉「感光度吸附」后 ISO 弹层可停在连续值（下发请求不被钳回档位）；关→开→关往返后
   弹层档位恢复；录制中打开同参数弹层仍为只读。

## 交付后路线（T26 修订，2026-10-22）

- **T24：纯逻辑直译批（无真机依赖，优先）——已关闭（2026-10-22）**：CompareTimeline decide
  全量内核、CompareAudio、AudioSync DSP、ComparePractice、CompareHistory 落
  `entry/src/main/ets/player/`，新增 58 用例（53→111）。遗留项（Compare 真 T 派生、AB 持久化）
  随 T25 接线消费。
- **T25：对比页接线批——已关闭（2026-10-22，含审查修复）**：decide 编排/黑层/主钟 resolveT、
  音频计划真实下发、历史面板与 preferences、PlayerEngine 能力补齐、PureLogicBarrel 编译守卫、
  历史数字串容错；审查修复重建续播/独播域复活/音轨重探/单侧进度。剩余项（AudioSync 平台解码
  与自动对齐入口、练习动线）转 T26。
- **T26：内录纯逻辑降级版——已关闭（2026-10-22）**：CaptureStateMachine/CaptureSilence/
  AudioProbe/CaptureSources/TrackSync/PcmDecode 六模块 + 46 用例（113→159）+ T25 P3×4；
  平台面调研结论见「T26 平台调研」。
- **T27：内录平台面（真机批，分四步）**——
  - **T27-1 平台探针**：AVScreenCapture NDK 最小会话（`OH_ORIGINAL_STREAM`）验证双源
    `AcquireAudioBuffer` 可取到 inner/app playback PCM、采样率/声道回读、隐私弹窗与撤销回调；
    同时定「AAC 二次编码 vs PCM 直写」路线；
  - **T27-2 ENV 音轨**：GPU 成片补环境音轨（AudioCapturer/AAC 编码 + muxer 单音轨），
    消费 AudioProbe 算式与 CaptureSilence 判据（环境路不判静音）；
  - **T27-3 CAP 双轨**：环境+内录双音轨落 muxer（轨序 0/1）、`captureParamMismatch` 档位体检、
    静音提示接线（SilencePromptGate + captureSilent 提示通道）、撤销回退环境音（CaptureStateMachine
    Revoked）、音频侧健康判据；
  - **T27-4 消费面**：CompareAudio/ClipExport 双音轨消费（TrackSync 偏移合成 + PcmDecode 解码核心）、
    AudioSync 平台解码（AVDemuxer+AVCodec→8kHz PCM）+ 自动对齐入口、ComparePractice 练习动线。
- **T28：离线光弧修复纯逻辑——已关闭（2026-10-22）**：ArcRepairPlan/Flow/Merge/Verdict
  四模块 + 41 用例（159→200）+ T26 P3×3；执行器转 T29。
- **T29：离线修复 CPU 执行器核心——已关闭（2026-10-22）**：native probe/解码/合并/编码/mux、
  NAPI 四口、ArkTS 门面与会话模型 + 28 用例（200→228）+ T28 P3×4。音频与面板转 T30，GPU 转 T31。
- **T30：离线修复音频直拷 + Player 面板——已关闭（2026-10-22）**：全轨 AddTrack/逐轨直拷、
  会话模型文案、Runner 复用 probe、Player「修复光弧」面板 + T29 P3×7；真机首验 6 项见
  「真机待验」T30 节。
- **T31：取景页 HUD 真机取证与修复——已关闭（2026-10-22，源码侧）**：沉浸式窗口/安全区、
  胶囊响应式组件化、Dock 2×4、更多面板+圆形快门、确定性网格、设置页/开发者入口收口；
  复审收尾 5 项（底栏自适应/读数防截断/快门只读弹层/弹层 11fp/系统栏初始态与串行）+ 死代码清理；
  **真机复验待用户以同证书重装后执行**（签名不匹配不安装）。
- **T32：真机复验 + 相机交互与长尾**——T31 待验 16 项真机走查（含 360vp UI 树无省略号、
  降级态底栏、录制中只读弹层、系统栏往返串行）；UIOrientation 接线与横屏 SafeInsets 左右留白
  （按 T31 横屏走查结论：放不下则锁竖屏并登记）；对焦/捏合变焦胶囊；离线修复 GPU 路线（ArcRepairGl）
  与 1080p 基准；蓝牙/HUD 布局编辑器等长尾（应用内更新已在 C 批落地，见上）。
- **T30+：长尾批**——蓝牙音箱面板（`BtSpeakerController.kt`）、
  HUD 布局编辑器
  （~~`HudMetrics`/`HudPageGrid`/`HudLayout` 类型+夹取+序列化~~ 已于 2026-10-23 J1 批完成纯逻辑地基；
  ~~J2 网格几何与拖拽落点~~ 已于 2026-10-23 J2 批完成；剩余 J3 编辑器 UI 与录制页消费；
  ~~`ParamsPersist.kt`~~ 已于 2026-10-23 I 批完成）、
  相册入库/分享/缩略图/系列归组等媒体长尾（~~应用内更新~~ 已于 2026-10-23 C 批完成）。

## 建议移植顺序

1. ~~`core` + 各包纯逻辑~~（core 第一轮 ☑ 2026-10-21，`diag` 最小移植 ☑，`record` 锚点七件 ☑ 2026-10-20）——打下语义地基；
2. DevEco 骨架 + `diag`；
3. 相机预览（XComponent）→ `ui` 基础 HUD；
4. `MrRecorder`（AVRecorder）最小可用录制 → `CodecRecorder`（GL + AVCodec）；
5. 光弧工具链（复用着色器与 ArcKeepRule）；
6. `media` 媒体库 → `player` 播放/剪辑 → 对比播放；
7. T28 后按「交付后路线」推进：T27 内录平台面四步（T27-1 平台探针 → T27-2 ENV 音轨 →
   T27-3 CAP 双轨 → T27-4 消费面）与 T30 离线修复收口 → T31 GPU 路线/真机调优与 T32+ 蓝牙/
   HUD 编辑器等长尾（应用内更新 C 批已完成）。
