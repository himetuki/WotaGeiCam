/*
 * GL-2 监视类效果渲染管线（对齐上游 38d712a 的移植）：
 * - 着色器：shaders.h 承载上游 TEXTURE_VS / PASS_THROUGH_FS / CURVE_FS / zebra / peaking（逐字生成器）；
 * - 几何：buildTexCoords 逐行移植（deg = normalize(sensor − display + stRot)；镜像是屏幕空间 x=-x）；
 *   stRotationFromMatrix 逐行移植 PreviewOrientation（列主序仿射部分与四种 k 复合比对教科书归一化矩阵）；
 * - 曲线：CurveStack.bakeBytes 的 256×1 交错 RGB（768B）→ GL_RGB 纹理（unit 2），与 core 同一契约；
 * - 效果：单效果槽（NONE/ZEBRA/PEAKING）照上游退避链：曲线版失败退无曲线版；效果再失败退直通并
 *   黑名单该效果；直通程序是最后兜底（不带任何新特性）；
 * - 斑马纹瓦片：64×64 RGBA8、周期 32、占空 0.25/0.35/0.50、条纹 0xFFE53935、平铺 4/8/16（ZebraPattern 逐项）；
 * - 相机帧经 OH_NativeImage 落 GL_TEXTURE_EXTERNAL_OES；渲染在 OnFrame（vsync）内同步绘制；
 * - EGL config 去 alpha fallback；ES3 优先、失败退 ES2；新 window ≠ 旧 surface 时整体重建；
 * - FBO 类 pass（PASS_THROUGH_2D/ARC_MERGE/Frost/NtscGrid）与独立渲染线程留待 GL-3。
 *
 * 线程契约（登记，P3）：OnFrame 回调与 NAPI setter（ArkTS UI 线程）在 OHOS XComponent 下同为
 * UI 线程回调——setter 只写请求态，渲染线程在下一帧消费；g_curveData 的 768B memcpy 与纹理上传
 * 因此无并发。若 GL-3 引入独立渲染线程，必须先把请求态/曲线数据改为原子或双缓冲交接（登记为先决条件）。
 */
#include "gl_render.h"
#include "shaders.h"
#include "codec_recorder.h"
#include "wota_math.h"
#include "arc_flow.h"
#include "arc_mend_pass.h"

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h> // GL_TEXTURE_EXTERNAL_OES
#include <hilog/log.h>
#include <native_image/native_image.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#define WOTA_LOG_DOMAIN 0x0000
#define WOTA_LOG_TAG "WotaGL"

#ifndef EGL_OPENGL_ES3_BIT
#define EGL_OPENGL_ES3_BIT EGL_OPENGL_ES3_BIT_KHR
#endif

namespace {
constexpr float kPi = 3.14159265358979323846f;
constexpr float kCanonicalEps = 1e-3f;
constexpr int kEffectNone = 0;
constexpr int kEffectZebra = 1;
constexpr int kEffectPeaking = 2;

// ---- 2026-10-28 Glow/降噪批：上游 Shaders.kt / core/Glow.kt / core/Denoise.kt 的常量口径 ----
/** Glow 图的取样单元（与既有 0=OES / 1=条纹 / 2=曲线错开；ES2 保证 MAX_TEXTURE_IMAGE_UNITS ≥ 8） */
constexpr int kGlowSrcUnit = 5;
/** 连续这么多帧 GL 报错就整条 Glow 链停用（上游 GlowBlurChain.GLOW_MAX_ERROR_STREAK） */
constexpr int kGlowMaxErrorStreak = 8;
/** 判档帧率未知时的兜底（上游 glowStatus 的「还没绑定编码面时帧率按 30 估」） */
constexpr int kGlowFpsFallback = 30;
/** Glow 分档阈值（像素 × 帧率）：≤40M 用 1、≤150M 用 2、否则 4（上游 core/Glow.kt） */
constexpr int64_t kGlowFullMaxPixelRate = 40000000LL;
constexpr int64_t kGlowHalfMaxPixelRate = 150000000LL;
/** 降噪亮差权重的 σ（上游 GlRenderEngine.denoiseSigmaR） */
constexpr float kDenoiseSigmaR = 0.08f;

EGLDisplay g_display = EGL_NO_DISPLAY;
EGLSurface g_surface = EGL_NO_SURFACE;
EGLContext g_context = EGL_NO_CONTEXT;
EGLConfig g_eglConfig = nullptr;
uint64_t g_width = 0;
uint64_t g_height = 0;
int g_clientVersion = 3;
bool g_useVao = false;
bool g_ready = false;
bool g_firstFrameLogged = false;
/** 当前 EGLSurface 对应的 native window（新 window 与旧 surface 不匹配时整体重建） */
void* g_window = nullptr;

// 几何：position 与 texcoord 各一条 VBO（texcoord 随方向实时重算）
GLuint g_vao = 0;
GLuint g_posVbo = 0;
GLuint g_uvVbo = 0;
const float kQuadPositions[8] = {-1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f};
int g_sensorDeg = 0;
int g_displayDeg = 0;
int g_stRotDeg = 0;
bool g_mirrored = false;
int g_geomKey = -1;
/**
 * WLB 批（letterbox）：上屏四边形的等比收缩因子（NDC 半域，1,1 = 铺满 = 旧行为逐字相同）。
 * 由 ArkTS 侧按「预览档尺寸 vs 表面尺寸 vs 旋转」算好后经 setDisplayFit 下发；
 * **编码面恒传 (1,1)**——成片是满幅画面，黑边只属于预览显示。
 */
float g_fitX = 1.0f;
float g_fitY = 1.0f;

// ---- GL-4 编码面（GPU 录制）：第二个 EGL window surface + 编码专用 UV ----
EGLSurface g_encSurface = EGL_NO_SURFACE;
int g_encW = 0;
int g_encH = 0;
GLuint g_encUvVbo = 0;
int g_encGeomKey = -1;
int g_encFailStreak = 0;
bool g_encFirstFrameLogged = false;
/** 编码 pass 恢复上屏 makeCurrent 失败：下一帧开头先重试绑定，未成功前禁止任何绘制 */
bool g_displayCtxLost = false;
constexpr int kDisplayCtxRetryCap = 60;
int g_displayCtxLostRetries = 0;
bool g_displayCtxAbortIssued = false;

// ---- GL-4 光弧链在线版（ARC_MERGE 补弧 + 抽帧 + sidecar/帧账）----
constexpr int kArcModeOff = 0;
constexpr int kArcModeMend = 1;
constexpr int kArcModeDrop = 2;
constexpr int kArcDeadbandMax = 32;
constexpr int64_t kTsUnitToleranceNs = 10LL * 1000 * 1000 * 1000; // 10s

struct ArcDropMark {
    int32_t out;
    int32_t n;
};

int g_arcRequestedMode = kArcModeOff; // 用户请求档（sidecar 按它写 mend/drop）
int g_arcEffectiveMode = kArcModeOff; // 实际运行档（MEND 失败降 DROP）
int g_arcDstFps = 0;
int g_arcDeadband = 12;
bool g_arcActive = false;
ArcKeepRule g_arcRule;
ArcRepairFlow g_arcFlow(24);
std::unique_ptr<ArcMendPass> g_arcMend;
std::vector<ArcDropMark> g_arcDrops;
int32_t g_encoderFrameIndex = 0;
int64_t g_arcSrcFirstTsNs = INT64_MIN;
int64_t g_arcSrcLastTsNs = INT64_MIN;
int64_t g_arcSrcFrames = 0;
int64_t g_lastArcTsNs = INT64_MIN;
int g_arcTsLogCount = 0;

// 原生帧账计数器（T6；不清零，ArkTS 起停两次快照做差）
std::atomic<int32_t> g_glCallbacks{0};
std::atomic<int32_t> g_glConsumed{0};
std::atomic<int32_t> g_encoderSwapOk{0};
std::atomic<int32_t> g_encoderSwapFail{0};
std::atomic<int32_t> g_arcDropped{0};

int64_t NowSteadyNs()
{
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

bool NearBy10s(int64_t a, int64_t b)
{
    const int64_t diff = a > b ? a - b : b - a;
    return diff <= kTsUnitToleranceNs;
}

/**
 * 相机帧时间戳归一：NativeImage → OnFrame 入参 → steady clock 三级回退；
 * 与 steady clock 相差 >10s 但 ×1000 后回到 10s 内时按微秒解释（打一条日志）。
 */
int64_t NormalizeArcTimestamp(int64_t nativeTs, uint64_t onFrameTs)
{
    static bool unitLogged = false;
    int64_t ts = nativeTs;
    if (ts <= 0) {
        ts = static_cast<int64_t>(onFrameTs);
    }
    if (ts <= 0) {
        ts = NowSteadyNs();
    }
    const int64_t now = NowSteadyNs();
    if (!NearBy10s(ts, now)) {
        // 单位探测：ns → µs(×1000) → ms(×1_000_000)；上界护栏避免乘溢出
        if (ts < 9000000000000000LL && NearBy10s(ts * 1000, now)) {
            if (!unitLogged) {
                unitLogged = true;
                OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                    "arc timestamp appears microsecond-based, scale x1000 (ts=%{public}lld)",
                    static_cast<long long>(ts));
            }
            ts *= 1000;
        } else if (ts < 9000000000000LL && NearBy10s(ts * 1000000, now)) {
            if (!unitLogged) {
                unitLogged = true;
                OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                    "arc timestamp appears millisecond-based, scale x1e6 (ts=%{public}lld)",
                    static_cast<long long>(ts));
            }
            ts *= 1000000;
        } else {
            // 量级/时基不可解释：退 steady clock（保证单调与 ns 口径），只记一条
            if (!unitLogged) {
                unitLogged = true;
                OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                    "arc timestamp implausible (ts=%{public}lld), fallback steady clock",
                    static_cast<long long>(ts));
            }
            ts = now;
        }
    }
    return ts;
}

void NoteArcFrame(int64_t tsNs)
{
    if (g_arcSrcFirstTsNs == INT64_MIN) {
        g_arcSrcFirstTsNs = tsNs;
    }
    g_arcSrcLastTsNs = tsNs;
    g_arcSrcFrames++;
}

/** 程序槽与 location 缓存（单效果槽：同一时刻只有一个效果 program） */
struct ProgramLocs {
    GLuint program = 0;
    GLint aPosition = -1;
    GLint aTexCoord = -1;
    GLint uTexMatrix = -1;
    GLint uFit = -1;
    GLint uFrame = -1;
    GLint uCurve = -1;
    GLint uStripe = -1;
    GLint uThreshold = -1;
    GLint uTile = -1;
    GLint uTexel = -1;
    GLint uStrength = -1;
    GLint uColor = -1;
    // 2026-10-28 Glow/降噪批：双边降噪（uSigmaR + 峰值同场时的 uDenoiseTexel）与 Glow 段
    GLint uSigmaR = -1;
    GLint uDenoiseTexel = -1;
    GLint uGlowSrc = -1;
    GLint uGlowGain = -1;
    GLint uGlowAdd = -1;
    GLint uGlowFreq = -1;
    GLint uGlowBlend = -1;
    GLint uGlowColorA = -1;
    GLint uGlowColorB = -1;
};
ProgramLocs g_passLocs;   // 直通（链接失败即无画面，永不删除）
ProgramLocs g_slotLocs;   // 上屏效果槽（曲线/斑马/峰值之一）
int g_slotEffect = kEffectNone;
bool g_slotCurve = false;
/**
 * 上屏槽的降噪档（槽键 (effect, curve, denoise) 的第三维，2026-10-28 批）：
 * 记**实际编出来**的档（回退链降档后可能与请求不同）；编码槽同理独立一份。
 */
int g_slotDenoise = WOTA_DENOISE_OFF;
// 编码槽（上游 46d8d91 双 program 槽的 OHOS 对应物）：**成片支路恒 NONE**——监看叠加
// （斑马/峰值）绝不进成片（产品裁决「监看叠加只上屏」）；曲线是创作观感、降噪是成片处理，两槽都带。
// 槽位不进键：编码槽只有 curve/denoise 两个自由度，跟随上屏请求。
ProgramLocs g_encSlotLocs;
bool g_encSlotCurve = false;
int g_encSlotDenoise = WOTA_DENOISE_OFF;

// ---- 2026-10-28 Glow/降噪批：参数与共享资源（scene layer，两槽共享同一张 glowTex）----
/**
 * Glow 参数（上游 core/Glow.kt GlowParams 的 napi 侧形态；颜色用 ARGB 数值，与上游同义）。
 * napi setter 只写这份请求态，渲染线程下一帧消费（本侧 setter 与 OnFrame 同为 UI 线程契约）。
 */
struct GlowParams {
    bool enabled = false;
    float threshold = 0.5f;
    float soft = 0.05f;
    float width = 0.02f;
    float gain = 1.0f;
    float add = 0.0f;
    float freq = 0.0f;
    float blend = 0.6f;
    uint32_t colorA = 0xFFFF3399u;
    uint32_t colorB = 0xFF33E6FFu;
};
GlowParams g_glowParams;
/** 画面降噪档请求（0=关 1=完整 2=轻量；上游 DENOISE_LEVEL_* 同值） */
int g_denoiseLevel = WOTA_DENOISE_OFF;

/** 1×1 全黑纹理：链未就绪（首帧/刚开启/已停用）时 uGlowSrc 绑它，光晕贡献为 0 */
GLuint g_glowBlackTex = 0;
/** 本帧可采样的 Glow 结果（0 = 未就绪，主 pass 退回黑纹理）；TickGlow 每帧写一次 */
GLuint g_glowFrameTex = 0;

// Glow 离屏链资源（三趟：亮部提取+降采样 → 横向高斯 → 纵向高斯）
GLuint g_glowDownTex = 0;
GLuint g_glowScratchTex = 0;
GLuint g_glowResultTex = 0;
GLuint g_glowDownFbo = 0;
GLuint g_glowScratchFbo = 0;
GLuint g_glowResultFbo = 0;
int g_glowW = 0;
int g_glowH = 0;
bool g_glowBroken = false;
int g_glowErrorStreak = 0;
int g_glowMaxEdge = 0;
bool g_glowLastClamped = false;
int g_glowScaleLogged = 0;

// ---- 直方图采样（上游 f31d18c 的 OHOS 对应物：RGB 曲线框背景，调曲线参考用）----
// 数据路：上屏帧绘制后 250ms 一拍把当前帧经**当前 program**（上屏槽 ⇒ 曲线输出后的画面，
// 「边调边看分布」口径）画进 64×64 迷你 FBO 并 glReadPixels 16KB，近地分箱成 3×64 计数
// （BinHistogramRgba 与 core/HistogramMath.ets binRgba 同式镜像）。只在弹窗可见时采
// （WotaGlSetHistogramEnabled）；16KB/4Hz 的读回停顿远低于既有霜板/补弧 pass，只发生在目标场景。
constexpr int kHistSize = 64;
constexpr int kHistBins = 64;
constexpr int kHistChannels = 3;
constexpr int kHistValues = kHistBins * kHistChannels;
constexpr int64_t kHistogramPeriodMs = 250;
bool g_histRequested = false;
GLuint g_histFbo = 0;
GLuint g_histTex = 0;
int64_t g_histLastSampleMs = 0;
// 快照（3×64 计数，R/G/B 依次）：napi 读侧与 GL 写侧跨线程，短临界锁（Android 用
// @Volatile 引用替换免锁；本侧 getter 直接出 Int32Array，192 int 量级锁可忽略，登记偏离）
std::mutex g_histMutex;
std::vector<int32_t> g_histSnapshot;

// 请求态（napi setter 只写状态；实际建槽/上传在渲染线程内完成）
int g_effectRequested = kEffectNone;
int g_effectBlacklisted = -1;
bool g_curveEnabled = false;
float g_zebraThreshold = 0.6f;
int g_zebraDensity = 1;
float g_peakingStrength = 0.6f;
int g_peakingColor = 4; // PeakingColor.RED

// 曲线 LUT（core CurveStack.bakeBytes：256×1 交错 RGB，768B）
uint8_t g_curveData[768] = {0};
bool g_curveDirty = false;
bool g_curveUploaded = false;
GLuint g_curveTexId = 0;
/** 曲线 program 在本 surface 内已失败：不再每帧重编（上游「引擎实例内持久」语义；surface 重建重置） */
bool g_curveFailed = false;

// 斑马纹瓦片
GLuint g_stripeTexId = 0;
int g_stripeDensity = 0;

// NativeImage（SurfaceTexture 等价物）：相机帧 → GL_TEXTURE_EXTERNAL_OES
OH_NativeImage* g_nativeImage = nullptr;
GLuint g_texId = 0;
std::atomic<bool> g_frameAvailable{false};
bool g_hasFrame = false;
float g_texMatrix[16] = {
    1.0f, 0.0f, 0.0f, 0.0f,
    0.0f, 1.0f, 0.0f, 0.0f,
    0.0f, 0.0f, 1.0f, 0.0f,
    0.0f, 0.0f, 0.0f, 1.0f
};
std::string g_inputSurfaceId;
/** NativeImage 失败原因（空 = 正常）；页面按它区分「未就绪」与「创建失败」 */
std::string g_nativeImageError;

bool NearF(float actual, float expected)
{
    return std::fabs(actual - expected) < 1e-2f;
}

/**
 * 纹理坐标四边形空间里的顺时针旋转 k（与 buildTexCoords 同一条推导）：
 * 0:(s,t) 90:(1-t,s) 180:(1-s,1-t) 270:(t,1-s)，写成 (r00,r01,r02, r10,r11,r12)。
 */
void RotationMap(int degrees, float rot[6])
{
    switch (WotaNormalizeDeg(degrees)) {
        case 90:
            rot[0] = 0.0f; rot[1] = -1.0f; rot[2] = 1.0f;
            rot[3] = 1.0f; rot[4] = 0.0f; rot[5] = 0.0f;
            break;
        case 180:
            rot[0] = -1.0f; rot[1] = 0.0f; rot[2] = 1.0f;
            rot[3] = 0.0f; rot[4] = -1.0f; rot[5] = 1.0f;
            break;
        case 270:
            rot[0] = 0.0f; rot[1] = 1.0f; rot[2] = 0.0f;
            rot[3] = -1.0f; rot[4] = 0.0f; rot[5] = 1.0f;
            break;
        default:
            rot[0] = 1.0f; rot[1] = 0.0f; rot[2] = 0.0f;
            rot[3] = 0.0f; rot[4] = 1.0f; rot[5] = 0.0f;
            break;
    }
}

