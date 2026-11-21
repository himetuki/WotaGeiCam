# 上游同步流程（Android → iOS：只读对比 + 人工移植）

## 现状（单树 monorepo）

仓库是**单树 monorepo**：main 一棵树同时管 Android / HarmonyOS / iOS，没有 bare 总仓库与多 worktree。

```
WotaGeiCam\              # 仓库根（main 一棵树）
├── Android\             # 安卓工程（上游权威 = origin/main）
│   └── WotaGeiCamApp\   # Gradle 根：app/src/main/java/com/wotagei/cam/ ...
├── HarmonyOS\
│   └── WotaGeiCamOHOS\  # 鸿蒙移植落点
└── iOS\                 # iOS 移植落点（本工程）
    └── docs\mappings\   # 映射真源（features/settings/storage/pages/platform-differences/manual-verification）
```

- **上游权威是 `origin/main`**（GitHub `himetuki/WotaGeiCam` 的 main）。本地 `Android/` 只是工作副本，
  可能滞后；读最新上游一律用 `git show origin/main:Android/WotaGeiCamApp/<path>`。
- **同步不做 merge**（不 merge 上游历史，也不做目录级 merge）。固定流程：
  **读 diff → 在 `iOS/` 手工移植 → 更新映射表与 `UPSTREAM_BASE`**。
- 本工程**只写 `iOS/` 内**成果，不改写既有提交、不修改 `Android/` 与 `HarmonyOS/`。

## 基线

- 初始只读基线：**`7394187`**（本映射与同步文档的评审起点）。
- 基线与同步进度记录在 `iOS/docs/UPSTREAM_BASE`（单个完整 SHA；不存在时按 `origin/main` 处理）。
- 同步脚本（可选）：只做 `git fetch --prune origin` + 打印 `UPSTREAM_BASE → origin/main` 的新提交与
  `--stat` + 打印语义锚点路径的 `--stat` + 可选更新 `UPSTREAM_BASE`。**不 merge、不提交、不改工作树。**

```bash
# 日常只读对比（脚本不会合并）
bash iOS/scripts/sync-upstream.sh                 # 只对比：新提交 + 锚点 diff
bash iOS/scripts/sync-upstream.sh --update-base   # 评审/移植完成后更新基线
```

> 若 `iOS/scripts/sync-upstream.sh` 尚未落地，用下节「手工等价命令」执行同样的只读对比。
> 该脚本若存在，也**只做只读对比**，与 HarmonyOS 的 `sync-upstream.sh` 同口径。

## 手工等价命令

```bash
git fetch --prune origin
git log --oneline "$(cat iOS/docs/UPSTREAM_BASE)"..origin/main
git diff --stat "$(cat iOS/docs/UPSTREAM_BASE)"..origin/main -- Android/WotaGeiCamApp
git show origin/main:Android/WotaGeiCamApp/<path>     # 读上游某文件当前内容
```

- 基线 SHA 在仓库里不存在（历史被重写/浅克隆）时：以 `origin/main` 为基线重新评审。
- 只对 `Android/WotaGeiCamApp`（必要时含 `deliverable/` 的 camera-knowledge）做对比；
  `iOS/`、`HarmonyOS/` 不在上游对比面内。

## 语义锚点（变了就按映射表人工移植）

每次同步先看下列上游路径有没有变；变了就在 `iOS/` 对应落点人工移植，并更新映射表登记
（注明对齐到的上游 commit 短哈希）。路径均在
`Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/` 下。

