/*
 * GPU 录制最小切片实现：OH_VideoEncoder（surface 输入）+ OH_AVMuxer（fd → MPEG_4）。
 * 顺序严格照头文件：CreateByMime → RegisterCallback → Configure → GetSurface（须在 Prepare 前）
 * → Prepare →（muxer Create/AddTrack/SetRotation/Start）→ 编码器 Start。
 * 详见 codec_recorder.h 的边界登记。
 */
#include "codec_recorder.h"
#include "gl_render.h"
#include "wota_math.h"

#include <hilog/log.h>
#include <multimedia/player_framework/native_avbuffer.h>
#include <multimedia/player_framework/native_avcodec_base.h>
#include <multimedia/player_framework/native_avcodec_videoencoder.h>
#include <multimedia/player_framework/native_avformat.h>
#include <multimedia/player_framework/native_avmuxer.h>

#include <chrono>

#define WOTA_LOG_DOMAIN 0x0000
#define WOTA_LOG_TAG "WotaGL"
#define WOTA_RECORD_TAG "WotaRecord"

namespace {
int64_t NowUs()
{
    return std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

} // namespace

CodecRecorder& CodecRecorder::Instance()
{
    static CodecRecorder instance;
    return instance;
}

std::string CodecRecorder::Start(int32_t fd, int32_t width, int32_t height, int32_t fps, int32_t bitrate,
    int32_t rotation)
{
    std::unique_lock<std::mutex> lock(mtx_);
    if (state_ != State::IDLE) {
        return "busy";
    }
    state_ = State::STARTING;
    lastError_.clear();
    samples_ = 0;
    eosSeen_ = false;
    lastPtsUs_ = -1;
    writeFailed_ = false;

    // 失败统一出口：持锁摘指针 → 解锁销毁（Destroy 不得与回调线程抢同一把锁）
    auto fail = [this, &lock](const char* code) -> std::string {
        return FailStart(lock, code);
    };

    codec_ = OH_VideoEncoder_CreateByMime(OH_AVCODEC_MIMETYPE_VIDEO_AVC);
    if (codec_ == nullptr) {
        return fail("encoder-create");
    }
    OH_AVCodecCallback callback;
    callback.onError = OnErrorCb;
    callback.onStreamChanged = OnStreamChangedCb;
    callback.onNeedInputBuffer = OnNeedInputBufferCb;
    callback.onNewOutputBuffer = OnNewOutputBufferCb;
    if (OH_VideoEncoder_RegisterCallback(codec_, callback, this) != AV_ERR_OK) {
        return fail("register-callback");
    }
    OH_AVFormat* format = OH_AVFormat_Create();
    if (format == nullptr) {
        return fail("format-create");
    }
    // 键类型按 SDK 文档：FRAME_RATE=double、BITRATE=int64、I_FRAME_INTERVAL=毫秒（1s=1000，
    // 上游 I_FRAME_INTERVAL_SEC=1）。任一键写入失败即拒配，避免静默落编码器默认值。
    bool formatOk = OH_AVFormat_SetIntValue(format, OH_MD_KEY_WIDTH, width);
    formatOk = OH_AVFormat_SetIntValue(format, OH_MD_KEY_HEIGHT, height) && formatOk;
    formatOk = OH_AVFormat_SetDoubleValue(format, OH_MD_KEY_FRAME_RATE, static_cast<double>(fps)) && formatOk;
    formatOk = OH_AVFormat_SetLongValue(format, OH_MD_KEY_BITRATE, static_cast<int64_t>(bitrate)) && formatOk;
    formatOk = OH_AVFormat_SetIntValue(format, OH_MD_KEY_I_FRAME_INTERVAL, 1000) && formatOk;
    if (!formatOk) {
        OH_AVFormat_Destroy(format);
        return fail("format-key");
    }
    const OH_AVErrCode configureRet = OH_VideoEncoder_Configure(codec_, format);
    OH_AVFormat_Destroy(format);
    if (configureRet != AV_ERR_OK) {
        return fail("configure");
    }
    // GetSurface 必须在 Prepare 之前（头文件明示）
    if (OH_VideoEncoder_GetSurface(codec_, &window_) != AV_ERR_OK || window_ == nullptr) {
        return fail("get-surface");
    }
    if (OH_VideoEncoder_Prepare(codec_) != AV_ERR_OK) {
        return fail("prepare");
    }

    // muxer 先于编码器 Start 建好，避免首包撞空 muxer
    muxer_ = OH_AVMuxer_Create(fd, AV_OUTPUT_FORMAT_MPEG_4);
    if (muxer_ == nullptr) {
        return fail("muxer");
    }
    OH_AVFormat* trackFormat = OH_AVFormat_Create();
    if (trackFormat == nullptr) {
        return fail("track-format");
    }
    bool trackOk = OH_AVFormat_SetStringValue(trackFormat, OH_MD_KEY_CODEC_MIME, OH_AVCODEC_MIMETYPE_VIDEO_AVC);
    trackOk = OH_AVFormat_SetIntValue(trackFormat, OH_MD_KEY_WIDTH, width) && trackOk;
    trackOk = OH_AVFormat_SetIntValue(trackFormat, OH_MD_KEY_HEIGHT, height) && trackOk;
    if (!trackOk) {
        OH_AVFormat_Destroy(trackFormat);
        return fail("track-format");
    }
    const OH_AVErrCode trackRet = OH_AVMuxer_AddTrack(muxer_, &track_, trackFormat);
    OH_AVFormat_Destroy(trackFormat);
    if (trackRet != AV_ERR_OK || track_ < 0) {
        return fail("track");
    }
    // 容器旋转 hint（须在 muxer Start 前）：编码 UV 只送缓冲原样帧（stRotDeg、不镜像），
    // 竖屏转正靠这里（上游 orientationHint 同义）。muxer 只接受 0/90/180/270，按上游
    // normalizeQuarter 四舍五入（45→90、359→0），非 90 倍数 sensor 不得让起录失败。
    const int32_t rawRotation = WotaNormalizeDeg(rotation);
    const int32_t safeRotation = WotaNormalizeQuarter(rawRotation);
    if (OH_AVMuxer_SetRotation(muxer_, safeRotation) != AV_ERR_OK) {
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_RECORD_TAG,
            "muxer set rotation failed rotation=%{public}d rounded=%{public}d",
            rawRotation, safeRotation);
        return fail("muxer-rotation");
    }
    if (OH_AVMuxer_Start(muxer_) != AV_ERR_OK) {
        return fail("muxer-start");
    }
    // 相对时基在 Start 前落值：回调可能在 Start 返回前就到（PTS 一律 wall-clock − startUs）
    startUs_ = NowUs();
    if (OH_VideoEncoder_Start(codec_) != AV_ERR_OK) {
        return fail("start");
    }
    state_ = State::RECORDING;
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_RECORD_TAG,
        "gpu record started %{public}dx%{public}d@%{public}d bitrate=%{public}d rotation=%{public}d (normalized; raw in WotaGl log)",
        width, height, fps, bitrate, safeRotation);
    return "";
}

