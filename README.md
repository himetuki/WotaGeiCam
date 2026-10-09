# WotaGeiCam

[![Build Release APKs](https://github.com/himetuki/WotaGeiCam/actions/workflows/build-apk.yml/badge.svg)](https://github.com/himetuki/WotaGeiCam/actions/workflows/build-apk.yml)

一款为 **WOTA 艺 / 演出现场拍摄**打造的 Android 录制相机：电影机式手动控制、实时 RGB 曲线调色、完整的取景辅助，以及一套针对光弧拍摄的工具链——强制低速快门、抽帧间隙的光弧修复、剪辑软件式的对比播放。

> 项目处于早期开发阶段，功能与界面迭代很快，最新版本以 [Releases](https://github.com/himetuki/WotaGeiCam/releases/latest) 页为准。

## 功能特性

### 拍摄控制
- 手动曝光：ISO / 快门 / EV；快门支持强制 1/24、1/25 低速档（长曝光保证光弧连贯），并按当前帧周期校验哪些档位可选
- 帧率控制（含非精确档的抽帧转换）、白平衡色温、手动对焦与变焦
- 实时 RGB 曲线调整
- 相机参数一律从 `CameraCharacteristics` 运行时读取，按设备能力自适应，不写死机型数值

### 取景辅助
- 斑马纹、参考线 / 网格、直方图
- 水平仪与俯仰仪（可开关、回正轻震提示）
- 控件位置自定义：编辑页内拖拽布局，Dock 与读数块可自由摆放，就近弹出的调节面板跟随控件锚定

### 录制
- MediaRecorder 与 MediaCodec + MediaMuxer 双录制引擎，码率可调
- **内录音轨（可选）**：除麦克风环境音外，可同时采集应用内 / 系统正在播放的音频（Android 10+ 媒体投影），成片带环境音 + 内录双音轨；会话期间前台服务常驻，通知栏提供一键停止
- **自动分段轮转**：单个文件到达 3.5 GiB（FAT32 安全上限）时自动换段，段名连续、内容不丢，可直接顺序拼接播放
- 分屏对比录制：主画面照常录制，右半屏可加载参考视频对照拍摄
  （"另镜头同录"需要设备并发相机支持，设备不支持时入口自动灰显并说明原因）
- 录制期帧率转换：设备没有 24/25 帧率原生档时，录制自动抽帧到所选帧率——
  「自动抽帧补弧」把被抽帧的画面并入前后帧保持光弧连贯，「仅抽帧」保留纯抽帧结果；
  被抽帧位次记录在成片同名的 `.drops.json` 里
- 光弧修复：存量素材的离线抽帧 + 补弧（帧率弹层同款语义），处理结果存为新文件、不覆盖原片

### 播放与整理
- 内置媒体库：标签、收藏、回收站、重命名、分享
- 播放器：AB 循环、逐帧步进、双指缩放平移
- **剪辑软件式对比播放**：一条时间线 + 双视频轨（某侧无素材时该侧黑屏）+ 环境音 / 内录音轨，
  同一时刻只有一条音轨出声、可随时切换监听哪一条；用于动作与运镜的逐段对照，并保留对比历史
- 冷启动自动维护：清理历史遗留的临时命名残留，并保持媒体库索引与本机文件一致

### 更新
- 应用内「检查更新 / 下载更新」：**只在你主动点击时联网**，读取 GitHub Releases 的最新版本并下载安装包
  （直连失败时按镜像链回退）；不自动检查、不自动下载、不后台联网

## 下载

到 [Releases](https://github.com/himetuki/WotaGeiCam/releases/latest) 下载。每次发布提供四种 APK（内容相同，按设备架构任选其一）：

| 文件 | 适配 |
|---|---|
| `WotaGeiCam-vX.Y.Z-release.apk` / `WotaGeiCam-vX.Y.Z-universal-release.apk` | 全设备通用 |
| `WotaGeiCam-vX.Y.Z-arm64-v8a-release.apk` | 64 位 ARM（2016 年后的绝大多数手机） |
| `WotaGeiCam-vX.Y.Z-armeabi-v7a-release.apk` | 32 位 ARM 老设备 |

版本号形如 `0.0.7-r24`：`r` 后的数字是检查点序号，越大越新；应用内「检查更新」按同一规则比较。

## 安装与升级

- 系统要求：Android 10（API 29）及以上
- 升级直接覆盖安装；**请勿先卸载**——卸载会清空应用内的设置与媒体库存档
- 也可以直接在应用内检查更新并下载安装包
- 本应用为纯 Kotlin 实现、不含原生库，四种 APK 内容一致

## 从源码构建

```bash
# 需要 JDK 17 与 Android SDK（Platform 34 / Build-Tools 33.0.1）
git clone https://github.com/himetuki/WotaGeiCam.git
cd WotaGeiCam/WotaGeiCamApp
./gradlew :app:assembleDebug      # 日常构建
./gradlew :app:assembleRelease    # 发布构建（签名说明见下）

# 打带检查点号的包（版本名 0.0.7-rNN，设置页可见）
./gradlew :app:assembleRelease -Prbuild=24

# 与 CI 一致的"四件套"（产物名带版本号：plain / universal / arm64-v8a / armeabi-v7a）
./gradlew :app:assembleRelease -PabiSplits=true -PapkBaseName=WotaGeiCam -Prbuild=24
```

签名说明：仓库不含签名材料。如需产出正式签名的 release 包，在 `WotaGeiCamApp/` 下自备 `keystore.properties`（含 `storeFile` / `storePassword` / `keyAlias` / `keyPassword` 四项）与 keystore 文件；缺失时 release 变体自动回退 debug 签名，仅供本地测试。

## CI

推送 `v*` 标签即触发 [GitHub Actions](.github/workflows/build-apk.yml)：从标签推导检查点号、自动产出带版本号的四种 APK（plain / universal / arm64-v8a / armeabi-v7a）并创建 GitHub Release。

## 隐私

- **唯一的联网用途是应用内的「检查更新 / 下载更新」**，且只在你主动点击时才发起请求（读取 GitHub Releases 的最新版本并下载 APK，直连失败按镜像链回退）——没有自动检查、没有后台联网、没有账号体系
- 不上传任何拍摄素材与使用数据：无统计、无广告、无第三方分析
- 拍摄素材只写入本机存储
- 内录音轨使用系统媒体投影采集音频，仅在录制期间有效（前台服务 + 常驻通知，可一键停止）
- 权限与用途：相机 / 麦克风（拍摄）、媒体读取（媒体库）、所有文件访问（删除或重命名本机素材时不弹系统授权框）、蓝牙（读取外接音频设备状态）、网络（仅用于更新检查与下载）

## 许可

本项目以 [GNU GPL v3](LICENSE)（SPDX: `GPL-3.0-only`）发布。

Copyright © 2026 himetuki
