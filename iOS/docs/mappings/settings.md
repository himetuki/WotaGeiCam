# 设置映射（Android 非 AI 设置键 → iOS）

基线：`Android/` 只读参考，提交 `7394187`。设置真源：`ui/SettingsScreen.kt` 的
`object WotaSettings`（键常量与默认值）与 `res/values/strings_settings.xml`（分组标签）。

## 两类持久化（iOS 必须逐类对齐，不可合并）

1. **入档白名单（运行参数）**：`ui/ParamsPersist.kt` 的 `KEYS`（34 键，`ParamsPersist.kt:58`）。
   冷启 `restore` 读键 → 坏值回默认/跳过 → 写回参数总线；用户动作完成点 `flush`，
   **只写与本次会话影子不同的字段**。iOS 用 `UserDefaults` 承载，白名单/默认/排除逐条对齐。
2. **直写 UI 设置**：设置页直接 `prefs.edit()` 落盘的纯 UI 项（方向/动画/亮度/HUD/文本/诊断等）。
   iOS 同样 `UserDefaults` 直写。

## 状态词表

`待实现` ｜ `待实现（平台无等价，需裁决）` ｜ `已实现（A2-S 骨架）` ｜ `排除（AI，不移植）`。

## 入档白名单键（ParamsPersist.KEYS，34 项）

| ID | 键 | 默认值 | 分组 | Android 源 | iOS 目标 | 状态 |
|---|---|---|---|---|---|---|
| S-001 | default_fps | 25 | 开机初始值 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:132<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:374 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-002 | default_shutter_ns | 40_000_000 (1/25s) | 开机初始值 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:133<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:375 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-003 | default_bitrate | 10_000_000 (10 Mbps) | 开机初始值 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:134<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:376 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-004 | default_ref_lines | 0 | 参考线默认 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:231<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:416 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-005 | level_enabled | true | 取景辅助 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:232<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:417 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-006 | default_iso | 100 | 开机初始值 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:162 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-007 | default_ae_mode | AUTO | 开机初始值 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:163 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-008 | default_ev | 0 | 开机初始值 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:164 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-009 | default_wb_preset | AUTO | 开机初始值 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:165 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-010 | default_wb_kelvin | 见 restore（2000–10000 K 域） | 开机初始值 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:166 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-011 | default_wb_tint | 0 (−50..50 域) | 开机初始值 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:167 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-012 | default_zoom | 1.0× | 开机初始值 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:168 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-013 | default_flash | OFF | 开机初始值 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:169 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-014 | default_stabilize | OFF | 开机初始值 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:170 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-015 | default_af_mode | 见实现（AF 模式枚举） | 开机初始值 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:171 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-016 | default_mf_diopter | 见实现 | 开机初始值 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:172 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-017 | default_size | 见实现（分辨率枚举） | 开机初始值 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:173 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-018 | default_frame_effect | 见实现 | 开机初始值 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:174 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-019 | default_zebra_threshold | 见实现 | 开机初始值 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:175 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-020 | default_zebra_density | 见实现 | 开机初始值 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:176 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-021 | default_peaking_strength | 见实现 | 开机初始值 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:177 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-022 | default_peaking_color | 见实现 | 开机初始值 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:178 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-023 | default_lens | 见实现（镜头选择） | 开机初始值 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:179 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-024 | default_arc_merge_deadband | 12（域 0..32） | 开机初始值 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:185<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:382 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-025 | denoise_audio | OFF（坏串回 OFF） | 取景辅助 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:188 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-026 | denoise_video | OFF（坏串回 OFF） | 取景辅助 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:189 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-027 | correction_auto | true（自动曝光纠偏开关） | 取景辅助 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:195 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-028 | correction_manual_ns | 见实现 | 取景辅助 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:196 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-029 | default_marker_spread | 见实现（站位线间距） | 取景辅助 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:204 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-030 | default_marker_color | 见实现（站位线颜色） | 取景辅助 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:205 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-031 | glow_params | 整份参数（缺键回出厂=关） | 取景辅助 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:218 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-032 | tone_params | 整份参数（复合串键） | 取景辅助 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:300 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-033 | display_scale | ORIG（上屏渲染比例枚举名，缺键回出厂） | 取景辅助 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:211 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-034 | echo_params | 整份参数（缺键回出厂=关） | 取景辅助 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:224 | iOS/WotaGeiCam/Settings/ | 待实现 |

