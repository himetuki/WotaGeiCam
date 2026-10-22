# 移植映射表（Android → HarmonyOS）

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
| `CameraAbility.from()`、`LensSlot.enumerateLenses/discoverCameraIds`、`VideoSizePicker.minFrameDurationNs/videoSizeTable` 的 CameraKit 探测版适配层 | 平台绑定（采集侧） | `entry/src/main/ets/camera/`：`KitAbility.ets`（能力快照工厂，逐字段映射表见文件头注释）、`KitLens.ets`（镜头枚举，复用 core 的 shouldHideLogicalHead/assignRearTiers/dumpLenses）、`VideoSizeKit.ets`（帧周期按 videoProfiles maxFps 折算注入）、`KitProbe.ets`（探测入口，pages/Index 按钮消费）。探测版落地 2026-10-20，对齐上游 38d712a；会话期字段已补 zoom/ev/flash/iso/ois（预览批）与 exposureNs/evStep/evRange(档数换算)/afModes/awbModes/maxRegions=1（参数下发批，`KitAbility.abilityRefineWithSession`）；留白仅剩 tint/逐帧 fps/帧周期/EIS 真值与 VideoSession 参数面（随录制批）；上游「按数字 id 探隐藏镜头」无 OHOS 等价物，不移植（见 KitLens.ets 头注释登记） | ☑ 探测版 |
| `CameraController` 的「默认开广角后摄 + 出画面」最小闭环（预览批） | 平台绑定（会话侧） | `entry/src/main/ets/camera/`：`PermGate.ets`（CAMERA 权限申请门）+ `KitPreview.ets`（PhotoSession 预览引擎：默认档 assignRearTiers 后首个 WIDE、`pickDirectPreviewSize` 选档、createSession/createPreviewOutput/start、stop 逐项收尾、`applyParams` 参数下发入口）+ `pages/Index.ets`（XComponent(SURFACE) 预览面 + 权限流按钮 + 参数自检八档，探测路径保留）。落地 2026-10-21，对齐上游 38d712a。留白项：切镜头 UI、GL/DIRECT 双路、参数下发 UI（本批只有自检入口）、VideoSession 能力（EIS 真值随录制批）、永久拒绝引导（随 HUD 批）。装机验证项（不阻塞）：授权框弹出与拒绝重试、预览画面出现/关页无崩溃、`hdc hilog \| grep -E "WotaAbility\|WotaLens"` 真值与镜头列表 | ☑ 部分 |
| `RequestApplier.kt`（参数 → 请求的纯决策层 + OHOS 会话下发） | 纯逻辑 + 平台绑定（下发侧） | `camera/RequestApplier.ets`：State/stateOf、Snapshot/snapshotOf、clampShutterNs（双钳制+强制档豁免）、frameDurationNs、pickFps、exposurePlan、flashPlan（五分支）、stabilizePlan（三门控）逐行直译；apply 段 `applySnapshot(session,…)` 逐键 try/catch 产出 applied/skipped/failed 报告（`ApplyReport`），`KitPreview.applyParams` + Index「参数自检」为真实消费者。落地 2026-10-21，对齐上游 38d712a。审查修复订正（2026-10-21）：曝光时长单位 OHOS=微秒、core=纳秒（采集 ×1000/下发 ÷1000）；曝光组按档语义（MANUAL 写全 时长+ISO+EV 归零；LOCK/AUTO 只写模式与补偿——LOCK 冻结当前 AE 曝光，不写 SENSOR 键，OHOS LOCKED 下 setIso 会实际生效，登记）；MANUAL 用 LOCKED（d.ts：setIso 仅 LOCKED 生效，不用 EXPOSURE_MODE_MANUAL）；闪光由纯 flashPlan 驱动（无灯/手动退化/TORCH 全走 plan，state.flashAvailable 已消费）；EIS skipped 按用户意图判定并如实标注原因（PhotoSession 未暴露 EIS 参数面 ≠ 镜头不支持；高帧率/4K 门控原因单独列出）；手动对焦距离经 refine 的 isFocusDistanceSupported 探针门控 + ManualFocus.setFocusDistance 归一化下发（API24；探针不可得/6.0 设备 → skipped 不是 failed，登记）；evStep≤0 不猜 1、补偿键进 skipped；白平衡 MANUAL 模式与色温分键报告；4K 下 EIS=不下发该键（非显式 false）；ParamMap 反向探针 FOCUS_MODE_AUTO→AfMode.MACRO(2) 近似、catch 清空集合。不移植项（无 OHOS 等价物）：RGB 增益四件套（kelvinTintToRgb255/gain255ToUnit/rggbFromRgb255/identityColorTransform，手动白平衡走 mode+kelvin，tint 留白）、meteringBox/fullFrameBox/meteringRect（改用归一化 setFocusPoint/setMeteringPoint）、cropBox（只有 setZoomRatio）、afTrigger（无 AF 触发语义）、逐帧 AE_TARGET_FPS_RANGE（预览档 profile 固定，FpsPick 仅 State/dump）。`camera/ParamMap.ets` 承载枚举映射（FocusMode/WhiteBalanceMode/ExposureMode/OIS/VideoStabilization ↔ Camera2 契约值 + 反向探针；Flash 语义经 RequestApplier.flashPlan 派生）。装机验证项（不阻塞）：开预览→参数自检（变焦/色温/补光）→hilog WotaAbility 见每档 apply 与 failed 项 | ☑ |
| `GlRenderEngine.kt`、`ArcMendPass.kt`、`FrostBlur.kt`、`FrostBlurChain.kt`、`NtscGrid.kt` | 平台绑定（GL 着色器本体是纯逻辑） | XComponent + NDK NativeWindow，OpenGL ES 着色器源码几乎原样复用；斑马纹/峰值对焦同在此链路。GL-0（构建骨架/EGL/OnFrame）、GL-1（NativeImage 相机帧 + 基对）、GL-2（旋转镜像/曲线 LUT/斑马/峰值）已落；**GL-3 已落（2026-10-21）**：FBO/RT 基础设施（frostRtSizeInto/FrostRtNeedsRebuild/fillFrostBlurOffset 逐行；RGBA8+LINEAR+CLAMP，glCheckFramebufferStatus 完整性出口）、三趟链（OES→2D 拷贝→横向高斯→纵向高斯，权重 0.17524/0.16577/0.14032/0.10629 逐值，highp→mediump 退避，错误连击 8 帧停链）、PASS_THROUGH_2D_FS 上屏、全屏毛玻璃开关（页面「毛玻璃」）；**GL-4 编码面已落（2026-10-21）**：EGL 第二个 window surface 挂编码器输入面（复用初始化缓存的 EGLConfig）、编码 pass 每帧第二趟且仅新相机帧驱动（vsync 重绘不重复送帧；上屏 swap 后）、编码 UV 口径 deg=stRotDeg 且不镜像（上游 buildTexCoords(encoderPass=true) 语义）、**编码 pass 绝不进入 frost 分支（上游红线：霜面是上屏专属）**、surface 丢失中止；编码 pass swap/绘制连续失败 3 次中止（encoder-pass-failed）；恢复上屏上下文失败 1 次即中止，g_displayCtxLost 每帧重试上限 60 帧（达上限 abort display-context-lost，首帧与上限各一条日志）、WotaGlDestroy 先 detach+abort；**上批 P3×6 修正（2026-10-21）**：帧账录制纪元（frameStart 只计录制窗口；begin 前入队、begin 后执行的残余 frameStart 登记为观测偏差，相机帧取 max(cameraDelta,glCbDelta) 上界，仅作到达上界/不得当精确丢帧数）、sidecar 诊断读取门控（skipped(diag)）、flush DrawPending 失败计 swapFail、display-context-lost 页面提示并禁用录制、实测源帧率整场口径（降级不重置，登记偏离）、MEND attach 最多 2 次重试后降 DROP；frost 开关只释放离屏资源/program（基础 g_idUvVbo 归 EGL 管线生命周期，开→关→开可用）；**GL-4 光弧链在线版已落（2026-10-21，对齐上游 38d712a）**：ARC_MERGE_FS（整像素选择+差分死区）逐 token、ArcMendPass 等价（cur+pending×2+acc×2 五张 unsized GL_RGBA FBO（ES2/ES3 通吃，对齐上游/frost）、两枚 program、完整性出口/Release 清零）、ArcKeepRule/ArcRepairFlow C++ 逐行对拍、MEND/DROP 只吃新相机帧、MEND 离屏补弧与停录 flush、MEND→DROP 降级（降级帧无条件直通补发、不记新 rule 位次）；drops 位次账与实测源帧率、原生五格帧账；**编码 pass/MEND 均不进入 frost 分支（上游红线）**。留白：ARC_MERGE_FS（A/B 修复链已落 shaders.h；离线 GPU 消费 T29）、Frost 画板/圆角 SDF/卡片 UV/letterbox 几何（HUD 批；本批 RT 内容=整屏等比画面，登记与上游 letterbox 口径差异）、NtscGrid（ntscGridStepNs 纯函数，Codec 批）、独立渲染线程与性能控制。已知：全屏毛玻璃与监视效果互斥一帧；`.so` 导入 1 条 napi 未验证工具链提示（已实测无抑制通道）。装机验证项（不阻塞）：毛玻璃开关画面整体模糊、方向与直通一致、关闭恢复效果态；**毛玻璃开→关→开仍可用**；横竖屏下模糊方向与画面一致；`hdc hilog \| grep WotaGL` 见 frost RT/programs/错误日志 | ☑ 部分（GL-0/1/2/3/4） |
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
| `AudioSync.kt`、`CompareAudio.kt`（对齐数学） | 纯逻辑 | **T24 已落（2026-10-22，对齐上游 38d712a）**：`player/AudioSync.ets` —— 常量（TARGET_SR=8000/MAX_SECONDS=30/ENV_WIN_MS=10/SEARCH_MS=10000/REFINE_MS=50/PEAK_GUARD_MS=150/MIN_CONFIDENCE=1.5）+ resample（线性插值、按目标帧数×maxSeconds 封顶）+ envelope（10ms 窗 RMS 去均值归一）+ coarseAlign（±10s 前缀能量归一化互相关）+ 迭代 radix-2 fft/inverseFft + lowPass1k/meanRemove/nextPowerOfTwo/score + refine（±50ms、1ms 步进、次峰排除 ±150ms、置信度主峰/次峰比）；平台解码（MediaExtractor/MediaCodec→8kHz PCM、按容器轨序取第 N 条音轨）留待内录/自动对齐批。`player/CompareAudio.ets` —— Side/AudioTrackKind/audioGroupIndexOf（环境=组0/内录=组1 唯一映射）、SplitAudioPlan+splitAudioPlanOf+splitAudioEffectOf（分屏单引擎音量恒 1，不误静音）、CompareAudioPlan+audioPlanOf（单音轨 CAP 回落 ENV 清 override）+applyOrderOf（静音另一侧→设/清 override→选中侧音量 1）+selectionEffectOf、EngineCommand（kind 判别 + 静态工厂）/OverrideTarget/SelectionEffect。**T25 已接线**：Compare 页 applyAudioPlan 按固定命令序真实下发（setVolume/selectAudioGroup），双音轨门控用 ClipExporter.probe 的 audioCount≥2，静音保留本侧三态扩展；AudioSync 平台解码（MediaExtractor/MediaCodec→8kHz PCM）留 T26 | ☑ 部分（T25 音频接线；自动对齐解码留 T26） |
| `FrameStep.kt`、`TapArbiter.kt`、`CompareTimeline.kt`、`ComparePractice.kt`、`CompareHistory.kt`、`ClipExport.kt`（ClipRule） | 纯逻辑为主 | `player/FrameStep.ets`（SNAP_TOL_MS=1.5、floor+1/ceil−1、duration 钳制）、`player/PlayerModel.ets`（AbRange valid、倍速档/最近档、clampPan 半域）、`player/TapArbiter.ets`（280ms 双击窗口）逐行直译；T19 新增 `player/CompareTimeline.ets`：TimelineGeometry（tMin=min(0,−offset)/tMax=max(leftDur,rightDur−offset)/leftCovers/rightCovers/leftTarget/rightTarget park 规则）+ normalizeOffset（[−rightDur,leftDur] 夹紧）+ rightTargetFor（leftPos+offset 夹到 0..rightDur）+ timelineSpan/timelineEnd，仅手动偏移子集；T22 订正：tMin/normalizeOffset/rightTargetFor 的 -0 归一 +0（纯逻辑测试发现，对齐上游 Long 语义，文件头登记）。T20 新增 `player/ClipRule.ets`：MIN_KEEP_MS=300、ClipRange、normalize（0..duration 夹紧，恰等 300ms 通过）、keepSample（relUs>=0）、clipNameOf（edit_<stem>_<yyyyMMdd_HHmmss>.mp4）、keyFrameIndexOf（四舍五入）、remapDrops（k>=shift 平移到 k−shift，segments 清空登记）、TrackChoice(-1/0/1)+audioOrdinalOf。**T22 纯逻辑单测落地（见 §纯逻辑测试）**：compare-timeline.test.ets（9 用例，off=0 回归红线两条独立钉死）+ clip-rule.test.ets（6 用例）。**T24 全量纯逻辑（2026-10-22，对齐上游 38d712a，接线留 T25）**：① `player/CompareTimeline.ets` 补齐 CompareDomain/DecideInput/DecideCommands/domainOf/resolveT/decide——域判定（黑层回灌消歧/pre-roll 安全网/off<0 头段含边界交接/末态优先）、主钟随域（双素材与左独播跟左钟、右独播跟右钟、末取 Tmax）、双覆盖纠偏（250ms 硬 seek / 邻域 nudge，公式逐字）、独播域 dormant pause+黑层、交接不 seek、时间线末循环开回绕 Tmin（材料感知：park 侧带 pause+黑层，off=0 退化为双 resume）与循环关停末帧、AB 激活不抢回绕、拖动压 seek/nudge/wrap；几何函数零改动。② `player/ComparePractice.ets`：primeWith/takeLeftId/deliver + armed/pendingRightId 状态语义（导出类与模块单例 comparePractice）；**分屏练拍页面未移植，动线接线阻塞**。③ `player/CompareHistory.ets`：Entry(rightId,leftId,ts)、MAX_ENTRIES=12、entriesOf（缺键/坏 JSON 空表、坏行跳过、id≤0 跳过）、recordEntry（rightId 去重+插队首+截 12，返回新数组不改入参）、removeEntry、encodeEntries（键序 rightId/leftId/ts）；preferences 适配留 T25。单测：compare-timeline 25 条（+16）/compare-practice 6 条/compare-history 10 条（见 §纯逻辑测试）。**T25 接线（2026-10-22）**：Compare.ets onTick 交 decide（应用顺序 pause/resume→seek→nudge→黑层写回）、主钟读数 resolveT（负轴 pre-roll）、seekTimeline 统一 park/黑层、setPlaying 末态回 Tmin + wantPlaying 续播、domBlack 常驻层、CompareHistory preferences 适配（CompareHistoryStore，store=wota_player_ab）与历史面板。留白：AB 持久化、练习页分屏（ComparePractice 纯逻辑已落但无消费者，T26） | ☑ 部分 |
| `ClipExport.kt`、`RemuxOrientation.kt` | 平台绑定（封装逻辑纯） | **T20 无损 remux 最小版（2026-10-21）**：`cpp/clip_exporter.h/.cpp`（OH_AVSource/OH_AVDemuxer 逐样本读 → OH_AVMuxer 原样写 MPEG_4，不解码/不重编码；worker 线程 + atomic 进度/取消，状态 `idle/running:<pct>/done:<base>:<v>:<a>[:audio=skipped]/failed:<code>/cancelled`；入点 Seek PREVIOUS_SYNC 取首个 SYNC 帧 basePts，视频/音频 PTS 同减 base，负 rel 丢弃，逐轨 monotonicPts 桥；出点按 PTS 截止，视频 32/音频 8 连续越界容忍 B 帧重排；源/视频轨旋转经 WotaNormalizeQuarter 透传，源格式优先；音轨可选 -1/0/1，预扫区间内零可写样本不建轨，AddTrack 失败退纯视频；先视频整轨后音频整轨；muxer Start 后取消 best-effort Stop）+ NAPI `startClipExport/getClipExportStatus/cancelClipExport/probeClipTracks` + `player/ClipExporter.ets`（fd 生命周期、200ms 轮询、10min 超时、失败/取消删除半成品、done 后按 basePts 关键帧序号 remapDrops 写输出 sidecar、同名追加 _N）+ Player 页「导出 A/B」面板（A/B≥300ms 才显示、暂停、音轨选择、进度/取消、成功路径/大小/媒体库跳转）。错误码 source/track-format/no-video/no-sync/muxer/add-track/rotation/muxer-start/read/sample-too-large/write/empty-range。**T21 修复 T20 复审 P3×4（2026-10-21）**：① done 日志打实际生效 rotation（与 `OH_AVMuxer_SetRotation` 同读归一后的 `appliedRotation`，源格式缺旋转键、视频轨带键时不再打错 sourceRotation）；② 音频拷贝阶段 NO_MEMORY 不判死——跳过剩余音频样本继续视频、done 状态追加 `audio=skipped`（首枚即超限=已 AddTrack 零样本 → WARN + 既有 audioSamples==0/Stop 失败兜底；视频轨 NO_MEMORY 维持 sample-too-large 判死，口径登记 clip_exporter.h）；③ ArkTS uniqueOutputPath 的 accessSync 异常按「被占用」继续 _N 循环、1..99 穷尽回退 `${stem}_${Date.now()}.mp4`（绝不返回 access 异常原候选名，防 TRUNC 覆盖旧成片）；④ 超时 fd 延迟关闭——CANCEL_GRACE_MS(5s) 耗尽进 linger 轮询（NATIVE_LINGER_MS=30s 上限）等 native 离开 running:，linger 内退出照常 close+discard、耗尽挂模块级 pendingFds（exportClip 入口/轮询 reap，Diag.w 登记泄漏），不关正被阻塞调用写的 fd。已知差异：逐轨顺序写入=非交织（真机播放器兼容性待验）、音频只选一条、无 MediaStore pending/commit（导出中直接写目标 mp4，极端并发刷新可能看到半成品）、无分享、clipName 追加时间戳（OHOS 私有目录防覆盖）、remapDrops 清空 segments（剪辑单文件不再适用源段账）。RemuxOrientation 只做旋转透传，不单独移植平台读源。留白：交织写入、双音轨、自动音频同步、剪辑进度持久化/后台保活；T18/T19 其余 P3 分别登记到健康/对比收口批（本批仅 PlayerEngine 音量防护） | ☑ 部分 |
| `PlayerScreen.kt`、`CompareScreen.kt` | UI | `pages/Player.ets` 最小版：播放画面/进度条/播放暂停/逐帧/A-B/倍速/帧率/镜像、单击显隐、双击启停、双指缩放平移（clampPan）；Gallery 卡片进入、返回刷新，顶部「对比」带 {leftId,leftPath} 进 Compare。T19 `pages/Compare.ets` 对比播放最小版：双普通 XComponent(SURFACE)+双 PlayerEngine、右片 MediaRepo 现选（排除左片）、共享播放/暂停/seek/倍速、手动偏移 ±0.5s/归零（park 门控：无覆盖侧停首/末帧，进入覆盖区由 tick 起播）、声音侧左/右/静音（默认左）、120ms tick 漂移校正（阈值 250ms/冷却 500ms）、两侧独立 attach/retry 与 surface 重建换代、页面隐藏暂停/销毁双 release。T20 在 A/B 合法（≥300ms）时增加「导出 A/B」入口与导出面板（音轨选择/进度/取消/成功路径与大小/媒体库跳转，导出期间拦截返回）。**T22 对比收口（2026-10-22）四项关闭 + 平台限制登记**：① 时间线双轴消费——进度 Slider min/max=TimelineGeometry.tMin()/tMax()（off>0 负轴 pre-roll 可拖）、拖动值 t 即时间线坐标、seekBoth 走 leftTarget/rightTarget park 规则、左端标签 tMin/右端 timelineEnd；off=0 在旧单左轴域逐像素等价（回归红线）；timelineSpan 无 UI 消费留白；进度读数仍随左钟（pre-roll 段显 0，最小版不派生真 T，登记）；② 右片 surface 重建/重试恢复播放位置（resumeRightPosMs 快照 rightTargetFor 或引擎实位，tryAttachRight 成功路径 seekTo 恢复；换选右片不恢复；左片既有恢复路径零改动）；③ attach/retry/surface 隐藏期门控——onSurfaceLoaded 重建分支在 !pageVisible 只置 deferred 标志（left/right 各一）由 onPageShow 消费执行、retryLeft/retryRight 入口拒 pageVisible=false、rebuild then 链 attach 前再查 pageVisible；④ 右片列表 id+path 双排除左片（leftClip 未就绪用路由参数原始 path=paramsLeftPath 兜底，防 FNV-1a ID 碰撞）。**漂移"速度微调"以平台限制登记关闭**：AVPlayer setSpeed 仅枚举档（player/PlayerEngine.ets speedToEnum 0.125/0.25/0.5/0.75/1.0 为证，无任意 ratio 接口），上游 AudioSync 的 setPlaybackParams 微调无 OHOS 等价物，邻域纠偏维持 250ms 硬 seek/500ms 冷却。**T23 P3 关销（2026-10-22）**：时间线左端标签负轴改用 `media/MediaFormat.formatSignedDuration`（off>0 的 pre-roll 显示 `-m:ss`；亚秒负值下限 `-00:01`，避免 500ms 偏移步进时与 0 混淆），进度读数仍随左钟口径（不派生真 T，登记保留）；formatDuration 负值归零语义不变，新增 media-format 3 用例钉住正/负/0（含 `2:00:50` 手写字面量）。**T25 对比页接线（2026-10-22）**：onTick 双片就绪且无错误时交 decide（域判定/主钟/交接/纠偏/末段回绕/拖动让路）；应用顺序 pause/resume→seek→nudge→黑层写回；主钟读数 resolveT（pre-roll 为负 T）；seekTimeline 统一 leftTarget/rightTarget park + 无覆盖侧暂停 + atEnd 撤黑 + 会话黑层写回；setPlaying 末态先回 Tmin、wantPlaying 续播意图（换右片/右片晚挂 attach 后补一次）；左右恢复位置快照改素材位；domBlack 常驻黑层（BLACK_LAYER_ENABLED 总开关）；底栏「循环:开/关」；音频计划真实下发（静音另一侧→设/清 override→选中侧音量 1，双音轨 chip，静音三态保留）；历史面板（回填右槽/缺失标记/删除）+ 选右片记账。**T25 审查修复（2026-10-22）**：① surface 重建/重试先记 wantPlaying（旧引擎 release 后 playing 归零），attach 成功走 seekAndPlayIfPaused（seekDone 落地再 play），maybeResumeWanted 不再以「任一侧在播」早退；② seekTimeline 写黑层后按 wantPlaying 统一 resumeDomainAt——拖回双覆盖区 dormant 侧随松手复活（不再无黑层冻结）；③ 换右片/左右 attach 成功后均按当前音频侧重探 audioSideDual，静音态也落轨选择（静默生效，解除静音不复位）；④ 右片未选/报错时 positionMs/sliderValue 跟左钟（重建中保留 T，预录态不回落）；⑤ 历史写入串行队列、number/string 统一 safe integer。进页起播仍为手点播放（上游 attach 起播，登记偏离）；nudge 为平台空操作（250ms 硬 seek 保留）。留白：AudioSync 平台解码与自动对齐入口、练习动线（分屏录制页，T26）、全屏手势锁定/字幕/AB 持久化、剪辑进度持久化 | ☑ 部分 |