/** Start 失败统一清理：摘指针并置 IDLE 后解锁，再销毁（不 close fd） */
std::string CodecRecorder::FailStart(std::unique_lock<std::mutex>& lock, const char* code)
{
    OH_AVCodec* codec = codec_;
    OH_AVMuxer* muxer = muxer_;
    OHNativeWindow* window = window_;
    codec_ = nullptr;
    muxer_ = nullptr;
    window_ = nullptr;
    track_ = -1;
    samples_ = 0;
    eosSeen_ = false;
    startUs_ = 0;
    lastPtsUs_ = -1;
    writeFailed_ = false;
    state_ = State::IDLE;
    if (lock.owns_lock()) {
        lock.unlock();
    }
    if (muxer != nullptr) {
        OH_AVMuxer_Stop(muxer); // 未 Start 时返回非 OK，忽略
        OH_AVMuxer_Destroy(muxer);
    }
    if (codec != nullptr) {
        OH_VideoEncoder_Destroy(codec);
    }
    if (window != nullptr) {
        OH_NativeWindow_DestroyNativeWindow(window);
    }
    OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_RECORD_TAG,
        "gpu record start failed code=%{public}s", code);
    return code;
}

std::string CodecRecorder::Stop()
{
    std::unique_lock<std::mutex> lock(mtx_);
    if (state_ != State::RECORDING && state_ != State::STARTING) {
        // 已中止/已停止：把中止原因如实交回给 ArkTS（surface 销毁路径由 native 先行 stop）
        return lastError_.empty() ? "idle" : lastError_;
    }
    state_ = State::STOPPING;
    lock.unlock();
    // 先解绑 EGL 编码面（若已 attach；幂等），再停编码器/销毁原生窗口
    WotaGlDetachEncoder();
    lock.lock();

    if (codec_ != nullptr) {
        if (!writeFailed_) {
            OH_VideoEncoder_NotifyEndOfStream(codec_);
            // 谓词含 writeFailed_：等待期间若 muxer 写失败，输出回调 notify_all 立即唤醒，
            // 不白等满 3s（审查 P3 竞态）
            eosCv_.wait_for(lock, std::chrono::seconds(3),
                [this] { return eosSeen_ || writeFailed_; });
        }
        if (writeFailed_) {
            // muxer 已失败：不再接受样本，跳过 EOS（直接走清理）
            OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_RECORD_TAG,
                "gpu record stop after write failure, skip eos wait");
        } else {
            OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_RECORD_TAG,
                "gpu record eos=%{public}d samples=%{public}d", eosSeen_ ? 1 : 0, samples_);
        }
    }
    const bool eos = eosSeen_;
    const int32_t samples = samples_;
    const std::string error = lastError_;

    // 先摘走资源指针再解锁销毁：回调线程见 codec_ == nullptr 即安全返回，避免与 Stop/Destroy 互等锁
    OH_AVCodec* codec = codec_;
    OH_AVMuxer* muxer = muxer_;
    OHNativeWindow* window = window_;
    codec_ = nullptr;
    muxer_ = nullptr;
    window_ = nullptr;
    track_ = -1;
    samples_ = 0;
    eosSeen_ = false;
    startUs_ = 0;
    lastPtsUs_ = -1;
    writeFailed_ = false;
    lock.unlock();

    if (codec != nullptr) {
        OH_VideoEncoder_Stop(codec);
    }
    if (muxer != nullptr) {
        OH_AVMuxer_Stop(muxer);
        OH_AVMuxer_Destroy(muxer);
    }
    if (codec != nullptr) {
        OH_VideoEncoder_Destroy(codec);
    }
    if (window != nullptr) {
        OH_NativeWindow_DestroyNativeWindow(window);
    }

    std::lock_guard<std::mutex> guard(mtx_);
    state_ = State::IDLE;
    if (!error.empty()) {
        OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_RECORD_TAG,
            "gpu record stopped with error=%{public}s samples=%{public}d", error.c_str(), samples);
        return error + " samples=" + std::to_string(samples);
    }
    return "ok samples=" + std::to_string(samples) + " eos=" + (eos ? "1" : "0");
}

