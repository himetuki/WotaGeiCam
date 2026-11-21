import Foundation

/// 「检查更新」的**纯逻辑层**（iOS 移植自 Android `update/UpdateChecker.kt`）。
///
/// 本文件**不含任何网络代码**：网络以闭包**参数注入**（`iOS/WotaGeiCam/Update/` 里的 URLSession
/// 实现提供真身），因此版本比较、资产挑选、说明清洗、302 探测编排都可离线复核。
///
/// iOS 差异（对齐 iOS/README.md 与 docs/mappings/features.md F-024）：
/// - 只认 **`.ipa`** 资产；`.apk` / `.hap` 一律拒选（绝不选非 iOS 包或签名不明包）；
/// - **无 App 内安装**：只做「查版本 + 可选打开 HTTPS 发布页」；下载 unsigned IPA 后由外部工具重签安装；
/// - 发布页回落是**安全 HTTPS 页面**（GitHub releases/latest），不是自动下载；
/// - 探测只准由用户点击触发（无生命周期/进入页面自动请求），下面不挂任何定时器。
public enum WotaUpdateConfig {

    /// 段 1：GitHub API latest release（免登录，公开仓库）。
    public static let repoApiLatest = "https://api.github.com/repos/himetuki/WotaGeiCam/releases/latest"

    /// 段 3：releases/latest 页面（302 → /releases/tag/<tag>）。
    public static let releasesLatestPage = "https://github.com/himetuki/WotaGeiCam/releases/latest"

    /// 下载直连前缀（releases/download/<tag>/<asset>）。
    public static let downloadBase = "https://github.com/himetuki/WotaGeiCam/releases/download"

    /// iOS 更新只接受 unsigned **IPA**；`.apk` / `.hap`（Android / HarmonyOS）一律拒选。
    public static let iosAssetSuffix = ".ipa"

    /// 明显非 iOS 的包后缀（防御性拒选，绝不进下载链）。
    public static let foreignAssetSuffixes = [".apk", ".hap"]

    /// 镜像前缀表（顺序即兜底优先级；**全表 HTTPS**，明文代理不收）。与 Android 名单保持一致。
    public static let mirrorPrefixes: [String] = [
        "https://gh-proxy.com/",
        "https://gh-proxy.org/",
        "https://gh.monlor.com/",
        "https://ghproxy.imciel.com/",
        "https://ghproxy.net/",
        "https://ghfast.top/",
        "https://gh.llkk.cc/",
        "https://ghproxy.cc/",
        "https://gh-proxy.net/",
        "https://hub.gitmirror.com/",
        "https://ghps.cc/",
    ]

    /// 能代理 `api.github.com` 的镜像（探测段 2 专用，逐节点短超时尝试）。
    public static let apiMirrorPrefixes: [String] = [
        "https://gh-proxy.com/",
        "https://gh-proxy.org/",
        "https://ghfast.top/",
    ]

    /// 直连下载 URL（只信仓库常量 + 已校验资产名重建，不信 JSON 里的 URL 字段）。
    public static func directDownloadUrl(tag: String, assetName: String) -> String {
        return "\(downloadBase)/\(tag)/\(assetName)"
    }
}

/// 版本比较（保守：任一侧解析失败 → 不提示）。
public enum WotaVersionCompare {

    /// 远端是否比本机新。tag 形如 `v0.0.7-r09`，本机形如 `0.0.7-r09`。
    public static func isNewerAvailable(localVersionName: String, remoteTag: String) -> Bool {
        guard let cmp = compare(localVersionName: localVersionName, remoteTag: remoteTag) else { return false }
        return cmp > 0
    }

    /// 比较：>0 远端新、=0 相同、<0 本机新；任一侧解析失败返回 nil。
    public static func compare(localVersionName: String, remoteTag: String) -> Int? {
        // 只有本地 lite 构建追加已知 -lite 后缀；远端 tag 仍按原语法严格解析，不吞任意后缀。
        var localName = localVersionName.trimmingCharacters(in: .whitespacesAndNewlines)
        if localName.hasSuffix("-lite") { localName = String(localName.dropLast("-lite".count)) }
        guard let local = parse(localName), let remote = parse(remoteTag) else { return nil }
        for i in 0..<3 {
            if local.main[i] != remote.main[i] {
                if remote.main[i] > local.main[i] { return 1 }
                return -1
            }
        }
        if remote.r == local.r { return 0 }
        return remote.r > local.r ? 1 : -1
    }

