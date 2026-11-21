import Foundation

/// 设置存储：`UserDefaults` 之上的一层**类型化访问器**。
///
/// 与 Android 的对应关系：
/// - Android `SharedPreferences("wota_settings")` → 这里默认用 `UserDefaults(suiteName: "wota_settings")`
///   （独立域，便于与 App 其它默认值隔离；取不到时退回 `UserDefaults.standard`）。
/// - 入档白名单（运行参数）的 getter 逐条复刻 Android `ParamsPersist.restore` 的「坏值回默认/跳过 + 产品域钳制」；
///   直写 UI 设置的 getter 复刻 `object WotaSettings` 的 getter 口径。
///
/// 写入分两类：
/// - 低频直写设置（方向/方向/动画等）走 `setXxx` 并可在动作完成点 `flush()`（同步落盘，
///   对齐 Android 对 `ui_orientation` 用 `commit()` 而非 `apply()` 的裁决）；
/// - 入档白名单的参数写盘由 `WotaParamsPersistSession.flush()` 统一「只写差异」。
public final class WotaSettingsStore {

    public let defaults: UserDefaults

    public init(defaults: UserDefaults) {
        self.defaults = defaults
    }

    /// 默认存储（套件名 `wota_settings`）。
    public static func standard() -> WotaSettingsStore {
        let defaults = UserDefaults(suiteName: WotaSettingsKeys.prefsSuiteName) ?? UserDefaults.standard
        return WotaSettingsStore(defaults: defaults)
    }

    /// 同步落盘（低频动作完成点调用；对齐 Android 的 `commit()` 口径）。
    @discardableResult
    public func flush() -> Bool {
        return defaults.synchronize()
    }

    // MARK: - 底层读写

    private func storedInt(_ key: String, _ fallback: Int) -> Int {
        guard defaults.object(forKey: key) != nil else { return fallback }
        return defaults.integer(forKey: key)
    }

    private func storedInt64(_ key: String, _ fallback: Int64) -> Int64 {
        guard let value = defaults.object(forKey: key) else { return fallback }
        if let number = value as? NSNumber { return number.int64Value }
        return fallback
    }

    private func storedFloat(_ key: String, _ fallback: Float) -> Float {
        guard defaults.object(forKey: key) != nil else { return fallback }
        return defaults.float(forKey: key)
    }

    private func storedBool(_ key: String, _ fallback: Bool) -> Bool {
        guard defaults.object(forKey: key) != nil else { return fallback }
        return defaults.bool(forKey: key)
    }

    private func storedString(_ key: String) -> String? {
        return defaults.string(forKey: key)
    }

    public func setInt(_ value: Int, _ key: String) { defaults.set(value, forKey: key) }
    public func setInt64(_ value: Int64, _ key: String) { defaults.set(NSNumber(value: value), forKey: key) }
    public func setFloat(_ value: Float, _ key: String) { defaults.set(value, forKey: key) }
    public func setBool(_ value: Bool, _ key: String) { defaults.set(value, forKey: key) }
    public func setString(_ value: String?, _ key: String) {
        if let value = value {
            defaults.set(value, forKey: key)
        } else {
            defaults.removeObject(forKey: key)
        }
    }

    /// 原始值读取（备份导出用；返回可迁移的 `Bool/Int/Int64/Float/String`）。
    public func rawValue(forKey key: String) -> WotaBackupValue? {
        guard let object = defaults.object(forKey: key) else { return nil }
        if let number = object as? NSNumber {
            let type = String(cString: number.objCType)
            if type == "c" || type == "B" { return .bool(number.boolValue) }
            if type == "f" { return .float(number.floatValue) }
            if type == "q" || type == "l" || type == "i" || type == "s" {
                return .int(number.intValue)
            }
            return .int(number.intValue)
        }
        if let string = object as? String { return .string(string) }
        return nil
    }

