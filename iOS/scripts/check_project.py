#!/usr/bin/env python3
"""WotaGeiCam iOS 工程结构检查（UIKit / iOS 10 基线，经典 Xcode 工程）。

只做静态结构与 plist/工程文件一致性校验；不冒充 Swift 编译、模拟器运行或真机验证。
退出码：0 = 全部检查通过；1 = 有检查失败。
"""

from __future__ import annotations

import plistlib
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

IOS_ROOT = Path(__file__).resolve().parent.parent
PROJECT = IOS_ROOT / "WotaGeiCam.xcodeproj" / "project.pbxproj"
INFO_PLIST = IOS_ROOT / "Config" / "Info.plist"
APP_DIR = IOS_ROOT / "WotaGeiCam"
SCHEME_DIR = IOS_ROOT / "WotaGeiCam.xcodeproj" / "xcshareddata" / "xcschemes"
GENERATOR = IOS_ROOT / "scripts" / "generate_project.py"

FAILURES: list[str] = []
CHECKS = 0

# 明确禁止的“新 API / 新框架”标记：iOS 10 基础路径不得使用。
FORBIDDEN_TOKENS = [
    "import SwiftUI",
    "import Combine",
    "@MainActor",
    "@StateObject",
    "@ObservedObject",
    "UIViewRepresentable",
    "import UniformTypeIdentifiers",
    "UTType",
    "PHPicker",
    "PHPhotoLibrary",
    " await ",
    "async let",
    "Task {",
    "Task<",
    "AsyncStream",
    "asyncSequence",
]

SWIFTUI_FILES_THAT_MUST_BE_GONE = [
    "WotaGeiCam/App/WotaGeiCamApp.swift",
    "WotaGeiCam/Camera/CameraViewModel.swift",
    "WotaGeiCam/Camera/CameraPreviewScreen.swift",
    "WotaGeiCam/UI/CameraOverlay.swift",
]


def check(condition: bool, message: str) -> bool:
    global CHECKS
    CHECKS += 1
    if not condition:
        FAILURES.append(message)
    return condition


def check_file(relative: str) -> None:
    check((IOS_ROOT / relative).is_file(), f"缺少文件：{relative}")


def check_dir(relative: str) -> None:
    check((IOS_ROOT / relative).is_dir(), f"缺少目录：{relative}")


def strip_pbxproj_noise(text: str) -> str:
    out: list[str] = []
    i = 0
    length = len(text)
    while i < length:
        two = text[i : i + 2]
        if two == "/*":
            end = text.find("*/", i + 2)
            i = length if end < 0 else end + 2
            continue
        if two == "//":
            end = text.find("\n", i + 2)
            i = length if end < 0 else end
            continue
        if text[i] == '"':
            i += 1
            while i < length:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        out.append(text[i])
        i += 1
    return "".join(out)