## record — 录制链路（移植重心）

| 上游 | 类型 | 鸿蒙落点 | 状态 |
| --- | --- | --- | --- |
| `MrRecorder.kt`（MediaRecorder 引擎） | 平台绑定 | `@ohos.multimedia.media` AVRecorder + CameraKit VideoSession：`camera/KitRecorder.ets` 最小录制（VIDEO_SOURCE_TYPE_SURFACE_YUV 面输入、NORMAL_VIDEO 会话、**videoProfiles + frameRateRange 覆盖选档**（VideoSizeTable 留待后续）、BitratePolicy 复用、SeriesIds 接线、MIN_KEEP_MS 判废）。落地 2026-10-21，对齐上游 38d712a。输出契约：`AVRecorderConfig.url = fd://<fd>`（fileIo.openSync 建文件取 fd，fd 保留到 AVRecorder reset/release 后关闭）；起录失败统一回收资源并删除已创建/半写文件；判废顺序=先 MIN_KEEP_MS（短段含 engineErr 一律 TOO_SHORT），唯一例外 av.stop 失败保留文件 triage（STOP_FAILED）；start/stop 有 starting/stopping 重入保护。留白：3.5GiB 分段轮转/起录健康三格/AutoStop 巡检（GPU 已接，AVRecorder 留白）/FrameLedger 接线/HEVC 重试/.drops.json/参数下发（VideoSession 无 ManualExposure/ManualIso/OIS）/orientationHint 与 AVRecorderConfig.rotation 未接线（显示旋转源随 HUD 批）/sampleRate 恒 48k 未探测/mirrored/useGpu 恒 false。装机验证项（不阻塞）：录 5 秒文件可播放有声音、停录后预览恢复、拒绝 MIC 录无声视频、<1 秒判废删除、fd:// 契约真机验证 | ☑ 部分 |
| `CodecRecorder.kt`（MediaCodec+MediaMuxer 引擎） | 平台绑定 | AVCodec + AVMuxer（NDK 侧），与 GL 链路衔接。**最小切片已落（2026-10-21，对齐上游 38d712a）**：`cpp/codec_recorder.h/.cpp`（OH_VideoEncoder CreateByMime→RegisterCallback→Configure→GetSurface（Prepare 前）→Prepare→muxer Create/AddTrack/Start→encoder Start；surface 输入、输出回调直写 OH_AVMuxer，PTS 一律 wall-clock 相对段基准（NowUs−startUs，µs）并经 OH_AVBuffer_SetBufferAttr 回写、lastPts 单调守卫（前提：无 B 帧，登记）；容器旋转 OH_AVMuxer_SetRotation(normalizeQuarter(sensor−display))（0/90/180/270 四舍五入，非 90 倍数 sensor 按上游归一；须在 muxer Start 前；编码 UV 仅送缓冲原样帧）；I 帧间隔 1000ms；FRAME_RATE/BITRATE 按 SDK 类型（double/int64）写入并检查；EOS 等待 ≤3s；错误机器码 busy/encoder-create/register-callback/format-create/format-key/configure/get-surface/prepare/muxer/track-format/track/muxer-rotation/muxer-start/start/encoder-surface；运行期 muxer-write/pts-set/muxer-missing/engine-error/surface-lost/encoder-pass-failed；muxer 写失败/时间轴回写失败即停止送样（samples 仅成功自增），错误经 getGpuRecordError/stop 带回；native 不 close fd）+ `camera/GlRecorder.ets`（fd 归 ArkTS、VideoStore 命名/落盘与 MIN_FREE_MB 起录预检、停录 close+stat 摘要；napi-failed 先 best-effort stop 再 close）+ GlRecorder（起录弧档/dstFps/deadband 传递；停录顺序 flushArcPending→（阻塞 stop 前冻结帧账 end 快照）→stop→逐项 drainArcDrops/帧计数/实测源帧率→close+stat→FrameLedger 成文→写 `.drops.json` sidecar（segments.count=muxer 视频样本数（part.samples，上游 VideoSegment.count；与首段旁 sidecar 同口径）；frameStart 不可用时相机帧退原生 GL 回调上界））+ GlPreview「录 GPU/停录」与三态转换按钮（关/补弧24/抽帧24，固定 1920×1080@30 或 24、BitratePolicy.resolve；500ms 监视 isGpuRecording/getGpuRecordError 校正录制态）+ **AutoStopMonitor（2026-10-21）**：1s 相机设备态转移（启动时以当前设备态预置 watch；session error 直连兜底）、首个 1s tick 立即查电量 batterySOC≤3% 与可用空间 <MIN_FREE_MB（其后 30s 节拍），一次录制至多一发，命中提示“成片已保存”；手动停录/相机丢失兜底统一按 AutoStopRules.stopGraceDelayMs 做 500ms 宽限（stopPending 防重入，首个停录原因合并）。相机丢失后 lensInfo/deviceStatus 失效，需重新进入本页起镜后才允许再次录制。**3.5GiB 分段轮转已落（2026-10-21）**：native `getGpuWrittenBytes`（成功写入 attr.size 累计）+ ArkTS 500ms 轮询 maybeRotate；SegmentPolicy（MAX_FILE_BYTES=3_758_096_384、ROTATE_SAFETY=0.98）；轮转=停段（EOS）→新文件→重建编码器/EGL 面（段间 lostMs 记账），每段独立 MP4/时间轴、各自 displayName；逐段 FrameLedger（partIndex/rotateLostMs/rotateFramesUpper）与 `frameSessionSummary`；首段旁 `.drops.json` 的 segments 覆盖全部段（first 前缀和/part.samples/lostMs），drops 只保留最后一段（上游跨段失真登记）；8MB 测试阈值入口（离开页面恢复默认）；summary 的 measuredSrcFps 标注为最后一段口径；**T22（2026-10-22）**：SegmentPolicy 无自动化单测登记撤销（test/pure/segment-policy.test.ets 5 用例，见 §纯逻辑测试）、stop 摘要追加末段账 lastPath/lastBytes/lastSamples（健康收口丢段专用）、被丢弃零样本段在首段 sidecar segments 留 count=0 条目（无害，登记——首段 sidecar 不回写）。上批 P3×4：起录后 1s 冷却（迟到点击不覆盖摘要）、删除 lastStopHadReason 死字段、Player metaNotice 移出控制层、stopReason 起录清理。本批 P3×5：轮转错误先 finish 再追加摘要、onPageHide/离开页面复位 8MB 测试阈值、起录清 lastStopAtMs、napi-failed 摘要带 path 保留 triage、~~SegmentPolicy 无自动化单测登记~~（T22 撤销）。 T18 P3×8：bootstrap/权限 pageVisible 门控、重试相机按钮 disabled(startingCamera)、SettingsStore.save 返回 boolean + 保存串行化、变焦实际值/最近档、HUD_READOUTS 驱动顶栏、appendNotice 冷却/错误追加、相机丢失清镜头/画幅标签、预览/录制帧率分显。留白：音频轨、参数下发、PTS 网格化、成片名前缀、MediaStore、NV12 直通与 DIRECT 路、系列归组 UI。已知：容器旋转 hint 在起录时定格（录制中转屏不更新，单段口径，登记）。装机验证项（不阻塞）：GL 页录 5s→hdc file recv 取回 mp4 可播放且时长 5s 量级；ES2 回退下 MEND 不降级（unsized GL_RGBA）；时间戳单位 ns/µs/ms 归一与实测源帧率合理；竖屏成片方向正确（muxer SetRotation）；停录后再次起录正常；录制中返回页面→录制中止、无崩溃；`hdc hilog \| grep -E "WotaGL\|WotaRecord"` 见编码面建立/首帧/EOS 日志 | ☑ 部分 |
| `CaptureFgService.kt`（前台服务） | 平台绑定 | 长时任务（continuous task）+ 通知栏常驻 | ☐ |
| `PlaybackCaptureController.kt`（内录） | 平台绑定 | **T26 纯逻辑已落（2026-10-22）**：`record/CaptureStateMachine.ets`（Idle→Authorizing→Active/Failed→Revoked 全转移 + 状态/事件短名）、`record/CaptureSilence.ets`（SilencePromptGate 按场 60s 节流 + captureSilenceVerdict 2s/5s 双门槛 + fedMsOf）、`record/AudioProbe.ets`（frameBytes/chunkBytes/recordBufferBytes/dbOf + captureParamMismatch 档位体检）、`record/CaptureSources.ets`（OH_AudioCaptureSourceType 契约 MIC=1/ALL_PLAYBACK=2/APP_PLAYBACK=3 + 内录源优先级 [ALL_PLAYBACK, APP_PLAYBACK]）；**平台面（AVScreenCapture 会话 + 隐私授权 + 双源采集 + AAC 三轨合流）T27 真机批**，调研结论见 §T26 平台调研 | ☑ 部分（T26 纯逻辑，平台 T27） |
| `Recorder.kt`、`CaptureStateMachine.kt`、`RecordProfile.kt`、`VideoStore.kt` | 纯逻辑为主 | 直译（状态机与接口不动，引擎实现注入）。最小契约已落：`record/RecordResult.ets`（RecordError 机器码 + RecordResult 首段字段/summary，VideoSegment/health/parts 留待分段批）、`record/RecordProfile.ets`（纯字段子集 + CODEC_H264/CODEC_HEVC/MIN_KEEP_MS + effectiveBitrate，captureAudio/arcConvert/fpsNtsc 留白）、`record/VideoStore.ets`（VID_yyyyMMdd_HHmmss_###.mp4 命名/MIN_FREE_MB 余量/sizeOf/discard；MediaStore pending 与相册入库留白）。分段轮转每段各自 displayName/文件，段序号只在 parts/segments/日志；系列归组（MediaSeries/SeriesPart）留白。落地 2026-10-21，对齐上游 38d712a。**T26 追加**：`record/CaptureStateMachine.ets` 直译上游授权状态机（16 用例）；`record/CaptureSources.ets` 因 Android usage 白名单无 OHOS 等价物，改以源枚举优先列表承载（差异登记于文件头）；`record/AudioProbe.ets` / `record/CaptureSilence.ets` 落可测算式与判据（消耗方留 T27） | ☑ 部分 |
| `ArcKeepRule.kt`、`ArcDropLog.kt`、`BitratePolicy.kt`、`AutoStopRules.kt`、`FrameLedger.kt` | 纯逻辑 | **逐行直译**——这些是与上游共享语义的锚点（见 UPSTREAM_SYNC.md），格式/判据/阈值必须与上游逐字段一致。直译完成 2026-10-20，对齐上游 38d712a，落点 `entry/src/main/ets/record/` 同名 .ets；BitratePolicy 的 `resolve(RecordProfile)` 重载待 RecordProfile 移植后补。在线消费已接线（2026-10-21）：ArcKeepRule/ArcRepairFlow 的 C++ 等价物（`cpp/arc_flow.*`）跑在编码支路，ArcDropLog 由 GlRecorder 停录写 sidecar，FrameLedger 合成原生五格+相机 frameStart（分段时逐段成文）；ArcDropLog segments 已随分段写全段清单（drops 只保留最后一段，跨段失真登记）；AutoStopRules/CameraLostWatch 由 `record/AutoStopMonitor.ets` 消费（GPU 主路径：1s 相机/30s 电量存储、一次一发、停止宽限 500ms；AVRecorder 路径留白） | ☑ |
| `RecordHealth.kt` | 纯逻辑 | `record/RecordHealth.ets`：GPU 三格（trackAdded/muxStarted/firstVideoSample）+ 判定顺序（错误码→三格→视频短窗 1.2s→完整窗 3s→WAIT）、retryActionOf（attempts<maxAttempts）、MIN_OUTPUT_BYTES=64KiB、resolveOutputBytes/streamHealthVerdict（AVRecorder 路纯函数留白）。**GPU 主路径已接（2026-10-21）**：native CodecRecorder 暴露 trackAdded/muxStarted/samples（getGpuHealth），Camera 500ms 巡检、最多自动重录 2 次、仅重开录制段不重开相机。T18 P3×4（T19）：GlRecorder stop 摘要补 samples、空段仅 samples=0 且 bytes=0 丢弃、onPageHide/Show 续跑巡检、GIVE_UP 文案常量生成、native HealthSnapshot 单锁快照。**T22 健康收口（2026-10-22，对齐上游 38d712a）四项关闭**：① 三格真信号——native `formatChanged_`（OnStreamChangedCb 置位，Start/FailStart/Stop 复位）= 上游 MediaCodec format-changed 等价物，getGpuHealth 扩 4 字段 `track,mux,samples,fmt`，Camera checkHealth 容 4 字段（3 字段旧序防御），healthVerdict 的 videoTrackAdded 实参 = `fmt || samples>0`（samples 兜底防回调时序不触），muxer AddTrack 位降为 Diag 诊断字段（三格语义对照表见 Camera.ets 头注释）；② 轮转重启健康窗——上一段已健康停巡检后轮转新段重启巡检（幂等），**healthAttempts 会话级不清零（与上游"每次起录清零"口径差异登记：防轮转循环无限重录）**，隐藏页不重启只置 healthPausedByHide 由 resumeHealthAfterShow 消费；③ 健康重录三残差——restartSegmentAfterHealthFailure 重置 recordStartedAtMs（宽限期口径）、GlRecorder.stop 摘要追加末段账 `lastPath/lastBytes/lastSamples`、健康重录与 GIVE_UP 丢段读末段账（仅末段 samples=0 且 bytes=0 才删，**保留已完成前段**；被丢弃零样本段在首段 sidecar segments 留 count=0 条目，无害登记）；④ 纯逻辑单测落地（test/pure/record-health.test.ets，21 用例，判定顺序五条各被点名，见 §纯逻辑测试）。AVRecorder 健康检查/stream 活性路留白（Index 未接） | ☑ 部分 |
| `ArcRepairPlan.kt`、`ArcRepairRunner.kt`、`ArcRepairGl.kt`（离线修复） | 平台绑定（计划逻辑纯） | 计划直译；执行走 CPU（ArkTS/NDK）与 GPU（复用 GL 着色器）双路。**在线 ArcRepairFlow 已落（2026-10-21）**：`cpp/arc_flow.h/.cpp` 的 Hold/Fold/Emit/Merge 指令序列 + `cpp/arc_mend_pass.*` 执行。**T28 纯逻辑已落（2026-10-22）**：`record/ArcRepairPlan.ets`（有理数抽帧计划 dstFrameCount/isKept/progressOf/of，29.97 与 25.4 真值位次锚点；不移植旧 Int 外壳）、`record/ArcRepairFlow.ets`（Hold/Fold/Emit/Merge 流式状态机 + 计划路 onSourceFrame + 在线分类路，PTS=keptCount·1e6/dstFps，补上 cpp/arc_flow.h 所引的 ArkTS 锚点）、`record/ArcRepairMerge.ets`（CPU 4:2:0 2×2 块整像素选择，Uint8Array 无符号 Δ、块内最大 Δ、4Y+U+V 整块搬运不造色）、`record/ArcRepairVerdict.ets`（arcRepairOk 三格 + arcRepairKeep 的 stop 门槛）；单测 41 条（plan 15/flow 9/merge 12/verdict 5）。**T29 CPU 执行器核心已落（2026-10-22）**：`cpp/arc_repair.h/.cpp`（单例+worker 状态机、probe：宽高/旋转/容器 fps/时长/音轨数/帧距中位数（≤2M 样本）、同步模式解码 → 紧打包 I420（NV12/NV21/I420 + stride/sliceHeight，未知格式 ARC_CPU_YUV）、ArcRepairFlow 指令执行（cur/pending/acc 三面整像素合并）、同步模式编码（YUVI420 缓冲输入、显式 PTS=j/dstFps）→ 首枚输出后 AddTrack/SetRotation/Start → 逐样本写 muxer、取消/deadline/状态串）、NAPI 四口（probeArcRepairSource/startArcRepair/getArcRepairStatus/cancelArcRepair）、`record/ArcRepairRunner.ets`（fd 生命周期/200ms 轮询/取消宽限+linger/失败 discard/成功 stat/`contract()` 启动日志）、`record/ArcRepairSessionModel.ets`（24 用例：错误码/probe 与状态串解析/速率回退/NO_NEED/去重门限/帧数估算/结果归因/note）。**T30 音频直拷 + Player 面板已落（2026-10-22）**：运行期保留全部 `audio/` 轨（`audioTrackIndices/audioFormats/audioDstTracks` 并行表），首枚视频输出时在 SetRotation/Start 前逐轨 AddTrack（失败逐轨降级）；视频 EOS 排空后用同一 fd 新建 audio source/demuxer，逐轨 select→SeekToTime(0,PREVIOUS_SYNC)→逐样本读，PTS 按视频首帧归一（视频基准缺失退回本轨首样）、负值丢弃、`MonotonicPts` 逐轨兜底后写 muxer；done 串 `audioSamples` = 全轨成功样本合计；`record/ArcRepairSessionModel.ets` 增 `describeRepairError`/`audioLaneNote`（29 用例）、`ArcRepairRunner.run` 可复用面板 probe 结果；Player 页「修复光弧」面板（probe 拦截 NO_NEED/无视频/失败、目标 24/25、死区 0/12/32、进度/取消/成功跳媒体库、与导出 A/B 互斥、忙碌拦返回）。**GPU 路线（ArcRepairGl）与真机调优 T31** | ☑ 部分（T30 音频+面板 / GPU T31） |
| `PreMuxVideoBuffer.kt`（起录缓冲）、`SeriesIds.kt`、`PendingName.kt`、`StalePendingSweep.kt`、`RotateGuard.kt`、`RotateFailLog.kt`、`OrientationExport.kt`、`AudioFeeder.kt` | 纯逻辑为主 | 直译。`SeriesIds.ets`、`PendingName.ets` 直译完成 2026-10-20（对齐上游 38d712a，落点 `entry/src/main/ets/record/` 同名 .ets）；其余待后续 | ☑ 部分 |

