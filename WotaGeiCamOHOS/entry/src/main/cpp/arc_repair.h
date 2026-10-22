/*
 * 离线光弧修复的 CPU 执行器核心（上游 record/ArcRepairRunner.kt + ArcRepairGl 的
 * CPU 路线子集；对齐上游 38d712a）。本批只做视频，不产音频轨（T30 补音频直拷）。
 *
 * 流程：AVSource/AVDemuxer 探测 → 同步模式 AVCodec 解码（buffer 输出，不接 surface）
 * → 转紧打包 I420 → ArcRepairFlow 指令执行（cur/pending/acc 三面整像素合并）
 * → 同步模式 AVCodec 编码（YUVI420 缓冲输入、显式 PTS=j/dstFps）→ 首枚输出后
 * AddTrack/SetRotation/Start → 逐样本写 AVMuxer。
 *
 * 关键契约（与 ArkTS 侧 record/ArcRepairSessionModel.ets 逐字段对齐）：
 * - probe 串：`ok:<w>:<h>:<rotation>:<containerFps>:<durationMs>:<cleanCount>:<medianUs>:<audioCount>:<mime>`
 *   或 `err:<code>`；
 * - status 串：`idle` | `running:<pct>` | `done:<videoSamples>:<audioSamples>:<mux0/1>:<track0/1>:<stop0/1>`
 *   | `failed:<code>` | `cancelled`；本批 audioSamples 恒 0；
 * - 错误码：ARC_SOURCE / ARC_DECODER / ARC_ENCODER / ARC_MUXER / ARC_CPU_YUV / ARC_CPU_FEED /
 *   ARC_NO_VIDEO / ARC_CANCELLED / ARC_TIMEOUT / ARC_ENGINE。
 *
 * 平台差异与依赖（登记）：
 * - 同步模式（QueryInputBuffer/QueryOutputBuffer）自 API 20（build-profile compatibleSdk=6.0.0(20)）；
 * - fd 全部归 ArkTS：native 绝不 close；失败/取消时 ArkTS 侧删半成品；
 * - 输出像素布局：解码输出按 OH_MD_KEY_VIDEO_STRIDE / OH_MD_KEY_VIDEO_SLICE_HEIGHT 解释，
 *   支持 NV12 / NV21 / YUVI420 三档；其它格式返回 ARC_CPU_YUV（绝不猜布局产出错色）；
 * - 解码 format 的旋转角被剥离（副本上写 0，容器旋转由 muxer 侧写回原始值），
 *   编码面只吃"已正向"的帧；
 * - C++ 的 DstFrameCount/IsKept 是 ArkTS record/ArcRepairPlan.ets 的整数公式镜像
 *   （ArkTS 为已测规格，三式逐字同形；真机 29.97/25.4 对账列 T30 首验清单）；
 * - 编码器只接受 AVC/YUVI420 缓冲输入（最保守组合）；不接受时 ARC_ENCODER，不静默降级；
 * - 取消位与墙钟 deadline（durationMs*8+120s）每样本检查；取消走 best-effort 收尾。
 */
#ifndef WOTA_ARC_REPAIR_H
#define WOTA_ARC_REPAIR_H

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>

class ArcRepair {
public:
    static ArcRepair& Instance();

    /** 只读探测：ok 串或 err:<code>；不启线程、不改任务状态 */
    std::string Probe(int32_t inFd, int64_t inSize);

    /**
     * 启动修复：空串 = 已入队；busy/args = 立即失败。fd 归 ArkTS（native 不 close）。
     * srcNum/srcDen/srcFrames/dstFrames 由 ArkTS 纯逻辑算好传入（C++ 镜像仅作流式判定）。
     */
    std::string Start(int32_t inFd, int64_t inSize, int32_t outFd, int32_t dstFps, int32_t bitrate,
        int32_t deadband, int64_t srcNum, int64_t srcDen, int64_t srcFrames, int64_t dstFrames,
        int32_t rotation, int64_t durationMs);

    /** 状态串（契约见文件头） */
    std::string Status();

    /** 置取消位（worker 在下一个样本边界收尾） */
    void Cancel();

    ~ArcRepair();

private:
    ArcRepair() = default;
    ArcRepair(const ArcRepair&) = delete;
    ArcRepair& operator=(const ArcRepair&) = delete;

    enum class State { IDLE, RUNNING, DONE, FAILED, CANCELLED };

    struct Job {
        int32_t inFd = -1;
        int64_t inSize = 0;
        int32_t outFd = -1;
        int32_t dstFps = 24;
        int32_t bitrate = 16'000'000;
        int32_t deadband = 12;
        int64_t srcNum = 30;
        int64_t srcDen = 1;
        int64_t srcFrames = 0;
        int64_t dstFrames = 0;
        int32_t rotation = 0;
        int64_t durationMs = 0;
    };

    void Run(const Job& job);
    void Finish(State state, const std::string& status);

    std::mutex mtx_;
    std::thread worker_;
    State state_ = State::IDLE;
    std::string terminalStatus_ = "idle";
    std::atomic<bool> cancel_{false};
    std::atomic<int32_t> progress_{0};
};

#endif // WOTA_ARC_REPAIR_H
