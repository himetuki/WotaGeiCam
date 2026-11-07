package com.wotagei.cam.stance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 判定核心单测：与 PC 试验台 tools/stance-lab/tests/test_core.py 逐用例镜像
 * （同输入同期望，规格文档 docs/plan/32 §1~§5，D8/D10/D14/D15/D19）。
 * 时间戳单位毫秒（PC 端秒 ×1000）。
 */
class StanceEngineTest {

    private fun b(cx: Float = 0.5f, by: Float = 0.75f, w: Float = 0.2f, h: Float = 0.6f, conf: Float = 0.9f) =
        PersonBox(cx, by, w, h, conf)

    private fun obs(tsMs: Long, boxes: List<PersonBox>) = FrameObs(tsMs, boxes)

    private fun engine(mode: String = JudgementEngine.MODE_MANUAL, n: Int = 1) =
        JudgementEngine(mode = mode, nManual = n)

    /** 喂 nBeats 拍域内帧，返回最后一拍输出。 */
    private fun feedOk(eng: JudgementEngine, startMs: Long, nBeats: Int = StanceSpec.SMOOTH_WINDOW): EngineOutput {
        var out: EngineOutput? = null
        for (i in 0 until nBeats) {
            out = eng.observe(obs(startMs + 300L * i, listOf(b())))
        }
        return out!!
    }

    // ------------------------------------------------------------------
    // 纯函数：距离过滤 / 选主 / β 派生 / 方向 / 锚点
    // ------------------------------------------------------------------

    @Test
    fun distanceFilter_keepsSingle() {
        assertEquals(listOf(b(h = 0.6f)), distanceFilter(listOf(b(h = 0.6f))))
        assertTrue(distanceFilter(listOf(b(h = 0.10f))).isEmpty())   // ≤10% = 路人
        assertTrue(distanceFilter(emptyList()).isEmpty())
    }

    @Test
    fun distanceFilter_dropsOutliers() {
        val crowd = listOf(b(h = 0.60f), b(h = 0.61f), b(h = 0.59f))
        val far = b(h = 0.20f)
        val near = b(h = 1.50f)
        val kept = distanceFilter(crowd + far + near)
        assertTrue(far !in kept && near !in kept)
        assertEquals(3, kept.size)
    }

    @Test
    fun minSubjectHeight_marksRoadside() {
        // D8 十四稿：框高 ≤10% 画面高 = 路人，不论 conf
        assertTrue(selectPrimary(listOf(b(h = 0.10f, conf = 0.99f))).isEmpty())
        assertTrue(selectPrimary(listOf(b(h = 0.08f), b(h = 0.09f))).isEmpty())
        assertTrue(selectPrimary(listOf(b(h = 0.11f), b(h = 0.09f))).isNotEmpty())
        val crowd = listOf(b(h = 0.60f, cx = 0.2f), b(h = 0.61f, cx = 0.5f), b(h = 0.59f, cx = 0.8f))
        assertEquals(3, distanceFilter(crowd + b(h = 0.08f, conf = 0.99f)).size)
    }

    @Test
    fun selectPrimary_picksSubject_ignoresDistant() {
        val subject = b(cx = 0.5f, h = 0.45f, conf = 0.84f)
        val distant = b(cx = 0.8f, h = 0.12f, conf = 0.5f)
        assertEquals(listOf(subject), selectPrimary(listOf(subject, distant)))
        assertTrue(selectPrimary(emptyList()).isEmpty())
    }

    @Test
    fun deriveThresholds_monotonic() {
        val (loScale, loConf) = StanceSpec.deriveThresholds(0.05f)
        val (hiScale, hiConf) = StanceSpec.deriveThresholds(0.95f)
        val (midScale, midConf) = StanceSpec.deriveThresholds(StanceSpec.BETA_DEFAULT)
        assertTrue(loScale < midScale && midScale < hiScale)   // β 大 = 域放宽（宽松）
        assertTrue(loConf > midConf && midConf > hiConf)       // β 大 = 置信度门限低
    }

    @Test
    fun verdictDirections_fourMargins() {
        // D19：贴画面左 → 「往左」；贴右 → 「往右」；脚贴底=太近 → 「往后」；脚悬空 → 「往前」
        assertEquals(Verdict.LEFT, verdictForOne(b(cx = 0.10f)))
        assertEquals(Verdict.RIGHT, verdictForOne(b(cx = 0.90f)))
        assertEquals(Verdict.FORWARD, verdictForOne(b(cx = 0.5f, by = 0.30f)))
        assertEquals(Verdict.BACK, verdictForOne(b(cx = 0.5f, by = 0.95f)))
        assertEquals(Verdict.OK, verdictForOne(b(cx = 0.5f, by = 0.75f)))
        assertEquals(Verdict.LEFT, verdictForOne(b(cx = 0.10f, by = 0.95f)))  // 横优先
    }

