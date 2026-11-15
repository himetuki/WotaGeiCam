# 存储映射（Android 非 AI 存储 → iOS）

基线：`Android/` 只读参考，提交 `7394187`。本表覆盖目录约定、命名、入库、缩略图/sidecar、
数据库 schema、备份/恢复、回收站、冷启动维护、归位不覆盖。

## 核心约束（逐条落到源码）

- **命名与目录对齐 Android**，不引入 Android 中不存在的隐式媒体库行为。
- **归位不覆盖**：目标已存在时**不动原文件、不覆盖**（多源证据见下）。
- **clip 永不删**：维护扫描只改可见性/名字，不删成片字节。
- **恢复是 additive**：备份导入合并且不覆盖既有键（`settingsToRestore`）。

## 状态词表

`待实现` ｜ `待实现（平台无等价，需裁决）` ｜ `已实现（A2-S 骨架）` ｜ `排除（AI，不移植）`。

## 目录与命名

| ID | 项 | 值 / 规则 | Android 源 | iOS 目标 | 状态 |
|---|---|---|---|---|---|
| ST-001 | 成片目录 | `Movies/WotaGeiCam/Camera` | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/VideoStore.kt:36 | Packages/WotaCore/Sources/WotaCore/StorageLayout.swift | 待实现（路径纯函数骨架已落） |
| ST-002 | 成片 MIME | `video/mp4` | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/VideoStore.kt:37 | iOS/WotaGeiCam/Recording/ | 待实现 |
| ST-003 | 成片命名 | `%sVID_yyyyMMdd_HHmmss_%03d.mp4`（`Locale.US`，3 位随机数；`%s` 为处理前缀，可为空） | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/VideoStore.kt:57 | Packages/WotaCore/Sources/WotaCore/RecordingFileNaming.swift | 待实现（命名纯函数骨架已落） |
| ST-004 | 剪辑成片目录 | `Movies/WotaGeiCam/Clip` | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/player/ClipExport.kt:54 | Packages/WotaCore/Sources/WotaCore/StorageLayout.swift | 待实现 |
| ST-005 | 剪辑成片命名 | `edit_<源名去扩展名>.mp4` | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/player/ClipExport.kt:100 | Packages/WotaCore/Sources/WotaCore/RecordingFileNaming.swift | 待实现 |
| ST-006 | 选轨成片目录 | `Movies/WotaGeiCam/Inau` | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/player/TrackExport.kt:41 | Packages/WotaCore/Sources/WotaCore/StorageLayout.swift | 待实现 |
| ST-007 | 选轨成片命名 | `inau_<源名去扩展名>.mp4` | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/player/TrackExport.kt:61 | Packages/WotaCore/Sources/WotaCore/RecordingFileNaming.swift | 待实现 |
| ST-008 | 处理前缀识别 | `VID_` 或 `[0-9]+fto[0-9]+f_VID_` 判为本 App clip | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/StalePendingSweep.kt:1 | Packages/WotaCore/Sources/WotaCore/RecordingFileNaming.swift | 待实现（`isWotaVideoFileName` 骨架已落） |
| ST-009 | pending 垃圾名 | 正则 `\.pending-\d+-`；`stripPendingJunk`/`commitRenameTarget`/`sidecarConvergeTarget` | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/PendingName.kt:1 | Packages/WotaCore/Sources/WotaCore/RecordingFileNaming.swift | 待实现 |
| ST-010 | 系列 ID | Int64 = 时间基 × 1000 + 进程内单调序号（`TIME_CAP_MS` 溢出守卫），避免改 schema | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/SeriesIds.kt:1 | Packages/WotaCore/Sources/WotaCore/SeriesIDGenerator.swift | 待实现（骨架已落 `SeriesIDGenerator`） |
| ST-011 | 抽帧 sidecar | 与成片同目录同名，扩展名 `.drops.json` | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/ArcDropLog.kt:75 | iOS/WotaGeiCam/ArcRepair/ | 待实现 |
| ST-012 | 空间门槛 | 可用 < 200MB 拒绝起录并给原因码 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/VideoStore.kt:75<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/core/Constants.kt:57 | iOS/WotaGeiCam/Recording/ | 待实现 |
| ST-013 | 分段上限 | 3.5 GiB（FAT32 安全上限），85% 提前告知换段 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/core/Constants.kt:56<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/core/Constants.kt:92 | iOS/WotaGeiCam/Recording/ | 待实现 |

