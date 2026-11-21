import Foundation

/// 设置持久化的**键名清单**（键名即契约，与 Android `ui/SettingsScreen.kt` 的 `object WotaSettings` 逐字对齐）。
///
/// 两类持久化，iOS 侧同样分开、不合并：
/// 1. **入档白名单（运行参数）**：`WotaParamsPersist.paramsKeys`（对应 Android `ParamsPersist.KEYS`）。
///    冷启 `WotaParamsRestore.restore` 读键 → 坏值回默认/跳过 → 写参数快照；写盘走
///    `WotaParamsPersistSession.flush()` 的「会话影子 + 只写差异」纪律。
/// 2. **直写 UI 设置**：设置页直接落盘的纯 UI 项（方向/动画/亮度/HUD/文本/诊断等）。
///
/// iOS 用 `UserDefaults` 承载，白名单 / 默认 / 排除逐条对齐 Android。
/// **AI 站位键（不声明、不读写、也不列名）不在此列，也不提供任何占位接口。**
public enum WotaSettingsKeys {

    /// UserDefaults 套件名（对齐 Android `WotaSettings.PREFS = "wota_settings"`）。
    public static let prefsSuiteName = "wota_settings"

    // MARK: - 入档白名单键（对应 Android ParamsPersist.KEYS 的 34 键）

    public static let defaultFps = "default_fps"
    public static let defaultShutterNs = "default_shutter_ns"
    public static let defaultBitrate = "default_bitrate"
    public static let defaultRefLines = "default_ref_lines"
    public static let levelEnabled = "level_enabled"
    public static let defaultIso = "default_iso"
    public static let defaultAeMode = "default_ae_mode"
    public static let defaultEv = "default_ev"
    public static let defaultWbPreset = "default_wb_preset"
    public static let defaultWbKelvin = "default_wb_kelvin"
    public static let defaultWbTint = "default_wb_tint"
    public static let defaultZoom = "default_zoom"
    public static let defaultFlash = "default_flash"
    public static let defaultStabilize = "default_stabilize"
    public static let defaultAfMode = "default_af_mode"
    public static let defaultMfDiopter = "default_mf_diopter"
    public static let defaultSize = "default_size"
    public static let defaultFrameEffect = "default_frame_effect"
    public static let defaultZebraThreshold = "default_zebra_threshold"
    public static let defaultZebraDensity = "default_zebra_density"
    public static let defaultPeakingStrength = "default_peaking_strength"
    public static let defaultPeakingColor = "default_peaking_color"
    public static let defaultLens = "default_lens"
    public static let defaultArcMergeDeadband = "default_arc_merge_deadband"
    public static let denoiseAudio = "denoise_audio"
    public static let denoiseVideo = "denoise_video"
    public static let correctionAuto = "correction_auto"
    public static let correctionManualNs = "correction_manual_ns"
    public static let defaultMarkerSpread = "default_marker_spread"
    public static let defaultMarkerColor = "default_marker_color"
    public static let glow = "glow_params"
    public static let tone = "tone_params"
    public static let displayScale = "display_scale"
    public static let echo = "echo_params"

    // MARK: - 直写 UI 设置键

    public static let motionMode = "motion_mode"
    public static let defaultSampleRate = "default_sample_rate"
    public static let defaultRenderMode = "default_render_mode"
    public static let defaultAudioMuted = "default_audio_muted"
    public static let uiOrientation = "ui_orientation"
    public static let cameraBrightenDelaySec = "camera_brighten_delay_sec"
    public static let playerBrightenRampTenths = "player_brighten_ramp_tenths"
    public static let playerTopBarCollapsed = "player_top_bar_collapsed"
    public static let hudItems = "hud_items"
    public static let hudPills = "hud_pills"
    public static let hudPillsSchema = "hud_pills_schema"
    public static let hudLayout = "hud_layout"
    public static let hudFrostBlur = "hud_frost_blur"
    public static let sliderContinuous = "slider_continuous"
    public static let levelBuzzEnabled = "level_buzz_enabled"
    public static let compareTickMs = "compare_tick_ms"
    public static let textScaleCamera = "text_scale_camera"
    public static let textScaleSettings = "text_scale_settings"
    public static let textScaleDialog = "text_scale_dialog"
    public static let galleryColumns = "gallery_columns"
    public static let curvePanelSideDp = "curve_panel_side_dp"
    public static let arcConvertMode = "arc_convert_mode"
    public static let curveStack = "curve_stack"
    public static let diagEnabled = "diag_enabled"
    public static let diagVerbose = "diag_verbose"

