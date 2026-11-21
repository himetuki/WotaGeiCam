# D1 媒体数据层（Android `media`/`record` → iOS `Library` + `WotaCore`）

只读基线：`Android/`，提交 `7394187`。本文件记录 **D1 数据层**的 Android → iOS 逐项映射、
接口契约、平台差异、切割取舍与人工验证清单。

- **源码状态**：源码已落，**未经 Apple 工具链编译**（本机 Windows 无 Xcode/swift，见文末「验证状态」）。
- **范围**：仅 `iOS/WotaGeiCam/Library/`、`iOS/Packages/WotaCore/Sources/WotaCore/Media*.swift`、本文件。
  未改动 `App/`、`Camera/`、`Player/`、`UI/`、`Settings/`、工程、脚本、共享映射，也未改动既有核心文件。
- **平台口径**：iOS 10 基线（iPhone 5 armv7 / iPhone 6 arm64），UIKit + AVFoundation；
  不用 SwiftUI / Combine / async-await / `Codable` / 新拍照选择器。平台无等价处的取舍见「平台差异」。
- **红线**（逐条有源码落点，见后）：目标存在绝不覆盖；成片永不删；永久删除仅限 App 自有目录内文件；
  不递归删未知文件；文件操作成功后才更新索引；索引原子写、坏索引只改名备份不删媒体；串行队列。

> 状态词：`源码已落（未编译）` ｜ `保留（平台差异）` ｜ `排除（AI，不移植）`。本文件不使用「完成/通过」等字样。

## 1. 目录与命名（对齐 Android，不引入隐式媒体库行为）

| ID | 项 | 值 | Android 源 | iOS 落点 |
|---|---|---|---|---|
| D1-001 | 成片目录 | `Movies/WotaGeiCam/Camera` | `.../record/VideoStore.kt:36` | `MediaStorageLayout.cameraRelativeDirectory`；`MediaLibraryPaths.cameraDirectoryURL` |
| D1-002 | 剪辑/选轨目录 | `Clip` / `Inau` | `.../player/ClipExport.kt` `.../player/TrackExport.kt` | `MediaStorageLayout.clipRelativeDirectory` / `inauRelativeDirectory` |
| D1-003 | 成片 MIME | `video/mp4` | `.../record/VideoStore.kt:37` | `MediaStorageLayout.videoMimeType` |
| D1-004 | 本工程成片名 | `VID_<ts>` 或 `[0-9]+fto[0-9]+f_VID_…` | `.../record/StalePendingSweep.kt`（`isOurClipName`） | `MediaStorageLayout.isOurClipName(_:)` |
| D1-005 | 剪辑/选轨名 | `edit_` / `inau_` 前缀 | `.../player/ClipExport.kt:100`、`TrackExport.kt:61` | `MediaStorageLayout.clipExportFileName` / `inauExportFileName` |
| D1-006 | sidecar 扩展 | `.drops.json`（与成片同目录同名） | `.../record/ArcDropLog.kt:75` | `MediaStorageLayout.sidecarExtension` / `sidecarFileName(forVideoFileName:)` |
| D1-007 | pending 名 & 清洗 | 正则 `\.pending-\d+-` | `.../record/PendingName.kt:25` | `MediaPendingName.stripPendingJunk/isPendingMarked/commitRenameTarget/stagingFileName` |
| D1-008 | 系列 id | `nowMs×1000 + seq`（`SEQ_SPAN=1000`、`TIME_CAP_MS` 夹取） | `.../record/SeriesIds.kt:31,38,50` | 由录制层在 `recordSeries(seriesId:…)` 传入（数据层不生成 id，见 D1-031） |

> iOS 无 MediaStore/DISPLAY_NAME 回查。`PendingName.sidecarConvergeTarget` 的「回查名」语义在 iOS 退化为
> 以本地文件当前名为准（`MediaPendingName.sidecarConvergeTarget(videoPath:queriedName:)` 保留签名）。

## 2. 索引（Room 四表 + 对比历史 → 单文件 JSON）

Android 用 Room `wota_media.db`（`.../media/WotaDb.kt:183`，`exportSchema=true`，
`fallbackToDestructiveMigration()` `:193`）。iOS 无 Room/KMP，改为 **单文件 JSON 索引**：

