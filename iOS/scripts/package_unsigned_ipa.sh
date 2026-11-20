#!/usr/bin/env bash
# 无签名 IPA 打包入口。
#
# 只接受真实 iOS 设备构建出的 .app：
#   - Info.plist DTPlatformName 必须是 iphoneos，Mach-O 平台必须是 IOS；
#   - 精确拒绝模拟器切片（x86_64/i386）与模拟器 Mach-O；
#   - 架构只接受 legacy {armv7, arm64} 或 modern {arm64}，逐架构校验
#     LC_VERSION_MIN_IPHONEOS / LC_BUILD_VERSION 的最低系统；
#   - 拒绝含 _CodeSignature / mobileprovision 的已签名输入；
#   - 先在 iOS/.build/tmp 内组包、解压回读并重校验 Payload 与哈希，
#     全部通过后才原子替换目标 .ipa；失败时保留旧 .ipa 不变。
# 不生成伪 .app / 伪 .ipa，不附带签名。
#
# 用法：
#   iOS/scripts/package_unsigned_ipa.sh --app <path/to/WotaGeiCam.app> [--output <path.ipa>]
# 退出码：
#   0  = 打包并通过 Payload 校验
#   64 = 参数错误
#   65 = 输入不是合格的真实设备构建产物
#   69 = 本机缺少所需 Apple/打包工具，未执行
#   70 = 打包或回读校验失败
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=apple_device_common.sh
. "$SCRIPT_DIR/apple_device_common.sh"

APP_DIR=""
OUTPUT=""

usage() {
  cat <<'EOF'
用法: package_unsigned_ipa.sh --app <真实设备构建的 .app> [--output <输出 .ipa>]
  --app     必填，指向 xcodebuild archive/build 生成的真机 .app
  --output  可选，默认 iOS/.build/unsigned/<AppName>-unsigned.ipa；必须物理位于 iOS/ 下
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --app)
      [ "$#" -ge 2 ] || { echo "ERROR: --app 缺少参数" >&2; exit 64; }
      APP_DIR="$2"
      shift 2
      ;;
    --output)
      [ "$#" -ge 2 ] || { echo "ERROR: --output 缺少参数" >&2; exit 64; }
      OUTPUT="$2"
      shift 2
      ;;
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
      echo "ERROR: 不接受位置参数：$1" >&2
      usage >&2
      exit 64
      ;;
  esac
done

if [ -z "$APP_DIR" ]; then
  echo "ERROR: 必须用 --app 指定 .app" >&2
  usage >&2
  exit 64
fi

case "$APP_DIR" in
  *.app) ;;
  *)
    echo "ERROR: --app 必须指向 .app 目录：$APP_DIR" >&2
    exit 64
    ;;
esac

if [ ! -d "$APP_DIR" ] || [ -L "$APP_DIR" ] || [ ! -f "$APP_DIR/Info.plist" ]; then
  echo "ERROR: $APP_DIR 不是包含 Info.plist 的普通 .app 目录" >&2
  exit 64
fi

apple_require_tools plutil lipo otool zip unzip
if ! command -v shasum >/dev/null 2>&1 && ! command -v sha256sum >/dev/null 2>&1; then
  echo "MISSING shasum/sha256sum" >&2
  apple_fail 69 "缺少 SHA-256 工具，未执行 IPA 打包。"
fi

