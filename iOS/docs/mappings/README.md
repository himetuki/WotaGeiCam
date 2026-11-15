# iOS 移植映射总览（A1）

本目录是 Android → iOS 的**可追溯映射索引**。目标：把 Android 非 AI 功能、设置、存储、页面
逐条映射到 iOS 落点，每行带 **Android 文件 + 行号**、**iOS 目标**、**转换说明**、**验证项**、
**当前状态**，并明确 **AI 站位排除**。

- 只读基线：`Android/`，提交 `7394187`。**未修改** `Android/`、`HarmonyOS/` 或 `iOS/` 之外任何文件。
- 技术栈：**UIKit + AVFoundation**（基础路径），本地纯逻辑包 `WotaCore`；无第三方依赖；**iOS 10 最低目标**；图像后端 Metal / OpenGL ES / CPU 三态降级（见下「兼容决策」）。
- 本任务**只移植与文档**：不写测试、不跑模拟器、不截图（用户 2026-11-15 修订）。

## 文件清单

| 文件 | 内容 |
|---|---|
| [features.md](features.md) | 全部非 AI 功能模块映射（F-xxx）+ AI 明确排除清单 |
| [settings.md](settings.md) | 全部非 AI 设置键映射（S-xxx）：白名单/默认值/分组/排除 |
| [storage.md](storage.md) | 目录/命名/schema/备份/回收站/冷启动维护/归位不覆盖（ST-xxx） |
| [pages.md](pages.md) | 路由/页面/浮层映射（P-xxx） |
| [platform-differences.md](platform-differences.md) | 平台无等价/升级事项（PD-xxx），不做产品裁决 |
| [manual-verification.md](manual-verification.md) | 真机能力人工验证清单（MV-xxx），不写入自动验收 |
| `../UPSTREAM_SYNC.md` | 上游同步流程（只读 diff → 人工移植 → 更新基线） |
| `../../scripts/verify_source_inventory.py` | 源清单/映射结构覆盖核验脚本（静态，非行为测试） |

## 状态词表（受 verify 脚本约束）

| 状态 | 含义 |
|---|---|
| `待实现` | 目标未实现；文档不代替实现证据 |
| `待实现（平台无等价，需裁决）` | 平台无可行等价或需产品降级，交用户裁决 |
| `已实现（A2-S 骨架）` | 骨架真实交付且可结构校验（随行给出证据路径） |
| `排除（AI，不移植）` | AI 站位，不实现、不移植、不保留占位 |

> **禁止**在本目录出现「已完成 / 完成 / 通过」等字样；未实现项必须标 `待实现`。
> `verify_source_inventory.py` 会断言这一点。

## 兼容决策（iPhone 5/6 最低适配，2026-11-15 用户指示）

用户新指示：**最低可能适配 iPhone 5 / iPhone 6**；**允许阉割功能**，但**必须保留基础 WOTA 艺录制参数调整与工具链**。
主代理采用 **UIKit + AVFoundation** 作为 iOS 基础兼容路径（**不再以 SwiftUI / iOS 16 为最低路径**）。据此：

| 维度 | 决策 | 依据 |
|---|---|---|
| 最低系统 | **iOS 10**（iPhone 5 上限 iOS 10.3.4；iPhone 6 上限 iOS 12.5.7） | 用户指示 |
| 最低机型 | **iPhone 5（armv7，32 位）+ iPhone 6（arm64）** | 用户指示 |
| UI 框架 | **UIKit**（基础路径）；SwiftUI 仅作可用设备上的可选增强，不是最低路径前提 | 用户指示 |
| 采集/录制 | **AVFoundation**（`AVCaptureSession`/`AVAssetWriter`/`AVAudioSession`） | 既有约束 |
| 图像后端 | **Metal（仅支持设备）/ OpenGL ES（独立重写）/ CPU 降级** 三态；iPhone 5 无 Metal ⇒ 不走 Metal | iPhone 5（A6）不支持 Metal（Metal 需 A7+） |
| 工具链门 | **旧 Xcode 产 armv7 切片** 与 **现代 Xcode 编译 arm64** 为**两个独立工具链门**（由主代理核实，见 `platform-differences.md` PD-012） | 用户指示 |
| 功能裁剪 | 可阉割非核心功能；**核心录制参数与工具链不得略过**（清单见下） | 用户指示 |

### 必须保留的核心 WOTA 艺录制参数（不得因低配阉割）

以下参数属于**基础录制能力**，在 iPhone 5/6 路径上必须可用（可按设备能力降级取值，但入口与调整逻辑要在）：

