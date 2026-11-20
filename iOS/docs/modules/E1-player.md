# E1 播放器与剪辑基础（Android `player/` → iOS `Player/` + `WotaCore`）

只读基线：`Android/`（提交 `4e897ce` 的迁出树）。本文件记录 **E1 播放/AB 循环/逐帧/缩放平移/
剪辑计划/对比接入契约** 的 Android → iOS 逐项映射、公共接口、平台差异与人工验证清单。

- **源码状态**：源码已落，**未经 Apple 工具链编译**（本机 Windows 无 Xcode / swiftc）。
- **写入范围**：仅 `iOS/WotaGeiCam/Player/`、`iOS/WotaGeiCam/Compare/`、
  `iOS/Packages/WotaCore/Sources/WotaCore/PlayerModels.swift`、`PlayerTimeline.swift`、本文件。
  未改 `Android/`、`HarmonyOS/`、工程文件、脚本、共享映射与任何隐私配置。
- **平台口径**：iOS 10 基线（iPhone 5 armv7 / iPhone 6 arm64），UIKit + AVFoundation；
  不用 SwiftUI / Combine / async-await / `Codable` / `@MainActor` / `UniformTypeIdentifiers`。
- **红线**：不用模拟器/截图、不写测试、不运行测试、不声明 Apple 编译通过；未导出的结果绝不入
  MediaLibrary；不扫描/删除外部媒体；AI 站位不实现、不移植、不留占位。

> 状态词：`源码已落（未编译）` ｜ `保留（平台差异）` ｜ `排除（AI，不移植）`。本文件不使用「完成/通过」字样。

## 1. 文件与职责

| iOS 文件 | 职责 | 对应 Android |
|---|---|---|
| `Player/PlayerEngine.swift` | `AVPlayer` 封装：状态机、进度轮询、seek、倍速、音量、AB、逐帧、资源回收 | `player/PlayerEngine.kt`（635 行） |
| `Player/PlayerSurfaceView.swift` | `AVPlayerLayer` 宿主；双指缩放/平移；视频方向（`preferredTransform`）；镜像 | `ui/WotaPlayerSurface`（Compose） |
| `Player/PlayerChrome.swift` | 顶栏 / dock / 浮动返回 / 拉出钮的显隐状态矩阵 | `player/PlayerChrome.kt` |
| `Player/PlayerTapArbiter.swift` | 单击（延迟显隐）/ 双击（启停）仲裁 | `player/TapArbiter.kt` |
| `Player/PlayerViewController.swift` | 播放页 UIKit 控制器：AB 按钮、逐帧、倍速、进度条 A/B 刻度、提示条 | `player/PlayerScreen.kt` |
| `Player/PlayerClipExporter.swift` | 区间规范化、preset 探测/降级、`AVAssetExportSession` 导出、staging → D1 入库 | `player/ClipExport.kt`、`TrackExport.kt` |
| `Compare/ComparePlayerCoordinator.swift` | 双播放器插入协议、统一时间线一拍编排、黑层/镜像/音频路由、对比历史 D1 桥 | `player/CompareTimeline.kt`、`CompareScreen.kt`（仅编排部分） |
| `WotaCore/PlayerModels.swift` | 纯值：状态、失败、AB 控制、倍速档、帧步进算术、缩放平移钳制、时间线几何/决策 | `PlayerEngine.kt`、`FrameStep.kt`、`CompareTimeline.kt` |
| `WotaCore/PlayerTimeline.swift` | 纯值：剪辑区间/命名、选轨平移、AB 持久化键、帧率来源、音频计划、槽位状态 | `ClipExport.kt`、`TrackExport.kt`、`CompareAudio.kt` |

`WotaCore` 不 import AVFoundation / UIKit，只依赖 CoreMedia 的 `CMTime` / `CMTimeRange`（iOS 4+）。

## 2. 功能映射（对齐 `docs/mappings/features.md`）

### 2.1 F-015 播放引擎

