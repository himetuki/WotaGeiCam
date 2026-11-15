# 页面映射（Android 非 AI 页面/路由/浮层 → iOS）

基线：`Android/` 只读参考，提交 `7394187`。路由真源：`ui/MainActivity.kt`；
媒体库路由表：`media/GalleryScreen.kt` 的 `object WotaNav`。

**UI 原则**：iOS 以原生相机/照片应用惯例为优先（NavigationStack、Tab、`.sheet`、
`.confirmationDialog`、`.alert`、原生手势），**不引入 Material 组件**，不以「和 Android 看起来一样」为目标。

## 路由表

| ID | 路由 | Android 源 | iOS 目标 | 转换说明 | 验证项 | 状态 |
|---|---|---|---|---|---|---|
| P-001 | `camera`（起始目的地） | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/MainActivity.kt:70<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/MainActivity.kt:538 | iOS/WotaGeiCam/Camera/CameraPreviewScreen.swift | 取景页为根；骨架已交付真实预览 + 权限/生命周期/错误反馈（无 Material） | 编译：骨架可构建；人工：真机首启出预览 | 已实现（A2-S 骨架） |
| P-002 | `settings` | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/MainActivity.kt:71<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/MainActivity.kt:595 | iOS/WotaGeiCam/Settings/ | 用原生 `Form`/`Section` 重建分组（见 settings.md） | 编译：设置页可构建；人工：各项读写生效 | 待实现 |
| P-003 | `hudEditor`（HUD 编辑） | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/MainActivity.kt:73<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/MainActivity.kt:605 | iOS/WotaGeiCam/UI/ | 拖放/吸附用 SwiftUI 原生手势重写 | 编译：编辑页可构建；人工：拖放保存后重启保持 | 待实现 |
| P-004 | `gallery` | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/media/GalleryScreen.kt:129<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/MainActivity.kt:558 | iOS/WotaGeiCam/Library/ | 媒体库页；页签/选择/时间线/网格 | 编译：库页可构建；人工：进入/返回/刷新正常 | 待实现 |
| P-005 | `player/{mediaId}` | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/media/GalleryScreen.kt:136<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/MainActivity.kt:567 | iOS/WotaGeiCam/Player/ | 播放页（含 AB/倍速/逐帧/导出浮层） | 编译：播放页可构建；人工：播放/AB/导出可用 | 待实现 |
| P-006 | `compare?leftMediaId=` | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/media/GalleryScreen.kt:137<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/MainActivity.kt:581 | iOS/WotaGeiCam/Compare/ | 对比页（双播放器 + 历史 + 镜像） | 编译：对比页可构建；人工：双片同步/历史/镜像可用 | 待实现 |

## 取景页内部块（`ui/CameraScreen.kt`）