bool CodecRecorder::IsRecording()
{
    std::lock_guard<std::mutex> lock(mtx_);
    // muxer 写失败即视为已不可用（页面据 false 收尾停录并展示 LastError）
    return state_ == State::RECORDING && !writeFailed_;
}

bool CodecRecorder::IsActive()
{
    std::lock_guard<std::mutex> lock(mtx_);
    // 资源仍活跃（STARTING/RECORDING/STOPPING，含 writeFailed 但尚未 Stop）：销毁渲染面时必须中止
    return state_ != State::IDLE;
}

std::string CodecRecorder::LastError()
{
    std::lock_guard<std::mutex> lock(mtx_);
    return lastError_;
}

void CodecRecorder::Abort(const std::string& reason)
{
    {
        std::lock_guard<std::mutex> lock(mtx_);
        if (state_ == State::IDLE) {
            return;
        }
        if (lastError_.empty()) {
            lastError_ = reason;
        }
    }
    Stop();
}

void CodecRecorder::AbortOnSurfaceLost()
{
    Abort("surface-lost");
}

OHNativeWindow* CodecRecorder::EncoderWindow()
{
    std::lock_guard<std::mutex> lock(mtx_);
    return window_;
}

void CodecRecorder::OnErrorCb(OH_AVCodec* codec, int32_t errorCode, void* userData)
{
    (void)codec;
    CodecRecorder* self = static_cast<CodecRecorder*>(userData);
    std::lock_guard<std::mutex> lock(self->mtx_);
    if (self->lastError_.empty()) {
        // 保留首个根因（例如 muxer-write 之后的级联 encoder error 不覆盖）
        self->lastError_ = "engine-error:" + std::to_string(errorCode);
    }
    OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_RECORD_TAG,
        "gpu record encoder error=%{public}d", errorCode);
}