## ui — 界面（全部 ArkUI 重写）

| 上游 | 类型 | 鸿蒙落点 | 状态 |
| --- | --- | --- | --- |
| `CameraScreen.kt`、`MainActivity.kt`、`HudLayer/HudLayout/HudLayoutEditor/HudMetrics/HudPageGrid.kt`、`CameraPills.kt`、`SettingsScreen.kt`、`SettingsLayout.kt`、`ParamsPersist.kt`、`PreviewCoords.kt` | UI | **最小主 UI/HUD 已落（2026-10-21，对齐上游 38d712a）**：`pages/Camera.ets` 全屏 GL 预览 + 固定 HUD（顶栏两行读数/3×3 网格/底栏/录制计时）+ 参数胶囊（镜头/画幅/帧率/变焦/闪光/效果/光弧/网格点按循环；快门/ISO/EV/WB 点按开 PillPopup 就近浮层）+ GlRecorder/AutoStop/宽限/分段接线；**T21 高级参数批（2026-10-21）**：`ui/ParamPills.ets` 五件直译（对拍 CameraPills.kt:236-628——AeModeRow（AUTO/MANUAL/LOCK，manualExposurePossible 门控）、ShutterPill（梯=supported 档滑杆吸附、Float 往返经梯值反查原始 ns、仅 MANUAL 显示滑杆、fpsHi=pick.lo 与下发同源）、IsoPill（isoLadder 仅 MANUAL 可调）、EvPill（evLadder+evText、MANUAL 只读注记）、WbPill（预设网格+MANUAL 色温滑杆 kelvinLadder，**tint 副轴留白**））、WotaParams 参数总线接线（起镜成功 `applyAbility(engine.refinedAbility())`、fps 档位联动重算）；顶栏第二行 快门/ISO/EV/WB 读数（AE AUTO 时 ISO 显 AUTO、EV 仅非 MANUAL 显示、wbShort 预设缩写+色温、能力缺失 `-`；`HudSpec.HUD_ROW2`）——**偏离登记：上游 READOUT 是可编排网格（hud_items 位掩码 + HudLayoutEditor），本批固定两行**；胶囊行追加 快门/ISO/EV/WB 四颗（点按开浮层，录制门控 `ParamPills.RECORDING_EDITABLE_PILLS` = {ISO,EV,WB}——**上游含 ZOOM/FOCUS，两颗胶囊本批未移植，差异登记**；快门录制中开窗只读 Note）；每次起录前与弹层改动 `engine.applyParams` 下发（RequestApplier 逐键报告，failed 项 Diag.w + appendNotice）；`pages/Settings.ets` 六项设置（画幅/帧率/码率/光弧/效果/网格）经 `ui/SettingsStore.ets`（preferences）持久化；`ui/HudSpec.ets` 固定布局常量；主入口改为 Camera（EntryAbility loadContent），Index/GlPreview 保留开发者测试入口；pageVisible 门控隐藏页 async 起镜链、启动失败/session error 提供「重试相机」、applyZoom 按实际生效值显示、Settings 保存串行化/类型白名单、HUD 读数顺序常量与顶栏一致、媒体库入口去重；效果槽同时作用于预览与编码（`gl_render DrawScene` 同一 GL 效果 program）。**T21 复审 P3×3 收口（T22，2026-10-22）**：① 快门读数 ※——syncParamLabels 在 MANUAL 且 ability 在册且 forcedShutterOutOfRange 时追加 `※`（上游 CameraScreen.kt:1195-1203 口径，与快门弹层/下发侧同源判据）；② applyParamsFromPills 的 skipped 摘要改 Diag.i（上游只进日志不占用户提示位），仅 failed 留 appendNotice；③ 相机丢失/session error 处置路径统一 closePill()（onCameraLost 回调首行收口弹层）。**T31 取景页真机取证与修复（2026-10-22，HUAWEI ALN-AL00 / HarmonyOS 7.0.0.109 / API 26 / 1260x2720）**：`ui/HudTokens.ets`（深色色板/字阶 11-12-13-17/触控 48/圆角间距/遮罩令牌）、`ui/WindowChrome.ets`（沉浸式 setWindowLayoutFullScreen + 透明系统栏白图标 + AvoidArea TYPE_SYSTEM|TYPE_CUTOUT 转 vp 安全区；普通页恢复黑底系统栏）、`ui/HudPill.ets`（@Component+@Prop 替 @Builder 值参数——真机「顶栏正确而胶囊恒为 镜头:—/画幅:—」的根因）、`ui/HudMoreSheet.ets`（复用 PillPopup 遮罩/量框：闪光/效果/光弧/网格）；Camera 顶栏两行各 5 项（label 11fp/value 13fp）+ 右侧 48vp 深色入口胶囊、顶部/底部渐变遮罩、Dock 2×4 主胶囊（镜头/画幅/帧率/变焦 + 快门/ISO/EV/WB，48vp、均分、无横向 Scroll）、更多+72vp 圆形快门（44vp 红点/录制中 24vp 方块/收尾禁用）、确定性 3×3 网格（2 横 2 竖 Divider 替代百分比 position——真机旧实现网格不可见）、删除「预览—fps」死读数（只显「Nfps」）；Settings 重做（List 顶对齐消除顶部 539px 空白、48vp 选项、保存提示定高不推动、开发者入口默认隐藏→版本号 7 连点显形）；Index/GlPreview 补页头+返回、Index 长文案改 13fp 可滚动日志区；EntryAbility 强制 COLOR_MODE_DARK；Gallery 恢复黑底系统栏。**T31 复审收尾（2026-10-22，P3×5+清理）**：底栏去固定高（相机断开/权限拒绝时状态行与重试按钮不裁 8 胶囊与 72vp 快门）、Dock 胶囊 12fp+左右 6vp、顶栏 fps 读数去「录」前缀（360vp 宽 4/5 等分不省略）、快门/ISO/EV/WB 录制中恢复可点开窗（只读由弹层 editable 承担，不再整颗禁用）、PillNote 11fp（HUD 弹层无 10fp）、WindowChrome 模块级 Promise 链串行 + EntryAbility.loadContent 前 prime（冷启动首帧无白条、设置/相机往返不乱序）；删死代码（GlCameraEngine.currentPreviewFps/previewFps 字段、Camera.fpsLabel、HudSpec.PILL_ROW_HEIGHT/gridFractions、HudTouch.MIN/HudShape.PANEL_RADIUS/HudColor.SUCCESS、SafeInsets.left/right 留 T32 横屏）。**真机复验未执行：已安装包签名指纹 48A3BDCE… 与本仓 `.local-signing` 自签证书 C8D36043… 不匹配，按安全红线不安装，交付源码/HAP 由用户以 DevEco 同证书重装后验证**（待验项见「真机待验」T31 节）。留白：HudLayout/HudLayoutEditor 自定义布局、ZOOM/FOCUS 胶囊与弹层、高级参数持久化（上游 ParamsPersist；本批会话内有效）、tint 副轴、PreviewCoords、SheetSpec/Widgets 全量、UIOrientation 接线（T32，本批保持系统方向） | ☑ 部分 |
| `design/Tokens.kt`、`PillPopup.kt`、`SheetSpec.kt`、`Widgets.kt` | UI（设计令牌近纯逻辑） | **PillPopup 最小实现已落（T21，2026-10-21）**：`ui/PillPopup.ets`——WotaPillPopup（半透明遮罩点击关 + 量框后定位面板 + 标题/收起钮；锚点取 `getUIContext().getComponentUtils().getRectangleById`（窗口 px），**取不到回退底贴屏居中**）、PillAnchor.place 纯函数直译（margin/right/bottom/above/below 钳制，MAX_HEIGHT_RATIO=0.62、gap 8vp/margin 6vp/面板 max 400vp 照搬）、PillChoices（网格单选 + disabled 灰显且点不动）、PillSlider（ladder 刻度滑杆：索引空间运行+nearestLadderIndex 吸附+梯不足 2 点禁用占位不退回连续）、PillNote。**T21 复审 P3（T22，2026-10-22）**：撤销 shown 封口——onMeasured 记录 lastW/lastH，onAreaChange 报告尺寸与上次放置尺寸不同时重跑 PillAnchor.place 更新位置态（内容增删导致面板尺寸变化时重排；同尺寸跳过 + place 纯函数 ⇒ 无重入环），shown 首测语义保留（首量显形）。未迁（登记）：LocalMotion 动效（淡入/0.94 长开）、TextScaleLayer、HDS 阴影/令牌色（静态深色近似）、WotaChip 视觉令牌、PillToggles/PillRowList（无消费方）。**T31**：新增 `ui/HudTokens.ets`（相机 HUD 自有令牌：深色色板/字阶 LABEL11-PILL12-VALUE13-TITLE17/触控 48/圆角/间距/遮罩渐变），是上游 HDS Tokens 的**静态子集等价**（非逐值移植，登记）。Tokens/SheetSpec/Widgets 其余仍待后续 | ☑ 部分 |
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

