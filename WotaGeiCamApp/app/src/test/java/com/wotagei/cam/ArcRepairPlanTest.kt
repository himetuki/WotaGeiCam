package com.wotagei.cam

import com.wotagei.cam.record.ArcOp
import com.wotagei.cam.record.ArcRepairFlow
import com.wotagei.cam.record.ArcRepairMerge
import com.wotagei.cam.record.ArcRepairPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 光弧修复 **v2 抽帧+补弧计划**的 JVM 全覆盖（用户 2026-10-03 定版口径，v1 插帧已废弃）。
 *
 * 本文件只钉纯逻辑层：保留集/帧数、取大算子、流式状态机的指令序列。解码/编码/EGL 只能在真机验
 * （MediaCodec、EGL14 在 JVM 里都不存在），那一半的证据来自 `ArcRepairRunner`/`ArcRepairGl`
 * 的日志与成片。
 *
 * 判据里既测"公式"也测"计划→行为"的桥（AGENTS 铁律）：[ArcRepairFlow] 是行为——引擎按它的
 * 指令序列执行；它与 [ArcRepairPlan.isKept]/`dstFrameCount` 必须自洽，否则发出帧数对不上进度
 * 分母、PTS 会错位。结尾整段枚举用例把 30→24 的每一帧逐个对账，专门防"删掉某个分支仍全绿"。
 */
class ArcRepairPlanTest {

    // region 帧数（保留集大小）

    @Test
    fun `空与负数源帧数输出为零`() {
        assertEquals(0, ArcRepairPlan.dstFrameCount(0, 30, 24))
        assertEquals(0, ArcRepairPlan.dstFrameCount(-7, 30, 24))
        assertEquals(0, ArcRepairPlan.of(-7, 30, 24).dstFrames)
    }

    @Test
    fun `30到24每5抽1_45帧出36帧`() {
        assertEquals(36, ArcRepairPlan.dstFrameCount(45, 30, 24))
        assertEquals(36, ArcRepairPlan.of(45, 30, 24).dstFrames)
        assertEquals(36, ArcRepairPlan.of(45, 30, 24).totalFrames)
    }

    @Test
    fun `30到25每6抽1`() {
        // ⌈45·25/30⌉ = ⌈37.5⌉ = 38：每 6 帧抽 1（丢 5,11,…），尾巴多保一枚
        assertEquals(38, ArcRepairPlan.dstFrameCount(45, 30, 25))
    }

    @Test
    fun `源不快于目标时恒等直通_没有帧可抽`() {
        assertEquals(20, ArcRepairPlan.dstFrameCount(20, 24, 24))
        assertEquals(20, ArcRepairPlan.dstFrameCount(20, 24, 25))
        assertEquals(20, ArcRepairPlan.dstFrameCount(20, 0, 24))
        for (s in 0 until 20) assertTrue(ArcRepairPlan.isKept(s, 24, 24))
    }

    @Test
    fun `输出时长与源恒等_30到24误差不足一帧`() {
        // 保留帧数/D ≈ 源帧数/S：|N·D − K·S| < S（45 帧：36·30=1080 = 45·24=1080 严格恒等）
        assertEquals(45 * 24, 36 * 30)
        for (n in 1..300) {
            val k = ArcRepairPlan.dstFrameCount(n, 30, 24)
            assertTrue("N=$n 时长账越界", kotlin.math.abs(n * 24 - k * 30) < 30)
        }
    }

    // endregion

    // region 保留集（isKept 与帧数互为正逆映射）

    @Test
    fun `30到24的抽帧点每5帧一个_不扎堆`() {
        val drops = (0 until 45).filter { !ArcRepairPlan.isKept(it, 30, 24) }
        assertEquals(listOf(4, 9, 14, 19, 24, 29, 34, 39, 44), drops)
    }

    @Test
    fun `30到25的抽帧点每6帧一个`() {
        val drops = (0 until 45).filter { !ArcRepairPlan.isKept(it, 30, 25) }
        assertEquals(listOf(5, 11, 17, 23, 29, 35, 41), drops)
    }

    @Test
    fun `60到24允许连续抽帧_留集仍与帧数自洽`() {
        val kept = (0 until 60).filter { ArcRepairPlan.isKept(it, 60, 24) }
        assertEquals(ArcRepairPlan.dstFrameCount(60, 60, 24), kept.size)
        // ⌊j·2.5⌋ 序列里有跳 2 的位次 ⇒ 必然出现连续抽帧（3,4 / 8,9 / …）
        val hasConsecutive = kept.zipWithNext().any { (a, b) -> b - a > 2 }
        assertTrue(hasConsecutive)
    }

    @Test
    fun `保留集等于输出下标的floor映射_正逆自洽`() {
        for (n in listOf(1, 2, 5, 45, 90, 301)) {
            val plan = ArcRepairPlan.of(n, 30, 24)
            val keptByMap = (0 until plan.dstFrames).map { j ->
                val s = (j.toLong() * 30 / 24).toInt()
                assertTrue("j=$j 映出的源下标越界（N=$n）", s < n)
                s
            }
            val keptByTest = (0 until n).filter { ArcRepairPlan.isKept(it, 30, 24) }
            assertEquals("N=$n 的两个保留集口径必须一致", keptByMap, keptByTest)
        }
    }

