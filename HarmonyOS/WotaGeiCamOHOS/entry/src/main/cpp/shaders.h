/*
 * 上游 camera/Shaders.kt 首批基对逐字移植（对齐上游 38d712a，Shaders.kt 19–40 行）：
 * TEXTURE_VS（全屏四边形顶点 + 纹理坐标，纹理矩阵在 VS 里乘）+ PASS_THROUGH_FS（直通，
 * 强制不透明）；ES 1.00（attribute/varying/gl_FragColor），ES2/ES3 上下文都能编译。
 * 后续 pass（PASS_THROUGH_2D_FS/CURVE_FS/ARC_MERGE_FS/zebra/peaking/frost）从 GL-2 起分批，不预埋。
 *
 * 2026-10-28 Glow/降噪批（上游 Shaders.kt GLOW_COPY_FS/GLOW_DECL/BILATERAL*_FS）：
 * - 主 pass 四条片元族（直通/曲线/斑马/峰值）统一带 uGlow* 段（applyGlow + vUvGlow）；
 * - 降噪并入同一批构建器：WotaSceneFragment(curve,denoise)、WotaZebraFragment(curve,denoise)、
 *   WotaPeakingFragment(curve,denoise)，denoise 0=关 / 1=完整核(9 采样) / 2=轻量核(5 采样)；
 * - 离屏趟（FROST 拷贝、GLOW 拷贝、高斯）不注入 Glow：uGlowSrc 是普通 2D RT 的产物，避免把
 *   samplerExternalOES 与 sampler2D 混进同一个 program（上游 FROST_COPY_FS 的同一纪律）。
 */
#ifndef WOTA_SHADERS_H
#define WOTA_SHADERS_H

#include <string>

/** 画面降噪档位（上游 core/Denoise.kt DENOISE_LEVEL_*：0=关 / 1=完整 / 2=轻量） */
constexpr int WOTA_DENOISE_OFF = 0;
constexpr int WOTA_DENOISE_FULL = 1;
constexpr int WOTA_DENOISE_LITE = 2;

/** 上游 Shaders.TEXTURE_VS；vUvGlow = 不乘 uTexMatrix 的原始纹理坐标（Glow 离屏链逐像素对齐，24 §1.2） */
static const char* WOTA_TEXTURE_VS =
    "attribute vec4 aPosition;\n"
    "attribute vec2 aTexCoord;\n"
    "uniform mat4 uTexMatrix;\n"
    "uniform vec2 uFit;\n"
    "varying vec2 vUv;\n"
    "varying vec2 vUvGlow;\n"
    "void main() {\n"
    "    gl_Position = vec4(aPosition.xy * uFit, aPosition.zw);\n"
    "    vUv = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;\n"
    "    vUvGlow = aTexCoord;\n"
    "}\n";

/**
 * 上游 Shaders.GLOW_DECL 逐 token：
 * `out = c + ((glow * gain + add) * hue) * (blend * n)`；pos 取归一化亮度、噪声频率 0 = 干净。
 * 注入点：直通/曲线/双边的终写表达式、斑马的 else 分支、峰值的 mix(c, uColor, paint) 的 c。
 */
static const std::string WOTA_GLOW_DECL =
    "varying vec2 vUvGlow;\n"
    "uniform sampler2D uGlowSrc;\n"
    "uniform float uGlowGain;\n"
    "uniform float uGlowAdd;\n"
    "uniform float uGlowFreq;\n"
    "uniform float uGlowBlend;\n"
    "uniform vec3 uGlowColorA;\n"
    "uniform vec3 uGlowColorB;\n"
    "float glowHash(vec2 p) {\n"
    "    vec3 q = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));\n"
    "    q += dot(q, q.yzx + 33.33);\n"
    "    return fract((q.x + q.y) * q.z);\n"
    "}\n"
    "vec3 applyGlow(vec3 c, vec2 uv) {\n"
    "    vec3 glow = texture2D(uGlowSrc, uv).rgb;\n"
    "    float pos = clamp(dot(glow, vec3(0.299, 0.587, 0.114)), 0.0, 1.0);\n"
    "    vec3 hue = mix(uGlowColorA, uGlowColorB, pos);\n"
    "    float n = mix(1.0, glowHash(uv * uGlowFreq), step(0.0001, uGlowFreq));\n"
    "    return c + ((glow * uGlowGain + uGlowAdd) * hue) * (uGlowBlend * n);\n"
    "}\n";

