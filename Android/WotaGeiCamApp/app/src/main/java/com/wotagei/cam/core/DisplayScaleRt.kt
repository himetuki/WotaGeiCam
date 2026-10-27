package com.wotagei.cam.core

/**
 * 上屏离屏 RT 的**尺寸纯函数**（docs/plan/24 R3 §2.2/§2.3，25 §11.5）：
 * 目标 = 等比区 / divisor（floor），两维都 ≥ [MIN_DISPLAY_RT_EDGE] 才可用。
 *
 * # 为什么阈值是 128px
 * 上屏 RT 是"先缩后放大"的中间缓冲：太小会把曲线/降噪的判读细节与监看叠加（斑马条纹、峰值描边）
 * 一起糊没，档位之间的观感差异还会跳到"马赛克"那一侧。128px 是 R1 手算档留下的经验下限
 * （`docs/plan/24` §2.2），不足的档在 UI 上灰显并给说明——**不静默改成更小的尺寸**。
 *
 * # 为什么非法输入一律 false
 * 这一支被三处共用（每帧的引擎、UI 的可用性判定、冷启/窗口变化后的钳位）。返回 `false` =
 * "这一档此刻不可用"，调用方拿它做**同一个决定**：UI 灰显、引擎回原片路径。给一个"1×1 兜底"
 * 会让三条路各自猜一个值，最终读数与实际静默分叉（24 §〇.1-4 禁止）。
 */
const val MIN_DISPLAY_RT_EDGE = 128

/**
 * 等比区尺寸 + 档 → 上屏 RT 尺寸，写进 `out[0]=宽 out[1]=高`（热点路径，不返回 Pair）。
 *
 * @return false = 这一档不可用（尺寸/除数非法，或任一维 < 128）；此时**不写** `out`
 */
fun resolveDisplayRtInto(out: IntArray, contentWidthPx: Int, contentHeightPx: Int, divisor: Int): Boolean {
    if (out.size < 2) throw IllegalArgumentException("out 至少两个 int，实际 ${out.size}")
    if (contentWidthPx <= 0 || contentHeightPx <= 0 || divisor <= 0) return false
    // floor：上屏 RT 只许比目标小（多出来的部分由放大 blit 补齐；ceil 会白占显存）
    val w = contentWidthPx / divisor
    val h = contentHeightPx / divisor
    if (w < MIN_DISPLAY_RT_EDGE || h < MIN_DISPLAY_RT_EDGE) return false
    out[0] = w
    out[1] = h
    return true
}

/**
 * [resolveDisplayRtInto] 的 Pair 版（UI 用：只在选择/状态行时调一次，不在帧路径上）。
 * null = 该档不可用。
 */
fun resolveDisplayRtOrNull(contentWidthPx: Int, contentHeightPx: Int, divisor: Int): Pair<Int, Int>? {
    if (contentWidthPx <= 0 || contentHeightPx <= 0 || divisor <= 0) return null
    val w = contentWidthPx / divisor
    val h = contentHeightPx / divisor
    if (w < MIN_DISPLAY_RT_EDGE || h < MIN_DISPLAY_RT_EDGE) return null
    return w to h
}

/**
 * 回落时该落到哪一档（docs/plan/24 R3 §2.3）：从 [current] 的**下一档（除数减半）**开始往下找
 * 第一个尺寸可用的档，[DisplayScale.ORIG] 永远兜底。
 *
 * # 为什么从"下一档"而不是 current 自己开始
 * 这个函数只在**回落**时被调用（current 刚刚失败）。若把 current 也算进来，当失败原因不是
 * "等比区太小"（超纹理上限 / FBO 不完整 / blit program 编不出）时，尺寸判定会说 current 可用
 * ⇒ 返回 current ⇒ UI 写回同一个值 ⇒ 下一帧再失败再回落：**无限循环**。除数减半保证每一轮都
 * 真的往"更少降档"的方向走一步（16→8→4→2→ORIG）。
 *
 * @param current 回落前用户选的档（`ORIG` 时直接返回 `ORIG`：已经到底了）
 * @param contentWidthPx contentHeightPx 等比区实测尺寸；≤0（还没量到）⇒ 直接 `ORIG`
 */
fun nearestDisplayScale(
    contentWidthPx: Int,
    contentHeightPx: Int,
    current: DisplayScale
): DisplayScale {
    if (contentWidthPx <= 0 || contentHeightPx <= 0) return DisplayScale.ORIG
    // 只从"比 current 更轻"的档里找：除数是 2 的幂，减半必然落在合法档位上
    var divisor = current.divisor / 2
    while (divisor > DisplayScale.ORIG.divisor) {
        if (resolveDisplayRtOrNull(contentWidthPx, contentHeightPx, divisor) != null) {
            return DisplayScale.fromDivisor(divisor)
        }
        divisor /= 2
    }
    return DisplayScale.ORIG
}

/**
 * 胶囊下方读数（24 R3 钉子 3）的档位文案：`原片` 用本地化标签，其余用除数字面量。
 *
 * 抽成纯函数是为了让「读数 = 档位标签」这条口径能被 JVM 单测钉住——UI 侧只负责套
 * `cam_display_scale_readout` 的 `预览：%1$s`，不再各写一份 when。
 */
fun displayScaleReadoutLabel(scale: DisplayScale, origLabel: String): String =
    if (scale == DisplayScale.ORIG) origLabel else scale.divisor.toString()

/**
 * 要不要重建上屏 RT（`DisplayPostPass.begin` 每帧问一句）。
 *
 * 判据只看尺寸（= 等比区或档位变了），**与开关无关**：同一档下窗口尺寸不变就不重建。
 * 目标非法（≤0）时返回 false：此时 `begin` 由 [resolveDisplayRtInto] 的 false 那一侧直接跳过，
 * 不该在这里顺手把已建好的资源拆掉（拆了下一帧又要建 = 真抖动）。
 */
fun displayRtNeedsRebuild(
    currentWidthPx: Int,
    currentHeightPx: Int,
    targetWidthPx: Int,
    targetHeightPx: Int
): Boolean {
    if (targetWidthPx <= 0 || targetHeightPx <= 0) return false
    return currentWidthPx != targetWidthPx || currentHeightPx != targetHeightPx
}
