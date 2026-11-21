#!/usr/bin/env bash
# 无签名真机 archive 入口（固定 -sdk iphoneos / generic/platform=iOS；不启动模拟器）。
#
# 用法：
#   iOS/scripts/apple_archive.sh [scheme] [configuration]
# 环境变量：
#   WOTA_SCHEME / WOTA_CONFIGURATION
#
# 归档先写入 iOS/.build/tmp/ 下的全新临时 .xcarchive，校验通过后才替换
# iOS/.build/Archives/<scheme>-<configuration>.xcarchive；失败不破坏旧归档。
# archive 的 Products 下必须恰好有 1 个 .app，且是本次构建新生成的（无陈旧产物）。
#
# 退出码：
#   0  = archive 成功且 .app 通过平台/架构/最低系统/Mach-O 校验
#   64 = scheme/configuration 不匹配或用法错误
#   65 = 解析出的构建配置或产物不符合预期
#   69 = 本机没有 xcodebuild / Apple 工具，明确记为未执行
#   70 = 归档结构或产物陈旧/缺失
#   其他 = 透传 xcodebuild 的退出码（ARCHIVE_EXIT 如实打印）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=apple_device_common.sh
. "$SCRIPT_DIR/apple_device_common.sh"

usage() {
  cat <<'EOF'
用法: apple_archive.sh [scheme] [configuration]
  scheme         默认 WotaGeiCam；必须是 WotaGeiCam.xcodeproj 的 shared scheme
  configuration  必须是该 scheme 的 Debug 或 Release 配置（不接受交叉组合）
环境变量: WOTA_SCHEME / WOTA_CONFIGURATION
固定参数: -sdk iphoneos -destination generic/platform=iOS，CODE_SIGNING_ALLOWED=NO
EOF
}

SCHEME=""
CONFIGURATION=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    -*)
      echo "ERROR: 未知参数：$1" >&2
      usage >&2
      exit 64
      ;;
    *)
      if [ -z "$SCHEME" ]; then
        SCHEME="$1"
      elif [ -z "$CONFIGURATION" ]; then
        CONFIGURATION="$1"
      else
        echo "ERROR: 多余参数：$1" >&2
        usage >&2
        exit 64
      fi
      shift
      ;;
  esac
done

SCHEME="${SCHEME:-${WOTA_SCHEME:-WotaGeiCam}}"
CONFIGURATION="${CONFIGURATION:-${WOTA_CONFIGURATION:-Release}}"

apple_validate_scheme_configuration "$SCHEME" "$CONFIGURATION"

if ! command -v xcodebuild >/dev/null 2>&1; then
  echo "BLOCKED: xcodebuild 未找到，archive 未执行。"
  if [ "$APPLE_PROFILE" = "legacy" ]; then
    echo "  该配置属于 Legacy 工具链（iOS 10.0 / armv7 + arm64），需要 Xcode <= 13.4.1。"
    echo "  Xcode 14+ 不再产出 armv7，也不再支持 iOS 10 部署目标，无法替代。"
  else
    echo "  该配置属于 Modern 工具链（iOS 15.0 / arm64），需要 Xcode 14+。"
  fi
  echo "复现命令（macOS，真机 SDK、无签名）："
  echo "  xcodebuild -project \"$APPLE_PROJECT\" -scheme \"$APPLE_SCHEME\" -configuration \"$APPLE_CONFIGURATION\" -sdk iphoneos -destination 'generic/platform=iOS' -archivePath \"$APPLE_BUILD_ROOT/Archives/$APPLE_SCHEME-$APPLE_CONFIGURATION.xcarchive\" -derivedDataPath \"$APPLE_BUILD_ROOT/DerivedData\" CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY='' archive"
  exit 69
fi

apple_require_tools plutil lipo otool