/** 复合矩阵 M ∘ Rot 是否等于 (s,t) → (s, 1-t)（PreviewOrientation.matchesCanonical 逐行） */
bool MatchesCanonical(float a11, float a12, float b1, float a21, float a22, float b2, const float rot[6])
{
    const float c11 = a11 * rot[0] + a12 * rot[3];
    const float c12 = a11 * rot[1] + a12 * rot[4];
    const float cb1 = a11 * rot[2] + a12 * rot[5] + b1;
    const float c21 = a21 * rot[0] + a22 * rot[3];
    const float c22 = a21 * rot[1] + a22 * rot[4];
    const float cb2 = a21 * rot[2] + a22 * rot[5] + b2;
    return NearF(c11, 1.0f) && NearF(c12, 0.0f) && NearF(cb1, 0.0f) &&
        NearF(c21, 0.0f) && NearF(c22, -1.0f) && NearF(cb2, 1.0f);
}

/**
 * 从列主序 4x4 缓冲变换矩阵解析「矩阵自带的顺时针旋转分量」（PreviewOrientation 82–131 行逐行）。
 * 全 0 → -1（未知，保留上次）；四种候选都不匹配 → 0（退教科书约定）。
 */
int StRotationFromMatrix(const float matrix[16])
{
    const float a11 = matrix[0];
    const float a12 = matrix[4];
    const float b1 = matrix[12];
    const float a21 = matrix[1];
    const float a22 = matrix[5];
    const float b2 = matrix[13];
    if (std::fabs(a11) < kCanonicalEps && std::fabs(a12) < kCanonicalEps &&
        std::fabs(a21) < kCanonicalEps && std::fabs(a22) < kCanonicalEps &&
        std::fabs(b1) < kCanonicalEps && std::fabs(b2) < kCanonicalEps) {
        return -1;
    }
    const int candidates[4] = {0, 90, 180, 270};
    for (int i = 0; i < 4; i++) {
        float rot[6];
        RotationMap(candidates[i], rot);
        if (MatchesCanonical(a11, a12, b1, a21, a22, b2, rot)) {
            return candidates[i];
        }
    }
    return 0;
}

/**
 * buildTexCoords 逐行移植（GlRenderEngine.kt 1841 行）：
 * deg = normalize(sensor − display + stRot)；mirror 作用在屏幕空间（先 x=-x）；
 * X=u-0.5、Y=0.5-v（Y 向下），内容顺时针转 θ 等价取样逆旋 -θ：X0=c·X+s·Y、Y0=-s·X+c·Y；
 * 写回属性 t=1-v 抵消顶点 Y 轴方向。
 *
 * 方向标定 v4（2026-10-28 用户第三轮实测）：在 v3（竖屏后摄 180/前摄 90）基础上
 * **前后摄各再 +180°**（用户报两路同时颠倒）：`deg = display + (rear ? 0 : 270)`、
 * `mirror = !front`。横屏（display=270）锚点：后摄 270、前摄 180。sensor 仍不参与（模拟器
 * webcam HAL 缓冲随显示旋转）；真机须按 SENSOR 语义复测重新标定（登记待真机批）。
 */
void RebuildTexCoords()
{
    const int deg = WotaNormalizeDeg(g_displayDeg + g_stRotDeg +
        (g_mirrored ? 0 : 270));
    const int key = (deg << 1) | (g_mirrored ? 1 : 0);
    if (key == g_geomKey) {
        return;
    }
    g_geomKey = key;
    const float rad = static_cast<float>(deg) * kPi / 180.0f;
    const float c = std::cos(rad);
    const float s = std::sin(rad);
    float uv[8];
    for (int i = 0; i < 4; i++) {
        float x = kQuadPositions[i * 2] * 0.5f;
        const float y = -kQuadPositions[i * 2 + 1] * 0.5f;
        if (g_mirrored) {
            x = -x;
        }
        const float sx = c * x + s * y;
        const float sy = -s * x + c * y;
        uv[i * 2] = sx + 0.5f;
        uv[i * 2 + 1] = 0.5f - sy;
    }
    glBindBuffer(GL_ARRAY_BUFFER, g_uvVbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(uv), uv, GL_DYNAMIC_DRAW);
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
        "texcoords deg=%{public}d mirror=%{public}d", deg, g_mirrored ? 1 : 0);
}

GLuint CompileShader(GLenum type, const char* source)
{
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint ok = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (ok != GL_TRUE) {
        char info[512] = {0};
        glGetShaderInfoLog(shader, sizeof(info), nullptr, info);
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "shader compile failed type=%{public}u log=%{public}s", static_cast<unsigned>(type), info);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

void FetchLocations(ProgramLocs& locs)
{
    locs.aPosition = glGetAttribLocation(locs.program, "aPosition");
    locs.aTexCoord = glGetAttribLocation(locs.program, "aTexCoord");
    locs.uTexMatrix = glGetUniformLocation(locs.program, "uTexMatrix");
    locs.uFit = glGetUniformLocation(locs.program, "uFit");
    locs.uFrame = glGetUniformLocation(locs.program, "uFrame");
    locs.uCurve = glGetUniformLocation(locs.program, "uCurve");
    locs.uStripe = glGetUniformLocation(locs.program, "uStripe");
    locs.uThreshold = glGetUniformLocation(locs.program, "uThreshold");
    locs.uTile = glGetUniformLocation(locs.program, "uTile");
    locs.uTexel = glGetUniformLocation(locs.program, "uTexel");
    locs.uStrength = glGetUniformLocation(locs.program, "uStrength");
    locs.uColor = glGetUniformLocation(locs.program, "uColor");
    // 2026-10-28 Glow/降噪批：降噪与 Glow 段的 uniform（不存在的返回 -1，写侧逐颗判）
    locs.uSigmaR = glGetUniformLocation(locs.program, "uSigmaR");
    locs.uDenoiseTexel = glGetUniformLocation(locs.program, "uDenoiseTexel");
    locs.uGlowSrc = glGetUniformLocation(locs.program, "uGlowSrc");
    locs.uGlowGain = glGetUniformLocation(locs.program, "uGlowGain");
    locs.uGlowAdd = glGetUniformLocation(locs.program, "uGlowAdd");
    locs.uGlowFreq = glGetUniformLocation(locs.program, "uGlowFreq");
    locs.uGlowBlend = glGetUniformLocation(locs.program, "uGlowBlend");
    locs.uGlowColorA = glGetUniformLocation(locs.program, "uGlowColorA");
    locs.uGlowColorB = glGetUniformLocation(locs.program, "uGlowColorB");
}

/** 编译+链接一组 VS/FS 到指定槽；失败打印 info log 返回 false */
bool BuildProgramLocs(const std::string& vsSrc, const std::string& fsSrc, ProgramLocs& locs)
{
    GLuint vs = CompileShader(GL_VERTEX_SHADER, vsSrc.c_str());
    GLuint fs = CompileShader(GL_FRAGMENT_SHADER, fsSrc.c_str());
    if (vs == 0 || fs == 0) {
        if (vs != 0) {
            glDeleteShader(vs);
        }
        if (fs != 0) {
            glDeleteShader(fs);
        }
        return false;
    }
    GLuint program = glCreateProgram();
    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glLinkProgram(program);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint ok = GL_FALSE;
    glGetProgramiv(program, GL_LINK_STATUS, &ok);
    if (ok != GL_TRUE) {
        char info[512] = {0};
        glGetProgramInfoLog(program, sizeof(info), nullptr, info);
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "program link failed log=%{public}s", info);
        glDeleteProgram(program);
        return false;
    }
    locs.program = program;
    FetchLocations(locs);
    return true;
}

void DeleteProgramLocs(ProgramLocs& locs)
{
    if (locs.program != 0) {
        glDeleteProgram(locs.program);
    }
    locs = ProgramLocs();
}

/** 曲线纹理上传（unit 2）：256×1 交错 RGB（core CurveStack.bakeBytes 同一契约） */
void EnsureCurveTexture()
{
    if (!g_curveDirty && g_curveUploaded && g_curveTexId != 0) {
        return;
    }
    if (!g_curveDirty || g_curveFailed) {
        return; // 无待上传数据 / 本 surface 已失败：不建空 LUT、不每帧重试
    }
    if (g_curveTexId == 0) {
        GLuint ids[1] = {0};
        glGenTextures(1, ids);
        if (ids[0] == 0) {
            g_curveFailed = true;
            OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "curve LUT glGenTextures failed, curve disabled until surface recreate");
            return;
        }
        g_curveTexId = ids[0];
    }
    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, g_curveTexId);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    // 上传前清一次遗留错误，上传后校验（P3：错误检测与透明退化）
    while (glGetError() != GL_NO_ERROR) {
    }
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, 256, 1, 0, GL_RGB, GL_UNSIGNED_BYTE, g_curveData);
    const GLenum err = glGetError();
    if (err != GL_NO_ERROR || glIsTexture(g_curveTexId) != GL_TRUE) {
        g_curveFailed = true;
        g_curveDirty = true; // 数据仍在，surface 重建后重试
        glDeleteTextures(1, &g_curveTexId);
        g_curveTexId = 0;
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "curve LUT upload failed err=%{public}u, curve disabled until surface recreate",
            static_cast<unsigned>(err));
        return;
    }
    g_curveDirty = false;
    g_curveUploaded = true;
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "curve LUT uploaded 256x1");
}

int SanitizeDensity(int density)
{
    return density < 1 ? 1 : (density > 3 ? 3 : density);
}

float TileRepeatOf(int density)
{
    static const float repeat[3] = {4.0f, 8.0f, 16.0f};
    return repeat[SanitizeDensity(density) - 1];
}

/** ZebraPattern 瓦片逐项：64px、周期 32、占空 0.25/0.35/0.50、条纹 0xFFE53935、空隙纯黑 */
void EnsureStripeTexture(int density)
{
    const int safe = SanitizeDensity(density);
    if (g_stripeTexId != 0 && g_stripeDensity == safe) {
        return;
    }
    static const float duty[3] = {0.25f, 0.35f, 0.50f};
    const int period = 64 / 2;
    const int lineWidthRaw = static_cast<int>(period * duty[safe - 1]);
    const int lineWidth = lineWidthRaw < 1 ? 1 : lineWidthRaw;
    uint8_t pixels[64 * 64 * 4];
    for (int y = 0; y < 64; y++) {
        for (int x = 0; x < 64; x++) {
            const int idx = (y * 64 + x) * 4;
            if ((x + y) % period < lineWidth) {
                pixels[idx] = 0xE5;
                pixels[idx + 1] = 0x39;
                pixels[idx + 2] = 0x35;
            } else {
                pixels[idx] = 0x00;
                pixels[idx + 1] = 0x00;
                pixels[idx + 2] = 0x00;
            }
            pixels[idx + 3] = 0xFF;
        }
    }
    if (g_stripeTexId == 0) {
        glGenTextures(1, &g_stripeTexId);
    }
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, g_stripeTexId);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 64, 64, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    g_stripeDensity = safe;
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
        "stripe tile %{public}dp period=32 line=%{public}d", 64, lineWidth);
}

/** 峰值色表（上游 peakingRgb 逐值） */
void PeakingRgbOf(int colorIndex, float rgb[3])
{
    static const float table[5][3] = {
        {1.00f, 1.00f, 1.00f}, // WHITE
        {0.12f, 1.00f, 0.28f}, // GREEN
        {0.20f, 0.55f, 1.00f}, // BLUE
        {1.00f, 0.60f, 0.05f}, // ORANGE
        {1.00f, 0.12f, 0.12f}, // RED
    };
    const int safe = colorIndex < 0 ? 0 : (colorIndex > 4 ? 4 : colorIndex);
    rgb[0] = table[safe][0];
    rgb[1] = table[safe][1];
    rgb[2] = table[safe][2];
}

/**
 * Glow/降噪判档用的输入尺寸（2026-10-28 Glow/降噪批）：
 * 编码面在挂时取编码尺寸（= 成片档，上游「按相机帧/录制档判，不按上屏档」口径），
 * 否则取上屏表面尺寸。相机帧原生尺寸本侧无独立观测面（NativeImage 只给缓冲矩阵），登记偏离。
 */
void GlowInputSize(int& w, int& h)
{
    if (g_encW > 0 && g_encH > 0) {
        w = g_encW;
        h = g_encH;
    } else {
        w = static_cast<int>(g_width);
        h = static_cast<int>(g_height);
    }
}

/** ARGB → 0..1 三元组（上游 core/Glow.kt glowRgb；只取 RGB，alpha 不参与光晕） */
void GlowRgbOf(uint32_t argb, float rgb[3])
{
    const uint32_t v = argb & 0xFFFFFFFFu;
    rgb[0] = static_cast<float>((v >> 16) & 0xFFu) / 255.0f;
    rgb[1] = static_cast<float>((v >> 8) & 0xFFu) / 255.0f;
    rgb[2] = static_cast<float>(v & 0xFFu) / 255.0f;
}

/**
 * 双边降噪 uniform（2026-10-28 批）：纹素步长取相机帧/编码面尺寸（错则降噪半径错），
 * σ 取上游同值。peaking 同场时降噪走 uDenoiseTexel（uTexel 是峰值的邻域步长），
 * 其余变体（直通/曲线/斑马）与上游一致共用 uTexel。
 */
void BindDenoiseUniforms(const ProgramLocs& locs, int denoise)
{
    if (denoise == WOTA_DENOISE_OFF) {
        return;
    }
    int inW = 0;
    int inH = 0;
    GlowInputSize(inW, inH);
    if (inW > 0 && inH > 0) {
        const float tx = 1.0f / static_cast<float>(inW);
        const float ty = 1.0f / static_cast<float>(inH);
        if (locs.uDenoiseTexel >= 0) {
            glUniform2f(locs.uDenoiseTexel, tx, ty);
        } else if (locs.uTexel >= 0) {
            glUniform2f(locs.uTexel, tx, ty);
        }
    }
    if (locs.uSigmaR >= 0) {
        glUniform1f(locs.uSigmaR, kDenoiseSigmaR);
    }
}

/**
 * Glow 段 uniform（2026-10-28 批；上游 drawPass 的 uGlow* 绑定逐项）：
 * 链未就绪/关着时绑 1×1 黑纹理并把 gain/add/blend 清零 ⇒ 贡献为 0，**不为它切 program**
 * （切 program 会闪帧、会在编码 swap 前编译）。两槽共享同一张 glowTex，本函数对两槽同口径。
 */
void BindGlowUniforms(const ProgramLocs& locs)
{
    const GLuint tex = (g_glowParams.enabled && g_glowFrameTex != 0) ? g_glowFrameTex : g_glowBlackTex;
    glActiveTexture(GL_TEXTURE0 + kGlowSrcUnit);
    glBindTexture(GL_TEXTURE_2D, tex);
    if (locs.uGlowSrc >= 0) {
        glUniform1i(locs.uGlowSrc, kGlowSrcUnit);
    }
    if (g_glowParams.enabled) {
        if (locs.uGlowGain >= 0) {
            glUniform1f(locs.uGlowGain, g_glowParams.gain);
        }
        if (locs.uGlowAdd >= 0) {
            glUniform1f(locs.uGlowAdd, g_glowParams.add);
        }
        if (locs.uGlowFreq >= 0) {
            glUniform1f(locs.uGlowFreq, g_glowParams.freq);
        }
        if (locs.uGlowBlend >= 0) {
            glUniform1f(locs.uGlowBlend, g_glowParams.blend);
        }
        float rgbA[3];
        float rgbB[3];
        GlowRgbOf(g_glowParams.colorA, rgbA);
        GlowRgbOf(g_glowParams.colorB, rgbB);
        if (locs.uGlowColorA >= 0) {
            glUniform3f(locs.uGlowColorA, rgbA[0], rgbA[1], rgbA[2]);
        }
        if (locs.uGlowColorB >= 0) {
            glUniform3f(locs.uGlowColorB, rgbB[0], rgbB[1], rgbB[2]);
        }
    } else {
        // 关着：零贡献（program 里仍有 Glow 段，靠 uniform 归零；与上游「关着时零成本」对齐到观感层）
        if (locs.uGlowGain >= 0) {
            glUniform1f(locs.uGlowGain, 0.0f);
        }
        if (locs.uGlowAdd >= 0) {
            glUniform1f(locs.uGlowAdd, 0.0f);
        }
        if (locs.uGlowFreq >= 0) {
            glUniform1f(locs.uGlowFreq, 0.0f);
        }
        if (locs.uGlowBlend >= 0) {
            glUniform1f(locs.uGlowBlend, 0.0f);
        }
    }
    // 其余 texture 绑定都在 GL_TEXTURE0：恢复活动单元，避免后续绑定落到 5 号
    glActiveTexture(GL_TEXTURE0);
}

/**
 * 画完把 5 号单元上的 Glow 图摘下来（上游 drawPass 尾部的同一条保险）：
 * 下一帧链要往同一张纹理里画，某些驱动只查「这张还挂在激活单元上」就当 feedback loop 报警。
 */
void UnbindGlowUnit()
{
    glActiveTexture(GL_TEXTURE0 + kGlowSrcUnit);
    glBindTexture(GL_TEXTURE_2D, 0);
    glActiveTexture(GL_TEXTURE0);
}

/**
 * 编码槽同步（上游 46d8d91「成片支路恒 NONE」的 OHOS 对应物）：编码支路 program 的
 * 自由度是 (curve, denoise)——effect 恒 NONE（斑马/峰值是监看叠加，绝不进成片）。
 * wantCurve 与上屏同源（同一份曲线纹理门控结果）；降噪档读全局 g_denoiseLevel（槽键第三维）。
 * 构建失败退 g_passLocs 直通（成片丢曲线/降噪优于成片带着监看叠加，且不阻塞录制）。
 * 在 SyncEffect 里一并驱动，保证两槽同源同帧。
 */