| ID | 项 | Android 源 | iOS 落点 |
|---|---|---|---|
| E1-001 | 装载 / 幂等重选 | `PlayerEngine.attach`（同 key 不重建） | `PlayerEngine.attach(url:)`：同 `mediaKey` 且未失败只补观察者；失败过才重建 |
| E1-002 | 软暂停 vs 释放 | `softPause` / `releasePlayer` | `softPause(_:)` 只归零 `rate`；`release()` 不可逆，之后全部空操作 |
| E1-003 | 状态机 | `playbackState/isPlaying/error` 三读数 | `PlayerPlaybackState`：idle/loading/ready/playing/paused/ended/failed(分类) |
| E1-004 | 进度轮询 | 120ms 轮询 | `addPeriodicTimeObserver(0.12s, .main)`；换片与释放时移除句柄 |
| E1-005 | seek | `seekTo` | `seek(to:commit:completion:)`；`commit` 走零容差 seek + 完成回调，失败显式回传 |
| E1-006 | 倍速 | `setSpeed` + `audioPitchFor=1.0` | `setSpeed` 归一到五档后写 `AVPlayer.rate`；AVPlayer 不变调（无需 pitch 参数） |
| E1-007 | 音量 | `setVolume` | `setVolume(0...1)` |
| E1-008 | 结尾行为 | `ended` 停末帧 / 单曲循环 | `.AVPlayerItemDidPlayToEndTime` → `.ended`；`setLoopMode(.one)` 且 AB 未激活时 `actionAtItemEnd = .none` |
| E1-009 | 失败分类 | 异常/错误串 | `mapItemFailure` → `avFoundationError`（domain/code）/ `unreadable` |
| E1-010 | 音频轨选择 | Media3 `TrackSelectionOverride` | **保留（平台差异）**：`AVPlayer` 无等价公开 API；E1 只用容器默认轨，见 §7 |

### 2.2 AB 循环

| ID | 项 | Android 源 | iOS 落点 |
|---|---|---|---|
| E1-020 | A/B 类型 | `Long` ms | `CMTime`（`PlayerABRange.start/end`，600 timescale） |
| E1-021 | 打 A | `markA`（`B <= A` 清 B 并关循环） | `PlayerABControl.markedA`；`PlayerEngine.markA()` |
| E1-022 | 打 B | `markB`（需 `B > A`，成功自动开循环） | `PlayerABControl.markedB`；`markB()` 返回 false = UI 提示 |
| E1-023 | 循环开关 | `toggleAbLoop`（仅合法范围可切） | `PlayerABControl.toggled`；`toggleAbLoop()` 返回 false = UI 提示 |
| E1-024 | 到 B 回 A | 轮询里 `shouldWrap` + seek | `PlayerABControl.shouldWrap` → `player.seek(to: A)`；`abWrapHook` 供对比页接管 |
| E1-025 | 切循环清观察者 | 移除抑制态观察者、清 pendingStep | 切换时不新增观察者；回到 A 时清 `pendingStepSeconds`；`release/换片` 统一移除全部观察者 |
| E1-026 | 不破坏原声 | rate 只改速度，pitch 恒 1 | 回绕只 seek，不改 `volume` / 不重建 `AVPlayerItem` |
| E1-027 | 持久化 | SharedPreferences 按 uri 记 A/B/loop | `PlayerABStore`（`UserDefaults`，键 `wota_player_ab:ab_a|ab_b|ab_loop:<url>`） |
| E1-028 | 恢复收整 | `restoreAb`：`B <= A` 清 B | `PlayerABStore.load` 同口径；不写媒体库索引 |

### 2.3 逐帧步进

| ID | 项 | Android 源 | iOS 落点 |
|---|---|---|---|
| E1-030 | 平台接口 | Media3 `seekToNext/Previous` | `AVPlayerItem.step(byCount: ±1)`（ObjC `stepByCount:`，iOS 6+，本工程 iOS 10 可用） |
| E1-031 | 仅暂停态 | 播放中拒绝 | `stepFrame` 守卫 `state != .playing && item.status == .readyToPlay`，否则 `.notPaused` |
| E1-032 | 帧率来源 | 读容器/轨道帧率 | `nominalFrameRate` → `minFrameDuration`（iOS 4+；**不是** iOS 16 的 `minimumFrameDuration`）→ `.unknown`；**不硬编码 fps** |
| E1-033 | 帧栅格算术 | `FrameStep.next`（1.5ms 容差；floor+1 / ceil−1） | `PlayerFrameStepMath.nextTarget` 同算术；`pendingTarget` 作 anchor 防连点互吞 |
| E1-034 | 显式失败 | 返回状态 + 原因 | `PlayerFrameStepOutcome(isExact:failure:)`；失败枚举 `notPaused/invalidTimebase/frameRateUnknown/atBoundary/unsupported` |
| E1-035 | 降级 | 旧设备近似 seek 不当精确 | 帧率已知才调 `step(byCount:)` 并标 `isExact = true`；帧率未知用 40ms 兜底做帧栅格零容差 seek，`isExact = false` + `.frameRateUnknown` |
| E1-036 | 边界 | 首帧/末帧停住 | 前进到末帧之后 / 后退到第 0 帧之前返回 `.atBoundary` |