    // MARK: - 白名单（入档参数，34 项）

    /// 入档白名单，**顺序与 Android `ParamsPersist.KEYS` 逐条一致**（顺序即契约）。
    ///
    /// 源实测：`Android/.../ui/ParamsPersist.kt:56` 的 `KEYS` 是 **34 项**。
    /// 注意 `ParamsPersist.kt` 的 KDoc 注释写的是 32 项（历史遗留），与本表不符——**以代码为准 = 34**。
    public static let paramsKeys: [String] = [
        defaultFps,
        defaultShutterNs,
        defaultBitrate,
        defaultRefLines,
        levelEnabled,
        defaultIso,
        defaultAeMode,
        defaultEv,
        defaultWbPreset,
        defaultWbKelvin,
        defaultWbTint,
        defaultZoom,
        defaultFlash,
        defaultStabilize,
        defaultAfMode,
        defaultMfDiopter,
        defaultSize,
        defaultFrameEffect,
        defaultZebraThreshold,
        defaultZebraDensity,
        defaultPeakingStrength,
        defaultPeakingColor,
        defaultLens,
        defaultArcMergeDeadband,
        denoiseAudio,
        denoiseVideo,
        correctionAuto,
        correctionManualNs,
        defaultMarkerSpread,
        defaultMarkerColor,
        glow,
        tone,
        displayScale,
        echo,
    ]

    // MARK: - 备份白名单（对齐 Android PortableUserData.SETTING_KEYS，去掉 AI 键）

    /// 可随备份迁移的设置键（对齐 Android `backup/PortableUserData.kt` 的 `SETTING_KEYS`，
    /// **剔除全部 AI 站位键**：本实现不声明、不读写、不列名这些键）。
    ///
    /// 新增设置须显式评估能否迁移；`compare_history` 之类的 URI 一律不带走。
    public static let backupKeys: Set<String> = [
        motionMode, defaultFps, defaultShutterNs, defaultBitrate, defaultSampleRate,
        defaultRenderMode, defaultAudioMuted, defaultIso, defaultAeMode, defaultEv,
        defaultWbPreset, defaultWbKelvin, defaultWbTint, defaultZoom, defaultFlash,
        defaultStabilize, defaultAfMode, defaultMfDiopter, defaultSize, defaultFrameEffect,
        defaultZebraThreshold, defaultZebraDensity, defaultPeakingStrength, defaultPeakingColor,
        defaultLens, defaultArcMergeDeadband, denoiseAudio, denoiseVideo,
        correctionAuto, correctionManualNs, defaultMarkerSpread, defaultMarkerColor,
        displayScale, glow, echo, defaultRefLines, levelEnabled,
        levelBuzzEnabled, hudItems, hudPills, hudPillsSchema, uiOrientation, hudLayout,
        textScaleCamera, textScaleSettings, textScaleDialog, galleryColumns,
        cameraBrightenDelaySec, playerBrightenRampTenths, playerTopBarCollapsed,
        curvePanelSideDp, tone, sliderContinuous, arcConvertMode, hudFrostBlur,
        curveStack, compareTickMs,
    ]

    /// 入档白名单键的集合视图（备份类型表与守卫用）。
    public static let paramsKeySet: Set<String> = Set(paramsKeys)
}
