package com.wotagei.cam.camera

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import com.wotagei.cam.source.KotlinSourceScan.occurrences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧呈现时间戳口径（真实时钟落网格）——2026-10-08 真机缺陷的回归守卫。
 *
 * 真机事实（秒表对照一轮 31.85s 的真实录制）：视频轨合计 34.21s（每段恒 ~180 帧 / 7.45s），
 * 音频轨合计 30.01s（音频戳取自真实 nanoTime ⇒ 它才是真实时长）⇒ 成片被匀速拉长 8~14%、
 * 与音轨逐渐错位。原因：戳按"帧序号 × 1e9/fps"走，隐含"源实到帧率 = 目标帧率"，而半精确档
 * （24※）下源实到 26~31 帧/秒。本文件把新口径钉住：**网格位置取自真实时钟**。
 */
class EncoderSlotClockTest {

    private val sec = 1_000_000_000L
    private val base = 1_000_000_000L
    private val fps24Step = 1_000_000_000L / 24

    // ---- ① 纯函数表 ----

    @Test
    fun `本段首帧落在基准上（slot 0）`() {
        assertEquals(base, encoderSlotPtsNs(base, base, 24, 0L))
        assertEquals(base, encoderSlotPtsNs(base, base + 1L, 24, 0L))
    }

    @Test
    fun `满一格取下一格`() {
        assertEquals(base + fps24Step, encoderSlotPtsNs(base, base + fps24Step, 24, 0L))
        assertEquals(base + 2 * fps24Step, encoderSlotPtsNs(base, base + 2 * fps24Step, 24, 0L))
    }

    @Test
    fun `不到一格不进格（抖动被网格吸收，容器帧长仍齐）`() {
        val jittery = base + fps24Step / 3
        assertEquals(base, encoderSlotPtsNs(base, jittery, 24, 0L))
    }

    @Test
    fun `源帧率高于目标时同格多帧：时间戳相等而不是被推进`() {
        val first = encoderSlotPtsNs(base, base, 24, 0L)
        // 同一格内再来一帧（48fps 实到时的情形）
        val second = encoderSlotPtsNs(base, base + 5_000_000L, 24, first)
        assertEquals("同格第二帧必须与第一帧同戳（推进就等于把源帧率当成目标帧率、时间轴又被拉长）", first, second)
    }

    @Test
    fun `源端漏帧时网格拉出空档（真实缺口如实反映，不压缩）`() {
        val pts = encoderSlotPtsNs(base, base + 3 * fps24Step, 24, base)
        assertEquals(base + 3 * fps24Step, pts)
    }

    @Test
    fun `时钟回退时绝不小于上一枚（容器对同轨时间戳倒退直接判失败）`() {
        val last = base + 5 * fps24Step
        assertEquals(last, encoderSlotPtsNs(base, base + fps24Step, 24, last))
        assertEquals(last, encoderSlotPtsNs(base, base - sec, 24, last))
    }

    @Test
    fun `时钟早于基准时退回基准`() {
        assertEquals(base, encoderSlotPtsNs(base, base - sec, 24, 0L))
    }

    @Test
    fun `帧率不可用时不改戳（原样返回上一枚）`() {
        assertEquals(123L, encoderSlotPtsNs(base, base + sec, 0, 123L))
        assertEquals(123L, encoderSlotPtsNs(base, base + sec, -1, 123L))
    }

    @Test
    fun `25fps 同样成立（另一条必需档位）`() {
        val step = 1_000_000_000L / 25
        assertEquals(base + step, encoderSlotPtsNs(base, base + step, 25, 0L))
        assertEquals(base, encoderSlotPtsNs(base, base + step - 1L, 25, 0L))
    }

    /**
     * 本缺陷的**回归本体**：源实到 31 帧/秒、目标 24 帧/秒，持续 10 秒——
     * 新口径的时间轴必须 ≈10 秒（旧口径 310 帧 × 1/24 ≈ 12.9 秒，正是真机那 8~14% 的拉长）。
     */
    @Test
    fun `源帧率高于目标时时间轴仍等于真实时长`() {
        var last = 0L
        var n = 0
        while (n < 310) {
            val now = base + n * sec / 31
            last = encoderSlotPtsNs(base, now, 24, last)
            n++
        }
        val span = last - base
        val oldSpan = 310 * fps24Step // 旧口径（帧序号 × 网格）
        assertTrue("新口径时间轴应 ≈10s，实际 ${span / 1e9}s", span in 9_900_000_000L..10_010_000_000L)
        assertTrue("旧口径应明显更长（${oldSpan / 1e9}s），否则本用例没测到拉长", oldSpan > 12_000_000_000L)
    }

