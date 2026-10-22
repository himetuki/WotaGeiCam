package com.wotagei.cam.camera

import com.wotagei.cam.core.RangeI
import com.wotagei.cam.core.WotaTiers
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import com.wotagei.cam.source.KotlinSourceScan.regionOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fps 退档口径守卫（2026-10-10 S3-S3-01 + S3-03）。
 *
 * # 缺陷本体（S3-01）
 * `Camera2Engine.ceilingFps` 用 `usable > r.lo` 挑范围：固定档 `[f,f]` 上恒为 `f > f == false`，
 * **所有固定档被全排除**；LEGACY 设备按契约只报固定档，于是「可达上限」必然落到兜底分支
 * `minOfOrNull { it.hi }`——那是全表**最小**上界。用户选 120fps、设备能到 60，却被退到 15fps。
 *
 * # 修法与本守卫的三段分工
 * 1. 算式抽成纯函数 [FrameRateFallback.ceilingFps]，口径改成「只排除被 cap 截断后不可用的范围」
 *    （`min(hi,cap) >= lo`），全表用例钉死；
 * 2. **接线守卫**钉住「[Camera2Engine.ceilingFps] 体内确实调用该纯函数」——注释里提一句、
 *    或把算式改回内联，本守卫必须红（判据散落内联 = 纯函数桥断）；
 * 3. 突变自证：把旧口径（`>` + 最小上界兜底）当突变体喂进同一张表，证实这张表真能测出该退化。
 *
 * S3-03 同文件守：白平衡保活节流必须用单调钟，墙钟回拨会让它永久停摆。
 */
class FrameRateFallbackTest {

    private fun maskedMain(rel: String): String = codeOnly(mainSourceText(rel))

    /**
     * 「计划→行为」桥：用例一律经它落到真函数上，测试里**不许另写一份算法**
     * （否则「改实现不改用例」时表照样全绿，正是静态证明最怕的假绿）。
     */
    private fun ceiling(ranges: List<RangeI>, cap: Int): Int = FrameRateFallback.ceilingFps(ranges, cap)

    // ------------------------------------------------------------------ 纯函数全表

    /** 固定档表（LEGACY 设备按契约只报这种；[cap] 取无高帧率能力时的封顶） */
    private val fixedOnly = listOf(
        RangeI(15, 15), RangeI(24, 24), RangeI(25, 25), RangeI(30, 30),
        RangeI(60, 60), RangeI(120, 120), RangeI(240, 240)
    )

    /** 混合表：可变范围 + 固定档 + 一条整体在 cap 之上的范围 */
    private val mixed = listOf(
        RangeI(7, 30), RangeI(30, 60), RangeI(60, 60), RangeI(120, 120), RangeI(200, 240)
    )

    @Test
    fun `全固定档_退到cap封顶的固定档而不是全表最小值`() {
        // 缺陷形态：旧口径此处返回 15（minOfOrNull{hi}），用户选 120fps 却拿到 15fps
        assertEquals("全固定档 + cap=60：120/240 被截断不可用，可达上限是 60", 60, ceiling(fixedOnly, 60))
        assertEquals("cap=60 时 60 档本身可用", 60, ceiling(listOf(RangeI(30, 30), RangeI(60, 60)), 60))
    }

    @Test
    fun `全固定档_有高帧率能力时退到全表最高固定档`() {
        // 高帧率设备 cap=Int.MAX_VALUE：[240,240] 自然可用
        assertEquals("cap 不设限时取全表最高固定档 240", 240, ceiling(fixedOnly, Int.MAX_VALUE))
        assertEquals("120 档设备退到 120", 120, ceiling(listOf(RangeI(30, 30), RangeI(120, 120)), Int.MAX_VALUE))
    }

    @Test
    fun `混合表_可变范围按截断后的上界参与取最大`() {
        assertEquals("[7,30]→30、[30,60]→60、[60,60]→60、[120,120] 被截断、[200,240] 整段在 cap 之上", 60, ceiling(mixed, 60))
        assertEquals("cap 不设限时 [200,240] 可用", 240, ceiling(mixed, Int.MAX_VALUE))
    }

