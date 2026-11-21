# B1 相机会话 / 参数能力 / 慢门（AVFoundation → `Camera/` + `WotaCore`）

本文件记录 B1 相机链的 Android → iOS 映射、**B2 可用的稳定接口**、本轮审查修复（P0-02 / P1-03 /
P1-09 / P2-01）与前后置切换事务，以及降级口径和人工验证清单。

- **源码状态**：源码已落，**未经 Apple 工具链编译**（本机 Windows 无 Xcode / Swift）。
- **范围**：
  - `iOS/WotaGeiCam/Camera/CameraSessionController.swift`
  - `iOS/WotaGeiCam/Camera/CameraParameterController.swift`
  - `iOS/WotaGeiCam/Camera/CameraCapabilityProbe.swift`
  - `iOS/Packages/WotaCore/Sources/WotaCore/CameraAbilityModel.swift`
  - `iOS/Packages/WotaCore/Sources/WotaCore/CameraFormatNegotiation.swift`
  - `iOS/Packages/WotaCore/Sources/WotaCore/RecordingConfiguration.swift`
  - `iOS/Packages/WotaCore/Sources/WotaCore/ShutterGateModel.swift`
  - 本文件
- **未改**：`CameraPreviewView*` / `UI/` / `Recording/RecordingConfigurationBridge.swift` / 工程与脚本 /
  `Android/` / `HarmonyOS/`。
- **平台口径**：iOS 10 基线、UIKit + AVFoundation、Swift 5.6 语言兼容、不引第三方；
  不使用 SwiftUI / Combine / async-await / `@MainActor`；不引入 AI 站位接口。

> 状态词：`源码已落（未编译）` ｜ `保留（平台差异）` ｜ `人工验证` ｜ `不在本任务范围`。

## 1. 线程与队列契约（B2 必须遵守）

`AVCaptureSession` 的所有配置、设备参数读写、格式协商与前后置切换都在
`CameraSessionController.sessionQueue` 这一条串行队列上执行（`CameraSessionController.swift:18`）。
`CameraParameterController.queue` 就是同一条队列（`CameraParameterController.swift:131`）。

| 接口 | 队列要求 | 说明 |
|---|---|---|
| `currentVideoDevice()` | sessionQueue | 当前 `AVCaptureDeviceInput.device` |
| `currentAbilitySnapshot()` | sessionQueue | 设备能力快照（format 表按 `uniqueID` 缓存） |
| `currentParameterState()` | sessionQueue | 当前参数落点；防抖读连接 |
| `videoConnections()` | sessionQueue | output 连接在前（录制优先），预览连接在后 |
| `setPreviewVideoConnection(_:)` | 主线程调用 | 内部转 sessionQueue 存储预览层连接 |
| `applyVideoConfiguration(_:completion:)` | 任意线程 | 内部转 sessionQueue，使用调用方给出的完整请求 |
| `updateVideoConfiguration(resolution:frameRate:completion:)` | 任意线程 | 内部转 sessionQueue，**在同一块内读最新状态**再补齐 nil 的一半 |
| `applyShutterLock(target:completion:)` | 任意线程 | 内部转 sessionQueue；帧周期与 sensor 曝光分开记账 |
| `switchCamera(to:completion:)` | 任意线程 | 内部转 sessionQueue；失败恢复旧输入 |
| `onDeviceChanged` | 主线程回调 | 输入替换成功后通知参数面板刷新（由 `CameraParameterController` 占用） |
| `onVideoInputReplaced` | 主线程回调 | 输入替换成功后的 B2/预览钩子（与 `onDeviceChanged` 并行，互不覆盖） |

**B2 加 output / 音频输入输出**：通过 `configureSession` / `addOutput` / `removeOutput` 在
sessionQueue 上加入；输出方向、镜像、录制连接参数由 B2 在 `onVideoInputReplaced` 后自行设置。
B1 **不硬编码**任何 `videoOrientation` / `isVideoMirrored`，也不假定录制输出一定存在。

## 2. 本轮修复与证据

### P0-02 `targetResolution` 未声明（编译阻断）

`CameraFormatNegotiation.selectFormat` 第 3 档提示语改用请求中的分辨率：

```swift
// CameraFormatNegotiation.swift:245-249
let reason = String(
    format: "设备 format 表没有同时支持 %@ 与 %.0f fps 的档位。",
    request.resolution.displayName,
    request.frameRate
)
```

`nearest` 仍用于 `selectedResolution`，`unsupportedReason` 语义未变（请求组合不存在，而非“最近可用”）。

### G1 手动曝光退出 / 手动白平衡增益入口