- 位置：`Documents/WotaGeiCam/wota_media_index.json`（在媒体目录**之外**，见 `MediaLibraryPaths.swift:18,53`）。
- 写：`Data.write(options: .atomic)`（`MediaIndexStore.swift:98`）先写临时文件再替换；
- 失败回滚：`apply(_:)` 落盘失败即回滚内存态并抛错，**不伪更新**（`MediaIndexStore.swift:37-52`）；
- 坏索引：解析失败 → **改名备份**为 `wota_media_index.corrupt-<ts>.json` 后从空索引重建，
  **媒体文件一个都不动**（`MediaIndexStore.swift:104-118`）。

| ID | Room 实体 | iOS 索引行 | Android 源 | iOS 落点 |
|---|---|---|---|---|
| D1-010 | `VideoClip`（身份） | `LibraryIdentity{mediaId,relativeDirectory,fileName,sizeBytes,durationMs,width,height,addedAtMs,modifiedAtMs}` | `.../media/MediaRepo.kt:76` | `LibraryIndexModels.swift:8` |
| D1-011 | `MediaTag`(unique `mediaId+tag`) | `LibraryTag{mediaId,path,tag,createdAtMs}` | `.../media/WotaDb.kt:31` | `LibraryIndexModels.swift:55` |
| D1-012 | `TrashItem`(PK uri) | `LibraryTrashItem{relativePath,mediaId,name,deletedAtMs,origin}` | `.../media/WotaDb.kt:45` | `LibraryIndexModels.swift:84` |
| D1-013 | `MediaSeries` | `LibrarySeries{seriesId,startedAtMs,partCount}` | `.../media/WotaDb.kt:55` | `LibraryIndexModels.swift:116` |
| D1-014 | `SeriesPart`(PK `seriesId+partIndex`) | `LibrarySeriesPart{seriesId,partIndex,mediaId,path,durationMs}` | `.../media/WotaDb.kt:63` | `LibraryIndexModels.swift:140` |
| D1-015 | `compare_history` 单键 | `LibraryIndexState.compareHistory[]` | `.../player/CompareHistory.kt:20` | `LibraryIndexModels.swift:177` |
| D1-016 | Room 迁移 | 无 schema 迁移（单 JSON，`version:1` 顶字段）；坏档无损备份 | `.../media/WotaDb.kt:193` | `LibraryIndexModels.swift:184`、`MediaIndexStore.swift:104` |

> 身份键：Android 备份还原按**指纹**（relativePath/name/size/duration）重绑，不用 MediaStore `_ID`
> （`WotaDb.kt:49` 注释）。iOS 无 MediaStore，稳定 `mediaId` 只作索引内主键，指纹见
> `MediaFingerprint`（`MediaItem.swift:55`）。行-文件失配由冷启维护（§7）对账。

## 3. pending 写入 → 原子发布 / 失败取消

Android 走 MediaStore `IS_PENDING` 两段式（`VideoStore.kt:84 createPending` / `:112 commit` / `:172 discard`）。
iOS 无 provider，改为 **App 自有目录暂存 + 原子改名为成片**：

| 步骤 | Android | iOS 落点 |
|---|---|---|
| 生成句柄 | `VideoStore.createPending`（写 `.pending` 行 + 空间门槛） | `LibraryFileStore.makePendingHandle(relativeDirectory:displayName:)`（`:223`）：`displayName` 先过 `sanitizedFileName`，再复核目录归属与叶子软链；撞名/非法返回 nil |
| 暂存名 | 由 provider 生成 `.pending-<id>-<name>` | `MediaPendingName.stagingFileName(displayName:processId:)` → `.pending-<段数字>-<name>`；段数字 = 进程号×百万桶 + 进程内序号（`nextPendingId()` `:210`），同一进程并发句柄互异 |
| 发布 | `VideoStore.commit`（async 复原名） | `LibraryFileStore.publishResult(handle:)`（`:295`）＝ 同目录 `moveItem` 改名；句柄身份按「最终名 + pendingId」重算暂存名校验（`isCurrentStagingName` `:281`），**目标存在抛 `targetExists`**；改名成功后即使读不到落盘属性也按「已保存」回报，属性缺失进 `attributeWarning`（`:322-336`） |
| 失败/取消 | `VideoStore.discard` | `LibraryFileStore.cancel(handle:)`（`:341`，仅删身份校验通过的当前句柄暂存；叶子软链拒绝） |
| 空间门槛 | `VideoStore.checkFreeSpace` → `LOW_STORAGE:<mb>`（`:73-77`） | **不在本层**：起录前空间校验归录制层（B2）；见「接口待办」 |

