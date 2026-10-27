package com.wotagei.cam.camera

import com.wotagei.cam.core.DENOISE_LEVEL_FULL
import com.wotagei.cam.core.DENOISE_LEVEL_LITE
import com.wotagei.cam.core.DENOISE_LEVEL_OFF
import com.wotagei.cam.core.FrameEffect

/**
 * GL program 的**四维键**（docs/plan/25 §11.2 / §3.2，24 R3 §1.3 定为四维）：
 * `(effect, curve, denoise, glow)`。
 *
 * # 为什么恰好四维、绝不含 `slot`
 * `slot`（DISPLAY / MOVIE）是键的**作用域**，不是维度：两个槽各自持有一个四维键，
 * 键相同就必须共用同一枚 program（缓存键就是这四维）。要是把 `slot` 塞进来，
 * `effect=NONE` 时两槽各编一份，录起首帧多一次编译——直接违背 24 R3 的"零代价"前提
 * （见 `26-24与25槽位口径交接.md` §二）。
 *
 * # 各维度的归属（§11.11 的视觉层归属表）
 * - `effect`：**monitor layer**（斑马 / 峰值），只有 DISPLAY 槽可能出现 ZEBRA/PEAKING；
 * - `curve` / `glow`：**scene layer**，两槽同值、进成片；
 * - `denoise`：scene layer，两槽同值；DISPLAY 在 effect ≠ NONE 时固定 0（与改前一致）。
 *
 * 不进键的东西：上屏档（DisplayScale）、Glow 降采样档/形状参数、斑马阈值/密度、峰值强度/颜色、
 * 降噪 σ、曲线表内容、编码尺寸/帧率——它们全走 uniform / RT 尺寸 / 链参数，改它们不重建 program。
 */
internal data class ProgramKey(
    val effect: FrameEffect,
    val curve: Boolean,
    val denoise: Int,
    val glow: Boolean
) {
    /** 日志用紧凑形式（`(NONE,1,2,0)`）。**只用于日志**，不参与判等/缓存键 */
    fun short(): String = "(${effect.name},${if (curve) 1 else 0},$denoise,${if (glow) 1 else 0})"

    /** 日志用的逐维形式（`effect=NONE curve=1 denoise=2 glow=0`）。同样只用于日志 */
    fun debug(): String =
        "effect=${effect.name} curve=${if (curve) 1 else 0} denoise=$denoise glow=${if (glow) 1 else 0}"
}

/**
 * 成片支路的键：**effect 恒 NONE** —— 这是"监看叠加不入成片"的**唯一真源**，
 * 不允许别处再写一份（守卫见 `MonitorEffectEncoderGuardTest`：签名里连 effect 形参都没有）。
 *
 * `curve` / `denoise` / `glow` 照常参与——它们是 scene layer，本来就要进成片。
 */
internal fun movieProgramKey(curve: Boolean, denoise: Int, glow: Boolean): ProgramKey {
    return ProgramKey(FrameEffect.NONE, curve, denoise, glow)
}

/**
 * 上屏支路的键：effect 原样（用户选的 NONE/ZEBRA/PEAKING）；**effect ≠ NONE 时降噪强制 0**。
 *
 * 理由：斑马/峰值是判读工具，对判读工具本身降噪没有意义；`uTexel` 一颗 uniform 被峰值与降噪共用，
 * 靠这条保证两者不同时在场（与单槽时代的隐式前提逐字一致，§3.6 的不变量）。
 */
internal fun displayProgramKey(
    effect: FrameEffect,
    curve: Boolean,
    denoise: Int,
    glow: Boolean
): ProgramKey {
    return ProgramKey(effect, curve, if (effect == FrameEffect.NONE) denoise else DENOISE_LEVEL_OFF, glow)
}

/**
 * 编译回退阶梯的下一级；`null` = 已经退到直通 `(NONE,false,0,false)` 仍编不出来。
 *
 * 阶梯**单调**，顺序是 **先降 glow → 再降 denoise（2→1→0）→ 再降 effect（→NONE）→ 最后降 curve**：
 *
 * ```
 * movie   : (NONE,c,2,g) → (NONE,c,2,0) → (NONE,c,1,0) → (NONE,c,0,0)=CURVE_FS → (NONE,0,0,0)=直通 → null
 * display : (ZEBRA,c,0,g) → (ZEBRA,c,0,0) → (NONE,c,0,0)=CURVE_FS → (NONE,0,0,0)=直通 → null
 * ```
 *
 * # 为什么降噪/效果先降、曲线最后降
 * 降噪核与斑马/峰值着色器是最容易在某些驱动上链接失败的两类（多采样循环 / 片元 highp），
 * 而**曲线 LUT 是用户的调色**：编不出降噪时宁可成片丢掉降噪，也不许连带把调色一起关掉
 * （旧单槽实现的 `curveFailed` 连坐缺陷正是"开一下斑马，成片丢调色"）。把 curve 放最后，
 * 保证 movie 链必然经过 `CURVE_FS` 这一级。
 *
 * **取舍（明确登记）**：不再尝试"有效果但无曲线"的中间档（`(ZEBRA,false,0,·)`）——
 * 那本质上是用丢失调色去换监看叠加，与"成片优先"相反。判定工具（斑马/峰值）失败时宁可
 * 退回"有调色、无叠加"的上屏程序，必要时再退直通。
 *
 * # 三条性质（`ProgramKeyTest` 全钉子）
 * 1. 每一步都严格下降 ⇒ 回退链无环、步数有界；
 * 2. effect 只会降到 NONE、绝不回升 ⇒ 从 display 键离开 ZEBRA/PEAKING 后不会回到监看叠加，
 *    从 movie 键出发全链 effect 恒 NONE；
 * 3. 终点恒为直通键，再下一级就是 null。
 */
internal fun nextFallback(key: ProgramKey): ProgramKey? = when {
    key.glow -> key.copy(glow = false)
    key.denoise >= DENOISE_LEVEL_LITE -> key.copy(denoise = DENOISE_LEVEL_FULL)
    key.denoise == DENOISE_LEVEL_FULL -> key.copy(denoise = DENOISE_LEVEL_OFF)
    key.effect != FrameEffect.NONE -> key.copy(effect = FrameEffect.NONE)
    key.curve -> key.copy(curve = false)
    else -> null
}
