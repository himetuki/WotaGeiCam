package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 录制停止后自动校准的接线守卫：
 * - stopInternal 在 result.value 赋值后调度 PostStopSync，且不等待；
 * - 后台对象用 SupervisorJob + Dispatchers.IO + Mutex，异常只记日志，绝不 runBlocking/抛回停止路径；
 * - 只挑双音轨分段跑 autoRun，成功后复制给其余分段。
 */
class PostStopSyncGuardTest {

    private fun masked(rel: String) = codeOnly(mainSourceText(rel))

    private fun scheduleViolations(stopBody: String): List<String> {
        val b = flatten(stopBody)
        val resultAt = b.indexOf("result.value =")
        val scheduleAt = b.indexOf("PostStopSync.schedule(app, segments)")
        val bad = ArrayList<String>()
        if (scheduleAt < 0) bad.add("stopInternal 未调度 PostStopSync")
        if (resultAt < 0) bad.add("找不到 result.value 赋值")
        if (resultAt >= 0 && scheduleAt >= 0 && scheduleAt < resultAt) {
            bad.add("后校准调度必须在 result.value 之后（不挡结果下发）")
        }
        if (b.contains("runBlocking")) bad.add("停止路径不得 runBlocking")
        return bad
    }

    @Test
    fun `停止后调度且不阻塞`() {
        val body = bodyOf(masked("ui/CameraScreen.kt"), "stopInternal")
        assertEquals(
            "后校准调度断点：\n${scheduleViolations(body).joinToString("\n")}",
            emptyList<String>(),
            scheduleViolations(body)
        )
        // 突变：删掉调度 → 同一把尺必须报红
        val mutant = body.replace("PostStopSync.schedule(app, segments)", "")
        assertTrue("删调度的突变体必须报红", scheduleViolations(mutant).isNotEmpty())
    }

    private fun missingBackgroundWires(scheduleBody: String, wholeFile: String): List<String> = listOf(
        wholeFile.contains("SupervisorJob()") to "后台作用域必须 SupervisorJob（单个失败不拖垮后续）",
        wholeFile.contains("Dispatchers.IO") to "解码/互相关必须跑 IO 线程",
        wholeFile.contains("Mutex()") to "多场停止必须串行（避免并发解码与 prefs 复合读写交错）",
        scheduleBody.contains("mutex.withLock") to "调度体必须真的进锁",
        scheduleBody.contains("TrackSync.countAudioTracks(appContext, uri) >= 2") to
            "必须逐段复核实际双音轨（captureAudio 只是请求，单轨段不许写 auto）",
        scheduleBody.contains("actualDual.size <= MAX_EXACT_SEGMENTS") to
            "段数不超过上限时必须逐段校准（直接复制首段会带容器起点差）",
        scheduleBody.contains("for (uri in actualDual)") to "逐段校准必须遍历全部实际双轨段",
        scheduleBody.contains("TrackSync.autoRun(appContext, uri)") to "逐段分支必须对每段跑 autoRun",
        scheduleBody.contains("TrackSync.autoRun(appContext, first)") to "超段数分支才对首段跑 autoRun",
        scheduleBody.contains("TrackSync.persistAuto(appContext, uri, r.offsetMs, r.confidence)") to
            "超段数分支才允许复制结果",
        scheduleBody.contains("catch (e: Throwable)") to "全部异常只记日志、不得抛回调用方",
        !wholeFile.contains("runBlocking") to "PostStopSync 不得使用 runBlocking"
    ).filter { (ok, _) -> !ok }.map { (_, why) -> why }

    @Test
    fun `后台对象不抛错且只跑双音轨`() {
        val whole = masked("player/PostStopSync.kt")
        val schedule = flatten(bodyOf(whole, "schedule"))
        assertEquals(
            "后校准后台接线断点：\n${missingBackgroundWires(schedule, whole).joinToString("\n")}",
            emptyList<String>(),
            missingBackgroundWires(schedule, whole)
        )
        // 突变 1：实际双轨复核退回恒真（单轨段也会被标 sync_done）
        val mutantCount = schedule.replace(
            "TrackSync.countAudioTracks(appContext, uri) >= 2",
            "true"
        )
        assertTrue("实际双轨复核被拆的突变体必须报红", missingBackgroundWires(mutantCount, whole).isNotEmpty())
        // 突变 2：逐段分支删掉 autoRun 调用（段结果无人计算）
        val mutantRun = schedule.replace("TrackSync.autoRun(appContext, uri)", "")
        assertTrue("逐段 autoRun 被删的突变体必须报红", missingBackgroundWires(mutantRun, whole).isNotEmpty())
        assertFalse(
            "PostStopSync 不得暴露阻塞式入口",
            whole.contains("fun scheduleBlocking") || whole.contains("runBlocking")
        )
    }
}
