package com.wotagei.cam.core

import com.wotagei.cam.R
import kotlin.math.pow

/**
 * 残影（R2 高光拖影，docs/research/R2-高光拖影可行性.md §四.2 双模式）的**参数模型与纯函数**。
 *
 * # 它是什么：多帧回波合成，不是长曝光
 * Camera2 契约下快门不得大于帧时长（24fps 最长 1/24），想把光弧拖得比单帧快门更长，
 * 只能**在帧序列上叠加**。实现取 R2 方案 B（单张反馈累积面，省内存）：
 * `acc_t = f(cur_t, k·acc_{t−1})`，展开就是「当前帧 + 按几何衰减的历史帧」——
 * 与滑窗 N+1 帧（B 站光弧倍增器的字面实现）观感接近，但只要两张乒乓 RT。
 *
 * # 双模式（2026-11-05 并入 B 站脚本已验证语义）
 * - [EchoMode.KEEP_BRIGHTNESS]「保持整体亮度不变」：`acc = max(cur, k·acc)`——星轨叠加法，
 *   逐像素取最大 ⇒ 静态背景完全不动、只有光源画弧，天然免疫背景提亮；弧是等亮度实心长条。
 *   B 站脚本默认档、暗光演出现场的推荐默认；
 * - [EchoMode.TRAIL]「提亮拖尾」：只让高光像素（亮度过阈值）进累积面，`acc = highlight(cur) + k·acc`，
 *   主 pass 加法叠回 ⇒ 彗尾观感，背景不吃提亮。
 *
 * # 倍数 → 衰减的换算（[echoDecayOf]）
 * UI 用「光弧倍数 N」（社区语言：拍屏 N−1 次 = N 倍光弧），链上只有一个衰减系数 k：
 * 取 `k = 0.5^(1/(N−1))` ⇒ 第 N−1 帧前的回波权重恰好衰减到一半，拖尾长度 ∝ N。
 * 封顶 [ECHO_MULTIPLIER_MAX]（10）——B 站脚本放开到 100× 是实锤翻车经验（画面成糊）。
 *
 * # 它是 scene layer，与 Glow 同族
 * 两槽（DISPLAY / MOVIE）都带、进成片；`enabled` 进 [com.wotagei.cam.camera.ProgramKey]
 * 的 `echo` 一维，形状参数（模式/倍数/阈值/强度）只换 uniform 不重建 program。
 * 默认关（零代价口径：关着时片元源码逐字不变、离屏链零调用）。
 */