关键：**文件先成功改名，再写索引**（`MediaLibrary.commit`：`publishResult` 成功后才 `adoptIntoIndex`，
`MediaLibrary.swift:156`）。发布失败时暂存文件保留、由冷启维护处置，绝不覆盖既有成片；
改名成功但索引写入失败时按「已保存 + 索引重试提示」回传真实可播放路径。

## 4. 收藏 / 标签

| ID | 行为 | Android | iOS 落点 |
|---|---|---|---|
| D1-020 | 收藏切换（`like`） | `MediaActions.setLike`（`:57`） | `MediaLibrary.setLike(mediaId:liked:)`（`:174`） |
| D1-021 | 加/删自定义 tag | `addTag`/`removeTag`（`:67,76`） | `addTag(mediaId:rawTag:)`/`removeTag(mediaId:tag:)`（`:178,186`） |
| D1-022 | tag 归一化（≤24、去空白、保留词拒绝） | `MediaTag.normalize`（`MediaTag.kt:22`） | `MediaTagSpace.normalize(_:)`（`MediaItem.swift:42`），非法返回 nil 不静默截断 |

## 5. 回收站 / 还原 / 永久删除（平台无等价，取舍见 §10）

Android：API30+ 直写 `IS_TRASHED`，API29 回退「本地 `trash_item` 行 + 从常规页签隐藏」
（`MediaRepo.kt:598 SYSTEM_TRASH_SUPPORTED`、`MediaActions.kt:166 moveToTrash` / `:177 restoreFromTrash`）。
iOS 无跨 App 回收站，采用 **App 自有目录回收站 = 索引标记**（等价 API29 回退路径）：

| 步骤 | 行为 | iOS 落点 |
|---|---|---|
| 进回收站 | 写 `LibraryTrashItem` 行；**文件仍原地**，常规页签过滤掉 | `MediaLibrary.moveToTrash(mediaIds:)`（`:246`） |
| 还原 | 删该行；文件从未移动 ⇒ **天然不覆盖，无覆盖可能** | `MediaLibrary.restoreFromTrash(mediaIds:)`（`:274`） |
| 永久删除 | 仅删**通过 `isOwned` 校验**的自有成片 + sidecar，再清标签/系列/回收站行；逐条计成功/失败 | `MediaLibrary.deleteForever(mediaIds:)`（`:291`）→ `LibraryFileStore.delete(entry:)`（`:368`） |
| 分享排除回收站 | `MediaActions.kt:360` | 由 UI 层按 `isTrashed` 过滤（本层提供 `MediaClipFiltering`，`MediaItem.swift:161`） |

删除红线落点：`LibraryFileStore.delete`：`isOwned` 校验失败抛 `notOwned`（`:369`）；文件不存在视为幂等已删
（`:370-373`）；删除失败抛错、**调用方不得据此更新索引**。清扫里的删档案走
`removeOwnedFile(at:)`（`:383`，非自有文件拒绝并记日志）。**不递归删未知文件**：只对自有目录**直接子文件**、
且名字命中自有形态者动手（`isOwned` `:121`，含 `mediaRoot` 归属复核 `:122`；`enumerateOwnedEntries` `:142`）。

