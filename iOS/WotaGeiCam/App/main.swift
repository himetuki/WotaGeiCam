import UIKit

// 兼容 iOS 10 的入口：直接调用 C 函数 `UIApplicationMain`，
// 不依赖 `@UIApplicationMain` / `@main` 在不同 Xcode 版本上的语法差异，
// 也不使用 SwiftUI 的 `App` 协议（SwiftUI 最低 iOS 13）。
UIApplicationMain(
    CommandLine.argc,
    CommandLine.unsafeArgv,
    nil,
    NSStringFromClass(AppDelegate.self)
)