    /// 写回一个可迁移值（备份恢复用；不覆盖已存在键由调用方保证）。
    public func setRawValue(_ value: WotaBackupValue, forKey key: String) {
        switch value {
        case .bool(let v): defaults.set(v, forKey: key)
        case .int(let v): defaults.set(v, forKey: key)
        case .int64(let v): defaults.set(NSNumber(value: v), forKey: key)
        case .float(let v): defaults.set(v, forKey: key)
        case .string(let v): defaults.set(v, forKey: key)
        }
    }

    // MARK: - 入档白名单（34 键）getter：坏值回默认 / 跳过 + 产品域钳制

    // ① 帧率：产品档位区间钳（与 Android restore 同口径）
    public func defaultFps() -> Int {
        let raw = storedInt(WotaSettingsKeys.defaultFps, 25)
        guard let lo = WotaTiers.fps.first, let hi = WotaTiers.fps.last else { return raw }
        return Swift.min(Swift.max(raw, lo), hi)
    }

    // ② 快门（ns）：>0 才套；坏值回默认 40_000_000
    public func defaultShutterNs() -> Int {
        let raw = storedInt(WotaSettingsKeys.defaultShutterNs, 40_000_000)
        return raw > 0 ? raw : 40_000_000
    }

    // ③ 码率（bps，不做钳制）
    public func defaultBitrate() -> Int { return storedInt(WotaSettingsKeys.defaultBitrate, 10_000_000) }

    // ④ 参考线掩码
    public func defaultRefLines() -> Int { return storedInt(WotaSettingsKeys.defaultRefLines, 0) }

    // ⑤ 水平仪开关
    public func levelEnabled() -> Bool { return storedBool(WotaSettingsKeys.levelEnabled, true) }

    // ⑥ ISO：>0 才套
    public func defaultIso() -> Int {
        let raw = storedInt(WotaSettingsKeys.defaultIso, 100)
        return raw > 0 ? raw : 100
    }

    // ⑦ AE 模式：LOCK 与坏串一律归 AUTO
    public func defaultAeMode() -> AeMode {
        return AeMode.parse(name: storedString(WotaSettingsKeys.defaultAeMode)).persisted
    }

    // ⑧ EV（设备域由相机层钳制）
    public func defaultEv() -> Int { return storedInt(WotaSettingsKeys.defaultEv, 0) }

    // ⑨ 白平衡三件套
    public func defaultWbPreset() -> WbPreset {
        return WbPreset.parse(name: storedString(WotaSettingsKeys.defaultWbPreset))
    }
    public func defaultWbKelvin() -> Int { return storedInt(WotaSettingsKeys.defaultWbKelvin, 5500) }
    public func defaultWbTint() -> Int { return storedInt(WotaSettingsKeys.defaultWbTint, 0) }

    // ⑩ 变焦：>0 才套
    public func defaultZoom() -> Float {
        let raw = storedFloat(WotaSettingsKeys.defaultZoom, 1)
        return raw > 0 ? raw : 1
    }

    // ⑪ 闪光 / 防抖
    public func defaultFlash() -> Flash { return Flash.parse(name: storedString(WotaSettingsKeys.defaultFlash)) }
    public func defaultStabilize() -> Stabilize {
        return Stabilize.parse(name: storedString(WotaSettingsKeys.defaultStabilize))
    }

    // ⑫ AF 模式与屈光度
    public func defaultAfMode() -> AfMode {
        return AfMode.parse(name: storedString(WotaSettingsKeys.defaultAfMode))
    }
    public func defaultMfDiopter() -> Float {
        let raw = storedFloat(WotaSettingsKeys.defaultMfDiopter, 0)
        return raw < 0 ? 0 : raw
    }

    // ⑬ 分辨率：坏串返回 nil（调用方跳过、保持出厂默认）
    public func defaultSize() -> WotaVideoSize? {
        return WotaVideoSize.parse(storedString(WotaSettingsKeys.defaultSize))
    }

