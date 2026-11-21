# C1 实时光弧反馈 / 累积拖尾 / 回波双模式（Android `camera`+`core`+`record`/`player` 回波弧 → iOS `Rendering/` + `WotaCore`）

只读基线：`Android/`，提交 `7394187`（提交号与 D1 同源）。本文件记录 **C1 渲染/回波后端**的
Android → iOS 逐项映射、统一接口契约、三后端降级、帧/纹理生命周期、切割取舍与人工验证清单。

- **源码状态**：源码已落，**未经 Apple 工具链编译**（本机 Windows 无 Xcode/swift，见文末「验证状态」）。
- **范围**：仅 `iOS/WotaGeiCam/Rendering/`（`Backend/` + `Shaders/` + `Resources/`）、
  `iOS/Packages/WotaCore/Sources/WotaCore/RenderEchoParameters.swift`、
  `ArcMergeParameters.swift`、本文件。未改动 `App/`、`Camera/`、`Player/`、`UI/`、`Settings/`、
  工程文件、脚本、共享映射，也未改动既有 `RenderBackend.swift`。
- **平台口径**：iOS 10 基线（iPhone 5 armv7 / iPhone 6 arm64），UIKit；纯 Foundation + CoreVideo +
  CoreGraphics +（可选）Metal / OpenGL ES。不用 SwiftUI / Combine / async-await / `@MainActor` / `Codable`。
- **红线**（逐条有源码落点，见后）：**Metal 与 OpenGL ES 源码均为 iOS 侧新写**，不翻译 Android GL/GLSL；
  iPhone 5（armv7）**编译期**不 import/链接 Metal；失败绝不返回黑帧/空帧；累积面尺寸变化即清黑；
  monitor-only（斑马/峰值对焦）与 AI 效果**不进成片渲染接口**；帧契约 BGRA 入 → 同尺寸可写 BGRA 出。

> 状态词：`源码已落（未编译）` ｜ `保留（平台差异）` ｜ `排除（AI，不移植）` ｜ `不在本任务范围`。
> 本文件不使用「完成/通过」等字样。

## 1. 统一帧后端协议与工厂（三条链的唯一图像处理入口）

`FrameRenderBackend`（`Backend/FrameRenderBackend.swift:73`）是实时取景、成片录制（B2）与离线导出（C2）
共用的**一帧进、一帧出**协议；不碰相机 API、不碰编码器、不改时间戳、不引音频。

| 成员 | 语义 | 落点 |
|---|---|---|
| `kind: RenderBackend` | `.metal` / `.openGLES` / `.cpu` | `FrameRenderBackend.swift:76` |
| `isOperational: Bool` | 构造成功且关键资源就绪；false 时调用方换下一个后端 | `:79` |
| `renderQueue: DispatchQueue` | **单一串行渲染队列**，所有 `process*`/`release` 都在其上 | `:82` |
| `accumulationSize: CGSize` | 当前累积面像素尺寸（诊断用） | `:85` |
| `resetEchoHistory()` | 换镜头/转屏/模式或拖尾档切换/重开时清累积面（R2 §五.2/§五.6） | `:91` |
| `processEcho(frame:parameters:)` | 反馈累积 + 双模式合成 + 拖尾着色 | `:100` |
| `processArcMerge(frame:parameters:)` | 抽帧补弧差分死区整像素合并 | `:108` |
| `previewImage(for:)` | **可选**输出帧→`CGImage`（仅上屏调试，默认 nil，不参与成片） | `:111,118` |
| `release()` | 释放全部后端资源，之后可惰性重建 | `:114` |

**帧契约**（coordinator 集成约定）：输入 `kCVPixelFormatType_32BGRA` 的 `CVPixelBuffer`，尺寸任意；
输出**同尺寸可写** BGRA `CVPixelBuffer`，几何（宽/高/像素格式）由 `RenderFrameOutput` 回带
（`FrameRenderBackend.swift:23`）；色彩空间与呈现时间戳由上层持有，后端不读取也不改写。
`RenderFrameGeometry`（`:122`）统一做格式校验（`validateBGRA` `:125`）与 IOSurface-backed BGRA 输出分配
（`makeBGRAOutput` `:134`，便于再映射为 GL/Metal 纹理）。所有失败都是**显式错误**
（`RenderBackendError` `:42`），绝不静默返回黑帧。