void SyncEncodeSlot(bool wantCurve)
{
    const int wantDenoise = g_denoiseLevel;
    if (g_encSlotLocs.program != 0 && wantCurve == g_encSlotCurve &&
        wantDenoise == g_encSlotDenoise) {
        return;
    }
    DeleteProgramLocs(g_encSlotLocs);
    g_encSlotCurve = false;
    g_encSlotDenoise = WOTA_DENOISE_OFF;
    if (!wantCurve && wantDenoise == WOTA_DENOISE_OFF) {
        return; // 直通（g_passLocs 常驻）
    }
    bool curve = wantCurve;
    int denoise = wantDenoise;
    bool built = false;
    if (denoise == WOTA_DENOISE_OFF) {
        // 无降噪：保持曲线单变体（WOTA_CURVE_FS）——成片最常见档，源码与上游逐字同一份
        built = BuildProgramLocs(WOTA_TEXTURE_VS, WOTA_CURVE_FS, g_encSlotLocs);
    } else {
        built = BuildProgramLocs(WOTA_TEXTURE_VS, WotaSceneFragment(curve, denoise), g_encSlotLocs);
        if (!built) {
            // 降噪变体整体编不出来（个别驱动的循环/数组限制）：退无降噪档，曲线保留
            OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "encode slot denoise failed, retry without denoise");
            denoise = WOTA_DENOISE_OFF;
            built = BuildProgramLocs(WOTA_TEXTURE_VS, WotaSceneFragment(curve, denoise), g_encSlotLocs);
        }
        if (!built && curve) {
            OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "encode slot bilateral+curve failed, retry without curve");
            curve = false;
            built = BuildProgramLocs(WOTA_TEXTURE_VS, WotaSceneFragment(false, denoise), g_encSlotLocs);
        }
    }
    if (built) {
        g_encSlotCurve = curve;
        g_encSlotDenoise = denoise;
    } else {
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "encode slot program failed, fallback pass-through (recording keeps going)");
    }
}

/**
 * 单效果槽程序切换（上游退避链）：目标效果+曲线+降噪变体失败 → 退无曲线变体；
 * 再失败退无降噪变体；再失败且非 NONE → 黑名单该效果并回直通（下一帧 target 归 NONE）；
 * 曲线单独模式失败 → 回直通。槽键 = (effect, curve, denoise)（2026-10-28 Glow/降噪批）。
 */
void SyncEffect()
{
    int target = g_effectRequested;
    if (target == g_effectBlacklisted) {
        target = kEffectNone;
    }
    // 曲线纹理门控（P1）：先确保纹理存在/上传，再判定 wantCurve——不能拿 texId 作前置条件，
    // 否则首次启用曲线时 texId==0 → wantCurve=false → 永不建纹理（循环依赖）。
    if (g_curveEnabled) {
        EnsureCurveTexture();
    }
    const bool wantCurve = g_curveEnabled && !g_curveFailed && g_curveUploaded && g_curveTexId != 0;
    const int wantDenoise = g_denoiseLevel;
    // 编码槽先同步（wantCurve/降噪同源）：上屏槽早退路径也不能跳过它
    SyncEncodeSlot(wantCurve);
    // 纹理生命周期照上游 syncEffect：目标不再用 zebra 时删条纹瓦片；曲线关闭时删曲线纹理
    // （数据仍在 g_curveData，重开时置 dirty 重新上传，不重复占显存）。
    if (target != kEffectZebra && g_stripeTexId != 0) {
        glDeleteTextures(1, &g_stripeTexId);
        g_stripeTexId = 0;
        g_stripeDensity = 0;
        OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "stripe tile released");
    }
    if (!wantCurve && g_curveTexId != 0) {
        glDeleteTextures(1, &g_curveTexId);
        g_curveTexId = 0;
        g_curveUploaded = false;
        g_curveDirty = true; // 数据仍在，重开曲线时重新上传
        OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "curve LUT released");
    }
    if (g_slotLocs.program != 0 && target == g_slotEffect && wantCurve == g_slotCurve &&
        wantDenoise == g_slotDenoise) {
        if (target == kEffectZebra) {
            EnsureStripeTexture(g_zebraDensity);
        }
        return;
    }
    DeleteProgramLocs(g_slotLocs);
    g_slotEffect = kEffectNone;
    g_slotCurve = false;
    g_slotDenoise = WOTA_DENOISE_OFF;
    if (target == kEffectNone && !wantCurve && wantDenoise == WOTA_DENOISE_OFF) {
        return; // 直通（g_passLocs 常驻）
    }

    bool curve = wantCurve;
    int denoise = wantDenoise;
    bool built = false;
    if (target == kEffectNone) {
        built = BuildProgramLocs(WOTA_TEXTURE_VS, WotaSceneFragment(curve, denoise), g_slotLocs);
        if (!built && denoise != WOTA_DENOISE_OFF) {
            OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "scene denoise variant failed, retry without denoise");
            denoise = WOTA_DENOISE_OFF;
            built = BuildProgramLocs(WOTA_TEXTURE_VS, WotaSceneFragment(curve, denoise), g_slotLocs);
        }
        if (!built && curve) {
            // CURVE_FS 直链失败：本 surface 内不再重试曲线（避免每帧重编）
            g_curveFailed = true;
            OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "scene curve variant failed, retry without curve");
            curve = false;
            built = BuildProgramLocs(WOTA_TEXTURE_VS, WotaSceneFragment(false, denoise), g_slotLocs);
        }
    } else if (target == kEffectZebra) {
        built = BuildProgramLocs(WOTA_TEXTURE_VS, WotaZebraFragment(curve, denoise), g_slotLocs);
        if (!built && denoise != WOTA_DENOISE_OFF) {
            OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "zebra+denoise failed, retry without denoise");
            denoise = WOTA_DENOISE_OFF;
            built = BuildProgramLocs(WOTA_TEXTURE_VS, WotaZebraFragment(curve, denoise), g_slotLocs);
        }
        if (!built && curve) {
            OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "zebra+curve failed, retry without curve");
            curve = false;
            built = BuildProgramLocs(WOTA_TEXTURE_VS, WotaZebraFragment(false, denoise), g_slotLocs);
            g_curveFailed = true; // 曲线变体失败：本 surface 内曲线不再尝试
        }
    } else {
        built = BuildProgramLocs(WOTA_TEXTURE_VS, WotaPeakingFragment(curve, denoise), g_slotLocs);
        if (!built && denoise != WOTA_DENOISE_OFF) {
            OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "peaking+denoise failed, retry without denoise");
            denoise = WOTA_DENOISE_OFF;
            built = BuildProgramLocs(WOTA_TEXTURE_VS, WotaPeakingFragment(curve, denoise), g_slotLocs);
        }
        if (!built && curve) {
            OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "peaking+curve failed, retry without curve");
            curve = false;
            built = BuildProgramLocs(WOTA_TEXTURE_VS, WotaPeakingFragment(false, denoise), g_slotLocs);
            g_curveFailed = true; // 曲线变体失败：本 surface 内曲线不再尝试
        }
    }
    if (!built) {
        if (target != kEffectNone) {
            g_effectBlacklisted = target;
            OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "effect %{public}d failed, blacklisted (fallback pass-through)", target);
        } else {
            OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "curve program failed, fallback pass-through");
        }
        return;
    }
    g_slotEffect = target;
    g_slotCurve = curve;
    g_slotDenoise = denoise;
    if (target == kEffectZebra) {
        EnsureStripeTexture(g_zebraDensity);
    }
    if (curve) {
        EnsureCurveTexture();
    }
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
        "program slot effect=%{public}d curve=%{public}d denoise=%{public}d",
        g_slotEffect, g_slotCurve ? 1 : 0, g_slotDenoise);
}

/**
 * 上屏与编码 pass 共用的绘制（同一套 uniform/纹理绑定）：
 * @param uvVbo  朝向来源（上屏 = 显示 UV；编码 = 编码 UV）
 * @param vaoOk  是否允许走上屏的 VAO 快路（编码面用独立 UV VBO，必须手动绑指针）
 * @param encodeSlot  true = locs 是编码槽/直通（effect 恒 NONE，只绑曲线）——
 *                    监看叠加（斑马/峰值）的 uniform 分支只属于上屏槽
 */
void DrawScene(const ProgramLocs& locs, GLuint uvVbo, bool vaoOk, bool applyFit = false,
    bool encodeSlot = false)
{
    glUseProgram(locs.program);
    if (locs.uTexMatrix >= 0) {
        glUniformMatrix4fv(locs.uTexMatrix, 1, GL_FALSE, g_texMatrix);
    }
    if (locs.uFit >= 0) {
        // letterbox：上屏吃 g_fit，编码面恒满幅（成片不带黑边）
        glUniform2f(locs.uFit, applyFit ? g_fitX : 1.0f, applyFit ? g_fitY : 1.0f);
    }
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, g_texId);
    if (locs.uFrame >= 0) {
        glUniform1i(locs.uFrame, 0);
    }
    if (encodeSlot) {
        // 编码支路：恒无斑马/峰值；曲线是创作观感，两槽都带（上游 46d8d91 口径）
        if (g_encSlotCurve) {
            glActiveTexture(GL_TEXTURE2);
            glBindTexture(GL_TEXTURE_2D, g_curveTexId);
            if (locs.uCurve >= 0) {
                glUniform1i(locs.uCurve, 2);
            }
        }
    } else if (locs.program == g_slotLocs.program) {
        if (g_slotEffect == kEffectZebra) {
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, g_stripeTexId);
            if (locs.uStripe >= 0) {
                glUniform1i(locs.uStripe, 1);
            }
            if (locs.uThreshold >= 0) {
                glUniform1f(locs.uThreshold, g_zebraThreshold);
            }
            const float repeat = TileRepeatOf(g_zebraDensity);
            if (locs.uTile >= 0) {
                glUniform2f(locs.uTile, repeat, repeat);
            }
        } else if (g_slotEffect == kEffectPeaking) {
            if (locs.uTexel >= 0) {
                glUniform2f(locs.uTexel, 0.002f, 0.002f);
            }
            if (locs.uStrength >= 0) {
                glUniform1f(locs.uStrength, g_peakingStrength);
            }
            float rgb[3];
            PeakingRgbOf(g_peakingColor, rgb);
            if (locs.uColor >= 0) {
                glUniform3f(locs.uColor, rgb[0], rgb[1], rgb[2]);
            }
        }
        if (g_slotCurve) {
            glActiveTexture(GL_TEXTURE2);
            glBindTexture(GL_TEXTURE_2D, g_curveTexId);
            if (locs.uCurve >= 0) {
                glUniform1i(locs.uCurve, 2);
            }
        }
    }
    // 2026-10-28 Glow/降噪批：降噪 uniform 按实际编出来的档（槽键第三维）；
    // Glow 段两槽同口径（同一张 glowTex，链未就绪/关着绑 1×1 黑 ⇒ 零贡献）
    BindDenoiseUniforms(locs,
        encodeSlot ? g_encSlotDenoise : (locs.program == g_slotLocs.program ? g_slotDenoise : WOTA_DENOISE_OFF));
    BindGlowUniforms(locs);
    if (vaoOk && g_useVao) {
        // ES3 每帧重绑 VAO（防后续 GL 状态改动把绘制落到未绑定/旧 VAO）
        glBindVertexArray(g_vao);
    } else {
        if (g_useVao) {
            glBindVertexArray(0);
        }
        // 无 VAO（ES2 / 编码面独立 UV）：手动绑 pos + uv 两条 VBO
        if (locs.aPosition >= 0) {
            glEnableVertexAttribArray(static_cast<GLuint>(locs.aPosition));
            glBindBuffer(GL_ARRAY_BUFFER, g_posVbo);
            glVertexAttribPointer(static_cast<GLuint>(locs.aPosition), 2, GL_FLOAT, GL_FALSE,
                2 * sizeof(float), reinterpret_cast<const void*>(0));
        }
        if (locs.aTexCoord >= 0) {
            glEnableVertexAttribArray(static_cast<GLuint>(locs.aTexCoord));
            glBindBuffer(GL_ARRAY_BUFFER, uvVbo);
            glVertexAttribPointer(static_cast<GLuint>(locs.aTexCoord), 2, GL_FLOAT, GL_FALSE,
                2 * sizeof(float), reinterpret_cast<const void*>(0));
        }
    }
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    // 画完立刻把 5 号单元上的 Glow 图摘下来（下一帧链要往同一张里画，上游 drawPass 同款保险）
    if (locs.uGlowSrc >= 0) {
        UnbindGlowUnit();
    }
}

// ---------------------------------------------------------------- 直方图采样（曲线框背景，f31d18c）

/**
 * RGBA(8888) 像素 → 3×64 档计数（R/G/B 依次）。与 core/HistogramMath.ets binRgba
 * **同式镜像**（bin = v >> 2；像素住在 GL 线程，16KB 读回后近地分箱再传 192 个 int）——
 * 契约在两处出现，接线守卫钉住两边的公式文本。
 */
void BinHistogramRgba(const uint8_t* rgba, int width, int height, std::vector<int32_t>& out)
{
    out.assign(kHistValues, 0);
    const int px = width * height;
    for (int i = 0; i < px; i++) {
        const int o = i * 4;
        out[rgba[o] >> 2]++;
        out[kHistBins + (rgba[o + 1] >> 2)]++;
        out[2 * kHistBins + (rgba[o + 2] >> 2)]++;
    }
}

/** 直方图迷你 RT（懒建）：64×64 RGBA + NEAREST + CLAMP，完整性出口对齐 frost RT 口径 */
bool EnsureHistogramRt()
{
    if (g_histFbo != 0) {
        return true;
    }
    GLuint tex = 0;
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, kHistSize, kHistSize, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    GLuint fbo = 0;
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
    const bool complete = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (!complete) {
        glDeleteFramebuffers(1, &fbo);
        glDeleteTextures(1, &tex);
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "histogram RT incomplete");
        return false;
    }
    g_histTex = tex;
    g_histFbo = fbo;
    return true;
}

/**
 * 上屏绘制尾部的直方图采样（GL 线程）：250ms 一拍把当前帧经**当前 program** 画进迷你 FBO
 * 并读回分箱。「曲线输出后」口径（弹窗用途是边调边看分布，见 HistogramMath 头注）。
 * 调用点：上屏 swap 之后、编码支路之前（display 上下文在场）；采样后恢复 viewport。
 */
void MaybeSampleHistogram(const ProgramLocs& locs)
{
    if (!g_histRequested || locs.program == 0) {
        return;
    }
    const int64_t now = NowSteadyNs() / 1000000;
    if (now - g_histLastSampleMs < kHistogramPeriodMs) {
        return;
    }
    g_histLastSampleMs = now;
    if (!EnsureHistogramRt()) {
        return;
    }
    glBindFramebuffer(GL_FRAMEBUFFER, g_histFbo);
    glViewport(0, 0, kHistSize, kHistSize);
    DrawScene(locs, g_uvVbo, g_useVao, false, false);
    uint8_t pixels[kHistSize * kHistSize * 4];
    glReadPixels(0, 0, kHistSize, kHistSize, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, static_cast<GLsizei>(g_width), static_cast<GLsizei>(g_height));
    std::vector<int32_t> bins;
    BinHistogramRgba(pixels, kHistSize, kHistSize, bins);
    {
        std::lock_guard<std::mutex> lock(g_histMutex);
        g_histSnapshot.swap(bins);
    }
}

/**
 * 编码 UV 重建：deg = stRotDeg、mirror = false —— 上游 buildTexCoords(encoderPass = true) 语义
 * （只抵消缓冲矩阵分量、不转正、不镜像；容器方向由 muxer SetRotation(normalizeQuarter) 写入）。
 */
void RebuildEncoderTexCoords()
{
    if (g_encUvVbo == 0) {
        return;
    }
    const int deg = WotaNormalizeDeg(g_stRotDeg);
    if (deg == g_encGeomKey) {
        return;
    }
    g_encGeomKey = deg;
    const float rad = static_cast<float>(deg) * kPi / 180.0f;
    const float c = std::cos(rad);
    const float s = std::sin(rad);
    float uv[8];
    for (int i = 0; i < 4; i++) {
        const float x = kQuadPositions[i * 2] * 0.5f;
        const float y = -kQuadPositions[i * 2 + 1] * 0.5f;
        // 编码 pass 不镜像（mirror 只作用于屏幕空间）
        const float sx = c * x + s * y;
        const float sy = -s * x + c * y;
        uv[i * 2] = sx + 0.5f;
        uv[i * 2 + 1] = 0.5f - sy;
    }
    glBindBuffer(GL_ARRAY_BUFFER, g_encUvVbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(uv), uv, GL_DYNAMIC_DRAW);
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
        "encoder texcoords deg=%{public}d", deg);
}

/**
 * 编码 pass：在编码器输入面上绘一帧。**绝不进入 frost 分支**（上游红线：霜面是上屏专属）；
 * 效果链走**编码槽**（effect 恒 NONE + 曲线）——监看叠加（斑马/峰值）只上屏、绝不进成片
 * （上游 46d8d91 双 program 槽裁决的 OHOS 对应物）。
 */
bool DrawEncoderPass()
{
    if (g_encSurface == EGL_NO_SURFACE || g_encW <= 0 || g_encH <= 0) {
        return false;
    }
    SyncEffect();
    RebuildEncoderTexCoords();
    if (eglMakeCurrent(g_display, g_encSurface, g_encSurface, g_context) != EGL_TRUE) {
        g_encFailStreak++;
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "encoder makeCurrent failed err=%{public}d streak=%{public}d",
            static_cast<int>(eglGetError()), g_encFailStreak);
        if (g_encFailStreak >= 3) {
            CodecRecorder::Instance().Abort("encoder-pass-failed");
        }
        return false;
    }
    glViewport(0, 0, g_encW, g_encH);
    glClearColor(0.02f, 0.02f, 0.04f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    const ProgramLocs& locs = g_encSlotLocs.program != 0 ? g_encSlotLocs : g_passLocs;
    bool drawn = false;
    if (locs.program != 0) {
        DrawScene(locs, g_encUvVbo, false, false, true);
        drawn = true;
    }
    const EGLBoolean swapped = drawn ? eglSwapBuffers(g_display, g_encSurface) : EGL_FALSE;
    if (drawn && swapped == EGL_TRUE) {
        g_encoderSwapOk++;
        g_encoderFrameIndex++; // 仅成功送帧推进输出序号（抽帧丢弃帧不推进）
    } else {
        g_encoderSwapFail++;
    }
    // 恢复上屏上下文：失败视一次即中止（若上下文仍绑在编码面，下一帧上屏会画错面）。
    // 置 g_displayCtxLost，WotaGlRender 下一帧开头先重试绑定，未成功前跳过全部绘制。
    const EGLBoolean restored = eglMakeCurrent(g_display, g_surface, g_surface, g_context);
    if (restored == EGL_TRUE) {
        glViewport(0, 0, static_cast<GLsizei>(g_width), static_cast<GLsizei>(g_height));
    } else {
        g_displayCtxLost = true;
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "encoder pass restore display context failed err=%{public}d, abort recording",
            static_cast<int>(eglGetError()));
        CodecRecorder::Instance().Abort("encoder-pass-failed");
        return false;
    }
    if (!drawn || swapped != EGL_TRUE) {
        g_encFailStreak++;
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "encoder pass failed drawn=%{public}d swap=%{public}d streak=%{public}d",
            drawn ? 1 : 0, swapped == EGL_TRUE ? 1 : 0, g_encFailStreak);
        if (g_encFailStreak >= 3) {
            CodecRecorder::Instance().Abort("encoder-pass-failed");
        }
        return false;
    }
    g_encFailStreak = 0;
    if (!g_encFirstFrameLogged) {
        g_encFirstFrameLogged = true;
        OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "encoder pass first frame %{public}dx%{public}d", g_encW, g_encH);
    }
    return true;
}

