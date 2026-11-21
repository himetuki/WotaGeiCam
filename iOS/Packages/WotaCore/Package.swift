// swift-tools-version: 5.6
import PackageDescription

// WotaCore 只容纳不依赖 UIKit / AVFoundation 的纯逻辑，便于独立编译与复用。
//
// 工具版本刻意停在 5.6（Xcode 13.4.1 自带 Swift 5.6.1），这样 legacy iOS 10 工具链
// 也能解析本清单；源码同时兼容 Swift 5.6，不使用 5.7 的简写语法。
// 平台下限取 iOS 10，与 legacy armv7 目标一致，不使用虚假平台号。
// 按当前交付指令不提供测试 target，也不引入任何第三方依赖。
let package = Package(
    name: "WotaCore",
    platforms: [
        .iOS(.v10),
        .macOS(.v10_13)
    ],
    products: [
        .library(name: "WotaCore", targets: ["WotaCore"])
    ],
    targets: [
        .target(name: "WotaCore", path: "Sources/WotaCore")
    ]
)
