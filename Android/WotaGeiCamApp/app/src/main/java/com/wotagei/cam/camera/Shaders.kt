package com.wotagei.cam.camera

/**
 * 渲染用的 GLSL ES 1.00 源码常量（斑马纹/峰值对焦/RGB 曲线都是片元级 pass，见 ）。
 *
 * 全局约定：
 * - 相机帧落在 `SurfaceTexture` 的 `GL_TEXTURE_EXTERNAL_OES` 纹理上，只能用 `samplerExternalOES`
 *   采样，所以每个片元着色器的第一行都必须 require `GL_OES_EGL_image_external`。
 * - 一律写成 ES 1.00（attribute/varying/texture2D），ES2 与 ES3 上下文都能编译；
 *   引擎会优先申请 ES3 上下文、失败退回 ES2，故着色器不能依赖 ES3 语法。
 * - 旋转与镜像烘在顶点的纹理坐标里（`GlRenderEngine.buildTexCoords`），`uTexMatrix` 只放
 *   `SurfaceTexture.getTransformMatrix` 的矩阵；两者混用会导致预览与录像方向不一致。
 * - 曲线按「是否需要」在 Kotlin 侧组合源码，不用 `#ifdef`：`#define` 要写在 `#extension`
 *   之前才生效，而部分驱动要求 `#extension` 是首条指令，会直接编译失败。
 */
internal object Shaders {

    /** 全屏四边形顶点 + 纹理坐标；纹理矩阵在 VS 里乘，FS 只做取样。 */
    val TEXTURE_VS = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        uniform mat4 uTexMatrix;
        varying vec2 vUv;
        void main() {
            gl_Position = aPosition;
            vUv = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
        }
    """.trimIndent()

    /** 直通（GPU 模式默认态，M2 门禁要求画面与 DIRECT 一致）。 */
    val PASS_THROUGH_FS = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vUv;
        uniform samplerExternalOES uFrame;
        void main() {
            // 强制不透明：部分机型相机帧的 alpha 通道为 0，直传会在半透明窗口面上变全黑
            gl_FragColor = vec4(texture2D(uFrame, vUv).rgb, 1.0);
        }
    """.trimIndent()

    /**
     * 直通的**普通 2D 纹理**变体，与 [PASS_THROUGH_FS] 唯一的差别是采样器类型。
     *
     * 为什么非有这条不可：GLSL 里采样器类型必须与纹理目标一致——规范上类型错配的 texture
     * lookup 结果**未定义**；常见驱动行为是按采样器声明的目标去读单元上对应槽位的绑定，
     * 于是 `samplerExternalOES` 采 `GL_TEXTURE_2D` 纹理时读到的 EXTERNAL 槽位是默认纹理
     * （不完整）→ 本机实测恒采出 (0,0,0,1) 全黑，且 draw/swap 照常成功、无任何失败返回。
     * 光弧修复 GPU 路的 cur/pend/acc 全是普通 2D FBO 纹理，曾把这条与 OES 版混用一条
     * program，真机成片全程黑屏（2026-10-05 定位，`record/ArcRepairGl` 与
     * `camera/ArcMendPass` 同一处缺陷一起修）——与 [FROST_COPY_FS] KDoc 里「OES 与 2D
     * 混进同一个 program 直接链接失败」是同一条纪律的两面：类型相同才能共 program，
     * 类型不同必须各备一条。
     *
     * 强制不透明：与 [PASS_THROUGH_FS] 同一个理由（解码/上游给的 alpha 不可信）。
     */
    val PASS_THROUGH_2D_FS = """
        precision mediump float;
        varying vec2 vUv;
        uniform sampler2D uFrame;
        void main() {
            gl_FragColor = vec4(texture2D(uFrame, vUv).rgb, 1.0);
        }
    """.trimIndent()