    private struct Parsed {
        let main: [Int64]
        let r: Int64
    }

    /// 解析 `v0.0.7-r09` / `0.0.7` / `0.0.7-r02`；主版本必须**恰好三段纯数字**。
    private static func parse(_ version: String) -> Parsed? {
        var s = version.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.count > 1, let first = s.first, first == "v" || first == "V" {
            s = String(s.dropFirst())
        }
        let mainStr: String
        let r: Int64
        if let range = s.range(of: "-r") {
            mainStr = String(s[s.startIndex..<range.lowerBound])
            let rText = String(s[range.upperBound...])
            guard let parsed = Int64(rText) else { return nil }
            r = parsed
        } else {
            mainStr = s
            r = 0
        }
        let parts = mainStr.components(separatedBy: ".")
        guard parts.count == 3 else { return nil }
        var main: [Int64] = []
        for part in parts {
            guard let value = Int64(part) else { return nil }
            main.append(value)
        }
        return Parsed(main: main, r: r)
    }
}

/// fallback 链的单个节点：label 供日志点名，build 把原始 GitHub URL 变换成本节点的实际 URL。
public final class WotaMirrorNode {
    public let label: String
    private let build: (String) -> String

    public init(label: String, build: @escaping (String) -> String) {
        self.label = label
        self.build = build
    }

    public func urlFor(rawUrl: String) -> String {
        return build(rawUrl)
    }
}

/// 有序 fallback 链状态机（探测段 2 与下载链共用）。
public final class WotaMirrorChain {
    private let nodes: [WotaMirrorNode]
    public private(set) var cursor = 0

    public init(nodes: [WotaMirrorNode]) {
        precondition(!nodes.isEmpty, "fallback 链至少要有一个节点")
        self.nodes = nodes
    }

    public var exhausted: Bool { return cursor >= nodes.count }
    public var currentLabel: String { return nodes[cursor].label }

    public func urlFor(rawUrl: String) -> String { return nodes[cursor].urlFor(rawUrl: rawUrl) }

    /// 移到下一个节点；已耗尽返回 false。
    @discardableResult
    public func advance() -> Bool {
        if exhausted { return false }
        cursor += 1
        return !exhausted
    }

    public func reset() { cursor = 0 }

    /// 探测段 2 的镜像链（无直连——直连是段 1 专职）。
    public static func probeChain() -> WotaMirrorChain {
        return WotaMirrorChain(nodes: WotaUpdateConfig.apiMirrorPrefixes.map { prefix in
            WotaMirrorNode(label: prefix) { url in prefix + url }
        })
    }
}

/// release 附件（JSON assets[] 里的一枚，或段 3 推导出来的）。
public struct WotaReleaseAsset {
    public let name: String
    public let url: String
    public let sizeBytes: Int64?

    public init(name: String, url: String, sizeBytes: Int64?) {
        self.name = name
        self.url = url
        self.sizeBytes = sizeBytes
    }
}

/// latest release 的最小结论。
public struct WotaReleaseInfo {
    public let tag: String
    public let asset: WotaReleaseAsset?
    /// 发版者写在 Release 说明里的 SHA-256（十六进制小写）。只有段 1 官方端点才提取。
    public let sha256: String?
    /// 说明原文（Markdown，未加工），供更新弹窗展示。
    public let notes: String?

    public init(tag: String, asset: WotaReleaseAsset?, sha256: String? = nil, notes: String? = nil) {
        self.tag = tag
        self.asset = asset
        self.sha256 = sha256
        self.notes = notes
    }
}

/// GitHub releases/latest JSON 的免依赖提取器（手写，只认我们关心的几个键）。
public enum WotaReleaseJson {

    public static func parseTag(_ json: String) -> String? {
        return stringValueAfterKey(json, "tag_name")
    }