    @Test
    fun `帧率越高时长越准（48fps 源、24fps 目标，10 秒仍是 10 秒）`() {
        var last = 0L
        var n = 0
        while (n < 480) {
            last = encoderSlotPtsNs(base, base + n * sec / 48, 24, last)
            n++
        }
        assertTrue("48fps 源也必须是 10s 量级，实际 ${(last - base) / 1e9}s", (last - base) in 9_900_000_000L..10_010_000_000L)
    }

    // ---- ② 接线守卫：stampEncoderPresentation 必须走新口径 ----

    @Test
    fun `呈现时间戳必须走真实时钟落网格`() {
        val gl = codeOnly(mainSourceText("camera/GlRenderEngine.kt"))
        val body = bodyOf(gl, "stampEncoderPresentation")
        assertTrue("必须调 encoderSlotPtsNs 桥", body.contains("encoderSlotPtsNs("))
        assertTrue("必须传真实时钟", body.contains("System.nanoTime()"))
        assertTrue("必须更新'最后一枚戳'账（防回退要它）", body.contains("encoderLastPtsNs ="))
        assertFalse(
            "不许退回'帧序号 × 网格'的旧口径（那会把源帧率当成目标帧率、时间轴被拉长）",
            body.contains("encoderFrameIndex *")
        )
        // 既有守卫的锚点（FrostEncoderGuardTest）不能被这轮改动碰掉
        assertTrue("锚点：EGL 呈现时间戳写入", body.contains("eglPresentationTimeANDROID(display, encoder, ptsNs)"))
        assertTrue("锚点：帧号自增", body.contains("encoderFrameIndex++"))
    }

    @Test
    fun `每段必须复位最后一枚戳的账`() {
        val gl = codeOnly(mainSourceText("camera/GlRenderEngine.kt"))
        // 定义 + 段起点复位（与 encoderBaseNs 同一处）
        val n = gl.split("encoderLastPtsNs").size - 1
        assertTrue("encoderLastPtsNs 至少要有定义 + 段起点复位 + 打戳处读写，实际 $n 处", n >= 3)
        assertTrue(
            "段起点必须与 encoderBaseNs 一起复位（否则上一段的戳会把新段第一帧顶到旧网格）",
            gl.contains("encoderBaseNs = System.nanoTime()\n        encoderLastPtsNs = 0L") ||
                gl.contains("encoderLastPtsNs = 0L")
        )
    }

    // ---- ③ 打戳观测三联（S1-M2-02 帧龄 / M2-03 空格 / M2-06 写戳失败）----
    //
    // 这三条都是**只观测**：成片时间轴口径、网格步长、封轨语义一字未动，
    // 唯一的行为修正是 M2-06 的「写戳失败不再先推进 encoderFrameIndex / encoderLastPtsNs」。

    private val step24 = 1_000_000_000L / 24
    private val ms = 1_000_000L

    // ---- ③-1 空格计数（纯函数 encoderSlotGap）----

    @Test
    fun `空格数_满一格与同格都不算空档`() {
        assertEquals("首帧没有上一枚戳，不算空档", 0L, encoderSlotGap(0L, base, 24))
        assertEquals("满一格正好推进一格，空档为 0", 0L, encoderSlotGap(base, base + step24, 24))
        assertEquals("同格多帧（源帧率高于目标）时间戳相等，不算空档", 0L, encoderSlotGap(base, base, 24))
    }

    @Test
    fun `空格数_跳两格空一格跳五格空四格`() {
        assertEquals(1L, encoderSlotGap(base, base + 2 * step24, 24))
        assertEquals(4L, encoderSlotGap(base, base + 5 * step24, 24))
        // M2-01 的实验形态（实到 23.75 → 每 4.00s 一个空档）正是这一条
        assertEquals("每 4.00s 一个空档 ⇒ 单次跳 5 格空 4 格", 4L, encoderSlotGap(base, base + 5 * step24, 24))
    }

    @Test
    fun `空格数_戳未前进与档位不可用都不计`() {
        assertEquals("时钟回退时戳被夹回上一枚，不是空档", 0L, encoderSlotGap(base + 5 * step24, base, 24))
        assertEquals("帧率不可用时不改戳，也不该报空档", 0L, encoderSlotGap(base, base + 9 * step24, 0))
        assertEquals(0L, encoderSlotGap(base, base + 9 * step24, -1))
    }