    // ⑭ 监看一族（产品域 sanity）
    public func defaultFrameEffect() -> FrameEffect {
        return FrameEffect.parse(name: storedString(WotaSettingsKeys.defaultFrameEffect))
    }
    public func defaultZebraThreshold() -> Float {
        let raw = storedFloat(WotaSettingsKeys.defaultZebraThreshold, 0.8)
        return Swift.min(Swift.max(raw, 0.6), 1)
    }
    public func defaultZebraDensity() -> Int {
        let raw = storedInt(WotaSettingsKeys.defaultZebraDensity, 2)
        return Swift.min(Swift.max(raw, 1), 3)
    }
    public func defaultPeakingStrength() -> Float {
        let raw = storedFloat(WotaSettingsKeys.defaultPeakingStrength, 0.6)
        return Swift.min(Swift.max(raw, 0), 1)
    }
    public func defaultPeakingColor() -> PeakingColor {
        return PeakingColor.parse(name: storedString(WotaSettingsKeys.defaultPeakingColor))
    }

    // ⑮ 镜头
    public func defaultLens() -> LensType { return LensType.parse(name: storedString(WotaSettingsKeys.defaultLens)) }

    // ⑯ 补弧差分死区：越界钳回 0..32
    public func defaultArcMergeDeadband() -> Int {
        return ArcMergeDeadband.clamp(storedInt(WotaSettingsKeys.defaultArcMergeDeadband, ArcMergeDeadband.default))
    }

    // ⑰ 降噪两项：坏串/缺键一律回 OFF
    public func denoiseAudio() -> AudioDenoise {
        return AudioDenoise.parse(name: storedString(WotaSettingsKeys.denoiseAudio))
    }
    public func denoiseVideo() -> VideoDenoise {
        return VideoDenoise.parse(name: storedString(WotaSettingsKeys.denoiseVideo))
    }

    // ⑱ 站位线：只恢复间距与颜色（线数恒停在出厂 0）
    public func defaultMarkerSpread() -> Float {
        return MarkerLines.clampSpread(storedFloat(WotaSettingsKeys.defaultMarkerSpread, MarkerLines.defaultSpread))
    }
    public func defaultMarkerColor() -> MarkerLineColor {
        return MarkerLineColor.parse(name: storedString(WotaSettingsKeys.defaultMarkerColor))
    }

    // ⑳ 复合串键（坏串整份回默认）
    public func glowParams() -> GlowParams {
        return WotaSettingsCodecs.decodeGlow(storedString(WotaSettingsKeys.glow))
    }
    public func echoParams() -> EchoParams {
        return WotaSettingsCodecs.decodeEcho(storedString(WotaSettingsKeys.echo))
    }
    public func toneParams() -> ToneParams {
        return WotaSettingsCodecs.decodeTone(storedString(WotaSettingsKeys.tone))
    }

    // ㉑ 上屏档
    public func displayScale() -> DisplayScale {
        return DisplayScale.parse(name: storedString(WotaSettingsKeys.displayScale))
    }

    // ㉓ 等号档曝光纠偏
    public func correctionAuto() -> Bool { return storedBool(WotaSettingsKeys.correctionAuto, true) }
    public func correctionManualNs() -> Int64 {
        return ExposureCorrection.clampManualNs(storedInt64(WotaSettingsKeys.correctionManualNs, 0))
    }

    // MARK: - 直写 UI 设置 getter

    public func motionMode() -> MotionMode {
        return MotionMode.parse(name: storedString(WotaSettingsKeys.motionMode))
    }
    public func defaultSampleRate() -> Int {
        return WotaTiers.clampSampleRate(
            storedInt(WotaSettingsKeys.defaultSampleRate, WotaTiers.defaultSampleRate)
        )
    }
    public func defaultRenderMode() -> RenderMode {
        return storedString(WotaSettingsKeys.defaultRenderMode) == RenderMode.direct.rawValue ? .direct : .gpu
    }
    public func defaultAudioMuted() -> Bool { return storedBool(WotaSettingsKeys.defaultAudioMuted, false) }

    public func uiOrientation() -> UIOrientation {
        return UIOrientation.fromPersistValue(storedString(WotaSettingsKeys.uiOrientation))
    }

    public func levelBuzzEnabled() -> Bool { return storedBool(WotaSettingsKeys.levelBuzzEnabled, true) }

    public func hudItemsMask() -> Int {
        return storedInt(WotaSettingsKeys.hudItems, HudItem.defaultMask)
    }

