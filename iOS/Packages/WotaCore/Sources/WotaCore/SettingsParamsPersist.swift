import Foundation

/// 入档白名单的**参数快照**（对应 Android `WotaParams` 总线里被持久化的那 34 个字段）。
///
/// 这是一个中立的纯数据载体：冷启 `WotaParamsRestore.load` 产出它交给相机层套用；
/// 录制页在动作完成点用 `WotaParamsPersistSession.flush` 把「与本次会话影子不同的字段」写回。
/// 不依赖 `WotaParams` 具体实现，便于 B1（相机）/ 渲染层共用。
///
/// **刻意不在此快照**：`markerCount`（站位线线数，用户口径「启动时取景器干净无线」，
/// 每次从 0 起步）；`renderMode`/`sampleRate`/`audioEnabled`/`focusPoint`/`curve`/`arc_convert_mode`
/// （各有专属直写链，避免第二写入方）。见 `iOS/docs/mappings/settings.md` 的排除清单。
public struct WotaPersistedParams: Equatable {
    public var fps: Int
    public var shutterNs: Int
    public var bitrate: Int
    public var refLines: Int
    public var levelEnabled: Bool
    public var iso: Int
    public var aeMode: AeMode
    public var ev: Int
    public var wbPreset: WbPreset
    public var wbKelvin: Int
    public var wbTint: Int
    public var zoom: Float
    public var flash: Flash
    public var stabilize: Stabilize
    public var afMode: AfMode
    public var mfDiopter: Float
    public var size: WotaVideoSize
    public var frameEffect: FrameEffect
    public var zebraThreshold: Float
    public var zebraDensity: Int
    public var peakingStrength: Float
    public var peakingColor: PeakingColor
    public var lens: LensType
    public var arcMergeDeadband: Int
    public var denoiseAudio: AudioDenoise
    public var denoiseVideo: VideoDenoise
    public var correctionAuto: Bool
    public var correctionManualNs: Int64
    public var markerSpread: Float
    public var markerColor: MarkerLineColor
    public var glow: GlowParams
    public var tone: ToneParams
    public var displayScale: DisplayScale
    public var echo: EchoParams

    public init(
        fps: Int = 25,
        shutterNs: Int = 40_000_000,
        bitrate: Int = 10_000_000,
        refLines: Int = 0,
        levelEnabled: Bool = true,
        iso: Int = 100,
        aeMode: AeMode = .auto,
        ev: Int = 0,
        wbPreset: WbPreset = .auto,
        wbKelvin: Int = 5500,
        wbTint: Int = 0,
        zoom: Float = 1,
        flash: Flash = .off,
        stabilize: Stabilize = .off,
        afMode: AfMode = .continuousVideo,
        mfDiopter: Float = 0,
        size: WotaVideoSize = .default,
        frameEffect: FrameEffect = .none,
        zebraThreshold: Float = 0.8,
        zebraDensity: Int = 2,
        peakingStrength: Float = 0.6,
        peakingColor: PeakingColor = .red,
        lens: LensType = .wide,
        arcMergeDeadband: Int = ArcMergeDeadband.default,
        denoiseAudio: AudioDenoise = .off,
        denoiseVideo: VideoDenoise = .off,
        correctionAuto: Bool = true,
        correctionManualNs: Int64 = 0,
        markerSpread: Float = MarkerLines.defaultSpread,
        markerColor: MarkerLineColor = .yellow,
        glow: GlowParams = .default,
        tone: ToneParams = .identity,
        displayScale: DisplayScale = .orig,
        echo: EchoParams = .default
    ) {
        self.fps = fps
        self.shutterNs = shutterNs
        self.bitrate = bitrate
        self.refLines = refLines
        self.levelEnabled = levelEnabled
        self.iso = iso
        self.aeMode = aeMode
        self.ev = ev
        self.wbPreset = wbPreset
        self.wbKelvin = wbKelvin
        self.wbTint = wbTint
        self.zoom = zoom
        self.flash = flash
        self.stabilize = stabilize
        self.afMode = afMode
        self.mfDiopter = mfDiopter
        self.size = size
        self.frameEffect = frameEffect
        self.zebraThreshold = zebraThreshold
        self.zebraDensity = zebraDensity
        self.peakingStrength = peakingStrength
        self.peakingColor = peakingColor
        self.lens = lens
        self.arcMergeDeadband = arcMergeDeadband
        self.denoiseAudio = denoiseAudio
        self.denoiseVideo = denoiseVideo
        self.correctionAuto = correctionAuto
        self.correctionManualNs = correctionManualNs
        self.markerSpread = markerSpread
        self.markerColor = markerColor
        self.glow = glow
        self.tone = tone
        self.displayScale = displayScale
        self.echo = echo
    }

