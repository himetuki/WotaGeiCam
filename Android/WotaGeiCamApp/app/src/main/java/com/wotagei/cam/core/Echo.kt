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
    /** TRAIL 模式的高光阈值 0.6..1.0（与 Glow 阈值同一域：低于它整个背景都会拖影） */
    val threshold: Float = 0.9f,
    /** TRAIL 模式的拖尾强度 0..2（主 pass 加法叠回的倍数） */
    val gain: Float = 1.0f
) {

    /** 域钳之后的同一份参数（幂等）。落盘/下发前必须过这一关，见 [GlowParams.sanitize] 同一条纪律 */
    fun sanitize(): EchoParams = copy(
        multiplier = if (multiplier in ECHO_MULTIPLIER_MIN..ECHO_MULTIPLIER_MAX) multiplier else 2,
        threshold = echoClamp(threshold, ECHO_THRESHOLD_MIN, ECHO_THRESHOLD_MAX, 0.9f),
        gain = echoClamp(gain, 0f, ECHO_GAIN_MAX, 1f)
    )

    companion object {
        val DEFAULT = EchoParams()
    }
}

/** 合成模式：max（保持亮度）/ 加法高光（提亮拖尾）。UI 文案见 cam_echo_mode_* */
enum class EchoMode { KEEP_BRIGHTNESS, TRAIL }

/** 倍数域：2 = 拍屏 1 次（2 倍光弧，推荐）；10 = 封顶（B 站 100× 翻车经验，R2 §四.3） */
const val ECHO_MULTIPLIER_MIN = 2
const val ECHO_MULTIPLIER_MAX = 10

/** TRAIL 阈值域：与 Glow 阈值共用同一套端点（60..100%），别在 UI 里再写一份 */
const val ECHO_THRESHOLD_MIN = 0.6f
const val ECHO_THRESHOLD_MAX = 1f

/** TRAIL 强度上限：2 倍封顶（加法叠回，再大就整段过曝） */
const val ECHO_GAIN_MAX = 2f

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
 * Echo 参数 → 单串持久化键（与 [encodeGlow] 同族：`key=value;` 串，键序固定，坏串整份回默认）。
 */
fun encodeEcho(params: EchoParams): String {
    val p = params.sanitize()
    return buildString(64) {
        append("enabled=").append(if (p.enabled) 1 else 0)
        append(";mode=").append(p.mode.name)
        append(";multiplier=").append(p.multiplier)
        append(";threshold=").append(p.threshold)
        append(";gain=").append(p.gain)
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
    return EchoParams(enabled, mode, multiplier, threshold, gain).sanitize()
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