    /**
     * 曲线单独一条着色器，不给 `PASS_THROUGH_FS` 加分支：直通是「链接失败就没图」的最后兜底，
     * 一个字节都不该被新特性污染；曲线 program 编不出来时退回它，只是色调不生效。
     */
    val CURVE_FS = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vUv;
        uniform samplerExternalOES uFrame;
        uniform sampler2D uCurve;
        vec3 applyCurve(vec3 c) {
            return vec3(
                texture2D(uCurve, vec2(c.r, 0.5)).r,
                texture2D(uCurve, vec2(c.g, 0.5)).g,
                texture2D(uCurve, vec2(c.b, 0.5)).b);
        }
        void main() {
            vec3 c = texture2D(uFrame, vUv).rgb;
            gl_FragColor = vec4(applyCurve(c), 1.0);
        }
    """.trimIndent()

    /**
     * 画面降噪**双边滤波**（2026-10-26 用户需求：WOTA 艺夜间录制、天空暗部噪点多）。
     *
     * # 为什么是双边而不是高斯
     * 夜景暗部噪点是**随机高频**噪声，高斯能把噪声与细节一起抹平（光弧边缘、灯牌文字发虚）；
     * 双边滤波在空间距离之外再加一条**亮度差权重**：颜色相近的邻域才参与平均，跨越边缘的
     * 邻域被压低——于是"平坦的夜空被强降噪、弧光边缘保持锐利"，这正是本项目要的形态。
     *
     * # 采样与循环（**如实写**：这里没有做静态展开）
     * 实现是 `for (int i = 0; i < 8; i++)` 遍历一枚 `vec2 o[8]` 局部数组（中心单独算），
     * 未展开。合法性依据是 GLSL ES 1.00 Appendix A：循环上界是编译期常量、下标是
     * constant-index-expression（循环计数变量）⇒ 合规，主流驱动都收。
     * **仍可能被个别驱动拒绝**：那时 [com.wotagei.cam.camera.GlRenderEngine.buildProgram] 会
     * 退回 `PASS_THROUGH_FS` 并把失败写进日志（画面照旧、降噪静默失效）——排障时先看那行日志，
     * 别怀疑参数。早期注释曾自称"静态展开、规避循环风险"，与实现不符（2026-10-26 订正）。
     *
     * # 两级核（与 [BILATERAL_LITE_FS] 成对）
     * 本档是**完整核**（8 邻域）；高分辨率/高帧率下按负载自动换用轻量核（4 邻域交叉），
     * 分档判据在 `core/Denoise.kt` 的 `denoiseKernelLite`（纯函数，UI 与引擎共用）。
     *
     * # 参数（uniform）
     * - `uTexel`：1/相机帧尺寸（UV 空间的一个纹素步长）；帧尺寸变了必须重传，否则降噪半径错。
     * - `uSigmaR`：亮度差权重的高斯 σ（0..1 归一化亮度域）。**越小越保边**：只让几乎同色的
     *   邻域参与平均；夜间暗部噪点建议值见 GlRenderEngine（不写死在这里，便于以后做强度档）。
     *
     * # 与曲线的关系
     * 与 [CURVE_FS] 同一条链：[BILATERAL_CURVE_FS] = 先双边再查曲线表。分开两条而不是在
     * 一条里加 if，理由与 [CURVE_FS] 的注释同源——program 链接失败时的退化路径要各自独立。
     *
     * 强制不透明：与 [PASS_THROUGH_FS] 同一个理由（HAL 给 alpha=0 时读出半透明）。
     */
    val BILATERAL_FS = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vUv;
        uniform samplerExternalOES uFrame;
        uniform vec2 uTexel;
        uniform float uSigmaR;
        vec3 bilateral(vec2 uv) {
            vec3 c = texture2D(uFrame, uv).rgb;
            // 亮度差权重：Δ 越大权重越小（高斯核），跨边缘的邻域自然被抑制
            float inv2s2 = 1.0 / (2.0 * uSigmaR * uSigmaR);
            vec3 sum = c;
            float wsum = 1.0;
            vec2 o[8];
            o[0] = vec2(-1.0, -1.0); o[1] = vec2(0.0, -1.0); o[2] = vec2(1.0, -1.0);
            o[3] = vec2(-1.0,  0.0);                          o[4] = vec2(1.0,  0.0);
            o[5] = vec2(-1.0,  1.0); o[6] = vec2(0.0,  1.0); o[7] = vec2(1.0,  1.0);
            for (int i = 0; i < 8; i++) {
                vec3 s = texture2D(uFrame, uv + o[i] * uTexel).rgb;
                vec3 d = s - c;
                float w = exp(-dot(d, d) * inv2s2);
                sum += s * w;
                wsum += w;
            }
            return sum / wsum;
        }
        void main() {
            gl_FragColor = vec4(bilateral(vUv), 1.0);
        }
    """.trimIndent()