data class EchoParams(
    /** 开关：映射成 ProgramKey 的 `echo` 一维（换开关 = 换 program，走会话缓存） */
    val enabled: Boolean = false,
    /** 合成模式；出厂 [EchoMode.KEEP_BRIGHTNESS]（B 站脚本默认档，R2 §四.2 推荐默认） */
    val mode: EchoMode = EchoMode.KEEP_BRIGHTNESS,
    /** 光弧倍数 2..10（拍屏 N−1 次 = N 倍）；换算成衰减系数见 [echoDecayOf] */
    val multiplier: Int = 2,
    /** TRAIL 高光阈值 0.6..1.0：择/除按 RGB 峰值判亮，其余按 BT.601 亮度判亮，避免彩色被旧亮门抹掉 */
    val threshold: Float = 0.9f,
    /** TRAIL 模式的拖尾强度 0..2（主 pass 加法叠回的倍数） */
    val gain: Float = 1.0f,
    /**
     * 拖尾颜色补丁模式（docs/plan/35，2026-11-09；**只在 [EchoMode.TRAIL] 档参与**）：
     * 拖尾发白是因为过阈像素在传感器上就是白的（荧光棒发光部），四模式按需染色/滤色/选源：
     * 加 [EchoTrailColorMode.ADD]（拖尾染上所选色）/ 减 [EchoTrailColorMode.SUBTRACT]（去掉所选色）/
     * 择 [EchoTrailColorMode.ONLY]（只该色像素拖尾）/ 除 [EchoTrailColorMode.EXCEPT]（只该色以外拖尾）。
     * 出厂 [EchoTrailColorMode.OFF] = 补丁关，画面与无补丁逐字节一致。
     */
    val trailColorMode: EchoTrailColorMode = EchoTrailColorMode.OFF,
    /** 补丁色（ARGB Long，core 不引 Compose，与 [GlowParams.color] 同一口径）。加/减的目标色、择/除的色度距离目标 */
    val trailColor: Long = ECHO_TRAIL_COLOR_DEFAULT,
    /** 择/除的归一化 RGB 色距容差 0.05..0.8；白灰与彩色独立分类，不受容差放宽影响（docs/plan/35 修订） */
    val trailTol: Float = 0.3f
) {

    /** 域钳之后的同一份参数（幂等）。落盘/下发前必须过这一关，见 [GlowParams.sanitize] 同一条纪律 */
    fun sanitize(): EchoParams = copy(
        multiplier = if (multiplier in ECHO_MULTIPLIER_MIN..ECHO_MULTIPLIER_MAX) multiplier else 2,
        threshold = echoClamp(threshold, ECHO_THRESHOLD_MIN, ECHO_THRESHOLD_MAX, 0.9f),
        gain = echoClamp(gain, 0f, ECHO_GAIN_MAX, 1f),
        trailColor = trailColor and ECHO_ARGB_MASK,
        trailTol = echoClamp(trailTol, ECHO_TRAIL_TOL_MIN, ECHO_TRAIL_TOL_MAX, 0.3f)
    )

    companion object {
        val DEFAULT = EchoParams()
    }
}

/** 合成模式：max（保持亮度）/ 加法高光（提亮拖尾）。UI 文案见 cam_echo_mode_* */
enum class EchoMode { KEEP_BRIGHTNESS, TRAIL }

/**
 * 拖尾颜色补丁模式（docs/plan/35，仅 TRAIL 档参与）：
 * - [OFF] 补丁关（出厂；拖尾保持现状——过阈像素什么色就拖什么色，实际舞台上即白）
 * - [ADD] 加：拖尾染上 [EchoParams.trailColor]（合成趟 `e *= tint`，白=中性元）
 * - [SUBTRACT] 减：从拖尾中去掉 [EchoParams.trailColor]（合成趟 `e *= 1−tint`）
 * - [ONLY] 择：只有色度接近 [EchoParams.trailColor] 的像素进累积面（灯体色相选源）
 * - [EXCEPT] 除：只有色度**不**接近 [EchoParams.trailColor] 的像素进累积面（除白 = 只拖彩色灯体）
 */
enum class EchoTrailColorMode { OFF, ADD, SUBTRACT, ONLY, EXCEPT }

/** 倍数域：2 = 拍屏 1 次（2 倍光弧，推荐）；10 = 封顶（B 站 100× 翻车经验，R2 §四.3） */
const val ECHO_MULTIPLIER_MIN = 2
const val ECHO_MULTIPLIER_MAX = 10

/**
 * TRAIL 阈值域：60..100%（2026-11-08 起 Glow 阈值下限单独收到 80、残影保持 60 不变——
 * 残影拖的是高光轮廓，低阈值段仍有用，两域自此分家，别再"顺手同步"）。
 */
const val ECHO_THRESHOLD_MIN = 0.6f
const val ECHO_THRESHOLD_MAX = 1f

/** TRAIL 强度上限：2 倍封顶（加法叠回，再大就整段过曝） */
const val ECHO_GAIN_MAX = 2f

/** 32 位 ARGB 掩码：与 Glow 的 GLOW_ARGB_MASK 同一口径（颜色是 8 位四通道，超出部分是脏数据） */
const val ECHO_ARGB_MASK = 0xFFFF_FFFFL

/** 出厂拖尾补丁色：白 = 中性元（加白不变色；双击色盘回归的就是它） */
const val ECHO_TRAIL_COLOR_DEFAULT = 0xFFFF_FFFFL