bool DrawEncoderMended(bool keep);
void DegradeArc(const char* reason);

/** 恢复上屏上下文（编码 pass / MEND / flush 共用出口）：失败置帧级守卫并中止录制 */
bool RestoreDisplayContext()
{
    if (eglMakeCurrent(g_display, g_surface, g_surface, g_context) != EGL_TRUE) {
        g_displayCtxLost = true;
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "restore display context failed err=%{public}d, abort recording",
            static_cast<int>(eglGetError()));
        CodecRecorder::Instance().Abort("encoder-pass-failed");
        return false;
    }
    g_displayCtxLost = false;
    g_displayCtxLostRetries = 0;
    g_displayCtxAbortIssued = false;
    glViewport(0, 0, static_cast<GLsizei>(g_width), static_cast<GLsizei>(g_height));
    return true;
}

/** MEND 发帧：待发帧 → 编码面 swap；成功推进帧序号并记（输出位次, 丢弃数） */
bool EmitMendedPending()
{
    if (g_arcMend == nullptr || !g_arcMend->DrawPending()) {
        return false;
    }
    const EGLBoolean swapped = eglSwapBuffers(g_display, g_encSurface);
    if (swapped != EGL_TRUE) {
        g_encoderSwapFail++;
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "mend emit swap failed err=%{public}d", static_cast<int>(eglGetError()));
        return false;
    }
    g_encoderSwapOk++;
    g_encFailStreak = 0;
    g_encoderFrameIndex++;
    g_arcDrops.push_back({static_cast<int32_t>(std::max(0, g_encoderFrameIndex - 1)),
        g_arcRule.TakeDrops()});
    return true;
}

/**
 * MEND（自动抽帧补弧）路的编码支路：特效链先渲进离屏 cur，再由 ArcRepairFlow 的指令
 * 决定滞留/并弧/发出。**红线：函数内不得出现 RunFrostChain / frost 分支**（霜面是上屏专属）。
 * @return true = 本帧已处理（发出/滞留/丢弃）；false = 失败已降级 DROP，调用方按 DROP 口径补发。
 */
bool DrawEncoderMended(bool keep)
{
    if (g_encSurface == EGL_NO_SURFACE || g_arcMend == nullptr || !g_arcMend->Ready() ||
        g_encW <= 0 || g_encH <= 0) {
        DegradeArc("mend-not-ready");
        return false;
    }
    if (eglMakeCurrent(g_display, g_encSurface, g_encSurface, g_context) != EGL_TRUE) {
        g_encFailStreak++;
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "mend makeCurrent failed err=%{public}d streak=%{public}d",
            static_cast<int>(eglGetError()), g_encFailStreak);
        DegradeArc("mend-makecurrent");
        if (g_encFailStreak >= 3) {
            CodecRecorder::Instance().Abort("encoder-pass-failed");
        }
        return false;
    }
    // 清掉上一帧可能残留的 GL 错误，避免 ArcMendPass 的 DrainGlError 误判本帧失败
    while (glGetError() != GL_NO_ERROR) {
    }
    SyncEffect();
    RebuildEncoderTexCoords();
    const ProgramLocs& locs = g_encSlotLocs.program != 0 ? g_encSlotLocs : g_passLocs;
    if (locs.program == 0) {
        RestoreDisplayContext();
        DegradeArc("mend-program");
        return false;
    }
    // 1) 特效链 → 离屏 cur（编码槽口径：effect 恒 NONE + 曲线；监看叠加不进成片。只 draw 不 swap）
    glBindFramebuffer(GL_FRAMEBUFFER, g_arcMend->CurFbo());
    glViewport(0, 0, g_encW, g_encH);
    glClearColor(0.02f, 0.02f, 0.04f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    DrawScene(locs, g_encUvVbo, false, false, true);
    // 立刻还 FB=0：ArcMendPass 的 copy/merge 自绑自还，DrawPending 契约是画进当前绑定面
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    // 2) 状态机指令（保留决策已由调用方 ArcKeepRule 给出）
    const std::vector<ArcOp> ops = g_arcFlow.OnClassification(keep);
    for (const ArcOp& op : ops) {
        bool ok = true;
        switch (op.kind) {
            case ArcOpKind::AccumulateCur:
                ok = g_arcMend->Accumulate(op.flag);
                break;
            case ArcOpKind::FoldAccIntoPending:
                ok = g_arcMend->FoldAccIntoPending();
                break;
            case ArcOpKind::HoldCurAsPending:
                ok = g_arcMend->HoldCurAsPending();
                break;
            case ArcOpKind::MergeCurAsPending:
                ok = g_arcMend->MergeCurAsPending(op.flag);
                break;
            case ArcOpKind::EmitPending:
                ok = EmitMendedPending();
                break;
        }
        if (!ok) {
            RestoreDisplayContext();
            DegradeArc("mend-op");
            return false;
        }
    }
    return RestoreDisplayContext();
}

/**
 * MEND 失败安全阀（上游三态两段的第一段）：MEND → DROP。补弧专属部件（pass/program/FBO）
 * 随重建作废，pending/acc 半程积累不保；抽帧节奏/位次账重置（登记：降级前位次账丢失）。
 * g_arcRequestedMode 保持用户请求（sidecar 仍按 mend 写，上游 activeArcConvert 口径）。
 */
void DegradeArc(const char* reason)
{
    if (g_arcEffectiveMode != kArcModeMend) {
        return;
    }
    OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
        "arc mend degraded to drop (%{public}s)", reason);
    g_arcEffectiveMode = kArcModeDrop;
    g_arcActive = true;
    if (g_arcMend != nullptr) {
        g_arcMend->Release();
        g_arcMend.reset();
    }
    const int64_t slotNs = g_arcDstFps > 0 ? 1000000000LL / g_arcDstFps : 0;
    g_arcRule = ArcKeepRule(slotNs, ArcKeepToleranceNs(slotNs));
    g_arcFlow = ArcRepairFlow(g_arcDstFps > 0 ? g_arcDstFps : 24);
    g_arcDrops.clear();
    // 实测源帧率账**不重置**：本批采用整场会话口径（登记偏离上游的降级点重启）；
    // 降级只重置 rule/flow/drops。
}

/**
 * 每帧第二趟：仅当**本 tick 消费到新相机帧**、编码面已挂且 CodecRecorder 处于 RECORDING 时送帧
 * （vsync 重绘不重复送帧）。弧关闭时行为与上批完全一致；弧开启时保留判定只吃新帧：
 * DROP 抽帧，MEND 走离屏补弧；分类/丢弃账/实测源帧率只在这里记账。
 */
void MaybeDrawEncoderPass(bool newFrameConsumed, int64_t frameTs)
{
    if (!newFrameConsumed) {
        return;
    }
    if (g_encSurface == EGL_NO_SURFACE) {
        return;
    }
    if (!CodecRecorder::Instance().IsRecording()) {
        return;
    }
    if (!g_arcActive || g_arcEffectiveMode == kArcModeOff) {
        DrawEncoderPass();
        return;
    }
    const bool keep = g_arcRule.OnFrame(frameTs);
    if (!keep) {
        g_arcDropped++;
    }
    // 实测源帧率记整场会话口径（保留帧与丢弃帧都算，且每枚新帧只记一次）
    NoteArcFrame(frameTs);
    if (g_arcEffectiveMode == kArcModeDrop) {
        if (!keep) {
            return;
        }
        if (DrawEncoderPass()) {
            g_arcDrops.push_back({static_cast<int32_t>(std::max(0, g_encoderFrameIndex - 1)),
                g_arcRule.TakeDrops()});
        }
    } else if (!DrawEncoderMended(keep)) {
        // 已降级 DROP：按上游安全阀**无条件直通补发本帧**（keep=false 的降级点也不丢画面），
        // 且不记位次账——DegradeArc 已重建 rule，此处 TakeDrops 属于新 rule（恒 0），
        // 写进 sidecar 会多一条 {out,0}（审查 P3）。
        DrawEncoderPass();
    }
}

// ---------------------------------------------------------------- Frost 全屏高斯链（GL-3）

constexpr int kFrostDownscale = 4;
constexpr int kFrostRtMaxEdge = 512;
constexpr int kFrostMaxErrorStreak = 8;
constexpr int kFrostWeightCount = 4;
static const float kFrostWeights[kFrostWeightCount] = {0.17524f, 0.16577f, 0.14032f, 0.10629f};

struct FrostProgram {
    GLuint program = 0;
    GLint aPosition = -1;
    GLint aTexCoord = -1;
    GLint uFrame = -1;
    GLint uTexMatrix = -1;
    GLint uFit = -1;
    GLint uSrc = -1;
    GLint uOffset = -1;
    GLint uWeight = -1;
    // 2026-10-28 Glow 链复用同一套离屏 program 结构：拷贝趟的亮部阈值两参
    GLint uGlowThreshold = -1;
    GLint uGlowSoft = -1;
};

FrostProgram g_frostCopy;
FrostProgram g_frostBlur;
FrostProgram g_frostDisplay;
bool g_frostBuilt = false;
bool g_frostBuildFailed = false;
GLuint g_idUvVbo = 0;
GLuint g_frostResultTex = 0;
GLuint g_frostScratchTex = 0;
GLuint g_frostResultFbo = 0;
GLuint g_frostScratchFbo = 0;
int g_frostW = 0;
int g_frostH = 0;
bool g_frostRequested = false;
bool g_frostBroken = false;
int g_frostErrorStreak = 0;

int CeilDiv(int a, int b)
{
    return (a + b - 1) / b;
}

/** frostRtSizeInto 逐行：÷4 向上取整；最长边 >512 时等比 round 缩到 512；最小 1 */
bool FrostRtSizeInto(int out[2], int contentW, int contentH)
{
    if (contentW <= 0 || contentH <= 0) {
        return false;
    }
    int w = CeilDiv(contentW, kFrostDownscale);
    int h = CeilDiv(contentH, kFrostDownscale);
    const int longest = w > h ? w : h;
    if (longest > kFrostRtMaxEdge) {
        const double k = static_cast<double>(kFrostRtMaxEdge) / static_cast<double>(longest);
        w = static_cast<int>(std::lround(static_cast<double>(w) * k));
        h = static_cast<int>(std::lround(static_cast<double>(h) * k));
        if (w < 1) {
            w = 1;
        }
        if (h < 1) {
            h = 1;
        }
    }
    out[0] = w;
    out[1] = h;
    return true;
}

/** frostRtNeedsRebuild 逐行：目标非法返回 false（不拆已建资源） */
bool FrostRtNeedsRebuild(int curW, int curH, int targetW, int targetH)
{
    if (targetW <= 0 || targetH <= 0) {
        return false;
    }
    return curW != targetW || curH != targetH;
}

/** fillFrostBlurOffset 逐行：横向 (1/w,0)，纵向 (0,1/h)；非法尺寸不写并返回 false */
bool FillFrostBlurOffset(float out[2], bool horizontal, int texWidth, int texHeight)
{
    if (texWidth <= 0 || texHeight <= 0) {
        return false;
    }
    if (horizontal) {
        out[0] = 1.0f / static_cast<float>(texWidth);
        out[1] = 0.0f;
    } else {
        out[0] = 0.0f;
        out[1] = 1.0f / static_cast<float>(texHeight);
    }
    return true;
}

/** 清空并计数当前 GL 错误（连击停链的输入） */
int DrainGlErrors()
{
    int count = 0;
    while (glGetError() != GL_NO_ERROR) {
        count++;
    }
    return count;
}

/** 全屏 quad 绘制（离屏趟不用 VAO：逐趟指定 pos/uv 两条 VBO，避免改 VAO 状态） */
void DrawFullscreenQuad(GLint aPosition, GLint aTexCoord, GLuint posVbo, GLuint uvVbo)
{
    if (g_useVao) {
        glBindVertexArray(0);
    }
    if (aPosition >= 0) {
        glEnableVertexAttribArray(static_cast<GLuint>(aPosition));
        glBindBuffer(GL_ARRAY_BUFFER, posVbo);
        glVertexAttribPointer(static_cast<GLuint>(aPosition), 2, GL_FLOAT, GL_FALSE,
            2 * sizeof(float), reinterpret_cast<const void*>(0));
    }
    if (aTexCoord >= 0) {
        glEnableVertexAttribArray(static_cast<GLuint>(aTexCoord));
        glBindBuffer(GL_ARRAY_BUFFER, uvVbo);
        glVertexAttribPointer(static_cast<GLuint>(aTexCoord), 2, GL_FLOAT, GL_FALSE,
            2 * sizeof(float), reinterpret_cast<const void*>(0));
    }
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

/** 编一枚离屏 program；失败打印 info log 返回 false（label 只进日志：frost / glow 各自可辨） */
bool BuildFrostProgram(const char* vsSrc, const std::string& fsSrc, FrostProgram& out, bool needWeight,
    const char* label = "frost")
{
    GLuint vs = CompileShader(GL_VERTEX_SHADER, vsSrc);
    GLuint fs = CompileShader(GL_FRAGMENT_SHADER, fsSrc.c_str());
    if (vs == 0 || fs == 0) {
        if (vs != 0) {
            glDeleteShader(vs);
        }
        if (fs != 0) {
            glDeleteShader(fs);
        }
        return false;
    }
    GLuint program = glCreateProgram();
    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glLinkProgram(program);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint ok = GL_FALSE;
    glGetProgramiv(program, GL_LINK_STATUS, &ok);
    if (ok != GL_TRUE) {
        char info[512] = {0};
        glGetProgramInfoLog(program, sizeof(info), nullptr, info);
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "%{public}s program link failed log=%{public}s", label, info);
        glDeleteProgram(program);
        return false;
    }
    out.program = program;
    out.aPosition = glGetAttribLocation(program, "aPosition");
    out.aTexCoord = glGetAttribLocation(program, "aTexCoord");
    out.uFrame = glGetUniformLocation(program, "uFrame");
    out.uTexMatrix = glGetUniformLocation(program, "uTexMatrix");
    out.uFit = glGetUniformLocation(program, "uFit");
    out.uSrc = glGetUniformLocation(program, "uSrc");
    out.uOffset = glGetUniformLocation(program, "uOffset");
    out.uWeight = needWeight ? glGetUniformLocation(program, "uWeight[0]") : -1;
    out.uGlowThreshold = glGetUniformLocation(program, "uGlowThreshold");
    out.uGlowSoft = glGetUniformLocation(program, "uGlowSoft");
    return true;
}

void DeleteFrostProgram(FrostProgram& p)
{
    if (p.program != 0) {
        glDeleteProgram(p.program);
    }
    p = FrostProgram();
}

/** 建三枚 offscreen program：拷贝（mediump）/高斯（highp→mediump 退避）/2D 直通上屏 */
bool BuildFrostPrograms()
{
    if (g_frostBuilt) {
        return true;
    }
    if (g_frostBuildFailed) {
        return false;
    }
    if (!BuildFrostProgram(WOTA_TEXTURE_VS, WOTA_FROST_COPY_FS, g_frostCopy, false)) {
        g_frostBuildFailed = true;
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "frost copy program failed, chain disabled");
        return false;
    }
    bool blurOk = BuildFrostProgram(WOTA_FROST_QUAD_VS, WotaFrostGaussianFragment(kFrostWeightCount, true),
        g_frostBlur, true);
    if (!blurOk) {
        OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "frost gaussian highp failed, retry mediump");
        blurOk = BuildFrostProgram(WOTA_FROST_QUAD_VS, WotaFrostGaussianFragment(kFrostWeightCount, false),
            g_frostBlur, true);
    }
    if (!blurOk || !BuildFrostProgram(WOTA_FROST_QUAD_VS, WOTA_PASS_THROUGH_2D_FS, g_frostDisplay, false)) {
        DeleteFrostProgram(g_frostCopy);
        DeleteFrostProgram(g_frostBlur);
        DeleteFrostProgram(g_frostDisplay);
        g_frostBuildFailed = true;
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "frost program build failed, chain disabled");
        return false;
    }
    if (g_frostBlur.uWeight >= 0) {
        glUseProgram(g_frostBlur.program);
        glUniform1fv(g_frostBlur.uWeight, kFrostWeightCount, kFrostWeights);
    }
    g_frostBuilt = true;
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
        "frost programs ready (gaussian taps=%{public}d)", kFrostWeightCount);
    return true;
}

void DeleteFrostTargets()
{
    if (g_frostResultFbo != 0) {
        glDeleteFramebuffers(1, &g_frostResultFbo);
        g_frostResultFbo = 0;
    }
    if (g_frostScratchFbo != 0) {
        glDeleteFramebuffers(1, &g_frostScratchFbo);
        g_frostScratchFbo = 0;
    }
    if (g_frostResultTex != 0) {
        glDeleteTextures(1, &g_frostResultTex);
        g_frostResultTex = 0;
    }
    if (g_frostScratchTex != 0) {
        glDeleteTextures(1, &g_frostScratchTex);
        g_frostScratchTex = 0;
    }
    g_frostW = 0;
    g_frostH = 0;
}

bool AttachFrostTexture(GLuint fbo, GLuint texture)
{
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
    return glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
}

/** 建/重建离屏 RT 与 FBO（RGBA8 + LINEAR + CLAMP；完整性只认 glCheckFramebufferStatus） */
bool EnsureFrostTargets(int w, int h)
{
    DeleteFrostTargets();
    GLuint texIds[2] = {0, 0};
    glGenTextures(2, texIds);
    if (texIds[0] == 0 || texIds[1] == 0) {
        // 半成功不泄漏：先删已生成的那个纹理再置 broken
        if (texIds[0] != 0) {
            glDeleteTextures(1, &texIds[0]);
        }
        if (texIds[1] != 0) {
            glDeleteTextures(1, &texIds[1]);
        }
        g_frostBroken = true;
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "frost glGenTextures failed, chain disabled");
        return false;
    }
    g_frostResultTex = texIds[0];
    g_frostScratchTex = texIds[1];
    const GLuint textures[2] = {g_frostResultTex, g_frostScratchTex};
    for (int i = 0; i < 2; i++) {
        glBindTexture(GL_TEXTURE_2D, textures[i]);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        // 只占位不填数据：内容全靠三趟画出来
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    }
    glBindTexture(GL_TEXTURE_2D, 0);

    GLuint fboIds[2] = {0, 0};
    glGenFramebuffers(2, fboIds);
    g_frostResultFbo = fboIds[0];
    g_frostScratchFbo = fboIds[1];
    bool complete = g_frostResultFbo != 0 && g_frostScratchFbo != 0;
    if (complete) {
        complete = AttachFrostTexture(g_frostResultFbo, g_frostResultTex) &&
            AttachFrostTexture(g_frostScratchFbo, g_frostScratchTex);
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    const bool hadGlError = DrainGlErrors() > 0;
    if (!complete || hadGlError) {
        g_frostBroken = true;
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "frost FBO incomplete, chain disabled (complete=%{public}d glError=%{public}d)",
            complete ? 1 : 0, hadGlError ? 1 : 0);
        DeleteFrostTargets();
        return false;
    }
    g_frostW = w;
    g_frostH = h;
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
        "frost RT %{public}dx%{public}d (downscale 1/%{public}d)", w, h, kFrostDownscale);
    return true;
}

