import Foundation

/// 冷启动清扫的处置动作，与 Android `SweepAction` 对齐。
public enum MediaSweepAction {
    case leave
    case renameClip
    case renameArchive
    case deleteArchive
}

/// 逐条处置计划；`targetName` 为**条目名**（不含目录），`.leave` / `.deleteArchive` 时为 nil。
public struct MediaSweepPlan {
    public let action: MediaSweepAction
    public let targetName: String?
    public init(action: MediaSweepAction, targetName: String?) {
        self.action = action
        self.targetName = targetName
    }
}

/// 冷启动清扫的**全部判断**（纯函数），与 Android `StalePendingSweep.sweepPlan` 逐条对齐。
///
/// | 形态 | 条件 | 处置 |
/// |---|---|---|
/// | 非 pending 形态 | 名字剥段后没变 | `.leave`（不是我们的东西） |
/// | 任何 | 比门槛新 | `.leave`（可能是正在录的那一段） |
/// | 侧车 `.drops.json` | 剥段后同名档案已在 | `.deleteArchive`（重复档案，纯档案无数据损失） |
/// | 侧车 `.drops.json` | 对应成片找得到 | `.renameArchive`（归位到成片真实名 + 扩展名） |
/// | 侧车 `.drops.json` | 对应成片也找不到 | `.deleteArchive`（无主档案） |
/// | 成片 | 剥段后同名成片已在 | `.leave`（数据红线：成片绝不删） |
/// | 成片 | 容器打不开（或未探） | `.leave`（半截 MP4 一动不动，连名字都不动） |
/// | 成片 | 探过且打得开 | `.renameClip`（去掉 pending 段） |
public enum MediaSweepPlanner {

    /// 年龄门槛：只处理「比这个还老」的条目（正在录的那一段是秒级的，绝不可能入内）。
    public static let staleMilliseconds: Int64 = 60 * 60 * 1000

    /// 单次扫描条目上限（目录可能很大，清扫不值得阻塞启动太久）。
    public static let maxEntriesPerRun = 500

    /// 单轮「库一致性修补」（删坏行 + 补入库）上限：收敛于多轮，别让冷启扫全库。
    public static let maxIndexPerRun = 40

    /// 逐条处置计划。
    ///
    /// - Parameters:
    ///   - name: 目录里的条目名。
    ///   - ageMs: 条目距今毫秒。
    ///   - cleanTwinExists: 剥段后的同名**条目**是否已在磁盘上。
    ///   - clipName: 仅侧车用：该档案对应的**已存在**成片文件名（nil = 找不到 ⇒ 无主档案）。
    ///   - clipPlayable: 仅成片用：容器可播探测结果。nil = 未探（侧车趟显式传 nil）⇒ 按不可播处理；
    ///     `.renameClip` 只认「探过且可播」（== true）这一种正向确认。
    public static func plan(
        name: String,
        ageMs: Int64,
        cleanTwinExists: Bool,
        clipName: String?,
        clipPlayable: Bool?
    ) -> MediaSweepPlan {
        let clean = MediaPendingName.stripPendingJunk(name)
        if clean.isEmpty || clean == name { return MediaSweepPlan(action: .leave, targetName: nil) }
        if ageMs < staleMilliseconds { return MediaSweepPlan(action: .leave, targetName: nil) }

        if clean.hasSuffix(MediaStorageLayout.sidecarExtension) {
            if cleanTwinExists { return MediaSweepPlan(action: .deleteArchive, targetName: nil) }
            if let clip = clipName {
                return MediaSweepPlan(action: .renameArchive, targetName: clip + MediaStorageLayout.sidecarExtension)
            }
            return MediaSweepPlan(action: .deleteArchive, targetName: nil)
        }

        if cleanTwinExists { return MediaSweepPlan(action: .leave, targetName: nil) }
        if clipPlayable != true { return MediaSweepPlan(action: .leave, targetName: nil) }
        return MediaSweepPlan(action: .renameClip, targetName: clean)
    }

    /// 档案「重复件」判据（纯函数），与 Android `archiveTwinExists` 对齐。
    ///
    /// 必须看真实归位目标 `targetName`（`clipName + ".drops.json"`），不能只看「剥段后的档案名」：
    /// 两者不一致（幽灵档案少 `.mp4` 段）时，旧判据查不到目标已存在，rename 会把有效档案覆盖掉。
    public static func archiveTwinExists(
        archiveName: String,
        targetName: String?,
        directoryNames: Set<String>
    ) -> Bool {
        if let target = targetName, target != archiveName, directoryNames.contains(target) { return true }
        let stripped = MediaPendingName.stripPendingJunk(archiveName)
        return stripped != archiveName && stripped != targetName && directoryNames.contains(stripped)
    }

    /// 判「无主档案」前要拿到磁盘上确认的成片候选名（纯函数，单一出口，避免维护层另写一套规则）。
    ///
    /// 结果按顺序为：剥 pending 段后的基名（本身可能就是成片名）、`基名 + ".mp4"`、`基名 + ".mov"`
    /// （剪辑导出允许 `.mov`，其 sidecar 仍是同基名 + `.drops.json`）。基名为空返回空数组。
    ///
    /// 调用方必须用**真实文件系统**查这些名字：名字集合只覆盖本轮枚举到的窗口，且
    /// `Set` 比较区分大小写和 Unicode 归一形式，而 `fileExists` 跟随软链、按卷的大小写规则匹配
    /// （APFS 默认不区分大小写）。删除不可逆，只认磁盘结果。
    public static func sidecarClipProbeNames(archiveName: String) -> [String] {
        let stripped = MediaPendingName.stripPendingJunk(archiveName)
        let base = stripped.hasSuffix(MediaStorageLayout.sidecarExtension)
            ? String(stripped.dropLast(MediaStorageLayout.sidecarExtension.count))
            : stripped
        if base.isEmpty { return [] }
        return [base, base + ".mp4", base + ".mov"]
    }
}
