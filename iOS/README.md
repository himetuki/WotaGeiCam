# WotaGeiCam iOS 原生移植（UIKit / iOS 10 基线，无签名侧载）

本目录是 Android → iOS 的独立原生移植工程。当前已交付**基础录制参数链、图像后处理回波、
片库/播放器/双片对比、A/B 剪辑、离线光弧修复、设置与备份、Full/Lite 双变体**的完整源码，
外加经典 Xcode 工程、双工具链无签名构建/归档/打包入口与 GitHub Actions 无签名 IPA 工作流。
**不含 AI 站位**（按约束全量排除，也不留占位接口）。

- Android 基线：`7394187`（只读参考，未修改）。
- Android 参考模块：`Android/WotaGeiCamApp/`；未修改 `Android/`、`HarmonyOS/`。
- 技术栈：`Swift 5.6 语言兼容 + UIKit + AVFoundation`；本地纯逻辑包 `WotaCore`；**无任何第三方依赖**。
- 用户口径（本任务硬约束）：最低要能支持 iPhone 5/6；功能可裁剪，但**基础 WOTA 艺录制参数调整
  与工具链必须保留**。因此暂停 SwiftUI / iOS 16 方向，采用 UIKit + iOS 10 兼容基线。

## 1. SwiftUI 文件替换清单（本任务实际改动）

| 原 SwiftUI 文件（已删除） | 新 UIKit 文件（已交付） |
|---|---|
| `WotaGeiCam/App/WotaGeiCamApp.swift`（`@main struct App`） | `WotaGeiCam/App/main.swift`（`UIApplicationMain`） |
| — | `WotaGeiCam/App/AppDelegate.swift`（`UIApplicationDelegate` + `UIWindow`，无 `UIScene`） |
| `WotaGeiCam/Camera/CameraViewModel.swift`（`@MainActor ObservableObject` + `@Published`） | `WotaGeiCam/Camera/CameraParameterController.swift`（串行队列 + 主线程回调） |
| `WotaGeiCam/Camera/CameraPreviewScreen.swift`（SwiftUI View，`scenePhase`/`onChange`） | `WotaGeiCam/Camera/CameraPreviewViewController.swift`（`UIViewController` + Auto Layout） |
| `WotaGeiCam/UI/CameraOverlay.swift`（`.ultraThinMaterial`、SF Symbols） | `WotaGeiCam/UI/CameraStatusOverlayView.swift` + `WotaGeiCam/UI/CameraParameterPanelView.swift` |

保留并沿用：`CameraSessionController.swift`、`CameraPermissionService.swift`、`CameraPreviewView.swift`
（新拆出）与 `App/AppVariant.swift`。骨架内**不存在** `import SwiftUI`、`import Combine`、
`@MainActor`、`Task`/`async-await`、`UTType`/`PHPicker`/`UniformTypeIdentifiers`、`UIViewRepresentable`、
`@StateObject`（由 `scripts/check_project.py` 强制检查）。

## 2. iOS 10 API 可行性（逐项确认，均有降级路径）

| 能力 | 使用的 API | 最低系统 | iOS 10 降级/门控 |
|---|---|---|---|
| 实时预览 | `AVCaptureVideoPreviewLayer` + `AVCaptureConnection.videoOrientation` | iOS 6 | 不使用 iOS 17 的 `videoRotationAngle`（legacy SDK 无此符号） |
| 设备发现 | `AVCaptureDevice.DiscoverySession` | iOS 10.0 | 其后回退 `AVCaptureDevice.default(for: .video)` |
| 会话启停/中断 | `startRunning`/`stopRunning` + `wasInterrupted`/`interruptionEnded`/`didStartRunning`/`runtimeError` 通知 | iOS 4/6 | 通知回调按 `wantsSessionRunning` 门控 |
| 权限 | `AVCaptureDevice.authorizationStatus(for:)` / `requestAccess` | iOS 7 | 被拒/受限有独立失败分类 + 跳系统设置 |
| 曝光补偿 | `exposureTargetBias` / `min|maxExposureTargetBias` / `setExposureTargetBias(_:completionHandler:)` | iOS 8 | 无该能力时禁用滑块并提示“该设备不支持曝光补偿” |
| 手电筒 | `hasTorch` / `isTorchAvailable` / `isTorchModeSupported` / `torchMode` | iOS 4/6 | 不支持时隐藏/禁用开关 |
| 布局安全区 | `view.safeAreaInsets` | iOS 11 | `#available(iOS 11.0, *)` 之外回退 `topLayoutGuide` / `bottomLayoutGuide` |
| 模糊/毛玻璃 | `UIVisualEffectView` + `UIBlurEffect(.dark)` | iOS 8 | 不使用 iOS 13+ 语义色与 SF Symbols |
| 打开设置 | `UIApplication.openSettingsURLString` + `open(_:options:completionHandler:)` | iOS 8/10 | — |

