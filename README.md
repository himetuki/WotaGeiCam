# WotaGeiCam

[![Build Release APKs](https://github.com/himetuki/WotaGeiCam/actions/workflows/build-apk.yml/badge.svg)](https://github.com/himetuki/WotaGeiCam/actions/workflows/build-apk.yml)

一款为 **WOTA 艺 / 演出现场拍摄**打造的 Android 录制相机：低光环境下的电影机式手动控制、完整的取景辅助，以及一套针对灯轨拍摄的工具链——强制低速快门、抽帧间隙的光弧修复、A/B 对比播放。

> 项目处于早期开发阶段，功能与界面迭代很快，最新版本以 [Releases](https://github.com/himetuki/WotaGeiCam/releases/latest) 页为准。

## 功能特性

### 拍摄控制
- 手动曝光：ISO / 快门 / EV，快门支持强制 1/24、1/25 等低速档（保证灯轨完整）
- 帧率控制、白平衡色温、手动对焦与变焦
- 白平衡与 RGB 曲线调整
- 相机参数一律从 `CameraCharacteristics` 运行时读取，按设备能力自适应，不写死机型数值

### 取景辅助
- 斑马纹、参考线 / 网格、直方图
- 水平仪（图形 / 数值双模式）

### 录制
- MediaRecorder 与 MediaCodec + MediaMuxer 双录制引擎，码率可调
- 分屏对比录制：主画面照常录制，右半屏可加载参考视频对照拍摄
  （"另镜头同录"需要设备并发相机支持，设备不支持时入口自动灰显并说明原因）
- 光弧修复：对强制低速快门造成的抽帧间隙做补帧重建，处理结果存为新文件、不覆盖原片

### 播放与整理
- 内置媒体库：标签、收藏、回收站、重命名、分享
- 播放器：AB 循环、逐帧步进
- A/B 对比播放：两条视频同步播放，用于动作与运镜的逐段对照，并保留对比历史

### 界面
- 控件位置自定义：编辑页内拖拽布局，Dock 与读数块可自由摆放
- 横竖屏方向可按页设置

## 下载

到 [Releases](https://github.com/himetuki/WotaGeiCam/releases/latest) 下载。每次发布提供四种 APK（内容相同，按设备架构任选其一）：

| 文件 | 适配 |
|---|---|
| `WotaGeiCam-vX.Y.Z-release.apk` / `WotaGeiCam-vX.Y.Z-universal-release.apk` | 全设备通用 |
| `WotaGeiCam-vX.Y.Z-arm64-v8a-release.apk` | 64 位 ARM（2016 年后的绝大多数手机） |
| `WotaGeiCam-vX.Y.Z-armeabi-v7a-release.apk` | 32 位 ARM 老设备 |

## 安装与升级

- 系统要求：Android 10（API 29）及以上
- 升级直接覆盖安装；**请勿先卸载**——卸载会清空应用内的设置与媒体库存档
- 本应用为纯 Kotlin 实现、不含原生库，四种 APK 内容一致

## 从源码构建

```bash
# 需要 JDK 17 与 Android SDK（Platform 34 / Build-Tools 33.0.1）
git clone https://github.com/himetuki/WotaGeiCam.git
cd WotaGeiCam/WotaGeiCamApp
./gradlew :app:assembleDebug      # 日常构建
./gradlew :app:assembleRelease    # 发布构建（签名说明见下）
```

签名说明：仓库不含签名材料。如需产出正式签名的 release 包，在 `WotaGeiCamApp/` 下自备 `keystore.properties`（含 `storeFile` / `storePassword` / `keyAlias` / `keyPassword` 四项）与 keystore 文件；缺失时 release 变体自动回退 debug 签名，仅供本地测试。

## CI

推送 `v*` 标签即触发 [GitHub Actions](.github/workflows/build-apk.yml)：自动产出带版本号的四种 APK（plain / universal / arm64-v8a / armeabi-v7a）并创建 GitHub Release。

## 隐私

- 应用清单**没有 INTERNET 权限**：不联网、不上传任何数据、无广告、无统计
- 所有拍摄素材只写入本机存储

## 许可

本项目以 [GNU GPL v3](LICENSE)（SPDX: `GPL-3.0-only`）发布。

Copyright © 2026 himetuki
