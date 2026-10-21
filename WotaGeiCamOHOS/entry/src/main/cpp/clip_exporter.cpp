/*
 * A/B 无损剪辑导出实现（详细边界见 clip_exporter.h）。
 * 关键顺序：源/轨格式 → 视频关键帧扫描取 basePts → 选中音频预扫（决定建不建轨）
 * → muxer AddTrack/SetRotation/Start → 视频整轨复制 → 音频整轨复制 → Stop。
 */
#include "clip_exporter.h"
#include "wota_math.h"

#include <hilog/log.h>
#include <multimedia/player_framework/native_avbuffer.h>
#include <multimedia/player_framework/native_avcodec_base.h>
#include <multimedia/player_framework/native_avdemuxer.h>
#include <multimedia/player_framework/native_avformat.h>
#include <multimedia/player_framework/native_avmuxer.h>
#include <multimedia/player_framework/native_avsource.h>

#include <algorithm>
#include <cstring>
#include <vector>

#define WOTA_LOG_DOMAIN 0x0000
#define WOTA_LOG_TAG "WotaClip"

namespace {

constexpr int32_t VIDEO_TAIL_TOLERANCE = 32;
constexpr int32_t AUDIO_TAIL_TOLERANCE = 8;
constexpr int32_t VIDEO_BUFFER_FLOOR = 8 * 1024 * 1024;
constexpr int32_t AUDIO_BUFFER_BYTES = 1024 * 1024;
constexpr int32_t BUFFER_CAP_MAX = 64 * 1024 * 1024;

struct TrackEntry {
    uint32_t index = 0;
    OH_AVFormat* format = nullptr;
};

enum class ReadOutcome {
    SAMPLE,
    END_OF_STREAM,
    NO_MEMORY,
    ERROR
};

int64_t SeekMs(int64_t us)
{
    return std::max<int64_t>(0, us / 1000);
}

int32_t VideoBufferCapacity(OH_AVFormat* format)
{
    int32_t maxInput = 0;
    if (format != nullptr && OH_AVFormat_GetIntValue(format, OH_MD_KEY_MAX_INPUT_SIZE, &maxInput) &&
        maxInput > 0) {
        const int64_t padded = static_cast<int64_t>(maxInput) + maxInput / 4;
        return static_cast<int32_t>(std::min<int64_t>(BUFFER_CAP_MAX,
            std::max<int64_t>(VIDEO_BUFFER_FLOOR, padded)));
    }
    return VIDEO_BUFFER_FLOOR;
}

bool IsEos(const OH_AVCodecBufferAttr& attr)
{
    // EOS 标志可能和最后一枚带数据的样本同现：只有空样本才判尾；写出前会清 EOS 位。
    return attr.size <= 0;
}

int64_t MonotonicPts(int64_t lastUs, int64_t candidateUs)
{
    return (lastUs >= 0 && candidateUs <= lastUs) ? lastUs + 1 : candidateUs;
}

void UpdateProgress(std::atomic<int32_t>& progress, int32_t pct)
{
    const int32_t clamped = std::min(99, std::max(0, pct));
    const int32_t prev = progress.load();
    if (clamped > prev) {
        progress.store(clamped);
    }
}

ReadOutcome ReadSample(OH_AVDemuxer* demuxer, uint32_t track, OH_AVBuffer* buffer,
    OH_AVCodecBufferAttr& attr)
{
    const OH_AVErrCode ret = OH_AVDemuxer_ReadSampleBuffer(demuxer, track, buffer);
    if (ret == AV_ERR_OK) {
        if (OH_AVBuffer_GetBufferAttr(buffer, &attr) != AV_ERR_OK) {
            return ReadOutcome::ERROR;
        }
        if (IsEos(attr)) {
            return ReadOutcome::END_OF_STREAM;
        }
        return ReadOutcome::SAMPLE;
    }
    if (ret == AV_ERR_NO_MEMORY) {
        return ReadOutcome::NO_MEMORY;
    }
    // 头文件未单列 EOF；OHOS demuxer 在尾样本后以 AV_ERR_UNKNOWN 结束读取（与 Android sampleSize<0
    // 同义）。真正的损坏读取也会落在这里，调用方按轨道尾处理，属于无损导出的保守降级。
    if (ret == AV_ERR_UNKNOWN) {
        return ReadOutcome::END_OF_STREAM;
    }
    return ReadOutcome::ERROR;
}

} // namespace