## 纯逻辑测试（T22 落地，T23/T24/T25/T26/T28/T29/T30 扩展，2026-10-22）

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
- **已落用例（235）**：record-health（21，直译上游 RecordHealthTest.kt 已移植面，判定顺序五条
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
  audioLaneNote 1；T30 审查修复：planForTarget 接线 1 + 逐档 NO_NEED 1）。
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
  与 1080p 基准；蓝牙/更新检查/HUD 布局编辑器等长尾。
- **T30+：长尾批**——蓝牙音箱面板（`BtSpeakerController.kt`）、应用内更新检查
  （`UpdateChecker/Downloader/Installer.kt`）、HUD 布局编辑器
  （`HudLayout/HudLayoutEditor/HudMetrics/HudPageGrid/ParamsPersist.kt`）、
  相册入库/分享/缩略图/系列归组等媒体长尾。

## 建议移植顺序

1. ~~`core` + 各包纯逻辑~~（core 第一轮 ☑ 2026-10-21，`diag` 最小移植 ☑，`record` 锚点七件 ☑ 2026-10-20）——打下语义地基；
2. DevEco 骨架 + `diag`；
3. 相机预览（XComponent）→ `ui` 基础 HUD；
4. `MrRecorder`（AVRecorder）最小可用录制 → `CodecRecorder`（GL + AVCodec）；
5. 光弧工具链（复用着色器与 ArcKeepRule）；
6. `media` 媒体库 → `player` 播放/剪辑 → 对比播放；
7. T28 后按「交付后路线」推进：T27 内录平台面四步（T27-1 平台探针 → T27-2 ENV 音轨 →
   T27-3 CAP 双轨 → T27-4 消费面）与 T30 离线修复收口 → T31 GPU 路线/真机调优与 T32+ 蓝牙/
   更新检查/HUD 编辑器等长尾。
