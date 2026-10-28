package com.wotagei.cam.ui

import com.wotagei.cam.R
import com.wotagei.cam.core.FrameEffect
import com.wotagei.cam.core.RenderMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顶栏告警条的行序规则（#3 多槽化）。
 *
 * 改前 `HudTopChrome` 用 `when { … }` 取第一个命中项、只画一条：多条降级并存时优先级低的那条被整个
 * 吞掉（「高帧率降级 + 缺麦克风」并存时用户看不到「本次仅录制画面」）。改后 [warnRows] 全量返回。
 *
 * 为什么期望值**逐字写死**而不是拿一个同样顺序的构造式去比：AGENTS.md 点过名的坑——由定义直接推出
 * 的恒等式（把实现重抄一遍当期望）测不出任何退化。这里期望列表是四个字面 `R.string.*` 常量的固定
 * 排列，谁把 `warnRows` 的分支顺序改了、或某条分支被删，这行就红。
 *
 * 期望顺序（逐字 = 改前那道 `when` 的分支顺序，不做降序重排）：
 * `cam_state_high_fps` > `no_audio_record_tip` > `capability_limited_tip` > `cam_direct_no_effect`
 */
class WarnRowsTest {

    @Test
    fun `allFourActiveReturnsFourRowsInPriorityOrder`() {
        val rows = warnRows(
            deviceFailedHighFps = true,
            audioDegraded = true,
            legacy = true,
            effect = FrameEffect.ZEBRA,
            renderMode = RenderMode.DIRECT
        )
        assertEquals(
            "四条同现时必须按固定优先级整串输出（期望值是字面常量排列，不是实现的重述）",
            listOf(
                R.string.cam_state_high_fps,
                R.string.no_audio_record_tip,
                R.string.capability_limited_tip,
                R.string.cam_direct_no_effect
            ),
            rows
        )
    }

    @Test
    fun `eachSingleConditionAloneYieldsExactlyItsRow`() {
        assertEquals(
            "高帧率降级单独成立时只出高帧率那条",
            listOf(R.string.cam_state_high_fps),
            warnRows(true, false, false, FrameEffect.NONE, RenderMode.GPU)
        )
        assertEquals(
            "缺麦克风单独成立时只出缺音频那条（多槽化前它被高帧率挡掉）",
            listOf(R.string.no_audio_record_tip),
            warnRows(false, true, false, FrameEffect.NONE, RenderMode.GPU)
        )
        assertEquals(
            "LEGACY 单独成立时只出能力受限那条",
            listOf(R.string.capability_limited_tip),
            warnRows(false, false, true, FrameEffect.NONE, RenderMode.GPU)
        )
        assertEquals(
            "DIRECT 下开特效单独成立时只出特效失效那条",
            listOf(R.string.cam_direct_no_effect),
            warnRows(false, false, false, FrameEffect.ZEBRA, RenderMode.DIRECT)
        )
    }

    @Test
    fun `emptyWhenAllClear`() {
        assertEquals(
            "四条件全清必须空列表（渲染侧据此整块不占位）",
            emptyList<Int>(),
            warnRows(false, false, false, FrameEffect.NONE, RenderMode.GPU)
        )
    }

    @Test
    fun `directEffectRowOnlyInDirectMode`() {
        // 有特效但走 GPU 渲染：特效照常生效，第四条不许出现
        assertFalse(
            "effect=ZEBRA 且 renderMode=GPU 时第四条不得出现",
            warnRows(false, false, false, FrameEffect.ZEBRA, RenderMode.GPU)
                .contains(R.string.cam_direct_no_effect)
        )
        // 反面：同一条件换成 DIRECT 就必须出现（否则上一条断言会靠「根本不产出第四条」蒙混过关）
        assertTrue(
            "effect=ZEBRA 且 renderMode=DIRECT 时第四条必须出现",
            warnRows(false, false, false, FrameEffect.ZEBRA, RenderMode.DIRECT)
                .contains(R.string.cam_direct_no_effect)
        )
        // DIRECT 但无特效：同样不出第四条
        assertFalse(
            "effect=NONE 且 renderMode=DIRECT 时第四条不得出现",
            warnRows(false, false, false, FrameEffect.NONE, RenderMode.DIRECT)
                .contains(R.string.cam_direct_no_effect)
        )
    }

    @Test
    fun `相邻优先级的indexOf逐级递增`() {
        val rows = warnRows(
            deviceFailedHighFps = true,
            audioDegraded = true,
            legacy = true,
            effect = FrameEffect.PEAKING,
            renderMode = RenderMode.DIRECT
        )
        val iHigh = rows.indexOf(R.string.cam_state_high_fps)
        val iAudio = rows.indexOf(R.string.no_audio_record_tip)
        val iLegacy = rows.indexOf(R.string.capability_limited_tip)
        val iDirect = rows.indexOf(R.string.cam_direct_no_effect)
        assertTrue("四条都必须在场（否则下面的大小断言是空转）", rows.size == 4)
        assertTrue("高帧率必须排在缺麦克风之前", iHigh < iAudio)
        assertTrue("缺麦克风必须排在 LEGACY 之前", iAudio < iLegacy)
        assertTrue("LEGACY 必须排在 DIRECT 特效之前", iLegacy < iDirect)
    }

    // ---- 2026-10-28 审查加固新增两行（追加在固定行之后，不插入既有序）----

    @Test
    fun `新增两行默认关闭_旧四参调用点行为逐字不变`() {
        assertEquals(
            "缺省参调用（旧签名形态）四条件全清必须仍为空——旧行为不许被新增行扰动",
            emptyList<Int>(),
            warnRows(false, false, false, FrameEffect.NONE, RenderMode.GPU)
        )
    }

    @Test
    fun `换段预提示与近似档手动行各自独立出现`() {
        assertEquals(
            "换段预提示单独成立时只出该行",
            listOf(R.string.cam_state_seg_near_cap),
            warnRows(false, false, false, FrameEffect.NONE, RenderMode.GPU, segNearCap = true)
        )
        assertEquals(
            "近似档+手动单独成立时只出该行",
            listOf(R.string.cam_state_approx_manual),
            warnRows(false, false, false, FrameEffect.NONE, RenderMode.GPU, approxManual = true)
        )
    }

    @Test
    fun `新增行追加在固定行之后_不抢降级文案的行序`() {
        val rows = warnRows(
            deviceFailedHighFps = true,
            audioDegraded = true,
            legacy = true,
            effect = FrameEffect.ZEBRA,
            renderMode = RenderMode.DIRECT,
            segNearCap = true,
            approxManual = true
        )
        assertEquals("六条全现按固定序+追加序输出", listOf(
            R.string.cam_state_high_fps,
            R.string.no_audio_record_tip,
            R.string.capability_limited_tip,
            R.string.cam_direct_no_effect,
            R.string.cam_state_seg_near_cap,
            R.string.cam_state_approx_manual
        ), rows)
    }
}