> iOS 侧 **不得**复现 MediaStore `IS_PENDING` / MediaScanner 语义（Android 实现细节，
> `VideoStore.kt:26` 自述「全程不用 MediaScanner」）。改为 App 自有目录原子落盘 + 显式可见性动作。

## 数据库 schema

| ID | 项 | 值 / 规则 | Android 源 | iOS 目标 | 状态 |
|---|---|---|---|---|---|
| ST-020 | 库名 | Room `wota_media.db`，version=1 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/media/WotaDb.kt:170<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/media/WotaDb.kt:183 | iOS/WotaGeiCam/Library/ | 待实现 |
| ST-021 | 实体 MediaTag | 唯一索引 `(mediaId, tag)` + tag 索引 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/media/WotaDb.kt:31 | iOS/WotaGeiCam/Library/ | 待实现 |
| ST-022 | 实体 TrashItem | 本地回收站行（API29 回退路径） | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/media/WotaDb.kt:45 | iOS/WotaGeiCam/Library/ | 待实现 |
| ST-023 | 实体 MediaSeries / SeriesPart | 系列与分部 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/media/WotaDb.kt:55<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/media/WotaDb.kt:63 | iOS/WotaGeiCam/Library/ | 待实现 |
| ST-024 | 迁移策略 | `fallbackToDestructiveMigration()`（改 schema 会清用户数据，注释告警） | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/media/WotaDb.kt:175 | iOS/WotaGeiCam/Library/ | 待实现 |

> 媒体身份键用**指纹**（relativePath/name/size/duration），**不用 MediaStore ID**
> （`WotaBackupAgent` 还原即按指纹重绑，见 ST-033）。iOS 侧保持同一身份口径。

## 回收站 / 删除（平台无等价）

| ID | 项 | Android 行为 | Android 源 | iOS 说明 | 状态 |
|---|---|---|---|---|---|
| ST-030 | 回收站 | API30+ 直写 `IS_TRASHED`；API29 回退「本地 trash_item + 从常规页签隐藏」 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/media/MediaActions.kt:160<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/media/MediaActions.kt:189 | iOS 无跨 App 回收站；改为 App 自有目录回收站 + 自有索引隐藏 | 待实现（平台无等价，需裁决） |
| ST-031 | 永久删除 | 直接 delete（无系统授权框）+ 清 sidecar/标签/系列/回收站/缩略图 + 非取消时序（hold→清理→250ms pop→900ms 刷新） | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/media/MediaActions.kt:251<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/media/MediaActions.kt:431 | iOS 删自有文件走系统确认；时序（清理后刷新）保留 | 待实现（平台无等价，需裁决） |
| ST-032 | 分享排除回收站 | 分享不含已回收项 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/media/MediaActions.kt:360 | iOS 分享面板排除回收站项 | 待实现 |

## 备份 / 恢复（平台无等价）

