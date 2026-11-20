# 功能映射（Android 非 AI 功能 → iOS）

基线：`Android/` 只读参考，提交 `7394187`。本表覆盖 **全部非 AI 功能模块**；
AI 站位一律不移植，逐条列在末节并标 `排除（AI，不移植）`。

## 列义与状态词表

- **ID**：稳定标识，`F-xxx`，只增不改；同一功能未来细拆用 `F-xxx.N`，不重编号。
- **Android 源**：仓库相对路径 + 行号（`路径:行`）。多个用 `<br>` 分隔。脚本 `verify_source_inventory.py` 逐条验证路径存在且行号 ≤ 文件行数。
- **iOS 目标**：拟落点（`iOS/WotaGeiCam/...` 或 `Packages/WotaCore/...`）。落点目录由 A2-S 骨架确定，文件可后建。
- **转换说明**：语义等价要点 / 平台差异 / 降级路径 / 禁止事项。
- **验证项**：构建或人工验证项（本任务不做测试、不跑模拟器、不截图）。
- **状态**：`待实现` ｜ `待实现（平台无等价，需裁决）` ｜ `已实现（A2-S 骨架）` ｜ `排除（AI，不移植）`。

> 说明：`已实现（A2-S 骨架）` 仅用于骨架已真实交付且可结构校验的项（证据路径随行给出）；
> 其余功能在本表中一律 `待实现`，文档不代替实现证据。平台无等价的功能标记为
> `待实现（平台无等价，需裁决）`，交由用户在升级规则下裁决，不代用户删减。

## 功能模块清单