/** 上游 Shaders.PASS_THROUGH_FS（ES 1.00；#extension 必须是首条指令；终写包 applyGlow） */
static const std::string WOTA_PASS_THROUGH_FS =
    std::string(
        "#extension GL_OES_EGL_image_external : require\n"
        "precision mediump float;\n"
        "varying vec2 vUv;\n"
        "uniform samplerExternalOES uFrame;\n") +
    WOTA_GLOW_DECL +
    "void main() {\n"
    "    // 强制不透明：部分机型相机帧的 alpha 通道为 0，直传会在半透明窗口面上变全黑\n"
    "    gl_FragColor = vec4(applyGlow(texture2D(uFrame, vUv).rgb, vUvGlow), 1.0);\n"
    "}\n";

/** 上游 Shaders.CURVE_FS（曲线单独一条着色器，不给 PASS_THROUGH 加分支；终写包 applyGlow） */
static const std::string WOTA_CURVE_FS =
    std::string(
        "#extension GL_OES_EGL_image_external : require\n"
        "precision mediump float;\n"
        "varying vec2 vUv;\n"
        "uniform samplerExternalOES uFrame;\n"
        "uniform sampler2D uCurve;\n"
        "vec3 applyCurve(vec3 c) {\n"
        "    return vec3(\n"
        "        texture2D(uCurve, vec2(c.r, 0.5)).r,\n"
        "        texture2D(uCurve, vec2(c.g, 0.5)).g,\n"
        "        texture2D(uCurve, vec2(c.b, 0.5)).b);\n"
        "}\n") +
    WOTA_GLOW_DECL +
    "void main() {\n"
    "    vec3 c = texture2D(uFrame, vUv).rgb;\n"
    "    gl_FragColor = vec4(applyGlow(applyCurve(c), vUvGlow), 1.0);\n"
    "}\n";

/**
 * 上游 Shaders.CURVE_DECL（注入斑马纹/峰值；曲线关着时调用方不注入，源码与引入曲线前逐字相同）。
 * 注：上游用 trimIndent，但 `$decl` 行本身无缩进会让 trimIndent 在插值后失效（缩进保留）；
 * 此处统一为无缩进输出——GLSL 对缩进不敏感，token 序列与上游一致。
 */
static const char* WOTA_CURVE_DECL =
    "uniform sampler2D uCurve;\n"
    "vec3 applyCurve(vec3 c) {\n"
    "    return vec3(\n"
    "        texture2D(uCurve, vec2(c.r, 0.5)).r,\n"
    "        texture2D(uCurve, vec2(c.g, 0.5)).g,\n"
    "        texture2D(uCurve, vec2(c.b, 0.5)).b);\n"
    "}\n";

/** 上游 Shaders.PASS_THROUGH_2D_FS（普通 2D 纹理直通；无 OES 扩展，强制不透明） */
static const char* WOTA_PASS_THROUGH_2D_FS =
    "precision mediump float;\n"
    "varying vec2 vUv;\n"
    "uniform sampler2D uFrame;\n"
    "void main() {\n"
    "    gl_FragColor = vec4(texture2D(uFrame, vUv).rgb, 1.0);\n"
    "}\n";

/** 上游 Shaders.FROST_COPY_FS（Frost 第①趟：OES → 2D RT 拷贝，强制不透明） */
static const char* WOTA_FROST_COPY_FS =
    "#extension GL_OES_EGL_image_external : require\n"
    "precision mediump float;\n"
    "varying vec2 vUv;\n"
    "uniform samplerExternalOES uFrame;\n"
    "void main() {\n"
    "    // 强制不透明：与 PASS_THROUGH_FS 同一个理由——HAL 给 alpha=0 时这里会连着糊出一张全黑\n"
    "    gl_FragColor = vec4(texture2D(uFrame, vUv).rgb, 1.0);\n"
    "}\n";

/** 上游 Shaders.FROST_QUAD_VS（恒等映射，不乘 uTexMatrix：RT 朝向已在拷贝趟烘好） */
static const char* WOTA_FROST_QUAD_VS =
    "attribute vec4 aPosition;\n"
    "attribute vec2 aTexCoord;\n"
    "uniform vec2 uFit;\n"
    "varying vec2 vUv;\n"
    "void main() {\n"
    "    gl_Position = vec4(aPosition.xy * uFit, aPosition.zw);\n"
    "    vUv = aTexCoord;\n"
    "}\n";

/**
 * 上游 Shaders.frostGaussianFragment(weightCount, highp) 的逐字生成器：
 * 可分离高斯一趟（横向/纵向共用一条 program，靠 uOffset 区分）；uWeight 定长常量下标数组；
 * 抽头展开 2N-1 次取样；highp 失败时调用方以 mediump 重编一次。
 * Glow 链复用同一份高斯源（上游 GlowBlurChain.gaussianSource 同款），不造第二份实现。
 */