    @Test
    fun verdictDomain_scalesWithBeta() {
        // β 大 → 安全区放宽 → 同一边距从「往左」变 OK
        val (tight, _) = StanceSpec.deriveThresholds(0.05f)
        val (loose, _) = StanceSpec.deriveThresholds(0.95f)
        val edge = b(cx = 0.22f, by = 0.72f, w = 0.2f, h = 0.6f)   // 左边距 0.12
        assertEquals(Verdict.LEFT, verdictForOne(edge, tight))
        assertEquals(Verdict.OK, verdictForOne(edge, loose))
    }

    @Test
    fun countConfirm_window() {
        val hist = mutableListOf<Int>()
        repeat(StanceSpec.STABLE_WINDOW - 1) {
            hist.add(1)
            assertNull(countConfirmedOf(hist))
        }
        hist.add(1)
        assertEquals(1, countConfirmedOf(hist)!!)
        hist.add(2)   // 窗口被打破 → 未达成一致返回末值（语义由引擎处理）
        assertEquals(2, countConfirmedOf(hist)!!)
    }

    @Test
    fun anchorSlots_basics() {
        assertEquals(listOf(0.5f), anchorSlots(listOf(b(cx = 0.5f)), 1))
        assertEquals(listOf(0.2f, 0.8f), anchorSlots(listOf(b(cx = 0.8f), b(cx = 0.2f)), 2))
        assertEquals(listOf(0.2f, 0.5f, 0.8f), anchorSlots(listOf(b(cx = 0.2f), b(cx = 0.8f)), 3))
    }

    @Test
    fun worstMarginDirection_basics() {
        assertEquals(Verdict.RIGHT, worstMarginDirection(b(cx = 0.90f)))   // 右脚贴右框
        assertEquals(Verdict.LEFT, worstMarginDirection(b(cx = 0.10f)))    // 左脚贴左框
        assertEquals(Verdict.BACK, worstMarginDirection(b(by = 0.95f)))    // 脚贴底=太近
        assertEquals(Verdict.FORWARD, worstMarginDirection(b(by = 0.30f))) // 脚悬空=太远
        assertEquals(Verdict.OK, worstMarginDirection(b(cx = 0.5f, by = 0.75f)))
    }

    // ------------------------------------------------------------------
    // 引擎：人数模式
    // ------------------------------------------------------------------

    @Test
    fun manualMode_locksImmediately() {
        val eng = engine()
        val out = eng.observe(obs(0, listOf(b())))
        assertEquals(1, out.nConfirmed!!)
        assertEquals(1, out.nEffective)
    }

    @Test
    fun autoMode_needsStableWindow() {
        val eng = engine(JudgementEngine.MODE_AUTO)
        val first = eng.observe(obs(0, listOf(b())))
        assertNull(first.nConfirmed)
        assertTrue(first.slots.isEmpty())
        var out = first
        for (i in 1 until StanceSpec.STABLE_WINDOW) {
            out = eng.observe(obs(300L * i, listOf(b())))
        }
        assertEquals(1, out.nConfirmed!!)
    }

    @Test
    fun missing_whenNoDetection_throttledLoop() {
        val eng = engine()
        eng.observe(obs(0, emptyList()))          // 第一拍占节流窗
        val out = eng.observe(obs(0, emptyList()))
        assertEquals(Verdict.MISSING, out.slots[0].verdict)
        assertTrue(out.lines.isEmpty())            // 2s 内节流
        val out2 = eng.observe(obs(3000, emptyList()))
        assertEquals(listOf("缺人"), out2.lines)    // D6 循环照播
    }

    @Test
    fun single_twoPersons_selectsSubject_noExtra() {
        // 用户实测场景（N=1：主体+远路人）→ 选主体判定、不报多人
        val eng = engine()
        eng.observe(obs(0, emptyList()))           // 占节流窗
        val subject = b(cx = 0.5f, h = 0.45f, conf = 0.84f)
        val distant = b(cx = 0.8f, h = 0.12f, conf = 0.5f)
        val out = eng.observe(obs(3000, listOf(subject, distant)))
        assertEquals(1, out.nEffective)
        assertEquals(0, out.extra)
        assertEquals(subject, out.slots[0].person)
        assertTrue(out.lines.none { it.contains("多人") })
    }

