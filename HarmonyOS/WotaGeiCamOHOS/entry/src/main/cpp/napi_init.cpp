/*
 * GL-0 原生模块（对齐上游 38d712a 的移植前置）：
 * - napi 模块名 nm_modname 必须与 ArkTS 侧 XComponent({ libraryname: 'entry' }) 及
 *   CMake 目标名一致（三者统一为 entry，产物 libentry.so）；
 * - Init：从 exports 的 OH_NATIVE_XCOMPONENT_OBJ 取 OH_NativeXComponent*，注册
 *   surface 回调（Created/Changed/Destroyed）与 OnFrame 帧回调（API 11+，vsync 驱动）；
 * - 全部打点走 hilog tag WotaGL / domain 0x0000。
 */
#include <napi/native_api.h>
#include <ace/xcomponent/native_interface_xcomponent.h>
#include <hilog/log.h>

#include <string>

#include "arc_repair.h"
#include "avscreen_probe.h"
#include "clip_exporter.h"
#include "codec_recorder.h"
#include "gl_render.h"

#define WOTA_LOG_DOMAIN 0x0000
#define WOTA_LOG_TAG "WotaGL"

/** 当前持有 OnFrame 注册的 XComponent 实例（页面重进 = 新实例，Created 时按指针差异补注册） */
static OH_NativeXComponent* g_frameComponent = nullptr;

static void EnsureFrameCallback(OH_NativeXComponent* component);

static void OnSurfaceCreatedCB(OH_NativeXComponent* component, void* window)
{
    uint64_t width = 0;
    uint64_t height = 0;
    OH_NativeXComponent_GetXComponentSize(component, window, &width, &height);
    bool ok = WotaGlInit(window, width, height);
    // 防御层：按实例指针差异补注册（契约见 EnsureFrameCallback 注释，不依赖 Init 一定重跑）
    EnsureFrameCallback(component);
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
        "surface created %{public}llux%{public}llu gl=%{public}d",
        static_cast<unsigned long long>(width), static_cast<unsigned long long>(height), ok ? 1 : 0);
}

static void OnSurfaceChangedCB(OH_NativeXComponent* component, void* window)
{
    uint64_t width = 0;
    uint64_t height = 0;
    OH_NativeXComponent_GetXComponentSize(component, window, &width, &height);
    WotaGlResize(width, height);
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
        "surface changed %{public}llux%{public}llu",
        static_cast<unsigned long long>(width), static_cast<unsigned long long>(height));
}

static void OnSurfaceDestroyedCB(OH_NativeXComponent* component, void* window)
{
    (void)window;
    // OnFrame 随 surface 销毁注销（重进页是新 XComponent 实例：Created 时补注册）
    OH_NativeXComponent_UnregisterOnFrameCallback(component);
    if (component == g_frameComponent) {
        g_frameComponent = nullptr;
    }
    WotaGlDestroy();
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "surface destroyed");
}

static void OnFrameCB(OH_NativeXComponent* component, uint64_t timestamp, uint64_t targetTimestamp)
{
    (void)targetTimestamp;
    // 守卫：旧实例的迟到帧不驱动新实例的 GL
    if (component != g_frameComponent) {
        return;
    }
    WotaGlRender(timestamp);
}

/**
 * 保证「当前 XComponent 实例」持有 OnFrame 注册。
 * 契约（单一来源）：XComponent 每次创建（首进/重进）都走加载路径并以带 OH_NATIVE_XCOMPONENT_OBJ
 * 的 exports 调 Init 完成 surface/OnFrame 注册；本函数在 Created 里按指针差异补注册是**防御层**，
 * 不依赖 Init 一定重跑（两种时序下都不会漏注册/重复注册）。
 */
static void EnsureFrameCallback(OH_NativeXComponent* component)
{
    if (component == g_frameComponent) {
        return;
    }
    if (OH_NativeXComponent_RegisterOnFrameCallback(component, OnFrameCB) != 0) {
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "register frame callback failed");
        return;
    }
    g_frameComponent = component;
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "frame callback registered");
}

