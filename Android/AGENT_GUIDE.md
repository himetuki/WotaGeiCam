# Android 开发指南：给 Forker 与编码 Agent

> **源码核对日期：2026-11-16。** 核对基于仓库提交 `4e897ce` 所在工作树的 Android 源码；该快照最近一次 Android 修改为 `fa0ac31`。本文是开发导航和兼容性契约，不替代源码、自动化测试或设备验收。
>
> 本文中的应用源码短路径，例如 `core/WotaParams.kt`，除非另有说明，均相对于 `Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/`。所有命令都注明起始目录。**文件名、持久化键、枚举名、bit 位、序列化字段和界面文案是不同的命名空间，不能混作一张可随意重命名的表。**

## 目录

1. [项目边界与阅读顺序](#scope)
2. [十分钟上手](#quick-start)
3. [仓库、模块与文件结构](#tree)
4. [工具链、变体与构建](#build)
5. [测试与验收](#verification)
6. [架构、数据流与资源归属](#architecture)
7. [键名体系与持久化字典](#keys)
8. [相机参数、渲染与录制契约](#camera-contracts)
9. [界面、控件定位与多语言修改](#ui)
10. [媒体库、播放器与文件协议](#media)
11. [AI、更新、备份与诊断](#services)
12. [按任务定位：改哪里、同步哪里、测什么](#recipes)
13. [Fork 身份、发布与提交前清单](#fork)
14. [源码索引与文档维护](#sources)

<a id="scope"></a>
## 1. 项目边界与阅读顺序

### 1.1 产品目标

WotaGeiCam 是面向 **WOTA 艺日常练习与临时录制** 的个人开源应用，不是全场景相机或专业摄影工具。核心场景是夜间荧光棒的高光连续光弧，常态单条 **1～4 分钟，通常不超过 5 分钟**；长录、换段和极限散热是边界验证，不应反过来替代产品基线。正式企划、演出等不可重拍的完整记录不属于可靠性承诺范围。

安卓端的主要技术路线：

- **Camera2 直用**，不是 CameraX；设备能力在运行时读取和协商。
- Kotlin + Jetpack Compose，单 Activity + Navigation Compose。
- 自研 EGL / OpenGL ES 管线，区分预览监看与写入成片的场景效果。
- MediaRecorder、MediaCodec + MediaMuxer 两套录制引擎。
- Media3 播放，MediaStore 管理视频文件，Room 保存应用侧元数据。
- full / lite 通过源集隔离 AI 站位功能；不是两套相机工程。

### 1.2 新 Agent 的推荐阅读顺序

1. 先读 [Android README](README.md) 的适用范围、功能位置、隐私和已知限制。
2. 按本文的[目录图](#tree)确认当前任务归属的包，不要从大文件第一行一路读到底。
3. 读[架构与 owner 表](#architecture)，确定谁拥有状态、线程、Surface 和长任务。
4. 改设置或参数前，查[键名字典](#keys)；改 UI 前，查[锚点与控件纪律](#ui)。
5. 找到对应生产入口和一组现有测试，再开始修改；用[任务配方](#recipes)检查连带修改。
6. 明确报告哪些是源码证明、哪些是 JVM 验证、哪些经过设备验证，不混用证据等级。

**公开 Fork 不依赖作者本机材料。** 根目录的 `docs/`、`camera-knowledge/`、`tools/`、`tasks/`、`deliverable/` 等被忽略；`Android/AGENTS.md` 也是本机材料，不保证随 clone 提供。源码注释中出现的 `docs/plan/NN`、历史任务编号和本机绝对路径只是历史背景，不能当成 Fork 上必定存在的构建依赖。公开开发入口以本文、已跟踪源码和测试为准。

**常见“按名字找不到”的类**（先看这张表，少走弯路）：

| 你可能在找 | 实际位置 |
|---|---|
| `SettingsStore` / `settings/` 包 | 不存在；设置是 `ui/SettingsScreen.kt` 里的 `object WotaSettings` |
| `UserBackupCodec` | 不存在；备份编解码是 `backup/PortableUserData.kt` + `WotaBackupAgent` |
| `RecordController` | 不存在；录制控制是 `ui/CameraScreen.kt` 内的 `RecordRunner` |
| `PlayerViewModel` | 不存在；播放是 `player/PlayerEngine.kt`，导出宿主是 `player/ExportHost.kt` |
| `WotaNav` | 不在 `ui/`；在 `media/GalleryScreen.kt` |
| `enum/` 包 | 不存在；枚举散在 `core/Constants.kt`、`core/Denoise.kt`、`core/UIOrientation.kt`、`core/WotaParams.kt`（`DisplayScale`）、`ui/anim/Motion.kt`（`MotionMode`） |
| `SettingsKeys.kt` | 不存在；键常量在 `WotaSettings` 内部逐条声明 |

### 1.3 十条铁律（先记住）

1. **UI 不直接碰 Camera2**：UI 写 `WotaParams`，引擎读快照组请求。
2. **监看叠加绝不进成片**：`movieProgramKey` 强制 `effect = NONE`，不要在别处再写一份判断。
3. **能力来自运行时**：`CameraCharacteristics` + 产品档求交；禁止按机型写死数值。
4. **帧率只有 `setFps` 一个写入口**；帧周期用选中的 fps 值算，不用区间下界。
5. **`ParamsPersist` 只有 34 项**；自动降级、瞬时态、音源、站位线线数一律不入档。
6. **键名 / bit 位 / 枚举值 / `P<n>`/`H<n>` ordinal / Room schema 是用户数据契约**：只追加，不改名，不插队。
7. **Room 改 schema 必须写 Migration**；否则标签、收藏、回收站、系列会被清空。
8. **相机与导出属于窗口**；用户发起的导出不因离开页面而取消。
9. **不要 uninstall 排障**；签名不同的包覆盖安装会清空用户数据。
10. **测试绿不等于设备对**；推送 / tag / Release 必须单独取得授权。

### 1.4 术语速查

| 术语 | 含义 |
|---|---|
| HUD | 取景页常驻叠加层（读数块 + 控件） |
| 胶囊 / pill | 点按会弹参数面板的控件；身份是 `PillKey` / `CamPill` |
| `HudItem` | 读数块里的一个读数项（快门 / 帧率 / ISO…） |
| ladder / 刻度梯 | 产品档位与设备能力求交后的可选刻度；滑杆可走索引空间 |
| scene layer | 进成片的画面效果：曲线、色调、Glow、残影、降噪 |
| monitor layer | 只上屏的监看叠加：斑马、峰值、参考线、站位线、水平仪、霜底板 |
| DISPLAY / MOVIE | GL 的两个 program 槽：上屏 / 编码 |
| `ProgramKey` | 五维 program 缓存键 `(effect, curve, denoise, glow, echo)` |
| DIRECT | 绕过 GL 的渲染模式（相机面直连编码器） |
| ENV / CAP | 环境音 / 内录音轨 |
| pending | MediaStore 两段式写入中的未完成行（`IS_PENDING=1`） |
| sidecar | 与视频同名的 `.drops.json` 抽帧位次记录 |
| exact / approx | 帧率精确档 / 近似档（UI 标 `※`） |
| deadband（D） | 补弧合并的差分死区，0..32，默认 12 |
| MEND / DROP | 抽帧补弧 / 仅抽帧 |
| KB / TRAIL | 残影“保持亮度” / “提亮拖尾” |
| owner | 某线程、Surface、编解码器或长任务的唯一负责方 |
| BUSY | 上一轮任务未收尾，拒绝新请求（不是失败） |

<a id="quick-start"></a>
## 2. 十分钟上手

### 2.1 先确认工作树，不覆盖别人的现场

在**仓库根目录**执行：

```bash
git status --short
git branch --show-current
git log -1 --oneline -- Android
```

- 本仓库是多平台 monorepo；当前分支名字可能是 iOS 移植分支，但安卓工程仍在 `Android/`。
- 存在未提交修改时，先划清归属；不要用 `reset --hard`、`clean -fd`、全仓格式化或 `git add -A` 代替范围管理。
- 两个会话同时开发时，优先使用各自分支 / worktree，避免共同修改同一棵树。
- 不读取或复制 `.env`、签名文件、`keystore.properties`、本机私密配置；不要把配置值、设备素材或诊断导出件写入公开文档。

### 2.2 最小构建入口

安装 JDK 17、Android SDK Platform 34 和 Build-Tools 33.0.1；SDK 通过本机 `ANDROID_HOME` 或标准 `sdk.dir` 配置提供。第一次构建需要下载公开 Maven / Gradle 依赖，运行应用的联网约束不等于构建过程不联网。

在**仓库根目录**开始：

```bash
cd Android/WotaGeiCamApp

# Git Bash / Linux / macOS：使用 bash 避免仓库 gradlew 的执行位问题。
bash ./gradlew :app:assembleLiteDebug

# 需要 AI 时再构建 full。
bash ./gradlew :app:assembleFullDebug
```

PowerShell 下在同一 Gradle 根目录使用 `./gradlew.bat` 替代 `bash ./gradlew`。

如果构建提示 Gradle JDK 的本机绝对路径不存在，先保证 `JAVA_HOME` 指向 JDK 17，再给命令增加 `-Dorg.gradle.java.home="$JAVA_HOME"`，不要照抄作者机器路径。无法定位任务时运行 `bash ./gradlew :app:tasks --all`；不要靠猜旧版无 flavor 的产物路径。

如果离线运行时报 `Plugin [id: 'com.android.application', version: '8.1.1'] was not found`，说明本机 Gradle 缓存里没有 AGP 插件标记；去掉 `--offline`，让 Gradle 从 Google Maven / Gradle Plugin Portal 解析，或先准备好依赖缓存。**离线模式只适用于已经成功构建过、缓存完整的机器。**

最小产物：

| 命令 | 默认产物路径（相对于 Gradle 根目录） |
|---|---|
| `:app:assembleLiteDebug` | `app/build/outputs/apk/lite/debug/app-lite-debug.apk` |
| `:app:assembleFullDebug` | `app/build/outputs/apk/full/debug/app-full-debug.apk` |
| `:app:assembleLiteRelease` | `app/build/outputs/apk/lite/release/` |
| `:app:assembleFullRelease` | `app/build/outputs/apk/full/release/` |

这些路径假定没有传自定义产物名或开启 ABI splits。安装前核对签名和包名；**不要为解决签名冲突直接卸载有用户数据的应用**。签名不同的 Fork 应使用独立包名，或由用户明确接受卸载 / 数据迁移方案。

### 2.3 最小定位命令

在**仓库根目录**执行；所有搜索限定在公开源码，不扫私密配置：

```bash
# 找类型或函数，而不是扫描 build/ 中的生成副本。
rg -n 'class CameraController|class Camera2Engine|class GlRenderEngine' \
  Android/WotaGeiCamApp/app/src/main/java

# 查某个持久化字面值的生产者、消费者与测试。
rg -n 'hud_pills|default_fps' Android/WotaGeiCamApp/app/src --glob '*.kt'

# 查看当前业务源码与测试的文件名。
rg --files Android/WotaGeiCamApp/app/src -g '*.kt' -g '*.xml' -g '!**/build/**'
```

<a id="tree"></a>
## 3. 仓库、模块与文件结构

```text
WotaGeiCam/
├─ Android/
│  ├─ README.md                       用户功能与构建简介
│  ├─ AGENT_GUIDE.md                  本文：公开的 Agent / Forker 开发入口
│  ├─ WotaGeiCam_Android_Icons/        图标设计素材，不是 Gradle 应用模块
│  └─ WotaGeiCamApp/                  Android Gradle 根目录
│     ├─ settings.gradle.kts         仓库与模块注册：:app、:baselineprofile
│     ├─ build.gradle.kts            AGP / Kotlin / KSP / Baseline Profile 插件版本
│     ├─ gradlew / gradlew.bat       Gradle Wrapper
│     ├─ gradle/wrapper/             Wrapper 版本入口
│     ├─ app/
│     │  ├─ build.gradle.kts         SDK、版本、flavor、签名判据、依赖、构建类型
│     │  ├─ proguard-rules.pro       R8 的少量必要保留规则
│     │  ├─ schemas/                Room 导出的数据库 schema
│     │  └─ src/
│     │     ├─ main/
│     │     │  ├─ AndroidManifest.xml
│     │     │  ├─ java/com/wotagei/cam/
│     │     │  │  ├─ WotaApp.kt      Application 入口
│     │     │  │  ├─ core/          参数、能力、规则与纯逻辑
│     │     │  │  ├─ camera/        Camera2、EGL/GLES、预览与渲染桥
│     │     │  │  ├─ record/        录制、音频、抽帧补弧、分段与帧账
│     │     │  │  ├─ media/         MediaStore、Room、系列、文件分享
│     │     │  │  ├─ player/        播放、对比、剪辑与离线后渲染
│     │     │  │  ├─ ui/            页面、控件编排、HUD、设置与参数面板
│     │     │  │  │  ├─ anim/      动效规格与取证钩子
│     │     │  │  │  ├─ design/    设计令牌、通用组件、开关、红心、弹层
│     │     │  │  │  ├─ dialog/    参数 / 曲线 / 音源 / 蓝牙等面板
│     │     │  │  │  ├─ theme/     Material 主题接入
│     │     │  │  │  └─ widget/    相机面、滑杆、参考线、站位线、水平仪
│     │     │  │  ├─ stance/       full / lite 共用的站位接口
│     │     │  │  ├─ bt/           蓝牙音箱管理
│     │     │  │  ├─ backup/       可携带用户元数据与系统备份适配
│     │     │  │  ├─ diag/         本地诊断日志
│     │     │  │  └─ update/       唯一允许运行时联网的更新模块
│     │     │  └─ res/
│     │     │     ├─ values/      文案、主题、颜色等资源
│     │     │     └─ xml/         备份与 FileProvider 白名单
│     │     ├─ full/
│     │     │  ├─ java/.../stance/ 站位分析 / 推理 / UI 实现
│     │     │  └─ assets/model_pack/ 模型包元数据与 .tflite
│     │     ├─ lite/java/.../stance/ lite 导流实现，不携带推理依赖
│     │     ├─ test/              两个 flavor 共用的 JVM 测试与平台桩
│     │     ├─ testFull/          仅 full 编译的 AI 测试与平台桩
│     │     └─ androidTest/       设备仪器测试，跟随 probe 构建类型
│     ├─ baselineprofile/        热路径 profile 采集测试模块
│     └─ scripts/                取证、retrace、颜色 / shader 检查等辅助脚本
├─ HarmonyOS/                    独立鸿蒙工程，不是 Android 的 Gradle 模块
├─ iOS/                          独立 iOS 工程，不是 Android 的 Gradle 模块
├─ .github/workflows/build-apk.yml Android 打包 / 发布入口
├─ .github/workflows/build-hap.yml 鸿蒙打包入口，共用 v* tag 发布触发
└─ .github/release-notes/         按 tag 命名的发布说明
```

**不是每个 Kotlin 文件都在 `src/main`。** 修改站位实现必须同时看 `src/full` / `src/lite`；把 full 的推理类直接引入 main，会让 lite 无法编译，或使 lite 意外携带 AI 依赖。`testFull` 中的 fixture 也不能被共用测试无条件引用。

<a id="build"></a>
## 4. 工具链、变体与构建

### 4.1 当前源码的版本事实

| 项目 | 当前值 / 真源 |
|---|---|
| Gradle | 8.3；`gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 8.1.1；根 `build.gradle.kts` |
| Kotlin / KSP | 1.9.0 / 1.9.0-1.0.13 |
| JDK / JVM target | 17 |
| SDK | `minSdk=29`、`compileSdk=34`、`targetSdk=34` |
| Compose | BOM `2024.06.00`；compiler extension `1.5.2` |
| Lifecycle / Navigation | 2.8.2 / 2.7.7 |
| Room | 2.6.1；KSP 生成实现与 schema |
| Media3 | 1.4.1；不是早期说明中的 1.1.1 |
| Coroutines | 1.8.1 |
| AI runtime | LiteRT 1.4.2，仅 `fullImplementation`；源码 import 仍使用 `org.tensorflow.lite` API |
| 基础版本 | `versionCode=12`、`appVersionBase="0.0.12"`；不是从 Git tag 自动推导基础版本 |
| namespace / applicationId | 均为 `com.wotagei.cam` |
| ABI | 默认仅 `arm64-v8a`；`-PabiSplits=true` 时另出 `armeabi-v7a` 与 universal |

真源：[根构建配置](WotaGeiCamApp/build.gradle.kts)、[应用构建配置](WotaGeiCamApp/app/build.gradle.kts)、[模块注册](WotaGeiCamApp/settings.gradle.kts)。升级依赖时同时核查 SDK / AGP / Kotlin compiler 的兼容范围，不因为 Maven 上有新版就单独替换一个数字。

### 4.2 flavor 与 build type 是两条轴

| 维度 | 值 | 开发时需要知道的语义 |
|---|---|---|
| flavor `ai` | `full` | 包含 AI 站位 UI、模型资产和 LiteRT CPU / GPU 依赖 |
| flavor `ai` | `lite` | 不含推理功能，保留同接口的提示入口；`versionName` 额外带 `-lite` |
| build type | `debug` | 不混淆；三类取证开关均为 false，**debug 不等于能打开取证钩子** |
| build type | `debugHook` | 可调试、R8 混淆 / 资源收缩、使用发布签名判据，三类钩子为 true |
| build type | `probe` | 可调试、不混淆、使用发布签名判据；`testBuildType="probe"` |
| build type | `release` | R8 混淆 / 资源收缩，三类钩子均关闭 |

三类 BuildConfig 字段为 `MERGE_HOOK`、`FROST_PROBE`、`COMPARE_PROBE`。不要改用 `BuildConfig.DEBUG` 判断钩子是否开放，也不要给发布包放开 adb extra 的取证入口。

full / lite 当前**同包名、同 versionCode**；能否保留数据互相覆盖的前提还包括**签名一致**。这不表示不同开发者签名的 Fork 可以覆盖安装原版。

### 4.3 版本、命名、签名与原生库

- `-Prbuild=05` 或 `-Prbuild=r05` 使版本名带 `-r05`；没有传值则不带 r 后缀。源码只移除前缀 `r`，不会把 `5` 自动补成 `05`。
- `-PapkBaseName=WotaGeiCam` 才启用含项目名 / 版本的自定义产物名；未传时保持 `app-*`。
- `-PabiSplits=true` 与普通构建会改变输出集合。两遍构建时，**每一遍完成后立即拷出产物**，不能指望同一 outputs 目录保留上一遍全部文件。
- Gradle 发现本机签名配置时按配置签名；缺失时 release 回退 debug 签名。**任务名是 release 不代表使用了正式发布证书。** 签名材料由 Fork 维护者自行安全配置，不能从仓库或作者机器获取。
- `jniLibs.useLegacyPackaging=true` 使依赖中的 native 库以压缩 / 安装时提取方式打包；这不能修复 ELF 本身的页对齐问题。
- `.tflite` 使用 `noCompress` 便于 mmap。不要为了包体积随意开启模型压缩。
- LiteRT 1.4.2 相关的源码 / 包结构兼容检查不等价于 CPU、GPU 或 16KiB 页设备推理验收；更不能把旧 Release 与当前源码视为同一个运行时。

### 4.4 发布 CI 的实际边界

[build-apk.yml](../.github/workflows/build-apk.yml) 当前做的是打包、命名 / 数量核验、上传和 tag 发布，**没有显式运行 JVM 单测或 Android Lint**。CI 打包绿不能替代下一节的验证。

- `v*` tag 同时触发 Android / HarmonyOS 两条工作流；不是只发安卓。
- Android 先构建 full / lite 的 split 三枚，再构建各自 plain 一枚。
- 最终是 **full 4 枚 + lite 4 枚 + compat 1 枚 = 9 枚 APK**。compat 的字节内容等于 lite universal，不是第三种功能变体。
- 最终资产名含 `-Android.apk`；Gradle 文件名已带 flavor，不得拷贝时再次追加 `-full` / `-lite`。
- `.github/release-notes/<tag>.md` 是手写发布说明入口；workflow_dispatch 打包不等于 tag 发布。
- 更新校验行 `SHA256:`、`SHA256-full:`、`SHA256-lite:` 的语义见[更新协议](#update)。
- **推送、推 tag、创建或覆盖 Release 都是外发动作，必须由维护者明确授权；本文不给予自动发布授权。**

<a id="verification"></a>
## 5. 测试与验收

### 5.1 常用命令

以下均在 **`Android/WotaGeiCamApp/`** 执行。首次联网解析依赖完成后，才考虑按环境增加 `--offline`。

```bash
# 共用逻辑在两个 flavor 下都执行；full 额外纳入 testFull。
bash ./gradlew :app:testFullDebugUnitTest :app:testLiteDebugUnitTest

# 示例：只跑一个共用测试。改代码前先找到最贴近的现有测试类。
bash ./gradlew :app:testLiteDebugUnitTest \
  --tests 'com.wotagei.cam.SettingsDefaultsTest'

# 静态检查。
bash ./gradlew :app:lintFullDebug :app:lintLiteDebug

# 双 flavor 编译与打包。
bash ./gradlew :app:assembleFullDebug :app:assembleLiteDebug

# 取证包与仪器测试 APK；先核对证书，不要默认连接含重要数据的日常机。
bash ./gradlew :app:assembleFullProbe :app:assembleFullProbeAndroidTest

# 会操作已连接设备；只有确认设备、数据与授权后再运行。
bash ./gradlew :app:connectedFullProbeAndroidTest
```

| 结果 | 默认位置（相对于 Gradle 根目录） |
|---|---|
| JVM XML | `app/build/test-results/testFullDebugUnitTest/`、`testLiteDebugUnitTest/` |
| JVM HTML | `app/build/reports/tests/testFullDebugUnitTest/`、`testLiteDebugUnitTest/` |
| Lint | `app/build/reports/lint-results-fullDebug.*`、`lint-results-liteDebug.*` |
| R8 mapping | `app/build/outputs/mapping/fullRelease/mapping.txt`、`liteRelease/mapping.txt` |

核对一次真实运行时，不要把中断前遗留 XML 或 `FROM-CACHE` 输出当成本次执行证明。需要重新执行时加 `--rerun-tasks`，报告任务、flavor、测试数、失败数与是否实际执行，勿抄历史数字。

### 5.2 测试风格

- `src/test` 不只有简单纯函数测试，还包含 Android 平台桩 / bridge 行为测试、源码接线守卫。
- `*GuardTest` 常对函数体、禁入符号、调用次序和白名单做断言。修改命名或布局也可能需要同步守卫，**不能为通过而删除判据**。
- [KotlinSourceScan](WotaGeiCamApp/app/src/test/java/com/wotagei/cam/source/KotlinSourceScan.kt) 提供注释 / 字面量遮蔽、函数体定位等工具，不是完整 Kotlin 解析器。字符串协议测试需保留原文，不能拿已遮蔽文本查字面值；新增 flavor / 新源码路径时也要检查扫描器是否真正覆盖。
- 测试输入定位失败应显式失败；“没有读到源码所以没有发现违规”不算通过。
- 新增行为优先补纯逻辑或 bridge 行为断言，再补接线守卫；只证明定义恒等式不能防止生产调用被删。
- `baselineprofile/` 是设备热路径采集模块，不是 JVM 单测替代品；重新采集前核对目标包名、flavor 和设备环境。

### 5.3 设备与辅助脚本

- JVM 绿证明不了厂商 HAL 接受曝光 / 白平衡请求，也证明不了成片方向、音轨、帧率、Surface 恢复或视觉观感。
- 涉及录制时至少检查：后 / 前摄、两种横屏方向、预览与成片、带音轨输出、正常停录、离页与恢复。普通练习先按 1～4 分钟验收；换段 / 热 / 长录另列边界。
- 模拟器软件 GPU 可验证绘制逻辑，不替代设备 GPU 的精度、性能与相机协商。
- Windows Git Bash 运行 `adb shell` 前使用 `export MSYS_NO_PATHCONV=1`，避免 `/sdcard` 被改写成本机路径。
- 截图和 UI dump 等临时文件只放工作区内 `.tmp/`；不从强杀结果推断异步持久化是否可靠，不用卸载清数据来绕开签名问题。
- `scripts/` 某些历史脚本仍假定 monorepo 迁移前的 `tools/` 位置或旧产物名，**先检查其 ROOT / ADB / APK 路径，不把注释里的旧命令原样粘贴执行**。

可选的独立检查：

```bash
# 在 Android/ 目录：脚本的 Tokens.kt 相对路径是 WotaGeiCamApp/...。
python -I WotaGeiCamApp/scripts/contrast-audit.py

# 在 Android/WotaGeiCamApp/ 目录；Windows + SDK emulator 的 SwiftShader。
python -I scripts/test-echo-color-gles.py \
  --gles-dir "$ANDROID_HOME/emulator/lib64/gles_swiftshader"
```

对比度脚本报告 PASS / FAIL 数字，不等于整页可用性审查；shader 脚本实际编译 / 执行相关 GLES 片元，但仍不代替真机。构建任务结束后，可在 Gradle 根目录执行 `bash ./gradlew --stop` 释放该环境的 Gradle daemon；不要按进程名批量杀掉其他项目的 Java 进程。

<a id="architecture"></a>
## 6. 架构、数据流与资源归属

### 6.1 启动与页面导航

- [WotaApp.kt](WotaGeiCamApp/app/src/main/java/com/wotagei/cam/WotaApp.kt) 是唯一 `Application`。启动期只做轻量工作：debug 下装 StrictMode、后台线程重试系统备份恢复并预热 `wota_settings`、创建内录通知渠道、等 prefs 预热完成后装诊断日志。**Room / 媒体库扫描不在启动期做**。
- [MainActivity.kt](WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/MainActivity.kt) 是单 Activity + Navigation Compose。起始目的地是录制页；设置页、控件编辑页由 Activity 内常量注册，媒体库 / 播放器 / 对比页的路由在 [media/GalleryScreen.kt](WotaGeiCamApp/app/src/main/java/com/wotagei/cam/media/GalleryScreen.kt) 的 `WotaNav` 里（`gallery`、`player/{mediaId}`、`compare?leftMediaId={leftMediaId}`）。
- **相机与导出属于窗口，不属于某个页面**：`CameraController.of(windowOwner)` 取 Activity 级 `AndroidViewModel`，`ExportHost.of(owner)` 同样是 Activity 级。离开录制页进入设置等页面时是“保活窗口内相机、按策略延迟释放”，不是每页重建一套相机。新增页面时不要另建一个 `CameraController` 实例。
- 返回只有一个出口 `MainActivity.navigateBack()`：它负责栈底不变量与防抖。**不要在新页面里直接 `popBackStack()` 绕过它**；录制页作为起始目的地被弹掉会导致整窗没有内容可画。组合消失另有 3 秒兜底，但那是事故恢复，不是正常导航手段。
- 每页用 `DestinationPresence` 回报“我还在组合里”，预览 / 组合看门狗据此判断是否需要恢复。新页面漏接这条回报，会把自己排除在自愈保护之外。

**新增页面的最小接线**：路由常量 → NavHost `composable` → `DestinationPresence` → 页面模式 / 窗口策略（若需要）→ 返回走 `navigateBack()` → 真机验证进入、返回、旋转、后台恢复。

### 6.2 参数总线与能力协商

参数总线是 [core/WotaParams.kt](WotaGeiCamApp/app/src/main/java/com/wotagei/cam/core/WotaParams.kt) 的 `WotaParams`。每个可调项是一个 `MutableStateFlow<ParamState<T>>`：

```kotlin
data class ParamState<T : Comparable<T>>(
    val value: T,
    val range: ClosedRange<T>?,   // null = 本机无此能力
    val enabled: Boolean = true,  // false = UI 灰显
    val exact: Boolean = true     // false = 只能给近似值
)
```

数据流是单向的：

```text
Compose 控件（录制页 / 设置页）
      │ 写 ParamState.value
      ▼
WotaParams（Main.immediate 上的 StateFlow）
      │ 引擎线程读取 snapshot(mode)
      ├─ Camera2Engine → CaptureRequest
      ├─ GlRenderEngine → uniform / program 键 / RT 尺寸
      └─ RecordRunner → RecordProfile / 音轨计划
```

规则：

- **UI 不直接调 Camera2**，也不直接拼 `CaptureRequest`；请求组装集中在 `camera/RequestApplier.kt`，由 `Camera2Engine` 在相机线程调用。
- 换镜头时 `CameraAbility` 整套替换，`WotaParams.applyAbility()` 重算范围并钳制当前值。任何“只在 UI 层夹一下”的新参数，都会在下一次换镜头或引擎回写时暴露。
- 帧率只有 `setFps()` 一个写入口：它同时重算快门 / ISO 的可选域。绕过它直写 `fps.value`，会出现“UI 显示 1/24、实际按 1/60 下发”的假读数。
- `requestedSize` 是用户意图，`size` 是当前镜头实际生效档；换镜头只把意图在新能力里重新落档，不要用一个字段同时承担两件事。
- 参数默认值由 `WotaSettings.applyDefaultsOnce()`（进程内一次）→ `applyDefaults()` → `ParamsPersist.restore()` 合流。设置页改的是“启动档位”，录制页改的是“本次会话值”，两者共用同一批键，语义是“键即启动档位”。

### 6.3 线程与 owner 表

| 线程 / 作用域 | 拥有者 | 职责 | 禁止事项 |
|---|---|---|---|
| 主线程 | Compose / `WotaParams`（`Dispatchers.Main.immediate`） | UI、参数总线、轻量状态同步 | 不做相机打开、编码、Room 全表扫描、文件 SHA |
| `WotaCamEngine` | `Camera2Engine` | open/close、会话重建、repeating request、参数下发 | 不碰 EGL、不做长 IO |
| `WotaCamCallback` | `Camera2Engine` | Camera2 回调、帧到达与状态发布 | 不阻塞；重活切回引擎线程 |
| `WotaGlRender`（`URGENT_DISPLAY`） | `GlRenderEngine` | 全部 EGL/GLES、program 编译、纹理、编码面 / 窗口面绘制 | 其他线程不得直接调 GL；Surface 变更走引擎消息 |
| `WotaRecordCtl` | `CameraScreen.kt` 内的 `RecordRunner` | 起录 / 停录、录制健康检查、换段、引擎看门狗、资源收尾 | 不直接画 GL；不阻塞主线程 |
| 每条音轨一个 `AudioFeeder` 线程 | `record/AudioFeeder.kt` | AudioRecord / 内录 PCM 抽取、队列、drain | 分段时只清队列，不重置“是否见过非零样本” |
| `WotaStanceAnalysis`（full） | `stance/StanceAnalysis.kt` | 分析帧读取、YUV→RGBA、检测、模型热换 | 不在主线程装载 Interpreter；不在分析线程外碰检测器 |
| `WotaStancePack`（full，daemon） | `stance/StanceController.kt` | 模型包 copy / SHA / 装配、串行化 | 不阻塞主线程；不与分析 owner 交错写文件 |
| 导出 owner 线程池（单 owner、零队列） | `player/SceneExportRunner.kt` / `ExportHost` | 解码 → GL → 重编码 → mux；忙则 BUSY | 不允许并发第二个导出；取消不能被包装成失败 |
| 120 ms 轮询协程 | `player/PlayerEngine.kt` | 单一进度源、AB 循环、同步微调 | 不新增第二套进度源 |
| 诊断落盘线程 | `diag/Diag.kt` | 环形缓冲、轮转、崩溃尾 | 不联网、不记录画面 / 声音内容 |

**跨线程约定**：状态用 `StateFlow` / `SharedFlow`；一次性命令用引擎的消息队列；大对象（Surface、ImageReader、MediaCodec）只在 owner 线程创建和释放。看到“主线程直接 `release()` GL / 编码器”就是结构错误，不是加锁能修的。

### 6.4 渲染管线：GPU 与 DIRECT、两槽、ProgramKey

渲染入口在录制页的 Sink 选择：`renderMode == GPU` 用 `GlRenderEngine`；否则用 `DirectSink`（TextureView 直连）。两条路的能力边界必须如实反映到 UI：

| 能力 | GPU | DIRECT |
|---|---|---|
| 预览上屏 | GL 窗口面 | TextureView |
| 斑马纹 / 峰值 | 支持（仅上屏） | 不支持 |
| Glow / 残影 / 霜底板 | 支持 | 不支持（参数可以保留，效果不生效） |
| 画面降噪 GPU 双边 | 支持 | 自动退回 HAL 快速档 |
| 编码 | GL 编码面 | 相机编码面 / 会话直连 |

**两槽**：GL 侧有 `DISPLAY` 与 `MOVIE` 两个 program 槽。键相同就共用同一枚 program；`slot` 本身不是键维度。

**ProgramKey 五维**（`camera/ProgramKey.kt`）：`(effect, curve, denoise, glow, echo)`。

- `movieProgramKey(...)` **强制 `effect = NONE`**：这是“监看叠加绝不进成片”的唯一真源，不允许别处再写一份判断。
- `displayProgramKey(...)` 在 `effect != NONE` 时**强制 `denoise = 0`**：判读工具不被降噪处理。
- 编译回退阶梯单调：`glow → echo → denoise(2→1→0) → effect(→NONE) → curve → 直通 → null`。曲线最后降，因为它是用户的调色，不能因为斑马纹编译失败就丢掉成片调色。
- 形状参数（Glow 的阈值 / 宽度 / 强度、Echo 的模式 / 倍数、降噪 σ、斑马阈值、峰值颜色等）只改 uniform，不进 program 键，不触发重编译。

**每帧绘制次序**（以源码为准）：`syncMovieSlot` → `drawGlowPass` → `drawEchoPass` → 抽帧补弧 → `drawEncoderPass`（编码面）→ `syncDisplaySlot` → 霜底板 → 窗口面 → 直方图采样。**编码面先画**，避免上屏开销影响成片。

**图层归属**：

- scene layer（进成片）：曲线、色调、Glow、残影、画面降噪。
- monitor layer（只上屏）：斑马纹、峰值对焦、参考线、站位线、水平仪、霜底板。
- 曲线与色调烘焙进同一张 LUT 纹理；不要在片元里再乘一次，也不要让后渲染导出再套一遍曲线（会双倍）。

### 6.5 录制决策与 Surface 交接

录制控制不在 `record/` 包里，而在 [ui/CameraScreen.kt](WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/CameraScreen.kt) 的 `RecordRunner`；录制引擎实现才在 `record/`。

**引擎选择**（`Recorders.useCodecEngine`）：

- `fps > 60`（高速会话）→ `CodecRecorder`。
- 录制期抽帧 / 补弧（`arcConvert != null`）→ `CodecRecorder`（MediaRecorder 不能拒帧）。
- 内录音轨（`captureAudio`）→ `CodecRecorder`（MediaRecorder 无多音轨）。
- 音频降噪非 OFF → `CodecRecorder`（NoiseSuppressor 只能挂在自采 AudioRecord 上）。
- 其余 ≤60fps 的普通录制 → `MrRecorder`。

**起录顺序**有硬约束：

1. 有界启动存储 / 取 `OutputSink`；
2. `Recorders.create` + `prepare`；
3. **音频预检要在 Surface 交接前完成**；
4. DIRECT 路把编码面交给相机会话（`setRecordingTarget`）；
5. GPU 路把编码面交给 GL（`setOutputSurface`，尺寸 / 方向 / 帧率必须与编码器逐像素一致）；
6. 等待预览 / 编码面就绪后才允许真正开写。

**换段**时旧编码面已死：必须先 `markSegmentRotating()`，再交接新 Surface，最后 `clearSegmentRotating()`；顺序反了会把“换段抑制窗口”外的帧误判成异常。

**资源 owner**：

- 相机会话：`CameraController` / `Camera2Engine`；`unbind()` 是释放入口。
- EGL / GL 资源：`GlRenderEngine`；`release()` 有界等待，不要从别的线程强拆。
- 录制引擎：`RecordRunner` 创建的 `Recorder`；`stopInternal` / `teardownAbandonedSession` 收尾。
- 内录投影：`PlaybackCaptureController` + `CaptureFgService`；targetSdk 34 的前台服务顺序是 `startForeground(mediaProjection)` → `getMediaProjection` → `registerCallback`。
- 导出：`ExportHost`（Activity 作用域）持有 `ClipExporter` / `SceneExportRunner` / `TrackExporter`，页面退出不取消，进度与结果留在宿主。

### 6.6 长任务与取消

- 导出是“单 owner、零队列”：上一轮没收尾时新请求直接 BUSY，不要排队，也不要在 UI 侧并发起两个。
- 取消异常必须原样重抛，不能落成 `Failed`；取消后是否保留 pending 输出行，按“封轨前 / 封轨后”区分。
- Muxer 失败与 commit（MediaStore 收录）失败是两个结果：commit 全败但字节已在盘上时，保留待冷启救援，文案不能说成“导出失败”。
- 帧账（`FrameLedger`）是观测数据，**不参与 PTS 决策**；PTS 单调修正由 mux 写入侧负责。

<a id="keys"></a>
## 7. 键名体系与持久化字典

这一章是 Fork / 迁移时最容易造成“用户数据悄悄失效”的部分。先记住一条：**下列六种“名字”不是同一个东西，不能一起重命名。**

| 命名空间 | 例子 | 改动的后果 |
|---|---|---|
| Kotlin 类 / 文件 / 包名 | `Camera2Engine`、`core/WotaParams.kt` | 只影响编译与源码导航；改包名要同步 import 与 Manifest |
| SharedPreferences 键字符串 | `default_fps`、`hud_pills` | 改了 = 老用户的值读不到（等于恢复出厂） |
| Room 列 / 表名 | `mediaId`、`media_tag` | 改了 = schema 变化；没 Migration 会触发破坏性重建，清空标签 / 回收站 / 系列 |
| 枚举持久化值 | `FLUENT`、`MEND`、`hal_fast` | 改了 = 老串解析失败，回落默认 |
| 位掩码 bit | `CamPill.ECHO = 65536` | 改了 = 用户显隐配置错位；复用旧位 = 语义错乱 |
| 用户可见文案资源 | `cam_pill_echo`、`player_arc_repair` | 可以改文案值，不要改资源名（翻译 / 测试 / 文档引用会断） |

### 7.1 SharedPreferences 文件一览

| 文件 | 定义处 | 内容 |
|---|---|---|
| `wota_settings` | `WotaSettings.PREFS`（`ui/SettingsScreen.kt`） | 全量设置、录制参数、复合串、HUD 布局、对比历史 |
| `wota_lens_pick` | `core/LensSlot.kt` 的 `LensPickPreference` | `lens_user_picked`：用户是否手选过镜头 |
| `wota_player_ab` | `player/PlayerEngine.kt` 的 `PREFS` | AB 循环、音轨同步缓存，均按 URI 键控 |
| `wota_diag` | `diag/Diag.kt` | `last_session`：上次会话时间戳 / 存活标记 |
| `wota_hud_layout_probe`、`wota_params_probe` | `androidTest` 探针 | 只用于跨进程落盘取证，**不写生产 prefs** |
| `wota_media.db` | `media/WotaDb.kt` | Room：标签、回收站、系列 |
| `wota_user_backup.bin` | `backup/PortableUserData.kt` | 唯一允许系统备份 / 换机迁移的文件 |

### 7.2 `wota_settings` 全量键表

下表按“是否进入录制参数记忆（`ParamsPersist.KEYS`）”分组。**类型与默认值以源码读取侧为准**；`域` 列是产品域或读取侧钳制。

#### A. 相机默认档（19 项，全部入档）

| 键字符串 | 常量 | 类型 | 默认 | 域 / 语义 |
|---|---|---|---|---|
| `default_iso` | `KEY_DEFAULT_ISO` | Int | 100 | `>0` 才套；设备域由 `applyAbility` 收口 |
| `default_ae_mode` | `KEY_DEFAULT_AE_MODE` | String | `AUTO` | `AUTO` / `MANUAL` / `LOCK`；**LOCK 存/恢复一律归 AUTO**（瞬时态） |
| `default_ev` | `KEY_DEFAULT_EV` | Int | 0 | EV 步数，不是倍率；设备域后钳 |
| `default_wb_preset` | `KEY_DEFAULT_WB_PRESET` | String | `AUTO` | `AUTO` `INCANDESCENT` `FLUORESCENT` `WARM_FLUORESCENT` `DAYLIGHT` `CLOUDY_DAYLIGHT` `TWILIGHT` `SHADE` `MANUAL` |
| `default_wb_kelvin` | `KEY_DEFAULT_WB_KELVIN` | Int | 5500 | 2000..10000 |
| `default_wb_tint` | `KEY_DEFAULT_WB_TINT` | Int | 0 | -50..50 |
| `default_zoom` | `KEY_DEFAULT_ZOOM` | Float | 1.0 | `>0` 才套 |
| `default_flash` | `KEY_DEFAULT_FLASH` | String | `OFF` | `OFF` / `ON` / `AUTO` / `TORCH` |
| `default_stabilize` | `KEY_DEFAULT_STABILIZE` | String | `OFF` | `OFF` / `OIS` / `EIS` / `OIS_EIS` |
| `default_af_mode` | `KEY_DEFAULT_AF_MODE` | String | `CONTINUOUS_VIDEO` | `CONTINUOUS_VIDEO` / `CONTINUOUS_PICTURE` / `MACRO` / `MANUAL` |
| `default_mf_diopter` | `KEY_DEFAULT_MF_DIOPTER` | Float | 0 | 负值归 0 |
| `default_size` | `KEY_DEFAULT_SIZE` | String | 缺键 = 总线 1920×1080 | `"WxH"`，存的是**意图值** `requestedSize`，不是当前生效尺寸 |
| `default_frame_effect` | `KEY_DEFAULT_FRAME_EFFECT` | String | `NONE` | `NONE` / `ZEBRA` / `PEAKING` |
| `default_zebra_threshold` | `KEY_DEFAULT_ZEBRA_THRESHOLD` | Float | 0.8 | 0.6..1.0 |
| `default_zebra_density` | `KEY_DEFAULT_ZEBRA_DENSITY` | Int | 2 | 1..3 |
| `default_peaking_strength` | `KEY_DEFAULT_PEAKING_STRENGTH` | Float | 0.6 | 0..1 |
| `default_peaking_color` | `KEY_DEFAULT_PEAKING_COLOR` | String | `RED` | `WHITE` / `GREEN` / `BLUE` / `ORANGE` / `RED` |
| `default_lens` | `KEY_DEFAULT_LENS` | String | `WIDE` | `LensType` 产品枚举名：`SUPER_WIDE` `WIDE` `TELEPHOTO` `SUPER_TELEPHOTO` `AUTO` `FRONT`；不是 cameraId |
| `default_arc_merge_deadband` | `KEY_DEFAULT_ARC_MERGE_DEADBAND` | Int | 12 | 0..32；走 `setArcMergeDeadband` 同步发布 |

#### B. 帧率 / 快门 / 码率 / 监看开关（5 项，入档）

| 键字符串 | 常量 | 类型 | 默认 | 域 / 语义 |
|---|---|---|---|---|
| `default_fps` | `KEY_DEFAULT_FPS` | Int | 25 | 产品档 `24/25/30/50/60/120/240`，再与设备能力求交；写入必须走 `setFps` |
| `default_shutter_ns` | `KEY_DEFAULT_SHUTTER_NS` | Int | 40000000（1/25 s） | `>0` 才套；ns；最终由 `clampShutterNs` 收口 |
| `default_bitrate` | `KEY_DEFAULT_BITRATE` | Int | 10000000 | 产品档 5 / 10 / 20 / 35 / 50 Mbps |
| `default_ref_lines` | `KEY_DEFAULT_REF_LINES` | Int 位掩码 | 0 | `RefLineType` 位：1,2,4,8,16,32,64,128,256,512,1024（`FULL_MASK=2047`） |
| `level_enabled` | `KEY_LEVEL_ENABLED` | Bool | true | 水平仪 / 俯仰仪总开关 |

#### C. 降噪 / 曝光纠偏 / 站位线 / Glow / 色调 / 上屏档 / 残影（10 项，入档）

| 键字符串 | 常量 | 类型 | 默认 | 域 / 语义 |
|---|---|---|---|---|
| `denoise_audio` | `KEY_DENOISE_AUDIO` | String | `off` | `off` / `system`；非 off 强制走 Codec 引擎 |
| `denoise_video` | `KEY_DENOISE_VIDEO` | String | `off` | `off` / `hal_fast` / `hal_hq` / `gpu_bilateral`；DIRECT 下 GPU 档退回 HAL 快速 |
| `correction_auto` | `KEY_CORRECTION_AUTO` | Bool | true | 等号档曝光纠偏自动开关 |
| `correction_manual_ns` | `KEY_CORRECTION_MANUAL_NS` | Long | 0 | 0..8_000_000 ns（0.5 ms 步进），自动关时生效 |
| `default_marker_spread` | `KEY_DEFAULT_MARKER_SPREAD` | Float | 0.60 | 0.15..1.00；站位线组占预览宽的比例 |
| `default_marker_color` | `KEY_DEFAULT_MARKER_COLOR` | String | `YELLOW` | `YELLOW` `CYAN` `MAGENTA` `GREEN` `WHITE` `ORANGE` |
| `display_scale` | `KEY_DISPLAY_SCALE` | String | `ORIG` | `ORIG` / `HALF` / `QUARTER` / `EIGHTH` / `SIXTEENTH`；只改上屏 RT，不重建会话 |
| `glow_params` | `KEY_GLOW` | String 复合 | 出厂 Glow（关） | 格式见 §7.3 |
| `tone_params` | `KEY_TONE` | String 复合 | 恒等 | 格式见 §7.3；**不要并进 `curve_stack`** |
| `echo_params` | `KEY_ECHO` | String 复合 | 出厂 Echo（关） | 格式见 §7.3 |

> **站位线线数不入档**：`markerCount` 是运行时值，默认 `0`（= 关），范围 0..8；只有间距与颜色持久化。用户口径是“启动时取景器干净无线”。不要“顺手补齐”这个键。

#### D. 纯 UI / 专属链 / 诊断（不入 `ParamsPersist` 档案，但仍在 `wota_settings`）

| 键字符串 | 常量 | 类型 | 默认 | 说明 |
|---|---|---|---|---|
| `motion_mode` | `KEY_MOTION` | String | `FLUENT` | `PLAIN` / `FLUENT` / `LIQUID` |
| `default_sample_rate` | `KEY_DEFAULT_SAMPLE_RATE` | Int | 48000 | 32000 / 44100 / 48000；设置页独占，**不进档案**（自动降级字段） |
| `default_render_mode` | `KEY_DEFAULT_RENDER` | String | `GPU` | 只有 `DIRECT` 被识别，其余按 GPU；设置页独占 |
| `default_audio_muted` | `KEY_DEFAULT_AUDIO_MUTE` | Bool | false | 设置页“静音录制”独占；录制页音源**不持久化** |
| `diag_enabled` | `KEY_DIAG_ENABLED` | Bool | false | 诊断日志总开关（崩溃件不受影响） |
| `diag_verbose` | `KEY_DIAG_VERBOSE` | Bool | false | 详细日志 |
| `level_buzz_enabled` | `KEY_LEVEL_BUZZ` | Bool | true | 水平仪回正震动 |
| `hud_items` | `KEY_HUD_ITEMS` | Int 位掩码 | 15（快门/帧率/码率/ISO） | 读数块显隐，位见 §7.4 |
| `hud_pills` | `KEY_HUD_PILLS` | Int 位掩码 | 32767（全开减 TONE/ECHO） | 控件显隐，位见 §7.4 |
| `hud_pills_schema` | `KEY_HUD_PILLS_SCHEMA` | Int | 0（旧）/ 当前 4 | 只随 `hud_pills` 一起写 |
| `ui_orientation` | `KEY_UI_ORIENTATION` | String | `landscape` | `landscape` / `portrait`；大小写不敏感；`commit()` 同步落盘 |
| `hud_layout` | `KEY_HUD_LAYOUT` | String 复合 | 默认表 | 格式见 §7.3；`commit()` 同步落盘 |
| `text_scale_camera` | `KEY_TEXT_SCALE_CAMERA` | Int | 100 | 档 80 / 90 / 100 / 110 / 120（%） |
| `text_scale_settings` | `KEY_TEXT_SCALE_SETTINGS` | Int | 100 | 同上 |
| `text_scale_dialog` | `KEY_TEXT_SCALE_DIALOG` | Int | 100 | 同上 |
| `gallery_columns` | `KEY_GALLERY_COLUMNS` | Int | 2 | 档 2 / 3 / 4 / 5；非档值回落 2 |
| `camera_brighten_delay_sec` | `KEY_CAMERA_BRIGHTEN_DELAY_SEC` | Int | 1 | 0..10；**0 = 关闭**，不是“立即” |
| `player_brighten_ramp_tenths` | `KEY_PLAYER_BRIGHTEN_RAMP_TENTHS` | Int | 10 | 0..30（十分之一秒）；0 = 关闭 |
| `player_top_bar_collapsed` | `KEY_PLAYER_TOP_BAR_COLLAPSED` | Bool | false | 播放页顶栏持久收起 |
| `curve_panel_side_dp` | `KEY_CURVE_PANEL_SIDE_DP` | Float | 200 | NaN / 负数 → 200；上限在组合期二次钳 |
| `slider_continuous` | `KEY_SLIDER_CONTINUOUS` | String | 空 | 逗号分隔的 `SliderKey.id`；空 = 全部吸附 |
| `arc_convert_mode` | `KEY_ARC_CONVERT` | String | `MEND` | `MEND` / `DROP`；专属直写链，不进档案 |
| `hud_frost_blur` | `KEY_FROST_BLUR` | Bool | false | 霜底板开关；缺键就是关，无隐式来源 |
| `curve_stack` | `KEY_CURVE_STACK` | String 复合 | 恒等 | 4 段曲线；专属直写链，不进档案 |
| `compare_tick_ms` | `KEY_COMPARE_TICK_MS` | Int | 200 | 100..1000，档 100 / 200 / 500 / 1000 |
| `compare_history` | `CompareHistory.KEY_COMPARE_HISTORY` | String JSON | 空表 | 最多 12 条，队首最新；格式见 §7.3 |

> `compare_history` 虽然物理上在 `wota_settings`，但**不进入备份**：它带 URI，属于设备 / 授权相关数据。`stance_*` 键也写在同一文件，定义在 full 源集，见 §7.7。

### 7.3 复合串格式

复合串的共同纪律：**键序固定、坏值不猜、新增字段用“缺键 = 出厂”的可选键**。但各 codec 的浮点写法并不完全统一：Glow / Echo 用 `Float.toString()` 保证往返，曲线用 `Locale.US` 三位小数，Tone 原样空格分隔。不要重排已有键，不要改变分段符。**解析失败**与**解析成功但数值越界**要分开：前者整串回默认，后者通常由 `sanitize()` 钳进安全域（各节分别注明）。

#### `glow_params`

```text
enabled=0|1;threshold=<float>;soft=<float>;width=<float>;gain=<float>;add=<float>;freq=<float>;blend=<float>;color=<ARGB hex>;coloronly=0|1
```

- `color` 与 `coloronly` 是后加的可选键：缺键时 `color=白`、`coloronly=false`。
- **解析失败**（已知键不是数、`enabled` 不是 0/1、颜色不是十六进制、`coloronly` 不是 0/1）→ **整串回 `GlowParams.DEFAULT`**；解析成功但数值越界 → `sanitize()` 钳进安全域。
- 未知键忽略（前向兼容），但不要依赖这一点来“顺手删字段”。
- 写之前必过 `sanitize()`：阈值 0.8..1.0、soft 0..0.5、width 0..0.08、gain 0..4、add 0..1、freq 0..64、blend 0..1。
- `colorOnly=false` 时光晕跟随**像素自身颜色**，`color` 字段完全不参与；`colorOnly=true` 时只用 `color`（默认白）单色发光。不要把“默认白”误读成“白色就代表跟随原色”——跟随原色来自 `colorOnly=false`。

#### `echo_params`

```text
enabled=0|1;mode=<KEEP_BRIGHTNESS|TRAIL>;multiplier=<2..10>;threshold=<float>;gain=<float>;tcm=<OFF|ADD|SUBTRACT|ONLY|EXCEPT>;tc=<ARGB hex>;ttol=<float>
```

- `tcm` / `tc` / `ttol` 是后加的可选键；缺键 = 补丁关 / 白 / 0.3。
- **解析失败**（`enabled` / `mode` / 必填数值 / 可选键值不认识）→ 整串回 `EchoParams.DEFAULT`；解析成功但数值越界 → 末尾 `sanitize()` 逐字段钳制（倍数额外：不在 2..10 就回 2）。
- 倍数域 2..10（1 倍不是有效档；要关就 `enabled=0`）；`threshold` 0.6..1.0；`gain` 0..2；`ttol` 0.05..0.8。
- `mode=KEEP_BRIGHTNESS` 时颜色补丁参数不参与；`tcm` 只在 `TRAIL` 档生效。

#### `tone_params`

```text
black white shadows highlights
```

- 四个空格分隔的浮点，顺序固定：黑点、白点、阴影、高光。
- 解码先按空格切分并**过滤掉不可解析项**，过滤后必须恰好剩 4 个，否则整串恒等；四项全 0 → 恒等；NaN → 0。
- 域：黑 / 白 **-0.35..0.35**；阴影 / 高光 -1..1。
- **不得并进 `curve_stack`**：那会改老用户曲线的持久格式。

#### `curve_stack`

```text
<master>;<red>;<green>;<blue>
```

每个 `<channel>` 是 `x:y,x:y,...`（逗号分隔控制点，`x` / `y` 为 0..1，编码保留 3 位小数）；空段 = 恒等。

- 必须恰好 4 段，否则整串回恒等。
- 段内坏点（非数、NaN/Inf）丢弃后按 x 排序；x 越界钳 0..1。
- 段序固定为 master / red / green / blue，**不要交换**。

#### `hud_layout`

```text
v2;T,<x>,<y>,<entry...>;L,<x>,<y>,<entry[:col.row]...>;R,...;D,...;B,...
```

- 容器键：`T` 顶栏、`L` 左 Dock、`R` 右 Dock、`D` 读数块、`B` 底栏。`T` / `B` 是行式非网格，不写格子后缀；`L` / `R` / `D` 是网格，条目可带 `:col.row`，`-` 表示没摆过。
- 条目 id：`P<ordinal>`（`CamPill`）/ `H<ordinal>`（`HudItem`）。**两套 ordinal 是持久化契约：只能追加，不能插入或删除中间项。**
- 版本：当前 `CURRENT_VERSION=2`。`version > 2` 或 `< 1` → 整表回默认；v1 → v2 不换算坐标，格子留哨兵（落点与旧版一致）。
- 解码容错：未知容器 → 丢该容器段；未知条目 → 丢该条目；坏格子 → 只丢该条目的格子信息、条目本身保留；最后统一过 `normalize()`。不要因为一条坏数据把整表清掉。
- 默认顺序由 `defaultOrderOf(zone)` 的**显式表**决定（`READOUT` 用 `HudItem.ALL` 的枚举序；`BOTTOM` 是 `ECHO → DENOISE → LENS`），不要手抄坐标；新增 `CamPill` 时 `homeZone` 的 `when` 会强制编译失败，逼你当场定容器。

#### `slider_continuous`

```text
shutter,iso,kelvin,tint,zoom,diopter,peaking
```

- 只存“关掉吸附”的滑杆 id，按 `SliderKey` 声明序、逗号分隔；空串 = 全部吸附。
- id 只许追加、不许改名；未知 id 读取时忽略。
- 没有梯的参数（EV、斑马阈值、并入门槛）不要加进来：它们本来就是逐格整数，开关无意义。

#### `compare_history`

```json
[{"rightId":123,"rightUri":"content://...","leftId":45,"ts":1700000000000}]
```

- 最多 12 条，队首最新；`rightUri` 只在非空时写；旧数据只有 `rightId` 也能读。
- 整串 JSON 坏 → 空表；单条坏行跳过；`rightId<=0` 且 `rightUri` 空的行丢弃。
- 去重键：库内 `media:<id>`，外来 `uri:<uri>`，两个命名空间不互相覆盖。

### 7.4 位掩码表

#### `hud_items`（读数块显隐）

| 位 | 枚举 | 含义 |
|---|---|---|
| 1 | `HudItem.SHUTTER` | 快门 |
| 2 | `HudItem.FPS` | 帧率 |
| 4 | `HudItem.BITRATE` | 码率 |
| 8 | `HudItem.ISO` | 感光度 |
| 16 | `HudItem.EV` | 曝光补偿 |
| 32 | `HudItem.WB` | 白平衡 |
| 64 | `HudItem.ZOOM` | 变焦 |

默认 `1|2|4|8 = 15`；`FULL = 127`。

#### `hud_pills`（控件显隐）

| 位 | 枚举 | 含义 |
|---|---|---|
| 1 | `LEVEL` | 水平仪 |
| 2 | `VOLUME` | 音量表 |
| 4 | `BT` | 音频 / 蓝牙入口（历史位名保留，**不要改名**） |
| 8 | `ZOOM` | 变焦 |
| 16 | `FOCUS` | 对焦 |
| 32 | `STAB` | 防抖 |
| 64 | `REFLINE` | 参考线 |
| 128 | `MONITOR` | 监看效果 |
| 256 | `CURVE` | RGB 曲线 |
| 512 | `FLASH` | 闪光灯 |
| 1024 | `LENS` | 镜头 |
| 2048 | `SIZE` | 画幅 |
| 4096 | `STORAGE` | 存储 / 容量 |
| 8192 | `DENOISE` | 降噪 |
| 16384 | `GLOW` | 光晕 |
| 32768 | `TONE` | 色调 |
| 65536 | `ECHO` | 残影 |

默认 `DEFAULT_MASK = 全开 − TONE − ECHO = 32767`。`MASK_SCHEMA = 4`，`ADDED_IN_SCHEMA = {1: DENOISE, 2: GLOW}`。

**升级语义**：老用户存的掩码里没有新位时，新胶囊默认隐藏；`migrateMask(mask, fromSchema)` 只 OR 进“那一版之后新增且登记过的位”。TONE / ECHO 刻意不登记（默认关，升级不自动打开）。读取是纯读，不写回；写掩码时必须同时写 `hud_pills_schema = MASK_SCHEMA`。

**新增一颗胶囊的完整改动**（缺一项都会出问题）：

1. `CamPill` **末尾追加**枚举项，bit 用下一个 2 的幂；
2. `MASK_SCHEMA + 1`；决定是否登记 `ADDED_IN_SCHEMA`；
3. `DEFAULT_MASK` 按产品口径决定是否包含；
4. `HudEntry.homeZone` 的 `when` 补分支（编译器会强制）；
5. `HudLayoutTable.defaultOrderOf(zone)` 补默认顺序；
6. `pillAnchorWriters` 补锚点登记（否则浮层钉左上角）；
7. `PillHost` 的 `when` 补分支；
8. 设置页显隐列表、label 资源、守卫测试（`CamPillTest`、`PillAnchorRegistryTest`、`HudLayout*Test`）。

### 7.5 `ParamsPersist`：34 项白名单与会话影子

**白名单 = 34 项**，精确为：

```text
default_fps, default_shutter_ns, default_bitrate, default_ref_lines, level_enabled,
default_iso, default_ae_mode, default_ev, default_wb_preset, default_wb_kelvin,
default_wb_tint, default_zoom, default_flash, default_stabilize, default_af_mode,
default_mf_diopter, default_size, default_frame_effect, default_zebra_threshold,
default_zebra_density, default_peaking_strength, default_peaking_color, default_lens,
default_arc_merge_deadband, denoise_audio, denoise_video, correction_auto,
correction_manual_ns, default_marker_spread, default_marker_color, glow_params,
tone_params, display_scale, echo_params
```

源码注释里若仍写“23 项 / 32 项”，那是旧值；以 `ParamsPersist.KEYS` 与 `ParamsPersistWiringGuardTest` 的 `expectedKeys` 为准（顺序也被钉住）。

**两条链**：

- **冷启恢复** `ParamsPersist.restore(prefs, params)`：逐项读取、坏值跳过或回默认、写回 `WotaParams`（只 `copy(value=)`，不碰 range / enabled / exact）。最终设备域由随后的 `applyAbility` 收口。它由 `WotaSettings.applyDefaults()` 末尾调用。
- **落盘** `ParamsPersistSession`：进录制页时冻结一份当前总线的“影子”；只在用户动作完成点调 `flush()`；`flush()` 读一次总线，**只写与影子不同的字段**；有差异才 `commit()`，成功后推进影子，失败保持旧影子下次重试。无差异不写盘、不触发监听。

> **已知实现缺口（2026-11-16 核对）**：`flush()` 成功分支更新了绝大多数影子，但**漏了 `correction_auto` / `correction_manual_ns` 两项**（`ui/ParamsPersist.kt` 的 `flush` 成功块里没有 `shCorrectionAuto` / `shCorrectionManualNs` 的赋值）。后果：同一会话里把“自动微调曝光”先关再开回，最后一次与旧影子相等，可能不落盘。Fork 若要修，补两行影子赋值并加一个“改 → 改回 → 再 flush”的测试；修好前不要把这组键当成绝对可靠的持久化保证。

**允许的 flush 落点**（六类，守卫钉住）：关闭参数弹层、长按锁定 AE、`ON_STOP`、`PillHost` 透传的持久化回调、站位线间距滑杆松手、站位线选色完成；滑杆松手统一走 `PillSlider` 的 `onValueChangeFinished`。

**禁止入档的字段**（不要让后人“补齐”）：

- `default_render_mode` / `default_sample_rate`：录制页里对它们的写是“※ 档自动切 GPU”之类**自动降级**，入档会把降级静默写成用户默认；
- `default_audio_muted` / 音源双开关：音源不跨进程恢复，否则每次启动都要重新申请内录授权；
- 对焦框、对焦锁定、AE `LOCK`：瞬时态；
- `curve_stack` / `arc_convert_mode`：已有专属直写链，避免第二写入方；
- `marker_count`：刻意不入档。

**新增一个录制参数入档**：`WotaParams` 字段 → UI 写值 → `ParamsPersist.KEYS` 加键 → `restore` 接线 → `ParamsPersistSession` 影子字段 → `flush` 读取 → 守卫测试更新（多一少一都红）。自动降级字段和瞬时态一律不加。

### 7.6 镜头偏好与播放器 URI 键

- `wota_lens_pick` / `lens_user_picked`：只记录“用户手选过镜头”，不参与换机迁移。镜头能力本身每次运行时从 CameraCharacteristics 重新读。
- `wota_player_ab`：
  - `ab_a:<uri>`（Long，A 点 ms）、`ab_b:<uri>`（Long）、`ab_loop:<uri>`（Bool）；
  - `sync_done:<uri>`（Bool）、`sync_offset:<uri>`（Long）、`sync_source:<uri>`（`auto` / `manual`）、`sync_conf:<uri>`（Float）、`sync_at:<uri>`（Long）。
- 这些键都以 `uri.toString()` 为后缀，**清理媒体时要同步清**（`TrackSync.clearFor` / 删除链）；否则回收站清空后会留下永久孤儿键。
- `wota_diag` / `last_session`：只用于区分“上次是正常退出还是被杀”，不含内容。

### 7.7 站位（AI）键

定义在 **full 源集** [stance/StanceController.kt](WotaGeiCamApp/app/src/full/java/com/wotagei/cam/stance/StanceController.kt) 的 `Prefs`，写入 `wota_settings`：

| 键 | 类型 | 默认 | 域 / 语义 |
|---|---|---|---|
| `stance_on` | Bool | false | 站位提示总开关 |
| `stance_mode` | String | `auto` | `auto` / `manual` |
| `stance_n` | Int | 1 | 1..5，手动人数 |
| `stance_beta` | Float | 0.3 | 0.05..0.95，判定松紧 |
| `stance_brighten` | Bool | false | 分析前提亮 |
| `stance_line_interval_ms` | Long | 2000 | 500..10000，提示线间隔 |
| `stance_poll_interval_ms` | Long | 4000 | 500..10000，轮询间隔 |
| `stance_gpu_mode` | String | `auto` | `auto` / `force` / `off` |
| `stance_gpu` | Bool | — | **旧布尔键，只读迁移一次**，不再写 |

lite 源集没有这些键的写入方；full 的 `StanceController` 是唯一 owner。站位参数是“实时旋钮”，不进 `ParamsPersist` 档案，但部分键进入备份白名单（见 §7.8）。

### 7.8 备份白名单与二进制格式

[backup/PortableUserData.kt](WotaGeiCamApp/app/src/main/java/com/wotagei/cam/backup/PortableUserData.kt) 的 `SETTING_KEYS` 是显式白名单，当前包含约 60 余项，按类型分派：

- Bool：`default_audio_muted`、`correction_auto`、`level_enabled`、`level_buzz_enabled`、`player_top_bar_collapsed`、`hud_frost_blur`、`stance_brighten`；
- Int：帧率 / 快门 / 码率 / 采样率 / ISO / EV / 白平衡色温色调 / 斑马密度 / 并入门槛 / 参考线 / HUD 掩码 / 文本缩放 / 网格列数 / 亮度 / 对比节拍 / 站位人数等；
- Long：`correction_manual_ns`、站位两个间隔；
- Float：变焦 / 屈光度 / 斑马阈值 / 峰值强度 / 站位线间距 / 曲线面板边长 / `stance_beta`；
- 其余白名单键按 String（UTF-8 长度 ≤ 65536 字节）。

> 注意三个容易看混的键：`default_render_mode`（GPU/DIRECT）、`default_sample_rate`、`default_audio_muted` 都在**备份**白名单里，但都不进 `ParamsPersist` 档案。它们是设置页独占 / 自动降级 / 音源类字段；“备份允许迁移”不等于“录制页可以写回”。

**刻意不迁移**：诊断开关、`compare_history`（带 URI）、`lens_user_picked`、`wota_player_ab`、`wota_diag`、回收站、URI、MediaStore ID、机型能力、模型文件。`stance_on` 与 `stance_gpu_mode` 也不迁移（新机默认不自动开 AI / 不依赖本机模型）。

格式（`VERSION = 1`）：

```text
int version
int settingsCount
  repeat: text key, byte type(1..5), value
int tagCount
  repeat: text relativePath, text name, long size, long durationMs, text value, long createdAt
```

限制：整文件 ≤ 4 MiB、≤ 10000 条标签、单串 ≤ 65536 字节；读取时校验版本、重复键、tag 指纹合法性、尾部必须无剩余数据；坏 / 截断 / 超量一律 `InvalidSnapshotException`，**绝不部分恢复**。

恢复语义是**补充不覆盖**（`settingsToRestore` 只取目标机没有的键）；标签按“相对路径 + 文件名 + 大小 + 时长”指纹匹配，只恢复唯一且完整匹配的媒体。

系统备份边界：`AndroidManifest.xml` 声明 `fullBackupOnly=true` + `WotaBackupAgent`；`res/xml/backup_rules.xml` 与 `data_extraction_rules.xml` 都只 `include domain="file" path="wota_user_backup.bin"`，不开放 root / sharedpref / database / external。

**新增设置键时**：显式判断能否迁移；能迁移就加进 `SETTING_KEYS` 与对应类型集合；带 URI、设备能力、瞬时态、日志开关的键不要加。只有**结构格式**变化才升 `VERSION`，加一个键不需要。

### 7.9 Room 与媒体文件协议

**Room**（[media/WotaDb.kt](WotaGeiCamApp/app/src/main/java/com/wotagei/cam/media/WotaDb.kt)）：

- 数据库 `wota_media.db`，`version = 1`，`exportSchema = true`，schema JSON 在 `app/schemas/com.wotagei.cam.media.WotaDb/1.json`。
- 表：
  - `media_tag`：`id` 自增主键、`mediaId`、`path`、`tag`、`createdAt`；唯一索引 `(mediaId, tag)`，索引 `tag`；
  - `trash_item`：`uri` 主键、`mediaId`、`name`、`deletedAt`、`origin`；索引 `mediaId`；
  - `media_series`：`seriesId` 主键、`startedAt`、`partCount`；
  - `series_part`：复合主键 `(seriesId, partIndex)`、`mediaId`、`uri`、`durationMs`。
- **红线**：`fallbackToDestructiveMigration()` 存在；按 Room 的破坏性迁移语义，新增实体 / 列 / 索引而不写 Migration 会删表重建，用户标签、收藏、回收站、系列随之丢失（本轮未做迁移演练，结论来自该配置的既定语义）。不要为了“先跑起来”接受破坏性迁移。
- 收藏标签空间 `TagSpace.LIKE = "like"`；改名后要 `movePathAfterRename`；删片后要清 `media_tag`、`series_part`、`trash_item`、缩略图与 `TrackSync` 缓存，再 `pruneOrphans`。
- 删除收尾必须在 `NonCancellable` 中跑完；收尾被取消会留下“媒体库永久空列表”的闸门状态。

**文件目录**（MediaStore 相对路径）：

| 目录 | 来源常量 | 内容 |
|---|---|---|
| `Movies/WotaGeiCam/Camera` | `VideoStore.RELATIVE_PATH` | 录制成片 |
| `Movies/WotaGeiCam/Clip` | `ClipRule.RELATIVE_PATH` | 无损剪辑 / 后渲染导出 |
| `Movies/WotaGeiCam/Inau` | `TrackRule.RELATIVE_PATH` | 选轨导出 |

命名：

- 录制：`VID_<yyyyMMdd_HHmmss>_<3 位随机>.mp4`；处理过的片可带前缀（如 `30fto24f_`、`edit_`）。
- 剪辑：`edit_<源名去扩展名>.mp4`。
- 写入走两段式：`IS_PENDING=1` 创建 → 写完 `commit` 清 0；分段每段各自一个 pending URI；`seal` 封段。
- 媒体库可见性门是 `IS_PENDING=0`；目录识别关键字是 `WotaGeiCam`。改目录会让媒体库“看不见旧片”，也会让 FileProvider / 分享路径失配。
- 空间门槛 200 MB；单段上限 3.5 GiB（FAT32 安全值）；到 85% 顶栏预提示换段。
- `.part` / `.pending-*` 残留由冷启清扫处理（1 小时阈值、最多 500 条、索引最多 40）；`PendingName` 是清洗与归位的唯一出口，不要各写一套正则。

**sidecar `.drops.json`**：录制期抽帧成片的丢帧位次记录，与视频同目录同名换扩展名；内容是 `mode`、`dstFps`、`drops`（`[帧序号, 丢帧数]`）、可选 `segments` / `lostMs`。改名要跟着视频走，删除要一起删。**剪辑导出会读取并重映射它**（`ClipExport.writeSidecar` → `ArcDropLog.readFrom`），所以它不是纯写档案。但录制期的 `drops` 的 k 是**段内序号**，停录时只取得**最后一段**的丢帧账，却随**首段**成片落盘；`segments` 里的 `first/count` 才是跨段真实口径。不要把它当“首段完整账”或跨段统一坐标；跨段精确修复要先改记录模型。

### 7.10 易混名字对照

| 名字 | 不是什么 | 实际语义 |
|---|---|---|
| `HudItem.ZOOM` / `CamPill.ZOOM` / `SliderKey.ZOOM` / `default_zoom` | 不是同一个“变焦” | 读数位 64 / 控件位 8 / 连续滑杆 id `zoom` / 默认倍率 Float |
| `default_lens` / `lens_user_picked` | 不是相机 ID | 前者是 `LensType` 产品档，后者是“用户手选过”的 Bool |
| `denoise_audio` / `denoise_video` | 不是值 | 键名；值是枚举 key（`off` / `system` / `hal_fast` / `hal_hq` / `gpu_bilateral`） |
| `tone_params` / `curve_stack` | 不是同一个调色链 | 色调四滑条独立键；曲线 4 段独立键；两者都烘进 LUT 但格式分开 |
| `hud_items` / `hud_pills` / `hud_pills_schema` | 不是同一套掩码 | 读数显隐 / 控件显隐 / 控件掩码版本 |
| `default_audio_muted` / `ParamsPersist` | 不矛盾 | 设置页可存“静音录制”，录制页的音源开关不跨进程恢复 |
| `default_render_mode` / `display_scale` | 不是同一件事 | 前者 GPU/DIRECT；后者只改上屏 RT 尺寸 |
| `marker_count` / `default_marker_spread` | 不是同一生命周期 | 线数是运行时值（不落盘）；间距/颜色落盘 |

<a id="camera-contracts"></a>
## 8. 相机参数、渲染与录制契约

### 8.1 能力来自运行时，不来自机型表

- 一切范围、档位、能力开关必须从 `CameraCharacteristics` 派生（`core/CameraAbility.from()`），产品档表只做“产品允许的上限”，再与设备能力求交。**禁止按机型硬编码数值。**
- 公开 API 没有的能力，只能按名称读隐藏键（例如 `android.colorCorrection.availableModes`、`android.sensor.neutralColorPoint`），并在运行时探测失败时如实降级；不要假设所有 HAL 都提供。
- 镜头按“逻辑头 + 物理头”枚举：`LensType` 是产品语义档，`LensSlot.key`（`logicId#physicalId`）才是唯一身份。后摄按物理焦距归档，多颗同焦段各自成档；前摄统一 `FRONT`，靠 key 区分。
- 部分子头只在逻辑头的 characteristics 里报 AF 模式；`effectiveAfModes` 负责回退，不要因为物理头没报就判成定焦。
- 换镜头 = 整套能力换挡：重算范围、钳制当前值、必要时重建会话。不要只改 UI 的灰显。

### 8.2 帧率与快门的硬不变量

- 帧率决议只保证“下发的值是设备声明过的”。普通表优先，高速表另走 `CONSTRAINED_HIGH_SPEED_VIDEO` 会话；>60fps 是分水岭。
- 24 / 25 是产品必须档：设备没有精确固定范围时仍可选，但标 `※` 表示近似档；不要把“近似”伪装成“精确”。
- **帧周期与快门基准必须用选中的 fps 值算，不能用区间下界**。历史上用 `[5,30]` 的下界 5 去算，把某 vivo 机型物理锁死在 4.98fps，这是已修复的严重缺陷，不要回退。
- 快门钳制：不超过 1/帧周期，并有 1/10 s 防手抖上限。1/24、1/25 的“强制档”只豁免**设备曝光范围**这一刀，不豁免帧周期与 1/10 s 两条产品规则。因此 25fps 下 1/24 仍不可选，24fps + 1/24 才是有效组合。
- `clampShutterNs` 是下发侧唯一出口；UI 的灰显与它会话必须同源。新增帧率写入点必须走 `setFps`，否则快门可选域不跟着更新。
- 高速会话出现 `IllegalArgumentException` 时降级普通会话；`IllegalStateException` 走预览重启；`CameraAccessException` 才判设备失败。不要把所有异常都当“设备坏了”。

### 8.3 曝光、白平衡、对焦

- **AE 手动档一次写全 ISO + 快门 + 帧周期三件套**，杜绝半手动中间态；AUTO 档刻意不写 SENSOR 三键（部分 MTK / vivo 会错采纳）。`LOCK` 是瞬时态，不跨进程。
- EV 的单位是**步数**，不是倍率；范围来自设备。
- 手动白平衡需要设备声明 `TRANSFORM_MATRIX` 能力，且部分机型声明了也不真正采纳增益。无法采纳时走画面层算法兜底并如实提示。不要删掉“离开录制页保活覆盖期”的早退判据，否则设置往返会闪绿帧。
- AF：`CONTINUOUS_PICTURE` 用 `TEMPLATE_PREVIEW`，其余用 `TEMPLATE_RECORD`；点按对焦 / 测光落点是预览归一化坐标，换算 `MeteringRectangle` 是相机层的事；长按锁定 AE 与对焦锁定是两条状态，不要合并。
- 会话重启后若对焦处于锁定态，要自动重新 `engageFocusLock("session-restart")`，否则锁定静默丢失。

### 8.4 方向：三写者与两套矩阵家族

方向由三个量共同决定：`sensorOrientation`、`displayDegrees`、`stMatrix`（`camera/PreviewOrientation.kt`）。

- `residualDegrees() = normalize(sensor - display)`；`appliedDegrees() = normalize(residual + stRotation)`。
- `stRotationFromMatrix` 有两族解析：常规“课本族”与**恒等族**（前摄常见，少一次 t 翻转）。不要用“矩阵是否恒等”来猜方向；用函数返回的 `stIdentityFamily`。
- 前摄容器角额外 +180°，`encoderMirrored = stIdentityFamily`（前摄镜像），后摄不镜像。
- 上屏与编码是两套 texcoord：编码用 `stRotationDegrees + encoderMirrored`，显示用 `displayDegrees + displayMirrored`；不要合并成一套。
- DIRECT 上屏要补 `appliedDegrees`，因为 TextureView 已把缓冲变换烘进上屏；漏补就是“预览正、成片歪”或反向。
- 导出方向单点仲裁：`normalizeQuarter` / `exportOrientationHint` / `ORIENTATION_MUXER_CCW=false`。送解码器的 `MediaFormat` 必须先**剥掉 `KEY_ROTATION`**（复制后 `removeKey`），否则平台会把旋转烘进像素，再写 muxer hint 就多转一次（实测差 180°）。不要用“把 hint 归零”来修，像素已被烘过会拉伸。
- 方向改动必须补 `PreviewOrientationTest` / `OrientationExportTest` 一类几何断言；只靠肉眼在一种方向上“看起来对”不算验证。

### 8.5 渲染模式与效果边界

- `RenderMode.GPU`：GL 全链路。`RenderMode.DIRECT`：绕过 GL，相机面直连编码器；**斑马纹、峰值、Glow、残影、霜底板全部不可用**，画面降噪 GPU 双边退回 HAL 快速档。
- 不可用不是“静默失效”：面板要显示降级说明，胶囊选中态按“实际生效”而不是“用户选择”来画。
- `DisplayScale` 只改窗口 pass 的离屏 RT 尺寸，不重建相机会话、不改成片尺寸、不接回相机流。
- 新增 scene 效果时，必须同时决定：DISPLAY 与 MOVIE 两槽是否都应用、是否进 `ProgramKey`（只有开关级变化才进）、编译失败时在回退阶梯的哪一级、离线导出是否支持、DIRECT 下如何提示。
- 新增 monitor 效果时，只能出现在 DISPLAY 键里；`movieProgramKey` 的签名里不应出现 effect 形参，让“监看不进成片”在类型层面无法绕过。
- 着色器里的 hash / 噪声必须用 `highp`（有 `GL_FRAGMENT_PRECISION_HIGH` 守卫）；大半径模糊必须迭代卷积，不能单遍稀疏采样。

### 8.6 录制引擎与音频

- 引擎选择是产品语义，不是性能优化：抽帧补弧、内录、>60fps、音频降噪四类**必须**走 `CodecRecorder`；其余可走 `MrRecorder`。
- 双音轨独立开关：环境音（AMBENT）与内录（CAPTURE）可任意组合，`audioTrackPlan()` 是唯一映射桥；不要再写“内录开就关环境音”的互斥。
- 内录需要 `MediaProjection`：前台服务类型必须是 `mediaProjection`，顺序是 `startForeground` → `getMediaProjection` → `registerCallback`；回调里要校验 projection 身份，旧回调不能拆新会话。
- 内录轨丢失 / 全静音 / 建立失败是三种不同状态，要通过 hint 通道告知用户，不能只写日志。
- 录制起速自检：引擎没在产出时自动重录（有次数上限）；`MIN_OUTPUT_BYTES` 之类的门槛用于区分“刚起录”与“真的没写”。
- 帧账记录相机 → 渲染 → 编码 → muxer 的计数，只用于诊断；不要用帧账反推 PTS 或丢帧策略。

### 8.7 分段、PTS 与异常收尾

- 单段上限 3.5 GiB；到 85% 预提示。换段时旧编码面已死，Surface 交接顺序见 §6.5。
- PTS 以编码器输出为主；mux 写入侧做逐轨单调修正，负 PTS 样本丢弃（开放 GOP 的前导 B 帧）。不要直写负 PTS：会让 MediaMuxer 给整条轨加巨大偏移并写出畸形 `ctts`，部分播放器表现为无声 / 错锚。
- 取消 / 停止 / 引擎崩溃的收尾顺序：停止接受新帧 → 等待编码器 drain（有界）→ 封轨 → muxer stop → commit → 释放 Surface / codec / muxer / extractor。任何一步被跳过，下一次起录就会遇到“设备忙 / 会话失效”。
- `BoundedStore` 用于起录 / 会话有界等待；不要在主线程 `await` 3 秒等待收尾。长任务作用域要对齐“谁拥有它”：用户发起的导出属于窗口，页面退出不取消。

<a id="ui"></a>
## 9. 界面、控件定位与多语言

### 9.1 页面与方向

- 单 Activity + Compose；设置页、控件编辑页、媒体库、播放器、对比页都受同一个 `ui_orientation` 设置控制：横屏档用 `SENSOR_LANDSCAPE`，竖屏档用 `SENSOR_PORTRAIT`，保留同轴翻转。不要改回 `LANDSCAPE` / `PORTRAIT` 单朝向。
- 贴边避让交给系统：`safeDrawingPadding()`（必要时叠 `displayCutoutPadding()`）。**禁止按方向写死 padding**：同一枚写死方向的常量在两个横屏方向必错一次。
- 可视边界要看 `mAppBounds` / `DisplayCutout`，不要相信 UI dump 的表观右缘（那可能是宽度被当成右边界）。

### 9.2 胶囊与锚点：`pillAnchorWriters` 是唯一登记表

在 [ui/CameraPills.kt](WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/CameraPills.kt)：

- `PillKey` 是控件身份；`RECORDING_EDITABLE_PILLS` 是“录制中可调”的单一白名单；`pillAnchorWriters` 是“这颗胶囊的浮层锚点由谁写”的单一登记表。
- 带就近弹窗的控件，锚点形参**必须无默认值必传**，漏挂要编译不过；新增 `PillKey` 同轮在 `pillAnchorWriters` 补一行。历史症状：有触发点无写入方，浮层永久钉在屏幕左上角。
- `PillHost` 的 `when` 要穷举；新增胶囊不改这里会编译失败，这是有意的。
- `PillPopup` 只负责浮层外观；`PillSlider` 的 `onValueChangeFinished` 是滑杆落盘的统一触发点，新增滑杆必须接。

### 9.3 刻度滑杆

- `PillSlider.ladder` 有效时，滑杆跑索引空间 `0..size-1`、`steps=size-2`，回调再换回真实值；读数显示真实值。不要直接把 ladder 当数值数组塞给 Material3。
- 梯由 `SliderLadder.kt` 的纯函数按运行时范围过滤，不写死机型数值；梯不足 2 点 → 禁用，不能静默变成连续滑杆。
- `SliderKey.id` 是持久化契约，只加不改；`slider_continuous` 只存“关掉吸附”的 id。
- 连续滑杆的 `snap()` 在 `ParamSlider.kt`：栅格锚下限 + 量化回钳；新增参数沿用同一桥，不要各写一份。

### 9.4 设计令牌、动效与视觉验收

- 颜色 / 字阶 / 间距 / 圆角以 `ui/design/Tokens.kt` 为唯一真源；新增颜色先加令牌，不在调用点写十六进制。
- 文字三档：`textHi` / `textMid` 承载正文（对比度 ≥4.5），`textLo` 只准非正文 / 辅助文本（≥3.0）；承载 `textLo` 的件不接霜。
- 动画三档：`PLAIN` / `FLUENT`（默认）/ `LIQUID`。液态档用 `LiquidSpring` 的 `response + dampingFraction` 参数化，刚度换算是唯一实现；交互路径用 `Animatable.animateTo(initialVelocity=…)` 保留速度，不要 `snapTo + 重新计时`。
- `Motion.kt` 之外不要直调 `tween(` / `animateColorAsState` / `animateDpAsState` / `expandIn` / `shrinkOut`；`MotionHygieneTest` 会拦。容器尺寸动画是登记过的具名例外。
- 系统“减少动效”开启时，时长归零、按压形变停用，但状态切换仍要发生。
- 视觉问题验收：模拟器 / 真机**同坐标像素采样** + 开关对照；动画落定 ≥3 秒后再取证。Compose 的 `pointerInput` 要跟随 `graphicsLayer` 位移时，必须挂在 layer 内侧；`Modifier.background(...).alpha(x)` 的黑底恒不透明，要淡入淡出必须 `.alpha(x).background(...)`。

### 9.5 文案与资源

- **新增**用户可见文案必须走 `res/values/strings*.xml`：`strings.xml`（通用 / 权限 / 诊断 / 内录）、`strings_camera.xml`、`strings_gallery.xml`、`strings_player.xml`、`strings_settings.xml`、`strings_bt.xml`、`strings_level.xml`。
- 当前仍有**遗留硬编码**：设置页“HUD 毛玻璃背板”及其说明两条直接写在 `SettingsScreen.kt`（源码注释已标注待迁移）。改到附近时顺手换成资源；不要把“已经有例外”当成新增硬编码的理由。
- 当前只有简体中文资源；新增文案加到对应文件，不要在 Kotlin 里写中文字面量（测试 / 翻译 / 文本缩放都依赖资源）。
- 不要改已有资源名；改值可以。新增语言是新增 `values-xx/` 目录，并检查文本高度与换行。
- 文本缩放三档（录制页 / 设置页 / 弹层）只缩放字号与行高，不改布局常量；新增固定高度控件时要验证 80% 与 120% 两档不裁字。

### 9.6 取证钩子只在 debugHook

`MERGE_HOOK` / `FROST_PROBE` / `COMPARE_PROBE` 三个 BuildConfig 字段只在 `debugHook` 为 true；`probe` 也为 true（仪器测试用），`release` / `debug` 恒 false。不要用 `BuildConfig.DEBUG` 判断钩子，也不要在发布包打开 adb extra 入口。取证包与正式包同签名判据，安装用 `adb install -r`，**不要 uninstall**（会清 `wota_settings` 与 `wota_media.db`）。

<a id="media"></a>
## 10. 媒体库、播放器与文件协议

### 10.1 写入：两段式 pending 与命名

- **录制成片**走 `VideoStore`：`MediaStore.Video.Media.EXTERNAL_CONTENT_URI` 插入 `IS_PENDING=1` 的行 → 写入 → `commit` 清 `IS_PENDING`。剪辑 / 选轨 / 后渲染导出自带 `insertPending`（同一套两段式约定，只是目录不同），不要以为它们都经过 `VideoStore`。
- **删除 pending 的判据是“成片是否已封轨”，不是“这次调用是否成功”**：`muxer.stop()` 之前的半成品才删；封轨成功后 commit 全败、取消或异常都**保留 pending 行**待冷启救援（删行会连带删文件，等于丢用户的成片）。`VideoStore.seal` 对 commit 失败也只报不删。
- 目录与命名见 §7.9；分段录制时每段各自一个 pending URI，`seriesId` 相同。
- 空间门槛 200 MB；低于门槛直接拒绝起录并给出原因码（`LOW_STORAGE:<MB>`）。
- sidecar 也走 `MediaStore.Files` + `application/json`，因为部分机型不允许在 `Movies/` 下直接写非媒体文件；写 sidecar 失败不应该让视频本体判废。
- FileProvider 只暴露三个最小根：`Movies/WotaGeiCam/`、`cache/updates/`、`cache/share-out/`。**不要把整盘或整个 cache 设为根**：任何未来调用方把任意路径喂进来，过宽的根都会为敏感文件铸出可读 URI。非本应用目录的视频由分享逻辑退回原 content URI。
- 冷启维护：`StalePendingSweep` 清 1 小时以上的 `.pending-*` / 临时件；`rescuePendingRows` 救援导出链 commit 全败留下的 `IS_PENDING=1` 行。归位时**不覆盖已有文件**。

### 10.2 发现与权限

- 媒体库识别关键字是 `WotaGeiCam`，可见性门是 `IS_PENDING=0`；`VideoClip` 是可播放条目的统一模型（id / uri / 名称 / 路径 / 时长 / 尺寸 / 标签 / 回收站 / 系列）。
- 系统照片选择器 / 文档 provider 回来的 URI 不一定是 MediaStore 条目：`isMediaStoreVideoUriParts` 只认 `authority=media` 且路径为 `[_ , video, media, <数字>]`；外来片 `id=0`，不能拿 id 反查，只能按 URI 播。`PickFailStage` 区分“读不到 / 不是视频 / 解不了码”。
- 权限四态：`ALL_FILES` / `FULL_READ` / `PARTIAL_READ` / `NONE`。Android 14+ 的“选择照片和视频”档对应 `READ_MEDIA_VISUAL_USER_SELECTED`；`MANAGE_EXTERNAL_STORAGE` 只有 API 30+ 才有，API 29 不要显示这个入口。
- 选片回程要 best-effort `takePersistableUriPermission`（吞 `SecurityException`），否则重启后 URI 失效。权限不足的提示要能行动（去设置 / 换选法），不要只说“读取失败”。
- 删除 / 改名非本应用录制的视频依赖“所有文件访问”；没有该权限时只对本应用目录生效，UI 要如实说明。

### 10.3 标签、回收站、系列（Room）

- 收藏是 `media_tag` 里 `tag = "like"` 的行；标签唯一键 `(mediaId, tag)`。改名后 `movePathAfterRename` 同步路径，不能只改 MediaStore。
- 回收站直写 MediaStore 的 `IS_TRASHED`；失败要回滚前缀，不能留下“界面已删、文件还在”的半态。
- 彻底删除的顺序：删 MediaStore 行 → 清 `media_tag` / `series_part` / `trash_item` → `pruneOrphans` → 清缩略图 → 清 `TrackSync` 缓存。整段在 `NonCancellable` 中跑完；中途取消会把媒体库卡在“永久空列表”。
- 系列：一次录制的分段共享 `seriesId`；`SeriesStore.record` 至少 2 段才建系列；分组动作（改名 / 分享 / 删除）按组扇出，不要逐段各弹一次。
- **新增实体 / 列 / 索引必须写 Migration**；当前 `version=1` 且开了破坏性迁移，直接改实体等于清空用户数据。

### 10.4 播放器

- `PlayerEngine` 包装 Media3 ExoPlayer；`softPause()` 保留实例，`releasePlayer()` 不可逆。页面切换优先 softPause，只有确定离页 / 媒体变更才 release。
- 进度只有一个来源：120 ms 轮询。不要同时挂 `Player.Listener` 的 position 更新与轮询，两者会打架。
- 倍速档 `0.1 / 0.3 / 0.5 / 0.75 / 1.0`；`audioPitchFor(speed) = 1f`——**保持原声音高 / 音色，只拉长时间**。不要恢复成“磁带 pitch=speed”的旧口径。
- 逐帧步进是纯帧号算术（`FrameStep`），有 seek 未落地的容差；不要用 `currentPosition / frameDuration` 直接取整。
- AB 循环按 URI 持久化（`ab_a:<uri>` 等）；A/B 无效（B 不晚于 A）要提示，不要静默。
- 双指缩放 / 平移 / 水平镜像只影响预览显示，不改文件。

### 10.5 三条导出链

| 链 | 入口 | 做什么 | 关键约束 |
|---|---|---|---|
| 无损剪辑 `ClipExporter` | 播放器 A/B → 剪辑 → 无损 | 重封装，不重编码 | 入点向前对齐关键帧；出点按 PTS；音轨可选（ENV/CAP），零样本轨不建；负 PTS 丢弃；逐轨单调；commit 4 次重试（2 立即 + 2 延迟） |
| 后渲染 `SceneExportRunner` | 播放器 → 剪辑 → 后渲染 | 解码 → GPU 双边降噪 → Glow → 残影 → 合成 → 重编码 → mux | **不做 RGB 曲线**（录制时已烘进源片）；全分辨率；解码 format 剥 `KEY_ROTATION`；方向靠 muxer hint；单 owner、忙则 BUSY；watchdog 有界；commit 与 mux 失败分开报 |
| 光弧修复 `ArcRepairRunner` | 播放器 → 光弧修复 | 抽帧 / 补弧到目标帧率 | 目标帧率不高于源 → `NO_NEED`；GPU / CPU 二选一；写新文件不覆盖原片；名字前缀 `XXftoXXf_`；并入门槛与录制期同一判据 |

- 三条链的音频轨选择一致：容器第一条音频 = ENV，第二条 = CAP。`sideHasDual` 的真值来源是 `TrackSync.countAudioTracks >= 2`，不是 `engine.audioGroups.size`。
- 导出作用域：`ExportHost` Activity 级；离开播放页不中断，进度与结果留在宿主。上一轮收尾未完时新请求 BUSY。
- 取消不能被包装成失败；commit 全败但成片字节已在盘上时，保留待冷启救援，文案不能说成“导出失败”。
- 后渲染导出弹层里改的参数只作用本次，不反写设置；弹层外要有常驻进度 / 百分比，不能只留一条无反馈的进度条。

### 10.6 双轨对比

- 两个 `PlayerEngine`；时间线纯逻辑在 `CompareTimeline`：域 `LEFT_SOLO` / `RIGHT_SOLO` / `DUAL` / `TIMELINE_END`，偏移符号约定 `rightPos = leftPos + offsetMs`。
- 音频只允许一侧出声（`CompareAudioPlan`）：`SetVolume(muted,0)` → 切轨 / 清覆盖 → `SetVolume(full,1)`，顺序不能反。
- 自动对齐走 `AudioSync`（8 kHz 单声道、10 ms RMS 包络、±10 s 互相关粗对齐 + 1 kHz FFT ±50 ms 精修）；低置信度要提示手动校正。
- 对比历史 `compare_history` 最多 12 条；外来片只记 URI。历史只用于找回组合，不是媒体索引。
- “对着左片练”是进程内桥（`ComparePractice`），不要持久化到 prefs。

<a id="services"></a>
## 11. AI、更新、备份与诊断

### 11.1 站位（AI）的 full / lite 双实现

- 两个 flavor 在**同一个 FQN** `com.wotagei.cam.stance.StanceFeature` 下各有一份实现：
  - `src/full/.../StanceFeature.kt`：完整检测、叠加、播报、模型包导入、GPU 三态；
  - `src/lite/.../StanceFeature.kt`：无推理的导流桩，`Overlay` 为空，弹层只说明“此版本无 AI 站位”。
- `src/main/.../stance/StanceUi.kt` 是两边共同遵守的对外数据类型；录制页只依赖它，不 import flavor 实现类。新增 AI 能力时，先改 `StanceUi` 契约，再改两份实现，否则 lite 编译不过或悄悄带上推理依赖。
- 分析 owner 是 `StanceAnalysis`：单条 `WotaStanceAnalysis` 线程、`ImageReader` YUV、约 3 fps 节流（333 ms）、YUV→RGBA、旋转、可选提亮、检测；模型热换也在这条线程内串行。
- 集成 owner 是 `StanceController`：持有 analysis / detector / speaker，对 UI 只暴露 `StateFlow<UiState>`；模型装配在 `WotaStancePack` daemon 线程，`packMutex` 保证文件事务不与装配交错。
- 相机侧挂载点是 `CameraController.setAnalysisTarget(surface, reader)` → `Camera2Engine.setAnalysisTarget`。会话是否接纳分析流由 `AnalysisSessionDecision` 表示：`ABSENT` / `INCLUDED` / `BLOCKED_HIGH_SPEED`；高速会话不强行降帧率，只暂停分析。
- 录制窗口只 `suspendForRecording` / `resumeAfterRecording`，不摘分析槽、不重建引擎；离页停听、回场恢复。前摄不提供站位提示（D13）。
- 站位参数是“实时旋钮”，prefs 即改即存，不进 `ParamsPersist` 档案；键表见 §7.7。

### 11.2 模型包契约

`src/full/assets/model_pack/` 当前是 5 个文件：

```text
pack.meta.json
head.json
margins.json
yolo11n_int8.tflite
yolo11n_fp32.tflite
```

- `pack.meta.json` 的 `schema = 1`，`features = [cx, by, w, h, conf]`，`files` 里逐文件带 SHA-256；`yolo.backends` 标注 `cpu: int8`、`gpu: fp32`、`npu: 未接入`。
- 校验（`StanceModelPack.validate`）要求：**恰好 5 个子项**、路径 canonical 且非符号链接、体积上限（JSON 64 KB / 单模型 64 MB / 整包 128 MB）、`schema` 匹配、`features` 匹配、`files.keys == 基础文件 + 一个 GPU 文件`、逐文件 SHA-256 匹配、`head.json` / `margins.json` 结构可解析。
- 目录：`filesDir/model_pack_builtin`（内置）、`filesDir/model_pack_user`（用户包，优先）、`externalFilesDir/model_packs`（只放待导入 zip，**永不作为 Interpreter 加载目录**）。
- 导入流程：staging `model_pack_import_*` → 校验 → rename 到 `model_pack_user`；rename 间崩溃由 `model_pack_previous` 恢复最后有效包。用户包坏时自动回退内置包并提示。
- 换模型 = 同时改资产、`pack.meta.json`（含 SHA）、必要时 `StanceHeadSpec.FEATURES` / 输入布局 / 输出解析 / 阈值。**输入布局必须从张量实读**（支持 NCHW 与 NHWC），不要猜 `[1,3,448,448]`。
- `tflite` 在 Gradle 里 `noCompress`，便于 mmap；不要为了体积开启压缩。
- LiteRT 1.4.2 只进 full；源码 import 仍是 `org.tensorflow.lite` API。不要把 LiteRT 依赖加到 main / lite。

<a id="update"></a>
### 11.3 应用内更新

- 唯一允许运行时联网的包是 `update/`。`UpdateNetworkGuardTest` 会 grep 全源码：网络符号只许命中该包；**不要在别的包加 HTTP / URL / Socket**。
- 仓库常量在 `UpdateConfig`：`REPO_API_LATEST` / `RELEASES_LATEST_PAGE` / `DOWNLOAD_BASE` 都指向 `himetuki/WotaGeiCam`。Fork 必须替换这三处，以及 `RedirectProbe.guessUniversalAssetName` 里的资产名规则（若项目名 / 命名规则变了）。
- 探测三段：GitHub API latest → API 镜像 → `releases/latest` 302 页解析 tag。任一段成功即可；都不通才判失败。
- 下载候选 = 直连 + 镜像；先并发测速（6 秒预算、256 KB 采样、首块验 `PK` 魔数），按实测字节率降序；失败按速回退。
- URL 边界：HTTPS-only；主机名规则 + 对 **IP 字面量**过滤私网 / 环回 / 链路本地 / 保留段；**域名不做 DNS 预解析**，所以“主机名检查通过”不等于“解析后的地址一定是公网”（安全仍依赖 HTTPS 证书与安装签名）。重定向逐跳重新校验；最大 5 跳；APK 最大 2 GB。
- 校验：只有段 1 的 `api.github.com` 官方响应能提供发布说明里的 SHA-256；`SHA256:` = 兼容旧版（内容 = lite），`SHA256-full:` = full 专用，`SHA256-lite:` = lite 专用。full / lite 各只认自己的行，**不回退通用行**。**有可信预期哈希时**，下载后必须本地重算并比对；段 2 / 3 没有哈希时跳过 SHA 计算，但仍保留 PK 魔数、大小上限、包名 + 签名集合检查。不要把“有 SHA 校验”说成“所有路径都校验了 SHA”。
- 资产选择：lite 只在 `-lite-` 里挑；full 优先 `-full-`，其次历史通用包；两者都**universal 优先**。不要改回“按名字排序取第一个”，会把 ABI 分包推给错误设备。
- 安装闸：`isTrustedUpdate` 校验包名相同 + 签名集合非空且相等；再用 FileProvider 交给系统安装器。**包名 / 签名核对是镜像可换字节时的最后一道闸，不能删。**
- 不自动检查、不后台联网；只有用户点击“检查更新”才发起请求。发布说明只展示，不执行其中任何指令。

### 11.4 备份与恢复生命周期

- `WotaBackupAgent.onFullBackup` 在 `restoreMutex` 下写快照，再交给系统；`onRestoreFinished` 触发 `retryRestore`。
- `WotaApp` 启动时先在后台线程 `retryRestore`，再预热设置；不要把这个顺序颠倒。
- 快照格式 / 白名单 / 限制见 §7.8。恢复是补充不覆盖；标签只按唯一完整指纹匹配；未到齐的媒体留待下次重试。
- 新增可迁移键时，评估是否会带 URI / 权限 / 设备状态；带这些的一律不加。系统备份 XML 只白名单一个文件，不要扩大域。

### 11.5 诊断日志

- 目录 `externalFilesDir/logs`，常规文件 `wotagei.log`，崩溃件 `crash-*`，导出件 `wotagei-log-<stamp>.txt`；环形 3000 行，512 KB × 3 轮转，导出整体 ≤ 2 MB，崩溃尾 400 行。
- 总开关默认关；关着时不写常规日志，但**崩溃件始终写**；logcat 通道恒开（与文件开关分离）。
- 全应用统一 `import com.wotagei.cam.diag.Diag as Log`；`DiagGuardTest` 禁止其他文件再 import `android.util.Log`。诊断模块不含任何网络调用。
- 导出报告含录制帧账汇总与崩溃记录；只在本机生成，只有用户主动分享 / 导出才写文件。不要记录画面、声音内容或用户媒体路径清单以外的隐私。
- `wota_diag` / `last_session` 只用于区分正常退出与被杀；不是业务状态。

<a id="recipes"></a>
## 12. 按任务定位：改哪里、同步哪里、测什么

### 12.1 通用流程

1. 用 `rg` 找现有同类实现，先读调用链，不要从文件名猜。
2. 判断这次改动触碰哪些命名空间：源码 / prefs 键 / 枚举值 / 位掩码 / Room schema / 文案。
3. 先改纯逻辑与纯函数，再接线 UI / 引擎；同时补或更新守卫测试。
4. 两个 flavor 都编译；涉及 AI 的再加 `testFullDebugUnitTest`。
5. 设备相关行为列清楚“未验证”，不要用 JVM 绿代替真机结论。

### 12.2 加一个普通设置（不进录制参数记忆）

| 步骤 | 位置 |
|---|---|
| 1. 加键常量与字符串 | `ui/SettingsScreen.kt` 的 `WotaSettings` |
| 2. 加读取函数（坏值回默认） | 同文件 |
| 3. 加写入函数（低频 / 需强杀保留用 `commit()`，否则 `apply()`） | 同文件 |
| 4. 设置页 UI | `SettingsScreen` 对应分组 |
| 5. 消费方 | 页面 / 引擎；不要在组合期反复读盘 |
| 6. 备份决策 | 能迁移则加 `PortableUserData.SETTING_KEYS` + 类型集合 |
| 7. 测试 | `SettingsDefaultsTest` / 对应功能测试 |

### 12.3 加一个录制参数并持久化

| 步骤 | 位置 |
|---|---|
| 1. 参数总线字段 | `core/WotaParams.kt` |
| 2. UI 写入点 | `ui/CameraScreen.kt` / 参数面板 |
| 3. 引擎消费 | `camera/RequestApplier.kt` / `Camera2Engine.applyParams` |
| 4. 能力钳制 | `core/CameraAbility.kt` / `WotaParams.applyAbility` |
| 5. 键 + 恢复 | `WotaSettings` 键、`ParamsPersist.KEYS`、`ParamsPersist.restore` |
| 6. 会话影子 | `ParamsPersistSession` 字段、`flush` 读取 |
| 7. 守卫 | `ParamsPersistWiringGuardTest`（34 → 35 要同步）、`ParamsPersistSessionTest` |
| 8. 备份决策 | 可迁移才加，自动降级 / 瞬时态不加 |

### 12.4 加一个相机控制（曝光 / 对焦 / 闪光等）

1. `WotaParams` 加状态；
2. `RequestApplier.apply(...)` 组请求；
3. `Camera2Engine.applyParams` 接线，必要时 `applyRepeatingNow`；
4. 能力门控与 UI 灰显同源；
5. 若要持久化，走 12.3；
6. 测试：`RequestApplierTest`、`CameraAbilityFpsTest`、对应 `*GuardTest`；真机核对“UI 值 = 实际下发值”。

### 12.5 加一颗胶囊

按 §7.4 的 8 步执行。额外注意：

- `CamPill` 的 **ordinal 是 `P<n>` 持久化 id**，只能尾部追加；
- `hud_pills` 的 bit 用下一个 2 的幂，同步 `MASK_SCHEMA`；
- `pillAnchorWriters` 必须同轮补行，锚点形参无默认值；
- `RECORDING_EDITABLE_PILLS` 决定录制中是否可调；
- 默认落位在 `HudEntry.homeZone` 与 `HudLayoutTable.defaultOrderOf` 两处；
- 测试：`CamPillTest`、`PillAnchorRegistryTest`、`PillEnabledGuardTest`、`HudLayoutCodecTest`、`HudLayoutGridTest`。

### 12.6 加一个 scene 效果（进成片）

1. `core/` 建参数模型（含 `sanitize()`）与复合串编解码；
2. 若要开关级切换，进 `ProgramKey`；形状参数只走 uniform；
3. `GlRenderEngine` 加 setter / uniform / pass / 纹理单元；决定在 `drawFrame` 的哪一步；
4. DISPLAY 与 MOVIE 两槽都应用；`movieProgramKey` 不放 effect；
5. `DirectSink` 对应 `isXxxAvailable()` 返回 false，UI 如实提示；
6. 离线后渲染若支持，改 `SceneExportPlan` / `SceneExportRunner` / `SceneExportGl`；不支持就明确禁用；
7. 持久化走复合键，不摊成多个 prefs；
8. 测试：`ProgramKeyTest`、shader / wiring guard、`SceneExportPlanTest`；真机看预览与成片一致。

### 12.7 加一个 monitor 效果（只上屏）

1. 只进 `displayProgramKey`；`movieProgramKey` 不应有 effect 形参；
2. `effect != NONE` 时显示槽 denoise 强制 0；
3. 编码支路不引用该效果纹理 / uniform；
4. `MonitorEffectEncoderGuardTest` / `StateOnlyEncoderGuardTest` 必须绿；
5. 真机录一段，回放确认成片没有叠加。

### 12.8 加一条导出效果

1. 纯决策层 `SceneExportPlan`（读什么参数、是否 active）；
2. 管线 `SceneExportRunner` / `SceneExportGl`（EGL、program、纹理、PTS）；
3. UI 只在弹层内配置，本次生效，不反写设置；
4. 曲线刻意不加（已烘进源片）；
5. 方向 / 音轨 / PTS / 取消 / commit 语义保持与现有一致；
6. 测试：`SceneExportPlanTest`、`SceneExportWiringGuardTest`、`ExportMuxMonotonicGuardTest`、`ExportCommitKeepGuardTest`。

### 12.9 加文案 / 改主题

- 文案：加到对应 `strings_*.xml`，不写 Kotlin 中文字面量；验证 80% / 120% 文本缩放与横竖屏不裁字。
- 颜色 / 字阶：先改 `ui/design/Tokens.kt`；跑 `contrast-audit.py`；正文 ≥4.5、非正文 / 图形 ≥3.0；不要绕过令牌写死颜色。
- 动效：只加 `MotionSpec` 具名规格；不要在调用点直调 `tween` / `animate*AsState`；跑 `MotionHygieneTest`。

### 12.10 改包名 / Fork 身份

见 §13.1 的同步表。核心原则：`applicationId` 决定“是不是另一个应用”，`namespace` 决定 Kotlin 包与 R 类；FileProvider authority 必须全局唯一。只改 `applicationId` 而不改 authority，两个应用同时安装会 provider 冲突。

### 12.11 改更新仓库

1. 替换 `UpdateConfig` 三处 URL；
2. 替换 `RedirectProbe.guessUniversalAssetName` 的项目名 / 资产名规则（若资产命名变了）；
3. 确认新仓库 Release 说明里有 `SHA256:` / `SHA256-full:` / `SHA256-lite:` 行，且哈希对应 universal 包；
4. 若改 CI，保持“每 flavor 四件套 + compat 一枚”的命名与数量校验，否则更新器挑不到包；
5. 跑 `UpdateLogicTest` / `UpdateNetworkGuardTest` / `UpdateSha256Test` / `UpdateInstallerTrustTest`；
6. 真机至少验一次“检查更新 → 下载 → 校验 → 安装器拉起”。

### 12.12 改 Room schema

1. 改实体 / DAO；
2. `version + 1`；
3. 写 `Migration` 并注册；**不要依赖 `fallbackToDestructiveMigration`**；
4. 更新 `app/schemas/.../<version>.json`（构建时导出）；
5. 测试迁移：旧库 → 新库，标签 / 回收站 / 系列不丢；
6. 确认 `MediaTagDao` / `SeriesDao` 的查询列与新 schema 一致。

### 12.13 换 AI 模型

按 §11.2：资产 + `pack.meta.json`（SHA）+ 输入布局实读 + 输出解析 + 阈值 / 特征 + 校验常量 + `testFull` 全套。不要只替换 `.tflite` 不改 meta；校验会拒绝，且用户包可能回退内置。

### 12.14 改媒体目录 / 命名

这会同时影响：媒体库发现（`WotaGeiCam` 关键字）、FileProvider 白名单（`Movies/WotaGeiCam/`）、备份标签指纹（相对路径）、分享路径、sidecar 归位。除非同时写迁移，否则不要改根目录。**新增子目录可以**：只要仍在 `Movies/WotaGeiCam/` 根下，FileProvider 的 `external-path` 已覆盖，不需要新增 `file_paths.xml` 项；但仍要检查媒体库过滤与备份指纹是否识别。只有**改变根目录**才需要同步 `file_paths.xml`、媒体库关键字与备份指纹。

### 12.15 改录制引擎选择

`Recorders.useCodecEngine` 是产品语义闸门，不是性能开关。改它之前先确认：

- MediaRecorder 能否承载新需求（多音轨 / 拒帧 / 降噪 / >60fps）；
- `RecordProfile` 字段是否两边都支持；
- 换段 / Surface 交接 / 健康检查是否仍成立；
- `AudioTrackPlanTest`、`DualTrackGuardTest`、`CaptureTrackLostGuardTest`、`MrRotateInstanceGuardTest` 等是否仍绿。

<a id="fork"></a>
## 13. Fork 身份、发布与提交前清单

### 13.1 改身份时必须同步的位置

| 位置 | 当前值 | 说明 |
|---|---|---|
| `app/build.gradle.kts` `namespace` | `com.wotagei.cam` | Kotlin 包 / R 类；改它要移动源码目录与全部 import |
| `app/build.gradle.kts` `applicationId` | `com.wotagei.cam` | 安装身份；与 namespace 可以不同，但建议一致 |
| `AndroidManifest.xml` provider authority | `com.wotagei.cam.provider` | 必须全局唯一 |
| `update/UpdateInstaller.kt` `AUTHORITY` | `com.wotagei.cam.provider` | 与 Manifest 同步 |
| `media/Share.kt` `AUTHORITY` | `com.wotagei.cam.provider` | 与 Manifest 同步 |
| `ui/SettingsScreen.kt` 的 `FileProvider.getUriForFile(...)` | 字面量同上 | 漏改会在导出日志 / 分享时崩 |
| `baselineprofile/build.gradle.kts` `namespace` | `com.wotagei.cam.bp` | 测试模块 |
| `baselineprofile/src/main/AndroidManifest.xml` `targetPackage` | `com.wotagei.cam` | 仪器测试目标 |
| `bp/HotPathsProfileTest.kt` `PACKAGE` | `com.wotagei.cam` | 基准 profile 采集目标 |
| `UpdateConfig` 三处 URL | `himetuki/WotaGeiCam` | 更新源 |
| `RedirectProbe` 资产名 | `WotaGeiCam-...` | 与 CI 产物命名绑定 |
| `appVersionBase` / `appVersionCode` | `0.0.12` / `12` | 版本单一来源 |
| `scripts/verify-on-device.sh` 的 `PKG` | `com.wotagei.cam` | 设备取证脚本；漏改会操作原版应用 |
| `test/.../source/KotlinSourceScan.kt` 的 `MAIN_SRC` | `src/main/java/com/wotagei/cam` | 源码扫描根；改包名后守卫会找不到源码 |
| `test/.../camera/ArcMendGlBoundaryFixture.kt` 的反射类名 | `com.wotagei.cam.camera.ArcMendPass` | 测试按 FQN 反射加载生产类 |
| 签名配置 | 本机 / Actions Secrets | Fork 不能使用原作者证书；换了签名就不能覆盖安装原版 |

> 改包名 / 命名空间前，先在仓库根跑 `rg -n 'com\.wotagei\.cam' Android/`，把 Gradle、Manifest、`scripts/`、`test/`、`androidTest/`、`baselineprofile/` 的命中逐条判断；不要只改 `build.gradle.kts` 与 Manifest。`Android/README.md`、根 README 与发布说明里的展示名可以保留或另改，但**运行时身份**必须与安装包一致。

> 只改 `applicationId` 而保留 `namespace` 是可行的最小改名方式（源码不用动），但 provider authority 仍必须改，否则与原版冲突。若同时改 namespace，记得移动 `com/wotagei/cam` 目录并全局替换 import；`Android/AGENTS.md` 里的旧路径不要当依据。

### 13.2 版本与发布命名

- `versionCode` 不带 r 号；同一大版本内各检查点可覆盖安装。`versionName = appVersionBase + "-rXX"`。
- CI 产物名：`WotaGeiCam-v<version>-<flavor>[-<abi>]-release-Android.apk`；compat = lite universal 的副本，名字字母序最前。
- 更新器按资产名挑包（universal 优先、flavor 分组）；改产物命名规则必须同步改更新器与 CI 校验，否则用户收到“检测到新版本但下不到包”。
- `.github/release-notes/<tag>.md` 是发布说明入口；CI 会回填三行 SHA。手写说明里不要自己写错哈希。

### 13.3 许可与致谢

- 仓库以 **GPL-3.0-only** 发布。分发修改版时必须提供对应源码并保留许可与版权声明；不要删 `LICENSE`、版权头或 README 的致谢。以上是项目约定，不构成法律意见；商业 / 合规用途请自行确认。
- 残影（Echo）的算法思路与双模式语义参考自 B 站“蒙德·究史尼”的「光弧倍增器」，README 已有致谢。**未复用其源代码**；Fork 若继续使用该功能，请保留致谢，不要改成“原创”。
- 不要用原作者名义发布 Fork，也不要把 Fork 的签名包说成官方包。

### 13.4 数据兼容

- 同 `applicationId` + 同签名 → 可覆盖升级，数据保留；不同签名 → 必须先卸载，会清 `wota_settings` / `wota_media.db`。
- 改 `applicationId` → 系统视为新应用，旧数据不会自动迁移；需要自行做导出 / 导入（现有备份文件只迁移白名单设置与标签指纹）。
- 改 Room schema 不写 Migration → 标签 / 收藏 / 回收站 / 系列被清空，这是用户数据的硬红线。
- 改 prefs 键 / 枚举值 / 位掩码 / 复合串格式 → 老用户配置静默失效；必须加迁移或保留旧键读取。

### 13.5 提交前清单

- [ ] `git status` 只包含本次范围内的文件；没有 iOS / HarmonyOS / 私密文档 / 构建产物混入。
- [ ] 没有 `.env`、`keystore.properties`、`*.jks`、`local.properties`、签名材料、绝对路径、设备序列号、用户媒体路径。
- [ ] 两个 flavor 都能编译：`assembleFullDebug` + `assembleLiteDebug`。
- [ ] 相关 JVM 测试通过；报告实际任务名、测试数、失败数，未跑就说未跑。
- [ ] 涉及 AI 的改动跑了 `testFullDebugUnitTest`；涉及共用逻辑的跑了 lite。
- [ ] Lint 已跑或明确未跑；新增警告不隐藏。
- [ ] 新增 / 改动键名、掩码、schema、复合串时，守卫测试同步更新。
- [ ] 文案在 80% / 120% 文本缩放、横竖屏、两种横屏方向下不裁字。
- [ ] 相机 / 录制 / 导出改动列明真机验证项；未验不写成“已修复”。
- [ ] 推送 / 打 tag / 发 Release 前单独取得维护者授权（本文不构成授权）。

<a id="sources"></a>
## 14. 源码索引与维护

### 14.1 高频文件索引

| 任务 | 先读 |
|---|---|
| 应用启动 / 诊断安装 | `WotaApp.kt` |
| 页面 / 导航 / 返回 / 看门狗 | `ui/MainActivity.kt`、`media/GalleryScreen.kt`（`WotaNav`） |
| 录制页 UI / 录制控制 | `ui/CameraScreen.kt`（含 `RecordRunner`） |
| 胶囊 / 锚点 / 录制中可调 | `ui/CameraPills.kt` |
| 参数记忆 | `ui/ParamsPersist.kt` |
| 设置键 / 默认档 / HUD | `ui/SettingsScreen.kt`（`WotaSettings`） |
| HUD 布局 | `ui/HudLayout.kt` |
| 参数总线 / 能力 | `core/WotaParams.kt`、`core/CameraAbility.kt` |
| 镜头枚举 | `core/LensSlot.kt` |
| 请求组装 | `camera/RequestApplier.kt` |
| Camera2 引擎 | `camera/Camera2Engine.kt` |
| GL 引擎 / program / pass | `camera/GlRenderEngine.kt`、`camera/ProgramKey.kt` |
| 预览方向 | `camera/PreviewOrientation.kt` |
| DIRECT 上屏 | `camera/DirectSink.kt` |
| 录制接口 / 引擎选择 | `record/Recorder.kt`、`record/RecordProfile.kt` |
| MediaRecorder / Codec 引擎 | `record/MrRecorder.kt`、`record/CodecRecorder.kt` |
| 音频抽取 / 内录 | `record/AudioFeeder.kt`、`record/PlaybackCaptureController.kt`、`record/CaptureFgService.kt` |
| 分段 / 帧账 / 健康 | `record/RecordHealth.kt`、`record/FrameLedger.kt` |
| 媒体库 / 标签 / 删除 | `media/MediaRepo.kt`、`media/MediaActions.kt`、`media/WotaDb.kt` |
| 文件写入 / pending | `record/VideoStore.kt`、`record/PendingName.kt`、`record/StalePendingSweep.kt` |
| 播放器 | `player/PlayerEngine.kt`、`player/PlayerScreen.kt` |
| 无损剪辑 | `player/ClipExport.kt` |
| 后渲染导出 | `player/SceneExportPlan.kt`、`player/SceneExportRunner.kt`、`player/SceneExportGl.kt` |
| 光弧修复 | `record/ArcRepairRunner.kt`、`record/ArcRepairGl.kt`、`record/ArcRepairPlan.kt` |
| 对比 / 音频对齐 | `player/CompareTimeline.kt`、`player/CompareAudio.kt`、`player/AudioSync.kt`、`player/TrackSync.kt` |
| AI 站位（full） | `src/full/.../stance/StanceFeature.kt`、`StanceController.kt`、`StanceAnalysis.kt`、`StanceModelPack.kt` |
| AI 导流（lite） | `src/lite/.../stance/StanceFeature.kt` |
| 更新 | `update/UpdateChecker.kt`、`UpdateDownloader.kt`、`UpdateInstaller.kt` |
| 备份 | `backup/PortableUserData.kt`、`backup/WotaBackupAgent.kt` |
| 诊断 | `diag/Diag.kt` |
| 设计令牌 / 动效 | `ui/design/Tokens.kt`、`ui/anim/Motion.kt`、`ui/design/WotaSwitch.kt` |

### 14.2 测试索引（按主题）

- 参数 / 键表：`SettingsDefaultsTest`、`ParamsPersistSessionTest`、`ParamsPersistWiringGuardTest`、`ParamSliderSnapTest`、`SliderSnapTest`、`SliderLadderTest`。
- HUD / 胶囊：`CamPillTest`、`PillAnchorRegistryTest`、`PillAnchorTest`、`PillEnabledGuardTest`、`HudLayout*Test`、`MarkerLinesTest`、`MarkerWiringGuardTest`。
- 相机 / 曝光 / 方向：`RequestApplierTest`、`CameraAbilityFpsTest`、`ForcedShutterTest`、`ShutterRangeTest`、`ExposureCorrection*Test`、`PreviewOrientationTest`、`OrientationExportTest`、`RemuxOrientationGuardTest`、`ProgramKeyTest`、`EglConfigCompatibilityTest`。
- 渲染 / 效果：`Glow*Test`、`Echo*Test`、`Shaders*Test`、`DenoiseWiringGuardTest`、`MonitorEffectEncoderGuardTest`、`StateOnlyEncoderGuardTest`、`Frost*Test`。
- 录制 / 音频：`RecorderCompatibilityBridgeTest`、`CaptureStateMachineTest`、`AudioTrackPlanTest`、`DualTrackGuardTest`、`CaptureTrackLostGuardTest`、`RecordHealthTest`、`FrameLedgerTest`、`MuxPtsMonotonicTest`、`SegmentFailureCleanupGuardTest`。
- 媒体 / 导出：`ClipExportTest`、`ExportMuxMonotonicGuardTest`、`ExportCommitKeepGuardTest`、`SceneExportPlanTest`、`SceneExportWiringGuardTest`、`ArcRepair*Test`、`MediaSidecarGuardTest`、`PendingNameTest`、`MediaAccessTest`。
- 对比 / 播放：`CompareTimelineTest`、`CompareAudioTest`、`ComparePlaybackGuardTest`、`TrackSyncTest`、`AudioSyncTest`、`FrameStepTest`。
- AI（testFull）：`StanceDetectorTest`、`StanceEngineTest`、`StanceInputLayoutTest`、`StanceModelPackSecurityTest`、`StancePackImportTest`、`StanceJsonBoundaryTest`、`StanceReaderCameraBridgeTest`。
- 更新 / 备份 / 诊断：`UpdateLogicTest`、`UpdateNetworkGuardTest`、`UpdateSha256Test`、`UpdateInstallerTrustTest`、`backup/*Test`、`diag/*Test`。

### 14.3 证据等级与报告写法

建议每次交付按四档标注：

1. **源码已核对**：给出 `文件:符号`，不贴大段源码。
2. **JVM 已验证**：任务名 + 测试数 + 失败数；说明是否 `--rerun-tasks`。
3. **设备已验证**：机型 / Android 版本 / 方向 / 前后面 / 是否带音轨 / 录了多久。
4. **未验证 / 推断**：明确写出，不混进“已完成”。

不要用“应该”“理论上”掩盖未验证；不要把历史版本的测试数、APK 大小、哈希抄到本轮结论里。

### 14.4 本文维护规则

- 新增 / 重命名持久化键、掩码位、复合串字段、Room 实体、路由、导出目录、更新资产规则时，**同一个改动里更新本文对应章节**。
- 源码注释里的 `docs/plan/NN`、历史任务编号、旧 SDK / 依赖版本 / 键数量只作背景；以当前源码与测试为准。
- 本文不承诺“最新版本号 / 最新发布说明”；版本以 `app/build.gradle.kts` 与 tag 为准。
- 本文面向公开 Fork；不要写入作者本机路径、私密文档内容、签名信息或设备序列号。

---

### 14.5 故障模式速查（症状 → 先查哪里）

| 症状 | 先查 |
|---|---|
| 参数浮层固定在左上角 / 点开错位 | `pillAnchorWriters` 是否漏登记；锚点形参是否被加了默认值 |
| 某根滑杆整根灰掉、拖不动 | ladder 是否为空或只有 1 点（`PillSlider` 会判禁用，不会静默变连续） |
| 设置页改完回录制页没生效 | 该键是否被 `applyDefaultsOnce` 的“进程内只一次”挡住；纯 UI 设置应每次直读 prefs |
| 自动纠偏开关改了又改回，重启后值不对 | `ParamsPersistSession.flush` 成功块漏更新 `shCorrectionAuto` / `shCorrectionManualNs`（已知缺口，见 §7.5） |
| 预览有效果、成片没有 | 该效果是否只进了 DISPLAY 键；`movieProgramKey` 是否被绕过 |
| 成片里出现斑马纹 / 峰值 | 编码支路是否引用了 effect；跑 `MonitorEffectEncoderGuardTest` / `StateOnlyEncoderGuardTest` |
| DIRECT 下效果消失 | 预期行为；检查 UI 是否如实提示、`isXxxAvailable()` 是否返回 false、降噪是否退回 HAL |
| 成片方向反 / 差 180° | 送解码器的 format 是否剥 `KEY_ROTATION`；muxer hint 是否又写了一次；前摄镜像是否重复 |
| 手动白平衡发绿 / 滑杆无效 | HAL 是否声明并采纳 `TRANSFORM_MATRIX`；覆盖期保活与 `awbState` 早退链是否被删 |
| 一录就掉到个位数帧率 | 帧周期是否用了 fps 区间下界；是否有写入点绕过 `setFps` |
| 换段瞬间停顿 / 换段失败 | 85% 预提示是否出现；Surface 交接是否在 `markSegmentRotating` 窗口内；重试是否回收了旧资源 |
| 导出有进度但媒体库没有文件 | commit 与 mux 失败是否被混为一种；pending 行是否保留；冷启救援是否执行 |
| 媒体库突然空列表 | 删除收尾是否被取消（`runDeleteFinish` 必须 `NonCancellable`）；Room 是否发生了破坏性迁移 |
| 更新器提示有新版本但下错包 | 资产是否 universal 优先；flavor 过滤是否正确；是否生成了 `-full-...-full-` 双段名 |
| 离线 Gradle 报 AGP 找不到 | 本机缓存不完整；去掉 `--offline`，先联网解析一次 |

**文档结束。** 如果只记一件事：**先确认命名空间与 owner，再改代码；键名、掩码、枚举值、Room schema 的兼容性优先于代码整洁。**