    @Test
    fun `被cap截断后仍可用与不可用的分界`() {
        // [60,120] 截断到 60 仍落在 [60,120] 内 ⇒ 可用，取 60
        assertEquals(60, ceiling(listOf(RangeI(60, 120)), 60))
        // [61,120] 截断到 60 已落在范围之外 ⇒ 不可用
        assertEquals("截断后低于 lo 的范围必须排除", 0, ceiling(listOf(RangeI(61, 120)), 60))
        // 同一条范围在 cap=Int.MAX_VALUE 下必然可用
        assertEquals(120, ceiling(listOf(RangeI(61, 120)), Int.MAX_VALUE))
    }

    @Test
    fun `无退档目标返回0而不是随便一个数`() {
        // 空表：旧兜底给 25，那是个设备根本没报过的帧率
        assertEquals("空能力表不得凭空造档位", 0, ceiling(emptyList(), 60))
        // 全部范围都在 cap 之上
        assertEquals("全表在 cap 之上 = 无退档目标", 0, ceiling(listOf(RangeI(120, 120), RangeI(240, 240)), 60))
        // CLOSED 占位（能力缺失时 RangeI.of 产出的 [0,0]）不得被当成可用
        assertEquals(0, ceiling(listOf(RangeI.CLOSED), 60))
        assertEquals("CLOSED 混在表里不影响其他范围", 60, ceiling(listOf(RangeI.CLOSED, RangeI(30, 30), RangeI(60, 60)), 60))
    }

    @Test
    fun `cap口径取自产品域常量而非机型数值`() {
        // 无高帧率能力时封顶 60 是 WotaTiers 的产品域分水岭，纯函数本身不内置任何机型值
        assertEquals(WotaTiers.HIGH_SPEED_FPS, ceiling(fixedOnly, WotaTiers.HIGH_SPEED_FPS))
        assertEquals("cap=59 时 60 档整段不可用，可达上限落到 30", 30, ceiling(fixedOnly, 59))
    }

    // ------------------------------------------------------------------ 突变自证（尺子自己能红）

    @Test
    fun `尺子自己能红_旧口径突变体必在全表上偏离`() {
        // 旧口径（缺陷形态）：`usable > r.lo` + 兜底 minOfOrNull{hi} ?: 25
        fun legacyCeiling(ranges: List<RangeI>, cap: Int): Int {
            var best = 0
            for (r in ranges) {
                val usable = minOf(r.hi, cap)
                if (usable > r.lo && usable > best) best = usable
            }
            return if (best > 0) best else (ranges.minOfOrNull { it.hi } ?: 25)
        }
        val cases = listOf(
            Triple(fixedOnly, 60, 60),
            Triple(fixedOnly, Int.MAX_VALUE, 240),
            Triple(mixed, 60, 60),
            Triple(mixed, Int.MAX_VALUE, 240),
            Triple(listOf(RangeI(60, 120)), 60, 60),
            Triple(listOf(RangeI(61, 120)), 60, 0),
            Triple(listOf(RangeI(61, 120)), Int.MAX_VALUE, 120),
            Triple(emptyList(), 60, 0),
            Triple(listOf(RangeI(120, 120), RangeI(240, 240)), 60, 0),
            Triple(listOf(RangeI.CLOSED), 60, 0),
            Triple(listOf(RangeI.CLOSED, RangeI(30, 30), RangeI(60, 60)), 60, 60),
            Triple(fixedOnly, 59, 30)
        )
        val diffs = cases.filter { (ranges, cap, expect) -> legacyCeiling(ranges, cap) != expect }
        assertTrue(
            "旧口径突变体必须在至少一条用例上偏离本表（否则这张表测不出本缺陷，尺子失效）：" +
                "偏离 ${diffs.size}/${cases.size} 条",
            diffs.isNotEmpty()
        )
        // 本缺陷的核心那一格必须真的被抓住（不能只靠边角用例凑数）
        assertEquals("S3-01 本体：全固定档 + cap=60，旧口径退成最小值 15", 15, legacyCeiling(fixedOnly, 60))
        assertEquals("S3-01 本体：新口径退到 60", 60, ceiling(fixedOnly, 60))
        // 只删掉兜底（谓词已修好、兜底仍返回最小值）也必须被抓出来
        val noFallback = cases.count { (ranges, cap, _) -> cap == 60 && legacyCeiling(ranges, cap) == ranges.minOfOrNull { it.hi } }
        assertTrue("全表至少要有一条能区分'修好谓词但留着旧兜底'的用例", noFallback > 0)
    }

