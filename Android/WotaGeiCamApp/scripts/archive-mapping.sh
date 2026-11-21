#!/usr/bin/env bash
# ============================================================================
# WotaGeiCam mapping 归档脚本（docs/plan/15 步骤 5）
#
# 为什么需要它：release 与 debugHook 都开着 R8，用户报来的正式包崩溃栈是混淆过的；
# 只有拿"那一次构建"的 mapping.txt 才能还原调用链。而 mapping.txt 只躺在
# app/build/outputs/mapping/，`gradlew clean` 一冲就没——本项目 deliverable/ 里
# 一份都没留过，等于"以后收到的崩溃栈永久读不回"。本脚本把归档固化成一条命令。
#
# 用法（项目根目录的 Git Bash 里，出包的同一轮跑）：
#   bash WotaGeiCamApp/scripts/archive-mapping.sh fullRelease     v1.0.0
#   bash WotaGeiCamApp/scripts/archive-mapping.sh liteRelease     v1.0.0
#   bash WotaGeiCamApp/scripts/archive-mapping.sh fullDebugHook   v1.0.0
#   bash WotaGeiCamApp/scripts/archive-mapping.sh liteDebugHook   v1.0.0
#
# 为什么参数是**完整变体名**（B17）：AGP 引入 full/lite flavor 后，mapping 输出目录是
# `outputs/mapping/<flavor><BuildType>/mapping.txt`（如 fullRelease），不再有裸 `release/`。
# 旧脚本传 `release` 时要么找不到文件，要么把旧目录里上一次的 mapping 当成本次归档。
# 现在参数与 AGP 变体名一一对应，找不到就失败，绝不回退旧目录。
#
# 它做四件事：
#   1. 把 mapping.txt gzip 进 deliverable/<版本>/mapping/（gz 后 ≈1.8MB，不进 git，
#      与 *.apk 同等待遇——见根目录 .gitignore 的 mapping 段），靠 APK-LOG.md 清单追溯；
#   2. 算 gz 与原始 mapping 的 MD5、大小；
#   3. 登记同一变体产出的 **APK SHA-256**（可能不止一枚：ABI splits 时同目录多包），
#      让 mapping 与它真正对应过的那次制品能互相核验；
#   4. 记下当前 commit SHA；工作树不干净时明写 "+dirty(N)"——这时 mapping 严格说
#      不完全对应 HEAD，清单里必须带着这个警示，不许装作干净；
#   5. 打一行可直接粘进 deliverable/<版本>/APK-LOG.md 的片段（MD5 + APK SHA-256 + commit，
#      plan/15 步骤 5 的原话要求）。
#
# 规矩：数字只能在**归档动作发生的那一刻**取（现场计算），不许沿用上一轮的值。
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

fail() { echo "!! ABORT: $*" >&2; exit 1; }
note() { echo "-- $*"; }

VARIANT="${1:-}"
VERSION="${2:-}"
case "$VARIANT" in
  fullRelease)   FLAVOR="full"; BUILD_TYPE="release" ;;
  liteRelease)   FLAVOR="lite"; BUILD_TYPE="release" ;;
  fullDebugHook) FLAVOR="full"; BUILD_TYPE="debugHook" ;;
  liteDebugHook) FLAVOR="lite"; BUILD_TYPE="debugHook" ;;
  "") fail "用法: archive-mapping.sh <fullRelease|liteRelease|fullDebugHook|liteDebugHook> <版本目录，如 v1.0.0>" ;;
  *)  fail "变体只认 fullRelease / liteRelease / fullDebugHook / liteDebugHook（其它变体不开 R8 或不出用户包）：$VARIANT" ;;
esac
[ -n "$VERSION" ] || fail "用法: archive-mapping.sh <完整变体名> <版本目录，如 v1.0.0>"

MAP="$ROOT/WotaGeiCamApp/app/build/outputs/mapping/$VARIANT/mapping.txt"
[ -f "$MAP" ] || fail "找不到 $MAP —— mapping 只在同一次构建后存在，clean 过就要先重出包（变体：$VARIANT）"

APK_DIR="$ROOT/WotaGeiCamApp/app/build/outputs/apk/$FLAVOR/$BUILD_TYPE"
shopt -s nullglob
APKS=("$APK_DIR"/*.apk)
shopt -u nullglob
[ "${#APKS[@]}" -gt 0 ] || fail "找不到 $APK_DIR/*.apk —— mapping 必须与同一次构建的 APK 一起登记"

DEST_DIR="$ROOT/deliverable/$VERSION/mapping"
mkdir -p "$DEST_DIR"

COMMIT="$(git -C "$ROOT" rev-parse HEAD)"
SHORT="$(git -C "$ROOT" rev-parse --short HEAD)"
DIRTY_N="$(git -C "$ROOT" status --porcelain | wc -l | tr -d ' ')"
DIRTY_TAG=""
[ "$DIRTY_N" -gt 0 ] && DIRTY_TAG="+dirty($DIRTY_N)"

NAME="mapping-${VARIANT}-${SHORT}-$(date +%Y%m%d-%H%M).txt.gz"
GZ="$DEST_DIR/$NAME"
gzip -9 -c "$MAP" > "$GZ"

RAW_BYTES=$(wc -c < "$MAP" | tr -d ' ')
GZ_BYTES=$(wc -c < "$GZ" | tr -d ' ')
RAW_MD5=$(md5sum "$MAP" | cut -d' ' -f1)
GZ_MD5=$(md5sum "$GZ" | cut -d' ' -f1)

# APK 哈希逐枚现场计算；空哈希/非法哈希一律失败，不让脏值进清单。
APK_SUMMARY=""
APK_SIDECAR="$DEST_DIR/$NAME.apk-sha256"
: > "$APK_SIDECAR"
for apk in "${APKS[@]}"; do
  sum=$(sha256sum "$apk" | cut -d' ' -f1)
  printf '%s' "$sum" | grep -Eq '^[0-9a-f]{64}$' || fail "APK SHA-256 非法：'$sum'（$apk）"
  base=$(basename "$apk")
  if [ -n "$APK_SUMMARY" ]; then APK_SUMMARY="$APK_SUMMARY,$base:$sum"; else APK_SUMMARY="$base:$sum"; fi
  printf '%s  %s\n' "$sum" "$base" >> "$APK_SIDECAR"
done
printf 'commit %s%s\nvariant %s\n' "$COMMIT" "$DIRTY_TAG" "$VARIANT" >> "$APK_SIDECAR"

# 与 gz 同目录放一枚 .md5 边车（整目录不进 git），离线核对用；正账仍在 APK-LOG.md
printf '%s  %s\ncommit %s%s\nvariant %s\nraw-md5 %s (%s B)\napk-sha256 %s\n' \
  "$GZ_MD5" "$NAME" "$COMMIT" "$DIRTY_TAG" "$VARIANT" "$RAW_MD5" "$RAW_BYTES" "$APK_SUMMARY" \
  > "$DEST_DIR/$NAME.md5"

note "已归档: $GZ"
note "原始  : $RAW_BYTES B / md5 $RAW_MD5"
note "压缩  : $GZ_BYTES B / md5 $GZ_MD5"
for apk in "${APKS[@]}"; do
  note "APK   : $(basename "$apk") / sha256 $(sha256sum "$apk" | cut -d' ' -f1)"
done
echo
echo "── 粘进 deliverable/$VERSION/APK-LOG.md 本轮那一行 ──"
echo "mapping=$NAME gz_md5=$GZ_MD5 raw_md5=$RAW_MD5 apk_sha256=$APK_SUMMARY commit=$SHORT$DIRTY_TAG"
