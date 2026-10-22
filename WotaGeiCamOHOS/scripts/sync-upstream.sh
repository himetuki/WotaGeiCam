#!/usr/bin/env bash
# 上游只读对比（worktree 模型）：fetch origin/main → 列出上次评审基线（UPSTREAM_BASE）之后的
# 新提交与语义锚点 diff，最后给人工移植提示。**只读：不 merge、不改工作树、不提交。**
#
# 仓库结构（2026-10-22 起）：根目录是 bare 仓库，两个 worktree 各管一端——
#   Android/    → main（上游安卓工程）
#   HarmonyOS/  → harmony（本 worktree，OHOS-only）
# 上游权威仍是 origin/main；Android/ 只是工作副本，可能滞后。两分支没有共同祖先，
# 所以永不 merge/pull origin/main。
#
# 用法（在本 worktree 任意目录）：
#   bash WotaGeiCamOHOS/scripts/sync-upstream.sh                 # 只对比，不改基线
#   bash WotaGeiCamOHOS/scripts/sync-upstream.sh --update-base   # 评审/移植后更新基线
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

if [ ! -d WotaGeiCamOHOS ] || [ ! -d WotaGeiCamOHOS/docs ]; then
  echo "错误：请在 harmony worktree 根（含 WotaGeiCamOHOS/）执行" >&2
  exit 1
fi

BASE_FILE="WotaGeiCamOHOS/docs/UPSTREAM_BASE"
ANDROID_UPSTREAM_DIR="WotaGeiCamApp"   # origin/main 里安卓工程的路径

ANCHOR_PATHS=(
  "WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/ArcDropLog.kt"
  "WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/ArcKeepRule.kt"
  "WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/ArcRepairPlan.kt"
  "WotaGeiCamApp/app/src/main/java/com/wotagei/cam/camera/Shaders.kt"
  "WotaGeiCamApp/app/src/main/java/com/wotagei/cam/camera/ArcMendPass.kt"
  "WotaGeiCamApp/app/src/main/java/com/wotagei/cam/player/FrameStep.kt"
  "WotaGeiCamApp/app/src/main/java/com/wotagei/cam/player/TapArbiter.kt"
  "WotaGeiCamApp/app/src/main/java/com/wotagei/cam/player/PlayerEngine.kt"
  "WotaGeiCamApp/app/src/main/java/com/wotagei/cam/player/PlayerScreen.kt"
  "WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/FrameLedger.kt"
  "WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/BitratePolicy.kt"
  "WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/AutoStopRules.kt"
  "WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/SeriesIds.kt"
  "WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/PendingName.kt"
  "WotaGeiCamApp/app/src/main/java/com/wotagei/cam/media/WotaDb.kt"
  "WotaGeiCamApp/app/src/main/java/com/wotagei/cam/core/Constants.kt"
  "WotaGeiCamApp/app/src/main/java/com/wotagei/cam/core/WotaParams.kt"
  "WotaGeiCamApp/app/src/main/java/com/wotagei/cam/core/CurveModel.kt"
  "WotaGeiCamApp/app/src/main/java/com/wotagei/cam/player/ClipExport.kt"
  "WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/PlaybackCaptureController.kt"
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

echo "[2/4] 基线 ${BASE:0:7} → origin/main ${UPSTREAM:0:7}"
if [ "$BASE" = "$UPSTREAM" ]; then
  echo "无新提交（已对齐）。"
else
  git log --oneline "$BASE..$UPSTREAM" || true
  echo "---- 上游全量变更 ----"
  git diff --stat "$BASE..$UPSTREAM" || true
fi

echo "[3/4] 语义锚点 diff（空输出 = 无需跟进）……"
git diff --stat "$BASE..$UPSTREAM" -- "${ANCHOR_PATHS[@]}" || true
echo "------------------------------------------------"

if [ "$UPDATE_BASE" -eq 1 ]; then
  printf '%s\n' "$UPSTREAM" > "$BASE_FILE"
  echo "[4/4] UPSTREAM_BASE 已更新为 ${UPSTREAM:0:7}；请与本次移植改动一起提交。"
else
  echo "[4/4] 基线未更新。评审并移植完成后执行："
  echo "      bash WotaGeiCamOHOS/scripts/sync-upstream.sh --update-base"
fi

cat <<HINT
上游权威副本 = origin/main（Android/ 是工作副本，可能滞后）：
  git show origin/main:$ANDROID_UPSTREAM_DIR/app/src/main/java/com/wotagei/cam/<文件>
  git diff ${BASE:0:7}..origin/main -- <路径>
完成。锚点有 diff 时按 WotaGeiCamOHOS/docs/PORT_MAP.md 定位鸿蒙侧落点并人工移植。
HINT
