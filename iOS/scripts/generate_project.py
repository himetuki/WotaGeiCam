#!/usr/bin/env python3
"""生成经典格式的 WotaGeiCam.xcodeproj（objectVersion 54，Xcode 12+ 可打开）。

为什么用脚本生成而不是 Xcode 16 同步目录：
- Xcode 16 的 `PBXFileSystemSynchronizedRootGroup` 要求 objectVersion 77，
  Xcode 13.4.1（legacy iOS 10 / armv7 工具链）无法打开；
- 经典工程必须逐文件引用，本脚本扫描 `WotaGeiCam/` 目录并稳定地重建
  PBXGroup / PBXFileReference / PBXBuildFile，新增 Swift 源文件后重跑本脚本即可，
  不需要手工并行编辑 project.pbxproj。

用法：
    python iOS/scripts/generate_project.py             # 重新生成工程与 shared schemes
    python iOS/scripts/generate_project.py --check     # 只校验磁盘上的工程是否与源码一致
    python iOS/scripts/generate_project.py --core-mode inline
        # 不引用本地 SwiftPM 包，改为把 Packages/WotaCore/Sources/WotaCore/*.swift
        # 直接编入 app target（供无法在工程内引用本地包的工具链使用）。

本脚本只写 iOS/ 内的工程文件，不接触其他目录，不生成任何 .app/.ipa。
"""

from __future__ import annotations

import argparse
import hashlib
import os
import sys
from pathlib import Path

IOS_ROOT = Path(__file__).resolve().parent.parent
APP_DIR_NAME = "WotaGeiCam"
APP_DIR = IOS_ROOT / APP_DIR_NAME
CONFIG_DIR = IOS_ROOT / "Config"
PROJECT_DIR = IOS_ROOT / "WotaGeiCam.xcodeproj"
PROJECT_FILE = PROJECT_DIR / "project.pbxproj"
SCHEME_DIR = PROJECT_DIR / "xcshareddata" / "xcschemes"
CORE_DIR = IOS_ROOT / "Packages" / "WotaCore" / "Sources" / "WotaCore"
CORE_PREFIX = CORE_DIR.relative_to(IOS_ROOT).as_posix() + "/"

PRODUCT_NAME = "WotaGeiCam"
TARGET_NAME = "WotaGeiCam"
OBJECT_VERSION = "54"
COMPATIBILITY_VERSION = "Xcode 12.0"

SOURCE_EXTENSIONS = {".swift"}
RESOURCE_EXTENSIONS = {".xcassets", ".bundle", ".storyboard", ".xib", ".strings", ".json", ".plist", ".xcprivacy"}
BUNDLE_SUFFIXES = (".xcassets", ".bundle")

FILE_TYPES = {
    ".swift": "sourcecode.swift",
    ".plist": "text.plist.xml",
    ".xcconfig": "text.xcconfig",
    ".xcassets": "folder.assetcatalog",
    ".bundle": "wrapper.plug-in",
    ".storyboard": "file.storyboard",
    ".xib": "file.xib",
    ".strings": "text.plist.strings",
    ".json": "text.json",
    ".xcprivacy": "text.xml",
}

# 构建配置：名称 -> (target 的 baseConfigurationReference 文件名, 是否 Debug 语义)
TARGET_CONFIGS = [
    ("Debug", "FullLegacy.xcconfig", True),
    ("Release", "FullLegacy.xcconfig", False),
    ("Debug-Lite", "LiteLegacy.xcconfig", True),
    ("Release-Lite", "LiteLegacy.xcconfig", False),
    ("Debug-Modern", "FullModern.xcconfig", True),
    ("Release-Modern", "FullModern.xcconfig", False),
    ("Debug-Lite-Modern", "LiteModern.xcconfig", True),
    ("Release-Lite-Modern", "LiteModern.xcconfig", False),
]