| 参数组 | 参数 | Android 源（证据） |
|---|---|---|
| 分辨率 / 帧率 | 分辨率档、帧率档（含 1/24 特殊档，真机能力见人工清单） | `Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/core/WotaParams.kt:1` |
| 曝光 | ISO、快门/曝光时间、曝光补偿 | `Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/camera/RequestApplier.kt:1` |
| 对焦 | 对焦模式、对焦距离（含手动对焦） | `Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/camera/RequestApplier.kt:1` |
| 白平衡 | 白平衡模式、色温、增益 | `Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/core/CameraAbility.kt:1` |
| 变焦 | 光学/数码变焦比 | `Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/core/WotaParams.kt:1` |
| 手电 / 闪光 | 录制手电开关与亮度 | `Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/core/WotaParams.kt:1` |
| 编码 / 码率 | H.264 或 HEVC、帧率/码率档 | `Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/RecordProfile.kt:1` |
| 录制档位 | WotaTiers 档位（分辨率/帧率组合） | `Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/core/Constants.kt:1` |

> 逐条映射见 `features.md`（相机参数 F-001…F-006）、`settings.md`（持久化白名单 S-001…S-034）与 `manual-verification.md`（真机能力 MV-001…MV-011、低配机型 MV-028…MV-037）。

### 工具链清单（两个独立门，不得混为一谈）

| 门 | 目标切片 | 说明 |
|---|---|---|
| 门 A：旧 Xcode | **armv7**（iPhone 5） | 旧工具链才能产出 armv7 切片；与现代工具链**独立**核实 |
| 门 B：现代 Xcode | **arm64**（iPhone 6 及更新） | 现行 Xcode 产 arm64 |
| Android 对照 | 上游产物含 `arm64-v8a` 与 `armeabi-v7a`（`Android/WotaGeiCamApp/app/build.gradle.kts:76`） | 说明上游本就有 32/64 位双切片，iOS 侧同理需双门 |

> 双门是否可行、如何取舍由**主代理核实并裁决**；本表只据实记录事实，不替裁决。A2-S 骨架此前按 SwiftUI/iOS 16 编写，**需按 UIKit/iOS 10 基础路径与新后端重新评估基线**（状态仍按骨架实际交付标注，标注改动见下）。

## 覆盖摘要（本任务清点结论）

- **功能**：33 行（F-000…F-032）。其中骨架已交付 1 项（F-000 预览）、AI 排除 1 项（F-030）、
  平台无等价需裁决 3 项（F-021 内录、F-024 更新安装、F-025 蓝牙电量/遥控；F-013/F-026 同属无等价）、
  兼容最低机型新增 2 项（F-031 三态渲染后端、F-032 armv7/arm64 双工具链门）。
- **设置键**：入档白名单 34 键（S-001…S-034）+ 直写 UI 键（S-040…S-064）+ 平台无等价 2 项（S-070/S-071）；
  AI 设置键 6 个明确排除；另有 5 项「有意不入档」项不得补齐。
- **存储**：目录/命名 13 项、schema 5 项、回收站/删除 3 项、备份 4 项、冷启动维护 8 项、FileProvider 1 项。
- **页面**：路由 6 项、取景页内部块 9 项、浮层 27 项。
- **平台差异**：12 项（PD-001…PD-012，含 PD-011 无 Metal 机型降级、PD-012 双工具链门）。
- **人工验证**：37 项（MV-001…MV-037，含低配机型/双工具链 MV-028…MV-037），全部 `未执行`。

## 关键事实（供实现与集成方引用）

- **AI 站位一律排除**：`stance/*`、`full/.../stance/*`、`lite/.../stance/StanceFeature.kt`、
  `model_pack/*`、`voice/*`、`stance_*` 设置键，iOS 不建落点、不留占位。
- **分屏是 App 内左右栏**（`CameraScreen.kt:465/2479/2493`，`SPLIT_SQUEEZE=0.5f`），**非** OS 多窗口、
  **无**上下变体、不随方向切换。iOS 取向为**产品决策，待用户裁决**（`pages.md` P-017、`platform-differences.md` PD-006）。
- **归位不覆盖**：目标已存在时不动原文件、不覆盖（`StalePendingSweep`、`settingsToRestore` additive）。
- **命名/目录对齐 Android**，不引入隐式媒体库行为（`Movies/WotaGeiCam/{Camera,Clip,Inau}`、
  `VID_yyyyMMdd_HHmmss_%03d.mp4`、`edit_`/`inau_` 前缀、`.drops.json`）。
- **平台无等价需裁决**：内录（ReplayKit 边界）、全盘媒体删改/回收站、App 内更新安装、蓝牙 A2DP 电量/遥控、
  系统备份、所有文件访问、前台服务、系统媒体库可见性（见 `platform-differences.md`）。

## 本机验证状态（Windows，无 Apple 工具链）

- `python -I iOS/scripts/verify_source_inventory.py`：见最终报告（记录退出码与输出摘要）。
- Apple 构建/归档/打包（`xcodebuild`）：**未执行**，阻塞于本机无 Xcode（见 `iOS/README.md` 与最终报告）。
- 本任务**不写测试、不跑模拟器、不截图**。
