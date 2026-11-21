import Foundation

/// 渲染 / 图像处理后端。
///
/// **关键约束**：iPhone 5 / 5c（A6）没有 Metal（Metal 需要 A7，即 iPhone 5s 起），
/// 因此 Metal 不能作为唯一后端；后续渲染任务必须保留 OpenGL ES（iOS 10 仍可用）或
/// CPU 路径作为降级。
public enum RenderBackend: String, Equatable, Sendable, CaseIterable {
    case metal
    case openGLES
    case cpu

    public var displayName: String {
        switch self {
        case .metal:
            return "Metal"
        case .openGLES:
            return "OpenGL ES"
        case .cpu:
            return "CPU"
        }
    }
}

/// 后端选择策略：纯逻辑，不依赖任何图形 API，便于后续独立验证。
public enum RenderBackendPolicy {

    /// 按优先级返回可用后端；CPU 永远是最后兜底，因此结果不会为空。
    ///
    /// - Parameters:
    ///   - supportsMetal: 设备与系统是否提供 Metal（A7 及以上，iOS 8+）。
    ///   - supportsOpenGLES: 是否可用 OpenGL ES（iOS 10 的 iPhone 5 可用，但已被 Apple 弃用）。
    public static func preferredBackends(
        supportsMetal: Bool,
        supportsOpenGLES: Bool
    ) -> [RenderBackend] {
        var backends: [RenderBackend] = []
        if supportsMetal {
            backends.append(.metal)
        }
        if supportsOpenGLES {
            backends.append(.openGLES)
        }
        backends.append(.cpu)
        return backends
    }

    /// 该设备是否只能走 OpenGL ES / CPU（即 A6 级别的 iPhone 5/5c）。
    public static func requiresLegacyGraphicsFallback(supportsMetal: Bool) -> Bool {
        return !supportsMetal
    }
}