    // ------------------------------------------------------------------ 接线守卫

    /** 引擎侧接线体检：返回违规清单，空 = 接线完整（真身与突变体同吃） */
    private fun engineViolations(engine: String): List<String> {
        val bad = ArrayList<String>()
        val ceilingBody = flatten(bodyOf(engine, "ceilingFps"))
        if (!ceilingBody.contains("FrameRateFallback.ceilingFps(")) {
            bad.add("ceilingFps 必须调用纯函数 FrameRateFallback.ceilingFps（算式内联 = 纯函数桥断，删掉口径测试仍全绿）")
        }
        if (ceilingBody.contains("usable >")) {
            bad.add("ceilingFps 体内不许残留旧口径 usable > r.lo（固定档会被全排除）")
        }
        if (ceilingBody.contains("minOfOrNull")) {
            bad.add("ceilingFps 体内不许残留 minOfOrNull 兜底（全表最小上界 = 退档退成最小值）")
        }
        val sync = flatten(bodyOf(engine, "syncFpsPick"))
        if (!sync.contains("val ceiling = ceilingFps(state)")) {
            bad.add("syncFpsPick 必须取 ceilingFps(state) 的值（绕开它就是退档口径没接线）")
        }
        if (!sync.contains("if (ceiling <= 0)")) {
            bad.add("syncFpsPick 必须处理 ceiling<=0（无退档目标时保持总线原值）")
        }
        if (sync.contains("minOfOrNull")) {
            bad.add("syncFpsPick 体内不许出现 minOfOrNull（全表最小上界 = 退档退成最小值）")
        }
        return bad
    }

    @Test
    fun `引擎退档口径接线完整`() {
        val violations = engineViolations(maskedMain("camera/Camera2Engine.kt"))
        assertEquals("退档口径接线发现断点：\n${violations.joinToString("\n")}", 0, violations.size)
    }

    @Test
    fun `尺子自己能红_算式内联或旧兜底残留必报红`() {
        val engine = maskedMain("camera/Camera2Engine.kt")
        assertTrue("良品必须先真的绿，否则后面突变体的红没有意义", engineViolations(engine).isEmpty())
        // 突变 1：把纯函数调用换回内联算式（保留旧谓词）
        val inlined = engine.replace(
            // 锚串随 2026-10-11 #15 迁移：cap 算式抽成 fpsCap() 单一真源（供退档日志复用）
            "return FrameRateFallback.ceilingFps(state.fpsRanges, fpsCap())",
            "var best = 0\n        for (r in state.fpsRanges) {\n            val usable = minOf(r.hi, cap)\n" +
                "            if (usable > r.lo && usable > best) best = usable\n        }\n" +
                "        return if (best > 0) best else (state.fpsRanges.minOfOrNull { it.hi } ?: 25)"
        )
        assertTrue(
            "算式内联后尺子必须红",
            engineViolations(inlined).any { it.contains("FrameRateFallback.ceilingFps") }
        )
        // 突变 2：接线还在，但旧兜底被塞回 syncFpsPick（注释里提纯函数不算数）
        val fallbackBack = engine.replace(
            "if (ceiling <= 0) {",
            "if (ceiling <= 0) {\n            writeBackFps(state.fpsRanges.minOfOrNull { it.hi } ?: 25, false)"
        )
        assertTrue(
            "旧兜底被塞回 syncFpsPick 后尺子必须红",
            engineViolations(fallbackBack).any { it.contains("minOfOrNull") }
        )
        // 突变 3：syncFpsPick 绕开 ceilingFps，直接写死一个常量当上限
        val hardCoded = engine.replace("val ceiling = ceilingFps(state)", "val ceiling = 60")
        assertTrue(
            "syncFpsPick 绕开 ceilingFps 后尺子必须红",
            engineViolations(hardCoded).any { it.contains("ceilingFps(state)") }
        )
        // 突变 4：把 ceiling<=0 的早退分支删掉（无退档目标时又去随便退一档）
        val noGuard = engine.replace("if (ceiling <= 0) {", "if (false) {")
        assertTrue(
            "ceiling<=0 早退分支被删后尺子必须红",
            engineViolations(noGuard).any { it.contains("ceiling<=0") }
        )
    }