| ID | Android 源 | iOS 目标 | 转换说明 | 验证项 | 状态 |
|---|---|---|---|---|---|
| F-000 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/MainActivity.kt:534 | iOS/WotaGeiCam/Camera/CameraPreviewScreen.swift | 单入口 + 串行会话；骨架已交付真实 `AVCapturePreviewLayer` 预览与权限/生命周期/错误反馈，无 Material。AVCaptureSession 与 Camera2 非语义等价，能力差异见 B1。 | 骨架编译（`bash iOS/scripts/apple_build.sh`，本机无 Xcode 未执行）；人工：真机首启出预览非黑屏 | 已实现（A2-S 骨架） |
| F-001 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/core/CameraAbility.kt:49<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/core/CameraAbility.kt:222 | iOS/WotaGeiCam/Camera/（设备能力探测） | 运行时提取相机能力（硬件级别/朝向/分辨率/FPS 区间/ISO/曝光/EV/变焦/AF/AWB/闪光/区域数/OIS-EIS/高速能力）。iOS 侧用 `AVCaptureDevice.formats`、`activeFormat`、`deviceCapabilities`、`AVCaptureDevice.Format.videoSupportedFrameRateRanges` 重建等价结构；**不得**假定与 Camera2 键一一对应。 | 编译：能力结构体可构建；人工：真机打印能力并与 Android 真机对照 | 待实现 |
| F-002 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/camera/RequestApplier.kt:435<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/camera/RequestApplier.kt:554 | iOS/WotaGeiCam/Recording/（参数应用） | WotaParams → CaptureRequest：闪光计划、防抖计划（OIS/EIS）、ISO/EV/快门/帧率/AF/变焦/白平衡色温与色调、测光区域、降噪、读出修正、FPS 选择。iOS 无同一套键：快门=曝光时长或 ISO 手动、色温=增益重算、测光区域=对焦点式曝光/白平衡。每参数必须有支持判断 + 降级路径。 | 编译：每参数有 gating 分支；人工：真机逐参数切档不崩、可回退 | 待实现 |
| F-003 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/core/WotaParams.kt:239<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/core/WotaParams.kt:79 | Packages/WotaCore/Sources/WotaCore/（参数状态机） | 参数状态容器 + 运行时钳制/门控（applyExposure/Ev/Zoom/Focus/WhiteBalance/Size/ModeGates）。纯逻辑可在 WotaCore 重写（Swift），绑定 AVFoundation 的 gating 留在平台层。 | 编译：纯逻辑包可构建；人工：越界参数被钳到可用档 | 待实现 |
| F-004 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/CodecRecorder.kt:49<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/CodecRecorder.kt:1022 | iOS/WotaGeiCam/Recording/（录制引擎） | 双引擎（MediaRecorder 简易 / MediaCodec+MediaMuxer 精细）择一。iOS 统一为 `AVAssetWriter` + `AVCaptureVideoDataOutput`（元数据用 `AVAssetWriterInput`）实现单一引擎即可，但须保留：分段轮转、GPU/CPU 渲染切换、高帧率与 arcConvert 强制走精细引擎的判据。算法重写，不翻译 GL/GLSL。 | 编译：writer 管线可构建；人工：真机录一段、段轮转、停止后可播放 | 待实现 |
| F-005 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/RecordProfile.kt:19<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/core/Constants.kt:28 | Packages/WotaCore/Sources/WotaCore/（规格） | 录制规格（宽高/帧率/captureRate/码率/编解码 H.264 或 HEVC/音频采样率/声道/音频码率/方向提示/镜像/GPU/帧率 NTSC/arcConvert/内录/降噪）+ 档位表（码率 5/10/20/35/50 Mbps、采样率 32k/44.1k/48k、FPS 24/25/30/50/60/120/240、快门分母、maxFile 3.5GiB、minFree 200MB）。用 WotaCore 承载规格与档位表（纯逻辑）；编码参数在平台层落到 AVAssetWriter 输出设置。 | 编译：规格结构体与档位枚举可构建；人工：真机选档后成片参数一致 | 待实现 |
| F-006 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/AutoStopRules.kt:22<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/AutoStopRules.kt:59 | Packages/WotaCore/Sources/WotaCore/（自停规则） | 电量 ≤3% 停、剩余 <200MB 停、相机丢失停、30s 巡检、500ms 停止宽限。纯判据进 WotaCore；电量/存储读数在平台层（`ProcessInfo`/`URL.resourceValues` 或 `AVAssetWriter` 估算）。 | 编译：判据纯函数可构建；人工：低电/满盘场景真机自停并提示 | 待实现 |
| F-007 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/VideoStore.kt:36<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/VideoStore.kt:57 | Packages/WotaCore/Sources/WotaCore/RecordingFileNaming.swift | 成片入库：目录 `Movies/WotaGeiCam/Camera`、MIME video/mp4、命名 `VID_yyyyMMdd_HHmmss_%03d.mp4`、空间门槛。iOS 无 MediaStore/IS_PENDING；改为 App 自有目录落盘（命名与目录仍对齐 Android），导出/可见性经显式 Photos 动作，不引入隐式媒体库行为。 | 编译：命名与路径纯函数可构建；人工：真机录制产出文件名/目录与 Android 一致 | 待实现（命名纯函数骨架已落 `WotaCore.RecordingFileNaming`） |
| F-008 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/PendingName.kt:1<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/SeriesIds.kt:1 | Packages/WotaCore/Sources/WotaCore/SeriesIDGenerator.swift | 段命名去 pending 垃圾（正则 `\.pending-\d+-`）、系列命名收敛、系列 ID = 时间基×1000 + 进程内单调序号（避免改 schema）。纯逻辑进 WotaCore。 | 编译：命名/ID 纯函数可构建；人工：多段录制系列归组正确 | 待实现 |
| F-009 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/StalePendingSweep.kt:1 | iOS/WotaGeiCam/Library/（冷启动维护） | 冷启动一次扫描：仅扫 `Movies/WotaGeiCam/Camera`、1h 龄阈值、≤500 条、clip 永不删、有行 ⇒ provider rename、查不出 ⇒ 不动、目标已存在 ⇒ 不覆盖、reconcile 坏行/补扫缺失 clip。iOS 无 provider/MediaStore；改为对 App 自有目录与自有索引做等价 reconciliation（不覆盖为硬约束）。 | 编译：reconcile 可构建；人工：制造半成品/坏行，重启一次收敛且不删不覆盖 | 待实现 |
| F-010 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/ArcRepairRunner.kt:1 | iOS/WotaGeiCam/ArcRepair/（抽帧补弧） | 录制期抽帧补弧（arcConvert）与离线修复：有理数保留集、PTS 网格、零样本轨剔除、加轨降级、失败归因码。**GL/GLSL 不得直译**；Metal 管线独立重写（见 C1）。判据层可先进 WotaCore。 | 编译：修复判据/计划层可构建；人工：真机开补弧录制成片帧率/时长正确 | 待实现 |
| F-011 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/ParamsPersist.kt:70 | iOS/WotaGeiCam/Settings/（参数持久化） | 34 键白名单入档、坏值回默认、会话影子只在用户动作完成点 flush、仅写与影子不同的字段。iOS 用 `UserDefaults` 承载，白名单/默认值/排除项逐条对齐（见 settings.md）。 | 编译：持久化层可构建；人工：改参数→杀进程→重启参数保持；未列入白名单的项不持久 | 待实现 |
| F-012 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/media/MediaActions.kt:1<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/media/WotaDb.kt:175 | iOS/WotaGeiCam/Library/（库元数据） | 收藏/标签、系列归属、缩略图缓存索引。iOS 侧用 SwiftData/Core Data 或 SQLite 建等价表（媒体指纹键：relativePath/name/size/duration，不用 MediaStore ID）。 | 编译：模型/DAO 可构建；人工：打标/收藏重启后保持 | 待实现 |
| F-013 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/media/MediaActions.kt:160<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/media/MediaActions.kt:251 | iOS/WotaGeiCam/Library/（回收站/删除） | Android 直写 `IS_TRASHED`(API30+)/本地 trash_item(API29) 且删除无系统授权框。iOS Photos 不能任意删改他方资源、无跨 App 回收站；改为 App 自有目录回收站 + 删除系统确认（`PHPhotoLibrary.performChanges` 删自有导入项需确认）。归位不覆盖与清理时序（media_tag/series_part/trash/缩略图）须保留。 | 编译：回收站/删除可构建；人工：进回收站→还原；永久删走系统确认 | 待实现（平台无等价，需裁决） |
| F-014 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/media/MediaActions.kt:86 | iOS/WotaGeiCam/Library/（重命名） | 重命名需读真实文件名并同步移动同名 sidecar/缩略图。iOS 对自有目录文件改名 + 同步 sidecar；对 Photos 资源改名受限（须导入导出），不隐式改写。 | 编译：重命名可构建；人工：改名后 sidecar 跟随、库内显示名一致 | 待实现 |
| F-015 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/player/PlayerEngine.kt:82<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/player/PlayerEngine.kt:314 | iOS/WotaGeiCam/Player/（播放引擎） | 媒体3/ExoPlayer → AVPlayer：attach/seek/逐帧步进/倍速分档/音量/音轨 override/AB 打点与循环/120ms 轮询。逐帧步进用 `AVPlayerItem.step(byCount:)` 或 `AVPlayer` seek 到帧边界。AB 段与同步偏移持久化沿用 uri 记键。 | 编译：播放封装可构建；人工：逐帧/AB 循环/倍速真机可用 | 待实现 |
| F-016 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/player/ClipExport.kt:1 | iOS/WotaGeiCam/Player/（剪辑导出） | 掐头去尾无损剪辑：入点对齐关键帧、出点按 PTS、双轨同减公共偏移、选轨只导选中一条、零样本轨剔除、sidecar 平移重映射、`edit_` 前缀。iOS 用 `AVAssetReader`+`AVAssetWriter`（passthrough）重封不重编码，负 PTS 丢弃不 clip 到 0。 | 编译：导出链可构建；人工：真机剪辑成片音画同步、sidecar 正确 | 待实现 |
| F-017 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/player/TrackExport.kt:1 | iOS/WotaGeiCam/Player/（选轨导出） | 全片导出视频 + 选中单音轨（环境/内录），`inau_` 前缀，内录轨按 −offset 平移 PTS。iOS 无内录轨来源（见 F-021），环境轨路径可移植；内录轨分支标记平台无等价。 | 编译：选轨导出可构建；人工：单音轨片导出正确（内录项不可达） | 待实现 |
| F-018 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/player/SceneExportPlan.kt:1<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/player/SceneExportRunner.kt:1 | iOS/WotaGeiCam/Player/（后渲染导出） | 播放器内后渲染导出：Glow + 画面降噪 + 残影离线重渲染（RGB 曲线刻意不做，录制期已烘进像素）。iOS 用 `AVAssetReader`→Metal 处理→`AVAssetWriter` 重编码；导出钮门控（无效果则禁）。GL/GLSL 不翻译，Metal 独立重写。 | 编译：管线可构建；人工：开/关效果导出对比、取消导出无残留 | 待实现 |
| F-019 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/core/AudioAlign.kt:1<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/player/TrackSync.kt:1<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/player/PostStopSync.kt:1 | Packages/WotaCore/Sources/WotaCore/（音频对齐 DSP） | 能量包络互相关 + FFT 精修 + 置信度（纯 DSP 零第三方）；偏移合成（内容差 + 首样本容器差）、AUTO/MANUAL 落盘策略、停止后逐段/首段近似校准。纯 DSP 与决策面进 WotaCore（Swift 重写）；内录轨相关分支因无内录来源标记平台无等价。 | 编译：DSP 与决策纯函数可构建；人工：双音轨片同步偏移合理、低置信不写盘 | 待实现 |
| F-020 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/player/CompareScreen.kt:140<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/player/CompareTimeline.kt:1 | iOS/WotaGeiCam/Compare/（对比播放） | 双播放器对比：域判定/主钟/决策指令（交接不 seek、250ms 硬 seek、末段回绕）、镜像、对比历史（12 条、rightId 去重）。纯逻辑（时间线）进 WotaCore，UI/播放器在平台层。 | 编译：时间线纯逻辑与 UI 可构建；人工：双片同步播放、硬 seek 生效、历史去重 | 待实现 |
| F-021 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/CodecRecorder.kt:319<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/CodecRecorder.kt:693 | iOS/WotaGeiCam/Recording/（内录音轨） | **平台无等价**：Android 用 `AudioPlaybackCapture` 采设备内播放声作第二音轨（前台服务 mediaProjection 授权，`CaptureFgService`）。iOS 只有 ReplayKit 全屏录制，**无法**把其它 App 音频混进 `AVCaptureSession`/`AVAssetWriter` 音轨。内录相关设置/选轨/同步后端一律不移植；如需设备内声，只能经 ReplayKit 全屏录制（产物形态不同），是否接受由用户裁决。 | 人工：确认真机上无内录入口；如引入 ReplayKit 需单独裁决其产物与权限文案 | 待实现（平台无等价，需裁决） |
| F-022 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/HudLayoutEditor.kt:189<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/HudLayout.kt:1 | iOS/WotaGeiCam/UI/（HUD 布局） | HUD 五容器 + 编辑页：位置表/顺序/可见掩码/网格吸附/描点拖放/分页。位置表编码（`encode/decode`，CURRENT_VERSION=2）与默认表进 WotaCore（纯逻辑）；拖放交互用 SwiftUI 重写（原生手势，不用 Material）。 | 编译：布局表纯逻辑可构建；人工：编辑拖放后重启布局保持 | 待实现 |
| F-023 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/diag/Diag.kt:1 | iOS/WotaGeiCam/（诊断） | 日志、旋转、崩溃件、导出件到 `Documents/WotaGeiCam/`，share-out 缓存，无网络。iOS 用 `os.Logger` + 自有目录日志轮转；导出经分享面板。 | 编译：日志层可构建；人工：导出诊断件可打开、无联网 | 待实现 |
| F-024 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/update/UpdateChecker.kt:1 | iOS/WotaGeiCam/Update/（更新检查） | **平台降级**：Android 读 GitHub latest release、镜像测速、SHA256 校验、FileProvider 触发 APK 安装（INTERNET+REQUEST_INSTALL_PACKAGES）。iOS 无 App 内 APK 式安装/覆盖安装；本轮 unsigned 侧载。iOS 只保留「查版本 + 跳转/提示」，下载 unsigned IPA 后由外部工具重签安装。 | 编译：查版本可构建；人工：版本对比正确、无自安装入口 | 待实现（平台无等价，需裁决） |
| F-025 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/bt/BtSpeakerController.kt:1 | iOS/WotaGeiCam/（音频路由） | **平台降级**：Android 读已配对/已连 A2DP 设备、电量、音量/传输控制。iOS 无第三方读 A2DP 电量/transport 公开 API；退化为系统音量 + 音频路由（`AVAudioSession` 路由、`MPVolumeView`），设备名/电量不保证可得。 | 编译：路由/音量层可构建；人工：真机连蓝牙后路由/音量可用（电量项按可得性） | 待实现（平台无等价，需裁决） |
| F-026 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/backup/PortableUserData.kt:1<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/backup/WotaBackupAgent.kt:1 | iOS/WotaGeiCam/Settings/（备份导入导出） | **平台降级**：Android 有 BackupAgent + 便携快照 `wota_user_backup.bin`（设置白名单 + 标签，4MiB/VERSION=1，additive 不覆盖）。iOS 无 BackupAgent 等价；改为显式导出/导入二进制快照 + Files 共享，仍 additive 不覆盖、排除 AI 键。 | 编译：快照编解码可构建；人工：导出→导入后设置/标签合并且不覆盖既有键 | 待实现（平台无等价，需裁决） |
| F-027 | Android/WotaGeiCamApp/app/src/main/AndroidManifest.xml:1 | iOS/Config/Info.plist | 权限：相机/麦克风/通知/蓝牙/媒体读取/所有文件访问等。iOS 侧按最小必要声明，每条 `UsageDescription` 必须具体说明 WOTA 艺录制用途（禁止占位）；不引入 Android 才需的 MANAGE_EXTERNAL_STORAGE/REQUEST_INSTALL_PACKAGES 语义。 | 编译：Info.plist 合法；人工：首启权限弹窗文案具体、非占位 | 待实现 |
| F-028 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/dialog/CurvePopup.kt:100 | iOS/WotaGeiCam/Rendering/（后期效果） | RGB 曲线/Glow/残影/降噪参数模型与持久（`curve_stack`/`glow_params`/`echo_params`/`tone_params`）。曲线 model/编解码可进 WotaCore；渲染在 Metal（C1/C2）独立重写。曲线是录制期 scene layer，导出不重复套用。 | 编译：效果参数编解码可构建；人工：改曲线后录制成片体现、导出不双套 | 待实现 |
| F-029 | Android/WotaGeiCamApp/app/build.gradle.kts:1 | iOS/Config/Full.xcconfig<br>iOS/Config/Lite.xcconfig | full/lite 双变体：Android 两 flavor 仅 AI 站位实现/依赖不同，**非 AI 功能完全一致**；同包名同 versionCode。iOS 两配置同 Bundle ID，差异只在 `WOTA_FULL`/`WOTA_LITE` 编译条件与元数据；因 AI 被排除，两变体无功能差异、无 AI 占位。 | 编译：两配置可构建（`apple_build.sh WotaGeiCam/WotaGeiCamLite`）；人工：Info.plist `WOTAAppVariant` 正确 | 已实现（A2-S 骨架） |
| F-030 | Android/WotaGeiCamApp/app/src/full/java/com/wotagei/cam/stance/StanceController.kt:129 | —（不建落点） | AI 站位：识别/播报/模型推理/GPU 三态/占位接口。**一律不实现、不移植、不保留占位**。 | 人工/静态：iOS 源码树无 `stance`/AI 相关符号与资源 | 排除（AI，不移植） |
| F-031 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/camera/GlRenderEngine.kt:1<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/camera/Shaders.kt:1 | iOS/WotaGeiCam/Rendering/Backend/（三态后端） | **兼容最低机型（2026-11-15 用户指示）**：图像后端按设备能力三态——**Metal（仅支持设备，A7+）/ OpenGL ES（独立重写）/ CPU 降级**。iPhone 5（A6，armv7）**不支持 Metal**，不得走 Metal 路径，须走 OpenGL ES 或 CPU 降级并保持核心参数调整可用。GL/GLSL 一律不翻译，三态后端均为 iOS 侧独立重写。 | 编译：三态后端可构建；人工：iPhone 5 走降级路径出画、支持机型走 Metal（见 MV-028/MV-030） | 待实现 |
| F-032 | Android/WotaGeiCamApp/app/build.gradle.kts:76 | iOS/Config/（armv7/arm64 切片） | **双工具链门**：iOS 需同时产出 armv7（iPhone 5）与 arm64（iPhone 6+）切片；旧 Xcode 产 armv7、现代 Xcode 产 arm64，为**两个独立工具链门**。Android 上游本就含 `arm64-v8a`/`armeabi-v7a` 双切片，iOS 同理需双门。是否合并为单一 universal 由主代理裁决。 | 编译：两门各自可出切片；人工：armv7 装 iPhone 5、arm64 装 iPhone 6（见 MV-031/MV-032） | 待实现 |

