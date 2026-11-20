import Foundation
#if canImport(WotaCore)
import WotaCore
#endif

#if arch(arm64) && canImport(Metal)
import Metal
#endif

#if canImport(OpenGLES)
import OpenGLES
#endif

// MARK: - 运行时图形能力探测
//
// 关键约束（ROADMAP F-031 / PD-011）：iPhone 5 / 5c（A6，armv7）**没有 Metal**，
// 不得走 Metal 路径。这里的 Metal 探测用 **编译期 `#if arch(arm64)`** 隔离——
// armv7 目标根本不 `import Metal`、根本不引用 Metal 符号，避免链接/编译风险；
// 运行期再叠加一次 `MTLCreateSystemDefaultDevice()` 检查（A7+ 才同时满足）。

/// 运行时图形能力快照（纯数据）。
public struct RenderRuntimeCapabilities {

    /// 是否具备 Metal（编译期 arm64 **且** 运行期有默认设备）。
    public let supportsMetal: Bool

    /// 是否可用 OpenGL ES 2（能在本机建出 `EAGLContext`）。
    public let supportsOpenGLES: Bool

    public init(supportsMetal: Bool, supportsOpenGLES: Bool) {
        self.supportsMetal = supportsMetal
        self.supportsOpenGLES = supportsOpenGLES
    }

    /// 探测当前进程/设备的图形能力。
    ///
    /// - Note: CPU 兜底永远可用，故 ``RenderBackendPolicy/preferredBackends(supportsMetal:supportsOpenGLES:)``
    ///   的结果不会为空。
    public static func current() -> RenderRuntimeCapabilities {
        return RenderRuntimeCapabilities(
            supportsMetal: probeMetal(),
            supportsOpenGLES: probeOpenGLES()
        )
    }

    /// 本机按优先级可用的后端列表（Metal → OpenGL ES → CPU）。
    public var availableKinds: [RenderBackend] {
        return RenderBackendPolicy.preferredBackends(
            supportsMetal: supportsMetal,
            supportsOpenGLES: supportsOpenGLES
        )
    }

    /// Metal 探测：armv7 编译期直接返回 false（不 import Metal，不引用符号）。
    private static func probeMetal() -> Bool {
        #if arch(arm64) && canImport(Metal)
        return MTLCreateSystemDefaultDevice() != nil
        #else
        return false
        #endif
    }

    /// OpenGL ES 探测：尝试建一个 ES2 上下文后立即丢弃。
    private static func probeOpenGLES() -> Bool {
        #if canImport(OpenGLES)
        guard let context = EAGLContext(api: .openGLES2) else { return false }
        // 建出来即视为可用；不驻留上下文（真正后端自己持有）。
        _ = context
        return true
        #else
        return false
        #endif
    }
}
