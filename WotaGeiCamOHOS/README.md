# WotaGeiCam for HarmonyOS（鸿蒙复现工程）

将上游 [himetuki/WotaGeiCam](https://github.com/himetuki/WotaGeiCam)（Android 录影相机）复现到
**HarmonyOS 6 及以上**（目标 API 版本以 DevEco Studio 当前 SDK 为准，HarmonyOS 6.0 对应 API 20+，
开工时再最终锁定）的移植工程，仅存在于本仓库的 `harmony` 分支。

> 工程骨架（AppScope/、entry/、hvigor 配置等）由 DevEco Studio 生成后放入本目录。
> 本目录在上游 main 分支中不存在，是 harmony 分支的“自留地”。

## 目录约定

```
WotaGeiCamOHOS/
├── AppScope/ entry/ ...     # DevEco 工程骨架（待生成）
├── docs/
│   ├── UPSTREAM_SYNC.md     # 上游同步流程（黄金规则 + 检查清单）
│   └── PORT_MAP.md          # Android 模块 → 鸿蒙落点映射表
├── scripts/
│   └── sync-upstream.sh     # 一条命令同步上游 main
└── .gitignore               # DevEco/hvigor 构建产物忽略规则
```

## 与上游的关系

- 上游 `main` 只作只读镜像，**永不在其上开发**；
- 本分支（`harmony`）所有改动只发生在 `WotaGeiCamOHOS/` 目录下，不改上游任何文件，
  因此 `git merge origin/main` 理论上零冲突；
- 上游语义变化（文件格式、判据、命名规则等）时，按
  [docs/UPSTREAM_SYNC.md](docs/UPSTREAM_SYNC.md) 的检查清单 +
  [docs/PORT_MAP.md](docs/PORT_MAP.md) 的映射表定位鸿蒙侧需要跟进的文件。

## 当前进度

- [x] 克隆上游、建立 `harmony` 分支（基于上游 38d712a，v0.0.9-r05 之后）
- [x] 建立同步机制与移植映射文档
- [ ] DevEco 工程骨架生成
- [ ] core 纯逻辑包移植
- [ ] 相机预览 + 录制链路
- [ ] 光弧工具链（抽帧 / 补弧 / 离线修复）
- [ ] 播放器 / 媒体库 / 剪辑导出
- [ ] 双音轨（内录）与蓝牙面板
- [ ] 更新检查（GitHub Releases）
