import Foundation
import CoreVideo
import CoreGraphics
#if canImport(WotaCore)
import WotaCore
#endif

// MARK: - 统一帧渲染后端协议
//
// 这是实时取景 / 成片录制（B2）与离线导出（C2）共用的**唯一图像处理入口**。
// 三条链共享同一套算法语义（累积反馈 + 双模式回波 + 拖尾着色 + 抽帧补弧死区合并），
// 但调用方代码独立；后端只做「一帧进、一帧出」的逐帧处理，不碰相机 API、不碰编码器、
// 不改时间戳、不引入音频。
//
// 帧契约（coordinator 集成约定）：
//   - 输入：`kCVPixelFormatType_32BGRA`（BGRA）`CVPixelBuffer`，尺寸任意；
//   - 输出：**同尺寸可写** BGRA `CVPixelBuffer`（``RenderFrameOutput`` 携带真实宽高与像素格式）；
//   - 色彩空间与时间戳由上层持有，后端不读取也不改写；
//   - 可选的预览 `CGImage`（``previewImage(for:)``）仅用于上屏调试，不参与成片。
//
// 线程契约：后端持有一条串行 ``renderQueue``（单一渲染队列），并保证「清黑 / 乒乓 / 尺寸变化释放」
// 都在该队列上发生。调用方必须把 `processEcho` / `processArcMerge` / `resetEchoHistory()` /
// `release()` **全部**投到 ``renderQueue``（`resetEchoHistory()` 与 `release()` 在 OpenGL ES 后端
// 内部也会同步落回该队列，但同一条链上的调用顺序仍应由调用方负责）。
//
// 故障回退：`process*` 是同步且可能抛 ``RenderBackendError``；失败时后端**不会**返回黑帧或空帧。
// 调用方（B2 录制 / C2 离线）收到错误后应：① 保留上一帧或直接把输入帧当作直通输出（`out = frame`），
// ② 用 ``RenderBackendFactory.makeEchoBackend`` 重新探测/换一个后端，③ 不要继续在同一实例上重试已判定
// 不可用的资源。注意 `processEcho` 与 `processArcMerge` **共享同一实例的乒乓累积面**，不要在同一实例上
// 交替驱动两条独立流，否则两条链的累积历史会互相污染；需要并行/交错使用时各取独立后端实例。

/// 后端处理结果。**只带真实像素几何**；色彩空间与呈现时间戳由上层持有。
public struct RenderFrameOutput {
    /// 已处理的可写 BGRA 帧（同输入尺寸）。
    public let pixelBuffer: CVPixelBuffer
    /// 真实像素宽。
    public let width: Int
    /// 真实像素高。
    public let height: Int
    /// 像素格式（恒为 ``kCVPixelFormatType_32BGRA``）。
    public let pixelFormat: OSType

    public init(pixelBuffer: CVPixelBuffer, width: Int, height: Int, pixelFormat: OSType) {
        self.pixelBuffer = pixelBuffer
        self.width = width
        self.height = height
        self.pixelFormat = pixelFormat
    }
}

/// 后端错误。所有失败都是显式错误，绝不静默返回黑帧/空帧。
public enum RenderBackendError: Error, CustomStringConvertible {
    /// 后端在构造时判定不可用（Metal 设备缺失 / EAGLContext 建不出等）。
    case backendNotOperational(RenderBackend)
    /// 输入不是 32BGRA `CVPixelBuffer`。
    case unsupportedPixelFormat(OSType)
    /// 分配输出像素缓冲失败。
    case outputAllocationFailed(width: Int, height: Int)
    /// 离屏渲染目标（纹理 / FBO / 乒乓面）建立失败。
    case renderTargetCreationFailed(reason: String)
    /// 着色器 / 核函数加载或编译失败。
    case shaderLoadFailed(reason: String)
    /// GPU 读回（readback）失败。
    case readbackFailed(reason: String)

    public var description: String {
        switch self {
        case .backendNotOperational(let kind): return "渲染后端不可用：\(kind.displayName)"
        case .unsupportedPixelFormat(let fmt): return "像素格式不支持（需 32BGRA）：\(fmt)"
        case .outputAllocationFailed(let w, let h): return "输出像素缓冲分配失败：\(w)x\(h)"
        case .renderTargetCreationFailed(let reason): return "离屏渲染目标建立失败：\(reason)"
        case .shaderLoadFailed(let reason): return "着色器加载失败：\(reason)"
        case .readbackFailed(let reason): return "GPU 读回失败：\(reason)"
        }
    }
}

