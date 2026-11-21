import AVFoundation
import UIKit

/// 真实 `AVCaptureVideoPreviewLayer` 宿主（UIKit，兼容 iOS 10）。
///
/// 不使用 SwiftUI / `UIViewRepresentable`；相机就绪后直接显示实时画面，
/// 不提供截图或占位图。
final class CameraPreviewView: UIView {

    override class var layerClass: AnyClass {
        AVCaptureVideoPreviewLayer.self
    }

    var previewLayer: AVCaptureVideoPreviewLayer {
        guard let layer = layer as? AVCaptureVideoPreviewLayer else {
            fatalError("layerClass must be AVCaptureVideoPreviewLayer")
        }
        return layer
    }

    var session: AVCaptureSession? {
        get { previewLayer.session }
        set { previewLayer.session = newValue }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        configure()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        configure()
    }

    /// 主线程获取图层的连接。方向和防抖都由会话队列管理。
    func registerConnection(with controller: CameraSessionController) {
        controller.setPreviewVideoConnection(previewLayer.connection)
    }

    private func configure() {
        backgroundColor = .black
        previewLayer.videoGravity = .resizeAspectFill
    }
}
