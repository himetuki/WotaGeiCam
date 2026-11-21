#!/usr/bin/env bash
# Apple 真机（iphoneos）构建 / 归档 / 无签名 IPA 打包的共用函数。
#
# 本文件只被 apple_build.sh、apple_archive.sh、package_unsigned_ipa.sh source，
# 不单独执行；所有临时目录与产物都限制在 iOS/.build/ 内。
# 兼容 macOS 自带 bash 3.2（不使用关联数组、mapfile、${var,,} 等 4.x 语法）。
#
# 事实来源：
#   - scheme 与 Debug/Release 配置的对应关系：generate_project.py 的 SCHEMES
#     与磁盘上的 shared xcscheme 双重核对，二者不一致直接拒绝；
#   - 期望架构 / 最低系统：Config/ToolchainLegacy.xcconfig（armv7 + arm64 / iOS 10.0）
#     与 Config/ToolchainModern.xcconfig（arm64 / iOS 15.0）；
#   - 产物位置与平台：xcodebuild -showBuildSettings 的解析结果和实际文件，
#     不按 DerivedData 默认目录猜路径。

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  echo "apple_device_common.sh 只供其他脚本 source，不能直接执行。" >&2
  exit 64
fi

APPLE_COMMON_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
APPLE_IOS_ROOT="$(cd "$APPLE_COMMON_DIR/.." && pwd -P)"
APPLE_PROJECT="$APPLE_IOS_ROOT/WotaGeiCam.xcodeproj"
APPLE_GENERATOR="$APPLE_COMMON_DIR/generate_project.py"
APPLE_BUILD_ROOT="$APPLE_IOS_ROOT/.build"
APPLE_DERIVED_DATA="$APPLE_BUILD_ROOT/DerivedData"
APPLE_TMP_ROOT="$APPLE_BUILD_ROOT/tmp"

# 由 apple_validate_scheme_configuration / apple_validate_build_settings 填写。
APPLE_SCHEME=""
APPLE_CONFIGURATION=""
APPLE_DEBUG_CONFIG=""
APPLE_RELEASE_CONFIG=""
APPLE_PROFILE=""
APPLE_EXPECTED_ARCHS=""
APPLE_EXPECTED_MIN_OS=""
APPLE_FULL_PRODUCT_NAME=""
APPLE_TARGET_BUILD_DIR=""
APPLE_RESOLVED_OUTPUT=""

apple_fail() {
  local code="$1"
  shift
  printf 'ERROR: %s\n' "$*" >&2
  exit "$code"
}

apple_trim() {
  printf '%s' "$1" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'
}

# 版本号归一成 major.minor.patch，便于 10 / 10.0 / 10.0.0 等价比较。
apple_version_key() {
  awk -v v="$1" 'BEGIN {
    n = split(v, a, ".");
    printf "%d.%d.%d", a[1] + 0, a[2] + 0, (n >= 3 ? a[3] + 0 : 0);
  }'
}

# apple_version_ge A B：A >= B 返回 0。
apple_version_ge() {
  awk -v a="$(apple_version_key "$1")" -v b="$(apple_version_key "$2")" 'BEGIN {
    na = split(a, x, ".");
    nb = split(b, y, ".");
    for (i = 1; i <= 3; i++) {
      av = (i <= na ? x[i] + 0 : 0);
      bv = (i <= nb ? y[i] + 0 : 0);
      if (av > bv) exit 0;
      if (av < bv) exit 1;
    }
    exit 0;
  }'
}

apple_mtime() {
  if stat -c %Y "$1" >/dev/null 2>&1; then
    stat -c %Y "$1"
  else
    stat -f %m "$1"
  fi
}

apple_sha256() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  elif command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    return 1
  fi
}

apple_tool_path() {
  local tool="$1"
  if command -v "$tool" >/dev/null 2>&1; then
    command -v "$tool"
    return 0
  fi
  if command -v xcrun >/dev/null 2>&1 && xcrun --find "$tool" >/dev/null 2>&1; then
    xcrun --find "$tool"
    return 0
  fi
  return 1
}

apple_require_tools() {
  local missing=0 tool path
  for tool in "$@"; do
    if path="$(apple_tool_path "$tool")"; then
      printf 'TOOL %s=%s\n' "$tool" "$path"
    else
      printf 'MISSING %s\n' "$tool" >&2
      missing=$((missing + 1))
    fi
  done
  if [ "$missing" -gt 0 ]; then
    apple_fail 69 "缺少 $missing 个 Apple/打包工具，未执行。"
  fi
}