/**
 * 三趟链：① OES → result（拷贝趟沿用上屏 UV：RT 与屏幕同朝向）② result → scratch（横向）
 * ③ scratch → result（纵向）。返回本帧是否产出可用 RT；错误连击 ≥8 停链（本 surface 内）。
 */
bool RunFrostChain()
{
    if (!g_frostRequested || g_frostBroken || !g_hasFrame || g_nativeImage == nullptr) {
        return false;
    }
    if (g_width <= 0 || g_height <= 0) {
        return false;
    }
    if (!BuildFrostPrograms()) {
        return false;
    }
    int size[2] = {0, 0};
    if (!FrostRtSizeInto(size, static_cast<int>(g_width), static_cast<int>(g_height))) {
        return false;
    }
    if (FrostRtNeedsRebuild(g_frostW, g_frostH, size[0], size[1])) {
        if (!EnsureFrostTargets(size[0], size[1])) {
            return false;
        }
    }
    if (g_frostResultFbo == 0 || g_frostScratchFbo == 0) {
        return false;
    }
    const int w = g_frostW;
    const int h = g_frostH;
    int errors = 0;

    // ① 拷贝：OES → result（uTexMatrix + 上屏 UV）
    glBindFramebuffer(GL_FRAMEBUFFER, g_frostResultFbo);
    glViewport(0, 0, w, h);
    glUseProgram(g_frostCopy.program);
    if (g_frostCopy.uTexMatrix >= 0) {
        glUniformMatrix4fv(g_frostCopy.uTexMatrix, 1, GL_FALSE, g_texMatrix);
    }
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, g_texId);
    if (g_frostCopy.uFrame >= 0) {
        glUniform1i(g_frostCopy.uFrame, 0);
    }
    if (g_frostCopy.uFit >= 0) {
        glUniform2f(g_frostCopy.uFit, 1.0f, 1.0f); // 中间 RT：恒等（RT 尺寸=表面尺寸）
    }
    DrawFullscreenQuad(g_frostCopy.aPosition, g_frostCopy.aTexCoord, g_posVbo, g_uvVbo);
    errors += DrainGlErrors();

    // ② 横向高斯：result → scratch（恒等 UV）
    float offset[2] = {0.0f, 0.0f};
    if (FillFrostBlurOffset(offset, true, w, h)) {
        glBindFramebuffer(GL_FRAMEBUFFER, g_frostScratchFbo);
        glUseProgram(g_frostBlur.program);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, g_frostResultTex);
        if (g_frostBlur.uSrc >= 0) {
            glUniform1i(g_frostBlur.uSrc, 0);
        }
        if (g_frostBlur.uOffset >= 0) {
            glUniform2f(g_frostBlur.uOffset, offset[0], offset[1]);
        }
        if (g_frostBlur.uWeight >= 0) {
            glUniform1fv(g_frostBlur.uWeight, kFrostWeightCount, kFrostWeights);
        }
        if (g_frostBlur.uFit >= 0) {
            glUniform2f(g_frostBlur.uFit, 1.0f, 1.0f); // 中间 RT：恒等
        }
        DrawFullscreenQuad(g_frostBlur.aPosition, g_frostBlur.aTexCoord, g_posVbo, g_idUvVbo);
        errors += DrainGlErrors();
    } else {
        errors++;
    }

    // ③ 纵向高斯：scratch → result（恒等 UV）
    if (FillFrostBlurOffset(offset, false, w, h)) {
        glBindFramebuffer(GL_FRAMEBUFFER, g_frostResultFbo);
        glUseProgram(g_frostBlur.program);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, g_frostScratchTex);
        if (g_frostBlur.uSrc >= 0) {
            glUniform1i(g_frostBlur.uSrc, 0);
        }
        if (g_frostBlur.uOffset >= 0) {
            glUniform2f(g_frostBlur.uOffset, offset[0], offset[1]);
        }
        if (g_frostBlur.uWeight >= 0) {
            glUniform1fv(g_frostBlur.uWeight, kFrostWeightCount, kFrostWeights);
        }
        if (g_frostBlur.uFit >= 0) {
            glUniform2f(g_frostBlur.uFit, 1.0f, 1.0f); // 中间 RT：恒等
        }
        DrawFullscreenQuad(g_frostBlur.aPosition, g_frostBlur.aTexCoord, g_posVbo, g_idUvVbo);
        errors += DrainGlErrors();
    } else {
        errors++;
    }

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, static_cast<GLsizei>(g_width), static_cast<GLsizei>(g_height));

    if (errors > 0) {
        g_frostErrorStreak += 1;
        OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "frost chain errors=%{public}d streak=%{public}d", errors, g_frostErrorStreak);
        if (g_frostErrorStreak >= kFrostMaxErrorStreak) {
            g_frostBroken = true;
            // 停链即释放：离屏资源 + 三枚 program 一并拆掉（下一轮开关给干净重试）
            DeleteFrostTargets();
            DeleteFrostProgram(g_frostCopy);
            DeleteFrostProgram(g_frostBlur);
            DeleteFrostProgram(g_frostDisplay);
            g_frostBuilt = false;
            OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "frost chain disabled after %{public}d error frames (resources released)",
                kFrostMaxErrorStreak);
        }
        return false; // 错误帧不显示 RT（如实退回正常效果）
    }
    g_frostErrorStreak = 0;
    return true;
}

void DeleteFrostResources()
{
    DeleteFrostTargets();
    DeleteFrostProgram(g_frostCopy);
    DeleteFrostProgram(g_frostBlur);
    DeleteFrostProgram(g_frostDisplay);
    // g_idUvVbo 是整条 2D/高斯链共用的基础 UV（BuildPassPipeline 建），不随 frost 开关释放：
    // 否则关→开时 WotaGlSetFrostEnabled(true) 不重建它，高斯/上屏会在 uvVbo==0 上绑指针导致
    // GL_INVALID_OPERATION 且 8 帧后停链（审查 P1）。其生命周期只属于 EGL 管线（DestroyGlObjects）。
    g_frostBuilt = false;
    g_frostBuildFailed = false;
    g_frostBroken = false;
    g_frostErrorStreak = 0;
}

// ---------------------------------------------------------------- Glow 离屏链（2026-10-28 Glow/降噪批）

/** 两枚离屏 program：① 亮部阈值拷贝（OES）② 可分离高斯（横向/纵向共用，与霜链同一份源） */
FrostProgram g_glowCopy;
FrostProgram g_glowBlur;

/** 上游 core/Glow.kt glowScaleFor：像素 × 帧率越大档越粗（1/2/4）；信息不全按 1（最高质量档） */
int GlowScaleFor(int widthPx, int heightPx, int fps)
{
    if (widthPx <= 0 || heightPx <= 0 || fps <= 0) {
        return 1;
    }
    const int64_t rate = static_cast<int64_t>(widthPx) * heightPx * fps;
    if (rate <= kGlowFullMaxPixelRate) {
        return 1;
    }
    if (rate <= kGlowHalfMaxPixelRate) {
        return 2;
    }
    return 4;
}

/** 单边按档缩：四舍五入、最小 1（与 glowRtSizeInto/glowRawLongestEdge 同源） */
int GlowScaledEdge(int px, int scale)
{
    if (px <= 0 || scale <= 0) {
        return 1;
    }
    const int v = static_cast<int>(std::lround(static_cast<double>(px) / scale));
    return v < 1 ? 1 : v;
}

/**
 * 上游 glowRtSizeInto：÷scale 四舍五入、最小 1；最长边超 GL_MAX_TEXTURE_SIZE 时**按最长边等比**夹取
 * （不许逐维独立夹：那会把 4096×2160 变成椭圆光晕）。非法输入返回 false = 不建链。
 */
bool GlowRtSizeInto(int out[2], int widthPx, int heightPx, int scale, int maxTexture)
{
    if (widthPx <= 0 || heightPx <= 0 || scale <= 0 || maxTexture <= 0) {
        return false;
    }
    int w = GlowScaledEdge(widthPx, scale);
    int h = GlowScaledEdge(heightPx, scale);
    const int longest = w > h ? w : h;
    if (longest > maxTexture) {
        const double k = static_cast<double>(maxTexture) / static_cast<double>(longest);
        w = static_cast<int>(std::lround(static_cast<double>(w) * k));
        h = static_cast<int>(std::lround(static_cast<double>(h) * k));
        if (w < 1) {
            w = 1;
        }
        if (h < 1) {
            h = 1;
        }
    }
    out[0] = w;
    out[1] = h;
    return true;
}

/**
 * **夹取前**的最长边（"到底有没有被夹"的唯一判据）：夹取后最长边恒等于上限，
 * 拿它反推会把 4096×2160 @ 4096 这种"本来就正好"误报成夹取（上游 P2 审查项）。
 */
int GlowRawLongestEdge(int widthPx, int heightPx, int scale)
{
    if (widthPx <= 0 || heightPx <= 0 || scale <= 0) {
        return 0;
    }
    const int a = GlowScaledEdge(widthPx, scale);
    const int b = GlowScaledEdge(heightPx, scale);
    return a > b ? a : b;
}

/** GL_MAX_TEXTURE_SIZE 只查一次；异常值兜 2048（低端机常见上限） */
int GlowMaxTextureEdge()
{
    if (g_glowMaxEdge > 0) {
        return g_glowMaxEdge;
    }
    GLint maxTex = 0;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTex);
    g_glowMaxEdge = maxTex > 0 ? static_cast<int>(maxTex) : 2048;
    return g_glowMaxEdge;
}

/**
 * 上游 fillGlowBlurOffset：width 以 **RT 短边** 为单位，offset = width * (min(1,h/w), min(1,w/h))，
 * 横/纵趟各取一个分量 ⇒ "横向 N 像素、纵向 N 像素"恒成立（圆光晕），换档/换朝向不改观感比例。
 */
bool FillGlowBlurOffset(float out[2], int rtWidthPx, int rtHeightPx, float widthParam, bool horizontal)
{
    if (rtWidthPx <= 0 || rtHeightPx <= 0) {
        return false;
    }
    if (!std::isfinite(widthParam) || widthParam < 0.0f) {
        return false;
    }
    const float sx = std::min(1.0f, static_cast<float>(rtHeightPx) / static_cast<float>(rtWidthPx));
    const float sy = std::min(1.0f, static_cast<float>(rtWidthPx) / static_cast<float>(rtHeightPx));
    if (horizontal) {
        out[0] = widthParam * sx;
        out[1] = 0.0f;
    } else {
        out[0] = 0.0f;
        out[1] = widthParam * sy;
    }
    return true;
}

void DeleteGlowTargets()
{
    if (g_glowDownFbo != 0) {
        glDeleteFramebuffers(1, &g_glowDownFbo);
        g_glowDownFbo = 0;
    }
    if (g_glowScratchFbo != 0) {
        glDeleteFramebuffers(1, &g_glowScratchFbo);
        g_glowScratchFbo = 0;
    }
    if (g_glowResultFbo != 0) {
        glDeleteFramebuffers(1, &g_glowResultFbo);
        g_glowResultFbo = 0;
    }
    if (g_glowDownTex != 0) {
        glDeleteTextures(1, &g_glowDownTex);
        g_glowDownTex = 0;
    }
    if (g_glowScratchTex != 0) {
        glDeleteTextures(1, &g_glowScratchTex);
        g_glowScratchTex = 0;
    }
    if (g_glowResultTex != 0) {
        glDeleteTextures(1, &g_glowResultTex);
        g_glowResultTex = 0;
    }
    g_glowW = 0;
    g_glowH = 0;
    g_glowLastClamped = false;
    g_glowFrameTex = 0;
}

void DeleteGlowPrograms()
{
    DeleteFrostProgram(g_glowCopy);
    DeleteFrostProgram(g_glowBlur);
}

void DeleteGlowResources()
{
    DeleteGlowTargets();
    DeleteGlowPrograms();
}

/** 1×1 全黑 RGBA8：链未就绪时的 uGlowSrc 兜底（贡献为 0，不为它切 program） */
bool EnsureGlowBlackTexture()
{
    if (g_glowBlackTex != 0) {
        return true;
    }
    static const uint8_t kBlackPixel[4] = {0, 0, 0, 0};
    glGenTextures(1, &g_glowBlackTex);
    if (g_glowBlackTex == 0) {
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "glow black texture glGenTextures failed");
        return false;
    }
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, g_glowBlackTex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, kBlackPixel);
    glBindTexture(GL_TEXTURE_2D, 0);
    return true;
}

/**
 * 编两枚 program：拷贝趟 = 亮部阈值（WOTA_GLOW_COPY_FS，**不是**霜那枚纯拷贝）；
 * 模糊趟复用霜链的高斯源（先 highp，失败退 mediump 再编一次）。
 * 编不出来 ⇒ 链停用（broken），主 pass 绑 1×1 黑，预览与成片其余部分零影响。
 */
bool BuildGlowPrograms()
{
    if (g_glowCopy.program != 0 && g_glowBlur.program != 0) {
        return true;
    }
    // 幂等重建：上一轮可能留下 copy != 0 && blur == 0 的半初始化态，先删旧件再编
    DeleteGlowPrograms();
    if (!BuildFrostProgram(WOTA_TEXTURE_VS, WOTA_GLOW_COPY_FS, g_glowCopy, false, "glow-copy")) {
        g_glowBroken = true;
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "glow copy program failed, chain disabled");
        return false;
    }
    bool blurOk = BuildFrostProgram(WOTA_FROST_QUAD_VS,
        WotaFrostGaussianFragment(kFrostWeightCount, true), g_glowBlur, true, "glow-blur");
    if (!blurOk) {
        OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "glow gaussian highp failed, retry mediump");
        blurOk = BuildFrostProgram(WOTA_FROST_QUAD_VS,
            WotaFrostGaussianFragment(kFrostWeightCount, false), g_glowBlur, true, "glow-blur");
    }
    if (!blurOk) {
        DeleteGlowPrograms();
        g_glowBroken = true;
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "glow gaussian program failed, chain disabled");
        return false;
    }
    if (g_glowBlur.uWeight >= 0) {
        glUseProgram(g_glowBlur.program);
        glUniform1fv(g_glowBlur.uWeight, kFrostWeightCount, kFrostWeights);
    }
    DrainGlErrors();
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
        "glow programs ready (gaussian taps=%{public}d)", kFrostWeightCount);
    return true;
}

/**
 * 建/重建三张纹理与三枚 FBO（RGBA8 + LINEAR + CLAMP；完整性只认 glCheckFramebufferStatus）。
 * 内部临时绑 FBO，退出时还原 0（Glow 链排在编码 pass 之前，带脏 FBO 出去会把成片画错面）。
 */
bool EnsureGlowTargets(int w, int h)
{
    DeleteGlowTargets();
    GLuint texIds[3] = {0, 0, 0};
    glGenTextures(3, texIds);
    if (texIds[0] == 0 || texIds[1] == 0 || texIds[2] == 0) {
        for (int i = 0; i < 3; i++) {
            if (texIds[i] != 0) {
                glDeleteTextures(1, &texIds[i]);
            }
        }
        g_glowBroken = true;
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "glow glGenTextures failed, chain disabled");
        return false;
    }
    g_glowDownTex = texIds[0];
    g_glowScratchTex = texIds[1];
    g_glowResultTex = texIds[2];
    const GLuint textures[3] = {g_glowDownTex, g_glowScratchTex, g_glowResultTex};
    for (int i = 0; i < 3; i++) {
        glBindTexture(GL_TEXTURE_2D, textures[i]);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        // 只占位不填数据：内容全靠三趟画出来
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    }
    glBindTexture(GL_TEXTURE_2D, 0);

    GLuint fboIds[3] = {0, 0, 0};
    glGenFramebuffers(3, fboIds);
    g_glowDownFbo = fboIds[0];
    g_glowScratchFbo = fboIds[1];
    g_glowResultFbo = fboIds[2];
    bool complete = g_glowDownFbo != 0 && g_glowScratchFbo != 0 && g_glowResultFbo != 0;
    if (complete) {
        complete = AttachFrostTexture(g_glowDownFbo, g_glowDownTex) &&
            AttachFrostTexture(g_glowScratchFbo, g_glowScratchTex) &&
            AttachFrostTexture(g_glowResultFbo, g_glowResultTex);
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    const bool hadGlError = DrainGlErrors() > 0;
    if (!complete || hadGlError) {
        g_glowBroken = true;
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "glow FBO incomplete, chain disabled (complete=%{public}d glError=%{public}d)",
            complete ? 1 : 0, hadGlError ? 1 : 0);
        DeleteGlowTargets();
        return false;
    }
    g_glowW = w;
    g_glowH = h;
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
        "glow RT %{public}dx%{public}d (three 2D textures, main pass samples one)", w, h);
    return true;
}

/**
 * Glow 离屏链：**每帧一次**（上屏 pass 之前）。三趟、三张纹理、三个 FBO（结果恒在 g_glowResultTex）：
 * ① OES ──(亮部提取 + 降采样到 1/scale, 恒等 UV + uTexMatrix)──▶ bright @ downFbo
 * ② bright ──(横向高斯)──▶ scratch @ scratchFbo
 * ③ scratch ──(纵向高斯)──▶ glow @ resultFbo        ← 主 pass 采这张（单元 kGlowSrcUnit）
 * ③ 写 result 时读的是 scratch，不构成 feedback loop。
 *
 * 三条纪律（照抄上游 GlowBlurChain）：
 * - 尺寸没变不重建（scale/输入尺寸变了才 EnsureGlowTargets）；
 * - 每帧只在 WotaGlRender 跑一次（不放进 DrawScene：那份绘制一帧被调多次）；
 * - 失败语义退化成"没有光晕"：program 编不出来/FBO 不完整/连续 8 帧报错 ⇒ broken 置位并删干净，
 *   主 pass 绑 1×1 黑；开关从 OFF 打到 ON 时给一次干净重试（resetAfterBreak 同义）。
 */