mkdir -p "$APPLE_BUILD_ROOT/Archives" "$APPLE_TMP_ROOT"
SETTINGS_FILE="$(mktemp "$APPLE_TMP_ROOT/archive-settings.XXXXXX")"
TMP_DIR="$(mktemp -d "$APPLE_TMP_ROOT/archive.XXXXXX")"
# 本次 archive 使用独立 DerivedData：避免 Xcode 复用 ArchiveIntermediates 里
# 旧构建产物，从而保证归档里的 .app 是本次新生成的（mtime 校验才有意义）。
APPLE_DERIVED_DATA="$TMP_DIR/DerivedData"
FINAL_ARCHIVE="$APPLE_BUILD_ROOT/Archives/$APPLE_SCHEME-$APPLE_CONFIGURATION.xcarchive"
TMP_ARCHIVE="$TMP_DIR/Result.xcarchive"
cleanup() {
  rm -f "$SETTINGS_FILE"
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

settings_rc=0
apple_show_build_settings "$APPLE_SCHEME" "$APPLE_CONFIGURATION" "$SETTINGS_FILE" || settings_rc=$?
if [ "$settings_rc" -ne 0 ]; then
  echo "ERROR: xcodebuild -showBuildSettings 失败（退出码 $settings_rc），未执行 archive。" >&2
  exit "$settings_rc"
fi

apple_validate_build_settings "$SETTINGS_FILE" "$APPLE_PROFILE" "$APPLE_CONFIGURATION"

# 删除 DerivedData 里的旧 .app，强制 archive 过程重新生成产物；
# 否则增量构建可能把很久以前的 .app 原样拷进新归档。
PREBUILT_APP="$APPLE_TARGET_BUILD_DIR/$APPLE_FULL_PRODUCT_NAME"
case "$PREBUILT_APP" in
  "$APPLE_BUILD_ROOT"/*) ;;
  *) apple_fail 70 "拒绝操作 iOS/.build 之外的产物路径：$PREBUILT_APP" ;;
esac
rm -rf "$PREBUILT_APP"

ARCHIVE_START="$(date +%s)"
archive_rc=0
xcodebuild \
  -project "$APPLE_PROJECT" \
  -scheme "$APPLE_SCHEME" \
  -configuration "$APPLE_CONFIGURATION" \
  -sdk iphoneos \
  -destination "generic/platform=iOS" \
  -archivePath "$TMP_ARCHIVE" \
  -derivedDataPath "$APPLE_DERIVED_DATA" \
  CODE_SIGNING_ALLOWED=NO \
  CODE_SIGNING_REQUIRED=NO \
  CODE_SIGN_IDENTITY="" \
  archive || archive_rc=$?

echo "ARCHIVE_EXIT=$archive_rc"
if [ "$archive_rc" -ne 0 ]; then
  echo "ERROR: xcodebuild archive 失败（退出码 $archive_rc），保留旧归档不变。" >&2
  exit "$archive_rc"
fi

[ -d "$TMP_ARCHIVE" ] || apple_fail 70 "archive 返回 0，但临时 .xcarchive 不存在：$TMP_ARCHIVE"
APPS_DIR="$TMP_ARCHIVE/Products/Applications"
[ -d "$APPS_DIR" ] || apple_fail 70 "archive 缺 Products/Applications：$TMP_ARCHIVE"

TOTAL_APPS="$(apple_count_apps "$TMP_ARCHIVE/Products")"
FLAT_APPS="$(apple_count_apps "$APPS_DIR")"
if [ "$TOTAL_APPS" != "1" ] || [ "$FLAT_APPS" != "1" ]; then
  echo "ERROR: archive Products 下的 .app 数量异常：Products=$TOTAL_APPS，Products/Applications=$FLAT_APPS；要求恰好 1 个。" >&2
  exit 70
fi

APP_PATH="$(apple_first_app_flat "$APPS_DIR")"
[ -n "$APP_PATH" ] || apple_fail 70 "无法定位 archive 内的 .app。"
[ "$(basename "$APP_PATH")" = "$APPLE_FULL_PRODUCT_NAME" ] || \
  apple_fail 70 "archive 内 .app=$(basename "$APP_PATH")，与解析出的产物名 $APPLE_FULL_PRODUCT_NAME 不一致。"

EXEC_NAME="$(apple_app_executable "$APP_PATH")" || apple_fail 65 "归档产物缺 CFBundleExecutable：$APP_PATH"
apple_require_fresh "$APP_PATH/$EXEC_NAME" "$ARCHIVE_START"
apple_validate_device_app "$APP_PATH" "$APPLE_PROFILE"

# 全部校验通过后才替换旧归档。
case "$TMP_ARCHIVE" in
  "$APPLE_BUILD_ROOT"/*) ;;
  *) apple_fail 70 "临时归档不在 iOS/.build 下：$TMP_ARCHIVE" ;;
esac
rm -rf "$FINAL_ARCHIVE"
mv "$TMP_ARCHIVE" "$FINAL_ARCHIVE" || apple_fail 70 "无法把归档移动到 $FINAL_ARCHIVE"

FINAL_APP="$FINAL_ARCHIVE/Products/Applications/$APPLE_FULL_PRODUCT_NAME"
[ -d "$FINAL_APP" ] || apple_fail 70 "归档移动后 .app 缺失：$FINAL_APP"

echo "ARCHIVE_PATH=$FINAL_ARCHIVE"
echo "ARCHIVE_APP=$FINAL_APP"
echo "ARCHIVE_SCHEME=$APPLE_SCHEME"
echo "ARCHIVE_CONFIGURATION=$APPLE_CONFIGURATION"
echo "ARCHIVE_EXIT=0"
