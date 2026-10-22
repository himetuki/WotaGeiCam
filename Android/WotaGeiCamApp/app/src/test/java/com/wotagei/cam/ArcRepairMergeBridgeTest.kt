package com.wotagei.cam

import com.wotagei.cam.record.ArcOp
import com.wotagei.cam.record.ArcRepairFlow
import com.wotagei.cam.record.ArcRepairMerge
import com.wotagei.cam.record.ArcRepairPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 补弧**流式桥**的计划→行为对账（60→24 连续抽帧，差分死区 D=2；帧间 Y 步进 4 保证
 * 连续 dropped 与 fold 都会真的过门）。
 *
 * 三个原语 [ArcOp.AccumulateCur] / [ArcOp.FoldAccIntoPending] / [ArcOp.MergeCurAsPending]
 * 在连续抽帧下会依次改写 acc / pending / cur。判据不重抄生产实现，而是**逐帧枚举**一份
 * 独立参考：对每对相邻保留帧，把中间的 dropped 帧按同一选择式折进 acc，再分别并进前/后
 * 保留帧；EOS 尾段单独折叠。参考与桥的输出必须逐帧完全相等。
 *
 * 敏感性自证：同时给出"完全忽略 acc"的坏参考，它必须与桥的输出不同——否则本用例无法
 * 区分"真的折叠了被抽帧"和"每帧原样发出"。
 */
class ArcRepairMergeBridgeTest {

    private data class Block(val y: List<Int>, val u: Int, val v: Int)

    private val deadband = 2

    /** 用生产算子对两枚 2×2 块做选择（与 CPU 路线同一入口） */
    private fun select(a: Block, b: Block): Block {
        require(a.y.size == 4 && b.y.size == 4)
        val aY = ByteArray(4) { a.y[it].toByte() }
        val bY = ByteArray(4) { b.y[it].toByte() }
        val aU = byteArrayOf(a.u.toByte())
        val bU = byteArrayOf(b.u.toByte())
        val aV = byteArrayOf(a.v.toByte())
        val bV = byteArrayOf(b.v.toByte())
        ArcRepairMerge.selectInPlace(aY, aU, aV, bY, bU, bV, 2, 2, deadband)
        return Block(
            listOf(aY[0].toInt() and 0xFF, aY[1].toInt() and 0xFF,
                aY[2].toInt() and 0xFF, aY[3].toInt() and 0xFF),
            aU[0].toInt() and 0xFF,
            aV[0].toInt() and 0xFF
        )
    }

    /**
     * 60 帧光源：保留帧按 i*4 递增；**两枚被抽帧里放一枚高于两侧保留帧的亮尖峰**（Y=255）。
     * 单调 i*4 时被抽帧的 luma 永远夹在前后保留帧之间，`select(cur, acc)` 恒为 cur ⇒
     * “后保留帧吃弧”分支等于空操作；尖峰逼出真正的 b→a 选择。
     */
    private fun frames(n: Int): List<Block> {
        val kept = (0 until n).filter { ArcRepairPlan.isKept(it, 60, 24) }
        val spikeFrom = (kept.getOrNull(1) ?: 0) + 1
        val spikeUntil = kept.getOrNull(2) ?: n
        return (0 until n).map { i ->
            val y = if (i in spikeFrom until spikeUntil) 250 else i * 4
            Block(listOf(y, y + 1, y, y + 1), (i * 11 + 7) % 256, (i * 23 + 3) % 256)
        }
    }

    /** 把 [from, until) 的 dropped 帧逐枚折进 acc（首枚 fresh，其余 select(acc, frame)） */
    private fun foldDropped(all: List<Block>, from: Int, until: Int): Block? {
        var acc: Block? = null
        for (i in from until until) {
            acc = if (acc == null) all[i] else select(acc, all[i])
        }
        return acc
    }

