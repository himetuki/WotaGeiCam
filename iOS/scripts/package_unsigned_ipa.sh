#!/usr/bin/env bash
# 无签名 IPA 打包入口。
#
# 只接受真实 iOS 设备构建出的 .app：
#   - 拒绝模拟器产物（DTPlatformName / vtool 平台必须是 iphoneos 而不是 iphonesimulator）；
#   - 拒绝含 x86_64 / i386 的切片；
#   - 打包后重新解压并校验 Payload 结构、Info.plist 与可执行文件。
# 不生成伪 .app / .ipa，不附带签名。
#
# 用法：
#   iOS/scripts/package_unsigned_ipa.sh --app <path/to/WotaGeiCam.app> [--output <path.ipa>]
# 退出码：
#   0 = 打包并通过 Payload 校验
#   64 = 参数错误
#   65 = 输入不是真实设备构建产物
#   69 = 本机缺少所需 Apple/打包工具，未执行
#   70 = 打包或校验失败
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IOS_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

APP_DIR=""
OUTPUT=""
usage() {
  cat <<'EOF'
用法: package_unsigned_ipa.sh --app <真实设备构建的 .app> [--output <输出 .ipa>]
  --app     必填，指向 xcodebuild archive/build 生成的真机 .app
  --output  可选，默认 iOS/.build/unsigned/<AppName>-unsigned.ipa（必须在 iOS/ 下）
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --app)
      APP_DIR="${2:-}"
      shift 2
      ;;
    --output)
      OUTPUT="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "ERROR: 未知参数：$1" >&2
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

if [ ! -d "$APP_DIR" ] || [ ! -f "$APP_DIR/Info.plist" ]; then
  echo "ERROR: $APP_DIR 不是包含 Info.plist 的 .app 目录" >&2
  exit 64
fi

case "$APP_DIR" in
  *.app) ;;
  *)
    echo "ERROR: --app 必须指向 .app 目录" >&2
    exit 64
    ;;
esac

if [ -z "$OUTPUT" ]; then
  OUTPUT="$IOS_ROOT/.build/unsigned/$(basename "$APP_DIR" .app)-unsigned.ipa"
fi

