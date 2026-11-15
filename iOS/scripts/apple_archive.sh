#!/usr/bin/env bash
# 无签名 archive 入口（真机 SDK，不启动模拟器）。
# 生成的 .xcarchive 位于 iOS/.build/Archives/ 下，仅作为后续 IPA 打包输入。
#
# 用法：
#   iOS/scripts/apple_archive.sh [scheme] [configuration]
# 退出码：0 = archive 成功；69 = 本机没有 xcodebuild，明确记为未执行。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IOS_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PROJECT="$IOS_ROOT/WotaGeiCam.xcodeproj"

SCHEME="${1:-${WOTA_SCHEME:-WotaGeiCam}}"
CONFIGURATION="${2:-${WOTA_CONFIGURATION:-Release}}"
ARCHIVE_ROOT="$IOS_ROOT/.build/Archives"
ARCHIVE_PATH="$ARCHIVE_ROOT/WotaGeiCam-$CONFIGURATION.xcarchive"

if ! command -v xcodebuild >/dev/null 2>&1; then
  echo "BLOCKED: xcodebuild 未找到，archive 未执行。"
  echo "工具链要求：Legacy scheme（iOS 10 / armv7 arm64）需 Xcode <= 13.4.1；Modern scheme（iOS 15 / arm64）需 Xcode 14+，"
  echo "            Xcode 14+ 不再产出 armv7、也不再支持 iOS 10 部署目标。"
  echo "复现命令（macOS，真机 SDK、无签名）："
  echo "  xcodebuild -project \"$PROJECT\" -scheme \"$SCHEME\" -configuration \"$CONFIGURATION\" -sdk iphoneos -destination 'generic/platform=iOS' -archivePath \"$ARCHIVE_PATH\" CODE_SIGNING_ALLOWED=NO archive"
  exit 69
fi

mkdir -p "$ARCHIVE_ROOT"

echo "xcodebuild archive"
echo "  project     = $PROJECT"
echo "  scheme      = $SCHEME"
echo "  configuration = $CONFIGURATION"
echo "  archive     = $ARCHIVE_PATH"
echo "  signing     = disabled (CODE_SIGNING_ALLOWED=NO)"

xcodebuild \
  -project "$PROJECT" \
  -scheme "$SCHEME" \
  -configuration "$CONFIGURATION" \
  -sdk iphoneos \
  -destination "generic/platform=iOS" \
  -archivePath "$ARCHIVE_PATH" \
  CODE_SIGNING_ALLOWED=NO \
  CODE_SIGNING_REQUIRED=NO \
  CODE_SIGN_IDENTITY="" \
  archive

PRODUCT_APP="$ARCHIVE_PATH/Products/Applications/WotaGeiCam.app"
if [ ! -d "$PRODUCT_APP" ]; then
  echo "ERROR: archive 结束但未找到 $PRODUCT_APP；不生成 IPA。" >&2
  exit 70
fi

echo "ARCHIVE_PATH=$ARCHIVE_PATH"
echo "ARCHIVE_APP=$PRODUCT_APP"
echo "ARCHIVE_EXIT=0"
