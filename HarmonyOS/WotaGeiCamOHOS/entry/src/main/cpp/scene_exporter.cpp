/*
 * 后渲染导出离线执行器实现（边界/契约见 scene_exporter.h）。
 * 管线骨架对拍 clip_exporter.cpp / arc_repair.cpp（单例 job 线程 + 同步编解码 + AVMuxer）。
 */
#include "scene_exporter.h"

#include <hilog/log.h>
#include <multimedia/player_framework/native_avbuffer.h>
#include <multimedia/player_framework/native_avcodec_base.h>
#include <multimedia/player_framework/native_avcodec_videodecoder.h>
#include <multimedia/player_framework/native_avcodec_videoencoder.h>
#include <multimedia/player_framework/native_avdemuxer.h>
#include <multimedia/player_framework/native_avformat.h>
#include <multimedia/player_framework/native_avmuxer.h>
#include <multimedia/player_framework/native_avsource.h>

#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <mutex>
#include <thread>
#include <vector>

#define WOTA_LOG_DOMAIN 0x0000
#define WOTA_LOG_TAG "WotaSceneExport"

namespace wota {
namespace {

constexpr int64_t kCodecTimeoutUs = 20'000;
constexpr int32_t kDefaultFps = 30;
constexpr int32_t kDefaultBitrate = 8'000'000;
/** 双边降噪 σ_r：与实时链 gl_render.cpp kDenoiseSigmaR 同值（离线无 HAL ISP，同一条核） */
constexpr float kSceneSigmaR = 0.08f;
/** 可分离高斯权重：与霜链/Glow 链同一份表（gl_render.cpp kFrostWeights 逐值同源） */
constexpr int kSceneWeightCount = 4;
const float kSceneWeights[kSceneWeightCount] = {0.17524f, 0.16577f, 0.14032f, 0.10629f};
/** Glow 亮部提取的降采样档（离线恒 2 = 半 RT；上游实时链按负载分档、导出不追实时负载） */
constexpr int kGlowDown = 2;

int32_t ClampI(int32_t v, int32_t lo, int32_t hi)
{
    return std::min(hi, std::max(lo, v));
}

/** 上游 WotaNormalizeQuarter 的镜像（90° 象限归一；rotation 只经此口写 muxer 一次） */
int32_t NormalizeQuarter(int32_t deg)
{
    int32_t d = deg % 360;
    if (d < 0) {
        d += 360;
    }
    return (d / 90) * 90;
}

/** 紧打包 I420 平面（与 arc_repair.cpp YuvFrame 同构；两执行器自包含，登记重复） */
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

/** 解码输出 → 紧打包 I420（与 arc_repair.cpp ConvertToI420 同式镜像：NV12/NV21/I420） */
bool ConvertToI420(const uint8_t* data, int32_t size, int32_t width, int32_t height,
    int32_t stride, int32_t sliceHeight, int32_t pixelFormat, YuvFrame& out)
{
    if (data == nullptr || width <= 0 || height <= 0) {
        return false;
    }
    out.Alloc(width, height);
    const int32_t cw = (width + 1) / 2;
    const int32_t ch = (height + 1) / 2;
    const int32_t yStride = stride > 0 ? stride : width;
    const int32_t uHeight = sliceHeight > height ? sliceHeight / 2 : ch;
    if (pixelFormat == AV_PIXEL_FORMAT_YUVI420) {
        if (size < yStride * sliceHeight + 2 * yStride * ch) {
            return false;
        }
        for (int32_t r = 0; r < height; ++r) {
            std::memcpy(out.y.data() + static_cast<size_t>(r) * width,
                data + static_cast<size_t>(r) * yStride, static_cast<size_t>(width));
        }
        for (int32_t r = 0; r < ch; ++r) {
            std::memcpy(out.u.data() + static_cast<size_t>(r) * cw,
                data + static_cast<size_t>(yStride * sliceHeight + r * yStride), static_cast<size_t>(cw));
            std::memcpy(out.v.data() + static_cast<size_t>(r) * cw,
                data + static_cast<size_t>(yStride * (sliceHeight + uHeight) + r * yStride),
                static_cast<size_t>(cw));
        }
        return true;
    }
    if (pixelFormat == AV_PIXEL_FORMAT_NV12 || pixelFormat == AV_PIXEL_FORMAT_NV21) {
        const int32_t uOff = pixelFormat == AV_PIXEL_FORMAT_NV12 ? 0 : 1;
        const int32_t vOff = pixelFormat == AV_PIXEL_FORMAT_NV12 ? 1 : 0;
        if (size < yStride * sliceHeight + yStride * ch) {
            return false;
        }
        for (int32_t r = 0; r < height; ++r) {
            std::memcpy(out.y.data() + static_cast<size_t>(r) * width,
                data + static_cast<size_t>(r) * yStride, static_cast<size_t>(width));
        }
        for (int32_t r = 0; r < ch; ++r) {
            const uint8_t* row = data + static_cast<size_t>(yStride * sliceHeight + r * yStride);
            for (int32_t c = 0; c < cw; ++c) {
                out.u[static_cast<size_t>(r) * cw + c] = row[c * 2 + uOff];
                out.v[static_cast<size_t>(r) * cw + c] = row[c * 2 + vOff];
            }
        }
        return true;
    }
    return false; // 其它格式不猜布局（arc_repair 同口径）
}

/** 有限域 RGB 三元组（0..255） */
struct Rgb {
    float r = 0;
    float g = 0;
    float b = 0;
};

/** 紧打包 I420 → RGB（有限域 BT.601；与 GPU 采样器的视频域展开同式，登记） */
void I420ToRgb(const YuvFrame& in, std::vector<Rgb>& out)
{
    const int32_t w = in.width;
    const int32_t h = in.height;
    const int32_t cw = (w + 1) / 2;
    out.assign(static_cast<size_t>(w) * h, Rgb{});
    for (int32_t y = 0; y < h; ++y) {
        for (int32_t x = 0; x < w; ++x) {
            const float yy = static_cast<float>(in.y[static_cast<size_t>(y) * w + x]) - 16.0f;
            const float u = static_cast<float>(in.u[static_cast<size_t>(y / 2) * cw + x / 2]) - 128.0f;
            const float v = static_cast<float>(in.v[static_cast<size_t>(y / 2) * cw + x / 2]) - 128.0f;
            // BT.601 有限域：Y (16..219)→0..255，U/V ±112
            float r = 1.164f * yy + 1.596f * v;
            float g = 1.164f * yy - 0.392f * u - 0.813f * v;
            float b = 1.164f * yy + 2.017f * u;
            Rgb& o = out[static_cast<size_t>(y) * w + x];
            o.r = ClampI(static_cast<int32_t>(r + 0.5f), 0, 255);
            o.g = ClampI(static_cast<int32_t>(g + 0.5f), 0, 255);
            o.b = ClampI(static_cast<int32_t>(b + 0.5f), 0, 255);
        }
    }
}

/** RGB → 紧打包 I420（逆变换；粗采样 chroma 与编码器期望一致） */
void RgbToI420(const std::vector<Rgb>& in, int32_t w, int32_t h, YuvFrame& out)
{
    out.Alloc(w, h);
    const int32_t cw = (w + 1) / 2;
    const int32_t ch = (h + 1) / 2;
    for (int32_t y = 0; y < h; ++y) {
        for (int32_t x = 0; x < w; ++x) {
            const Rgb& c = in[static_cast<size_t>(y) * w + x];
            // BT.601 有限域逆变换
            const float rf = c.r;
            const float gf = c.g;
            const float bf = c.b;
            const float yyv = 0.257f * rf + 0.504f * gf + 0.098f * bf + 16.0f;
            const float uv = -0.148f * rf - 0.291f * gf + 0.439f * bf + 128.0f;
            const float vv = 0.439f * rf - 0.368f * gf - 0.071f * bf + 128.0f;
            out.y[static_cast<size_t>(y) * w + x] =
                static_cast<uint8_t>(ClampI(static_cast<int32_t>(yyv + 0.5f), 0, 255));
            if ((y % 2 == 0) && (x % 2 == 0)) {
                out.u[static_cast<size_t>(y / 2) * cw + x / 2] =
                    static_cast<uint8_t>(ClampI(static_cast<int32_t>(uv + 0.5f), 0, 255));
                out.v[static_cast<size_t>(y / 2) * cw + x / 2] =
                    static_cast<uint8_t>(ClampI(static_cast<int32_t>(vv + 0.5f), 0, 255));
            }
        }
    }
}

/** Glow 单串解析（core/Glow.ets encodeGlow 格式的 C++ 镜像；坏串=出厂=关，不猜） */
struct SceneGlow {
    bool enabled = false;
    float threshold = 0.7f;
    float soft = 0.05f;
    float width = 0.02f;
    float gain = 1.0f;
    float add = 0.0f;
    float blend = 0.6f;
    float tintR = 1.0f;
    float tintG = 1.0f;
    float tintB = 1.0f;
    bool colorOnly = false;
};

float SceneFloatOr(const std::string& v, float def)
{
    if (v.empty()) {
        return def;
    }
    char* end = nullptr;
    const double parsed = std::strtod(v.c_str(), &end);
    if (end == v.c_str() || *end != '\0' || !std::isfinite(parsed)) {
        return def;
    }
    return static_cast<float>(parsed);
}

uint32_t SceneHexOr(const std::string& v, uint32_t def)
{
    if (v.empty()) {
        return def;
    }
    uint32_t out = 0;
    for (const char c : v) {
        int32_t d;
        if (c >= '0' && c <= '9') {
            d = c - '0';
        } else if (c >= 'A' && c <= 'F') {
            d = c - 'A' + 10;
        } else if (c >= 'a' && c <= 'f') {
            d = c - 'a' + 10;
        } else {
            return def; // 非十六进制 ⇒ 整串回默认（坏色值不许猜）
        }
        out = out * 16 + static_cast<uint32_t>(d);
        if (out > 0xFFFFFFFFu) {
            return def;
        }
    }
    return out;
}

SceneGlow ParseGlowSpec(const std::string& spec)
{
    SceneGlow g;
    // 缺键/坏串按出厂（enabled 缺 = 关）；键值对 `k=v` 用 `;` 连接
    bool hasEnabled = false;
    std::string keys[10];
    std::string vals[10];
    int32_t n = 0;
    size_t pos = 0;
    while (pos <= spec.size() && n < 10) {
        const size_t semi = spec.find(';', pos);
        const std::string pair = spec.substr(pos, semi == std::string::npos ? std::string::npos : semi - pos);
        const size_t eq = pair.find('=');
        if (eq != std::string::npos && eq > 0) {
            keys[n] = pair.substr(0, eq);
            vals[n] = pair.substr(eq + 1);
            ++n;
        }
        if (semi == std::string::npos) {
            break;
        }
        pos = semi + 1;
    }
    float threshold = 0.7f;
    bool thresholdOk = false;
    uint32_t tint = 0xFFFFFFFFu;
    bool tintOk = false;
    for (int32_t i = 0; i < n; ++i) {
        const std::string& k = keys[i];
        const std::string& v = vals[i];
        if (k == "enabled") {
            hasEnabled = v == "1" || v == "0";
            g.enabled = v == "1";
        } else if (k == "threshold") {
            threshold = SceneFloatOr(v, 0.7f);
            thresholdOk = true;
        } else if (k == "soft") {
            g.soft = SceneFloatOr(v, 0.05f);
        } else if (k == "width") {
            g.width = SceneFloatOr(v, 0.02f);
        } else if (k == "gain") {
            g.gain = SceneFloatOr(v, 1.0f);
        } else if (k == "add") {
            g.add = SceneFloatOr(v, 0.0f);
        } else if (k == "blend") {
            g.blend = SceneFloatOr(v, 0.6f);
        } else if (k == "color") {
            tint = SceneHexOr(v, 0xFFFFFFFFu);
            tintOk = true;
        } else if (k == "coloronly") {
            g.colorOnly = v == "1";
        }
        // 未知键忽略（前向兼容；旧 colorA/colorB 键走这里自然退役）
    }
    // 关键键在册但值坏 ⇒ 整串回出厂（enabled 缺/坏 = 关）
    if (!hasEnabled || !thresholdOk || (tintOk == false && spec.find("color=") != std::string::npos)) {
        return SceneGlow{}; // 全默认 = 关
    }
    // 域钳（与 core/Glow.ets sanitize 同域）
    g.threshold = ClampI(static_cast<int32_t>(std::isfinite(threshold) ? threshold * 1000.0f : 700.0f), 600, 1000) / 1000.0f;
    g.soft = std::min(0.5f, std::max(0.0f, g.soft));
    g.width = std::min(0.2f, std::max(0.0f, g.width));
    g.gain = std::min(4.0f, std::max(0.0f, g.gain));
    g.add = std::min(1.0f, std::max(0.0f, g.add));
    g.blend = std::min(1.0f, std::max(0.0f, g.blend));
    g.tintR = static_cast<float>((tint >> 16) & 0xFF) / 255.0f;
    g.tintG = static_cast<float>((tint >> 8) & 0xFF) / 255.0f;
    g.tintB = static_cast<float>(tint & 0xFF) / 255.0f;
    return g;
}

/** 双边降噪 full 核（9 采样）：与 shaders.h WotaBilateralDecl(false) 逐 token 同式（RGB 域） */
void BilateralFull(std::vector<Rgb>& buf, int32_t w, int32_t h)
{
    const float inv2s2 = 1.0f / (2.0f * kSceneSigmaR * kSceneSigmaR);
    std::vector<Rgb> src = buf;
    const int32_t ox[8] = {-1, 0, 1, -1, 1, -1, 0, 1};
    const int32_t oy[8] = {-1, -1, -1, 0, 0, 1, 1, 1};
    for (int32_t y = 0; y < h; ++y) {
        for (int32_t x = 0; x < w; ++x) {
            const Rgb c = src[static_cast<size_t>(y) * w + x];
            Rgb sum = c;
            float wsum = 1.0f;
            for (int32_t i = 0; i < 8; ++i) {
                const int32_t sx = ClampI(x + ox[i], 0, w - 1);
                const int32_t sy = ClampI(y + oy[i], 0, h - 1);
                const Rgb s = src[static_cast<size_t>(sy) * w + sx];
                const float dr = s.r - c.r;
                const float dg = s.g - c.g;
                const float db = s.b - c.b;
                const float wgt = std::exp(-(dr * dr + dg * dg + db * db) * inv2s2);
                sum.r += s.r * wgt;
                sum.g += s.g * wgt;
                sum.b += s.b * wgt;
                wsum += wgt;
            }
            Rgb& o = buf[static_cast<size_t>(y) * w + x];
            o.r = sum.r / wsum;
            o.g = sum.g / wsum;
            o.b = sum.b / wsum;
        }
    }
}

float Smoothstep(float lo, float hi, float v)
{
    const float t = std::min(1.0f, std::max(0.0f, (v - lo) / std::max(1e-6f, hi - lo)));
    return t * t * (3.0f - 2.0f * t);
}

/** Glow 三趟（CPU 镜像）：亮部提取（软边朝 1.0 收敛）→ 半 RT 可分离高斯 → 合成 */
void ApplyGlow(std::vector<Rgb>& buf, int32_t w, int32_t h, const SceneGlow& g)
{
    const int32_t gw = std::max(1, w / kGlowDown);
    const int32_t gh = std::max(1, h / kGlowDown);
    // ① 亮部提取（GLOW_COPY_FS 同式：soft 收敛 + hi 严格 > lo + pick = mix(c, tint, colorOnly)）
    std::vector<Rgb> glowA(static_cast<size_t>(gw) * gh, Rgb{});
    const float soft = std::min(g.soft, std::max(1.0f - g.threshold, 0.0f) + 0.0001f);
    const float lo = std::max(g.threshold - soft, 0.0f);
    const float hi = std::max(g.threshold + soft, lo + 0.0001f);
    const float pickR = g.colorOnly ? g.tintR : 0.0f;
    const float pickG = g.colorOnly ? g.tintG : 0.0f;
    const float pickB = g.colorOnly ? g.tintB : 0.0f;
    for (int32_t y = 0; y < gh; ++y) {
        for (int32_t x = 0; x < gw; ++x) {
            // 盒采样降采样（实时链的线性采样近似；2×2 均值）
            float r = 0;
            float gg = 0;
            float b = 0;
            for (int32_t dy = 0; dy < kGlowDown; ++dy) {
                for (int32_t dx = 0; dx < kGlowDown; ++dx) {
                    const Rgb& c = buf[static_cast<size_t>(std::min(h - 1, y * kGlowDown + dy)) * w +
                        std::min(w - 1, x * kGlowDown + dx)];
                    r += c.r;
                    gg += c.g;
                    b += c.b;
                }
            }
            r /= kGlowDown * kGlowDown;
            gg /= kGlowDown * kGlowDown;
            b /= kGlowDown * kGlowDown;
            const float luma = 0.299f * r + 0.587f * gg + 0.114f * b;
            const float t = Smoothstep(lo, hi, luma / 255.0f);
            Rgb& o = glowA[static_cast<size_t>(y) * gw + x];
            o.r = (g.colorOnly ? pickR * 255.0f : r) * t;
            o.g = (g.colorOnly ? pickG * 255.0f : gg) * t;
            o.b = (g.colorOnly ? pickB * 255.0f : b) * t;
        }
    }
    // ② 可分离高斯（uOffset = width×短长比，半 RT 的 uv 域 → 像素步长）
    const float sx = std::min(1.0f, static_cast<float>(h) / w);
    const float sy = std::min(1.0f, static_cast<float>(w) / h);
    const int32_t stepX = std::max(1, static_cast<int32_t>(g.width * sx * gw + 0.5f));
    const int32_t stepY = std::max(1, static_cast<int32_t>(g.width * sy * gh + 0.5f));
    std::vector<Rgb> glowB = glowA;
    for (int32_t y = 0; y < gh; ++y) {
        for (int32_t x = 0; x < gw; ++x) {
            Rgb sum = glowA[static_cast<size_t>(y) * gw + x];
            float wsum = kSceneWeights[0];
            sum.r *= kSceneWeights[0];
            sum.g *= kSceneWeights[0];
            sum.b *= kSceneWeights[0];
            for (int32_t i = 1; i < kSceneWeightCount; ++i) {
                const int32_t xa = ClampI(x + i * stepX, 0, gw - 1);
                const int32_t xb = ClampI(x - i * stepX, 0, gw - 1);
                const Rgb& a = glowA[static_cast<size_t>(y) * gw + xa];
                const Rgb& b2 = glowA[static_cast<size_t>(y) * gw + xb];
                sum.r += (a.r + b2.r) * kSceneWeights[i];
                sum.g += (a.g + b2.g) * kSceneWeights[i];
                sum.b += (a.b + b2.b) * kSceneWeights[i];
                wsum += 2.0f * kSceneWeights[i];
            }
            Rgb& o = glowB[static_cast<size_t>(y) * gw + x];
            o.r = sum.r / wsum;
            o.g = sum.g / wsum;
            o.b = sum.b / wsum;
        }
    }
    for (int32_t x = 0; x < gw; ++x) {
        for (int32_t y = 0; y < gh; ++y) {
            Rgb sum = glowB[static_cast<size_t>(y) * gw + x];
            float wsum = kSceneWeights[0];
            sum.r *= kSceneWeights[0];
            sum.g *= kSceneWeights[0];
            sum.b *= kSceneWeights[0];
            for (int32_t i = 1; i < kSceneWeightCount; ++i) {
                const int32_t ya = ClampI(y + i * stepY, 0, gh - 1);
                const int32_t yb = ClampI(y - i * stepY, 0, gh - 1);
                const Rgb& a = glowB[static_cast<size_t>(ya) * gw + x];
                const Rgb& b2 = glowB[static_cast<size_t>(yb) * gw + x];
                sum.r += (a.r + b2.r) * kSceneWeights[i];
                sum.g += (a.g + b2.g) * kSceneWeights[i];
                sum.b += (a.b + b2.b) * kSceneWeights[i];
                wsum += 2.0f * kSceneWeights[i];
            }
            Rgb& o = glowA[static_cast<size_t>(y) * gw + x];
            o.r = sum.r / wsum;
            o.g = sum.g / wsum;
            o.b = sum.b / wsum;
        }
    }
    // ③ 合成（applyGlow 同式）：out = c + glow·tint·(gain+add)·(blend·1)——freq=0 ⇒ n=1
    for (int32_t y = 0; y < h; ++y) {
        for (int32_t x = 0; x < w; ++x) {
            const Rgb& gl = glowA[static_cast<size_t>(std::min(gh - 1, y / kGlowDown)) * gw +
                std::min(gw - 1, x / kGlowDown)];
            Rgb& o = buf[static_cast<size_t>(y) * w + x];
            const float k = (g.gain + g.add) * g.blend;
            o.r = ClampI(static_cast<int32_t>(o.r + gl.r * g.tintR * k + 0.5f), 0, 255);
            o.g = ClampI(static_cast<int32_t>(o.g + gl.g * g.tintG * k + 0.5f), 0, 255);
            o.b = ClampI(static_cast<int32_t>(o.b + gl.b * g.tintB * k + 0.5f), 0, 255);
        }
    }
}

struct SceneJob {
    int32_t inFd = -1;
    int64_t inSize = 0;
    int32_t outFd = -1;
    int64_t startMs = 0;
    int64_t endMs = 0;
    int32_t audioOrdinal = -1;
    std::string glowSpec;
    bool denoiseOn = false;
};

struct SceneImpl {
    std::atomic<bool> running{false};
    std::atomic<bool> cancelFlag{false};
    std::atomic<int32_t> progress{0};
    std::string status = "idle";
    std::thread worker;