    public static func parseAssets(_ json: String) -> [WotaReleaseAsset] {
        let chars = Array(json)
        guard let arrStart = indexOfValueAfterKey(chars, "assets", "[") else { return [] }
        guard let arrEnd = matchingBracket(chars, arrStart, "[", "]") else { return [] }
        let arr = Array(chars[(arrStart + 1)..<arrEnd])
        var out: [WotaReleaseAsset] = []
        var i = 0
        while i < arr.count {
            if arr[i] == "\u{7B}" {
                guard let end = matchingBracket(arr, i, "\u{7B}", "\u{7D}") else { break }
                let obj = String(arr[i...end])
                if let name = stringValueAfterKey(obj, "name"),
                   let url = stringValueAfterKey(obj, "browser_download_url") {
                    out.append(WotaReleaseAsset(name: name, url: url, sizeBytes: numberValueAfterKey(obj, "size")))
                }
                i = end + 1
            } else {
                i += 1
            }
        }
        return out
    }

    /// 资产名是否可安全拼进下载 URL（纯函数）。只接受**纯文件名 + `.ipa`**：
    /// 不含 `/`、`\`、连续点，长度有上限，且显式拒绝 `.apk` / `.hap`。
    public static func isSafeReleaseAssetName(_ name: String?) -> Bool {
        guard let name = name else { return false }
        if name.isEmpty || name.count > 255 { return false }
        if name.contains("/") || name.contains("\\") { return false }
        if name.contains("..") { return false }
        let lowered = name.lowercased()
        for suffix in WotaUpdateConfig.foreignAssetSuffixes where lowered.hasSuffix(suffix) { return false }
        return lowered.hasSuffix(WotaUpdateConfig.iosAssetSuffix)
    }

    /// 挑 IPA 附件（三态与 Android 一致，仅把 `.apk` 换成 `.ipa`）：
    /// - lite：只在 `-lite-` 附件里挑，universal 优先；无 → nil（不提示，保守）；
    /// - full：优先 `-full-` 附件（universal 优先），其次无 flavor 的历史通用包；**绝不推 lite 包**；
    /// - nil：优先 universal，无则第一个 `.ipa`。
    public static func pickIPAAsset(_ assets: [WotaReleaseAsset], flavor: String? = nil) -> WotaReleaseAsset? {
        let ipas = assets.filter { isSafeReleaseAssetName($0.name) }
        if flavor == "lite" {
            let lite = ipas.filter { $0.name.range(of: "-lite-", options: .caseInsensitive) != nil }
            return lite.first(where: { $0.name.range(of: "universal", options: .caseInsensitive) != nil }) ?? lite.first
        }
        if flavor == "full" {
            let full = ipas.filter { $0.name.range(of: "-full-", options: .caseInsensitive) != nil }
            if !full.isEmpty {
                return full.first(where: { $0.name.range(of: "universal", options: .caseInsensitive) != nil }) ?? full.first
            }
            let historical = ipas.filter {
                $0.name.range(of: "-lite", options: .caseInsensitive) == nil
            }
            return historical.first(where: { $0.name.range(of: "universal", options: .caseInsensitive) != nil })
                ?? historical.first
        }
        return ipas.first(where: { $0.name.range(of: "universal", options: .caseInsensitive) != nil }) ?? ipas.first
    }

    public static func parse(_ json: String) -> WotaReleaseInfo? {
        return parse(json, extractSha256: false, flavor: nil)
    }

    /// [extractSha256] 仅段 1（api.github.com 官方端点）为 true：只有那时 body 里的 SHA-256 才可信。
    public static func parse(_ json: String, extractSha256: Bool, flavor: String? = nil) -> WotaReleaseInfo? {
        guard let tag = parseTag(json) else { return nil }
        let body = stringValueAfterKey(json, "body")
        var sha: String? = nil
        if extractSha256, let body = body {
            sha = parseSha256FromNotes(body, flavor: flavor)
        }
        return WotaReleaseInfo(tag: tag, asset: pickIPAAsset(parseAssets(json), flavor: flavor), sha256: sha, notes: body)
    }