    /** 双边 + 曲线（顺序：先降噪再查曲线表；分开独立程序，退化路径不互相牵连） */
    val BILATERAL_CURVE_FS = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vUv;
        uniform samplerExternalOES uFrame;
        uniform sampler2D uCurve;
        uniform vec2 uTexel;
        uniform float uSigmaR;
        vec3 applyCurve(vec3 c) {
            return vec3(
                texture2D(uCurve, vec2(c.r, 0.5)).r,
                texture2D(uCurve, vec2(c.g, 0.5)).g,
                texture2D(uCurve, vec2(c.b, 0.5)).b);
        }
        vec3 bilateral(vec2 uv) {
            vec3 c = texture2D(uFrame, uv).rgb;
            float inv2s2 = 1.0 / (2.0 * uSigmaR * uSigmaR);
            vec3 sum = c;
            float wsum = 1.0;
            vec2 o[8];
            o[0] = vec2(-1.0, -1.0); o[1] = vec2(0.0, -1.0); o[2] = vec2(1.0, -1.0);
            o[3] = vec2(-1.0,  0.0);                          o[4] = vec2(1.0,  0.0);
            o[5] = vec2(-1.0,  1.0); o[6] = vec2(0.0,  1.0); o[7] = vec2(1.0,  1.0);
            for (int i = 0; i < 8; i++) {
                vec3 s = texture2D(uFrame, uv + o[i] * uTexel).rgb;
                vec3 d = s - c;
                float w = exp(-dot(d, d) * inv2s2);
                sum += s * w;
                wsum += w;
            }
            return sum / wsum;
        }
        void main() {
            gl_FragColor = vec4(applyCurve(bilateral(vUv)), 1.0);
        }
    """.trimIndent()

    /**
     * 画面降噪**轻量档**（4 邻域交叉：左/右/上/下 + 中心 = 5 次采样，完整档是 9 次）。
     *
     * 给"本机跑不动完整核"的场景用：分档判据在 `core/Denoise.kt` 的 `denoiseKernelLite`
     * （按编码面"像素 × 帧率"这个固定负载算，纯函数），4K30 / 1080p120 / 4K60 / 1080p240
     * 这类组合自动落到这一档。**仍然降噪**，只是空间支持半径小一半、边缘保持略弱——
     * 不是"关掉降噪"，档位与面板设置都不变。
     *
     * 权重公式、uniform 语义、不透明处理与 [BILATERAL_FS] 完全一致，只换邻域集合，
     * 所以两档之间切换不会改变画面亮度/色相，只改降噪强度。
     */
    val BILATERAL_LITE_FS = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vUv;
        uniform samplerExternalOES uFrame;
        uniform vec2 uTexel;
        uniform float uSigmaR;
        vec3 bilateral(vec2 uv) {
            vec3 c = texture2D(uFrame, uv).rgb;
            float inv2s2 = 1.0 / (2.0 * uSigmaR * uSigmaR);
            vec3 sum = c;
            float wsum = 1.0;
            vec2 o[4];
            o[0] = vec2(-1.0, 0.0); o[1] = vec2(1.0, 0.0);
            o[2] = vec2(0.0, -1.0); o[3] = vec2(0.0, 1.0);
            for (int i = 0; i < 4; i++) {
                vec3 s = texture2D(uFrame, uv + o[i] * uTexel).rgb;
                vec3 d = s - c;
                float w = exp(-dot(d, d) * inv2s2);
                sum += s * w;
                wsum += w;
            }
            return sum / wsum;
        }
        void main() {
            gl_FragColor = vec4(bilateral(vUv), 1.0);
        }
    """.trimIndent()

