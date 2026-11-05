package com.wotagei.cam.camera

import com.wotagei.cam.core.DENOISE_LEVEL_FULL
import com.wotagei.cam.core.DENOISE_LEVEL_LITE
import com.wotagei.cam.core.DENOISE_LEVEL_OFF
import com.wotagei.cam.core.FrameEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ProgramKey] 五维键与 [nextFallback] 回退阶梯的**纯 JVM 性质测试**
 * （docs/plan/25 §3.2 / §5.3；2026-11-05 R2 残影加第 5 维 echo）。
 *
 * 这组性质是双槽模型的全部前提，任何一条破了都是"编译过、单测绿、真机录出斑马纹"：
 * - 键**恰好五维、不含 slot**（含 slot ⇒ 两槽各编一份，录起首帧多一次编译）；
 * - 成片键 effect **对任意入参**恒 NONE（这是"监看叠加不入成片"的唯一真源）；
 * - 上屏键只在 effect=NONE 时携带降噪（`uTexel` 一颗 uniform 被峰值与降噪共用）；
 * - effect=NONE 时两键相等（共用同一枚 program 的前提）；
 * - 回退阶梯单调、无环、终点是直通；glow 先降、echo 次之；离开 ZEBRA/PEAKING 后绝不回升。
 */
class ProgramKeyTest {

    private val denoiseLevels = listOf(DENOISE_LEVEL_OFF, DENOISE_LEVEL_FULL, DENOISE_LEVEL_LITE)

    private val booleans = listOf(false, true)

    // ------------------------------------------------------------------ 五维、无 slot

    @Test
    fun `键恰好五维且不含slot`() {
        val fields = ProgramKey::class.java.declaredFields
            .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()
        assertEquals(
            "ProgramKey 必须恰好五维 effect/curve/denoise/glow/echo —— slot 是键的作用域不是维度",
            setOf("effect", "curve", "denoise", "glow", "echo"),
            fields
        )
        assertFalse("键里不许出现 slot", fields.any { it.contains("slot", ignoreCase = true) })
    }

    @Test
    fun `movieProgramKey 只吃曲线降噪Glow残影四个形参`() {
        // 编译期钉死"不许把 effect 传进来"：这行一旦编译不过，说明签名被加了第五维入口。
        // （源码级守卫另有 MonitorEffectEncoderGuardTest 的签名断言，二者互不替代。）
        val ref: (Boolean, Int, Boolean, Boolean) -> ProgramKey = ::movieProgramKey
        assertEquals(
            "形参顺序 = (curve, denoise, glow, echo)",
            movieProgramKey(true, DENOISE_LEVEL_LITE, false, true),
            ref(true, DENOISE_LEVEL_LITE, false, true)
        )
    }

    // ------------------------------------------------------------------ 成片键 effect 恒 NONE

    @Test
    fun `成片键的effect对任意入参恒为NONE`() {
        for (e in FrameEffect.values()) {
            for (c in booleans) {
                for (d in denoiseLevels) {
                    for (g in booleans) {
                        for (h in booleans) {
                            // e 只是把"全枚举"跑一遍：成片键从不认识用户选的效果档
                            assertEquals(
                                "movieProgramKey($c,$d,$g,$h) 的 effect 必须恒 NONE（与用户选 $e 无关）",
                                FrameEffect.NONE,
                                movieProgramKey(c, d, g, h).effect
                            )
                        }
                    }
                }
            }
            // 上屏键则原样保留效果档——两条支路唯一的语义差就在这一维
            assertEquals(e, displayProgramKey(e, true, DENOISE_LEVEL_FULL, false, false).effect)
        }
    }

    // ------------------------------------------------------------------ 上屏键的降噪闸

    @Test
    fun `上屏键只在effect为NONE时携带降噪`() {
        for (e in FrameEffect.values()) {
            for (d in denoiseLevels) {
                val key = displayProgramKey(e, true, d, false, false)
                val expected = if (e == FrameEffect.NONE) d else DENOISE_LEVEL_OFF
                assertEquals("displayProgramKey($e,..,$d,..).denoise", expected, key.denoise)
            }
        }
        // 斑马/峰值一律不带降噪（含轻量档）
        for (e in listOf(FrameEffect.ZEBRA, FrameEffect.PEAKING)) {
            for (d in denoiseLevels) {
                assertEquals(DENOISE_LEVEL_OFF, displayProgramKey(e, true, d, false, false).denoise)
            }
        }
    }

