/*
 * GPU 录制最小切片（CodecRecorder 等价物的 GL→H.264→MP4 通路，对齐上游 38d712a 的最小化）：
 * OH_VideoEncoder（surface 输入）+ OH_AVMuxer（fd 输出 MPEG_4），视频-only、单段、无抽帧/无音频。
 *
 * 边界（登记）：
 * - 输出 fd 归 ArkTS 所有（fileIo.openSync 取得并传入）；native 绝不 close，停录后由 ArkTS 关闭并 stat；
 * - 每步失败返回机器码：busy/encoder-create/register-callback/format-create/format-key/configure/
 *   get-surface/prepare/muxer/track-format/track/muxer-rotation/muxer-start/start/encoder-surface；
 *   运行期原因：muxer-write/pts-set/muxer-missing/engine-error/surface-lost/encoder-pass-failed；
 * - PTS：一律用墙钟相对段基准（NowUs() − startUs，µs）并经 OH_AVBuffer_SetBufferAttr 回写；
 *   lastPtsUs_ 只做单调守卫。前提：编码器无 B 帧（默认档，登记）；
 * - 容器旋转：OH_AVMuxer_SetRotation(normalizeQuarter(sensor − display))（0/90/180/270 四舍五入；
 *   非 90 倍数 sensor 按上游 normalizeQuarter 归一），须在 muxer Start 前；
 * - muxer 写失败/时间轴回写失败即停止送样并置 writeFailed_（IsRecording=false，资源仍活跃至 Stop），
 *   错误由 getGpuRecordError/stopGpuRecord 如实带回；samples 仅成功写入自增；
 * - 编码器原生窗口由本类 GetSurface 取得、Stop 时 Destroy（须先由 gl_render 解绑 EGL 编码面）；
 * - 音频轨/分段轮转/抽帧/FrameLedger/.drops.json/参数下发留白（后续批）。
 */
#ifndef WOTA_CODEC_RECORDER_H
#define WOTA_CODEC_RECORDER_H

#include <native_window/external_window.h>

#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <string>

// 前向声明（与 SDK 头一致的 typedef 重声明，C++ 允许同目标重复 typedef；避免本头拉入整个媒体头集）
typedef struct OH_AVCodec OH_AVCodec;
typedef struct OH_AVMuxer OH_AVMuxer;
typedef struct OH_AVFormat OH_AVFormat;
typedef struct OH_AVBuffer OH_AVBuffer;

/**
 * 录制健康单锁快照：trackAdded/muxStarted/samples + formatChanged。
 * 三格语义对照（2026-10-22 健康收口批）：上游 GPU 路 videoTrackAdded 的真信号是
 * MediaCodec format-changed 回调；OHOS 侧等价物 = OH_VCodecCallback onStreamChanged
 * （surface 输入下首帧到达/编码器格式落定时触发），formatChanged 位即此信号。
 * trackAdded（muxer AddTrack 成功）为 OHOS 特有诊断位，不进健康判定（Camera.ets 头注释登记）。
 */
struct HealthSnapshot {
    bool trackAdded = false;
    bool muxStarted = false;
    int32_t samples = 0;
    bool formatChanged = false;
};

class CodecRecorder {
public:
    static CodecRecorder& Instance();

    /** 起录：空串 = 成功；非空 = 机器码（失败已内部回收全部已建资源）。rotation 为容器 hint（0/90/180/270） */
    std::string Start(int32_t fd, int32_t width, int32_t height, int32_t fps, int32_t bitrate,
        int32_t rotation);

    /** 停录：返回摘要（ok samples=N eos=0/1 或错误串）；native 不 close fd */
    std::string Stop();

    /** 可继续送帧：RECORDING 且无写失败（STARTING/writeFailed 均为 false） */
    bool IsRecording();

    /** 资源仍活跃（state_ != IDLE，含 writeFailed 未 Stop）：销毁渲染面时的兜底中止判据 */
    bool IsActive();

    std::string LastError();

    /** 已成功写入 muxer 的样本字节累计（轮转触发量；Stop 后 0） */
    int64_t GetWrittenBytes();

    /** 健康三格：编码器视频轨已添加 / muxer 已启动 / 成功写入样本数 */
    bool IsVideoTrackAdded();
    bool IsMuxStarted();
    int32_t SampleCount();

    /** 一次加锁读取三格（NAPI getGpuHealth 用） */
    HealthSnapshot GetHealthSnapshot();

    /** 以指定原因中止录制（surface 丢失/编码 pass 连续失败/muxer 写失败）；内部走 Stop 清理 */
    void Abort(const std::string& reason);

    /** surface 丢失（WotaGlDestroy）：Abort("surface-lost") 的具名入口 */
    void AbortOnSurfaceLost();

    /** Start 成功后编码器输入面（供 gl_render attach EGL 编码面）；Stop 后为空 */
    OHNativeWindow* EncoderWindow();

private:
    CodecRecorder() = default;
    ~CodecRecorder() = default;
    CodecRecorder(const CodecRecorder&) = delete;
    CodecRecorder& operator=(const CodecRecorder&) = delete;

    enum class State { IDLE, STARTING, RECORDING, STOPPING };

    static void OnErrorCb(OH_AVCodec* codec, int32_t errorCode, void* userData);
    static void OnStreamChangedCb(OH_AVCodec* codec, OH_AVFormat* format, void* userData);
    static void OnNeedInputBufferCb(OH_AVCodec* codec, uint32_t index, OH_AVBuffer* buffer, void* userData);
    static void OnNewOutputBufferCb(OH_AVCodec* codec, uint32_t index, OH_AVBuffer* buffer, void* userData);

    void HandleOutputBuffer(uint32_t index, OH_AVBuffer* buffer);
    /** Start 失败路径：持锁摘指针 → 解锁后销毁（避免 Destroy 与回调线程互等锁） */
    std::string FailStart(std::unique_lock<std::mutex>& lock, const char* code);

    std::mutex mtx_;
    std::condition_variable eosCv_;
    State state_ = State::IDLE;
    OH_AVCodec* codec_ = nullptr;
    OH_AVMuxer* muxer_ = nullptr;
    int32_t track_ = -1;
    OHNativeWindow* window_ = nullptr;
    int64_t startUs_ = 0;
    int64_t lastPtsUs_ = -1;
    int32_t samples_ = 0;
    int64_t writtenBytes_ = 0;
    bool muxStarted_ = false;
    /** 编码器 OnStreamChanged 真信号（上游 format-changed 等价物；Start/FailStart/Stop 复位） */
    bool formatChanged_ = false;
    bool eosSeen_ = false;
    /** muxer 写失败：停止送样并作为中止原因带回 ArkTS（samples 仅成功写入自增） */
    bool writeFailed_ = false;
    std::string lastError_;
};

#endif // WOTA_CODEC_RECORDER_H
