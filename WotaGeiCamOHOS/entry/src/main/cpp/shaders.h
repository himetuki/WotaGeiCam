/*
 * 上游 camera/Shaders.kt 首批基对逐字移植（对齐上游 38d712a，Shaders.kt 19–40 行）：
 * TEXTURE_VS（全屏四边形顶点 + 纹理坐标，纹理矩阵在 VS 里乘）+ PASS_THROUGH_FS（直通，
 * 强制不透明）；ES 1.00（attribute/varying/gl_FragColor），ES2/ES3 上下文都能编译。
 * 后续 pass（PASS_THROUGH_2D_FS/CURVE_FS/ARC_MERGE_FS/zebra/peaking/frost）从 GL-2 起分批，不预埋。
 */
#ifndef WOTA_SHADERS_H
#define WOTA_SHADERS_H

#include <string>

/** 上游 Shaders.TEXTURE_VS */
static const char* WOTA_TEXTURE_VS =
    "attribute vec4 aPosition;\n"
    "attribute vec2 aTexCoord;\n"
    "uniform mat4 uTexMatrix;\n"
    "varying vec2 vUv;\n"
    "void main() {\n"
    "    gl_Position = aPosition;\n"
    "    vUv = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;\n"
    "}\n";

/** 上游 Shaders.PASS_THROUGH_FS（ES 1.00；#extension 必须是首条指令） */
static const char* WOTA_PASS_THROUGH_FS =
    "#extension GL_OES_EGL_image_external : require\n"
    "precision mediump float;\n"
    "varying vec2 vUv;\n"
    "uniform samplerExternalOES uFrame;\n"
    "void main() {\n"
    "    // 强制不透明：部分机型相机帧的 alpha 通道为 0，直传会在半透明窗口面上变全黑\n"
    "    gl_FragColor = vec4(texture2D(uFrame, vUv).rgb, 1.0);\n"
    "}\n";

/** 上游 Shaders.CURVE_FS（曲线单独一条着色器，不给 PASS_THROUGH 加分支） */
static const char* WOTA_CURVE_FS =
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
    "}\n"
    "void main() {\n"
    "    vec3 c = texture2D(uFrame, vUv).rgb;\n"
    "    gl_FragColor = vec4(applyCurve(c), 1.0);\n"
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

/**
 * 上游 Shaders.zebraFragment(curveOn) 的逐字生成器：
 * 逐像素亮度比较（uThreshold），超阈值处叠 GL_REPEAT 平铺条纹贴图（fract(vUv * uTile)），
 * 其余保持原画面；曲线在亮度判定之前施加（斑马纹标在「所见」的过曝点上）。
 */
static std::string WotaZebraFragment(bool curveOn)
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
    src +=
        "float lumaOf(vec3 c) {\n"
        "    return dot(c, vec3(0.299, 0.587, 0.114));\n"
        "}\n"
        "void main() {\n"
        "    vec3 c = texture2D(uFrame, vUv).rgb;";
    if (curveOn) {
        src += "\n    c = applyCurve(c);";
    }
    src +=
        "\n"
        "    float luma = lumaOf(c);\n"
        "    if (luma > uThreshold) {\n"
        "        gl_FragColor = vec4(texture2D(uStripe, fract(vUv * uTile)).rgb, 1.0);\n"
        "    } else {\n"
        "        gl_FragColor = vec4(c, 1.0);\n"
        "    }\n"
        "}\n";
    return src;
}

/**
 * 上游 Shaders.peakingFragment(curveOn) 的逐字生成器：
 * 中心 + 左右上下 5 点邻域亮度差分，边缘强度超 uStrength 处按 smoothstep 过渡带叠 uColor 描边；
 * 曲线只施加在中心取样上（差分取样仍走原帧，避免 256 档量化台阶被描成假边缘）。
 */
static std::string WotaPeakingFragment(bool curveOn)
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
    src +=
        "float lumaOf(vec3 c) {\n"
        "    return dot(c, vec3(0.299, 0.587, 0.114));\n"
        "}\n"
        "void main() {\n"
        "    vec3 c = texture2D(uFrame, vUv).rgb;";
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
        "    gl_FragColor = vec4(mix(c, uColor, paint), 1.0);\n"
        "}\n";
    return src;
}

#endif // WOTA_SHADERS_H