# 输出必须在 iOS/ 下：相对路径先按当前目录展开，再做前缀判断。
case "$OUTPUT" in
  /*) ;;
  *) OUTPUT="$PWD/$OUTPUT" ;;
esac
case "$OUTPUT" in
  "$IOS_ROOT"/*) ;;
  *)
    echo "ERROR: 输出必须位于 iOS/ 下：$OUTPUT" >&2
    exit 64
    ;;
esac

OUTPUT_DIR="$(dirname "$OUTPUT")"
mkdir -p "$OUTPUT_DIR"
OUTPUT_ABS="$(cd "$OUTPUT_DIR" && pwd)/$(basename "$OUTPUT")"

missing_tools=0
for tool in plutil lipo zip unzip; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "MISSING $tool"
    missing_tools=$((missing_tools + 1))
  fi
done
if ! command -v xcrun >/dev/null 2>&1 || ! xcrun --find vtool >/dev/null 2>&1; then
  echo "MISSING xcrun/vtool"
  missing_tools=$((missing_tools + 1))
fi
if [ "$missing_tools" -gt 0 ]; then
  echo "BLOCKED: 缺少 Apple/打包工具，未执行 IPA 打包。" >&2
  exit 69
fi

PLIST="$APP_DIR/Info.plist"
BUNDLE_ID="$(plutil -extract CFBundleIdentifier raw -o - "$PLIST")"
EXECUTABLE="$(plutil -extract CFBundleExecutable raw -o - "$PLIST")"
PLATFORM="$(plutil -extract DTPlatformName raw -o - "$PLIST" 2>/dev/null || true)"
MIN_OS="$(plutil -extract MinimumOSVersion raw -o - "$PLIST" 2>/dev/null || true)"

if [ -z "$BUNDLE_ID" ] || [ -z "$EXECUTABLE" ]; then
  echo "ERROR: Info.plist 缺 CFBundleIdentifier 或 CFBundleExecutable" >&2
  exit 65
fi

EXEC_PATH="$APP_DIR/$EXECUTABLE"
if [ ! -f "$EXEC_PATH" ]; then
  echo "ERROR: 可执行文件不存在：$EXEC_PATH" >&2
  exit 65
fi

echo "input app   = $APP_DIR"
echo "bundle id   = $BUNDLE_ID"
echo "executable  = $EXECUTABLE"
echo "platform    = ${PLATFORM:-unknown}"
echo "minimum OS  = ${MIN_OS:-unknown}"

if [ "$PLATFORM" != "iphoneos" ]; then
  echo "ERROR: 拒绝模拟器/非设备产物：DTPlatformName=$PLATFORM，需要 iphoneos。" >&2
  echo "请用 iOS/scripts/apple_archive.sh（真机 SDK，无签名）生成 .app 后再打包。" >&2
  exit 65
fi

BUILD_INFO="$(xcrun vtool -show-build "$EXEC_PATH" 2>/dev/null || true)"
if ! printf '%s\n' "$BUILD_INFO" | grep -Eq 'platform[[:space:]]+IOS[[:space:]]*$'; then
  echo "ERROR: vtool 未报告 platform IOS，拒绝打包。" >&2
  printf '%s\n' "$BUILD_INFO" >&2
  exit 65
fi
if printf '%s\n' "$BUILD_INFO" | grep -Eq 'platform[[:space:]]+IOSSIMULATOR'; then
  echo "ERROR: vtool 报告 IOSSIMULATOR，拒绝模拟器产物。" >&2
  exit 65
fi

ARCHS="$(lipo -archs "$EXEC_PATH")"
echo "archs       = $ARCHS"
case "$ARCHS" in
  *arm64*) ;;
  *)
    echo "ERROR: 可执行文件不含 arm64 切片。" >&2
    exit 65
    ;;
esac
case "$ARCHS" in
  *x86_64*|*i386*)
    echo "ERROR: 可执行文件含模拟器切片（x86_64/i386）。" >&2
    exit 65
    ;;
esac

PACKAGE_ROOT="$IOS_ROOT/.build/package"
mkdir -p "$PACKAGE_ROOT"
STAGE="$(mktemp -d "$PACKAGE_ROOT/ipa.XXXXXX")"
VALIDATE="$(mktemp -d "$PACKAGE_ROOT/validate.XXXXXX")"
cleanup() {
  rm -rf "$STAGE" "$VALIDATE"
}
trap cleanup EXIT

mkdir -p "$STAGE/Payload"
APP_NAME="$(basename "$APP_DIR")"
if command -v ditto >/dev/null 2>&1; then
  ditto "$APP_DIR" "$STAGE/Payload/$APP_NAME"
else
  cp -R "$APP_DIR" "$STAGE/Payload/$APP_NAME"
fi

mkdir -p "$(dirname "$OUTPUT_ABS")"
rm -f "$OUTPUT_ABS"
(
  cd "$STAGE"
  zip -qry "$OUTPUT_ABS" Payload
)

# 解压回读校验 Payload 结构。
unzip -q "$OUTPUT_ABS" -d "$VALIDATE"
PAYLOAD="$VALIDATE/Payload"
if [ ! -d "$PAYLOAD" ]; then
  echo "ERROR: IPA 缺 Payload 目录" >&2
  exit 70
fi

APP_COUNT="$(find "$PAYLOAD" -maxdepth 1 -type d -name '*.app' | wc -l | tr -d ' ')"
if [ "$APP_COUNT" != "1" ]; then
  echo "ERROR: Payload 下应有且仅有 1 个 .app，实际 $APP_COUNT" >&2
  exit 70
fi

IPA_APP="$(find "$PAYLOAD" -maxdepth 1 -type d -name '*.app' | head -n 1)"
IPA_EXEC="$IPA_APP/$(plutil -extract CFBundleExecutable raw -o - "$IPA_APP/Info.plist")"
if [ ! -f "$IPA_APP/Info.plist" ] || [ ! -x "$IPA_EXEC" ]; then
  echo "ERROR: IPA 内 .app 缺 Info.plist 或可执行文件" >&2
  exit 70
fi
plutil -lint "$IPA_APP/Info.plist" >/dev/null

IPA_PLATFORM="$(plutil -extract DTPlatformName raw -o - "$IPA_APP/Info.plist" 2>/dev/null || true)"
if [ "$IPA_PLATFORM" != "iphoneos" ]; then
  echo "ERROR: IPA 内 .app 平台不是 iphoneos：$IPA_PLATFORM" >&2
  exit 70
fi

SIZE_BYTES="$(wc -c < "$OUTPUT_ABS" | tr -d ' ')"
if command -v shasum >/dev/null 2>&1; then
  SHA256="$(shasum -a 256 "$OUTPUT_ABS" | awk '{print $1}')"
else
  SHA256="$(sha256sum "$OUTPUT_ABS" | awk '{print $1}')"
fi

echo "IPA_PATH=$OUTPUT_ABS"
echo "IPA_SIZE_BYTES=$SIZE_BYTES"
echo "IPA_SHA256=$SHA256"
echo "IPA_PAYLOAD_APP=$APP_NAME"
echo "IPA unsigned; 侧载前必须由使用者重新签名。"
echo "PACKAGE_EXIT=0"