static OH_NativeXComponent_Callback g_surfaceCallback;

/** ArkTS 出口：相机 PreviewOutput 用的 native surfaceId（未就绪返回空串，调用方轮询） */
static napi_value GetInputSurfaceId(napi_env env, napi_callback_info info)
{
    (void)info;
    std::string surfaceId = WotaGlGetInputSurfaceId();
    napi_value result = nullptr;
    napi_create_string_utf8(env, surfaceId.c_str(), surfaceId.size(), &result);
    return result;
}

/** ArkTS 出口：NativeImage 失败原因（空串 = 正常/尚未失败；页面据此结束轮询并显示原因） */
static napi_value GetNativeImageError(napi_env env, napi_callback_info info)
{
    (void)info;
    std::string error = WotaGlGetNativeImageError();
    napi_value result = nullptr;
    napi_create_string_utf8(env, error.c_str(), error.size(), &result);
    return result;
}

// ---------------------------------------------------------------- GL-2 参数入口（只写状态，渲染线程内生效）

static napi_value SetDisplayGeometry(napi_env env, napi_callback_info info)
{
    size_t argc = 3;
    napi_value argv[3] = {nullptr, nullptr, nullptr};
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
    int32_t sensorDeg = 0;
    int32_t displayDeg = 0;
    bool mirrored = false;
    if (argc >= 3) {
        napi_get_value_int32(env, argv[0], &sensorDeg);
        napi_get_value_int32(env, argv[1], &displayDeg);
        napi_get_value_bool(env, argv[2], &mirrored);
    }
    WotaGlSetDisplayGeometry(sensorDeg, displayDeg, mirrored);
    return nullptr;
}

/** WLB：上屏等比收缩因子（1,1 = 铺满；编码面不受影响） */
static napi_value SetDisplayFit(napi_env env, napi_callback_info info)
{
    size_t argc = 2;
    napi_value argv[2] = {nullptr, nullptr};
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
    double fitX = 1.0;
    double fitY = 1.0;
    if (argc >= 2) {
        napi_get_value_double(env, argv[0], &fitX);
        napi_get_value_double(env, argv[1], &fitY);
    }
    WotaGlSetDisplayFit(static_cast<float>(fitX), static_cast<float>(fitY));
    return nullptr;
}

