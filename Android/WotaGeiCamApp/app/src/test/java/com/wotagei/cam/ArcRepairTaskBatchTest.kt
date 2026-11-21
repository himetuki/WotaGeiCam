package com.wotagei.cam

import com.wotagei.cam.record.ArcProgressStage
import com.wotagei.cam.record.ArcRepairError
import com.wotagei.cam.record.ArcRepairTaskGate
import com.wotagei.cam.record.arcAbortReason
import com.wotagei.cam.record.arcAudioHasWritable
import com.wotagei.cam.record.arcAudioOutPtsUs
import com.wotagei.cam.record.arcRepairProgress
import com.wotagei.cam.record.arcRunVerdict
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Y23/Y24/Y25 补弧执行端批的**桥测**（纯函数全表 + 接线守卫）。
 *
 * 这三张卡共同的形态：判据从「消息体内部的状态位」改成「可单测的纯函数 + 唯一出口」——
 * 所有权/取消（Y23）、EOS 与排空结果（Y24）、音频预扫与拷贝共用判据（Y25）。守卫钉住调用点，
 * 纯函数钉住真值表；单独改坏任一侧都会红。
 */
class ArcRepairTaskBatchTest {

    // region Y23 所有权/取消/进度

    @Test
    fun `连续启动两次只领到一轮所有权`() {
        val gate = ArcRepairTaskGate()
        val first = gate.begin()
        assertNotNull("首轮应领到 token", first)
        assertNull("第二轮必须被拒绝（不再排队）", gate.begin())
        assertNull("第三轮同样拒绝", gate.begin())
        assertTrue("首轮仍是当前轮", gate.isCurrent(first!!))
        gate.end(first)
        val second = gate.begin()
        assertNotNull("收尾后才能领下一轮", second)
        assertNotEquals("新一轮必须是新 token（不复用旧取消记账）", first, second)
    }

    @Test
    fun `取消只作用于发起它的那一轮`() {
        val gate = ArcRepairTaskGate()
        val t1 = gate.begin()!!
        gate.cancel(t1)
        assertTrue("当前轮取消立即生效", gate.isCancelled(t1))
        gate.end(t1)
        val t2 = gate.begin()!!
        assertFalse("下一轮不得继承上一轮的取消", gate.isCancelled(t2))
        assertFalse("未知 token 一律未取消", gate.isCancelled(9_999L))
    }

    @Test
    fun `准备期取消优先于超时`() {
        assertEquals(
            "取消必须立即给 CANCELLED（不看截止）",
            ArcRepairError.CANCELLED,
            arcAbortReason(cancelled = true, nowMs = 1L, deadlineMs = 10_000_000L)
        )
        assertNull("未取消且未到期可继续", arcAbortReason(cancelled = false, nowMs = 5L, deadlineMs = 5L))
        assertEquals(
            "超过截止给 TIMEOUT",
            ArcRepairError.TIMEOUT,
            arcAbortReason(cancelled = false, nowMs = 6L, deadlineMs = 5L)
        )
    }

    @Test
    fun `封口前进度永远到不了百分之百`() {
        val total = 240
        val processFull = arcRepairProgress(ArcProgressStage.PROCESS, total, total)
        assertTrue("处理期封顶 0.95", processFull > 0.9f && processFull < 1f)
        assertEquals("准备期 0.02", 0.02f, arcRepairProgress(ArcProgressStage.PREPARE, 0, 0), 0.0001f)
        assertTrue("收尾期 0.98 也小于 1", arcRepairProgress(ArcProgressStage.FINALIZE, 0, 0) < 1f)
        assertEquals("只有发布成功才是 1.0", 1f, arcRepairProgress(ArcProgressStage.PUBLISHED, 0, 0), 0f)
        assertEquals("总帧数为 0 时处理期不越界", 0.02f, arcRepairProgress(ArcProgressStage.PROCESS, 0, 0), 0.0001f)
    }

    // endregion

    // region Y24 结果裁决

