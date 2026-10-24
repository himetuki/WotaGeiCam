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
| `BtSpeakerController.kt`（媒体音量/播放控制/电量） | 平台绑定 | `@ohos.bluetooth.ble/constant` + A2DP，电量走 GATT 电池服务 | ☐ |

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
| `StorageAccess.kt` | 平台绑定 | `@ohos.file.fs` + `photoAccessHelper`（媒体库写入用安全控件/授权），与文件层一起移植 | ☐ |

## diag — 诊断日志

| 上游 | 类型 | 鸿蒙落点 | 状态 |
| --- | --- | --- | --- |
| `Diag.kt` | 平台绑定（薄） | `diag/DiagPure.ets`（纯函数层：diagLine/diagStamp/头部文案/轮转命名/丢弃自曝/帧账段，逐字段锚定）+ `diag/Diag.ets`（完整版：总开关默认关+级别档位门控、ring 3000/pending 3000 封顶丢最老并自曝、setInterval 1s 拍落盘 ≤500 行、512KB×3 轮转、崩溃件同步直写留 3、导出报告 2MB 预算）+ EntryAbility onCreate/onDestroy 接线。完整版落地 2026-10-21，对齐上游 342dd99。有意偏离（登记于 Diag.ets 头注释）：hilog 恒写双通道、setInterval 替代写盘线程（导出于 UI 线程同步读拼写有阻塞风险，TaskPool 化留待后续）、errorManager 'error' 替代 UncaughtExceptionHandler（无上一处理器链）、last-session.txt 替代 SharedPreferences、导出只写应用目录、时区 ±HH:MM/线程恒 main、deviceSummary 的硬件行用 hardwareModel 近似且设备行用 deviceType（语义与 Android Build.HARDWARE/Build.DEVICE 不同）、board/fingerprint 省行、ABI 用 deviceInfo.abiList。留白：公共目录导出、exportForShare 分享面板、总开关/详细档持久化（上游 WotaSettings diagEnabled/diagVerbose）与详细档 UI 入口（随设置页批）。装机验证项：开日志→导出→hdc file recv 看行格式与轮转；人为崩溃→crash-*.txt；清除后 logs 目录内本系统日志件（当前件/轮转件/崩溃件）清空，last-session.txt 保留，且日志关闭时不会立即新建当前件 | ☑ |

## media — 媒体库