/** 择/除色度容差域：0.05（严格）..0.8（宽松）；软边固定 0.1 在着色器侧 */
const val ECHO_TRAIL_TOL_MIN = 0.05f
const val ECHO_TRAIL_TOL_MAX = 0.8f

private fun echoClamp(value: Float, lo: Float, hi: Float, fallback: Float): Float =
    if (!value.isFinite()) fallback else value.coerceIn(lo, hi)

/**
 * 光弧倍数 → 反馈衰减系数（纯函数，JVM 全测）。
 *
 * `k = 0.5^(1/(N−1))`：第 N−1 帧前的回波权重恰好降到一半，拖尾的有效长度随 N 单调变长
 * （N=2 → 0.5，N=3 → ≈0.707，N=4 → ≈0.794，N=10 → ≈0.926）。域外（<2）按 2 处理：
 * 1 倍 = 没有拖影，`enabled` 关掉才是正解，不给一个「1 倍但开着」的假档。
 */
fun echoDecayOf(multiplier: Int): Float {
    val n = multiplier.coerceAtLeast(ECHO_MULTIPLIER_MIN)
    return 0.5f.pow(1f / (n - 1f))
}

/**
 * 累积趟源像素门控模式 → uniform `uEchoMask`（纯函数，三链同源，引擎每帧只做查表）：
 * 0 = 不门控（OFF/加/减 都不筛源像素），1 = 择（ONLY），2 = 除（EXCEPT）。
 * [EchoMode.KEEP_BRIGHTNESS] 档恒 0（docs/plan/35 U1/E7：补丁只活在 TRAIL 档）。
 */
fun echoTrailMaskOf(params: EchoParams): Int = when {
    params.mode != EchoMode.TRAIL -> 0
    params.trailColorMode == EchoTrailColorMode.ONLY -> 1
    params.trailColorMode == EchoTrailColorMode.EXCEPT -> 2
    else -> 0
}

/**
 * 合成趟拖尾染色模式 → uniform `uEchoTintMode`（纯函数，三链同源）：
 * 0 = 不染色（OFF/择/除），1 = 加（`e *= tint`），2 = 减（`e *= 1−tint`）。KB 档恒 0（同 [echoTrailMaskOf]）。
 */
fun echoTrailTintOf(params: EchoParams): Int = when {
    params.mode != EchoMode.TRAIL -> 0
    params.trailColorMode == EchoTrailColorMode.ADD -> 1
    params.trailColorMode == EchoTrailColorMode.SUBTRACT -> 2
    else -> 0
}

/** 补丁色 → 0..1 三元组（uniform 用；复用 [glowRgb]，同一 ARGB Long 口径） */
fun echoTrailTintRgbOf(params: EchoParams): FloatArray = glowRgb(params.trailColor)

/** 择/除色度容差（uniform 用；sanitize 后取值，域见 ECHO_TRAIL_TOL_*） */
fun echoTrailTolOf(params: EchoParams): Float = params.sanitize().trailTol

/**
 * Echo 参数 → 单串持久化键（与 [encodeGlow] 同族：`key=value;` 串，键序固定，坏串整份回默认）。
 */
fun encodeEcho(params: EchoParams): String {
    val p = params.sanitize()
    return buildString(96) {
        append("enabled=").append(if (p.enabled) 1 else 0)
        append(";mode=").append(p.mode.name)
        append(";multiplier=").append(p.multiplier)
        append(";threshold=").append(p.threshold)
        append(";gain=").append(p.gain)
        // docs/plan/35 新增的可选键（与 Glow 的 color/coloronly 同一条纪律：decode 缺键=出厂）
        append(";tcm=").append(p.trailColorMode.name)
        append(";tc=").append(p.trailColor.toString(16).uppercase())
        append(";ttol=").append(p.trailTol)
    }
}

