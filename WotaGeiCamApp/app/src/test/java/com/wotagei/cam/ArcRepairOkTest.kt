package com.wotagei.cam

import com.wotagei.cam.record.arcRepairOk
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 光弧修复成功判据（[com.wotagei.cam.record.arcRepairOk]）的纯函数全表 + 接线守卫。
 *
 * 背景（缺陷 2）：修复期间存储写满时 `writeSampleData` 逐样本抛错被吞，moov 没写出 ⇒ 成片只剩
 * ftyp 头；旧判据 `muxStarted && videoTrack >= 0` 对这份废片照样报成功并 commit 进相册。
 * 新判据补第三格"写出的视频样本数 > 0"，只拦**零样本整废**，不把"可播但缺帧"改判失败（代价不对称）。
 *
 * 期望值一律手写字面量。突变：把 `> 0` 改成 `>= 0` → 「零样本必须失败」用例必红；
 * 把判据退回两格（删样本参数）→ 编译失败，同样拦得住回潮。
 */
class ArcRepairOkTest {

    @Test
    fun `三格全真且有样本才算成功`() {
        assertTrue("标准成功形态", arcRepairOk(muxStarted = true, videoTrackAdded = true, writtenVideoSamples = 1))
        assertTrue("多样本成功", arcRepairOk(muxStarted = true, videoTrackAdded = true, writtenVideoSamples = 1_000))
    }

    @Test
    fun `零样本整废必须失败`() {
        // 盘满形态：muxer 启动了、轨也建了，但一个样本都没写出去（成片只有 ftyp 头）
        assertFalse("muxer 启动 + 有轨 + 零样本 ⇒ 失败", arcRepairOk(muxStarted = true, videoTrackAdded = true, writtenVideoSamples = 0))
    }

    @Test
    fun `前两格任一为假即失败`() {
        assertFalse("muxer 没 start", arcRepairOk(muxStarted = false, videoTrackAdded = true, writtenVideoSamples = 100))
        assertFalse("视频轨没建", arcRepairOk(muxStarted = true, videoTrackAdded = false, writtenVideoSamples = 100))
        assertFalse("全假", arcRepairOk(muxStarted = false, videoTrackAdded = false, writtenVideoSamples = 0))
    }

    @Test
    fun `负样本数不算写出过`() {
        // 防御：计数器初值/异常路径污染成负数时同样不放行
        assertFalse(arcRepairOk(muxStarted = true, videoTrackAdded = true, writtenVideoSamples = -1))
    }

    /**
     * 接线守卫：判据必须由 `run` 收尾统一调用（不许旁路回写两格判据），写失败必须留 MUX 失败码，
     * `finish` 必须按 ok 决定 keep（keep=false 只回收新 pending 的 sink，源片本就不在这条路上）。
     */
    @Test
    fun `修复会话必须接 arcRepairOk 判据且写失败留 MUX 码`() {
        val src = codeOnly(mainSourceText("record/ArcRepairRunner.kt"))
        val run = bodyOf(src, "run")
        assertTrue("run 收尾必须调 arcRepairOk（成功判据唯一入口）", run.contains("arcRepairOk("))
        assertTrue("零样本时的失败码必须优先取 writeFailCode（归因到 MUX 而非误报 NO_VIDEO）", run.contains("writeFailCode"))

        val wv = bodyOf(src, "writeVideo")
        assertTrue("视频样本必须写成功才计数", wv.contains("writtenVideoSamples++"))
        assertTrue("视频写失败必须记 writeFailCode（ENGINE:MUX）", wv.contains("writeFailCode") && wv.contains("ArcRepairError.ENGINE"))

        val da = bodyOf(src, "drainAudioUpTo")
        assertTrue("音频写失败也要留 MUX 失败码（音轨整废时归因不误报）", da.contains("writeFailCode"))

        val fin = bodyOf(src, "finish")
        assertTrue("finish 必须按 ok 决定 keep（keep=false 只回收新输出，不动源片）", fin.contains("keep = ok"))
    }
}
