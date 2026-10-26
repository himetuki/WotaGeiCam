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
        assertTrue("必须调 encoderAdaptivePtsNs 桥（源慢接管口径）", body.contains("encoderAdaptivePtsNs("))
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
        val line = stampObsLine("换面", 200 * ms, 4, 80 * ms, 0, 0, 2375, 0)
        assertTrue("帧龄均值必须进段收尾行：$line", line.contains("均值=50ms"))
        assertTrue("帧龄峰值必须进段收尾行：$line", line.contains("峰值=80ms"))
        assertTrue("样本数必须进段收尾行（均值无样本时要自明）：$line", line.contains("样本=4"))
    }

    @Test
    fun `段收尾行必须含空格数与最大跨距与写戳失败数`() {
        val line = stampObsLine("解面", 0, 0, 0, 23, 4, 0, 2)
        assertTrue("空格数必须进段收尾行：$line", line.contains("空格=23格"))
        assertTrue("最大跨距必须进段收尾行：$line", line.contains("最大跨距=4格"))
        assertTrue("写戳失败数必须进段收尾行：$line", line.contains("写戳失败=2"))
    }

    @Test
    fun `段收尾行无样本时均值报零而不是除零崩`() {
        val line = stampObsLine("换面", 0, 0, 0, 0, 0, 0, 0)
        assertTrue("无样本必须是 均值=0ms 且 样本=0：$line", line.contains("均值=0ms") && line.contains("样本=0"))
    }

    /** 突变自证：把均值字段从行里删掉（内联字符串时被手滑改掉的形态）必红 */
    @Test
    fun `尺子自己能红_段收尾行删掉任一字段必报红`() {
        val good = stampObsLine("换面", 200 * ms, 4, 80 * ms, 23, 4, 2375, 2)
        // 断言本身能看出缺字段（供突变体吃）
        fun violated(s: String) =
            !s.contains("均值=50ms") || !s.contains("空格=23格") || !s.contains("写戳失败=2") || !s.contains("实到=23.75fps")
        assertFalse("良品必须先真的绿", violated(good))
        assertTrue("删掉均值字段必红", violated(good.replace("均值=50ms", "均值=")))
        assertTrue("删掉空格字段必红", violated(good.replace("空格=23格", "空格=")))
        assertTrue("删掉失败数字段必红", violated(good.replace("写戳失败=2", "写戳失败=")))
        assertTrue("删掉实到字段必红", violated(good.replace("实到=23.75fps", "")))
    }

    /** 实到帧率必须**直报**，用户报「成片帧率不对」时 logcat 一行就能读出来 */
    @Test
    fun `段收尾行必须直报实到帧率`() {
        val line = stampObsLine("换面", 200 * ms, 4, 80 * ms, 23, 4, 2375, 2)
        assertTrue("实到帧率必须进段收尾行（两位小数）：$line", line.contains("实到=23.75fps"))
        // 两位小数必须补零：23.05 不能打成 23.5
        val padded = stampObsLine("换面", 0, 0, 0, 0, 0, 2305, 0)
        assertTrue("不足一位小数必须补零：$padded", padded.contains("实到=23.05fps"))
        // 不可算时必须报 0.00 而不是天文数字或崩
        assertTrue(
            stampObsLine("换面", 0, 0, 0, 0, 0, 0, 0).contains("实到=0.00fps")
        )
    }

    /** 实到帧率纯函数的边界：没帧 / 跨度非正 一律报零 */
    @Test
    fun `实到帧率_无帧或跨度非正报零`() {
        assertEquals(0L, stampAchievedCentiFps(0, 1_000L))
        assertEquals(0L, stampAchievedCentiFps(-3, 1_000L))
        assertEquals(0L, stampAchievedCentiFps(683, 0L))
        assertEquals(0L, stampAchievedCentiFps(683, -1_000L))
    }

    /**
     * 实到读数与「源真实帧率」的偏差是**一帧量**，不是零。
     *
     * `span` 盖的是第 0 帧到第 n−1 帧的到达时刻，末帧曝光不在跨度里 ⇒ `n/span` 比 `n/(n/fps)` 大。
     * 本仓有「恒等式假证明」前科，所以这里把两个端点都写死，不写成任何形式的等式断言。
     */
    @Test
    fun `实到帧率_读数只偏高约一帧`() {
        // span 取 n 个帧距（末帧曝光刚好在跨度外）⇒ 读数 ≈ 23.75
        assertEquals(2375L, stampAchievedCentiFps(683, 683 * srcStep2375))
        // span 只取 n−1 个帧距 ⇒ 读数偏高约一帧（≈0.03fps @23.75）
        assertEquals(2378L, stampAchievedCentiFps(683, 682 * srcStep2375))
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
        // 2026-10-26 反馈附B：帧龄改"段内漂移"——必须先取段首基线再相减，
        // 否则两时钟域（nanoTime vs SENSOR_TIMESTAMP）的不定常量差会让指标恒为巨负
        if (!body.contains("stampAgeBaseNs = ageNs")) bad.add("必须取段首帧龄基线（跨时钟域常量的唯一去处）")
        if (!body.contains("ageNs - stampAgeBaseNs")) bad.add("帧龄累计必须吃相对基线的漂移")
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
        if (!drain.contains("stampAchievedCentiFps(")) bad.add("实到帧率必须走纯函数 stampAchievedCentiFps 算（内联除法 = 边界无人断）")
        if (!drain.contains("encoderFrameIndex")) bad.add("实到帧率的分子必须取本段帧号 encoderFrameIndex")
        if (!drain.contains("encoderBaseNs")) bad.add("实到帧率的分母必须用本段基准 encoderBaseNs 算时长")
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
            // 2026-10-26 起帧龄改"段内漂移"：累计吃 driftNs（age − 段首基线），突变判据随之更新。
            // 把基线行删掉 ⇒ drift 恒等于跨时钟域的原始差 ⇒ 指标回退成巨负数，必须报红
            Triple("删掉帧龄漂移累计（只剩戳不记龄）", gl.replace("stampAgeSumNs += driftNs", ""), true),
            Triple("删掉帧龄基线（退回跨时钟域巨负值）", gl.replace("stampAgeBaseNs = ageNs", ""), true),
            Triple("帧龄每帧另取一次时钟", gl.replace("val nowNs = System.nanoTime()", "val nowNs = System.nanoTime(); System.nanoTime()"), true),
            Triple("空格内联不走纯函数桥", gl.replace("val gap = encoderSlotGap(encoderLastPtsNs, ptsNs, fps)", "val gap = (ptsNs - encoderLastPtsNs) / 1000000L"), true),
            Triple("删掉空格累计", gl.replace("stampGapSlots += gap", ""), true),
            Triple("写戳失败不计次", gl.replace("stampFailCount++", ""), true),
            // M2-06 的缺陷形态：账在写戳之前就推进
            Triple(
                "先推进位次再写戳（M2-06 缺陷形态）",
                gl.replace(
                    "val ptsNs = encoderAdaptivePtsNs(encoderBaseNs, nowNs, stepNs, encoderLastPtsNs, encoderFrameIndex)",
                    "val ptsNs = encoderAdaptivePtsNs(encoderBaseNs, nowNs, stepNs, encoderLastPtsNs, encoderFrameIndex); encoderLastPtsNs = ptsNs; encoderFrameIndex++"
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
            // 实到帧率（2026-10-11）：分子写死 / 内联除法，两种退化形态都必须报红
            Triple("实到帧率内联算术不走纯函数", gl.replace("stampAchievedCentiFps(", "inlineFps("), true),
            Triple("实到帧率分子不取本段帧号", gl.replace("stampAchievedCentiFps(encoderFrameIndex,", "stampAchievedCentiFps(0,"), true),
            Triple("实到帧率分母不用本段基准", gl.replace("System.nanoTime() - encoderBaseNs", "System.nanoTime() - stampAgeSumNs"), true),
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

    // ---- ④ PTS 网格自适应（形态 B：逐帧接管，源慢时 PTS = max(base+elapsed, last)）----
    //
    // 背景（2026-10-11 真机量化，ALN-AL00）：名义 24fps 档上源实到 ≈23.75fps ⇒ 槽位按名义速率推进、
    // 帧按实到速率到达 ⇒ 每约 95 帧累计欠一格 ⇒ 每 4.00s 成片一个空洞（28.79s 片 683 帧 vs 应有 691，
    // 末帧 PTS 落在 689 格 = 28.708s，7 个假空档）。WIKO 源 24.48fps > 24 ⇒ 零空洞，同一模型两台机。
    // **那 7 格不是源的缺口**，是网格锚错速率的失真——源实况是「连续每秒交 23.75 帧、无缺口」。

    /** 23.75fps 恒源的帧距（ns）：`1e9/23.75 = 42105263.157…` 截断。与真机那台同源。 */
    private val srcStep2375 = 1_000_000_000L * 1000L / 23_750L

    /** 逐帧跑一段，返回 [pts 序列, 相邻空格累计, 末帧戳] */
    private data class Trace(val pts: List<Long>, val gaps: Long, val last: Long)

    private fun trace(
        srcStepNs: Long,
        frames: Int,
        adaptive: Boolean,
        skipFrames: Int = 0,
        skipAt: Int = Int.MAX_VALUE
    ): Trace {
        var last = 0L
        var gaps = 0L
        val pts = ArrayList<Long>(frames)
        for (i in 0 until frames) {
            // skipFrames：模拟源端**真漏帧**（到达时刻跳过若干帧距）
            val idx = if (i >= skipAt) i + skipFrames else i
            val now = base + idx * srcStepNs
            val p = if (adaptive) {
                encoderAdaptivePtsNs(base, now, fps24Step, last, i)
            } else {
                encoderSlotPtsNs(base, now, 24, last)
            }
            gaps += encoderSlotGap(last, p, 24)
            last = p
            pts.add(p)
        }
        return Trace(pts, gaps, last)
    }

    @Test
    fun `自适应口径_23点75恒源逐帧无洞且帧长恒定`() {
        val t = trace(srcStep2375, frames = 683, adaptive = true)
        assertEquals("慢源逐帧空格必须恒 0（否则就是那每 4.00s 一个的假空档）", 0L, t.gaps)
        val diffs = (1 until t.pts.size).map { t.pts[it] - t.pts[it - 1] }.toSet()
        assertEquals(
            "相邻差必须恒为 1/23.75（帧长均匀），实测出现了 ${diffs.size} 种取值：${diffs.sorted().take(4)}",
            setOf(srcStep2375), diffs
        )
        // 「末帧跨度 = 真实时长」**不是恒等式**：严格是 (n−1)/实际fps + 末帧曝光，与 n/实际fps 差一帧
        // （约 42ms 量级）。所以这里断言落在 ±1 帧长的容差带上，不把等号当恒等式。
        val span = t.last - base
        val oneFrame = srcStep2375
        assertTrue(
            "末帧跨度应 ≈ 683/23.75 ≈ 28.75s，实际 ${span / 1e9}s",
            span in 683L * srcStep2375 - oneFrame..683L * srcStep2375 + oneFrame
        )
        assertTrue("容差带必须真的容得下（28.7s..28.8s）", span in 28_700_000_000L..28_800_000_000L)
    }

    @Test
    fun `自适应口径_同一输入下旧口径7洞新口径0洞`() {
        val oldT = trace(srcStep2375, frames = 683, adaptive = false)
        val newT = trace(srcStep2375, frames = 683, adaptive = true)
        assertEquals("旧口径在 683 帧上必须数出 7 格假空档（钉住这条缺陷事实）", 7L, oldT.gaps)
        assertEquals("新口径必须归零", 0L, newT.gaps)
    }

    @Test
    fun `自适应口径_源快于名义时逐帧与旧口径逐字相同`() {
        for (srcFps in listOf(31, 48)) {
            val t = trace(1_000_000_000L / srcFps, frames = 400, adaptive = true)
            val oldT = trace(1_000_000_000L / srcFps, frames = 400, adaptive = false)
            val firstDiff = t.pts.indices.firstOrNull { t.pts[it] != oldT.pts[it] }
            assertTrue("源 ${srcFps}fps > 名义 24 时必须逐帧与旧口径逐字相同，首个不一致帧：$firstDiff", firstDiff == null)
        }
    }

    @Test
    fun `自适应口径_首帧与档位不可用与时钟回退逐字退旧口径`() {
        // frameIndex = 0 / 负：段首没有样本，整体走旧口径（接管谓词此时对任何正 elapsed 恒真，必须早退挡掉）
        for (fi in listOf(0, -1, -7)) {
            assertEquals(
                "frameIndex=$fi 必须与旧口径逐字相同",
                encoderSlotPtsNs(base, base + 7 * step24, 24, 0L),
                encoderAdaptivePtsNs(base, base + 7 * step24, step24, 0L, fi)
            )
        }
        // 档位不可用：生产侧 fps<=0 早退、压根不进本函数；等价于传进来的 stepNs 不可用
        for (fps in listOf(0, -1)) {
            assertEquals(
                "fps=$fps 时旧口径原样退回上一枚",
                encoderSlotPtsNs(base, base + sec, fps, 12_345L),
                encoderAdaptivePtsNs(base, base + sec, 0L, 12_345L, 5)
            )
        }
        // 时钟回退 / 未起走：退基准再夹地板
        assertEquals(
            encoderSlotPtsNs(base, base - sec, 24, base + 3 * step24),
            encoderAdaptivePtsNs(base, base - sec, step24, base + 3 * step24, 4)
        )
    }

    @Test
    fun `自适应口径_源端真漏3帧时空档如实反映不压缩`() {
        // 接管分支不认「源有没有真的漏帧」，它只判「慢没慢」：真漏 3 帧就该数出 3 格
        val t = trace(srcStep2375, frames = 683, adaptive = true, skipFrames = 3, skipAt = 300)
        assertEquals("真漏 3 帧必须如实记 3 格（压缩就等于把真实缺口藏起来）", 3L, t.gaps)
    }

    // ---- ④-1 形态守卫：生产实现不许偏离「形态 B」 ----

    @Test
    fun `形态B守卫_接管分支逐帧重判且源快时走旧内核`() {
        val body = flatten(bodyOf(codeOnly(mainSourceText("camera/GlRenderEngine.kt")), "encoderAdaptivePtsNs"))
        assertTrue("必须早于接管判定的段首早退：frameIndex <= 0", body.contains("if (frameIndex <= 0) return"))
        assertTrue("档位不可用必须原样退回上一枚", body.contains("if (stepNs <= 0L) return lastPtsNs"))
        assertTrue("接管谓词必须是「elapsed 越过名义网格位置」", body.contains("elapsed > frameIndex * stepNs"))
        assertTrue("接管分支必须是 base + elapsed", body.contains("baseNs + elapsed"))
        assertTrue("不接管分支必须复用旧内核（不许复制一份网格算术）", body.contains("slotGridPtsNs("))
        assertTrue("最终必须夹住地板", body.contains("if (candidate > lastPtsNs) candidate else lastPtsNs"))
        assertFalse("不许锁 step'（形态 A 需要跨帧状态，源漂移会逐渐失调）", body.contains("val step'"))
    }

    // ---- ④-2 突变自证 ----

    /**
     * 本文件产出的**真值表**（慢源/快源逐帧 + 三条退化行），突变体与良品同吃这张表。
     *
     * 表是手推的：慢源 6 帧的相邻差恒 42105263、快源 31fps 6 帧仍在名义网格上、
     * 三条退化行各自退旧口径。上一轮的坑是「突变体在真值表上逐点相等」——那是语义空操作，
     * 尺子永远绿、什么也测不到。这里每条突变体都必须至少偏离 1 行，否则直接判红。
     */
    private fun truthTable(pts: (Long, Long, Long, Long, Int) -> Long): List<Pair<String, Long>> {
        val rows = ArrayList<Pair<String, Long>>()
        // 慢源 23.75：逐帧 6 帧的末戳（相邻差恒 42105263 ⇒ 5·42105263 + base）
        rows.add("慢源6帧" to traceWith(pts, srcStep2375, 6))
        // 快源 31fps：逐帧仍被名义网格吸住（源快 ⇒ 接管谓词永不触发），但 6 帧的到达时刻
        // 0/1/2/3/4/5 × 32258064ns 落在第 0/0/1/2/3/3 格 ⇒ 末戳 = base + 3·step（不是 base）
        rows.add("快源31fps_6帧" to traceWith(pts, 1_000_000_000L / 31, 6))
        rows.add("frameIndex=0" to pts(base, base + 7 * fps24Step, fps24Step, 0L, 0))
        rows.add("frameIndex=-1" to pts(base, base + 7 * fps24Step, fps24Step, 12_345L, -1))
        rows.add("档位不可用" to pts(base, base + 7 * fps24Step, 0L, 12_345L, 5))
        rows.add("时钟回退" to pts(base, base - sec, fps24Step, base + 3 * fps24Step, 4))
        return rows
    }

    private fun traceWith(pts: (Long, Long, Long, Long, Int) -> Long, srcStepNs: Long, frames: Int): Long {
        var last = 0L
        for (i in 0 until frames) last = pts(base, base + i * srcStepNs, fps24Step, last, i)
        return last
    }

    /** 网格算术的本地复刻（与生产 [slotGridPtsNs] 同一形态；生产形状由「形态B守卫」钉住） */
    private fun gridCore(b: Long, now: Long, step: Long, lastPts: Long): Long {
        val elapsed = now - b
        val grid = if (elapsed <= 0L) b else b + (elapsed / step) * step
        return if (grid > lastPts) grid else lastPts
    }

    /** 本地复刻生产实现（可突变）；生产侧形状由上面的「形态B守卫」钉住，防止这份复刻漂走 */
    private fun adaptiveCopy(invertPredicate: Boolean = false, doubleStep: Boolean = false): (Long, Long, Long, Long, Int) -> Long =
        { baseNs, nowNs, stepNs, last, frameIndex ->
            if (stepNs <= 0L) last
            else if (frameIndex <= 0) gridCore(baseNs, nowNs, stepNs, last)
            else {
                val elapsed = nowNs - baseNs
                if (elapsed <= 0L) gridCore(baseNs, nowNs, stepNs, last)
                else {
                    val takeOver = if (invertPredicate) elapsed <= frameIndex * stepNs else elapsed > frameIndex * stepNs
                    val candidate = if (takeOver) {
                        baseNs + elapsed * (if (doubleStep) 2L else 1L)
                    } else {
                        gridCore(baseNs, nowNs, stepNs, last)
                    }
                    if (candidate > last) candidate else last
                }
            }
        }

    @Test
    fun `尺子自己能红_接管谓词反写与步长翻倍必偏离真值表`() {
        val good = truthTable(adaptiveCopy())
        // 良品必须先真的绿（否则"突变体报红"没有意义）
        assertEquals("慢源6帧应落在 base+5·42105263", base + 5 * srcStep2375, good[0].second)
        // 快源 31fps 逐帧仍被名义网格吸住：6 帧的到达时刻 0/1/2/3/4/5 × 32258064 全部落进
        // 第 0/0/1/2/3/3 格 ⇒ 末戳 = base + 3·step（**不是** base：第 4 帧起已经进到第 3 格）
        assertEquals("快源31fps_6帧应落在 base+3·网格步长", base + 3 * fps24Step, good[1].second)
        val mutants = listOf(
            "接管谓词反写（> 改 <=）" to adaptiveCopy(invertPredicate = true),
            "接管步长翻倍（base+elapsed 改 base+2·elapsed）" to adaptiveCopy(doubleStep = true)
        )
        mutants.forEach { (name, bad) ->
            val rows = truthTable(bad)
            val deviated = good.indices.filter { good[it].second != rows[it].second }
            assertTrue(
                "突变体「$name」必须至少偏离真值表 1 行（偏离 0 行 = 语义空操作，尺子什么也测不到）。实际偏离：$deviated",
                deviated.isNotEmpty()
            )
        }
        // 钉住谓词反写必须连**快源行**一起带偏：只偏慢源行的话，
        // 「接管条件写反」与「接管条件恒真」就分不开，这条尺子测不出前者的退化
        val invRows = truthTable(adaptiveCopy(invertPredicate = true))
        assertTrue(
            "接管谓词反写必须连快源行一起偏离（实测偏离：${good.indices.filter { good[it].second != invRows[it].second }}）",
            good.indices.any { good[it].second != invRows[it].second && it == 1 }
        )
    }
}