    /// 从 Release 说明文本提取 SHA-256（纯函数）。
    /// - `SHA256-lite:` lite 变体专用行（只认本行）；
    /// - `SHA256-full:` full 变体专用行（只认本行；无则判无哈希，绝不回退通用行）；
    /// - `SHA256:`      通用行（旧调用/未知变体）。
    public static func parseSha256FromNotes(_ notes: String, flavor: String? = nil) -> String? {
        if flavor == "lite" {
            return firstHexMatch(notes, "SHA256-lite\\s*[:：]\\s*([0-9a-fA-F]{64})(?![0-9a-fA-F])")
        }
        if flavor == "full" {
            return firstHexMatch(notes, "SHA256-full\\s*[:：]\\s*([0-9a-fA-F]{64})(?![0-9a-fA-F])")
        }
        return firstHexMatch(notes, "SHA256\\s*[:：]\\s*([0-9a-fA-F]{64})(?![0-9a-fA-F])")
    }

    // MARK: - 手写 JSON 提取

    private static func firstHexMatch(_ text: String, _ pattern: String) -> String? {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return nil }
        let ns = text as NSString
        guard let match = regex.firstMatch(in: text, range: NSRange(location: 0, length: ns.length)) else { return nil }
        guard match.numberOfRanges >= 2 else { return nil }
        let range = match.range(at: 1)
        if range.location == NSNotFound { return nil }
        return ns.substring(with: range).lowercased()
    }

    private static func stringValueAfterKey(_ json: String, _ key: String) -> String? {
        let chars = Array(json)
        let needle = Array("\"\(key)\"")
        var i = indexOf(chars, needle, from: 0)
        while i >= 0 {
            if let v = valueStartAfterColon(chars, i + needle.count), chars[v] == "\"" {
                return readString(chars, v)
            }
            i = indexOf(chars, needle, from: i + needle.count)
        }
        return nil
    }

    private static func numberValueAfterKey(_ json: String, _ key: String) -> Int64? {
        let chars = Array(json)
        let needle = Array("\"\(key)\"")
        var i = indexOf(chars, needle, from: 0)
        while i >= 0 {
            if let v = valueStartAfterColon(chars, i + needle.count) {
                var j = v
                while j < chars.count && chars[j].isNumber { j += 1 }
                if j > v { return Int64(String(chars[v..<j])) }
            }
            i = indexOf(chars, needle, from: i + needle.count)
        }
        return nil
    }

    private static func valueStartAfterColon(_ chars: [Character], _ fromKeyEnd: Int) -> Int? {
        var j = fromKeyEnd
        while j < chars.count && chars[j].isWhitespace { j += 1 }
        if j >= chars.count || chars[j] != ":" { return nil }
        j += 1
        while j < chars.count && chars[j].isWhitespace { j += 1 }
        return j < chars.count ? j : nil
    }

    private static func indexOfValueAfterKey(_ chars: [Character], _ key: String, _ valueChar: Character) -> Int? {
        let needle = Array("\"\(key)\"")
        var i = indexOf(chars, needle, from: 0)
        while i >= 0 {
            if let v = valueStartAfterColon(chars, i + needle.count), chars[v] == valueChar { return v }
            i = indexOf(chars, needle, from: i + needle.count)
        }
        return nil
    }

    private static func indexOf(_ chars: [Character], _ needle: [Character], from: Int) -> Int {
        if needle.isEmpty || chars.count < needle.count { return -1 }
        var i = from
        while i <= chars.count - needle.count {
            var matched = true
            for k in 0..<needle.count where chars[i + k] != needle[k] { matched = false; break }
            if matched { return i }
            i += 1
        }
        return -1
    }

    private static func readString(_ chars: [Character], _ openQuote: Int) -> String? {
        var out = ""
        var i = openQuote + 1
        while i < chars.count {
            let c = chars[i]
            if c == "\\" && i + 1 < chars.count {
                switch chars[i + 1] {
                case "n": out.append("\n")
                case "t": out.append("\t")
                case "r": out.append("\r")
                default: out.append(chars[i + 1])
                }
                i += 2
            } else if c == "\"" {
                return out
            } else {
                out.append(c)
                i += 1
            }
        }
        return nil
    }

    private static func matchingBracket(
        _ chars: [Character], _ open: Int, _ openCh: Character, _ closeCh: Character
    ) -> Int? {
        var depth = 0
        var i = open
        var inStr = false
        while i < chars.count {
            let c = chars[i]
            if inStr && c == "\\" {
                i += 1
            } else if c == "\"" {
                inStr = !inStr
            } else if !inStr && c == openCh {
                depth += 1
            } else if !inStr && c == closeCh {
                depth -= 1
                if depth == 0 { return i }
            }
            i += 1
        }
        return nil
    }
}