static std::string WotaFrostGaussianFragment(int weightCount, bool highp)
{
    const char* precision = highp ? "highp" : "mediump";
    std::string src = "precision ";
    src += precision;
    src +=
        " float;\n"
        "varying vec2 vUv;\n"
        "uniform sampler2D uSrc;\n"
        "uniform vec2 uOffset;\n";
    src += "uniform float uWeight[" + std::to_string(weightCount) + "];\n";
    src +=
        "void main() {\n"
        "        vec3 sum = texture2D(uSrc, vUv).rgb * uWeight[0];";
    for (int i = 1; i < weightCount; i++) {
        src += "\n        sum += (texture2D(uSrc, vUv + uOffset * " + std::to_string(i) + ".0).rgb";
        src += " + texture2D(uSrc, vUv - uOffset * " + std::to_string(i) + ".0).rgb) * uWeight[";
        src += std::to_string(i) + "];";
    }
    src +=
        "\n"
        "    gl_FragColor = vec4(sum, 1.0);\n"
        "}\n";
    return src;
}

/**
 * 上游 Shaders.GLOW_COPY_FS（Glow 链第①趟：OES → 2D，拷 + 亮部阈值 + 降采样）：
 * `t = smoothstep(th − soft, max(th + soft, th − soft + 0.0001), luma)`，亮部乘回原色。
 * 只吃 samplerExternalOES（OES 不能当 FBO 附件）；与模糊趟的 sampler2D 分属两条 program。
 */
static const char* WOTA_GLOW_COPY_FS =
    "#extension GL_OES_EGL_image_external : require\n"
    "precision mediump float;\n"
    "varying vec2 vUv;\n"
    "uniform samplerExternalOES uFrame;\n"
    "uniform float uGlowThreshold;\n"
    "uniform float uGlowSoft;\n"
    "void main() {\n"
    "    vec3 c = texture2D(uFrame, vUv).rgb;\n"
    "    float luma = dot(c, vec3(0.299, 0.587, 0.114));\n"
    "    float t = smoothstep(\n"
    "        uGlowThreshold - uGlowSoft,\n"
    "        max(uGlowThreshold + uGlowSoft, uGlowThreshold - uGlowSoft + 0.0001),\n"
    "        luma);\n"
    "    gl_FragColor = vec4(c * t, 1.0);\n"
    "}\n";

/**
 * 上游 Shaders.BILATERAL_FS/BILATERAL_LITE_FS 的 bilateral() 函数体（逐 token 对拍）：
 * 中心 + N 邻域（full=8 邻域/9 采样、lite=4 邻域/5 采样），亮度差权重 exp(−|Δ|²/2σ²)。
 * 循环上界是编译期常量、下标是循环计数变量 ⇒ GLSL ES 1.00 Appendix A 合规。
 *
 * `texelUniform` 可换名：峰值对焦自己的邻域步长也叫 uTexel（与上游同一条「一颗 uniform 被
 * 峰值与降噪共用」的历史），两者同场时降噪改用 uDenoiseTexel，避免互相覆盖。
 */
static std::string WotaBilateralDecl(bool lite, const char* texelUniform)
{
    const int taps = lite ? 4 : 8;
    std::string src = "uniform vec2 ";
    src += texelUniform;
    src +=
        ";\n"
        "uniform float uSigmaR;\n"
        "vec3 bilateral(vec2 uv) {\n"
        "    vec3 c = texture2D(uFrame, uv).rgb;\n"
        "    // 亮度差权重：Δ 越大权重越小（高斯核），跨边缘的邻域自然被抑制\n"
        "    float inv2s2 = 1.0 / (2.0 * uSigmaR * uSigmaR);\n"
        "    vec3 sum = c;\n"
        "    float wsum = 1.0;\n";
    src += "    vec2 o[" + std::to_string(taps) + "];\n";
    if (lite) {
        src +=
            "    o[0] = vec2(-1.0, 0.0); o[1] = vec2(1.0, 0.0);\n"
            "    o[2] = vec2(0.0, -1.0); o[3] = vec2(0.0, 1.0);\n";
    } else {
        src +=
            "    o[0] = vec2(-1.0, -1.0); o[1] = vec2(0.0, -1.0); o[2] = vec2(1.0, -1.0);\n"
            "    o[3] = vec2(-1.0,  0.0);                          o[4] = vec2(1.0,  0.0);\n"
            "    o[5] = vec2(-1.0,  1.0); o[6] = vec2(0.0,  1.0); o[7] = vec2(1.0,  1.0);\n";
    }
    src += "    for (int i = 0; i < " + std::to_string(taps) + "; i++) {\n";
    src += "        vec3 s = texture2D(uFrame, uv + o[i] * ";
    src += texelUniform;
    src +=
        ").rgb;\n"
        "        vec3 d = s - c;\n"
        "        float w = exp(-dot(d, d) * inv2s2);\n"
        "        sum += s * w;\n"
        "        wsum += w;\n"
        "    }\n"
        "    return sum / wsum;\n"
        "}\n";
    return src;
}