| ID | 项 | 值 / 规则 | Android 源 | iOS 说明 | 状态 |
|---|---|---|---|---|---|
| ST-033 | 便携快照 | `wota_user_backup.bin`（`PREFS_NAME=wota_settings`，`VERSION=1`，`MAX_BYTES=4MiB`，设置白名单 + 标签） | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/backup/PortableUserData.kt:12<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/backup/PortableUserData.kt:14 | iOS 改为显式导出/导入二进制快照 + Files 共享 | 待实现（平台无等价，需裁决） |
| ST-034 | 恢复 additive | `settingsToRestore` 不覆盖既有键；`mergeTags`/`uniqueCandidates`/`matches` 去重合并 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/backup/PortableUserData.kt:76<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/backup/PortableUserData.kt:80 | iOS 保持 additive 不覆盖；AI 键排除 | 待实现 |
| ST-035 | BackupAgent | `onFullBackup`/`onRestoreFinished` 重试；tags 按指纹重绑 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/backup/WotaBackupAgent.kt:1 | iOS 无 BackupAgent 等价；改显式导入导出 | 待实现（平台无等价，需裁决） |
| ST-036 | 备份规则范围 | 仅 include `domain=file path=wota_user_backup.bin`（不含 root/sharedpref/database/external） | Android/WotaGeiCamApp/app/src/main/res/xml/backup_rules.xml:5<br>Android/WotaGeiCamApp/app/src/main/res/xml/data_extraction_rules.xml:5 | iOS 备份范围按最小化原则 | 待实现 |

## 冷启动维护（归位不覆盖）

| ID | 项 | 规则 | Android 源 | iOS 目标 | 状态 |
|---|---|---|---|---|---|
| ST-040 | 触发时机 | 仅真冷启动（`savedInstanceState == null`），后台线程 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/MainActivity.kt:331 | iOS/WotaGeiCam/Library/ | 待实现 |
| ST-041 | 扫描范围 | 仅 `Movies/WotaGeiCam/Camera` 目录 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/StalePendingSweep.kt:59 | iOS/WotaGeiCam/Library/ | 待实现 |
| ST-042 | 年龄门槛 | 1 小时（`SWEEP_STALE_MS=60*60*1000`） | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/StalePendingSweep.kt:45 | iOS/WotaGeiCam/Library/ | 待实现 |
| ST-043 | 条目上限 | ≤500 条（`SWEEP_MAX_ENTRIES`） | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/StalePendingSweep.kt:48 | iOS/WotaGeiCam/Library/ | 待实现 |
| ST-044 | clip 永不删 | 探测可提取才改名，查不出则一动不动 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/StalePendingSweep.kt:75 | iOS/WotaGeiCam/Library/ | 待实现 |
| ST-045 | 归位不覆盖 | 有行 ⇒ provider rename；查不出 ⇒ 不动；归档改名目标已存在 ⇒ **绝不覆盖** | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/StalePendingSweep.kt:1 | iOS/WotaGeiCam/Library/ | 待实现 |
| ST-046 | pending 行救援 | `rescuePendingRows`：导出链 commit 全败留下的 `IS_PENDING=1` 行救援 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/StalePendingSweep.kt:37 | iOS/WotaGeiCam/Library/ | 待实现 |
| ST-047 | 库一致性 | reconcile 坏行、补扫缺失 clip、`clipFor` 三种名形 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/StalePendingSweep.kt:1 | iOS/WotaGeiCam/Library/ | 待实现 |

> iOS 无 provider 时，本项等价为「对 App 自有目录与自有索引做 reconciliation」；
> 不覆盖、不删 clip、年龄门槛、条目上限为硬约束，逐条保留。

## FileProvider / 临时目录（Android 实现细节，iOS 换等价）

| ID | 项 | Android 值 | Android 源 | iOS 等价 | 状态 |
|---|---|---|---|---|---|
| ST-050 | 暴露路径 | external `Movies/WotaGeiCam/`；cache `updates/`（更新 APK）；cache `share-out/`（诊断导出） | Android/WotaGeiCamApp/app/src/main/res/xml/file_paths.xml:12<br>Android/WotaGeiCamApp/app/src/main/res/xml/file_paths.xml:13<br>Android/WotaGeiCamApp/app/src/main/res/xml/file_paths.xml:14 | iOS 无 FileProvider；分享用 UIActivityViewController/`ShareLink`，临时件放 iOS 沙盒 tmp 且限 iOS 内 | 待实现（平台无等价，需裁决） |