一律不对标 Camera2 语义：每个参数映射都先做设备支持判断，再决定可调或降级。

## 3. 工具链真实边界（不猜测可通过）

Apple 官方口径（已在 `Config/Shared.xcconfig` 注释中引用）：

- Xcode 14 起**只构建 64 位**：`32-bit app support has been discontinued in Xcode 14. Xcode 14 will only build 64-bit apps.`
- Xcode 14 release notes：构建 armv7/armv7s/i386 部署目标 **不再受支持**；部署到早于
  **iOS 11** 的系统也**不再受支持**。
- Apple 系统需求表：Xcode 14.3 → iOS 11–16.4；Xcode 15.x → iOS 12–17.x；Xcode 16/16.4 → iOS 15–18。

由此得出本工程的硬事实：

| 目标机型 | 架构 | 最高系统 | 现实可行工具链 |
|---|---|---|---|
| iPhone 5 / 5c（A6） | **armv7** | iOS 10.3.4 | **必须 Xcode ≤ 13**（推荐 13.4.1）：Xcode 14+ 不产出 armv7，也不支持 iOS 10 部署目标 |
| iPhone 6 / 6 Plus（A8） | **arm64** | iOS 12.5.x | Xcode ≤ 13 亦可；iOS 10 部署目标同样需要旧工具链 |
| 现代设备（本骨架 Modern 配置） | **arm64** | iOS 15+ | Xcode 14+（不做 iOS 10 部署目标） |

结论：**只要还要覆盖 iPhone 5（armv7）与 iOS 10 部署目标，就必须保留 Xcode 13.4.1 这类 legacy
工具链**；Modern 配置是同一套源码面向新 Xcode 的 arm64 通道。iOS 10 路径完全不依赖
concurrency/`@MainActor`/`UTType`/`PHPicker`/`UniformTypeIdentifiers`，因此同一套源码能被两种工具链编译。

## 4. Legacy / Modern 配置矩阵

| Scheme | 变体 | 构建配置 | 基础 xcconfig | 部署目标 | ARCHS | 需要的 Xcode |
|---|---|---|---|---|---|---|
| `WotaGeiCam` | full | `Debug` / `Release` | `Config/FullLegacy.xcconfig` | 10.0 | `armv7 arm64` | ≤ 13.4.1 |
| `WotaGeiCamLite` | lite | `Debug-Lite` / `Release-Lite` | `Config/LiteLegacy.xcconfig` | 10.0 | `armv7 arm64` | ≤ 13.4.1 |
| `WotaGeiCamModern` | full | `Debug-Modern` / `Release-Modern` | `Config/FullModern.xcconfig` | 15.0 | `arm64` | 14+ |
| `WotaGeiCamLiteModern` | lite | `Debug-Lite-Modern` / `Release-Lite-Modern` | `Config/LiteModern.xcconfig` | 15.0 | `arm64` | 14+ |

- xcconfig 分层：`Shared` + 变体（`Full`/`Lite`）+ 工具链（`ToolchainLegacy`/`ToolchainModern`）
  由 combo 文件（`FullLegacy` / `FullModern` / `LiteLegacy` / `LiteModern`）按顺序 `#include`。
- 部署目标与架构**只写在 xcconfig**，不写进 `project.pbxproj`（已由检查脚本强制）。
- full/lite 使用**相同 Bundle ID** `com.wotagei.cam`（对齐 Android 双 flavor），只影响编译条件
  `WOTA_FULL` / `WOTA_LITE` 与 Info.plist 的 `WOTAAppVariant`。当前无 AI 站位，故两变体无功能差异。