# 统一走 xcrun，避免 PATH 里缺少 Xcode 工具链目录时误判。
apple_lipo() {
  xcrun lipo "$@"
}

apple_otool() {
  xcrun otool "$@"
}

apple_profile_archs() {
  case "$1" in
    legacy) printf '%s\n' "armv7 arm64" ;;
    modern) printf '%s\n' "arm64" ;;
    *) return 1 ;;
  esac
}

apple_profile_min_os() {
  case "$1" in
    legacy) printf '%s\n' "10.0" ;;
    modern) printf '%s\n' "15.0" ;;
    *) return 1 ;;
  esac
}

apple_normalize_archs() {
  printf '%s\n' $1 | sed '/^[[:space:]]*$/d' | sort | tr '\n' ' ' | sed 's/[[:space:]]*$//'
}

# 只接受真实设备构建会产出的两种精确集合：
#   legacy = {armv7, arm64}；modern = {arm64}。
# 含 x86_64/i386 返回 2，其他组合返回 1。
apple_profile_for_archs() {
  local normalized="$1" legacy_normalized
  case " $normalized " in
    *" x86_64 "*|*" i386 "*) return 2 ;;
  esac
  legacy_normalized="$(apple_normalize_archs "armv7 arm64")"
  case "$normalized" in
    "$legacy_normalized") printf '%s\n' "legacy" ;;
    "arm64") printf '%s\n' "modern" ;;
    *) return 1 ;;
  esac
}

# 从 generate_project.py 的 SCHEMES 读取 <scheme> 的 Debug/Release 配置对。
apple_generator_pair() {
  local scheme="$1" line pair
  [ -f "$APPLE_GENERATOR" ] || return 1
  line="$(grep -m 1 -F "(\"$scheme.xcscheme\"," "$APPLE_GENERATOR" 2>/dev/null || true)"
  [ -n "$line" ] || return 1
  pair="$(printf '%s\n' "$line" | sed -E 's/.*\.xcscheme", "([^"]+)", "([^"]+)".*/\1 \2/')"
  case "$pair" in
    *'"'*|*'.xcscheme'*) return 1 ;;
  esac
  set -- $pair
  [ "$#" -eq 2 ] || return 1
  printf '%s %s\n' "$1" "$2"
}