- `setAutoExposure(completion:)`（`CameraParameterController.swift:366`）：在 sessionQueue 内检查
  `isExposureModeSupported(.continuousAutoExposure)` 后恢复 `.continuousAutoExposure`；
  设备不支持/无输入时走 `.unsupported`，锁定失败走 `.failed`，不伪造成功。
- `setManualWhiteBalanceGains(red:green:blue:completion:)`（`CameraParameterController.swift:521`）：
  要求 locked 白平衡 + 增益域，每通道钳到 `1...maxWhiteBalanceGain`，下发后读回实际增益。
  该接口与 `setWhiteBalance(mode:kelvin:tint:)` 共用同一套设备限制。

### P1-03 防抖归属：format 判能力、connection 读写状态

| 语义 | 正确归属 | 落点 |
|---|---|---|
| 当前 format 是否支持某防抖模式 | `AVCaptureDevice.Format.isVideoStabilizationModeSupported(_:)` | `CameraParameterController.swift:641`、`CameraCapabilityProbe.swift:231` |
| 连接是否支持防抖 | `AVCaptureConnection.isVideoStabilizationSupported` | `CameraParameterController.swift:648` |
| 当前防抖模式（读/写） | `AVCaptureConnection.activeVideoStabilizationMode` | `CameraParameterController.swift:653-655`、`CameraCapabilityProbe.swift:160` |

- `setStabilization(mode:)`（`CameraParameterController.swift:631`）对 `videoConnections()` 中的
  **所有**视频连接统一下发；`mode == .off` 不要求 format 支持，非 off 时同时校验 format 与连接能力。
- 连接级属性不需要 `lockForConfiguration`；下发后逐条读回校验，读回不一致返回 `.failed`，不伪造 `.applied`。
- `parameterState(for:ability:videoConnection:)`（`CameraCapabilityProbe.swift:135`）没有连接时
  显示 `.off` 并加 note；连接状态与当前 format 能力不一致时加“可能已回退”note。

**预览连接覆盖**：`videoConnections()` 只自动包含 `session.outputs` 的连接。预览连接不属于 output，
需要预览宿主调用 `setPreviewVideoConnection(previewView.previewLayer.connection)` 注册
（`CameraSessionController.swift:155`）。未注册时防抖只覆盖录制/数据输出连接，参数面板的
状态 note 会如实说明；不得声称预览已覆盖。

### P1-09 曝光时间域统一为纳秒

能力快照构造一次转换，消费方全部按纳秒使用：

```swift
// CameraCapabilityProbe.swift:47-52
let exposureDurationRange = CameraNumericRange(
    minimum: Double(CameraCapabilityProbe.nanoseconds(device.activeFormat.minExposureDuration)),
    maximum: Double(CameraCapabilityProbe.nanoseconds(device.activeFormat.maxExposureDuration))
)
```

- `CameraAbilitySnapshot.exposureDurationRange` 与 `CameraParameterState.exposureDurationRange`
  均补了“单位：纳秒”注释（`CameraAbilityModel.swift:245`、`:391`）；
- `CameraParameterState.exposureDurationSeconds` 仍是秒域，两者不混用；
- `CameraParameterController` 的手动曝光与 `CameraShutterGate.evaluate` 无需再改换算点；
  手动曝光回执会写明“设备/帧周期夹到 …”，不再只报请求值；
- 慢门下发额外按落档后的活动 format 重新探测曝光域，并在写 `setExposureModeCustom` 前夹取：
  `CameraSessionController.swift:943-954`。1/24 → `41_666_666 ns`、1/25 → `40_000_000 ns`
  不再被秒域误夹成 `0/1 ns`。

**降级口径（1/24、1/25）**：

1. 设备 format 承载 24/25 fps 且曝光域覆盖目标 → 帧周期 + sensor 曝光都下发，状态 `.applied`；
2. 设备不支持手动 sensor 曝光时间 → 只锁帧周期，状态 `.frameLockedExposureUnavailable`；
3. 设备曝光域小于目标（例如上界 20 ms）→ 夹到设备上界并给出“被夹到 … ns”note，状态
   `.exposureClamped`（`ShutterGateModel.swift:372-399`）；
4. format 不承载 24/25 fps → `.frameRateUnsupported`，不静默改分辨率/帧率。

### P2-01 分辨率/帧率：同一 sessionQueue 块内读最新状态

`setResolution` / `setFrameRate` 不再“先读 state → 出队 → 再异步 apply”，而是调用
`updateVideoConfiguration(resolution:frameRate:)`（`CameraSessionController.swift:252`）：

```text
sessionQueue 块内：currentParameterState() → 补全 nil 的一半 → applyVideoConfigurationOnQueue
```