    // ------------------------------------------------- #15 无退档目标早退：行为固化 + 可观测化
    //
    // 2026-10-11：r31 的 `acc4c42`+`29c4bca` 把退档口径改成 [FrameRateFallback.ceilingFps]，
    // 复核后**现行口径是正确的**（旧判据 `usable > r.lo` 对固定档 `[f,f]` 恒 false ⇒ 所有固定档被
    // 排除 ⇒ LEGACY 设备必然走兜底 minOfOrNull{hi}，用户选 120fps、设备能到 60 却被退到 15fps）。
    // 本项是「改了但没装机验」，不是「改错了」。所以**行为一字不改**，只把早退分支固化下来并让
    // 它可判读：真机抽验卡见 `docs/LEGACY退档抽验卡-v1.md`。

    /**
     * 从**原文**的 `syncFpsPick` 体里切出 `if (ceiling <= 0) { … return }` 这一支。
     *
     * 为什么用原文而不是 [maskedMain]：这一支的判据大半在**字符串字面量**里（`fps=$target`、
     * `ceiling=$ceiling`），`codeOnly` 会把字面量内容遮成空格，切出来什么也断不了。
     * 函数体的边界仍按遮蔽文本的区间取——`codeOnly` 输出与原文**逐位等长**（换行位置不变），
     * 所以同一组偏移量在原文上直接可用（`KotlinSourceScan` 的既有契约）。
     */
    private fun noCeilingBranch(rawEngine: String): String {
        val r = regionOf(codeOnly(rawEngine), "syncFpsPick")
        val sync = flatten(rawEngine.substring(r.start, r.end))
        val at = sync.indexOf("if (ceiling <= 0) {")
        val end = sync.indexOf("return }", at)
        assertTrue("锚点丢失：没截到 ceiling<=0 早退分支（分支形态改了或被删）", at >= 0 && end > at)
        return sync.substring(at, end + "return }".length)
    }

    /** [fpsCap] 的函数体（原文切片，同上口径） */
    private fun fpsCapBody(rawEngine: String): String {
        val r = regionOf(codeOnly(rawEngine), "fpsCap")
        return flatten(rawEngine.substring(r.start, r.end))
    }

    @Test
    fun `无退档目标时早退分支不许回写也不许发高帧率失败态`() {
        val branch = noCeilingBranch(mainSourceText("camera/Camera2Engine.kt"))
        assertFalse(
            "无退档目标时必须保持总线原值：早退分支里出现 writeBackFps = 把设备给不出的帧率写回了总线",
            branch.contains("writeBackFps(")
        )
        assertFalse(
            "无退档目标时不得发 OPEN_FAILED_HIGH_FPS：那是「已退档」的信号，此刻并没有退档",
            branch.contains("publishDevice(")
        )
    }