    @Test
    fun `编码器排空失败把结果从纯成功里剔出去`() {
        val clean = arcRunVerdict(ok = true, encodeFail = null, writeFail = null, fallback = ArcRepairError.NO_VIDEO)
        assertTrue("三格通过且排空无失败才算成功", clean.ok)
        assertNull("成功不带失败码", clean.code)

        val timedOut = arcRunVerdict(
            ok = true,
            encodeFail = ArcRepairError.TIMEOUT,
            writeFail = null,
            fallback = ArcRepairError.NO_VIDEO
        )
        assertFalse("末次排空超时不得报纯成功", timedOut.ok)
        assertEquals("失败码归因排空超时", ArcRepairError.TIMEOUT, timedOut.code)

        val dequeue = arcRunVerdict(
            ok = true,
            encodeFail = "${ArcRepairError.ENGINE}:ENC",
            writeFail = "${ArcRepairError.ENGINE}:MUX",
            fallback = ArcRepairError.NO_VIDEO
        )
        assertFalse(dequeue.ok)
        assertEquals("编码器失败码优先于写样本失败", "${ArcRepairError.ENGINE}:ENC", dequeue.code)

        val zero = arcRunVerdict(ok = false, encodeFail = null, writeFail = null, fallback = ArcRepairError.NO_VIDEO)
        assertFalse(zero.ok)
        assertEquals("零样本落回既有兜底码", ArcRepairError.NO_VIDEO, zero.code)
    }

    @Test
    fun `源码钉扎_带负载的EOS先处理再交接`() {
        val src = codeOnly(mainSourceText("record/ArcRepairRunner.kt"))
        val loop = bodyOf(src, "mainLoop")
        val payload = loop.indexOf("decInfo.size > 0")
        val eosFlush = loop.indexOf("flow.onSourceEos")
        assertTrue("mainLoop 必须显式判 payload（不能只判 eos）", payload >= 0)
        assertTrue("mainLoop 必须调用 onSourceEos 收尾", eosFlush >= 0)
        assertTrue(
            "EOS 带 payload 时必须先处理载荷再交接 EOS（旧实现先走 eos 分支丢末帧）",
            payload < eosFlush
        )
        assertTrue("带 payload 的分支必须走 handleDecodedFrame", loop.contains("handleDecodedFrame("))
    }

    @Test
    fun `源码钉扎_排空异常与超时都写失败码`() {
        val src = codeOnly(mainSourceText("record/ArcRepairRunner.kt"))
        val drain = bodyOf(src, "drainEncoder")
        assertTrue("出队异常必须记 encodeFailCode", drain.contains("encodeFailCode"))
        assertTrue("排空超时也必须记 encodeFailCode", drain.contains("ArcRepairError.TIMEOUT"))
        val run = bodyOf(src, "run")
        assertTrue("run 收尾必须经 arcRunVerdict 合成", run.contains("arcRunVerdict("))
    }

    // endregion

    // region Y25 音轨时基与可写判据

    @Test
    fun `只有视频起点前短样本的音轨不建轨`() {
        // 视频首帧 base=1s，内录整体后移（shift=+0.2s）：样本全在 0.9~0.95s，平移后仍早于 base
        val samples = listOf(900_000L, 950_000L)
        assertFalse("没有一枚能落进 [0, …] ⇒ 不得建零样本轨", arcAudioHasWritable(samples, 1_000_000L, 200_000L))
        assertTrue("越过 base 的短样本才算可写", arcAudioHasWritable(samples + 1_200_000L, 1_000_000L, 200_000L))
        // 平移为负（内录提前）时，起点附近的样本同样按同一判据排除
        assertFalse(arcAudioHasWritable(listOf(0L, 50_000L), 0L, -100_000L))
        assertTrue(arcAudioHasWritable(listOf(150_000L), 0L, -100_000L))
    }

    @Test
    fun `预扫与拷贝共用同一个输出PTS判据`() {
        val cases = listOf(
            Triple(900_000L, 1_000_000L, 200_000L),
            Triple(1_200_000L, 1_000_000L, 200_000L),
            Triple(0L, 0L, -100_000L),
            Triple(150_000L, 0L, -100_000L),
            Triple(-300_000L, 0L, 400_000L)
        )
        for ((sample, base, shift) in cases) {
            val out = arcAudioOutPtsUs(sample, base, shift)
            val writable = arcAudioHasWritable(listOf(sample), base, shift)
            assertEquals("单样本序列必须与输出 PTS 判据同口径：$sample/$base/$shift", out >= 0L, writable)
        }
        assertEquals("平移后的输出 PTS 就是返回值", 400_000L, arcAudioOutPtsUs(1_200_000L, 1_000_000L, 200_000L))
    }