    /** 轻量核 + 曲线（顺序同 [BILATERAL_CURVE_FS]：先降噪再查曲线表） */
    val BILATERAL_LITE_CURVE_FS = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vUv;
        uniform samplerExternalOES uFrame;
        uniform sampler2D uCurve;
        uniform vec2 uTexel;
        uniform float uSigmaR;
        vec3 applyCurve(vec3 c) {
            return vec3(
                texture2D(uCurve, vec2(c.r, 0.5)).r,
                texture2D(uCurve, vec2(c.g, 0.5)).g,
                texture2D(uCurve, vec2(c.b, 0.5)).b);
        }
        vec3 bilateral(vec2 uv) {
            vec3 c = texture2D(uFrame, uv).rgb;
            float inv2s2 = 1.0 / (2.0 * uSigmaR * uSigmaR);
            vec3 sum = c;
            float wsum = 1.0;
            vec2 o[4];
            o[0] = vec2(-1.0, 0.0); o[1] = vec2(1.0, 0.0);
            o[2] = vec2(0.0, -1.0); o[3] = vec2(0.0, 1.0);
            for (int i = 0; i < 4; i++) {
                vec3 s = texture2D(uFrame, uv + o[i] * uTexel).rgb;
                vec3 d = s - c;
                float w = exp(-dot(d, d) * inv2s2);
                sum += s * w;
                wsum += w;
            }
            return sum / wsum;
        }
        void main() {
            gl_FragColor = vec4(applyCurve(bilateral(vUv)), 1.0);
        }
    """.trimIndent()

    // region 光弧修复（用户需求第 7 项：v2 抽帧+补弧，不改任何既有 pass）

    /**
     * 补弧**整像素选择**片元（2026-10-17 定版）：
     * `Δluma = luma(b) − luma(a)`；`Δluma > uDeadbandLuma`（**严格大于**）时整颗像素取 `b`，
     * 否则取 `a`。
     *
     * - **不造色的唯一保证是整像素搬运**：输出的 RGB 三元组必须整体来自 `a` 或整体来自 `b`，
     *   绝不做逐通道 `max`/加权混合。逐通道取大会把两个不同色相拼成原片没有的颜色
     *   （实测：极橙 (255,140,0) 与黑合并 → 珊瑚粉 (255,109,158)，蓝通道凭空 +158）；
     *   这条缺陷在 CPU 路的 YUV 逐平面取大上真机像素验证过，GPU 路同族，一起改。
     * - **死区 `uDeadbandLuma` 是差分阈值**（`D/255.0`，D 默认 12、钳 0..32），不是"多亮才算弧"
     *   的绝对亮度阈值：相同亮度的地面/人体受光不叠加，只有真正新增的光才改变像素。
     *   `D=0` 退化为纯整像素选择，仍不造色。
     * - **已知残差（真机待验）**：本 shader 是逐像素整像素选择，但输出走 Surface 交给 H.264
     *   编码器时默认 4:2:0——一个 2×2 块内可能同时含来自 a 与 b 的像素，编码器做色度抽样时会
     *   把两帧色度平均 ⇒ **弧边缘仍可能出现 1~2px 的色度混色**（非全画面）。CPU 路按 2×2 块
     *   整体搬运，没有这一层。不改成按块输出：那会让 luma 变块状，得不偿失。
     * - **量纲不同（刻意近似）**：这里拿 SurfaceTexture 解码后的 **full-range RGB** 按 BT.601
     *   权重算 luma；CPU 路直接吃解码器**原始 Y 平面**（H.264 常见 limited range 16..235、
     *   HD 常 BT.709）。中性内容 `ΔGPU ≈ 1.164 × ΔY`，同一 D 的有效门槛约差 1.16 倍；
     *   彩色内容还叠加矩阵差异。不是同量纲，统一量纲要动解码路径。
     * - v2 抽帧+补弧语义不变：被抽帧独有的弧段在两邻帧上重现，弧不在抽帧点断裂；
     *   v1 的 0.5 cross-fade 会把弧拆成两道半亮重影（原帧/混合帧逐帧交替＝用户看到的
     *   "来回闪动"），已废弃。
     * - 两个输入都是**普通 2D 纹理**（OES 帧先经拷贝趟落到 2D），所以不需要 OES 扩展声明；
     *   取样单元 **0 = uA（保留帧/累积面）、1 = uB（被抽帧）**（宿主 ArcMendPass/ArcRepairGl
     *   同一份常量），写入目标是乒乓的另一面，读写不同体、无 feedback loop。
     * - 强制不透明：与 [PASS_THROUGH_FS] 同一个理由——HAL/解码器给 alpha=0 时会读出半透明。
     *   **`alpha = 1.0` 是载重不变量**，`ArcRepairMergeParityTest` 的源扫描钉着。
     */
    val ARC_MERGE_FS = """
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
    """.trimIndent()

    // endregion

    /** 注入到斑马纹/峰值里的曲线取样实现；曲线关着时调用方传空串，源码与引入曲线前逐字相同。 */
    private const val CURVE_DECL = """
uniform sampler2D uCurve;
vec3 applyCurve(vec3 c) {
    return vec3(
        texture2D(uCurve, vec2(c.r, 0.5)).r,
        texture2D(uCurve, vec2(c.g, 0.5)).g,
        texture2D(uCurve, vec2(c.b, 0.5)).b);
}
    """

    /**
     * 斑马纹（逐像素亮度比较，超阈值处叠 `GL_REPEAT` 平铺条纹贴图，其余保持原画面）。
     * uThreshold 为 0..1 归一化亮度（UI 80–100 ↔ 0.6–1.0）；uTile 为平铺倍数（密度档 1/2/3 → 4/8/16）。
     * 曲线在亮度判定之前施加，斑马纹才会标在「所见」的过曝点上。
     */
    fun zebraFragment(curveOn: Boolean): String {
        val decl = if (curveOn) CURVE_DECL else ""
        val apply = if (curveOn) "\n    c = applyCurve(c);" else ""
        return """
        #extension GL_OES_EGL_image_external : require
        precision highp float;
        varying vec2 vUv;
        uniform samplerExternalOES uFrame;
        uniform sampler2D uStripe;
        uniform float uThreshold;
        uniform vec2 uTile;
$decl
        float lumaOf(vec3 c) {
            return dot(c, vec3(0.299, 0.587, 0.114));
        }
        void main() {
            vec3 c = texture2D(uFrame, vUv).rgb;$apply
            float luma = lumaOf(c);
            if (luma > uThreshold) {
                gl_FragColor = vec4(texture2D(uStripe, fract(vUv * uTile)).rgb, 1.0);
            } else {
                gl_FragColor = vec4(c, 1.0);
            }
        }
        """.trimIndent()
    }

    /**
     * 峰值对焦（/中心 + 左右上下 5 点邻域亮度差分，边缘强度超阈值处叠纯色描边）。
     * uTexel 是邻域取样步长（UV 空间，取 0.002 量级，与帧尺寸无关）；
     * uStrength 是 0..1 的边缘强度阈值；uColor 由引擎按 PeakingColor 档位换算。
     * 用 highp：差分结果在 mediump 下容易量化成台阶。
     *
     * 曲线只施加在中心取样（描底色）上，四个差分取样仍走原帧：256 档查表在平滑渐变里存在
     * 1/255 的量化台阶，若拿它做差分，峰值会把曲线每一级台阶都描成「合焦边」。
     */
    fun peakingFragment(curveOn: Boolean): String {
        val decl = if (curveOn) CURVE_DECL else ""
        val apply = if (curveOn) "\n    c = applyCurve(c);" else ""
        return """
        #extension GL_OES_EGL_image_external : require
        precision highp float;
        varying vec2 vUv;
        uniform samplerExternalOES uFrame;
        uniform vec2 uTexel;
        uniform float uStrength;
        uniform vec3 uColor;
$decl
        float lumaOf(vec3 c) {
            return dot(c, vec3(0.299, 0.587, 0.114));
        }
        void main() {
            vec3 c = texture2D(uFrame, vUv).rgb;$apply
            float left = lumaOf(texture2D(uFrame, vUv - vec2(uTexel.x, 0.0)).rgb);
            float right = lumaOf(texture2D(uFrame, vUv + vec2(uTexel.x, 0.0)).rgb);
            float down = lumaOf(texture2D(uFrame, vUv - vec2(0.0, uTexel.y)).rgb);
            float up = lumaOf(texture2D(uFrame, vUv + vec2(0.0, uTexel.y)).rgb);
            float gx = abs(right - left);
            float gy = abs(up - down);
            // Sobel 思路的各向同性近似：主轴取大、副轴折半计权
            float edge = max(gx, gy) + 0.5 * min(gx, gy);
            // 0.08 的过渡带避免描边边缘成锯齿硬切；上界恒大于下界，smoothstep 不会退化
            float paint = smoothstep(uStrength, uStrength + 0.08, edge);
            gl_FragColor = vec4(mix(c, uColor, paint), 1.0);
        }
        """.trimIndent()
    }

    // region 毛玻璃离屏链（#84：三趟里后两趟的源；第一趟见 FROST_COPY_FS）

    /**
     * 第 ① 趟 OES → 2D 拷贝（离屏）。
     *
     * 为什么非有这一趟不可：`GL_TEXTURE_EXTERNAL_OES` **不能当 FBO 颜色附件**，而高斯要跑在 FBO 上，
     * 所以先把相机帧拷进一张普通 `GL_TEXTURE_2D`。这一层今天之前全工程不存在（唯一的 2D 纹理是曲线 LUT
     * 与斑马纹瓦片，都是贴图不是画布）。
     *
     * 顶点源沿用 [TEXTURE_VS]、纹理坐标沿用「上屏 pass 那一套」（`buildTexCoords(encoderPass = false)`），
     * 于是拷出来的 RT 与屏幕上看到的等比画面**同朝向、同内容**：卡片矩形 → UV 只剩一次仿射，
     * 不必再管机型给的缓冲矩阵。
     *
     * 它是新增片元里**唯一**允许出现 `samplerExternalOES` 的一条；模糊那两条只吃 `sampler2D`，
     * 两者混进同一个 program 会直接链接失败。
     */
    val FROST_COPY_FS = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vUv;
        uniform samplerExternalOES uFrame;
        void main() {
            // 强制不透明：与 PASS_THROUGH_FS 同一个理由——HAL 给 alpha=0 时这里会连着糊出一张全黑
            gl_FragColor = vec4(texture2D(uFrame, vUv).rgb, 1.0);
        }
    """.trimIndent()