> 白名单内 **markerCount（站位线线数）刻意不入档**（每次从 0 起步）；`glow_params`/`echo_params`/`tone_params`
> 是复合串键，坏串整份回默认。iOS 侧逐键保持「坏值回默认，绝不抛」的容错口径。

## 直写 UI 设置键（设置页直接落盘）

| ID | 键 | 默认值 | 分组 | Android 源 | iOS 目标 | 状态 |
|---|---|---|---|---|---|---|
| S-040 | motion_mode | FLUENT | 动画风格 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:131<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:358 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-041 | default_sample_rate | 48_000 Hz | 开机初始值 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:135<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:377 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-042 | default_render_mode | GPU | 开机初始值 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:136<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:409 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-043 | default_audio_muted | false | 开机初始值 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:138<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:393 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-044 | ui_orientation | LANDSCAPE（默认）/ PORTRAIT | 界面方向 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:249 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-045 | camera_brighten_delay_sec | 1（秒） | 亮度 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:272 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-046 | player_brighten_ramp_tenths | 10（十分之一档） | 亮度 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:278 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-047 | player_top_bar_collapsed | 见实现 | 界面 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:284 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-048 | hud_items | HudItem.DEFAULT_MASK | 取景器常驻功能 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:234<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:419 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-049 | hud_pills | CamPill.DEFAULT_MASK（带版本迁移） | 取景器常驻功能 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:236 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-050 | hud_pills_schema | 0 | 取景器常驻功能 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:243 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-051 | hud_layout | HudLayoutTable.CURRENT_VERSION=2 编码串 | 取景器常驻功能 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:259 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-052 | hud_frost_blur | 见实现（霜板模糊开关） | 取景器常驻功能 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:326 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-053 | slider_continuous | 空集（全部吸附） | 滑杆吸附 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:309<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:435 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-054 | level_buzz_enabled | true | 取景辅助 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:233<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:418 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-055 | compare_tick_ms | 200（域 100..1000） | 对比播放 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:339<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:340 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-056 | text_scale_camera | 100（%） | 文本高度 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:260 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-057 | text_scale_settings | 100（%） | 文本高度 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:261 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-058 | text_scale_dialog | 100（%） | 文本高度 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:262 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-059 | gallery_columns | 2（列） | 界面 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:263 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-060 | curve_panel_side_dp | 见实现（曲线面板侧宽） | 取景辅助 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:292 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-061 | arc_convert_mode | MEND | 取景辅助 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:316<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:367 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-062 | curve_stack | 曲线堆栈串 | 取景辅助 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:329 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-063 | diag_enabled | false | 关于（诊断） | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:227<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:396 | iOS/WotaGeiCam/Settings/ | 待实现 |
| S-064 | diag_verbose | false | 关于（诊断） | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:230<br>Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/SettingsScreen.kt:404 | iOS/WotaGeiCam/Settings/ | 待实现 |

## 平台无等价 / 需裁决的设置相关项

| ID | 项 | Android 源 | 说明 | 状态 |
|---|---|---|---|---|
| S-070 | 所有文件访问（MANAGE_EXTERNAL_STORAGE） | Android/WotaGeiCamApp/app/src/main/res/values/strings_settings.xml:121 | Android 设置页有「所有文件访问」区与授权引导。iOS 无对应全局存储权限，改为 App 自有目录 + 显式 Photos 动作。 | 待实现（平台无等价，需裁决） |
| S-071 | 权限状态区（相机/麦克风/通知/蓝牙/存储） | Android/WotaGeiCamApp/app/src/main/res/values/strings_settings.xml:74 | Android 逐权限状态展示与引导。iOS 按系统权限项重建对应入口与引导（文案须具体，禁占位）。 | 待实现 |

## 明确排除（AI 设置键）

