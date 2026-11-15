import Foundation

/// full / lite 两个变体由 Xcode 构建配置注入的编译条件区分。
/// 当前骨架尚未包含 AI 站位能力，因此两变体只有构建配置和元数据差异，
/// 不提供任何 AI 占位接口；后续功能分流从这里读取变体。
enum AppVariant: String {
    case full
    case lite

    static var current: AppVariant {
        #if WOTA_LITE
        return .lite
        #else
        return .full
        #endif
    }

    var badgeText: String {
        switch self {
        case .full:
            return "FULL"
        case .lite:
            return "LITE"
        }
    }
}
