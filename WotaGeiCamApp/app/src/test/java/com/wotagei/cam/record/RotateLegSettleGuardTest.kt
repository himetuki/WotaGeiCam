package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import com.wotagei.cam.source.KotlinSourceScan.regionOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * 换段腿的**双端结算必须互斥**（2026-10-11）。
 *
 * 背景：腿起点原先是 `@Volatile Long`。volatile 只保证「读到的值新」，**不保证「读-算-清-加」
 * 这四步原子**——控制线程读出 start、泵线程抢先结算清 0、控制线程再 add，同一条腿记两遍，
 * [RotateLossLedger] 读数虚高约一份部分腿。
 *
 * 修法：`AtomicLong.getAndSet(0L)` 把「读 + 清」做成一次 CAS，谁先消费谁独得 start，
 * 另一侧必得 0 早退 ⇒ 双加在结构上不可能，零窗口零锁。
 *
 * ⚠ **控制线程那一笔 `rotateLoss.add(lost)` 不能删**：`rotateLoss.totalMs` 被
 * `finishWithoutTeardown` 拿去生成**用户可见**的失败结果，删了用户看到的丢失量就少算。
 */
class RotateLegSettleGuardTest {

    private val codec = codeOnly(mainSourceText("record/CodecRecorder.kt"))

    /**
     * 腿结算的本地同构模型：两处结算各自「`getAndSet(0L)` 消费 → 算 lost → `rotateLoss.add`」。
     * 生产侧形状由下面的「结构体检」钉住，这份模型不会悄悄漂走。
     *
     * @param rawAtStop true = 把控制线程那侧退回成旧形态（裸读不清位），用于突变自证
     */
    private class LegModel(private val rawAtStop: Boolean = false) {
        val start = AtomicLong(0L)
        val loss = RotateLossLedger()

        fun begin(now: Long) { start.set(now) }

        /** 泵线程侧：与生产 [CodecRecorder.settleRotateLeg] 同构 */
        fun settlePump(now: Long) {
            val s = start.getAndSet(0L)
            if (s == 0L) return
            loss.add(now - s)
        }

        /** 控制线程侧：与生产 [CodecRecorder.settleRotateLegAtStop] 同构 */
        fun settleAtStop(now: Long) {
            val s = if (rawAtStop) start.get() else start.getAndSet(0L)
            if (s == 0L) return
            loss.add(now - s)
        }
    }

    private val legMs = 5_000L

    @Test
    fun `有序双结算_先停录后泵线程只记一份`() {
        val m = LegModel()
        m.begin(1_000L)
        m.settleAtStop(1_000L + legMs)   // 控制线程先到
        m.settlePump(1_000L + legMs)      // 泵线程后到，必得 0 早退
        assertEquals("累计丢失必须只有一份", legMs, m.loss.totalMs)
        assertEquals("次数必须只有一次", 1, m.loss.count)
    }

    @Test
    fun `有序双结算_先泵线程后停录只记一份`() {
        val m = LegModel()
        m.begin(1_000L)
        m.settlePump(1_000L + legMs)      // 泵线程先到
        m.settleAtStop(1_000L + legMs)    // 控制线程后到，必得 0 早退
        assertEquals("累计丢失必须只有一份", legMs, m.loss.totalMs)
        assertEquals("次数必须只有一次", 1, m.loss.count)
    }

    /**
     * 突变自证：把控制线程那侧退回成**裸读不清位**（旧缺陷形态），两种顺序都必须多记一份。
     *
     * 上一轮的坑是「突变体与真身逐点相等」——那叫语义空操作，尺子永远绿。这里断言的是
     * **真身记一份、突变体记两份**，两条断言彼此独立成立。
     */
    @Test
    fun `尺子自己能红_控制线程裸读不清位必双加`() {
        /** 每次都新建模型：账本跨调用累加，复用实例会把两次结算叠成两份（假红） */
        fun run(rawAtStop: Boolean, stopFirst: Boolean): Pair<Long, Int> {
            val m = LegModel(rawAtStop = rawAtStop)
            m.begin(1_000L)
            if (stopFirst) {
                m.settleAtStop(1_000L + legMs); m.settlePump(1_000L + legMs)
            } else {
                m.settlePump(1_000L + legMs); m.settleAtStop(1_000L + legMs)
            }
            return m.loss.totalMs to m.loss.count
        }
        listOf(true, false).forEach { stopFirst ->
            val (gTotal, gCount) = run(false, stopFirst)
            val (bTotal, bCount) = run(true, stopFirst)
            assertEquals("stopFirst=$stopFirst 真身总量必须一份", legMs, gTotal)
            assertEquals("stopFirst=$stopFirst 真身次数必须一次", 1, gCount)
            assertEquals("stopFirst=$stopFirst 突变体必须多记一份", 2 * legMs, bTotal)
            assertEquals("stopFirst=$stopFirst 突变体次数必须变两次", 2, bCount)
        }
    }

