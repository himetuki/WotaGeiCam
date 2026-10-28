package com.wotagei.cam.core

import com.wotagei.cam.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Glow（荧光棒光晕，docs/plan/24 R3 §1.1–§1.5）的**参数模型与纯函数**。
 *
 * # 它是 scene layer，不是监看叠加
 * 与斑马纹/峰值对焦相反：Glow 是**画面内容的一部分**（光晕该被录进去），所以两个 program 槽
 * （DISPLAY / MOVIE）都带它（25 号计划 §11.1/§11.11）。它在 `ProgramKey` 里是**独立一维**
 * `glow`，不是"有我没有"的互斥分支——曲线/降噪/斑马/峰值都能与它共存。
 *
 * # 为什么默认关
 * 默认档必须与改前逐字节一致（24 §〇.1 不变量 1）：关着时片元源码逐字不变、离屏链零调用。
 * 用户显式打开才付出 Glow 的 GPU 成本。
 *
 * # 颜色用 ARGB Long 而不是 Compose Color
 * 与 [MarkerLineColor] 同族：core 层不引 Compose，且 `Color` 这个名字会与 Compose 的撞名。
 *
 * # 域钳在 [sanitize]
 * 所有数值都在构造/解码时钳进安全域，**shader 侧不再判断**（少一条分支 = 少一处驱动差异）。
 * NaN/Infinity 一律回落到该字段的出厂值：坏值若漏进 uniform，效果是"整帧看不出原因的发灰"。
 */
data class GlowParams(
    /** 开关：映射成 [com.wotagei.cam.camera.ProgramKey] 的 `glow` 一维（换开关 = 换 program，走会话缓存） */
    val enabled: Boolean = false,
    /** 0..1 亮度阈值：超过才发光（与 Sapphire `S_Glow.Threshold` 同名同义） */
    val threshold: Float = 0.5f,
    /** 0..0.5 阈值软边宽度（smoothstep 的过渡带） */
    val soft: Float = 0.05f,
    /** 0..0.2 光晕扩散半径，以 **RT 短边** 为单位（各向同性，见 [fillGlowBlurOffset]） */
    val width: Float = 0.02f,
    /** 0..4 光晕强度倍数（主 pass） */
    val gain: Float = 1.0f,
    /** 0..1 额外叠加的光量（过曝感，主 pass） */
    val add: Float = 0.0f,
    /** 0..64 光晕噪声频率（0 = 干净），主 pass；链只跑一次所以噪声不能进链 */
    val freq: Float = 0.0f,
    /** 0..1 混合强度比例（0 = 原图，1 = 满强度） */
    val blend: Float = 0.6f,
    /**
     * 光晕颜色（ARGB Long；**白 = 不改色**，跟随光棒自身的颜色）。
     *
     * 2026-10-28 用户口径两步走：先删掉「预设 A/B + 两端色带」（那是给整片改色），
     * 再允许**用色盘自选单色**——所以这里只有一个颜色、没有 A/B 两端：
     * 白（出厂）⇒ 输出 = 原色 × 亮度（同色光晕）；选了颜色 ⇒ 原色 × 所选色（整体偏这个色调）。
     * 色盘上点圆心就是白（饱和度 0），等于回到"跟随原色"。
     */
    val color: Long = GLOW_COLOR_DEFAULT
) {

    /** 域钳之后的同一份参数（幂等）。落盘/下发前必须过这一关，见类注释 */
    fun sanitize(): GlowParams = copy(
        threshold = glowClamp(threshold, GLOW_THRESHOLD_MIN, GLOW_THRESHOLD_MAX, 0.5f),
        soft = glowClamp(soft, 0f, GLOW_SOFT_MAX, 0.05f),
        width = glowClamp(width, 0f, GLOW_WIDTH_MAX, 0.02f),
        gain = glowClamp(gain, 0f, GLOW_GAIN_MAX, 1f),
        add = glowClamp(add, 0f, GLOW_ADD_MAX, 0f),
        freq = glowClamp(freq, 0f, GLOW_FREQ_MAX, 0f),
        blend = glowClamp(blend, 0f, 1f, 0.6f),
        color = color and GLOW_ARGB_MASK
    )

    companion object {
        /**
         * 出厂默认：**白 = 不改色**（同色光晕）。2026-10-28 用户口径两步走：
         * ① 撤掉「预设 A/B + 两端色带」（那是给整片换色）；② 开放**色盘自选单色**。
         * 白是中性元 ⇒ 默认观感仍是"光棒什么色就发什么色的光晕"，只有用户显式选色才偏色。
         */
        val DEFAULT = GlowParams()
    }
}