    /// 显隐掩码（读）：缺键 = 从没动过 ⇒ 全开；版本落后 ⇒ 只补那一版之后新加的位（纯读，不写回）。
    public func hudPillsMask() -> Int {
        let raw = storedInt(WotaSettingsKeys.hudPills, CamPill.defaultMask)
        let schema = storedInt(WotaSettingsKeys.hudPillsSchema, 0)
        return schema >= CamPill.maskSchema ? raw : CamPill.migrateMask(raw, fromSchema: schema)
    }
    public func hudPillsSchema() -> Int { return storedInt(WotaSettingsKeys.hudPillsSchema, 0) }

    /// 控件位置表（版本化复合串）。**iOS 侧暂作为不透明串原样保留**：
    /// Android `HudLayoutTable` 是一个 2000+ 行的独立编解码器，本任务只保证「不丢、能原样回写」，
    /// 结构化编辑随 UI 任务（G）另立。缺键返回 nil（= 用默认布局）。
    public func hudLayoutRaw() -> String? { return storedString(WotaSettingsKeys.hudLayout) }

    public func hudFrostBlur() -> Bool { return storedBool(WotaSettingsKeys.hudFrostBlur, false) }

    public func continuousSliders() -> Set<SliderKey> {
        return WotaSettingsCodecs.decodeContinuousSliders(storedString(WotaSettingsKeys.sliderContinuous))
    }

    public func compareTickMs() -> Int {
        let raw = storedInt(WotaSettingsKeys.compareTickMs, 200)
        return Swift.min(Swift.max(raw, 100), 1000)
    }

    private func textScale(_ key: String) -> Int {
        let raw = storedInt(key, 100)
        return Swift.min(Swift.max(raw, 80), 120)
    }
    public func textScaleCamera() -> Int { return textScale(WotaSettingsKeys.textScaleCamera) }
    public func textScaleSettings() -> Int { return textScale(WotaSettingsKeys.textScaleSettings) }
    public func textScaleDialog() -> Int { return textScale(WotaSettingsKeys.textScaleDialog) }

    public func galleryColumns() -> Int {
        let raw = storedInt(WotaSettingsKeys.galleryColumns, 2)
        return [2, 3, 4, 5].contains(raw) ? raw : 2
    }

    /// 曲线面板边长（dp）：坏值/缺键回 200（设计档）。**读取侧只钳下限**——上限是组合期才有
    /// 的输入（弹窗可用区），由组合期带着 maxSide 二次钳（对齐 Android `CurvePanelSize.clamp`）。
    public func curvePanelSideDp() -> Float {
        let raw = storedFloat(WotaSettingsKeys.curvePanelSideDp, 200)
        if !raw.isFinite || raw < 0 { return 200 }
        return raw
    }

    public func arcConvertMode() -> ArcConvertMode {
        return ArcConvertMode.parse(name: storedString(WotaSettingsKeys.arcConvertMode))
    }
    public func curveStack() -> CurveStack {
        return WotaSettingsCodecs.decodeCurveStack(storedString(WotaSettingsKeys.curveStack))
    }

    public func cameraBrightenDelaySec() -> Int {
        let raw = storedInt(WotaSettingsKeys.cameraBrightenDelaySec, 1)
        return Swift.min(Swift.max(raw, 0), 10)
    }
    public func playerBrightenRampTenths() -> Int {
        let raw = storedInt(WotaSettingsKeys.playerBrightenRampTenths, 10)
        return Swift.min(Swift.max(raw, 0), 30)
    }
    public func playerTopBarCollapsed() -> Bool {
        return storedBool(WotaSettingsKeys.playerTopBarCollapsed, false)
    }

    public func diagEnabled() -> Bool { return storedBool(WotaSettingsKeys.diagEnabled, false) }
    public func diagVerbose() -> Bool { return storedBool(WotaSettingsKeys.diagVerbose, false) }

    // MARK: - 写入（入档白名单；只由 ParamsPersistSession.flush 调用，参数已按产品域归一）

