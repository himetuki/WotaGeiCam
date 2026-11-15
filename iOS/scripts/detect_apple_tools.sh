#!/usr/bin/env bash
# 检查 Apple 工具链是否可用。只报告事实，不构建、不启动模拟器。
# 退出码：0 = xcodebuild / xcrun / xcode-select / plutil 与关键工具都在；
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

for tool in xcodebuild xcrun xcode-select plutil; do
  if command -v "$tool" >/dev/null 2>&1; then
    report_found "$tool" "$(command -v "$tool")"
  else
    report_missing "$tool"
  fi
done

if command -v xcrun >/dev/null 2>&1; then
  for tool in swift swiftc vtool lipo; do
    if xcrun --find "$tool" >/dev/null 2>&1; then
      report_found "xcrun:$tool" "$(xcrun --find "$tool")"
    else
      report_missing "xcrun:$tool"
    fi
  done
fi

for tool in zip unzip; do
  if command -v "$tool" >/dev/null 2>&1; then
    report_found "$tool" "$(command -v "$tool")"
  else
    report_missing "$tool"
  fi
done

echo "host=uname:$(uname -s 2>/dev/null || echo unknown)"
if command -v xcodebuild >/dev/null 2>&1; then
  echo "--- xcodebuild -version ---"
  xcodebuild -version || true
  # 工具链可行性：Legacy(iOS10/armv7) 需要 Xcode <= 13.4.1；Modern(iOS15/arm64) 需要 Xcode 14+。
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

if [ "$missing" -gt 0 ]; then
  echo "APPLE_TOOLCHAIN=unavailable missing=$missing"
  echo "未执行任何 xcodebuild build/archive 或 IPA 打包；请在 macOS 上重跑 iOS/scripts/ 下的入口脚本："
  echo "  Legacy scheme（WotaGeiCam / WotaGeiCamLite，iOS 10 / armv7 arm64）需 Xcode <= 13.4.1；"
  echo "  Modern scheme（WotaGeiCamModern / WotaGeiCamLiteModern，iOS 15 / arm64）需 Xcode 14+。"
  exit 69
fi

echo "APPLE_TOOLCHAIN=available"
exit 0