/** 32 位 ARGB 掩码：颜色是 8 位四通道，超出部分是脏数据 */
private const val GLOW_ARGB_MASK = 0xFFFF_FFFFL

/** 出厂光晕色：白 = 不改色（跟随光棒自身颜色） */
const val GLOW_COLOR_DEFAULT = 0xFFFF_FFFFL

/**
 * ARGB Long → 0..1 三元组（uniform 用）。只取 RGB：alpha 不参与光晕（输出恒不透明，
 * 与 `PASS_THROUGH_FS` 同一条"强制不透明"纪律）。
 */
fun glowRgb(argb: Long): FloatArray {
    val v = argb and GLOW_ARGB_MASK
    return floatArrayOf(
        ((v shr 16) and 0xFF).toFloat() / 255f,
        ((v shr 8) and 0xFF).toFloat() / 255f,
        (v and 0xFF).toFloat() / 255f
    )
}

/**
 * 色盘几何（纯函数，JVM 全测）：圆盘 = 色相角 + 饱和度半径，明度固定 1（光晕是加法层，
 * 亮度由「增益」滑杆管；色盘只负责色相与饱和度）。
 *
 * - [glowHueSatOf]：像素圆心 → 色相（0..1）与饱和度（0..1，圆心 0 = 白）
 * - [glowArgbFromWheel]：角度/半径 + 明度 → ARGB；圆心即白（= 跟随原色）
 * - [glowWheelMarkerOf]：参数色 → 色盘上的落点（画选中圈用；灰/白无彩 ⇒ 圆心）
 */
fun glowHueSatOf(dx: Float, dy: Float, radiusPx: Float): FloatArray {
    if (radiusPx <= 0f) return floatArrayOf(0f, 0f)
    val dist = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
    val sat = (dist / radiusPx).coerceIn(0f, 1f)
    // atan2 的 0° 在 +x，色盘把红放 0°（顺时针与屏幕坐标一致：+y 向下 = 加角度）
    val deg = Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()).toFloat()
    val hue = (((deg % 360f) + 360f) % 360f) / 360f
    return floatArrayOf(hue, sat)
}

