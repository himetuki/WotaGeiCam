#!/usr/bin/env bash
# 上游只读对比（单树 monorepo 版）：fetch origin/main → 列出上次评审基线（UPSTREAM_BASE）
# 之后的新提交与语义锚点 diff，最后给人工移植提示。**只读：不 merge、不改工作树、不提交。**
#
# 仓库结构（2026-10-22 单树 monorepo 起）：main 一棵树同时含两端——
#   Android/WotaGeiCamApp     → 安卓工程（上游权威 = origin/main）
#   HarmonyOS/WotaGeiCamOHOS  → 鸿蒙工程（移植落点）
# 上游权威是 origin/main；本地工作树可能滞后。两端代码互不 import，
# 同步 = diff → 人工移植 → 更新 PORT_MAP 与 UPSTREAM_BASE，永不 merge。
#
# 用法（在仓库根或其任意子目录）：
#   bash HarmonyOS/WotaGeiCamOHOS/scripts/sync-upstream.sh                 # 只对比，不改基线
#   bash HarmonyOS/WotaGeiCamOHOS/scripts/sync-upstream.sh --update-base   # 评审/移植后更新基线
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

if [ ! -d HarmonyOS/WotaGeiCamOHOS ] || [ ! -d HarmonyOS/WotaGeiCamOHOS/docs ]; then
  echo "错误：请在 monorepo 根（含 HarmonyOS/WotaGeiCamOHOS/）执行" >&2
  exit 1
fi

BASE_FILE="HarmonyOS/WotaGeiCamOHOS/docs/UPSTREAM_BASE"
UPSTREAM_DIR="Android/WotaGeiCamApp"   # origin/main 里安卓工程的路径

# 语义锚点（相对 Android/WotaGeiCamApp/ 的路径）
ANCHOR_PATHS=(
  "app/src/main/java/com/wotagei/cam/record/ArcDropLog.kt"
  "app/src/main/java/com/wotagei/cam/record/ArcKeepRule.kt"
  "app/src/main/java/com/wotagei/cam/record/ArcRepairPlan.kt"
  "app/src/main/java/com/wotagei/cam/camera/Shaders.kt"
  "app/src/main/java/com/wotagei/cam/camera/ArcMendPass.kt"
  "app/src/main/java/com/wotagei/cam/player/FrameStep.kt"
  "app/src/main/java/com/wotagei/cam/player/TapArbiter.kt"
  "app/src/main/java/com/wotagei/cam/player/PlayerEngine.kt"
  "app/src/main/java/com/wotagei/cam/player/PlayerScreen.kt"
  "app/src/main/java/com/wotagei/cam/record/FrameLedger.kt"
  "app/src/main/java/com/wotagei/cam/record/BitratePolicy.kt"
  "app/src/main/java/com/wotagei/cam/record/AutoStopRules.kt"
  "app/src/main/java/com/wotagei/cam/record/SeriesIds.kt"
  "app/src/main/java/com/wotagei/cam/record/PendingName.kt"
  "app/src/main/java/com/wotagei/cam/media/WotaDb.kt"
  "app/src/main/java/com/wotagei/cam/core/Constants.kt"
  "app/src/main/java/com/wotagei/cam/core/WotaParams.kt"
  "app/src/main/java/com/wotagei/cam/core/CurveModel.kt"
  "app/src/main/java/com/wotagei/cam/player/ClipExport.kt"
  "app/src/main/java/com/wotagei/cam/record/PlaybackCaptureController.kt"
)

UPDATE_BASE=0
case "${1:-}" in
  --update-base) UPDATE_BASE=1 ;;
  "") ;;
  *)
    echo "未知参数：$1（只支持 --update-base）" >&2
    exit 2
    ;;
esac

echo "[1/4] fetch origin（只读；不 merge）……"
git fetch --prune origin

UPSTREAM="$(git rev-parse --verify -q origin/main || true)"
if [ -z "$UPSTREAM" ]; then
  echo "错误：找不到 origin/main。请确认 remote origin 指向 github.com/himetuki/WotaGeiCam。" >&2
  exit 1
fi

BASE="$(cat "$BASE_FILE" 2>/dev/null | tr -d '[:space:]' || true)"
if [ -n "$BASE" ] && ! git cat-file -e "${BASE}^{commit}" 2>/dev/null; then
  echo "警告：UPSTREAM_BASE 记录的 $BASE 在当前仓库不存在，改用 origin/main 作基线。" >&2
  BASE=""
fi
if [ -z "$BASE" ]; then
  BASE="$UPSTREAM"
  echo "提示：未记录基线，本次以 origin/main（${UPSTREAM:0:7}）为基线。"
fi

# 基线提交里安卓工程的路径：单树重组（2026-10-22）之前在 WotaGeiCamApp/，之后在 Android/WotaGeiCamApp/
if git cat-file -e "$BASE:Android/WotaGeiCamApp" 2>/dev/null; then
  BASE_SUB="Android/WotaGeiCamApp"
else
  BASE_SUB="WotaGeiCamApp"
fi

echo "[2/4] 基线 ${BASE:0:7}（安卓工程在 $BASE_SUB）→ origin/main ${UPSTREAM:0:7}"
if [ "$BASE" = "$UPSTREAM" ]; then
  echo "无新提交（已对齐）。"
else
  # 只列动过安卓工程的提交（合并历史里混有鸿蒙侧提交，避免噪声）
  git log --oneline "$BASE..$UPSTREAM" -- Android/WotaGeiCamApp WotaGeiCamApp || true
  echo "---- 上游（安卓工程）全量变更 ----"
  git diff --stat "$BASE:$BASE_SUB" "$UPSTREAM:$UPSTREAM_DIR" || true
fi

echo "[3/4] 语义锚点 diff（空输出 = 无需跟进）……"
git diff --stat "$BASE:$BASE_SUB" "$UPSTREAM:$UPSTREAM_DIR" -- "${ANCHOR_PATHS[@]}" || true
echo "------------------------------------------------"

if [ "$UPDATE_BASE" -eq 1 ]; then
  printf '%s\n' "$UPSTREAM" > "$BASE_FILE"
  echo "[4/4] UPSTREAM_BASE 已更新为 ${UPSTREAM:0:7}；请与本次移植改动一起提交。"
else
  echo "[4/4] 基线未更新。评审并移植完成后执行："
  echo "      bash HarmonyOS/WotaGeiCamOHOS/scripts/sync-upstream.sh --update-base"
fi

cat <<HINT
上游权威副本 = origin/main 的 $UPSTREAM_DIR/：
  git show "origin/main:$UPSTREAM_DIR/app/src/main/java/com/wotagei/cam/<文件>"
  git diff "$BASE:$BASE_SUB" "origin/main:$UPSTREAM_DIR" -- <相对路径>
完成。锚点有 diff 时按 HarmonyOS/WotaGeiCamOHOS/docs/PORT_MAP.md 定位鸿蒙侧落点并人工移植。
HINT