/// Release 说明 → 更新弹窗里的纯文本（去标记、不重排内容；机器用的 SHA-256 行整行隐去）。
public enum WotaReleaseNotes {

    /// 弹窗最多展示的字数。
    public static let maxDisplayChars = 4000

    private static let hashLine = "SHA256(-full|-lite)?\\s*[:：]\\s*[0-9a-fA-F]{64}(?![0-9a-fA-F])"
    private static let hashLabelOnly = "^.*SHA256\\s*[:：]\\s*$"
    private static let heading = "^\\s*#{1,6}\\s+"
    private static let bullet = "^(\\s*)[-*+]\\s+"
    private static let rule = "^[-*_]{3,}$"

    public static func forDisplay(_ raw: String?) -> String? {
        guard let raw = raw, !raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        let normalized = raw.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
        var out: [String] = []
        for line in normalized.components(separatedBy: "\n") {
            let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
            if containsMatch(trimmed, hashLine) || fullMatch(trimmed, hashLabelOnly) { continue }
            if trimmed.isEmpty { out.append(""); continue }
            if fullMatch(trimmed, rule) { continue }
            var s = trimTrailingWhitespace(line)
            s = s.replacingOccurrences(of: heading, with: "", options: .regularExpression)
            s = s.replacingOccurrences(of: bullet, with: "$1\u{2022} ", options: .regularExpression)
            s = s.replacingOccurrences(of: "**", with: "")
                .replacingOccurrences(of: "__", with: "")
                .replacingOccurrences(of: "`", with: "")
            out.append(s)
        }
        var text = out.joined(separator: "\n")
        text = text.replacingOccurrences(of: "\n{3,}", with: "\n\n", options: .regularExpression)
        text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { return nil }
        if text.count <= maxDisplayChars { return text }
        return truncateAtLine(text)
    }

    private static func containsMatch(_ text: String, _ pattern: String) -> Bool {
        return text.range(of: pattern, options: .regularExpression) != nil
    }

    private static func fullMatch(_ text: String, _ pattern: String) -> Bool {
        guard let range = text.range(of: pattern, options: .regularExpression) else { return false }
        return range.lowerBound == text.startIndex && range.upperBound == text.endIndex
    }

    private static func trimTrailingWhitespace(_ text: String) -> String {
        var end = text.endIndex
        while end > text.startIndex {
            let prev = text.index(before: end)
            if text[prev].isWhitespace { end = prev } else { break }
        }
        return String(text[text.startIndex..<end])
    }

    private static func truncateAtLine(_ text: String) -> String {
        var cut = min(maxDisplayChars, text.count)
        let limited = text.prefix(cut)
        if let lastNewline = limited.lastIndex(of: "\n") {
            let offset = limited[limited.startIndex..<lastNewline].count
            if offset > cut / 2 { cut = offset }
        }
        let head = trimTrailingWhitespace(String(text.prefix(cut)))
        return head + "\n\u{2026}"
    }
}

/// 段 3：releases/latest 页 302 → tag 解析与资产名推导。
public enum WotaRedirectProbe {

    /// `.../releases/tag/v0.0.8-r01`（尾斜杠容忍）→ `v0.0.8-r01`；不是 tag 路径返回 nil。
    public static func tagFromLocation(_ location: String) -> String? {
        let marker = "/releases/tag/"
        guard let range = location.range(of: marker) else { return nil }
        var tag = String(location[range.upperBound...])
        if tag.hasSuffix("/") { tag = String(tag.dropLast()) }
        if tag.isEmpty || tag.contains("/") { return nil }
        return tag
    }

    /// 段 3 无 JSON 资产列表，IPA 名从 tag 推导。
    /// 必须与 `.github/workflows/build-ipa.yml` 的实际产物一致：
    /// `<prefix>-unsigned-iOS-WotaGeiCam-<Full-Modern|Lite-Modern>.ipa`（prefix 即 tag）。
    public static func guessUniversalAssetName(tag: String, flavor: String? = nil) -> String {
        let label = flavor == "lite" ? "Lite-Modern" : "Full-Modern"
        return "\(tag)-unsigned-iOS-WotaGeiCam-\(label).ipa"
    }
}

