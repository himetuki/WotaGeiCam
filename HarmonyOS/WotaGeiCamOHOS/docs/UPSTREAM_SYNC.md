# 上游同步流程（单树 monorepo：只读对比 + 人工移植）

## 现状（2026-10-22 单树 monorepo 化后）

仓库是**单树 monorepo**：main 一棵树同时管两端，没有 bare 总仓库与双 worktree。

```
WotaGeiCam\              # 仓库根（main 一棵树）
├── Android\             # 安卓工程（上游权威 = origin/main）
│   └── WotaGeiCamApp\   # Gradle 根
└── HarmonyOS\
    └── WotaGeiCamOHOS\  # 鸿蒙工程（移植落点）
```

- **上游权威是 `origin/main`**（github.com/himetuki/WotaGeiCam 的 main）。
  本地 `Android/` 只是工作副本，可能滞后；读最新上游一律用
  `git show origin/main:Android/WotaGeiCamApp/<path>`。
- 2026-10-22 起从「bare + 双 worktree（main/harmony 两分支）」改为单树 monorepo：
  原 harmony 分支的历史已并入 main 祖先，原分支仅作存档。
- **同步仍不做 merge**（不 merge 上游历史，也不做目录级 merge）：
  固定为 **读 diff → 在 `HarmonyOS/WotaGeiCamOHOS/` 手工移植 → 更新 PORT_MAP 与 UPSTREAM_BASE**。

## 基线文件

`HarmonyOS/WotaGeiCamOHOS/docs/UPSTREAM_BASE` 记录"上次评审/对齐到的上游提交"（单个完整 SHA）。
同步脚本以它为起点列出新提交；评审并移植完成后执行 `--update-base` 更新，
**与本次移植改动一起提交**。

当前基线：`9cc36c5`（2026-10-28 追赶轮收口后推进；4998ce4 → 9cc36c5 的 39 提交已全部
评审并落地/登记，处置汇总见 PORT_MAP「上游基线推进（2026-10-28）」节。此前基线：
`4998ce4`（2026-10-25）；`b462fad`（2026-10-23）。

| 提交 | 结论 |
| --- | --- |
| `0dee86d` 设置页「滑杆吸附」 | ✅ **完整移植**（`ui/SliderSnap.ets` + PillSlider 连续模式 + 设置分组） |
| `e950328` 蓝牙内录音画同步 | ✅ **纯逻辑层已移植**（AudioAlign/PreflightPolicy/PreflightPcm/PostStopSyncPlan/TrackSync 决策面/audioShiftUs）；预校准采集、ptsShiftUs、PlayerScreen 同步 UI 等平台面挂 **T27-2/3/4** |
| `971874a` 更新弹窗展示 Release 说明 | ✅ **已移植**（`update/` 四模块 + 设置页更新弹层；OHOS 化偏离见 PORT_MAP update 节） |
| `376b3ef` 说明镜像也读 + 测速选路 | ✅ **已移植**（镜像表/说明直读 + 并发测速排序；段 3 因平台无重定向控制而跳过） |
| `82943cf` 产物命名改平台后缀制 | ✅ **本侧等价已落**（资产挑选 `-signed-Harmony.hap`/`-unsigned-Harmony.hap`，校验值读 `SHA256SUMS`） |
| `31fa579` 安卓工程移入 `Android/` | 结构性提交，无移植动作（同步脚本已兼容两代路径） |
| `3398153` 安卓版本基数 0.0.10 | 安卓侧版本号，鸿蒙版本由 build-hap.yml 按 tag 注入，无动作 |
| `b462fad` 发布说明纳入仓库 | CI/文档，无移植动作 |

### UPSTREAM_BASE 推进规则

`UPSTREAM_BASE` **只在「待评审队列全部评审完、且暂缓项已登记」时才推进**。
2026-10-23 满足该条件：四个功能提交均已评审并落地/登记，其余为结构性/CI 提交，
故基线一次推进到当时的 `origin/main`（`b462fad`）。**下一次推进点**：新的上游功能提交
评审完（含 T27-4 平台面收口）后，再 `--update-base`。

## 日常同步（只读，脚本不会合并）

```bash
# 在仓库根执行
bash HarmonyOS/WotaGeiCamOHOS/scripts/sync-upstream.sh                 # 只对比：新提交 + 锚点 diff
bash HarmonyOS/WotaGeiCamOHOS/scripts/sync-upstream.sh --update-base   # 评审/移植完成后更新基线
```

脚本只做四件事：

1. `git fetch --prune origin`（只更新远端跟踪引用）；
2. 打印 `UPSTREAM_BASE → origin/main` 的新提交与全量 `--stat`；
3. 只打印语义锚点路径的 `--stat`（空输出 = 无需跟进）；
4. 可选地把 `origin/main` 当前 SHA 写入 `UPSTREAM_BASE`。

它不改工作树、不 merge、不提交、不切换分支。基线 SHA 在仓库里不存在时（历史被重写/浅克隆）
会警告并退回以 `origin/main` 为基线。

手工等价命令：

```bash
git fetch --prune origin
git log --oneline "$(cat HarmonyOS/WotaGeiCamOHOS/docs/UPSTREAM_BASE)"..origin/main
# 基线还在旧布局（安卓工程在仓库根的 WotaGeiCamApp/）时，跨布局对拍要显式给两侧路径：
git diff --stat "$(cat HarmonyOS/WotaGeiCamOHOS/docs/UPSTREAM_BASE)":WotaGeiCamApp origin/main:Android/WotaGeiCamApp
git show origin/main:Android/WotaGeiCamApp/<path>       # 读上游某文件当前内容
# 基线更新到新布局后，两侧都是 Android/WotaGeiCamApp，可简化为：
git diff --stat "$(cat HarmonyOS/WotaGeiCamOHOS/docs/UPSTREAM_BASE)"..origin/main
```

## 上游变更检查清单（语义锚点）

每次同步先看下列上游文件有没有变；变了就按映射表人工移植到 `HarmonyOS/WotaGeiCamOHOS/`：

```bash
git diff --stat <基线>:WotaGeiCamApp origin/main:Android/WotaGeiCamApp -- app/src/main/java/com/wotagei/cam/<下列相对路径>
git show origin/main:Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/<相对路径>
```

（基线已在新布局时，两侧路径一致，直接 `<基线>..origin/main` 即可。）

| 语义锚点（上游路径，Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ 下） | 变更波及 |
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

- **不做 merge**：上游历史虽已并入 main 祖先，同步仍只走「读 diff → 人工移植」，
  不要对 `Android/` 或 `HarmonyOS/` 做目录级 merge，也不要 merge 上游历史。
- 安卓侧改动直接在 `Android/` 下提交到 main；鸿蒙移植改动提交在 `HarmonyOS/WotaGeiCamOHOS/`。
  不要把安卓侧的变动混进鸿蒙移植提交。
- 读最新上游用 `git show origin/main:Android/WotaGeiCamApp/...`；
  本地 `Android/` 只是工作副本，可能滞后，对拍以 origin/main 为准。
- 原 `harmony` 分支已并入 main、仅作存档，**不再推送**，也不要在其上继续开发。
- 两条 CI（`build-apk.yml` / `build-hap.yml`）同在仓库根的 `.github/workflows/`，
  单树共用，无需跨目录复制。
- 看到 `UPSTREAM_BASE` 指向的提交不存在：说明仓库历史被重写或浅克隆，
  脚本会退回 `origin/main` 作基线，重新评审后 `--update-base` 即可。
