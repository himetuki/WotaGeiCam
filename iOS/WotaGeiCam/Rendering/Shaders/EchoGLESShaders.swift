import Foundation

// MARK: - OpenGL ES 2 着色器（iOS 侧新写）
//
// **不是** Android `camera/Shaders.kt` 的翻译：Android 链吃 `samplerExternalOES`（相机 OES 纹理），
// 本后端的输入/输出都是普通 `sampler2D`（`CVPixelBuffer` 经 `CVOpenGLESTextureCache` 映射为 2D 纹理），
// 因此纹理矩阵、OES 扩展、DISPLAY/MOVIE 槽机制一律不存在。算法语义（反馈累积算式、双模式、
// 拖尾色门控/染色、差分死区）与 Android 逐项同源，源码逐字重写。
//
// 目标：OpenGL ES 2.0（GLSL ES 100），覆盖 iOS 10 / iPhone 5（A6，无 Metal）。

enum EchoGLESShaders {

    /// 全屏四边形顶点着色器（恒等纹理坐标，与输入/输出同向，无需翻转）。
    static let fullscreenVertex = """
    attribute vec4 aPosition;
    attribute vec2 aTexCoord;
    varying vec2 vUv;
    void main() {
        gl_Position = aPosition;
        vUv = aTexCoord;
    }
    """

    /// 直通拷贝（参数关闭或首帧兜底；**不是**黑帧）。
    static let copyFragment = """
    precision mediump float;
    varying vec2 vUv;
    uniform sampler2D uFrame;
    void main() {
        gl_FragColor = vec4(texture2D(uFrame, vUv).rgb, 1.0);
    }
    """

    /// 回波累积趟：`acc = max(cur, k·prev)`（保持亮度）/ `cur·t + k·prev`（提亮拖尾）。
    ///
    /// `uMode` 0 = 保持亮度，1 = 提亮拖尾；`uMask` 0/1/2 = 无/择/除（拖尾颜色补丁的源像素门控）；
    /// `uTint/uTol` 为择/除的色度目标与容差（仅 TRAIL 且 `uMask>0.5` 时参与）。
    static let echoAccumFragment = """
    precision mediump float;
    varying vec2 vUv;
    uniform sampler2D uCur;
    uniform sampler2D uPrev;
    uniform float uDecay;
    uniform float uMode;
    uniform float uThreshold;
    uniform vec3 uTint;
    uniform float uMask;
    uniform float uTol;
    void main() {
        vec3 c = texture2D(uCur, vUv).rgb;
        vec3 prev = texture2D(uPrev, vUv).rgb * uDecay;
        if (uMode < 0.5) {
            gl_FragColor = vec4(max(c, prev), 1.0);
        } else {
            float luma = dot(c, vec3(0.299, 0.587, 0.114));
            float t = smoothstep(uThreshold - 0.05, uThreshold + 0.05, luma);
            if (uMask > 0.5) {
                float peak = max(c.r, max(c.g, c.b));
                float tintPeak = max(uTint.r, max(uTint.g, uTint.b));
                float sat = (peak - min(c.r, min(c.g, c.b))) / max(peak, 0.0001);
                float tintSat = (tintPeak - min(uTint.r, min(uTint.g, uTint.b))) / max(tintPeak, 0.0001);
                float m;
                if (sat < 0.05 || tintSat < 0.05) {
                    m = (sat < 0.05 && tintSat < 0.05) ? 1.0 : 0.0;
                } else {
                    vec3 chroma = c / max(peak, 0.0001);
                    vec3 tintChroma = uTint / max(tintPeak, 0.0001);
                    m = 1.0 - smoothstep(uTol, uTol + 0.1, length(chroma - tintChroma));
                }
                t = smoothstep(uThreshold - 0.05, uThreshold + 0.05, peak);
                t *= (uMask < 1.5) ? m : 1.0 - m;
            }
            gl_FragColor = vec4(c * t + prev, 1.0);
        }
    }
    """

    /// 回波合成趟：`applyEcho(base, echo)`。
    ///
    /// `uMode` 0 = 保持亮度（`max`），1 = 提亮拖尾（`min(base + echo·gain, 1)`，可先按加/减染色）。
    static let echoCompositeFragment = """
    precision mediump float;
    varying vec2 vUv;
    uniform sampler2D uBase;
    uniform sampler2D uEcho;
    uniform float uMode;
    uniform float uGain;
    uniform vec3 uTint;
    uniform float uTintMode;
    void main() {
        vec3 c = texture2D(uBase, vUv).rgb;
        vec3 e = texture2D(uEcho, vUv).rgb;
        if (uMode < 0.5) {
            gl_FragColor = vec4(max(c, e), 1.0);
        } else {
            if (uTintMode > 0.5) {
                e *= (uTintMode < 1.5) ? uTint : (vec3(1.0) - uTint);
            }
            gl_FragColor = vec4(min(c + e * uGain, vec3(1.0)), 1.0);
        }
    }
    """

    /// 抽帧补弧：差分死区整像素选择 `select(kept, dropped)`（`uA` = 累积面，`uB` = 被抽帧）。
    static let arcMergeFragment = """
    precision mediump float;
    varying vec2 vUv;
    uniform sampler2D uA;
    uniform sampler2D uB;
    uniform float uDeadbandLuma;
    void main() {
        vec3 a = texture2D(uA, vUv).rgb;
        vec3 b = texture2D(uB, vUv).rgb;
        float la = dot(a, vec3(0.299, 0.587, 0.114));
        float lb = dot(b, vec3(0.299, 0.587, 0.114));
        gl_FragColor = vec4((lb - la) > uDeadbandLuma ? b : a, 1.0);
    }
    """
}