# 从实际 shared xcscheme 读取：LaunchAction = Debug 配置，ArchiveAction = Release 配置。
apple_scheme_file_pair() {
  local file="$1" debug release
  [ -f "$file" ] || return 1
  debug="$(sed -n '/<LaunchAction/,/<\/LaunchAction>/s/.*buildConfiguration *= *"\([^"]*\)".*/\1/p' "$file" | head -n 1 || true)"
  release="$(sed -n '/<ArchiveAction/,/<\/ArchiveAction>/s/.*buildConfiguration *= *"\([^"]*\)".*/\1/p' "$file" | head -n 1 || true)"
  [ -n "$debug" ] && [ -n "$release" ] || return 1
  printf '%s %s\n' "$debug" "$release"
}

apple_print_scheme_pairs() {
  echo "可用的 shared scheme（源自 generate_project.py）：" >&2
  grep -F '.xcscheme",' "$APPLE_GENERATOR" 2>/dev/null \
    | sed -E 's/.*\("([^"]+)\.xcscheme", "([^"]+)", "([^"]+)".*/  \1: Debug=\2 Release=\3/' >&2 || true
}

# 校验并设置 APPLE_SCHEME / APPLE_CONFIGURATION / APPLE_*_CONFIG / APPLE_PROFILE /
# APPLE_EXPECTED_ARCHS / APPLE_EXPECTED_MIN_OS。
# 64 = scheme/配置不匹配；70 = 仓库内生成物与生成器不一致。
apple_validate_scheme_configuration() {
  local scheme="$1" configuration="$2" scheme_file generator_pair scheme_pair

  [ -n "$scheme" ] || apple_fail 64 "scheme 不能为空。"
  [ -n "$configuration" ] || apple_fail 64 "configuration 不能为空。"
  case "$scheme" in
    *[!A-Za-z0-9._-]*) apple_fail 64 "非法 scheme 名称：$scheme" ;;
  esac
  case "$configuration" in
    *[!A-Za-z0-9._-]*) apple_fail 64 "非法 configuration 名称：$configuration" ;;
  esac

  scheme_file="$APPLE_PROJECT/xcshareddata/xcschemes/$scheme.xcscheme"
  [ -f "$scheme_file" ] || {
    echo "ERROR: 没有 shared scheme：$scheme" >&2
    echo "  expected: $scheme_file" >&2
    apple_print_scheme_pairs
    exit 64
  }

  generator_pair="$(apple_generator_pair "$scheme")" || \
    apple_fail 70 "无法从 generate_project.py 读取 scheme $scheme 的配置对。"
  scheme_pair="$(apple_scheme_file_pair "$scheme_file")" || \
    apple_fail 70 "无法从 $scheme_file 读取 LaunchAction/ArchiveAction 的构建配置。"

  [ "$generator_pair" = "$scheme_pair" ] || \
    apple_fail 70 "scheme $scheme 的配置对与生成器不一致：generate_project.py=[$generator_pair]，xcscheme=[$scheme_pair]；请重跑 generate_project.py。"

  set -- $scheme_pair
  APPLE_DEBUG_CONFIG="$1"
  APPLE_RELEASE_CONFIG="$2"

  case "$configuration" in
    "$APPLE_DEBUG_CONFIG"|"$APPLE_RELEASE_CONFIG") ;;
    *)
      echo "ERROR: scheme $scheme 不支持 configuration=${configuration}。" >&2
      echo "  该 scheme 只接受：${APPLE_DEBUG_CONFIG}（Debug）或 ${APPLE_RELEASE_CONFIG}（Release）。" >&2
      exit 64
      ;;
  esac

  case "$configuration" in
    *Modern*) APPLE_PROFILE="modern" ;;
    *) APPLE_PROFILE="legacy" ;;
  esac

  APPLE_SCHEME="$scheme"
  APPLE_CONFIGURATION="$configuration"
  APPLE_EXPECTED_ARCHS="$(apple_profile_archs "$APPLE_PROFILE")" || \
    apple_fail 70 "无法为 profile $APPLE_PROFILE 推导期望架构。"
  APPLE_EXPECTED_MIN_OS="$(apple_profile_min_os "$APPLE_PROFILE")" || \
    apple_fail 70 "无法为 profile $APPLE_PROFILE 推导最低系统。"

  echo "SCHEME=$APPLE_SCHEME"
  echo "CONFIGURATION=$APPLE_CONFIGURATION"
  echo "SCHEME_CONFIG_PAIR=$APPLE_DEBUG_CONFIG/$APPLE_RELEASE_CONFIG"
  echo "PROFILE=$APPLE_PROFILE"
  echo "EXPECTED_ARCHS=$APPLE_EXPECTED_ARCHS"
  echo "EXPECTED_MINIMUM_OS=$APPLE_EXPECTED_MIN_OS"
}

apple_app_executable() {
  local app="$1" name
  name="$(plutil -extract CFBundleExecutable raw -o - "$app/Info.plist" 2>/dev/null || true)"
  [ -n "$name" ] || return 1
  printf '%s\n' "$name"
}

