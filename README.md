<div align="center">

# WotaGeiCam

**面向 WOTA 艺日常训练的不成熟个人开源相机项目** —— 把光弧拍好看，把动作练标准

[![Build Release APKs](https://github.com/himetuki/WotaGeiCam/actions/workflows/build-apk.yml/badge.svg)](https://github.com/himetuki/WotaGeiCam/actions/workflows/build-apk.yml)
[![Build Release HAPs](https://github.com/himetuki/WotaGeiCam/actions/workflows/build-hap.yml/badge.svg)](https://github.com/himetuki/WotaGeiCam/actions/workflows/build-hap.yml)
![Release](https://img.shields.io/github/v/release/himetuki/WotaGeiCam?color=7f3a9c&label=Release)
![Platform](https://img.shields.io/badge/平台-Android%20·%20HarmonyOS-e42b9f)
![License](https://img.shields.io/github/license/himetuki/WotaGeiCam?color=9c1f7b)

[English](README.en.md) · [下载 Releases](https://github.com/himetuki/WotaGeiCam/releases/latest) · [反馈 Issue](.github/ISSUE_TEMPLATE/bug-report-feature-request.md)

</div>

## 社区翻译

一个人能覆盖的机型与语言都很有限，翻译这块尤其如此。[@mayday112](https://github.com/mayday112) 为应用制作并持续维护着英文（English）/ 印尼文（Bahasa Indonesia）界面的翻译版：[mayday112/WotaGeiCam](https://github.com/mayday112/WotaGeiCam)，正好补上了这块空缺——真心感谢他的付出。

需要英文（English）/ 印尼文（Bahasa Indonesia）界面的话，推荐直接使用这个版本；翻译的问题与建议欢迎反馈给他，应用本身的问题与建议欢迎按 [Issue 模板](.github/ISSUE_TEMPLATE/bug-report-feature-request.md) 反馈给我。他的版本与本仓库各自独立构建、各自签名，互相切换需要卸载重装（会清空应用内的设置与收藏）；翻译版包含的内容以其仓库说明为准。

**目录**

- [✨ 特色功能](#-特色功能)
- [功能特性](#功能特性)（[拍摄控制](#拍摄控制) · [取景辅助](#取景辅助) · [界面与动效](#界面与动效) · [录制](#录制) · [光弧工具链](#光弧工具链) · [播放与整理](#播放与整理)）
- [功能预定](#功能预定)
- [已知问题与限制](#已知问题与限制)
- [下载](#下载)
- [安装与升级](#安装与升级)
- [从源码构建](#从源码构建)
- [CI](#ci)
- [诊断日志](#诊断日志)
- [隐私](#隐私)
- [许可](#许可)

> 长期验证的机型屈指可数，厂商相机 HAL 的差异远比一个人能摸完的多。已知问题如实列在[「已知问题与限制」](#已知问题与限制)；遇到奇怪行为欢迎按 [Issue 模板](.github/ISSUE_TEMPLATE/bug-report-feature-request.md) 告诉我机型与现象（附诊断日志更好）。**安全问题请走 [安全政策](SECURITY.md) 私密渠道，勿开公开 Issue。** 你的反馈是这个项目前进的主要方式，也请对缺陷与未打磨之处多多包涵。

## ✨ 特色功能

**🌟 光弧工具链 · 把光弧拍好看**
- 强制低速快门 **1/24 · 1/25** 满窗长曝光保光弧连贯；**等号档曝光纠偏**自动留读出余量防掉帧。
- **残影（高光拖影）**：多帧回波拉长光弧，「保持亮度 / 提亮拖尾」双模式、倍数 2~10×，预览 / 成片 / 后渲染导出三路。（算法思路参考自 B 站「蒙德·究史尼」的「光弧倍增器」开源脚本，特此致谢）
- **录制期抽帧补弧 + 离线光弧修复**：无原生低速档的机型也能保住连贯光弧。

**🎯 训练对照 · 把动作练标准**
- **AI 站位提示**：预览态自动识别站位，角色语音逐号位报「往左 / 往右 / 往前 / 往后」校正，完成 / 缺人 / 多人 / 全员完成各有提示、句尾带角色口癖；轮播节奏（轮询内 / 轮询间间隔）与判定精确度可调；GPU 加速三态可选；录制开始自动暂停，停录恢复。
- **分屏对照**：一侧播放参考视频（直接采用其音频）、另一侧录制被摄者，供录制者对照参考观察被摄者的练习状态；被摄者正被录制、无法边看边练，由参考视频的声音引导节奏。
- **双轨对比播放**：把自己的练习与参考并排比对，音轨可切、按音频自动对齐。

**🎵 音频 · 对着伴奏合拍**
- **双音轨**（环境音 + 内录）、**音频降噪**、**蓝牙音箱**音量 / 播放控制。

**🎨 实时画面特效 · 进成片**
- **光晕 Glow**、**实时 RGB 曲线**、**画面降噪**（GPU 双边，专治夜间暗部）。

**🔍 专业监看**
- **斑马纹 / 峰值对焦** · **参考线 / 网格** · **站位线**（独立于参考线）· **水平仪 / 俯仰仪**。

> 单树 monorepo：`Android/WotaGeiCamApp/` 为 Android 工程（功能定义），`HarmonyOS/WotaGeiCamOHOS/` 为同产品鸿蒙移植（仍在进行、尚未达可安装使用程度）。功能与界面迭代较快，最新版本以 [Releases](https://github.com/himetuki/WotaGeiCam/releases/latest) 为准。

## 功能特性

以下按类别逐项列出功能，并标注**位置**、**调出方式**与**用法**。以 Android 版为准；HarmonyOS 版为同产品移植，仍在进行、尚未达到可安装使用程度，对照与进度见 [`HarmonyOS/WotaGeiCamOHOS/docs/PORT_MAP.md`](HarmonyOS/WotaGeiCamOHOS/docs/PORT_MAP.md)。

### 拍摄控制

| 功能 | 位置 · 调出 | 用法 |
|---|---|---|
| 手动曝光（快门 / ISO / EV） | 录制页右下读数块，点按对应读数开面板 | 拖滑杆调值；录制中 ISO / EV 可调，快门只读 |
| 强制低速快门 1/24 · 1/25 | 快门面板 | 选 1/24 或 1/25 档，长曝光保证光弧连贯；可选性按当前帧周期校验 |
| 等号档曝光纠偏 | 快门面板（选 1/24 / 1/25 时露出） | 「自动微调曝光」默认开，防帧周期被拉长掉帧；关后手动滑杆 0~+8ms；左上角实测读数行仅手动曝光显示 |
| 帧率 | 右下读数块「帧率」，点按 | 选档；※ 为本机近似档；非精确档录制时自动抽帧转换 |
| 白平衡 | 右下读数块「白平衡」，点按 | 选预设或调色温 / 色调 |
| 对焦 / 变焦 | 右竖 Dock 对焦 / 变焦胶囊；或直接点按画面 | 点按画面对焦测光、双击锁定、长按解锁；自动曝光档长按半秒锁定 / 解除 AE |
| 实时 RGB 曲线 | 左竖 Dock「曲线」胶囊 | 拖节点调色；弹窗可拖右缘放大，「重置」回恒等 |
| 刻度滑杆 | 各参数面板；设置 → 滑杆吸附 | 默认按能力分档吸附、读数显示真实值，可逐参数改连续值 |
| 参数记忆 | 自动 | 录制页改过的参数重启保留，滑杆松手即存 |
| 手势导航适配 | 自动 | 小白条设备底栏自动避让手势区；参数按设备能力运行时读取，不写死机型数值 |

### 取景辅助

| 功能 | 位置 · 调出 | 用法 |
|---|---|---|
| 斑马纹 / 峰值对焦 | 左竖 Dock「监看」胶囊 | 选效果，调阈值 / 密度 / 强度 / 颜色；仅 GPU 渲染生效 |
| 光晕 Glow | 左竖 Dock「光晕」胶囊 | 开关 + 阈值 / 强度 / 混合；颜色分「像素自身色 / 仅色盘色」；预览与成片都带；仅 GPU |
| 参考线 / 网格 | 左竖 Dock「参考线」圆钮 | 多选线型（三分法 / 对角线 / 安全框 / 网格等）；默认样式在 设置 → 参考线默认 |
| 站位线 | 顶栏右端圆钮（分屏钮左侧） | 点按开浮层调条数 / 间距、长按选色；仅预览叠加、不进成片，录制中可用 |
| AI 站位提示 | 顶栏右端圆钮（站位线钮左侧） | 预览态识别站位并角色语音逐号位报方向（往左 / 往右 / 往前 / 往后），支持 1~5 人、自动 / 手动人数、精确度 β、轮播双间隔（轮询内 / 轮询间）、夜间预处理提亮、GPU 加速三态（自动 / 强制 / 关）与模型组导入；开关与参数即改即存，录制中自动暂停、停录恢复；**仅 full 变体携带**，lite 变体该按钮显示获取 full 版的引导（见[已知问题](#已知问题与限制)） |
| 水平仪 / 俯仰仪 | 右竖 Dock | 回正瞬间轻震；开关在 设置 → 取景辅助 |
| 读数块 | 右下读数块 | 快门 / 帧率 / 码率 / ISO / EV / 白平衡 / 变焦，逐项显隐在 设置 → 取景器常驻功能 |
| 控件布局自定义 | 设置 → 取景器常驻功能 → 编辑控件 | 拖拽摆放 Dock 与读数块；调节面板跟随控件锚点 |

### 界面与动效

| 功能 | 位置 · 调出 | 用法 |
|---|---|---|
| 动画风格 | 设置 → 动画风格 | 无动画 / 流畅（默认）/ 液态Q弹 三档；液态档为物理弹簧动效，可被手势打断后从当前速度续接 |
| 液态手势开关 | 液态档下的各开关 | 拇指沿轨道拖动过中点即切换，松手从离手速度落位 |
| 收藏红心动画 | 播放页顶栏 / 媒体库收藏入口 | 点收藏绽放环与彩点；系统「减少动效」或「无动画」档下只切状态不播放 |
| 亮度 | 设置 → 亮度 | 启动亮屏延迟、播放起播渐亮 |
| 界面方向 / 文本高度 | 设置 → 界面方向 / 文本高度 | 横竖屏切换；读数与面板文字大小 |

### 录制

| 功能 | 位置 · 调出 | 用法 |
|---|---|---|
| 开始 / 停止 | 底栏中央快门 | 点按起录 / 停录；双引擎，码率在 设置 → 开机初始值 |
| 双音轨（环境音 + 内录） | 右竖 Dock「音频」胶囊，点按开音源面板 | 切环境音 / 内录；内录采集系统正在播放的音频，需通知权限；冷启动默认环境音 |
| 蓝牙音箱 | 音源面板 | 媒体音量 / 播放控制 / 电量；配对在系统蓝牙设置 |
| 分屏对照 | 顶栏右端分屏钮 | 一侧播参考视频（直接采用其音频）、另一侧录制被摄者，供录制者对照观察被摄者练习状态；被摄者正被录制、无法边看边练，由参考声音引导节奏；须开录前打开，录制中锁定 |
| 自动分段 | 自动 | 单段 3.5 GiB 换段、段名连续；85% 顶栏预提示 |
| 帧率 / 白平衡如实提示 | 顶栏告警行 | 实际帧率持续过低、或手动白平衡不被机型采纳时提示（见[已知问题](#已知问题与限制)） |
| 起录缓冲 / 换段重试 | 自动 | 起录瞬间样本补写不丢；换段失败先回收资源再重试 |
| 录制帧账 | 诊断日志导出 | 每段末相机 → 渲染 → 编码 → muxer 帧数汇总，定位掉帧环节 |

### 光弧工具链

| 功能 | 位置 · 调出 | 用法 |
|---|---|---|
| 录制期抽帧补弧 | 帧率读数面板（无 24/25 原生档时自动触发） | 「自动抽帧补弧」并入被抽帧保光弧连贯 / 「仅抽帧」；抽帧位次记入同名 `.drops.json` |
| 残影（高光拖影） | 底栏左侧「残影」胶囊（默认隐藏） | 先在 设置 → 取景器常驻功能 勾出；选模式（保持亮度 / 提亮拖尾）与倍数 2~10×；预览 / 成片 / 后渲染导出三路；仅 GPU |
| 离线光弧修复 | 播放器 → 光弧修复 | 选目标帧率，GPU / CPU 二选一；存新文件不覆盖原片 |
| 并入门槛 | 帧率弹层 / 光弧修复弹层 / 设置 → 开机初始值 | 默认 12（0–32），调大更保守、调小并入噪点；录制与离线、CPU 与 GPU 同一判据 |

> 残影功能的**算法思路与双模式等产品语义**参考自 B 站[@蒙德·究史尼](https://space.bilibili.com/8205741)的开源脚本「光弧倍增器」，在此致谢。借鉴范围是思路与语义，**未复用其任何源代码**：本项目的着色器与实现均为独立编写，技术路线亦不同——GL 实时反馈累积、回波拖在运动方向后方，而非其 Python 离线滑窗（回波朝运动方向前方）。

### 播放与整理

| 功能 | 位置 · 调出 | 用法 |
|---|---|---|
| 媒体库 | 录制页底栏缩略图入口 | 全部 / 收藏 / 回收站；长按多选批量重命名 / 分享 / 删除 |
| 播放器 | 媒体库点按视频 | AB 循环、逐帧步进、倍速 0.1x–1x、双指缩放平移、水平镜像 |
| 剪辑导出 | 播放器底栏 A/B 标段 → 剪辑 | 无损（不重编码）/ 后渲染（重编码，可叠加降噪 + 光晕 + 残影，弹层内配置、仅作用本次）；音轨可选；导出中离开播放页不中断 |
| 双轨对比 | 媒体库长按 → 对比播放 | 双视频轨 + 音轨切换、按音频自动对齐；保留对比历史 |
| 冷启动维护 | 自动 | 清理临时残留、保持媒体库索引一致；归位不覆盖已有文件 |

## 功能预定

规划 / 研究中，尚未落地：

- [ ] **R1 · 测光模式选择**：可选评价 / 中央重点 / 点测光等测光模式；效果是否可见依设备 HAL 实现。
- [ ] **光弧修复小模型**：修复光弧的死区 / 断点，自相邻帧延长补全，让光弧更连贯。
- [ ] **硬件能力轮（方案 34 暂存）**：EDGE 锐化并入降噪、镜头暗角并入色调、风格化 GL LUT 预设（黑柔 / WOTA 艺向）、分屏副镜头同录（双文件）；方案已成文待实施。
- [ ] **鸿蒙 AI 站位**：待麒麟 NPU 推理接口研究落地后，出携带 AI 站位的 HarmonyOS 包（当前鸿蒙包与 lite 口径同步）。

## 已知问题与限制

按"如实说"的口径列出当前已知的问题面（核对至 v0.0.12-r05；这条清单会随反馈更新）：

- **AI 站位提示为实验性功能（v0.0.12-r05 起，full 变体携带）**：检测模型与判定逻辑已过单测和模型张量校验，但**真机识别精度尚未系统验证**（尤其暗光、远景、多人场景）。安全区默认值来自 PC 试验台的少量标注，换机位 / 换场地建议先用精确度 β 与「预处理提亮」调，并以实际播报为准；模型组可从电脑导入替换，坏包会自动回退内置。GPU 加速的「自动」档按兼容性白名单裁决——白名单未收录的 GPU（如部分麒麟 Maleoon 机型）在「强制」档下实测可用，推理验证失败会自动回退 CPU 并提示。

- **AI 站位提示语音可自定义（fork 开发者）**：播报语音是仓库内 `Android/WotaGeiCamApp/app/src/full/assets/voice/` 下的 14 个 WAV（单声道），按文件名契约取用：`num_1`～`num_5`（号位）、`dir_left` / `dir_right` / `dir_forward` / `dir_backward`（方向）、`st_done` / `all_done`（完成 / 全员完成）、`st_missing` / `st_extra`（缺人 / 多人）、`end`（一轮播报结尾的口癖）。用同名 WAV 覆盖即可生效，无需改代码；只进 full 变体，lite 不携带。

- **设置页连点返回导致的整屏黑（r31～r41 已修，更早版本请升级）**：真机日志确认根因**不在相机**——连点设置页返回时，第二下落在刚回到的录制页上，把导航的起始页面弹掉，返回栈被清空，界面框架从此没有内容可画：整屏黑、无控件，但应用活着、返回可用。r40/r41 起页内返回不再能弹掉起始页（另有 400ms 防抖），任何页面若界面被拆掉超过 3 秒会自动恢复；相机管线也已上收窗口作用域，导航竞争动不到相机状态。旧版本（≤ v0.0.11-r30）遇到请先升级。

- **实际帧率由相机 HAL 决定，App 只能协商、不能保证**：帧率档位决议只保证"下发的值是设备声明过的"；暗光、慢快门或厂商策略都可能让实际到帧率低于所选档。App 在实际帧率持续低于目标约七成时会顶栏提示实测值，但**不强行干预**——强行锁档在部分机型会导致取景器假死（这是修过的另一个缺陷的教训）。部分厂商 HAL 还会在请求固定档之外自行放宽范围：实测某华为机型请求 `[24,24]`、结果侧变成 `[15,24]`。
- **旧版本（≤ v0.0.10-r03）在"宽帧率区间"机型上有严重掉帧缺陷**：手动曝光时帧周期被按区间下界下发，把实际帧率物理锁死在低档（某 vivo 机型实测 4.98fps 装进 25fps 容器；"调低分辨率/码率也躲不开"，因为它与编码负载无关）。已在 v0.0.11-r01 修复——在旧版上遇到"一录就掉帧 / 一放歌（正式演出场景）就掉帧"，请先升级再观察。
- **手动白平衡的机型依赖**：色温增益需要设备声明 `TRANSFORM_MATRIX` 能力，且部分机型（尤其带安卓兼容层的鸿蒙设备）声明了也不真正采纳增益。v0.0.11-r28 起两段修复：①修掉"开启手动档整屏发绿、滑杆无效"的根因（下发增益时错误地使用了恒等色彩矩阵，现在改用相机自动白平衡自己算出的矩阵）；②无法采纳增益的机型自动改走画面层算法兜底并如实提示「本机白平衡不接受手动增益」，兜底是相对自动白平衡的色温/色调偏移，效果因机型而异、尚无闭环标定数据。另有已知残留：从设置页返回录制页的瞬间，个别机型可能有一帧过渡混合（相机在兼容层上重新接画面），不影响后续画面。
- **高码率长录会换段**：单段上限 3.5GiB（FAT32 安全值），50Mbps 档约 9 分钟即到——10 分钟以上的录制**必然**自动换段一次（字节到 85% 时顶栏预提示）。换段窗口有界、受阻会明确失败并记账，但那一刻的短促停顿是可预期的。
- **取景辅助与场景特效依赖 GPU 渲染路径**：不使用 GPU（DIRECT）时斑马纹、峰值对焦、光晕与残影不生效（应用内也有提示）。
- **HarmonyOS 版的安装门槛**：unsigned 包仅供结构验证，装不上零售机；签名 profile 必须由 AGC/DevEco 签发且包含目标设备 UDID。
- **测试覆盖有限**：长期真机验证的机型只有 WIKO GAR-AN60、华为 ALN-AL00 / ADY-AL00、vivo V2505A 和一台模拟器；低端机与小众 ROM 基本没有覆盖，能力协商代码在那上面大概率还有没见过的行为。遇到了请按下文导出诊断日志发给我，就是帮大忙。


### 更新

| 功能 | 位置 · 调出 | 用法 |
|---|---|---|
| 检查 / 下载更新 | 设置 → 关于 → 检查更新 | 主动点击才联网，读取 GitHub Releases 最新版；下载前测速选最快通道，失败按速回退；不自动检查、不后台联网 |
| 更新说明 | 发现新版本弹窗 | 显示该版本 Release 说明原文，仅供展示 |
| 安全校验 | 自动 | 强制 HTTPS、拒内网 / 环回目标；SHA-256 本地比对不符即丢弃；安装前核对包名与签名一致 |

## 下载

到 [Releases](https://github.com/himetuki/WotaGeiCam/releases/latest) 下载。打一个 `vX.X.X` 或 `vX.X.X-rYY` 标签即同时产出两端安装包，资产进同一个 Release，按扩展名前的平台后缀（`-Android` / `-Harmony`）区分：

| 文件 | 平台 / 适配 |
|---|---|
| `WotaGeiCam-vX.X.X-rYY-full-release-Android.apk` / `WotaGeiCam-vX.X.X-rYY-full-universal-release-Android.apk` | Android full：全设备通用，**含 AI 站位提示**（拿不准选 universal） |
| `WotaGeiCam-vX.X.X-rYY-lite-release-Android.apk` / `WotaGeiCam-vX.X.X-rYY-lite-universal-release-Android.apk` | Android lite：**无 AI 站位**（体积小约 21MB；与 full 可互相覆盖安装保留数据，更新各推各包） |
| `WotaGeiCam-vX.X.X-rYY-full-arm64-v8a-release-Android.apk` / `WotaGeiCam-vX.X.X-rYY-full-armeabi-v7a-release-Android.apk` | Android full：按 ABI 分包（lite 同样有分包，命名把 full 换 lite） |
| `WotaGeiCam-vX.X.X-rYY-release-Android.apk` / `WotaGeiCam-vX.X.X-rYY-universal-release-Android.apk`（及 `-arm64-v8a-` / `-armeabi-v7a-` 分包） | **兼容旧版**：无 flavor 段、**内容 = lite**——给 v0.0.12-r01 及更早版本的应用内自动更新用（那时的更新器只认识这种文件名）；手动下载请优先选上表的 full / lite |
| `WotaGeiCam-vX.X.X-rYY-unsigned-Harmony.hap` | HarmonyOS（未配置签名 Secrets 时产出；见下节安装注意）——当前与 lite 同功能口径 |

配置 HAP 签名 Secrets 后，Release 里会另有 `vX.X.X-rYY-signed-Harmony.hap`，并附 `SHA256SUMS`。

文件名中的 `X.X.X-rYY` 即完整版本名，`r` 后的数字是检查点序号，越大越新；应用内「检查更新」按同一规则比较，并按包类型（full / lite）只推对应安装包。v0.0.12-r05 起一个版本共 12 枚 Android APK（full 4 + lite 4 + 兼容旧版 4）；**旧版（≤r01）自动更新会落到"兼容旧版"组（=lite 内容）**。应用内更新会依次尝试多个公共加速节点并按实测速度重排，节点失效自动跳过；全部失败请手动下载。历史版本（`v0.0.9-r05` 及更早）使用旧命名 `WotaGeiCam-vX.Y.Z-*.apk`，且只有 Android 四件套。

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
./gradlew :app:assembleFullDebug      # 日常构建（full = 有 AI 站位；lite = 无 AI 导流版）
./gradlew :app:assembleFullRelease    # 发布构建（签名说明见下）

# 打带检查点号的包（版本名 0.0.10-rNN，设置页可见；属性接受 05 或 r05 两种写法）
# 双 flavor：full / lite 各出一枚（lite 包名相同、体积小，无 AI 站位）
./gradlew :app:assembleFullRelease :app:assembleLiteRelease -Prbuild=05
```

```bash
# 在 Android/WotaGeiCamApp/ 目录下执行。与 CI 一致的双 flavor 全套
# （full / lite × plain / universal / arm64-v8a / armeabi-v7a，共 8 枚）。
# 分两遍构建：先分包（按 flavor 分目录输出），拷出后再打不分包——第二遍会清掉第一遍的产物。
./gradlew :app:assembleFullRelease :app:assembleLiteRelease -PabiSplits=true -PapkBaseName=WotaGeiCam -Prbuild=05
mkdir -p ../../apk-out
for d in full lite; do
  for f in app/build/outputs/apk/$d/release/*.apk; do
    b=$(basename "$f" .apk); cp "$f" "../../apk-out/${b}-$d.apk"
  done
done
./gradlew :app:assembleFullRelease :app:assembleLiteRelease -PapkBaseName=WotaGeiCam -Prbuild=05
for d in full lite; do
  for f in app/build/outputs/apk/$d/release/*.apk; do
    b=$(basename "$f" .apk); cp "$f" "../../apk-out/${b}-$d.apk"
  done
done
```

> flavor 说明：full 与 lite 的 `applicationId` / `versionCode` 相同（可互相覆盖安装且保留数据），lite 的 versionName 带 `-lite` 后缀；应用内「检查更新」按版本类型各推各包（full 只收 full、lite 只收 lite）。

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

两个工程互不 import，共享的只有产品语义与发布流程。鸿蒙侧跟进安卓版改动走「只读对比 + 人工移植」，流程见 [`HarmonyOS/WotaGeiCamOHOS/docs/UPSTREAM_SYNC.md`](HarmonyOS/WotaGeiCamOHOS/docs/UPSTREAM_SYNC.md)。

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

整项目以 [GNU GPL v3](LICENSE)（SPDX: `GPL-3.0-only`）发布。项目早期仅有 Android 版，现为 Android 与 HarmonyOS 双版本，两个工程均以同一许可证发布。

Copyright © 2026 himetuki
