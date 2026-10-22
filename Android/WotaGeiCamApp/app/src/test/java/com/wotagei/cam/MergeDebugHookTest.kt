package com.wotagei.cam

import com.wotagei.cam.ui.anim.MergeHookArgs
import com.wotagei.cam.ui.anim.mergeHookArgsOf
import com.wotagei.cam.ui.anim.parseMergeHookSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #73 第 1 件（融合进度取证钩子）的纯函数层。
 *
 * 只测两条**计划→行为**的桥：[mergeHookArgsOf]（"release 不可达"那道闸门）与
 * [parseMergeHookSpec]（adb 串 → 参数）。两条都不是恒等式：
 * - 把 `if (enabled) … else DEFAULT` 的闸门删掉（改成无条件 `parseMergeHookSpec(raw)`），
 *   [hookGateKeepsReleaseBlind] 立刻红；
 * - 把夹取判断（`isIn01()` / `s <= MAX_SCALE`）删掉，[outOfRangeValuesAreDroppedNotClamped] 立刻红。
 *
 * **测不到**的东西照实写清楚（本文件一条都不冒充它们）：
 * `BuildConfig.MERGE_HOOK` 在三个变体里各是什么值（构建侧证据，用生成的 BuildConfig.java 核，见交付报告）、
 * intent extra 有没有真的被 MainActivity 收进 [com.wotagei.cam.ui.anim.MergeDebugHook]、
 * 钉住之后画面是否真的停在中间态（只有真机能证）。
 */
class MergeDebugHookTest {

    @Test
    fun hookGateKeepsReleaseBlind() {
        // 闸门关着时，喂进去的合法规格必须整个作废——这一条就是"正式 release 变体进不到钩子"的本体
        val blocked = mergeHookArgsOf(enabled = false, raw = "pin=0.5&t=20")
        assertNull("闸门关着就不许有钉值", blocked.pinned)
        assertEquals("闸门关着就不许放长时长", 1f, blocked.timeScale, 0.0001f)
        assertFalse("关着时 active 必须 false，徽标与 MotionSpec 都不该动", blocked.active)
        // 同一个串在开着的变体里必须真的生效（否则上一条的"红"可能只是解析器坏了）
        val open = mergeHookArgsOf(enabled = true, raw = "pin=0.5&t=20")
        assertEquals(0.5f, open.pinned!!, 0.0001f)
        assertEquals(20f, open.timeScale, 0.0001f)
        assertTrue(open.active)
    }

    @Test
    fun pinAndScaleAreIndependentAndBothAccepted() {
        assertEquals(0.25f, parseMergeHookSpec("pin=0.25").pinned!!, 0.0001f)
        assertEquals(1f, parseMergeHookSpec("pin=0.25").timeScale, 0.0001f)
        assertNull("只写倍率时不许顺手钉进度", parseMergeHookSpec("t=20").pinned)
        assertEquals(20f, parseMergeHookSpec("t=20").timeScale, 0.0001f)
        // 两枚一起写：取证时"钉住 + 放长"要能同时用（钉住取值、放长看连续）
        val both = parseMergeHookSpec("pin=0.9&t=20")
        assertEquals(0.9f, both.pinned!!, 0.0001f)
        assertEquals(20f, both.timeScale, 0.0001f)
        // 分隔符两种都认，键名大小写不敏感（人在敲 adb 命令，别因为 SHIFT 没放开就静默失效）
        val mixed = parseMergeHookSpec("PIN=0.75;t=10")
        assertEquals(0.75f, mixed.pinned!!, 0.0001f)
        assertEquals(10f, mixed.timeScale, 0.0001f)
    }

    /**
     * 取证要的那六档必须都能钉（0 / 0.25 / 0.5 / 0.75 / 0.9 / 1）。
     * ⚠ `pin=0` 与"没钉"是两件事：0f 是合法钉值（徽标要显示、两颗要回到原位且不收点击），
     * null 才是关。这一条钉的就是这个区别——把 `pinned` 的判定写成 `if (p > 0)` 就红了。
     */
    @Test
    fun evidenceLadderIsPinableIncludingZero() {
        for (p in listOf(0f, 0.25f, 0.5f, 0.75f, 0.9f, 1f)) {
            val args = parseMergeHookSpec("pin=$p")
            assertEquals("取证档位 $p 必须能钉住", p, args.pinned!!, 0.0001f)
            assertTrue("pin=0 也算钩子生效（否则截图时无从判断这是钉态还是自然态）", args.active)
        }
        // 没带 extra（从桌面图标重进那条路）必须是关，且 0f 不等于关
        assertFalse(mergeHookArgsOf(enabled = true, raw = null).active)
    }

    @Test
    fun outOfRangeValuesAreDroppedNotClamped() {
        // 越界不夹取而是丢掉：这是取证工具，宁可"没生效"也不要"半个值生效"（半个值会让人把错帧当正常帧）
        assertNull(parseMergeHookSpec("pin=1.4").pinned)
        assertNull(parseMergeHookSpec("pin=-0.2").pinned)
        assertNull(parseMergeHookSpec("pin=abc").pinned)
        assertEquals(1f, parseMergeHookSpec("t=0").timeScale, 0.0001f)
        assertEquals(1f, parseMergeHookSpec("t=-3").timeScale, 0.0001f)
        assertEquals(1f, parseMergeHookSpec("t=9999").timeScale, 0.0001f)
        // 整串读不懂 → 整个关（不是"半条生效"）
        val garbage = mergeHookArgsOf(enabled = true, raw = "随便写点什么")
        assertFalse(garbage.active)
        // off 是显式关闭：留着 extra 键但把钩子撤掉，不需要 force-stop
        assertFalse(mergeHookArgsOf(enabled = true, raw = "off").active)
        // 越界那一枚丢掉，另一枚照常（"pin=2&t=20" 不该把倍率也带走）
        val half = parseMergeHookSpec("pin=2&t=20")
        assertNull(half.pinned)
        assertEquals(20f, half.timeScale, 0.0001f)
    }

    /**
     * 裸写一个数当 pin 用（`--es wota_merge_hook 0.5` 省敲前缀），但只认 0..1：
     * 把这条判断删掉，`"3"` 就会被当成"钉在 3"，绘制层读到一个越界进度。
     */
    @Test
    fun bareNumberIsPinOnlyWhenInRange() {
        assertEquals(0.5f, parseMergeHookSpec("0.5").pinned!!, 0.0001f)
        assertEquals(1f, parseMergeHookSpec("1").pinned!!, 0.0001f)
        assertNull("裸数字越界就当没写，不许当倍率也不许当钉值", parseMergeHookSpec("3").pinned)
        assertEquals(1f, parseMergeHookSpec("3").timeScale, 0.0001f)
    }

    @Test
    fun defaultArgsAreInactive() {
        val off = MergeHookArgs.DEFAULT
        assertNull(off.pinned)
        assertEquals(1f, off.timeScale, 0.0001f)
        assertFalse(off.active)
        // 上限也得钉住：倍率没有上限就能把动画放到"永远跑不完"，取证时会误判成卡死
        assertEquals(50f, MergeHookArgs.MAX_SCALE, 0.0001f)
    }
}
