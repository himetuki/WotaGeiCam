#!/usr/bin/env bash
# 无签名构建入口（真机 SDK，不启动模拟器、不跑测试）。
#
# 用法：
#   iOS/scripts/apple_build.sh [scheme] [configuration]
# 环境变量：
#   WOTA_SCHEME / WOTA_CONFIGURATION / WOTA_DESTINATION
# 退出码：0 = 构建成功；69 = 本机没有 xcodebuild，明确记为未执行。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IOS_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PROJECT="$IOS_ROOT/WotaGeiCam.xcodeproj"

SCHEME="${1:-${WOTA_SCHEME:-WotaGeiCam}}"
CONFIGURATION="${2:-${WOTA_CONFIGURATION:-Debug}}"
DESTINATION="${WOTA_DESTINATION:-generic/platform=iOS}"
DERIVED_DATA="$IOS_ROOT/.build/DerivedData"

if ! command -v xcodebuild >/dev/null 2>&1; then
  echo "BLOCKED: xcodebuild 未找到，构建未执行。"
  echo "工具链要求（部署目标/架构来自 Config/*.xcconfig，不写在 pbxproj）："
  echo "  Legacy scheme（WotaGeiCam / WotaGeiCamLite；iOS 10.0、armv7 + arm64）需要 Xcode <= 13.4.1。"
  echo "  Modern scheme（WotaGeiCamModern / WotaGeiCamLiteModern；iOS 15.0、arm64）需要 Xcode 14+。"
  echo "  Xcode 14+ 已不再产出 armv7，也不再支持 iOS 10 部署目标，故 Legacy 通道无法用新工具链替代。"
  echo "复现命令（macOS，按 scheme 选择对应 Xcode）："
  echo "  xcodebuild -project \"$PROJECT\" -scheme \"$SCHEME\" -configuration \"$CONFIGURATION\" -destination \"$DESTINATION\" -derivedDataPath \"$DERIVED_DATA\" CODE_SIGNING_ALLOWED=NO build"
  exit 69
fi

mkdir -p "$DERIVED_DATA"

echo "xcodebuild build"
echo "  project     = $PROJECT"
echo "  scheme      = $SCHEME"
echo "  configuration = $CONFIGURATION"
echo "  destination = $DESTINATION"
echo "  derivedData = $DERIVED_DATA"
echo "  signing     = disabled (CODE_SIGNING_ALLOWED=NO)"

xcodebuild \
  -project "$PROJECT" \
  -scheme "$SCHEME" \
  -configuration "$CONFIGURATION" \
  -destination "$DESTINATION" \
  -derivedDataPath "$DERIVED_DATA" \
  CODE_SIGNING_ALLOWED=NO \
  CODE_SIGNING_REQUIRED=NO \
  CODE_SIGN_IDENTITY="" \
  build

echo "BUILD_EXIT=0"