**工厂入口**：`RenderBackendFactory.makeEchoBackend(preferred:capabilities:)`
（`Backend/RenderBackendFactory.swift:44`）→ `makeBackend`（`:24`）。按运行时能力在 Metal → OpenGL ES → CPU
中选第一个 `isOperational` 的后端；CPU 恒可用，故工厂**不会返回 nil**，也**不返回占位后端**。
`instantiate`（`:52`）对 Metal/OpenGL ES 分支再做**编译期**隔离（见 §5）。

**运行时能力探测**：`RenderRuntimeCapabilities.current()`（`Backend/RenderRuntimeCapabilities.swift:37`），
`availableKinds`（`:45`）委托 `WotaCore.RenderBackendPolicy.preferredBackends`（既有文件，未改）。

## 2. 参数模型（纯逻辑，三链同源）

`WotaCore` 侧新增两份纯逻辑模型，无 UIKit/图形 API 依赖，实时/录制/离线共享同一语义与同一份域钳。

| 结构 | Android 源 | iOS 落点 |
|---|---|---|
| `RenderEchoMode{keepBrightness,trail}` | `core/Echo.kt:72` `enum class EchoMode` | `RenderEchoParameters.swift:23`（`androidName` 与 `EchoMode.name` 同字） |
| `RenderTrailColorMode{off,add,subtract,only,except}` | `core/Echo.kt:82` | `RenderEchoParameters.swift:52` |
| 倍数域 2..10 | `core/Echo.kt` `ECHO_MULT_MIN/MAX`（R2 §四.3） | `renderEchoMultiplierMin/Max`（`:84-85`） |
| TRAIL 阈值域 0.6..1.0 | `core/Echo.kt:89` | `renderEchoThresholdMin/Max`（`:88-89`） |
| TRAIL 强度上限 2 | `core/Echo.kt:95` | `renderEchoGainMax`（`:92`） |
| 拖尾色默认白 / ARGB 掩码 | `core/Echo.kt:102` | `renderTrailColorDefault/renderARGBMask`（`:95,98`） |
| 择/除容差域 0.05..0.8 | `core/Echo.kt:105-106` | `renderTrailToleranceMin/Max`（`:101-102`） |
| `EchoParams{DEFAULT,sanitize}` | `core/Echo.kt:32,67` | `RenderEffectParameters.default`（`:151`）、`sanitized()`（`:176`） |
| 倍数→衰减 `echoDecayOf` | `core/Echo.kt:118`（`0.5^(1/(N−1))`） | `renderEchoDecay(multiplier:)`（`:194`） |
| 拖尾门控/染色/色距 uniform | `core/Echo.kt` `echoTrailMaskOf/TintOf/TintRgbOf/TolOf` | `renderTrailMaskUniform/TintModeUniform/TintRGB/Tolerance`（`:202,212,222,232`） |
| 掉键兼容的复合串 `encodeEcho/decodeEcho` | `core/Echo.kt`（键序 `enabled;mode;…`） | `renderEncodeEcho/renderDecodeEcho`（`:245,261`；坏值整串回默认） |

`RenderEffectParameters` 字段：`isEnabled / mode / multiplier(2..10) / highlightThreshold(0.6..1.0) /
trailGain(0..2) / trailColorMode / trailColor(ARGB) / trailTolerance(0.05..0.8)`；
派生 `backwardWindowFrames`（= 钳后倍数，离线预读用）、`feedbackDecay`（= `renderEchoDecay`）、
`clampedMultiplier`。**倍数变化不清累积面，模式/拖尾档变化要清**（`resetEchoHistory` 调用时机由上层把握）。

`ArcMergeParameters{deadband:0..32,default 12}`（`ArcMergeParameters.swift:22`）：`sanitized()`（`:35`）、
`deadbandLuma`（`:40`，= deadband/255）；纯函数 `arcMergeClamp/arcMergeLuma/arcMergeSelectPixel/arcMergeLumaOfRGB`。
对照 Android `core/Constants.kt:105` `object ArcMergeDeadband`（`DEFAULT=12 / MIN=0 / MAX=32 / luma 归一`）。

## 3. 回波算法（Android → iOS 语义对照，代码重写）