    @Test
    fun `负下标防御为不保留`() {
        assertFalse(ArcRepairPlan.isKept(-1, 30, 24))
    }

    // endregion

    // region 进度

    @Test
    fun `进度在边界与超界处被钳制`() {
        assertEquals(0f, ArcRepairPlan.progressOf(0, 36), 0f)
        assertEquals(0.5f, ArcRepairPlan.progressOf(18, 36), 1e-6f)
        assertEquals(1f, ArcRepairPlan.progressOf(36, 36), 0f)
        assertEquals(1f, ArcRepairPlan.progressOf(40, 36), 0f)
        assertEquals(0f, ArcRepairPlan.progressOf(-3, 10), 0f)
    }

    @Test
    fun `总帧数为0时进度归零而不是除零`() {
        assertEquals(0f, ArcRepairPlan.progressOf(0, 0), 0f)
        assertEquals(0f, ArcRepairPlan.progressOf(5, 0), 0f)
        assertEquals(0f, ArcRepairPlan.progressOf(5, -1), 0f)
    }

    // endregion

    // region 取大合并算子

    private fun b(v: Int): Byte = v.toByte()

    @Test
    fun `取大按无符号比_0x80不当负数`() {
        val acc = byteArrayOf(b(0x10), b(0x80), b(0x7F))
        ArcRepairMerge.mergeMax(acc, byteArrayOf(b(0x20), b(0x7F), b(0x80)), 3)
        // 0x80(128) > 0x7F(127)：结果 0x80 必须赢，而不是按有符号被 0x7F 反超
        assertEquals(b(0x20), acc[0])
        assertEquals(b(0x80), acc[1])
        assertEquals(b(0x80), acc[2])
    }

    @Test
    fun `黑帧并入是恒等_不改动保留帧`() {
        val acc = byteArrayOf(1, 2, 3, 4)
        ArcRepairMerge.mergeMax(acc, ByteArray(4), 4)
        assertEquals(byteArrayOf(1, 2, 3, 4).toList(), acc.toList())
    }

    @Test
    fun `并集语义_两次取大等于三分最大`() {
        val acc = byteArrayOf(10, 0, b(200))
        ArcRepairMerge.mergeMax(acc, byteArrayOf(0, 30, 50), 3)
        ArcRepairMerge.mergeMax(acc, byteArrayOf(5, 5, 5), 3)
        assertEquals(listOf(10, 30, 200), acc.map { it.toInt() and 0xFF })
    }

    @Test
    fun `len边界与就地可用`() {
        val acc = byteArrayOf(9, 9, 9, 9)
        ArcRepairMerge.mergeMax(acc, acc, 4)   // acc 与 src 同一数组（就地）
        assertEquals(byteArrayOf(9, 9, 9, 9).toList(), acc.toList())
        val src = byteArrayOf(1, 99, 1, 1)
        ArcRepairMerge.mergeMax(acc, src, 2)   // len 只作用前两格
        assertEquals(byteArrayOf(9, 99, 9, 9).toList(), acc.toList())
    }

    // endregion

    // region 流式状态机（计划→行为的桥）

    /** 按 30→24/N=45 完整喂一遍流，返回 (emitted pts 列表, 全部指令) */
    private fun feed30to24(): Pair<List<Long>, List<ArcOp>> {
        val flow = ArcRepairFlow(ArcRepairPlan.of(45, 30, 24), dstFps = 24)
        val ops = mutableListOf<ArcOp>()
        for (s in 0 until 45) ops += flow.onSourceFrame(s)
        ops += flow.onSourceEos()
        val pts = ops.filterIsInstance<ArcOp.EmitPending>().map { it.ptsUs }
        return pts to ops
    }

    @Test
    fun `桥_发出帧数等于计划帧数`() {
        val (pts, _) = feed30to24()
        assertEquals(ArcRepairPlan.dstFrameCount(45, 30, 24), pts.size)
        assertEquals(36, pts.size)
    }

