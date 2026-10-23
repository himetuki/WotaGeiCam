# WotaGeiCam for HarmonyOS（鸿蒙复现工程）

将上游 [himetuki/WotaGeiCam](https://github.com/himetuki/WotaGeiCam)（Android 录影相机）复现到
**HarmonyOS 6 及以上**（目标 API 版本以 DevEco Studio 当前 SDK 为准，HarmonyOS 6.0 对应 API 20+，
开工时再最终锁定）的移植工程，仅存在于本仓库的 `harmony` 分支。

> 工程骨架（AppScope/、entry/、hvigor 配置等）由 DevEco Studio 生成后放入本目录。
> 本目录在上游 main 分支中不存在，是 harmony 分支的“自留地”。

## 目录约定

```
WotaGeiCamOHOS/
├── AppScope/                 # 应用级配置与资源（app.json5：bundleName com.wotagei.camohos）
├── entry/                    # 主模块（EntryAbility + pages/Index）
├── hvigor/                   # hvigor 构建配置（modelVersion 6.1.1）
├── hvigorw / hvigorw.bat     # 构建入口（见下方「构建」：委托 DevEco 自带 wrapper）
├── build-profile.json5       # compatibleSdkVersion 6.0.0(20)，targetSdkVersion 6.1.1(24)
├── docs/
│   ├── UPSTREAM_SYNC.md      # 上游同步流程（黄金规则 + 检查清单）
│   └── PORT_MAP.md           # Android 模块 → 鸿蒙落点映射表
├── scripts/
│   ├── sync-upstream.sh      # 一条命令同步上游 main
│   ├── pure-test.mjs         # 纯逻辑测试架（test/pure/*.test.ets 的转译执行器）
│   └── local-sign.mjs        # HAP 本地签名探针（hap-sign-tool 全链 + verify-app 回验）
├── test/
│   └── pure/                 # 纯逻辑单测（.test.ets；entry 模块外，hvigor 不扫描、不进 HAP）
├── AGENT.md                  # 给 AI 代理的工程导览（改哪些文件、跳过哪些）
└── .gitignore                # DevEco/hvigor 构建产物忽略规则（含 .pure-test-tmp/、.local-signing/）
```

## 构建

前置条件：本机安装 DevEco Studio 并设置 `DEVECO_HOME` 指向安装目录
（引擎与 wrapper 都在 `%DEVECO_HOME%\tools\hvigor\` 下），`node` 在 PATH 中。

```bash
cd WotaGeiCamOHOS
export DEVECO_SDK_HOME="$DEVECO_HOME/sdk"   # Windows Git Bash 示例
./hvigorw --mode module -p product=default assembleHap   # 或 cmd: hvigorw.bat ...
```

产物：`entry/build/default/outputs/default/entry-default-unsigned.hap`（未签名；
签名需在 `build-profile.json5` 的 `signingConfigs` 配置证书，或用 DevEco Studio 自动签名）。

### CI（GitHub Actions）

仓库是 **bare 总仓库 + 两个 worktree**：本 worktree 检出 `harmony`，
`.github/workflows/build-hap.yml` 是鸿蒙侧唯一 workflow；安卓侧在 `Android/` worktree 的
`main` 分支，各跑各的 CI。鸿蒙侧在 GitHub 托管的 Ubuntu runner 上打包，不用本机 DevEco：

- 工具链：华为 DevEco **Linux 命令行工具** `commandline-tools-linux-x64-6.1.1.280.zip`
  （2.14GB，解压 6.5GB），内置 HarmonyOS 6.1.1 SDK / API 24（openharmony + hms）、
  hvigor 6.24.2、ohpm、node、native LLVM 与 CMake，与本工程 `build-profile.json5` 对齐。
  zip 做 SHA256 固定校验；要换镜像/私有资产，改仓库变量 `HARMONY_CLI_TOOLS_URL` 与
  `HARMONY_CLI_TOOLS_SHA256`（成对修改）。
- JDK 必需：hvigor 的 `PackageHap` 任务会 `spawn java`，即使不签名也会失败（`spawn java ENOENT`），
  workflow 用 `setup-java` 固定 Temurin 17。
- 触发：push 到 `harmony` 分支、push `ohos-v*` 标签。
  一个 tag 名只能指向一个提交，**不存在"同一个 tag 同时打两边"**。要一次推送同时发两端，
  两个 tag 建好后一起推：

  ```bash
  git tag v0.0.10          # 指向 main 的提交（Android）
  git tag ohos-v0.0.10     # 指向 harmony 的提交（鸿蒙）
  git push origin v0.0.10 ohos-v0.0.10
  ```

  两条 workflow 并行跑，Android 产物是 `android-*`，鸿蒙产物是 `ohos-*`，各自进各自的
  Release 页（`v0.0.10` / `ohos-v0.0.10`）。`workflow_dispatch` 需要 workflow 文件位于默认
  分支（main）才会出现在 Actions 列表里；本文件目前只在 harmony（`.gitignore` 单独放行），
  所以手动触发入口暂不可用，用 tag 或分支推送即可。`detect` job 是为将来把本文件镜像到
  main 时准备的：main 的提交没有 `WotaGeiCamOHOS/` 就跳过而不是报错。
- 版本号：tag 是单一来源。workflow 在构建前把 `ohos-v0.0.10-r05` 写进
  `AppScope/app.json5`：`versionName=0.0.10`（HarmonyOS 只接受 `数字.数字.数字`，
  不接受 `-r05`），`versionCode=1000000 + major*1e6 + minor*1e4 + patch*100 + r`
  （r 号折进来，保证递增）。tag 不合规时警告并保持文件原值；手动 dispatch 不注入。
- 产物：`ohos-<tag>-unsigned.hap` + `SHA256SUMS`（分支推送为 `ohos-harmony-<sha7>-unsigned.hap`）。
  Android 侧对应 `android-v<版本>[-r<NN>]-*.apk` 四件套——main 的
  `.github/workflows/build-apk.yml` 目前仍用 `-PapkBaseName=WotaGeiCam`，要改成 `android`
  才对齐（归安卓侧维护）。
  配齐 `HAP_KEYSTORE_BASE64` / `HAP_APP_CERT_BASE64` / `HAP_PROFILE_BASE64`
  （及 `HAP_KEY_ALIAS`、`HAP_KEY_PASSWORD`、`HAP_KEYSTORE_PASSWORD`）后，额外产出 signed HAP
  并跑 `verify-app` 回验；缺任意一项只交付 unsigned。
- 实测口径（2026-10-22，WSL Ubuntu 24.04 用与 workflow 相同的命令）：`ohpm install --all` →
  235 条纯逻辑用例全绿 → `assembleHap -p buildMode=release` 产出 unsigned HAP →
  `hap-sign-tool sign-app` + `verify-app` 成功。因此 CI 只需要网络和一个 JDK。
  版本注入另在本机 DevEco 构建实测：`v0.0.10-r05` → `module.json` 的
  `versionName=0.0.10`、`versionCode=1001005`。

## 纯逻辑测试

`test/pure/*.test.ets` 是不依赖真机的纯函数单测（对拍上游 JVM 测试的已移植面），
由自研轻量测试架执行：`scripts/pure-test.mjs` 用 DevEco 自带 typescript
（`$DEVECO_HOME/tools/hvigor/hvigor/node_modules/typescript`，可用环境变量
`PURE_TEST_TS_PATH` 覆盖）把测试文件及其 import 闭包（`entry/src/main/ets/` 起）
转译到 `.pure-test-tmp/` 镜像目录运行；`@ohos.*`/`@kit.*` import 桩成惰性 Proxy，
不触碰平台 API。测试目录在 entry 模块外，不进 HAP。

```bash
cd WotaGeiCamOHOS
node scripts/pure-test.mjs     # 退出码 0 = 全绿；任何失败非 0（CI 可直接消费）
```

已覆盖（235 用例）：record-health（21，判定顺序五条全点名）、compare-timeline（26，
T22 几何 9 + T24 内核 16 + T26 审查修复 1（右片重建恢复目标四域臂），含 off=0 回归红线三条）、slider-ladder（9，上游
SliderLadderTest 直译）、segment-policy（5）、clip-rule（6）、media-format（3，
formatSignedDuration 正/负/0 及 formatDuration 口径对齐）、compare-audio（16，
T24：选轨计划/命令顺序/桥函数 + 分屏 3 臂）、audio-sync（10，T24：重采样/包络/
粗对齐/FFT/精修）、compare-practice（6，T24）、compare-history（12，T24 10 + T25 2：
数字串宽松解析对齐上游 getLong）、capture-state-machine（16，T26：上游 14 条转移 +
状态/事件短名）、capture-silence（5，T26）、audio-probe（3，T26）、capture-sources（3，
T26）、track-sync（11，T26）、pcm-decode（7，T26）、arc-repair-plan（15，T28）、
arc-repair-flow（12，T28 9 + T29 3）、arc-repair-merge（13，T28 12 + T29 1）、
arc-repair-verdict（5，T28）、arc-repair-session（31，T29 25 + T30 4 + 审查修复 2：planForTarget 接线 / 逐档 NO_NEED）。

> **为什么工程里没有 hvigorw.js**：DevEco 自带 wrapper 按「自身位于
> `tools/hvigor/bin/`」的布局解析引擎路径；把它的副本放进工程根会导致引擎链接
> 指向工程根的上一级目录（ENOENT: hvigor.js）。因此 `hvigorw` / `hvigorw.bat`
> 通过 `DEVECO_HOME`（或 `HVIGOR_WRAPPER_MODULE`）直接调用安装目录中的原版。
> 用 DevEco Studio 打开工程构建不受影响。

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
- [x] DevEco 工程骨架生成（deveco CLI，API 20–24，assembleHap 构建验证通过）
- [x] core 纯逻辑包移植（2026-10-21：Constants/WotaParams/CurveModel/LensSlot/CameraAbility/VideoSizePicker/UIOrientation + diag 最小版，编译通过；相机适配层待后续）
- [x] record 纯逻辑锚点（ArcKeepRule/ArcDropLog/BitratePolicy/AutoStopRules/FrameLedger/SeriesIds/PendingName）（2026-10-20：七件直译 + camera/DeviceStatus 支撑枚举，编译通过；RecordHealth 及引擎类待后续）
- [x] 相机能力探测适配层（探测版，2026-10-21）：camera/KitAbility+KitLens+VideoSizeKit+KitProbe（对齐上游 38d712a；会话期能力字段与 XComponent 预览留待预览批，CAMERA 权限申请流随预览批）
- [x] 相机预览最小闭环（XComponent+PhotoSession，2026-10-21）：PermGate+KitPreview+Index 预览面；会话期补 zoom/ev/flash/iso 真值；切镜/GL/参数下发留待后续批（真机出画面为装机验证项）
- [x] diag 完整版（2026-10-21）：DiagPure 纯函数层 + 环形缓冲/落盘轮转/崩溃件/导出报告 + EntryAbility 接线 + Index 诊断区三按钮（公共目录导出与分享面板留白）
- [x] 参数下发层（2026-10-21）：camera/RequestApplier（决策核心+PhotoSession 逐键下发报告）+ camera/ParamMap（枚举映射/反向探针）+ applyParams 与参数自检八档；camera 侧 ArkTS warning 清零
- [x] 最小录制（AVRecorder 面输入，2026-10-21）：camera/KitRecorder（VideoSession+AVRecorder 起录/停录/判废）+ record/VideoStore/RecordProfile/RecordResult（命名/余量/落盘/契约最小版）+ MICROPHONE 权限与视频-only 退路；分段/健康/入库留待后续（真机录出为装机验证项）
- [x] GL 基础设施（GL-0，2026-10-21）：entry/src/main/cpp 原生构建骨架（CMake+napi entry 模块）+ XComponent 原生 surface 回调 + EGL/GLES3 上下文与 OnFrame 渲染循环 + GlPreview 动画试验页；相机输入/上游着色器族留待 GL-1
- [x] GL 相机帧入 GL（GL-1，2026-10-21）：OH_NativeImage→external OES 纹理→上游 TEXTURE_VS/PASS_THROUGH_FS 逐字上屏；napi getInputSurfaceId；ES3→ES2 退避与去 alpha fallback；GlCameraEngine+GlPreview 闭环（方向校正/剩余着色器族留待 GL-2）
- [x] GL 监视类效果（GL-2，2026-10-21）：旋转/镜像烘 UV（buildTexCoords+stRotation）、CURVE_FS+CurveStack LUT、斑马纹/峰值单趟效果（含曲线叠加与上游退避链）、ZebraPattern 瓦片、四态切换页；FBO 链（ARC_MERGE/Frost/NtscGrid）留待 GL-3
- [x] GL 离屏基础设施与毛玻璃（GL-3，2026-10-21）：FBO/RT（尺寸/offset 纯函数逐行）、OES→2D 拷贝→横向→纵向高斯三趟链（weights 逐值、highp 退避、错误连击停链）、PASS_THROUGH_2D 上屏、全屏毛玻璃开关；ARC_MERGE/画板几何/NtscGrid 留待后续批
- [x] GL 编码面与 GPU 录制最小切片（GL-4，2026-10-21）：EGL 第二编码面（复用缓存 config）+ 每帧第二趟编码 pass（编码 UV deg=stRotDeg 不镜像、**不碰 frost**）、OH_VideoEncoder(surface)+OH_AVMuxer(fd, MPEG_4) 直写视频-only MP4（PTS wall-clock 相对段基准 µs）、muxer SetRotation(normalizeQuarter(sensor−display)) 容器旋转（四舍五入到 0/90/180/270）；编码 pass 恢复上屏上下文失败 1 次即中止，g_displayCtxLost 帧级守卫重试上限 60 帧、GlRecorder（fd 归 ArkTS/VideoStore 落盘与余量预检/停录摘要）、页面「录 GPU/停录」闭环（500ms 监视 native 录制态）；上批 P3 ×4（frost 停链释放与开关复位、半成功纹理不泄漏、首开预编、曲线失败用户动作重试）。留白：音频轨/分段/抽帧/FrameLedger/.drops.json/参数下发
- [x] GL-4 光弧链在线版（2026-10-21）：ARC_MERGE_FS（整像素选择+差分死区）与 ArcMendPass（cur+pending×2+acc×2）、ArcKeepRule/ArcRepairFlow C++ 逐行对拍、MEND/DROP 只吃新相机帧、停录 flush 滞留帧、MEND→DROP 降级、`.drops.json` sidecar、原生五格帧账 + camera frameStart 合成、页面三态转换（关/补弧24/抽帧24）；上批 P3 ×4（旋转角共享归一/STOP 竞态/活跃判据/上下文恢复与 60 帧重试上限）。留白：音频轨/分段/参数下发/PTS 网格化/成片名前缀/离线修复
- [x] 媒体库最小版（2026-10-21）：`media/WotaDb.ets`（RDB 四表：media_tag/trash_item/media_series/series_part，version 1）、`MediaRepo`（私有 videos 扫描 + AVMetadata 1.5s 超时 + FNV-1a ID + 标签/回收站联表 + 收藏/入回收站/还原/彻底删除）、`MediaTag`/`MediaFormat`（TagSpace/页签/clampedSelection/时间线/时长/大小）、`pages/Gallery`（全部/收藏/回收站三页签、二次确认删除、元信息面板）、Index/GlPreview 入口；上批 P3×6（帧账录制纪元/sidecar 诊断门控/flush swapFail/上下文丢失提示/实测账整场口径/MEND attach 重试）。留白：缩略图/相册入库/分享/重命名/自定义标签/系列折叠/自定义页签
- [x] 播放器最小版（2026-10-21）：AVPlayer+XComponent(SURFACE) 播放、进度/暂停、逐帧（FrameStep+SEEK_CLOSEST）、A/B 会话循环、平台倍速档、帧率侧车/默认/手动、镜像与双指缩放平移；Gallery 卡片进播放页、返回刷新；媒体库 P3×5（探测缓存模块化/重试 DB/页签失败回退/sidecar 错误区分/open 进超时）。平台偏离：倍速 0.125/0.25/0.5/0.75/1.0（无 0.1/0.3）；帧率来自 .drops.json→30→手动。留白：对比播放/选轨/剪辑导出/AB 持久化/旋转观看/缩略图
- [x] 录制可靠性最小版（2026-10-21）：record/AutoStopMonitor（1s 相机设备态转移（当前态预置+session error 兜底）+ 首个 1s 立即/其后 30s 电量≤3%/可用空间<MIN_FREE_MB，一次录制至多一发；AutoStopRules/CameraLostWatch 在线消费）+ GlCameraEngine DeviceStatus（start 成功 OPEN_SUCCEED / session error/stop CLOSE）+ GlPreview 自动停录“成片已保存/收尾可能失败”提示与手动停录 500ms 宽限（stopPending 防重入；相机丢失后需重新起镜才可再录）；上批 P3：探测满载提示、findById 严格路径、WotaDb 等待超时/IN 分块/按路径清账、逐帧队列、completed seek stale、attach 失败重试。留白：3.5GiB 分段/双音轨内录/蓝牙/对比播放/RecordHealth/AVRecorder 巡检
- [x] 3.5GiB 分段轮转（2026-10-21）：native getGpuWrittenBytes + ArkTS 500ms 轮询；SegmentPolicy（3.5GiB×0.98）；停段→新文件→重建编码器/EGL 面（lostMs 记账）、每段独立 MP4/时间轴；逐段 FrameLedger + 会话段清单；首段旁 .drops.json segments 覆盖全段（part.samples 口径；drops 仅末段，登记跨段失真）；8MB 分段测试入口（GlPreview「分段测试」）。上批 P3×4：1s 起录冷却、死字段清理、Player 探测提示移出控制层、stopReason 起录清理。留白：音频/双轨内录/蓝牙/对比播放/RecordHealth/AVRecorder 分段/系列归组
- [x] 主录制 UI/HUD 最小版（2026-10-21）：pages/Camera（全屏 GL 预览 + 固定 HUD：顶栏读数/3×3 网格/底栏/录制计时）、参数胶囊（镜头/画幅/帧率/变焦/闪光/效果/光弧/网格）、GlRecorder+AutoStop+宽限+分段接线；pages/Settings（画幅/帧率/码率/光弧/效果/网格 preferences 持久化）+ ui/HudSpec；GlCameraEngine 镜头/画幅/变焦/闪光最小扩展（预览帧率 API 不可设，登记预览可能30fps）；主入口改 Camera，Index/GlPreview 保留开发者测试；pageVisible 防后台起镜、重试相机、变焦实际值显示。留白：自定义 HUD 布局、PillPopup、完整参数面板、音频/双轨/对比/剪辑导出
- [x] 录制健康三格（T18，2026-10-21）：record/RecordHealth（GPU 三格判定/1.2s/3s 窗口/最多 2 次重录/64KiB 活性）；native CodecRecorder 暴露 trackAdded/muxStarted/samples + getGpuHealth；Camera 500ms 巡检、自动重录（仅重开录制段）、空段丢弃与明确报错；T17 P3×8（pageVisible 权限门控/重试相机/Settings 保存串行化/变焦实际值/HUD_READOUTS/appendNotice/相机丢失标签/预览录制帧率分显）。留白：AVRecorder 健康检查、streamHealth 活性路、音频/双音轨、剪辑导出、对比播放、蓝牙、HUD 自定义布局
- [x] 对比播放最小版（T19，2026-10-21）：`player/CompareTimeline.ets`（TimelineGeometry park 规则 + normalizeOffset/rightTargetFor/timelineSpan/End）、PlayerEngine setVolume（双实例无静态共享；T20 收紧 NaN 防护并移除 setMuted）、`pages/Compare.ets`（双 XComponent(SURFACE)+双 PlayerEngine、右片 MediaRepo 现选、共享播放/暂停/seek/倍速、手动偏移 ±0.5s/归零、声音侧左/右/静音默认左、120ms 漂移校正 250ms 阈值/500ms 冷却、两侧独立 attach/retry 与 surface 重建、隐藏暂停/销毁双 release）；Player 顶部「对比」入口 + main_pages 注册。T18 P3×4：空段仅 samples=0 且 bytes=0 才丢（bytes<0 保留 triage）、onPageShow 续跑健康巡检（保留 healthStartMs）、GIVE_UP 文案由 FULL_WINDOW_MS/MAX_RESTART_ATTEMPTS 生成、native HealthSnapshot 单锁快照供 getGpuHealth。留白：自动对齐/DecideCommands/AudioSync、AB 持久化、美颜/滤镜导出、蓝牙
- [x] A/B 无损剪辑导出（T20，2026-10-21）：`player/ClipRule.ets`（normalize/keepSample/clipNameOf/keyFrameIndexOf/remapDrops/TrackChoice）；native `cpp/clip_exporter`（OH_AVSource/AVDemuxer→OH_AVMuxer 逐样本 remux、关键帧入点/basePts 平移/旋转透传/视频 32 音频 8 B 帧尾容忍/逐轨 monotonicPts/音轨选择与零样本不建轨/worker 进度取消）；NAPI 四口 + `player/ClipExporter.ets`（fd/轮询/超时/半成品删除/sidecar 平移/防覆盖命名）；Player「导出 A/B」面板与 media library 跳转。T19 P3：PlayerEngine setVolume Number.isFinite 防护、删除 setMuted。已知差异：逐轨非交织、单音轨、无 MediaStore pending/分享；T18/T19 剩余 P3 登记到健康/对比收口批。留白：交织、双音轨/自动同步、剪辑进度持久化、后台保活
- [x] HUD 高级参数面板（T21，2026-10-21）：`ui/PillPopup.ets`（就近浮层基础设施：遮罩点击关/PillAnchor.place 钳制定位/档位网格/刻度滑杆/Note，锚点 getRectangleById+底贴屏回退）+ `ui/SliderLadder.ets`（梯子纯函数族直译）+ `ui/ParamFormat.ets`（shutterText/evText/wbShort/shutterItems）+ `ui/ParamPills.ets`（AeModeRow/快门/ISO/EV/WB 弹层，录制门控 RECORDING_EDITABLE={ISO,EV,WB}）；Camera 页参数总线接线（起镜 applyAbility(refined)、起录前/弹层改动 engine.applyParams 逐键下发、顶栏第二行 快门/ISO/EV/WB 读数）；T20 P3×4（clip done 旋转日志同 SetRotation 变量、音频 NO_MEMORY 跳过不判死、uniqueOutputPath 占用兜底、超时 fd linger 延迟关闭+pendingFds reap）。留白：ZOOM/FOCUS 胶囊、tint 副轴、参数持久化、动效/Tokens、可编排 READOUT 网格
- [x] 健康+对比收口（T22，2026-10-22）：三格真信号（native formatChanged_=onStreamChanged 等价上游 format-changed，getGpuHealth 4 字段；muxer track 位降诊断）、轮转重启健康窗（attempts 会话级登记）、健康重录三残差（recordStartedAtMs 重置/stop 摘要末段账 lastPath|lastBytes|lastSamples/丢段读末段账保留前段）、时间线双轴消费（Slider min/max=tMin/tMax、seekBoth 走 park 规则、timelineSpan 留白）、右片 surface 重建恢复位置、隐藏期 attach/retry/surface 门控（deferred 标志）、右列表 id+path 双排除、漂移速度微调平台限制登记（AVPlayer setSpeed 仅枚举档，speedToEnum 为证）、T21 P3×4（快门读数 ※/skipped 降 Diag.i/相机丢失 closePill/PillPopup 尺寸重排）+ 纯逻辑测试架（scripts/pure-test.mjs + test/pure 五件 50 用例）
- [x] T23 收尾交付（2026-10-22）：T22 P3×2 清账（pure-test 死常量 SRC_ANCHOR 清理；对比页时间线左端标签负轴显示 `media/MediaFormat.formatSignedDuration` + 3 用例，53 用例全绿）+ 本地签名探针 `scripts/local-sign.mjs`（hap-sign-tool 全链 ECC P-256：keypair→CA→app-cert→profile-cert→sign-profile→sign-app→verify-app 自动回验）+ 交付口径（见下「交付」节）
- [x] T24 纯逻辑直译（2026-10-22）：`player/CompareTimeline.ets` 补 decide 全量内核（CompareDomain/DecideInput/DecideCommands/domainOf/resolveT/decide：域判定黑层回灌、交接不 seek、双覆盖 250ms 硬 seek/邻域 nudge、时间线末回绕 Tmin 与末帧停驻、AB 不抢回绕、拖动让路）+ `player/CompareAudio.ets`（轨序 0/1 单一映射、单侧静音计划、固定命令序、分屏单引擎音量恒 1）+ `player/AudioSync.ets`（重采样/包络/粗对齐/radix-2 FFT/1kHz 低通/±50ms 精修与置信度；平台解码留 T25）+ `player/ComparePractice.ets`（armed/takeLeftId/deliver，练习页分屏阻塞）+ `player/CompareHistory.ets`（容错解析/去重插首/截 12/编码键序；preferences 留 T25）；新增 58 用例（53→111），`pages/` 零改动
- [x] T25 对比页接线（2026-10-22）：Compare 页 onTick 交 decide（pause/resume→seek→nudge→黑层写回；主钟读数 resolveT，pre-roll 显负 T）、seekTimeline 统一 park/黑层、setPlaying 末态回 Tmin + wantPlaying 续播（换右片/右片晚挂补一次）、domBlack 常驻透明层 + 底栏循环开关；音频计划真实下发（CompareAudio applyOrderOf → setVolume/selectAudioGroup，ClipExporter.probe audioCount≥2 门控 chip，静音三态保留）；`player/PlayerEngine.ets` 增 pauseIfPlaying/playIfPaused（completed 按 positionMs 补 seek）/nudgeSpeedForSync（平台空操作）/selectAudioGroup（getTrackDescription 音轨序）；`player/CompareHistoryStore.ets`（wota_player_ab）+ 历史面板（回填/缺失/删除）+ 选右片记账；`player/PureLogicBarrel.ets` 编译守卫（EntryAbility 启动日志）；CompareHistory 数字串容错（+2 用例，113 全绿）。审查修复：surface 重建后按 wantPlaying 自动复播（seekAndPlayIfPaused）、拖回双覆盖区 dormant 侧随松手复活、换片/attach 后音轨门控无条件重探、右片未选/报错时进度跟左钟、静音态音轨静默生效、历史写入串行。进页起播仍为手点播放（登记偏离）；练习动线/AudioSync 平台解码转 T26

- [x] T26 内录纯逻辑降级版（2026-10-22）：`record/CaptureStateMachine.ets`（Idle→Authorizing→Active/Failed→Revoked 全转移 + 状态/事件短名，16 用例）、`record/CaptureSilence.ets`（SilencePromptGate 按场 60s 节流 + captureSilenceVerdict 2s/5s 双门槛 + fedMsOf，5）、`record/AudioProbe.ets`（frameBytes/chunkBytes/recordBufferBytes/dbOf + captureParamMismatch 档位体检，3）、`record/CaptureSources.ets`（OH_AudioCaptureSourceType 契约 MIC=1/ALL_PLAYBACK=2/APP_PLAYBACK=3 + 内录源优先级，3）、`player/TrackSync.ets`（内容差+首样本容器差合成/低置信度归零/失败分类，11）、`player/PcmDecode.ets`（PTS 落位/空洞补零/PCM16/24/F32LE/降单声道，7）；PureLogicBarrel 纳入六模块（启动日志扩展 cap/src/sync/chunk/silence/pcm）；新增 46 用例（113→159，含审查修复 1 条右片重建恢复目标四域臂）。T25 P3×4：自然播完清 wantPlaying、右片重建按快照单次 seek、探测中跳过 override、删死 API。审查修复：右片重建在右独播/pre-roll 域保留重建前素材位（`rightResumeTargetOf` 以快照为右钟）、标签函数去 default 改显式 throw、AudioProbe 补网格、探测早退清 pending。**平台面（AVScreenCapture 双源+AAC 三轨+隐私授权）转 T27 真机批**，调研结论见 docs/PORT_MAP.md「T26 平台调研」

- [x] T28 离线光弧修复纯逻辑（2026-10-22）：`record/ArcRepairPlan.ets`（有理数抽帧计划 dstFrameCount/isKept/progressOf/of；29.97 与 25.4 真值位次锚点；不移植旧 Int 外壳）、`record/ArcRepairFlow.ets`（Hold/Fold/Emit/Merge 流式状态机 + 计划路/在线分类路，PTS=keptCount·1e6/dstFps；补上 cpp/arc_flow.h 所引 ArkTS 锚点）、`record/ArcRepairMerge.ets`（CPU 4:2:0 2×2 块整像素选择，Uint8Array 无符号 Δ、块内最大 Δ、4Y+U+V 整块搬运不造色）、`record/ArcRepairVerdict.ets`（arcRepairOk 三格 + arcRepairKeep stop 门槛）；四模块纳入 PureLogicBarrel（`arc=36/0/1`）与 RecordAnchors 编译门；新增 41 用例（159→200）。T26 P3×3：双表面重建右先挂用快照恢复、已播态 seekTo 纠偏、PORT_MAP 旧描述订正。**执行器（解码/编码/EGL/Player 面板）转 T29**

- [x] T29 离线修复 CPU 执行器核心（2026-10-22）：`cpp/arc_repair.h/.cpp`（单例+worker 状态机；probe 宽高/旋转/容器 fps/时长/音轨数/帧距中位数 ≤2M 样本；同步模式解码 → 紧打包 I420（NV12/NV21/I420 + stride/sliceHeight，未知格式 ARC_CPU_YUV）；ArcRepairFlow 指令执行（cur/pending/acc 三面整像素合并）；同步模式编码（YUVI420 缓冲输入、显式 PTS=j/dstFps）→ 首枚输出后 AddTrack/SetRotation/Start → 逐样本写 muxer；取消/deadline/状态串）+ NAPI 四口（probeArcRepairSource/startArcRepair/getArcRepairStatus/cancelArcRepair）+ `record/ArcRepairSessionModel.ets`（错误码/probe 与状态解析/速率回退/NO_NEED/去重/帧数/归因/note，24 用例）+ `record/ArcRepairRunner.ets`（fd 生命周期/200ms 轮询/取消宽限+linger/失败 discard/成功 stat/`contract()` 启动日志）；T28 P3×4（Flow 字段私有化+dstFps 非法抛错、GPU 突变断言、文档口径）；新增 30 用例（200→230）。审查修复：linger 挂起前先 unlink 半成品（reap 兜底再删）、CSD 缓冲建轨后 Free 不写样、取消终态 `cancelled`、零样本归 ARC_NO_VIDEO、probe 时长不可得 ok=false、统一 parkOrDiscard、音轨硬闸（T30 已由音频直拷取代）、命名防覆盖、native 卫生。**执行器未接 UI**：音频轨与 Player「修复光弧」面板 T30（开放按钮前必须先补音频）；GPU 路线 T31

- [x] T30 离线修复音频直拷 + Player「修复光弧」面板（2026-10-22）：native 运行期保留全部 `audio/` 轨（`audioTrackIndices/audioFormats/audioDstTracks` 并行表），首枚视频输出时在 SetRotation/Start 前逐轨 AddTrack（失败逐轨降级）；视频 EOS 排空后同 fd 新建 audio source/demuxer 逐轨 select→SeekToTime(0,PREVIOUS_SYNC)→读样本，PTS 按视频首帧归一、负值丢弃、逐轨单调兜底后写 muxer；done 串 audioSamples=全轨成功样本合计；`record/ArcRepairSessionModel.ets` 增 `describeRepairError`（全码中文文案 + ENGINE 后缀 + busy/args/worker + 未知兜底）与 `audioLaneNote`（无/成功/降级三态），删除临时音轨硬闸；`ArcRepairRunner.run` 可复用面板 probe 结果、结果带 `audioSamples`；`pages/Player.ets` 底栏「修复光弧」面板（probe 拦截 NO_NEED/无视频/失败、目标 24/25、死区 0/12/32、进度/取消/成功跳媒体库、与导出 A/B 互斥、忙碌拦返回）。T29 P3×7（discardQuiet(ENOENT)/排空 delta 仅诊断 WARN/缺像素格式即 ARC_CPU_YUV/stride·slice 单次 WARN/nativeStarted/头注与 JSDoc 订正）。审查修复（P1×1+P2×1+P3×5）：probe 补赋 dstFrames + 面板守卫改源侧事实并逐档判 NO_NEED（不可行档禁用）、成片说明按实际目标档/死区重算且切换即刷新、零样本音轨预读后不建轨、逐轨成功数进 native done 日志（note 不再宣称全部）、lane 读失败记 readError、Seek(0) 失败回退从头读。**面板可用，GPU 路线与真机调优 T31**

- [x] T31 取景页 HUD 真机取证与修复（2026-10-22，HUAWEI ALN-AL00 / HarmonyOS 7.0.0.109 / API 26 / 1260x2720）：真机取证（旧包 XComponent [0,124][1260,2629] 未沉浸、底部白条；胶囊 镜头:—/画幅:— 而顶栏已为 广角/1080p；单行 Scroll 仅 6 颗可见；录制键绿色圆角矩形；网格完全不可见；系统栏白底黑图标）→ `ui/HudTokens.ets`（深色色板/字阶 11-12-13-17/触控 48/遮罩令牌）、`ui/WindowChrome.ets`（沉浸式+透明系统栏白图标+AvoidArea 安全区；普通页黑底栏）、`ui/HudPill.ets`（@Component+@Prop 替 @Builder 值参数：胶囊标签不刷新根因）、`ui/HudMoreSheet.ets`（复用 PillPopup：闪光/效果/光弧/网格）、Camera 顶栏两行各 5 项+右侧 48vp 入口胶囊+渐变遮罩、Dock 2×4 主胶囊（48vp、无横向 Scroll）、更多+圆形快门（44vp 红点/录制中 24vp 方块）、确定性 3×3 网格、删「预览—fps」死读数、Settings List 顶对齐+48vp 选项+开发者入口 7 连点显形、Index/GlPreview 页头+返回、EntryAbility 强制深色、Gallery 黑底栏。**复审收尾（P3×5+清理）**：底栏去固定高（相机断开/权限拒绝不裁快门）、Dock 胶囊 12fp+左右 6vp、顶栏 fps 去「录」前缀（360vp 防截断）、快门/ISO/EV/WB 录制中恢复开窗（只读由弹层承担）、PillNote 11fp（HUD 无 10fp）、WindowChrome 串行链 + loadContent 前 prime（首帧无白条/往返不乱序）、删 currentPreviewFps/previewFps/fpsLabel 等死代码。**真机复验未执行：安装包签名指纹与本仓自签证书不匹配，按红线不安装**；待用户以 DevEco 同证书重装后按 PORT_MAP T31 16 项走查

**进度收敛（T31 口径）——已落主线**：相机预览/能力探测/参数下发（KitAbility/KitPreview/RequestApplier）、GL 渲染链 GL-0..GL-4（含毛玻璃/光弧在线补弧抽帧/编码面）、GPU 录制（3.5GiB 分段+健康三格重录+AutoStop）、媒体库/播放器/对比播放（decide 编排/黑层/主钟/音频选轨/历史）、A-B 无损剪辑、HUD 高级参数面板、diag 完整版、对比页纯逻辑内核（decide/音频/自动对齐 DSP/练习桥/历史）、内录纯逻辑内核（授权态机/静音判据/采集算式/源契约/双轨偏移/PCM 解码）、离线光弧修复（抽帧计划/指令流/整像素合并/成功判据 + native CPU 执行器 probe/解码/合并/编码/mux + 音频直拷 + Player 修复面板）、纯逻辑测试架（235 用例）。
**留白（后续批）**：T31 真机复验 16 项与相机交互（T32：UIOrientation 接线/对焦/捏合变焦）、离线修复 GPU 路线与真机调优（T32：ArcRepairGl/1080p 基准/色度残差）、内录平台面四步（T27-1 平台探针/T27-2 ENV 音轨/T27-3 CAP 双轨/T27-4 消费面）、AudioSync 平台解码与自动对齐入口、练习动线（分屏录制页）、VideoSession 参数面（录制参数下发）、蓝牙面板、更新检查、HUD 布局编辑器、缩略图/相册入库/分享、ParamsPersist 持久化、交织写入剪辑等——逐项见 [docs/PORT_MAP.md](docs/PORT_MAP.md) 与「交付后路线」节。

## 交付

构建产物（`./hvigorw --mode module -p product=default assembleHap` 后）：

| 产物 | 路径 | 字节数（单次快照） | sha256（单次快照，易变） |
| --- | --- | --- | --- |
| 未签名 HAP | `WotaGeiCamOHOS/entry/build/default/outputs/default/entry-default-unsigned.hap` | 5799217 | `9482c55e0bf9180d960d173b08d9be609836735947c544dda70e38aa3d2acc40` |
| 本地签名 HAP（自签探针） | `WotaGeiCamOHOS/entry/build/default/outputs/default/entry-default-signed.hap` | 5919986 | `87fec43772edbead00a58c14d07371d6d34eca13317b20618a200bf302cd3c00` |

> **hash 不可复现，勿作复验判据**：zip 条目时间戳随打包时刻变化，同一源码两次 clean 构建
> 字节数相同但 sha256 不同；signed 包还叠加 sign-app 时间戳，每次重签必变，**signed 字节数也
> 会随签名块长度小幅浮动**（本快照 5919986B 不是固定值）。上表是 T31 复审收尾批的单次本机快照（仅交付
> 留痕）。内容级复验**只对 unsigned 有效**：解包后按路径排序逐文件 sha256 比对（16 个业务文件）；
> signed 包以 `local-sign.mjs` 内置 `verify-app` 的证书链/profile 输出为准（`.pages.info` 由签名
> 工具生成，不参与内容比对）。示例：
>
> ```bash
> rm -rf /tmp/hap-check && mkdir -p /tmp/hap-check
> unzip -q entry-default-unsigned.hap -d /tmp/hap-check
> (cd /tmp/hap-check && find . -type f | sed 's|^\./||' | sort | \
>   while IFS= read -r f; do sha256sum "$f"; done) > /tmp/hap-manifest.txt
> ```
>
> 两次构建的 `/tmp/hap-manifest.txt` 应逐行一致。

- 未签名包：clean 构建（WARN=2 基线：napi 未验证提示 + 未配置签名跳过）产物。
- 本地签名包：`node scripts/local-sign.mjs` 产出（自签 ECC P-256 证书链 + debug profile）。
  脚本的 `BUNDLE` 已随 2026-10-23 的改名更新为 `com.wotagei.camohos`；profile JSON/p7b 已按新包名
  重签（`verify-app` 回验通过：3 级证书链 + 3259 字节 profile），p12 与四张证书沿用原材料
  （证书指纹不变，覆盖安装不受影响）。重签方法：删 `.local-signing/wota-debug-profile.json` 与
  `wota-debug.p7b` 后复跑脚本（材料只在缺失时生成）。HarmonyOS NEXT 采用新式签名：带 `<hap sign block>` 标记的签名块位于 ZIP
  尾部中央目录之前，**不产生旧式 `META-INF/` 条目**——「签名块
  与 profile 可见」以脚本内置 `verify-app` 回验为准（实测还原 3 级证书链 + 3254 字节 profile）。
  `unzip -l` 可见 17 项 = 16 个业务文件 + sign-app 生成的 `.pages.info`（本快照 592 字节），无 `META-INF/`。
  **仅证明包结构可签名/可验证**——自签证书未经华为云颁发，真机安装请改用 DevEco Studio 自动签名。
- 复跑：`node scripts/local-sign.mjs`（需 `DEVECO_SDK_HOME` 或 `HAP_SIGN_TOOL_JAR`、PATH 中有 `java`；
  可用 `LOCAL_SIGN_PASS` 覆盖本地口令）；签名材料只首次生成，落 `.local-signing/`（已 gitignore，
  POSIX 下 p12 收紧为 0600），重复执行会复用材料并重签；签名先写 `.tmp`，任一步失败即删临时件，
  既有 signed HAP 保持不动，仅回验通过后才替换成品。
- 安装：真机/模拟器 `hdc install <signed.hap>`，均需有效签名。自签探针产物不可直接装零售机；
  模拟器同样建议使用 DevEco 自动签名产物（AGC 账号或本机 debug 证书 + 含设备 ID 的 profile），
  社区 OpenHarmony 签名材料未在本批实测范围内。
- 签名指引：DevEco Studio → File → Project Structure → Signing Configs → 勾选 "Automatically generate signature"
  （需 AGC 开发者账号），构建即产出可安装 HAP。
- 真机验证清单：见 [docs/PORT_MAP.md](docs/PORT_MAP.md)「真机待验」节（T22 新增 4 项、T23 新增 1 项 + 各行「装机验证项」）。