拓扑对齐 Android「方案 B：**单张反馈累积面 + 乒乓双 RT**」（`camera/EchoChain.kt:45`、
`camera/GlRenderEngine.kt:2757 drawEchoPass`）：`acc_t = f(cur_t, k·acc_{t−1})`，写读不同体，**无 feedback loop**；
首帧 `prev` 视为黑 ⇒ 恒等；尺寸变化即重建并清黑（`EchoChain.kt:282 ensureRenderTarget` / `:322-323` 补清）。

| 趟 | 算式 | Android 源 | iOS 落点（GLSL / Metal / CPU） |
|---|---|---|---|
| 累积·保持亮度 | `acc = max(cur, k·prev)` | `camera/Shaders.kt:880 ECHO_ACCUM_FS` / `:215 SCENE_ECHO_ACCUM_FS` | `EchoGLESShaders.echoAccumFragment`、`EchoMetalShaders`、`CPUFrameBackend.accumulateEcho` |
| 累积·提亮拖尾 | `t=smoothstep(thr∓0.05,luma); acc=cur·t + k·prev` | 同上（`Shaders.kt:900,916`） | 同上（择/除改用 RGB 峰值并按色度门控，`plan/35`） |
| 合成 | `max(base,echo)` / `min(base+echo·gain,1)`（可先按加/减染色） | `Shaders.kt:264 SCENE_COMPOSITE_ECHO_FS`、`ECHO_DECL:925` | `EchoGLESShaders.echoCompositeFragment`、Metal `echoCompositeFragment`、`CPUFrameBackend.compositeEcho` |
| 直通拷贝（关/首帧兜底） | `out=cur`（**不是黑帧**） | —（iOS 新增兜底趟） | `copyFragment` / `echoCopyFragment` / `CPUFrameBackend.copyWorkToAccWrite` |

`k = renderEchoDecay(multiplier)`（N=2→0.5，N=10→0.926），拖尾有效长度随 N 单调变长。拖尾色补丁五模式
（OFF/ADD/SUBTRACT/ONLY/EXCEPT）只在 TRAIL 档参与，`KEEP_BRIGHTNESS` 档恒不生效（`plan/35` U1/E7）。
**本任务只做回波（echo）+ 补弧（arcMerge）两条趟**；Android 链上的 Glow（`core/Glow.kt:100 glowRgb` /
`:296 glowRtSizeInto`）**不在 C1 范围**，故 `applyEcho` 在此是 echo-only，不内联 glow（见 §7 取舍）。

## 4. 抽帧补弧（差分死区，在线/离线同一拓扑）

对照 Android `camera/Shaders.kt:552 ARC_MERGE_FS`、`camera/ArcMendPass.kt:133 accumulate`、
`record/ArcRepairGl.kt:218 accumulate`、`player/SceneExportGl.kt` 的弧趟。判据是**差分**死区：
`Δluma = luma(被抽帧) − luma(保留帧)`，`Δluma > uDeadbandLuma`（严格大于）时整颗像素取被抽帧，否则保留。

iOS：`processArcMerge`（GLES `OpenGLESFrameBackend`、Metal `encodeArcMerge`、CPU `CPUFrameBackend.arcMerge:292`）
首帧 = 原样拷贝入累积面，其后 = 与累积面的死区选择结果。源像素门控/色度判亮沿用 §3 的 `RenderEffectParameters`
纯函数，DQ 值走 `ArcMergeParameters.deadbandLuma`。

## 5. 三后端实现与降级（Metal / OpenGL ES / CPU）

### 5.1 Metal（仅 arm64 编译）

`Backend/MetalFrameBackend.swift`，整文件包在 `#if arch(arm64) && canImport(Metal)` 内：
armv7（iPhone 5，A6）**根本不编译本文件、不 import Metal、不引用任何 Metal 符号**（防 armv7 链接风险，
不只是运行时判断）。工厂 `instantiate`（`RenderBackendFactory.swift:55`）同样只在 arm64 分支 `return MetalFrameBackend()`，
armv7 直接 `return nil`。运行期再叠加 `MTLCreateSystemDefaultDevice()` 检查（A7+ 才同时满足）。
两张乒乓 `MTLTexture(.bgra8Unorm, [.renderTarget,.shaderRead])` + 4 条 `MTLRenderPipelineState`
（累积/合成/拷贝/补弧），逐帧一条 command buffer 顺序编码，`waitUntilCompleted()` 后才返回（同步帧契约）。
累积面重建或 `resetEchoHistory()` 后会先用一次 clear pass 清黑两张乒乓面（累积趟只清写面，prev 面必须单独清），
否则首帧会把未初始化/陈旧的 prev 面当历史叠加。

