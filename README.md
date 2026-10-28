# WotaGeiCam

[![Build Release APKs](https://github.com/himetuki/WotaGeiCam/actions/workflows/build-apk.yml/badge.svg)](https://github.com/himetuki/WotaGeiCam/actions/workflows/build-apk.yml)
[![Build Release HAPs](https://github.com/himetuki/WotaGeiCam/actions/workflows/build-hap.yml/badge.svg)](https://github.com/himetuki/WotaGeiCam/actions/workflows/build-hap.yml)

**[English](README.en.md)**

一个面向 WOTA 艺 / 演出现场拍摄的个人开源相机项目：手动曝光与对焦控制、实时 RGB 曲线调色、可自定义的取景辅助，以及一套围绕光弧拍摄的工具链——强制低速快门、抽帧补弧与离线光弧修复、环境音 + 内录的双音轨录制、双轨对比播放。

这是个人开发者的业余项目，不是成熟产品：长期验证的机型屈指可数，厂商相机 HAL 的差异远比一个人能摸完的多，已知的问题面如实列在下方[「已知问题与限制」](#已知问题与限制)。如果你遇到奇怪的行为，欢迎开 issue 告诉我机型与现象（附诊断日志更好）——你的反馈是这个项目前进的主要方式；也请对缺陷与未打磨之处多多包涵。

本仓库是单树 monorepo：`Android/WotaGeiCamApp/` 是 Android 原版工程（产品的功能定义），`HarmonyOS/WotaGeiCamOHOS/` 是同一产品的 HarmonyOS 移植工程。

> 项目处于早期开发阶段，功能与界面迭代较快，最新版本以 [Releases](https://github.com/himetuki/WotaGeiCam/releases/latest) 页为准。

## 功能特性

以下功能清单以 Android 版为底本（这是产品的功能定义）；HarmonyOS 版为同产品的移植，语义逐项对齐进行中——移植对照与进度见 [`HarmonyOS/WotaGeiCamOHOS/docs/PORT_MAP.md`](HarmonyOS/WotaGeiCamOHOS/docs/PORT_MAP.md)，安卓侧的新特性会按批移植过去。

### 拍摄控制
- 手动曝光：ISO / 快门 / EV；快门支持强制 1/24、1/25 低速档（长曝光保证光弧连贯），并按当前帧周期校验哪些档位可选
- 帧率控制（含非精确档的抽帧转换）、白平衡色温、手动对焦与变焦
- 实时 RGB 曲线调整：编辑弹窗可拖右缘宽高等比放大（默认尺寸为最小值，只增不减；节点与线宽随画布同步放大，方便手指大的人调节），扇面「重置」一键回恒等并把弹窗尺寸复位到默认最小
- 取景手势：轻触画面对焦并测光；双击在该点对焦并锁定；锁定期间轻触画面不重新对焦（首次给出提示），长按画面解锁并在该点重新对焦；自动曝光档下长按半秒锁定 / 解除曝光（AE）；对焦面板内也有「锁定 / 解除锁定」条目
- 录制页参数持久化：录制页上改过的参数（感光度、快门、帧率、白平衡、变焦、分辨率等）会被记住，重启后不回默认值；滑杆松手即保存
- 刻度滑杆：滑杆按设备能力分档运行，读数仍显示真实值；设置页「滑杆吸附」可逐个参数改成连续值（例如变焦调到 1.2×、1.7×）
- 参数胶囊点按或长按都会打开就近的调节面板；录制中只有感光度、曝光补偿、白平衡、变焦、对焦可调，其余只读
- 相机参数一律从设备能力运行时读取，按机型自适应，不写死机型数值

### 取景辅助
- 斑马纹与峰值对焦（可调阈值 / 密度 / 强度 / 颜色；不使用 GPU 渲染时这两项不生效）
- 光晕（Glow）实时合成：亮部提取 + 扩散，跟随画面高光的原色，或在色盘里自选单色；上屏监看与成片都带
- 参考线 / 网格：三分法、对角线、十字准线、广播安全框、安全区域、网格、中心标记、水平线、上 / 中 / 下红线，可多选
- 水平仪与俯仰仪（可开关，回正瞬间轻震提示）
- 音量表与常驻读数块（快门 / 帧率 / 码率 / 感光度 / EV / 白平衡 / 变焦），可逐项显隐
- 控件位置自定义：编辑页内拖拽布局，Dock 与读数块可自由摆放，就近弹出的调节面板跟随控件锚点

### 录制
- 双录制引擎，码率可调
- 帧率如实提示：实际到帧率由相机 HAL 决定、App 只能协商不能保证——实际帧率持续低于所选档约七成时顶栏提示「画面会卡」及当前实测值；帧率档是本机近似档（※）且开了手动曝光时，取景态就预先告知暗光下可能低于所选值（[已知问题](#已知问题与限制)有详细说明）
- 白平衡如实降级：手动色温增益在未声明对应能力的机型上不被 HAL 采纳时，自动改走画面层算法并在运行时提示
- 内录音轨（可选）：除麦克风环境音外，可同时采集应用内 / 系统正在播放的音频，成片为环境音 + 内录双音轨；前台服务常驻、通知栏一键停止；高版本系统首次开启时申请通知权限
- 音源与蓝牙：音源面板可切环境音 / 内录，并提供外接蓝牙音箱的媒体音量、播放控制与电量读数（配对与连接在系统蓝牙设置完成）；「音频」控件常驻一圈淡蓝描边，提示可点开该面板，内录进行中描边转为主色
- 音源不跨启动记忆：冷启动默认环境音，避免每次启动都申请采集授权
- 自动分段轮转：单文件到达 3.5 GiB（FAT32 安全上限）自动换段，段名连续、内容不丢；字节接近上限（85%）时顶栏预提示「即将自动换段」；平台因文件达上限自行停录时，已写完的完整段照常保留
- 分屏对照：主画面照常录制，右半屏可加载参考视频实时对照拍摄（分屏开关在录制中锁定，需开录前打开）
- 起录缓冲：录制开始瞬间（编码器 / muxer 尚未就绪时）已编码的视频样本按到达顺序补写，不直接丢弃
- 录制帧账：相机 → 渲染 → 编码器 → muxer 逐级帧数计数，每段末尾输出一行汇总，可在诊断日志导出中查看，便于定位掉帧环节
- 换段重建失败时先回收本轮已建的编码器 / 封装器资源再重试，避免后续无法开始录制

### 光弧工具链
- 录制期帧率转换：设备没有 24 / 25 帧率原生档时，录制自动抽帧到所选帧率——「自动抽帧补弧」把被抽帧的画面并入前后帧保持光弧连贯，「仅抽帧」保留纯抽帧结果；被抽帧位次记录在成片同名的 `.drops.json` 里
- 抽帧相位按槽宽 10% 的容差对齐时间戳，吸收抖动，长时间统计帧数不变
- 补弧合并：整像素选择 + 差分死区，录制与离线修复、CPU 与 GPU 共用同一判据；「并入门槛」滑杆（默认 12，范围 0–32）在录制页帧率弹层、播放器光弧修复弹层、设置页默认值三处可调，调大更保守、调小会把噪点也并进去
- 离线光弧修复：对存量素材做抽帧 + 补弧（与帧率弹层同款语义），GPU 加速与纯 CPU 双路可选，结果存为新文件、不覆盖原片；失败的半成品会被回收，不写入媒体库

### 播放与整理
- 内置媒体库：分类标签、收藏、回收站、重命名、分享，长按多选批量操作
- 播放器：AB 循环、逐帧步进、倍速（0.1x–1x）、双指缩放平移、水平镜像
- 剪辑导出：按 A/B 段导出，音轨可选（默认环境音，双音轨素材可切内录；单音轨素材只有环境音），只写选中的那一条；选中音轨在导出区间没有样本时按纯视频导出。导出方式二选一：**无损**（不重编码，画面与你录到的逐字一致）或**后渲染**（重编码，可叠加双边降噪与光晕；重编码的画质损耗与降噪幅度随素材和机型而异，没有高噪素材上的量化结论）；无效果可选时后渲染自动置灰
- 双轨对比播放：一条时间线 + 双视频轨（某侧无素材时该侧黑屏），音频可在左右两侧、环境音 / 内录轨之间切换，同一时刻只有一侧一条音轨出声，可按音频自动对齐；保留对比历史
- 冷启动自动维护：清理历史遗留的临时命名残留、保持媒体库索引与本机文件一致；档案归位前核对目标名，不覆盖已有文件

## 已知问题与限制

按"如实说"的口径列出当前已知的问题面（核对至 v0.0.11-r20；这条清单会随反馈更新）：

- **实际帧率由相机 HAL 决定，App 只能协商、不能保证**：帧率档位决议只保证"下发的值是设备声明过的"；暗光、慢快门或厂商策略都可能让实际到帧率低于所选档。App 在实际帧率持续低于目标约七成时会顶栏提示实测值，但**不强行干预**——强行锁档在部分机型会导致取景器假死（这是修过的另一个缺陷的教训）。部分厂商 HAL 还会在请求固定档之外自行放宽范围：实测某华为机型请求 `[24,24]`、结果侧变成 `[15,24]`。
- **旧版本（≤ v0.0.10-r03）在"宽帧率区间"机型上有严重掉帧缺陷**：手动曝光时帧周期被按区间下界下发，把实际帧率物理锁死在低档（某 vivo 机型实测 4.98fps 装进 25fps 容器；"调低分辨率/码率也躲不开"，因为它与编码负载无关）。已在 v0.0.11-r01 修复——在旧版上遇到"一录就掉帧 / 一放歌（正式演出场景）就掉帧"，请先升级再观察。
- **手动白平衡的机型依赖**：色温增益需要设备声明 `TRANSFORM_MATRIX` 能力；未声明的机型自动改走画面层算法兜底（运行时会提示），效果因机型而异，且该兜底路径尚无"支持机型"上的闭环标定数据。
- **高码率长录会换段**：单段上限 3.5GiB（FAT32 安全值），50Mbps 档约 9 分钟即到——10 分钟以上的录制**必然**自动换段一次（字节到 85% 时顶栏预提示）。换段窗口有界、受阻会明确失败并记账，但那一刻的短促停顿是可预期的。
- **取景辅助依赖 GPU 渲染路径**：不使用 GPU（DIRECT）时斑马纹与峰值对焦不生效（应用内也有提示）。
- **HarmonyOS 版的安装门槛**：unsigned 包仅供结构验证，装不上零售机；签名 profile 必须由 AGC/DevEco 签发且包含目标设备 UDID。
- **测试覆盖有限**：长期真机验证的机型只有 WIKO GAR-AN60、华为 ALN-AL00 / ADY-AL00、vivo V2505A 和一台模拟器；低端机与小众 ROM 基本没有覆盖，能力协商代码在那上面大概率还有没见过的行为。遇到了请按下文导出诊断日志发给我，就是帮大忙。


### 更新
- 应用内「检查更新 / 下载更新」：**只在你主动点击时联网**，读取 GitHub Releases 的最新版本并下载安装包（直连失败时按 HTTPS 镜像链回退），不自动检查、不自动下载、不后台联网
- 每次下载前先给直连与各镜像**测速**（每路只采样 256KiB），按实测速度挑最快的通道，下载失败再按速度依次回退；测速全部失败时回到「直连优先 + 镜像表序」的老顺序
- 发现新版本时，弹窗里会显示该版本的更新说明（GitHub Release 的说明原文，去掉 Markdown 标记；直连与镜像读取的说明都会显示，它只用于展示、不参与安装判断）
- 下载地址一律用仓库常量与校验过的资产名重建并强制 HTTPS，编码形态的 IP 字面量与指向内网 / 环回的目标一律拒绝；SHA-256 只从 GitHub 官方接口读取，下载完成后本地比对，不匹配即丢弃；安装前核对包名与签名与已装应用一致，不一致不交给系统安装器

## 下载

到 [Releases](https://github.com/himetuki/WotaGeiCam/releases/latest) 下载。打一个 `vX.X.X` 或 `vX.X.X-rYY` 标签即同时产出两端安装包，资产进同一个 Release，按扩展名前的平台后缀（`-Android` / `-Harmony`）区分：

| 文件 | 平台 / 适配 |
|---|---|
| `WotaGeiCam-vX.X.X-rYY-release-Android.apk` / `WotaGeiCam-vX.X.X-rYY-universal-release-Android.apk` | Android：全设备通用（拿不准选 universal） |
| `WotaGeiCam-vX.X.X-rYY-arm64-v8a-release-Android.apk` | Android：64 位 ARM（2016 年后的绝大多数手机） |
| `WotaGeiCam-vX.X.X-rYY-armeabi-v7a-release-Android.apk` | Android：32 位 ARM 老设备 |
| `WotaGeiCam-vX.X.X-rYY-unsigned-Harmony.hap` | HarmonyOS（未配置签名 Secrets 时产出；见下节安装注意） |

配置 HAP 签名 Secrets 后，Release 里会另有 `vX.X.X-rYY-signed-Harmony.hap`，并附 `SHA256SUMS`。

文件名中的 `X.X.X-rYY` 即完整版本名，`r` 后的数字是检查点序号，越大越新；应用内「检查更新」按同一规则比较。历史版本（`v0.0.9-r05` 及更早）使用旧命名 `WotaGeiCam-vX.Y.Z-*.apk`，且只有 Android 四件套。

## 安装与升级

**Android**
- 系统要求：Android 10（API 29）及以上
- 升级直接覆盖安装；**请勿先卸载**——卸载会清空应用内的设置与媒体库存档
- 也可以直接在应用内检查更新并下载安装包
- 本应用为纯 Kotlin 实现、不含原生库，四种 APK 内容一致；安装包内置 Baseline Profile（启动期热路径预编译，冷启动更快）
- 「所有文件访问」自 Android 11 起提供，Android 10 上应用内如实标注为不支持（此时删除 / 重命名只对本应用录制的视频生效）

**HarmonyOS**
- `*-unsigned-Harmony.hap` 未带发布签名，仅供结构验证，不能作为正式安装渠道
- `*-signed-Harmony.hap` 的 `profile.p7b` 必须是由 AGC/DevEco 签发、包含目标设备 UDID 的 profile，否则装不上零售机；开发调试可在 DevEco Studio 里自动签名后安装

## 从源码构建

**Android**（需要 JDK 17 与 Android SDK，Platform 34 / Build-Tools 33.0.1；SDK 位置按标准方式提供：`ANDROID_HOME` 或 `Android/WotaGeiCamApp/local.properties` 的 `sdk.dir`）：

```bash
git clone https://github.com/himetuki/WotaGeiCam.git
cd WotaGeiCam/Android/WotaGeiCamApp
./gradlew :app:assembleDebug      # 日常构建
./gradlew :app:assembleRelease    # 发布构建（签名说明见下）

# 打带检查点号的包（版本名 0.0.10-rNN，设置页可见；属性接受 05 或 r05 两种写法）
./gradlew :app:assembleRelease -Prbuild=05
```

```bash
# 在 Android/WotaGeiCamApp/ 目录下执行。与 CI 一致的"四件套"
# （plain / universal / arm64-v8a / armeabi-v7a）。
# 分两遍构建：先分包三枚，拷出后再打不分包一枚——第二遍会清掉第一遍在输出目录的产物。
./gradlew :app:assembleRelease -PabiSplits=true -PapkBaseName=WotaGeiCam -Prbuild=05
mkdir -p ../../apk-out && cp app/build/outputs/apk/release/*.apk ../../apk-out/
./gradlew :app:assembleRelease -PapkBaseName=WotaGeiCam -Prbuild=05
cp app/build/outputs/apk/release/*.apk ../../apk-out/
```

签名说明：仓库不含签名材料。如需产出正式签名的 release 包，在 `Android/WotaGeiCamApp/` 下自备 `keystore.properties`（含 `storeFile` / `storePassword` / `keyAlias` / `keyPassword` 四项）与 keystore 文件；缺失时 release 变体自动回退 debug 签名，仅供本地测试。`Android/WotaGeiCamApp/gradle.properties` 里的 `org.gradle.java.home` 记的是打包机的 JDK 位置，机器上 JDK 位置不同时改这一行或删掉改用 `JAVA_HOME`（CI 也是这样处理的）。

**HarmonyOS**（需要 DevEco Studio 的 hvigor 与 HarmonyOS SDK）：

```bash
cd HarmonyOS/WotaGeiCamOHOS
export DEVECO_SDK_HOME="$DEVECO_HOME/sdk"   # Windows Git Bash 示例
./hvigorw --mode module -p product=default assembleHap
```

产物在 `HarmonyOS/WotaGeiCamOHOS/entry/build/default/outputs/default/entry-default-unsigned.hap`。
纯逻辑测试：`cd HarmonyOS/WotaGeiCamOHOS && node scripts/pure-test.mjs`（762 用例）。

### 仓库布局

```
WotaGeiCam/
├── Android/WotaGeiCamApp/     # 安卓 Gradle 工程（入库）
├── HarmonyOS/WotaGeiCamOHOS/  # 鸿蒙 DevEco 工程（入库）
├── .github/workflows/         # build-apk.yml + build-hap.yml（v* tag 双流水线）
├── tools/                     # 本地工具链：android-sdk（SDK/模拟器）、avd（虚拟机）、
│   │                          # trace_processor 等；连同 node_modules/ docs/ research/
│   │                          # deliverable/ agent-memory/ camera-knowledge/ .zcode/
│   │                          # .mimosa/ 均为本地资产（gitignored 不入库）
└── README.md LICENSE .gitignore .gitattributes
```

两个工程互不 import，共享的只有产品语义与发布流程。鸿蒙侧跟进安卓上游改动走「只读对比 + 人工移植」，流程见 [`HarmonyOS/WotaGeiCamOHOS/docs/UPSTREAM_SYNC.md`](HarmonyOS/WotaGeiCamOHOS/docs/UPSTREAM_SYNC.md)。

## CI

推送 `v*` 标签同时触发两条流水线（也可在 Actions 页手动触发并填写检查点号），并行构建、资产进同一个 GitHub Release：

- [build-apk.yml](.github/workflows/build-apk.yml)：从标签推导检查点号，分两遍构建产出带版本号的四种 APK（plain / universal / arm64-v8a / armeabi-v7a）与 mapping 文件，校验四枚齐全后上架
- [build-hap.yml](.github/workflows/build-hap.yml)：用华为 DevEco Linux 命令行工具（内置 HarmonyOS 6.1.1 / API 24 SDK）编 HAP，含纯逻辑测试与可选签名；工具链版本与 SHA256 固定在 workflow 里，镜像可用仓库变量 `HARMONY_CLI_TOOLS_URL` / `HARMONY_CLI_TOOLS_SHA256` 覆盖

tag 带检查点号（如 `v0.0.10-r05`）时：APK 的 versionName 带 `-r05`，产物名也带；HAP 的平台只允许 `数字.数字.数字` 形式的 versionName，所以包内 `versionName=0.0.10`、r 号折算进 `versionCode`（公式 `1000000 + major*1e6 + minor*1e4 + patch*100 + r`），产物名仍带 `-r05`。

### Actions Secrets

| Secret | 用途 |
| --- | --- |
| `WOTA_KEYSTORE_BASE64` / `WOTA_KEYSTORE_PROPS` | Android 发布签名（p12/jks + properties） |
| `HAP_KEYSTORE_BASE64` / `HAP_APP_CERT_BASE64` / `HAP_PROFILE_BASE64` | HAP 签名三件套（DevEco 自动签名导出） |
| `HAP_KEY_ALIAS` / `HAP_KEY_PASSWORD` / `HAP_KEYSTORE_PASSWORD` | HAP 密钥别名与口令 |

未配置 HAP 签名 Secrets 时只产 unsigned 包。

## 诊断日志

遇到"功能不对但说不清"的问题（例如内录被系统拒绝、录制中途异常）时，可以把应用日志导出给开发者定位（以下按 Android 版描述）：

- 应用**自带日志，默认关着**（不占空间；崩溃记录不受此开关影响，崩溃时照样留下现场）
- 抓问题的步骤：设置页 →「诊断日志」→ 打开「记录日志」→ 复现一次 → 点「直接分享」一步拉起系统分享面板；或点「导出日志」把合并报告写到 `Documents/WotaGeiCam/`；需要更细的信息就打开「详细日志」
- 「清除日志」一键清空；日志文件按 512 KB × 3 自动轮转（约 1.5 MB 封顶），导出报告整体以 2 MB 为上限
- 导出的报告含录制帧账汇总（与「记录日志」开关无关）与崩溃记录；日志**只在本机生成、只由你主动导出**，内容不含画面与声音，也不会上传
- 注意：默认关着意味着问题出现后再开日志，之前的现场不会留下——排查复现不稳定的问题时，先打开日志再复现一次

## 隐私

双平台同一原则：以下权限与用途明细按 Android 版列出，鸿蒙版按 HarmonyOS 权限模型对应实现。

- **唯一的联网用途是应用内的「检查更新 / 下载更新」**，且只在你主动点击时才发起请求（读取 GitHub Releases 的最新版本并下载安装包，直连失败按镜像链回退；下载后本地重算 SHA-256 比对，不匹配即丢弃）——没有自动检查、没有后台联网、没有账号体系
- 不上传任何拍摄素材与使用数据：无统计、无广告、无第三方分析
- 拍摄素材只写入本机存储；对外分享只开放成片、更新包、诊断日志三类文件，不把整块存储交给系统分享组件
- 内录音轨使用系统音频采集机制，仅在录制期间有效（前台服务 + 常驻通知，可一键停止）
- 权限与用途（Android）：相机 / 麦克风（拍摄）、媒体读取（媒体库）、所有文件访问（删除或重命名非本应用录制的素材时不弹系统授权框）、蓝牙（发现与控制外接音箱；Android 10/11 上系统要求位置权限用于蓝牙设备扫描，仅此用途）、通知（Android 13+ 内录常驻通知）、安装未知应用（应用内更新安装）、网络（仅用于更新检查与下载；另有播放组件自带的「网络状态」只读权限，不产生联网行为）

## 许可

整项目以 [GNU GPL v3](LICENSE)（SPDX: `GPL-3.0-only`）发布：`Android/` 子树沿用上游安卓版的 GPL v3，HarmonyOS 移植工程以同一许可证发布。

Copyright © 2026 himetuki