# shared scheme：名称 -> (Debug 配置, Release 配置)
SCHEMES = [
    ("WotaGeiCam.xcscheme", "Debug", "Release"),
    ("WotaGeiCamLite.xcscheme", "Debug-Lite", "Release-Lite"),
    ("WotaGeiCamModern.xcscheme", "Debug-Modern", "Release-Modern"),
    ("WotaGeiCamLiteModern.xcscheme", "Debug-Lite-Modern", "Release-Lite-Modern"),
]

_IDS: dict[str, str] = {}


def oid(seed: str) -> str:
    """确定性的 24 位十六进制对象 id；同一 seed 永远得到同一 id。"""
    if seed in _IDS:
        return _IDS[seed]
    digest = hashlib.md5(("wotageicam:" + seed).encode("utf-8")).hexdigest()[:24].upper()
    if digest in _IDS.values():
        raise SystemExit("对象 id 冲突：" + seed)
    _IDS[seed] = digest
    return digest


def walk_app(app_dir: Path):
    """扫描 app 目录：源码、资源与目录（含空目录）。"""
    sources, resources, directories = [], [], []
    for dirpath, dirnames, filenames in os.walk(app_dir):
        dirnames[:] = sorted(name for name in dirnames if not name.startswith("."))
        current = Path(dirpath)
        relative_dir = current.relative_to(app_dir)
        if relative_dir.as_posix() != ".":
            directories.append(relative_dir.as_posix())

        for name in list(dirnames):
            if name.endswith(BUNDLE_SUFFIXES):
                resources.append((relative_dir / name).as_posix())
                dirnames.remove(name)

        for name in sorted(filenames):
            if name.startswith("."):
                continue
            suffix = Path(name).suffix
            relative = (relative_dir / name).as_posix()
            if suffix in SOURCE_EXTENSIONS:
                sources.append(relative)
            elif suffix in RESOURCE_EXTENSIONS:
                resources.append(relative)

    return sorted(sources), sorted(resources), sorted(directories)


def config_files():
    return sorted(path.name for path in CONFIG_DIR.iterdir() if path.is_file() and not path.name.startswith("."))


def core_sources():
    """inline 模式使用的 WotaCore 源码清单。

    递归扫描，保证后续集成代理在 Sources/WotaCore 下新增文件（含子目录，
    例如渲染/Metal 相关扩展）无需手改工程即可被直接编译路径收录。
    """
    if not CORE_DIR.is_dir():
        return []
    return sorted(
        path.relative_to(IOS_ROOT).as_posix()
        for path in CORE_DIR.rglob("*.swift")
        if path.is_file()
    )


def file_type(name: str) -> str:
    suffix = Path(name).suffix
    if suffix in FILE_TYPES:
        return FILE_TYPES[suffix]
    return "text"


