import Foundation

/// 更新器的**唯一网络实现**（本仓库所有 URLSession 代码只允许出现在 `iOS/WotaGeiCam/Update/`）。
///
/// - 只在**用户点击**「检查更新 / 打开发布页」时被调用；本类型自己不挂定时器、不进后台队列。
/// - 只用 completion-handler 形式的 `URLSession`（无 async/await，最低 iOS 7），探测短超时、失败即弃。
/// - 只访问 `WotaCore.WotaUpdateConfig` 里声明的 **HTTPS** 端点；不跟随重定向（段 3 需要读 Location）。
/// - 不做任何自动下载/安装：下载与安装由使用者在外部完成（unsigned IPA 须重签）。
final class UpdateNetwork: NSObject, URLSessionDataDelegate {

    /// 单次请求超时（探测用短超时，与 Android 侧「逐节点短超时」同精神）。
    static let requestTimeout: TimeInterval = 8
    /// 同步桥接的总等待上限（略大于请求超时）。
    static let overallTimeout: TimeInterval = 12
    /// 探测响应硬上限：GitHub API/镜像 JSON 远小于此值；超限立即取消，避免镜像返回超大包。
    static let maxJSONBytes = 1_024 * 1_024

    /// 一次 JSON 探测的跨线程状态（结果盒 + 信号量）。
    private final class JSONFetch {
        let box = ResultBox()
        let semaphore = DispatchSemaphore(value: 0)
    }

    private let bodyLock = NSLock()
    private var jsonFetches: [Int: JSONFetch] = [:]
    private var bodyBuffers: [Int: Data] = [:]
    private var bodyStatus: [Int: Int] = [:]
    private var bodyTooLarge: Set<Int> = []

    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = UpdateNetwork.requestTimeout
        config.timeoutIntervalForResource = UpdateNetwork.overallTimeout
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.httpAdditionalHeaders = ["User-Agent": "WotaGeiCam-iOS-Updater"]
        return URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }()

    /// 不跟随 302：把 3xx 响应连同 `Location` 原样交回，供段 3 解析 tag。
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }

    /// 只接受 https 端点（明文/自定义 scheme 一律拒绝）。
    private func safeUrl(_ urlString: String) -> URL? {
        guard let url = URL(string: urlString), url.scheme == "https", let host = url.host, !host.isEmpty else {
            return nil
        }
        return url
    }

    /// GET 返回正文；任何失败（连不上/非 2xx/超时/超过 `maxJSONBytes`）返回 nil。
    /// **只允许在后台队列调用**（内部用信号量把异步任务转同步，会阻塞当前线程）。
    func fetchJsonSynchronously(_ urlString: String) -> String? {
        guard let url = safeUrl(urlString) else { return nil }
        let fetch = JSONFetch()
        let task = session.dataTask(with: url)
        let id = task.taskIdentifier
        bodyLock.lock()
        jsonFetches[id] = fetch
        bodyBuffers[id] = Data()
        bodyLock.unlock()
        task.resume()
        if fetch.semaphore.wait(timeout: .now() + UpdateNetwork.overallTimeout) == .timedOut {
            task.cancel()
            discardJSONFetch(id)
            return nil
        }
        return fetch.box.get()
    }

    // MARK: - URLSessionDataDelegate（JSON 探测的流式上限）

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        let id = dataTask.taskIdentifier
        bodyLock.lock()
        let tracked = jsonFetches[id] != nil
        if tracked, let http = response as? HTTPURLResponse {
            bodyStatus[id] = http.statusCode
            if !(200..<300).contains(http.statusCode) || http.expectedContentLength > Int64(UpdateNetwork.maxJSONBytes) {
                bodyTooLarge.insert(id)
                bodyBuffers[id] = Data()
                bodyLock.unlock()
                completionHandler(.cancel)
                return
            }
        }
        bodyLock.unlock()
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        let id = dataTask.taskIdentifier
        bodyLock.lock()
        guard jsonFetches[id] != nil else {
            bodyLock.unlock()
            return
        }
        guard !bodyTooLarge.contains(id) else {
            bodyLock.unlock()
            return
        }
        var buffer = bodyBuffers[id] ?? Data()
        if buffer.count + data.count > UpdateNetwork.maxJSONBytes {
            bodyTooLarge.insert(id)
            bodyBuffers[id] = Data()
            bodyLock.unlock()
            dataTask.cancel()
            return
        }
        buffer.append(data)
        bodyBuffers[id] = buffer
        bodyLock.unlock()
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let id = task.taskIdentifier
        bodyLock.lock()
        let fetch = jsonFetches.removeValue(forKey: id)
        let data = bodyBuffers.removeValue(forKey: id)
        let status = bodyStatus.removeValue(forKey: id)
        let tooLarge = bodyTooLarge.remove(id) != nil
        bodyLock.unlock()
        guard let fetch = fetch else { return }
        if error == nil, !tooLarge,
           let status = status, (200..<300).contains(status),
           let data = data {
            fetch.box.set(String(data: data, encoding: .utf8))
        }
        fetch.semaphore.signal()
    }

    private func discardJSONFetch(_ id: Int) {
        bodyLock.lock()
        jsonFetches.removeValue(forKey: id)
        bodyBuffers.removeValue(forKey: id)
        bodyStatus.removeValue(forKey: id)
        bodyTooLarge.remove(id)
        bodyLock.unlock()
    }

    /// GET 不跟随重定向，返回 `Location` 头；失败 nil。只允许在后台队列调用。
    func fetchRedirectLocationSynchronously(_ urlString: String) -> String? {
        guard let url = safeUrl(urlString) else { return nil }
        let box = ResultBox()
        let semaphore = DispatchSemaphore(value: 0)
        let task = session.dataTask(with: url) { _, response, _ in
            if let http = response as? HTTPURLResponse {
                box.set(http.value(forHTTPHeaderField: "Location"))
            }
            semaphore.signal()
        }
        task.resume()
        if semaphore.wait(timeout: .now() + UpdateNetwork.overallTimeout) == .timedOut {
            task.cancel()
            return nil
        }
        return box.get()
    }

    /// 跨线程结果盒（信号量已提供同步，这里再加锁避免数据竞争警告）。
    private final class ResultBox {
        private let lock = NSLock()
        private var value: String?

        func set(_ newValue: String?) {
            lock.lock()
            value = newValue
            lock.unlock()
        }

        func get() -> String? {
            lock.lock()
            defer { lock.unlock() }
            return value
        }
    }
}
