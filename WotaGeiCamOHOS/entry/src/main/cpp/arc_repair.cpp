/*
 * 离线光弧修复 CPU 执行器实现（边界/契约见 arc_repair.h）。
 */
#include "arc_repair.h"
#include "arc_flow.h"
#include "wota_math.h"

#include <hilog/log.h>
#include <multimedia/player_framework/native_avbuffer.h>
#include <multimedia/player_framework/native_avcodec_base.h>
#include <multimedia/player_framework/native_avcodec_videodecoder.h>
#include <multimedia/player_framework/native_avcodec_videoencoder.h>
#include <multimedia/player_framework/native_avdemuxer.h>
#include <multimedia/player_framework/native_avformat.h>
#include <multimedia/player_framework/native_avmuxer.h>
#include <multimedia/player_framework/native_avsource.h>

#include <algorithm>
#include <chrono>
#include <cstring>
#include <vector>

#define WOTA_LOG_DOMAIN 0x0000
#define WOTA_LOG_TAG "WotaArcRepair"

namespace {

constexpr int32_t kMaxProbeSamples = 2'000'000;
constexpr int64_t kProbeTimeoutUs = 10'000;
constexpr int64_t kCodecTimeoutUs = 20'000;
constexpr int64_t kCleanMinUs = 1001;
constexpr int64_t kCleanMaxUs = 1'000'000;
constexpr int32_t kMinDeltas = 8;
constexpr int32_t kProbeBufferFloor = 8 * 1024 * 1024;
constexpr int32_t kBufferCapMax = 64 * 1024 * 1024;
constexpr int32_t kDefaultFps = 30;

/** ArkTS record/ArcRepairPlan.ets 的整数公式镜像（三式逐字同形；ArkTS 为已测规格） */
int64_t DstFrameCount(int64_t srcFrames, int64_t srcNum, int64_t srcDen, int64_t dstFps)
{
    if (srcFrames <= 0) {
        return 0;
    }
    if (srcNum <= 0 || srcDen <= 0 || dstFps <= 0 || srcNum <= dstFps * srcDen) {
        return srcFrames;
    }
    return (srcFrames * dstFps * srcDen + srcNum - 1) / srcNum;
}

bool IsKept(int64_t s, int64_t srcNum, int64_t srcDen, int64_t dstFps)
{
    if (s < 0) {
        return false;
    }
    if (srcNum <= 0 || srcDen <= 0 || dstFps <= 0 || srcNum <= dstFps * srcDen) {
        return true;
    }
    const int64_t j = (s * dstFps * srcDen + srcNum - 1) / srcNum;
    return j * srcNum / (dstFps * srcDen) == s;
}

/** 紧打包 I420 平面（Y/U/V 各自连续，无 stride 填充） */
struct YuvFrame {
    int32_t width = 0;
    int32_t height = 0;
    std::vector<uint8_t> y;
    std::vector<uint8_t> u;
    std::vector<uint8_t> v;

    void Alloc(int32_t w, int32_t h)
    {
        width = w;
        height = h;
        y.assign(static_cast<size_t>(w) * h, 0);
        const int32_t cw = (w + 1) / 2;
        const int32_t ch = (h + 1) / 2;
        u.assign(static_cast<size_t>(cw) * ch, 0);
        v.assign(static_cast<size_t>(cw) * ch, 0);
    }
};

int32_t ClampDeadband(int32_t deadband)
{
    return std::min(32, std::max(0, deadband));
}

/**
 * 2×2 块整像素选择（a 就地更新；与 record/ArcRepairMerge.ets 同规则）：
 * 块内取 4 个 Y 的最大 Δ，`maxΔ > D`（严格大于）时整块 4Y+U+V 取自 b，否则整块保持 a。
 */
void SelectInPlace(YuvFrame& a, const YuvFrame& b, int32_t deadband)
{
    if (a.width != b.width || a.height != b.height || a.width <= 0 || a.height <= 0) {
        return;
    }
    const int32_t width = a.width;
    const int32_t height = a.height;
    const int32_t cw = width / 2;
    const int32_t ch = height / 2;
    const int32_t d = ClampDeadband(deadband);
    int32_t c = 0;
    for (int32_t by = 0; by < ch; ++by) {
        for (int32_t bx = 0; bx < cw; ++bx) {
            const int32_t i = bx * 2 + by * 2 * width;
            const int32_t i1 = i + 1;
            const int32_t i2 = i + width;
            const int32_t i3 = i2 + 1;
            const int32_t delta = std::max(std::max(static_cast<int32_t>(b.y[i]) - a.y[i],
                static_cast<int32_t>(b.y[i1]) - a.y[i1]),
                std::max(static_cast<int32_t>(b.y[i2]) - a.y[i2],
                    static_cast<int32_t>(b.y[i3]) - a.y[i3]));
            if (delta > d) {
                a.y[i] = b.y[i];
                a.y[i1] = b.y[i1];
                a.y[i2] = b.y[i2];
                a.y[i3] = b.y[i3];
                a.u[c] = b.u[c];
                a.v[c] = b.v[c];
            }
            ++c;
        }
    }
}

/** 解码输出 → 紧打包 I420。支持 NV12 / NV21 / YUVI420；其它格式返回 false（不猜布局）。 */
bool ConvertToI420(const uint8_t* data, int32_t size, int32_t width, int32_t height, int32_t stride,
    int32_t sliceHeight, int32_t pixelFormat, YuvFrame& out)
{
    if (data == nullptr || width <= 0 || height <= 0) {
        return false;
    }
    const int32_t s = stride > 0 ? stride : width;
    const int32_t sh = sliceHeight > 0 ? sliceHeight : height;
    const int64_t yBytes = static_cast<int64_t>(s) * sh;
    const int64_t uvBytes = static_cast<int64_t>(s) * (sh / 2);
    if (static_cast<int64_t>(size) < yBytes + uvBytes) {
        return false;
    }
    out.Alloc(width, height);
    // Y：逐行拷贝（丢弃 stride 右侧填充与 sliceHeight 下部填充）
    for (int32_t row = 0; row < height; ++row) {
        std::memcpy(out.y.data() + static_cast<size_t>(row) * width,
            data + static_cast<size_t>(row) * s, static_cast<size_t>(width));
    }
    const int32_t cw = (width + 1) / 2;
    const int32_t ch = (height + 1) / 2;
    const uint8_t* uv = data + yBytes;
    if (pixelFormat == AV_PIXEL_FORMAT_YUVI420) {
        // 三平面：U、V 各自连续，行内 stride/2 有效
        const uint8_t* uPlane = uv;
        const uint8_t* vPlane = uv + static_cast<size_t>(s / 2) * sh;
        for (int32_t row = 0; row < ch; ++row) {
            std::memcpy(out.u.data() + static_cast<size_t>(row) * cw,
                uPlane + static_cast<size_t>(row) * (s / 2), static_cast<size_t>(cw));
            std::memcpy(out.v.data() + static_cast<size_t>(row) * cw,
                vPlane + static_cast<size_t>(row) * (s / 2), static_cast<size_t>(cw));
        }
        return true;
    }
    if (pixelFormat == AV_PIXEL_FORMAT_NV12 || pixelFormat == AV_PIXEL_FORMAT_NV21) {
        // 交织平面：NV12 = U,V；NV21 = V,U
        const int32_t uOff = pixelFormat == AV_PIXEL_FORMAT_NV12 ? 0 : 1;
        const int32_t vOff = pixelFormat == AV_PIXEL_FORMAT_NV12 ? 1 : 0;
        for (int32_t row = 0; row < ch; ++row) {
            const uint8_t* src = uv + static_cast<size_t>(row) * s;
            for (int32_t col = 0; col < cw; ++col) {
                out.u[static_cast<size_t>(row) * cw + col] = src[static_cast<size_t>(col) * 2 + uOff];
                out.v[static_cast<size_t>(row) * cw + col] = src[static_cast<size_t>(col) * 2 + vOff];
            }
        }
        return true;
    }
    return false;
}

int32_t ProbeBufferCapacity(OH_AVFormat* videoFormat)
{
    int32_t maxInput = 0;
    if (videoFormat != nullptr &&
        OH_AVFormat_GetIntValue(videoFormat, OH_MD_KEY_MAX_INPUT_SIZE, &maxInput) && maxInput > 0) {
        const int64_t padded = static_cast<int64_t>(maxInput) + maxInput / 4;
        return static_cast<int32_t>(std::min<int64_t>(kBufferCapMax,
            std::max<int64_t>(kProbeBufferFloor, padded)));
    }
    return kProbeBufferFloor;
}

bool FormatIntOrZero(OH_AVFormat* format, const char* key, int32_t& out)
{
    out = 0;
    if (format == nullptr) {
        return false;
    }
    return OH_AVFormat_GetIntValue(format, key, &out);
}

int32_t FormatFrameRate(OH_AVFormat* format)
{
    double fps = 0.0;
    if (format != nullptr && OH_AVFormat_GetDoubleValue(format, OH_MD_KEY_FRAME_RATE, &fps) && fps > 0) {
        return static_cast<int32_t>(fps + 0.5);
    }
    int32_t intFps = 0;
    if (format != nullptr && OH_AVFormat_GetIntValue(format, OH_MD_KEY_FRAME_RATE, &intFps) && intFps > 0) {
        return intFps;
    }
    return 0;
}

std::string MakeStatusDone(int32_t videoSamples, int64_t audioSamples, bool muxStarted,
    bool trackAdded, bool muxStopOk)
{
    // done:<videoSamples>:<audioSamples>:<mux0/1>:<track0/1>:<stop0/1>
    // audioSamples = 全部音轨成功写入 muxer 的样本合计（T30 起真实值；无音轨源恒 0）
    return "done:" + std::to_string(videoSamples) + ":" + std::to_string(audioSamples) + ":" +
        (muxStarted ? "1" : "0") + ":" + (trackAdded ? "1" : "0") + ":" +
        (muxStopOk ? "1" : "0");
}

/**
 * 单条音频轨的直拷记账。三条并行表（audioTrackIndices/audioFormats/audioDstTracks）与
 * audioStats 同下标：源轨下标 / 格式副本 / muxer 轨号（-1 = AddTrack 失败降级）/ 样本账。
 */
struct AudioLaneStats {
    int32_t samples = 0;
    int32_t skipped = 0;
    int64_t lastPtsUs = -1;
    bool readError = false;
    /** 预读探针：该轨至少有一枚非空样本（false = 零样本轨，不 AddTrack，避免 muxer Stop 失败） */
    bool hasData = false;
};

/** 探测结果（内部结构；串化在 Probe） */
struct ProbeResult {
    bool ok = false;
    int32_t width = 0;
    int32_t height = 0;
    int32_t rotation = 0;
    int32_t containerFps = 0;
    int64_t durationMs = 0;
    int32_t cleanCount = 0;
    int64_t medianUs = 0;
    int32_t audioCount = 0;
    std::string mime;
    std::string error;
};

/** 逐轨单调 PTS 兜底（与 clip_exporter 的 MonotonicPts 同式；本 TU 独立定义） */
int64_t MonotonicPts(int64_t lastUs, int64_t candidateUs)
{
    return (lastUs >= 0 && candidateUs <= lastUs) ? lastUs + 1 : candidateUs;
}

bool HasEosFlag(const OH_AVCodecBufferAttr& attr)
{
    return (attr.flags & AVCODEC_BUFFER_FLAGS_EOS) != 0;
}

} // namespace

