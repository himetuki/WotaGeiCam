package com.wotagei.cam.record

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
 * B06：逐样本异常只留首条明细、其余只计数；计数/最大量进段尾（或收尾）汇总；日志里只写
 * MediaStore 数字 id，不写完整 `content://` URI。
 *
 * 为什么用源码守卫而不是真机行为测试：PTS 兜底与写失败分支都在泵线程的 muxer 写点上，
 * JVM 侧没有可注入的 MediaMuxer 桩（见 MuxPtsMonotonicTest 的分层理由）；这里钉的正是
 * "日志是否还按帧刷"这条接线——纯函数表测不到它，删掉 `if (计数 == 1)` 之外全绿。
 */
class LogRateLimitGuardTest {

    private val recorder = codeOnly(mainSourceText("record/CodecRecorder.kt"))
    private val arc = codeOnly(mainSourceText("record/ArcRepairRunner.kt"))

    // ---- 录制链：负戳夹紧 / 真倒退 / 同格等值 ----

    @Test
    fun `负戳夹紧日志必须首条留明细其余只计数`() {
        val w = bodyOf(recorder, "writeSample")
        val start = w.indexOf("if (shifted < 0L)")
        assertTrue("锚点丢失：writeSample 没有偏移为负的夹紧判定", start >= 0)
        val end = w.indexOf("} else {", start)
        assertTrue("锚点丢失：夹紧判定没有配 else（正常路径必须与夹紧分开）", end > start)
        val branch = w.substring(start, end)
        assertTrue("夹紧必须计数（量级进段尾汇总）", branch.contains("muxPtsClampFixes++"))
        assertTrue("夹紧必须记最大提前量", branch.contains("muxPtsMaxEarlyUs"))
        assertTrue(
            "夹紧日志必须限速为首条（逐样本 w 正是本条要治的洪泛）",
            branch.contains("if (muxPtsClampFixes == 1)")
        )
        assertTrue("夹紧首条必须留 w 级明细（否则现场无从判断分段偏移）", branch.contains("Log.w("))
    }

    @Test
    fun `真倒退与同格等值日志都只留首条`() {
        val w = bodyOf(recorder, "writeSample")
        val eq = w.indexOf("if (info.presentationTimeUs == lastUs)")
        val elseAt = w.indexOf("} else {", eq)
        assertTrue("锚点丢失：等值分叉不存在", eq >= 0 && elseAt > eq)
        val eqBranch = w.substring(eq, elseAt)
        assertTrue("等值日志必须限速为第一枚", eqBranch.contains("if (muxPtsEqualFixes == 1)"))
        val backBranch = w.substring(elseAt)
        assertTrue("真倒退日志必须限速为第一枚（次数/最大量由段尾汇总给出）", backBranch.contains("if (muxPtsFixes == 1)"))
    }

    @Test
    fun `夹紧计数进段尾汇总并逐段复位`() {
        val seg = bodyOf(recorder, "runSegment")
        assertTrue("段尾汇总必须带夹紧次数", seg.contains("muxPtsClampFixes"))
        assertTrue("段尾汇总必须带最大提前量", seg.contains("muxPtsMaxEarlyUs"))
        val reset = bodyOf(recorder, "resetMuxPtsBooks")
        assertTrue("复位必须清夹紧计数（否则跨段串味）", reset.contains("muxPtsClampFixes = 0"))
        assertTrue("复位必须清最大提前量", reset.contains("muxPtsMaxEarlyUs = 0"))
    }

    // ---- 修复链：写样本失败（视频 + 每条音频 lane）----

    @Test
    fun `修复链写失败日志必须首条留异常明细其余只计数`() {
        val video = flatten(bodyOf(arc, "writeVideo"))
        assertTrue("视频写失败必须计数", video.contains("videoWriteFails++"))
        assertTrue("视频写失败日志必须限速为第一枚", video.contains("if (videoWriteFails == 1)"))
        assertTrue("视频写失败首条必须留 e 级异常明细", video.contains("Log.e("))
        val audio = flatten(bodyOf(arc, "drainUpTo"))
        assertTrue("音频写失败必须计数", audio.contains("audioWriteFails++"))
        assertTrue("音频写失败日志必须限速为第一枚", audio.contains("if (audioWriteFails == 1)"))
        assertTrue("音频写失败首条必须留 e 级异常明细", audio.contains("Log.e("))
    }

    @Test
    fun `修复链收尾必须给出写失败总数`() {
        val run = flatten(bodyOf(arc, "run"))
        assertTrue("收尾汇总必须包含视频写失败计数", run.contains("videoWriteFails"))
        assertTrue("收尾汇总必须包含音频写失败计数", run.contains("audioWriteFails"))
    }

    // ---- 媒体标识：只写数字 id，不写完整 URI ----

    @Test
    fun `录制侧媒体日志只写数字id不写完整URI`() {
        val raw = mainSourceText("record/VideoStore.kt")
        assertEquals(
            "插入/提交/废弃/封段/打开失败六处日志都必须经 mediaIdOf 收敛",
            6, occurrences(raw, "mediaIdOf").size
        )
        listOf(
            "uri=\$uri",                 // insert 返回后
            "uri=\${sink.uri}",          // discard 与打开失败
            "uri=\${pending.uri}",       // 提交失败
            "uri=\${pending?.uri}",      // 封段汇总
        ).forEach { leaked ->
            assertFalse("日志不得直接落完整 URI（B06）：$leaked", raw.contains(leaked))
        }
        assertTrue(
            "封段汇总必须写数字 id（pending 为 null 时回 none）",
            raw.contains("id=\${mediaIdOf(pending?.uri)} committed=\$committed")
        )
    }
}