class Renderer:
    def __init__(self, core_mode: str):
        self.core_mode = core_mode
        self.sources, self.resources, self.directories = walk_app(APP_DIR)
        if core_mode == "inline":
            self.sources = sorted(self.sources + core_sources())
        self.config_names = config_files()
        self.lines: list[str] = []

    # ---- 基础工具 ----

    def emit(self, indent: int, text: str) -> None:
        self.lines.append("\t" * indent + text)

    def ref_comment(self, object_id: str, label: str) -> str:
        return object_id + " /* " + label + " */"

    # ---- 各 section ----

    def build_file_section(self) -> None:
        self.emit(1, "/* Begin PBXBuildFile section */")
        for relative in self.sources:
            name = Path(relative).name
            self.emit(
                2,
                self.ref_comment(oid("build:" + relative), name + " in Sources")
                + " = {isa = PBXBuildFile; fileRef = "
                + self.ref_comment(oid("file:" + relative), name)
                + "; };",
            )
        for relative in self.resources:
            name = Path(relative).name
            self.emit(
                2,
                self.ref_comment(oid("build:" + relative), name + " in Resources")
                + " = {isa = PBXBuildFile; fileRef = "
                + self.ref_comment(oid("file:" + relative), name)
                + "; };",
            )
        if self.core_mode == "package":
            self.emit(
                2,
                self.ref_comment(oid("build:framework:WotaCore"), "WotaCore in Frameworks")
                + " = {isa = PBXBuildFile; productRef = "
                + self.ref_comment(oid("productdep:WotaCore"), "WotaCore")
                + "; };",
            )
        self.emit(1, "/* End PBXBuildFile section */")
        self.emit(0, "")

    def file_reference_section(self) -> None:
        self.emit(1, "/* Begin PBXFileReference section */")
        self.emit(
            2,
            self.ref_comment(oid("product:app"), PRODUCT_NAME + ".app")
            + " = {isa = PBXFileReference; explicitFileType = wrapper.application; includeInIndex = 0; path = "
            + PRODUCT_NAME
            + ".app; sourceTree = BUILT_PRODUCTS_DIR; };",
        )
        for relative in sorted(set(self.sources + self.resources)):
            name = Path(relative).name
            # inline 模式下 WotaCore 源码组不带 path（见 group_section），
            # 因此这里用相对工程根（iOS/）的全路径，才能解析到子目录里的文件。
            if self.core_mode == "inline" and relative.startswith(CORE_PREFIX):
                path_value = relative
            else:
                path_value = name
            self.emit(
                2,
                self.ref_comment(oid("file:" + relative), name)
                + " = {isa = PBXFileReference; lastKnownFileType = "
                + file_type(name)
                + "; path = "
                + path_value
                + "; sourceTree = \"<group>\"; };",
            )
        for name in self.config_names:
            self.emit(
                2,
                self.ref_comment(oid("file:Config/" + name), name)
                + " = {isa = PBXFileReference; lastKnownFileType = "
                + file_type(name)
                + "; path = "
                + name
                + "; sourceTree = \"<group>\"; };",
            )
        self.emit(1, "/* End PBXFileReference section */")
        self.emit(0, "")

    def frameworks_phase(self) -> None:
        self.emit(1, "/* Begin PBXFrameworksBuildPhase section */")
        self.emit(2, self.ref_comment(oid("phase:frameworks"), "Frameworks") + " = {")
        self.emit(3, "isa = PBXFrameworksBuildPhase;")
        self.emit(3, "buildActionMask = 2147483647;")
        self.emit(3, "files = (")
        if self.core_mode == "package":
            self.emit(4, self.ref_comment(oid("build:framework:WotaCore"), "WotaCore in Frameworks") + ",")
        self.emit(3, ");")
        self.emit(3, "runOnlyForDeploymentPostprocessing = 0;")
        self.emit(2, "};")
        self.emit(1, "/* End PBXFrameworksBuildPhase section */")
        self.emit(0, "")

    def group_section(self) -> None:
        self.emit(1, "/* Begin PBXGroup section */")
        # 工程主组
        self.emit(2, self.ref_comment(oid("group:root"), "WotaGeiCam") + " = {")
        self.emit(3, "isa = PBXGroup;")
        self.emit(3, "children = (")
        self.emit(4, self.ref_comment(oid("group:" + APP_DIR_NAME), APP_DIR_NAME) + ",")
        self.emit(4, self.ref_comment(oid("group:Config"), "Config") + ",")
        if self.core_mode == "inline":
            self.emit(4, self.ref_comment(oid("group:CoreInline"), "WotaCore (inline)") + ",")
        self.emit(4, self.ref_comment(oid("group:Products"), "Products") + ",")
        self.emit(3, ");")
        self.emit(3, "sourceTree = \"<group>\";")
        self.emit(2, "};")

        # app 根组
        self.emit(2, self.ref_comment(oid("group:" + APP_DIR_NAME), APP_DIR_NAME) + " = {")
        self.emit(3, "isa = PBXGroup;")
        self.emit(3, "children = (")
        for child in self.app_root_children():
            self.emit(4, child)
        self.emit(3, ");")
        self.emit(3, "path = " + APP_DIR_NAME + ";")
        self.emit(3, "sourceTree = \"<group>\";")
        self.emit(2, "};")

        # 子目录组
        for directory in self.directories:
            group_id = oid("group:" + APP_DIR_NAME + "/" + directory)
            name = directory.split("/")[-1]
            self.emit(2, self.ref_comment(group_id, name) + " = {")
            self.emit(3, "isa = PBXGroup;")
            self.emit(3, "children = (")
            for child in self.directory_children(directory):
                self.emit(4, child)
            self.emit(3, ");")
            self.emit(3, "path = " + name + ";")
            self.emit(3, "sourceTree = \"<group>\";")
            self.emit(2, "};")

        # Config 组
        self.emit(2, self.ref_comment(oid("group:Config"), "Config") + " = {")
        self.emit(3, "isa = PBXGroup;")
        self.emit(3, "children = (")
        for name in self.config_names:
            self.emit(4, self.ref_comment(oid("file:Config/" + name), name) + ",")
        self.emit(3, ");")
        self.emit(3, "path = Config;")
        self.emit(3, "sourceTree = \"<group>\";")
        self.emit(2, "};")

        # inline 模式的 WotaCore 源码组
        if self.core_mode == "inline":
            self.emit(2, self.ref_comment(oid("group:CoreInline"), "WotaCore (inline)") + " = {")
            self.emit(3, "isa = PBXGroup;")
            self.emit(3, "children = (")
            for relative in core_sources():
                name = Path(relative).name
                self.emit(4, self.ref_comment(oid("file:" + relative), name) + ",")
            self.emit(3, ");")
            # 故意不设 path：本组文件引用用 iOS/ 相对全路径（支持子目录里的新增文件）。
            self.emit(3, "sourceTree = \"<group>\";")
            self.emit(2, "};")

        # Products 组
        self.emit(2, self.ref_comment(oid("group:Products"), "Products") + " = {")
        self.emit(3, "isa = PBXGroup;")
        self.emit(3, "children = (")
        self.emit(4, self.ref_comment(oid("product:app"), PRODUCT_NAME + ".app") + ",")
        self.emit(3, ");")
        self.emit(3, "name = Products;")
        self.emit(3, "sourceTree = \"<group>\";")
        self.emit(2, "};")
        self.emit(1, "/* End PBXGroup section */")
        self.emit(0, "")

    def app_root_children(self) -> list[str]:
        top_dirs = sorted({d.split("/")[0] for d in self.directories})
        children = [self.ref_comment(oid("group:" + APP_DIR_NAME + "/" + d), d) + "," for d in top_dirs]
        top_files = sorted(r for r in self.sources + self.resources if "/" not in r)
        children += [self.ref_comment(oid("file:" + r), Path(r).name) + "," for r in top_files]
        return children

    def directory_children(self, directory: str) -> list[str]:
        prefix = directory + "/"
        sub_dirs = sorted(
            {d[len(prefix):].split("/")[0] for d in self.directories if d.startswith(prefix)}
        )
        children = [
            self.ref_comment(oid("group:" + APP_DIR_NAME + "/" + prefix + d), d) + ","
            for d in sub_dirs
        ]
        files = sorted(r for r in self.sources + self.resources if r.startswith(prefix) and "/" not in r[len(prefix):])
        children += [self.ref_comment(oid("file:" + r), Path(r).name) + "," for r in files]
        return children

    def native_target_section(self) -> None:
        self.emit(1, "/* Begin PBXNativeTarget section */")
        self.emit(2, self.ref_comment(oid("target:" + TARGET_NAME), TARGET_NAME) + " = {")
        self.emit(3, "isa = PBXNativeTarget;")
        self.emit(
            3,
            "buildConfigurationList = "
            + self.ref_comment(oid("configlist:target"), "Build configuration list for PBXNativeTarget \"" + TARGET_NAME + "\"")
            + ";",
        )
        self.emit(3, "buildPhases = (")
        self.emit(4, self.ref_comment(oid("phase:sources"), "Sources") + ",")
        self.emit(4, self.ref_comment(oid("phase:frameworks"), "Frameworks") + ",")
        self.emit(4, self.ref_comment(oid("phase:resources"), "Resources") + ",")
        self.emit(3, ");")
        self.emit(3, "buildRules = (")
        self.emit(3, ");")
        self.emit(3, "dependencies = (")
        self.emit(3, ");")
        self.emit(3, "name = " + TARGET_NAME + ";")
        if self.core_mode == "package":
            self.emit(3, "packageProductDependencies = (")
            self.emit(4, self.ref_comment(oid("productdep:WotaCore"), "WotaCore") + ",")
            self.emit(3, ");")
        self.emit(3, "productName = " + PRODUCT_NAME + ";")
        self.emit(3, "productReference = " + self.ref_comment(oid("product:app"), PRODUCT_NAME + ".app") + ";")
        self.emit(3, "productType = \"com.apple.product-type.application\";")
        self.emit(2, "};")
        self.emit(1, "/* End PBXNativeTarget section */")
        self.emit(0, "")

    def project_section(self) -> None:
        self.emit(1, "/* Begin PBXProject section */")
        self.emit(2, self.ref_comment(oid("project"), "Project object") + " = {")
        self.emit(3, "isa = PBXProject;")
        self.emit(3, "attributes = {")
        self.emit(4, "BuildIndependentTargetsInParallel = 1;")
        self.emit(4, "LastSwiftUpdateCheck = 1200;")
        self.emit(4, "LastUpgradeCheck = 1200;")
        self.emit(4, "TargetAttributes = {")
        self.emit(5, oid("target:" + TARGET_NAME) + " = {")
        self.emit(6, "CreatedOnToolsVersion = 12.0;")
        self.emit(5, "};")
        self.emit(4, "};")
        self.emit(3, "};")
        self.emit(
            3,
            "buildConfigurationList = "
            + self.ref_comment(oid("configlist:project"), "Build configuration list for PBXProject \"" + TARGET_NAME + "\"")
            + ";",
        )
        self.emit(3, "compatibilityVersion = \"" + COMPATIBILITY_VERSION + "\";")
        self.emit(3, "developmentRegion = en;")
        self.emit(3, "hasScannedForEncodings = 0;")
        self.emit(3, "knownRegions = (")
        self.emit(4, "en,")
        self.emit(4, "Base,")
        self.emit(4, "\"zh-Hans\",")
        self.emit(3, ");")
        self.emit(3, "mainGroup = " + self.ref_comment(oid("group:root"), TARGET_NAME) + ";")
        if self.core_mode == "package":
            self.emit(3, "packageReferences = (")
            self.emit(4, self.ref_comment(oid("package:WotaCore"), "XCLocalSwiftPackageReference \"Packages/WotaCore\"") + ",")
            self.emit(3, ");")
        self.emit(3, "productRefGroup = " + self.ref_comment(oid("group:Products"), "Products") + ";")
        self.emit(3, "projectDirPath = \"\";")
        self.emit(3, "projectRoot = \"\";")
        self.emit(3, "targets = (")
        self.emit(4, self.ref_comment(oid("target:" + TARGET_NAME), TARGET_NAME) + ",")
        self.emit(3, ");")
        self.emit(2, "};")
        self.emit(1, "/* End PBXProject section */")
        self.emit(0, "")

    def resources_phase(self) -> None:
        self.emit(1, "/* Begin PBXResourcesBuildPhase section */")
        self.emit(2, self.ref_comment(oid("phase:resources"), "Resources") + " = {")
        self.emit(3, "isa = PBXResourcesBuildPhase;")
        self.emit(3, "buildActionMask = 2147483647;")
        self.emit(3, "files = (")
        for relative in self.resources:
            name = Path(relative).name
            self.emit(4, self.ref_comment(oid("build:" + relative), name + " in Resources") + ",")
        self.emit(3, ");")
        self.emit(3, "runOnlyForDeploymentPostprocessing = 0;")
        self.emit(2, "};")
        self.emit(1, "/* End PBXResourcesBuildPhase section */")
        self.emit(0, "")

    def sources_phase(self) -> None:
        self.emit(1, "/* Begin PBXSourcesBuildPhase section */")
        self.emit(2, self.ref_comment(oid("phase:sources"), "Sources") + " = {")
        self.emit(3, "isa = PBXSourcesBuildPhase;")
        self.emit(3, "buildActionMask = 2147483647;")
        self.emit(3, "files = (")
        for relative in self.sources:
            name = Path(relative).name
            self.emit(4, self.ref_comment(oid("build:" + relative), name + " in Sources") + ",")
        self.emit(3, ");")
        self.emit(3, "runOnlyForDeploymentPostprocessing = 0;")
        self.emit(2, "};")
        self.emit(1, "/* End PBXSourcesBuildPhase section */")
        self.emit(0, "")

    def build_configuration_section(self) -> None:
        self.emit(1, "/* Begin XCBuildConfiguration section */")
        for name, _, is_debug in TARGET_CONFIGS:
            self.emit(2, self.ref_comment(oid("buildconfig:project:" + name), name) + " = {")
            self.emit(3, "isa = XCBuildConfiguration;")
            self.emit(3, "buildSettings = {")
            for setting in self.project_settings(is_debug):
                self.emit(4, setting)
            self.emit(3, "};")
            self.emit(3, "name = \"" + name + "\";")
            self.emit(2, "};")

        for name, base_config, _ in TARGET_CONFIGS:
            self.emit(2, self.ref_comment(oid("buildconfig:target:" + name), name) + " = {")
            self.emit(3, "isa = XCBuildConfiguration;")
            self.emit(
                3,
                "baseConfigurationReference = "
                + self.ref_comment(oid("file:Config/" + base_config), base_config)
                + ";",
            )
            self.emit(3, "buildSettings = {")
            for setting in self.target_settings():
                self.emit(4, setting)
            self.emit(3, "};")
            self.emit(3, "name = \"" + name + "\";")
            self.emit(2, "};")
        self.emit(1, "/* End XCBuildConfiguration section */")
        self.emit(0, "")

    @staticmethod
    def project_settings(is_debug: bool) -> list[str]:
        settings = [
            "ALWAYS_SEARCH_USER_PATHS = NO;",
            "CLANG_ENABLE_MODULES = YES;",
            "CLANG_ENABLE_OBJC_ARC = YES;",
            "COPY_PHASE_STRIP = NO;",
            "ENABLE_STRICT_OBJC_MSGSEND = YES;",
            "GCC_C_LANGUAGE_STANDARD = gnu17;",
            "GCC_NO_COMMON_BLOCKS = YES;",
        ]
        if is_debug:
            settings += [
                "DEBUG_INFORMATION_FORMAT = dwarf;",
                "ENABLE_TESTABILITY = YES;",
                "GCC_OPTIMIZATION_LEVEL = 0;",
                "ONLY_ACTIVE_ARCH = YES;",
                "SWIFT_ACTIVE_COMPILATION_CONDITIONS = \"DEBUG $(inherited)\";",
                "SWIFT_OPTIMIZATION_LEVEL = \"-Onone\";",
            ]
        else:
            settings += [
                "DEBUG_INFORMATION_FORMAT = \"dwarf-with-dsym\";",
                "ENABLE_NS_ASSERTIONS = NO;",
                "SWIFT_COMPILATION_MODE = wholemodule;",
                "SWIFT_OPTIMIZATION_LEVEL = \"-O\";",
                "VALIDATE_PRODUCT = YES;",
            ]
        return settings

    @staticmethod
    def target_settings() -> list[str]:
        return [
            "ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME = AccentColor;",
            "CODE_SIGN_STYLE = Automatic;",
            "CURRENT_PROJECT_VERSION = 12;",
            "GENERATE_INFOPLIST_FILE = NO;",
            "INFOPLIST_FILE = Config/Info.plist;",
            "LD_RUNPATH_SEARCH_PATHS = (",
            "\t\"$(inherited)\",",
            "\t\"@executable_path/Frameworks\",",
            ");",
            "MARKETING_VERSION = 0.0.12;",
            "PRODUCT_BUNDLE_IDENTIFIER = com.wotagei.cam;",
            "PRODUCT_NAME = \"$(TARGET_NAME)\";",
        ]

    def configuration_list_section(self) -> None:
        self.emit(1, "/* Begin XCConfigurationList section */")
        for seed, label in [
            ("configlist:project", "Build configuration list for PBXProject \"" + TARGET_NAME + "\""),
            ("configlist:target", "Build configuration list for PBXNativeTarget \"" + TARGET_NAME + "\""),
        ]:
            prefix = "buildconfig:project:" if seed.endswith("project") else "buildconfig:target:"
            self.emit(2, self.ref_comment(oid(seed), label) + " = {")
            self.emit(3, "isa = XCConfigurationList;")
            self.emit(3, "buildConfigurations = (")
            for name, _, _ in TARGET_CONFIGS:
                self.emit(4, self.ref_comment(oid(prefix + name), name) + ",")
            self.emit(3, ");")
            self.emit(3, "defaultConfigurationIsVisible = 0;")
            self.emit(3, "defaultConfigurationName = Release;")
            self.emit(2, "};")
        self.emit(1, "/* End XCConfigurationList section */")
        self.emit(0, "")

    def package_sections(self) -> None:
        if self.core_mode != "package":
            return
        self.emit(1, "/* Begin XCLocalSwiftPackageReference section */")
        self.emit(2, self.ref_comment(oid("package:WotaCore"), "XCLocalSwiftPackageReference \"Packages/WotaCore\"") + " = {")
        self.emit(3, "isa = XCLocalSwiftPackageReference;")
        self.emit(3, "relativePath = Packages/WotaCore;")
        self.emit(2, "};")
        self.emit(1, "/* End XCLocalSwiftPackageReference section */")
        self.emit(0, "")
        self.emit(1, "/* Begin XCSwiftPackageProductDependency section */")
        self.emit(2, self.ref_comment(oid("productdep:WotaCore"), "WotaCore") + " = {")
        self.emit(3, "isa = XCSwiftPackageProductDependency;")
        self.emit(3, "package = " + self.ref_comment(oid("package:WotaCore"), "XCLocalSwiftPackageReference \"Packages/WotaCore\"") + ";")
        self.emit(3, "productName = WotaCore;")
        self.emit(2, "};")
        self.emit(1, "/* End XCSwiftPackageProductDependency section */")
        self.emit(0, "")

    def render(self) -> str:
        self.emit(0, "// !$*UTF8*$!")
        self.emit(0, "{")
        self.emit(1, "archiveVersion = 1;")
        self.emit(1, "classes = {")
        self.emit(1, "};")
        self.emit(1, "objectVersion = " + OBJECT_VERSION + ";")
        self.emit(1, "objects = {")
        self.emit(0, "")
        self.build_file_section()
        self.file_reference_section()
        self.frameworks_phase()
        self.group_section()
        self.native_target_section()
        self.project_section()
        self.resources_phase()
        self.sources_phase()
        self.build_configuration_section()
        self.configuration_list_section()
        self.package_sections()
        self.emit(1, "};")
        self.emit(1, "rootObject = " + self.ref_comment(oid("project"), "Project object") + ";")
        self.emit(0, "}")
        return "\n".join(self.lines) + "\n"


