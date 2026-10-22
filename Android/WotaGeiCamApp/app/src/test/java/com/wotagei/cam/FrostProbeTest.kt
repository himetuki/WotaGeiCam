package com.wotagei.cam

import com.wotagei.cam.ui.frostProbeEnabledOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #84 霜探针的纯函数层（[frostProbeEnabledOf]，"release 不可达"那道闸门）。
 *
 * 两条都不是恒等式：
 * - 把 `enabled &&` 那半边删掉（探针在正式 release 里也能被 adb 点亮），[gateKeepsReleaseBlind] 立刻红；
 * - 把 `&& raw` 那半边删掉（变体开着就恒亮，多余的叠层会污染 A/B 像素对照），
 *   [rawFalseStaysOff] 立刻红。
 *
 * **测不到**的东西照实写清楚（本文件一条都不冒充）：
 * `BuildConfig.FROST_PROBE` 在四个变体里各是什么值（构建侧证据，用生成的 BuildConfig 核）；
 * extra 有没有真的被 MainActivity 收进 [com.wotagei.cam.ui.FrostProbe]；
 * 叠层画出来之后数字对不对（只有真机能证——这正是探针存在的目的）。
 */
class FrostProbeTest {

    @Test
    fun gateKeepsReleaseBlind() {
        // 闸门关着时，adb 喂 true 必须整个作废——这一条就是"正式 release 进不到探针"的本体
        assertFalse("闸门关着就不许开探针", frostProbeEnabledOf(enabled = false, raw = true))
        // 同一个 extra 在开着的变体里必须真的生效（否则上一条的"红"可能只是函数写死了 false）
        assertTrue(frostProbeEnabledOf(enabled = true, raw = true))
    }

    @Test
    fun rawFalseStaysOff() {
        // 变体开着但没带 extra（从桌面图标重进）= 关闭态：探针不亮，A/B 像素对照才不被叠层污染
        assertFalse(frostProbeEnabledOf(enabled = true, raw = false))
    }
}