    /** 与本文件既有口径的**一致性**守卫：encoderSlotPtsNs 拉出的空档，encoderSlotGap 必须如实数出来 */
    @Test
    fun `空格数与既有落格口径自洽（不改戳只数数）`() {
        // 期望值手推，不由实现反推：目标 24fps ⇒ step = 1e9/24 = 41666666ns（整除截断）
        // - 源 12fps：帧距 83333333ns = 2×step+1 ⇒ 每帧跨 2 格 ⇒ 每帧空 1 格；首帧无上一枚戳不计
        //   120 帧 ⇒ 119 次跳转 ⇒ 空格 119 格、最大跨距 1
        // - 源 8fps：帧距 125000000ns = 3×step+2 ⇒ 每帧跨 3 格 ⇒ 每帧空 2 格
        //   80 帧 ⇒ 79 次跳转 ⇒ 空格 158 格、最大跨距 2
        assertEquals(119L, gapTotalOver(srcFps = 12, frames = 120))
        assertEquals(158L, gapTotalOver(srcFps = 8, frames = 80))
        assertEquals(1L, gapMaxOver(srcFps = 12, frames = 120))
        assertEquals(2L, gapMaxOver(srcFps = 8, frames = 80))
    }

    /** 按源实到帧率跑一段，逐帧走「既有落格口径 → 空格桥」，返回累计空格格数 */
    private fun gapTotalOver(srcFps: Int, frames: Int): Long {
        var last = 0L
        var total = 0L
        for (i in 0 until frames) {
            val pts = encoderSlotPtsNs(base, base + i * sec / srcFps, 24, last)
            total += encoderSlotGap(last, pts, 24)
            last = pts
        }
        return total
    }

    private fun gapMaxOver(srcFps: Int, frames: Int): Long {
        var last = 0L
        var max = 0L
        for (i in 0 until frames) {
            val pts = encoderSlotPtsNs(base, base + i * sec / srcFps, 24, last)
            val g = encoderSlotGap(last, pts, 24)
            if (g > max) max = g
            last = pts
        }
        return max
    }

    /**
     * 突变自证：把生产 [encoderSlotGap] 的**扣减量**改坏（`- 1L` 改成 `- 2L`）必须被上表测出来。
     *
     * 上一轮这里放的是把判定改成 `jumped > 0L` 的突变体——**那是语义空操作**：真品与突变体在
     * `jumped <= 0` 都返 0、`jumped == 1` 都返 0（真品走 else、突变体被 `> 1L` 之外的分支兜住后同样返 0）、
     * `jumped >= 2` 都返 `jumped - 1`，逐点相等 ⇒ 下面那条 `any { mutant != expect }` 恒 false，
     * 这条"突变自证"永远绿、什么也证明不了。**判据：突变体必须在真值表上真的偏离，否则尺子是空的。**
     * 现在这个突变体与真身只差一个 `- 2L`（`if (step <= 0L) 0L` 那道守卫也照样抄进来，保证差异只有这一处），
     * 在第 4 行（跳两格，期望 1 / 实得 0）与第 5 行（跳五格，期望 4 / 实得 3）同时偏离 ⇒ 尺子有两颗牙。
     */
    @Test
    fun `尺子自己能红_空格扣减量被改坏必报红`() {
        val mutant: (Long, Long, Int) -> Long = { last, pts, fps ->
            if (fps <= 0 || last <= 0L || pts < last) 0L
            else {
                val step = 1_000_000_000L / fps
                if (step <= 0L) 0L else {
                    val jumped = (pts - last) / step
                    if (jumped > 1L) jumped - 2L else 0L   // 突变：`- 1L` 被改成 `- 2L`（多扣一格）
                }
            }
        }
        val cases = listOf(
            Triple(0L, base, 0L),
            Triple(base, base + step24, 0L),
            Triple(base, base, 0L),
            Triple(base, base + 2 * step24, 1L),
            Triple(base, base + 5 * step24, 4L)
        )
        val deviatedAt = cases.indices.filter { i -> mutant(cases[i].first, cases[i].second, 24) != cases[i].third }
        assertEquals(
            "突变体必须恰好在第 4、5 行（跳两格/跳五格）偏离真值表；偏离 0 行 = 语义空操作，尺子什么也测不到。" +
                "实际偏离行号：$deviatedAt",
            listOf(3, 4), deviatedAt
        )
        // 良品在同一张表上必须全对（否则"突变体报红"没有意义）
        cases.forEach { (l, p, expect) -> assertEquals(expect, encoderSlotGap(l, p, 24)) }
    }