Metal 着色器**运行期现场编译**：经典工程不把 `.metal` 收进 Sources/Resources（Xcode 对 `.metal`
有专门编译语义，放 Resources 不是可移植做法），故后端经 `device.makeLibrary(source:options:)`
编译内嵌文本；文本由 `EchoMetalShaders.source()`（`Shaders/EchoMetalShaders.swift`）返回
`embeddedSource`。`Rendering/Resources/EchoKernels.metal` 是供审阅的参考副本，
`check_project.py` 校验两者逐行一致，避免双份漂移。

### 5.2 OpenGL ES 2（iOS 10 / iPhone 5 可用）

`Backend/OpenGLESFrameBackend.swift`，整文件包在 `#if canImport(OpenGLES)` 内。`EAGLContext(api:.openGLES2)`
+ `CVOpenGLESTextureCache`：输入/输出 `CVPixelBuffer` 映射为普通 `sampler2D`（**非** `samplerExternalOES`，
故无纹理矩阵、无 OES 扩展、无相机会话依赖）。两张乒乓 RGBA8 纹理 + FBO，`ensureAccumulation` 尺寸变化即重建、
`clearAccumulation` 清黑，`release()` 删程序/纹理/FBO/缓存。GLSL ES 100 见
`Shaders/EchoGLESShaders.swift`（`fullscreenVertex:15`、`copyFragment:26`、`echoAccumFragment:39`、
`echoCompositeFragment:82`、`arcMergeFragment:106`）。

P1-05 审查（`docs/reviews/ios2026-11-19/P1-05-opengles-backend.md`）修复的运行期/编译点：

- **顶点指针生命周期**：`drawQuad` 把 enable → `glVertexAttribPointer` → `glDrawArrays` → disable 全部放进
  `positions.withUnsafeBufferPointer` / `texCoords.withUnsafeBufferPointer` 的嵌套作用域。`glVertexAttribPointer`
  保存裸指针、draw 时才解引用，闭包外继续使用是未定义行为；此前 disable 发生在 draw 之前，两个属性都是
  disabled，画面会全黑/垃圾。现用客户端数组（GLES2 原生支持），指针随闭包存活到 draw 之后。
- **`internalFormat` 类型**：`CVOpenGLESTextureCacheCreateTextureFromImage` 的 `internalFormat` 位置改为
  `GLint(GL_RGBA)`（原先 `GLenum`，Swift 不隐式转换 `UInt32→Int32`，是编译阻断）。`format` 仍为 `GL_BGRA`。
- **上下文/线程生命周期**：`EAGLContext.setCurrent` 在 `init` / `release` / `withContext` 均保存并恢复进入前
  的当前上下文（不再无条件置 nil）；`resetEchoHistory()` 与 `release()` 通过 `DispatchSpecific` 标记同步落回
  `renderQueue`，避免与渲染中的 `process*` 并发触碰 GL。`release()` 后 `process*` 走 `prepareResources()` 惰性重建。
- **同步帧契约**：帧尾由 `glFlush()` 改为 `glFinish()`，等 GPU 真正写完再返回，供 B2 编码器读取输出。
- **首帧 / 关闭直通 / 同尺寸**：首帧 `prev` 面由 `ensureAccumulation` 清黑；`isEnabled == false` 走 `copy` 直通
  （非黑帧）；同尺寸复用前校验两张纹理与两张 FBO 均非 0，并处理 `resetEchoHistory()` 遗留的清黑请求。


### 5.3 CPU 兜底（真降级，绝不返回黑帧）

`Backend/CPUFrameBackend.swift`：基帧保留**全分辨率**（清晰），仅**回波累积面**在内部工作分辨率上做
（`maxWorkingEdge=1280`，`:22`），合成时把累积面双线性上采样回全分辨率（`:238 compositeEcho`）。
每帧输出 **真实逐像素处理后的同尺寸 BGRA 帧**（`:283-286` 写回 `CVPixelBuffer`）。核心参数入口**全保留**
（模式/倍数/衰减/阈值/强度/拖尾色模式/色/容差/补弧死区照常生效），仅拖尾细节粒度受工作分辨率限制 —— 拖尾本就柔和，
观感可接受。这是**显式 notes 的降分辨率降级**，不是静默丢参数、更不是占位空帧。

