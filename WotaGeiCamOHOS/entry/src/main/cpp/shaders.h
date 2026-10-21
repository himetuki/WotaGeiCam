/*
 * 上游 camera/Shaders.kt 首批基对逐字移植（对齐上游 38d712a，Shaders.kt 19–40 行）：
 * TEXTURE_VS（全屏四边形顶点 + 纹理坐标，纹理矩阵在 VS 里乘）+ PASS_THROUGH_FS（直通，
 * 强制不透明）；ES 1.00（attribute/varying/gl_FragColor），ES2/ES3 上下文都能编译。
 * 后续 pass（PASS_THROUGH_2D_FS/CURVE_FS/ARC_MERGE_FS/zebra/peaking/frost）从 GL-2 起分批，不预埋。
 */
#ifndef WOTA_SHADERS_H
#define WOTA_SHADERS_H

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

#endif // WOTA_SHADERS_H