ArcRepair& ArcRepair::Instance()
{
    static ArcRepair instance;
    return instance;
}

ArcRepair::~ArcRepair()
{
    cancel_.store(true);
    if (worker_.joinable()) {
        worker_.join();
    }
}

std::string ArcRepair::Probe(int32_t inFd, int64_t inSize)
{
    if (inFd < 0 || inSize <= 0) {
        return "err:ARC_SOURCE";
    }
    ProbeResult result;
    OH_AVSource* source = OH_AVSource_CreateWithFD(inFd, 0, inSize);
    if (source == nullptr) {
        return "err:ARC_SOURCE";
    }
    OH_AVFormat* sourceFormat = OH_AVSource_GetSourceFormat(source);
    int32_t trackCount = 0;
    if (sourceFormat == nullptr ||
        !OH_AVFormat_GetIntValue(sourceFormat, OH_MD_KEY_TRACK_COUNT, &trackCount) || trackCount <= 0) {
        OH_AVFormat_Destroy(sourceFormat);
        OH_AVSource_Destroy(source);
        return "err:ARC_SOURCE";
    }
    OH_AVFormat_GetLongValue(sourceFormat, OH_MD_KEY_DURATION, &result.durationMs);
    OH_AVFormat_Destroy(sourceFormat);

    int32_t videoTrack = -1;
    OH_AVFormat* videoFormat = nullptr;
    for (int32_t i = 0; i < trackCount; ++i) {
        OH_AVFormat* trackFormat = OH_AVSource_GetTrackFormat(source, static_cast<uint32_t>(i));
        if (trackFormat == nullptr) {
            continue;
        }
        int32_t trackType = -1;
        OH_AVFormat_GetIntValue(trackFormat, OH_MD_KEY_TRACK_TYPE, &trackType);
        if (trackType == MEDIA_TYPE_AUD) {
            result.audioCount++;
            OH_AVFormat_Destroy(trackFormat);
            continue;
        }
        if (trackType == MEDIA_TYPE_VID && videoTrack < 0) {
            videoTrack = i;
            videoFormat = trackFormat;
            continue;
        }
        OH_AVFormat_Destroy(trackFormat);
    }
    if (videoTrack < 0 || videoFormat == nullptr) {
        OH_AVFormat_Destroy(videoFormat);
        OH_AVSource_Destroy(source);
        return "err:ARC_NO_VIDEO";
    }
    FormatIntOrZero(videoFormat, OH_MD_KEY_WIDTH, result.width);
    FormatIntOrZero(videoFormat, OH_MD_KEY_HEIGHT, result.height);
    FormatIntOrZero(videoFormat, OH_MD_KEY_ROTATION, result.rotation);
    result.containerFps = FormatFrameRate(videoFormat);
    const char* mime = nullptr;
    if (OH_AVFormat_GetStringValue(videoFormat, OH_MD_KEY_CODEC_MIME, &mime) && mime != nullptr) {
        result.mime = mime;
    }
    if (result.width <= 0 || result.height <= 0) {
        OH_AVFormat_Destroy(videoFormat);
        OH_AVSource_Destroy(source);
        return "err:ARC_NO_VIDEO";
    }

    // 帧距扫描：读 ≤2M 样本，干净区间 [1001, 1e6]µs 的相邻 PTS 差求中位数
    OH_AVDemuxer* demuxer = OH_AVDemuxer_CreateWithSource(source);
    if (demuxer == nullptr) {
        OH_AVFormat_Destroy(videoFormat);
        OH_AVSource_Destroy(source);
        return "err:ARC_SOURCE";
    }
    if (OH_AVDemuxer_SelectTrackByID(demuxer, static_cast<uint32_t>(videoTrack)) != AV_ERR_OK) {
        OH_AVDemuxer_Destroy(demuxer);
        OH_AVFormat_Destroy(videoFormat);
        OH_AVSource_Destroy(source);
        return "err:ARC_SOURCE";
    }
    OH_AVBuffer* buffer = OH_AVBuffer_Create(ProbeBufferCapacity(videoFormat));
    OH_AVFormat_Destroy(videoFormat);
    if (buffer == nullptr) {
        OH_AVDemuxer_Destroy(demuxer);
        OH_AVSource_Destroy(source);
        return "err:ARC_SOURCE";
    }
    std::vector<int64_t> deltas;
    int64_t prevPts = -1;
    int32_t read = 0;
    while (read < kMaxProbeSamples) {
        const OH_AVErrCode ret = OH_AVDemuxer_ReadSampleBuffer(demuxer, static_cast<uint32_t>(videoTrack), buffer);
        if (ret != AV_ERR_OK) {
            break; // EOS（AV_ERR_UNKNOWN）或损坏：已读样本决定结论
        }
        OH_AVCodecBufferAttr attr;
        if (OH_AVBuffer_GetBufferAttr(buffer, &attr) != AV_ERR_OK) {
            break;
        }
        if (attr.size <= 0) {
            break;
        }
        read++;
        if (prevPts >= 0) {
            const int64_t d = attr.pts - prevPts;
            if (d >= kCleanMinUs && d <= kCleanMaxUs) {
                deltas.push_back(d);
            }
        }
        prevPts = attr.pts;
    }
    OH_AVBuffer_Destroy(buffer);
    OH_AVDemuxer_Destroy(demuxer);
    OH_AVSource_Destroy(source);

    result.cleanCount = static_cast<int32_t>(deltas.size());
    if (!deltas.empty()) {
        std::sort(deltas.begin(), deltas.end());
        result.medianUs = deltas[deltas.size() / 2];
    }
    result.ok = true;
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
        "probe %{public}dx%{public}d rot=%{public}d fps=%{public}d dur=%{public}lld clean=%{public}d "
        "median=%{public}lld audio=%{public}d mime=%{public}s",
        result.width, result.height, result.rotation, result.containerFps,
        static_cast<long long>(result.durationMs), result.cleanCount,
        static_cast<long long>(result.medianUs), result.audioCount, result.mime.c_str());

    return "ok:" + std::to_string(result.width) + ":" + std::to_string(result.height) + ":" +
        std::to_string(result.rotation) + ":" + std::to_string(result.containerFps) + ":" +
        std::to_string(result.durationMs) + ":" + std::to_string(result.cleanCount) + ":" +
        std::to_string(result.medianUs) + ":" + std::to_string(result.audioCount) + ":" + result.mime;
}

