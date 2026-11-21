import AVFoundation

/// 相机权限访问的薄封装；用协议隔开平台调用，便于后续替换或注入。
protocol CameraPermissionProviding {
    var authorizationStatus: AVAuthorizationStatus { get }
    func requestAccess(_ completion: @escaping (Bool) -> Void)
}

struct CameraPermissionService: CameraPermissionProviding {
    var authorizationStatus: AVAuthorizationStatus {
        AVCaptureDevice.authorizationStatus(for: .video)
    }

    func requestAccess(_ completion: @escaping (Bool) -> Void) {
        AVCaptureDevice.requestAccess(for: .video, completionHandler: completion)
    }
}