    public func setDefaultFps(_ value: Int) { setInt(value, WotaSettingsKeys.defaultFps) }
    public func setDefaultShutterNs(_ value: Int) { setInt(value, WotaSettingsKeys.defaultShutterNs) }
    public func setDefaultBitrate(_ value: Int) { setInt(value, WotaSettingsKeys.defaultBitrate) }
    public func setDefaultRefLines(_ value: Int) { setInt(value, WotaSettingsKeys.defaultRefLines) }
    public func setLevelEnabled(_ value: Bool) { setBool(value, WotaSettingsKeys.levelEnabled) }
    public func setDefaultIso(_ value: Int) { setInt(value, WotaSettingsKeys.defaultIso) }
    public func setDefaultAeMode(_ value: AeMode) { setString(value.persisted.rawValue, WotaSettingsKeys.defaultAeMode) }
    public func setDefaultEv(_ value: Int) { setInt(value, WotaSettingsKeys.defaultEv) }
    public func setDefaultWbPreset(_ value: WbPreset) { setString(value.rawValue, WotaSettingsKeys.defaultWbPreset) }
    public func setDefaultWbKelvin(_ value: Int) { setInt(value, WotaSettingsKeys.defaultWbKelvin) }
    public func setDefaultWbTint(_ value: Int) { setInt(value, WotaSettingsKeys.defaultWbTint) }
    public func setDefaultZoom(_ value: Float) { setFloat(value, WotaSettingsKeys.defaultZoom) }
    public func setDefaultFlash(_ value: Flash) { setString(value.rawValue, WotaSettingsKeys.defaultFlash) }
    public func setDefaultStabilize(_ value: Stabilize) { setString(value.rawValue, WotaSettingsKeys.defaultStabilize) }
    public func setDefaultAfMode(_ value: AfMode) { setString(value.rawValue, WotaSettingsKeys.defaultAfMode) }
    public func setDefaultMfDiopter(_ value: Float) { setFloat(value, WotaSettingsKeys.defaultMfDiopter) }
    public func setDefaultSize(_ value: WotaVideoSize) { setString(value.encoded, WotaSettingsKeys.defaultSize) }
    public func setDefaultFrameEffect(_ value: FrameEffect) { setString(value.rawValue, WotaSettingsKeys.defaultFrameEffect) }
    public func setDefaultZebraThreshold(_ value: Float) { setFloat(value, WotaSettingsKeys.defaultZebraThreshold) }
    public func setDefaultZebraDensity(_ value: Int) { setInt(value, WotaSettingsKeys.defaultZebraDensity) }
    public func setDefaultPeakingStrength(_ value: Float) { setFloat(value, WotaSettingsKeys.defaultPeakingStrength) }
    public func setDefaultPeakingColor(_ value: PeakingColor) { setString(value.rawValue, WotaSettingsKeys.defaultPeakingColor) }
    public func setDefaultLens(_ value: LensType) { setString(value.rawValue, WotaSettingsKeys.defaultLens) }
    public func setDefaultArcMergeDeadband(_ value: Int) { setInt(value, WotaSettingsKeys.defaultArcMergeDeadband) }
    public func setDenoiseAudio(_ value: AudioDenoise) { setString(value.rawValue, WotaSettingsKeys.denoiseAudio) }
    public func setDenoiseVideo(_ value: VideoDenoise) { setString(value.rawValue, WotaSettingsKeys.denoiseVideo) }
    public func setCorrectionAuto(_ value: Bool) { setBool(value, WotaSettingsKeys.correctionAuto) }
    public func setCorrectionManualNs(_ value: Int64) { setInt64(value, WotaSettingsKeys.correctionManualNs) }
    public func setDefaultMarkerSpread(_ value: Float) { setFloat(value, WotaSettingsKeys.defaultMarkerSpread) }
    public func setDefaultMarkerColor(_ value: MarkerLineColor) { setString(value.rawValue, WotaSettingsKeys.defaultMarkerColor) }
    public func setGlowParams(_ value: GlowParams) { setString(WotaSettingsCodecs.encodeGlow(value), WotaSettingsKeys.glow) }
    public func setEchoParams(_ value: EchoParams) { setString(WotaSettingsCodecs.encodeEcho(value), WotaSettingsKeys.echo) }
    public func setToneParams(_ value: ToneParams) { setString(WotaSettingsCodecs.encodeTone(value), WotaSettingsKeys.tone) }
    public func setDisplayScale(_ value: DisplayScale) { setString(value.rawValue, WotaSettingsKeys.displayScale) }