### 2.4 双指缩放 / 平移 / 方向

| ID | 项 | Android 源 | iOS 落点 |
|---|---|---|---|
| E1-040 | 手势 | `detectTransformGestures` | `UIPinchGestureRecognizer` + `UIPanGestureRecognizer`（均为两指） |
| E1-041 | 缩放范围 | min 0.1 / max 4 | `PlayerZoomPanMath.clampScale`：0.1...4（与 Android 同档；`s < 1` 时平移域为 0） |
| E1-042 | 平移钳制 | `±viewport×(s−1)/2` | `PlayerZoomPanMath.clampPan`，逐轴；`s <= 1` 域为 0 |
| E1-043 | 叠加顺序 | rotation → scale → translation | `PlayerSurfaceView.applyTransform`：`rotated → scaledBy → translatedBy`（平移在最外层）；`rotationDegrees` 默认 0，仅供自绘表面手工旋转 |
| E1-044 | 方向来源 | 视频 `preferredTransform` / 设备方向 | `PlayerVideoOrientationMath.degrees(from:)` 读 `preferredTransform`，归一到 0/90/180/270，存 `trackRotationDegrees`；`AVPlayerLayer` 已应用该 transform，不二次旋转；**不读设备方向** |
| E1-045 | 非标准角度 | — | 非 90 度倍数按 0 处理，保留原画面不二次变形 |
| E1-046 | 镜像 | `mirror` 状态 | `PlayerSurfaceView.isMirrored`：`scaleX` 取负，与缩放/平移复合 |
| E1-047 | 视口变化 | — | `layoutSubviews` 里 `reclampPan()`，转屏/分屏后自动重夹 |

### 2.5 控件 chrome / 单击双击

| ID | 项 | Android 源 | iOS 落点 |
|---|---|---|---|
| E1-050 | 四层显隐 | `playerChromeOf(controlsVisible, topBarCollapsed)` | `PlayerChrome` / `playerChromeOf` 逐字段同值 |
| E1-051 | 单击/双击 | `TapArbiter`：单击延迟、第二击判启停 | `PlayerTapArbiter`（240ms 窗口）；VC 只挂一个单击识别器，第二击取消挂起并启停 |

### 2.6 F-016 剪辑导出

| ID | 项 | Android 源 | iOS 落点 |
|---|---|---|---|
| E1-060 | 区间规范化 | `ClipRule.normalize`（≥300ms） | `PlayerClipRule.normalize`（<300ms 返回 nil） |
| E1-061 | 成片命名 | `edit_<stem>.mp4` | `PlayerClipRule.clipName(from:fileExtension:)`；撞名由导出器追加序号 |
| E1-062 | 计划类型 | `(startMs,endMs)` + 选轨 | `PlayerClipExportPlan`（区间、轨、平移、preset、容器、降级说明） |
| E1-063 | 无损直通 | MediaExtractor/MediaMuxer 只改 PTS | `AVAssetExportSession` 优先 `AVAssetExportPresetPassthrough`；不可用则按阶梯重编码并记 notes |
| E1-064 | 入点关键帧 | 入点前向对齐 I 帧 | **保留（平台差异）**：由 `AVAssetExportSession` 自行决定容器切点，不手写关键帧索引 |
| E1-065 | 出点 | 按 PTS 截止 | `timeRange` 出点；组合分支按区间裁剪 |
| E1-066 | 双轨偏移 | 公共偏移 = 视频首样本 PTS；内录 `−offset` | `PlayerTrackRule.shiftSeconds`；组合里内录轨正平移 = 延后插入，负平移 = 裁前导 |
| E1-067 | 选轨 | 环境 = 组 0 / 内录 = 组 1 | 容器音轨数组：`[0]` 环境、`[1]` 内录；内录缺失显式回落环境并记 notes |
| E1-068 | preset 探测 | 编码器/容器能力 | `AVAssetExportSession.exportPresets(compatibleWith:)` + `supportedFileTypes`；mp4 优先、mov 次之 |
| E1-069 | 降级 | 编码参数回落 | 运行期失败自动降一档重试（直通 → 最高 → 1080p → 720p → 中 → 低），失败输出即删 |
| E1-070 | 不入未导出结果 | 发布时才进 MediaStore | 先写 `Clip/` pending staging；**仅 `status == .completed` 且文件非空**才 `MediaLibrary.commit`；取消/失败 `cancelPending` |
| E1-071 | 取消/完成回收 | 协程取消 + 资源释放 | `cancel()` → `cancelExport` + 停进度定时器 + 删 staging + 幂等回调；成功回调后 session/定时器置 nil |