    // ---- 结构体检（真身与突变体同吃） ----

    private fun legViolations(maskedCodec: String): List<String> {
        val bad = ArrayList<String>()
        val settle = bodyOf(maskedCodec, "settleRotateLeg")
        val atStop = bodyOf(maskedCodec, "settleRotateLegAtStop")
        listOf("settleRotateLeg" to settle, "settleRotateLegAtStop" to atStop).forEach { (name, body) ->
            if (!body.contains("rotateLegStartMs.getAndSet(0L)")) {
                bad.add("$name 必须用 rotateLegStartMs.getAndSet(0L) 原子读清")
            }
            if (body.contains("rotateLegStartMs = 0L")) {
                bad.add("$name 不许用裸 `rotateLegStartMs = 0L` 清位（读-清-加非原子，双加窗口仍在）")
            }
        }
        // 控制线程那一笔不许被删：rotateLoss.totalMs 是用户可见失败结果的丢失量
        if (!atStop.contains("rotateLoss.add(lost)")) {
            bad.add("控制线程那一笔 rotateLoss.add(lost) 不许删（用户可见失败结果靠它）")
        }
        if (!bodyOf(maskedCodec, "beginRotateLeg").contains("rotateLegStartMs.set(")) {
            bad.add("beginRotateLeg 必须走 set(...)（AtomicLong 没有裸赋值）")
        }
        if (!bodyOf(maskedCodec, "resetRotateBooks").contains("rotateLegStartMs.set(0L)")) {
            bad.add("resetRotateBooks 必须走 set(0L)")
        }
        if (!Regex("private val rotateLegStartMs = AtomicLong").containsMatchIn(maskedCodec)) {
            bad.add("rotateLegStartMs 必须是 AtomicLong（@Volatile 不保证读-清-加原子）")
        }
        if (Regex("@Volatile\\s+private var rotateLegStartMs").containsMatchIn(maskedCodec)) {
            bad.add("旧的 `@Volatile var` 形态不许复活（它是本缺陷的成因）")
        }
        return bad
    }

    /** 把 [settleRotateLegAtStop] 体内的某段文本换掉（不动另一处结算，避免锚串两处同时命中） */
    private fun mutateAtStop(src: String, from: String, to: String): String {
        val r = regionOf(src, "settleRotateLegAtStop")
        val body = src.substring(r.start, r.end)
        assertTrue("突变锚点没截到：$from", body.contains(from))
        return src.substring(0, r.start) + body.replace(from, to) + src.substring(r.end)
    }

    @Test
    fun `双端结算接线完整`() {
        val bad = legViolations(codec)
        assertEquals("双端结算接线发现断点：\n${bad.joinToString("\n")}", 0, bad.size)
    }

    @Test
    fun `尺子自己能红_接线突变体必报红`() {
        assertEquals("良品接线必须先绿", 0, legViolations(codec).size)
        val mutants = listOf(
            Triple(
                "控制线程退回裸读不清位（缺陷形态）",
                mutateAtStop(codec, "rotateLegStartMs.getAndSet(0L)", "rotateLegStartMs.get()"),
                true
            ),
            Triple("控制线程退回裸赋值清位", mutateAtStop(codec, "rotateLegStartMs.getAndSet(0L)", "rotateLegStartMs = 0L"), true),
            Triple("删掉控制线程那一笔 add", mutateAtStop(codec, "rotateLoss.add(lost)", "Unit"), true),
            Triple("泵线程退回裸读不清位", codec.replace("val start = rotateLegStartMs.getAndSet(0L)", "val start = rotateLegStartMs.get()"), true),
            Triple("AtomicLong 退回 @Volatile var", codec.replace("private val rotateLegStartMs = AtomicLong(0L)", "private var rotateLegStartMs = 0L"), true),
            // 对照组：只改注释不许引发红（尺子不许对无害改动过敏）
            Triple("对照组：只改注释", codec.replace("private val rotateLegStartMs = AtomicLong(0L)", "private val rotateLegStartMs = AtomicLong(0L) // 注释"), false)
        )
        mutants.forEach { (name, mutated, shouldBeRed) ->
            val red = legViolations(mutated)
            if (shouldBeRed) {
                assertTrue("突变体「$name」必须报红（实际违规：${red.joinToString("；")}）", red.isNotEmpty())
            } else {
                assertEquals("对照组「$name」不该报红（实际违规：${red.joinToString("；")}）", 0, red.size)
            }
        }
    }
}