    /// 键数量的守卫值（源实测 = 34）。
    public static let fieldCount = 34
}

/// 冷启恢复（对应 Android `ParamsPersist.restore`）：读键 → 坏值回默认/跳过 → 产出参数快照。
///
/// getter 侧的钳制/兜底都在 `WotaSettingsStore` 内完成，本函数只做汇总，**绝不抛**。
public enum WotaParamsRestore {
    public static func load(from store: WotaSettingsStore) -> WotaPersistedParams {
        return WotaPersistedParams(
            fps: store.defaultFps(),
            shutterNs: store.defaultShutterNs(),
            bitrate: store.defaultBitrate(),
            refLines: store.defaultRefLines(),
            levelEnabled: store.levelEnabled(),
            iso: store.defaultIso(),
            aeMode: store.defaultAeMode(),
            ev: store.defaultEv(),
            wbPreset: store.defaultWbPreset(),
            wbKelvin: store.defaultWbKelvin(),
            wbTint: store.defaultWbTint(),
            zoom: store.defaultZoom(),
            flash: store.defaultFlash(),
            stabilize: store.defaultStabilize(),
            afMode: store.defaultAfMode(),
            mfDiopter: store.defaultMfDiopter(),
            size: store.defaultSize() ?? .default,
            frameEffect: store.defaultFrameEffect(),
            zebraThreshold: store.defaultZebraThreshold(),
            zebraDensity: store.defaultZebraDensity(),
            peakingStrength: store.defaultPeakingStrength(),
            peakingColor: store.defaultPeakingColor(),
            lens: store.defaultLens(),
            arcMergeDeadband: store.defaultArcMergeDeadband(),
            denoiseAudio: store.denoiseAudio(),
            denoiseVideo: store.denoiseVideo(),
            correctionAuto: store.correctionAuto(),
            correctionManualNs: store.correctionManualNs(),
            markerSpread: store.defaultMarkerSpread(),
            markerColor: store.defaultMarkerColor(),
            glow: store.glowParams(),
            tone: store.toneParams(),
            displayScale: store.displayScale(),
            echo: store.echoParams()
        )
    }
}

/// 持久化会话（对应 Android `ParamsPersistSession`：会话影子 + 用户动作完成点 flush）。
///
/// - 构造时冻结**影子** = 进入录制页那一刻的总线快照；
/// - `flush(current:)` 只把「与影子不同」的字段一次写盘并同步，无差异时不触碰存储（返回 true）；
///   写盘失败则保持旧影子，下次重试；
/// - 影子 diff 同时封死「静默覆盖设置页」缺陷：用户在设置页改了某键、而录制页总线还停在旧值，
///   此后任何 flush 都不会把陈旧值写回。
public final class WotaParamsPersistSession {

    private let store: WotaSettingsStore
    private var shadow: WotaPersistedParams

    public init(store: WotaSettingsStore, mount: WotaPersistedParams) {
        self.store = store
        self.shadow = mount
    }