ClipExporter& ClipExporter::Instance()
{
    static ClipExporter instance;
    return instance;
}

ClipExporter::~ClipExporter()
{
    cancel_.store(true);
    if (worker_.joinable()) {
        worker_.join();
    }
}

std::string ClipExporter::Start(int32_t inFd, int64_t inSize, int32_t outFd, int64_t startMs,
    int64_t endMs, int32_t audioOrdinal)
{
    if (inFd < 0 || inSize <= 0 || outFd < 0 || startMs < 0 || endMs <= startMs) {
        return "args";
    }
    std::lock_guard<std::mutex> lock(mtx_);
    if (state_ == State::RUNNING) {
        return "busy";
    }
    if (worker_.joinable()) {
        worker_.join(); // 上一任务已落终态（Finish 是线程最后一步），此处不会等待运行中的 worker
    }
    cancel_.store(false);
    progress_.store(0);
    terminalStatus_ = "running:0";
    state_ = State::RUNNING;
    Job job;
    job.inFd = inFd;
    job.inSize = inSize;
    job.outFd = outFd;
    job.startMs = startMs;
    job.endMs = endMs;
    job.audioOrdinal = audioOrdinal < 0 ? -1 : audioOrdinal;
    try {
        worker_ = std::thread(&ClipExporter::Run, this, job);
    } catch (...) {
        state_ = State::FAILED;
        terminalStatus_ = "failed:worker";
        return "worker";
    }
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
        "clip export start fd=%{public}d size=%{public}lld range=%{public}lld..%{public}lld audio=%{public}d",
        inFd, static_cast<long long>(inSize), static_cast<long long>(startMs),
        static_cast<long long>(endMs), job.audioOrdinal);
    return "";
}

std::string ClipExporter::Status()
{
    std::lock_guard<std::mutex> lock(mtx_);
    if (state_ == State::RUNNING) {
        return "running:" + std::to_string(progress_.load());
    }
    return terminalStatus_;
}

void ClipExporter::Cancel()
{
    cancel_.store(true);
}

std::string ClipExporter::ProbeTracks(int32_t inFd, int64_t inSize)
{
    if (inFd < 0 || inSize <= 0) {
        return "err:source";
    }
    OH_AVSource* source = OH_AVSource_CreateWithFD(inFd, 0, inSize);
    if (source == nullptr) {
        return "err:source";
    }
    OH_AVFormat* sourceFormat = OH_AVSource_GetSourceFormat(source);
    if (sourceFormat == nullptr) {
        OH_AVSource_Destroy(source);
        return "err:source";
    }
    int32_t trackCount = 0;
    const bool countOk = OH_AVFormat_GetIntValue(sourceFormat, OH_MD_KEY_TRACK_COUNT, &trackCount);
    OH_AVFormat_Destroy(sourceFormat);
    if (!countOk || trackCount <= 0) {
        OH_AVSource_Destroy(source);
        return "err:source";
    }
    int32_t videoCount = 0;
    int32_t audioCount = 0;
    std::string error;
    for (int32_t i = 0; i < trackCount; i++) {
        OH_AVFormat* format = OH_AVSource_GetTrackFormat(source, static_cast<uint32_t>(i));
        if (format == nullptr) {
            error = "err:track-format";
            break;
        }
        int32_t trackType = -1;
        const bool hasType = OH_AVFormat_GetIntValue(format, OH_MD_KEY_TRACK_TYPE, &trackType);
        const char* mime = nullptr;
        OH_AVFormat_GetStringValue(format, OH_MD_KEY_CODEC_MIME, &mime);
        if ((hasType && trackType == MEDIA_TYPE_VID) ||
            (mime != nullptr && std::strncmp(mime, "video/", 6) == 0)) {
            videoCount++;
        } else if ((hasType && trackType == MEDIA_TYPE_AUD) ||
            (mime != nullptr && std::strncmp(mime, "audio/", 6) == 0)) {
            audioCount++;
        }
        OH_AVFormat_Destroy(format);
    }
    OH_AVSource_Destroy(source);
    if (!error.empty()) {
        return error;
    }
    return std::to_string(videoCount) + "," + std::to_string(audioCount);
}