    @Test
    fun single_subjectRightOfRoadside_judgesSubjectNotLeftmost() {
        // 回归（2026-11-07）：主体在右侧、路人在左侧时，旧实现按 cx 取最左 → 判路人。
        // D9 口径是"选主（conf×框高最大）为主体，其余背景忽略"，判定对象必须与 nEffective 同源。
        val eng = engine()
        eng.observe(obs(0, emptyList()))
        val roadside = b(cx = 0.2f, h = 0.12f, conf = 0.6f)
        val subject = b(cx = 0.7f, h = 0.45f, conf = 0.84f)
        val out = eng.observe(obs(3000, listOf(roadside, subject)))
        assertEquals(1, out.nEffective)
        assertEquals(0, out.extra)
        assertEquals(subject, out.slots[0].person)
    }

    @Test
    fun extra_reportsMany_ignoresSurplus() {
        val eng = engine(n = 2)
        eng.observe(obs(0, emptyList()))           // 占节流窗
        val out = eng.observe(obs(3000, listOf(b(h = 0.6f, cx = 0.3f), b(h = 0.6f, cx = 0.7f))))
        assertEquals(2, out.nEffective)
        assertEquals(0, out.extra)
        val out2 = eng.observe(
            obs(6000, listOf(b(h = 0.6f, cx = 0.2f), b(h = 0.6f, cx = 0.5f), b(h = 0.6f, cx = 0.8f))),
        )
        assertEquals(1, out2.extra)
        assertEquals("多人", out2.lines.first())    // 「多人」优先占节流窗
        assertEquals(2, out2.slots.size)            // 多余框不参与判定（取前 N）
    }

    // ------------------------------------------------------------------
    // 引擎：判定 / 滞回 / 平滑 / 播报
    // ------------------------------------------------------------------

    @Test
    fun okFlow_reportsAllDone_onceAndKeepsWatching() {
        val eng = engine()
        eng.observe(obs(0, emptyList()))
        val out = feedOk(eng, 3000)
        assertTrue(out.slots[0].done)
        assertEquals(listOf("全员，完成"), out.lines)
        // 「全员，完成」后不停监控（§5.1），继续 OK 不再播
        val out2 = eng.observe(obs(10000, listOf(b(cx = 0.48f))))
        assertTrue(out2.slots[0].done)
        assertTrue(out2.lines.isEmpty())
    }

    @Test
    fun hysteresis_microShift_staysDone() {
        val eng = engine()
        eng.observe(obs(0, emptyList()))
        feedOk(eng, 3000)
        // 完成态微动：整体微近（bottom 越界 0.03、top 不判，均 < 门限 0.05 占比）
        val micro = b(cx = 0.5f, by = 0.93f, h = 0.45f)
        for (i in 0 until 10) {
            val out = eng.observe(obs(4000 + 300L * i, listOf(micro)))
            assertTrue("beat $i 掉出——滞回失效", out.slots[0].done)
            assertTrue(out.lines.isEmpty())
        }
    }

    @Test
    fun bigShift_exitsDone_andRepolls() {
        val eng = engine()
        eng.observe(obs(0, emptyList()))
        feedOk(eng, 3000)
        // 大幅位移：贴右（右边距越界 > 门限）→ 掉出 → 重新轮询报方向
        val big = b(cx = 0.92f)
        var out: EngineOutput? = null
        for (i in 0 until StanceSpec.SMOOTH_WINDOW) {
            out = eng.observe(obs(4000 + 300L * i, listOf(big)))
        }
        assertFalse(out!!.slots[0].done)
        assertEquals(Verdict.RIGHT, out.slots[0].verdict)
        assertEquals("1号位，往右", out.lines.last())
    }

    @Test
    fun speakThrottle_sameText2s_changeImmediate() {
        val eng = engine()
        eng.observe(obs(0, listOf(b(cx = 0.9f))))   // 第一拍报「1号位，往右」
        val out1 = eng.observe(obs(1000, listOf(b(cx = 0.9f))))
        assertTrue(out1.lines.isEmpty())             // 2s 内同文本节流
        val out2 = eng.observe(obs(2500, listOf(b(cx = 0.9f))))
        assertEquals(listOf("1号位，往右"), out2.lines)
    }