void CodecRecorder::OnStreamChangedCb(OH_AVCodec* codec, OH_AVFormat* format, void* userData)
{
    (void)codec;
    (void)format;
    (void)userData;
}

void CodecRecorder::OnNeedInputBufferCb(OH_AVCodec* codec, uint32_t index, OH_AVBuffer* buffer, void* userData)
{
    (void)codec;
    (void)index;
    (void)buffer;
    (void)userData;
    // surface 输入模式不消费输入 buffer（帧由 EGL 编码面送入）
}

void CodecRecorder::OnNewOutputBufferCb(OH_AVCodec* codec, uint32_t index, OH_AVBuffer* buffer, void* userData)
{
    (void)codec;
    CodecRecorder* self = static_cast<CodecRecorder*>(userData);
    self->HandleOutputBuffer(index, buffer);
}

void CodecRecorder::HandleOutputBuffer(uint32_t index, OH_AVBuffer* buffer)
{
    std::lock_guard<std::mutex> lock(mtx_);
    if (codec_ == nullptr) {
        return; // Stop 已摘走编码器：迟到回调安全返回（buffer 随 Destroy 回收）
    }
    if (writeFailed_) {
        // muxer 已失败：不再尝试写样（文件不可续），只释放输出 buffer 等页面停录
        OH_VideoEncoder_FreeOutputBuffer(codec_, index);
        return;
    }
    OH_AVCodecBufferAttr attr;
    if (buffer == nullptr || OH_AVBuffer_GetBufferAttr(buffer, &attr) != AV_ERR_OK) {
        OH_VideoEncoder_FreeOutputBuffer(codec_, index);
        return;
    }
    if ((attr.flags & AVCODEC_BUFFER_FLAGS_EOS) != 0) {
        eosSeen_ = true;
        eosCv_.notify_all();
        OH_VideoEncoder_FreeOutputBuffer(codec_, index);
        return;
    }
    // PTS：一律输出 wall-clock 相对段基准（NowUs − startUs，µs），避免编码器绝对时基/全 0 污染
    // MP4 时间轴；lastPtsUs_ 只做单调守卫。前提登记：编码器无 B 帧。
    int64_t ptsUs = NowUs() - startUs_;
    if (ptsUs <= lastPtsUs_) {
        ptsUs = lastPtsUs_ + 1;
    }
    attr.pts = ptsUs;
    if (OH_AVBuffer_SetBufferAttr(buffer, &attr) != AV_ERR_OK) {
        // 回写失败 → muxer 将拿到错误时间轴，按写失败中止（不把坏样写进文件）
        lastError_ = "pts-set";
        writeFailed_ = true;
        eosCv_.notify_all(); // 唤醒 Stop 的 EOS 等待（writeFailed 谓词）
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_RECORD_TAG,
            "set buffer attr(pts) failed, abort recording");
        OH_VideoEncoder_FreeOutputBuffer(codec_, index);
        return;
    }
    if (muxer_ == nullptr || track_ < 0) {
        lastError_ = "muxer-missing";
        writeFailed_ = true;
        eosCv_.notify_all(); // 唤醒 Stop 的 EOS 等待（writeFailed 谓词）
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_RECORD_TAG,
            "muxer track missing during recording, abort");
        OH_VideoEncoder_FreeOutputBuffer(codec_, index);
        return;
    }
    const OH_AVErrCode writeRet = OH_AVMuxer_WriteSampleBuffer(muxer_, static_cast<uint32_t>(track_), buffer);
    if (writeRet != AV_ERR_OK) {
        // 写失败即中止：停止送样，错误如实带回 ArkTS（页面轮询 isGpuRecording=false 后收尾）
        lastError_ = "muxer-write";
        writeFailed_ = true;
        eosCv_.notify_all(); // 唤醒 Stop 的 EOS 等待（writeFailed 谓词）
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_RECORD_TAG,
            "muxer write failed ret=%{public}d, abort recording", static_cast<int>(writeRet));
        OH_VideoEncoder_FreeOutputBuffer(codec_, index);
        return;
    }
    lastPtsUs_ = ptsUs;
    samples_++; // 仅成功写入自增
    OH_VideoEncoder_FreeOutputBuffer(codec_, index);
}
