# iOS 移植验收日志

所有命令在仓库根 `E:/22600/Agent/ZCode/WotaGeiCam` 执行，除非条目另有说明。仅记录真实输出，不以预期代替结果。

## 2026-11-15 / 起始基线

- `git status --short`：无输出，工作树干净。
- `git log -1 --format='%h %s'`：`7394187 docs(release): v0.0.12-r13 发布说明——相机恢复、模型/备份自愈与更新校验`。
- `git switch -c port/ios-20261115`：退出 0，已建立新分支，既有提交保持不动。
- `command -v xcodebuild`、`command -v swift`：未找到；不是构建/测试通过。
- 插件目录检索：`ios-simulator@zcode-plugins-official` installed=true / enabled=true；当前会话未列出 iOS 构建/模拟器工具。尚无 Apple 编译、模拟器截图或无签名成品证据。
- 已读插件公开 `README.md:84-90`（macOS、完整Xcode、Simulator runtime）和 `dist/providers/preflight.js:3-40`（本地 `xcode-select` / `xcodebuild` / `xcrun simctl`）；确认不是远程 Mac 服务。A2 拆为源码接口 A2-S 与不可豁免的 Apple 验证 A2-V，后者阻塞。
- `wsl --exec sh -lc 'command -v swift; command -v clang; uname -m'`：只输出 `x86_64`；当前 WSL 同样没有 Swift 编译器，正在核实是否可在项目目录部署纯逻辑测试工具。
- 初始路线图提交：`276c8ed docs(ios): establish isolated port roadmap and acceptance gates`；只包含三个新增 iOS 文档。`git diff --name-only 7394187 -- Android HarmonyOS` 无输出。

## 2026-11-15 / 用户设备兼容修订

- 用户补充最低设备可能为 iPhone 5/6，允许阉割高级功能，但必须保留基础 WOTA 录制参数调整和工具链。
- 基础路线从 SwiftUI/iOS16 改为 UIKit/AVFoundation/iOS10 源码兼容；iPhone5 使用 OpenGL ES/CPU 降级，Metal 仅作为支持设备的后端。
- iPhone5 的 armv7 与现代 Xcode 的实际产出能力尚未验证；iPhone6 的 arm64 也需真实 Apple 工具链编译。两者均不得以静态配置宣称已可安装。

## 2026-11-15 / 用户修订

- 用户取消模拟器与全部测试工作，只要求移植、编译和打包；已通知A1/A2按新范围交付。
- 不再准备 Swift/WSL 测试工具链；仅检查过官方工具链可下载性，没有下载、解压或安装。
- Apple设备目标编译/无签名打包仍是交付目标；当前Windows无Xcode，真实构建尚不能执行。

## A1 / 源映射基线

- 主代理执行 `python -I iOS/scripts/verify_source_inventory.py`：`checks=1643 failures=0` / `PASS`，退出0。
- 已阅读映射索引及平台差异表，确认核心参数、iPhone5/6兼容、双工具链和待裁决项未静默遗漏；现有映射状态不代表实现通过。
- `git diff --name-only -- Android HarmonyOS` 无输出。
- 提交 `3027f1b docs(ios): add traceable Android migration mappings`：仅9个iOS映射/同步/核验文件。UIKit骨架仍在独立重构，未混入本提交。
- A2重构中旧结构脚本引用已移除的SwiftUI文件曾退出1，待A2-S以新UIKit入口同步修复后重新核对。

## 兼容性资料

- Apple官方：<https://developer.apple.com/news/upcoming-requirements/?id=06062022a> 明确 Xcode14 仅编译64位应用，iOS10是最后支持32位App的iOS版本。
- Apple官方Xcode14发布说明：<https://developer.apple.com/documentation/xcode-release-notes/xcode-14-release-notes>：不再支持armv7/armv7s/i386以及iOS11之前的部署目标。
- 因此Legacy和Modern构建分别保留，禁止宣称单个现代Xcode已覆盖iPhone5。


## A2 / 工程骨架

待子代理返回并由主代理核验。
