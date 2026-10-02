#!/usr/bin/env bash
# ============================================================================
# WotaGeiCam 崩溃栈还原（docs/plan/15 步骤 5）
#
# 为什么需要它：正式包开着 R8，用户报来的栈长这样：
#   at l5.u.a(SourceFile:20)
# 只有拿"那次构建"的 mapping.txt 喂给 retrace，才能还原成
#   at com.wotagei.cam.camera.Camera2Engine.teardownDevice(Camera2Engine.kt:…)
# mapping 的归档在 archive-mapping.sh（deliverable/<版本>/mapping/*.txt.gz）。
#
# 用法（项目根目录的 Git Bash 里）：
#   bash WotaGeiCamApp/scripts/retrace-crash.sh <崩溃栈.txt> <mapping.txt 或 .txt.gz>
#   bash WotaGeiCamApp/scripts/retrace-crash.sh --selftest     # 人造一条栈自证链路（plan/15 的门禁）
#
# 本机坑（都踩过，写死在脚本里）：
#   - retrace.bat 是 Windows 程序，不认 MSYS 路径 ⇒ 一律 cygpath -w 转过再传；
#   - 临时文件只进项目内 .tmp/；
#   - 管道退出码是最后一个命令的 ⇒ 输出落文件后单独查 exit（本项目因此把两次
#     BUILD FAILED 读成过 exit 0）。
#
# 门禁（--selftest 干的事）：从当前 release mapping 里解析出 Camera2Engine 的混淆名，
# 用它人造一条栈跑 retrace，断言还原结果里出现**可读的原始类名与方法名**——
# 断言失败 exit 1。混淆名每个构建都变，所以从 mapping 现场解析，不许写死。
# 已知无害告警：R8 8.1.1 写的 "Map version 2.2" 对本机 retrace 4.0.48 报 unknown——
# 实测类名/方法名/行号照常还原（2026-09-30 selftest 通过），别拿这条告警当失败。
# ============================================================================
set -uo pipefail
export MSYS_NO_PATHCONV=1

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
RETRACE="$ROOT/tools/android-sdk/cmdline-tools/latest/bin/retrace.bat"
TMP="$ROOT/.tmp/retrace-$(date +%Y%m%d-%H%M%S)"

fail() { echo "!! ABORT: $*" >&2; exit 1; }
note() { echo "-- $*"; }

[ -f "$RETRACE" ] || fail "retrace 不在 $RETRACE（tools/android-sdk 没装全？）"

run_retrace() { # $1=mapping(raw)  $2=stack.txt  输出: $3 指定的文件
  local mapw stackw outw
  mapw="$(cygpath -w "$1")"
  stackw="$(cygpath -w "$2")"
  outw="$(cygpath -w "$3")"
  "$RETRACE" "$mapw" "$stackw" > "$3" 2>&1
  echo $?
}

prepare_mapping() { # $1=传入的 mapping 路径；gz 就地解到 .tmp，返回 raw 路径
  local m="$1"
  case "$m" in
    *.gz)
      mkdir -p "$TMP"
      gunzip -c "$m" > "$TMP/mapping.txt" || fail "gz 解压失败: $m"
      echo "$TMP/mapping.txt"
      ;;
    *) echo "$m" ;;
  esac
}

SELFTEST=0
STACK=""
MAP=""
if [ "${1:-}" = "--selftest" ]; then
  SELFTEST=1
  MAP="$ROOT/WotaGeiCamApp/app/build/outputs/mapping/release/mapping.txt"
  [ -f "$MAP" ] || fail "selftest 需要现成的 release mapping（先出一次 release/debugHook 包）"
else
  STACK="${1:-}"
  MAP="${2:-}"
  [ -n "$STACK" ] && [ -f "$STACK" ] || fail "用法: retrace-crash.sh <崩溃栈.txt> <mapping.txt[.gz]>（或 --selftest）"
  [ -n "$MAP" ] && [ -f "$MAP" ] || fail "mapping 不存在: $MAP"
fi

MAPRAW="$(prepare_mapping "$MAP")"

if [ "$SELFTEST" -eq 1 ]; then
  # 1) 从 mapping 现场解析 Camera2Engine 的混淆名（每个构建都变，不许写死）
  OBF="$(grep -m1 '^com\.wotagei\.cam\.camera\.Camera2Engine -> ' "$MAPRAW" | sed 's/.* -> \(.*\):$/\1/')"
  [ -n "$OBF" ] || fail "mapping 里找不到 Camera2Engine 的混淆名"
  # 2) 人造一条混淆栈：teardownDevice 在本类里对应短名方法（a/b 由 R8 现定，取该类块里
  #    带行号映射的第一条方法名，比猜字母稳）
  METH="$(grep -A 200 "^com\.wotagei\.cam\.camera\.Camera2Engine -> $OBF:" "$MAPRAW" \
    | grep -m1 -E '^[[:space:]]+[0-9]+:[0-9]+:.* -> ([a-zA-Z0-9_$]+)$' \
    | sed 's/.* -> //')"
  [ -n "$METH" ] || fail "mapping 里找不到该类带行号的方法映射"
  mkdir -p "$TMP"
  STACK="$TMP/synthetic-crash.txt"
  {
    echo "java.lang.IllegalStateException: synthetic probe (retrace selftest)"
    echo "	at $OBF.$METH(SourceFile:10)"
    echo "	at l5.u(SourceFile:1)"
  } > "$STACK"
  note "selftest: 混淆名=$OBF 方法=$METH 临时栈=$STACK"
fi

OUT="$TMP-out.txt"
mkdir -p "$(dirname "$OUT")"
EXIT="$(run_retrace "$MAPRAW" "$STACK" "$OUT")"
echo "──────── retrace 输出（exit=$EXIT）────────"
cat "$OUT"
echo "──────────────────────────────────────────"

if [ "$SELFTEST" -eq 1 ]; then
  # 3) 断言：还原结果里出现可读的原始类名与方法名，不是原样回显
  if grep -q "com.wotagei.cam.camera.Camera2Engine" "$OUT" && grep -qE "teardownDevice|syncFpsPick|collectJobs" "$OUT"; then
    note "selftest 通过：混淆栈还原出了原始类名与方法名"
    exit 0
  fi
  fail "selftest 失败：还原结果里没有可读的原始类/方法名（mapping 与栈不匹配？）"
fi
note "完成。还原结果在 $OUT"