    @Test
    fun smooth_requiresTwoOfThree() {
        val eng = engine()
        eng.observe(obs(0, emptyList()))
        // OK→歪→OK 交替：不稳定 → 一直未完成
        val seq = listOf(b(), b(cx = 0.9f), b(), b(cx = 0.9f), b(), b())
        for ((i, box) in seq.withIndex()) {
            val out = eng.observe(obs(300L * i, listOf(box)))
            assertFalse(out.slots[0].done)
        }
        // 连续 3 拍 OK → 完成
        var out: EngineOutput? = null
        for (i in 0 until StanceSpec.SMOOTH_WINDOW) {
            out = eng.observe(obs(3000 + 300L * i, listOf(b())))
        }
        assertTrue(out!!.slots[0].done)
    }

    @Test
    fun reset_reanchors() {
        val eng = engine()
        eng.observe(obs(0, emptyList()))
        feedOk(eng, 3000)
        eng.reset()
        assertNull(eng.nConfirmed)
        val out = eng.observe(obs(20000, listOf(b())))
        assertEquals(1, out.nConfirmed!!)          // manual 立即重锁
        assertFalse(out.slots[0].done)             // 完成态被重置
    }

    @Test
    fun betaChange_resetsDoneHysteresis() {
        // 用户实测 bug：β 拉回严格后红判决与 done 滞回锁矛盾 → 改 β 必须重置状态
        val eng = engine()
        eng.observe(obs(0, emptyList()))
        feedOk(eng, 3000)
        assertTrue(eng.isDone)
        eng.setBeta(0.05f)                          // 拉严格（域收紧）
        assertFalse(eng.isDone)
        val out = eng.observe(obs(10000, listOf(b(cx = 0.5f, by = 0.75f))))
        assertEquals(Verdict.OK, out.slots[0].verdict)
        assertFalse(out.slots[0].done)              // 平滑重新积累
    }

    // ------------------------------------------------------------------
    // 判定头（JSON 权重 + 手写 sigmoid）
    // ------------------------------------------------------------------

    @Test
    fun head_parseAndMath() {
        val json = """{"coef":[0.0,0.0,0.0,0.0,5.0],"intercept":-3.0,
            "meta":{"head_version":"v1-logreg","features":["cx","by","w","h","conf"]}}"""
        val head = StanceHead.fromJson(json)
        assertEquals("v1-logreg", head.version)
        // P(good) = sigmoid(5*conf - 3)：conf=0.9 → 0.8175…；conf=0.2 → 0.1192…
        val good = head.pGood(b(conf = 0.9f))
        val bad = head.pGood(b(conf = 0.2f))
        assertEquals(0.8175f, good, 0.001f)
        assertEquals(0.1192f, bad, 0.001f)
        // β=0.3 → 门槛 0.611：good 过门 → OK；bad 判歪 → 几何方向（域内框 → OK 几何兜底）
        assertEquals(Verdict.OK, head.judge(b(conf = 0.9f), beta = 0.3f))
        val rightBad = b(cx = 0.92f, conf = 0.2f)
        assertEquals(Verdict.RIGHT, head.judge(rightBad, beta = 0.3f))
        // 头说歪但几何在安全区内 → OK（头只放宽不收紧）
        assertEquals(Verdict.OK, head.judge(b(cx = 0.5f, by = 0.75f, conf = 0.2f), beta = 0.3f))
    }

    @Test
    fun head_featureMismatchThrows() {
        val json = """{"coef":[1.0,2.0],"intercept":0.0}"""
        try {
            StanceHead.fromJson(json)
            throw IllegalStateException("应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("特征数不匹配"))
        }
    }

    @Test
    fun calibratedBands_overrideAndSanity() {
        // MARGIN_BANDS 是 object 单例（全局可变）——快照恢复，防测试间污染
        val snapshot = StanceSpec.MARGIN_BANDS.toMap()
        try {
            // 合法标定覆盖（真实标定值：用户构图脚贴画面底）
            val ok = """{"bands":{"left":[0.278,0.430],"right":[0.270,0.387],"bottom":[0.000,0.051]}}"""
            assertTrue(StanceSpec.loadCalibratedBands(ok))
            assertEquals(0.000f to 0.051f, StanceSpec.MARGIN_BANDS["bottom"])
            // 用户真实构图：脚贴底（bottom=0.05 ∈ 标定域）→ 不再误报「往后」
            assertEquals(Verdict.OK, verdictForOne(b(cx = 0.5f, by = 0.95f, w = 0.38f, h = 0.44f)))
            // 非法结构 → 不写入、保持原样
            assertFalse(StanceSpec.loadCalibratedBands("""{"bands":{"left":[0.9,0.1]}}"""))
            assertFalse(StanceSpec.loadCalibratedBands("不是json"))
        } finally {
            StanceSpec.MARGIN_BANDS.clear()
            StanceSpec.MARGIN_BANDS.putAll(snapshot)
        }
    }
}
