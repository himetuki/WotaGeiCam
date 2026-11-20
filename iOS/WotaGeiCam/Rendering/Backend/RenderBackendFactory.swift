import Foundation
#if canImport(WotaCore)
import WotaCore
#endif

// MARK: - 统一后端工厂
//
// 按运行时能力在 `.metal`（仅 arm64 编译）/ `.openGLES` / `.cpu` 之间选择，并返回其统一协议实例。
// CPU 永远是兜底，故本工厂**不会返回 nil**，也**不会返回永远黑/空的占位后端**。

public enum RenderBackendFactory {

    /// 本机可用的后端列表（Metal → OpenGL ES → CPU）。iPhone 5（armv7）下不含 Metal。
    public static func availableKinds(
        capabilities: RenderRuntimeCapabilities = RenderRuntimeCapabilities.current()
    ) -> [RenderBackend] {
        return capabilities.availableKinds
    }

    /// 构造一个后端实例，优先 `preferred`（若其可用），否则按能力顺序回退到第一个可用后端。
    ///
    /// - Parameters:
    ///   - preferred: 调用方希望优先使用的后端（例如设置里强制 CPU）。`nil` = 让工厂择优。
    ///   - capabilities: 注入的能力快照（便于上层复用一次探测结果）。
    /// - Returns: 第一个 `isOperational == true` 的后端；最坏情形为 CPU 后端。
    public static func makeBackend(
        preferred: RenderBackend? = nil,
        capabilities: RenderRuntimeCapabilities = RenderRuntimeCapabilities.current()
    ) -> FrameRenderBackend {
        var candidates = capabilities.availableKinds
        if let preferred = preferred, let index = candidates.firstIndex(of: preferred) {
            candidates.remove(at: index)
            candidates.insert(preferred, at: 0)
        }

        for kind in candidates {
            if let backend = instantiate(kind), backend.isOperational {
                return backend
            }
        }
        // 理论上不可达：可用列表至少含 CPU，而 CPU 构造恒可用。留一线防御。
        return CPUFrameBackend()
    }

    /// 便捷入口：直接给实时录制 / 离线导出用的回波后端。
    public static func makeEchoBackend(
        preferred: RenderBackend? = nil,
        capabilities: RenderRuntimeCapabilities = RenderRuntimeCapabilities.current()
    ) -> FrameRenderBackend {
        return makeBackend(preferred: preferred, capabilities: capabilities)
    }

    /// 单种类实例化。Metal 分支在 armv7 编译期被整段排除。
    private static func instantiate(_ kind: RenderBackend) -> FrameRenderBackend? {
        switch kind {
        case .metal:
            #if arch(arm64) && canImport(Metal)
            return MetalFrameBackend()
            #else
            // armv7（iPhone 5）：没有 Metal，编译期就到此为止。
            return nil
            #endif
        case .openGLES:
            #if canImport(OpenGLES)
            return OpenGLESFrameBackend()
            #else
            return nil
            #endif
        case .cpu:
            return CPUFrameBackend()
        }
    }
}