    @Test
    fun `无退档目标日志必须一行读得完（ranges加target加cap加ceiling判定）`() {
        val raw = mainSourceText("camera/Camera2Engine.kt")
        val branch = noCeilingBranch(raw)
        assertTrue("日志必须报 ranges 摘要（判读要看设备到底报了什么）", branch.contains("rangesSummary(state.fpsRanges)"))
        assertTrue("日志必须报 target", branch.contains("fps=\$target"))
        assertTrue("日志必须报 cap", branch.contains("cap="))
        assertTrue("日志必须报 ceiling 判定", branch.contains("ceiling=\$ceiling"))
        assertTrue(
            "日志必须仍是同一 TAG 的一次 Log.w（logcat 一行 grep）",
            Regex("Log\\.w\\(\\s*TAG,").containsMatchIn(branch)
        )
        // cap 的算式必须只有一份（ceilingFps 与日志共用），抄两份就会让「日志说的 cap」与
        // 「实际算的 cap」分叉 —— 分叉出来的日志比没日志更坏
        val cap = fpsCapBody(raw)
        assertTrue("cap 算式必须集中在 fpsCap 里", cap.contains("highSpeedCapable") && cap.contains("WotaTiers.HIGH_SPEED_FPS"))
    }

    @Test
    fun `尺子自己能红_早退分支被塞回写回或删掉必报红`() {
        val raw = mainSourceText("camera/Camera2Engine.kt")
        val branch = noCeilingBranch(raw)
        // 良品先真的绿：早退分支只有日志 + return
        assertFalse("良品早退分支必须先不含 writeBackFps", branch.contains("writeBackFps("))
        assertFalse("良品早退分支必须先不含 publishDevice", branch.contains("publishDevice("))

        // 突变 1：往早退分支里塞回总线回写（旧兜底形态：随便退到一个数）
        val wroteBack = raw.replace(
            "if (ceiling <= 0) {",
            "if (ceiling <= 0) {\n            writeBackFps(ceiling, false)"
        )
        assertTrue(
            "早退分支被塞回 writeBackFps 后尺子必须红",
            noCeilingBranch(wroteBack).contains("writeBackFps(")
        )
        // 突变 2：往早退分支里塞回高帧率失败态
        val published = raw.replace(
            "if (ceiling <= 0) {",
            "if (ceiling <= 0) {\n            publishDevice(DeviceStatus.OPEN_FAILED_HIGH_FPS)"
        )
        assertTrue(
            "早退分支被塞回 publishDevice 后尺子必须红",
            noCeilingBranch(published).contains("publishDevice(")
        )
        // 突变 3：把早退分支删掉（沿既有 mutation 4 范式），切片断言必须当场红而不是给半份文本
        val noBranch = raw.replace("if (ceiling <= 0) {", "if (false) {")
        val threw = try {
            noCeilingBranch(noBranch)
            false
        } catch (e: AssertionError) {
            true
        }
        assertTrue("ceiling<=0 早退分支被删后切片断言必须报红（不许静默放行）", threw)
    }

    // ------------------------------------------------------------------ S3-03 单调钟守卫

    @Test
    fun `白平衡保活节流必须用单调钟`() {
        val engine = maskedMain("camera/Camera2Engine.kt")
        val body = flatten(bodyOf(engine, "onCaptureCompleted"))
        assertTrue("保活节流必须读 SystemClock.elapsedRealtime()", body.contains("SystemClock.elapsedRealtime()"))
        assertTrue(
            "节流判据必须仍然在（换钟不许顺手把节流删了）",
            body.contains("now - lastWbKeepAliveMs < WB_KEEP_ALIVE_MS")
        )
        assertTrue(
            "不许退回 System.currentTimeMillis()（墙钟回拨 ⇒ now-last 变负 ⇒ 手动白平衡保活永久停摆）",
            !maskedMain("camera/Camera2Engine.kt").contains("System.currentTimeMillis()")
        )
    }

    @Test
    fun `尺子自己能红_换回墙钟必报红`() {
        val engine = maskedMain("camera/Camera2Engine.kt")
        val wallClock = engine.replace("SystemClock.elapsedRealtime()", "System.currentTimeMillis()")
        assertTrue("良品本来就不得含墙钟", !engine.contains("System.currentTimeMillis()"))
        assertTrue("换回墙钟后尺子必须红", wallClock.contains("System.currentTimeMillis()"))
        assertTrue(
            "且必须真的被守卫抓住：elapsedRealtime 消失、currentTimeMillis 出现",
            !flatten(bodyOf(wallClock, "onCaptureCompleted")).contains("SystemClock.elapsedRealtime()")
        )
    }
}