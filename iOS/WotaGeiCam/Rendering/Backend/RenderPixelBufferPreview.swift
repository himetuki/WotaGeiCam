import Foundation
import CoreVideo
import CoreGraphics

// MARK: - 输出帧 → 预览 CGImage（可选，仅上屏调试）
//
// 不依赖 CoreImage / CoreGraphics 以外的框架。用于把后端产出的 BGRA 帧快速转成 CGImage 显示，
// **不参与成片**。默认后端不实现；CPU 后端与需要上屏调试的调用方可用。

public enum RenderPixelBufferPreview {

    /// BGRA `CVPixelBuffer` → `CGImage`（拷贝像素；调用方不复用后即可释放缓冲）。
    public static func makeCGImage(from buffer: CVPixelBuffer) -> CGImage? {
        let format = CVPixelBufferGetPixelFormatType(buffer)
        guard format == kCVPixelFormatType_32BGRA else { return nil }
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(buffer) else { return nil }
        let width = CVPixelBufferGetWidth(buffer)
        let height = CVPixelBufferGetHeight(buffer)
        let bytesPerRow = CVPixelBufferGetBytesPerRow(buffer)
        let bitmapInfo = CGBitmapInfo(
            rawValue: CGImageAlphaInfo.premultipliedFirst.rawValue
                | CGBitmapInfo.byteOrder32Little.rawValue
        )
        guard let context = CGContext(
            data: base,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: bytesPerRow,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: bitmapInfo.rawValue
        ) else {
            return nil
        }
        return context.makeImage()
    }
}