### 2.7 F-020 对比编排与 D1 桥

| ID | 项 | Android 源 | iOS 落点 |
|---|---|---|---|
| E1-080 | 双播放器插入协议 | `Side` + 两台 `PlayerEngine` | `CompareSlotDriving`（prepare/play/pause/seek/volume/blackout/audio/nudge） |
| E1-081 | 引擎适配 | — | `PlayerEngineCompareSlot`：复用 `PlayerEngine` 的 AB/进度/逐帧 |
| E1-082 | 统一时间线 | `TimelineGeometry` / `decide` | `WotaCore.CompareTimelineGeometry` / `CompareTimeline.decide` 逐分支同口径 |
| E1-083 | 素材域 | `CompareDomain` | `WotaCore.CompareDomain`（leftSolo/rightSolo/dual/timelineEnd） |
| E1-084 | 硬 seek / 软追赶 | 250ms 硬 seek、邻域微调速率 | `hardResyncSeconds = 0.25`；`nudgeSpeed(forDriftSeconds:)` 为协议钩子（默认 no-op，E2 可落地） |
| E1-085 | 黑层 | `domBlackL/R` 会话位 | `leftBlackout/rightBlackout` + `onBlackoutChanged`；`seekTimeline` 同口径 park + 暂停 + 黑层 |
| E1-086 | 统一 seek | `seekTimeline` | `seekTimeline(to:)`：域内落位、域外 park、末态不亮黑层 |
| E1-087 | AB 回绕让位 | `abWrapHook` | `handleABWrap(targetSeconds:)`；调用方把 `PlayerEngine.abWrapHook` 接到这里 |
| E1-088 | 音频只播一侧 | `CompareAudioPlan` | `applyAudio(activeSide:track:dualAudio:)`：发声侧音量 1，另一侧 0 |
| E1-089 | 对比历史 D1 | `CompareHistory` + Room | `CompareHistoryBridge`：`MediaLibrary.compareHistory/recordCompare/removeCompare`；纯逻辑在 `MediaCompareHistory` |
| E1-090 | 历史上限/去重 | 12 条、右侧 key 去重 | `MediaCompareHistory.maxEntries=12`；`media:<id>` / `uri:<uri>` 命名空间隔离 |

## 3. 公共接口速查

### 3.1 `PlayerEngine`

```swift
// 装载 / 状态
func attach(url: URL)
func release()
var statusSnapshot: PlayerPlaybackState { get }
var durationSeconds: Double { get }
var abRangeSnapshot: PlayerABRange { get }

// 播放
func softPause(_ paused: Bool)
func setPlaying(_ playing: Bool)
func seek(to seconds: Double, commit: Bool = false, completion: ((Bool) -> Void)? = nil)
func setSpeed(_ value: Float)       // 归一到 0.1/0.3/0.5/0.75/1.0
func setVolume(_ value: Float)
func setLoopMode(_ mode: PlayerLoopMode)

// 逐帧
@discardableResult func stepFrame(forward: Bool) -> PlayerFrameStepOutcome

// AB
@discardableResult func markA() -> Bool
@discardableResult func markB() -> Bool
@discardableResult func toggleAbLoop() -> Bool
func clearAb()

// 回调（主线程）
var onStateChanged: ((PlayerPlaybackState) -> Void)?
var onProgress: ((Double, Double) -> Void)?
var onSeekCompleted: ((Bool) -> Void)?
var onFrameStep: ((PlayerFrameStepOutcome) -> Void)?
var onABChanged: ((PlayerABRange) -> Void)?
var abWrapHook: ((CMTime, CMTime) -> Bool)?
```