/** HSV（各 0..1）→ ARGB Long；色相归一化到 0..1，饱和度/明度越界钳制 */
fun glowArgbFromHsv(h: Float, s: Float, v: Float): Long {
    val hh = (((h % 1f) + 1f) % 1f) * 6f
    val ss = s.coerceIn(0f, 1f)
    val vv = v.coerceIn(0f, 1f)
    val i = hh.toInt()
    val f = hh - i
    val p = vv * (1f - ss)
    val q = vv * (1f - ss * f)
    val t = vv * (1f - ss * (1f - f))
    val (r, g, b) = when (i % 6) {
        0 -> Triple(vv, t, p)
        1 -> Triple(q, vv, p)
        2 -> Triple(p, vv, t)
        3 -> Triple(p, q, vv)
        4 -> Triple(t, p, vv)
        else -> Triple(vv, p, q)
    }
    fun ch(x: Float): Long = (x.coerceIn(0f, 1f) * 255f + 0.5f).toLong()
    return (0xFFL shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
}

/** 色盘拖拽点 → ARGB（明度恒 1）：[glowHueSatOf] 之后直接取色 */
fun glowArgbFromWheel(dx: Float, dy: Float, radiusPx: Float): Long {
    val hs = glowHueSatOf(dx, dy, radiusPx)
    return glowArgbFromHsv(hs[0], hs[1], 1f)
}

/** 参数色 → 色盘落点（单位圆内的 x/y 与饱和度）：给 UI 画选中圈；白/灰没有色相 ⇒ 落在圆心 */
fun glowWheelMarkerOf(argb: Long): FloatArray {
    val rgb = glowRgb(argb)
    val max = kotlin.math.max(rgb[0], kotlin.math.max(rgb[1], rgb[2]))
    val min = kotlin.math.min(rgb[0], kotlin.math.min(rgb[1], rgb[2]))
    val delta = max - min
    if (delta <= 1e-4f || max <= 1e-4f) return floatArrayOf(0f, 0f, 0f)
    val sat = delta / max
    val hue = when (max) {
        rgb[0] -> ((rgb[1] - rgb[2]) / delta).let { if (it < 0f) it + 6f else it }
        rgb[1] -> (rgb[2] - rgb[0]) / delta + 2f
        else -> (rgb[0] - rgb[1]) / delta + 4f
    } / 6f
    val ang = hue * 2f * Math.PI.toFloat()
    return floatArrayOf((kotlin.math.cos(ang) * sat), (kotlin.math.sin(ang) * sat), sat)
}

// ---------------------------------------------------------------------------------------------
// 安全域（sanitize 与 UI 滑杆共用同一套端点，别在 UI 里再写一份）
// ---------------------------------------------------------------------------------------------

const val GLOW_THRESHOLD_MIN = 0f
const val GLOW_THRESHOLD_MAX = 1f
const val GLOW_SOFT_MAX = 0.5f
const val GLOW_WIDTH_MAX = 0.2f
const val GLOW_GAIN_MAX = 4f
const val GLOW_ADD_MAX = 1f
const val GLOW_FREQ_MAX = 64f

/** 全曲线负载阈值（像素 × 帧率）：≤ 40M 用 scale=1（24 §1.4，初版拍定、T3 真机回填） */
const val GLOW_FULL_MAX_PIXEL_RATE = 40_000_000L

private fun glowClamp(value: Float, lo: Float, hi: Float, fallback: Float): Float =
    if (!value.isFinite()) fallback else value.coerceIn(lo, hi)

/**
 * Glow 参数 → 单串持久化键（B7 用；与 `hud_layout` 的复合键同族）。
 *
 * 格式：`key=value` 用 `;` 连接，键序固定（便于人读与 diff）。浮点用 `Float.toString()`，
 * 它保证 `toFloat()` 往返精确；颜色用 8 位大写十六进制（ARGB）。
 * 写出去之前先 [GlowParams.sanitize]，落盘值永远在安全域内。
 */
fun encodeGlow(params: GlowParams): String {
    val p = params.sanitize()
    return buildString(160) {
        append("enabled=").append(if (p.enabled) 1 else 0)
        append(";threshold=").append(p.threshold)
        append(";soft=").append(p.soft)
        append(";width=").append(p.width)
        append(";gain=").append(p.gain)
        append(";add=").append(p.add)
        append(";freq=").append(p.freq)
        append(";blend=").append(p.blend)
        append(";color=").append(p.color.toString(16).uppercase())
    }
}

/**
 * 单串持久化键 → [GlowParams]。**任何一处不认识/解析失败都整串回 [GlowParams.DEFAULT]**：
 * 光晕是"能改画面"的参数，坏值宁可回出厂，也不许猜一个近似值。
 *
 * 未知键忽略（前向兼容：以后加参数时老版本读新串不炸）；缺键、非数、颜色不是十六进制都算坏串。
 */
fun decodeGlow(raw: String?): GlowParams {
    if (raw.isNullOrBlank()) return GlowParams.DEFAULT
    val values = HashMap<String, String>(16)
    for (pair in raw.split(';')) {
        val eq = pair.indexOf('=')
        if (eq <= 0) continue
        values[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim()
    }
    fun floatOf(key: String): Float? = values[key]?.toFloatOrNull()
    fun colorOf(key: String): Long? = values[key]?.toLongOrNull(16)?.and(GLOW_ARGB_MASK)

    val enabled = when (values["enabled"]) {
        "1" -> true
        "0" -> false
        else -> return GlowParams.DEFAULT
    }
    return GlowParams(
        enabled = enabled,
        threshold = floatOf("threshold") ?: return GlowParams.DEFAULT,
        soft = floatOf("soft") ?: return GlowParams.DEFAULT,
        width = floatOf("width") ?: return GlowParams.DEFAULT,
        gain = floatOf("gain") ?: return GlowParams.DEFAULT,
        add = floatOf("add") ?: return GlowParams.DEFAULT,
        freq = floatOf("freq") ?: return GlowParams.DEFAULT,
        blend = floatOf("blend") ?: return GlowParams.DEFAULT,
        // color 是 2026-10-28 新增的**可选**键：缺键（旧串）= 白（跟随原色）；
        // 键在但解析失败 ⇒ 整串回默认（坏色值不许猜）
        color = if (values.containsKey("color")) {
            colorOf("color") ?: return GlowParams.DEFAULT
        } else GLOW_COLOR_DEFAULT
    ).sanitize()
}

/**
 * Glow 离屏链的分档：`像素 × 帧率` 越大档越粗（1 = 原尺寸链 / 2 = 半尺寸 / 4 = 四分之一）。
 *
 * 与 [denoiseKernelLite] 同一套判据与同一个魔数（[DENOISE_FULL_KERNEL_PIXEL_RATE] = 150M），
 * 不造第二个阈值：两处都是"每帧逐像素跑满全分辨率"的 pass，负载口径相同。
 * 按**相机帧**（= 录制档）判，不按上屏档判（与 `denoiseLevelNow` 同一条理由：成片那一路才是负载主体）。
 *
 * 信息不全（尺寸/帧率未知）⇒ 1（最高质量档）：此时链本来就不会跑，别先猜一个粗档让首帧闪一下。
 */
fun glowScaleFor(widthPx: Int, heightPx: Int, fps: Int): Int {
    if (widthPx <= 0 || heightPx <= 0 || fps <= 0) return 1
    val rate = widthPx.toLong() * heightPx.toLong() * fps.toLong()
    return when {
        rate <= GLOW_FULL_MAX_PIXEL_RATE -> 1
        rate <= DENOISE_FULL_KERNEL_PIXEL_RATE -> 2
        else -> 4
    }
}

/**
 * 相机帧尺寸 + 档 → 离屏 RT 尺寸，写进 `out[0]=宽 out[1]=高`（热点路径，不返回 Pair）。
 *
 * 三条规则：
 * 1. 尺寸/档/`maxTexture` 非法（≤0）⇒ 返回 false = **不建链**（绝不硬给 1×1 去蒙）；
 * 2. 否则按 `scale` 等比缩小、四舍五入，两边最小 1（0 边长的 RT 必然 FBO 不完整）；
 * 3. 最长边超过 `maxTexture`（= 运行时查到的 `GL_MAX_TEXTURE_SIZE`）⇒ **按最长边等比夹取**。
 *    **不许逐维独立夹**：两维各夹一次会把 4096×2160 夹成 4096×2048 以外的比例，光晕在屏幕上变成
 *    椭圆——这正是真机上"横向看是扁的、竖向看是长的"那一类缺陷。
 *
 * 返回值只是"尺寸可用与否"；**"到底有没有被夹"必须用夹取前的 [glowRawLongestEdge] 判**——
 * 拿夹取后的 `max(out[0], out[1]) == maxTexture` 判会把"本来就正好等于上限"误报成夹取。
 */
fun glowRtSizeInto(out: IntArray, widthPx: Int, heightPx: Int, scale: Int, maxTexture: Int): Boolean {
    if (out.size < 2) throw IllegalArgumentException("out 至少两个 int，实际 ${out.size}")
    if (widthPx <= 0 || heightPx <= 0 || scale <= 0 || maxTexture <= 0) return false
    var w = glowScaledEdge(widthPx, scale)
    var h = glowScaledEdge(heightPx, scale)
    val longest = max(w, h)
    if (longest > maxTexture) {
        val k = maxTexture.toDouble() / longest
        w = max(1, (w * k).roundToInt())
        h = max(1, (h * k).roundToInt())
    }
    out[0] = w
    out[1] = h
    return true
}

/**
 * **夹取前**的最长边（= [glowRtSizeInto] 除以 `scale`、四舍五入、最小 1 之后的最长边）。
 *
 * 它是"这次到底有没有被 `GL_MAX_TEXTURE_SIZE` 夹"的唯一判据：夹取后最长边恒等于上限，
 * 所以**不能**拿夹取后的尺寸反推（4096×2160 在 4096 上限下压根没夹，却会被误报）。
 * 非法输入（尺寸/档 ≤0）返回 0（调用方据此判"没有夹"）。
 */
fun glowRawLongestEdge(widthPx: Int, heightPx: Int, scale: Int): Int {
    if (widthPx <= 0 || heightPx <= 0 || scale <= 0) return 0
    return max(glowScaledEdge(widthPx, scale), glowScaledEdge(heightPx, scale))
}

/** 单边按档缩：四舍五入、最小 1（与 [glowRtSizeInto]、[glowRawLongestEdge] 同源，别在别处再写一份） */
private fun glowScaledEdge(px: Int, scale: Int): Int = max(1, (px.toDouble() / scale).roundToInt())

/**
 * 可分离高斯某一趟的取样位移（写入调用方复用的数组 ⇒ 每帧零分配）。
 *
 * 口径（24 §1.2）：`uGlowWidth` 以 **RT 短边** 为单位，长边那一维按比例缩小 ⇒
 * `offset = width * vec2(min(1, rtH/rtW), min(1, rtW/rtH))`，横/纵趟各取一个分量。
 * 于是"横向 20px、纵向 20px"恒成立（各向同性，圆光晕），换档/换朝向都不改观感比例。
 *
 * 尺寸非法（≤0）或参数非有限/为负 ⇒ 不写数组并返回 false：调用方据此跳过本趟，
 * 免得拿 (0,0) 位移糊出一张不糊的图。
 */
fun fillGlowBlurOffset(
    out: FloatArray,
    rtWidthPx: Int,
    rtHeightPx: Int,
    widthParam: Float,
    horizontal: Boolean
): Boolean {
    if (out.size < 2 || rtWidthPx <= 0 || rtHeightPx <= 0) return false
    if (!widthParam.isFinite() || widthParam < 0f) return false
    val sx = min(1f, rtHeightPx.toFloat() / rtWidthPx)
    val sy = min(1f, rtWidthPx.toFloat() / rtHeightPx)
    if (horizontal) {
        out[0] = widthParam * sx
        out[1] = 0f
    } else {
        out[0] = 0f
        out[1] = widthParam * sy
    }
    return true
}

/**
 * Glow 胶囊状态行该显示哪一句（纯函数，JVM 全测）：返回 `R.string.cam_glow_*` 的资源 id，
 * `null` = 这帧什么都不显示。
 *
 * 顺序是**语义优先级**，不是随意的：
 * 1. **DIRECT 先说清楚**：没有 GL 管线，后面所有状态都无意义（"需切到使用 GPU 才生效"）；
 * 2. **关着 ⇒ 什么都不显示**（含停用/夹取）：效果没开时这些残留状态都是噪声；
 * 3. 停用 ⇒ "Glow 已停用"（链坏了，开了也没有光晕——必须让用户知道）；
 * 4. 被机型上限夹过**且 RT 真的建了** ⇒ "已夹到 N×M"（rtW/rtH 为 0 时不能报一个假尺寸）；
 * 5. 否则报分档：已知输入尺寸报"当前：×N（W×H 自动）"，未知报"当前：×N（自动）"。
 *
 * @param status 引擎快照（null = 引擎还没建好/已释放 ⇒ 没有可报的分档，见上面第 2/5 条）
 * @param enabled 用户开关
 * @param gpuMode 是否 GPU 渲染（DIRECT 恒 false）
 */
fun glowStatusNote(status: GlowStatus?, enabled: Boolean, gpuMode: Boolean): Int? = when {
    !gpuMode -> R.string.cam_glow_direct
    !enabled -> null
    status == null -> null
    status.broken -> R.string.cam_glow_broken
    status.clamped && status.rtWidthPx > 0 && status.rtHeightPx > 0 -> R.string.cam_glow_clamped
    status.inputWidthPx > 0 && status.inputHeightPx > 0 -> R.string.cam_glow_status_res_label
    else -> R.string.cam_glow_status_auto
}

/**
 * Glow 的运行期状态快照（B6b 填、B7 的胶囊读数用）。纯数据，无 GL 依赖。
 *
 * @param available 链这一帧是否产出了可采样的 glow 纹理（false 时主 pass 绑 1×1 黑纹理，贡献为 0）
 * @param enabled 用户开关（`GlowParams.enabled`）
 * @param broken 链连续报错/链接失败已停用（胶囊该显示"Glow 已停用"）
 * @param scale 本帧分档（[glowScaleFor]）
 * @param inputWidthPx inputHeightPx 判档用的输入尺寸（= 相机帧 = 录制档）
 * @param fps 判档用的帧率
 * @param rtWidthPx rtHeightPx 链 RT 实际尺寸（0 = 还没建）
 * @param clamped 最长边是否被 `GL_MAX_TEXTURE_SIZE` 夹取（真机排障要看得见）
 */
data class GlowStatus(
    val available: Boolean,
    val enabled: Boolean,
    val broken: Boolean,
    val scale: Int,
    val inputWidthPx: Int,
    val inputHeightPx: Int,
    val fps: Int,
    val rtWidthPx: Int,
    val rtHeightPx: Int,
    val clamped: Boolean
)