    // ---- ③-2 段收尾行成文（纯函数 stampObsLine）：帧龄均值/峰值、空格、失败数 ----

    @Test
    fun `段收尾行必须含帧龄均值与峰值`() {
        val line = stampObsLine("换面", 200 * ms, 4, 80 * ms, 0, 0, 0)
        assertTrue("帧龄均值必须进段收尾行：$line", line.contains("均值=50ms"))
        assertTrue("帧龄峰值必须进段收尾行：$line", line.contains("峰值=80ms"))
        assertTrue("样本数必须进段收尾行（均值无样本时要自明）：$line", line.contains("样本=4"))
    }

    @Test
    fun `段收尾行必须含空格数与最大跨距与写戳失败数`() {
        val line = stampObsLine("解面", 0, 0, 0, 23, 4, 2)
        assertTrue("空格数必须进段收尾行：$line", line.contains("空格=23格"))
        assertTrue("最大跨距必须进段收尾行：$line", line.contains("最大跨距=4格"))
        assertTrue("写戳失败数必须进段收尾行：$line", line.contains("写戳失败=2"))
    }

    @Test
    fun `段收尾行无样本时均值报零而不是除零崩`() {
        val line = stampObsLine("换面", 0, 0, 0, 0, 0, 0)
        assertTrue("无样本必须是 均值=0ms 且 样本=0：$line", line.contains("均值=0ms") && line.contains("样本=0"))
    }

    /** 突变自证：把均值字段从行里删掉（内联字符串时被手滑改掉的形态）必红 */
    @Test
    fun `尺子自己能红_段收尾行删掉任一字段必报红`() {
        val good = stampObsLine("换面", 200 * ms, 4, 80 * ms, 23, 4, 2)
        // 断言本身能看出缺字段（供突变体吃）
        fun violated(s: String) = !s.contains("均值=50ms") || !s.contains("空格=23格") || !s.contains("写戳失败=2")
        assertFalse("良品必须先真的绿", violated(good))
        assertTrue("删掉均值字段必红", violated(good.replace("均值=50ms", "均值=")))
        assertTrue("删掉空格字段必红", violated(good.replace("空格=23格", "空格=")))
        assertTrue("删掉失败数字段必红", violated(good.replace("写戳失败=2", "写戳失败=")))
    }

    // ---- ③-3 接线守卫：打戳处记账 + 段尾落行 + M2-06 不推进位次 ----

    /** 打戳处接线体检：返回违规清单，空 = 接线完整（真身与突变体同吃，本仓既有范式） */
    private fun stampBodyViolations(maskedGl: String): List<String> {
        val body = flatten(bodyOf(maskedGl, "stampEncoderPresentation"))
        val bad = ArrayList<String>()
        // 正向锚点先行：切片空转会让下面所有否定断言一起假绿
        val write = body.indexOf("eglPresentationTimeANDROID(display, encoder, ptsNs)")
        if (write < 0) bad.add("锚点丢失：没截到 eglPresentationTimeANDROID")
        val idx = body.indexOf("encoderFrameIndex++")
        if (idx < 0) bad.add("锚点丢失：没截到帧号自增")
        // M2-02 帧龄：换帧墙钟 − 相机 SOF 戳，均值与极值两枚都记
        if (!body.contains("lastDrawnTimestampNs")) bad.add("帧龄必须取相机 SOF 戳")
        if (!body.contains("nowNs - frameTsNs")) bad.add("帧龄必须用同一趟 nanoTime 的 nowNs")
        if (body.split("System.nanoTime()").size - 1 > 1) bad.add("不许每帧再取一次 System.nanoTime（两趟时钟之差不是帧龄）")
        if (!body.contains("stampAgeSumNs +=")) bad.add("必须累计帧龄和")
        if (!body.contains("stampAgeCount++")) bad.add("必须累计帧龄样本数")
        if (!body.contains("stampAgeMaxNs")) bad.add("必须累计帧龄峰值")
        // M2-03 空格：必须走纯函数桥（内联即失守——删掉分支测试仍全绿）
        if (!body.contains("encoderSlotGap(")) bad.add("空格计数必须走纯函数 encoderSlotGap 桥")
        if (!body.contains("stampGapSlots +=")) bad.add("必须累计空格数")
        if (!body.contains("stampGapMaxSlots")) bad.add("必须累计最大跨距")
        // M2-06：失败计次 + 失败不推进位次
        if (!body.contains("stampFailCount++")) bad.add("写戳失败必须计次")
        val guard = body.indexOf("if (!written) return")
        val last = body.indexOf("encoderLastPtsNs =")
        if (guard < 0) bad.add("锚点丢失：没截到写戳失败早退")
        if (last < 0) bad.add("锚点丢失：没截到最后一枚戳的推进")
        if (write >= 0 && guard >= 0 && last >= 0 && guard !in write until last) {
            bad.add("失败早退必须先于推进 encoderLastPtsNs（否则账与容器内容不一致）")
        }
        if (write >= 0 && guard >= 0 && idx >= 0 && guard !in write until idx) {
            bad.add("失败早退必须先于推进 encoderFrameIndex（否则位次 sidecar 宣称了没有网格戳的位次）")
        }
        return bad
    }