# 校验一个真实的 iphoneos .app：
#   - Info.plist 平台、最低系统、主可执行文件；
#   - 精确架构集合（legacy {armv7,arm64} / modern {arm64}），拒绝模拟器切片；
#   - 逐架构 Mach-O 证据：MH_EXECUTE + LC_BUILD_VERSION(platform IOS)/LC_VERSION_MIN_IPHONEOS；
#   - 拒绝含 _CodeSignature / mobileprovision 的已签名或带描述文件输入。
# 65 = 输入产物不合格。
apple_validate_device_app() {
  local app="$1" profile="$2"
  local expected_archs expected_archs_norm expected_min_os plist exec_name exec_path platform min_os
  local archs_raw archs_norm signed_artifact arch header filetype loads
  local build_platform build_min_os evidence

  expected_archs="$(apple_profile_archs "$profile")" || apple_fail 65 "未知 profile：$profile"
  expected_archs_norm="$(apple_normalize_archs "$expected_archs")"
  expected_min_os="$(apple_profile_min_os "$profile")" || apple_fail 65 "未知 profile：$profile"

  case "$app" in
    *.app) ;;
    *) apple_fail 65 "不是 .app 目录：$app" ;;
  esac
  [ -d "$app" ] || apple_fail 65 ".app 不存在：$app"
  [ ! -L "$app" ] || apple_fail 65 "拒绝符号链接 .app：$app"

  plist="$app/Info.plist"
  [ -f "$plist" ] || apple_fail 65 "缺少 Info.plist：$plist"
  plutil -lint "$plist" >/dev/null || apple_fail 65 "Info.plist 不是合法 plist：$plist"

  signed_artifact="$(find "$app" \( -name '_CodeSignature' -o -name '*.mobileprovision' -o -name 'CodeResources' \) -print -quit 2>/dev/null || true)"
  [ -z "$signed_artifact" ] || \
    apple_fail 65 "拒绝已签名/带描述文件的输入：${signed_artifact}（必须来自 CODE_SIGNING_ALLOWED=NO 的设备构建）"

  exec_name="$(apple_app_executable "$app")" || apple_fail 65 "Info.plist 缺 CFBundleExecutable。"
  case "$exec_name" in
    */*|"") apple_fail 65 "非法 CFBundleExecutable：$exec_name" ;;
  esac
  exec_path="$app/$exec_name"
  [ -f "$exec_path" ] || apple_fail 65 "主可执行文件不存在：$exec_path"
  [ ! -L "$exec_path" ] || apple_fail 65 "主可执行文件是符号链接：$exec_path"

  platform="$(plutil -extract DTPlatformName raw -o - "$plist" 2>/dev/null || true)"
  [ "$platform" = "iphoneos" ] || \
    apple_fail 65 "拒绝非真机产物：DTPlatformName=${platform:-<missing>}，需要 iphoneos。"

  min_os="$(plutil -extract MinimumOSVersion raw -o - "$plist" 2>/dev/null || true)"
  [ -n "$min_os" ] || apple_fail 65 "Info.plist 缺 MinimumOSVersion。"
  [ "$(apple_version_key "$min_os")" = "$(apple_version_key "$expected_min_os")" ] || \
    apple_fail 65 "MinimumOSVersion=$min_os 与 $profile 期望 $expected_min_os 不符。"

  archs_raw="$(apple_lipo -archs "$exec_path")" || apple_fail 65 "lipo 无法读取架构：$exec_path"
  archs_norm="$(apple_normalize_archs "$archs_raw")"
  case " $archs_norm " in
    *" x86_64 "*|*" i386 "*)
      apple_fail 65 "拒绝模拟器切片：archs=[$archs_norm]（只允许真机 armv7/arm64）。"
      ;;
  esac
  [ "$archs_norm" = "$expected_archs_norm" ] || \
    apple_fail 65 "架构不匹配：实际=[$archs_norm]，$profile 期望=[$expected_archs]。"

  for arch in $archs_norm; do
    header="$(apple_otool -arch "$arch" -hv "$exec_path" 2>/dev/null | awk '/^[[:space:]]*0x[0-9a-fA-F]+[[:space:]]/ {print; exit}' || true)"
    [ -n "$header" ] || apple_fail 65 "otool 无法读取 $arch 的 Mach-O header：$exec_path"
    filetype="$(printf '%s\n' "$header" | awk '{print $5}')"
    [ "$filetype" = "2" ] || apple_fail 65 "$arch 不是 MH_EXECUTE（filetype=${filetype}）。"

    loads="$(apple_otool -arch "$arch" -l "$exec_path" 2>/dev/null)" || \
      apple_fail 65 "otool 无法读取 $arch 的 load commands：$exec_path"

    if printf '%s\n' "$loads" | grep -q 'cmd LC_BUILD_VERSION'; then
      build_platform="$(printf '%s\n' "$loads" | awk '/cmd LC_BUILD_VERSION/ {f=1} f && /^[[:space:]]*platform / {sub(/^[[:space:]]*platform[[:space:]]+/, ""); print; exit}')"
      build_min_os="$(printf '%s\n' "$loads" | awk '/cmd LC_BUILD_VERSION/ {f=1} f && /^[[:space:]]*minos / {sub(/^[[:space:]]*minos[[:space:]]+/, ""); print; exit}')"
      build_platform="$(apple_trim "$build_platform")"
      build_min_os="$(apple_trim "$build_min_os")"
      case "$build_platform" in
        IOS) ;;
        *SIMULATOR*|IOSSIMULATOR) apple_fail 65 "$arch 的 LC_BUILD_VERSION platform=${build_platform}，拒绝模拟器产物。" ;;
        *) apple_fail 65 "$arch 的 LC_BUILD_VERSION platform=${build_platform:-<missing>}，不是 IOS。" ;;
      esac
      [ -n "$build_min_os" ] || apple_fail 65 "$arch 的 LC_BUILD_VERSION 缺 minos。"
      evidence="LC_BUILD_VERSION platform=$build_platform minos=$build_min_os"
    elif printf '%s\n' "$loads" | grep -q 'cmd LC_VERSION_MIN_IPHONEOS'; then
      build_min_os="$(printf '%s\n' "$loads" | awk '/cmd LC_VERSION_MIN_IPHONEOS/ {f=1} f && /^[[:space:]]*version / {sub(/^[[:space:]]*version[[:space:]]+/, ""); print; exit}')"
      build_min_os="$(apple_trim "$build_min_os")"
      [ -n "$build_min_os" ] || apple_fail 65 "$arch 的 LC_VERSION_MIN_IPHONEOS 缺 version。"
      evidence="LC_VERSION_MIN_IPHONEOS version=$build_min_os"
    else
      apple_fail 65 "$arch 缺 LC_VERSION_MIN_IPHONEOS / LC_BUILD_VERSION，无法证明是 iphoneos Mach-O。"
    fi

    apple_version_ge "$build_min_os" "$expected_min_os" || \
      apple_fail 65 "$arch Mach-O 最低系统 $build_min_os 低于 $profile 期望 ${expected_min_os}。"
    echo "MACHO_ARCH=$arch $evidence"
  done

  echo "APP_PLATFORM=$platform"
  echo "APP_MINIMUM_OS=$min_os"
  echo "APP_ARCHS=$archs_norm"
  echo "APP_EXECUTABLE=$exec_name"
  echo "APP_SIGNING=absent"
}

# -showBuildSettings 的退出码原样返回；输出写到 $3 供 apple_setting 解析。
apple_show_build_settings() {
  local scheme="$1" configuration="$2" output="$3" rc=0
  mkdir -p "$(dirname "$output")"
  xcodebuild \
    -project "$APPLE_PROJECT" \
    -scheme "$scheme" \
    -configuration "$configuration" \
    -sdk iphoneos \
    -destination "generic/platform=iOS" \
    -derivedDataPath "$APPLE_DERIVED_DATA" \
    CODE_SIGNING_ALLOWED=NO \
    CODE_SIGNING_REQUIRED=NO \
    CODE_SIGN_IDENTITY="" \
    -showBuildSettings >"$output" 2>&1 || rc=$?
  echo "BUILD_SETTINGS_EXIT=$rc"
  return "$rc"
}

apple_setting() {
  local file="$1" key="$2"
  sed -n "s/^[[:space:]]*${key}[[:space:]]*=[[:space:]]*//p" "$file" | head -n 1 || true
}