## 5. 经典工程与“新增源文件自动收录”

工程是**经典格式**：`objectVersion = 54`、`compatibilityVersion = "Xcode 12.0"`，
`PBXGroup` / `PBXFileReference` / `PBXBuildFile`。
**刻意不使用 Xcode 16 的 `PBXFileSystemSynchronizedRootGroup`**（objectVersion 77）——那种工程
Xcode 13.4.1 永远打不开。作为交换，源文件清单由脚本确定性生成：

```bash
# 生成/刷新工程与 shared schemes（新增 Swift/资源后执行一次）
python iOS/scripts/generate_project.py

# 只校验磁盘工程与源码目录是否一致（CI/验收用）
python iOS/scripts/generate_project.py --check

# 若本地 SPM 本地包引用不可用：把 WotaCore 源码直接编进 app target
python iOS/scripts/generate_project.py --core-mode inline
```

- 脚本递归扫描 `WotaGeiCam/`，**新增任意层级的 `.swift` 都被自动收进 app target**；集成代理新增
  `Library/`、`Settings/`、`Update/` 等文件后无需手改 `project.pbxproj`。
- `--core-mode inline` 递归收录 `Packages/WotaCore/Sources/WotaCore/**.swift`（含后续子目录，如
  C1 的渲染/Metal 扩展），并去掉本地包引用——这是 iOS 10 SPM 上限冲突时的**源码直接编译路径**，
  不使用任何假平台号。源码用 `#if canImport(WotaCore) import WotaCore #endif` 条件导入，两种模式同源可编。

## 6. 目录与模块边界

```
iOS/
  Config/                       # 9 个 xcconfig（Shared/变体/工具链/组合）+ Info.plist（权限文案）
  Packages/WotaCore/            # 纯逻辑 Swift Package（swift-tools-version 5.6、.iOS(.v10)、无第三方、无测试 target）
  WotaGeiCam/
    App/                        # main.swift(UIApplicationMain)、AppDelegate、AppVariant
    Camera/                     # 权限、会话控制器（BGRA/防抖/方向）、真实预览、取景页、参数控制器与能力探测
    UI/                         # 状态/错误反馈浮层、参数面板、片库页（原生 UIKit，无 Material）
    Rendering/                  # Metal / OpenGL ES / CPU 三后端 + shader（arm64 Metal，iOS10 全后端可用）
    Recording/                  # 录像协调器、AVAssetWriter、AAC 音频、段边界与空间门
    ArcRepair/                  # 离线光弧修复：状态机计划 + AVAssetReader/Writer 直拷音轨
    Library/                    # 媒体库：索引、暂存句柄、发布对账、维护清扫
    Player/ Compare/            # 播放器、A/B 持久化与剪辑导出、双片对比（共享时间轴）
    Settings/ Update/           # 设置页、用户备份、应用内更新检查（不装 IPA）
    Resources/                  # Assets.xcassets（AccentColor）+ PrivacyInfo.xcprivacy（隐私清单）
  WotaGeiCam.xcodeproj/         # 经典工程 + 4 个共享 scheme
  scripts/                      # generate_project / check_project / verify_source_inventory + Apple 构建/归档/打包入口
```

## 7. 命令入口

所有脚本只操作 `iOS/` 内的 `.build/`，不向 C 盘或其他目录写临时文件。

```bash
# 结构/配置/plist/工程入口/生成一致性检查（无 Apple 工具也能跑）
python iOS/scripts/check_project.py

# Apple 工具存在性（不构建）
bash iOS/scripts/detect_apple_tools.sh

# 无签名构建（真机 SDK，默认 generic/platform=iOS）
bash iOS/scripts/apple_build.sh WotaGeiCam Release            # Legacy：需 Xcode ≤ 13.4.1
bash iOS/scripts/apple_build.sh WotaGeiCamModern Release-Modern # Modern：需 Xcode 14+

# 无签名归档；产物在 iOS/.build/Archives/
bash iOS/scripts/apple_archive.sh WotaGeiCam Release

# 只接受真实设备 .app；拒绝模拟器产物，并校验 IPA Payload
bash iOS/scripts/package_unsigned_ipa.sh \
  --app iOS/.build/Archives/WotaGeiCam-Release.xcarchive/Products/Applications/WotaGeiCam.app
```