### 5.4 降级链

| 设备 | 编译期 | 运行期可用链 |
|---|---|---|
| iPhone 5/5c（A6，armv7，iOS 10） | **不含 Metal 分支** | OpenGL ES 2 → CPU |
| iPhone 5s..（A7+，arm64） | 含 Metal 分支 | Metal → OpenGL ES 2 → CPU |
| Metal 设备缺 `MTLCreateSystemDefaultDevice` | 含分支但探测 false | OpenGL ES 2 → CPU |
| 全部 GPU 后端不可用 | — | CPU（恒 `isOperational`） |

## 6. 帧 / 纹理生命周期与资源释放

| 事件 | Metal | OpenGL ES | CPU |
|---|---|---|---|
| 输入映射 | `CVMetalTextureCacheCreateTextureFromImage(.bgra8Unorm)` | `CVOpenGLESTextureCacheCreateTextureFromImage(GL_BGRA)` | 直接锁 `BaseAddress` |
| 输出映射 | 同输入、`renderTarget` 用途 | 同输入、渲进 RGBA 输出纹理 | 直接写输出缓冲 |
| 累积面建立 | 尺寸变→重建两 `MTLTexture` | 尺寸变→重建两纹理+FBO | 尺寸变→重建 Float 数组 |
| 清黑 | 累积趟 `loadAction=.clear` 黑 | `clearAccumulation()` `glClearColor(0,0,0,1)` | `ensureWorkingSize` 归零 |
| 通读 | `waitUntilCompleted()` 后返回；`CVMetalTexture` 存活至完成 | 末尾 `glFinish()` 等 GPU 写完；`CVOpenGLESTexture*` 局部存活至作用域尾 | 同步逐像素回写 |
| 上下文复位 | 无（Metal 无线程局部 current） | `setCurrent` 保存/恢复进入前上下文；`resetEchoHistory`/`release` 同步落回 `renderQueue` | 无 |
| 释放 | `release()` 清纹理/刷新缓存 | `release()` 删程序/纹理/FBO/缓存 | `release()` 清数组 |

清累积触发点（`resetEchoHistory`）：换镜头 / 转屏 / **模式或拖尾档切换** / 显式关闭再开启 /
尺寸变化（R2 §五.2/§五.6）；**倍数变化不清**（k 只影响往后衰减）。

## 7. 平台差异与取舍

| ID | Android | iOS 取舍 | 判定 |
|---|---|---|---|
| C1-PD-01 | GL 链吃 `samplerExternalOES`（相机 OES 纹理，带纹理矩阵） | 后端输入/输出都是普通 2D 纹理（`CVPixelBuffer` 映射），无 OES/矩阵 | `保留（平台差异）` |
| C1-PD-02 | Metal 不可用则整机走 GL | Metal 用 `#if arch(arm64) && canImport(Metal)` **编译期**隔离，armv7 不链接 | `保留（平台差异）` |
| C1-PD-03 | `.metal` 由 Gradle/工程编译 | 当前 generator 只收 `.swift` 源；`.metal` 作资源、后端运行期 `makeLibrary(source:)` 编译 | 接口待办（见 §8） |
| C1-PD-04 | 离线链把 echo 包在 Glow 外层（`SceneExportGl.kt:39`） | **Glow 不在 C1 范围**，`applyEcho` 为 echo-only；Glow/降噪由各自任务接入 | `不在本任务范围` |
| C1-PD-05 | 录制期空间门槛等媒体行为 | 不引入；帧后端只管像素，媒体行为归 D1/B2 | `不在本任务范围` |
| C1-PD-06 | monitor-only（斑马/峰值对焦）与 AI 效果 | **不进本渲染接口**（`FrameRenderBackend` 只有 echo/arcMerge 两条 `process*`） | `排除（AI，不移植）` |

## 8. 接口契约（供 B2 录制 / C2 离线接入）

- **入口**：`RenderBackendFactory.makeEchoBackend(preferred:capabilities:) -> FrameRenderBackend`
  （`RenderBackendFactory.swift:44`）。`preferred` 可传设置里强制的后端；`capabilities` 可复用一次探测。