# 用 xcodebuild 实际解析出的设置核对 profile，并暴露产物的真实路径。
# 65 = 解析出的配置与预期不符；70 = 无法定位构建产物。
apple_validate_build_settings() {
  local file="$1" profile="$2" configuration="$3"
  local sdk platform archs min_os resolved_config full_name target_dir

  sdk="$(apple_trim "$(apple_setting "$file" SDKROOT)")"
  platform="$(apple_trim "$(apple_setting "$file" PLATFORM_NAME)")"
  archs="$(apple_normalize_archs "$(apple_setting "$file" ARCHS)")"
  min_os="$(apple_trim "$(apple_setting "$file" IPHONEOS_DEPLOYMENT_TARGET)")"
  resolved_config="$(apple_trim "$(apple_setting "$file" CONFIGURATION)")"
  full_name="$(apple_trim "$(apple_setting "$file" FULL_PRODUCT_NAME)")"
  target_dir="$(apple_trim "$(apple_setting "$file" TARGET_BUILD_DIR)")"

  # SDKROOT 会随 Xcode 版本携带版本号（Xcode 16.4 实测 iphoneos18.5），只要求 iphoneos 前缀。
  case "$sdk" in
    iphoneos*) ;;
    *) apple_fail 65 "SDKROOT=${sdk}，要求 iphoneos 系列。" ;;
  esac
  [ "$platform" = "iphoneos" ] || apple_fail 65 "PLATFORM_NAME=${platform}，要求 iphoneos。"
  [ "$resolved_config" = "$configuration" ] || \
    apple_fail 65 "实际构建配置=${resolved_config}，与请求的 $configuration 不符。"
  [ "$archs" = "$(apple_normalize_archs "$(apple_profile_archs "$profile")")" ] || \
    apple_fail 65 "ARCHS=[$archs]，$profile 期望=[$(apple_profile_archs "$profile")]。"
  [ "$(apple_version_key "$min_os")" = "$(apple_version_key "$(apple_profile_min_os "$profile")")" ] || \
    apple_fail 65 "IPHONEOS_DEPLOYMENT_TARGET=${min_os}，$profile 期望=$(apple_profile_min_os "$profile")。"

  case "$full_name" in
    */*|"") apple_fail 70 "FULL_PRODUCT_NAME 非法：$full_name" ;;
    *.app) ;;
    *) apple_fail 70 "FULL_PRODUCT_NAME 不是 .app：$full_name" ;;
  esac
  [ -n "$target_dir" ] || apple_fail 70 "TARGET_BUILD_DIR 为空。"
  case "$target_dir" in
    "$APPLE_BUILD_ROOT"/*) ;;
    *) apple_fail 70 "TARGET_BUILD_DIR 不在 iOS/.build 下：$target_dir" ;;
  esac

  APPLE_FULL_PRODUCT_NAME="$full_name"
  APPLE_TARGET_BUILD_DIR="$target_dir"

  echo "RESOLVED_SDKROOT=$sdk"
  echo "RESOLVED_PLATFORM=$platform"
  echo "RESOLVED_ARCHS=$archs"
  echo "RESOLVED_MINIMUM_OS=$min_os"
  echo "RESOLVED_CONFIGURATION=$resolved_config"
  echo "RESOLVED_PRODUCT=$full_name"
  echo "RESOLVED_PRODUCT_DIR=$target_dir"
}

apple_require_fresh() {
  local path="$1" since="$2" mtime
  [ -e "$path" ] || apple_fail 70 "产物不存在：$path"
  mtime="$(apple_mtime "$path")" || apple_fail 70 "无法读取 mtime：$path"
  if [ "$mtime" -lt "$((since - 2))" ]; then
    apple_fail 70 "拒绝陈旧产物（mtime=${mtime}，本次构建开始=${since}）：$path"
  fi
  echo "FRESH_PRODUCT=$path"
}

apple_count_apps() {
  find "$1" -type d -name '*.app' 2>/dev/null | wc -l | tr -d ' '
}

apple_first_app_flat() {
  find "$1" -mindepth 1 -maxdepth 1 -type d -name '*.app' -print -quit 2>/dev/null
}

# 解析 --output：必须是 .ipa、不能含 ..，且解析后物理位于 iOS/ 下。
# 结果写入 APPLE_RESOLVED_OUTPUT。
apple_resolve_output_path() {
  local raw="$1" abs dir ancestor parent real_ancestor real_dir
  [ -n "$raw" ] || apple_fail 64 "输出路径为空。"
  case "$raw" in
    *.ipa) ;;
    *) apple_fail 64 "输出文件必须以 .ipa 结尾：$raw" ;;
  esac
  case "$raw" in
    /*) abs="$raw" ;;
    *) abs="$PWD/$raw" ;;
  esac
  case "/$abs/" in
    *"/../"*) apple_fail 64 "输出路径不能包含 ..：$raw" ;;
  esac

  dir="$(dirname "$abs")"
  ancestor="$dir"
  while [ ! -d "$ancestor" ]; do
    parent="$(dirname "$ancestor")"
    [ "$parent" != "$ancestor" ] || apple_fail 64 "无法解析输出目录：$dir"
    ancestor="$parent"
  done
  real_ancestor="$(cd "$ancestor" && pwd -P)" || apple_fail 64 "无法解析输出目录：$dir"
  case "$real_ancestor/" in
    "$APPLE_IOS_ROOT"/*) ;;
    *) apple_fail 64 "输出必须物理位于 iOS/ 下：${raw}（解析到 ${real_ancestor}）" ;;
  esac

  mkdir -p "$dir" || apple_fail 70 "无法创建输出目录：$dir"
  real_dir="$(cd "$dir" && pwd -P)" || apple_fail 64 "无法解析输出目录：$dir"
  case "$real_dir/" in
    "$APPLE_IOS_ROOT"/*) ;;
    *) apple_fail 64 "输出必须物理位于 iOS/ 下：${raw}（解析到 ${real_dir}）" ;;
  esac

  APPLE_RESOLVED_OUTPUT="$real_dir/$(basename "$abs")"
}