| ID | 块 | Android 源 | iOS 目标 | 转换说明 | 验证项 | 状态 |
|---|---|---|---|---|---|---|
| P-010 | 取景页主体 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/CameraScreen.kt:300 | iOS/WotaGeiCam/Camera/ | 预览 + HUD 五容器 + 各覆盖层 | 编译：取景页可构建；人工：预览与控件叠加正确 | 待实现 |
| P-011 | 对焦框/点按对焦 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/CameraScreen.kt:2894<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/CameraScreen.kt:3011 | iOS/WotaGeiCam/Camera/ | 点按设对焦点（iOS 用 `focusPointOfInterest` + 原生反馈） | 编译：可构建；人工：点按对焦生效 | 待实现 |
| P-012 | 提示条 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/CameraScreen.kt:2933 | iOS/WotaGeiCam/UI/ | 顶部/底部提示 | 编译：可构建；人工：提示出现/消失正常 | 待实现 |
| P-013 | 预览门（gate） | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/CameraScreen.kt:2966 | iOS/WotaGeiCam/Camera/ | 预览可用性门控 | 编译：可构建；人工：重开中不显示半截旧告警 | 待实现 |
| P-014 | 底栏胶囊带 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/CameraScreen.kt:4206<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/CameraPills.kt:1946 | iOS/WotaGeiCam/UI/ | 底栏参数胶囊（SIZE/FPS/SHUTTER/ISO/EV/WB/ZOOM/FOCUS/BITRATE/LENS/REFLINE/MONITOR/FLASH/STAB/STORAGE/BT/DENOISE/GLOW/TONE/ECHO） | 编译：可构建；人工：各胶囊就近弹层正确 | 待实现 |
| P-015 | 站位线簇/读数 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/CameraScreen.kt:4315<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/CameraScreen.kt:4345 | iOS/WotaGeiCam/UI/ | 站位线圆钮/线数/颜色/上屏比例读数（线数不持久） | 编译：可构建；人工：线数启动为 0，间距/颜色保持 | 待实现 |
| P-016 | 曝光遥测 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/CameraScreen.kt:4380 | iOS/WotaGeiCam/Camera/ | 曝光/波形读数 | 编译：可构建；人工：读数随环境变化 | 待实现 |
| P-017 | 分屏（App 内左右栏） | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/CameraScreen.kt:465<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/CameraScreen.kt:2479<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/CameraScreen.kt:2493 | iOS/WotaGeiCam/Compare/ | **事实**：Android 是 **App 内左右栏**（`SPLIT_SQUEEZE=0.5f`，`transformOrigin=(0f,0.5f)`，录制页整层缩到**左半屏**，**右半屏**给模式面板/实时对比播放；只有 graphicsLayer 变换，布局不重排；录制中锁定不可切分屏；进入时探一次并发双摄）。**非** OS 多窗口。iOS：iPhone 无系统级 Split View；是否实现、及竖上/下横左/右的具体取向为**产品决策，待用户裁决**，本表不写成通过。 | 人工（裁决后）：布局取向与开关行为符合用户决定 | 待实现（平台无等价，需裁决） |
| P-018 | 分屏对比播放器 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/CameraScreen.kt:479<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/CameraScreen.kt:2583 | iOS/WotaGeiCam/Compare/ | 右栏实时对比播放（循环左片）；音频计划 `splitAudioPlanOf`（环境/内录组下标）；内录组因无内录来源不可达 | 人工：右栏循环播放/选轨正确（内录项不可达） | 待实现（平台无等价，需裁决） |

## 浮层/对话框/弹层

