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

/** 录制健康三格："trackAdded,muxStarted,samples" */
static napi_value GetGpuHealth(napi_env env, napi_callback_info info)
{
    (void)info;
    std::string health = std::string(CodecRecorder::Instance().IsVideoTrackAdded() ? "1" : "0") + "," +
        (CodecRecorder::Instance().IsMuxStarted() ? "1" : "0") + "," +
        std::to_string(CodecRecorder::Instance().SampleCount());
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
static napi_value Init(napi_env env, napi_value exports)
{
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "module init");
    // 先导出 napi 能力：ArkTS 侧 import from 'entry' 的模块实例可能没有 XComponent 对象，
    // 不能因为 unwrap 失败就把 getInputSurfaceId 一起丢掉
    napi_property_descriptor desc[] = {
        {"getInputSurfaceId", nullptr, GetInputSurfaceId, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"getNativeImageError", nullptr, GetNativeImageError, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"setDisplayGeometry", nullptr, SetDisplayGeometry, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"setEffect", nullptr, SetEffect, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"setCurveEnabled", nullptr, SetCurveEnabled, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"setZebraParams", nullptr, SetZebraParams, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"setPeakingParams", nullptr, SetPeakingParams, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"uploadCurveLut", nullptr, UploadCurveLut, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"setFrostEnabled", nullptr, SetFrostEnabled, nullptr, nullptr, nullptr, napi_default, nullptr},
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