std::string ArcRepair::Start(int32_t inFd, int64_t inSize, int32_t outFd, int32_t dstFps,
    int32_t bitrate, int32_t deadband, int64_t srcNum, int64_t srcDen, int64_t srcFrames,
    int64_t dstFrames, int32_t rotation, int64_t durationMs)
{
    if (inFd < 0 || inSize <= 0 || outFd < 0 || dstFps <= 0 || srcNum <= 0 || srcDen <= 0) {
        return "args";
    }
    std::lock_guard<std::mutex> lock(mtx_);
    if (state_ == State::RUNNING) {
        return "busy";
    }
    if (worker_.joinable()) {
        worker_.join(); // 上一任务已落终态（Finish 是线程最后一步）
    }
    cancel_.store(false);
    progress_.store(0);
    terminalStatus_ = "running:0";
    state_ = State::RUNNING;
    Job job;
    job.inFd = inFd;
    job.inSize = inSize;
    job.outFd = outFd;
    job.dstFps = dstFps;
    job.bitrate = bitrate > 0 ? bitrate : 16'000'000;
    job.deadband = ClampDeadband(deadband);
    job.srcNum = srcNum;
    job.srcDen = srcDen;
    job.srcFrames = srcFrames;
    job.dstFrames = dstFrames > 0 ? dstFrames : DstFrameCount(srcFrames, srcNum, srcDen, dstFps);
    job.rotation = rotation;
    job.durationMs = durationMs;
    try {
        worker_ = std::thread(&ArcRepair::Run, this, job);
    } catch (...) {
        state_ = State::FAILED;
        terminalStatus_ = "failed:ARC_ENGINE";
        return "worker";
    }
    return "";
}

std::string ArcRepair::Status()
{
    std::lock_guard<std::mutex> lock(mtx_);
    if (state_ == State::RUNNING) {
        return "running:" + std::to_string(progress_.load());
    }
    return terminalStatus_;
}

void ArcRepair::Cancel()
{
    cancel_.store(true);
}

void ArcRepair::Finish(State state, const std::string& status)
{
    std::lock_guard<std::mutex> lock(mtx_);
    state_ = state;
    terminalStatus_ = status;
}

