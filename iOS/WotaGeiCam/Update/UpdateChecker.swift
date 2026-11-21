import Foundation
import UIKit

#if canImport(WotaCore)
import WotaCore
#endif

/// 手动「检查更新」服务（对应 Android `update/UpdateChecker` 的 iOS 数据面）。
///
/// - **只在用户点击时运行一次**：无定时器、无进入页面自动请求、无后台唤醒。
/// - 编排在后台队列执行（网络同步桥接），完成回调**固定在主线程**。
/// - iOS 只做「查版本 + 可选打开发布页 / 打开资产页」：**不做 App 内下载或安装**；
///   unsigned IPA 须由使用者用外部工具重签后安装。外部签名/安装边界见
///   `iOS/docs/modules/F1-settings.md`。
/// - 另提供 `verifyFile(at:expectedSha256:)`：当使用者已用外部工具取得 IPA 时，
///   可用它做端到端 SHA-256 校验（对齐 Android 侧的下载后校验职责，纯本地、无联网）。
final class UpdateChecker {

    static let shared = UpdateChecker()

    private let network = UpdateNetwork()

    private init() {}

    /// 本机版本名（Info.plist `CFBundleShortVersionString`，构建期注入）。
    var localVersionName: String {
        let value = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
        return value ?? "0.0.0"
    }

    /// full / lite（决定挑哪种 IPA 资产）。
    var flavor: String {
        return AppVariant.current.rawValue
    }

    /// 安全 HTTPS 发布页（releases/latest）。UI 可直接跳这里让用户手动取包。
    var releasePageUrlString: String {
        return WotaUpdateConfig.releasesLatestPage
    }

    /// 点按触发：探测 latest release。完成回调在主线程。
    func check(completion: @escaping (WotaProbeResult) -> Void) {
        let network = self.network
        let local = localVersionName
        let flavorName = flavor
        DispatchQueue.global(qos: .userInitiated).async {
            let result = WotaUpdateProbe.probeLatestRelease(
                localVersionName: local,
                fetchJson: { [network] url in network.fetchJsonSynchronously(url) },
                fetchRedirectLocation: { [network] url in network.fetchRedirectLocationSynchronously(url) },
                isCancelled: { false },
                flavor: flavorName
            )
            DispatchQueue.main.async {
                completion(result)
            }
        }
    }

    /// 打开发布页（安全 HTTPS 页面；不自动下载）。
    func openReleasePage() {
        open(urlString: WotaUpdateConfig.releasesLatestPage)
    }

    /// 打开某个 tag 的 release 页（例如探测到新版后）。
    func openTagPage(tag: String) {
        let encoded = tag.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? tag
        open(urlString: "https://github.com/himetuki/WotaGeiCam/releases/tag/\(encoded)")
    }

    /// 打开一枚资产的下载页（仍由使用者手动确认下载；不在 App 内安装）。
    func openAssetDownloadPage(tag: String, assetName: String) {
        open(urlString: WotaUpdateConfig.directDownloadUrl(tag: tag, assetName: assetName))
    }

    /// 用外部工具取得文件后，本地校验 SHA-256（大写/小写/带空格皆容；不联网）。
    func verifyFile(at url: URL, expectedSha256: String) -> Bool {
        guard let data = try? Data(contentsOf: url) else { return false }
        let expected = expectedSha256.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: " ", with: "")
            .lowercased()
        guard expected.count == 64 else { return false }
        return WotaSHA256.hex(data) == expected
    }

    private func open(urlString: String) {
        guard let url = URL(string: urlString), url.scheme == "https" else { return }
        DispatchQueue.main.async {
            UIApplication.shared.open(url, options: [:], completionHandler: nil)
        }
    }
}
