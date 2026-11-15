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

    /// 当前工程锁定竖屏取景。
    ///
    /// `AVCaptureConnection.videoOrientation` 自 iOS 6 起可用，覆盖 iOS 10 及以上；
    /// iOS 17 起官方推荐 `videoRotationAngle`，但那需要支持 iOS 17 SDK 的构建，
    /// 这里不使用，以保证 legacy（iOS 10）工具链也能编译。
    func updateVideoOrientation() {
        guard let connection = previewLayer.connection else { return }
        if connection.isVideoOrientationSupported {
            connection.videoOrientation = .portrait
        }
    }

    private func configure() {
        backgroundColor = .black
        previewLayer.videoGravity = .resizeAspectFill
    }
}
