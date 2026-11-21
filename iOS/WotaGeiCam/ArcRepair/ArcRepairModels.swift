import Foundation
#if canImport(WotaCore)
import WotaCore
#endif

// MARK: - C2 离线光弧修复：纯逻辑层
//
// 本文件只放可在主线程/后台线程安全使用的纯值逻辑：
//   - 源帧率有理数探测与吸附（对照 Android `record/ArcKeepRule.kt` 的 `ArcRateProbe`）；
//   - 抽帧计划与流式执行桥（对照 Android `record/ArcRepairPlan.kt`）；
//   - 成片命名（对照 Android `ArcRateProbe.convertNamePrefix` + iOS `MediaStorageLayout`）；
//   - 结果/进度/失败码。
//
// 像素处理不在这里：一律走既有 `FrameRenderBackend.processArcMerge`（C1 契约），
// 本层不复制任何 Android GL/GLSL 代码，也不直接操作 YUV 平面。

/// 修复失败码（机器可读；UI 按码给文案，日志按码过滤）。
enum ArcRepairFailureCode: String {
    case source = "ARC_SOURCE"
    case noNeed = "ARC_NO_NEED"
    case noOutput = "ARC_NO_OUTPUT"
    case reader = "ARC_READER"
    case writer = "ARC_ENCODER"
    case render = "ARC_RENDER"
    case audio = "ARC_AUDIO"
    case noVideo = "ARC_NO_VIDEO"
    case timeout = "ARC_TIMEOUT"
    case cancelled = "ARC_CANCELLED"
    case engine = "ARC_ENGINE"
}

/// 图像后端偏好。默认自动（Metal → OpenGL ES → CPU）；CPU 供兼容/排障重试。
enum ArcRepairBackendPreference: Int {
    case automatic = 0
    case cpu = 1

    var displayName: String {
        switch self {
        case .automatic: return "自动"
        case .cpu: return "CPU"
        }
    }
}

/// 一次离线修复的目标参数（启动后不再读取 UI）。
struct ArcRepairConfiguration {
    var targetFps: Int
    var deadband: Int
    var backendPreference: ArcRepairBackendPreference

    static let defaultTargetFps = 24

    /// 产品口径只允许强制档 24/25（与 Android `WotaTiers.REQUIRED_FPS` 对齐）。
    static let allowedTargetFps: [Int] = [24, 25]

    init(
        targetFps: Int = ArcRepairConfiguration.defaultTargetFps,
        deadband: Int = 12,
        backendPreference: ArcRepairBackendPreference = .automatic
    ) {
        self.targetFps = targetFps
        self.deadband = deadband
        self.backendPreference = backendPreference
    }

    func sanitized() -> ArcRepairConfiguration {
        let fps = ArcRepairConfiguration.allowedTargetFps.contains(targetFps)
            ? targetFps
            : ArcRepairConfiguration.defaultTargetFps
        return ArcRepairConfiguration(
            targetFps: fps,
            deadband: ArcMergeClamp.clamp(deadband),
            backendPreference: backendPreference
        )
    }
}

/// `ArcMergeConstants` 的本地钳制（避免模型层依赖具体模块名；值域与 WotaCore 同源）。
enum ArcMergeClamp {
    static let minValue = 0
    static let maxValue = 32

    static func clamp(_ value: Int) -> Int {
        if value < minValue { return minValue }
        if value > maxValue { return maxValue }
        return value
    }
}

// MARK: - 实测源帧率（有理数）

/// 实测源帧率（已约分）。整数视图只用于日志与成片名前缀。
struct ArcRepairMeasuredRate {
    let num: Int64
    let den: Int64

    /// 有效帧距样本少于该数（极短视频）时中位数不可信，退回容器帧率。
    static let minimumDeltaSamples = 8

    /// 帧距滤噪带（微秒）：<1ms 是重复样本，>1s 是轨道断层。
    static let cleanDeltaRangeUs: ClosedRange<Int64> = 1_001...1_000_000

    /// 实测帧率上限（产品最高 240，留一倍余量）。
    static let maximumMeasuredFps = 480

