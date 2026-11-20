package com.wotagei.cam

import com.wotagei.cam.record.arcEncSampleWritable
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Y24 编码器输出侧的**末帧保留**（真值表 + 排空模型 + 接线钉扎 + 突变）。
 *
 * 缺陷形态：排空循环把 `!eos` 写进了"要不要写样本"的条件。平台会把最后一枚编码帧与
 * `BUFFER_FLAG_END_OF_STREAM` 合在同一枚输出缓冲，于是末帧被 release 掉——成片比抽帧计划
 * 少一帧、尾部补弧被削，而音频轨已推到源末尾，容器时长/音画尾巴对不上。
 *
 * 判定必须与 EOS 解耦：**有负载且非配置帧 → 先写，再交接 EOS**。这里用一枚与 drainEncoder
 * 同序的模型把"先写后交接"跑成行为断言，再钉住源码里实际调用的纯判据；`突变_*` 把旧条件
 * 写回源码文本，证明钉扎不是恒绿。
 */
class ArcRepairEncoderTailTest {

    /** 一枚编码器输出的最小模型（drainEncoder 的 idx >= 0 分支只看这三样） */
    private data class EncOut(val size: Int, val codecConfig: Boolean = false, val eos: Boolean = false)

    /** 与 drainEncoder 同序：先按纯判据写样本，再 release，最后交接 EOS */
    private fun drainModel(bufs: List<EncOut>): Pair<Int, Boolean> {
        var written = 0
        var videoDone = false
        for (b in bufs) {
            if (arcEncSampleWritable(b.size, b.codecConfig)) written++
            if (b.eos) {
                videoDone = true
                break
            }
        }
        return written to videoDone
    }

    /** 源码接线违规项（空表 = 接线正确）；抽出来也供突变体复用 */
    private fun violations(raw: String): List<String> {
        val src = codeOnly(raw)
        val drain = bodyOf(src, "drainEncoder")
        val write = bodyOf(src, "writeVideo")
        val bad = ArrayList<String>()
        if (!drain.contains("arcEncSampleWritable(")) {
            bad.add("写样本必须经纯判据 arcEncSampleWritable")
        }
        if (drain.contains("!eos")) {
            bad.add("写条件不得再出现 !eos（会吞掉与 EOS 合帧的末帧）")
        }
        val writeAt = drain.indexOf("arcEncSampleWritable(")
        val releaseAt = drain.indexOf("releaseOutputBuffer")
        val eosAt = drain.indexOf("if (eos) {")
        if (writeAt < 0) {
            bad.add("缺少写样本的调用点")
        } else {
            if (releaseAt <= writeAt) bad.add("写样本必须在 releaseOutputBuffer 之前（缓冲先写后还）")
            if (eosAt <= releaseAt) bad.add("EOS 交接必须在写出之后（与解码侧 mainLoop 同序）")
        }
        if (!drain.contains("videoDone = true")) bad.add("EOS 仍要收尾（videoDone）")
        if (!write.contains("BUFFER_FLAG_END_OF_STREAM.inv()")) {
            bad.add("与 EOS 合帧的样本入户前必须剥掉 EOS 位（它不是样本属性）")
        }
        if (!write.contains("vBufInfo.set") || !write.contains("bi.flags")) {
            bad.add("关键帧位仍须随样本走（vBufInfo.set 吃 bi.flags）")
        }
        return bad
    }

    @Test
    fun `真值表_写不写只看负载与配置帧_与EOS无关`() {
        // Y24 旧条件写作 `!eos && !cfg && size > 0`：把 EOS 当成"不写"的理由。
        // 新判据的入参里**根本没有 eos**——与 EOS 合帧的末帧（size>0、cfg=false）照样写。
        assertTrue("普通帧要写", arcEncSampleWritable(sizeBytes = 2048, codecConfig = false))
        assertFalse("零长度 EOS 没有载荷，不写", arcEncSampleWritable(sizeBytes = 0, codecConfig = false))
        assertFalse(
            "配置帧（csd）已随 outputFormat 进 addTrack，不能再当样本写",
            arcEncSampleWritable(sizeBytes = 4096, codecConfig = true)
        )
        assertFalse("零长度配置帧同样不写", arcEncSampleWritable(sizeBytes = 0, codecConfig = true))
    }

    @Test
    fun `排空模型_末帧与EOS合枚时末帧先写出再收尾`() {
        // 计划内的 3 枚保留帧，平台把最后一枚与 EOS 合并
        val bufs = listOf(
            EncOut(2048),
            EncOut(2048),
            EncOut(2048, eos = true)
        )
        val (written, done) = drainModel(bufs)
        assertEquals("计划内的末帧必须写出去（旧条件只写 2 枚）", 3, written)
        assertTrue("写完末帧之后才交接 EOS", done)
    }

    @Test
    fun `排空模型_纯EOS与配置帧都不占样本`() {
        val tailOnly = drainModel(listOf(EncOut(2048), EncOut(0, eos = true)))
        assertEquals("零长度 EOS 不写样本", 1, tailOnly.first)
        assertTrue("零长度 EOS 仍算排空收尾", tailOnly.second)

        val withCsd = drainModel(listOf(EncOut(4096, codecConfig = true), EncOut(2048), EncOut(0, eos = true)))
        assertEquals("配置帧不得当样本写", 1, withCsd.first)
        assertTrue("配置帧之后仍按 EOS 收尾", withCsd.second)
    }

    @Test
    fun `源码钉扎_写条件已去掉EOS且先写载荷再release交接`() {
        val raw = mainSourceText("record/ArcRepairRunner.kt")
        assertEquals("编码输出侧接线断点：\n${violations(raw).joinToString("\n")}", emptyList<String>(), violations(raw))
    }

    @Test
    fun `突变_写条件退回 !eos 必红`() {
        val raw = mainSourceText("record/ArcRepairRunner.kt")
        assertEquals("良品必须先真的绿", 0, violations(raw).size)
        // 突变：把写条件改回旧实现（EOS 与末帧合枚时吞掉末帧）
        val mutant = raw.replace(
            "if (muxStarted && arcEncSampleWritable(bi.size, cfg)) writeVideo(enc, idx, bi)",
            "if (muxStarted && !eos && !cfg && bi.size > 0) writeVideo(enc, idx, bi)"
        )
        assertTrue("退回 !eos 的突变体必须报红", violations(mutant).isNotEmpty())
    }

    @Test
    fun `突变_入户前不剥EOS位必红`() {
        val raw = mainSourceText("record/ArcRepairRunner.kt")
        val mutant = raw.replace("bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()", "bi.flags")
        assertTrue("把 EOS 位带进样本的突变体必须报红", violations(mutant).isNotEmpty())
    }
}