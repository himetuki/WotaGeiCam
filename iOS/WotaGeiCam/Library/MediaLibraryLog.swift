import Foundation

/// 数据层日志（真机 os_log 之前的最小实现；不联网、不写外部目录）。
///
/// 对应 Android `diag/Diag`，但这里只保留数据层需要的最小信息级别；
/// 完整诊断/日志轮转属于后续独立任务，不在此实现。
enum MediaLibraryLog {
    static func info(_ message: String) {
        NSLog("[WotaMedia] %@", message)
    }

    static func error(_ message: String) {
        NSLog("[WotaMedia][E] %@", message)
    }
}