### 3.2 `PlayerClipExporter`

```swift
static func makePlan(sourceURL:asset:range:audioTrack:offsetSeconds:)
    -> Result<PlayerClipExportPlan, PlayerClipExportFailure>
func export(sourceURL:sourceDisplayName:range:audioTrack:offsetSeconds:
            completion: @escaping (PlayerClipExportOutcome) -> Void)
func cancel()
var onProgress: ((Float) -> Void)?
```

`PlayerClipExportOutcome`：`.completed(已入库绝对路径, plan)` / `.cancelled` / `.failed(分类+说明)`。

### 3.3 分屏 / 对比接入契约（E2 用）

```swift
protocol CompareSlotDriving: AnyObject {
    var isReady: Bool { get }
    var isPlaying: Bool { get }
    var positionSeconds: Double { get }
    var durationSeconds: Double { get }
    var abLoopActive: Bool { get }
    func prepare(url: URL)
    func play(); func pause()
    func seek(to seconds: Double)
    func setVolume(_ volume: Float)
    func setBlackout(_ blackout: Bool)          // 默认 no-op
    func selectAudioTrack(_ kind: PlayerAudioTrackKind) // 默认 no-op
    func nudgeSpeed(forDriftSeconds drift: Double)      // 默认 no-op
}

final class ComparePlayerCoordinator {
    init(left:right:leftDuration:rightDuration:offset:)
    func prepare(leftURL:rightURL:)
    func updateDurations(leftDuration:rightDuration:)
    func setOffsetSeconds(_ seconds: Double)
    func setMirror(side: CompareSide, mirrored: Bool)
    func play(); func pause()
    func seekTimeline(to seconds: Double)
    func handleABWrap(targetSeconds: Double)
    func applyAudio(activeSide:track:dualAudio:)
    func tick()
    func startTicking(interval: TimeInterval = 0.2)
    func stopTicking(); func teardown()
    var onSlotsChanged: ((ComparePlaybackSlots, CompareDomain) -> Void)?
    var onBlackoutChanged: ((Bool, Bool) -> Void)?
    var onMirrorChanged: ((Bool, Bool) -> Void)?
}

final class CompareHistoryBridge {
    func load(completion: @escaping ([MediaCompareEntry]) -> Void)
    func record(leftId:rightId:rightUri:completion:)
    func remove(key: String, completion:)
}
```

**分屏布局不在本契约内**：不提供 frame / 分割比例 / 取向 / 左右栏宽度，`CompareOverlayState`
也刻意不含这些字段。E2 自行决定是否实现分屏、如何排布。

## 4. 关键行为口径

### 4.1 状态与线程

- 所有状态变化与 UI 回调一律 **主线程**；`AVAsset.loadValuesAsynchronously` 在系统队列执行，
  完成后 `DispatchQueue.main.async` 回到主线程。
- `state` 的 `didSet` 只在值变化时回调，避免轮询重复刷状态。
- `release()` 不可逆：置 `isReleased`，移除时间观察者/KVO/通知，`replaceCurrentItem(nil)`，
  清空 AB/位置/时长；此后全部方法守卫直接返回。

### 4.2 AB 循环

- `markA`：位置取 `livePositionSeconds()`（`player.currentTime()`，非轮询缓存），保证打点贴近用户所见。
- `markB`：必须已有合法 A 且 `B > A`，成功自动 `loopEnabled = true`。
- 回绕在**唯一进度观察者**里判定；每次到 B 只发一次 seek（seek 后位置立即回 A，不会连续触发）。
- 切换循环/清除 AB 不重建 player、不改音量；`abWrapHook` 返回 true 时引擎不再自行 seek。
- 持久化键 = URL `absoluteString`；换片装载时 `PlayerABStore.load` 自动恢复，`B <= A` 收整。

### 4.3 逐帧

- 只在暂停且 `readyToPlay` 时工作；帧率已知时调 `step(byCount:)`，逐帧由播放器按解码
  时间戳推进，落点由 0.12s 周期观察者回读。
- 帧率未知：退回按 40ms 兜底步长做帧栅格零容差 seek，并返回 `isExact = false,
  failure = .frameRateUnknown`；UI 必须显示降级提示，**不得把近似 seek 说成精确逐帧**。