- **单帧调用**（`processEcho` / `processArcMerge` / `resetEchoHistory()` / `release()` **全部**须投到
  `backend.renderQueue`，同步执行，不用阻塞式同步回调）：
  - `try backend.processEcho(frame:parameters:) -> RenderFrameOutput`
  - `try backend.processArcMerge(frame:parameters:) -> RenderFrameOutput`
- **队列约束与回退**：后端是同步帧契约；`process*` 抛 `RenderBackendError` 时**绝不返回黑帧/空帧**。
  调用方收到错误应保留上一帧或直接把输入帧直通输出（`out = frame`），再用工厂重新探测/换一个后端，
  不在已判定不可用的实例上盲目重试。OpenGL ES 后端的 `resetEchoHistory()` / `release()` 内部会同步落回
  `renderQueue`，但调用方仍应保持四类调用在同一条渲染队列上的有序投递。
- **状态共享警告**：同一实例的 `processEcho` 与 `processArcMerge` **共用同一套乒乓累积面**，不要在同一实例上
  交替驱动两条独立流；需要交错/并行时各取独立后端实例。
- **重置 / 释放**：`backend.resetEchoHistory()`（模式/朝向/档位变化时）、`backend.release()`（停录/退屏）。
  `release()` 后 `process*` 可惰性重建资源（OpenGL ES 后端经 `prepareResources()`，Metal 保留管线/缓存）。
- **返回值**：`RenderFrameOutput{pixelBuffer:CVPixelBuffer, width:Int, height:Int, pixelFormat:OSType}`；
  `pixelFormat` 恒 `kCVPixelFormatType_32BGRA`；`frame` 期望同格式。
- **可选上屏**：`backend.previewImage(for:) -> CGImage?`（默认 nil）。

**集成待办（交后续任务，不在本节实现）**：

1. B2 录制链在采集到相机帧后，拷贝/交给 2D `CVPixelBuffer` 再投 `processEcho`，编码前再 `processArcMerge`
   （补弧属录制期转换）；音频/时间戳仍由录制链持有。因两条 `process*` 共享同一实例的累积面，回波预览与
   补弧录制若需交错/并行，**各取独立后端实例**，不要共用同一个实例交替驱动。
2. C2 离线链用 `SceneExportPlan` 等价的逐帧计划驱动 `processEcho`（全分辨率、顺序跑），`multiplier` 走同一
   `RenderEffectParameters`。
3. App 启动设一次 `RenderRuntimeCapabilities.current()` 供工厂复用；`Settings` 可暴露后端偏好。
4. 生成工程时重跑 `scripts/generate_project.py` 收纳 `Rendering/**/*.swift` 为新 Sources（本任务不写工程文件）。

## 9. 人工验证清单（真机，未执行）

- MV-C1-01 保持亮度：开启回波（KEEP_BRIGHTNESS，2×）对准高光环，静态背景不动、光源拖出星轨弧。
- MV-C1-02 提亮拖尾：切 TRAIL（2×，阈值 0.9），只有高光拖尾、背景不跟着重影；调阈值/强度观感随之变。
- MV-C1-03 倍数单调：2×→10× 拖尾逐渐变长；拨回低倍数**不闪断**（不清累积面）。
- MV-C1-04 拖尾色五模式：OFF/ADD/SUBTRACT/ONLY/EXCEPT 逐个切换观感符合定义，仅 TRAIL 档生效。
- MV-C1-05 清累积：换镜头/转屏/切模式或拖尾档 → 旧残影立即消失，不残留到新画面。
- MV-C1-06 补弧死区：抽帧转换开补弧，D=0 与 D=32 的弧亮度明显不同；D 越大越保守（少取被抽帧）。
- MV-C1-07 关闭零副作用：`isEnabled=false` 输出与原帧**逐像素一致**（走直通拷贝，非黑帧）。
- MV-C1-08 iPhone 5（armv7）：能装能跑，走 OpenGL ES 2，**不触发 Metal**（无 Metal 符号/无崩溃）。
- MV-C1-09 降级：人为禁用 GPU 后端 → 走 CPU，输出为真实处理帧（非黑），参数仍生效（拖尾略柔）。
- MV-C1-10 尺寸变化：预览/录像尺寸切换后无花屏、无旧尺寸残影，累积面按新尺寸清黑。
- MV-C1-11 资源释放：反复进出相机页/停录起录，无 GL/Metal 资源泄漏告警、内存不持续增长。