void ArcRepair::Run(const Job& job)
{
    const auto deadline = std::chrono::steady_clock::now() +
        std::chrono::milliseconds(job.durationMs > 0 ? job.durationMs * 8 + 120'000 : 600'000);
    OH_AVSource* source = nullptr;
    OH_AVDemuxer* demuxer = nullptr;
    OH_AVCodec* decoder = nullptr;
    OH_AVCodec* encoder = nullptr;
    OH_AVMuxer* muxer = nullptr;
    OH_AVBuffer* inBuffer = nullptr;
    OH_AVFormat* videoFormat = nullptr;
    int32_t width = 0;
    int32_t height = 0;
    bool muxStarted = false;
    bool trackAdded = false;
    bool muxStopOk = false;
    int32_t videoSamples = 0;
    int32_t dstTrack = -1;
    // 音频直拷表（必须在 fail lambda 之前声明：失败清理要销毁格式副本）
    std::vector<uint32_t> audioTrackIndices;
    std::vector<OH_AVFormat*> audioFormats;
    std::vector<int32_t> audioDstTracks;
    std::vector<AudioLaneStats> audioStats;

    auto fail = [&](const std::string& code) {
        if (encoder != nullptr) {
            OH_VideoEncoder_Stop(encoder);
        }
        if (muxer != nullptr) {
            if (muxStarted) {
                OH_AVMuxer_Stop(muxer); // best-effort 封口，半成品由 ArkTS 删
            }
            OH_AVMuxer_Destroy(muxer);
        }
        if (encoder != nullptr) {
            OH_VideoEncoder_Destroy(encoder);
        }
        if (decoder != nullptr) {
            OH_VideoDecoder_Stop(decoder);
            OH_VideoDecoder_Destroy(decoder);
        }
        if (inBuffer != nullptr) {
            OH_AVBuffer_Destroy(inBuffer);
        }
        if (demuxer != nullptr) {
            OH_AVDemuxer_Destroy(demuxer);
        }
        if (source != nullptr) {
            OH_AVSource_Destroy(source);
        }
        OH_AVFormat_Destroy(videoFormat);
        for (auto& format : audioFormats) {
            OH_AVFormat_Destroy(format);
            format = nullptr;
        }
        audioFormats.clear();
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "repair failed %{public}s",
            code.c_str());
        // 取消走独立终态（契约/验收单都按 `cancelled` 判；不再发 failed:ARC_CANCELLED）
        if (code == "ARC_CANCELLED") {
            Finish(State::CANCELLED, "cancelled");
        } else {
            Finish(State::FAILED, "failed:" + code);
        }
    };
    auto cancelledOrTimedOut = [&]() -> const char* {
        if (cancel_.load()) {
            return "ARC_CANCELLED";
        }
        if (std::chrono::steady_clock::now() > deadline) {
            return "ARC_TIMEOUT";
        }
        return nullptr;
    };

    // ---------- 源与视频轨
    source = OH_AVSource_CreateWithFD(job.inFd, 0, job.inSize);
    if (source == nullptr) {
        fail("ARC_SOURCE");
        return;
    }
    OH_AVFormat* sourceFormat = OH_AVSource_GetSourceFormat(source);
    int32_t trackCount = 0;
    if (sourceFormat == nullptr ||
        !OH_AVFormat_GetIntValue(sourceFormat, OH_MD_KEY_TRACK_COUNT, &trackCount) || trackCount <= 0) {
        OH_AVFormat_Destroy(sourceFormat);
        fail("ARC_SOURCE");
        return;
    }
    OH_AVFormat_Destroy(sourceFormat);
    int32_t videoTrack = -1;
    for (int32_t i = 0; i < trackCount; ++i) {
        OH_AVFormat* tf = OH_AVSource_GetTrackFormat(source, static_cast<uint32_t>(i));
        if (tf == nullptr) {
            continue;
        }
        int32_t type = -1;
        OH_AVFormat_GetIntValue(tf, OH_MD_KEY_TRACK_TYPE, &type);
        if (type == MEDIA_TYPE_VID && videoTrack < 0) {
            videoTrack = i;
            videoFormat = tf;
        } else if (type == MEDIA_TYPE_AUD) {
            // 运行期保留全部音频轨（T30 直拷）：下标与格式副本进并行表，样本由独立 demuxer 读
            audioTrackIndices.push_back(static_cast<uint32_t>(i));
            audioFormats.push_back(tf);
            audioDstTracks.push_back(-1);
            audioStats.push_back(AudioLaneStats());
        } else {
            OH_AVFormat_Destroy(tf);
        }
    }
    if (videoTrack < 0 || videoFormat == nullptr) {
        fail("ARC_NO_VIDEO");
        return;
    }
    // 零样本音轨预检（T30 审查 P3）：AddTrack 早于直拷，零样本轨会让 muxer Stop 失败并连坐
    // 整片。这里用临时 source/demuxer 每轨预读一枚样本：无非空样本的轨直接不建轨（降级）。
    if (!audioTrackIndices.empty()) {
        OH_AVSource* preSource = OH_AVSource_CreateWithFD(job.inFd, 0, job.inSize);
        OH_AVDemuxer* preDemuxer = preSource != nullptr
            ? OH_AVDemuxer_CreateWithSource(preSource) : nullptr;
        OH_AVBuffer* preBuffer = preDemuxer != nullptr ? OH_AVBuffer_Create(256 * 1024) : nullptr;
        if (preDemuxer != nullptr && preBuffer != nullptr) {
            for (size_t ai = 0; ai < audioTrackIndices.size(); ++ai) {
                if (OH_AVDemuxer_SelectTrackByID(preDemuxer, audioTrackIndices[ai]) != AV_ERR_OK) {
                    continue;
                }
                OH_AVDemuxer_SeekToTime(preDemuxer, 0, SEEK_MODE_PREVIOUS_SYNC);
                for (int32_t probe = 0; probe < 8; ++probe) {
                    if (OH_AVDemuxer_ReadSampleBuffer(preDemuxer, audioTrackIndices[ai], preBuffer)
                        != AV_ERR_OK) {
                        break;
                    }
                    OH_AVCodecBufferAttr probeAttr;
                    if (OH_AVBuffer_GetBufferAttr(preBuffer, &probeAttr) != AV_ERR_OK ||
                        probeAttr.size <= 0) {
                        break;
                    }
                    audioStats[ai].hasData = true;
                    break;
                }
                OH_AVDemuxer_UnselectTrackByID(preDemuxer, audioTrackIndices[ai]);
                if (!audioStats[ai].hasData) {
                    OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                        "audio track %{public}u has no data, track skipped", audioTrackIndices[ai]);
                }
            }
        } else {
            // 预检不可用：保守按「有数据」处理（直拷阶段逐样本失败仍会降级）
            for (auto& stats : audioStats) {
                stats.hasData = true;
            }
            OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "audio preflight unavailable, assume all lanes have data");
        }
        OH_AVBuffer_Destroy(preBuffer);
        OH_AVDemuxer_Destroy(preDemuxer);
        OH_AVSource_Destroy(preSource);
    }
    FormatIntOrZero(videoFormat, OH_MD_KEY_WIDTH, width);
    FormatIntOrZero(videoFormat, OH_MD_KEY_HEIGHT, height);
    if (width <= 0 || height <= 0) {
        fail("ARC_NO_VIDEO");
        return;
    }
    const char* mimeRaw = nullptr;
    OH_AVFormat_GetStringValue(videoFormat, OH_MD_KEY_CODEC_MIME, &mimeRaw);
    const std::string mime = mimeRaw != nullptr ? mimeRaw : "";

    demuxer = OH_AVDemuxer_CreateWithSource(source);
    if (demuxer == nullptr || OH_AVDemuxer_SelectTrackByID(demuxer, static_cast<uint32_t>(videoTrack)) != AV_ERR_OK) {
        fail("ARC_SOURCE");
        return;
    }

    // ---------- 解码器（同步模式；buffer 输出）
    decoder = OH_VideoDecoder_CreateByMime(mime.c_str());
    if (decoder == nullptr) {
        fail("ARC_DECODER");
        return;
    }
    OH_AVFormat* decFormat = OH_AVFormat_Create();
    OH_AVFormat_Copy(decFormat, videoFormat);
    // 剥旋转角：解码面只吃正向帧，容器旋转在 muxer 侧写回原始值
    OH_AVFormat_SetIntValue(decFormat, OH_MD_KEY_ROTATION, 0);
    if (OH_VideoDecoder_Configure(decoder, decFormat) != AV_ERR_OK) {
        OH_AVFormat_Destroy(decFormat);
        fail("ARC_DECODER");
        return;
    }
    OH_AVFormat_Destroy(decFormat);
    if (OH_VideoDecoder_Prepare(decoder) != AV_ERR_OK || OH_VideoDecoder_Start(decoder) != AV_ERR_OK) {
        fail("ARC_DECODER");
        return;
    }

    // ---------- 编码器（同步模式；AVC + YUVI420 缓冲输入）
    encoder = OH_VideoEncoder_CreateByMime(OH_AVCODEC_MIMETYPE_VIDEO_AVC);
    if (encoder == nullptr) {
        fail("ARC_ENCODER");
        return;
    }
    OH_AVFormat* encFormat = OH_AVFormat_Create();
    bool encOk = OH_AVFormat_SetIntValue(encFormat, OH_MD_KEY_WIDTH, width);
    encOk = OH_AVFormat_SetIntValue(encFormat, OH_MD_KEY_HEIGHT, height) && encOk;
    encOk = OH_AVFormat_SetDoubleValue(encFormat, OH_MD_KEY_FRAME_RATE,
        static_cast<double>(job.dstFps)) && encOk;
    encOk = OH_AVFormat_SetLongValue(encFormat, OH_MD_KEY_BITRATE,
        static_cast<int64_t>(job.bitrate)) && encOk;
    encOk = OH_AVFormat_SetIntValue(encFormat, OH_MD_KEY_I_FRAME_INTERVAL, 1000) && encOk;
    encOk = OH_AVFormat_SetIntValue(encFormat, OH_MD_KEY_PIXEL_FORMAT,
        AV_PIXEL_FORMAT_YUVI420) && encOk;
    if (!encOk || OH_VideoEncoder_Configure(encoder, encFormat) != AV_ERR_OK) {
        OH_AVFormat_Destroy(encFormat);
        fail("ARC_ENCODER");
        return;
    }
    OH_AVFormat_Destroy(encFormat);
    if (OH_VideoEncoder_Prepare(encoder) != AV_ERR_OK || OH_VideoEncoder_Start(encoder) != AV_ERR_OK) {
        fail("ARC_ENCODER");
        return;
    }

    // ---------- 输出封装（延迟到首枚编码输出才 AddTrack/Start）
    muxer = OH_AVMuxer_Create(job.outFd, AV_OUTPUT_FORMAT_MPEG_4);
    if (muxer == nullptr) {
        fail("ARC_MUXER");
        return;
    }

    inBuffer = OH_AVBuffer_Create(ProbeBufferCapacity(videoFormat));

    // ---------- 抽帧状态机与三面
    ArcRepairFlow flow(job.dstFps);
    YuvFrame cur;
    YuvFrame pending;
    YuvFrame acc;
    bool hasPendingFrame = false;
    bool hasAccFrame = false;
    int32_t srcIndex = 0;
    int64_t ptsBase = -1;
    int64_t prevPts = -1;
    const int64_t minGapUs = (500'000 * job.srcDen) / job.srcNum;
    int32_t decodedOutW = width;
    int32_t decodedOutH = height;
    int32_t stride = width;
    int32_t sliceHeight = height;
    int32_t pixelFormat = AV_PIXEL_FORMAT_YUVI420;
    /** stride/sliceHeight 缺失告警：每条任务只打一次（防逐帧刷日志） */
    bool layoutWarned = false;
    bool encoderEos = false;

    auto encodeFrame = [&](const YuvFrame& frame, int64_t ptsUs) -> const char* {
        uint32_t index = 0;
        const OH_AVErrCode q = OH_VideoEncoder_QueryInputBuffer(encoder, &index, kCodecTimeoutUs);
        if (q != AV_ERR_OK) {
            return "ARC_CPU_FEED";
        }
        OH_AVBuffer* buf = OH_VideoEncoder_GetInputBuffer(encoder, index);
        if (buf == nullptr) {
            return "ARC_CPU_FEED";
        }
        const int64_t total = static_cast<int64_t>(frame.width) * frame.height * 3 / 2;
        if (total <= 0 || total > OH_AVBuffer_GetCapacity(buf)) {
            return "ARC_CPU_FEED";
        }
        uint8_t* dst = OH_AVBuffer_GetAddr(buf);
        if (dst == nullptr) {
            return "ARC_CPU_FEED";
        }
        size_t offset = 0;
        std::memcpy(dst + offset, frame.y.data(), frame.y.size());
        offset += frame.y.size();
        std::memcpy(dst + offset, frame.u.data(), frame.u.size());
        offset += frame.u.size();
        std::memcpy(dst + offset, frame.v.data(), frame.v.size());
        OH_AVCodecBufferAttr attr;
        attr.pts = ptsUs;
        attr.size = static_cast<int32_t>(total);
        attr.offset = 0;
        attr.flags = AVCODEC_BUFFER_FLAGS_NONE;
        if (OH_AVBuffer_SetBufferAttr(buf, &attr) != AV_ERR_OK ||
            OH_VideoEncoder_PushInputBuffer(encoder, index) != AV_ERR_OK) {
            return "ARC_CPU_FEED";
        }
        return nullptr;
    };

    auto drainEncoder = [&](bool drainAll) -> const char* {
        int32_t guard = 0;
        int32_t idleRounds = 0;
        while (guard++ < 4096) {
            // EOS 收尾期间同样服从取消/deadline（原实现会在 TRY_AGAIN 上空转 4096×20ms）
            if (const char* stop = cancelledOrTimedOut()) {
                return stop;
            }
            uint32_t index = 0;
            const OH_AVErrCode q = OH_VideoEncoder_QueryOutputBuffer(encoder, &index, kCodecTimeoutUs);
            if (q == AV_ERR_TRY_AGAIN_LATER) {
                if (!drainAll) {
                    return nullptr;
                }
                // 收尾排空：空转上限 50 拍（~1s），仍然拿不到就交回（EOS 由 Stop 兜底）
                if (++idleRounds > 50) {
                    return nullptr;
                }
                continue;
            }
            idleRounds = 0;
            if (q != AV_ERR_OK) {
                return "ARC_ENCODER";
            }
            OH_AVBuffer* buf = OH_VideoEncoder_GetOutputBuffer(encoder, index);
            if (buf == nullptr) {
                return "ARC_ENCODER";
            }
            OH_AVCodecBufferAttr attr;
            const bool attrOk = OH_AVBuffer_GetBufferAttr(buf, &attr) == AV_ERR_OK;
            const bool eos = attrOk && HasEosFlag(attr);
            if (attrOk && !eos && attr.size > 0) {
                if (!trackAdded) {
                    // 首枚编码输出：用输出描述建轨（编码器已给出真实 csd/宽高）
                    OH_AVFormat* desc = OH_VideoEncoder_GetOutputDescription(encoder);
                    OH_AVFormat* trackFormat = OH_AVFormat_Create();
                    if (desc == nullptr || !OH_AVFormat_Copy(trackFormat, desc)) {
                        OH_AVFormat_Destroy(desc);
                        OH_AVFormat_Destroy(trackFormat);
                        OH_VideoEncoder_FreeOutputBuffer(encoder, index);
                        return "ARC_MUXER";
                    }
                    OH_AVFormat_SetIntValue(trackFormat, OH_MD_KEY_WIDTH, width);
                    OH_AVFormat_SetIntValue(trackFormat, OH_MD_KEY_HEIGHT, height);
                    OH_AVFormat_Destroy(desc);
                    const OH_AVErrCode addRet = OH_AVMuxer_AddTrack(muxer, &dstTrack, trackFormat);
                    OH_AVFormat_Destroy(trackFormat);
                    if (addRet != AV_ERR_OK) {
                        OH_VideoEncoder_FreeOutputBuffer(encoder, index);
                        return "ARC_MUXER";
                    }
                    trackAdded = true;
                    // T30：视频轨之后、SetRotation/Start 之前逐轨 AddTrack 音频（失败逐轨降级，
                    // 不连坐：该轨 audioDstTracks 保持 -1，直拷阶段跳过）
                    for (size_t ai = 0; ai < audioFormats.size(); ++ai) {
                        if (audioFormats[ai] == nullptr || !audioStats[ai].hasData) {
                            continue; // 零样本/无格式轨不建轨（降级，避免 muxer Stop 失败）
                        }
                        int32_t audioDst = -1;
                        const OH_AVErrCode audioAdd =
                            OH_AVMuxer_AddTrack(muxer, &audioDst, audioFormats[ai]);
                        if (audioAdd != AV_ERR_OK) {
                            audioDstTracks[ai] = -1;
                            OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                                "audio track %{public}u addTrack failed (%{public}d), lane degraded",
                                audioTrackIndices[ai], static_cast<int32_t>(audioAdd));
                            continue;
                        }
                        audioDstTracks[ai] = audioDst;
                    }
                    const int32_t safeRotation = WotaNormalizeQuarter(job.rotation);
                    if (OH_AVMuxer_SetRotation(muxer, safeRotation) != AV_ERR_OK) {
                        OH_VideoEncoder_FreeOutputBuffer(encoder, index);
                        return "ARC_MUXER";
                    }
                    if (OH_AVMuxer_Start(muxer) != AV_ERR_OK) {
                        OH_VideoEncoder_FreeOutputBuffer(encoder, index);
                        return "ARC_MUXER";
                    }
                    muxStarted = true;
                }
                // CSD（SPS/PPS）已随 AddTrack 的 trackFormat 登记（GetOutputDescription 拷贝）：
                // 编码器若再吐一枚 CODEC_DATA 缓冲，**建轨后直接 Free，不写样**——否则它的
                // PTS=0 会与首真实帧撞车、videoSamples 虚增，muxer 也可能拒收畸形样本。
                if ((attr.flags & AVCODEC_BUFFER_FLAGS_CODEC_DATA) != 0) {
                    OH_VideoEncoder_FreeOutputBuffer(encoder, index);
                    continue;
                }
                attr.flags &= ~static_cast<uint32_t>(AVCODEC_BUFFER_FLAGS_EOS);
                OH_AVBuffer_SetBufferAttr(buf, &attr);
                if (OH_AVMuxer_WriteSampleBuffer(muxer, static_cast<uint32_t>(dstTrack), buf) != AV_ERR_OK) {
                    OH_VideoEncoder_FreeOutputBuffer(encoder, index);
                    return "ARC_MUXER";
                }
                videoSamples++;
            }
            OH_VideoEncoder_FreeOutputBuffer(encoder, index);
            if (eos) {
                encoderEos = true;
                return nullptr;
            }
        }
        return drainAll ? nullptr : nullptr;
    };

    // 执行一条流式指令（cur/pending/acc 三面；操作数顺序对齐上游）
    auto applyOps = [&](const std::vector<ArcOp>& ops) -> const char* {
        for (const ArcOp& op : ops) {
            switch (op.kind) {
                case ArcOpKind::HoldCurAsPending:
                    pending = cur;
                    hasPendingFrame = true;
                    break;
                case ArcOpKind::AccumulateCur:
                    if (op.flag) {
                        acc = cur;
                    } else if (!hasAccFrame) {
                        acc = cur;
                    } else {
                        SelectInPlace(acc, cur, job.deadband); // acc = select(acc, cur)
                    }
                    hasAccFrame = true;
                    break;
                case ArcOpKind::FoldAccIntoPending:
                    if (hasPendingFrame && hasAccFrame) {
                        SelectInPlace(pending, acc, job.deadband); // pending = select(pending, acc)
                    }
                    break;
                case ArcOpKind::MergeCurAsPending:
                    if (op.flag && hasAccFrame) {
                        pending = cur;
                        SelectInPlace(pending, acc, job.deadband); // pending = select(cur, acc)
                    } else {
                        pending = cur;
                    }
                    hasPendingFrame = true;
                    break;
                case ArcOpKind::EmitPending: {
                    if (!hasPendingFrame) {
                        break;
                    }
                    const char* feedErr = encodeFrame(pending, op.ptsUs);
                    if (feedErr != nullptr) {
                        return feedErr;
                    }
                    const char* drainErr = drainEncoder(false);
                    if (drainErr != nullptr) {
                        return drainErr;
                    }
                    progress_.store(static_cast<int32_t>(std::min<int64_t>(99,
                        flow.KeptCount() * 100 / std::max<int64_t>(1, job.dstFrames))));
                    break;
                }
            }
        }
        return nullptr;
    };

    bool inputEos = false;
    bool decoderEos = false;
    int32_t guard = 0;
    while (!decoderEos && guard++ < 5'000'000) {
        if (const char* stop = cancelledOrTimedOut()) {
            fail(stop);
            return;
        }
        // 喂入
        if (!inputEos) {
            uint32_t index = 0;
            const OH_AVErrCode q = OH_VideoDecoder_QueryInputBuffer(decoder, &index, kCodecTimeoutUs);
            if (q == AV_ERR_OK) {
                OH_AVBuffer* buf = OH_VideoDecoder_GetInputBuffer(decoder, index);
                if (buf == nullptr) {
                    fail("ARC_DECODER");
                    return;
                }
                const OH_AVErrCode readRet = OH_AVDemuxer_ReadSampleBuffer(demuxer,
                    static_cast<uint32_t>(videoTrack), buf);
                OH_AVCodecBufferAttr attr;
                if (readRet == AV_ERR_OK && OH_AVBuffer_GetBufferAttr(buf, &attr) == AV_ERR_OK) {
                    if (attr.size > 0) {
                        attr.flags &= ~static_cast<uint32_t>(AVCODEC_BUFFER_FLAGS_EOS);
                        OH_AVBuffer_SetBufferAttr(buf, &attr);
                        if (OH_VideoDecoder_PushInputBuffer(decoder, index) != AV_ERR_OK) {
                            fail("ARC_DECODER");
                            return;
                        }
                    } else {
                        attr.pts = 0;
                        attr.size = 0;
                        attr.offset = 0;
                        attr.flags = AVCODEC_BUFFER_FLAGS_EOS;
                        OH_AVBuffer_SetBufferAttr(buf, &attr);
                        OH_VideoDecoder_PushInputBuffer(decoder, index);
                        inputEos = true;
                    }
                } else {
                    // 读到尾/损坏：按 EOS 处理（无损导出同口径的保守降级）
                    OH_AVCodecBufferAttr eosAttr;
                    eosAttr.pts = 0;
                    eosAttr.size = 0;
                    eosAttr.offset = 0;
                    eosAttr.flags = AVCODEC_BUFFER_FLAGS_EOS;
                    OH_AVBuffer_SetBufferAttr(buf, &eosAttr);
                    OH_VideoDecoder_PushInputBuffer(decoder, index);
                    inputEos = true;
                }
            }
        }
        // 取帧
        uint32_t outIndex = 0;
        const OH_AVErrCode oq = OH_VideoDecoder_QueryOutputBuffer(decoder, &outIndex, kCodecTimeoutUs);
        if (oq == AV_ERR_STREAM_CHANGED) {
            OH_AVFormat* desc = OH_VideoDecoder_GetOutputDescription(decoder);
            FormatIntOrZero(desc, OH_MD_KEY_WIDTH, decodedOutW);
            FormatIntOrZero(desc, OH_MD_KEY_HEIGHT, decodedOutH);
            OH_AVFormat_Destroy(desc);
            continue;
        }
        if (oq == AV_ERR_TRY_AGAIN_LATER) {
            continue;
        }
        if (oq != AV_ERR_OK) {
            // 其它错误不再空转到 deadline（原实现一律 continue）：解码器坏即失败
            fail("ARC_DECODER");
            return;
        }
        OH_AVBuffer* frameBuf = OH_VideoDecoder_GetOutputBuffer(decoder, outIndex);
        if (frameBuf == nullptr) {
            fail("ARC_DECODER");
            return;
        }
        OH_AVCodecBufferAttr attr;
        if (OH_AVBuffer_GetBufferAttr(frameBuf, &attr) != AV_ERR_OK) {
            OH_VideoDecoder_FreeOutputBuffer(decoder, outIndex);
            fail("ARC_DECODER");
            return;
        }
        if (HasEosFlag(attr)) {
            OH_VideoDecoder_FreeOutputBuffer(decoder, outIndex);
            decoderEos = true;
            break;
        }
        if (attr.size <= 0) {
            OH_VideoDecoder_FreeOutputBuffer(decoder, outIndex);
            continue;
        }
        // 输出布局（STRIDE/SLICE_HEIGHT/PIXEL_FORMAT 每次取，STREAM_CHANGED 后跟随）
        OH_AVFormat* outDesc = OH_VideoDecoder_GetOutputDescription(decoder);
        int32_t fmtW = 0;
        int32_t fmtH = 0;
        FormatIntOrZero(outDesc, OH_MD_KEY_WIDTH, fmtW);
        FormatIntOrZero(outDesc, OH_MD_KEY_HEIGHT, fmtH);
        if (fmtW > 0) {
            decodedOutW = fmtW;
        }
        if (fmtH > 0) {
            decodedOutH = fmtH;
        }
        int32_t fmtStride = 0;
        int32_t fmtSlice = 0;
        int32_t fmtPixel = 0;
        FormatIntOrZero(outDesc, OH_MD_KEY_VIDEO_STRIDE, fmtStride);
        FormatIntOrZero(outDesc, OH_MD_KEY_VIDEO_SLICE_HEIGHT, fmtSlice);
        FormatIntOrZero(outDesc, OH_MD_KEY_PIXEL_FORMAT, fmtPixel);
        OH_AVFormat_Destroy(outDesc);
        if (fmtStride > 0) {
            stride = fmtStride;
        }
        if (fmtSlice > 0) {
            sliceHeight = fmtSlice;
        }
        if (fmtPixel > 0) {
            pixelFormat = fmtPixel;
        }
        // 输出显示尺寸必须与容器宽高一致（旋转已剥离，解码面应是正向同尺寸）：
        // 不一致说明解码器按其它布局输出，继续按容器宽高解释会产出错色/错位 —— 硬失败（上游同口径）
        if (fmtW > 0 && fmtH > 0 && (fmtW != width || fmtH != height)) {
            OH_VideoDecoder_FreeOutputBuffer(decoder, outIndex);
            fail("ARC_CPU_YUV");
            return;
        }
        // 严格像素格式（T29 审查 P3）：缺 OH_MD_KEY_PIXEL_FORMAT 键即失败——绝不默认猜 I420。
        // stride/sliceHeight 缺失时每条任务只 WARN 一次并回落宽高（部分 ROM 只给宽高）。
        if (fmtPixel <= 0) {
            OH_VideoDecoder_FreeOutputBuffer(decoder, outIndex);
            fail("ARC_CPU_YUV");
            return;
        }
        if (!layoutWarned && (fmtStride <= 0 || fmtSlice <= 0)) {
            layoutWarned = true;
            OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "decoder layout keys missing (stride=%{public}d slice=%{public}d), fallback %{public}dx%{public}d",
                fmtStride, fmtSlice, decodedOutW, decodedOutH);
        }
        const uint8_t* data = OH_AVBuffer_GetAddr(frameBuf);
        if (!ConvertToI420(data, attr.size, decodedOutW, decodedOutH, stride, sliceHeight,
            pixelFormat, cur)) {
            OH_VideoDecoder_FreeOutputBuffer(decoder, outIndex);
            fail("ARC_CPU_YUV");
            return;
        }
        OH_VideoDecoder_FreeOutputBuffer(decoder, outIndex);

        // 首帧 PTS 归零 + 去重门限（半个源帧周期）
        if (ptsBase < 0) {
            ptsBase = attr.pts;
        }
        const int64_t rel = std::max<int64_t>(0, attr.pts - ptsBase);
        if (srcIndex > 0 && rel - prevPts < minGapUs) {
            continue; // 重复/抖动 PTS：丢弃，不推进状态机
        }
        prevPts = rel;
        const bool kept = IsKept(srcIndex, job.srcNum, job.srcDen, job.dstFps);
        const std::vector<ArcOp> ops = flow.OnClassification(kept);
        if (const char* opErr = applyOps(ops)) {
            fail(opErr);
            return;
        }
        srcIndex++;
    }
    if (const char* stop = cancelledOrTimedOut()) {
        fail(stop);
        return;
    }

    // ---------- EOS 尾巴：把累积补弧并进最后一枚待发帧
    if (const char* opErr = applyOps(flow.OnSourceEos())) {
        fail(opErr);
        return;
    }
    // 编码器 EOS
    if (!encoderEos) {
        uint32_t index = 0;
        if (OH_VideoEncoder_QueryInputBuffer(encoder, &index, kCodecTimeoutUs) == AV_ERR_OK) {
            OH_AVBuffer* buf = OH_VideoEncoder_GetInputBuffer(encoder, index);
            if (buf != nullptr) {
                OH_AVCodecBufferAttr attr;
                attr.pts = 0;
                attr.size = 0;
                attr.offset = 0;
                attr.flags = AVCODEC_BUFFER_FLAGS_EOS;
                OH_AVBuffer_SetBufferAttr(buf, &attr);
                OH_VideoEncoder_PushInputBuffer(encoder, index);
            }
        }
        if (const char* drainErr = drainEncoder(true)) {
            fail(drainErr);
            return;
        }
    }
    if (const char* stop = cancelledOrTimedOut()) {
        fail(stop);
        return;
    }
    // 排空截尾诊断（T29 审查 P3）：delta 只用于日志/真机判据，**不改变成功判定**
    // （成功仍只由 arcRepairOk 三格 + stop 门槛决定）；非零即提示编码器/时钟问题。
    if (!encoderEos || videoSamples != static_cast<int32_t>(job.dstFrames)) {
        OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "encoder tail mismatch eos=%{public}d videoSamples=%{public}d dstFrames=%{public}lld "
            "delta=%{public}d", encoderEos ? 1 : 0, videoSamples,
            static_cast<long long>(job.dstFrames),
            videoSamples - static_cast<int32_t>(job.dstFrames));
    }

    // ---------- 音频直拷（T30）：视频轨写完后逐轨顺序写，不与视频交织
    // 用同一 fd 新建 source/demuxer（原 demuxer 已读尽视频轨，回 seek 不可靠）。
    int64_t audioSamples = 0;
    if (muxStarted && !audioTrackIndices.empty()) {
        OH_AVSource* audioSource = OH_AVSource_CreateWithFD(job.inFd, 0, job.inSize);
        OH_AVDemuxer* audioDemuxer = audioSource != nullptr
            ? OH_AVDemuxer_CreateWithSource(audioSource) : nullptr;
        OH_AVBuffer* audioBuffer = audioDemuxer != nullptr ? OH_AVBuffer_Create(1024 * 1024) : nullptr;
        if (audioSource == nullptr || audioDemuxer == nullptr || audioBuffer == nullptr) {
            OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "audio copy skipped: source/demuxer/buffer unavailable");
        } else {
            for (size_t ai = 0; ai < audioTrackIndices.size(); ++ai) {
                if (audioDstTracks[ai] < 0) {
                    continue; // AddTrack 阶段已降级
                }
                const uint32_t laneTrack = audioTrackIndices[ai];
                const int32_t laneDst = audioDstTracks[ai];
                AudioLaneStats& lane = audioStats[ai];
                if (const char* stop = cancelledOrTimedOut()) {
                    OH_AVBuffer_Destroy(audioBuffer);
                    OH_AVDemuxer_Destroy(audioDemuxer);
                    OH_AVSource_Destroy(audioSource);
                    fail(stop);
                    return;
                }
                // 每轨独立 select；SeekToTime(0) 失败不判死——新建 demuxer 本来就在 0，
                // 回退为直接从头读（T30 审查 P3）
                if (OH_AVDemuxer_SelectTrackByID(audioDemuxer, laneTrack) != AV_ERR_OK) {
                    OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                        "audio lane %{public}u select failed, degraded", laneTrack);
                    continue;
                }
                if (OH_AVDemuxer_SeekToTime(audioDemuxer, 0, SEEK_MODE_PREVIOUS_SYNC) != AV_ERR_OK) {
                    OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                        "audio lane %{public}u seek(0) failed, read from current position", laneTrack);
                }
                int64_t laneBaseUs = -1;
                for (;;) {
                    if (const char* stop = cancelledOrTimedOut()) {
                        OH_AVBuffer_Destroy(audioBuffer);
                        OH_AVDemuxer_Destroy(audioDemuxer);
                        OH_AVSource_Destroy(audioSource);
                        fail(stop);
                        return;
                    }
                    const OH_AVErrCode readRet = OH_AVDemuxer_ReadSampleBuffer(audioDemuxer,
                        laneTrack, audioBuffer);
                    if (readRet != AV_ERR_OK) {
                        // AV_ERR_UNKNOWN = 正常 EOF；其它错误记 readError（摘要 WARN 标注）
                        if (readRet != AV_ERR_UNKNOWN) {
                            lane.readError = true;
                        }
                        break;
                    }
                    OH_AVCodecBufferAttr attr;
                    if (OH_AVBuffer_GetBufferAttr(audioBuffer, &attr) != AV_ERR_OK) {
                        lane.readError = true;
                        break;
                    }
                    if (attr.size <= 0) {
                        break;
                    }
                    // PTS 归一：以视频首帧为基准（与视频 PTS 同轴）；视频基准缺失时退回本轨首样本
                    if (laneBaseUs < 0) {
                        laneBaseUs = attr.pts;
                    }
                    const int64_t base = ptsBase >= 0 ? ptsBase : laneBaseUs;
                    int64_t norm = attr.pts - base;
                    if (norm < 0) {
                        lane.skipped++; // 早于视频首帧的音频段丢弃（上游同口径）
                        continue;
                    }
                    norm = MonotonicPts(lane.lastPtsUs, norm);
                    lane.lastPtsUs = norm;
                    attr.pts = norm;
                    attr.flags &= ~static_cast<uint32_t>(AVCODEC_BUFFER_FLAGS_EOS);
                    OH_AVBuffer_SetBufferAttr(audioBuffer, &attr);
                    if (OH_AVMuxer_WriteSampleBuffer(muxer, static_cast<uint32_t>(laneDst),
                        audioBuffer) != AV_ERR_OK) {
                        lane.skipped++;
                        OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                            "audio lane %{public}u write failed, sample skipped", laneTrack);
                        continue;
                    }
                    lane.samples++;
                    audioSamples++;
                }
                OH_AVDemuxer_UnselectTrackByID(audioDemuxer, laneTrack);
                OH_LOG_Print(LOG_APP, lane.samples > 0 ? LOG_INFO : LOG_WARN, WOTA_LOG_DOMAIN,
                    WOTA_LOG_TAG, "audio lane %{public}u done track=%{public}d samples=%{public}d "
                    "skipped=%{public}d%s", laneTrack, laneDst, lane.samples,
                    lane.skipped, lane.readError ? " (read error)" : "");
            }
            OH_AVBuffer_Destroy(audioBuffer);
            OH_AVDemuxer_Destroy(audioDemuxer);
            OH_AVSource_Destroy(audioSource);
            int32_t lanesOk = 0;
            for (const auto& stats : audioStats) {
                if (stats.samples > 0) {
                    lanesOk++;
                }
            }
            OH_LOG_Print(LOG_APP, lanesOk > 0 ? LOG_INFO : LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "audio lanes ok=%{public}d/%{public}zu samples=%{public}lld",
                lanesOk, audioStats.size(), static_cast<long long>(audioSamples));
        }
    }
    if (const char* stop = cancelledOrTimedOut()) {
        fail(stop);
        return;
    }

    // ---------- 收尾：Stop 结果进状态串（arcRepairKeep 的 stop 门槛）
    OH_VideoEncoder_Stop(encoder);
    muxStopOk = muxStarted && OH_AVMuxer_Stop(muxer) == AV_ERR_OK;
    OH_AVMuxer_Destroy(muxer);
    muxer = nullptr;
    OH_VideoEncoder_Destroy(encoder);
    encoder = nullptr;
    OH_VideoDecoder_Stop(decoder);
    OH_VideoDecoder_Destroy(decoder);
    decoder = nullptr;
    OH_AVBuffer_Destroy(inBuffer);
    inBuffer = nullptr;
    OH_AVDemuxer_Destroy(demuxer);
    demuxer = nullptr;
    OH_AVSource_Destroy(source);
    source = nullptr;
    OH_AVFormat_Destroy(videoFormat);
    videoFormat = nullptr;

    for (auto& format : audioFormats) {
        OH_AVFormat_Destroy(format);
        format = nullptr;
    }
    audioFormats.clear();
    progress_.store(100);
    const std::string done = MakeStatusDone(videoSamples, audioSamples, muxStarted,
        trackAdded, muxStopOk);
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
        "repair done %{public}s srcFrames=%{public}lld dstFrames=%{public}lld delta=%{public}d "
        "audio=%{public}lld lanes=%{public}zu", done.c_str(),
        static_cast<long long>(job.srcFrames), static_cast<long long>(job.dstFrames),
        videoSamples - static_cast<int32_t>(job.dstFrames),
        static_cast<long long>(audioSamples), audioTrackIndices.size());
    Finish(State::DONE, done);
}