bool TickGlow()
{
    g_glowFrameTex = 0;
    if (!g_glowParams.enabled || g_glowBroken) {
        return false;
    }
    if (g_glowParams.blend <= 0.0f) {
        // 上游 drawGlowPass：blend<=0 时链不跑（贡献恒 0，别白烧三趟）
        return false;
    }
    if (!g_hasFrame || g_nativeImage == nullptr) {
        return false;
    }
    if (!BuildGlowPrograms()) {
        return false;
    }
    int inW = 0;
    int inH = 0;
    GlowInputSize(inW, inH);
    const int scale = GlowScaleFor(inW, inH, kGlowFpsFallback);
    const int maxEdge = GlowMaxTextureEdge();
    int size[2] = {0, 0};
    if (!GlowRtSizeInto(size, inW, inH, scale, maxEdge)) {
        // 尺寸/档非法（还没出帧）：这帧没有光晕，但资源留着别拆
        return false;
    }
    if (size[0] != g_glowW || size[1] != g_glowH) {
        if (!EnsureGlowTargets(size[0], size[1])) {
            return false;
        }
        // "到底有没有被夹"必须用**夹取前**的原始最长边判（上游 P2 审查项）
        g_glowLastClamped = GlowRawLongestEdge(inW, inH, scale) > maxEdge;
        if (g_glowLastClamped) {
            OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "glow RT longest edge clamped to %{public}dpx (GL_MAX_TEXTURE_SIZE, proportional)",
                maxEdge);
        }
    }
    if (g_glowDownFbo == 0 || g_glowScratchFbo == 0 || g_glowResultFbo == 0) {
        return false;
    }
    if (scale != g_glowScaleLogged) {
        g_glowScaleLogged = scale;
        OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "glow chain scale=%{public}d rt=%{public}dx%{public}d (once per frame)",
            scale, g_glowW, g_glowH);
    }
    const int w = g_glowW;
    const int h = g_glowH;
    GLint viewport[4] = {0, 0, 0, 0};
    glGetIntegerv(GL_VIEWPORT, viewport);
    int errors = 0;
    glViewport(0, 0, w, h);

    // ① OES → bright：恒等 UV + uTexMatrix ⇒ RT(q) = B(M·q)，主 pass 的 vUvGlow=aTexCoord 才能逐像素对齐
    glBindFramebuffer(GL_FRAMEBUFFER, g_glowDownFbo);
    glUseProgram(g_glowCopy.program);
    if (g_glowCopy.uTexMatrix >= 0) {
        glUniformMatrix4fv(g_glowCopy.uTexMatrix, 1, GL_FALSE, g_texMatrix);
    }
    if (g_glowCopy.uFit >= 0) {
        glUniform2f(g_glowCopy.uFit, 1.0f, 1.0f); // 离屏 RT：恒等（尺寸本身已按档缩小）
    }
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, g_texId);
    if (g_glowCopy.uFrame >= 0) {
        glUniform1i(g_glowCopy.uFrame, 0);
    }
    // 亮部阈值/软边是每帧都可能变的形状参数（只换 uniform，不重建 program/RT）
    if (g_glowCopy.uGlowThreshold >= 0) {
        glUniform1f(g_glowCopy.uGlowThreshold, g_glowParams.threshold);
    }
    if (g_glowCopy.uGlowSoft >= 0) {
        glUniform1f(g_glowCopy.uGlowSoft, g_glowParams.soft);
    }
    DrawFullscreenQuad(g_glowCopy.aPosition, g_glowCopy.aTexCoord, g_posVbo, g_idUvVbo);
    errors += DrainGlErrors();

    // ②③ 可分离高斯：横 → scratch，纵 → glow（同一枚 program，差别只在 uOffset 方向）
    float offset[2] = {0.0f, 0.0f};
    if (FillGlowBlurOffset(offset, w, h, g_glowParams.width, true)) {
        glBindFramebuffer(GL_FRAMEBUFFER, g_glowScratchFbo);
        glUseProgram(g_glowBlur.program);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, g_glowDownTex);
        if (g_glowBlur.uSrc >= 0) {
            glUniform1i(g_glowBlur.uSrc, 0);
        }
        if (g_glowBlur.uOffset >= 0) {
            glUniform2f(g_glowBlur.uOffset, offset[0], offset[1]);
        }
        if (g_glowBlur.uWeight >= 0) {
            glUniform1fv(g_glowBlur.uWeight, kFrostWeightCount, kFrostWeights);
        }
        if (g_glowBlur.uFit >= 0) {
            glUniform2f(g_glowBlur.uFit, 1.0f, 1.0f);
        }
        DrawFullscreenQuad(g_glowBlur.aPosition, g_glowBlur.aTexCoord, g_posVbo, g_idUvVbo);
        errors += DrainGlErrors();
    } else {
        errors++;
    }
    if (FillGlowBlurOffset(offset, w, h, g_glowParams.width, false)) {
        glBindFramebuffer(GL_FRAMEBUFFER, g_glowResultFbo);
        glUseProgram(g_glowBlur.program);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, g_glowScratchTex);
        if (g_glowBlur.uSrc >= 0) {
            glUniform1i(g_glowBlur.uSrc, 0);
        }
        if (g_glowBlur.uOffset >= 0) {
            glUniform2f(g_glowBlur.uOffset, offset[0], offset[1]);
        }
        if (g_glowBlur.uWeight >= 0) {
            glUniform1fv(g_glowBlur.uWeight, kFrostWeightCount, kFrostWeights);
        }
        if (g_glowBlur.uFit >= 0) {
            glUniform2f(g_glowBlur.uFit, 1.0f, 1.0f);
        }
        DrawFullscreenQuad(g_glowBlur.aPosition, g_glowBlur.aTexCoord, g_posVbo, g_idUvVbo);
        errors += DrainGlErrors();
    } else {
        errors++;
    }

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
    // 把源纹理从单元 0 摘下来：下一帧要往这几张里画（部分驱动按"仍挂在激活单元上"报 feedback）
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, 0);

    if (errors > 0) {
        // 报错帧的 RT 内容不可信（半张图/上帧残留）：撤掉结果，这帧退"无光晕"
        g_glowErrorStreak++;
        OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "glow chain errors=%{public}d streak=%{public}d", errors, g_glowErrorStreak);
        if (g_glowErrorStreak >= kGlowMaxErrorStreak) {
            g_glowBroken = true;
            DeleteGlowResources();
            OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "glow chain disabled after %{public}d error frames (resources released)",
                kGlowMaxErrorStreak);
        }
        return false;
    }
    g_glowErrorStreak = 0;
    g_glowFrameTex = g_glowResultTex;
    return true;
}

/**
 * 新会话复位请求态（P3）：黑名单/效果请求/曲线开关/参数/几何/毛玻璃都不得跨页面残留，
 * 让重进的页面拥有一次干净重试（例如瞬态驱动失败导致的黑名单）。
 */
void ResetSessionState()
{
    g_effectBlacklisted = -1;
    g_effectRequested = kEffectNone;
    g_curveEnabled = false;
    g_curveDirty = false;
    g_curveUploaded = false;
    g_curveFailed = false;
    g_zebraThreshold = 0.6f;
    g_zebraDensity = 1;
    g_peakingStrength = 0.6f;
    g_peakingColor = 4;
    g_sensorDeg = 0;
    g_displayDeg = 0;
    g_fitX = 1.0f;
    g_fitY = 1.0f;
    g_stRotDeg = 0;
    g_mirrored = false;
    g_geomKey = -1;
    g_frostRequested = false;
    g_frostErrorStreak = 0;
    // 2026-10-28 Glow/降噪批：参数与档位不跨页面残留（UI 重进页会重新下发）
    g_glowParams = GlowParams();
    g_denoiseLevel = WOTA_DENOISE_OFF;
    g_glowBroken = false;
    g_glowErrorStreak = 0;
    g_glowFrameTex = 0;
    g_glowScaleLogged = 0;
    g_glowMaxEdge = 0;
    g_glowLastClamped = false;
}

bool BuildPassPipeline()
{
    if (!BuildProgramLocs(WOTA_TEXTURE_VS, WOTA_PASS_THROUGH_FS, g_passLocs)) {
        return false;
    }
    glUseProgram(g_passLocs.program);
    if (g_passLocs.uFrame >= 0) {
        glUniform1i(g_passLocs.uFrame, 0);
    }
    // Glow 兜底纹理（1×1 黑）随管线建、随 DestroyGlObjects 删：链未就绪时主 pass 直接绑它
    EnsureGlowBlackTexture();
    // GLES3 下绘制要求绑定 VAO；ES2 上下文没有 VAO，逐帧绑 VBO + attrib 指针
    g_useVao = g_clientVersion >= 3;
    if (g_useVao) {
        glGenVertexArrays(1, &g_vao);
        glBindVertexArray(g_vao);
    }
    glGenBuffers(1, &g_posVbo);
    glBindBuffer(GL_ARRAY_BUFFER, g_posVbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(kQuadPositions), kQuadPositions, GL_STATIC_DRAW);
    glGenBuffers(1, &g_uvVbo);
    glBindBuffer(GL_ARRAY_BUFFER, g_uvVbo);
    glBufferData(GL_ARRAY_BUFFER, 4 * 2 * sizeof(float), nullptr, GL_DYNAMIC_DRAW);
    // GL-4：编码面独立 UV（deg=stRotDeg、不镜像），与上屏 UV 分开缓存/更新
    glGenBuffers(1, &g_encUvVbo);
    glBindBuffer(GL_ARRAY_BUFFER, g_encUvVbo);
    glBufferData(GL_ARRAY_BUFFER, 4 * 2 * sizeof(float), nullptr, GL_DYNAMIC_DRAW);
    g_encGeomKey = -1;
    // 离屏链恒等 UV（拷贝趟已烘朝向；高斯/2D 直通趟不再旋转）
    static const float kIdUv[8] = {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f};
    glGenBuffers(1, &g_idUvVbo);
    glBindBuffer(GL_ARRAY_BUFFER, g_idUvVbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(kIdUv), kIdUv, GL_STATIC_DRAW);
    if (g_passLocs.aPosition >= 0) {
        glEnableVertexAttribArray(static_cast<GLuint>(g_passLocs.aPosition));
        glBindBuffer(GL_ARRAY_BUFFER, g_posVbo);
        glVertexAttribPointer(static_cast<GLuint>(g_passLocs.aPosition), 2, GL_FLOAT, GL_FALSE,
            2 * sizeof(float), reinterpret_cast<const void*>(0));
    }
    if (g_passLocs.aTexCoord >= 0) {
        glEnableVertexAttribArray(static_cast<GLuint>(g_passLocs.aTexCoord));
        glBindBuffer(GL_ARRAY_BUFFER, g_uvVbo);
        glVertexAttribPointer(static_cast<GLuint>(g_passLocs.aTexCoord), 2, GL_FLOAT, GL_FALSE,
            2 * sizeof(float), reinterpret_cast<const void*>(0));
    }
    g_geomKey = -1;
    RebuildTexCoords();
    return true;
}

/** NativeImage 帧可用回调：头文件明令回调内不许调其他接口，只置原子标志 */
void OnFrameAvailableCB(void* context)
{
    (void)context;
    g_glCallbacks++;
    g_frameAvailable.store(true);
}

/** 建 external OES 纹理 + NativeImage（须在 EGL 就绪、上下文 current 的线程调用） */
bool CreateNativeImage()
{
    g_nativeImageError.clear();
    glGenTextures(1, &g_texId);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, g_texId);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    g_nativeImage = OH_NativeImage_Create(g_texId, GL_TEXTURE_EXTERNAL_OES);
    if (g_nativeImage == nullptr) {
        g_nativeImageError = "create-failed";
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "OH_NativeImage_Create failed");
        return false;
    }
    OH_OnFrameAvailableListener listener;
    listener.context = nullptr;
    listener.onFrameAvailable = OnFrameAvailableCB;
    if (OH_NativeImage_SetOnFrameAvailableListener(g_nativeImage, listener) != 0) {
        g_nativeImageError = "set-listener-failed";
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "SetOnFrameAvailableListener failed");
        return false;
    }
    uint64_t surfaceId = 0;
    if (OH_NativeImage_GetSurfaceId(g_nativeImage, &surfaceId) == 0) {
        g_inputSurfaceId = std::to_string(surfaceId);
        OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "native image created surfaceId=%{public}s", g_inputSurfaceId.c_str());
    } else {
        g_nativeImageError = "get-surface-id-failed";
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "OH_NativeImage_GetSurfaceId failed");
        return false;
    }
    return true;
}

/** 拆 NativeImage：顺序 = 反注册 listener → destroy → 删纹理（均在 context current 时） */
void DestroyNativeImage()
{
    if (g_nativeImage != nullptr) {
        OH_NativeImage_UnsetOnFrameAvailableListener(g_nativeImage);
        OH_NativeImage_Destroy(&g_nativeImage);
        g_nativeImage = nullptr;
    }
    if (g_texId != 0) {
        glDeleteTextures(1, &g_texId);
        g_texId = 0;
    }
    g_hasFrame = false;
    g_frameAvailable.store(false);
    g_inputSurfaceId.clear();
}

/** 以指定 GLES 客户端版本建 context + surface + program；任一步失败整体回收返回 false */
bool TryInitWithVersion(void* window, int clientVersion)
{
    const EGLint surfaceAttribs[] = {EGL_NONE};
    g_surface = eglCreateWindowSurface(g_display, g_eglConfig,
        reinterpret_cast<EGLNativeWindowType>(window), surfaceAttribs);
    if (g_surface == EGL_NO_SURFACE) {
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "eglCreateWindowSurface failed err=%{public}d (es%{public}d)",
            static_cast<int>(eglGetError()), clientVersion);
        return false;
    }
    // 上下文属性显式成对给出：ES3 优先，失败退 ES2 重试一次（策略照上游）
    const EGLint contextAttribsEs3[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    const EGLint contextAttribsEs2[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    const EGLint* contextAttribs = clientVersion >= 3 ? contextAttribsEs3 : contextAttribsEs2;
    g_context = eglCreateContext(g_display, g_eglConfig, EGL_NO_CONTEXT, contextAttribs);
    if (g_context == EGL_NO_CONTEXT) {
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "eglCreateContext failed err=%{public}d (es%{public}d)",
            static_cast<int>(eglGetError()), clientVersion);
        eglDestroySurface(g_display, g_surface);
        g_surface = EGL_NO_SURFACE;
        return false;
    }
    if (eglMakeCurrent(g_display, g_surface, g_surface, g_context) != EGL_TRUE) {
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "eglMakeCurrent failed err=%{public}d (es%{public}d)",
            static_cast<int>(eglGetError()), clientVersion);
        eglDestroyContext(g_display, g_context);
        g_context = EGL_NO_CONTEXT;
        eglDestroySurface(g_display, g_surface);
        g_surface = EGL_NO_SURFACE;
        return false;
    }
    g_clientVersion = clientVersion;
    if (!BuildPassPipeline()) {
        OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "pass program failed on es%{public}d context", clientVersion);
        eglMakeCurrent(g_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroyContext(g_display, g_context);
        g_context = EGL_NO_CONTEXT;
        eglDestroySurface(g_display, g_surface);
        g_surface = EGL_NO_SURFACE;
        return false;
    }
    return true;
}

void DestroyGlObjects()
{
    DeleteFrostResources();
    // Glow 链（2026-10-28 批）：三 RT + 两 program + 1×1 黑纹理，同样必须在 EGL 拆除之前删
    DeleteGlowResources();
    if (g_glowBlackTex != 0) {
        glDeleteTextures(1, &g_glowBlackTex);
        g_glowBlackTex = 0;
    }
    g_glowW = 0;
    g_glowH = 0;
    g_glowBroken = false;
    g_glowErrorStreak = 0;
    g_glowFrameTex = 0;
    g_glowMaxEdge = 0;
    g_glowLastClamped = false;
    // 直方图迷你 RT 必须在 EGL 拆除**之前**删（需现存当前上下文；5742c0f 释放顺序口径）
    if (g_histFbo != 0) {
        glDeleteFramebuffers(1, &g_histFbo);
        g_histFbo = 0;
    }
    if (g_histTex != 0) {
        glDeleteTextures(1, &g_histTex);
        g_histTex = 0;
    }
    {
        std::lock_guard<std::mutex> lock(g_histMutex);
        g_histSnapshot.clear();
    }
    g_histLastSampleMs = 0;
    DeleteProgramLocs(g_slotLocs);
    DeleteProgramLocs(g_encSlotLocs);
    g_encSlotCurve = false;
    g_encSlotDenoise = WOTA_DENOISE_OFF;
    DeleteProgramLocs(g_passLocs);
    if (g_curveTexId != 0) {
        glDeleteTextures(1, &g_curveTexId);
        g_curveTexId = 0;
    }
    if (g_stripeTexId != 0) {
        glDeleteTextures(1, &g_stripeTexId);
        g_stripeTexId = 0;
    }
    g_stripeDensity = 0;
    if (g_uvVbo != 0) {
        glDeleteBuffers(1, &g_uvVbo);
        g_uvVbo = 0;
    }
    if (g_encUvVbo != 0) {
        glDeleteBuffers(1, &g_encUvVbo);
        g_encUvVbo = 0;
    }
    if (g_idUvVbo != 0) {
        // 基础 UV 归 EGL 管线生命周期：只在这里删（frost 开关只释放离屏资源/program）
        glDeleteBuffers(1, &g_idUvVbo);
        g_idUvVbo = 0;
    }
    if (g_posVbo != 0) {
        glDeleteBuffers(1, &g_posVbo);
        g_posVbo = 0;
    }
    if (g_vao != 0) {
        glDeleteVertexArrays(1, &g_vao);
        g_vao = 0;
    }
    g_slotEffect = kEffectNone;
    g_slotCurve = false;
    g_slotDenoise = WOTA_DENOISE_OFF;
    g_geomKey = -1;
    g_encGeomKey = -1;
}} // namespace