/// 探测结论：UI 只消费这个。
public enum WotaProbeResult {
    /// 远端有新版。
    case updateAvailable(
        latestTag: String,
        assetName: String,
        downloadUrl: String,
        sizeBytes: Int64?,
        sha256: String?,
        notes: String?
    )
    /// 已是最新。
    case upToDate
    /// 三段 fallback 全失败（或拿不到可下载的 IPA）。
    case failed
    /// 用户取消。
    case cancelled

    public var isUpdateAvailable: Bool {
        if case .updateAvailable = self { return true }
        return false
    }
}

/// 段 1→2→3 编排。网络以两个闭包注入。**无自动触发**：只由用户点击调用方驱动。
public enum WotaUpdateProbe {

    public static func probeLatestRelease(
        localVersionName: String,
        fetchJson: (String) -> String?,
        fetchRedirectLocation: (String) -> String?,
        isCancelled: () -> Bool = { false },
        flavor: String? = nil
    ) -> WotaProbeResult {
        // 段 1：直连 GitHub API（官方端点：Release 说明 body 可信）。
        if isCancelled() { return .cancelled }
        if let json = fetchJson(WotaUpdateConfig.repoApiLatest),
           let result = jsonNode(json, localVersionName, extractSha256: true, flavor: flavor) {
            return result
        }
        // 段 2：镜像前缀逐个代理同一 API（extractSha256=false：镜像可改写 body）。
        let chain = WotaMirrorChain.probeChain()
        while !chain.exhausted {
            if isCancelled() { return .cancelled }
            if let json = fetchJson(chain.urlFor(rawUrl: WotaUpdateConfig.repoApiLatest)),
               let result = jsonNode(json, localVersionName, extractSha256: false, flavor: flavor) {
                return result
            }
            chain.advance()
        }
        // 段 3：302 → tag → 推导资产名（无 size）。
        if isCancelled() { return .cancelled }
        if let location = fetchRedirectLocation(WotaUpdateConfig.releasesLatestPage),
           let tag = WotaRedirectProbe.tagFromLocation(location) {
            let assetName = WotaRedirectProbe.guessUniversalAssetName(tag: tag, flavor: flavor)
            let asset = WotaReleaseAsset(
                name: assetName,
                url: WotaUpdateConfig.directDownloadUrl(tag: tag, assetName: assetName),
                sizeBytes: nil
            )
            return decide(WotaReleaseInfo(tag: tag, asset: asset), localVersionName: localVersionName)
        }
        return .failed
    }

    /// 一个 JSON 节点的结论：nil = 该节点无结论（解析失败 / 有新版但拿不到 IPA），继续下一段。
    private static func jsonNode(
        _ json: String, _ localVersionName: String, extractSha256: Bool, flavor: String?
    ) -> WotaProbeResult? {
        guard let info = WotaReleaseJson.parse(json, extractSha256: extractSha256, flavor: flavor) else { return nil }
        if !WotaVersionCompare.isNewerAvailable(localVersionName: localVersionName, remoteTag: info.tag) {
            return .upToDate
        }
        guard let asset = info.asset else { return nil }
        // 段 2 的 JSON 可能被镜像改写：URL 字段一律不信，用可信仓库常量 + 已校验资产名重建。
        let url = WotaUpdateConfig.directDownloadUrl(tag: info.tag, assetName: asset.name)
        return .updateAvailable(
            latestTag: info.tag, assetName: asset.name, downloadUrl: url,
            sizeBytes: asset.sizeBytes, sha256: info.sha256, notes: info.notes
        )
    }

    private static func decide(_ info: WotaReleaseInfo, localVersionName: String) -> WotaProbeResult {
        guard let asset = info.asset else { return .failed }
        if !WotaVersionCompare.isNewerAvailable(localVersionName: localVersionName, remoteTag: info.tag) {
            return .upToDate
        }
        return .updateAvailable(
            latestTag: info.tag, assetName: asset.name,
            downloadUrl: WotaUpdateConfig.directDownloadUrl(tag: info.tag, assetName: asset.name),
            sizeBytes: asset.sizeBytes, sha256: nil, notes: nil
        )
    }
}
