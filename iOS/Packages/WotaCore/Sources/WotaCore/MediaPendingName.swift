import Foundation

/// pending 临时名清洗，与 Android `PendingName` 对齐。
///
/// Android 的 pending 段由 MediaProvider 产生（`.pending-<id>-<原名>`），commit 后异步复原。
/// iOS 无 provider，但**录制期的暂存文件**同样使用 `.pending-<数字>-<原名>` 形态命名
/// （见 `stagingFileName`），因此：
/// - 录制完成 → 原子发布（rename 掉 marker 段），等价于 Android commit；
/// - 失败/取消 → 删暂存文件，等价于 Android discard；
/// - 冷启动维护 → 用同一套正则识别并归位历史残留（`StalePendingSweep` 等价）。
///
/// `.pending-` + **纯数字** + `-` 形态才判为临时段；名字里恰好含 "pending" 字样但不是该形态的
/// 正常名字（`my.pending-file.mp4`、`pending-abc-x.mp4`）不受影响。
public enum MediaPendingName {

    /// MediaProvider / 本工程暂存名的临时段：`.pending-<纯数字>-`。
    private static let pendingSegmentPattern = #"\.pending-\d+-"#

    /// 剥掉名字里所有 pending 临时段；无临时段时原样返回。
    public static func stripPendingJunk(_ displayName: String) -> String {
        displayName.replacingOccurrences(
            of: pendingSegmentPattern,
            with: "",
            options: .regularExpression
        )
    }

    /// 名字是否带 pending 临时段（剥离后与原名不同即为真）。
    public static func isPendingMarked(_ displayName: String) -> Bool {
        stripPendingJunk(displayName) != displayName
    }

    /// commit 后改名的单一出口：前缀 + 剥净临时段的名字。
    public static func commitRenameTarget(prefix: String, fetchedName: String) -> String {
        prefix + stripPendingJunk(fetchedName)
    }

    /// 录制期暂存文件名（等价于 Android 的 pending uri 名）。
    ///
    /// 形态与 Android 一致：`.pending-<纯数字>-<displayName>`；发布时用 `stripPendingJunk`
    /// 剥掉前缀得到最终名，从而与冷启动清扫共用同一套判据。
    public static func stagingFileName(displayName: String, processId: Int32) -> String {
        let pid = processId >= 0 ? processId : 0
        return ".pending-\(pid)-" + displayName
    }

    /// sidecar 归位用的**成片路径**，与 Android `PendingName.sidecarConvergeTarget` 对齐。
    ///
    /// 入参与返回都是成片路径（不是 sidecar 路径）：真正的搬动交给调用方按成片路径拼扩展名。
    /// 比较用**物理当前名**：文件真叫 `.pending-9-VID_1.mp4` 时必须搬，哪怕剥净后与目标同名。
    ///
    /// - Parameters:
    ///   - videoPath: 成片当前路径。
    ///   - queriedName: 回查到的成片显示名（nil/空 = 回查失败，退回当前名剥净）。
    /// - Returns: 成片应处的路径；nil = 无需搬动。
    public static func sidecarConvergeTarget(videoPath: String, queriedName: String?) -> String? {
        let directory = (videoPath as NSString).deletingLastPathComponent
        let current = (videoPath as NSString).lastPathComponent
        if current.isEmpty { return nil }
        let currentClean = stripPendingJunk(current)
        if currentClean.isEmpty { return nil }
        let realName: String
        if let queried = queriedName, !stripPendingJunk(queried).isEmpty {
            realName = stripPendingJunk(queried)
        } else {
            realName = currentClean
        }
        if realName == current { return nil }
        return directory.isEmpty ? realName : (directory as NSString).appendingPathComponent(realName)
    }
}