以下键属于 AI 站位，iOS **不实现、不保留占位**（`排除（AI，不移植）`）：

| 键 | Android 源 | 状态 |
|---|---|---|
| stance_mode | Android/WotaGeiCamApp/app/src/full/java/com/wotagei/cam/stance/StanceController.kt:129 | 排除（AI，不移植） |
| stance_n | Android/WotaGeiCamApp/app/src/full/java/com/wotagei/cam/stance/StanceController.kt:129 | 排除（AI，不移植） |
| stance_beta | Android/WotaGeiCamApp/app/src/full/java/com/wotagei/cam/stance/StanceController.kt:129 | 排除（AI，不移植） |
| stance_brighten | Android/WotaGeiCamApp/app/src/full/java/com/wotagei/cam/stance/StanceController.kt:129 | 排除（AI，不移植） |
| stance_line_interval_ms | Android/WotaGeiCamApp/app/src/full/java/com/wotagei/cam/stance/StanceController.kt:129 | 排除（AI，不移植） |
| stance_poll_interval_ms | Android/WotaGeiCamApp/app/src/full/java/com/wotagei/cam/stance/StanceController.kt:129 | 排除（AI，不移植） |

## 排除而不入档的项（防后人补齐）

以下为 Android **有意不持久化**的项，iOS 不得添加等价持久化：

| 项 | 理由（Android 源 KDoc） | Android 源 |
|---|---|---|
| renderMode / sampleRate（录制页侧） | 设置页独占；录制页那两处是「※ 档自动切 GPU」的自动降级，入档会把降级写成用户默认 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/ParamsPersist.kt:43 |
| audioEnabled（环境音/内录开关） | 音源双开关任何路径都不写 prefs、不跨进程恢复 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/ParamsPersist.kt:44 |
| focusPoint | 瞬时点按态，冷启不钉死对焦框 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/ParamsPersist.kt:45 |
| curve / arc_convert_mode | 已有各自专属直写链，避免第二写入方 | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/ParamsPersist.kt:46 |
| markerCount（站位线线数） | 刻意不入档，每次从 0 起步（启动取景器干净无线） | Android/WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/ParamsPersist.kt:47 |

## 设置分组（iOS 需给出等价分区，用原生 Form/Section）

源：`Android/WotaGeiCamApp/app/src/main/res/values/strings_settings.xml`

| 分组（中文） | Android 源 | 状态 |
|---|---|---|
| 开机初始值 | Android/WotaGeiCamApp/app/src/main/res/values/strings_settings.xml:7 | 待实现 |
| 界面方向 | Android/WotaGeiCamApp/app/src/main/res/values/strings_settings.xml:19 | 待实现 |
| 动画风格 | Android/WotaGeiCamApp/app/src/main/res/values/strings_settings.xml:26 | 待实现 |
| 亮度 | Android/WotaGeiCamApp/app/src/main/res/values/strings_settings.xml:34 | 待实现 |
| 取景器常驻功能 | Android/WotaGeiCamApp/app/src/main/res/values/strings_settings.xml:44 | 待实现 |
| 滑杆吸附 | Android/WotaGeiCamApp/app/src/main/res/values/strings_settings.xml:48 | 待实现 |
| 参考线默认 | Android/WotaGeiCamApp/app/src/main/res/values/strings_settings.xml:52 | 待实现 |
| 取景辅助 | Android/WotaGeiCamApp/app/src/main/res/values/strings_settings.xml:56 | 待实现 |
| 对比播放 | Android/WotaGeiCamApp/app/src/main/res/values/strings_settings.xml:62 | 待实现 |
| 文本高度 | Android/WotaGeiCamApp/app/src/main/res/values/strings_settings.xml:67 | 待实现 |
| 权限状态 | Android/WotaGeiCamApp/app/src/main/res/values/strings_settings.xml:74 | 待实现 |
| 关于 | Android/WotaGeiCamApp/app/src/main/res/values/strings_settings.xml:91 | 待实现 |
| 所有文件访问 | Android/WotaGeiCamApp/app/src/main/res/values/strings_settings.xml:121 | 待实现（平台无等价，需裁决） |