/**
 * 单串持久化键 → [EchoParams]。与 [decodeGlow] 同一条纪律：**任何一处不认识/解析失败都整串回
 * [EchoParams.DEFAULT]**——残影是"能改画面"的参数，坏值宁可回出厂也不猜近似值。
 * 未知键忽略（前向兼容）。
 */
fun decodeEcho(raw: String?): EchoParams {
    if (raw.isNullOrBlank()) return EchoParams.DEFAULT
    val values = HashMap<String, String>(8)
    for (pair in raw.split(';')) {
        val eq = pair.indexOf('=')
        if (eq <= 0) continue
        values[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim()
    }
    val enabled = when (values["enabled"]) {
        "1" -> true
        "0" -> false
        else -> return EchoParams.DEFAULT
    }
    val mode = when (values["mode"]) {
        EchoMode.KEEP_BRIGHTNESS.name -> EchoMode.KEEP_BRIGHTNESS
        EchoMode.TRAIL.name -> EchoMode.TRAIL
        // mode 是 2026-11-05 首版就有的键：值不认识 = 坏串回默认（不猜）
        else -> return EchoParams.DEFAULT
    }
    val multiplier = values["multiplier"]?.toIntOrNull() ?: return EchoParams.DEFAULT
    val threshold = values["threshold"]?.toFloatOrNull() ?: return EchoParams.DEFAULT
    val gain = values["gain"]?.toFloatOrNull() ?: return EchoParams.DEFAULT
    // tcm/tc/ttol 是 docs/plan/35 新增的**可选**键（对照 Glow 的 color/coloronly 同一条纪律）：
    // 缺键（旧串，v0.0.12-r05 及之前写入的）= 补丁关、色白、容差出厂；键在但解析失败 ⇒ 整串回默认（坏值不许猜）
    val trailColorMode = if (values.containsKey("tcm")) {
        when (values["tcm"]) {
            EchoTrailColorMode.OFF.name -> EchoTrailColorMode.OFF
            EchoTrailColorMode.ADD.name -> EchoTrailColorMode.ADD
            EchoTrailColorMode.SUBTRACT.name -> EchoTrailColorMode.SUBTRACT
            EchoTrailColorMode.ONLY.name -> EchoTrailColorMode.ONLY
            EchoTrailColorMode.EXCEPT.name -> EchoTrailColorMode.EXCEPT
            else -> return EchoParams.DEFAULT
        }
    } else EchoTrailColorMode.OFF
    val trailColor = if (values.containsKey("tc")) {
        values["tc"]?.toLongOrNull(16)?.and(ECHO_ARGB_MASK) ?: return EchoParams.DEFAULT
    } else ECHO_TRAIL_COLOR_DEFAULT
    val trailTol = if (values.containsKey("ttol")) {
        values["ttol"]?.toFloatOrNull() ?: return EchoParams.DEFAULT
    } else 0.3f
    return EchoParams(enabled, mode, multiplier, threshold, gain, trailColorMode, trailColor, trailTol).sanitize()
}

/** Echo 的运行期状态快照（纯数据；DIRECT 恒 available=false，与 [GlowStatus] 同构但字段更少） */
data class EchoStatus(
    /** 链这一帧是否产出了可采样的累积面（false 时主 pass 绑 1×1 黑纹理 ⇒ 两模式贡献都是恒等） */
    val available: Boolean,
    val enabled: Boolean,
    /** 链连续报错/链接失败已停用（胶囊该显示"残影已停用"） */
    val broken: Boolean
)

/**
 * 残影胶囊状态行该显示哪一句（纯函数；与 [glowStatusNote] 同一条语义优先级）：
 * DIRECT 先说清楚 → 关着不显示 → 停用必可见。
 */
fun echoStatusNote(status: EchoStatus?, enabled: Boolean, gpuMode: Boolean): Int? = when {
    !gpuMode -> R.string.cam_echo_direct
    !enabled -> null
    status == null -> null
    status.broken -> R.string.cam_echo_broken
    else -> null
}
