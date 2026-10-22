/*
 * 在线弧逻辑实现：逐行对拍上游 record/ArcKeepRule.kt 与 record/ArcRepairPlan.kt（ArcRepairFlow）。
 * 语义注释见头文件；本文件不依赖 GL/日志。
 */
#include "arc_flow.h"

#include <algorithm>

int64_t ArcKeepToleranceNs(int64_t slotNs, int64_t percent)
{
    if (slotNs <= 0) {
        return 0;
    }
    const int64_t p = std::min<int64_t>(100, std::max<int64_t>(0, percent));
    return slotNs * p / 100;
}

ArcKeepRule::ArcKeepRule(int64_t slotNs, int64_t toleranceNs)
    : slotNs_(slotNs),
      toleranceNs_(toleranceNs < 0 ? ArcKeepToleranceNs(slotNs) : toleranceNs)
{
}

bool ArcKeepRule::OnFrame(int64_t tsNs)
{
    if (slotNs_ <= 0) {
        return true;
    }
    if (nextDueNs_ == kArcKeepNotStarted) {
        // 首帧保留并锚定节奏：下一帧的应到时刻 = 首帧时刻 + 帧间隔
        nextDueNs_ = tsNs + slotNs_;
        return true;
    }
    if (tsNs >= nextDueNs_ - toleranceNs_) {
        // 保留帧不清 pendingDrops——区间账在发出那一帧时由 TakeDrops 取走
        nextDueNs_ += slotNs_;
        if (nextDueNs_ <= tsNs) {
            nextDueNs_ = tsNs + slotNs_; // 停滞追平，不连发
        }
        return true;
    }
    pendingDrops_++;
    return false;
}

int32_t ArcKeepRule::TakeDrops()
{
    const int32_t n = pendingDrops_;
    pendingDrops_ = 0;
    return n;
}

void ArcKeepRule::Reset()
{
    nextDueNs_ = kArcKeepNotStarted;
    pendingDrops_ = 0;
}

ArcRepairFlow::ArcRepairFlow(int dstFps) : dstFps_(dstFps > 0 ? dstFps : 24)
{
}

int64_t ArcRepairFlow::PtsOf(int keptIndex) const
{
    // 上游 keptCount.toLong() * 1_000_000L / dstFps：先乘后除，不累积截断
    return static_cast<int64_t>(keptIndex) * 1000000LL / static_cast<int64_t>(dstFps_);
}

std::vector<ArcOp> ArcRepairFlow::OnClassification(bool kept)
{
    std::vector<ArcOp> ops;
    if (!kept) {
        // 首枚保留帧之前的被抽帧没有"前帧"可并，直接弃（片头黑场语义不受损）
        if (!hasPending_) {
            return ops;
        }
        const bool fresh = !hasAcc_;
        hasAcc_ = true;
        ArcOp op;
        op.kind = ArcOpKind::AccumulateCur;
        op.flag = fresh;
        ops.push_back(op);
        return ops;
    }
    if (!hasPending_) {
        hasPending_ = true;
        ArcOp op;
        op.kind = ArcOpKind::HoldCurAsPending;
        ops.push_back(op);
        return ops;
    }
    if (hasAcc_) {
        ArcOp fold;
        fold.kind = ArcOpKind::FoldAccIntoPending;
        ops.push_back(fold);
    }
    ArcOp emit;
    emit.kind = ArcOpKind::EmitPending;
    emit.ptsUs = PtsOf(keptCount_);
    ops.push_back(emit);
    ArcOp merge;
    merge.kind = ArcOpKind::MergeCurAsPending;
    merge.flag = hasAcc_;
    ops.push_back(merge);
    keptCount_++;
    hasAcc_ = false;
    return ops;
}

std::vector<ArcOp> ArcRepairFlow::OnSourceEos()
{
    std::vector<ArcOp> ops;
    if (!hasPending_) {
        return ops;
    }
    if (hasAcc_) {
        ArcOp fold;
        fold.kind = ArcOpKind::FoldAccIntoPending;
        ops.push_back(fold);
    }
    ArcOp emit;
    emit.kind = ArcOpKind::EmitPending;
    emit.ptsUs = PtsOf(keptCount_);
    ops.push_back(emit);
    keptCount_++;
    hasAcc_ = false;
    hasPending_ = false;
    return ops;
}

bool ArcRepairFlow::HasPending() const
{
    return hasPending_;
}

bool ArcRepairFlow::HasAcc() const
{
    return hasAcc_;
}

int ArcRepairFlow::KeptCount() const
{
    return keptCount_;
}
