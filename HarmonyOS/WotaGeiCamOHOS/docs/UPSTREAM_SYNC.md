# 上游同步流程（bare 总仓库 + worktree：只读对比 + 人工移植）

## 现状（2026-10-22 迁入 worktree 后）

根目录 `E:\22600\Agent\ZCode\WotaGeiCam\` 是 **bare 仓库**，两个 worktree 各管一端：

```
WotaGeiCam\              # bare 仓库（只有 .git；不直接编辑）
├── Android\             # worktree: main  → 上游安卓工程（只读参照）
└── HarmonyOS\           # worktree: harmony → 本工程（OHOS-only）
    └── WotaGeiCamOHOS\
```

- **上游权威是 `origin/main`**（github.com/himetuki/WotaGeiCam 的 main）。
  `Android/` 只是工作副本，可能滞后；读最新上游一律用
  `git show origin/main:WotaGeiCamApp/<path>`。
- `harmony` 与 `origin/main` **没有共同祖先**（OHOS-only 独立历史）。
  **永不 merge / pull `origin/main`**：合并没有意义，还会把安卓目录整棵带回。
- harmony 的入库路径：`WotaGeiCamOHOS/`、`.github/workflows/build-hap.yml`、
  `LICENSE`、`.gitattributes`、`.gitignore`。安卓目录/安卓 CI 只存在于 `main`。
- 同步方式固定为：**读 diff → 在 `WotaGeiCamOHOS/` 手工移植 → 更新 PORT_MAP 与 UPSTREAM_BASE**。

## 基线文件

`WotaGeiCamOHOS/docs/UPSTREAM_BASE` 记录"上次评审/对齐到的上游提交"（单个完整 SHA）。
同步脚本以它为起点列出新提交；评审并移植完成后执行 `--update-base` 更新，
**与本次移植改动一起提交**。

当前基线：`38d712a`（T31 批次对齐的上游提交）。之后 main 上的新提交会在下次同步时列出。

## 日常同步（只读，脚本不会合并）

```bash
# 在 HarmonyOS worktree 的任意目录
bash WotaGeiCamOHOS/scripts/sync-upstream.sh                 # 只对比：新提交 + 锚点 diff
bash WotaGeiCamOHOS/scripts/sync-upstream.sh --update-base   # 评审/移植完成后更新基线
```

脚本只做四件事：

1. `git fetch --prune origin`（只更新远端跟踪引用，shared bare 仓库全局生效）；
2. 打印 `UPSTREAM_BASE → origin/main` 的新提交与全量 `--stat`；
3. 只打印语义锚点路径的 `--stat`（空输出 = 无需跟进）；
4. 可选地把 `origin/main` 当前 SHA 写入 `UPSTREAM_BASE`。

它不改工作树、不 merge、不提交、不切换分支。基线 SHA 在仓库里不存在时（历史被重写/浅克隆）
会警告并退回以 `origin/main` 为基线。

手工等价命令：

```bash
git fetch --prune origin
git log --oneline "$(cat WotaGeiCamOHOS/docs/UPSTREAM_BASE)"..origin/main
git diff --stat "$(cat WotaGeiCamOHOS/docs/UPSTREAM_BASE)"..origin/main
git diff --stat "$(cat WotaGeiCamOHOS/docs/UPSTREAM_BASE)"..origin/main -- <锚点路径>
git show origin/main:WotaGeiCamApp/<path>       # 读上游某文件当前内容
```

## 上游变更检查清单（语义锚点）

每次同步先看下列上游文件有没有变；变了就按映射表人工移植到 `WotaGeiCamOHOS/`：

```bash
git diff --stat <基线>..origin/main -- <下列路径>
git show origin/main:<路径>
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
| `record/ArcRepairRunner.kt` | 修复成功/可保留判据（三格 + stop 门槛；`record/ArcRepairVerdict.ets`）+ **执行器契约**：NO_NEED 门槛（源实测≤目标）、解码去重门限（半个源帧周期）、失败归因（`ARC_ENGINE:MUX_CLOSE`）、状态串与错误码（`record/ArcRepairSessionModel.ets` / `cpp/arc_repair.*` / `record/ArcRepairRunner.ets`）+ **音频直拷 PTS 口径**：以视频首帧为基准归一、负值丢弃、逐轨 `MonotonicPts` 单调兜底、AddTrack 失败逐轨降级（`cpp/arc_repair.cpp` 音频直拷段）+ 零样本轨 AddTrack 前预读剔除（`hasData`） |
| `record/CodecRecorder.kt` | 内录音轨收尾判据：静音双门槛（2s 收尾/5s 巡检）、fedMsOf、采样率/声道档位（`record/CaptureSilence.ets` / `record/AudioProbe.ets`） |
| `record/FrameLedger.kt` | 录制帧账输出格式 |
| `record/BitratePolicy.kt`、`record/AutoStopRules.kt` | 码率策略、3.5 GiB 分段轮转与自停判据 |
| `record/SeriesIds.kt`、`record/PendingName.kt` | 段命名/系列命名规则，跨平台素材互认的关键 |
| `media/WotaDb.kt` | 媒体库 schema（鸿蒙侧 relStore 建表需同步迁移） |
| `core/Constants.kt`、`core/WotaParams.kt`、`core/CurveModel.kt` | 参数键名、RGB 曲线模型 |
| `player/ClipExport.kt` | 剪辑导出选轨/零样本轨规则 |
| `record/PlaybackCaptureController.kt` | 双音轨（环境音+内录）轨位布局 |
| `diag/Diag.kt` | 导出件格式/轮转规则/崩溃件命名（鸿蒙侧 `diag/DiagPure.ets`） |

流程：有变更 → 在 `docs/PORT_MAP.md` 找到该上游文件对应的鸿蒙落点 → 同步语义 →
在 `PORT_MAP.md` 中更新登记（注明对齐到的上游 commit 短哈希）→ 更新 `UPSTREAM_BASE`。

## 边界与常见错误

- **不要**在 harmony worktree 里 `git merge origin/main` / `git pull origin main`：历史独立。
- **不要**把 `Android/` 的改动当成鸿蒙移植的一部分提交；两端各自在各自 worktree 提交。
- 更新 `Android/` 到最新上游：在 `main` worktree 里拉取/切换，或直接用
  `git show origin/main:WotaGeiCamApp/...`；不要把上游历史合并进 `harmony`。
- `.github/workflows/build-apk.yml` 只属于 `main`；harmony 只保留 `build-hap.yml`
  （`.gitignore` 单独放行）。不要在两个 worktree 之间复制 workflow。
- 看到 `UPSTREAM_BASE` 指向的提交不存在：说明仓库历史被重写或浅克隆，
  脚本会退回 `origin/main` 作基线，重新评审后 `--update-base` 即可。