APP_NAME="$(basename "$APP_DIR")"
EXEC_NAME="$(apple_app_executable "$APP_DIR")" || {
  echo "ERROR: Info.plist 缺 CFBundleExecutable：$APP_DIR/Info.plist" >&2
  exit 65
}
case "$EXEC_NAME" in
  */*|"")
    echo "ERROR: 非法 CFBundleExecutable：$EXEC_NAME" >&2
    exit 65
    ;;
esac
ARCHS_RAW="$(apple_lipo -archs "$APP_DIR/$EXEC_NAME")" || {
  echo "ERROR: lipo 无法读取主可执行文件架构：$APP_DIR/$EXEC_NAME" >&2
  exit 65
}
ARCHS_NORM="$(apple_normalize_archs "$ARCHS_RAW")"
case " $ARCHS_NORM " in
  *" x86_64 "*|*" i386 "*)
    echo "ERROR: 拒绝模拟器切片：archs=[$ARCHS_NORM]。请用真机 archive 产物。" >&2
    exit 65
    ;;
esac
PROFILE="$(apple_profile_for_archs "$ARCHS_NORM" || true)"
if [ -z "$PROFILE" ]; then
  echo "ERROR: 不支持的架构组合：archs=[$ARCHS_NORM]。" >&2
  echo "  只接受 legacy {armv7, arm64} 或 modern {arm64}。" >&2
  exit 65
fi
echo "input app   = $APP_DIR"
echo "input archs = $ARCHS_NORM"
echo "input profile = $PROFILE"

apple_validate_device_app "$APP_DIR" "$PROFILE"

if [ -z "$OUTPUT" ]; then
  OUTPUT="$APPLE_BUILD_ROOT/unsigned/$(basename "$APP_DIR" .app)-unsigned.ipa"
fi
# 直接调用（不要放进 $(...)）：路径越界时 apple_fail 才能以 64 退出主进程。
apple_resolve_output_path "$OUTPUT"
OUTPUT_ABS="$APPLE_RESOLVED_OUTPUT"
OUTPUT_DIR="$(dirname "$OUTPUT_ABS")"

mkdir -p "$APPLE_TMP_ROOT" "$OUTPUT_DIR"
STAGE="$(mktemp -d "$APPLE_TMP_ROOT/ipa-stage.XXXXXX")"
VERIFY="$(mktemp -d "$APPLE_TMP_ROOT/ipa-verify.XXXXXX")"
TMP_IPA="$OUTPUT_DIR/.$(basename "$OUTPUT_ABS" .ipa)-tmp.$$.ipa"
rm -f "$TMP_IPA"
cleanup() {
  rm -rf "$STAGE" "$VERIFY"
  rm -f "$TMP_IPA"
}
trap cleanup EXIT

mkdir -p "$STAGE/Payload"
if command -v ditto >/dev/null 2>&1; then
  ditto "$APP_DIR" "$STAGE/Payload/$APP_NAME" || apple_fail 70 "复制 .app 失败：$APP_DIR"
else
  cp -R "$APP_DIR" "$STAGE/Payload/$APP_NAME" || apple_fail 70 "复制 .app 失败：$APP_DIR"
fi
[ -d "$STAGE/Payload/$APP_NAME" ] || apple_fail 70 "Payload 内 .app 缺失。"

(
  cd "$STAGE"
  zip -qry "$TMP_IPA" Payload
) || apple_fail 70 "zip 生成 IPA 失败：$TMP_IPA"

# 回读：解压到临时目录，重新校验结构、平台、架构、Mach-O 与哈希。
unzip -q "$TMP_IPA" -d "$VERIFY" || apple_fail 70 "IPA 解压回读失败：$TMP_IPA"
[ -d "$VERIFY/Payload" ] || apple_fail 70 "IPA 缺 Payload 目录。"
IPA_APP_COUNT="$(find "$VERIFY/Payload" -mindepth 1 -maxdepth 1 -type d -name '*.app' | wc -l | tr -d ' ')"
[ "$IPA_APP_COUNT" = "1" ] || apple_fail 70 "Payload 下应有且仅有 1 个 .app，实际 $IPA_APP_COUNT。"
IPA_APP="$(apple_first_app_flat "$VERIFY/Payload")"
[ -n "$IPA_APP" ] || apple_fail 70 "无法定位 Payload 内的 .app。"
[ "$(basename "$IPA_APP")" = "$APP_NAME" ] || \
  apple_fail 70 "Payload 内 .app=$(basename "$IPA_APP")，与输入 $APP_NAME 不一致。"

apple_validate_device_app "$IPA_APP" "$PROFILE"

SRC_EXEC_SHA="$(apple_sha256 "$APP_DIR/$EXEC_NAME")" || apple_fail 70 "无法计算源可执行文件 SHA-256。"
IPA_EXEC_NAME="$(apple_app_executable "$IPA_APP")" || apple_fail 70 "解压后的 .app 缺 CFBundleExecutable。"
IPA_EXEC_SHA="$(apple_sha256 "$IPA_APP/$IPA_EXEC_NAME")" || apple_fail 70 "无法计算解压后可执行文件 SHA-256。"
[ "$SRC_EXEC_SHA" = "$IPA_EXEC_SHA" ] || \
  apple_fail 70 "解压后主可执行文件 SHA-256 不一致：源=$SRC_EXEC_SHA，IPA=$IPA_EXEC_SHA。"

SRC_BUNDLE_ID="$(plutil -extract CFBundleIdentifier raw -o - "$APP_DIR/Info.plist" 2>/dev/null || true)"
IPA_BUNDLE_ID="$(plutil -extract CFBundleIdentifier raw -o - "$IPA_APP/Info.plist" 2>/dev/null || true)"
[ -n "$SRC_BUNDLE_ID" ] && [ "$SRC_BUNDLE_ID" = "$IPA_BUNDLE_ID" ] || \
  apple_fail 70 "解压后 CFBundleIdentifier 不一致：源=${SRC_BUNDLE_ID:-<missing>}，IPA=${IPA_BUNDLE_ID:-<missing>}。"

TMP_IPA_SHA="$(apple_sha256 "$TMP_IPA")" || apple_fail 70 "无法计算 IPA SHA-256。"
SIZE_BYTES="$(wc -c < "$TMP_IPA" | tr -d ' ')"

# 校验全部通过后才替换目标文件；失败时旧 IPA 原样保留。
if [ -e "$OUTPUT_ABS" ] && [ ! -f "$OUTPUT_ABS" ]; then
  apple_fail 70 "输出路径已存在且不是普通文件：$OUTPUT_ABS"
fi
mv -f "$TMP_IPA" "$OUTPUT_ABS" || apple_fail 70 "无法写入输出 IPA：$OUTPUT_ABS"
FINAL_SHA="$(apple_sha256 "$OUTPUT_ABS")" || apple_fail 70 "无法计算输出 IPA SHA-256。"
[ "$FINAL_SHA" = "$TMP_IPA_SHA" ] || apple_fail 70 "输出 IPA 哈希与校验前不一致：$OUTPUT_ABS"

echo "IPA_PATH=$OUTPUT_ABS"
echo "IPA_SIZE_BYTES=$SIZE_BYTES"
echo "IPA_SHA256=$FINAL_SHA"
echo "IPA_PAYLOAD_APP=$APP_NAME"
echo "IPA_PROFILE=$PROFILE"
echo "IPA_EXEC_SHA256=$SRC_EXEC_SHA"
echo "IPA unsigned; 侧载前必须由使用者重新签名。"
echo "PACKAGE_EXIT=0"