/// 统一帧渲染后端。
///
/// 实现类：``MetalFrameBackend``（仅 arm64 编译，A7+ 运行时可用）、
/// ``OpenGLESFrameBackend``（iOS 10 / iPhone 5 可用）、``CPUFrameBackend``（永远可用的兜底）。
/// 通过 ``RenderBackendFactory`` 按运行时能力获取，不直接 new。
public protocol FrameRenderBackend: AnyObject {

    /// 后端种类。
    var kind: RenderBackend { get }

    /// 运行期是否可用（构造成功且关键资源就绪）。`false` 时调用方应改用下一个后端。
    var isOperational: Bool { get }

    /// 单一串行渲染队列：所有 `process*` / ``release()`` 都应在该队列上调用（单一渲染队列约定）。
    var renderQueue: DispatchQueue { get }

    /// 当前累积面的像素尺寸（未就绪 = `.zero`）。仅用于诊断/日志。
    var accumulationSize: CGSize { get }

    /// 清空累积历史（换镜头 / 转屏 / 模式或拖尾档切换 / 显式重开）。
    ///
    /// 依据 `docs/research/R2 §五.2/§五.6`：方向矩阵变、模式变、显式关闭再开启都必须清累积面，
    /// 否则旧朝向/旧模式的残影会被当成本帧历史继续叠进成片。倍数变化**不**清（k 只影响往后的衰减）。
    func resetEchoHistory()

    /// 处理一帧回波（反馈累积 + 双模式合成 + 拖尾着色）。
    ///
    /// - Parameters:
    ///   - frame: 输入 BGRA `CVPixelBuffer`（相机帧 / 解码帧 / 已拷贝的 2D 原始帧）。
    ///   - parameters: 回波参数（``RenderEffectParameters``；内部会 sanitize）。
    /// - Returns: 同尺寸可写 BGRA 输出帧。
    /// - Note: `parameters.isEnabled == false` 时仍返回“输入的原样拷贝”，**不返回黑帧**。
    func processEcho(frame: CVPixelBuffer, parameters: RenderEffectParameters) throws -> RenderFrameOutput

    /// 处理一帧抽帧补弧（差分死区整像素合并），用于录制期转换与离线修复。
    ///
    /// - Parameters:
    ///   - frame: 本帧（被抽帧或保留帧，BGRA）。
    ///   - parameters: 死区参数（``ArcMergeParameters``）。
    /// - Returns: 同尺寸可写 BGRA 输出帧（首帧 = 原样拷贝；其后 = 与累积面的死区选择结果）。
    func processArcMerge(frame: CVPixelBuffer, parameters: ArcMergeParameters) throws -> RenderFrameOutput

    /// 可选：把输出帧转成预览 `CGImage`（仅上屏调试用，不参与成片）。默认实现返回 nil。
    func previewImage(for output: RenderFrameOutput) -> CGImage?

    /// 释放全部后端资源（尺寸变化 / 停止 / 退出）。之后再次 `process*` 会惰性重建。
    func release()
}

public extension FrameRenderBackend {
    func previewImage(for output: RenderFrameOutput) -> CGImage? { return nil }
}

/// 像素格式与几何的公共校验/工具。
enum RenderFrameGeometry {

    /// 输入必须是 32BGRA 单平面。
    static func validateBGRA(_ buffer: CVPixelBuffer) throws -> (width: Int, height: Int) {
        let format = CVPixelBufferGetPixelFormatType(buffer)
        guard format == kCVPixelFormatType_32BGRA else {
            throw RenderBackendError.unsupportedPixelFormat(format)
        }
        return (CVPixelBufferGetWidth(buffer), CVPixelBufferGetHeight(buffer))
    }

    /// 建立一个同尺寸、可写、BGRA 的输出 `CVPixelBuffer`（IOSurface-backed，便于再映射为 GL/Metal 纹理）。
    static func makeBGRAOutput(width: Int, height: Int) throws -> CVPixelBuffer {
        let attributes: [String: Any] = [
            kCVPixelBufferPixelFormatTypeKey as String: Int(kCVPixelFormatType_32BGRA),
            kCVPixelBufferWidthKey as String: width,
            kCVPixelBufferHeightKey as String: height,
            kCVPixelBufferIOSurfacePropertiesKey as String: [String: Any]()
        ]
        var buffer: CVPixelBuffer?
        let status = CVPixelBufferCreate(
            kCFAllocatorDefault, width, height, kCVPixelFormatType_32BGRA,
            attributes as CFDictionary, &buffer
        )
        guard status == kCVReturnSuccess, let output = buffer else {
            throw RenderBackendError.outputAllocationFailed(width: width, height: height)
        }
        return output
    }
}