    /** 独立参考：按保留集逐对枚举，不依赖 ArcRepairFlow 的指令序列 */
    private fun reference(all: List<Block>): List<Block> {
        val kept = all.indices.filter { ArcRepairPlan.isKept(it, 60, 24) }
        if (kept.isEmpty()) return emptyList()
        val out = ArrayList<Block>(kept.size)
        var pending = all[kept[0]]
        for (k in 1 until kept.size) {
            val acc = foldDropped(all, kept[k - 1] + 1, kept[k])
            if (acc == null) {
                out += pending
                pending = all[kept[k]]
            } else {
                // 前保留帧吃弧后发出；后保留帧同一 acc 升格为新的 pending
                out += select(pending, acc)
                pending = select(all[kept[k]], acc)
            }
        }
        val tail = foldDropped(all, kept.last() + 1, all.size)
        out += if (tail == null) pending else select(pending, tail)
        return out
    }

    /** 按生产 ArcRepairFlow 的指令序列驱动三原语（与 ArcRepairRunner.execCpu 同构） */
    private fun runFlow(all: List<Block>): List<Block> {
        val flow = ArcRepairFlow(ArcRepairPlan.of(all.size, 60, 24))
        var acc: Block? = null
        var pending: Block? = null
        val emitted = ArrayList<Block>()
        for (s in all.indices) {
            for (op in flow.onSourceFrame(s)) {
                when (op) {
                    is ArcOp.AccumulateCur ->
                        acc = if (op.fresh) all[s] else select(acc!!, all[s])
                    is ArcOp.FoldAccIntoPending -> pending = select(pending!!, acc!!)
                    is ArcOp.HoldCurAsPending -> pending = all[s]
                    is ArcOp.MergeCurAsPending ->
                        pending = if (op.useAcc) select(all[s], acc!!) else all[s]
                    is ArcOp.EmitPending -> emitted += pending!!
                }
            }
        }
        for (op in flow.onSourceEos()) {
            when (op) {
                is ArcOp.FoldAccIntoPending -> pending = select(pending!!, acc!!)
                is ArcOp.EmitPending -> emitted += pending!!
                else -> error("EOS 不该出现 $op")
            }
        }
        return emitted
    }

    @Test
    fun `连续抽帧桥_三原语结果与逐帧枚举参考完全一致`() {
        val all = frames(60)
        val fromFlow = runFlow(all)
        val expected = reference(all)
        assertEquals("输出帧数必须等于保留集大小", expected.size, fromFlow.size)
        assertEquals("60→24 的保留帧数", ArcRepairPlan.dstFrameCount(60, 60, 24), fromFlow.size)
        assertEquals("桥输出必须与逐帧枚举参考逐帧相等", expected, fromFlow)
        // “后保留帧吃弧”不是空操作：尖峰位于两枚保留帧之间的被抽帧里，前后两枚保留帧
        // 的输出都必须变成该尖峰（select(cur, acc) 的 b 侧真的被选走）。
        val kept = all.indices.filter { ArcRepairPlan.isKept(it, 60, 24) }
        val spikeAt = kept.getOrNull(1)!! + 1
        assertTrue("尖峰必须落在被抽帧里（构造前提）", !ArcRepairPlan.isKept(spikeAt, 60, 24))
        assertEquals("前保留帧必须整块吃进尖峰", all[spikeAt], fromFlow[1])
        assertEquals("后保留帧必须整块吃进尖峰", all[spikeAt], fromFlow[2])
        // 敏感性：完全忽略 acc 的坏参考必须与桥不同（否则本测试证明不了折叠真的发生）
        val ignoredAcc = kept.map { all[it] }
        assertNotEquals("忽略被抽帧的坏参考必须与桥不同", ignoredAcc, fromFlow)
        assertTrue("至少一帧必须因折叠被抽帧而发生整像素变化", fromFlow.indices.any { fromFlow[it] != all[kept[it]] })
    }
}
