/*
 * T27-1 平台探针：AVScreenCapture 内录能力取证（真机批的第一步）。
 *
 * 要回答的三个取证问题（PORT_MAP T27 节的前置）：
 * 1. **隐私弹窗形态**：Init/StartScreenCapture 在真机上是否拉系统确认框、用户拒绝/超时走
 *    哪个错误码/状态回调（错误码与 OH_SCREEN_CAPTURE_STATE_* 原样进状态串，不解读）；
 * 2. **双源同采**：audioSource=OH_ALL_PLAYBACK（内录全播放流）+ SetMicrophoneEnabled(true)
 *    时，AUDIO_INNER 与 AUDIO_MIC 两类音频缓冲是否**同时**送达（双源计数分别上报）；
 * 3. **节奏**：durationMs 窗口内每源的回调次数/字节数（预校准要 800ms 双源，这里给实证）。
 *
 * 设计与 clip_exporter/arc_repair 同款：后台 std::thread + 状态串轮询；fd/生命周期全收口；
 * 取消即提前 Stop+Release。**探针不落盘**（只计数），不进录制链。
 */
#ifndef WOTA_AVSCREEN_PROBE_H
#define WOTA_AVSCREEN_PROBE_H

#include <atomic>
#include <mutex>
#include <string>
#include <thread>

class AvScreenProbe {
public:
    static AvScreenProbe& Instance();

    /**
     * 启动探针：空串 = 已入队；busy/args = 立即失败。
     * @param durationMs 采样窗口（500..10000，钳制）；期间计数两类音频缓冲
     */
    std::string Start(int32_t durationMs);

    /** idle | running:<elapsedMs> | ok:<字段串> | failed:<code> | cancelled */
    std::string Status();

    /** 置取消位（窗口提前收口；状态最终落 cancelled 或 ok——已有样本仍给 ok 串） */
    void Cancel();

    ~AvScreenProbe();

private:
    AvScreenProbe() = default;
    AvScreenProbe(const AvScreenProbe&) = delete;
    AvScreenProbe& operator=(const AvScreenProbe&) = delete;

    void Run(int32_t durationMs);
    std::string Finish(const std::string& terminal);

    struct Counters {
        // 两类音频源各自的回执账（双源同采 = 两边都 >0）
        volatile int64_t innerCallbacks = 0;
        volatile int64_t innerBytes = 0;
        volatile int64_t micCallbacks = 0;
        volatile int64_t micBytes = 0;
        // 状态回调（OH_SCREEN_CAPTURE_STATE_* 原值，逗号分隔追加，封顶 8 个防串爆）
        volatile int states[8] = {-1, -1, -1, -1, -1, -1, -1, -1};
        volatile int stateCount = 0;
        // 最近一次错误码（SetCallback 的 onError）
        volatile int32_t lastError = 0;
    };

    std::mutex mtx_;
    std::thread worker_;
    bool running_ = false;
    std::string terminal_ = "idle";
    int64_t startedAtMs_ = 0;
    Counters counters_{};
    std::atomic<bool> cancel_{false};
};

#endif // WOTA_AVSCREEN_PROBE_H
