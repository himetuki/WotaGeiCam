import Foundation

/// 分段录制系列 id 生成器，口径与 Android `SeriesIds` 对齐：
/// 时间基占高位、进程内单调序号占低位，保证同毫秒内并发也不重复，且始终为正数。
///
/// Android 把该 id 用作 Room `Long` 主键；iOS 后续持久化层需要保持一致语义，
/// 因此这里以 `Int64` 作为公开类型，不使用字符串。
public final class SeriesIDGenerator: @unchecked Sendable {
    public static let sequenceSpan: Int64 = 1_000

    /// `timeCapMilliseconds * sequenceSpan + 999 < Int64.max`，任意输入都不回绕成负数。
    public static let timeCapMilliseconds: Int64 = 9_000_000_000_000_000

    private let lock = NSLock()
    private var last: Int64 = 0

    public init() {}

    public static func pack(nowMilliseconds: Int64, sequence: Int64) -> Int64 {
        let clampedTime = min(max(nowMilliseconds, 1), timeCapMilliseconds)
        let base = clampedTime * sequenceSpan
        let normalizedSequence = ((sequence % sequenceSpan) + sequenceSpan) % sequenceSpan
        return base + normalizedSequence
    }

    /// 下一个系列 id：严格单调，时钟回拨或同毫秒连续调用都不会重复。
    @discardableResult
    public func next(nowMilliseconds: Int64 = SeriesIDGenerator.currentTimeMilliseconds()) -> Int64 {
        lock.lock()
        defer { lock.unlock() }

        let candidate = Self.pack(nowMilliseconds: nowMilliseconds, sequence: 0)
        let value = last < candidate ? candidate : last + 1
        last = value
        return value
    }

    public static func currentTimeMilliseconds() -> Int64 {
        Int64((Date().timeIntervalSince1970 * 1_000).rounded(.down))
    }
}