    init(num: Int64, den: Int64) {
        if num <= 0 || den <= 0 {
            self.num = 0
            self.den = 1
            return
        }
        let divisor = ArcRepairMeasuredRate.greatestCommonDivisor(num, den)
        self.num = divisor > 1 ? num / divisor : num
        self.den = divisor > 1 ? den / divisor : den
    }

    var isValid: Bool { return num > 0 && den > 0 }

    var secondsPerFrame: Double {
        guard den > 0 else { return 0 }
        return Double(den) / Double(num)
    }

    /// 四舍五入并钳到 1...480（半上，无浮点）。
    var roundedFps: Int {
        guard num > 0, den > 0 else { return 30 }
        let rounded = (num * 2 + den) / (den * 2)
        if rounded < 1 { return 1 }
        if rounded > Int64(ArcRepairMeasuredRate.maximumMeasuredFps) {
            return ArcRepairMeasuredRate.maximumMeasuredFps
        }
        return Int(rounded)
    }

    /// 源帧率 ≤ 整数 n ⇔ num ≤ n·den（整数化会把 25.4 打成 25 而误判“无需抽帧”）。
    func atMost(_ fps: Int) -> Bool {
        guard fps > 0 else { return true }
        return num <= Int64(fps) * den
    }

    var text: String {
        return "\(num)/\(den)"
    }

    /// 已知档位：exact 整数档在前，NTSC（÷1001）在后。
    private static let knownRates: [ArcRepairMeasuredRate] = [
        ArcRepairMeasuredRate(num: 24, den: 1),
        ArcRepairMeasuredRate(num: 25, den: 1),
        ArcRepairMeasuredRate(num: 30, den: 1),
        ArcRepairMeasuredRate(num: 50, den: 1),
        ArcRepairMeasuredRate(num: 60, den: 1),
        ArcRepairMeasuredRate(num: 120, den: 1),
        ArcRepairMeasuredRate(num: 240, den: 1),
        ArcRepairMeasuredRate(num: 24_000, den: 1_001),
        ArcRepairMeasuredRate(num: 30_000, den: 1_001),
        ArcRepairMeasuredRate(num: 60_000, den: 1_001)
    ]

    /// 中位帧距吸附到已知档位；未命中退 `1_000_000/d` 的原始有理数。
    static func snapToKnownRate(deltaUs: Int64) -> ArcRepairMeasuredRate {
        if deltaUs <= 0 { return ArcRepairMeasuredRate(num: 0, den: 1) }
        for rate in knownRates {
            let difference = deltaUs * rate.num - 1_000_000 * rate.den
            let tolerance = 2 * rate.num
            if difference <= tolerance && difference >= -tolerance {
                return rate
            }
        }
        return ArcRepairMeasuredRate(num: 1_000_000, den: deltaUs)
    }

    /// 由相邻样本的显示时间戳（微秒）实测帧率。
    ///
    /// 调用方传入的可以是解码序时间戳；这里先排序去重，再取相邻差的中位数，
    /// 因此 B 帧重排、重复样本与空样本都不会毒化结果。时间戳不足时退回容器帧率。
    static func measured(fromTimestampsUs timestamps: [Int64], fallbackFps: Double) -> ArcRepairMeasuredRate {
        guard timestamps.count >= minimumDeltaSamples else {
            return fallback(fps: fallbackFps)
        }
        let sorted = timestamps.sorted()
        var deltas: [Int64] = []
        deltas.reserveCapacity(sorted.count)
        var previous: Int64 = 0
        var hasPrevious = false
        for timestamp in sorted {
            if hasPrevious {
                let delta = timestamp - previous
                if cleanDeltaRangeUs.contains(delta) {
                    deltas.append(delta)
                }
            }
            previous = timestamp
            hasPrevious = true
        }
        guard deltas.count >= minimumDeltaSamples else {
            return fallback(fps: fallbackFps)
        }
        deltas.sort()
        let median = deltas[deltas.count / 2]
        return snapToKnownRate(deltaUs: median)
    }

