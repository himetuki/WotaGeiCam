#!/usr/bin/env bash
# 无签名真机构建入口（固定 -sdk iphoneos / generic/platform=iOS；不启动模拟器、不跑测试）。
#
# 用法：
#   iOS/scripts/apple_build.sh [scheme] [configuration]
# 环境变量：
#   WOTA_SCHEME / WOTA_CONFIGURATION
#
# 只接受 shared scheme 自身的 Debug/Release 配置对（来自 generate_project.py 生成的
# xcscheme），例如 WotaGeiCam 只允许 Debug/Release，WotaGeiCamModern 只允许
# Debug-Modern/Release-Modern；交叉组合直接拒绝。
#
# 退出码：
#   0  = 构建成功，且新产出的 .app 通过平台/架构/最低系统/Mach-O 校验
#   64 = scheme/configuration 不匹配或用法错误
#   65 = 解析出的构建配置或产物不符合预期
#   69 = 本机没有 xcodebuild / Apple 工具，明确记为未执行
#   70 = 构建产物缺失、陈旧或 Mach-O 校验失败
#   其他 = 透传 xcodebuild 的退出码（BUILD_EXIT 如实打印）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=apple_device_common.sh
. "$SCRIPT_DIR/apple_device_common.sh"

usage() {
  cat <<'EOF'
用法: apple_build.sh [scheme] [configuration]
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
CONFIGURATION="${CONFIGURATION:-${WOTA_CONFIGURATION:-Debug}}"

# 先校验 scheme/配置对，再检查工具：配置错误即使在没有 Xcode 的主机上也要如实拒绝。
apple_validate_scheme_configuration "$SCHEME" "$CONFIGURATION"

if ! command -v xcodebuild >/dev/null 2>&1; then
  echo "BLOCKED: xcodebuild 未找到，构建未执行。"
  if [ "$APPLE_PROFILE" = "legacy" ]; then
    echo "  该配置属于 Legacy 工具链（iOS 10.0 / armv7 + arm64），需要 Xcode <= 13.4.1。"
    echo "  Xcode 14+ 不再产出 armv7，也不再支持 iOS 10 部署目标，无法替代。"
  else
    echo "  该配置属于 Modern 工具链（iOS 15.0 / arm64），需要 Xcode 14+。"
  fi
  echo "复现命令（macOS，真机 SDK、无签名）："
  echo "  xcodebuild -project \"$APPLE_PROJECT\" -scheme \"$APPLE_SCHEME\" -configuration \"$APPLE_CONFIGURATION\" -sdk iphoneos -destination 'generic/platform=iOS' -derivedDataPath \"$APPLE_DERIVED_DATA\" CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY='' build"
  exit 69
fi

apple_require_tools plutil lipo otool

mkdir -p "$APPLE_DERIVED_DATA" "$APPLE_TMP_ROOT"
SETTINGS_FILE="$(mktemp "$APPLE_TMP_ROOT/build-settings.XXXXXX")"
cleanup() {
  rm -f "$SETTINGS_FILE"
}
trap cleanup EXIT

settings_rc=0
apple_show_build_settings "$APPLE_SCHEME" "$APPLE_CONFIGURATION" "$SETTINGS_FILE" || settings_rc=$?
if [ "$settings_rc" -ne 0 ]; then
  echo "ERROR: xcodebuild -showBuildSettings 失败（退出码 ${settings_rc}），未执行构建。" >&2
  exit "$settings_rc"
fi

apple_validate_build_settings "$SETTINGS_FILE" "$APPLE_PROFILE" "$APPLE_CONFIGURATION"

APP_PATH="$APPLE_TARGET_BUILD_DIR/$APPLE_FULL_PRODUCT_NAME"
case "$APP_PATH" in
  "$APPLE_BUILD_ROOT"/*) ;;
  *) apple_fail 70 "拒绝操作 iOS/.build 之外的产物路径：$APP_PATH" ;;
esac

# 删除旧产物，确保后面看到的是本次构建真正生成的 .app（不做增量陈旧复用）。
rm -rf "$APP_PATH"

BUILD_START="$(date +%s)"
build_rc=0
xcodebuild \
  -project "$APPLE_PROJECT" \
  -scheme "$APPLE_SCHEME" \
  -configuration "$APPLE_CONFIGURATION" \
  -sdk iphoneos \
  -destination "generic/platform=iOS" \
  -derivedDataPath "$APPLE_DERIVED_DATA" \
  CODE_SIGNING_ALLOWED=NO \
  CODE_SIGNING_REQUIRED=NO \
  CODE_SIGN_IDENTITY="" \
  build || build_rc=$?

echo "BUILD_EXIT=$build_rc"
if [ "$build_rc" -ne 0 ]; then
  echo "ERROR: xcodebuild build 失败（退出码 ${build_rc}），不产出新的 .app。" >&2
  exit "$build_rc"
fi

[ -d "$APP_PATH" ] || apple_fail 70 "构建返回 0，但未找到实际产物：$APP_PATH"
EXEC_NAME="$(apple_app_executable "$APP_PATH")" || apple_fail 65 "产物缺 CFBundleExecutable：$APP_PATH"
apple_require_fresh "$APP_PATH/$EXEC_NAME" "$BUILD_START"
apple_validate_device_app "$APP_PATH" "$APPLE_PROFILE"

EXEC_SHA="$(apple_sha256 "$APP_PATH/$EXEC_NAME" 2>/dev/null || true)"
if [ -n "$EXEC_SHA" ]; then
  echo "BUILD_EXEC_SHA256=$EXEC_SHA"
fi

echo "BUILD_APP=$APP_PATH"
echo "BUILD_SCHEME=$APPLE_SCHEME"
echo "BUILD_CONFIGURATION=$APPLE_CONFIGURATION"
echo "BUILD_EXIT=0"