def strip_swift_comments(text: str) -> str:
    """去掉 `//` 行注释与 `/* */` 块注释（保留字符串字面量）。

    禁止标记扫描只看真实代码：源码里"为了兼容 iOS 10 故意不用 videoRotationAngle"
    这类说明性注释不应被判为使用了新 API。
    """
    out: list[str] = []
    i = 0
    length = len(text)
    while i < length:
        char = text[i]
        if char == '"':
            out.append(char)
            i += 1
            while i < length:
                if text[i] == "\\":
                    out.append(text[i])
                    if i + 1 < length:
                        out.append(text[i + 1])
                    i += 2
                    continue
                out.append(text[i])
                if text[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        if text.startswith("//", i):
            end = text.find("\n", i)
            i = length if end < 0 else end
            continue
        if text.startswith("/*", i):
            end = text.find("*/", i + 2)
            i = length if end < 0 else end + 2
            continue
        out.append(char)
        i += 1
    return "".join(out)


def balanced(text: str) -> bool:
    stack: list[str] = []
    pairs = {")": "(", "}": "{"}
    for char in text:
        if char in "({":
            stack.append(char)
        elif char in ")}":
            if not stack or stack.pop() != pairs[char]:
                return False
    return not stack


def swift_sources() -> list[Path]:
    files = sorted(APP_DIR.rglob("*.swift"))
    files += sorted((IOS_ROOT / "Packages" / "WotaCore" / "Sources").rglob("*.swift"))
    return files


def main() -> int:
    required_files = [
        ".gitignore",
        "README.md",
        "Config/Info.plist",
        "Config/Shared.xcconfig",
        "Config/Full.xcconfig",
        "Config/Lite.xcconfig",
        "Config/ToolchainLegacy.xcconfig",
        "Config/ToolchainModern.xcconfig",
        "Config/FullLegacy.xcconfig",
        "Config/FullModern.xcconfig",
        "Config/LiteLegacy.xcconfig",
        "Config/LiteModern.xcconfig",
        "WotaGeiCam.xcodeproj/project.pbxproj",
        "WotaGeiCam.xcodeproj/xcshareddata/xcschemes/WotaGeiCam.xcscheme",
        "WotaGeiCam.xcodeproj/xcshareddata/xcschemes/WotaGeiCamLite.xcscheme",
        "WotaGeiCam.xcodeproj/xcshareddata/xcschemes/WotaGeiCamModern.xcscheme",
        "WotaGeiCam.xcodeproj/xcshareddata/xcschemes/WotaGeiCamLiteModern.xcscheme",
        "WotaGeiCam/App/main.swift",
        "WotaGeiCam/App/AppDelegate.swift",
        "WotaGeiCam/App/AppVariant.swift",
        "WotaGeiCam/Camera/CameraPermissionService.swift",
        "WotaGeiCam/Camera/CameraSessionController.swift",
        "WotaGeiCam/Camera/CameraPreviewView.swift",
        "WotaGeiCam/Camera/CameraPreviewViewController.swift",
        "WotaGeiCam/Camera/CameraParameterController.swift",
        "WotaGeiCam/UI/CameraStatusOverlayView.swift",
        "WotaGeiCam/UI/CameraParameterPanelView.swift",
        "WotaGeiCam/Resources/Assets.xcassets/Contents.json",
        "Packages/WotaCore/Package.swift",
        "Packages/WotaCore/Sources/WotaCore/CameraSessionStateMachine.swift",
        "Packages/WotaCore/Sources/WotaCore/RecordingFileNaming.swift",
        "Packages/WotaCore/Sources/WotaCore/SeriesIDGenerator.swift",
        "Packages/WotaCore/Sources/WotaCore/StorageLayout.swift",
        "Packages/WotaCore/Sources/WotaCore/RenderBackend.swift",
        "Packages/WotaCore/Sources/WotaCore/ExposureBiasModel.swift",
        "scripts/check_project.py",
        "scripts/generate_project.py",
        "scripts/detect_apple_tools.sh",
        "scripts/apple_build.sh",
        "scripts/apple_archive.sh",
        "scripts/package_unsigned_ipa.sh",
    ]
    for relative in required_files:
        check_file(relative)

    for module in [
        "WotaGeiCam/App",
        "WotaGeiCam/Camera",
        "WotaGeiCam/Rendering",
        "WotaGeiCam/Recording",
        "WotaGeiCam/ArcRepair",
        "WotaGeiCam/Library",
        "WotaGeiCam/Player",
        "WotaGeiCam/Compare",
        "WotaGeiCam/Settings",
        "WotaGeiCam/Update",
        "WotaGeiCam/UI",
    ]:
        check_dir(module)

    # 交付指令：不交付测试与模拟器工程。
    check(not (IOS_ROOT / "WotaGeiCamTests").exists(), "不应存在 WotaGeiCamTests（当前交付不含测试）")
    check(not (IOS_ROOT / "Packages/WotaCore/Tests").exists(), "当前交付不含测试 target，不应有 Tests 目录")

    # SwiftUI 时代的文件必须已被 UIKit 版本替换，不能残留。
    for relative in SWIFTUI_FILES_THAT_MUST_BE_GONE:
        check(not (IOS_ROOT / relative).exists(), f"SwiftUI 文件未替换，仍存在：{relative}")

    # 入口与预览：UIKit + 真实 AVFoundation。
    app_delegate = (IOS_ROOT / "WotaGeiCam/App/AppDelegate.swift").read_text(encoding="utf-8")
    main_swift = (IOS_ROOT / "WotaGeiCam/App/main.swift").read_text(encoding="utf-8")
    preview_view = (IOS_ROOT / "WotaGeiCam/Camera/CameraPreviewView.swift").read_text(encoding="utf-8")
    preview_controller = (IOS_ROOT / "WotaGeiCam/Camera/CameraPreviewViewController.swift").read_text(encoding="utf-8")
    session_controller = (IOS_ROOT / "WotaGeiCam/Camera/CameraSessionController.swift").read_text(encoding="utf-8")
    permission_service = (IOS_ROOT / "WotaGeiCam/Camera/CameraPermissionService.swift").read_text(encoding="utf-8")
    parameter_controller = (IOS_ROOT / "WotaGeiCam/Camera/CameraParameterController.swift").read_text(encoding="utf-8")

    check("UIApplicationDelegate" in app_delegate and "UIWindow" in app_delegate, "入口不是 UIKit AppDelegate/UIWindow")
    check("UIApplicationMain" in main_swift, "main.swift 未使用 UIApplicationMain 入口")
    check("UIViewController" in preview_controller, "取景页不是 UIViewController")
    check("AVCaptureVideoPreviewLayer" in preview_view, "预览不是 AVCaptureVideoPreviewLayer")
    check("videoOrientation" in preview_view, "预览未使用 iOS 10 可用的 videoOrientation")
    check(
        "videoRotationAngle" not in strip_swift_comments(preview_view),
        "预览使用了 iOS 17 API videoRotationAngle（iOS 10 不可用）",
    )
    check(
        "AVCaptureSession" in session_controller and "session.startRunning()" in session_controller,
        "会话控制未真实启动 AVCaptureSession",
    )
    check("DispatchQueue(" in session_controller and "capture.session" in session_controller, "缺串行会话队列")
    check("let sessionQueue: DispatchQueue" in session_controller, "未公开串行队列给后续录制/渲染接入")
    check("func configureSession(" in session_controller and "func addOutput(" in session_controller, "缺 B1 录制接入点（configureSession/addOutput）")
    check("func currentVideoDevice()" in session_controller, "缺当前设备查询接口")
    check("AVCaptureDevice.requestAccess" in permission_service, "缺真实相机权限请求")
    check("AVCaptureDevice.DiscoverySession" in session_controller, "缺 iOS 10 可用设备发现（DiscoverySession）")
    # stop 之后权限回调/迟到通知不得把会话重新拉回 running。
    check("var wantsSessionRunning" in session_controller, "缺「最近意图」标志 wantsSessionRunning")
    check(
        session_controller.count("guard self.wantsSessionRunning") >= 3,
        "权限回调/中断恢复/didStartRunning 通知未全部按 wantsSessionRunning 门控",
    )

    # 生命周期与错误反馈（无 SwiftUI scenePhase）。
    check(
        "didEnterBackgroundNotification" in preview_controller and "willEnterForegroundNotification" in preview_controller,
        "缺前后台生命周期处理（UIApplication 通知）",
    )
    check("sessionController.stop()" in preview_controller and "sessionController.start()" in preview_controller, "生命周期未启停会话")
    check("openSettingsURLString" in preview_controller, "缺错误反馈/设置入口")
    check("CameraNoticeContent" in (IOS_ROOT / "WotaGeiCam/UI/CameraStatusOverlayView.swift").read_text(encoding="utf-8"), "缺错误反馈卡片模型")

    # iOS 10 能力门：低于 iOS 11 的布局回退、以及参数调整的 supports 判断与降级文案。
    check("topLayoutGuide" in preview_controller and "#available(iOS 11.0, *)" in preview_controller, "缺 iOS 10 布局回退路径")
    check("lockForConfiguration" in parameter_controller, "参数调整未走 lockForConfiguration")
    check("isTorchAvailable" in parameter_controller or "hasTorch" in parameter_controller, "手电筒缺设备支持判断")
    check("isTorchModeSupported" in parameter_controller, "手电筒缺模式支持判断")
    check("minExposureTargetBias" in parameter_controller and "maxExposureTargetBias" in parameter_controller, "曝光缺设备支持判断")
    check("该设备不支持曝光补偿" in parameter_controller, "曝光不支持时缺降级说明")
    check("setExposureTargetBias" in parameter_controller, "未真实设置曝光目标偏置")

    # WotaCore 的渲染后端策略必须保留 OpenGL ES / CPU 降级（iPhone 5 无 Metal）。
    render_backend = (IOS_ROOT / "Packages/WotaCore/Sources/WotaCore/RenderBackend.swift").read_text(encoding="utf-8")
    check("case openGLES" in render_backend and "case cpu" in render_backend, "缺 OpenGL ES / CPU 渲染降级后端")
    check("supportsMetal" in render_backend, "缺 Metal 能力判断（Metal 不能是唯一后端）")
    exposure_model = (IOS_ROOT / "Packages/WotaCore/Sources/WotaCore/ExposureBiasModel.swift").read_text(encoding="utf-8")
    check("isAdjustable" in exposure_model and "func clamp(" in exposure_model, "曝光区间模型缺支持判断/夹取")

    # 条件导入：使 --core-mode inline（源码直接编译路径）也能工作。
    conditional_import_files = [
        "WotaGeiCam/Camera/CameraSessionController.swift",
        "WotaGeiCam/Camera/CameraParameterController.swift",
        "WotaGeiCam/Camera/CameraPreviewViewController.swift",
        "WotaGeiCam/UI/CameraStatusOverlayView.swift",
        "WotaGeiCam/UI/CameraParameterPanelView.swift",
    ]
    for relative in conditional_import_files:
        text = (IOS_ROOT / relative).read_text(encoding="utf-8")
        check("#if canImport(WotaCore)" in text, f"{relative} 缺 #if canImport(WotaCore) 条件导入")

    # 禁止的新框架/新 API：所有 WotaGeiCam/ 与 WotaCore 源码都不允许出现。
    for swift_file in swift_sources():
        relative = swift_file.relative_to(IOS_ROOT).as_posix()
        code = strip_swift_comments(swift_file.read_text(encoding="utf-8"))
        for token in FORBIDDEN_TOKENS:
            check(token not in code, f"{relative} 出现禁止标记：{token}")
        if "safeAreaLayoutGuide" in code:
            check("#available(iOS 11" in code, f"{relative} 使用了 safeAreaLayoutGuide 但没有 iOS 11 可用性门")

    # Info.plist：权限文案必须具体指向 WOTA 艺录制，且没有占位词。
    if INFO_PLIST.is_file():
        with INFO_PLIST.open("rb") as handle:
            plist = plistlib.load(handle)
        camera = str(plist.get("NSCameraUsageDescription", ""))
        microphone = str(plist.get("NSMicrophoneUsageDescription", ""))
        photo_add = str(plist.get("NSPhotoLibraryAddUsageDescription", ""))
        check("WOTA" in camera and "相机" in camera, "NSCameraUsageDescription 未具体说明 WOTA 艺录制相机用途")
        check("WOTA" in microphone and "麦克风" in microphone, "NSMicrophoneUsageDescription 未具体说明 WOTA 艺录制麦克风用途")
        check("WOTA" in photo_add and "照片图库" in photo_add, "NSPhotoLibraryAddUsageDescription 未具体说明保存用途")
        for key, value in {
            "NSCameraUsageDescription": camera,
            "NSMicrophoneUsageDescription": microphone,
            "NSPhotoLibraryAddUsageDescription": photo_add,
        }.items():
            lowered = value.lower()
            check(
                not any(token in lowered for token in ["todo", "placeholder", "占位", "待补充", "test purpose"]),
                f"{key} 含占位文案",
            )
        check(plist.get("CFBundleDisplayName") == "$(WOTA_DISPLAY_NAME)", "CFBundleDisplayName 未使用 WOTA_DISPLAY_NAME")
        check(plist.get("WOTAAppVariant") == "$(WOTA_VARIANT)", "WOTAAppVariant 未使用 WOTA_VARIANT")
        check(plist.get("WOTAAndroidBaseline") == "$(WOTA_ANDROID_BASELINE)", "WOTAAndroidBaseline 未参数化")

    # 构建配置：变体 + 工具链组合。
    shared = (IOS_ROOT / "Config/Shared.xcconfig").read_text(encoding="utf-8")
    check("WOTA_SOURCE_API_BASELINE = 10.0" in shared, "Shared.xcconfig 未声明 iOS 10 源码基线")
    check("SWIFT_VERSION = 5.0" in shared, "Shared.xcconfig 未固定 Swift 语言版本 5")
    check("SUPPORTED_PLATFORMS = iphoneos" in shared, "Shared.xcconfig 未限制为真机平台")
    check("TARGETED_DEVICE_FAMILY = 1" in shared, "Shared.xcconfig 未固定 iPhone only")

    legacy = (IOS_ROOT / "Config/ToolchainLegacy.xcconfig").read_text(encoding="utf-8")
    modern = (IOS_ROOT / "Config/ToolchainModern.xcconfig").read_text(encoding="utf-8")
    check("IPHONEOS_DEPLOYMENT_TARGET = 10.0" in legacy, "Legacy 配置未固定 iOS 10 部署目标")
    check("ARCHS = armv7 arm64" in legacy, "Legacy 配置未包含 armv7 + arm64")
    check("armv7" in legacy and "arm64" in legacy, "Legacy 配置未区分 armv7/arm64")
    check("IPHONEOS_DEPLOYMENT_TARGET = 15.0" in modern, "Modern 配置未固定 iOS 15 部署目标")
    check("ARCHS = arm64" in modern and "armv7" not in modern.split("IPHONEOS_DEPLOYMENT_TARGET")[-1].replace("不会", ""), "Modern 配置不应包含 armv7 架构")

    for combo, fragments in [
        ("FullLegacy.xcconfig", ["Shared.xcconfig", "Full.xcconfig", "ToolchainLegacy.xcconfig"]),
        ("FullModern.xcconfig", ["Shared.xcconfig", "Full.xcconfig", "ToolchainModern.xcconfig"]),
        ("LiteLegacy.xcconfig", ["Shared.xcconfig", "Lite.xcconfig", "ToolchainLegacy.xcconfig"]),
        ("LiteModern.xcconfig", ["Shared.xcconfig", "Lite.xcconfig", "ToolchainModern.xcconfig"]),
    ]:
        text = (IOS_ROOT / "Config" / combo).read_text(encoding="utf-8")
        for fragment in fragments:
            check(f'#include "{fragment}"' in text, f"{combo} 未 #include {fragment}")

    for variant_file, token in [("Full.xcconfig", "WOTA_FULL"), ("Lite.xcconfig", "WOTA_LITE")]:
        text = (IOS_ROOT / "Config" / variant_file).read_text(encoding="utf-8")
        check(token in text, f"{variant_file} 缺编译条件 {token}")
        check("PRODUCT_BUNDLE_IDENTIFIER = com.wotagei.cam" in text, f"{variant_file} 未使用相同 Bundle ID")

    # pbxproj：经典格式（不得再用 Xcode 16 同步目录）+ 源码引用完整。
    if PROJECT.is_file():
        pbx = PROJECT.read_text(encoding="utf-8")
        check(balanced(strip_pbxproj_noise(pbx)), "project.pbxproj 括号不配对")
        check("objectVersion = 54" in pbx, "project.pbxproj 不是 objectVersion = 54（经典格式）")
        check('compatibilityVersion = "Xcode 12.0"' in pbx, "project.pbxproj 兼容版本不是 Xcode 12.0")
        check("PBXFileSystemSynchronizedRootGroup" not in pbx, "不应再使用 Xcode 16 同步目录")
        check("IPHONEOS_DEPLOYMENT_TARGET" not in pbx, "部署目标不应写在 pbxproj（应由 xcconfig 决定）")
        for section in [
            "PBXBuildFile",
            "PBXFileReference",
            "PBXGroup",
            "PBXNativeTarget",
            "PBXProject",
            "PBXSourcesBuildPhase",
            "PBXResourcesBuildPhase",
            "XCBuildConfiguration",
            "XCConfigurationList",
        ]:
            check(f"/* Begin {section} section */" in pbx, f"project.pbxproj 缺 section：{section}")

        defined = set(re.findall(r"^\s*([0-9A-F]{24})\s*(?:/\*.*?\*/)?\s*=\s*\{", pbx, re.MULTILINE))
        referenced = set(re.findall(r"\b[0-9A-F]{24}\b", pbx))
        undefined = sorted(referenced - defined)
        check(not undefined, f"project.pbxproj 存在未定义的对象引用：{undefined[:5]}")

        root_match = re.search(r"rootObject = ([0-9A-F]{24})", pbx)
        check(root_match is not None and root_match.group(1) in defined, "project.pbxproj rootObject 无效")

        check('productType = "com.apple.product-type.application";' in pbx, "缺 application productType")
        check("com.apple.product-type.bundle.unit-test" not in pbx, "当前交付不应包含测试 target")
        check("INFOPLIST_FILE = Config/Info.plist;" in pbx, "缺 INFOPLIST_FILE 指向 Config/Info.plist")
        check("ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME = AccentColor;" in pbx, "缺 AccentColor 设置")
        check("relativePath = Packages/WotaCore;" in pbx, "缺本地包引用 Packages/WotaCore（package 模式）")

        for name, _, _ in [
            ("Debug", "", True),
            ("Release", "", False),
            ("Debug-Lite", "", True),
            ("Release-Lite", "", False),
            ("Debug-Modern", "", True),
            ("Release-Modern", "", False),
            ("Debug-Lite-Modern", "", True),
            ("Release-Lite-Modern", "", False),
        ]:
            check(f'name = "{name}";' in pbx, f"project.pbxproj 缺构建配置 {name}")
        for base_config in ["FullLegacy.xcconfig", "LiteLegacy.xcconfig", "FullModern.xcconfig", "LiteModern.xcconfig"]:
            check(base_config in pbx, f"project.pbxproj 缺 baseConfigurationReference {base_config}")

        # 每个 Swift 源文件都要被 Sources 阶段收录（经典工程靠脚本保持同步）。
        for swift_file in sorted(APP_DIR.rglob("*.swift")):
            name = swift_file.name
            check(f"{name} in Sources */" in pbx, f"源文件未被 Sources 阶段收录：{name}")
        check("Assets.xcassets in Resources */" in pbx, "Assets.xcassets 未被 Resources 阶段收录")

    # schemes：Legacy / Modern × full / lite。
    for scheme_name, debug_config, release_config in [
        ("WotaGeiCam.xcscheme", "Debug", "Release"),
        ("WotaGeiCamLite.xcscheme", "Debug-Lite", "Release-Lite"),
        ("WotaGeiCamModern.xcscheme", "Debug-Modern", "Release-Modern"),
        ("WotaGeiCamLiteModern.xcscheme", "Debug-Lite-Modern", "Release-Lite-Modern"),
    ]:
        scheme_path = SCHEME_DIR / scheme_name
        if not scheme_path.is_file():
            continue
        try:
            tree = ET.parse(scheme_path)
        except ET.ParseError as error:
            check(False, f"{scheme_name} XML 解析失败：{error}")
            continue
        scheme = tree.getroot()
        check(scheme.find("BuildAction") is not None, f"{scheme_name} 缺 BuildAction")
        check(scheme.find("TestAction") is not None, f"{scheme_name} 缺 TestAction")
        check(scheme.find("ArchiveAction") is not None, f"{scheme_name} 缺 ArchiveAction")
        text = scheme_path.read_text(encoding="utf-8")
        check(f'buildConfiguration = "{debug_config}"' in text, f"{scheme_name} 未使用 {debug_config}")
        check(f'buildConfiguration = "{release_config}"' in text, f"{scheme_name} 未使用 {release_config}")
        check('BlueprintName = "WotaGeiCam"' in text, f"{scheme_name} 未指向 WotaGeiCam")
        check("TestableReference" not in text, f"{scheme_name} 不应包含测试项")

    # 工程文件必须与源码目录一致：调用生成器自检。
    if GENERATOR.is_file():
        result = subprocess.run(
            [sys.executable, str(GENERATOR), "--check"],
            capture_output=True,
            text=True,
            cwd=str(IOS_ROOT),
        )
        check(result.returncode == 0, "generate_project.py --check 失败：" + (result.stdout or result.stderr).strip())

    # WotaCore 清单：工具版本与平台下限（不使用虚假平台号）。
    package_manifest = (IOS_ROOT / "Packages/WotaCore/Package.swift").read_text(encoding="utf-8")
    check("swift-tools-version: 5.6" in package_manifest, "Package.swift 工具版本不是 5.6（legacy 工具链需要 <= 5.6）")
    check(".iOS(.v10)" in package_manifest, "Package.swift 平台下限不是 iOS 10")
    check("name: \"WotaCore\"" in package_manifest, "Package.swift 包名不是 WotaCore")
    check("url:" not in package_manifest, "WotaCore 不得引入远程第三方依赖")

    # Swift 源文件括号配对（粗粒度结构检查，不等同编译）。
    files = swift_sources()
    check(bool(files), "未找到任何 Swift 源文件")
    for swift_file in files:
        if not balanced(strip_swift_comments(swift_file.read_text(encoding="utf-8"))):
            check(False, f"Swift 源括号不配对：{swift_file.relative_to(IOS_ROOT)}")

    gitignore = (IOS_ROOT / ".gitignore").read_text(encoding="utf-8")
    for token in [".build/", "DerivedData/", "*.xcarchive", "*.ipa", "xcuserdata/"]:
        check(token in gitignore, f"iOS/.gitignore 缺 {token}")

    readme = (IOS_ROOT / "README.md").read_text(encoding="utf-8")
    for token in ["未执行", "不使用模拟器", "人工验证清单", "armv7", "arm64", "UIKit", "iOS 10"]:
        check(token in readme, f"README 缺标记：{token}")

    # 不得预置伪造的 app/ipa 产物。
    fake_artifacts = [
        str(path.relative_to(IOS_ROOT))
        for pattern in ("*.app", "*.ipa", "*.xcarchive")
        for path in IOS_ROOT.rglob(pattern)
        if ".build" not in path.parts and "build" not in path.parts
    ]
    check(not fake_artifacts, f"iOS/ 下不应预置 .app/.ipa/.xcarchive：{fake_artifacts}")

    print(f"checks={CHECKS}")
    if FAILURES:
        print("FAILED:")
        for failure in FAILURES:
            print(f"  - {failure}")
        return 1
    print("PASS: iOS UIKit/iOS10 工程结构、配置、plist 与入口检查全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