`apple_build.sh` / `apple_archive.sh` 找不到 `xcodebuild` 时返回 **69** 并打印可复现命令，
**不会伪装成功**。`package_unsigned_ipa.sh` 校验 `DTPlatformName == iphoneos`、`vtool` 平台、
`lipo` 切片与 IPA 内 `Payload/*.app`；本机无 Apple 工具时同样返回 **69**。本机不预置任何
`.app` / `.ipa` / `.xcarchive`（检查脚本会拒绝伪造产物）。

### CI 工作流（`.github/workflows/build-ipa.yml`，Modern 无签名 IPA）

仓库根的 Actions 工作流与安卓/HAP 并列：`v*` 标签推送或 Actions 页手动触发，先在 ubuntu 上跑结构
检查，再在 **macos-15 + Xcode 16.4** 上按 scheme/配置对执行 archive 与 IPA 打包，上传 unsigned
IPA、`.app`、`.xcarchive` 与 SHA-256 清单。不需要签名 Secrets。
Legacy（iPhone5/armv7）无托管 Xcode 13.4.1，需自建 macOS12 runner，本工作流不覆盖。

## 8. 本机实际验证状态（Windows Git Bash，无 Apple 工具）

已实际执行（真实退出码）：

- `generate_project.py --check`（package 与 inline 两种登记模式均一致，48 个源文件收录）；
- `check_project.py`（1502 项检查 PASS：工程/plist/xcconfig/scheme/隐私清单/源映射全绿）；
- `verify_source_inventory.py`（1643 项 Android→iOS 功能与参数映射核对 PASS）；
- `.github/workflows/build-ipa.yml` YAML 结构解析通过；
- `bash -n scripts/*.sh`、`detect_apple_tools.sh` 及各 Apple 脚本的参数校验分支。

未执行（阻塞于本机没有 Apple 工具链，**不是代码通过**）：

- `xcodebuild build` / `xcodebuild archive` / Swift 编译 / 无签名 IPA 打包：**未执行**。
- 本轮**不使用模拟器**，不交付截图、不编写或执行测试套件；当前没有模拟器或测试证据。
- 旧工具链（Xcode 13.4.1）与新工具链（Xcode 14+）的**实际编译均未在本机执行**；
  本机为 Windows，无 `xcodebuild`/`xcrun`/`swiftc`。Swift 级编译错误只能由 macOS CI 或
  用户机器的真实构建暴露。

因此当前版本**不能称为“可运行版本”**；“插件已启用”或文件存在都不等于运行证明。
在 macOS + Xcode 上执行上节命令（或触发 CI 工作流）即可补齐编译证据。

## 9. 后续任务（B1 及之后）可修改的文件与稳定接口

**B1 后续可直接修改/扩展**（本任务已锁定接口，请勿改名）：

- `WotaGeiCam/Camera/CameraSessionController.swift`：新增 output/录制相关方法（在 `sessionQueue` 内）。
- `WotaGeiCam/Camera/CameraPreviewViewController.swift`：接入录制按钮/状态。
- `WotaGeiCam/Camera/CameraParameterController.swift`：扩展参数（帧率/分辨率/白平衡等）。
- `WotaGeiCam/UI/CameraParameterPanelView.swift`、`CameraStatusOverlayView.swift`：新增控件。
- 新增目录（`Recording/`、`Library/` 等）放新文件：跑一次 `generate_project.py` 即被收录。

**已锁定的公共接口（保持签名与队列语义）**：

- `CameraSessionController.sessionQueue: DispatchQueue`：唯一的会话串行队列；**录制与渲染必须在
  同一条队列上操作 session 与设备**，不要在别处新建会话队列。
- `CameraSessionController.session: AVCaptureSession`、`private(set) var videoInput: AVCaptureDeviceInput?`：
  当前输入；读取设备参数用 `currentVideoDevice()`（在 `sessionQueue` 上调用）。
