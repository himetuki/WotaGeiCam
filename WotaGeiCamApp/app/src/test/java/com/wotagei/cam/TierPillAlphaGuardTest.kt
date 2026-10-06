package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `TierPill` 灰化 alpha 的**修饰符顺序**守卫（与 `ComparePlaybackGuardTest` 的「黑层 alpha 必须在
 * background 之前」同族，防同一类 modifier 顺序缺陷复现）。
 *
 * 事故形态：`TierItem.supported=false` 的档位要"看得出灰着"（口径见 `ui/design/PillPopup.kt` 的
 * `PillChoices` 注）。灰化走 `Modifier.alpha(tierAlpha)`——modifier 链**左为外、右为内**，`alpha`
 * 只作用在它右边的绘制上；一旦把它排到 `wotaCard(...)`/`background(...)` 这些绘制类之后，就只淡了
 * 文字、整枚控件并没灰化，正是 CompareScreen 双黑同一类顺序缺陷。
 *
 * 当前没有任何 `TierPicker` 调用点传 `supported=false`（灰化是死路，见 `TierItem` KDoc），所以这条
 * 顺序修正目前是"零像素变化"；守卫的作用是在将来真接线时不让顺序退回错误形态。全部判定作用在
 * `codeOnly` 遮蔽后的文本上——注释里提到这些 token 不算接线。
 */
class TierPillAlphaGuardTest {

    /** TierPill 体内 `alpha(` 是否排在绘制类 `background(` 之前（flatten 后比偏移，不受缩进/换行影响） */
    private fun alphaBeforeBackground(raw: String): Boolean {
        val body = KotlinSourceScan.flatten(
            KotlinSourceScan.bodyOf(KotlinSourceScan.codeOnly(raw), "TierPill")
        )
        val iAlpha = body.indexOf(".alpha(")
        val iBg = body.indexOf(".background(")
        return iAlpha >= 0 && iBg > iAlpha
    }

    @Test
    fun `TierPill灰化alpha必须盖住整枚控件`() {
        val raw = KotlinSourceScan.mainSourceText("ui/widget/ParamSlider.kt")
        assertTrue(
            "TierPill 的 .alpha(tierAlpha) 必须排在 .background(tierFill) 之前：链左为外右为内，" +
                "alpha 排在绘制类之后只淡文字、整档并没灰化（与 CompareScreen 双黑同族）",
            alphaBeforeBackground(raw)
        )
    }

    @Test
    fun `尺子自己能红`() {
        val raw = KotlinSourceScan.mainSourceText("ui/widget/ParamSlider.kt")
        assertTrue("良品必须先真的绿，否则下面的红没有意义", alphaBeforeBackground(raw))
        // 把顺序改回「background 在前」的错误形态 ⇒ 必红（这正是本轮修掉的那条）
        val mutated = raw
            .replace(".alpha(tierAlpha)", "@@TMP@@")
            .replace(".background(tierFill)", ".alpha(tierAlpha)")
            .replace("@@TMP@@", ".background(tierFill)")
        assertFalse(
            "顺序退回 background 在前时守卫必须变红，否则这条守卫没有鉴别力",
            alphaBeforeBackground(mutated)
        )
    }
}