因此“读旧镜头 state → switchCamera 插入 → 用旧分辨率协商新镜头”的交错被消除；
`setResolutionAndFrameRate` 仍走完整请求（两个值都由用户显式给出），不额外读状态。

### 前后置切换：同一配置事务先移除旧输入

`switchCameraOnQueue`（`CameraSessionController.swift:496`）的顺序固定为：

1. `beginConfiguration`；
2. 移除旧输入；
3. `canAddInput(newInput)` → `addInput`；
4. 失败则在同一事务内 `canAddInput(oldInput)` → `addInput` 恢复；
5. 旧输入也恢复不了（或本就没有旧输入）→ `videoInput = nil`、`isConfigured = false`，
   状态机转入 `.failed(.configurationFailed)`，回执 `requiresSessionRebuild = true`
   （`CameraSwitchOutcome` 新增字段，默认 false，源兼容）；`retry()` 据此重建输入；
6. `commitConfiguration`。

成功路径只替换 input，并 `invalidateCache` + 主线程 `onDeviceChanged`；**不设置任何连接方向/镜像/防抖**。
旧实现“先 canAdd 新输入、再加”的做法已移除，因为旧输入占用时 `canAddInput` 可能直接为假。

## 3. 能力与档位口径（与 Android 对齐）

- 分辨率档 / 帧率档 / 快门分母 / 码率档均为**产品档位**，不是机型常量；是否可选由运行时 format 表决定。
- `CameraFormatDescriptor.isUsable` 过滤抽象 format（0×0 或无帧率区间）只用于档位展示与协商；
  平台层构造的 `formats` **不过滤任何一项**，保证下标与 `AVCaptureDevice.formats` 一一对应。
- `CameraShutterGate` 的两条状态严格分开：`frameDurationSettable`（帧率被 format 承载）与
  `sensorExposureSettable`（曝光时间域可用）；“快门可用”不等于“sensor 曝光时间可设”。
- `RecordingConfiguration` 只描述“应该怎么录”，不打开 `AVAssetWriter`；B2 负责编码器/音轨/起停。

## 4. B2 / G 集成待办（明确未做）

1. **预览连接注册**：Preview VC 在会话启动/布局后调用
   `sessionController.setPreviewVideoConnection(previewView.previewLayer.connection)`，
   否则防抖只覆盖 output 连接。
2. **录制方向恢复**：B2 在 `onVideoInputReplaced` 后用录制输出连接的 `videoOrientation` /
   `isVideoMirrored` / 防抖设置恢复自身 hook；B1 不做。
3. **UI 入口**：当前 `CameraParameterPanelView` 只接了手电筒、EV 偏置、刷新；分辨率/帧率/
   ISO/快门/对焦/白平衡/防抖/变焦均未接 UI。控制器现提供
   `setAutoExposure(completion:)`（恢复 `.continuousAutoExposure`，G1 手动曝光“自动”出口）、
   `setWhiteBalance(mode:kelvin:tint:)` 与 `setManualWhiteBalanceGains(red:green:blue:)`
   （每通道钳到 `1...maxWhiteBalanceGain`）；UI 控件仍待 G1 接入。
4. **输出/音频**：B2 在 sessionQueue 内加入 `AVCaptureVideoDataOutput` 与可选音频输入/输出；
   B1 不预设输出数量与顺序（`videoConnections()` 以当前 `session.outputs` 实际顺序为准）。

## 5. 人工验证清单（不写入自动验收）

1. 真机首启预览非黑屏；前后置连续切换 20 次，画面不断流、不出现“无输入”空会话。
2. 选 1/24、1/25：画面明显变亮/拖影变长；面板读数与“设备曝光域”提示为纳秒或换算后的秒。
3. 切到不支持手动曝光的 format/设备：状态为“帧周期已锁 / 曝光时间不可手动设置”，不是“已生效”。
4. 设备曝光域上界 20 ms 时选 1/24：曝光夹到 20_000_000 ns，note 明确，不出现 0/1 ns。
5. 防抖：支持机型切标准/影院级，预览与成片一致；换到不支持该模式的 format 后：
   要么明确提示“当前格式不支持”，要么如实显示回退状态，不保留旧的“已开启”。
6. 后摄选 4K/30 后立刻切前摄：不出现“4K 静默回退到 720p”；按当前镜头重新选择或明确提示。
7. 预览连接未注册时读防抖状态：显示关闭并带 note，不崩溃、不伪造开启。

## 6. 验证状态

- 本机仅做**源码结构核验**与静态检索；未运行 `swift build`、Xcode 编译、单测、模拟器、真机。
- 临时文件：无。
