#!/usr/bin/env bash
# 上游只读对比（2026-10-22 历史剥离后）：fetch origin → 列出 main..origin/main 的新提交与
# 语义锚点 diff，最后给人工移植提示。**本脚本只读，绝不 merge、绝不改 harmony 内容。**
#
# 背景：harmony 是 OHOS-only 独立历史，与 origin/main 无共同祖先（合并会把上游 Android 目录
# 整棵带回）；上游 Android 源码不入库，只读查看用 `git show origin/main:<path>`。
# 磁盘上的 WotaGeiCamApp/ 是 38d712a 快照（未跟踪、被忽略），仅供 grep/IDE，会漂移。
#
# 用法：在仓库任意位置执行 bash WotaGeiCamOHOS/scripts/sync-upstream.sh
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

if [ "$(git rev-parse --abbrev-ref HEAD)" != "harmony" ]; then
  echo "错误：当前不在 harmony 分支，请先执行 git switch harmony" >&2
  exit 1
fi

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

echo "[1/3] fetch 上游（只读；不 merge、不改 harmony）……"
# 比较基线在 fetch 之前取：否则随后把 main fast-forward 到 origin/main，下面两段恒为空
BASE_MAIN="$(git rev-parse --verify -q main || true)"
git fetch --prune origin

echo "[2/3] origin/main 相对上次镜像的新提交……"
if [ -z "$BASE_MAIN" ]; then
  echo "提示：本地无 main 镜像，先列出 origin/main 最近 10 条；之后以本次 fetch 为基线。"
  git log --oneline -10 origin/main || true
  BASE_MAIN="$(git rev-parse origin/main)"
else
  NEW_COMMITS="$(git log --oneline "$BASE_MAIN"..origin/main || true)"
  if [ -z "$NEW_COMMITS" ]; then
    echo "无新提交。"
  else
    echo "$NEW_COMMITS"
    echo "---- 上游全量变更 ----"
    git diff --stat "$BASE_MAIN"..origin/main || true
  fi
fi

echo "[3/3] 语义锚点 diff……"
echo "---- 语义锚点 diff（空输出 = 无需跟进）----"
git diff --stat "$BASE_MAIN"..origin/main -- "${ANCHOR_PATHS[@]}" || true
echo "------------------------------------------------"

# 比较完成后再对齐本地 main 镜像；被检出或有分叉时 fast-forward 会失败，只提示不中断
if ! git fetch -q origin main:main 2>/dev/null; then
  echo "提示：本地 main 镜像未更新（可能被检出或有分叉）；下次以 origin/main 为基线。"
fi

cat <<'HINT'
上游权威副本 = origin/main（也可用本地 main 镜像）：
  git show origin/main:WotaGeiCamApp/app/src/main/java/com/wotagei/cam/<文件>
  git diff main..origin/main -- <路径>
磁盘 WotaGeiCamApp/ 是 38d712a 快照、未跟踪，仅供 grep/IDE 对拍，可能已漂移。
完成。锚点有 diff 时按 WotaGeiCamOHOS/docs/PORT_MAP.md 定位鸿蒙侧落点并人工移植。
HINT