| 上游 | 类型 | 鸿蒙落点 | 状态 |
| --- | --- | --- | --- |
| `WotaDb.kt` | 平台绑定（schema 纯逻辑） | `media/WotaDb.ets`：RDB（wota_media.db，version 1）四表逐字段对齐上游——`media_tag(id,mediaId,path,tag,createdAt)` + UNIQUE(mediaId,tag) + INDEX(tag)、`trash_item(uri PK,mediaId,name,deletedAt,origin)` + INDEX(mediaId)、`media_series(seriesId,startedAt,partCount)`、`series_part(seriesId,partIndex,mediaId,uri,durationMs)` + PK(seriesId,partIndex)；DAO 全 Promise、ResultSet 必 close、SQL 失败 Diag.e 后上抛。落地 2026-10-21，对齐上游 342dd99。版本不匹配按 Room fallbackToDestructiveMigration 等价实现 deleteRdbStore 重建（收藏/标签/回收站一起丢，登记）；schema 导出 JSON/Migration 未做 | ☑ 部分 |
| `MediaRepo.kt`、`SeriesStore.kt`、`SeriesGroup.kt`、`MediaTag.kt` | 纯逻辑为主 | `media/MediaRepo.ets`：扫描私有 filesDir/videos/*.mp4（stat size/mtime + AVMetadataExtractor 1.5s 超时补时长/分辨率，失败归零）、WotaDb 联表标签/回收站/系列、dateAddedSec 降序；ID=相对路径 32 位 FNV-1a（0→1；碰撞概率 1 万条约 1.2%/10 万条约 69%，升级 64 位或 (mediaId,path) 二次确认，登记）；探测 1.5s 超时只放弃结果、fd/extractor 延后到 fetch settle 回收；正在录制的半成品排除在列表外且动作层拒绝；收藏/移入回收站/还原/彻底删除（unlink + .drops.json + 清账）。`media/MediaTag.ets`：TagSpace（like/trash/长度 24/保留词大小写不敏感）、MediaScope/ListMode、ClipFilter/GalleryTabs（All→Like→Trash→自定义）/clampedSelection 逐行。`media/MediaFormat.ets`：groupTimeline（今天/昨天/同年/跨年）、formatDuration、formatSize、formatSignedDuration（T23 新增：负轴带符号时长，对比页时间线左端标签消费）。落地 2026-10-21，对齐上游 342dd99。**G 批（2026-10-23）补 `media/SeriesGroup.ets`**：`groupSpans`（seriesId 归组、≥2 段才成组、partIndex 升序含脏数据排尾、首见位定序、`collapse=false` 逐字等价基线）、`galleryEntries`（代表段 = 首段）、`expandSeriesTargets`（组级动作扇出 + 按 id 去重）。留白：SeriesStore 落库侧的 UI 展示开关（本侧恒折叠，无回退开关按钮）、自定义标签管理、相册 AllVideos、缩略图、外部改名（ID 脱钩，登记）。**P3 修复（2026-10-21）**：探测缓存/在途/负缓存提模块级 + 512 上限 + 陈旧剔除；未 settle 任务上限 4（stale 剔除不释放计数，真正挂起 open/fetch 的 fd/extractor 泄漏上界=4，登记）；满载时写 3s 负缓存并在 Gallery 提示“元数据暂不可用”；DB 重试入口 retryDb；switchTab 失败回退；sidecar stat 区分 13900002 与 IO 错误；open/stat 进超时任务；帧账 cameraFrames 明确为到达上界（非精确值） | ☑ 部分 |
| `MediaActions.kt`、`Share.kt` | 平台绑定 | 本批只落**本地**收藏/回收站/彻底删除（MediaRepo 动作，RDB 清账）；`photoAccessHelper` 相册入库、系统分享（shareData）、重命名、删除授权回路留白 | ☑ 部分 |
| `GalleryScreen.kt` | UI | `pages/Gallery.ets`：全部/收藏/回收站三页签（含数量）、卡片列表（文件名/时长/大小/日期/收藏星标）、空态/加载态/刷新、收藏切换、移入回收站、还原、彻底删除二次确认（AlertDialog）、元信息面板；卡片点击进入播放页。Index/GlPreview 均有「媒体库」入口。落地 2026-10-21。**G 批（2026-10-23）分段折叠**：列表改吃 `SeriesGroup.galleryEntries`——同一次录制的多段折成一张卡（角标「N 段」、副行「N 段 · 总时长」），代表段 = 首段；收藏/移入回收站/还原/彻底删除经 `expandSeriesTargets` 扇出到整组（提示补「（含 N 段）」，彻底删除的确认框也写明段数）。留白：缩略图、全屏手势锁定、时间线/网格/全屏滑动切换、自定义标签页、分段逐个展开（本侧点组卡进首段播放）、折叠回退开关、分享、重命名、MediaStore 相册（对比播放已由 Player 顶部「对比」入口提供；T20 剪辑成片落私有 videos，Gallery 下次刷新可见） | ☑ 部分 |

## player — 播放器 / 对比 / 剪辑

| 上游 | 类型 | 鸿蒙落点 | 状态 |
| --- | --- | --- | --- |
| `PlayerEngine.kt` | 平台绑定 | `player/PlayerEngine.ets` + `pages/Player.ets` 最小版：AVPlayer + XComponent(SURFACE) + fdSrc、prepare/play/pause/seek、逐帧（SEEK_CLOSEST + FrameStep 栅格/pending 锚点）、A/B 会话内循环（position 轮询回绕 + abWrapInFlight）、倍速（平台档 0.125/0.25/0.5/0.75/1.0，上游 0.1/0.3 偏离登记）、120ms 位置轮询、释放先 off 再 release；T19 增加 setVolume（0..1 夹紧），两实例各持自身 AVPlayer/fd/tick，无静态共享（Compare 页双路验证）；T20 音量 P3：Number.isFinite 防护（NaN/Infinity → 0）、删除无调用的 setMuted。落地 2026-10-21，对齐上游 342dd99。平台偏离：AVPlayer 无帧率读数（.drops.json dstFps→30→页面 24/25/30 手动）、无 SeekParameters（SEEK_CLOSEST，真机若落同步帧退 SEEK_PREV_SYNC 登记）。留白：播放器内双轨/选轨、自动音频同步、AB 持久化、旋转观看、缩略图、音轨菜单 | ☑ 部分 |
| `AudioSync.kt`、`CompareAudio.kt`（对齐数学） | 纯逻辑 | **T24 已落（2026-10-22，对齐上游 342dd99）**：`player/AudioSync.ets` —— 常量（TARGET_SR=8000/MAX_SECONDS=30/ENV_WIN_MS=10/SEARCH_MS=10000/REFINE_MS=50/PEAK_GUARD_MS=150/MIN_CONFIDENCE=1.5）+ resample（线性插值、按目标帧数×maxSeconds 封顶）+ envelope（10ms 窗 RMS 去均值归一）+ coarseAlign（±10s 前缀能量归一化互相关）+ 迭代 radix-2 fft/inverseFft + lowPass1k/meanRemove/nextPowerOfTwo/score + refine（±50ms、1ms 步进、次峰排除 ±150ms、置信度主峰/次峰比）；平台解码（MediaExtractor/MediaCodec→8kHz PCM、按容器轨序取第 N 条音轨）留待内录/自动对齐批。`player/CompareAudio.ets` —— Side/AudioTrackKind/audioGroupIndexOf（环境=组0/内录=组1 唯一映射）、SplitAudioPlan+splitAudioPlanOf+splitAudioEffectOf（分屏单引擎音量恒 1，不误静音）、CompareAudioPlan+audioPlanOf（单音轨 CAP 回落 ENV 清 override）+applyOrderOf（静音另一侧→设/清 override→选中侧音量 1）+selectionEffectOf、EngineCommand（kind 判别 + 静态工厂）/OverrideTarget/SelectionEffect。**T25 已接线**：Compare 页 applyAudioPlan 按固定命令序真实下发（setVolume/selectAudioGroup），双音轨门控用 ClipExporter.probe 的 audioCount≥2，静音保留本侧三态扩展；AudioSync 平台解码（MediaExtractor/MediaCodec→8kHz PCM）留 T26 | ☑ 部分（T25 音频接线；自动对齐解码留 T26） |
| `FrameStep.kt`、`TapArbiter.kt`、`CompareTimeline.kt`、`ComparePractice.kt`、`CompareHistory.kt`、`ClipExport.kt`（ClipRule） | 纯逻辑为主 | `player/FrameStep.ets`（SNAP_TOL_MS=1.5、floor+1/ceil−1、duration 钳制）、`player/PlayerModel.ets`（AbRange valid、倍速档/最近档、clampPan 半域）、`player/TapArbiter.ets`（280ms 双击窗口）逐行直译；T19 新增 `player/CompareTimeline.ets`：TimelineGeometry（tMin=min(0,−offset)/tMax=max(leftDur,rightDur−offset)/leftCovers/rightCovers/leftTarget/rightTarget park 规则）+ normalizeOffset（[−rightDur,leftDur] 夹紧）+ rightTargetFor（leftPos+offset 夹到 0..rightDur）+ timelineSpan/timelineEnd，仅手动偏移子集；T22 订正：tMin/normalizeOffset/rightTargetFor 的 -0 归一 +0（纯逻辑测试发现，对齐上游 Long 语义，文件头登记）。T20 新增 `player/ClipRule.ets`：MIN_KEEP_MS=300、ClipRange、normalize（0..duration 夹紧，恰等 300ms 通过）、keepSample（relUs>=0）、clipNameOf（edit_<stem>_<yyyyMMdd_HHmmss>.mp4）、keyFrameIndexOf（四舍五入）、remapDrops（k>=shift 平移到 k−shift，segments 清空登记）、TrackChoice(-1/0/1)+audioOrdinalOf。**T22 纯逻辑单测落地（见 §纯逻辑测试）**：compare-timeline.test.ets（9 用例，off=0 回归红线两条独立钉死）+ clip-rule.test.ets（6 用例）。**T24 全量纯逻辑（2026-10-22，对齐上游 342dd99，接线留 T25）**：① `player/CompareTimeline.ets` 补齐 CompareDomain/DecideInput/DecideCommands/domainOf/resolveT/decide——域判定（黑层回灌消歧/pre-roll 安全网/off<0 头段含边界交接/末态优先）、主钟随域（双素材与左独播跟左钟、右独播跟右钟、末取 Tmax）、双覆盖纠偏（250ms 硬 seek / 邻域 nudge，公式逐字）、独播域 dormant pause+黑层、交接不 seek、时间线末循环开回绕 Tmin（材料感知：park 侧带 pause+黑层，off=0 退化为双 resume）与循环关停末帧、AB 激活不抢回绕、拖动压 seek/nudge/wrap；几何函数零改动。② `player/ComparePractice.ets`：primeWith/takeLeftId/deliver + armed/pendingRightId 状态语义（导出类与模块单例 comparePractice）；**分屏练拍页面未移植，动线接线阻塞**。③ `player/CompareHistory.ets`：Entry(rightId,leftId,ts)、MAX_ENTRIES=12、entriesOf（缺键/坏 JSON 空表、坏行跳过、id≤0 跳过）、recordEntry（rightId 去重+插队首+截 12，返回新数组不改入参）、removeEntry、encodeEntries（键序 rightId/leftId/ts）；preferences 适配留 T25。单测：compare-timeline 25 条（+16）/compare-practice 6 条/compare-history 10 条（见 §纯逻辑测试）。**T25 接线（2026-10-22）**：Compare.ets onTick 交 decide（应用顺序 pause/resume→seek→nudge→黑层写回）、主钟读数 resolveT（负轴 pre-roll）、seekTimeline 统一 park/黑层、setPlaying 末态回 Tmin + wantPlaying 续播、domBlack 常驻层、CompareHistory preferences 适配（CompareHistoryStore，store=wota_player_ab）与历史面板。留白：AB 持久化、练习页分屏（ComparePractice 纯逻辑已落但无消费者，T26） | ☑ 部分 |
| `ClipExport.kt`、`RemuxOrientation.kt` | 平台绑定（封装逻辑纯） | **T20 无损 remux 最小版（2026-10-21）**：`cpp/clip_exporter.h/.cpp`（OH_AVSource/OH_AVDemuxer 逐样本读 → OH_AVMuxer 原样写 MPEG_4，不解码/不重编码；worker 线程 + atomic 进度/取消，状态 `idle/running:<pct>/done:<base>:<v>:<a>[:audio=skipped]/failed:<code>/cancelled`；入点 Seek PREVIOUS_SYNC 取首个 SYNC 帧 basePts，视频/音频 PTS 同减 base，负 rel 丢弃，逐轨 monotonicPts 桥；出点按 PTS 截止，视频 32/音频 8 连续越界容忍 B 帧重排；源/视频轨旋转经 WotaNormalizeQuarter 透传，源格式优先；音轨可选 -1/0/1，预扫区间内零可写样本不建轨，AddTrack 失败退纯视频；先视频整轨后音频整轨；muxer Start 后取消 best-effort Stop）+ NAPI `startClipExport/getClipExportStatus/cancelClipExport/probeClipTracks` + `player/ClipExporter.ets`（fd 生命周期、200ms 轮询、10min 超时、失败/取消删除半成品、done 后按 basePts 关键帧序号 remapDrops 写输出 sidecar、同名追加 _N）+ Player 页「导出 A/B」面板（A/B≥300ms 才显示、暂停、音轨选择、进度/取消、成功路径/大小/媒体库跳转）。错误码 source/track-format/no-video/no-sync/muxer/add-track/rotation/muxer-start/read/sample-too-large/write/empty-range。**T21 修复 T20 复审 P3×4（2026-10-21）**：① done 日志打实际生效 rotation（与 `OH_AVMuxer_SetRotation` 同读归一后的 `appliedRotation`，源格式缺旋转键、视频轨带键时不再打错 sourceRotation）；② 音频拷贝阶段 NO_MEMORY 不判死——跳过剩余音频样本继续视频、done 状态追加 `audio=skipped`（首枚即超限=已 AddTrack 零样本 → WARN + 既有 audioSamples==0/Stop 失败兜底；视频轨 NO_MEMORY 维持 sample-too-large 判死，口径登记 clip_exporter.h）；③ ArkTS uniqueOutputPath 的 accessSync 异常按「被占用」继续 _N 循环、1..99 穷尽回退 `${stem}_${Date.now()}.mp4`（绝不返回 access 异常原候选名，防 TRUNC 覆盖旧成片）；④ 超时 fd 延迟关闭——CANCEL_GRACE_MS(5s) 耗尽进 linger 轮询（NATIVE_LINGER_MS=30s 上限）等 native 离开 running:，linger 内退出照常 close+discard、耗尽挂模块级 pendingFds（exportClip 入口/轮询 reap，Diag.w 登记泄漏），不关正被阻塞调用写的 fd。已知差异：逐轨顺序写入=非交织（真机播放器兼容性待验）、音频只选一条、无 MediaStore pending/commit（导出中直接写目标 mp4，极端并发刷新可能看到半成品）、无分享、clipName 追加时间戳（OHOS 私有目录防覆盖）、remapDrops 清空 segments（剪辑单文件不再适用源段账）。RemuxOrientation 只做旋转透传，不单独移植平台读源。留白：交织写入、双音轨、自动音频同步、剪辑进度持久化/后台保活；T18/T19 其余 P3 分别登记到健康/对比收口批（本批仅 PlayerEngine 音量防护） | ☑ 部分 |
| `PlayerScreen.kt`、`CompareScreen.kt` | UI | `pages/Player.ets` 最小版：播放画面/进度条/播放暂停/逐帧/A-B/倍速/帧率/镜像、单击显隐、双击启停、双指缩放平移（clampPan）；Gallery 卡片进入、返回刷新，顶部「对比」带 {leftId,leftPath} 进 Compare。T19 `pages/Compare.ets` 对比播放最小版：双普通 XComponent(SURFACE)+双 PlayerEngine、右片 MediaRepo 现选（排除左片）、共享播放/暂停/seek/倍速、手动偏移 ±0.5s/归零（park 门控：无覆盖侧停首/末帧，进入覆盖区由 tick 起播）、声音侧左/右/静音（默认左）、120ms tick 漂移校正（阈值 250ms/冷却 500ms）、两侧独立 attach/retry 与 surface 重建换代、页面隐藏暂停/销毁双 release。T20 在 A/B 合法（≥300ms）时增加「导出 A/B」入口与导出面板（音轨选择/进度/取消/成功路径与大小/媒体库跳转，导出期间拦截返回）。**T22 对比收口（2026-10-22）四项关闭 + 平台限制登记**：① 时间线双轴消费——进度 Slider min/max=TimelineGeometry.tMin()/tMax()（off>0 负轴 pre-roll 可拖）、拖动值 t 即时间线坐标、seekBoth 走 leftTarget/rightTarget park 规则、左端标签 tMin/右端 timelineEnd；off=0 在旧单左轴域逐像素等价（回归红线）；timelineSpan 无 UI 消费留白；进度读数仍随左钟（pre-roll 段显 0，最小版不派生真 T，登记）；② 右片 surface 重建/重试恢复播放位置（resumeRightPosMs 快照 rightTargetFor 或引擎实位，tryAttachRight 成功路径 seekTo 恢复；换选右片不恢复；左片既有恢复路径零改动）；③ attach/retry/surface 隐藏期门控——onSurfaceLoaded 重建分支在 !pageVisible 只置 deferred 标志（left/right 各一）由 onPageShow 消费执行、retryLeft/retryRight 入口拒 pageVisible=false、rebuild then 链 attach 前再查 pageVisible；④ 右片列表 id+path 双排除左片（leftClip 未就绪用路由参数原始 path=paramsLeftPath 兜底，防 FNV-1a ID 碰撞）。**漂移"速度微调"以平台限制登记关闭**：AVPlayer setSpeed 仅枚举档（player/PlayerEngine.ets speedToEnum 0.125/0.25/0.5/0.75/1.0 为证，无任意 ratio 接口），上游 AudioSync 的 setPlaybackParams 微调无 OHOS 等价物，邻域纠偏维持 250ms 硬 seek/500ms 冷却。**T23 P3 关销（2026-10-22）**：时间线左端标签负轴改用 `media/MediaFormat.formatSignedDuration`（off>0 的 pre-roll 显示 `-m:ss`；亚秒负值下限 `-00:01`，避免 500ms 偏移步进时与 0 混淆），进度读数仍随左钟口径（不派生真 T，登记保留）；formatDuration 负值归零语义不变，新增 media-format 3 用例钉住正/负/0（含 `2:00:50` 手写字面量）。**T25 对比页接线（2026-10-22）**：onTick 双片就绪且无错误时交 decide（域判定/主钟/交接/纠偏/末段回绕/拖动让路）；应用顺序 pause/resume→seek→nudge→黑层写回；主钟读数 resolveT（pre-roll 为负 T）；seekTimeline 统一 leftTarget/rightTarget park + 无覆盖侧暂停 + atEnd 撤黑 + 会话黑层写回；setPlaying 末态先回 Tmin、wantPlaying 续播意图（换右片/右片晚挂 attach 后补一次）；左右恢复位置快照改素材位；domBlack 常驻黑层（BLACK_LAYER_ENABLED 总开关）；底栏「循环:开/关」；音频计划真实下发（静音另一侧→设/清 override→选中侧音量 1，双音轨 chip，静音三态保留）；历史面板（回填右槽/缺失标记/删除）+ 选右片记账。**T25 审查修复（2026-10-22）**：① surface 重建/重试先记 wantPlaying（旧引擎 release 后 playing 归零），attach 成功走 seekAndPlayIfPaused（seekDone 落地再 play），maybeResumeWanted 不再以「任一侧在播」早退；② seekTimeline 写黑层后按 wantPlaying 统一 resumeDomainAt——拖回双覆盖区 dormant 侧随松手复活（不再无黑层冻结）；③ 换右片/左右 attach 成功后均按当前音频侧重探 audioSideDual，静音态也落轨选择（静默生效，解除静音不复位）；④ 右片未选/报错时 positionMs/sliderValue 跟左钟（重建中保留 T，预录态不回落）；⑤ 历史写入串行队列、number/string 统一 safe integer。进页起播仍为手点播放（上游 attach 起播，登记偏离）；nudge 为平台空操作（250ms 硬 seek 保留）。留白：AudioSync 平台解码与自动对齐入口、练习动线（分屏录制页，T26）、全屏手势锁定/字幕/AB 持久化、剪辑进度持久化。**D 批（2026-10-23）同步来源 UI**：`player/SyncPrefsStore.ets` 读 TrackSync 五键快照，导出面板显示「已同步 +Xms（自动校准/手动）」/「未同步：导出按 0 平移」+ 诚实边界行「原片分享不重写容器；只有本页导出/光弧修复会应用同步残差」；导出与光弧修复都在起跑时取偏移（`audioShiftUs` / `capOffsetMs`）。**未迁**：手动「同步音轨」按钮与 `PostStopSync.running` 重读（均依赖偏移计算平台面，T27-4） | ☑ 部分 |
| `player/AudioAlign.kt`（e950328 新拆出的 DSP 单一真源） | 纯逻辑 | **已落（2026-10-23，对齐 e950328 纯逻辑层）**：`core/AudioAlign.ets`——resample/envelope/coarseAlign/fft/inverseFft/refine/lowPass1k/meanRemove 自 `player/AudioSync.ets` 迁出为单一真源（按采样率参数化，非 8k 写死），新增 `estimateOffsetMs(envPcm, capPcm, sr, searchMs, refineMs, minConfidence)`：**三红线**=时长不足 200ms / 任一路静音 / 置信度低于门槛 → 一律返回 null（不硬凑偏移）。`player/AudioSync.ets` 改为 59 行薄壳同名 API 委托（常量原名转发，既有 10 例经委托零回归）；decodeMono 平台解码仍留 T27-4 | ☑ 纯逻辑层 |
| `player/TrackSync.kt`（e950328 扩容） | 纯逻辑 + 平台绑定 | **已落纯逻辑层（2026-10-23）**：`player/TrackSync.ets` 62→257 行——`Source`(AUTO/MANUAL)、`AutoDecision` 四态（ManualProtected/LowConfidence/OutOfRange/Write，静态工厂 + sameAs）、`SyncSnapshot`、`autoDecision`/`sourceOfStored`、prefs 五键编解码（sync_done/offset/source/conf/at，键名与上游逐字；`SyncPrefs` 最小 get/put 注入接口，不把 `@ohos.data.preferences` 拖进纯模块）、`writeAutoPrefs`/`writeSyncPrefs`（写前复核 MANUAL，对应上游锁内复核的 TOCTOU 语义）。平台面 `countAudioTracks`（可先用 ClipExporter probe 替代）与 autoRun 解码留 T27-4 | ☑ 部分 |
| `player/PostStopSync.kt` | 纯逻辑（调度属平台） | **已落决策面（2026-10-23）**：`player/PostStopSyncPlan.ets`——`requestedKeys`（段筛选）/`actualDualKeys`（双轨谓词注入，异常按非双轨）/`planFor`（MAX_EXACT_SEGMENTS=4：≤4 段逐段，超 4 段测首段+复制并记「近似」），异常隔离。taskpool 调度与 Mutex 串行化留 T27（文件尾接口注释） | ☑ 部分 |
| `player/ClipExport.kt` 的 `ClipRule.audioShiftUs` + 导出/修复两条链的平移接线 | 纯逻辑 + 平台绑定 | **已落（2026-10-23，对齐 e950328 完整）**：① 纯逻辑 `player/ClipRule.ets`——`audioShiftUs(trackChoice, offsetMs)`（CAP=`0 - offsetMs*1000` 避免 JS `-0`，ENV/NO_AUDIO=0）与 `remuxAudioSampleWritable(relUs, shiftUs, endRelUs)`（平移后负值丢弃、出点吃平移后时刻）；② `player/ClipExporter.ets` 的 `exportClip(..., audioShiftUs, onProgress)` 把平移传给 native；`cpp/clip_exporter.cpp` 的 Start/Job 增 `audioShiftUs`，**预扫（`attr.pts - basePtsUs + job.audioShiftUs`）与拷贝循环（`audioRel`）同口径**，视频轨仍只减公共基准；③ 光弧修复 `record/ArcRepairRunner.ets` 的 `run(..., capOffsetMs, ...)` → native `arc_repair.cpp` 只对「确有第二轨」的 lane 1 平移，并新增**平移后可写性预检**（按视频首样本 PTS 判，防零样本轨崩 muxer）；④ `player/SyncPrefsStore.ets`（`@ohos.data.preferences` 适配 TrackSync 五键，读不到=未同步）。接线守卫见 `test/pure/clip-export-shift.test.ets`（含 C++ 源码结构检查）。**留白**：同步偏移的产生（手动「同步音轨」与停止后自动校准）仍挂 T27-4——本侧只消费已落盘的结果 | ☑ 完整（偏移来源留 T27-4） |

## record — 录制链路（移植重心）

| 上游 | 类型 | 鸿蒙落点 | 状态 |
| --- | --- | --- | --- |
| `MrRecorder.kt`（MediaRecorder 引擎） | 平台绑定 | `@ohos.multimedia.media` AVRecorder + CameraKit VideoSession：`camera/KitRecorder.ets` 最小录制（VIDEO_SOURCE_TYPE_SURFACE_YUV 面输入、NORMAL_VIDEO 会话、**videoProfiles + frameRateRange 覆盖选档**（VideoSizeTable 留待后续）、BitratePolicy 复用、SeriesIds 接线、MIN_KEEP_MS 判废）。落地 2026-10-21，对齐上游 342dd99。输出契约：`AVRecorderConfig.url = fd://<fd>`（fileIo.openSync 建文件取 fd，fd 保留到 AVRecorder reset/release 后关闭）；起录失败统一回收资源并删除已创建/半写文件；判废顺序=先 MIN_KEEP_MS（短段含 engineErr 一律 TOO_SHORT），唯一例外 av.stop 失败保留文件 triage（STOP_FAILED）；start/stop 有 starting/stopping 重入保护。留白：3.5GiB 分段轮转/起录健康三格/AutoStop 巡检（GPU 已接，AVRecorder 留白）/FrameLedger 接线/HEVC 重试/.drops.json/参数下发（VideoSession 无 ManualExposure/ManualIso/OIS）/orientationHint 与 AVRecorderConfig.rotation 未接线（显示旋转源随 HUD 批）/sampleRate 恒 48k 未探测/mirrored/useGpu 恒 false。装机验证项（不阻塞）：录 5 秒文件可播放有声音、停录后预览恢复、拒绝 MIC 录无声视频、<1 秒判废删除、fd:// 契约真机验证 | ☑ 部分 |
| `CodecRecorder.kt`（MediaCodec+MediaMuxer 引擎） | 平台绑定 | AVCodec + AVMuxer（NDK 侧），与 GL 链路衔接。**最小切片已落（2026-10-21，对齐上游 342dd99）**：`cpp/codec_recorder.h/.cpp`（OH_VideoEncoder CreateByMime→RegisterCallback→Configure→GetSurface（Prepare 前）→Prepare→muxer Create/AddTrack/Start→encoder Start；surface 输入、输出回调直写 OH_AVMuxer，PTS 一律 wall-clock 相对段基准（NowUs−startUs，µs）并经 OH_AVBuffer_SetBufferAttr 回写、lastPts 单调守卫（前提：无 B 帧，登记）；容器旋转 OH_AVMuxer_SetRotation(normalizeQuarter(sensor−display))（0/90/180/270 四舍五入，非 90 倍数 sensor 按上游归一；须在 muxer Start 前；编码 UV 仅送缓冲原样帧）；I 帧间隔 1000ms；FRAME_RATE/BITRATE 按 SDK 类型（double/int64）写入并检查；EOS 等待 ≤3s；错误机器码 busy/encoder-create/register-callback/format-create/format-key/configure/get-surface/prepare/muxer/track-format/track/muxer-rotation/muxer-start/start/encoder-surface；运行期 muxer-write/pts-set/muxer-missing/engine-error/surface-lost/encoder-pass-failed；muxer 写失败/时间轴回写失败即停止送样（samples 仅成功自增），错误经 getGpuRecordError/stop 带回；native 不 close fd）+ `camera/GlRecorder.ets`（fd 归 ArkTS、VideoStore 命名/落盘与 MIN_FREE_MB 起录预检、停录 close+stat 摘要；napi-failed 先 best-effort stop 再 close）+ GlRecorder（起录弧档/dstFps/deadband 传递；停录顺序 flushArcPending→（阻塞 stop 前冻结帧账 end 快照）→stop→逐项 drainArcDrops/帧计数/实测源帧率→close+stat→FrameLedger 成文→写 `.drops.json` sidecar（segments.count=muxer 视频样本数（part.samples，上游 VideoSegment.count；与首段旁 sidecar 同口径）；frameStart 不可用时相机帧退原生 GL 回调上界））+ GlPreview「录 GPU/停录」与三态转换按钮（关/补弧24/抽帧24，固定 1920×1080@30 或 24、BitratePolicy.resolve；500ms 监视 isGpuRecording/getGpuRecordError 校正录制态）+ **AutoStopMonitor（2026-10-21）**：1s 相机设备态转移（启动时以当前设备态预置 watch；session error 直连兜底）、首个 1s tick 立即查电量 batterySOC≤3% 与可用空间 <MIN_FREE_MB（其后 30s 节拍），一次录制至多一发，命中提示“成片已保存”；手动停录/相机丢失兜底统一按 AutoStopRules.stopGraceDelayMs 做 500ms 宽限（stopPending 防重入，首个停录原因合并）。相机丢失后 lensInfo/deviceStatus 失效，需重新进入本页起镜后才允许再次录制。**3.5GiB 分段轮转已落（2026-10-21）**：native `getGpuWrittenBytes`（成功写入 attr.size 累计）+ ArkTS 500ms 轮询 maybeRotate；SegmentPolicy（MAX_FILE_BYTES=3_758_096_384、ROTATE_SAFETY=0.98）；轮转=停段（EOS）→新文件→重建编码器/EGL 面（段间 lostMs 记账），每段独立 MP4/时间轴、各自 displayName；逐段 FrameLedger（partIndex/rotateLostMs/rotateFramesUpper）与 `frameSessionSummary`；首段旁 `.drops.json` 的 segments 覆盖全部段（first 前缀和/part.samples/lostMs），drops 只保留最后一段（上游跨段失真登记）；8MB 测试阈值入口（离开页面恢复默认）；summary 的 measuredSrcFps 标注为最后一段口径；**T22（2026-10-22）**：SegmentPolicy 无自动化单测登记撤销（test/pure/segment-policy.test.ets 5 用例，见 §纯逻辑测试）、stop 摘要追加末段账 lastPath/lastBytes/lastSamples（健康收口丢段专用）、被丢弃零样本段在首段 sidecar segments 留 count=0 条目（无害，登记——首段 sidecar 不回写）。上批 P3×4：起录后 1s 冷却（迟到点击不覆盖摘要）、删除 lastStopHadReason 死字段、Player metaNotice 移出控制层、stopReason 起录清理。本批 P3×5：轮转错误先 finish 再追加摘要、onPageHide/离开页面复位 8MB 测试阈值、起录清 lastStopAtMs、napi-failed 摘要带 path 保留 triage、~~SegmentPolicy 无自动化单测登记~~（T22 撤销）。 T18 P3×8：bootstrap/权限 pageVisible 门控、重试相机按钮 disabled(startingCamera)、SettingsStore.save 返回 boolean + 保存串行化、变焦实际值/最近档、HUD_READOUTS 驱动顶栏、appendNotice 冷却/错误追加、相机丢失清镜头/画幅标签、预览/录制帧率分显。留白：音频轨、参数下发、PTS 网格化、成片名前缀、MediaStore、NV12 直通与 DIRECT 路、系列归组 UI。已知：容器旋转 hint 在起录时定格（录制中转屏不更新，单段口径，登记）。装机验证项（不阻塞）：GL 页录 5s→hdc file recv 取回 mp4 可播放且时长 5s 量级；ES2 回退下 MEND 不降级（unsized GL_RGBA）；时间戳单位 ns/µs/ms 归一与实测源帧率合理；竖屏成片方向正确（muxer SetRotation）；停录后再次起录正常；录制中返回页面→录制中止、无崩溃；`hdc hilog \| grep -E "WotaGL\|WotaRecord"` 见编码面建立/首帧/EOS 日志 | ☑ 部分 |
| `CaptureFgService.kt`（前台服务） | 平台绑定 | 长时任务（continuous task）+ 通知栏常驻 | ☐ |
| `PlaybackCaptureController.kt`（内录） | 平台绑定 | **T26 纯逻辑已落（2026-10-22）**：`record/CaptureStateMachine.ets`（Idle→Authorizing→Active/Failed→Revoked 全转移 + 状态/事件短名）、`record/CaptureSilence.ets`（SilencePromptGate 按场 60s 节流 + captureSilenceVerdict 2s/5s 双门槛 + fedMsOf）、`record/AudioProbe.ets`（frameBytes/chunkBytes/recordBufferBytes/dbOf + captureParamMismatch 档位体检）、`record/CaptureSources.ets`（OH_AudioCaptureSourceType 契约 MIC=1/ALL_PLAYBACK=2/APP_PLAYBACK=3 + 内录源优先级 [ALL_PLAYBACK, APP_PLAYBACK]）；**平台面（AVScreenCapture 会话 + 隐私授权 + 双源采集 + AAC 三轨合流）T27 真机批**，调研结论见 §T26 平台调研 | ☑ 部分（T26 纯逻辑，平台 T27） |
| `Recorder.kt`、`CaptureStateMachine.kt`、`RecordProfile.kt`、`VideoStore.kt` | 纯逻辑为主 | 直译（状态机与接口不动，引擎实现注入）。最小契约已落：`record/RecordResult.ets`（RecordError 机器码 + RecordResult 首段字段/summary，VideoSegment/health/parts 留待分段批）、`record/RecordProfile.ets`（纯字段子集 + CODEC_H264/CODEC_HEVC/MIN_KEEP_MS + effectiveBitrate，captureAudio/arcConvert/fpsNtsc 留白）、`record/VideoStore.ets`（VID_yyyyMMdd_HHmmss_###.mp4 命名/MIN_FREE_MB 余量/sizeOf/discard；MediaStore pending 与相册入库留白）。分段轮转每段各自 displayName/文件，段序号只在 parts/segments/日志；系列归组（MediaSeries/SeriesPart）留白。落地 2026-10-21，对齐上游 342dd99。**T26 追加**：`record/CaptureStateMachine.ets` 直译上游授权状态机（16 用例）；`record/CaptureSources.ets` 因 Android usage 白名单无 OHOS 等价物，改以源枚举优先列表承载（差异登记于文件头）；`record/AudioProbe.ets` / `record/CaptureSilence.ets` 落可测算式与判据（消耗方留 T27） | ☑ 部分 |
| `ArcKeepRule.kt`、`ArcDropLog.kt`、`BitratePolicy.kt`、`AutoStopRules.kt`、`FrameLedger.kt` | 纯逻辑 | **逐行直译**——这些是与上游共享语义的锚点（见 UPSTREAM_SYNC.md），格式/判据/阈值必须与上游逐字段一致。直译完成 2026-10-20，对齐上游 342dd99，落点 `entry/src/main/ets/record/` 同名 .ets；BitratePolicy 的 `resolve(RecordProfile)` 重载待 RecordProfile 移植后补。在线消费已接线（2026-10-21）：ArcKeepRule/ArcRepairFlow 的 C++ 等价物（`cpp/arc_flow.*`）跑在编码支路，ArcDropLog 由 GlRecorder 停录写 sidecar，FrameLedger 合成原生五格+相机 frameStart（分段时逐段成文）；ArcDropLog segments 已随分段写全段清单（drops 只保留最后一段，跨段失真登记）；AutoStopRules/CameraLostWatch 由 `record/AutoStopMonitor.ets` 消费（GPU 主路径：1s 相机/30s 电量存储、一次一发、停止宽限 500ms；AVRecorder 路径留白） | ☑ |
| `RecordHealth.kt` | 纯逻辑 | `record/RecordHealth.ets`：GPU 三格（trackAdded/muxStarted/firstVideoSample）+ 判定顺序（错误码→三格→视频短窗 1.2s→完整窗 3s→WAIT）、retryActionOf（attempts<maxAttempts）、MIN_OUTPUT_BYTES=64KiB、resolveOutputBytes/streamHealthVerdict（AVRecorder 路纯函数留白）。**GPU 主路径已接（2026-10-21）**：native CodecRecorder 暴露 trackAdded/muxStarted/samples（getGpuHealth），Camera 500ms 巡检、最多自动重录 2 次、仅重开录制段不重开相机。T18 P3×4（T19）：GlRecorder stop 摘要补 samples、空段仅 samples=0 且 bytes=0 丢弃、onPageHide/Show 续跑巡检、GIVE_UP 文案常量生成、native HealthSnapshot 单锁快照。**T22 健康收口（2026-10-22，对齐上游 342dd99）四项关闭**：① 三格真信号——native `formatChanged_`（OnStreamChangedCb 置位，Start/FailStart/Stop 复位）= 上游 MediaCodec format-changed 等价物，getGpuHealth 扩 4 字段 `track,mux,samples,fmt`，Camera checkHealth 容 4 字段（3 字段旧序防御），healthVerdict 的 videoTrackAdded 实参 = `fmt || samples>0`（samples 兜底防回调时序不触），muxer AddTrack 位降为 Diag 诊断字段（三格语义对照表见 Camera.ets 头注释）；② 轮转重启健康窗——上一段已健康停巡检后轮转新段重启巡检（幂等），**healthAttempts 会话级不清零（与上游"每次起录清零"口径差异登记：防轮转循环无限重录）**，隐藏页不重启只置 healthPausedByHide 由 resumeHealthAfterShow 消费；③ 健康重录三残差——restartSegmentAfterHealthFailure 重置 recordStartedAtMs（宽限期口径）、GlRecorder.stop 摘要追加末段账 `lastPath/lastBytes/lastSamples`、健康重录与 GIVE_UP 丢段读末段账（仅末段 samples=0 且 bytes=0 才删，**保留已完成前段**；被丢弃零样本段在首段 sidecar segments 留 count=0 条目，无害登记）；④ 纯逻辑单测落地（test/pure/record-health.test.ets，21 用例，判定顺序五条各被点名，见 §纯逻辑测试）。AVRecorder 健康检查/stream 活性路留白（Index 未接） | ☑ 部分 |
| `ArcRepairPlan.kt`、`ArcRepairRunner.kt`、`ArcRepairGl.kt`（离线修复） | 平台绑定（计划逻辑纯） | 计划直译；执行走 CPU（ArkTS/NDK）与 GPU（复用 GL 着色器）双路。**在线 ArcRepairFlow 已落（2026-10-21）**：`cpp/arc_flow.h/.cpp` 的 Hold/Fold/Emit/Merge 指令序列 + `cpp/arc_mend_pass.*` 执行。**T28 纯逻辑已落（2026-10-22）**：`record/ArcRepairPlan.ets`（有理数抽帧计划 dstFrameCount/isKept/progressOf/of，29.97 与 25.4 真值位次锚点；不移植旧 Int 外壳）、`record/ArcRepairFlow.ets`（Hold/Fold/Emit/Merge 流式状态机 + 计划路 onSourceFrame + 在线分类路，PTS=keptCount·1e6/dstFps，补上 cpp/arc_flow.h 所引的 ArkTS 锚点）、`record/ArcRepairMerge.ets`（CPU 4:2:0 2×2 块整像素选择，Uint8Array 无符号 Δ、块内最大 Δ、4Y+U+V 整块搬运不造色）、`record/ArcRepairVerdict.ets`（arcRepairOk 三格 + arcRepairKeep 的 stop 门槛）；单测 41 条（plan 15/flow 9/merge 12/verdict 5）。**T29 CPU 执行器核心已落（2026-10-22）**：`cpp/arc_repair.h/.cpp`（单例+worker 状态机、probe：宽高/旋转/容器 fps/时长/音轨数/帧距中位数（≤2M 样本）、同步模式解码 → 紧打包 I420（NV12/NV21/I420 + stride/sliceHeight，未知格式 ARC_CPU_YUV）、ArcRepairFlow 指令执行（cur/pending/acc 三面整像素合并）、同步模式编码（YUVI420 缓冲输入、显式 PTS=j/dstFps）→ 首枚输出后 AddTrack/SetRotation/Start → 逐样本写 muxer、取消/deadline/状态串）、NAPI 四口（probeArcRepairSource/startArcRepair/getArcRepairStatus/cancelArcRepair）、`record/ArcRepairRunner.ets`（fd 生命周期/200ms 轮询/取消宽限+linger/失败 discard/成功 stat/`contract()` 启动日志）、`record/ArcRepairSessionModel.ets`（24 用例：错误码/probe 与状态串解析/速率回退/NO_NEED/去重门限/帧数估算/结果归因/note）。**T30 音频直拷 + Player 面板已落（2026-10-22）**：运行期保留全部 `audio/` 轨（`audioTrackIndices/audioFormats/audioDstTracks` 并行表），首枚视频输出时在 SetRotation/Start 前逐轨 AddTrack（失败逐轨降级）；视频 EOS 排空后用同一 fd 新建 audio source/demuxer，逐轨 select→SeekToTime(0,PREVIOUS_SYNC)→逐样本读，PTS 按视频首帧归一（视频基准缺失退回本轨首样）、负值丢弃、`MonotonicPts` 逐轨兜底后写 muxer；done 串 `audioSamples` = 全轨成功样本合计；`record/ArcRepairSessionModel.ets` 增 `describeRepairError`/`audioLaneNote`（29 用例）、`ArcRepairRunner.run` 可复用面板 probe 结果；Player 页「修复光弧」面板（probe 拦截 NO_NEED/无视频/失败、目标 24/25、死区 0/12/32、进度/取消/成功跳媒体库、与导出 A/B 互斥、忙碌拦返回）。**GPU 路线（ArcRepairGl）与真机调优 T31** | ☑ 部分（T30 音频+面板 / GPU T31） |
| `PreMuxVideoBuffer.kt`（起录缓冲）、`SeriesIds.kt`、`PendingName.kt`、`StalePendingSweep.kt`、`RotateGuard.kt`、`RotateFailLog.kt`、`OrientationExport.kt`、`AudioFeeder.kt` | 纯逻辑为主 | 直译。`SeriesIds.ets`、`PendingName.ets` 直译完成 2026-10-20（对齐上游 342dd99，落点 `entry/src/main/ets/record/` 同名 .ets）；其余待后续 | ☑ 部分 |
| `record/AudioPreflightPolicy.kt`（e950328 新增） | 纯逻辑 | **已落（2026-10-23，对齐 e950328）**：`record/AudioPreflightPolicy.ets`——MIN/MAX_PRE_SHIFT_MS=20..600、MAX_AUTO_OFFSET_MS=1000、MIN_CONFIDENCE=2.0（比对比页手动 1.5 更严：无人值守自动写盘）、`capPreShiftUs`（低置信 → 0；`-offset<20ms` 即内录不早 → 0；>600 钳到 600）、`shouldPreflight(envOn,capOn,a2dp)`、`autoOffsetWithinRange`；上游 `a2dpFromOutputTypes` 的 Android 常量比对改为**注入布尔组合面**（activeA2dp ‖ listHasA2dp），平台比对移到 AudioRoute | ☑ 纯逻辑层 |
| `record/PreflightPcm.kt`（e950328 新增） | 纯逻辑 | **已落（2026-10-23）**：`record/PreflightPcm.ets`——`PreflightMonoPcm`：交织 PCM16→单声道 float、按两路共同 t0 落位（早于 t0 钳 0、不带负前导）、空洞补零、静音锁存与 peak | ☑ |
| `record/AudioRoute.kt`（e950328 新增） | 平台绑定 | **已落（2026-10-23，降级实现）**：`record/AudioRoute.ets`——`isA2dpLikelyOn()` 取 `audio.getAudioManager().getRoutingManager().getDevicesSync(OUTPUT_DEVICES_FLAG)`，出现 `DeviceType.BLUETOOTH_A2DP` 即 true，异常按 false。**降级登记**：OHOS 无 `isBluetoothA2dpOn`「活动路由」API（本机 SDK d.ts 已查证：仅 BLUETOOTH_A2DP=8 与 getDevices/getDevicesSync，无 getActiveOutputDevice），本判据等价上游的**兜底路径**（设备列表含 A2DP）——A2DP 已连但输出切回扬声器时会多跑一次预校准（无害：预校准失败路径恒 shift=0，不写错偏移）。真机待验见对应小节 | ☑ 降级 |
| `record/AudioPreflight.kt`（预校准采集）、`record/CodecRecorder.kt` 的 `AudioChannel.ptsShiftUs` 与 `preflightAudioSync` 挂点、`record/Recorder.kt` 的引擎契约 | 平台绑定 | **未落，整块挂 T27**：① 预校准需两条临时 AudioFeeder 双源同采 800ms，OHOS 内录原始 PCM 只有 AVScreenCapture NDK 一条路且**每次会话拉隐私确认**——预校准+正式录制=二次弹窗（体验不可接受，须 T27-3 复用同一 capture 会话，或砍预校准只留后校准）；② ptsShiftUs 需内录音轨，而 `cpp/codec_recorder.cpp` 现无音频轨（留白）；③ Recorder 契约随 T27-3 录制链改造一并落。策略与 PCM 落位算式已在纯逻辑层备好（上两行） | ☐ |

## ui — 界面（全部 ArkUI 重写）

| 上游 | 类型 | 鸿蒙落点 | 状态 |
| --- | --- | --- | --- |
| `CameraScreen.kt`、`MainActivity.kt`、`HudLayer/HudLayout/HudLayoutEditor/HudMetrics/HudPageGrid.kt`、`CameraPills.kt`、`SettingsScreen.kt`、`SettingsLayout.kt`、`ParamsPersist.kt`、`PreviewCoords.kt` | UI | **最小主 UI/HUD 已落（2026-10-21，对齐上游 342dd99）**：`pages/Camera.ets` 全屏 GL 预览 + 固定 HUD（顶栏两行读数/3×3 网格/底栏/录制计时）+ 参数胶囊（镜头/画幅/帧率/变焦/闪光/效果/光弧/网格点按循环；快门/ISO/EV/WB 点按开 PillPopup 就近浮层）+ GlRecorder/AutoStop/宽限/分段接线；**T21 高级参数批（2026-10-21）**：`ui/ParamPills.ets` 五件直译（对拍 CameraPills.kt:236-628——AeModeRow（AUTO/MANUAL/LOCK，manualExposurePossible 门控）、ShutterPill（梯=supported 档滑杆吸附、Float 往返经梯值反查原始 ns、仅 MANUAL 显示滑杆、fpsHi=pick.lo 与下发同源）、IsoPill（isoLadder 仅 MANUAL 可调）、EvPill（evLadder+evText、MANUAL 只读注记）、WbPill（预设网格+MANUAL 色温滑杆 kelvinLadder，**tint 副轴留白**））、WotaParams 参数总线接线（起镜成功 `applyAbility(engine.refinedAbility())`、fps 档位联动重算）；顶栏第二行 快门/ISO/EV/WB 读数（AE AUTO 时 ISO 显 AUTO、EV 仅非 MANUAL 显示、wbShort 预设缩写+色温、能力缺失 `-`；`HudSpec.HUD_ROW2`）——~~偏离登记：上游 READOUT 是可编排网格（hud_items 位掩码 + HudLayoutEditor），本批固定两行~~（**J3a 2026-10-24 已接**：快门/ISO/EV/WB/帧率迁入 `hud_items` 掩码驱动的读数条，固定行只留镜头/画幅/电量/空间/时长，见交付批 J3a 节；zone 化与拖拽编辑器仍留 J3b）；胶囊行追加 快门/ISO/EV/WB 四颗（点按开浮层，录制门控 `ParamPills.RECORDING_EDITABLE_PILLS` = {ISO,EV,WB}——**上游含 ZOOM/FOCUS，两颗胶囊本批未移植，差异登记**；快门录制中开窗只读 Note）；每次起录前与弹层改动 `engine.applyParams` 下发（RequestApplier 逐键报告，failed 项 Diag.w + appendNotice）；`pages/Settings.ets` 六项设置（画幅/帧率/码率/光弧/效果/网格）经 `ui/SettingsStore.ets`（preferences）持久化；`ui/HudSpec.ets` 固定布局常量；主入口改为 Camera（EntryAbility loadContent），Index/GlPreview 保留开发者测试入口；pageVisible 门控隐藏页 async 起镜链、启动失败/session error 提供「重试相机」、applyZoom 按实际生效值显示、Settings 保存串行化/类型白名单、HUD 读数顺序常量与顶栏一致、媒体库入口去重；效果槽同时作用于预览与编码（`gl_render DrawScene` 同一 GL 效果 program）。**T21 复审 P3×3 收口（T22，2026-10-22）**：① 快门读数 ※——syncParamLabels 在 MANUAL 且 ability 在册且 forcedShutterOutOfRange 时追加 `※`（上游 CameraScreen.kt:1195-1203 口径，与快门弹层/下发侧同源判据）；② applyParamsFromPills 的 skipped 摘要改 Diag.i（上游只进日志不占用户提示位），仅 failed 留 appendNotice；③ 相机丢失/session error 处置路径统一 closePill()（onCameraLost 回调首行收口弹层）。**T31 取景页真机取证与修复（2026-10-22，HUAWEI ALN-AL00 / HarmonyOS 7.0.0.109 / API 26 / 1260x2720）**：`ui/HudTokens.ets`（深色色板/字阶 11-12-13-17/触控 48/圆角间距/遮罩令牌）、`ui/WindowChrome.ets`（沉浸式 setWindowLayoutFullScreen + 透明系统栏白图标 + AvoidArea TYPE_SYSTEM|TYPE_CUTOUT 转 vp 安全区；普通页恢复黑底系统栏）、`ui/HudPill.ets`（@Component+@Prop 替 @Builder 值参数——真机「顶栏正确而胶囊恒为 镜头:—/画幅:—」的根因）、`ui/HudMoreSheet.ets`（复用 PillPopup 遮罩/量框：闪光/效果/光弧/网格）；Camera 顶栏两行各 5 项（label 11fp/value 13fp）+ 右侧 48vp 深色入口胶囊、顶部/底部渐变遮罩、Dock 2×4 主胶囊（镜头/画幅/帧率/变焦 + 快门/ISO/EV/WB，48vp、均分、无横向 Scroll）、更多+72vp 圆形快门（44vp 红点/录制中 24vp 方块/收尾禁用）、确定性 3×3 网格（2 横 2 竖 Divider 替代百分比 position——真机旧实现网格不可见）、删除「预览—fps」死读数（只显「Nfps」）；Settings 重做（List 顶对齐消除顶部 539px 空白、48vp 选项、保存提示定高不推动、开发者入口默认隐藏→版本号 7 连点显形）；Index/GlPreview 补页头+返回、Index 长文案改 13fp 可滚动日志区；EntryAbility 强制 COLOR_MODE_DARK；Gallery 恢复黑底系统栏。**T31 复审收尾（2026-10-22，P3×5+清理）**：底栏去固定高（相机断开/权限拒绝时状态行与重试按钮不裁 8 胶囊与 72vp 快门）、Dock 胶囊 12fp+左右 6vp、顶栏 fps 读数去「录」前缀（360vp 宽 4/5 等分不省略）、快门/ISO/EV/WB 录制中恢复可点开窗（只读由弹层 editable 承担，不再整颗禁用）、PillNote 11fp（HUD 弹层无 10fp）、WindowChrome 模块级 Promise 链串行 + EntryAbility.loadContent 前 prime（冷启动首帧无白条、设置/相机往返不乱序）；删死代码（GlCameraEngine.currentPreviewFps/previewFps 字段、Camera.fpsLabel、HudSpec.PILL_ROW_HEIGHT/gridFractions、HudTouch.MIN/HudShape.PANEL_RADIUS/HudColor.SUCCESS、SafeInsets.left/right 留 T32 横屏）。**真机复验未执行：已安装包签名指纹 48A3BDCE… 与本仓 `.local-signing` 自签证书 C8D36043… 不匹配，按安全红线不安装，交付源码/HAP 由用户以 DevEco 同证书重装后验证**（待验项见「真机待验」T31 节）。留白：~~HudLayout/HudLayoutEditor 自定义布局~~（J1/J2 2026-10-23 纯逻辑全落；**J3a/J3b 2026-10-24 已接 UI**——hud_items 读数条 + 顺序编辑器（`hud_layout` 真编码）；**自由拖拽编辑器本体登记为设计性偏离**：本侧取景 HUD 是 T31 真机取证后的固定结构，无上游五容器自由布局的对应面，见交付批 J3b 节）、ZOOM/FOCUS 胶囊与弹层、tint 副轴、PreviewCoords、SheetSpec/Widgets 全量、UIOrientation 接线（T32，本批保持系统方向）。**高级参数持久化已落（本批 I，2026-10-23，对齐上游 `ui/ParamsPersist.kt`）**：`ui/ParamsPersist.ets`（纯逻辑：24 键白名单 `PARAMS_KEYS`，键名/顺序逐字等于上游 `WotaSettings.KEY_*`；`restore` 冷启恢复只写 value、range/enabled/exact 不动，机型域钳制仍交给 `applyAbility`；`ParamsPersistSession` 进页冻结影子、动作完成点 `flush` 只写与影子不同的字段、写失败不推进影子；枚举编解码与尺寸串 `"WxH"`；零 `@ohos.`/`@kit.` import）+ `ui/ParamsStore.ets`（平台件：`getPreferencesSync` 同步门面 + `putSync`/`flushSync`，写失败只 `Diag.w`，与 SettingsStore 共用 `wota_camera_settings`）；`pages/Camera.ets`：`bootstrap` 读完设置后 `restore`，flush 点 = 弹层提交 / 帧率 / 画幅 / 变焦 / 闪光循环 + `onPageHide` + `aboutToDisappear`。**排除清单照搬**：renderMode/sampleRate（设置页独占的自动降级）、audioEnabled（内录授权）、focusPoint（瞬时态）、curve/arc_convert_mode（专属直写链）。**键名迁移**：`fps→default_fps`、`bitrate→default_bitrate`、`sizeW/sizeH→default_size`（迁移读新键优先、回退旧键；save 只写新键，旧键保留不删），`level_enabled`/`default_ref_lines` 本来同名；`arcMode`/`grid` 留在 SettingsStore 原地（本侧已登记行为偏离，不折进 24 键/参考线掩码）。**与上游差异登记**：① 变焦/闪光循环走 engine 直写、不更新参数总线，flush 不会把 zoom/flash 入档（上游由 ZOOM/FOCUS 弹层写总线）；② 弹层滑杆每 tick 都走 `applyParamsFromPills`（上游按松手 `onValueChangeFinished`）；落盘侧已用 300ms 尾随节流 + 一次 commit 批写对齐（首请求立即、尾部补一次、关键点立即落盘） | ☑ 部分 |
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
- **已落用例（583）**：record-health（21，直译上游 RecordHealthTest.kt 已移植面，判定顺序五条
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
  顺序跟表走/默认表=枚举序回归线/编辑页与相机页接线守卫 2）。
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
