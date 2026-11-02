# REBUILD_MAP：安卓 Kotlin/Compose ↔ 鸿蒙 ArkTS 逐文件比对台账

2026-11-02 起的「逐个比对重建」工作底账。与 PORT_MAP（按上游提交追踪）互补：本表按
**源文件**对拍，verdict 分四档——
`✅对齐`（功能与交互语义在册）/ `🔶形态偏离`（功能在册、交互形态有意不同，带裁定）/
`❌缺失`（功能缺口，待重建）/ `🔍待比对`（尚未逐段对拍）。
每完成一个文件的逐段比对，更新本行并挂重建提交号。

## 一、UI / Compose 面

| 安卓文件 | 行数 | 鸿蒙对应 | verdict | 备注/缺口 |
|---|---|---|---|---|
| ui/CameraScreen.kt | 4294 | pages/Camera.ets | 🔶→推进中 | 深区对拍两批完成：**站位线族 ✅ 全量重建（9eb441c）** + **分屏对比 ✅ 重建（本批）**——分屏圆钮（录制锁）+ 左半压缩=纯变换（transformOrigin 左缘中点，上游 graphicsLayer 同式）+ 右侧面板两态（模式列表：另镜头录制占位[与上游同占位]/实时对比播放/退出；对比态：内嵌 PlayerEngine 参考片循环+自动开播+内嵌选片列表）+ ComparePractice 动线接通（对比页「对着练」→ 录制页首次组合取半屏）；余项：录完弹回对比页填右片（pendingRightId 消费，需入库 id 时序面）续批；双摄并发探测=OHOS 相机模型无等价面（占位与上游同形） |
| ui/CameraPills.kt | 1744 | ui/ParamPills.ets + Camera.ets 弹层面 + GlowPanel/TonePanel | ✅ | 19 颗胶囊对齐收口：Bitrate 弹层 ✅、**Flash 四模式摊开弹层 ✅（本批——Dock 点按循环保留历史、弹层为上游同构主入口；MANUAL 档禁自动预闪与下发侧 flashPlan 同口径）**、Size/Fps/Zoom=Dock 循环（🔶 形态裁定在册）、RefLine=设置页+快切钮、Monitor=设置页；GlowColorWheel ✅（c972228） |
| ui/HudLayer.kt | 2155 | pages/Camera.ets HUD 区 + 读数条 + HudLayout/HudMetrics/HudPageGrid/HudDragOrder | ✅ | 组件族对齐：HudEntryItem/ZoneBox/Grid/Dock/Readout/TopChrome/BottomZone/RecordButton/VolumeLeds/warnRows 逐项有对应（J1-J3+wx 三行批）；纯层 hudGridGap/hudGridRowPitchPx/bandLeftPx/zoneBandHeight 同源在册；形态偏离=T31 固定结构（五容器自由布局待真机，登记） |
| ui/HudLayout.kt | 2274 | core/HudLayout.ets (2787) | ✅ | J1-J3 移植在册；编辑器跨容器自由布局=产品决策待真机（PORT_MAP 登记） |
| ui/HudLayoutEditor.kt | 1353 | pages/HudEditor.ets | 🔶 | READOUT 区拖拽 ✅；五容器自由布局无渲染面（T31 结构，登记） |
| ui/SettingsScreen.kt | 2113 | pages/Settings.ets | ✅ | 14/14 组对齐：默认/方向/动效(24345d5)/亮度(f6c6ede)/HUD/滑杆吸附/参考线/水平仪/对比/文本/权限/诊断/检查更新+版本/**关于**（包名+数据说明，本批）；STORAGE 组 N/A——MANAGE_ALL_FILES_ACCESS 是 Android 专属，本侧沙箱 MediaRepo 零媒体权限姿态（20aa0e0 同裁定） |
| ui/dialog/CurvePopup.kt | 893 | ui/CurvePopup.ets | ✅ | 含缩放 eb956f5 + 320a956/bda89bb 全量（曲线批C1-C3） |
| ui/dialog/BtSpeakerPanel.kt | 376 | pages/BtPanel.ets | ✅ | 面板在册（设备行/配对/连接；OHOS 化差异=系统蓝牙设置跳转面，登记） |
| ui/dialog/CameraDialogs.kt | 375 | Camera.ets 各弹层 | 🔍 | 待比对 |
| ui/dialog/AudioSourcePanel.kt | 233 | （无） | ❌→平台面待批 | **宿主不存在**：本侧 GPU 录制链无音轨（GlRecorder=纯视频，内录仅开发者页 AVScreenCapture 探针）；音源面板（MIC/内录/蓝牙三选）要等「录制链加音轨」native 批（含 AudioFeeder/muxer 音轨）——摆无宿主的假面板比缺更糟 |
| ui/design/PillPopup.kt | 510 | ui/PillPopup.ets | ✅ | 显形动效已重建（本批）：0.94→1 弹簧+淡入（OFFSET 规格），MotionModeStore 直读闸（PLAIN 直达）；PillSlider.readContinuous 同款注入形态免穿参 |
| ui/design/WotaSwitch.kt | 288 | ui/WotaSwitch.ets | ✅ | 速度继承已重建（springCurve(velocity) 初速度入口 + stiffness=(2π/r)²/c=2ζ√(mass·k) 换算面，17da517） |
| ui/design/LikeHeart.kt | 281 | core/LikeBurst.ets + ui/LikeHeart.ets | ✅ | 数学全表+Canvas 宿主（71a74ce）；数字滚动刻意不迁 |
| ui/design/Widgets.kt | 688 | HudTokens/PillPopup/HudPill/HudSpec 散布 | ✅ | 卡片/芯片/圆钮/时长徽标/菜单弹层/霜板登记族全在册（TOK+J1-J3 批）；霜板双路径（卡片/Dock 壳）在册 |
| ui/design/Tokens.kt | 315 | ui/HudTokens.ets | ✅ | 令牌化对齐（TOK 批） |
| ui/anim/LiquidMerge.kt | 981 | Gallery Swiper.curve + CurvePopup 扇面 animateTo | 🔶→推进中 | 三处速度继承：①相册翻页 ✅（Swiper.curve(springMotion FLOAT)，velocity 由手势连续性继承，本批）②曲线扇面 ✅（fanOpen 三处包 animateTo springMotion(FLOAT)，本批）③dockSlide N/A——本侧 Dock 是 T31 固定 2×4 结构，无「换栏滑动」宿主（LAND 批登记） |
| ui/anim/Motion.kt | 264 | Settings.ets motionGroup + 各处 animateTo | 🔶 | 动效档（motion_mode 三档）已重建并接 LikeHeart 消费闸（本批）；全局闸只覆盖红心宿主，其余动效点（弹层/开关）逐个接线=续批；系统减少动效无 ArkUI 读取面（登记） |
| ui/ParamsPersist.kt | 394 | ui/ParamsPersist.ets | ✅ | 键表 26（上游 32 的差集=未移植批键，登记） |
| ui/FrostProbe.kt / FrostSpace.kt | 216/179 | native 霜链（setFrostEnabled 在册） | 🔶 | 霜链本体在册；FrostProbe=Android 专属尺寸探针 UI（开发者页），N/A 登记 |
| ui/MainActivity.kt | 721 | EntryAbility + pages | 🔶 | 导出上收/组合守护/导航留痕=N/A（Android 导航模型）；亮度驱动 ✅（f6c6ede） |
| ui/widget/ParamSlider.kt | 343 | ui/PillPopup.ets PillSlider | ✅ | 吸附开关/梯/连续三态对齐（0dee86d） |
| ui/widget/RefLineOverlay.kt | 243 | ui/RefLineOverlay.ets | ✅ | |
| ui/widget/CameraSurface.kt | 194 | XComponent 面 | ✅ | 平台等价 |

## 二、页面（上表之外的整页）

| 安卓 | 鸿蒙 | verdict | 备注 |
|---|---|---|---|
| player/PlayerScreen.kt (1987) | pages/Player.ets (1771) | ✅ | 深区对拍完成：AB 行/倍速/声音/帧步进/缩放平移/chrome 矩阵/亮度/导出双模/修复面板逐项对齐；**导出配置面板补齐（本批）**——弹层内当场可调光晕开关+阈值(60~100%)/增益/混合+降噪开关，只作用本次导出不反写设置（上游 350c671）；收藏宿主无（3651901 半边 N/A 已登记） |
| player/CompareScreen.kt (1405) | pages/Compare.ets (1846) | ✅ | ABP2/时间线/音轨/练习/双画面 pinchZoom 在册（捏合缩放重建 0a082a8；单指语义 fingers:2 隔离对齐） |
| media/GalleryScreen.kt (1430) | pages/Gallery.ets (1751) | ✅ | 深区对拍完成：三页签+自定义标签页签/系列面板/三列表模式(网格·时间线·全屏)/导入/改名/分享/对比/回收站/红心/空态逐项对齐；网格列数=产品决策不迁（列表架构，登记）；❤ 宿主=全屏查看器（3651901 半边 N/A） |
| pages/Index 路由 | entryability/Index | ✅ | |

## 三、功能/引擎面（非 UI，抽样）

| 安卓 | 鸿蒙 | verdict |
|---|---|---|
| camera/Camera2Engine.kt (1899) | camera/GlCameraEngine.ets + KitAbility | ✅ 等价面（OHOS 会话模型不同，RequestApplier 头注登记） |
| camera/GlRenderEngine.kt (3271) | native gl_render.cpp | ✅ 双 program 槽/降噪/Glow/霜链在册 |
| player/SceneExportRunner.kt (453) + SceneExportGl.kt (677) | native scene_exporter.cpp | ✅（GL→CPU 同式偏离登记，ca51f6e） |
| record/CodecRecorder.kt (2790) | native codec_recorder.cpp | ✅ GPU 录制链 |
| player/PlayerEngine.kt (616) | player/PlayerEngine.ets | ✅ |
| 其余 core/record/media/update/diag | 同名 .ets | ✅（历批纯层移植，PORT_MAP 全程登记） |

## 四、重建队列（❌/🔶 中可执行项，按优先级）

1. **WotaSwitch 速度继承**（🔶→✅）：`curves.springCurve(velocity, mass, stiffness, damping)`
   支持初速度——球落位改 springCurve（velocity 取 pan 端速度），stiffness=(2π/response)²、
   damping=2ζ√(mass·stiffness)（LiquidSpring.OFFSET 语义换算）。**本批执行**。
2. **Motion.kt 统一动效闸**（🔶→基本收口）：动效档设置+持久化 ✅；消费闸已接：LikeHeart
   （PLAIN 不播）+ PillPopup 显形（PLAIN 直达，本批）。系统减少动效读取面=平台无 API（登记）。
3. **LiquidMerge 速度继承三处**（❌）：相册 Pager snapAnimation/曲线扇面/dockSlide——
   springCurve 化逐处重建（待批，量大）。
4. **CameraScreen/HudLayer/Settings/Player/Gallery 五大面逐段对拍**（🔍）：
   按 Compare 先例（逐区列清单→判 verdict→补缺）推进。已比对子项登记：
   - 手势族：点按/双击/长按（TAP 仲裁 ✅）、双指捏合（Player/Compare ✅）、Dock 长按换栏
     （🔶=J3b 已登记，编辑器 READOUT 拖拽在册）
   - 标签/文案族：CameraDialogs 的 label 函数族 ✅（ParamFormat.ets 同源）
5. **CameraDialogs.kt**：label 函数族 + PlayerScreen 工厂面——label 族已对齐（ParamFormat），
   rememberPlayerEngine 工厂=N/A（生命周期模型不同，PlayerEngine.ets 直构），✅。