    @Test
    fun `桥_PTS为均匀目标帧率步长_严格递增`() {
        val (pts, _) = feed30to24()
        pts.forEachIndexed { j, p -> assertEquals(j.toLong() * 1_000_000L / 24, p) }
        assertTrue(pts.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `桥_滞留帧恰好发出一次且EOS后清零`() {
        val (_, ops) = feed30to24()
        val holds = ops.filterIsInstance<ArcOp.HoldCurAsPending>().size
        val merges = ops.filterIsInstance<ArcOp.MergeCurAsPending>().size
        val emits = ops.filterIsInstance<ArcOp.EmitPending>().size
        // 首枚保留帧 Hold 一次；其后每枚保留帧经 Merge 升格；Hold+Merge == Emit == 36
        assertEquals(1, holds)
        assertEquals(35, merges)
        assertEquals(emits, holds + merges)
        // EOS 后状态机清零：再问一次只会得到空指令
        val flow = ArcRepairFlow(ArcRepairPlan.of(45, 30, 24), 24)
        for (s in 0 until 45) flow.onSourceFrame(s)
        flow.onSourceEos()
        assertTrue(flow.onSourceEos().isEmpty())
    }

    @Test
    fun `桥_被抽帧并进前后两保留帧_合并计数对账`() {
        val (_, ops) = feed30to24()
        // 30→24 抽 9 枚，前 8 枚各夹在两保留帧之间 ⇒ 各 1 次 Accumulate(fresh)、1 次 Fold（并前帧）、
        // 1 次 useAcc 的 Merge（并后帧）；最后一枚（源 44）后面没有保留帧，它的补弧由 EOS 的
        // Fold 收尾 ⇒ Fold 总数 9、useAcc 的 Merge 只有 8
        val accs = ops.filterIsInstance<ArcOp.AccumulateCur>()
        assertEquals(9, accs.size)
        assertTrue(accs.all { it.fresh })
        assertEquals(9, ops.count { it is ArcOp.FoldAccIntoPending })
        assertEquals(8, ops.count { it is ArcOp.MergeCurAsPending && it.useAcc })
    }

    @Test
    fun `桥_连续抽帧时第二枚不fresh_不清残留也不漏累积`() {
        val flow = ArcRepairFlow(ArcRepairPlan.of(60, 60, 24), 24)
        val accs = mutableListOf<ArcOp.AccumulateCur>()
        for (s in 0 until 60) accs += flow.onSourceFrame(s).filterIsInstance<ArcOp.AccumulateCur>()
        // 60→24 的连续抽帧对（源 3,4 等）：第一枚 fresh 清残留、第二枚接着取大
        assertTrue(accs.first().fresh)
        val consecutive = accs.zipWithNext().filter { (a, b) -> a.fresh && !b.fresh }
        assertTrue("60→24 必有连续抽帧的 fresh→非fresh 序列", consecutive.isNotEmpty())
    }

    @Test
    fun `桥_EOS尾巴上的被抽帧并进最后一枚保留帧`() {
        // 30→24/N=45：最后一枚源（44）恰是抽帧位次 ⇒ EOS 指令 = [Fold, Emit(最后一枚的PTS)]
        // （35 = 已发出的前 35 枚，待发的那枚正是输出第 35 位）
        val flow = ArcRepairFlow(ArcRepairPlan.of(45, 30, 24), 24)
        for (s in 0 until 45) flow.onSourceFrame(s)
        val tail = flow.onSourceEos()
        assertEquals(
            listOf<ArcOp>(ArcOp.FoldAccIntoPending, ArcOp.EmitPending(35 * 1_000_000L / 24)),
            tail
        )
    }

    @Test
    fun `桥_首保留帧之前的被抽帧直接弃_空流不炸`() {
        val flow = ArcRepairFlow(ArcRepairPlan.of(45, 30, 24), 24)
        // -1 是防御位（计划里负下标不保留），此时还没有滞留帧 ⇒ 空指令
        assertTrue(flow.onSourceFrame(-1).isEmpty())
        // 空流 EOS：没有滞留帧就没有输出
        assertTrue(ArcRepairFlow(ArcRepairPlan.of(0, 30, 24), 24).onSourceEos().isEmpty())
    }

    /** 整段枚举：对 30→24/N=45 的每一帧逐个对账（防"删掉某个分支仍全绿"） */
    @Test
    fun `整段枚举_45帧逐帧对账`() {
        val flow = ArcRepairFlow(ArcRepairPlan.of(45, 30, 24), 24)
        var emitted = 0
        var pending = false
        var hasAcc = false
        for (s in 0 until 45) {
            for (op in flow.onSourceFrame(s)) {
                when (op) {
                    is ArcOp.AccumulateCur -> {
                        assertTrue("Accumulate 必在滞留之后", pending)
                        assertEquals("fresh 口径：acc 空时才 fresh", !hasAcc, op.fresh)
                        hasAcc = true
                    }
                    is ArcOp.FoldAccIntoPending -> assertTrue("Fold 必伴随未消费的 acc", hasAcc)
                    is ArcOp.HoldCurAsPending -> {
                        assertFalse("Hold 只发生在首滞留", pending)
                        pending = true
                    }
                    is ArcOp.MergeCurAsPending -> {
                        assertTrue("Merge 只发生在已有滞留", pending)
                        assertEquals(hasAcc, op.useAcc)
                        hasAcc = false
                    }
                    is ArcOp.EmitPending -> {
                        assertTrue(pending)
                        assertEquals("PTS 与发出序号绑定", emitted.toLong() * 1_000_000L / 24, op.ptsUs)
                        emitted++
                    }
                }
            }
        }
        val tail = flow.onSourceEos()
        assertTrue(tail.any { it is ArcOp.EmitPending })
        emitted += tail.count { it is ArcOp.EmitPending }
        assertEquals(ArcRepairPlan.dstFrameCount(45, 30, 24), emitted)
    }

    // endregion
}
