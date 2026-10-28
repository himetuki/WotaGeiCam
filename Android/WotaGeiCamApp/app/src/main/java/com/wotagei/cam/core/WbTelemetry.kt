package com.wotagei.cam.core

import java.util.Locale

/**
 * 手动白平衡取证遥测的**纯函数层**（docs/plan/28 §2.4 批次 1：先补取证遥测，再改模型）。
 *
 * 为什么先遥测：手动白平衡「常驻偏冷」的根因审查（28 §2.3 R1/R2）离线只能证到
 * 「绝对增益模型 + identity 矩阵与设备标定量无关」，还有 4 个未知量必须靠真机日志钉死——
 * 1. 本机 AUTO 档 HAL 回报的 `COLOR_CORRECTION_GAINS`（决定中性增益在哪，R1 是否成立）；
 * 2. `SENSOR_NEUTRAL_COLOR_POINT` 是否回报（决定 B3 相对模型的 CCT 换算路径）；
 * 3. HAL 是否真的采纳请求增益（request vs result 对比，「TRANSFORM_MATRIX 回报对」
 *    不代表增益被采纳——华为 ALN-AL00 的前车之鉴）；
 * 4. 合法增益下限是否允许 < 1.0（决定 B3 的 clamp 策略）。
 *
 * **判读表**（导出诊断件后对照，28 §2.4-4 原文）：
 * - `auto gains` 归一 G 后 R/G、B/G ≈ 1 ⇒ 本机参考白点就在 6500K 附近，R1 不成立，重点查 R2；
 * - `auto gains` 归一后明显 ≠ 1 ⇒ R1 成立，批次 2（B3）用「设备标定相对模型」；
 * - `采纳=否`（result ≠ request）⇒ HAL 覆盖增益，手动档只能做相对偏移，B3 要先评估；
 * - `neutral=无` ⇒ B3 用 gains 反解 CCT；有值 ⇒ 直接按 McCamy 近似换算。
 *
 * 全部输入是**已提取的原始类型**（FloatArray/Int），不碰 android.graphics.* ——JVM 单测可全表。
 * android 侧的读取（CaptureResult/ColorSpaceTransform → FloatArray）在引擎遥测函数里做。
 */
object WbTelemetry {

    private const val NA = "无"

    /** 增益三元组 → 日志串：null/缺元素 = 「无」，正常 = `[1.623,1.000,1.412]`（R,G,B） */
    fun formatGains(g: FloatArray?): String {
        if (g == null || g.size < 3) return NA
        return "[%.3f,%.3f,%.3f]".format(Locale.US, g[0], g[1], g[2])
    }

    /** AUTO 档判读的比值口径：R/G 与 B/G（G 归一）。G≤0 视为异常报「无」 */
    fun formatGainsRatios(g: FloatArray?): String {
        if (g == null || g.size < 3 || g[1] <= 0f) return NA
        return "r/g=%.3f b/g=%.3f".format(Locale.US, g[0] / g[1], g[2] / g[1])
    }

    /**
     * AUTO 档判读（§2.4-4 第 1 条）：归一 G 后 R/G、B/G 是否明显偏离 1。
     * 偏差容差 0.05——形状级偏差（曲线插值误差）远小于它，「中性白点在不在 6500K」的
     * 差异 typically 在 0.3 以上，不会误判。
     */
    fun autoGainsDeviateFromNeutral(g: FloatArray?, tol: Float = 0.05f): Boolean {
        if (g == null || g.size < 3 || g[1] <= 0f) return false
        return kotlin.math.abs(g[0] / g[1] - 1f) > tol || kotlin.math.abs(g[2] / g[1] - 1f) > tol
    }

    /**
     * 3×3 色彩矩阵摘要（9 元素按行展开）：取对角线 + 首行——identity 一眼可辨
     * （diag=[1.000,1.000,1.000]），HAL 标定 CCM 的量级也能读出来。
     */
    fun formatTransform9(e: FloatArray?): String {
        if (e == null || e.size < 9) return NA
        return "diag=[%.3f,%.3f,%.3f] row0=[%.3f,%.3f,%.3f]".format(
            Locale.US, e[0], e[4], e[8], e[0], e[1], e[2]
        )
    }

    /** identity 矩阵判据（守卫与判读共用：R2 的「identity 抹掉标定 CCM」就看这行） */
    fun isIdentityTransform9(e: FloatArray?): Boolean {
        if (e == null || e.size < 9) return false
        for (row in 0..2) for (col in 0..2) {
            val expect = if (row == col) 1f else 0f
            if (kotlin.math.abs(e[row * 3 + col] - expect) > 1e-4f) return false
        }
        return true
    }

    /** `SENSOR_NEUTRAL_COLOR_POINT`（3 元素）→ 日志串；缺报 = 「无」（判读表第 4 条的入口） */
    fun formatNeutralPoint(e: FloatArray?): String {
        if (e == null || e.size < 3) return NA
        return "[%.3f,%.3f,%.3f]".format(Locale.US, e[0], e[1], e[2])
    }

    /**
     * 手动档「采纳与否」判据（§2.4-2）：HAL 回报的 gains 与请求 gains 在容差内一致才算采纳。
     * result 缺报（键没给）也算未采纳——「拿不到实据」按最坏情况报告，不装作没问题。
     * 容差 0.02：HAL 内部量化（rational/定点）会带小数噪声，但不会到 0.02 量级。
     */
    fun gainsAdopted(request: FloatArray, result: FloatArray?, tol: Float = 0.02f): Boolean {
        if (result == null || result.size < 3) return false
        for (i in 0..2) if (kotlin.math.abs(request[i] - result[i]) > tol) return false
        return true
    }
}