static napi_value SetEffect(napi_env env, napi_callback_info info)
{
    size_t argc = 1;
    napi_value argv[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
    int32_t effect = 0;
    if (argc >= 1) {
        napi_get_value_int32(env, argv[0], &effect);
    }
    WotaGlSetEffect(effect);
    return nullptr;
}

static napi_value SetCurveEnabled(napi_env env, napi_callback_info info)
{
    size_t argc = 1;
    napi_value argv[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
    bool on = false;
    if (argc >= 1) {
        napi_get_value_bool(env, argv[0], &on);
    }
    WotaGlSetCurveEnabled(on);
    return nullptr;
}

static napi_value SetZebraParams(napi_env env, napi_callback_info info)
{
    size_t argc = 2;
    napi_value argv[2] = {nullptr, nullptr};
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
    double threshold = 0.6;
    int32_t density = 1;
    if (argc >= 2) {
        napi_get_value_double(env, argv[0], &threshold);
        napi_get_value_int32(env, argv[1], &density);
    }
    WotaGlSetZebraParams(static_cast<float>(threshold), density);
    return nullptr;
}

static napi_value SetPeakingParams(napi_env env, napi_callback_info info)
{
    size_t argc = 2;
    napi_value argv[2] = {nullptr, nullptr};
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
    double strength = 0.6;
    int32_t colorIndex = 4;
    if (argc >= 2) {
        napi_get_value_double(env, argv[0], &strength);
        napi_get_value_int32(env, argv[1], &colorIndex);
    }
    WotaGlSetPeakingParams(static_cast<float>(strength), colorIndex);
    return nullptr;
}

/** 曲线 LUT：ArrayBuffer（768B = 256×1 交错 RGB）→ 原生静态缓冲，渲染线程内上传纹理 */
static napi_value UploadCurveLut(napi_env env, napi_callback_info info)
{
    size_t argc = 1;
    napi_value argv[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
    bool accepted = false;
    if (argc >= 1) {
        void* data = nullptr;
        size_t length = 0;
        if (napi_get_arraybuffer_info(env, argv[0], &data, &length) == napi_ok) {
            accepted = WotaGlUploadCurveLut(data, length);
        }
    }
    napi_value result = nullptr;
    napi_get_boolean(env, accepted, &result);
    return result;
}

/** 毛玻璃全屏链开关（GL-3） */
static napi_value SetFrostEnabled(napi_env env, napi_callback_info info)
{
    size_t argc = 1;
    napi_value argv[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
    bool on = false;
    if (argc >= 1) {
        napi_get_value_bool(env, argv[0], &on);
    }
    WotaGlSetFrostEnabled(on);
    return nullptr;
}

// ---------------------------------------------------------------- Glow/降噪（2026-10-28 批）

/**
 * Glow 参数：enabled 0/1，threshold/soft/width/gain/add/freq/blend 为 0..1 域内浮点，
 * colorA/colorB 为 ARGB 数值（uint32）。缺参按出厂默认（与既有 setter 的 all-or-nothing 同款）。
 */
static napi_value SetGlowParams(napi_env env, napi_callback_info info)
{
    size_t argc = 10;
    napi_value argv[10] = {nullptr, nullptr, nullptr, nullptr, nullptr,
        nullptr, nullptr, nullptr, nullptr, nullptr};
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
    int32_t enabled = 0;
    double threshold = 0.5;
    double soft = 0.05;
    double width = 0.02;
    double gain = 1.0;
    double add = 0.0;
    double freq = 0.0;
    double blend = 0.6;
    uint32_t colorA = 0xFFFF3399u;
    uint32_t colorB = 0xFF33E6FFu;
    if (argc >= 10) {
        napi_get_value_int32(env, argv[0], &enabled);
        napi_get_value_double(env, argv[1], &threshold);
        napi_get_value_double(env, argv[2], &soft);
        napi_get_value_double(env, argv[3], &width);
        napi_get_value_double(env, argv[4], &gain);
        napi_get_value_double(env, argv[5], &add);
        napi_get_value_double(env, argv[6], &freq);
        napi_get_value_double(env, argv[7], &blend);
        napi_get_value_uint32(env, argv[8], &colorA);
        napi_get_value_uint32(env, argv[9], &colorB);
    }
    WotaGlSetGlowParams(enabled, static_cast<float>(threshold), static_cast<float>(soft),
        static_cast<float>(width), static_cast<float>(gain), static_cast<float>(add),
        static_cast<float>(freq), static_cast<float>(blend), colorA, colorB);
    return nullptr;
}

/** Glow 状态串：off 或 on:<broken0/1>:<scale>:<inW>:<inH>:<fps>:<rtW>:<rtH>:<clamped0/1> */
static napi_value GetGlowStatus(napi_env env, napi_callback_info info)
{
    (void)info;
    std::string status = WotaGlGetGlowStatus();
    napi_value result = nullptr;
    napi_create_string_utf8(env, status.c_str(), status.size(), &result);
    return result;
}

/** 画面降噪档：0=关 / 1=完整核（9 采样）/ 2=轻量核（5 采样）；0 时槽键归零、下一帧回收变体 */
static napi_value SetDenoiseLevel(napi_env env, napi_callback_info info)
{
    size_t argc = 1;
    napi_value argv[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
    int32_t level = 0;
    if (argc >= 1) {
        napi_get_value_int32(env, argv[0], &level);
    }
    WotaGlSetDenoiseLevel(level);
    return nullptr;
}

// ---------------------------------------------------------------- GL-4 GPU 录制（封装归 CodecRecorder，编码面归 gl_render）

static napi_value MakeString(napi_env env, const std::string& text)
{
    napi_value result = nullptr;
    napi_create_string_utf8(env, text.c_str(), text.size(), &result);
    return result;
}

/** 起录：fd 归 ArkTS（native 不 close）；空串 = 成功，非空 = 机器码。arcMode 0=off/1=MEND/2=DROP */
static napi_value StartGpuRecord(napi_env env, napi_callback_info info)
{
    size_t argc = 8;
    napi_value argv[8] = {nullptr, nullptr, nullptr, nullptr, nullptr, nullptr, nullptr, nullptr};
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
    if (argc < 8) {
        return MakeString(env, "bad-args");
    }
    int32_t fd = -1;
    int32_t width = 0;
    int32_t height = 0;
    int32_t fps = 0;
    int32_t bitrate = 0;
    int32_t arcMode = 0;
    int32_t arcDstFps = 0;
    int32_t deadband = 12;
    napi_get_value_int32(env, argv[0], &fd);
    napi_get_value_int32(env, argv[1], &width);
    napi_get_value_int32(env, argv[2], &height);
    napi_get_value_int32(env, argv[3], &fps);
    napi_get_value_int32(env, argv[4], &bitrate);
    napi_get_value_int32(env, argv[5], &arcMode);
    napi_get_value_int32(env, argv[6], &arcDstFps);
    napi_get_value_int32(env, argv[7], &deadband);
    // 先 Start：busy 时不得改写 active 录制的弧状态（审查 P3）；成功后再配置弧档
    std::string error = CodecRecorder::Instance().Start(fd, width, height, fps, bitrate,
        WotaGlEncoderRotation());
    if (!error.empty()) {
        return MakeString(env, error);
    }
    WotaGlSetArcConvert(arcMode, arcDstFps, deadband);
    // 编码器原生窗口 → EGL 编码面（失败即中止录制；预览/效果不受影响）
    OHNativeWindow* window = CodecRecorder::Instance().EncoderWindow();
    if (window == nullptr || !WotaGlAttachEncoderWindow(window, width, height)) {
        CodecRecorder::Instance().Abort("encoder-surface");
        WotaGlSetArcConvert(0, 0, 0);
        return MakeString(env, "encoder-surface");
    }
    return MakeString(env, "");
}

/** 停录：返回摘要（ok samples=N eos=0/1 或错误串）；native 不 close fd */
static napi_value StopGpuRecord(napi_env env, napi_callback_info info)
{
    (void)info;
    return MakeString(env, CodecRecorder::Instance().Stop());
}

static napi_value IsGpuRecording(napi_env env, napi_callback_info info)
{
    (void)info;
    napi_value result = nullptr;
    napi_get_boolean(env, CodecRecorder::Instance().IsRecording(), &result);
    return result;
}

/** 最近一次录制错误（空串 = 无）；surface 销毁路径的 surface-lost 可在此取回 */
static napi_value GetGpuRecordError(napi_env env, napi_callback_info info)
{
    (void)info;
    return MakeString(env, CodecRecorder::Instance().LastError());
}

// ---------------------------------------------------------------- GL-4 光弧链在线版

/** 停录前冲刷 MEND 滞留帧（1 = 已发出末帧） */
static napi_value FlushArcPending(napi_env env, napi_callback_info info)
{
    (void)info;
    napi_value result = nullptr;
    napi_create_int32(env, WotaGlFlushArcPending(), &result);
    return result;
}

/** 被抽帧位次紧凑串 "k,n;k,n"（取走即清） */
static napi_value DrainArcDrops(napi_env env, napi_callback_info info)
{
    (void)info;
    return MakeString(env, WotaGlDrainArcDrops());
}

/** 实测源帧率（0 = 样本不足） */
static napi_value MeasuredArcSrcFps(napi_env env, napi_callback_info info)
{
    (void)info;
    napi_value result = nullptr;
    napi_create_int32(env, WotaGlMeasuredArcSrcFps(), &result);
    return result;
}

/** 已成功写入 muxer 的字节累计（轮转触发量；Stop 后 0） */
static napi_value GetGpuWrittenBytes(napi_env env, napi_callback_info info)
{
    (void)info;
    napi_value result = nullptr;
    napi_create_double(env, static_cast<double>(CodecRecorder::Instance().GetWrittenBytes()), &result);
    return result;
}

/** 录制健康："trackAdded,muxStarted,samples,fmt"（fmt = onStreamChanged 真信号 0/1；ArkTS 侧容 3 字段旧序防御） */
static napi_value GetGpuHealth(napi_env env, napi_callback_info info)
{
    (void)info;
    const HealthSnapshot snapshot = CodecRecorder::Instance().GetHealthSnapshot();
    std::string health = std::string(snapshot.trackAdded ? "1" : "0") + "," +
        (snapshot.muxStarted ? "1" : "0") + "," + std::to_string(snapshot.samples) + "," +
        (snapshot.formatChanged ? "1" : "0");
    return MakeString(env, health);
}

/** 原生帧账计数 "glCallbacks,glConsumed,swapOk,swapFail,arcDropped" */
static napi_value GetGpuFrameCounters(napi_env env, napi_callback_info info)
{
    (void)info;
    return MakeString(env, WotaGlGetFrameCounters());
}


/** 当前实际运行档（0/1/2；MEND 降级后为 2） */
static napi_value GetArcEffectiveMode(napi_env env, napi_callback_info info)
{
    (void)info;
    napi_value result = nullptr;
    napi_create_int32(env, WotaGlGetArcEffectiveMode(), &result);
    return result;
}

// ---------------------------------------------------------------- T20 A/B 无损剪辑导出

/** 启动 remux：fd 归 ArkTS（native 不 close）；空串 = 已入队，busy/args/worker = 立即失败 */
/** T27-1 平台探针：启动 AVScreenCapture 取证（空串=入队；busy/args=失败） */
static napi_value StartAvScreenProbe(napi_env env, napi_callback_info info)
{
    size_t argc = 1;
    napi_value argv[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
    int32_t durationMs = 2500;
    if (argc >= 1) {
        napi_get_value_int32(env, argv[0], &durationMs);
    }
    return MakeString(env, AvScreenProbe::Instance().Start(durationMs));
}

/** 探针状态串：idle | running:<elapsedMs> | ok:<字段> | failed:<code> | cancelled */
static napi_value GetAvScreenProbeStatus(napi_env env, napi_callback_info info)
{
    (void)info;
    return MakeString(env, AvScreenProbe::Instance().Status());
}

static napi_value CancelAvScreenProbe(napi_env env, napi_callback_info info)
{
    (void)env;
    (void)info;
    AvScreenProbe::Instance().Cancel();
    return nullptr;
}

static napi_value StartClipExport(napi_env env, napi_callback_info info)
{
    // 第 7 位 audioShiftUs 可选：老调用点（6 参）按 0 处理，避免 ABI 断裂
    size_t argc = 7;
    napi_value argv[7] = {nullptr, nullptr, nullptr, nullptr, nullptr, nullptr, nullptr};
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
    if (argc < 6) {
        return MakeString(env, "args");
    }
    int32_t inFd = -1;
    int64_t inSize = 0;
    int32_t outFd = -1;
    int64_t startMs = 0;
    int64_t endMs = 0;
    int32_t audioOrdinal = -1;
    int64_t audioShiftUs = 0;
    napi_get_value_int32(env, argv[0], &inFd);
    napi_get_value_int64(env, argv[1], &inSize);
    napi_get_value_int32(env, argv[2], &outFd);
    napi_get_value_int64(env, argv[3], &startMs);
    napi_get_value_int64(env, argv[4], &endMs);
    napi_get_value_int32(env, argv[5], &audioOrdinal);
    if (argc >= 7) {
        napi_get_value_int64(env, argv[6], &audioShiftUs);
    }
    return MakeString(env, ClipExporter::Instance().Start(inFd, inSize, outFd, startMs, endMs,
        audioOrdinal, audioShiftUs));
}

/** idle | running:<pct> | done:<base>:<v>:<a>[:audio=skipped] | failed:<code> | cancelled */
static napi_value GetClipExportStatus(napi_env env, napi_callback_info info)
{
    (void)info;
    return MakeString(env, ClipExporter::Instance().Status());
}

static napi_value CancelClipExport(napi_env env, napi_callback_info info)
{
    (void)info;
    (void)env;
    ClipExporter::Instance().Cancel();
    return nullptr;
}

/** 只读轨探测："videoCount,audioCount"；失败 "err:<code>" */
static napi_value ProbeClipTracks(napi_env env, napi_callback_info info)
{
    size_t argc = 2;
    napi_value argv[2] = {nullptr, nullptr};
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
    if (argc < 2) {
        return MakeString(env, "err:source");
    }
    int32_t inFd = -1;
    int64_t inSize = 0;
    napi_get_value_int32(env, argv[0], &inFd);
    napi_get_value_int64(env, argv[1], &inSize);
    return MakeString(env, ClipExporter::Instance().ProbeTracks(inFd, inSize));
}

// ---------------------------------------------------------------- T29 离线光弧修复（CPU 执行器核心）

/** 只读探测：ok 串或 err:<code>（串格式见 record/ArcRepairSessionModel.ets 头注） */
static napi_value ProbeArcRepairSource(napi_env env, napi_callback_info info)
{
    size_t argc = 2;
    napi_value argv[2] = {nullptr, nullptr};
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
    if (argc < 2) {
        return MakeString(env, "err:ARC_SOURCE");
    }
    int32_t inFd = -1;
    int64_t inSize = 0;
    napi_get_value_int32(env, argv[0], &inFd);
    napi_get_value_int64(env, argv[1], &inSize);
    return MakeString(env, ArcRepair::Instance().Probe(inFd, inSize));
}

/** 启动修复：空串 = 已入队；busy/args = 立即失败。fd 归 ArkTS（native 不 close） */
static napi_value StartArcRepair(napi_env env, napi_callback_info info)
{
    // 第 13 位 capOffsetMs 可选：老调用点（12 参）按 0 处理
    size_t argc = 13;
    napi_value argv[13] = {nullptr, nullptr, nullptr, nullptr, nullptr, nullptr,
        nullptr, nullptr, nullptr, nullptr, nullptr, nullptr, nullptr};
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
    if (argc < 12) {
        return MakeString(env, "args");
    }
    int32_t inFd = -1;
    int64_t inSize = 0;
    int32_t outFd = -1;
    int32_t dstFps = 24;
    int32_t bitrate = 0;
    int32_t deadband = 12;
    int64_t srcNum = 30;
    int64_t srcDen = 1;
    int64_t srcFrames = 0;
    int64_t dstFrames = 0;
    int32_t rotation = 0;
    int64_t durationMs = 0;
    int32_t capOffsetMs = 0;
    napi_get_value_int32(env, argv[0], &inFd);
    napi_get_value_int64(env, argv[1], &inSize);
    napi_get_value_int32(env, argv[2], &outFd);
    napi_get_value_int32(env, argv[3], &dstFps);
    napi_get_value_int32(env, argv[4], &bitrate);
    napi_get_value_int32(env, argv[5], &deadband);
    napi_get_value_int64(env, argv[6], &srcNum);
    napi_get_value_int64(env, argv[7], &srcDen);
    napi_get_value_int64(env, argv[8], &srcFrames);
    napi_get_value_int64(env, argv[9], &dstFrames);
    napi_get_value_int32(env, argv[10], &rotation);
    napi_get_value_int64(env, argv[11], &durationMs);
    if (argc >= 13) {
        napi_get_value_int32(env, argv[12], &capOffsetMs);
    }
    return MakeString(env, ArcRepair::Instance().Start(inFd, inSize, outFd, dstFps, bitrate,
        deadband, srcNum, srcDen, srcFrames, dstFrames, rotation, durationMs, capOffsetMs));
}

/** idle | running:<pct> | done:<v>:<a>:<mux>:<track>:<stop> | failed:<code> | cancelled */
static napi_value GetArcRepairStatus(napi_env env, napi_callback_info info)
{
    (void)info;
    return MakeString(env, ArcRepair::Instance().Status());
}

static napi_value CancelArcRepair(napi_env env, napi_callback_info info)
{
    (void)info;
    (void)env;
    ArcRepair::Instance().Cancel();
    return nullptr;
}

/*
 * 模块初始化契约（P2 顺序风险修复）：
 * - XComponent 带 libraryname 创建时会加载本库，并以**带 OH_NATIVE_XCOMPONENT_OBJ 的 exports**
 *   调用 Init —— 这条路径完成 surface 回调 + OnFrame 注册（首进/重进页皆由 XComponent 触发加载）；
 * - ArkTS 侧**不得在页面顶层静态 import 本模块**：那会让本模块先以"无 XComponent 对象"的 exports
 *   完成初始化并进模块缓存，XComponent 再创建时不再重跑 Init，surface 回调就永远不注册；
 * - GlPreview 在 XComponent onLoad 之后才 dynamic import 桥模块（camera/GlLibBridge，内含 .so 静态导入），
 *   届时 XComponent 的加载路径已完成注册，再轮询 getInputSurfaceId；两条路径在代码与日志上均可论证。
 *
 * 线程契约（登记，P3）：下面的 setter 与 OnFrame 回调在 OHOS XComponent 下同为 UI 线程回调，
 * setter 只写请求态、渲染在下一帧消费，无并发；独立渲染线程（GL-3）需先做原子/双缓冲交接。
 */
static napi_value SetHistogramEnabled(napi_env env, napi_callback_info info)
{
    size_t argc = 1;
    napi_value argv[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
    bool on = false;
    if (argc >= 1) {
        napi_get_value_bool(env, argv[0], &on);
    }
    WotaGlSetHistogramEnabled(on);
    return nullptr;
}

/** 直方图快照 → Int32Array(192)（3×64 计数 R/G/B）；无样本返回 null（ArkTS 侧按无数据绘制） */
static napi_value GetHistogramSnapshot(napi_env env, napi_callback_info info)
{
    int32_t bins[192];
    if (!WotaGlGetHistogramSnapshot(bins, 192)) {
        napi_value nullValue = nullptr;
        napi_get_null(env, &nullValue);
        return nullValue;
    }
    napi_value out = nullptr;
    void* data = nullptr;
    napi_create_arraybuffer(env, sizeof(bins), &data, &out);
    if (data != nullptr) {
        memcpy(data, bins, sizeof(bins));
    }
    napi_value typed = nullptr;
    napi_create_typedarray(env, napi_int32_array, 192, out, 0, &typed);
    return typed;
}

static napi_value Init(napi_env env, napi_value exports)
{
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "module init");
    // 先导出 napi 能力：ArkTS 侧 import from 'entry' 的模块实例可能没有 XComponent 对象，
    // 不能因为 unwrap 失败就把 getInputSurfaceId 一起丢掉
    napi_property_descriptor desc[] = {
        {"getInputSurfaceId", nullptr, GetInputSurfaceId, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"getNativeImageError", nullptr, GetNativeImageError, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"setDisplayGeometry", nullptr, SetDisplayGeometry, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"setDisplayFit", nullptr, SetDisplayFit, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"setEffect", nullptr, SetEffect, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"setCurveEnabled", nullptr, SetCurveEnabled, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"setZebraParams", nullptr, SetZebraParams, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"setPeakingParams", nullptr, SetPeakingParams, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"setHistogramEnabled", nullptr, SetHistogramEnabled, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"getHistogramSnapshot", nullptr, GetHistogramSnapshot, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"uploadCurveLut", nullptr, UploadCurveLut, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"setFrostEnabled", nullptr, SetFrostEnabled, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"setGlowParams", nullptr, SetGlowParams, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"getGlowStatus", nullptr, GetGlowStatus, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"setDenoiseLevel", nullptr, SetDenoiseLevel, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"startGpuRecord", nullptr, StartGpuRecord, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"stopGpuRecord", nullptr, StopGpuRecord, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"isGpuRecording", nullptr, IsGpuRecording, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"getGpuRecordError", nullptr, GetGpuRecordError, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"flushArcPending", nullptr, FlushArcPending, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"drainArcDrops", nullptr, DrainArcDrops, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"measuredArcSrcFps", nullptr, MeasuredArcSrcFps, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"getGpuFrameCounters", nullptr, GetGpuFrameCounters, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"getGpuWrittenBytes", nullptr, GetGpuWrittenBytes, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"getGpuHealth", nullptr, GetGpuHealth, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"getArcEffectiveMode", nullptr, GetArcEffectiveMode, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"startAvScreenProbe", nullptr, StartAvScreenProbe, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"getAvScreenProbeStatus", nullptr, GetAvScreenProbeStatus, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"cancelAvScreenProbe", nullptr, CancelAvScreenProbe, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"startClipExport", nullptr, StartClipExport, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"getClipExportStatus", nullptr, GetClipExportStatus, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"cancelClipExport", nullptr, CancelClipExport, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"probeClipTracks", nullptr, ProbeClipTracks, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"probeArcRepairSource", nullptr, ProbeArcRepairSource, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"startArcRepair", nullptr, StartArcRepair, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"getArcRepairStatus", nullptr, GetArcRepairStatus, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"cancelArcRepair", nullptr, CancelArcRepair, nullptr, nullptr, nullptr, napi_default, nullptr},
    };
    napi_define_properties(env, exports, sizeof(desc) / sizeof(desc[0]), desc);

    napi_value exportInstance = nullptr;
    if (napi_get_named_property(env, exports, OH_NATIVE_XCOMPONENT_OBJ, &exportInstance) != napi_ok) {
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "get XComponent obj failed (page not bound?)");
        return exports;
    }
    OH_NativeXComponent* component = nullptr;
    if (napi_unwrap(env, exportInstance, reinterpret_cast<void**>(&component)) != napi_ok ||
        component == nullptr) {
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "unwrap XComponent failed");
        return exports;
    }
    g_surfaceCallback.OnSurfaceCreated = OnSurfaceCreatedCB;
    g_surfaceCallback.OnSurfaceChanged = OnSurfaceChangedCB;
    g_surfaceCallback.OnSurfaceDestroyed = OnSurfaceDestroyedCB;
    g_surfaceCallback.DispatchTouchEvent = nullptr;
    if (OH_NativeXComponent_RegisterCallback(component, &g_surfaceCallback) != 0) {
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "register surface callback failed");
    }
    EnsureFrameCallback(component);
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "xcomponent callbacks registered");
    return exports;
}

static napi_module g_entryModule = {
    .nm_version = 1,
    .nm_flags = 0,
    .nm_filename = nullptr,
    .nm_register_func = Init,
    .nm_modname = "entry",
    .nm_priv = nullptr,
    .reserved = {0},
};

extern "C" __attribute__((constructor)) void RegisterEntryModule(void)
{
    napi_module_register(&g_entryModule);
}