    /// 容器/名义帧率的回退：识别常见 NTSC 值，其余按毫 fps 保留三位后约分。
    static func fallback(fps: Double) -> ArcRepairMeasuredRate {
        guard fps.isFinite, fps > 0 else {
            return ArcRepairMeasuredRate(num: 30, den: 1)
        }
        let candidates: [(Double, Int64, Int64)] = [
            (23.976, 24_000, 1_001),
            (29.97, 30_000, 1_001),
            (59.94, 60_000, 1_001)
        ]
        for candidate in candidates where abs(fps - candidate.0) < 0.02 {
            return ArcRepairMeasuredRate(num: candidate.1, den: candidate.2)
        }
        return ArcRepairMeasuredRate(num: Int64((fps * 1000).rounded()), den: 1_000)
    }

    private static func greatestCommonDivisor(_ a: Int64, _ b: Int64) -> Int64 {
        var x = a
        var y = b
        while y != 0 {
            let remainder = x % y
            x = y
            y = remainder
        }
        return x
    }
}

// MARK: - 抽帧计划与流式桥

/// 把实测源帧率抽成目标帧率的计划。
///
/// 输出只含保留帧，绝不插帧；保留集 = { floor(j·S/D) }，被抽帧由 `ArcRepairFlow`
/// 分桶进前后两枚保留帧。全程整数域，无浮点。
struct ArcRepairPlan {
    let srcNum: Int64
    let srcDen: Int64
    let dstFps: Int
    let srcFrames: Int

    var dstFrames: Int {
        return ArcRepairPlan.dstFrameCount(
            srcFrames: srcFrames,
            srcNum: srcNum,
            srcDen: srcDen,
            dstFps: dstFps
        )
    }

    var totalFrames: Int { return dstFrames }

    static func dstFrameCount(srcFrames: Int, srcNum: Int64, srcDen: Int64, dstFps: Int) -> Int {
        if srcFrames <= 0 { return 0 }
        if srcNum <= 0 || srcDen <= 0 || dstFps <= 0 ||
            srcNum <= Int64(dstFps) * srcDen {
            return srcFrames
        }
        let numerator = Int64(srcFrames) * Int64(dstFps) * srcDen + srcNum - 1
        return Int(numerator / srcNum)
    }

    static func isKept(srcIndex: Int, srcNum: Int64, srcDen: Int64, dstFps: Int) -> Bool {
        if srcIndex < 0 { return false }
        if srcNum <= 0 || srcDen <= 0 || dstFps <= 0 ||
            srcNum <= Int64(dstFps) * srcDen {
            return true
        }
        let source = Int64(srcIndex)
        let target = Int64(dstFps)
        let outputIndex = (source * target * srcDen + srcNum - 1) / srcNum
        return outputIndex * srcNum / (target * srcDen) == source
    }

    func isKept(srcIndex: Int) -> Bool {
        return ArcRepairPlan.isKept(
            srcIndex: srcIndex,
            srcNum: srcNum,
            srcDen: srcDen,
            dstFps: dstFps
        )
    }

    static func progressOf(doneFrames: Int, totalFrames: Int) -> Float {
        if totalFrames <= 0 { return 0 }
        let value = Float(doneFrames) / Float(totalFrames)
        if value < 0 { return 0 }
        if value > 1 { return 1 }
        return value
    }

    init(srcNum: Int64, srcDen: Int64, dstFps: Int, srcFrames: Int) {
        self.srcNum = srcNum
        self.srcDen = srcDen
        self.dstFps = dstFps
        self.srcFrames = srcFrames < 0 ? 0 : srcFrames
    }
}

/// 计划 → 行为的单帧处置指令（对照 Android `ArcOp`）。
enum ArcRepairOperation {
    /// 被抽帧：`fresh == true` 时累积面整帧换成本帧，否则 `acc = select(acc, cur)`。
    case accumulate(fresh: Bool)
    /// 累积补弧并进前一枚保留帧：`pending = select(pending, acc)`。
    case foldAccIntoPending
    /// 首枚保留帧直接滞留待发。
    case holdCurAsPending
    /// 后一枚保留帧带弧升级：`pending = select(cur, acc)`；`useAcc == false` 时 `pending = cur`。
    case mergeCurAsPending(useAcc: Bool)
    /// 把待发帧写进编码器（`outputIndex` 从 0 开始，PTS = outputIndex / dstFps）。
    case emit(outputIndex: Int)
}