    /** 段尾接线体检：返回违规清单，空 = 接线完整 */
    private fun drainViolations(maskedGl: String): List<String> {
        val bad = ArrayList<String>()
        val drain = flatten(bodyOf(maskedGl, "drainStampObs"))
        if (!drain.contains("stampObsLine(")) bad.add("段尾行必须走纯函数 stampObsLine 成文（内联字符串 = 删字段仍全绿）")
        if (!drain.contains("Log.i(")) bad.add("段尾行必须真的发出去")
        listOf("stampAgeSumNs", "stampAgeCount", "stampAgeMaxNs", "stampGapSlots", "stampGapMaxSlots", "stampFailCount")
            .forEach { if (!drain.contains("$it = 0")) bad.add("段尾必须清零 $it（漏一枚就跨段串账）") }
        val rebind = flatten(bodyOf(maskedGl, "replaceEncoderSurface"))
        // needle 必须止于标识符：`occurrences` 的词边界判定看的是 needle **后面**那个字符，
        // needle 带尾括号时其后紧跟实参首字母（`drainStampObs(if (...)` ⇒ 后面是 `i`）会被判成标识符字符、
        // 命中被丢弃，计数恒 0（本轮门禁 3 红就是踩在这里，属守卫自身缺陷而非生产缺陷）。
        // `drainStampObs` 在本函数体内只有 1141 那一处调用点，词边界天然成立 ⇒ 计数即调用点数。
        val n = occurrences(rebind, "drainStampObs").size
        if (n != 1) bad.add("换面/解面必须共用同一出口且调用点唯一，实际 $n 处")
        // 顺序闸门**无条件生效**：上一轮写成 `if (n == 1 && ...)`，而 n 因上面的词边界误判恒为 0，
        // 整条顺序断言被短路、从未执行过一次（假绿比红更危险）。这里拆成三枚互不依赖的判定。
        val drainAt = rebind.indexOf("drainStampObs(")
        val destroyAt = rebind.indexOf("destroyEglSurface(encoderEglSurface)")
        if (drainAt < 0) bad.add("锚点丢失：没截到 drainStampObs 调用点（被删或改名，顺序无从谈起）")
        if (destroyAt < 0) bad.add("锚点丢失：没截到销毁旧编码面")
        if (occurrences(rebind, "destroyEglSurface(encoderEglSurface)").size != 1) {
            bad.add("销毁旧编码面必须只有一处（多处意味着换面途中还有别的销毁点，账会悬空）")
        }
        if (drainAt >= 0 && destroyAt >= 0 && drainAt >= destroyAt) {
            bad.add("落行必须早于销毁旧编码面（新面绑定失败会就地 return，放在后面账就悬着混进下一段）")
        }
        return bad
    }

    @Test
    fun `打戳处记账与段尾落行接线完整`() {
        val gl = codeOnly(mainSourceText("camera/GlRenderEngine.kt"))
        val stampBad = stampBodyViolations(gl)
        assertEquals("打戳处接线发现断点：\n${stampBad.joinToString("\n")}", 0, stampBad.size)
        val drainBad = drainViolations(gl)
        assertEquals("段尾接线发现断点：\n${drainBad.joinToString("\n")}", 0, drainBad.size)
    }