def render_scheme(debug_config: str, release_config: str) -> str:
    target_id = oid("target:" + TARGET_NAME)
    reference = """            <BuildableReference
               BuildableIdentifier = "primary"
               BlueprintIdentifier = "{target_id}"
               BuildableName = "{product}.app"
               BlueprintName = "{target}"
               ReferencedContainer = "container:{project}.xcodeproj">
            </BuildableReference>""".format(target_id=target_id, product=PRODUCT_NAME, target=TARGET_NAME, project=PRODUCT_NAME)
    return """<?xml version="1.0" encoding="UTF-8"?>
<Scheme
   LastUpgradeVersion = "1200"
   version = "1.3">
   <BuildAction
      parallelizeBuildables = "YES"
      buildImplicitDependencies = "YES">
      <BuildActionEntries>
         <BuildActionEntry
            buildForTesting = "YES"
            buildForRunning = "YES"
            buildForProfiling = "YES"
            buildForArchiving = "YES"
            buildForAnalyzing = "YES">
{reference}
         </BuildActionEntry>
      </BuildActionEntries>
   </BuildAction>
   <TestAction
      buildConfiguration = "{debug_config}"
      selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB"
      selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB"
      shouldUseLaunchSchemeArgsEnv = "YES">
      <Testables>
      </Testables>
   </TestAction>
   <LaunchAction
      buildConfiguration = "{debug_config}"
      selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB"
      selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB"
      launchStyle = "0"
      useCustomWorkingDirectory = "NO"
      ignoresPersistentStateOnLaunch = "NO"
      debugDocumentVersioning = "YES"
      debugServiceExtension = "internal"
      allowLocationSimulation = "YES">
      <BuildableProductRunnable
         runnableDebuggingMode = "0">
{reference}
      </BuildableProductRunnable>
   </LaunchAction>
   <ProfileAction
      buildConfiguration = "{release_config}"
      shouldUseLaunchSchemeArgsEnv = "YES"
      savedToolIdentifier = ""
      useCustomWorkingDirectory = "NO"
      debugDocumentVersioning = "YES">
      <BuildableProductRunnable
         runnableDebuggingMode = "0">
{reference}
      </BuildableProductRunnable>
   </ProfileAction>
   <AnalyzeAction
      buildConfiguration = "{debug_config}">
   </AnalyzeAction>
   <ArchiveAction
      buildConfiguration = "{release_config}"
      revealArchiveInOrganizer = "YES">
   </ArchiveAction>
</Scheme>
""".format(reference=reference, debug_config=debug_config, release_config=release_config)