/**
 * 上游 Shaders.BILATERAL_FS / BILATERAL_LITE_FS / BILATERAL_CURVE_FS / BILATERAL_LITE_CURVE_FS：
 * 先降噪再查曲线表；终写包 applyGlow（与 scene 支路的注入点同一种形态）。
 */
static std::string WotaBilateralFragment(bool curveOn, bool lite)
{
    std::string src =
        "#extension GL_OES_EGL_image_external : require\n"
        "precision mediump float;\n"
        "varying vec2 vUv;\n"
        "uniform samplerExternalOES uFrame;\n";
    if (curveOn) {
        src += WOTA_CURVE_DECL;
    }
    src += WotaBilateralDecl(lite, "uTexel");
    src += WOTA_GLOW_DECL;
    src +=
        "void main() {\n"
        "    gl_FragColor = vec4(applyGlow(";
    if (curveOn) {
        src += "applyCurve(bilateral(vUv))";
    } else {
        src += "bilateral(vUv)";
    }
    src +=
        ", vUvGlow), 1.0);\n"
        "}\n";
    return src;
}

/**
 * scene 片元族（effect = NONE）的 (curve, denoise) 构建器：
 * denoise=关时回落上游两枚常量 WOTA_PASS_THROUGH_FS / WOTA_CURVE_FS（源码逐字同一份），
 * 开时按 full/lite 走 BILATERAL*_FS 的生成器。
 */
static std::string WotaSceneFragment(bool curveOn, int denoise)
{
    if (denoise == WOTA_DENOISE_OFF) {
        return curveOn ? WOTA_CURVE_FS : WOTA_PASS_THROUGH_FS;
    }
    return WotaBilateralFragment(curveOn, denoise == WOTA_DENOISE_LITE);
}

/**
 * 上游 Shaders.zebraFragment(curveOn) 的逐字生成器 + 降噪维度（2026-10-28 Glow/降噪批）：
 * 逐像素亮度比较（uThreshold），超阈值处叠 GL_REPEAT 平铺条纹贴图（fract(vUv * uTile)），
 * 其余保持原画面；曲线在亮度判定之前施加（斑马纹标在「所见」的过曝点上）；
 * 降噪同样先于亮度判定（判读工具看的是「所见」的噪声水平）；**条纹分支原样输出、不包 Glow**，
 * 亮度判定仍用 pre-Glow 的 c（上游 glowFragment 的 ZEBRA 注入点：只包 else 分支）。
 */
static std::string WotaZebraFragment(bool curveOn, int denoise = WOTA_DENOISE_OFF)
{
    std::string src =
        "#extension GL_OES_EGL_image_external : require\n"
        "precision highp float;\n"
        "varying vec2 vUv;\n"
        "uniform samplerExternalOES uFrame;\n"
        "uniform sampler2D uStripe;\n"
        "uniform float uThreshold;\n"
        "uniform vec2 uTile;\n";
    if (curveOn) {
        src += WOTA_CURVE_DECL;
    }
    if (denoise != WOTA_DENOISE_OFF) {
        src += WotaBilateralDecl(denoise == WOTA_DENOISE_LITE, "uTexel");
    }
    src += WOTA_GLOW_DECL;
    src +=
        "float lumaOf(vec3 c) {\n"
        "    return dot(c, vec3(0.299, 0.587, 0.114));\n"
        "}\n"
        "void main() {\n";
    if (denoise != WOTA_DENOISE_OFF) {
        src += "    vec3 c = bilateral(vUv);";
    } else {
        src += "    vec3 c = texture2D(uFrame, vUv).rgb;";
    }
    if (curveOn) {
        src += "\n    c = applyCurve(c);";
    }
    src +=
        "\n"
        "    float luma = lumaOf(c);\n"
        "    if (luma > uThreshold) {\n"
        "        gl_FragColor = vec4(texture2D(uStripe, fract(vUv * uTile)).rgb, 1.0);\n"
        "    } else {\n"
        "        gl_FragColor = vec4(applyGlow(c, vUvGlow), 1.0);\n"
        "    }\n"
        "}\n";
    return src;
}