/// 抽帧计划的流式执行桥（与 Android `ArcRepairFlow` 同状态机）。
final class ArcRepairFlow {
    private let dstFps: Int
    private let plannedKeep: ((Int) -> Bool)?
    private(set) var keptCount: Int = 0
    private(set) var hasPending: Bool = false
    private(set) var hasAcc: Bool = false

    init(plan: ArcRepairPlan) {
        self.dstFps = plan.dstFps
        self.plannedKeep = { index in plan.isKept(srcIndex: index) }
    }

    /// 离线路：按计划逐源帧问询。
    func onSourceFrame(srcIndex: Int) -> [ArcRepairOperation] {
        let kept = plannedKeep?(srcIndex) ?? true
        return onClassification(kept: kept)
    }

    func onClassification(kept: Bool) -> [ArcRepairOperation] {
        if !kept {
            // 首枚保留帧之前的被抽帧没有“前帧”可并，直接弃。
            if !hasPending { return [] }
            let fresh = !hasAcc
            hasAcc = true
            return [.accumulate(fresh: fresh)]
        }
        if !hasPending {
            hasPending = true
            return [.holdCurAsPending]
        }
        var operations: [ArcRepairOperation] = []
        if hasAcc {
            operations.append(.foldAccIntoPending)
        }
        operations.append(.emit(outputIndex: keptCount))
        operations.append(.mergeCurAsPending(useAcc: hasAcc))
        keptCount += 1
        hasAcc = false
        return operations
    }

    /// 解码 EOS：尾巴上的累积补弧并进最后一枚待发帧后发出。
    func onSourceEos() -> [ArcRepairOperation] {
        if !hasPending { return [] }
        var operations: [ArcRepairOperation] = []
        if hasAcc {
            operations.append(.foldAccIntoPending)
        }
        operations.append(.emit(outputIndex: keptCount))
        keptCount += 1
        hasAcc = false
        hasPending = false
        return operations
    }
}

// MARK: - 命名

/// 输出成片名。结果必须落在 iOS 媒体库认得的自有成片形态内，
/// 否则 `MediaLibrary` 的后续列表/维护不会收录。
enum ArcRepairNaming {

    /// `30fto24f_` 形态（源在前、目标在后），与 Android `convertNamePrefix` 对齐。
    static func conversionPrefix(sourceFps: Int, dstFps: Int) -> String {
        let source = max(1, sourceFps)
        let target = max(1, dstFps)
        return "\(source)fto\(target)f_"
    }

    /// 计算最终名（不含撞名后缀，撞名由 runner 追加序号）。
    ///
    /// 规则：
    /// - 源是 `VID_…` / 已带转换前缀的相机片 → `NNftoNNf_VID_…mp4`（库内相机成片形态）；
    /// - 源是 `edit_…` / `inau_…` → 保留原族前缀，转换前缀插在族前缀之后；
    /// - 其它（外来/未知名）→ `edit_NNftoNNf_<stem>.mp4`，保证 `isOwnedVideoFileName` 为真。
    static func outputFileName(sourceFileName: String, sourceFps: Int, dstFps: Int) -> String {
        let lastPathName = (sourceFileName as NSString).lastPathComponent
        let rawStem = stem(of: lastPathName)
        let safeStem = sanitizedStem(rawStem)
        let stripped = strippingConversionPrefix(safeStem)
        let prefix = conversionPrefix(sourceFps: sourceFps, dstFps: dstFps)

        var candidate: String
        let lowered = stripped.lowercased()
        if lowered.hasPrefix("edit_") {
            candidate = "edit_" + prefix + String(stripped.dropFirst("edit_".count)) + ".mp4"
        } else if lowered.hasPrefix("inau_") {
            candidate = "inau_" + prefix + String(stripped.dropFirst("inau_".count)) + ".mp4"
        } else {
            candidate = prefix + stripped + ".mp4"
        }
        if isOwnedVideoName(candidate) {
            return candidate
        }
        candidate = "edit_" + prefix + stripped + ".mp4"
        if isOwnedVideoName(candidate) {
            return candidate
        }
        return "edit_" + prefix + "arc.mp4"
    }

