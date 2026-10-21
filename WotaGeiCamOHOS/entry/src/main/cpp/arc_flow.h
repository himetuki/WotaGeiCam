/*
 * 录制在线路的光弧判定（逐行对拍上游 38d712a 与 ArkTS 锚点）：
 * - record/ArcKeepRule.kt / record/ArcKeepRule.ets：应到时刻抽帧规则（槽宽 + 10% 相位容差、
 *   首帧锚定、停滞追平、pendingDrops 账）；
 * - record/ArcRepairPlan.kt 的 ArcRepairFlow（在线路）：Hold/Fold/Emit/Merge 指令序列。
 *
 * 平台差异（登记）：
 * - Kotlin sealed interface ArcOp → C++ 枚举 + 单结构体（kind + flag + ptsUs）；
 * - List<ArcOp> → std::vector<ArcOp>（每帧至多 3 条指令，量级可忽略）；
 * - 本类只依赖标准库，不碰 GL/日志，便于与 ArkTS 锚点做静态对拍；
 * - EmitPending.ptsUs 按上游 keptCount*1e6/dstFps 计算但本批**不打戳**（PTS 仍走
 *   codec_recorder 的 wall-clock 相对段基准），字段保留给 PTS 网格批。
 */
#ifndef WOTA_ARC_FLOW_H
#define WOTA_ARC_FLOW_H

#include <cstdint>
#include <vector>

/** 相位容差占槽宽的比例（%）：上游 ARC_KEEP_TOLERANCE_PERCENT */
constexpr int kArcKeepTolerancePercent = 10;

/** 未开始哨兵（上游 Long.MIN_VALUE；真实时间戳不可能等于它） */
constexpr int64_t kArcKeepNotStarted = INT64_MIN;

/** 槽宽 → 相位容差（比例钳 0..100；slotNs <= 0 ⇒ 0，保持关闭路径零等待） */
int64_t ArcKeepToleranceNs(int64_t slotNs, int64_t percent = kArcKeepTolerancePercent);

/**
 * 录制在线路的抽帧保留规则：输出第 k 帧的应到时刻 = 锚点 + k·槽宽；
 * 判据原文：slotNs <= 0 恒保留；否则 ts >= nextDueNs - toleranceNs 才保留；丢弃 pendingDrops++；
 * 保留后 nextDue += slotNs，落后则追平（不连发）。
 */
class ArcKeepRule {
public:
    explicit ArcKeepRule(int64_t slotNs = 0, int64_t toleranceNs = -1);

    /** 相机帧到达（tsNs 单调纳秒）；true = 保留为本输出帧 */
    bool OnFrame(int64_t tsNs);

    /** 取走"最近一次保留之前丢弃了几帧"（取走即清零） */
    int32_t TakeDrops();

    /** 每次录制开始/编码面重挂时重置 */
    void Reset();

private:
    int64_t slotNs_;
    int64_t toleranceNs_;
    int64_t nextDueNs_ = kArcKeepNotStarted;
    int32_t pendingDrops_ = 0;
};

/** 单帧处置指令（对应上游 ArcOp） */
enum class ArcOpKind {
    AccumulateCur,      // flag = fresh
    FoldAccIntoPending,
    HoldCurAsPending,
    MergeCurAsPending,  // flag = useAcc
    EmitPending,        // ptsUs 保留（本批不消费）
};

struct ArcOp {
    ArcOpKind kind;
    bool flag = false;
    int64_t ptsUs = 0;
};

/**
 * 流式状态机（上游 ArcRepairFlow 在线路）：一帧滞留（pending）+ 一个累积面（acc）。
 * 每枚新的源帧问一次 OnClassification；停录前问一次 OnSourceEos。
 */
class ArcRepairFlow {
public:
    explicit ArcRepairFlow(int dstFps = 24);

    std::vector<ArcOp> OnClassification(bool kept);
    std::vector<ArcOp> OnSourceEos();

    bool HasPending() const;
    bool HasAcc() const;
    int KeptCount() const;

private:
    int64_t PtsOf(int keptIndex) const;

    int dstFps_;
    int keptCount_ = 0;
    bool hasPending_ = false;
    bool hasAcc_ = false;
};

#endif // WOTA_ARC_FLOW_H