/**
 * 上游 Shaders.peakingFragment(curveOn) 的逐字生成器 + 降噪维度（2026-10-28 Glow/降噪批）：
 * 中心 + 左右上下 5 点邻域亮度差分，边缘强度超 uStrength 处按 smoothstep 过渡带叠 uColor 描边；
 * 曲线只施加在中心取样上（差分取样仍走原帧，避免 256 档量化台阶被描成假边缘）；
 * 降噪同样只作用于中心取样的输出色（差分取样走原帧，理由与曲线同源），且**降噪的纹素步长
 * 走 uDenoiseTexel**——uTexel 在这里是峰值自己的邻域步长，两者同场不能共用一颗 uniform。
 * 描边色不被光晕染色：Glow 只包 mix 的 c（上游 glowFragment 的 PEAKING 注入点）。
 */
static std::string WotaPeakingFragment(bool curveOn, int denoise = WOTA_DENOISE_OFF)
{
    std::string src =
        "#extension GL_OES_EGL_image_external : require\n"
        "precision highp float;\n"
        "varying vec2 vUv;\n"
        "uniform samplerExternalOES uFrame;\n"
        "uniform vec2 uTexel;\n"
        "uniform float uStrength;\n"
        "uniform vec3 uColor;\n";
    if (curveOn) {
        src += WOTA_CURVE_DECL;
    }
    if (denoise != WOTA_DENOISE_OFF) {
        src += WotaBilateralDecl(denoise == WOTA_DENOISE_LITE, "uDenoiseTexel");
    }
    src += WOTA_GLOW_DECL;
    src +=
        "float lumaOf(vec3 c) {\n"
        "    return dot(c, vec3(0.299, 0.587, 0.114));\n"
        "}\n"
        "void main() {\n";
    if (denoise != WOTA_DENOISE_OFF) {
        src += "    vec3 c = bilateral(vUv);";
    } else {
        src += "    vec3 c = texture2D(uFrame, vUv).rgb;";
    }
    if (curveOn) {
        src += "\n    c = applyCurve(c);";
    }
    src +=
        "\n"
        "    float left = lumaOf(texture2D(uFrame, vUv - vec2(uTexel.x, 0.0)).rgb);\n"
        "    float right = lumaOf(texture2D(uFrame, vUv + vec2(uTexel.x, 0.0)).rgb);\n"
        "    float down = lumaOf(texture2D(uFrame, vUv - vec2(0.0, uTexel.y)).rgb);\n"
        "    float up = lumaOf(texture2D(uFrame, vUv + vec2(0.0, uTexel.y)).rgb);\n"
        "    float gx = abs(right - left);\n"
        "    float gy = abs(up - down);\n"
        "    // Sobel 思路的各向同性近似：主轴取大、副轴折半计权\n"
        "    float edge = max(gx, gy) + 0.5 * min(gx, gy);\n"
        "    // 0.08 的过渡带避免描边边缘成锯齿硬切；上界恒大于下界，smoothstep 不会退化\n"
        "    float paint = smoothstep(uStrength, uStrength + 0.08, edge);\n"
        "    gl_FragColor = vec4(mix(applyGlow(c, vUvGlow), uColor, paint), 1.0);\n"
        "}\n";
    return src;
}

/**
 * 上游 Shaders.ARC_MERGE_FS（2026-10-17 定版，逐 token）：
 * Δluma = luma(b) − luma(a)，严格大于 uDeadbandLuma 时整颗像素取 b，否则取 a。
 * 整像素搬运是"不造色"的唯一保证（不做逐通道 max/混合）；a = 保留帧/累积面，
 * b = 被抽帧；两个输入都是普通 2D FBO 纹理（OES 帧先经特效链落到 cur）。
 */
static const char* WOTA_ARC_MERGE_FS =
    "precision mediump float;\n"
    "varying vec2 vUv;\n"
    "uniform sampler2D uA;\n"
    "uniform sampler2D uB;\n"
    "uniform float uDeadbandLuma;\n"
    "void main() {\n"
    "    vec3 a = texture2D(uA, vUv).rgb;\n"
    "    vec3 b = texture2D(uB, vUv).rgb;\n"
    "    float la = dot(a, vec3(0.299, 0.587, 0.114));\n"
    "    float lb = dot(b, vec3(0.299, 0.587, 0.114));\n"
    "    gl_FragColor = vec4((lb - la) > uDeadbandLuma ? b : a, 1.0);\n"
    "}\n";

#endif // WOTA_SHADERS_H