def planned_outputs(core_mode: str):
    outputs = {PROJECT_FILE: Renderer(core_mode).render()}
    for name, debug_config, release_config in SCHEMES:
        outputs[SCHEME_DIR / name] = render_scheme(debug_config, release_config)
    return outputs


def main() -> int:
    parser = argparse.ArgumentParser(description="生成/校验 WotaGeiCam.xcodeproj（经典格式）")
    parser.add_argument("--check", action="store_true", help="只校验磁盘上的工程是否与源码一致")
    parser.add_argument("--core-mode", choices=["package", "inline"], default="package")
    arguments = parser.parse_args()

    outputs = planned_outputs(arguments.core_mode)

    if arguments.check:
        stale = []
        for path, content in outputs.items():
            if not path.is_file() or path.read_text(encoding="utf-8") != content:
                stale.append(path.relative_to(IOS_ROOT).as_posix())
        if stale:
            print("STALE: 以下生成文件与源码目录不一致：" + ", ".join(stale))
            print("请运行：python iOS/scripts/generate_project.py")
            return 1
        print("OK: project.pbxproj 与 shared schemes 与源码目录一致")
        return 0

    for path, content in outputs.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8", newline="\n")
    renderer = Renderer(arguments.core_mode)
    print("objectVersion = " + OBJECT_VERSION)
    print("core-mode = " + arguments.core_mode)
    print("sources = " + str(len(renderer.sources)))
    print("resources = " + str(len(renderer.resources)))
    print("groups = " + str(len(renderer.directories)))
    print("target-configs = " + str(len(TARGET_CONFIGS)))
    print("schemes = " + str(len(SCHEMES)))
    print("written = " + str(len(outputs)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
