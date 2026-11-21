import UIKit

/// UIKit 应用入口（iOS 10 基线，无 UIScene / 无 SwiftUI）。
/// 根控制器为 [CameraPreviewViewController]：真实相机预览 + 权限/生命周期/错误反馈。
final class AppDelegate: UIResponder, UIApplicationDelegate {

    var window: UIWindow?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.backgroundColor = .black
        window.rootViewController = CameraPreviewViewController()
        window.makeKeyAndVisible()
        self.window = window
        MediaLibrary.runStartupMaintenance()
        return true
    }
}