| 语义锚点（上游相对路径） | 波及 iOS 映射 |
|---|---|
| `core/CameraAbility.kt`、`camera/RequestApplier.kt`、`core/WotaParams.kt` | features.md F-001/F-002/F-003；manual-verification.md MV-001…MV-011 |
| `core/Constants.kt`（WotaTiers）、`record/RecordProfile.kt`、`record/AutoStopRules.kt` | features.md F-005/F-006；storage.md ST-012/ST-013 |
| `record/VideoStore.kt`、`record/PendingName.kt`、`record/SeriesIds.kt` | features.md F-007/F-008；storage.md ST-001…ST-010 |
| `record/StalePendingSweep.kt` | features.md F-009；storage.md ST-040…ST-047（归位不覆盖） |
| `record/CodecRecorder.kt` | features.md F-004；platform-differences.md PD-001（内录） |
| `record/ArcRepairRunner.kt`、`record/ArcDropLog.kt` | features.md F-010；storage.md ST-011 |
| `media/WotaDb.kt`、`media/MediaActions.kt`、`media/GalleryScreen.kt`、`ui/GalleryEntryScreen.kt` | features.md F-012/F-013/F-014；storage.md ST-020…ST-032；pages.md P-004/P-037…P-045 |
| `player/PlayerEngine.kt`、`player/PlayerScreen.kt` | features.md F-015；pages.md P-005/P-025…P-033 |
| `player/ClipExport.kt`、`player/TrackExport.kt`、`player/SceneExportRunner.kt`、`player/SceneExportPlan.kt` | features.md F-016/F-017/F-018 |
| `core/AudioAlign.kt`、`player/TrackSync.kt`、`player/PostStopSync.kt` | features.md F-019（内录分支见 PD-001） |
| `player/CompareScreen.kt`、`player/CompareTimeline.kt`、`player/CompareHistory.kt` | features.md F-020；pages.md P-006/P-034…P-036 |
| `ui/SettingsScreen.kt`、`ui/ParamsPersist.kt`、`res/values/strings_settings.xml` | settings.md 全部；features.md F-011 |
| `ui/HudLayout.kt`、`ui/HudLayoutEditor.kt`、`ui/HudLayer.kt`、`ui/CameraPills.kt`、`ui/CameraScreen.kt` | features.md F-022；pages.md P-010…P-018/P-046 |
| `diag/Diag.kt` | features.md F-023；settings.md S-063/S-064 |
| `update/UpdateChecker.kt`、`update/UpdateDownloader.kt`、`update/UpdateInstaller.kt` | features.md F-024；platform-differences.md PD-003 |
| `bt/BtSpeakerController.kt`、`ui/dialog/BtSpeakerPanel.kt` | features.md F-025；platform-differences.md PD-004 |
| `backup/PortableUserData.kt`、`backup/WotaBackupAgent.kt`、`res/xml/backup_rules.xml`、`data_extraction_rules.xml` | features.md F-026；storage.md ST-033…ST-036；platform-differences.md PD-005 |
| `app/src/main/AndroidManifest.xml`、`res/xml/file_paths.xml` | features.md F-027；storage.md ST-050；platform-differences.md PD-007/PD-008 |
| `stance/StanceUi.kt`、`full/java/.../stance/*`、`lite/java/.../stance/StanceFeature.kt`、`full/assets/model_pack/*`、`full/assets/voice/*` | **AI 排除**：不移植，仅确认 iOS 侧仍无对应符号 |

## 流程（每次同步）

1. `git fetch --prune origin`，列 `UPSTREAM_BASE → origin/main` 的新提交与 `--stat`。
2. 只对**语义锚点**路径看 `--stat`；空输出 = 无需跟进。
3. 对每个有变更的锚点：
   - 在 `iOS/docs/mappings/` 找到对应映射行（上表），按落点人工移植；
   - 平台无等价的变更**不静默删减**，登记到 `platform-differences.md` 并标 `待实现（平台无等价，需裁决）`；
   - AI 相关变更**一律忽略**，不建落点。
4. 更新映射表登记（对齐到的上游 commit 短哈希）；评审完队列后再 `--update-base` 推进基线。
5. **分步本地提交**：每次提交只 stage 本任务的 `iOS/` 文件（不使用根级 `git add -A`），
   一条提交对应一次语义移植/文档更新，便于回溯。**不推送**（除非用户显式要求）。

## 边界与禁止事项

- **不做 merge**：不 merge 上游历史，也不对 `Android/`/`HarmonyOS/` 做目录级 merge。
- **不改上游**：不修改 `Android/`、`HarmonyOS/` 下任何文件；Android 侧改动由 Android 侧提交到 main。
- **不翻译 GL/GLSL**：Android 的 GL/GLSL 只作算法参考，iOS 侧用 Metal/Swift **重写**。
- **语义非等价**：不假定 iOS 相机 API 与 Camera2 语义等价；每参数有支持判断与降级路径。
- **命名/目录对齐**：不引入 Android 中不存在的隐式媒体库行为；命名与目录与 Android 对齐。
- **AI 排除**：AI 站位不实现、不移植、不保留占位接口。
- **禁止推送**：除非用户显式强调，任务结束不推送到远端。
- **隐私**：不读 `.env`、密钥、账户、签名等隐私配置；提交前防隐私泄露。

## 与 HarmonyOS 同步流程的关系

同一 mono 树的两条移植链（`HarmonyOS/` 与 `iOS/`）共用「读 diff → 人工移植 → 更新基线」方法，
但**各自独立**：参考 `HarmonyOS/WotaGeiCamOHOS/docs/UPSTREAM_SYNC.md` 作为方法模板即可，
不共享 `UPSTREAM_BASE` 文件，也不交叉 merge。