    /// 撞名时在扩展名前追加序号（iOS 无 MediaStore 自动去重）。
    static func fileName(_ baseName: String, collisionIndex: Int) -> String {
        if collisionIndex <= 0 { return baseName }
        let stemValue = stem(of: baseName)
        let extensionValue = extensionName(of: baseName)
        let suffix = extensionValue.isEmpty ? "" : "." + extensionValue
        return "\(stemValue) \(collisionIndex + 1)\(suffix)"
    }

    private static func strippingConversionPrefix(_ name: String) -> String {
        let pattern = "^[0-9]+fto[0-9]+f_"
        guard let range = name.range(of: pattern, options: .regularExpression) else {
            return name
        }
        let stripped = String(name[range.upperBound...])
        return stripped.isEmpty ? name : stripped
    }

    private static func sanitizedStem(_ name: String) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return "arc" }
        if trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("\u{0}") {
            return "arc"
        }
        return trimmed
    }

    private static func stem(of fileName: String) -> String {
        guard let dot = fileName.lastIndex(of: "."), dot != fileName.startIndex else {
            return fileName
        }
        return String(fileName[fileName.startIndex..<dot])
    }

    private static func extensionName(of fileName: String) -> String {
        guard let dot = fileName.lastIndex(of: "."), dot != fileName.startIndex else {
            return ""
        }
        return String(fileName[fileName.index(after: dot)...])
    }

    private static func isOwnedVideoName(_ name: String) -> Bool {
        #if canImport(WotaCore)
        return MediaStorageLayout.isOwnedVideoFileName(name)
        #else
        let lowered = name.lowercased()
        if lowered.hasPrefix("edit_") && lowered.hasSuffix(".mp4") { return true }
        if lowered.hasPrefix("inau_") && lowered.hasSuffix(".mp4") { return true }
        guard lowered.hasSuffix(".mp4"), name.contains("VID_") else { return false }
        let prefix = name.components(separatedBy: "VID_").first ?? ""
        if prefix.isEmpty { return true }
        return prefix.range(of: "^[0-9]+fto[0-9]+f_$", options: .regularExpression) != nil
        #endif
    }
}

// MARK: - 进度与结果

/// 后台任务投给主线程的进度快照。
struct ArcRepairProgressUpdate {
    var fraction: Float
    var message: String
    var canCancel: Bool
}

/// 修复结果（成功/取消/失败三态，成功带已入库产物）。
struct ArcRepairReport {
    enum Outcome {
        case published
        case cancelled
        case failed
    }

    var outcome: Outcome
    var outputURL: URL?
    var mediaId: Int64?
    var outputBytes: Int64
    var errorCode: String?
    var message: String
    var notes: [String]
    var sourceFpsText: String
    var sourceFrames: Int
    var outputFrames: Int
    var targetFps: Int
    var engineName: String

    var isPublished: Bool {
        if case .published = outcome { return true }
        return false
    }

    var isCancelled: Bool {
        if case .cancelled = outcome { return true }
        return false
    }

    static func failure(
        code: ArcRepairFailureCode,
        message: String,
        notes: [String],
        sourceFpsText: String = "-",
        sourceFrames: Int = 0,
        outputFrames: Int = 0,
        targetFps: Int = 0,
        engineName: String = "-"
    ) -> ArcRepairReport {
        return ArcRepairReport(
            outcome: .failed,
            outputURL: nil,
            mediaId: nil,
            outputBytes: 0,
            errorCode: code.rawValue,
            message: message,
            notes: notes,
            sourceFpsText: sourceFpsText,
            sourceFrames: sourceFrames,
            outputFrames: outputFrames,
            targetFps: targetFps,
            engineName: engineName
        )
    }

    static func cancelled(
        sourceFpsText: String = "-",
        sourceFrames: Int = 0,
        outputFrames: Int = 0,
        targetFps: Int = 0,
        engineName: String = "-"
    ) -> ArcRepairReport {
        return ArcRepairReport(
            outcome: .cancelled,
            outputURL: nil,
            mediaId: nil,
            outputBytes: 0,
            errorCode: ArcRepairFailureCode.cancelled.rawValue,
            message: "已取消，未生成新片。",
            notes: [],
            sourceFpsText: sourceFpsText,
            sourceFrames: sourceFrames,
            outputFrames: outputFrames,
            targetFps: targetFps,
            engineName: engineName
        )
    }
}