    void SetStatus(const std::string& s)
    {
        std::lock_guard<std::mutex> lock(mutex);
        status = s;
    }

    std::string GetStatus()
    {
        std::lock_guard<std::mutex> lock(mutex);
        if (running.load()) {
            return "running:" + std::to_string(progress.load());
        }
        return status;
    }

    std::mutex mutex;
};

int64_t NowMs()
{
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

/** 任务主循环（失败/取消都以字符串原因收尾；骨架对拍 clip_exporter RunJob） */
void RunJob(SceneImpl& impl, const SceneJob& job, int64_t id)
{
    const auto fail = [&](const std::string& reason) {
        impl.running.store(false);
        impl.SetStatus("failed:" + reason);
    };
    const auto cancelled = [&]() {
        return impl.cancelFlag.load();
    };

    OH_AVSource* source = OH_AVSource_CreateWithFD(job.inFd, 0, job.inSize);
    if (source == nullptr) {
        fail("source");
        return;
    }
    OH_AVFormat* sourceFormat = OH_AVSource_GetSourceFormat(source);
    int32_t sourceRotation = 0;
    bool sourceRotationPresent = false;
    if (sourceFormat != nullptr) {
        sourceRotationPresent = OH_AVFormat_GetIntValue(sourceFormat, OH_MD_KEY_ROTATION, &sourceRotation);
    }

    // 轨道分类：只 select 视频轨 + 选中的那条音轨（09c19c9 同义实现）
    OH_AVDemuxer* demuxer = OH_AVDemuxer_CreateWithSource(source);
    if (demuxer == nullptr) {
        fail("source");
        OH_AVSource_Destroy(source);
        return;
    }
    int32_t videoTrack = -1;
    int32_t srcW = 0;
    int32_t srcH = 0;
    int32_t trackRotation = 0;
    int64_t srcBitrate = 0;
    double srcFps = 0;
    std::string videoMime;
    std::vector<int32_t> audioTracks;
    int32_t trackCount = 0;
    if (sourceFormat == nullptr || !OH_AVFormat_GetIntValue(sourceFormat, OH_MD_KEY_TRACK_COUNT, &trackCount)) {
        trackCount = 0;
    }
    for (int32_t i = 0; i < trackCount; ++i) {
        OH_AVFormat* fmt = OH_AVSource_GetTrackFormat(source, i);
        if (fmt == nullptr) {
            continue;
        }
        const char* mime = nullptr;
        OH_AVFormat_GetStringValue(fmt, OH_MD_KEY_CODEC_MIME, &mime);
        const std::string mimeStr = mime != nullptr ? mime : "";
        if (videoTrack < 0 && mimeStr.rfind("video/", 0) == 0) {
            videoTrack = i;
            OH_AVFormat_GetIntValue(fmt, OH_MD_KEY_WIDTH, &srcW);
            OH_AVFormat_GetIntValue(fmt, OH_MD_KEY_HEIGHT, &srcH);
            OH_AVFormat_GetIntValue(fmt, OH_MD_KEY_ROTATION, &trackRotation);
            OH_AVFormat_GetLongValue(fmt, OH_MD_KEY_BITRATE, &srcBitrate);
            OH_AVFormat_GetDoubleValue(fmt, OH_MD_KEY_FRAME_RATE, &srcFps);
            const char* vm = nullptr;
            OH_AVFormat_GetStringValue(fmt, OH_MD_KEY_CODEC_MIME, &vm);
            videoMime = vm != nullptr ? vm : "";
        } else if (mimeStr.rfind("audio/", 0) == 0) {
            audioTracks.push_back(i);
        }
    }
    if (videoTrack < 0 || srcW <= 0 || srcH <= 0) {
        fail("no-video");
        OH_AVSource_Destroy(source);
        return;
    }
    int32_t audioTrack = -1;
    if (job.audioOrdinal >= 0 && !audioTracks.empty()) {
        audioTrack = audioTracks[std::min(static_cast<size_t>(job.audioOrdinal), audioTracks.size() - 1)];
    }
    if (OH_AVDemuxer_SelectTrackByID(demuxer, static_cast<uint32_t>(videoTrack)) != AV_ERR_OK) {
        fail("source");
        OH_AVSource_Destroy(source);
        return;
    }
    if (audioTrack >= 0 &&
        OH_AVDemuxer_SelectTrackByID(demuxer, static_cast<uint32_t>(audioTrack)) != AV_ERR_OK) {
        audioTrack = -1; // 音轨选轨失败逐轨降级（不连坐视频）
    }

    // 决策口径（SceneExportPlan 镜像）：帧率/码率
    const int32_t srcFpsI = static_cast<int32_t>(srcFps);
    const double outFps = (srcFpsI >= 15 && srcFpsI <= 60) ? static_cast<double>(srcFpsI) : kDefaultFps;
    const int64_t outBitrate = (srcBitrate >= 1'000'000 && srcBitrate <= 200'000'000)
        ? srcBitrate : kDefaultBitrate;
    const SceneGlow glow = ParseGlowSpec(job.glowSpec);
    const bool applyGlow = glow.enabled;
    if (!applyGlow && !job.denoiseOn) {
        // 无效果时后渲染与无损结果一致：上游 ba9f0f4「置灰而不是偷偷降级」的防御纵深
        fail("no-effect");
        OH_AVSource_Destroy(source);
        return;
    }

    // 解码器（同步 buffer 模式；剥旋转角——解码面恒正向帧，旋转写 muxer 一次）
    OH_AVCodec* decoder = OH_VideoDecoder_CreateByMime(videoMime.c_str());
    if (decoder == nullptr) {
        fail("decoder");
        OH_AVSource_Destroy(source);
        return;
    }
    OH_AVFormat* decFormat = OH_AVFormat_Create();
    OH_AVFormat_Copy(decFormat, OH_AVSource_GetTrackFormat(source, videoTrack));
    OH_AVFormat_SetIntValue(decFormat, OH_MD_KEY_ROTATION, 0);
    if (OH_VideoDecoder_Configure(decoder, decFormat) != AV_ERR_OK ||
        OH_VideoDecoder_Prepare(decoder) != AV_ERR_OK || OH_VideoDecoder_Start(decoder) != AV_ERR_OK) {
        OH_AVFormat_Destroy(decFormat);
        fail("decoder");
        OH_AVSource_Destroy(source);
        return;
    }
    OH_AVFormat_Destroy(decFormat);

    // 编码器（AVC + YUVI420 buffer 输入）
    OH_AVCodec* encoder = OH_VideoEncoder_CreateByMime(OH_AVCODEC_MIMETYPE_VIDEO_AVC);
    if (encoder == nullptr) {
        fail("encoder");
        OH_AVSource_Destroy(source);
        return;
    }
    OH_AVFormat* encFormat = OH_AVFormat_Create();
    bool encOk = OH_AVFormat_SetIntValue(encFormat, OH_MD_KEY_WIDTH, srcW);
    encOk = OH_AVFormat_SetIntValue(encFormat, OH_MD_KEY_HEIGHT, srcH) && encOk;
    encOk = OH_AVFormat_SetDoubleValue(encFormat, OH_MD_KEY_FRAME_RATE, outFps) && encOk;
    encOk = OH_AVFormat_SetLongValue(encFormat, OH_MD_KEY_BITRATE, outBitrate) && encOk;
    encOk = OH_AVFormat_SetIntValue(encFormat, OH_MD_KEY_I_FRAME_INTERVAL, 1000) && encOk;
    encOk = OH_AVFormat_SetIntValue(encFormat, OH_MD_KEY_PIXEL_FORMAT, AV_PIXEL_FORMAT_YUVI420) && encOk;
    if (!encOk || OH_VideoEncoder_Configure(encoder, encFormat) != AV_ERR_OK ||
        OH_VideoEncoder_Prepare(encoder) != AV_ERR_OK || OH_VideoEncoder_Start(encoder) != AV_ERR_OK) {
        OH_AVFormat_Destroy(encFormat);
        fail("encoder");
        OH_AVSource_Destroy(source);
        return;
    }
    OH_AVFormat_Destroy(encFormat);

    // muxer（延迟到首枚编码输出再 AddTrack/Start——clip_exporter/arc_repair 同口径）
    OH_AVMuxer* muxer = OH_AVMuxer_Create(job.outFd, AV_OUTPUT_FORMAT_MPEG_4);
    if (muxer == nullptr) {
        fail("muxer");
        OH_AVSource_Destroy(source);
        return;
    }
    // 旋转只写一次（56631bc）：源容器级优先、视频轨兜底、归一 90° 象限
    const int32_t rotation = NormalizeQuarter(sourceRotationPresent ? sourceRotation : trackRotation);

    OH_AVBuffer* videoBuf = OH_AVBuffer_Create(16 * 1024 * 1024);
    OH_AVBuffer* audioBuf = audioTrack >= 0 ? OH_AVBuffer_Create(256 * 1024) : nullptr;
    if (videoBuf == nullptr || (audioTrack >= 0 && audioBuf == nullptr)) {
        fail("buffer");
        OH_AVSource_Destroy(source);
        return;
    }

    std::vector<Rgb> rgb;
    YuvFrame in;
    YuvFrame out;
    bool trackAdded = false;
    bool muxStarted = false;
    int32_t dstVideoTrack = -1;
    int32_t dstAudioTrack = -1;
    int64_t basePts = -1;
    const int64_t startUs = job.startMs * 1000;
    const int64_t endUs = job.endMs * 1000;
    const int64_t totalUs = std::max<int64_t>(1, endUs - startUs);
    bool inputEos = false;
    bool encoderEos = false;
    const char* error = nullptr;
    bool wasCancelled = false;
    int32_t decoded = 0;

    auto submitFrame = [&](const YuvFrame& f, int64_t srcPtsUs) -> const char* {
        // sceneExportShouldSubmit 镜像（源 PTS ∈ [startUs, endUs) 左闭右开；区间外只解码）
        if (srcPtsUs < startUs || (endUs > startUs && srcPtsUs >= endUs)) {
            return nullptr;
        }
        // sceneExportPtsUs 镜像：基准 = 首枚提交帧的源 PTS，负值钳 0
        if (basePts < 0) {
            basePts = srcPtsUs;
        }
        const int64_t pts = std::max<int64_t>(0, srcPtsUs - basePts);
        uint32_t index = 0;
        if (OH_VideoEncoder_QueryInputBuffer(encoder, &index, kCodecTimeoutUs) != AV_ERR_OK) {
            return "encoder";
        }
        OH_AVBuffer* buf = OH_VideoEncoder_GetInputBuffer(encoder, index);
        if (buf == nullptr) {
            return "encoder";
        }
        const int64_t total = static_cast<int64_t>(f.width) * f.height * 3 / 2;
        if (total <= 0 || total > OH_AVBuffer_GetCapacity(buf)) {
            return "encoder";
        }
        uint8_t* dst = OH_AVBuffer_GetAddr(buf);
        std::memcpy(dst, f.y.data(), f.y.size());
        std::memcpy(dst + f.y.size(), f.u.data(), f.u.size());
        std::memcpy(dst + f.y.size() + f.u.size(), f.v.data(), f.v.size());
        OH_AVCodecBufferAttr attr;
        attr.pts = pts;
        attr.size = static_cast<int32_t>(total);
        attr.offset = 0;
        attr.flags = AVCODEC_BUFFER_FLAGS_NONE;
        if (OH_AVBuffer_SetBufferAttr(buf, &attr) != AV_ERR_OK ||
            OH_VideoEncoder_PushInputBuffer(encoder, index) != AV_ERR_OK) {
            return "encoder";
        }
        return nullptr;
    };

    // 解码器 EOS = 推一枚带 EOS 旗的空输入缓冲（API 面无 NotifyEos；arc_repair 同款）
    auto notifyDecoderEos = [&]() -> const char* {
        uint32_t inIdx = 0;
        if (OH_VideoDecoder_QueryInputBuffer(decoder, &inIdx, kCodecTimeoutUs) != AV_ERR_OK) {
            return "decoder";
        }
        OH_AVBuffer* inBuf = OH_VideoDecoder_GetInputBuffer(decoder, inIdx);
        if (inBuf == nullptr) {
            return "decoder";
        }
        OH_AVCodecBufferAttr eosAttr;
        eosAttr.pts = 0;
        eosAttr.size = 0;
        eosAttr.offset = 0;
        eosAttr.flags = AVCODEC_BUFFER_FLAGS_EOS;
        if (OH_AVBuffer_SetBufferAttr(inBuf, &eosAttr) != AV_ERR_OK ||
            OH_VideoDecoder_PushInputBuffer(decoder, inIdx) != AV_ERR_OK) {
            return "decoder";
        }
        return nullptr;
    };

    auto drainEncoder = [&](bool drainAll) -> const char* {
        int32_t guard = 0;
        while (guard++ < 4096) {
            if (cancelled()) {
                return "cancel";
            }
            uint32_t index = 0;
            const OH_AVErrCode q = OH_VideoEncoder_QueryOutputBuffer(encoder, &index, kCodecTimeoutUs);
            if (q == AV_ERR_TRY_AGAIN_LATER) {
                if (!drainAll) {
                    return nullptr;
                }
                continue;
            }
            if (q != AV_ERR_OK) {
                return "encoder";
            }
            OH_AVBuffer* buf = OH_VideoEncoder_GetOutputBuffer(encoder, index);
            if (buf == nullptr) {
                return "encoder";
            }
            OH_AVCodecBufferAttr attr;
            const bool attrOk = OH_AVBuffer_GetBufferAttr(buf, &attr) == AV_ERR_OK;
            const bool eos = attrOk && (attr.flags & AVCODEC_BUFFER_FLAGS_EOS) != 0;
            if (attrOk && !eos && attr.size > 0) {
                if (!trackAdded) {
                    // 首枚编码输出建轨：视频轨 + 音轨（一次性），旋转只写一次
                    OH_AVFormat* desc = OH_VideoEncoder_GetOutputDescription(encoder);
                    OH_AVFormat* trackFormat = OH_AVFormat_Create();
                    if (desc == nullptr || !OH_AVFormat_Copy(trackFormat, desc)) {
                        OH_AVFormat_Destroy(desc);
                        OH_AVFormat_Destroy(trackFormat);
                        OH_VideoEncoder_FreeOutputBuffer(encoder, index);
                        return "muxer";
                    }
                    OH_AVFormat_Destroy(desc);
                    OH_AVFormat_SetIntValue(trackFormat, OH_MD_KEY_WIDTH, srcW);
                    OH_AVFormat_SetIntValue(trackFormat, OH_MD_KEY_HEIGHT, srcH);
                    if (OH_AVMuxer_AddTrack(muxer, &dstVideoTrack, trackFormat) != AV_ERR_OK) {
                        OH_AVFormat_Destroy(trackFormat);
                        OH_VideoEncoder_FreeOutputBuffer(encoder, index);
                        return "muxer";
                    }
                    OH_AVFormat_Destroy(trackFormat);
                    if (audioTrack >= 0) {
                        OH_AVFormat* audioFormat = OH_AVSource_GetTrackFormat(source, audioTrack);
                        OH_AVFormat* audioCopy = OH_AVFormat_Create();
                        if (audioFormat != nullptr && OH_AVFormat_Copy(audioCopy, audioFormat)) {
                            if (OH_AVMuxer_AddTrack(muxer, &dstAudioTrack, audioCopy) != AV_ERR_OK) {
                                dstAudioTrack = -1; // 音轨逐轨降级
                            }
                        }
                        OH_AVFormat_Destroy(audioCopy);
                    }
                    if (OH_AVMuxer_SetRotation(muxer, rotation) != AV_ERR_OK ||
                        OH_AVMuxer_Start(muxer) != AV_ERR_OK) {
                        OH_VideoEncoder_FreeOutputBuffer(encoder, index);
                        return "muxer";
                    }
                    muxStarted = true;
                }
                if ((attr.flags & AVCODEC_BUFFER_FLAGS_CODEC_DATA) != 0) {
                    OH_VideoEncoder_FreeOutputBuffer(encoder, index);
                    continue; // CSD 已随建轨登记（arc_repair 同口径）
                }
                attr.flags &= ~static_cast<uint32_t>(AVCODEC_BUFFER_FLAGS_EOS);
                OH_AVBuffer_SetBufferAttr(buf, &attr);
                if (OH_AVMuxer_WriteSampleBuffer(muxer, static_cast<uint32_t>(dstVideoTrack), buf) != AV_ERR_OK) {
                    OH_VideoEncoder_FreeOutputBuffer(encoder, index);
                    return "muxer";
                }
            }
            OH_VideoEncoder_FreeOutputBuffer(encoder, index);
            if (eos) {
                encoderEos = true;
                return nullptr;
            }
        }
        return nullptr;
    };

    // 主循环：demuxer 交替读视频/音轨；视频解码→特效趟→编码，音轨区间内直拷
    while (error == nullptr) {
        if (cancelled()) {
            wasCancelled = true;
            break;
        }
        // 先排编码器（不排空会积压）
        error = drainEncoder(false);
        if (error != nullptr) {
            break;
        }
        if (inputEos) {
            if (!encoderEos) {
                continue; // 等编码器吐 EOS
            }
            break;
        }
        // 读视频轨下一枚（音轨样本按区间内直拷穿插读出）
        OH_AVCodecBufferAttr attr;
        const OH_AVErrCode rd = OH_AVDemuxer_ReadSampleBuffer(demuxer,
            static_cast<uint32_t>(videoTrack), videoBuf);
        if (rd != AV_ERR_OK) {
            inputEos = true;
            if (notifyDecoderEos() != nullptr) {
                error = "decoder";
            }
            continue;
        }
        if (!OH_AVBuffer_GetBufferAttr(videoBuf, &attr)) {
            error = "decoder";
            break;
        }
        // 音轨穿插直拷（09c19c9 同义：只拷用户选中的一条；区间内原样透传，不重编码）
        if (audioTrack >= 0 && audioBuf != nullptr && muxStarted && dstAudioTrack >= 0) {
            OH_AVCodecBufferAttr aAttr;
            if (OH_AVDemuxer_ReadSampleBuffer(demuxer, static_cast<uint32_t>(audioTrack),
                audioBuf) == AV_ERR_OK && OH_AVBuffer_GetBufferAttr(audioBuf, &aAttr)) {
                if ((aAttr.flags & AVCODEC_BUFFER_FLAGS_EOS) == 0 && aAttr.size > 0 &&
                    aAttr.pts >= startUs && (endUs <= startUs || aAttr.pts < endUs)) {
                    OH_AVMuxer_WriteSampleBuffer(muxer, static_cast<uint32_t>(dstAudioTrack),
                        audioBuf);
                }
            }
        }
        if (attr.size > 0 && (attr.flags & AVCODEC_BUFFER_FLAGS_EOS) == 0) {
            // demuxer buffer → decoder 输入 buffer（同步模式喂法，arc_repair 同款）
            uint32_t inIdx = 0;
            if (OH_VideoDecoder_QueryInputBuffer(decoder, &inIdx, kCodecTimeoutUs) != AV_ERR_OK) {
                error = "decoder";
                break;
            }
            OH_AVBuffer* inBuf = OH_VideoDecoder_GetInputBuffer(decoder, inIdx);
            if (inBuf == nullptr || OH_AVBuffer_GetCapacity(inBuf) < static_cast<int64_t>(attr.size)) {
                error = "decoder";
                break;
            }
            const uint8_t* srcAddr = OH_AVBuffer_GetAddr(videoBuf);
            if (srcAddr == nullptr) {
                error = "decoder";
                break;
            }
            std::memcpy(OH_AVBuffer_GetAddr(inBuf), srcAddr, static_cast<size_t>(attr.size));
            OH_AVCodecBufferAttr inAttr = attr;
            inAttr.offset = 0;
            if (OH_AVBuffer_SetBufferAttr(inBuf, &inAttr) != AV_ERR_OK ||
                OH_VideoDecoder_PushInputBuffer(decoder, inIdx) != AV_ERR_OK) {
                error = "decoder";
                break;
            }
        }
        // 拉解码输出（同步模式；有输出就过特效趟）
        int32_t drainGuard = 0;
        while (drainGuard++ < 64 && error == nullptr) {
            uint32_t outIndex = 0;
            const OH_AVErrCode q = OH_VideoDecoder_QueryOutputBuffer(decoder, &outIndex, kCodecTimeoutUs);
            if (q == AV_ERR_TRY_AGAIN_LATER) {
                break;
            }
            if (q != AV_ERR_OK) {
                error = "decoder";
                break;
            }
            OH_AVBuffer* outBuf = OH_VideoDecoder_GetOutputBuffer(decoder, outIndex);
            OH_AVCodecBufferAttr outAttr;
            if (outBuf == nullptr || !OH_AVBuffer_GetBufferAttr(outBuf, &outAttr)) {
                OH_VideoDecoder_FreeOutputBuffer(decoder, outIndex);
                error = "decoder";
                break;
            }
            const bool eos = (outAttr.flags & AVCODEC_BUFFER_FLAGS_EOS) != 0;
            if (!eos && outAttr.size > 0) {
                // 布局每次取（STREAM_CHANGED 后跟随）
                OH_AVFormat* outDesc = OH_VideoDecoder_GetOutputDescription(decoder);
                int32_t stride = srcW;
                int32_t sliceHeight = srcH;
                int32_t fmtPixel = AV_PIXEL_FORMAT_YUVI420;
                if (outDesc != nullptr) {
                    int32_t v = 0;
                    if (OH_AVFormat_GetIntValue(outDesc, OH_MD_KEY_VIDEO_STRIDE, &v) && v > 0) {
                        stride = v;
                    }
                    if (OH_AVFormat_GetIntValue(outDesc, OH_MD_KEY_VIDEO_SLICE_HEIGHT, &v) && v > 0) {
                        sliceHeight = v;
                    }
                    if (OH_AVFormat_GetIntValue(outDesc, OH_MD_KEY_PIXEL_FORMAT, &v)) {
                        fmtPixel = v;
                    }
                    OH_AVFormat_Destroy(outDesc);
                }
                if (!ConvertToI420(OH_AVBuffer_GetAddr(outBuf), outAttr.size, srcW, srcH,
                    stride, sliceHeight, fmtPixel, in)) {
                    OH_VideoDecoder_FreeOutputBuffer(decoder, outIndex);
                    error = "pixel-format";
                    break;
                }
                I420ToRgb(in, rgb);
                if (job.denoiseOn) {
                    BilateralFull(rgb, srcW, srcH);
                }
                if (applyGlow) {
                    ApplyGlow(rgb, srcW, srcH, glow);
                }
                RgbToI420(rgb, srcW, srcH, out);
                error = submitFrame(out, outAttr.pts);
                if (error != nullptr) {
                    OH_VideoDecoder_FreeOutputBuffer(decoder, outIndex);
                    break;
                }
                ++decoded;
                impl.progress.store(static_cast<int32_t>(std::min<int64_t>(99,
                    std::max<int64_t>(0, outAttr.pts - basePts) * 100 / totalUs)));
            }
            OH_VideoDecoder_FreeOutputBuffer(decoder, outIndex);
            if (eos) {
                inputEos = true;
                break;
            }
        }
    }

    // 收尾：排空编码器 → EOS → Stop（半成品由 ArkTS 删；与 arc_repair 收尾同序）
    if (error == nullptr && !wasCancelled) {
        if (!inputEos) {
            notifyDecoderEos();
        }
        error = drainEncoder(true);
    }

    if (muxer != nullptr) {
        if (muxStarted) {
            OH_AVMuxer_Stop(muxer); // best-effort 封口
        }
        OH_AVMuxer_Destroy(muxer);
    }
    if (encoder != nullptr) {
        OH_VideoEncoder_Stop(encoder);
        OH_VideoEncoder_Destroy(encoder);
    }
    if (decoder != nullptr) {
        OH_VideoDecoder_Stop(decoder);
        OH_VideoDecoder_Destroy(decoder);
    }
    if (videoBuf != nullptr) {
        OH_AVBuffer_Destroy(videoBuf);
    }
    if (audioBuf != nullptr) {
        OH_AVBuffer_Destroy(audioBuf);
    }
    OH_AVSource_Destroy(source);

    impl.running.store(false);
    if (wasCancelled) {
        // 取消是独立终态（a0ef71d 口径）：不映射成 failed
        impl.SetStatus("cancel:by-user");
    } else if (error != nullptr) {
        impl.SetStatus(std::string("failed:") + error);
    } else {
        impl.SetStatus("done:" + std::to_string(id));
    }
}

} // namespace

struct SceneExporter::Impl : SceneImpl {
};

SceneExporter& SceneExporter::Instance()
{
    static SceneExporter inst;
    return inst;
}

std::string SceneExporter::Start(int32_t inFd, int64_t inSize, int32_t outFd,
    int64_t startMs, int64_t endMs, int32_t audioOrdinal,
    const std::string& glowSpec, int32_t denoiseOn)
{
    if (impl_ == nullptr) {
        impl_ = new Impl();
    }
    if (impl_->running.load()) {
        return "busy";
    }
    if (inFd < 0 || outFd < 0 || endMs <= startMs || inSize <= 0) {
        return "args";
    }
    const SceneJob job{inFd, inSize, outFd, startMs, endMs, audioOrdinal, glowSpec, denoiseOn != 0};
    const SceneGlow glow = ParseGlowSpec(glowSpec);
    if (!glow.enabled && denoiseOn == 0) {
        return "no-effect"; // ba9f0f4：置灰判据的防御纵深
    }
    impl_->cancelFlag.store(false);
    impl_->progress.store(0);
    impl_->running.store(true);
    impl_->SetStatus("running:0");
    const int64_t id = NowMs();
    std::thread(&RunJob, std::ref(*impl_), job, id).detach();
    (void)id;
    return "";
}

std::string SceneExporter::Status()
{
    if (impl_ == nullptr) {
        return "idle";
    }
    return impl_->GetStatus();
}

void SceneExporter::Cancel()
{
    if (impl_ != nullptr) {
        impl_->cancelFlag.store(true);
    }
}

} // namespace wota
