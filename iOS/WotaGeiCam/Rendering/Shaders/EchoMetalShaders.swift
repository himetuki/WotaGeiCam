#if arch(arm64) && canImport(Metal)
import Foundation

// MARK: - Metal 着色器源码（iOS 侧新写）
//
// 与 `EchoGLESShaders` 同源、同语义、**同样不是** Android `Shaders.kt` 的翻译：
// 输入/输出都是普通 `texture2d<float>`（BGRA8，来自 `CVPixelBuffer`），没有 OES/相机外部纹理。
//
// 运行期取源策略：经典工程不把 `.metal` 收进 Sources/Resources（Xcode 对 `.metal` 有专门编译语义，
// 放 Resources 不是可移植做法），因此**只使用内嵌 `embeddedSource`**。
// `Rendering/Resources/EchoKernels.metal` 是供审阅的参考副本；`check_project.py` 校验两者逐行一致。
// Metal 语法保持在 Metal 1.0 / iOS 10 档（`texture2d<T>`、无 `[[function_constant]]`、无 `texture2d<T>` 新 API）。

enum EchoMetalShaders {

    /// 运行期唯一源：内嵌文本（不再尝试读不存在的 bundle 资源）。
    static func source() -> String {
        return embeddedSource
    }

    /// 内嵌 Metal 源（与 `Rendering/Resources/EchoKernels.metal` 逐行一致，由检查脚本钉住）。
    static let embeddedSource = """
    #include <metal_stdlib>
    using namespace metal;

    struct VertexOut {
        float4 position [[position]];
        float2 uv;
    };

    // 全屏四边形（TRIANGLE_STRIP，顶点序 0..3）。NDC 下沿 ↔ 纹理 v=1，与输入/输出同向。
    vertex VertexOut echoVertex(uint vid [[vertex_id]]) {
        float2 positions[4] = { float2(-1.0, -1.0), float2(1.0, -1.0), float2(-1.0, 1.0), float2(1.0, 1.0) };
        float2 uvs[4] = { float2(0.0, 1.0), float2(1.0, 1.0), float2(0.0, 0.0), float2(1.0, 0.0) };
        VertexOut out;
        out.position = float4(positions[vid], 0.0, 1.0);
        out.uv = uvs[vid];
        return out;
    }

    struct EchoAccumUniforms {
        float decay;
        float mode;      // 0 = 保持亮度，1 = 提亮拖尾
        float threshold;
        float tintR;
        float tintG;
        float tintB;
        float mask;      // 0 = 不门控，1 = 择，2 = 除
        float tol;
    };

    // 回波累积趟：acc = max(cur, k·prev) / cur·t + k·prev。
    fragment float4 echoAccumFragment(VertexOut in [[stage_in]],
                                      texture2d<float> cur [[texture(0)]],
                                      texture2d<float> prev [[texture(1)]],
                                      constant EchoAccumUniforms& u [[buffer(0)]]) {
        constexpr sampler smp(filter::linear, address::clamp_to_edge);
        float3 c = cur.sample(smp, in.uv).rgb;
        float3 p = prev.sample(smp, in.uv).rgb * u.decay;
        if (u.mode < 0.5) {
            return float4(max(c, p), 1.0);
        }
        float luma = dot(c, float3(0.299, 0.587, 0.114));
        float t = smoothstep(u.threshold - 0.05, u.threshold + 0.05, luma);
        if (u.mask > 0.5) {
            float peak = max(c.r, max(c.g, c.b));
            float tintPeak = max(u.tintR, max(u.tintG, u.tintB));
            float sat = (peak - min(c.r, min(c.g, c.b))) / max(peak, 0.0001);
            float tintSat = (tintPeak - min(u.tintR, min(u.tintG, u.tintB))) / max(tintPeak, 0.0001);
            float m;
            if (sat < 0.05 || tintSat < 0.05) {
                m = (sat < 0.05 && tintSat < 0.05) ? 1.0 : 0.0;
            } else {
                float3 chroma = c / max(peak, 0.0001);
                float3 tintChroma = float3(u.tintR, u.tintG, u.tintB) / max(tintPeak, 0.0001);
                m = 1.0 - smoothstep(u.tol, u.tol + 0.1, length(chroma - tintChroma));
            }
            t = smoothstep(u.threshold - 0.05, u.threshold + 0.05, peak);
            t *= (u.mask < 1.5) ? m : (1.0 - m);
        }
        return float4(c * t + p, 1.0);
    }

    struct EchoCompositeUniforms {
        float mode;      // 0 = 保持亮度（max），1 = 提亮拖尾（加法）
        float gain;
        float tintR;
        float tintG;
        float tintB;
        float tintMode;  // 0 = 不染色，1 = 加，2 = 减
    };

    // 回波合成趟：applyEcho(base, echo)。
    fragment float4 echoCompositeFragment(VertexOut in [[stage_in]],
                                          texture2d<float> base [[texture(0)]],
                                          texture2d<float> echo [[texture(1)]],
                                          constant EchoCompositeUniforms& u [[buffer(0)]]) {
        constexpr sampler smp(filter::linear, address::clamp_to_edge);
        float3 c = base.sample(smp, in.uv).rgb;
        float3 e = echo.sample(smp, in.uv).rgb;
        if (u.mode < 0.5) {
            return float4(max(c, e), 1.0);
        }
        if (u.tintMode > 0.5) {
            float3 tint = float3(u.tintR, u.tintG, u.tintB);
            e *= (u.tintMode < 1.5) ? tint : (float3(1.0) - tint);
        }
        return float4(min(c + e * u.gain, float3(1.0)), 1.0);
    }

    // 直通拷贝（参数关闭 / 首帧兜底；不是黑帧）。
    fragment float4 echoCopyFragment(VertexOut in [[stage_in]],
                                     texture2d<float> frame [[texture(0)]]) {
        constexpr sampler smp(filter::linear, address::clamp_to_edge);
        return float4(frame.sample(smp, in.uv).rgb, 1.0);
    }

    struct ArcMergeUniforms {
        float deadband;
    };

    // 抽帧补弧：差分死区整像素选择。
    fragment float4 arcMergeFragment(VertexOut in [[stage_in]],
                                     texture2d<float> kept [[texture(0)]],
                                     texture2d<float> dropped [[texture(1)]],
                                     constant ArcMergeUniforms& u [[buffer(0)]]) {
        constexpr sampler smp(filter::linear, address::clamp_to_edge);
        float3 a = kept.sample(smp, in.uv).rgb;
        float3 b = dropped.sample(smp, in.uv).rgb;
        float la = dot(a, float3(0.299, 0.587, 0.114));
        float lb = dot(b, float3(0.299, 0.587, 0.114));
        return float4((lb - la) > u.deadband ? b : a, 1.0);
    }
    """
}
#endif
