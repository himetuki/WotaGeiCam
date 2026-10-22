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
#   bash WotaGeiCamApp/scripts/archive-mapping.sh release  v1.0.0
#   bash WotaGeiCamApp/scripts/archive-mapping.sh debugHook v1.0.0
#
# 它做四件事：
#   1. 把 mapping.txt gzip 进 deliverable/<版本>/mapping/（gz 后 ≈1.8MB，不进 git，
#      与 *.apk 同等待遇——见根目录 .gitignore 的 mapping 段），靠 APK-LOG.md 清单追溯；
#   2. 算 gz 与原始 mapping 的 MD5、大小；
#   3. 记下当前 commit SHA；工作树不干净时明写 "+dirty(N)"——这时 mapping 严格说
#      不完全对应 HEAD，清单里必须带着这个警示，不许装作干净；
#   4. 打一行可直接粘进 deliverable/<版本>/APK-LOG.md 的片段（MD5 + commit 两列，
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
  release|debugHook) ;;
  "") fail "用法: archive-mapping.sh <release|debugHook> <版本目录，如 v1.0.0>" ;;
  *)  fail "变体只认 release / debugHook（其它变体不出用户包，不归档）：$VARIANT" ;;
esac
[ -n "$VERSION" ] || fail "用法: archive-mapping.sh <release|debugHook> <版本目录，如 v1.0.0>"

MAP="$ROOT/WotaGeiCamApp/app/build/outputs/mapping/$VARIANT/mapping.txt"
[ -f "$MAP" ] || fail "找不到 $MAP —— mapping 只在同一次构建后存在，clean 过就要先重出包"

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

# 与 gz 同目录放一枚 .md5 边车（整目录不进 git），离线核对用；正账仍在 APK-LOG.md
printf '%s  %s\ncommit %s%s\nraw-md5 %s (%s B)\n' "$GZ_MD5" "$NAME" "$COMMIT" "$DIRTY_TAG" "$RAW_MD5" "$RAW_BYTES" \
  > "$DEST_DIR/$NAME.md5"

note "已归档: $GZ"
note "原始  : $RAW_BYTES B / md5 $RAW_MD5"
note "压缩  : $GZ_BYTES B / md5 $GZ_MD5"
echo
echo "── 粘进 deliverable/$VERSION/APK-LOG.md 本轮那一行 ──"
echo "mapping=$NAME gz_md5=$GZ_MD5 raw_md5=$RAW_MD5 commit=$SHORT$DIRTY_TAG"
