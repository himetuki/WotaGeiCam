# 上游同步流程（OHOS-only 独立历史：只读对比 + 人工移植）

## 2026-10-22 历史剥离（现状，必读）

harmony 分支的全部历史已重写为 **OHOS-only**（456 → 41 提交，另有本次收尾提交）：

- 安卓源码目录（`WotaGeiCamApp/`）、安卓图标（`WotaGeiCam_Android_Icons/`）、安卓 CI（`.github/`）
  与安卓根 `README.md` **已从 harmony 的每一个提交中移除**；源码仍保留在本机磁盘上，
  仅取消跟踪（被 `.gitignore` 忽略），供移植对拍用。
- 入库路径只有：`WotaGeiCamOHOS/`、`LICENSE`、`.gitattributes`、`.gitignore`（根）。
- **harmony 与 origin/main 已无共同祖先**（`git merge-base harmony main` 无输出）。
- pull / merge 已废用：没有共同祖先，合并无意义且会把上游 Android 目录整棵带回；
  上游跟踪配置（`branch.harmony.remote` / `branch.harmony.merge`）已 unset。
- 重写前的完整备份：`.git/backup-pre-ohos-only.bundle`（不进版本库，勿提交）。

## 分支模型

```
origin/main ──fetch──▶ origin/main（+ 本地 main 镜像，只读，不开发）
                              │
                              └──只读对照（git show / git diff）──▶ harmony（OHOS-only）
                                                          ▲
                                            读 diff → 手工移植 → 更新 PORT_MAP
```

`main` / `origin/main` 是上游的**只读权威副本**：要读上游某一版源码都用
`git show origin/main:<path>`；磁盘上的 `WotaGeiCamApp/` 是 38d712a 快照，会漂移，仅供 grep/IDE。

## 黄金规则

1. **harmony 只装 OHOS 代码**：入库路径只有 `WotaGeiCamOHOS/` + `LICENSE` + `.gitattributes` + `.gitignore`。
   `WotaGeiCamApp/`、`WotaGeiCam_Android_Icons/`、`.github/`、根 `README.md` 一律不入库
   （不要对它们 `git add`；`git add -f` 会把安卓树重新写进历史，破坏 OHOS-only 约定）。
2. **永不合并上游**：没有共同祖先，合并无意义且会带回安卓目录。同步方式固定为
   「读 diff → 手工移植到 `WotaGeiCamOHOS/` → 更新 PORT_MAP 登记」。
3. **上游权威副本是 main / origin/main**：只读查看用 `git show origin/main:<path>`、
   `git diff main..origin/main -- <path>`；磁盘 `WotaGeiCamApp/` 仅供 grep/IDE，可能已漂移。
4. **共享语义只用文档锚定**：鸿蒙侧与上游共享的格式/判据/命名（见下方锚点表）在
   [PORT_MAP.md](PORT_MAP.md) 里登记对应关系，上游一变就能定位到鸿蒙侧要跟的文件。

## 日常同步（只读，脚本不会合并）

```bash
git switch harmony
bash WotaGeiCamOHOS/scripts/sync-upstream.sh   # fetch + 列出新提交与锚点 diff，退出码 0
```

脚本只做三件事：在 fetch 前记住本地 `main` 镜像位，`git fetch --prune origin` 后打印
「上次镜像 → 新 origin/main」的新提交与全量 `--stat` → 只打印锚点路径的 `--stat` →
最后才把本地 `main` 镜像 fast-forward 到 origin/main（失败只提示）。比较在前、镜像跟进在后，
否则镜像一更新，新提交段就恒为空。
它不改动 harmony、不合并、不提交；无新提交时打印「无新提交。」且锚点段为空。

手工等价命令：

```bash
git fetch --prune origin
git log --oneline main..origin/main              # 上游新提交
git diff --stat main..origin/main                # 上游全量变更
git diff --stat main..origin/main -- <锚点路径>    # 只关心共享语义
git show origin/main:<path>                      # 读上游某文件当前内容
```

## 上游变更检查清单（语义锚点）

历史剥离后不存在「合并」这一步：每次同步先看下列上游文件有没有变，
变了就按映射表人工移植到 `WotaGeiCamOHOS/`：

```bash
git diff main..origin/main --stat -- <下列路径>
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

流程：有变更 → 在 PORT_MAP.md 找到该上游文件对应的鸿蒙落点 → 同步语义 →
在 PORT_MAP.md 中更新登记（注明对齐到的上游 commit 短哈希）。

## 把 harmony 推到自己的远端（可选）

harmony 是本地分支，如需备份或协作可推到你自己的 fork：

```bash
git remote add fork <你的仓库地址>
git push -u fork harmony
```

**不要推 origin**：harmony 与 `origin/main` 已无共同祖先，`git push origin harmony` 会被判为非快进；
对上游分支使用 `--force` 同样禁止（会毁掉上游历史）。