    @Test
    fun `effect为NONE时两槽键完全相等`() {
        // 同键共用 program 的前提：effect=NONE 时上屏与成片必须落在同一个五维键上
        for (c in booleans) {
            for (d in denoiseLevels) {
                for (g in booleans) {
                    for (h in booleans) {
                        assertEquals(
                            "displayProgramKey(NONE,$c,$d,$g,$h) 必须等于 movieProgramKey($c,$d,$g,$h)",
                            movieProgramKey(c, d, g, h),
                            displayProgramKey(FrameEffect.NONE, c, d, g, h)
                        )
                    }
                }
            }
        }
        // 反证：开着监看叠加时两键必须不等（否则成片会带上叠加）
        assertTrue(
            displayProgramKey(FrameEffect.ZEBRA, true, DENOISE_LEVEL_OFF, false, false) !=
                movieProgramKey(true, DENOISE_LEVEL_OFF, false, false)
        )
    }

    // ------------------------------------------------------------------ 回退阶梯

    /** 阶梯的严格下降量（每一步都必须减小）：glow → echo → denoise → effect → curve 的字典序 */
    private fun rank(k: ProgramKey): Int =
        (if (k.glow) 1 else 0) * 32 + (if (k.echo) 1 else 0) * 16 + k.denoise * 4 +
            (if (k.effect != FrameEffect.NONE) 1 else 0) * 2 + (if (k.curve) 1 else 0)

    /** 把回退链走到底（步数上限防死循环；JVM 测试里链应 ≤7 步） */
    private fun chain(from: ProgramKey): List<ProgramKey> {
        val out = ArrayList<ProgramKey>()
        var cur: ProgramKey? = from
        var guard = 0
        while (cur != null) {
            out.add(cur)
            assertTrue("回退链超过 16 步（疑似环）", guard++ < 16)
            cur = nextFallback(cur)
        }
        return out
    }