bool WotaGlInit(void* window, uint64_t width, uint64_t height)
{
    if (g_ready) {
        // 新 window 与当前 EGLSurface 不匹配（页面重建/窗口切换未先触发 destroyed）：
        // 旧 EGLSurface 不能复用，整体重建（登记：正常路径仍是 destroyed→created）
        if (window != g_window) {
            OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "new window (%{public}p) != old (%{public}p), rebuild GL", window, g_window);
            WotaGlDestroy();
        } else {
            WotaGlResize(width, height);
            return true;
        }
    }
    g_window = window;
    g_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (g_display == EGL_NO_DISPLAY || eglInitialize(g_display, nullptr, nullptr) != EGL_TRUE) {
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "eglInitialize failed err=%{public}d", static_cast<int>(eglGetError()));
        return false;
    }
    const EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_NONE
    };
    // 去 alpha fallback（P3 ③）：个别设备/模拟器没有 RGBA8 window config，去掉 alpha 再选一次
    const EGLint configAttribsNoAlpha[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_NONE
    };
    EGLint numConfigs = 0;
    EGLint firstErr = EGL_SUCCESS;
    if (eglChooseConfig(g_display, configAttribs, &g_eglConfig, 1, &numConfigs) != EGL_TRUE ||
        numConfigs < 1) {
        firstErr = eglGetError();
        OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "eglChooseConfig (alpha) failed err=%{public}d configs=%{public}d, retry without alpha",
            static_cast<int>(firstErr), static_cast<int>(numConfigs));
        g_eglConfig = nullptr;
        numConfigs = 0;
        if (eglChooseConfig(g_display, configAttribsNoAlpha, &g_eglConfig, 1, &numConfigs) != EGL_TRUE ||
            numConfigs < 1) {
            OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "eglChooseConfig (no-alpha) failed err=%{public}d configs=%{public}d (first err=%{public}d)",
                static_cast<int>(eglGetError()), static_cast<int>(numConfigs), static_cast<int>(firstErr));
            eglTerminate(g_display);
            g_display = EGL_NO_DISPLAY;
            return false;
        }
    }
    g_width = width;
    g_height = height;
    glViewport(0, 0, static_cast<GLsizei>(width), static_cast<GLsizei>(height));

    // 上下文策略照上游：ES3 优先，失败（含链编拒绝 ES1.00/OES 扩展）退 ES2 重试一次
    bool ok = TryInitWithVersion(window, 3);
    if (!ok) {
        OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "fallback to ES2 context");
        ok = TryInitWithVersion(window, 2);
    }
    if (!ok) {
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "GL init failed on es3 and es2");
        eglTerminate(g_display);
        g_display = EGL_NO_DISPLAY;
        return false;
    }
    glViewport(0, 0, static_cast<GLsizei>(width), static_cast<GLsizei>(height));
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
        "egl ready %{public}ux%{public}u es=%{public}d glVersion=%{public}s",
        static_cast<unsigned>(width), static_cast<unsigned>(height), g_clientVersion,
        reinterpret_cast<const char*>(glGetString(GL_VERSION)));
    g_ready = true;
    g_firstFrameLogged = false;
    g_displayCtxLost = false;
    g_displayCtxLostRetries = 0;
    g_displayCtxAbortIssued = false;
    // 上下文就绪后才建 NativeImage（顺序：上下文 → image；Destroy 反之）
    if (!CreateNativeImage()) {
        OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "GL ready but NativeImage unavailable (no camera frames)");
    }
    return true;
}

void WotaGlResize(uint64_t width, uint64_t height)
{
    if (!g_ready) {
        return;
    }
    g_width = width;
    g_height = height;
    glViewport(0, 0, static_cast<GLsizei>(width), static_cast<GLsizei>(height));
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
        "gl resize %{public}ux%{public}u", static_cast<unsigned>(width), static_cast<unsigned>(height));
}

void WotaGlRender(uint64_t timestamp)
{
    (void)timestamp;
    if (!g_ready) {
        return;
    }
    // 编码 pass 恢复上屏上下文失败后的守卫：先重试绑定，未成功前跳过本帧全部绘制，
    // 防止把上屏几何画到编码面（审查 P3）。
    if (g_displayCtxLost) {
        if (eglMakeCurrent(g_display, g_surface, g_surface, g_context) != EGL_TRUE) {
            g_displayCtxLostRetries++;
            if (g_displayCtxLostRetries == 1) {
                OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                    "display context lost, retry each frame (cap=%{public}d) err=%{public}d",
                    kDisplayCtxRetryCap, static_cast<int>(eglGetError()));
            } else if (g_displayCtxLostRetries >= kDisplayCtxRetryCap && !g_displayCtxAbortIssued) {
                // 不每帧刷同一条：首帧一条 + 达上限一条
                g_displayCtxAbortIssued = true;
                OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                    "display context lost beyond %{public}d retries, abort recording", kDisplayCtxRetryCap);
                CodecRecorder::Instance().Abort("display-context-lost");
            }
            return;
        }
        g_displayCtxLost = false;
        g_displayCtxLostRetries = 0;
        g_displayCtxAbortIssued = false;
        glViewport(0, 0, static_cast<GLsizei>(g_width), static_cast<GLsizei>(g_height));
        OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "display context restored");
    }
    // 帧可用才拉起新帧（回调里只置位）；无帧时仅清屏（不画未完成纹理）
    bool newFrameConsumed = false;
    int64_t frameTs = 0;
    if (g_nativeImage != nullptr && g_frameAvailable.exchange(false)) {
        if (OH_NativeImage_UpdateSurfaceImage(g_nativeImage) == 0) {
            OH_NativeImage_GetTransformMatrix(g_nativeImage, g_texMatrix);
            const int st = StRotationFromMatrix(g_texMatrix);
            if (st >= 0 && st != g_stRotDeg) {
                g_stRotDeg = st;
                g_geomKey = -1;
            }
            g_hasFrame = true;
            newFrameConsumed = true;
            g_glConsumed++;
            // 弧链时间戳：NativeImage → OnFrame 入参 → steady clock（单位异常按微秒归一）
            frameTs = NormalizeArcTimestamp(OH_NativeImage_GetTimestamp(g_nativeImage), timestamp);
            if (g_arcActive && g_arcEffectiveMode != kArcModeOff) {
                if (frameTs < g_lastArcTsNs) {
                    frameTs = g_lastArcTsNs + 1; // 防倒退
                }
                g_lastArcTsNs = frameTs;
                if (g_arcTsLogCount < 3) {
                    g_arcTsLogCount++;
                    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                        "arc frame ts=%{public}lld ns", static_cast<long long>(frameTs));
                }
            }
        } else {
            OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "UpdateSurfaceImage failed");
        }
    }
    RebuildTexCoords();
    glClearColor(0.02f, 0.02f, 0.04f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    if (!g_hasFrame || g_nativeImage == nullptr) {
        return;
    }
    // Glow 离屏链（2026-10-28 批，scene layer）：**每帧一次**，上屏 pass 之前跑；
    // 本帧结果 g_glowFrameTex 由两个槽共享（编码 pass 稍后也用同一张）。关着/未就绪不改任何状态。
    TickGlow();
    // Frost 全屏链（毛玻璃）：成功产出 RT 则直接走 2D 直通上屏，跳过效果程序绘制
    // （登记：全屏版毛玻璃与监视效果互斥一帧；上游是「模糊 RT + 画板」叠加，画板几何随 HUD 批）
    if (g_frostRequested && RunFrostChain()) {
        glUseProgram(g_frostDisplay.program);
        if (g_frostDisplay.uFrame >= 0) {
            glUniform1i(g_frostDisplay.uFrame, 0);
        }
        if (g_frostDisplay.uFit >= 0) {
            glUniform2f(g_frostDisplay.uFit, g_fitX, g_fitY); // 上屏：letterbox 等比收缩
        }
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, g_frostResultTex);
        DrawFullscreenQuad(g_frostDisplay.aPosition, g_frostDisplay.aTexCoord, g_posVbo, g_idUvVbo);
        const EGLBoolean frostSwapped = eglSwapBuffers(g_display, g_surface);
        if (frostSwapped != EGL_TRUE) {
            OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "eglSwapBuffers (frost) failed err=%{public}d", static_cast<int>(eglGetError()));
        }
        if (!g_firstFrameLogged) {
            g_firstFrameLogged = true;
            OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "first frame (frost) %{public}ux%{public}u swap=%{public}d",
                static_cast<unsigned>(g_width), static_cast<unsigned>(g_height),
                frostSwapped == EGL_TRUE ? 1 : 0);
        }
        // 上屏 swap 后第二趟：编码面绘制（仅本 tick 有新相机帧；frost 是上屏专属）
        MaybeDrawEncoderPass(newFrameConsumed, frameTs);
        return;
    }
    SyncEffect();
    const ProgramLocs& locs = g_slotLocs.program != 0 ? g_slotLocs : g_passLocs;
    if (locs.program == 0) {
        return;
    }
    glUseProgram(locs.program);
    if (locs.uTexMatrix >= 0) {
        glUniformMatrix4fv(locs.uTexMatrix, 1, GL_FALSE, g_texMatrix);
    }
    if (locs.uFit >= 0) {
        glUniform2f(locs.uFit, g_fitX, g_fitY); // 上屏：letterbox 等比收缩
    }
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, g_texId);
    if (locs.uFrame >= 0) {
        glUniform1i(locs.uFrame, 0);
    }
    if (locs.program == g_slotLocs.program) {
        if (g_slotEffect == kEffectZebra) {
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, g_stripeTexId);
            if (locs.uStripe >= 0) {
                glUniform1i(locs.uStripe, 1);
            }
            if (locs.uThreshold >= 0) {
                glUniform1f(locs.uThreshold, g_zebraThreshold);
            }
            const float repeat = TileRepeatOf(g_zebraDensity);
            if (locs.uTile >= 0) {
                glUniform2f(locs.uTile, repeat, repeat);
            }
        } else if (g_slotEffect == kEffectPeaking) {
            if (locs.uTexel >= 0) {
                glUniform2f(locs.uTexel, 0.002f, 0.002f);
            }
            if (locs.uStrength >= 0) {
                glUniform1f(locs.uStrength, g_peakingStrength);
            }
            float rgb[3];
            PeakingRgbOf(g_peakingColor, rgb);
            if (locs.uColor >= 0) {
                glUniform3f(locs.uColor, rgb[0], rgb[1], rgb[2]);
            }
        }
        if (g_slotCurve) {
            glActiveTexture(GL_TEXTURE2);
            glBindTexture(GL_TEXTURE_2D, g_curveTexId);
            if (locs.uCurve >= 0) {
                glUniform1i(locs.uCurve, 2);
            }
        }
    }
    // 2026-10-28 Glow/降噪批：降噪档取上屏槽实际档；Glow 段与编码槽同口径（同一张 glowTex）
    BindDenoiseUniforms(locs,
        locs.program == g_slotLocs.program ? g_slotDenoise : WOTA_DENOISE_OFF);
    BindGlowUniforms(locs);
    if (g_useVao) {
        // ES3 每帧重绑 VAO（防后续 GL 状态改动把绘制落到未绑定/旧 VAO）
        glBindVertexArray(g_vao);
    } else {
        // ES2 上下文无 VAO：逐帧重绑 VBO 与 attrib 指针
        if (locs.aPosition >= 0) {
            glEnableVertexAttribArray(static_cast<GLuint>(locs.aPosition));
            glBindBuffer(GL_ARRAY_BUFFER, g_posVbo);
            glVertexAttribPointer(static_cast<GLuint>(locs.aPosition), 2, GL_FLOAT, GL_FALSE,
                2 * sizeof(float), reinterpret_cast<const void*>(0));
        }
        if (locs.aTexCoord >= 0) {
            glEnableVertexAttribArray(static_cast<GLuint>(locs.aTexCoord));
            glBindBuffer(GL_ARRAY_BUFFER, g_uvVbo);
            glVertexAttribPointer(static_cast<GLuint>(locs.aTexCoord), 2, GL_FLOAT, GL_FALSE,
                2 * sizeof(float), reinterpret_cast<const void*>(0));
        }
    }
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    if (locs.uGlowSrc >= 0) {
        UnbindGlowUnit();
    }
    EGLBoolean swapped = eglSwapBuffers(g_display, g_surface);
    if (swapped != EGL_TRUE) {
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "eglSwapBuffers failed err=%{public}d", static_cast<int>(eglGetError()));
    }
    if (!g_firstFrameLogged) {
        g_firstFrameLogged = true;
        // 方向链路首帧汇总（上游 81cb3a5 的 OHOS 对应物；本侧无 stMatrix 面——方向由
        // sensor/display/mirror 直接下发，恒等族矩阵识别 N/A）：真机排查「前摄方向不对」
        // 第一眼就看这行的四个量 + 编码容器角是否自洽
        OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "first frame %{public}ux%{public}u swap=%{public}d orient sensor=%{public}d display=%{public}d "
            "mirror=%{public}d fit=%{public}.4f/%{public}.4f enc=%{public}dx%{public}d encRot=%{public}d",
            static_cast<unsigned>(g_width), static_cast<unsigned>(g_height),
            swapped == EGL_TRUE ? 1 : 0,
            g_sensorDeg, g_displayDeg, g_mirrored ? 1 : 0,
            g_fitX, g_fitY, g_encW, g_encH, WotaGlEncoderRotation());
    }
    // 直方图采样（曲线弹窗开着才真采；250ms 一拍。当前 program = 曲线输出后的画面）
    MaybeSampleHistogram(locs);
    // 上屏 swap 后第二趟：编码面绘制（仅本 tick 有新相机帧、同一效果链）
    MaybeDrawEncoderPass(newFrameConsumed, frameTs);
}

void WotaGlDestroy()
{
    // 录制资源仍活跃（含 writeFailed_ 但尚未 Stop 的态）：surface 不可续，先中止
    if (CodecRecorder::Instance().IsActive()) {
        OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "gl destroy while recorder active, aborting gpu record (surface-lost)");
        CodecRecorder::Instance().AbortOnSurfaceLost();
    }
    WotaGlDetachEncoder();
    if (g_display == EGL_NO_DISPLAY) {
        g_ready = false;
        return;
    }
    // 拆序与建序相反：先拆 NativeImage（其纹理依赖当前上下文），再删 GL 对象、拆 EGL
    DestroyNativeImage();
    if (g_ready) {
        DestroyGlObjects();
    }
    if (eglMakeCurrent(g_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT) != EGL_TRUE) {
        OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "destroy: eglMakeCurrent(release) failed err=%{public}d", static_cast<int>(eglGetError()));
    }
    if (g_context != EGL_NO_CONTEXT) {
        if (eglDestroyContext(g_display, g_context) != EGL_TRUE) {
            OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "destroy: eglDestroyContext failed err=%{public}d", static_cast<int>(eglGetError()));
        }
        g_context = EGL_NO_CONTEXT;
    }
    if (g_surface != EGL_NO_SURFACE) {
        if (eglDestroySurface(g_display, g_surface) != EGL_TRUE) {
            OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "destroy: eglDestroySurface failed err=%{public}d", static_cast<int>(eglGetError()));
        }
        g_surface = EGL_NO_SURFACE;
    }
    if (eglTerminate(g_display) != EGL_TRUE) {
        OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "destroy: eglTerminate failed err=%{public}d", static_cast<int>(eglGetError()));
    }
    g_display = EGL_NO_DISPLAY;
    g_ready = false;
    g_firstFrameLogged = false;
    g_displayCtxLost = false;
    g_displayCtxLostRetries = 0;
    g_displayCtxAbortIssued = false;
    g_nativeImageError.clear();
    g_window = nullptr;
    // 新会话干净重试（P3）：黑名单/请求态/几何在页面上次会话后不得残留
    ResetSessionState();
    // 释放本线程 EGL 绑定（surface 反复重建/页面反复进出不留线程级残留）
    eglReleaseThread();
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "gl destroyed");
}

// ---------------------------------------------------------------- GL-4 编码面（GPU 录制）

bool WotaGlAttachEncoderWindow(OHNativeWindow* window, int width, int height)
{
    if (!g_ready || g_display == EGL_NO_DISPLAY || g_context == EGL_NO_CONTEXT ||
        window == nullptr || width <= 0 || height <= 0) {
        OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "encoder attach rejected ready=%{public}d window=%{public}d %{public}dx%{public}d",
            g_ready ? 1 : 0, window == nullptr ? 0 : 1, width, height);
        return false;
    }
    if (g_encSurface != EGL_NO_SURFACE) {
        WotaGlDetachEncoder(); // 幂等：旧编码面先拆（重录/重挂）
    }
    // 复用初始化时缓存的 EGLConfig（g_eglConfig），不重新 chooseConfig
    const EGLint surfaceAttribs[] = {EGL_NONE};
    g_encSurface = eglCreateWindowSurface(g_display, g_eglConfig,
        reinterpret_cast<EGLNativeWindowType>(window), surfaceAttribs);
    if (g_encSurface == EGL_NO_SURFACE) {
        g_encW = 0;
        g_encH = 0;
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "encoder eglCreateWindowSurface failed err=%{public}d (gpu record unavailable)",
            static_cast<int>(eglGetError()));
        return false;
    }
    if (g_encUvVbo == 0) {
        // 管线未就绪（BuildPassPipeline 未建编码 UV）：不可用
        eglDestroySurface(g_display, g_encSurface);
        g_encSurface = EGL_NO_SURFACE;
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "encoder attach failed: pipeline not ready (uv vbo missing)");
        return false;
    }
    g_encW = width;
    g_encH = height;
    g_encGeomKey = -1;
    g_encFailStreak = 0;
    g_encFirstFrameLogged = false;
    g_displayCtxLost = false;
    g_displayCtxLostRetries = 0;
    g_displayCtxAbortIssued = false;
    // 弧会话账随编码面重挂复位（drops/实测帧率由 setArcConvert 在起录前清零，这里只复位节奏）
    g_encoderFrameIndex = 0;
    g_arcRule.Reset();
    g_arcFlow = ArcRepairFlow(g_arcDstFps > 0 ? g_arcDstFps : 24);
    g_arcSrcFirstTsNs = INT64_MIN;
    g_arcSrcLastTsNs = INT64_MIN;
    g_arcSrcFrames = 0;
    g_lastArcTsNs = INT64_MIN;
    g_arcTsLogCount = 0;
    g_arcActive = g_arcEffectiveMode != kArcModeOff;
    if (g_arcEffectiveMode == kArcModeMend) {
        // 每次释放旧 pass 后重建；最多 2 次（惰性 attach 阶段偶发 GL 资源瞬时失败）
        bool ensured = false;
        for (int attempt = 1; attempt <= 2 && !ensured; attempt++) {
            if (g_arcMend != nullptr) {
                g_arcMend->Release();
                g_arcMend.reset();
            }
            g_arcMend = std::make_unique<ArcMendPass>();
            g_arcMend->SetDeadband(g_arcDeadband);
            ensured = g_arcMend->Ensure(g_encW, g_encH, g_useVao);
            if (!ensured) {
                OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                    "arc mend pass ensure failed on attach attempt=%{public}d", attempt);
            }
        }
        if (!ensured) {
            g_arcMend.reset();
            DegradeArc("mend-ensure");
        }
    }
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
        "encoder surface attached %{public}dx%{public}d arc=%{public}d",
        width, height, g_arcEffectiveMode);
    return true;
}