    @Test
    fun `源码钉扎_音频建轨延迟到首帧基准确定且共用判据`() {
        val src = codeOnly(mainSourceText("record/ArcRepairRunner.kt"))
        val openLanes = bodyOf(src, "openAudioLanes")
        assertFalse("建 lane 不得再提前 addTrack", openLanes.contains("addTrack("))
        assertFalse("建 lane 不得再自己预扫可写性（时基未定）", openLanes.contains("hasWritableSample("))
        val startMux = bodyOf(src, "startMuxerIfReady")
        assertTrue("muxer.start 前必须验收音频轨", startMux.contains("addAudioTracksIfReady("))
        val add = bodyOf(src, "addAudioTracksIfReady")
        assertTrue("建轨前必须用 hasWritableSample 验收", add.contains("hasWritableSample("))
        assertTrue("验收必须用拷贝同一时基 decodePtsBase", add.contains("decodePtsBase"))
        val prescan = bodyOf(src, "hasWritableSample")
        assertTrue("预扫必须走 arcAudioOutPtsUs", prescan.contains("arcAudioOutPtsUs("))
        val drain = bodyOf(src, "drainUpTo")
        assertTrue("拷贝必须走同一个 arcAudioOutPtsUs", drain.contains("arcAudioOutPtsUs("))

        val open = bodyOf(src, "open")
        assertTrue("帧率预扫 EOF 认样本存在性（负 PTS 前导合法）", open.contains("videoEx.sampleSize >= 0L"))
        assertFalse("不得再用 sampleTime 符号判 EOF", open.contains("videoEx.sampleTime >= 0L"))
    }

    // endregion

    // region Y23 源码接线

    @Test
    fun `源码钉扎_所有权在投递前领取且取消按token记账`() {
        val src = codeOnly(mainSourceText("record/ArcRepairRunner.kt"))
        val start = bodyOf(src, "start")
        val begin = start.indexOf("gate.begin()")
        val post = start.indexOf("handler.post")
        assertTrue("start 必须在 post 之前领所有权", begin >= 0 && post > begin)
        assertFalse("不得再在消息内复位进程级 cancelRequested", start.contains("cancelRequested = false"))
        assertTrue("start 必须把 token 交还调用方（null = 忙，宿主据此回滚槽位）", start.contains("return token"))
        val cancel = bodyOf(src, "cancel")
        assertTrue("取消必须按 token 落到所有权门（晚取消不串新一轮）", cancel.contains("gate.cancel(token)"))
    }

    @Test
    fun `源码钉扎_任务身份绑定来源URI与代数`() {
        val tasks = flatten(codeOnly(mainSourceText("record/ArcRepairTasks.kt")))
        assertTrue("取消必须按 identity 匹配（来源 URI + 代数），不许无参取消当前轮", tasks.contains("run.request != request"))
        assertTrue("确认必须按 taskId 移除", tasks.contains("filterNot { it.request.taskId == taskId }"))
        assertTrue("起跑必须单槽（已有任务在跑直接拒绝，不排队）", tasks.contains("if (previous.running != null) return false"))
        assertTrue("终态到达必须照实入队（取消与完成赛跑也不改判）", tasks.contains("results + ArcRepairResultItem"))
        val host = flatten(codeOnly(mainSourceText("player/ArcRepairHost.kt")))
        assertTrue("宿主必须按来源 URI 起跑", host.contains("clip.uri.toString()"))
        assertTrue("取消出口必须交给执行器 token", host.contains("onCancel = runner::cancel"))
    }

    @Test
    fun `源码钉扎_准备期与收尾都有取消截止检查`() {
        val src = codeOnly(mainSourceText("record/ArcRepairRunner.kt"))
        val open = bodyOf(src, "open")
        assertTrue("准备期至少要有多处 checkAbort", open.split("checkAbort()").size - 1 >= 3)
        assertTrue("读到源时长后必须扩正式截止", open.contains("deadlineMs ="))
        val loop = bodyOf(src, "mainLoop")
        assertTrue("主循环每轮必须 checkAbort", loop.contains("checkAbort()"))
        assertFalse("不得再在主循环里另起一枚 deadline", loop.contains("val deadline ="))
        val finish = bodyOf(src, "finish")
        assertTrue("封口前必须最后验一次取消", finish.contains("cancelled()"))
        assertTrue("封口前必须给 FINALIZE 进度", finish.contains("ArcProgressStage.FINALIZE"))
        val count = bodyOf(src, "countOutput")
        assertTrue("进度必须走阶段化纯函数", count.contains("arcRepairProgress("))
        assertFalse("不得再直写 0..1 帧数进度", count.contains("ArcRepairPlan.progressOf("))
    }

    // endregion
}