    @Test
    fun `回退链严格单调且终点是直通键`() {
        val direct = ProgramKey(FrameEffect.NONE, false, DENOISE_LEVEL_OFF, false, false)
        for (e in FrameEffect.values()) {
            for (c in booleans) {
                for (d in denoiseLevels) {
                    for (g in booleans) {
                        for (h in booleans) {
                            val start = displayProgramKey(e, c, d, g, h)
                            val steps = chain(start)
                            assertEquals("回退链第一步恒为自身（含终点的判定）", start, steps.first())
                            for (i in 1 until steps.size) {
                                assertTrue(
                                    "回退必须严格下降：${steps[i - 1].short()} → ${steps[i].short()}",
                                    rank(steps[i]) < rank(steps[i - 1])
                                )
                            }
                            assertEquals("回退链终点必须是直通键（${start.short()}）", direct, steps.last())
                            assertNull("终点再下一级必须是 null", nextFallback(direct))
                            assertTrue("同一键不许在链里出现两次（无环）", steps.size == steps.toSet().size)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `回退先降glow再降echo`() {
        val withBoth = ProgramKey(FrameEffect.NONE, true, DENOISE_LEVEL_LITE, true, true)
        assertEquals(
            "glow 必须最先降（注入式 scene 效果里它排最外层之下、链路最老）",
            withBoth.copy(glow = false),
            nextFallback(withBoth)
        )
        // glow 降完才轮到 echo（R2 §四.5 的定版位置）
        val echoOnly = withBoth.copy(glow = false)
        assertEquals(
            "echo 第二个降（它是最新的注入段，回退顺序 = 接入顺序的逆序）",
            echoOnly.copy(echo = false),
            nextFallback(echoOnly)
        )
        // 两个都关后继续降降噪档（不是先降曲线，见下一条）
        val plain = echoOnly.copy(echo = false)
        assertEquals(plain.copy(denoise = DENOISE_LEVEL_FULL), nextFallback(plain))
    }

    @Test
    fun `回退先降降噪档再降effect最后降曲线`() {
        // P2（2026-10-27 审查）：降噪着色器是最容易链接失败的一类，而曲线是用户的调色——
        // 宁可成片丢掉降噪，也不许连带把调色关掉。所以 denoise 先退、curve 最后退。
        val lite = ProgramKey(FrameEffect.NONE, true, DENOISE_LEVEL_LITE, false, false)
        assertEquals("轻量核 → 完整核（曲线不动）", lite.copy(denoise = DENOISE_LEVEL_FULL), nextFallback(lite))
        val full = ProgramKey(FrameEffect.NONE, true, DENOISE_LEVEL_FULL, false, false)
        assertEquals("完整核 → 无降噪（曲线仍不动）", full.copy(denoise = DENOISE_LEVEL_OFF), nextFallback(full))
        val curveOnly = ProgramKey(FrameEffect.NONE, true, DENOISE_LEVEL_OFF, false, false)
        assertEquals("最后才降曲线（CURVE_FS → 直通）", curveOnly.copy(curve = false), nextFallback(curveOnly))
        // display 键的 effect 维度在曲线之前降
        val zebra = ProgramKey(FrameEffect.ZEBRA, true, DENOISE_LEVEL_OFF, false, false)
        assertEquals("effect 先降到 NONE、保住曲线", zebra.copy(effect = FrameEffect.NONE), nextFallback(zebra))
    }

    @Test
    fun `降噪或效果编不出来时必经过CURVE_FS这一级`() {
        // "CURVE_FS 级" = 无降噪、无监看叠加、曲线开着 = (NONE,true,0,false,false)
        val curveLevel = ProgramKey(FrameEffect.NONE, true, DENOISE_LEVEL_OFF, false, false)
        // movie：曲线+轻量降噪+glow+echo 的失败链必须先后经过完整核、无降噪，最后才丢掉曲线
        val movie = chain(movieProgramKey(true, DENOISE_LEVEL_LITE, true, true))
        assertTrue("movie 链必须覆盖 CURVE_FS 级：${movie.map { it.short() }}", movie.contains(curveLevel))
        assertEquals(
            "movie 链的顺序：去 glow → 去 echo → 2→1→0 → CURVE_FS → 直通",
            listOf(
                ProgramKey(FrameEffect.NONE, true, DENOISE_LEVEL_LITE, true, true),
                ProgramKey(FrameEffect.NONE, true, DENOISE_LEVEL_LITE, false, true),
                ProgramKey(FrameEffect.NONE, true, DENOISE_LEVEL_LITE, false, false),
                ProgramKey(FrameEffect.NONE, true, DENOISE_LEVEL_FULL, false, false),
                curveLevel,
                ProgramKey(FrameEffect.NONE, false, DENOISE_LEVEL_OFF, false, false)
            ).map { it.short() },
            movie.map { it.short() }
        )
        // display：斑马+曲线的失败链必须落到"有曲线、无叠加"
        val display = chain(displayProgramKey(FrameEffect.ZEBRA, true, DENOISE_LEVEL_LITE, false, false))
        assertTrue("display 链必须覆盖 CURVE_FS 级：${display.map { it.short() }}", display.contains(curveLevel))
        assertEquals(
            "display 链的顺序：effect→NONE → CURVE_FS → 直通",
            listOf(
                ProgramKey(FrameEffect.ZEBRA, true, DENOISE_LEVEL_OFF, false, false),
                curveLevel,
                ProgramKey(FrameEffect.NONE, false, DENOISE_LEVEL_OFF, false, false)
            ).map { it.short() },
            display.map { it.short() }
        )
        // 明确登记取舍：不再有"有效果但无曲线"的中间档（那是用丢调色换监看叠加）
        for (k in chain(displayProgramKey(FrameEffect.ZEBRA, true, DENOISE_LEVEL_OFF, false, false))) {
            assertFalse(
                "display 链里不许出现「有效果但丢了曲线」的中间档（${k.short()}）",
                k.effect != FrameEffect.NONE && !k.curve
            )
        }
    }

    @Test
    fun `从movie键出发回退链effect恒为NONE`() {
        for (c in booleans) {
            for (d in denoiseLevels) {
                for (g in booleans) {
                    for (h in booleans) {
                        for (k in chain(movieProgramKey(c, d, g, h))) {
                            assertEquals(
                                "成片键的回退链里不许出现监看叠加（出现 = 斑马纹进成片）",
                                FrameEffect.NONE,
                                k.effect
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `离开ZEBRA或PEAKING后回退链不得再回到监看叠加`() {
        for (e in listOf(FrameEffect.ZEBRA, FrameEffect.PEAKING)) {
            for (c in booleans) {
                for (d in denoiseLevels) {
                    for (g in booleans) {
                        for (h in booleans) {
                            val steps = chain(displayProgramKey(e, c, d, g, h))
                            var left = false
                            for (k in steps) {
                                if (k.effect == FrameEffect.NONE) left = true
                                assertFalse(
                                    "回退链离开 ${e.name} 后又回到 ${k.effect.name}（${steps.map { it.short() }}）",
                                    left && k.effect != FrameEffect.NONE
                                )
                            }
                            assertEquals("终点的 effect 必须是 NONE", FrameEffect.NONE, steps.last().effect)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `日志形式只做展示且不扮成键的一部分`() {
        val key = ProgramKey(FrameEffect.PEAKING, true, DENOISE_LEVEL_OFF, false, false)
        assertEquals("(PEAKING,1,0,0,0)", key.short())
        assertEquals("effect=PEAKING curve=1 denoise=0 glow=0 echo=0", key.debug())
        assertTrue("短形式必须能从展示串一眼看出效果档", key.short().contains("PEAKING"))
    }
}