    @Test
    fun `写戳失败计次且不进位次索引与最后一枚戳`() {
        val gl = codeOnly(mainSourceText("camera/GlRenderEngine.kt"))
        val body = flatten(bodyOf(gl, "stampEncoderPresentation"))
        // 顺序闸门的正面表述（与违规清单同一判据，单独点名便于失败时定位）
        val write = body.indexOf("eglPresentationTimeANDROID(display, encoder, ptsNs)")
        val guard = body.indexOf("if (!written) return")
        val last = body.indexOf("encoderLastPtsNs =")
        val idx = body.indexOf("encoderFrameIndex++")
        assertTrue("锚点丢失：没截到写戳失败早退", guard >= 0)
        assertTrue("写戳失败必须计次（否则只有一行日志、无计数）", body.contains("stampFailCount++"))
        assertTrue("失败早退必须先于推进 encoderLastPtsNs（guard=$guard last=$last）", guard in write until last)
        assertTrue("失败早退必须先于推进 encoderFrameIndex（guard=$guard idx=$idx）", guard in write until idx)
    }

    /** 突变自证：把各处改回缺陷形态（内联 / 删字段 / 不计次 / 先推进 / 落行晚了）必报红 */
    @Test
    fun `尺子自己能红_打戳三联各突变体必报红`() {
        val gl = codeOnly(mainSourceText("camera/GlRenderEngine.kt"))
        // 良品必须先真的绿，否则后面突变体的红没有意义
        assertEquals("良品打戳接线必须先绿", 0, stampBodyViolations(gl).size)
        assertEquals("良品段尾接线必须先绿", 0, drainViolations(gl).size)

        val mutants = listOf(
            Triple("删掉帧龄累计（只剩戳不记龄）", gl.replace("stampAgeSumNs += ageNs", ""), true),
            Triple("帧龄每帧另取一次时钟", gl.replace("val nowNs = System.nanoTime()", "val nowNs = System.nanoTime(); System.nanoTime()"), true),
            Triple("空格内联不走纯函数桥", gl.replace("val gap = encoderSlotGap(encoderLastPtsNs, ptsNs, fps)", "val gap = (ptsNs - encoderLastPtsNs) / 1000000L"), true),
            Triple("删掉空格累计", gl.replace("stampGapSlots += gap", ""), true),
            Triple("写戳失败不计次", gl.replace("stampFailCount++", ""), true),
            // M2-06 的缺陷形态：账在写戳之前就推进
            Triple(
                "先推进位次再写戳（M2-06 缺陷形态）",
                gl.replace(
                    "val ptsNs = encoderSlotPtsNs(encoderBaseNs, nowNs, fps, encoderLastPtsNs)",
                    "val ptsNs = encoderSlotPtsNs(encoderBaseNs, nowNs, fps, encoderLastPtsNs); encoderLastPtsNs = ptsNs; encoderFrameIndex++"
                ),
                true
            ),
            Triple("段尾不落观测行", gl.replace("drainStampObs(if (surface == null)", "/*观测*/(if (surface == null)"), true),
            Triple(
                "落行晚于销毁旧面",
                gl.replace("drainStampObs(if (surface == null)", "")
                    .replace("destroyEglSurface(encoderEglSurface)", "destroyEglSurface(encoderEglSurface); drainStampObs(if (surface == null)"),
                true
            ),
            Triple("段尾漏清一枚账", gl.replace("stampGapMaxSlots = 0L", ""), true),
            Triple("段尾内联成文不走纯函数", gl.replace("stampObsLine(", "inlineLine("), true),
            // 对照组：改注释/空白不许引发红（尺子不许对无害改动过敏）
            // 注释必须落在**声明之前**：masked 文本上再插的 `//`/`/* */` 不会二次遮蔽，
            // 插在名字与 `(` 之间会把 `\bfun\s+drainStampObs\s*\(` 这条声明正则打断，
            // bodyOf 直接抛「找不到函数体」——尺子对无害改动过敏，还把整条用例变成红。
            Triple("对照组：只改注释", gl.replace("private fun drainStampObs(", "/*观测*/ private fun drainStampObs("), false),
            Triple("对照组：只改空白", gl.replace("private fun stampEncoderPresentation", "private  fun  stampEncoderPresentation"), false)
        )
        mutants.forEach { (name, mutated, shouldBeRed) ->
            val red = stampBodyViolations(mutated) + drainViolations(mutated)
            if (shouldBeRed) {
                assertTrue("突变体「$name」必须报红（实际违规：${red.joinToString("；")}）", red.isNotEmpty())
            } else {
                assertEquals("对照组「$name」不该报红（实际违规：${red.joinToString("；")}）", 0, red.size)
            }
        }
    }
}