void WotaGlDetachEncoder()
{
    if (g_encSurface != EGL_NO_SURFACE) {
        if (g_display != EGL_NO_DISPLAY) {
            // 恢复上屏 makeCurrent 再销毁编码面（幂等；上下文已拆时只清指针）
            if (g_surface != EGL_NO_SURFACE && g_context != EGL_NO_CONTEXT) {
                if (eglMakeCurrent(g_display, g_surface, g_surface, g_context) == EGL_TRUE) {
                    g_displayCtxLost = false;
                    g_displayCtxLostRetries = 0;
                    g_displayCtxAbortIssued = false;
                } else {
                    // 绑定失败：置帧级守卫，WotaGlRender 在恢复前不绘制
                    g_displayCtxLost = true;
                    OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                        "detach: restore display context failed err=%{public}d (frame guard armed)",
                        static_cast<int>(eglGetError()));
                }
            }
            if (eglDestroySurface(g_display, g_encSurface) != EGL_TRUE) {
                OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                    "encoder eglDestroySurface failed err=%{public}d", static_cast<int>(eglGetError()));
            }
        }
        g_encSurface = EGL_NO_SURFACE;
        OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "encoder surface detached");
    }
    // 释放补弧离屏资源；保留 drops/实测帧率供停录后 drain，g_arcActive 置 false（下一段 attach 重开）
    if (g_arcMend != nullptr) {
        g_arcMend->Release();
        g_arcMend.reset();
    }
    g_arcActive = false;
    g_encW = 0;
    g_encH = 0;
    g_encGeomKey = -1;
    g_encFailStreak = 0;
    g_encFirstFrameLogged = false;
}

bool WotaGlReady()
{
    return g_ready;
}

std::string WotaGlGetInputSurfaceId()
{
    if (!g_ready || g_nativeImage == nullptr) {
        return std::string();
    }
    return g_inputSurfaceId;
}

std::string WotaGlGetNativeImageError()
{
    return g_nativeImageError;
}

int WotaGlEncoderRotation()
{
    // 与显示几何同一输入源：sensor 来自镜头信息，display 来自 display.rotation×90。
    // 方向常数 v2（见 RebuildTexCoords 头注）：与上屏同式 `sensor − display + (front?0:180)`，
    // 保证容器角与竖屏窗口的上屏转角同一叙事。muxer 只接受 0/90/180/270：按上游
    // normalizeQuarter 四舍五入（45→90、359→0），非 90 倍数 sensorOrientation 不得让
    // 起录直接失败（审查 P2）。
    const int raw = WotaNormalizeDeg(g_displayDeg + (g_mirrored ? 0 : 270));
    const int rounded = WotaNormalizeQuarter(raw);
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
        "encoder rotation raw=%{public}d rounded=%{public}d mirror=%{public}d",
        raw, rounded, g_mirrored ? 1 : 0);
    return rounded;
}

// ---------------------------------------------------------------- GL-4 光弧链在线版（napi 调用）

void WotaGlSetArcConvert(int mode, int dstFps, int deadband)
{
    int m = mode < 0 ? kArcModeOff : (mode > kArcModeDrop ? kArcModeDrop : mode);
    if (m != kArcModeOff && dstFps <= 0) {
        m = kArcModeOff; // dstFps 非法：拒斥回落关闭
    }
    const int db = std::min(kArcDeadbandMax, std::max(0, deadband));
    g_arcRequestedMode = m;
    g_arcEffectiveMode = m;
    g_arcDstFps = (m != kArcModeOff) ? dstFps : 0;
    g_arcDeadband = db;
    g_arcActive = m != kArcModeOff;

    if (g_arcMend != nullptr) {
        g_arcMend->Release();
        g_arcMend.reset();
    }
    const int64_t slotNs = m != kArcModeOff ? 1000000000LL / dstFps : 0;
    g_arcRule = ArcKeepRule(slotNs, ArcKeepToleranceNs(slotNs));
    g_arcFlow = ArcRepairFlow(m != kArcModeOff ? dstFps : 24);
    g_arcDrops.clear();
    g_arcSrcFirstTsNs = INT64_MIN;
    g_arcSrcLastTsNs = INT64_MIN;
    g_arcSrcFrames = 0;
    g_lastArcTsNs = INT64_MIN;
    g_arcTsLogCount = 0;

    // 只配置状态：MEND 的 pass 一律延迟到编码面 attach 时构建（此时才有编码尺寸）。
    // 运行期切换弧档不支持（登记）：本入口仅服务起录前的参数下发。
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
        "arc convert requested=%{public}d effective=%{public}d dstFps=%{public}d deadband=%{public}d mend=%{public}s",
        g_arcRequestedMode, g_arcEffectiveMode, dstFps, db,
        m == kArcModeMend ? "deferred-attach" : "none");
}

int WotaGlFlushArcPending()
{
    if (g_arcEffectiveMode != kArcModeMend || g_arcMend == nullptr ||
        g_encSurface == EGL_NO_SURFACE || !g_arcFlow.HasPending()) {
        return 0;
    }
    if (eglMakeCurrent(g_display, g_encSurface, g_encSurface, g_context) != EGL_TRUE) {
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "flush arc pending: encoder makeCurrent failed err=%{public}d",
            static_cast<int>(eglGetError()));
        g_arcFlow.OnSourceEos();
        RestoreDisplayContext(); // 统一守卫：恢复失败置帧级丢失标志并中止录制
        return 0;
    }
    int flushed = 0;
    glViewport(0, 0, g_encW, g_encH); // DrawPending 画进当前编码面（flush 路径不经过 cur 渲染）
    if (g_arcFlow.HasAcc() && !g_arcMend->FoldAccIntoPending()) {
        OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "flush arc pending: fold acc failed, emit without arc");
    }
    if (g_arcMend->DrawPending()) {
        const EGLBoolean swapped = eglSwapBuffers(g_display, g_encSurface);
        if (swapped == EGL_TRUE) {
            g_encoderSwapOk++;
            g_encoderFrameIndex++;
            g_arcDrops.push_back({static_cast<int32_t>(std::max(0, g_encoderFrameIndex - 1)),
                g_arcRule.TakeDrops()});
            flushed = 1;
        } else {
            g_encoderSwapFail++;
            OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "flush arc pending: swap failed err=%{public}d", static_cast<int>(eglGetError()));
        }
    } else {
        g_encoderSwapFail++;
        OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "flush arc pending: DrawPending failed, last frame dropped");
    }
    g_arcFlow.OnSourceEos(); // 无论成败清状态
    RestoreDisplayContext();
    return flushed;
}

std::string WotaGlDrainArcDrops()
{
    if (g_arcDrops.empty()) {
        return std::string();
    }
    std::string out;
    for (size_t i = 0; i < g_arcDrops.size(); i++) {
        if (i > 0) {
            out += ';';
        }
        out += std::to_string(g_arcDrops[i].out);
        out += ',';
        out += std::to_string(g_arcDrops[i].n);
    }
    g_arcDrops.clear();
    return out;
}

int WotaGlMeasuredArcSrcFps()
{
    if (g_arcSrcFrames < 2 || g_arcSrcFirstTsNs == INT64_MIN ||
        g_arcSrcLastTsNs <= g_arcSrcFirstTsNs) {
        return 0;
    }
    const int64_t span = g_arcSrcLastTsNs - g_arcSrcFirstTsNs;
    int64_t fps = ((g_arcSrcFrames - 1) * 1000000000LL + span / 2) / span;
    if (fps < 1) {
        fps = 1;
    }
    if (fps > 480) {
        fps = 480;
    }
    return static_cast<int>(fps);
}

std::string WotaGlGetFrameCounters()
{
    return std::to_string(g_glCallbacks.load()) + "," +
        std::to_string(g_glConsumed.load()) + "," +
        std::to_string(g_encoderSwapOk.load()) + "," +
        std::to_string(g_encoderSwapFail.load()) + "," +
        std::to_string(g_arcDropped.load());
}

int WotaGlGetArcEffectiveMode()
{
    return g_arcEffectiveMode;
}

// ---------------------------------------------------------------- GL-2 外部入口（napi 调用，只写状态）

void WotaGlSetDisplayGeometry(int sensorDeg, int displayDeg, bool mirrored)
{
    g_sensorDeg = sensorDeg;
    g_displayDeg = displayDeg;
    g_mirrored = mirrored;
    g_geomKey = -1;
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
        "geometry sensor=%{public}d display=%{public}d mirror=%{public}d",
        sensorDeg, displayDeg, mirrored ? 1 : 0);
}

/**
 * WLB（letterbox）：上屏等比收缩因子（NDC 半域；1,1 = 铺满）。预览档比例与表面比例不一致时
 * 由 ArkTS 侧算出并下发，黑边只出现在预览上屏，编码面恒满幅。
 */
void WotaGlSetDisplayFit(float fitX, float fitY)
{
    const float x = (fitX > 0.0f && fitX <= 1.0f) ? fitX : 1.0f;
    const float y = (fitY > 0.0f && fitY <= 1.0f) ? fitY : 1.0f;
    if (x == g_fitX && y == g_fitY) {
        return;
    }
    g_fitX = x;
    g_fitY = y;
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
        "display fit x=%{public}.4f y=%{public}.4f", x, y);
}

void WotaGlSetEffect(int effect)
{
    g_effectRequested = effect < kEffectNone ? kEffectNone : (effect > kEffectPeaking ? kEffectPeaking : effect);
}

void WotaGlSetCurveEnabled(bool on)
{
    if (on && !g_curveEnabled) {
        // 用户动作（关→开）：给曲线一次干净重试（不在帧循环里复位，避免每帧重编）
        g_curveFailed = false;
    }
    g_curveEnabled = on;
}

void WotaGlSetZebraParams(float threshold, int density)
{
    g_zebraThreshold = threshold < 0.0f ? 0.0f : (threshold > 1.0f ? 1.0f : threshold);
    g_zebraDensity = SanitizeDensity(density);
}

void WotaGlSetPeakingParams(float strength, int colorIndex)
{
    g_peakingStrength = strength < 0.0f ? 0.0f : (strength > 1.0f ? 1.0f : strength);
    g_peakingColor = colorIndex < 0 ? 0 : (colorIndex > 4 ? 4 : colorIndex);
}

/**
 * 直方图采样开关（曲线弹窗可见时开；写在 napi 线程、读在 GL 线程）。关 = 停采 + 清快照
 * （省电 + 不留旧数据，上游 setHistogramEnabled 同口径）。
 */
void WotaGlSetHistogramEnabled(bool on)
{
    g_histRequested = on;
    if (!on) {
        std::lock_guard<std::mutex> lock(g_histMutex);
        g_histSnapshot.clear();
    }
}

/**
 * 取直方图快照（3×64 计数，R/G/B 依次；无样本返回 false）。出参 caller 提供容量 ≥192 的缓冲。
 * 归一化在 ArkTS 侧走 core/HistogramMath.normalizedHeights（各通道内按各自最大值归一）。
 */
bool WotaGlGetHistogramSnapshot(int32_t* out, int capacity)
{
    if (out == nullptr || capacity < kHistValues) {
        return false;
    }
    std::lock_guard<std::mutex> lock(g_histMutex);
    if (static_cast<int>(g_histSnapshot.size()) < kHistValues) {
        return false;
    }
    for (int i = 0; i < kHistValues; i++) {
        out[i] = g_histSnapshot[i];
    }
    return true;
}

/**
 * 毛玻璃开关（GL-3/开关=重试语义，登记）：
 * - false→true：复位 broken/buildFailed/errorStreak（用户动作给一次干净重试），并就绪时**当场预编**
 *   三枚 program（UI 线程上下文 current；失败登记，OnFrame 懒建仍兜底）；
 * - true→false：释放离屏 targets 与三枚 program（LUT/条纹等效果资源不动）。
 */
void WotaGlSetFrostEnabled(bool on)
{
    const bool was = g_frostRequested;
    g_frostRequested = on;
    if (on && !was) {
        g_frostBroken = false;
        g_frostBuildFailed = false;
        g_frostErrorStreak = 0;
        if (g_ready && !g_frostBuilt && !g_frostBuildFailed) {
            BuildFrostPrograms();
        }
    } else if (!on && was) {
        DeleteFrostResources();
    }
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
        "frost requested=%{public}d (switch=retry semantics)", on ? 1 : 0);
}

bool WotaGlUploadCurveLut(const void* data, size_t length)
{
    if (data == nullptr || length != sizeof(g_curveData)) {
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "curve LUT rejected length=%{public}zu (want %{public}zu)", length, sizeof(g_curveData));
        return false;
    }
    std::memcpy(g_curveData, data, sizeof(g_curveData));
    g_curveDirty = true;
    // 用户动作（新 LUT 到达）：清掉上次的失败标记，允许重试（另一处复位在 SetCurveEnabled 关→开）
    g_curveFailed = false;
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "curve LUT queued 256x1 RGB");
    return true;
}

// ---------------------------------------------------------------- Glow/降噪外部入口（2026-10-28 批）

/** 参数域钳（上游 core/Glow.kt sanitize：NaN/Inf 回落该字段出厂值，其余钳进安全域） */
static float GlowClampF(float value, float lo, float hi, float fallback)
{
    if (!std::isfinite(value)) {
        return fallback;
    }
    if (value < lo) {
        return lo;
    }
    if (value > hi) {
        return hi;
    }
    return value;
}

/**
 * 写入 Glow 参数（上游 GlowProvider.setGlowParams 的 OHOS 对应物）。
 * **形状参数只换 uniform**（不重建 program）；打开动作（false→true）给一次干净重试：
 * 复位 broken/errorStreak 并就绪时当场预编两枚 program（用户动作带上下文 current，失败登记，
 * OnFrame 懒建仍兜底）。关掉只停跑、不删资源（再打开不重建、不闪）。
 */
void WotaGlSetGlowParams(int enabled, float threshold, float soft, float width, float gain,
    float add, float freq, float blend, uint32_t colorA, uint32_t colorB)
{
    const bool was = g_glowParams.enabled;
    g_glowParams.enabled = enabled != 0;
    g_glowParams.threshold = GlowClampF(threshold, 0.0f, 1.0f, 0.5f);
    g_glowParams.soft = GlowClampF(soft, 0.0f, 0.5f, 0.05f);
    g_glowParams.width = GlowClampF(width, 0.0f, 0.2f, 0.02f);
    g_glowParams.gain = GlowClampF(gain, 0.0f, 4.0f, 1.0f);
    g_glowParams.add = GlowClampF(add, 0.0f, 1.0f, 0.0f);
    g_glowParams.freq = GlowClampF(freq, 0.0f, 64.0f, 0.0f);
    g_glowParams.blend = GlowClampF(blend, 0.0f, 1.0f, 0.6f);
    g_glowParams.colorA = colorA & 0xFFFFFFFFu;
    g_glowParams.colorB = colorB & 0xFFFFFFFFu;
    if (g_glowParams.enabled && !was) {
        // 显式开启 = 给坏键/停用链一次重试（同一坏键不逐帧重试的口径不变）
        g_glowBroken = false;
        g_glowErrorStreak = 0;
        if (g_ready) {
            BuildGlowPrograms();
        }
    }
    if (!g_glowParams.enabled) {
        g_glowFrameTex = 0; // 停跑即撤结果（资源保留，再开不重建）
    }
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
        "glow params enabled=%{public}d th=%{public}.3f soft=%{public}.3f width=%{public}.3f "
        "gain=%{public}.3f add=%{public}.3f freq=%{public}.2f blend=%{public}.3f a=%{public}u b=%{public}u",
        g_glowParams.enabled ? 1 : 0, g_glowParams.threshold, g_glowParams.soft, g_glowParams.width,
        g_glowParams.gain, g_glowParams.add, g_glowParams.freq, g_glowParams.blend,
        static_cast<unsigned>(g_glowParams.colorA), static_cast<unsigned>(g_glowParams.colorB));
}

/**
 * Glow 状态串（上游 core/Glow.kt GlowStatus 的 OHOS 对应物，ArkTS 侧按字段解析）：
 * `off` 或 `on:<broken0/1>:<scale>:<inW>:<inH>:<fps>:<rtW>:<rtH>:<clamped0/1>`。
 * fps 本侧无编码帧率观测面，按上游「未知按 30」口径恒报 30。
 * 只读：UI 线程（napi）读，GL 线程写；本侧 setter/OnFrame 同为 UI 线程（登记契约），无并发。
 */
std::string WotaGlGetGlowStatus()
{
    if (!g_glowParams.enabled) {
        return "off";
    }
    int inW = 0;
    int inH = 0;
    GlowInputSize(inW, inH);
    const int scale = GlowScaleFor(inW, inH, kGlowFpsFallback);
    std::string status = "on:";
    status += g_glowBroken ? "1" : "0";
    status += ":";
    status += std::to_string(scale);
    status += ":";
    status += std::to_string(inW);
    status += ":";
    status += std::to_string(inH);
    status += ":";
    status += std::to_string(kGlowFpsFallback);
    status += ":";
    status += std::to_string(g_glowW);
    status += ":";
    status += std::to_string(g_glowH);
    status += ":";
    status += g_glowLastClamped ? "1" : "0";
    return status;
}

/**
 * 画面降噪档（0=关 / 1=完整核 / 2=轻量核；上游 setVideoDenoise + denoiseLevelNow 的 OHOS 对应物）。
 * 只写请求态：档位是 program 槽键 (effect, curve, denoise) 的第三维，下一帧 SyncEffect 按新键
 * 重建/回收变体（level=0 时槽键归 0，旧的降噪 program 由 DeleteProgramLocs 一并清掉）。
 * 非法值钳进 0..2（0 当关处理，不留未知档）。
 */
void WotaGlSetDenoiseLevel(int level)
{
    const int safe = level < WOTA_DENOISE_OFF ? WOTA_DENOISE_OFF
        : (level > WOTA_DENOISE_LITE ? WOTA_DENOISE_LITE : level);
    if (safe == g_denoiseLevel) {
        return;
    }
    g_denoiseLevel = safe;
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
        "video denoise level=%{public}d (0=off 1=full 2=lite; applied on next frame)", safe);
}
