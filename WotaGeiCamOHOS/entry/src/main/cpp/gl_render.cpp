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

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h> // GL_TEXTURE_EXTERNAL_OES
#include <hilog/log.h>
#include <native_image/native_image.h>

#include <atomic>
#include <cmath>
#include <cstring>
#include <string>

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

/** 程序槽与 location 缓存（单效果槽：同一时刻只有一个效果 program） */
struct ProgramLocs {
    GLuint program = 0;
    GLint aPosition = -1;
    GLint aTexCoord = -1;
    GLint uTexMatrix = -1;
    GLint uFrame = -1;
    GLint uCurve = -1;
    GLint uStripe = -1;
    GLint uThreshold = -1;
    GLint uTile = -1;
    GLint uTexel = -1;
    GLint uStrength = -1;
    GLint uColor = -1;
};
ProgramLocs g_passLocs;   // 直通（链接失败即无画面，永不删除）
ProgramLocs g_slotLocs;   // 效果槽（曲线/斑马/峰值之一）
int g_slotEffect = kEffectNone;
bool g_slotCurve = false;

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

int NormalizeDeg(int deg)
{
    return ((deg % 360) + 360) % 360;
}

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
    switch (NormalizeDeg(degrees)) {
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
 */
void RebuildTexCoords()
{
    const int deg = NormalizeDeg(g_sensorDeg - g_displayDeg + g_stRotDeg);
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
    locs.uFrame = glGetUniformLocation(locs.program, "uFrame");
    locs.uCurve = glGetUniformLocation(locs.program, "uCurve");
    locs.uStripe = glGetUniformLocation(locs.program, "uStripe");
    locs.uThreshold = glGetUniformLocation(locs.program, "uThreshold");
    locs.uTile = glGetUniformLocation(locs.program, "uTile");
    locs.uTexel = glGetUniformLocation(locs.program, "uTexel");
    locs.uStrength = glGetUniformLocation(locs.program, "uStrength");
    locs.uColor = glGetUniformLocation(locs.program, "uColor");
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
 * 单效果槽程序切换（上游退避链）：目标效果+曲线变体失败 → 退无曲线变体；再失败且非 NONE →
 * 黑名单该效果并回直通（下一帧 target 归 NONE）；曲线单独模式失败 → 回直通。
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
    if (g_slotLocs.program != 0 && target == g_slotEffect && wantCurve == g_slotCurve) {
        if (target == kEffectZebra) {
            EnsureStripeTexture(g_zebraDensity);
        }
        return;
    }
    DeleteProgramLocs(g_slotLocs);
    g_slotEffect = kEffectNone;
    g_slotCurve = false;
    if (target == kEffectNone && !wantCurve) {
        return; // 直通（g_passLocs 常驻）
    }

    bool curve = wantCurve;
    bool built = false;
    if (target == kEffectNone) {
        built = BuildProgramLocs(WOTA_TEXTURE_VS, WOTA_CURVE_FS, g_slotLocs);
        if (!built) {
            // CURVE_FS 直链失败：本 surface 内不再重试曲线（避免每帧重编）
            g_curveFailed = true;
            OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "curve program failed, disabled until surface recreate");
        }
    } else if (target == kEffectZebra) {
        built = BuildProgramLocs(WOTA_TEXTURE_VS, WotaZebraFragment(curve), g_slotLocs);
        if (!built && curve) {
            OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "zebra+curve failed, retry without curve");
            curve = false;
            built = BuildProgramLocs(WOTA_TEXTURE_VS, WotaZebraFragment(false), g_slotLocs);
            g_curveFailed = true; // 曲线变体失败：本 surface 内曲线不再尝试
        }
    } else {
        built = BuildProgramLocs(WOTA_TEXTURE_VS, WotaPeakingFragment(curve), g_slotLocs);
        if (!built && curve) {
            OH_LOG_Print(LOG_APP, LOG_WARN, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "peaking+curve failed, retry without curve");
            curve = false;
            built = BuildProgramLocs(WOTA_TEXTURE_VS, WotaPeakingFragment(false), g_slotLocs);
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
    if (target == kEffectZebra) {
        EnsureStripeTexture(g_zebraDensity);
    }
    if (curve) {
        EnsureCurveTexture();
    }
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
        "program slot effect=%{public}d curve=%{public}d", g_slotEffect, g_slotCurve ? 1 : 0);
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
    GLint uSrc = -1;
    GLint uOffset = -1;
    GLint uWeight = -1;
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

/** 编一枚离屏 program；失败打印 info log 返回 false */
bool BuildFrostProgram(const char* vsSrc, const std::string& fsSrc, FrostProgram& out, bool needWeight)
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
            "frost program link failed log=%{public}s", info);
        glDeleteProgram(program);
        return false;
    }
    out.program = program;
    out.aPosition = glGetAttribLocation(program, "aPosition");
    out.aTexCoord = glGetAttribLocation(program, "aTexCoord");
    out.uFrame = glGetUniformLocation(program, "uFrame");
    out.uTexMatrix = glGetUniformLocation(program, "uTexMatrix");
    out.uSrc = glGetUniformLocation(program, "uSrc");
    out.uOffset = glGetUniformLocation(program, "uOffset");
    out.uWeight = needWeight ? glGetUniformLocation(program, "uWeight[0]") : -1;
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
            OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
                "frost chain disabled after %{public}d error frames", kFrostMaxErrorStreak);
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
    if (g_idUvVbo != 0) {
        glDeleteBuffers(1, &g_idUvVbo);
        g_idUvVbo = 0;
    }
    g_frostBuilt = false;
    g_frostBuildFailed = false;
    g_frostBroken = false;
    g_frostErrorStreak = 0;
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
    g_stRotDeg = 0;
    g_mirrored = false;
    g_geomKey = -1;
    g_frostRequested = false;
    g_frostErrorStreak = 0;
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
    DeleteProgramLocs(g_slotLocs);
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
    g_geomKey = -1;
}
} // namespace

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
    // 帧可用才拉起新帧（回调里只置位）；无帧时仅清屏（不画未完成纹理）
    if (g_nativeImage != nullptr && g_frameAvailable.exchange(false)) {
        if (OH_NativeImage_UpdateSurfaceImage(g_nativeImage) == 0) {
            OH_NativeImage_GetTransformMatrix(g_nativeImage, g_texMatrix);
            const int st = StRotationFromMatrix(g_texMatrix);
            if (st >= 0 && st != g_stRotDeg) {
                g_stRotDeg = st;
                g_geomKey = -1;
            }
            g_hasFrame = true;
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
    // Frost 全屏链（毛玻璃）：成功产出 RT 则直接走 2D 直通上屏，跳过效果程序绘制
    // （登记：全屏版毛玻璃与监视效果互斥一帧；上游是「模糊 RT + 画板」叠加，画板几何随 HUD 批）
    if (g_frostRequested && RunFrostChain()) {
        glUseProgram(g_frostDisplay.program);
        if (g_frostDisplay.uFrame >= 0) {
            glUniform1i(g_frostDisplay.uFrame, 0);
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
    EGLBoolean swapped = eglSwapBuffers(g_display, g_surface);
    if (swapped != EGL_TRUE) {
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "eglSwapBuffers failed err=%{public}d", static_cast<int>(eglGetError()));
    }
    if (!g_firstFrameLogged) {
        g_firstFrameLogged = true;
        OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "first frame %{public}ux%{public}u swap=%{public}d",
            static_cast<unsigned>(g_width), static_cast<unsigned>(g_height),
            swapped == EGL_TRUE ? 1 : 0);
    }
}

void WotaGlDestroy()
{
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
    g_nativeImageError.clear();
    g_window = nullptr;
    // 新会话干净重试（P3）：黑名单/请求态/几何在页面上次会话后不得残留
    ResetSessionState();
    // 释放本线程 EGL 绑定（surface 反复重建/页面反复进出不留线程级残留）
    eglReleaseThread();
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "gl destroyed");
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

void WotaGlSetEffect(int effect)
{
    g_effectRequested = effect < kEffectNone ? kEffectNone : (effect > kEffectPeaking ? kEffectPeaking : effect);
}

void WotaGlSetCurveEnabled(bool on)
{
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

void WotaGlSetFrostEnabled(bool on)
{
    g_frostRequested = on;
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "frost requested=%{public}d", on ? 1 : 0);
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
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "curve LUT queued 256x1 RGB");
    return true;
}
