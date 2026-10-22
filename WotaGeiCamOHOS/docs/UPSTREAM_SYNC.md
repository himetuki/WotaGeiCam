# 上游同步流程（最少步数适配）

## 分支模型

```
origin/main ──fetch──▶ main（本地只读镜像，不开发）
                            │
                            └─merge──▶ harmony（所有改动只在 WotaGeiCamOHOS/ 下）
```

`harmony` 分支已配置 `branch.harmony.remote=origin`、`branch.harmony.merge=refs/heads/main`，
所以在 harmony 分支上 `git pull` 就是「拉取并合并上游 main」，一步完成同步；
`git status` 会以 origin/main 为基准显示 ahead/behind，直接反映与上游的漂移量。

## 黄金规则（保证零冲突）

1. **只新增，不修改**：harmony 分支上的所有改动只发生在 `WotaGeiCamOHOS/` 目录内，
   永不改动上游已有文件（`WotaGeiCamApp/`、`README.md`、`.gitattributes` 等）。
2. **确需改上游时走上游**：给 [himetuki/WotaGeiCam](https://github.com/himetuki/WotaGeiCam) 提 PR，
   合并后随下一次同步自然进入本地，避免本地分叉。
3. **共享语义只用文档锚定**：鸿蒙侧与上游共享的格式/判据/命名（见下方检查清单），
   在 [PORT_MAP.md](PORT_MAP.md) 里登记对应关系，上游一变就能定位到鸿蒙侧要跟的文件。

违反规则 1 的那一刻起，merge 就可能出现冲突，同步成本从「1 条命令」涨到「手工解冲突」。

## 日常同步

方式一（等价）：

```bash
git switch harmony
git pull            # = fetch origin + merge origin/main
```

方式二（带前置检查，推荐）：

```bash
bash WotaGeiCamOHOS/scripts/sync-upstream.sh
```

## 合并后检查清单（语义锚点）

merge 无冲突不代表语义同步——以下上游文件定义了与鸿蒙侧共享的**格式与判据**，
每次同步后先看它们有没有变，变了再按映射表跟进鸿蒙侧：

```bash
git diff ORIG_HEAD..origin/main --stat -- <下列路径>
```

| 语义锚点（上游路径，WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ 下） | 变更波及 |
| --- | --- |
| `record/ArcDropLog.kt` | `.drops.json` 文件格式，鸿蒙侧读写与其对齐 |
| `record/ArcKeepRule.kt` | 补弧合并判据（整像素选择 + 差分死区、并入门槛默认 12 / 0–32） |
| `record/ArcRepairPlan.kt`（ArcRepairFlow 在线路） | 抽帧状态机指令序 Hold/Fold/Emit/Merge（C++ `cpp/arc_flow.*` 逐行对拍） |
| `camera/Shaders.kt`（ARC_MERGE_FS） | 补弧整像素选择片元（严格大于死区取被抽帧；`cpp/shaders.h` 逐 token） |
| `camera/ArcMendPass.kt` | 补弧离屏拓扑 cur+pending×2+acc×2 与指令原语（`cpp/arc_mend_pass.*`） |
| `player/FrameStep.kt` | 帧步进纯帧号算术（1.5ms 吸附/floor+1/ceil−1；`player/FrameStep.ets`） |
| `player/TapArbiter.kt` | 单击/双击 280ms 仲裁（`player/TapArbiter.ets`） |
| `player/PlayerEngine.kt` | AVPlayer 封装/AB 会话循环/倍速/120ms 轮询（`player/PlayerEngine.ets`） |
| `player/PlayerScreen.kt` | 播放页控制层/手势/缩放平移（`pages/Player.ets`） |
| `player/CompareTimeline.kt` | 对比时间线域判定/主钟/决策指令（交接不 seek、250ms 硬 seek、末段回绕 Tmin；`player/CompareTimeline.ets`，纯逻辑单测 25 条） |
| `player/AudioSync.kt` | 自动同步 DSP（重采样/包络/粗对齐/FFT 精修与置信度；`player/AudioSync.ets`，常量与算法逐项对拍） |
| `player/CompareAudio.kt` | 对比音频轨序（环境=组 0/内录=组 1）与静音/override 命令顺序（`player/CompareAudio.ets`） |
| `player/CompareHistory.kt` | 对比历史 JSON 契约（`[{"rightId","leftId","ts"}]`、rightId 去重/12 条上限；`player/CompareHistory.ets` 纯核心 + `player/CompareHistoryStore.ets` preferences 适配） |
| `player/TrackSync.kt` | 双音轨偏移合成（内容差 + 首样本容器差）、低置信度归零、失败分类（`player/TrackSync.ets`） |
| `record/AudioFeeder.kt` | PCM 泵契约：帧宽/读块/整缓冲算式、静音锁存与逐段账（`record/AudioProbe.ets` / `record/CaptureSilence.ets`） |
| `record/ArcRepairPlan.kt` | 离线修复抽帧计划与流式指令序（有理数保留集/PTS 网格；`record/ArcRepairPlan.ets` / `record/ArcRepairFlow.ets`，C++ 侧 `cpp/arc_flow.*`） |
| `record/ArcRepairRunner.kt` | 修复成功/可保留判据（三格 + stop 门槛；`record/ArcRepairVerdict.ets`，执行器 T29） |
| `record/CodecRecorder.kt` | 内录音轨收尾判据：静音双门槛（2s 收尾/5s 巡检）、fedMsOf、采样率/声道档位（`record/CaptureSilence.ets` / `record/AudioProbe.ets`） |
| `record/FrameLedger.kt` | 录制帧账输出格式 |
| `record/BitratePolicy.kt`、`record/AutoStopRules.kt` | 码率策略、3.5 GiB 分段轮转与自停判据 |
| `record/SeriesIds.kt`、`record/PendingName.kt` | 段命名/系列命名规则，跨平台素材互认的关键 |
| `media/WotaDb.kt` | 媒体库 schema（鸿蒙侧 relStore 建表需同步迁移） |
| `core/Constants.kt`、`core/WotaParams.kt`、`core/CurveModel.kt` | 参数键名、RGB 曲线模型 |
| `player/ClipExport.kt` | 剪辑导出选轨/零样本轨规则 |
| `record/PlaybackCaptureController.kt` | 双音轨（环境音+内录）轨位布局 |
| `diag/Diag.kt` | 导出件格式/轮转规则/崩溃件命名（鸿蒙侧 `diag/DiagPure.ets`） |

流程：有变更 → 在 PORT_MAP.md 找到该上游文件对应的鸿蒙落点 → 同步语义 →
在 PORT_MAP.md 中更新登记（注明对齐到的上游 commit 短哈希）。

## 把 harmony 推到自己的远端（可选）

harmony 是本地分支，不影响同步；如需备份或协作：

```bash
git remote add fork <你的仓库地址>
git push -u fork harmony
```