## AI 明确排除清单（逐文件）

以下 Android 文件/资源与键属于 AI 站位，iOS 侧**不建任何落点、不留占位接口**；
`verify_source_inventory.py` 断言 iOS 源码树中不存在 AI 符号/资源。

| 来源 | 类型 | 状态 |
|---|---|---|
| Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/stance/StanceUi.kt:1 | neutral UI 态 | 排除（AI，不移植） |
| Android/WotaGeiCamApp/app/src/full/java/com/wotagei/cam/stance/StanceAnalysis.kt:1 | 识别分析 | 排除（AI，不移植） |
| Android/WotaGeiCamApp/app/src/full/java/com/wotagei/cam/stance/StanceController.kt:1 | 控制器 | 排除（AI，不移植） |
| Android/WotaGeiCamApp/app/src/full/java/com/wotagei/cam/stance/StanceDetector.kt:1 | 检测器 | 排除（AI，不移植） |
| Android/WotaGeiCamApp/app/src/full/java/com/wotagei/cam/stance/StanceEngine.kt:1 | 引擎 | 排除（AI，不移植） |
| Android/WotaGeiCamApp/app/src/full/java/com/wotagei/cam/stance/StanceFeature.kt:1 | 特性开关 | 排除（AI，不移植） |
| Android/WotaGeiCamApp/app/src/full/java/com/wotagei/cam/stance/StanceHead.kt:1 | 头部模型 | 排除（AI，不移植） |
| Android/WotaGeiCamApp/app/src/full/java/com/wotagei/cam/stance/StanceJson.kt:1 | 解析 | 排除（AI，不移植） |
| Android/WotaGeiCamApp/app/src/full/java/com/wotagei/cam/stance/StanceModelPack.kt:1 | 模型包 | 排除（AI，不移植） |
| Android/WotaGeiCamApp/app/src/full/java/com/wotagei/cam/stance/StanceOverlay.kt:1 | 叠加层 | 排除（AI，不移植） |
| Android/WotaGeiCamApp/app/src/full/java/com/wotagei/cam/stance/StancePopup.kt:1 | 弹层 | 排除（AI，不移植） |
| Android/WotaGeiCamApp/app/src/full/java/com/wotagei/cam/stance/StanceSpeaker.kt:1 | 播报 | 排除（AI，不移植） |
| Android/WotaGeiCamApp/app/src/full/java/com/wotagei/cam/stance/StanceTypes.kt:1 | 类型 | 排除（AI，不移植） |
| Android/WotaGeiCamApp/app/src/lite/java/com/wotagei/cam/stance/StanceFeature.kt:1 | lite 推广桩 | 排除（AI，不移植） |
| Android/WotaGeiCamApp/app/src/full/assets/model_pack/（*.json/*.tflite） | 模型资源 | 排除（AI，不移植） |
| Android/WotaGeiCamApp/app/src/full/assets/voice/（*.wav） | 播报音频 | 排除（AI，不移植） |
| Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:129（stance_* 键） | 设置键 | 排除（AI，不移植） |