| ID | 浮层 | Android 源 | iOS 目标 | 转换说明 | 验证项 | 状态 |
|---|---|---|---|---|---|---|
| P-020 | 音源管理面板 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/dialog/AudioSourcePanel.kt:64 | iOS/WotaGeiCam/Recording/ | 音源页签（环境音/蓝牙）；**内录页签不移植**（平台无等价） | 编译：面板可构建；人工：无内录入口 | 待实现 |
| P-021 | 蓝牙音箱面板 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/dialog/BtSpeakerPanel.kt:82 | iOS/WotaGeiCam/UI/ | 设备列表/电量/连接/设置；iOS 退化为系统音量+路由（电量按可得性） | 编译：面板可构建；人工：路由/音量可用 | 待实现（平台无等价，需裁决） |
| P-022 | 相机对话框组 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/dialog/CameraDialogs.kt:222 | iOS/WotaGeiCam/Camera/ | 相机相关二级对话框 | 编译：可构建；人工：各对话框正常 | 待实现 |
| P-023 | RGB 曲线面板 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/dialog/CurvePopup.kt:100 | iOS/WotaGeiCam/Rendering/ | 曲线编辑器/通道/直方图；Metal 重写 | 编译：可构建；人工：曲线调整生效 | 待实现 |
| P-024 | 媒体访问对话框 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/dialog/MediaAccessDialog.kt:33 | iOS/WotaGeiCam/Library/ | Android「所有文件访问」引导；iOS 换权限引导（文案具体） | 编译：可构建；人工：引导跳转系统设置 | 待实现（平台无等价，需裁决） |
| P-025 | 播放器顶栏 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/player/PlayerScreen.kt:1981 | iOS/WotaGeiCam/Player/ | 顶栏（可折叠） | 编译：可构建；人工：折叠状态保持 | 待实现 |
| P-026 | AB 打点行 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/player/PlayerScreen.kt:1161 | iOS/WotaGeiCam/Player/ | A/B 打点与循环 | 编译：可构建；人工：打点/循环正确 | 待实现 |
| P-027 | 倍速弹层 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/player/PlayerScreen.kt:1251 | iOS/WotaGeiCam/Player/ | 倍速分档 | 编译：可构建；人工：倍速切换生效 | 待实现 |
| P-028 | 剪辑导出弹层 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/player/PlayerScreen.kt:1302 | iOS/WotaGeiCam/Player/ | 入出点/无损 | 编译：可构建；人工：剪辑产出正确 | 待实现 |
| P-029 | 导出效果配置 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/player/PlayerScreen.kt:1483 | iOS/WotaGeiCam/Player/ | 后渲染效果当场配置（不反写设置） | 编译：可构建；人工：效果只作用于本次导出 | 待实现 |
| P-030 | 选轨弹层 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/player/PlayerScreen.kt:1712 | iOS/WotaGeiCam/Player/ | 环境/内录选轨；内录项不可达 | 编译：可构建；人工：单音轨片只亮环境音 | 待实现（平台无等价，需裁决） |
| P-031 | 选轨导出弹层 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/player/PlayerScreen.kt:1758 | iOS/WotaGeiCam/Player/ | 全片选轨导出 | 编译：可构建；人工：环境轨导出正确 | 待实现 |
| P-032 | 抽帧修复弹层 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/player/PlayerScreen.kt:1854 | iOS/WotaGeiCam/ArcRepair/ | 离线修复入口 | 编译：可构建；人工：修复产出正确 | 待实现 |
| P-033 | 缩放手势/进度条 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/player/PlayerScreen.kt:2075<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/player/PlayerScreen.kt:2143 | iOS/WotaGeiCam/Player/ | 捏合缩放/平移、可拖动 seek | 编译：可构建；人工：手势与 seek 生效 | 待实现 |
| P-034 | 对比历史表 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/player/CompareScreen.kt:1161 | iOS/WotaGeiCam/Compare/ | 对比历史（12 条，rightId 去重） | 编译：可构建；人工：历史去重/上限正确 | 待实现 |
| P-035 | 镜像弹层 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/player/CompareScreen.kt:1293 | iOS/WotaGeiCam/Compare/ | 镜像对照 | 编译：可构建；人工：镜像正确 | 待实现 |
| P-036 | 对比同步启动 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/player/CompareScreen.kt:1398 | iOS/WotaGeiCam/Compare/ | 同步启动/域判定 | 编译：可构建；人工：交接不 seek、硬 seek 生效 | 待实现 |
| P-037 | 库顶栏/页签/选择栏 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/media/GalleryScreen.kt:448<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/media/GalleryScreen.kt:532<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/media/GalleryScreen.kt:590 | iOS/WotaGeiCam/Library/ | 库顶栏/页签/多选栏 | 编译：可构建；人工：多选与操作正确 | 待实现 |
| P-038 | 时间线/网格 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/media/GalleryScreen.kt:630<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/media/GalleryScreen.kt:805 | iOS/WotaGeiCam/Library/ | 时间线列表 + 剪辑网格（列数可调） | 编译：可构建；人工：列数保持、时间线归组正确 | 待实现 |
| P-039 | 系列面板 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/media/GalleryScreen.kt:937 | iOS/WotaGeiCam/Library/ | 系列分部面板 | 编译：可构建；人工：系列归组正确 | 待实现 |
| P-040 | 卡片菜单 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/media/GalleryScreen.kt:1034 | iOS/WotaGeiCam/Library/ | 卡片操作菜单 | 编译：可构建；人工：菜单项可用 | 待实现 |
| P-041 | 重命名对话框 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/media/GalleryScreen.kt:1096 | iOS/WotaGeiCam/Library/ | 重命名（同步 sidecar） | 编译：可构建；人工：改名后 sidecar 跟随 | 待实现 |
| P-042 | 标签对话框 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/media/GalleryScreen.kt:1110 | iOS/WotaGeiCam/Library/ | 标签增删 | 编译：可构建；人工：标签重启后保持 | 待实现 |
| P-043 | 全屏浏览 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/media/GalleryScreen.kt:1161 | iOS/WotaGeiCam/Library/ | 全屏单/滑浏览 | 编译：可构建；人工：全屏浏览与返回正常 | 待实现 |
| P-044 | 剪辑选择器 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/media/GalleryScreen.kt:1265 | iOS/WotaGeiCam/Library/ | 从库选片 | 编译：可构建；人工：选片回填正确 | 待实现 |
| P-045 | 缩略图 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/media/GalleryScreen.kt:1346 | iOS/WotaGeiCam/Library/ | 缩略图生成/缓存 | 编译：可构建；人工：缩略图正确、缓存清理 | 待实现 |
| P-046 | HUD 编辑页 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/HudLayoutEditor.kt:189 | iOS/WotaGeiCam/UI/ | 拖放/吸附/分区标签/保存重置 | 编译：可构建；人工：拖放后重启布局保持 | 待实现 |