`displayName` / 索引行 → 绝对路径只有一个出口 `LibraryFileStore.ownedFileURL(relativeDirectory:fileName:)`（`:91`）：
①相对目录必须是自有三目录之一；②文件名必须是安全的 POSIX 单段名（`isSafeComponent` `:105`：非空、非 `.`/`..`、
不含 `/` 与 NUL）；③`isPath` 复核目录归属。**磁盘上已有的名字不套用户输入的 `sanitizedFileName`**
（后者还修剪首尾空白、拒 `\`，会在 APFS 上把真实文件误判成不存在）；写入/搬动类入口
（`makePendingHandle` / `publishResult` / `cancel` / `rename` / `moveSidecar`）另做叶子软链判定
（`fileExists` 跟随软链，悬空软链会报「不存在」），拒绝在软链上写字节或搬成目标。

## 6. 重命名 + sidecar 跟随（目标存在绝不覆盖）

Android `MediaActions.rename`（`:86`）先 `ensureSuffix`，改 DISPLAY_NAME，再回查 DATA 更新
`movePathAfterRename`，并 `ArcDropLog.renameFor` 跟随 sidecar（`ArcDropLog.kt:163`）。

| 步骤 | iOS 落点 |
|---|---|
| 名消毒（拒 `/`、`\`、`.`、`..`、空） | `MediaStorageLayout.sanitizedFileName(_:)`（`:120`） |
| 保证后缀不变 | `MediaStorageLayout.ensuringSuffix(_:like:)`（`:129`） |
| 改名（**目标已存在抛 `targetExists`**） | `LibraryFileStore.rename(entry:to:)`（`:289`，覆盖保护 `:302-304`，含悬空软链） |
| sidecar 跟随（**目标已存在不覆盖，宁留原名**） | `LibraryFileStore.moveSidecar(fromVideo:toVideo:)`（`:326`，保护 `:331-333`，含悬空软链） |
| 身份重绑（保留 mediaId ⇒ 标签/系列不失联） | `MediaLibraryMaintenance.remapIdentity(oldRelativePath:newEntry:)`（`:326`） |

顺序：**文件改名成功后才重绑索引**（`MediaLibrary.rename` `:219-242`）。撞名后缀由调用方按真实名回查决定，
本层不做隐式加序号（与 Android 不自动改名同口径）。

## 7. 冷启动维护（等价 `StalePendingSweep`，三趟）

Android `StalePendingSweep.runOnce`：年龄门槛 `SWEEP_STALE_MS=60*60*1000`（`:45`）、
条目上限 `SWEEP_MAX_ENTRIES=500`（`:48`）、索引轮上限 `SWEEP_MAX_INDEX=40`（`:51`）、
成片永不删（`:75`）、归位不覆盖、`rescuePendingRows`（`:293`）、`sweepPlan` 纯表（`:439`）。

iOS 等价：`MediaLibraryMaintenance.swift`（由 `MediaLibrary.runMaintenance` `:85` 在串行队列调用，
入口 `MediaLibrary.runStartupMaintenance` `:76` 与业务共用 `.shared` 实例），进程内只跑一次（`markRunOnce()` `:45`）。

| 趟 | 行为 | 约束落点 |
|---|---|---|
| 1. 成片/sidecar pending 清扫（仅 Camera） | 剥 pending 段、按 `sweepPlan` 改名/删重复档案；**名字配对看全量目录，500 只限本轮处理条数** | `sweepStalePending`（`:84`）；动作窗口 500 `:90`；全量名集合 `:91`；纯表 `MediaSweepPlanner.plan`（`MediaSweepPlanner.swift:53`） |
| 2. pending 成片救援（三目录） | 年龄 ≥1h 且**可提取**才归位到干净名 | `rescuePendingFiles`（`:220`）；年龄门 `:230`；可播探测 `:232`；上限 40 `:223,226` |
| 3. 库一致性对账 | (a) 删「行在文件不在」坏行（年龄门）；(b) 给「有文件没行」成片补入库 | `reconcileIndex`（`:247`）；(a)`:256-266`；(b)`:279-289`，上限 40 `:282` |

验收点逐条锚：

- **1h 门槛**：`MediaSweepPlanner.staleMilliseconds = 3_600_000`（`MediaSweepPlanner.swift:36`），用于 `:62`（`plan`）、
  `MediaLibraryMaintenance.swift:113`（成片探测受年龄门约束）、`:230`（救援）、`:262`（坏行年龄门）。
- **条目上限 500 / 索引轮 40**：`MediaSweepPlanner.swift:39,42`，用于 `MediaLibraryMaintenance.swift:90,223,226,282`。
  **500 只限制动作条数**：配对用的 `directoryNames` 取全量枚举（`:91`），不取 `prefix(500)`。
- **侧车无主判定以磁盘为准**：`.deleteArchive`（`MediaLibraryMaintenance.swift:148-160`）在「找不到成片」时先落盘探测
  （`firstExistingClip` `:205`，候选名由 `MediaSweepPlanner.sidecarClipProbeNames` 统一给出：剥段基名 / `.mp4` / `.mov`），
  命中即跳过删除。`fileExists` 跟随软链、按卷的大小写规则匹配，避免窗口截断与 `Set` 大小写差异造成误删；
  已有干净同名档案（`twin`）的重复件不再重复探测。坏 `.drops.json` 内容不参与判定：不解 JSON、不因解析失败删档案。
- **成片永不删**：`MediaSweepPlanner.plan` 中成片只有 `.leave` / `.renameClip`（`MediaSweepPlanner.swift:72-74`），
  无删成片分支；`MediaLibraryMaintenance.applyClip`（`:108-135`）只做改名，从不删成片字节。
- **目标存在绝不覆盖**：`MediaLibraryMaintenance.swift:127`（成片归位前查目标）、`:164`（档案归位前查目标，
  存在即留原名）、`:235`（救援前查目标）；底层再经 `LibraryFileStore.publishResult/rename/moveSidecar` 二次保护。
- **删档案仅限自有**：`removeOwnedFile(at:)`（`LibraryFileStore.swift:383`），非自有拒绝。
- **坏索引行不驱动文件操作**：索引行的路径一律经 `LibraryFileStore.ownedFileURL`（`:91`）重建，
  目录越界/文件名带分隔符的行按「不存在」对待（`MediaLibraryMaintenance.swift:256`、`MediaLibrary.swift:403,479`）。
- **不递归删未知文件**：全程 `contentsOfDirectory` 直接子层 + 名形态匹配（`LibraryFileStore.swift:142-154`），无 `enumeratorAt`/递归删除。

## 8. 系列 / 分部

| 项 | Android | iOS 落点 |
|---|---|---|
| 落库门槛（≥2 段才成系列） | `SeriesStore.record`（`:58`）/`MIN_SERIES_PARTS=2`（`:15`） | `MediaLibrary.recordSeries(seriesId:startedAtMs:parts:)`（`:323`），<2 段不写 |
| 归组展示（≥2 才折叠） | `SeriesGroup.galleryEntries`（`:74`）/`MIN_GROUP_SIZE=2`（`:69`） | `MediaSeriesGrouper.galleryEntries(_:collapse:)`（`MediaSeriesGrouper.swift:57`） |
| 组级动作扇出 | `SeriesGroup.expandSeriesTargets`（`:137`） | `MediaSeriesGrouper.expandSeriesTargets(picked:all:)`（`:120`） |

## 9. 对比历史

Android `CompareHistory`：SharedPreferences 单键 `compare_history`、上限 12、按 `media:<id>`/`uri:<uri>` 去重
（`CompareHistory.kt:20,23,77`）。iOS 落点为索引内的 `compareHistory[]`（`MediaCompareHistory.swift`，
`maxEntries=12` `:33`；`record` `:77`；`remove` `:98`；JSON 编解码 `:36,56`，`JSONSerialization` 手写字典，不用 `Codable`）。
`MediaLibrary.compareHistory/recordCompare/removeCompare`（`:364,372,385`）。

## 10. 平台差异与取舍

| ID | Android | iOS 取舍 | 判定 |
|---|---|---|---|
| D1-PD-01 | MediaStore `IS_PENDING`/MediaScanner | App 自有目录 `.pending-<pid>-` 暂存 + 原子改名发布 | `保留（平台差异）` |
| D1-PD-02 | Room `wota_media.db` | 单文件 JSON 索引（原子写 + 坏档改名备份） | `保留（平台差异）` |
| D1-PD-03 | 系统回收站 `IS_TRASHED`/API29 回退表 | App 自有目录回收站（索引标记隐藏，文件不动，还原天然不覆盖） | `保留（平台差异）` |
| D1-PD-04 | FileProvider `external-path` | 无 FileProvider；分享用系统分享面板，临时件限沙盒 | `保留（平台差异）`（分享归 UI/分享任务） |
| D1-PD-05 | MediaStore 列直读 DURATION/WIDTH/HEIGHT | 对自有文件做 AVAsset 一次性探测并缓存入索引（`MediaItemAssembler`） | `保留（平台差异）` |
| D1-PD-06 | 空间门槛 `LOW_STORAGE` 在 VideoStore 起录前校验 | 属录制层（B2）职责，数据层不复制 | 接口待办（见 §11） |
| — | AI 站位（`stance/*`） | 不实现、不移植、不留占位 | `排除（AI，不移植）` |

## 11. 接口契约（供 B2 录制 / E1 播放接入）

所有写/查都在 `MediaLibrary` 的**单一串行队列**（`MediaLibrary.swift:49`）执行；**完成回调一律回主队列**
（`deliver(_:_:)` `:531`）。无 async/await / Combine / SwiftUI。

入口：`MediaLibrary.shared`（容器默认 `Documents`，`MediaLibrary.swift:52`）；
`MediaLibrary(containerURL:)`（测试/自定义容器）。**索引是「每实例一份内存快照 + 整表原子写」**，
所以生产路径只能有一个长期存活的实例（`.shared`）：自定义容器仅供测试，须自建、自持，并让维护与业务共用它。

| 分组 | 签名 |
|---|---|
| 启动维护 | `static func runStartupMaintenance(on:completion:)` `:76`（默认 `on: .shared`，**不再自建实例**）；`func runMaintenance(completion:)` `:85` |
| 列表/查询 | `func list(filter:completion: ([MediaItem]) -> Void)` `:97`；`func item(mediaId:completion: (MediaItem?) -> Void)` `:108`；`func galleryEntries(filter:completion: ([MediaGalleryEntry]) -> Void)` `:118` |
| pending | `func beginPending(displayName:completion: (MediaPendingHandle?) -> Void)` `:133`；`func commit(_:completion: (MediaCommitOutcome) -> Void)` `:146`；`func cancelPending(_:completion: (Bool) -> Void)?` `:164` |
| 收藏/标签 | `setLike(mediaId:liked:completion:)` `:174`；`addTag(mediaId:rawTag:completion:)` `:178`；`removeTag(mediaId:tag:completion:)` `:186` |
| 重命名 | `rename(mediaId:newName:completion: (MediaRenameOutcome) -> Void)` `:219` |
| 回收站 | `moveToTrash(mediaIds:completion:)?` `:246`；`restoreFromTrash(mediaIds:completion:)?` `:274`；`deleteForever(mediaIds:completion: (MediaDeleteReport) -> Void)` `:291` |
| 系列 | `recordSeries(seriesId:startedAtMs:parts:completion:)` `:323` |
| 对比历史 | `compareHistory(completion: ([MediaCompareEntry]) -> Void)` `:364`；`recordCompare(rightId:rightUri:leftId:completion:)?` `:372`；`removeCompare(key:completion:)?` `:385` |

返回类型：`MediaCommitOutcome{.published(MediaItem)/.failed(String)}`；`MediaRenameOutcome{.renamed(MediaItem)/.failed(String)}`；
`MediaDeleteReport{deleted:Int, failed:Int}`；`MediaSeriesPartInput{partIndex:Int, mediaId:Int64, path:String, durationMs:Int64}`。

**`MediaItem` 字段**（`MediaItem.swift:80`）：`mediaId:Int64`（0=外来来源，不入库）、`fileName`、
`relativeDirectory`、`absolutePath:String?`、`sizeBytes:Int64`、`durationMs:Int64`、`width/height:Int`、
`mimeType`、`createdAtMs:Int64`、`tags:Set<String>`、`isTrashed:Bool`、`seriesId:Int64?`、`partIndex:Int?`、
`seriesParts:Int`；计算属性 `liked`、`relativePath`、`fingerprint`、`identityKey`。

**集成待办（交后续任务，不在本节实现）**：

1. 录制层（B2）起录前空间校验（`LOW_STORAGE`）与调用 `beginPending/commit/cancelPending` 的时序；
2. 分段录制的 `seriesId` 由录制层用 `SeriesIDGenerator` 生成后经 `recordSeries` 落库；
3. App owner 启动回调需调用一次 `MediaLibrary.runStartupMaintenance { report in … }`（默认 `on: .shared`；
   **不要**自己 `MediaLibrary(containerURL:)` 再造一个实例跑维护，两份索引快照会互相覆盖）；本层不修改 `App/` 文件，仅提供入口；
4. 列表/回收站页签 UI（G1）按 `MediaClipFilter` 与 `isTrashed` 呈现；分屏/对比 UI 归 E1/E2。

## 12. 人工验证清单（真机，未执行）

- MV-D1-01 pending：录制中强杀 App → 重新进入（触发维护）→ 半截 `.pending-*` 文件**不被删**（打不开则原样留）。
- MV-D1-02 pending 发布：正常录停 → 目录内出现干净成片名，索引内出现该条（无 `.pending-` 残留）。
- MV-D1-03 归位不覆盖：手工造一个与归位目标同名的成片 → 维护**不覆盖**、原名保留。
- MV-D1-04 收藏/标签：加/删 tag 后杀 App 重进，标签仍在；`>24` 字/保留词被拒。
- MV-D1-05 重命名：改名后 sidecar 跟随；目标名已存在时**拒绝改名**并保原名。
- MV-D1-06 回收站：进回收站后常规页签不显示；还原后复原；永久删除只删自有成片+sidecar，外部文件仍在。
- MV-D1-07 坏索引：手工写坏 `wota_media_index.json` → 重进后生成 `.corrupt-<ts>.json` 备份，媒体文件未被删。
- MV-D1-08 系列：一次录制 2+ 段后归为一组；单段不折叠。
- MV-D1-09 侧车归属（P1-08）：`Camera/` 造 600+ 条目，令 `.drops.json` 落在枚举前 500、其成片落在 500 之后 →
  冷启维护**不删**该侧车；再造成片真的不存在的侧车 → 仍会被清理（无主档案不因修复而永远留存）。
- MV-D1-10 索引单一 owner（P1-07）：冷启维护刚开始就进相册收藏/改名 → 维护结束后该修改仍在；连续两次冷启不重复 `adopt`。

## 13. 验证状态（本机 Windows，无 Apple 工具链）

- 源码已落，**Apple 编译/归档未执行**：本机 `command -v xcodebuild` 与 `command -v swift` 均不存在（见 `iOS/README.md`）。
  不以结构检查替代编译，不声称「编译通过」。
- **未写测试、未跑模拟器、未截图**（用户指示）。
- 已做的静态核对：本层新增文件不含禁令牌（`import SwiftUI`/`Combine`/`@MainActor`/`UTType`/` await `/`Task {` 等，见
  `scripts/check_project.py`），不含 `stance`；关键分支（1h/500/成片不删/目标不覆盖/仅自有删除/原子索引）有源码落点（见 §7、§5）。
- 工程侧：新增 `WotaGeiCam/Library/*.swift` 需在生成工程时纳入 Sources（`scripts/generate_project.py` 自动发现），
  由主代理重跑生成脚本，不在本任务写入工程文件。

## 14. 静态审查整改（docs/reviews/ios2026-11-19，2026-11-19）

只改源码与本文，**未执行 Apple 编译/归档/真机验证**（不同证据类型，不互相冒充）。

| 审查项 | 整改落点 |
|---|---|
| P1-02 缺 `import WotaCore` | `LibraryIndexModels.swift`、`MediaLibraryPaths.swift` 顶部补 `#if canImport(WotaCore) import WotaCore #endif`（与其余 27 个 App 文件同款；兼容 `--core-mode inline`）。全 App 重扫后仅剩 `UpdateNetwork.swift` 的注释命中，不需要 import |
| P1-06 路径安全 | `makePendingHandle` 消毒 `displayName` 并复核目录归属/软链；`publishResult`/`cancel` 用 `isCurrentStagingName` 重算暂存名校验句柄身份、`ownedFileURL` 再验路径；`rename`/`moveSidecar` 目标位补软链判定；`isOwned` 增加 `mediaRoot` 归属复核；索引行 → 路径统一走 `ownedFileURL`（`MediaLibrary.swift:403,479`、`MediaLibraryMaintenance.swift:256`） |
| P1-07 索引双实例 | `runStartupMaintenance(on:completion:)` 默认 `on: .shared`，不再在维护入口新建实例（签名由 `containerURL:completion:` 改为 `on:completion:`；仓内无调用方，B2/E1 未使用该入口） |
| P1-08 侧车窗口误判 | `sweepStalePending` 用全量目录名配对（`:91`），500 只限动作条数；`.deleteArchive` 判「无主」前用 `firstExistingClip`（`:205`）落盘探测，候选名由 `MediaSweepPlanner.sidecarClipProbeNames` 统一给出；不解 `.drops.json` 内容，不因 JSON 损坏删档案 |

