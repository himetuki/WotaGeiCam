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
- A2重构中旧结构脚本引用已移除的SwiftUI文件曾退出1；A2-S现已改用UIKit入口，待主代理在并行模块收口后重新生成工程并核对。

## 兼容性资料

- Apple官方：<https://developer.apple.com/news/upcoming-requirements/?id=06062022a> 明确 Xcode14 仅编译64位应用，iOS10是最后支持32位App的iOS版本。
- Apple官方Xcode14发布说明：<https://developer.apple.com/documentation/xcode-release-notes/xcode-14-release-notes>：不再支持armv7/armv7s/i386以及iOS11之前的部署目标。
- 因此Legacy和Modern构建分别保留，禁止宣称单个现代Xcode已覆盖iPhone5。


## A2 / 工程骨架

- A2代理交付UIKit/iOS10骨架、经典objectVersion54工程、4个共享scheme、8个配置、Swift5.6核心包及inline路径。主代理已读取关键源码、配置和工程生成脚本。
- 并行D1新增文件时，工作树 `generate_project.py --check` 与 `check_project.py` 返回1：只因新Library文件尚未收录。不得将这个状态报为全树通过。
- 为独立验收A2，主代理将已暂存iOS文件用 `git checkout-index` 导出到工作区内新建 `.snapshot-a2-*` 目录，仅对快照运行静态结构检查：`checks=473` / `PASS` / `A2_SNAPSHOT_EXIT=0`。已即时清除快照目录。
- `bash -n iOS/scripts/*.sh` 退出0。
- `detect_apple_tools.sh` 退出69：缺xcodebuild/xcrun/xcode-select/plutil；未执行Apple编译、archive、真实IPA打包。
- 修正生成工程CRLF引起的diff-check：iOS目录新增.gitattributes eol=lf、生成脚本强制LF；git diff --cached --check退出0。
- 修正文档：本轮不使用模拟器、不测试；Modern最低iOS15不能装到iPhone6，iPhone6使用Legacy配置。A2源码结构通过不代表可运行或可安装。

## 2026-11-20 / 打包就绪轮（回答 /goal「是否达到可打包构建的程度」）

结论：**本地可验证项全部通过；Swift 级编译仍无证据（本机无 Apple 工具链），
达到可打包构建的最后实证交给新增 CI 工作流。** 未宣称已编译或已出包。

已完成项：

- 工程重生成（package 模式）：`python -I iOS/scripts/generate_project.py` — sources=48、
  resources=2（Assets.xcassets + PrivacyInfo.xcprivacy）、groups=15、8 配置、4 scheme；
  此前约 40 个未收录 Swift 文件全部进入 Sources 阶段。
- 两种登记模式一致性：`generate_project.py --check --core-mode package` 与
  `--core-mode inline` 均 PASS（inline 1532 项）；磁盘最终态为 package 模式。
- `check_project.py`：1502 项 PASS。更新三处过时断言：方向 owner 改查会话控制器
  （`setPreviewVideoConnection` 内 `videoOrientation`）、照片图库权限键改为必须不存在、
  新增 PrivacyInfo.xcprivacy 内容断言（四类 API + 理由编号逐一钉死）与 Resources 收录；
  支持 `--core-mode` 传递。
- `verify_source_inventory.py`：checks=1643 failures=0 PASS。
- `.github/workflows/build-ipa.yml` 新增并经 YAML 结构解析（jobs×2、matrix 2 项、
  push tags v* + workflow_dispatch）：ubuntu 结构检查先行，macos-15 上 Modern 双变体
  archive → unsigned IPA 打包（Payload 回读校验）→ 上传 .app/.xcarchive/.ipa/SHA256SUMS。
  仅新增该文件，未改动既有工作流；不需要签名 Secrets。
- 源码收口（均为已定位阻断项，非新功能）：
  CPU 后端像素缓冲锁失败改为抛错，杜绝交出未写入面；ArcRepair 字体类型
  `UIFontTextStyle`→`UIFont.TextStyle`；片库暂存编号接入唯一分配（进程号×百万桶+序号），
  发布/取消/读取按 pendingId 重算暂存名校验句柄身份；发布改名后属性读取失败按
  「已保存+警告」回报（`publishResult`/`MediaPublishResult`，替换旧 `publish`）。
- 文档同步：README（功能集/目录/命令/CI/权限与隐私清单口径）、ROADMAP 状态复核节、
  D1-media.md 发布签名与行号、mappings F-011 32→34 键、S-033 FULL→ORIG。
- 清理 iOS/scripts/__pycache__。

未完成项 / 阻塞项：