- 帧长来源：`nominalFrameRate > 0` → `1/rate`；否则 `minFrameDuration`（iOS 4+）；否则 40ms 兜底。
- 连点：`pendingStepSeconds` 作为下一次计算的 anchor，seek 未落地期间不会互相吞掉。

### 4.4 缩放/平移

- 手势只认两指；单指留给单击/双击与进度条。
- 视图只上报增量（`scaleDelta`、`translation`），基准值由控制器持有；控制器调
  `PlayerZoomPanMath` 钳制后写回视图，视图不保存手势基准。
- 变换顺序固定 `rotate → scale → translate`；`reclampPan()` 在布局变化与手势结束时调用。
- **旋转口径（平台差异）**：角度仍从视频轨 `preferredTransform` 归一得出
  （`trackRotationDegrees`，不读设备方向）；但 `AVPlayerLayer` 已按该 transform 呈现，
  `rotationDegrees` 默认保持 0，避免双重旋转。它只作为自绘表面的手工旋转入口。

### 4.5 剪辑导出

1. `makePlan` 同步读 `asset.duration` / 视频轨 / 音轨数，规范化区间，探测 preset 与容器。
2. `preparePending` 在 `Clip/` 下生成 staging（`.pending-<pid>-edit_<stem>.<ext>`），撞名追加序号。
3. 仅环境轨且无平移时**直接导出源资产** + `timeRange`；否则构建 `AVMutableComposition`
   （视频轨首条 + 选中音轨按平移插入），组合已裁剪，不再设 `timeRange`。
4. 运行期失败自动降一档 preset 重试；每次重试先删半成品；阶梯耗尽才失败。
5. 成功后校验文件存在且 size > 0，再 `MediaLibrary.commit`；取消/失败 `cancelPending`。
6. 完成/取消/失败都回收 session、进度定时器、回调，`finish` 幂等。

### 4.6 对比编排

- 每拍读两槽实时位置 → `CompareTimeline.decide` → 固定顺序应用：
  停/走 → seek → 软追赶 → 黑层回灌。
- `seekTimeline` 与 Android 同口径：域内精确落位，域外 park（早头 0 / 越尾素材尽头）并暂停，
  非末态亮黑层。
- 音频只播一侧：`CompareAudioPlanner.plan` 决定是否选内录，另一侧 `setVolume(0)`；
  不做左右混音（避免梳状滤波）。
- 时间线末：`timelineLoop` 且用户仍在播放、非拖动、AB 未激活时回 `Tmin`；否则停末帧。

## 5. 平台差异与取舍

| ID | 项 | Android | iOS（本工程） | 取舍 |
|---|---|---|---|---|
| PD-E1-01 | 音频轨 override | Media3 `TrackSelectionOverride`（环境组 0 / 内录组 1） | `AVPlayer` 无公开等价 API；`AVMutableAudioMix` 需组合 | E1 用容器默认音轨；对比页音频「只播一侧」用音量，不声称选了内录 |
| PD-E1-02 | 无损剪辑 | `MediaExtractor` + `MediaMuxer` 原样写样本、只改 PTS | `AVAssetExportSession`：直通优先，失败降级重编码 | 直通可用时无损；不可用时**显式 notes**，不静默重编码 |
| PD-E1-03 | 入点精度 | 前向对齐视频关键帧、出点按 PTS | 由 `AVAssetExportSession` 决定切点 | 不手写关键帧索引；精度以导出器实际行为为准 |
| PD-E1-04 | 帧率读取 | `MediaFormat.KEY_FRAME_RATE` | `nominalFrameRate` / `minFrameDuration`（iOS 4+） | 读不到显式降级 40ms + `isExact=false` + `.frameRateUnknown`，不硬编码 25fps |
| PD-E1-05 | 缩放下限 | Compose 版 min 0.1 | `PlayerZoomPanMath.minScale = 0.1` | 与 Android 同档；`s < 1` 时钳制域自动归零（`clampAxis` 对非正半宽返回 0） |
| PD-E1-06 | 分屏布局 | App 内左右栏 `SPLIT_SQUEEZE=0.5` | **不裁决**：iPhone 无系统 Split View，P-017 属产品决策 | 只给 `CompareSlotDriving` + `ComparePlayerCoordinator`，E2 自行排布 |
| PD-E1-07 | 对比软追赶 | `nudgeSpeedForSync` 微调速率 | 协议钩子 `nudgeSpeed(forDriftSeconds:)` 默认 no-op | 硬 seek 已实现；软追赶留给 E2 决定（避免改动 `PlayerEngine` 档位语义） |
| PD-E1-08 | AB 持久化 | SharedPreferences | `UserDefaults` | 同键口径（按 URL）；不写媒体库索引 |
| PD-E1-09 | 进度轮询 | 120ms | `addPeriodicTimeObserver` 0.12s | 同节拍 |
| PD-E1-10 | 视频旋转 | `graphicsLayer(rotationZ)` 手动施加 | `AVPlayerLayer` 已按 `preferredTransform` 呈现 | 角度仍从 `preferredTransform` 归一（`trackRotationDegrees`），但不再写图层 transform，避免双重旋转；`rotationDegrees` 仅留给自绘表面 |