void ClipExporter::Finish(State state, const std::string& status)
{
    std::lock_guard<std::mutex> lock(mtx_);
    state_ = state;
    terminalStatus_ = status;
}

void ClipExporter::Run(const Job& job)
{
    OH_AVSource* source = nullptr;
    OH_AVDemuxer* demuxer = nullptr;
    OH_AVMuxer* muxer = nullptr;
    OH_AVBuffer* videoBuffer = nullptr;
    OH_AVBuffer* audioBuffer = nullptr;
    std::vector<OH_AVFormat*> formats;
    std::vector<TrackEntry> videoTracks;
    std::vector<TrackEntry> audioTracks;

    int64_t basePtsUs = -1;
    int64_t endRelUs = 0;
    int32_t videoSamples = 0;
    int32_t audioSamples = 0;
    int32_t sourceRotation = 0;
    bool audioBuilt = false;
    bool audioSkipped = false;
    bool muxerStarted = false;
    bool cancelled = false;
    bool success = false;
    std::string error;
    int32_t videoTrackIndex = -1;
    int32_t audioTrackIndex = -1;

    do {
        source = OH_AVSource_CreateWithFD(job.inFd, 0, job.inSize);
        if (source == nullptr) {
            error = "source";
            break;
        }
        OH_AVFormat* sourceFormat = OH_AVSource_GetSourceFormat(source);
        if (sourceFormat == nullptr) {
            error = "source";
            break;
        }
        int32_t trackCount = 0;
        const bool countOk = OH_AVFormat_GetIntValue(sourceFormat, OH_MD_KEY_TRACK_COUNT, &trackCount);
        const bool sourceRotationPresent =
            OH_AVFormat_GetIntValue(sourceFormat, OH_MD_KEY_ROTATION, &sourceRotation);
        OH_AVFormat_Destroy(sourceFormat);
        if (!countOk || trackCount <= 0) {
            error = "source";
            break;
        }

        for (int32_t i = 0; i < trackCount; i++) {
            OH_AVFormat* format = OH_AVSource_GetTrackFormat(source, static_cast<uint32_t>(i));
            if (format == nullptr) {
                error = "track-format";
                break;
            }
            formats.push_back(format);
            int32_t trackType = -1;
            const bool hasType = OH_AVFormat_GetIntValue(format, OH_MD_KEY_TRACK_TYPE, &trackType);
            const char* mime = nullptr;
            OH_AVFormat_GetStringValue(format, OH_MD_KEY_CODEC_MIME, &mime);
            const bool isVideo = (hasType && trackType == MEDIA_TYPE_VID) ||
                (mime != nullptr && std::strncmp(mime, "video/", 6) == 0);
            const bool isAudio = (hasType && trackType == MEDIA_TYPE_AUD) ||
                (mime != nullptr && std::strncmp(mime, "audio/", 6) == 0);
            TrackEntry entry;
            entry.index = static_cast<uint32_t>(i);
            entry.format = format;
            if (isVideo) {
                videoTracks.push_back(entry);
            } else if (isAudio) {
                audioTracks.push_back(entry);
            }
        }
        if (!error.empty()) {
            break;
        }
        if (videoTracks.empty()) {
            error = "no-video";
            break;
        }
        const TrackEntry videoTrack = videoTracks[0];
        int32_t trackRotation = 0;
        const bool trackRotationPresent =
            OH_AVFormat_GetIntValue(videoTrack.format, OH_MD_KEY_ROTATION, &trackRotation);
        // 源格式优先（容器级旋转=显示角，权威口径）；缺失时退回视频轨格式。
        const int32_t rotation = sourceRotationPresent ? sourceRotation :
            (trackRotationPresent ? trackRotation : 0);

        TrackEntry* chosenAudio = nullptr;
        if (job.audioOrdinal >= 0) {
            if (static_cast<size_t>(job.audioOrdinal) < audioTracks.size()) {
                chosenAudio = &audioTracks[static_cast<size_t>(job.audioOrdinal)];
            } else {
                audioSkipped = true;
            }
        }

        demuxer = OH_AVDemuxer_CreateWithSource(source);
        if (demuxer == nullptr) {
            error = "source";
            break;
        }
        videoBuffer = OH_AVBuffer_Create(VideoBufferCapacity(videoTrack.format));
        if (videoBuffer == nullptr) {
            error = "sample-too-large";
            break;
        }
        if (chosenAudio != nullptr) {
            audioBuffer = OH_AVBuffer_Create(AUDIO_BUFFER_BYTES);
            if (audioBuffer == nullptr) {
                chosenAudio = nullptr;
                audioSkipped = true;
            }
        }

        if (OH_AVDemuxer_SelectTrackByID(demuxer, videoTrack.index) != AV_ERR_OK) {
            error = "read";
            break;
        }
        if (OH_AVDemuxer_SeekToTime(demuxer, SeekMs(job.startMs * 1000), SEEK_MODE_PREVIOUS_SYNC) != AV_ERR_OK) {
            error = "no-sync";
            break;
        }

        // 入点向前对齐到第一个视频同步帧；basePts 必须取该同步帧的源 PTS。
        OH_AVCodecBufferAttr attr;
        for (;;) {
            if (cancel_.load()) {
                cancelled = true;
                break;
            }
            const ReadOutcome outcome = ReadSample(demuxer, videoTrack.index, videoBuffer, attr);
            if (outcome == ReadOutcome::END_OF_STREAM) {
                error = "no-sync";
                break;
            }
            if (outcome == ReadOutcome::NO_MEMORY) {
                error = "sample-too-large";
                break;
            }
            if (outcome == ReadOutcome::ERROR) {
                error = "read";
                break;
            }
            if ((attr.flags & AVCODEC_BUFFER_FLAGS_SYNC_FRAME) != 0) {
                basePtsUs = attr.pts;
                break;
            }
        }
        if (cancelled || !error.empty()) {
            break;
        }
        endRelUs = job.endMs * 1000 - basePtsUs;

        // 音轨预扫：选中轨在区间内至少有一枚可写样本才建轨（零样本轨会让 muxer Stop 失败）。
        if (chosenAudio != nullptr) {
            bool writable = false;
            OH_AVDemuxer_UnselectTrackByID(demuxer, videoTrack.index);
            if (OH_AVDemuxer_SelectTrackByID(demuxer, chosenAudio->index) != AV_ERR_OK) {
                audioSkipped = true;
                chosenAudio = nullptr;
            } else {
                const bool seekOk = OH_AVDemuxer_SeekToTime(demuxer, SeekMs(basePtsUs),
                    SEEK_MODE_PREVIOUS_SYNC) == AV_ERR_OK;
                if (seekOk) {
                    int32_t outOfRange = 0;
                    while (outOfRange < AUDIO_TAIL_TOLERANCE) {
                        if (cancel_.load()) {
                            cancelled = true;
                            break;
                        }
                        const ReadOutcome outcome = ReadSample(demuxer, chosenAudio->index, audioBuffer, attr);
                        if (outcome == ReadOutcome::END_OF_STREAM ||
                            outcome == ReadOutcome::NO_MEMORY || outcome == ReadOutcome::ERROR) {
                            break;
                        }
                        const int64_t rel = attr.pts - basePtsUs;
                        if (rel > endRelUs) {
                            outOfRange++;
                        } else {
                            outOfRange = 0;
                            if (rel >= 0) {
                                writable = true;
                                break;
                            }
                        }
                    }
                }
                OH_AVDemuxer_UnselectTrackByID(demuxer, chosenAudio->index);
                audioSkipped = !writable;
            }
            if (cancelled) {
                break;
            }
        }

        muxer = OH_AVMuxer_Create(job.outFd, AV_OUTPUT_FORMAT_MPEG_4);
        if (muxer == nullptr) {
            error = "muxer";
            break;
        }
        if (OH_AVMuxer_AddTrack(muxer, &videoTrackIndex, videoTrack.format) != AV_ERR_OK ||
            videoTrackIndex < 0) {
            error = "add-track";
            break;
        }
        if (!audioSkipped && chosenAudio != nullptr) {
            if (OH_AVMuxer_AddTrack(muxer, &audioTrackIndex, chosenAudio->format) == AV_ERR_OK &&
                audioTrackIndex >= 0) {
                audioBuilt = true;
            } else {
                // 轨道格式不被 muxer 接受：降级纯视频（视频完好优先于整体失败）
                audioSkipped = true;
                audioTrackIndex = -1;
                OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                    "audio add-track failed, export video only");
            }
        }
        if (OH_AVMuxer_SetRotation(muxer, WotaNormalizeQuarter(rotation)) != AV_ERR_OK) {
            error = "rotation";
            break;
        }
        if (OH_AVMuxer_Start(muxer) != AV_ERR_OK) {
            error = "muxer-start";
            break;
        }
        muxerStarted = true;

        // 第一趟：视频整轨（输出 0 = 入点同步帧）。
        if (OH_AVDemuxer_SelectTrackByID(demuxer, videoTrack.index) != AV_ERR_OK ||
            OH_AVDemuxer_SeekToTime(demuxer, SeekMs(basePtsUs), SEEK_MODE_PREVIOUS_SYNC) != AV_ERR_OK) {
            error = "read";
            break;
        }
        int32_t videoTail = 0;
        int64_t videoLastPts = -1;
        for (;;) {
            if (cancel_.load()) {
                cancelled = true;
                break;
            }
            const ReadOutcome outcome = ReadSample(demuxer, videoTrack.index, videoBuffer, attr);
            if (outcome == ReadOutcome::END_OF_STREAM) {
                break;
            }
            if (outcome == ReadOutcome::NO_MEMORY) {
                error = "sample-too-large";
                break;
            }
            if (outcome == ReadOutcome::ERROR) {
                error = "read";
                break;
            }
            const int64_t rel = attr.pts - basePtsUs;
            if (rel > endRelUs) {
                if (++videoTail >= VIDEO_TAIL_TOLERANCE) {
                    break;
                }
                continue;
            }
            videoTail = 0;
            if (rel < 0) { // 开放 GOP 前导 B 帧：负 PTS 不写
                continue;
            }
            const int64_t pts = MonotonicPts(videoLastPts, rel);
            videoLastPts = pts;
            attr.pts = pts;
            attr.flags &= ~static_cast<uint32_t>(AVCODEC_BUFFER_FLAGS_EOS);
            if (OH_AVBuffer_SetBufferAttr(videoBuffer, &attr) != AV_ERR_OK ||
                OH_AVMuxer_WriteSampleBuffer(muxer, static_cast<uint32_t>(videoTrackIndex), videoBuffer) !=
                    AV_ERR_OK) {
                error = "write";
                break;
            }
            videoSamples++;
            if (endRelUs > 0) {
                int32_t pct = static_cast<int32_t>(std::min<int64_t>(100,
                    std::max<int64_t>(0, rel * 100 / endRelUs)));
                if (audioBuilt) {
                    pct = pct * 7 / 10;
                }
                UpdateProgress(progress_, pct);
            }
        }
        if (cancelled || !error.empty()) {
            break;
        }
        if (videoSamples == 0) {
            error = "empty-range";
            break;
        }

        // 第二趟：选中音频整轨（预扫已确认区间内至少一枚可写样本）。
        if (audioBuilt) {
            OH_AVDemuxer_UnselectTrackByID(demuxer, videoTrack.index);
            if (OH_AVDemuxer_SelectTrackByID(demuxer, chosenAudio->index) != AV_ERR_OK ||
                OH_AVDemuxer_SeekToTime(demuxer, SeekMs(basePtsUs), SEEK_MODE_PREVIOUS_SYNC) != AV_ERR_OK) {
                error = "read";
                break;
            }
            int32_t audioTail = 0;
            int64_t audioLastPts = -1;
            for (;;) {
                if (cancel_.load()) {
                    cancelled = true;
                    break;
                }
                const ReadOutcome outcome = ReadSample(demuxer, chosenAudio->index, audioBuffer, attr);
                if (outcome == ReadOutcome::END_OF_STREAM) {
                    break;
                }
                if (outcome == ReadOutcome::NO_MEMORY) {
                    error = "sample-too-large";
                    break;
                }
                if (outcome == ReadOutcome::ERROR) {
                    error = "read";
                    break;
                }
                const int64_t rel = attr.pts - basePtsUs;
                if (rel > endRelUs) {
                    if (++audioTail >= AUDIO_TAIL_TOLERANCE) {
                        break;
                    }
                    continue;
                }
                audioTail = 0;
                if (rel < 0) {
                    continue;
                }
                const int64_t pts = MonotonicPts(audioLastPts, rel);
                audioLastPts = pts;
                attr.pts = pts;
                attr.flags &= ~static_cast<uint32_t>(AVCODEC_BUFFER_FLAGS_EOS);
                if (OH_AVBuffer_SetBufferAttr(audioBuffer, &attr) != AV_ERR_OK ||
                    OH_AVMuxer_WriteSampleBuffer(muxer, static_cast<uint32_t>(audioTrackIndex), audioBuffer) !=
                        AV_ERR_OK) {
                    error = "write";
                    break;
                }
                audioSamples++;
                if (endRelUs > 0) {
                    const int32_t ratio = static_cast<int32_t>(std::min<int64_t>(100,
                        std::max<int64_t>(0, rel * 100 / endRelUs)));
                    UpdateProgress(progress_, 70 + ratio * 3 / 10);
                }
            }
            if (cancelled || !error.empty()) {
                break;
            }
            if (audioSamples == 0) {
                // 预扫通过却一枚未写：已建轨无法回退，只能按失败收尾（ArkTS 删除半成品）。
                error = "write";
                break;
            }
        }

        if (OH_AVMuxer_Stop(muxer) != AV_ERR_OK) {
            error = "write";
            break;
        }
        muxerStarted = false;
        success = true;
    } while (false);

    if (muxer != nullptr) {
        if (muxerStarted) {
            OH_AVMuxer_Stop(muxer); // 取消/失败：best-effort 封口，半成品由 ArkTS 删除
        }
        OH_AVMuxer_Destroy(muxer);
        muxer = nullptr;
    }
    if (demuxer != nullptr) {
        OH_AVDemuxer_Destroy(demuxer);
        demuxer = nullptr;
    }
    if (videoBuffer != nullptr) {
        OH_AVBuffer_Destroy(videoBuffer);
        videoBuffer = nullptr;
    }
    if (audioBuffer != nullptr) {
        OH_AVBuffer_Destroy(audioBuffer);
        audioBuffer = nullptr;
    }
    for (OH_AVFormat* format : formats) {
        if (format != nullptr) {
            OH_AVFormat_Destroy(format);
        }
    }
    formats.clear();
    if (source != nullptr) {
        OH_AVSource_Destroy(source);
        source = nullptr;
    }

    if (cancelled) {
        OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "clip export cancelled v=%{public}d a=%{public}d", videoSamples, audioSamples);
        Finish(State::CANCELLED, "cancelled");
        return;
    }
    if (!success) {
        if (error.empty()) {
            error = "read";
        }
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "clip export failed %{public}s v=%{public}d a=%{public}d",
            error.c_str(), videoSamples, audioSamples);
        Finish(State::FAILED, "failed:" + error);
        return;
    }
    progress_.store(100);
    std::string status = "done:" + std::to_string(basePtsUs) + ":" + std::to_string(videoSamples) +
        ":" + std::to_string(audioSamples);
    if (audioSkipped) {
        status += ":audio=skipped";
    }
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
        "clip export done base=%{public}lld v=%{public}d a=%{public}d skipped=%{public}d rotation=%{public}d",
        static_cast<long long>(basePtsUs), videoSamples, audioSamples, audioSkipped ? 1 : 0,
        WotaNormalizeQuarter(sourceRotation));
    Finish(State::DONE, status);
}
