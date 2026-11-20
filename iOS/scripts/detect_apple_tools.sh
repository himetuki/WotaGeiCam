#!/usr/bin/env bash
# 检查 Apple 真机构建/归档/无签名 IPA 打包所需工具是否可用。
# 只报告事实，不构建、不启动模拟器、不写任何产物。
#
# 退出码：0  = 所需工具与 iphoneos SDK 都在；
#         69 = 有缺失（Apple 构建/归档/打包门禁未执行）。
set -u

missing=0
report_missing() {
  echo "MISSING $1"
  missing=$((missing + 1))
}
report_found() {
  echo "FOUND $1 -> $2"
}

for tool in xcodebuild xcrun xcode-select plutil zip unzip; do
  if command -v "$tool" >/dev/null 2>&1; then
    report_found "$tool" "$(command -v "$tool")"
  else
    report_missing "$tool"
  fi
done

if command -v xcrun >/dev/null 2>&1; then
  for tool in swift swiftc lipo otool; do
    if xcrun --find "$tool" >/dev/null 2>&1; then
      report_found "xcrun:$tool" "$(xcrun --find "$tool")"
    else
      report_missing "xcrun:$tool"
    fi
  done
  for tool in vtool codesign; do
    if xcrun --find "$tool" >/dev/null 2>&1; then
      report_found "xcrun:$tool(optional)" "$(xcrun --find "$tool")"
    else
      echo "NOT_FOUND xcrun:$tool(optional) -> 当前脚本不依赖"
    fi
  done
else
  for tool in swift swiftc lipo otool; do
    report_missing "xcrun:$tool"
  done
fi

# 无签名 IPA 脚本要求能算 SHA-256（macOS 自带 shasum，GNU 环境用 sha256sum）。
if command -v shasum >/dev/null 2>&1; then
  report_found "shasum" "$(command -v shasum)"
elif command -v sha256sum >/dev/null 2>&1; then
  report_found "sha256sum" "$(command -v sha256sum)"
else
  report_missing "shasum/sha256sum"
fi

# ditto 不是必需（打包脚本会退化为 cp -R），只报告。
if command -v ditto >/dev/null 2>&1; then
  report_found "ditto(optional)" "$(command -v ditto)"
else
  echo "NOT_FOUND ditto(optional) -> package_unsigned_ipa.sh 将使用 cp -R"
fi

echo "host=uname:$(uname -s 2>/dev/null || echo unknown)"

if command -v xcodebuild >/dev/null 2>&1; then
  echo "--- xcodebuild -version ---"
  xcodebuild -version || true
  # 工具链可行性：Legacy(iOS 10 / armv7) 需要 Xcode <= 13.4.1；Modern(iOS 15 / arm64) 需要 Xcode 14+。
  xcode_version="$(xcodebuild -version 2>/dev/null | sed -n '1s/^Xcode //p')"
  if [ -n "${xcode_version:-}" ]; then
    xcode_major="${xcode_version%%.*}"
    echo "xcode_version=$xcode_version xcode_major=$xcode_major"
    if [ "$xcode_major" -le 13 ] 2>/dev/null; then
      echo "LEGACY_IOS10_ARMV7=supported（可产出 armv7 且支持 iOS 10 部署目标）"
    else
      echo "LEGACY_IOS10_ARMV7=unsupported（Xcode 14 起只构建 64 位，且不支持早于 iOS 11 的部署目标）"
    fi
    if [ "$xcode_major" -ge 14 ] 2>/dev/null; then
      echo "MODERN_ARM64_IOS15=supported"
    else
      echo "MODERN_ARM64_IOS15=unsupported（Modern 配置建议 Xcode 14+）"
    fi
  fi
fi

if command -v xcode-select >/dev/null 2>&1; then
  echo "--- xcode-select -p ---"
  xcode-select -p || true
fi

if command -v xcrun >/dev/null 2>&1; then
  echo "--- iphoneos SDK ---"
  if sdk_path="$(xcrun --sdk iphoneos --show-sdk-path 2>/dev/null)" && [ -n "$sdk_path" ]; then
    echo "FOUND iphoneos_sdk -> $sdk_path"
    sdk_version="$(xcrun --sdk iphoneos --show-sdk-version 2>/dev/null || true)"
    [ -n "$sdk_version" ] && echo "iphoneos_sdk_version=$sdk_version"
  else
    report_missing "iphoneos_sdk"
  fi
fi

if [ "$missing" -gt 0 ]; then
  echo "APPLE_TOOLCHAIN=unavailable missing=$missing"
  echo "未执行任何 xcodebuild build/archive 或 IPA 打包；请在 macOS 上重跑 iOS/scripts/ 下的入口脚本。"
  echo "构建入口固定 -sdk iphoneos -destination 'generic/platform=iOS'，并传 CODE_SIGNING_ALLOWED=NO。"
  echo "  Legacy scheme（WotaGeiCam / WotaGeiCamLite，iOS 10.0 / armv7 + arm64）需要 Xcode <= 13.4.1；"
  echo "  Modern scheme（WotaGeiCamModern / WotaGeiCamLiteModern，iOS 15.0 / arm64）需要 Xcode 14+。"
  exit 69
fi

echo "APPLE_TOOLCHAIN=available"
exit 0