- 配置接入点：`configureSession(_ block: @escaping (AVCaptureSession) -> Void)`、
  `addOutput(_ output: AVCaptureOutput, completion: ((Bool) -> Void)? = nil)`（不支持时回 `false`，
  调用方负责降级）、`removeOutput(_ output: AVCaptureOutput)`。
- 生命周期：`start()` / `stop()` / `retry()`；状态回调 `onPhaseChange: ((CameraSessionPhase) -> Void)?`
  （固定主线程）。内部以 `wantsSessionRunning` 表示“最近意图”：`stop()` 后即使权限授予回调或
  `didStartRunning`/`interruptionEnded` 通知**迟到，也不会把会话重新置为 running**。
- `CameraPermissionProviding`：`authorizationStatus` / `requestAccess` 协议，便于替换替身。
- `CameraParameterController`：`refresh()`、`setTorch(enabled:)`、`setExposureBias(_:)`，
  `onSnapshot: ((CameraParameterSnapshot) -> Void)?`；内部复用 controller 的 `sessionQueue`。
- `WotaCore`：`CameraSessionPhase` / `CameraSessionEvent` / `CameraSessionStateMachine`、
  `RecordingFileNaming`（`VID_yyyyMMdd_HHmmss_%03d.mp4`，对齐 Android `VideoStore.displayName`）、
  `SeriesIDGenerator`、`StorageLayout`（`Movies/WotaGeiCam/Camera`，只算路径不建目录）、
  `RenderBackend` / `RenderBackendPolicy`（Metal **不是唯一后端**：无 Metal 时走 OpenGL ES → CPU）、
  `ExposureBiasRange`（`isAdjustable` / `clamp` / 归一化映射）。
- 设备分工：D1/F1 只新增 `Library/` / `Settings/` / `Update/` 及各自 Core 文件，不改动本骨架文件；
  渲染/Metal 扩展由 C1/集成代理新增，`--core-mode inline` 也会自动收录其子目录文件。

## 10. Info.plist 权限文案与隐私清单（摘要）

- `NSCameraUsageDescription`：说明 WOTA 艺录制需用后置相机取景与录像。
- `NSMicrophoneUsageDescription`：说明 WOTA 艺录制需用麦克风录制现场应援声作为音轨。
- 不写入系统照片图库（成片只进 app 沙盒片库），因此**不声明**
  `NSPhotoLibraryAddUsageDescription`（检查脚本强制其不存在）。
- `Resources/PrivacyInfo.xcprivacy`：`NSPrivacyTracking = false`，不收集任何数据；
  四类 Required Reason API 与理由编号（UserDefaults CA92.1 / FileTimestamp C617.1+3B52.1 /
  DiskSpace E174.1 / SystemBootTime 35F9.1）与代码实际用途一一对应，已登记进 Resources 阶段。

均为具体用途描述，无占位文案（检查脚本会拒绝 TODO/占位词）。

## 11. 人工验证清单（Apple工具链和真机就绪后执行，不写入静态验收）

1. 真机首次启动：权限弹窗文案正确；允许后出现实时预览而非黑屏。
2. 拒绝权限：显示“需要相机权限”，点“打开设置”跳转系统设置；开启后返回可重试成功。
3. 后台/前台：切后台再回来预览恢复；**返回前台前不得出现后台重新占用相机**（`wantsSessionRunning` 门控）。
4. 中断恢复：来电/控制中心后返回，从“预览被中断”恢复到实时预览。
5. 无相机环境：显示“未找到可用相机”，不是黑色占位页。
6. 竖屏取景：方向正确、无 90° 旋转或拉伸（当前锁定竖屏）。
7. 参数面板：曝光滑块在支持设备上真实改变画面亮度，不支持设备上禁用并给出说明；手电筒同理。
8. **iPhone 5（armv7 / iOS 10.3.4）**：用 Xcode 13.4.1 以 Legacy 配置构建并安装，确认预览可用。
9. **iPhone 6（arm64 / iOS 12.5.x）**：使用 Legacy 配置构建并安装；Modern 的最低系统为 iOS 15，不能安装到 iPhone 6。
10. full 与 lite 两个共享 scheme 构建成功，`WOTAAppVariant` 分别为 `full` / `lite`。
11. 录制任务接入前，确认 `Movies/WotaGeiCam/Camera` 命名与 Android 基线一致。
