/*
 * A/B 无损剪辑导出器（上游 ClipExport.kt 的 remux 子集）：
 * OH_AVSource/OH_AVDemuxer 逐样本读源 → OH_AVMuxer 原样写 MP4，不解码/不重编码。
 *
 * 边界（登记）：
 * - fd 全部归 ArkTS：输入 READ_ONLY、输出 READ_WRITE|CREATE|TRUNC；native 绝不 close；
 * - 入点对齐前一个视频同步帧，公共 basePts = 该同步帧源 PTS；视频/音频 PTS 同减 base，
 *   负 rel 丢弃；出点按 PTS 截止，连续越界样本容忍视频 32/音频 8（B 帧重排）；
 * - 逐轨顺序写入（先视频整轨、后选中音频整轨），非交织 MP4 的播放器兼容性列真机验证项；
 * - 音轨只导一条：audioOrdinal = -1 无 / >=0 容器音轨序；选中轨区间内零可写样本时不建轨
 *   （零样本轨会让 muxer Stop 失败），状态带 audio=skipped；
 * - **音频同步平移（对齐上游 e950328 的 ClipRule.audioShiftUs）**：audioShiftUs 只作用于选中
 *   音频轨，`audioRel = rel + audioShiftUs`。预扫的越界/负值判据、拷贝循环的封轨判据、
 *   负 PTS 丢弃与单调桥**一律吃平移后的时刻**——预扫与写出必须同口径，否则会出现
 *   「预扫说可写、拷贝一枚都没写」⇒ 零样本轨崩 muxer。视频轨永不平移（shift 恒 0）；
 *   平移后为负的样本丢弃而不裁到 0（同刻多样本会崩 muxer）；
 * - 音频拷贝阶段样本超缓冲（AV_ERR_NO_MEMORY）不判死（T20 复审 P3）：跳过剩余音频样本
 *   继续收尾，done 状态追加 audio=skipped（已写样本保留、视频轨完好）；首枚样本即超限
 *   （已 AddTrack 零样本）时 WARN + 跳过，依赖既有兜底（audioSamples==0 → write 失败，
 *   零样本轨 muxer Stop 亦会失败）收尾；视频轨 NO_MEMORY 维持 sample-too-large 判死
 *   （视频残缺即整片无价值）；
 * - 旋转只透传源容器/视频轨 OH_MD_KEY_ROTATION（经 WotaNormalizeQuarter 到 0/90/180/270；
 *   SetRotation 与 done 日志同读归一后的同一变量）；
 * - 后台 std::thread + 状态轮询；取消每样本检查；muxer 已 Start 时取消走 best-effort Stop，
 *   半成品由 ArkTS 删除。
 */
#ifndef WOTA_CLIP_EXPORTER_H
#define WOTA_CLIP_EXPORTER_H

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>

class ClipExporter {
public:
    static ClipExporter& Instance();

    /**
     * 启动导出：空串 = 已入队；busy/args/worker = 立即失败。fd 归 ArkTS，native 不 close。
     * audioShiftUs = 选中音频轨的同步平移（µs；只对 audioOrdinal >= 0 生效，视频轨不受影响）。
     */
    std::string Start(int32_t inFd, int64_t inSize, int32_t outFd, int64_t startMs, int64_t endMs,
        int32_t audioOrdinal, int64_t audioShiftUs);

    /** idle | running:<pct> | done:<basePtsUs>:<videoSamples>:<audioSamples>[:audio=skipped]
     *  | failed:<code> | cancelled */
    std::string Status();

    /** 置取消位（worker 在下一个样本边界收尾；状态最终落 cancelled） */
    void Cancel();

    /** 只读探测：`videoCount,audioCount`；失败 `err:<code>`（不建 muxer、不改导出状态） */
    std::string ProbeTracks(int32_t inFd, int64_t inSize);

    ~ClipExporter();

private:
    ClipExporter() = default;
    ClipExporter(const ClipExporter&) = delete;
    ClipExporter& operator=(const ClipExporter&) = delete;

    enum class State { IDLE, RUNNING, DONE, FAILED, CANCELLED };

    struct Job {
        int32_t inFd = -1;
        int64_t inSize = 0;
        int32_t outFd = -1;
        int64_t startMs = 0;
        int64_t endMs = 0;
        int32_t audioOrdinal = -1;
        int64_t audioShiftUs = 0;
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

#endif // WOTA_CLIP_EXPORTER_H