## 10. 验证状态（本机 Windows，无 Apple 工具链）

- 源码已落，**Apple 编译/归档未执行**：本机 `command -v xcodebuild` 与 `command -v swift` 均不存在
  （见 `iOS/README.md`）。不以结构检查替代编译，不声称「编译通过」。
- **未写测试、未跑模拟器、未截图**（用户指示）。
- **P1-05 审查修复（静态，未编译）**：已修 `OpenGLESFrameBackend` 的顶点指针作用域（enable→pointer→draw→disable
  同处一对 `withUnsafeBufferPointer`）、`CVOpenGLESTextureCacheCreateTextureFromImage` 的 `GLint` `internalFormat`、
  `EAGLContext.setCurrent` 保存/恢复与 `resetEchoHistory`/`release` 的队列串行、帧尾 `glFinish`、
  `release()` 后惰性重建；并修 `MetalFrameBackend` 首帧/重置后累积面未清黑（prev 面未初始化/陈旧叠加）。
  以上均为**源码级修复**，无 Apple 工具链验证，不声称编译通过。
- **静态核对**：`WotaCore/RenderEchoParameters.swift` 与 `ArcMergeParameters.swift` 逐项复核域钳、复合串
  解码、派生量与死区选择，**未发现确证缺陷**，本轮未改动。`Rendering/**` 内未使用 iOS 13+ 或 iOS 10 之外 API。
- **.metal 唯一性**：`iOS/` 内仅有 `Rendering/Resources/EchoKernels.metal` 一个 `.metal` 参考副本，
  运行期不读取它（只使用 `EchoMetalShaders.embeddedSource`）；`check_project.py` 校验参考副本与内嵌源一致。
- 已做的静态核对：`python -I iOS/scripts/check_project.py` 对本层新文件**无禁令牌命中**
  （`import SwiftUI`/`Combine`/`@MainActor`/`UTType`/` await `/`Task {` 等 FORBIDDEN_TOKENS 均未出现在
  Render/Glow/Arc 文件中），无 `stance`。该脚本当前仅报「新 `.swift` 未被 Sources 阶段收录」与
  「generator 待重跑」/「README 缺标记」——均属**工程生成侧**、由主代理重跑生成脚本处理，**非本层源码缺陷**。
- `--core-mode inline` 兼容：本层所有 `import WotaCore` 一律包在 `#if canImport(WotaCore) … #endif` 内
  （`FrameRenderBackend`/`RenderRuntimeCapabilities`/`RenderBackendFactory`/`CPUFrameBackend`/`OpenGLESFrameBackend`/
  `MetalFrameBackend` 共 6 处），inline 模式下 WotaCore 源直接编入 App target、无独立模块可 import 时也能编。
- iOS 10 门控：本层未使用任何 iOS 13+ API（无 `UIAction`/`UIMenu`/`DiffableDataSource`/`UIWindowScene`/
  `.systemBackground`/`UTType` 等；`MTLTextureUsage`/`CVPixelBufferLockFlags` 等均在 iOS 10 内），故无需 `#available`。
- Metal 隔离：所有 Metal 类型/实例化（`import Metal`、`MTLDevice`、`CVMetalTextureCache*`、
  `MTLCreateSystemDefaultDevice()`、`MetalFrameBackend` 实例化、`EchoMetalShaders`）都包在
  `#if arch(arm64) && canImport(Metal)` 内；armv7 目标不解析任何 Metal 符号。
- 新增文件清单（行数，P1-05 修复后）：`WotaCore/RenderEchoParameters.swift`(321)、`ArcMergeParameters.swift`(77)、
  `Rendering/Backend/{FrameRenderBackend(161),RenderRuntimeCapabilities(74),RenderBackendFactory(73),
  CPUFrameBackend(439),OpenGLESFrameBackend(590),MetalFrameBackend(353),RenderPixelBufferPreview(39)}.swift`、
  `Rendering/Shaders/{EchoGLESShaders(120),EchoMetalShaders(140)}.swift`、`Rendering/Resources/EchoKernels.metal`(122)。
- 工程侧：新增 `Rendering/**/*.swift` 需在生成工程时纳入 Sources（`scripts/generate_project.py` 自动发现），
  由主代理重跑生成脚本，不在本任务写入工程文件。