    // MARK: - 写入（直写 UI 设置；低频动作完成点可先 set 再 flush()）

    public func setMotionMode(_ value: MotionMode) { setString(value.rawValue, WotaSettingsKeys.motionMode) }
    public func setDefaultSampleRate(_ value: Int) { setInt(value, WotaSettingsKeys.defaultSampleRate) }
    public func setDefaultRenderMode(_ value: RenderMode) { setString(value.rawValue, WotaSettingsKeys.defaultRenderMode) }
    public func setDefaultAudioMuted(_ value: Bool) { setBool(value, WotaSettingsKeys.defaultAudioMuted) }
    public func setUIOrientation(_ value: UIOrientation) { setString(value.rawValue, WotaSettingsKeys.uiOrientation) }
    public func setLevelBuzzEnabled(_ value: Bool) { setBool(value, WotaSettingsKeys.levelBuzzEnabled) }
    public func setHudItemsMask(_ value: Int) { setInt(value, WotaSettingsKeys.hudItems) }
    /// 写掩码时必须**同时**写新版本号（否则迁移会反复生效）。
    public func setHudPillsMask(_ value: Int) {
        setInt(value, WotaSettingsKeys.hudPills)
        setInt(CamPill.maskSchema, WotaSettingsKeys.hudPillsSchema)
    }
    public func setHudLayoutRaw(_ value: String?) { setString(value, WotaSettingsKeys.hudLayout) }
    public func setHudFrostBlur(_ value: Bool) { setBool(value, WotaSettingsKeys.hudFrostBlur) }
    public func setContinuousSliders(_ value: Set<SliderKey>) {
        setString(WotaSettingsCodecs.encodeContinuousSliders(value), WotaSettingsKeys.sliderContinuous)
    }
    public func setCompareTickMs(_ value: Int) { setInt(Swift.min(Swift.max(value, 100), 1000), WotaSettingsKeys.compareTickMs) }
    public func setTextScaleCamera(_ value: Int) { setInt(value, WotaSettingsKeys.textScaleCamera) }
    public func setTextScaleSettings(_ value: Int) { setInt(value, WotaSettingsKeys.textScaleSettings) }
    public func setTextScaleDialog(_ value: Int) { setInt(value, WotaSettingsKeys.textScaleDialog) }
    public func setGalleryColumns(_ value: Int) {
        setInt([2, 3, 4, 5].contains(value) ? value : 2, WotaSettingsKeys.galleryColumns)
    }
    public func setCurvePanelSideDp(_ value: Float) { setFloat(value, WotaSettingsKeys.curvePanelSideDp) }
    public func setArcConvertMode(_ value: ArcConvertMode) { setString(value.rawValue, WotaSettingsKeys.arcConvertMode) }
    public func setCurveStack(_ value: CurveStack) {
        setString(WotaSettingsCodecs.encodeCurveStack(value), WotaSettingsKeys.curveStack)
    }
    public func setCameraBrightenDelaySec(_ value: Int) {
        setInt(Swift.min(Swift.max(value, 0), 10), WotaSettingsKeys.cameraBrightenDelaySec)
    }
    public func setPlayerBrightenRampTenths(_ value: Int) {
        setInt(Swift.min(Swift.max(value, 0), 30), WotaSettingsKeys.playerBrightenRampTenths)
    }
    public func setPlayerTopBarCollapsed(_ value: Bool) { setBool(value, WotaSettingsKeys.playerTopBarCollapsed) }
    public func setDiagEnabled(_ value: Bool) { setBool(value, WotaSettingsKeys.diagEnabled) }
    public func setDiagVerbose(_ value: Bool) { setBool(value, WotaSettingsKeys.diagVerbose) }
}
