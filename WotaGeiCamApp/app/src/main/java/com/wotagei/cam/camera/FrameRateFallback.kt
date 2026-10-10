package com.wotagei.cam.camera

import com.wotagei.cam.core.RangeI

/**
 * fps 退档口径（纯函数，无 Android 依赖，可在 JVM 直接单测）。
 *
 * # 为什么拆出来（2026-10-10 S3-S3-01）
 * 原口径内联在 [Camera2Engine.ceilingFps] 私有方法里，用 `usable > r.lo` 挑范围。对**固定档**
 * `[f,f]` 这个条件恒为 `f > f == false`，于是所有固定档全被排除；LEGACY 设备按契约只报固定档，
 * 「可达上限」必然退到兜底分支 `minOfOrNull { it.hi }`——那是全表**最小**的上界，与函数名/注释
 * 宣称的「最高」相反。用户选 120fps、设备其实能到 60，会被退到 15fps（成片帧率与所选差 8 倍）。
 *
 * 拆出来的另一个理由同族：口径是纯算术，却因为住在持有 `CameraAbility` 的引擎里而**零测试覆盖**，
 * 改动只能靠真机取证发现（参见 `docs/scan/S3-会话入口与接线.md` S3-01）。
 */
object FrameRateFallback {

    /**
     * 设备在 [cap] 封顶下**可达的最高帧率**。
     *
     * 口径：**只排除被 cap 截断后不可用的范围**。范围 `[lo,hi]` 截断后可用 ⟺ `min(hi,cap) >= lo`
     * （截断后仍能落在 `[lo,hi]` 里）；固定档 `[f,f]` 在 `f <= cap` 时自然成立，不再被误杀。
     * 返回所有可用范围「截断后上界」的最大值。
     *
     * @param cap 无高帧率能力时的上限（`WotaTiers.HIGH_SPEED_FPS`）；有高帧率能力传 [Int.MAX_VALUE]
     * @return 可达上限；**一个可用范围都没有时返回 0**——含义是「无退档目标」，
     *         调用方必须保持总线原值而不是随便退到某个数（旧口径的兜底正是绕开 cap 写回
     *         一个设备给不出的帧率）。
     */
    fun ceilingFps(ranges: List<RangeI>, cap: Int): Int {
        var best = 0
        for (r in ranges) {
            val usable = minOf(r.hi, cap)
            if (usable >= r.lo && usable > best) best = usable
        }
        return best
    }
}