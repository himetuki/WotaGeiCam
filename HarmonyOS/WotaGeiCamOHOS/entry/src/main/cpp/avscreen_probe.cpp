/*
 * T27-1 平台探针实现（详细边界见 avscreen_probe.h；API 面逐一核对自
 * sysroot .../multimedia/player_framework/native_avscreen_capture*.h，API 10+）。
 *
 * 探针序列：Create → SetCallback（老式三件套结构体，since 10）→ 组 Config
 * （HOME_SCREEN + ORIGINAL_STREAM + 内录源 OH_ALL_PLAYBACK + mic 源同配 48k/2ch）
 * → SetMicrophoneEnabled(true) → Init → StartScreenCapture → 计数 durationMs →
 * StopScreenCapture → Release。每一步的错误码都进状态串（真机取证不解读、原样上报）。
 */
#include "avscreen_probe.h"

#include <multimedia/player_framework/native_avscreen_capture.h>
#include <multimedia/player_framework/native_avscreen_capture_base.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <mutex>
#include <thread>

namespace {

constexpr int32_t MIN_WINDOW_MS = 500;
constexpr int32_t MAX_WINDOW_MS = 10000;
constexpr int32_t DEFAULT_WINDOW_MS = 2500;

int64_t NowMs()
{
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

/** 探针的共享账本：回调线程写、worker/轮询读（全部 volatile/int64，免锁读） */
struct ProbeCtx {
    AvScreenProbe *owner = nullptr; // 未使用（回调签名无 userData，探针是单例所以无需）
    volatile int64_t innerCallbacks = 0;
    volatile int64_t innerBytes = 0;
    volatile int64_t micCallbacks = 0;
    volatile int64_t micBytes = 0;
    volatile int states[8] = {-1, -1, -1, -1, -1, -1, -1, -1};
    volatile int stateCount = 0;
    volatile int32_t lastError = 0;
};

ProbeCtx g_ctx;

void OnError(OH_AVScreenCapture *capture, int32_t errorCode)
{
    (void)capture;
    g_ctx.lastError = errorCode;
}

void OnAudioBuffer(OH_AVScreenCapture *capture, bool isReady, OH_AudioCaptureSourceType type)
{
    (void)type;
    if (!isReady) {
        return;
    }
    // 音频回调里取一次缓冲（只记账，不拷贝），取完立刻还——不还回调会被卡死
    OH_AudioBuffer *buffer = nullptr;
    if (OH_AVScreenCapture_AcquireAudioBuffer(capture, &buffer, type) !=
        AV_SCREEN_CAPTURE_ERR_OK || buffer == nullptr || buffer->buf == nullptr) {
        return;
    }
    const int32_t size = buffer->size > 0 ? buffer->size : 0;
    if (type == OH_ALL_PLAYBACK || type == OH_APP_PLAYBACK || type == OH_SOURCE_DEFAULT) {
        g_ctx.innerCallbacks++;
        g_ctx.innerBytes += size;
    } else if (type == OH_MIC) {
        g_ctx.micCallbacks++;
        g_ctx.micBytes += size;
    }
    OH_AVScreenCapture_ReleaseAudioBuffer(capture, type);
}

void OnVideoBuffer(OH_AVScreenCapture *capture, bool isReady)
{
    // 探针不要视频流；缓冲必须取还（不取会停流/不还会卡死）——video 缓冲经
    // OH_AVScreenCapture_ReleaseVideoBuffer 归还即可，不计数（T27 只取证音频）
    (void)isReady;
    OH_AVScreenCapture_ReleaseVideoBuffer(capture);
}

} // namespace

AvScreenProbe& AvScreenProbe::Instance()
{
    static AvScreenProbe instance;
    return instance;
}

AvScreenProbe::~AvScreenProbe()
{
    Cancel();
    if (worker_.joinable()) {
        worker_.join();
    }
}

std::string AvScreenProbe::Start(int32_t durationMs)
{
    std::lock_guard<std::mutex> lock(mtx_);
    if (running_) {
        return "busy";
    }
    if (worker_.joinable()) {
        worker_.join(); // 上一轮已落终态（Finish 是线程最后一步）
    }
    cancel_.store(false);
    g_ctx = ProbeCtx{};
    startedAtMs_ = NowMs();
    terminal_ = "running:0";
    running_ = true;
    const int32_t window = std::min(MAX_WINDOW_MS, std::max(MIN_WINDOW_MS, durationMs));
    try {
        worker_ = std::thread(&AvScreenProbe::Run, this, window);
    } catch (...) {
        running_ = false;
        terminal_ = "failed:worker";
        return "worker";
    }
    return "";
}

void AvScreenProbe::Cancel()
{
    cancel_.store(true);
}

std::string AvScreenProbe::Status()
{
    std::lock_guard<std::mutex> lock(mtx_);
    if (running_) {
        return "running:" + std::to_string(NowMs() - startedAtMs_);
    }
    return terminal_;
}

std::string AvScreenProbe::Finish(const std::string& terminal)
{
    std::lock_guard<std::mutex> lock(mtx_);
    terminal_ = terminal;
    running_ = false;
    return terminal;
}

void AvScreenProbe::Run(int32_t durationMs)
{
    OH_AVScreenCapture *capture = nullptr;
    bool started = false;
    std::string terminal;
    do {
        capture = OH_AVScreenCapture_Create();
        if (capture == nullptr) {
            terminal = Finish("failed:create");
            return;
        }
        // 老式三件套回调（since 10）：必须在 Init 之前注册
        OH_AVScreenCaptureCallback callbacks{};
        callbacks.onError = OnError;
        callbacks.onAudioBufferAvailable = OnAudioBuffer;
        callbacks.onVideoBufferAvailable = OnVideoBuffer;
        if (OH_AVScreenCapture_SetCallback(capture, callbacks) != AV_SCREEN_CAPTURE_ERR_OK) {
            terminal = "failed:set-callback";
            break;
        }
        // 双源同采取证：内录走 ALL_PLAYBACK（含其它应用播放），麦克风经总开关打开
        if (OH_AVScreenCapture_SetMicrophoneEnabled(capture, true) != AV_SCREEN_CAPTURE_ERR_OK) {
            terminal = "failed:mic-switch";
            break;
        }
        OH_AVScreenCaptureConfig config{};
        config.captureMode = OH_CAPTURE_HOME_SCREEN;
        config.dataType = OH_ORIGINAL_STREAM;
        config.audioInfo.micCapInfo.audioSampleRate = 48000;
        config.audioInfo.micCapInfo.audioChannels = 2;
        config.audioInfo.micCapInfo.audioSource = OH_MIC;
        config.audioInfo.innerCapInfo.audioSampleRate = 48000;
        config.audioInfo.innerCapInfo.audioChannels = 2;
        config.audioInfo.innerCapInfo.audioSource = OH_ALL_PLAYBACK;
        const int initRet = OH_AVScreenCapture_Init(capture, config);
        if (initRet != AV_SCREEN_CAPTURE_ERR_OK) {
            // 隐私/权限拒绝的第一现场（真机取证重点）：原样进串
            terminal = "failed:init:" + std::to_string(initRet) +
                ":err=" + std::to_string(g_ctx.lastError);
            break;
        }
        const int startRet = OH_AVScreenCapture_StartScreenCapture(capture);
        if (startRet != AV_SCREEN_CAPTURE_ERR_OK) {
            // 拉起系统确认框后用户拒绝/超时的典型落点
            terminal = "failed:start:" + std::to_string(startRet) +
                ":err=" + std::to_string(g_ctx.lastError);
            break;
        }
        started = true;
        const int64_t deadline = NowMs() + durationMs;
        while (NowMs() < deadline) {
            if (cancel_.load()) {
                break;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
        }
        OH_AVScreenCapture_StopScreenCapture(capture);
        if (cancel_.load()) {
            terminal = Finish("cancelled");
        } else {
            terminal = Finish("ok");
        }
    } while (false);

    if (capture != nullptr) {
        if (started) {
            // Stop 已发过；这里只兜等待，不再重复 Stop（重复调用的行为未定义）
        }
        OH_AVScreenCapture_Release(capture);
    }
    if (!terminal.empty() && terminal != "ok" && terminal != "cancelled" && running_) {
        Finish(terminal);
    }

    // ok 态把取证账拼进状态串（Finish 已置 running_=false，这里追加字段）
    std::lock_guard<std::mutex> lock(mtx_);
    if (terminal_ == "ok") {
        std::string states;
        for (int i = 0; i < g_ctx.stateCount && i < 8; i++) {
            if (i > 0) {
                states += ",";
            }
            states += std::to_string(g_ctx.states[i]);
        }
        terminal_ = "ok:inner=" + std::to_string(g_ctx.innerCallbacks) + "/" +
            std::to_string(g_ctx.innerBytes) + ":mic=" + std::to_string(g_ctx.micCallbacks) +
            "/" + std::to_string(g_ctx.micBytes) + ":states=" + (states.empty() ? "-" : states) +
            ":err=" + std::to_string(g_ctx.lastError) +
            ":elapsedMs=" + std::to_string(NowMs() - startedAtMs_);
    }
}