- Apple 编译、archive、无签名 IPA 打包、真机安装：**本机 Windows 无 xcodebuild/xcrun/
  swiftc**，各脚本缺工具分支返回 69，不伪造成功。这是当前唯一实质阻塞，
  由 macOS CI（工作流）或用户机器真实构建暴露 Swift 级问题。
- Legacy iPhone5/armv7：无托管 Xcode 13.4.1，需自建 macOS12 runner，本轮不覆盖。
- 真机相机能力、armv7 运行行为：只列人工验证清单，不入自动验收。

证据（命令与退出码摘要）：

- `python -I iOS/scripts/generate_project.py` → sources=48 written=5，退出0；
- `generate_project.py --check`（package）→ OK，退出0；`--core-mode inline --check` → OK，退出0；
- `check_project.py` → checks=1502 PASS，退出0；`--core-mode inline` → checks=1532 PASS；
- `verify_source_inventory.py` → checks=1643 failures=0，退出0；
- 工作流 YAML 结构解析 → jobs=['structure-checks','build-unsigned-ipa']，matrix 2 项。

## 2026-11-21 / 只读审计与修复轮（本分支 8 个提交，未推送）

审计口径：Windows 无 Swift/Xcode，只有静态源码审查 + 三个本地脚本；**没有 Apple 编译证据**。
四路只读审计覆盖 Camera/Recording、Library/Settings/Update/ArcRepair、Player/Compare/Rendering、
WotaCore/scripts/CI；所有结论先以当前源码复核，再修复。

已修复（对应提交）：

- `d596f3c` 编译阻断：`CompareDecideCommands` 三处实参顺序违反 Swift 声明顺序；
  `UIAccessibility.post`（iOS 11+）与 `UIColor.systemBlue`（iOS 13+）在 Legacy iOS 10 目标无门控；
  `setAutoExposure` 的可选闭包方法引用改为显式闭包。
- `7da4020` 数据丢失：录音/剪辑/补弧在暂存文件已完整封装、仅最终名发布失败时，
  不再 `cancelPending` 删除成片；新增 `MediaCommitOutcome.publishFailed` 区分「暂存无效」与「发布失败」。
  音频 append 失败降级为无声录像，不再让整段录像判死。
- `7480745` 索引/路径：瞬时索引读失败保留原文件并拒绝写入；坏索引备份失败禁止覆盖；
  `mediaId` 上限防溢出；改名目标必须是自有成片名；索引重绑失败回滚改名；
  sidecar 归位统一走 `sidecarFileName(forVideoFileName:)`；批量补录只写一次索引；
  `default_size` 尺寸上限/乘法溢出防护；采样率用 Int64 距离防 armv7 `Int32.min` 溢出。
- `89d52b3` 备份：`importBackup` 返回 `ImportResult`（设置条数 + 标签）；
  媒体库按跨端指纹导出标签，导入时只对唯一、非回收站匹配做加法合并。
- `68af674` 播放器/GPU：从末帧拖回中间后状态离开 `.ended`；恢复播放的异步 seek 用代际令牌防暂停被覆盖；
  逐帧播放态隐式暂停；Metal/GLES 同步后检查 GPU 错误并抛 `readbackFailed`；非整数帧率按真实周期取整。
- `d2718d0` 资源/CI：更新 JSON 响应 1 MiB 流式上限；CI 的 tag/branch 前缀经 env + 白名单字符，
  不再拼进 `run` 脚本；IPA 资产名与 CI 实际产物一致；Metal 只保留内嵌源并新增参考副本一致性检查；
  设置页复用 cell，target 随 cell 释放。
- `e5e46a6` 并发：ArcRepair 的 `finished` 统一走 `stateLock`；播放器旧 asset 元数据回填不再覆盖新素材。
- `bc8ed67` 索引留证：顶层 JSON 可解析但个别行被丢弃时，先复制原始索引到 `corrupt-*` 再继续。

本轮静态证据：

- `python -I iOS/scripts/check_project.py` → `checks=1504` PASS（新增 2 条 Metal 一致性检查）；
- `python -I iOS/scripts/verify_source_inventory.py` → `checks=1643 failures=0` PASS；
- `python -I iOS/scripts/generate_project.py --check` → OK；
- `.github/workflows/build-ipa.yml` YAML 解析通过。

未完成 / 不声称：

- Apple 编译、archive、unsigned IPA、真机安装：本机无工具链，仍未执行。
- 录音音频声道（固定 2ch）与设备单声道麦克风的实际兼容、录制方向是否需写 `AVAssetWriterInput.transform`：
  仓库事实不足，列入真机验证，不在本轮猜改。
- AppIcon / Legacy 启动图、PrivacyInfo 的 SystemBootTime 类别是否保留：需产品/设备侧确认。
- Mimosa 完整安全扫描在本轮未取得结论（`scanner_enobufs`），**不得据此宣称项目安全**。
