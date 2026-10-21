# WotaGeiCam for HarmonyOS（鸿蒙复现工程）

将上游 [himetuki/WotaGeiCam](https://github.com/himetuki/WotaGeiCam)（Android 录影相机）复现到
**HarmonyOS 6 及以上**（目标 API 版本以 DevEco Studio 当前 SDK 为准，HarmonyOS 6.0 对应 API 20+，
开工时再最终锁定）的移植工程，仅存在于本仓库的 `harmony` 分支。

> 工程骨架（AppScope/、entry/、hvigor 配置等）由 DevEco Studio 生成后放入本目录。
> 本目录在上游 main 分支中不存在，是 harmony 分支的“自留地”。

## 目录约定

```
WotaGeiCamOHOS/
├── AppScope/                 # 应用级配置与资源（app.json5：bundleName com.wotagei.cam）
├── entry/                    # 主模块（EntryAbility + pages/Index）
├── hvigor/                   # hvigor 构建配置（modelVersion 6.1.1）
├── hvigorw / hvigorw.bat     # 构建入口（见下方「构建」：委托 DevEco 自带 wrapper）
├── build-profile.json5       # compatibleSdkVersion 6.0.0(20)，targetSdkVersion 6.1.1(24)
├── docs/
│   ├── UPSTREAM_SYNC.md      # 上游同步流程（黄金规则 + 检查清单）
│   └── PORT_MAP.md           # Android 模块 → 鸿蒙落点映射表
├── scripts/
│   └── sync-upstream.sh      # 一条命令同步上游 main
├── AGENT.md                  # 给 AI 代理的工程导览（改哪些文件、跳过哪些）
└── .gitignore                # DevEco/hvigor 构建产物忽略规则
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
- [x] 媒体库最小版（2026-10-21）：`media/WotaDb.ets`（RDB 四表：media_tag/trash_item/media_series/series_part，version 1）、`MediaRepo`（私有 videos 扫描 + AVMetadata 1.5s 超时 + FNV-1a ID + 标签/回收站联表 + 收藏/入回收站/还原/彻底删除）、`MediaTag`/`MediaFormat`（TagSpace/页签/clampedSelection/时间线/时长/大小）、`pages/Gallery`（全部/收藏/回收站三页签、二次确认删除、元信息面板）、Index/GlPreview 入口；上批 P3×6（帧账录制纪元/sidecar 诊断门控/flush swapFail/上下文丢失提示/实测账整场口径/MEND attach 重试）。留白：播放器/缩略图/相册入库/分享/重命名/自定义标签/系列折叠/自定义页签
- [ ] 相机预览 + 录制链路
- [ ] 光弧工具链（抽帧 / 补弧 / 离线修复）
- [ ] 播放器 / 媒体库 / 剪辑导出
- [ ] 双音轨（内录）与蓝牙面板
- [ ] 更新检查（GitHub Releases）