## 6. 不做 / 排除

- **AI 站位**：不实现、不移植、不保留占位接口。
- **分屏左右布局**：不实现、不裁决；只给插入协议与状态（见 §3.3）。
- **未导出结果入媒体库**：导出器只在成功且文件非空后 commit；失败/取消删 staging。
- **外部媒体扫描/删除**：本模块不遍历、不删任何外部文件。
- **测试/模拟器/截图**：按用户要求不写、不运行、不产出。
- **工程文件**：未运行 `generate_project.py`，`project.pbxproj` 仍待集成方刷新（见 §8）。

## 7. 人工验证清单（真机，待 Xcode 集成后执行）

> 以下为**待执行**清单，不代表已通过；本机无 Apple 工具链，无法运行。

1. **装载/状态**：选库内片段 → loading → ready → 播放；坏文件 → failed 且提示 reason。
2. **AB 循环**：播到 5s 打 A、10s 打 B → 自动循环；到 10s 回 5s；关循环后不再回绕；
   打 A 晚于 B → B 清空且循环关闭；退出重进同片 → A/B 自动恢复。
3. **逐帧**：暂停后连点前进 10 次 → 每次一帧且不漂移；后退回起点 → `.atBoundary`；
   播放中点击 → `.notPaused` 提示；帧率未知素材 → 「降级」提示且 `isExact=false`。
4. **缩放/平移**：双指放大到 4x 上限；平移被钳在边界；捏回 1x 平移归零；
   竖拍视频方向正确（`AVPlayerLayer` 按 `preferredTransform` 呈现，不叠加第二层旋转）；
   镜像开关生效；转屏后画面不越界。
5. **倍速**：五档切换，声音不变调；暂停/恢复后倍速保持。
6. **单击/双击**：单击 240ms 后显隐控件；快速双击不闪控件且启停；顶栏收起后浮动返回在、
   「拉出」钮在；再点拉出恢复顶栏。
7. **剪辑导出**：A/B 区间 ≥300ms → 导出成功、库内出现 `edit_<stem>`；
   区间 <300ms → 明确失败；导出中取消 → staging 清理、库内无残留；
   双音轨片段选内录 → 导出音轨为内录（若直通不可用，plan.notes 有降级说明）。
8. **对比接入**（E2 联调）：两段不同时长 + 偏移 → 统一进度条覆盖域正确；
   右轨 dormant 时黑层出现且暂停；拖到时间线末停末帧；AB 回绕两轨同时回 A；
   音频只播选中一侧；历史记录 12 条、同一右侧只留一条。

## 8. 静态检查与集成状态

- `python iOS/scripts/check_project.py`：**未发现禁用 token**（SwiftUI/Combine/@MainActor/
  async-await/UTType/PHPicker 等均无）；`canImport(WotaCore)` 守卫齐备。
- 当前 **FAILED 仅为源码未登记**：新增 `Player/*`、`Compare/*`、`WotaCore/Player*.swift|Timeline*.swift`
  尚未写入 `WotaGeiCam.xcodeproj/project.pbxproj`。按仓约定由集成方运行
  `python iOS/scripts/generate_project.py` 刷新工程；本任务写入范围不含工程文件，故未自行生成。
- 同批未登记的还有 A2-S/D1/C1/F1 的既有源码与 `CameraCapabilityProbe.swift` 等，属同一待集成批次。
- **未编译**：Windows 环境无 Xcode/swiftc，不声明 Apple 编译通过。