    /// 与影子不同的字段写盘；返回是否落成（无差异恒 true）。
    @discardableResult
    public func flush(current: WotaPersistedParams) -> Bool {
        // 读取口径与 flush 侧对齐（AE 归一、产品域钳制），避免把越界值写盘。
        let value = current
        var dirty = false

        func changed<T: Equatable>(_ a: T, _ b: T) -> Bool { return a != b }

        if changed(value.fps, shadow.fps) { store.setDefaultFps(value.fps); dirty = true }
        if changed(value.shutterNs, shadow.shutterNs) { store.setDefaultShutterNs(value.shutterNs); dirty = true }
        if changed(value.bitrate, shadow.bitrate) { store.setDefaultBitrate(value.bitrate); dirty = true }
        if changed(value.refLines, shadow.refLines) { store.setDefaultRefLines(value.refLines); dirty = true }
        if changed(value.levelEnabled, shadow.levelEnabled) { store.setLevelEnabled(value.levelEnabled); dirty = true }
        if changed(value.iso, shadow.iso) { store.setDefaultIso(value.iso); dirty = true }
        let ae = value.aeMode.persisted
        if changed(ae, shadow.aeMode.persisted) { store.setDefaultAeMode(ae); dirty = true }
        if changed(value.ev, shadow.ev) { store.setDefaultEv(value.ev); dirty = true }
        if changed(value.wbPreset, shadow.wbPreset) { store.setDefaultWbPreset(value.wbPreset); dirty = true }
        if changed(value.wbKelvin, shadow.wbKelvin) { store.setDefaultWbKelvin(value.wbKelvin); dirty = true }
        if changed(value.wbTint, shadow.wbTint) { store.setDefaultWbTint(value.wbTint); dirty = true }
        if changed(value.zoom, shadow.zoom) { store.setDefaultZoom(value.zoom); dirty = true }
        if changed(value.flash, shadow.flash) { store.setDefaultFlash(value.flash); dirty = true }
        if changed(value.stabilize, shadow.stabilize) { store.setDefaultStabilize(value.stabilize); dirty = true }
        if changed(value.afMode, shadow.afMode) { store.setDefaultAfMode(value.afMode); dirty = true }
        if changed(value.mfDiopter, shadow.mfDiopter) { store.setDefaultMfDiopter(value.mfDiopter); dirty = true }
        if changed(value.size, shadow.size) { store.setDefaultSize(value.size); dirty = true }
        if changed(value.frameEffect, shadow.frameEffect) { store.setDefaultFrameEffect(value.frameEffect); dirty = true }
        if changed(value.zebraThreshold, shadow.zebraThreshold) {
            store.setDefaultZebraThreshold(value.zebraThreshold); dirty = true
        }
        if changed(value.zebraDensity, shadow.zebraDensity) {
            store.setDefaultZebraDensity(value.zebraDensity); dirty = true
        }
        if changed(value.peakingStrength, shadow.peakingStrength) {
            store.setDefaultPeakingStrength(value.peakingStrength); dirty = true
        }
        if changed(value.peakingColor, shadow.peakingColor) {
            store.setDefaultPeakingColor(value.peakingColor); dirty = true
        }
        if changed(value.lens, shadow.lens) { store.setDefaultLens(value.lens); dirty = true }
        if changed(value.arcMergeDeadband, shadow.arcMergeDeadband) {
            store.setDefaultArcMergeDeadband(value.arcMergeDeadband); dirty = true
        }
        if changed(value.denoiseAudio, shadow.denoiseAudio) { store.setDenoiseAudio(value.denoiseAudio); dirty = true }
        if changed(value.denoiseVideo, shadow.denoiseVideo) { store.setDenoiseVideo(value.denoiseVideo); dirty = true }
        if changed(value.correctionAuto, shadow.correctionAuto) {
            store.setCorrectionAuto(value.correctionAuto); dirty = true
        }
        if changed(value.correctionManualNs, shadow.correctionManualNs) {
            store.setCorrectionManualNs(value.correctionManualNs); dirty = true
        }
        if changed(value.markerSpread, shadow.markerSpread) {
            store.setDefaultMarkerSpread(value.markerSpread); dirty = true
        }
        if changed(value.markerColor, shadow.markerColor) {
            store.setDefaultMarkerColor(value.markerColor); dirty = true
        }
        if changed(value.glow, shadow.glow) { store.setGlowParams(value.glow); dirty = true }
        if changed(value.echo, shadow.echo) { store.setEchoParams(value.echo); dirty = true }
        if changed(value.tone, shadow.tone) { store.setToneParams(value.tone); dirty = true }
        if changed(value.displayScale, shadow.displayScale) {
            store.setDisplayScale(value.displayScale); dirty = true
        }

        if !dirty { return true }
        let ok = store.flush()
        if ok { shadow = value }
        return ok
    }
}