    /**
     * 第 ②③ 趟的顶点源：恒等映射，**不乘** `uTexMatrix`。
     *
     * RT 已经是普通 2D 图（朝向在拷贝趟就烘进去了），再乘一次机型给的缓冲矩阵会被转两遍。
     */
    val FROST_QUAD_VS = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vUv;
        void main() {
            gl_Position = aPosition;
            vUv = aTexCoord;
        }
    """.trimIndent()

    /**
     * 可分离高斯的一趟源（横向与纵向共用同一条 program，差别只在 `uOffset` 的方向，
     * 由 [fillFrostBlurOffset] 给；这样只需一枚 program，转屏/改尺寸都不必重编译）。
     *
     * - 只吃 `sampler2D`（见 [FROST_COPY_FS] 那条纪律）；
     * - `uWeight` 是长度 = [FROST_BLUR_WEIGHTS] 表长的**常量下标**数组（ES 1.00 允许常量下标，
     *   不允许变量下标），抽头展开成 2N-1 次取样；表长与下标必须同源，否则链接期数组越界、整条链停用；
     * - 默认 highp：取样位移是 1/RT 边长量级的小数，mediump 下与 vUv 相加会丢精度（糊出来的方向偏移）；
     *   少数驱动不支持片元 highp，调用方会把 [highp] 改 false 重编一次再不行才停用模糊
     *   （与 `GlRenderEngine.buildProgram` 的退避同一思路）。
     */
    fun frostGaussianFragment(weightCount: Int, highp: Boolean): String {
        val precision = if (highp) "highp" else "mediump"
        val taps = StringBuilder()
        taps.append("        vec3 sum = texture2D(uSrc, vUv).rgb * uWeight[0];")
        for (i in 1 until weightCount) {
            taps.append("\n        sum += (texture2D(uSrc, vUv + uOffset * ").append(i).append(".0).rgb")
            taps.append(" + texture2D(uSrc, vUv - uOffset * ").append(i).append(".0).rgb) * uWeight[").append(i).append("];")
        }
        return """
        precision $precision float;
        varying vec2 vUv;
        uniform sampler2D uSrc;
        uniform vec2 uOffset;
        uniform float uWeight[$weightCount];
        void main() {
$taps
            gl_FragColor = vec4(sum, 1.0);
        }
        """.trimIndent()
    }

    // endregion

    // region 上屏画板（#84 步骤 2 · A2 混合：GL 只出「模糊纹理 + 圆角裁切 + 底板色」这一块板）

    /**
     * 画板的顶点源：一枚**单位四边形**（`GlRenderEngine.positions` 那套 `-1..1`）按 uniform 摆到
     * 这一块板在 NDC 里的位置。
     *
     * 为什么走 uniform 而不是每帧改顶点缓冲：矩形表每帧最多 8 块板，走 `glVertexAttribPointer` 就要
     * 每帧往 direct buffer 里重写 4×2 个数并 `position(0)`（且两块板之间还得防着读到上一块的数据），
     * 而 uniform 版一次绘制只改 3 个 uniform、顶点缓冲恒是那枚构造期建好的全屏四边形，**零缓冲改动**。
     *
     * `vLocal` 带着 `-1..1` 的局部坐标进片元（`uHalfPx` 在片元侧乘回像素），`vUv` 直接线性插值到
     * 采样窗口：u 用 x、v 用 y 且 **y=+1 落在 vMax**（GL 的 v 向上，与 [FrostUvRect] 同一口径）。
     * 不乘 `uTexMatrix`：霜面已经是普通 2D 图（朝向在拷贝趟就烘进去了），再乘一次机型缓冲矩阵会被转两遍
     * ——与 [FROST_QUAD_VS] 同一条理由。
     */
    val FROST_PLATE_VS = """
        attribute vec4 aPosition;
        uniform vec4 uRect;
        uniform vec4 uUvRect;
        varying vec2 vLocal;
        varying vec2 vUv;
        void main() {
            gl_Position = vec4(uRect.xy + aPosition.xy * uRect.zw, 0.0, 1.0);
            vLocal = aPosition.xy;
            vec2 t = (aPosition.xy + 1.0) * 0.5;
            vUv = vec2(mix(uUvRect.x, uUvRect.y, t.x), mix(uUvRect.z, uUvRect.w, t.y));
        }
    """.trimIndent()

    /**
     * 画板的片元：rounded-rect SDF 遮罩 + 霜面取样 + 底板色压深。
     *
     * - `sd` 那三行与 [frostRoundedRectSdfPx] 是同一条算式（后者是能在 JVM 上跑的镜像、有手算单测；
     *   前者只有真机能证）。半径先夹进 `[0, 短边一半]` 再参与算式：`uRadiusPx` 送进来的可能是
     *   「胶囊 = 短边一半」这一档，不夹就会出现 `half - r` 为负、整块板被判到外面（板凭空消失）。
     * - `cover` 只在轮廓那 1px 里取中间值 ⇒ **圆角靠它，不靠 stencil、不加 pass**：
     *   配合 `GL_BLEND` 的 `SRC_ALPHA/ONE_MINUS_SRC_ALPHA`，alpha=0 的像素等于没画。
     * - `mix(uTint, blur, 1.0 - uAlpha)`：底板色与透光是一枚值的两面，透光 = 1 − alpha，
     *   档位只从 `ui/design/frostScrimAlphaFor` 那条桥来（docs/plan/14 §二 的 WCAG 账），
     *   片元这里**不许**再写第二个 alpha 常量。
     * - 信箱黑边不参与采样：送进来的 `uUvRect` 已经被 [frostUvInto] 裁到等比画面内，
     *   贴边那几块板最多多吃一个纹素（CLAMP_TO_EDGE + 向外取整），不会绕到画面另一头。
     * - 默认 highp：`sd` 的自变量是**像素**坐标（本机 Dock 底板半轴 ≈216px），mediump 的 10 位尾数
     *   在 200 量级上只剩 ±0.1px 分辨力，圆角过渡带会被量化成锯齿；少数驱动不支持片元 highp，
     *   调用方会拿 [highp] = false 重编一次、再不行才停用画板（与 [GlRenderEngine.buildProgram] 同思路）。
     */
    fun frostPlateFragment(highp: Boolean): String {
        val precision = if (highp) "highp" else "mediump"
        return """
        precision $precision float;
        varying vec2 vLocal;
        varying vec2 vUv;
        uniform sampler2D uSrc;
        uniform vec2 uHalfPx;
        uniform float uRadiusPx;
        uniform vec3 uTint;
        uniform float uAlpha;
        void main() {
            vec2 ext = uHalfPx;
            float r = min(max(uRadiusPx, 0.0), min(ext.x, ext.y));
            vec2 q = abs(vLocal) * ext - (ext - vec2(r));
            float sd = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
            float cover = 1.0 - smoothstep(-1.0, 1.0, sd);
            vec3 plate = mix(uTint, texture2D(uSrc, vUv).rgb, 1.0 - uAlpha);
            gl_FragColor = vec4(plate, cover);
        }
        """.trimIndent()
    }

    // endregion
}
