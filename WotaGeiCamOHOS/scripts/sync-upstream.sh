#!/usr/bin/env bash
# 同步上游 main 到本地 harmony 分支。
# 用法：在仓库任意位置执行 bash WotaGeiCamOHOS/scripts/sync-upstream.sh
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

if [ "$(git rev-parse --abbrev-ref HEAD)" != "harmony" ]; then
  echo "错误：当前不在 harmony 分支，请先执行 git switch harmony" >&2
  exit 1
fi

if [ -n "$(git status --porcelain)" ]; then
  echo "错误：工作区有未提交改动，请先 commit 或 stash" >&2
  git status --short
  exit 1
fi

echo "[1/3] fetch 上游……"
git fetch origin

echo "[2/3] merge origin/main……"
git merge origin/main

echo "[3/3] 检查语义锚点是否变更……"
ANCHOR_PATHS=(
  "WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/ArcDropLog.kt"
  "WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/ArcKeepRule.kt"
  "WotaGeiCamApp/app/src/main/java/com/wotagei/cam/record/ArcRepairPlan.kt"
  "WotaGeiCamApp/app/src/main/java/com/wotagei/cam/camera/Shaders.kt"
  "WotaGeiCamApp/app/src/main/java/com/wotagei/cam/camera/ArcMendPass.kt"
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
echo "---- 语义锚点 diff（空输出 = 无需跟进）----"
git diff ORIG_HEAD..origin/main --stat -- "${ANCHOR_PATHS[@]}" || true
echo "------------------------------------------------"
echo "完成。若有 diff，按 WotaGeiCamOHOS/docs/PORT_MAP.md 定位鸿蒙侧落点并同步语义。"
