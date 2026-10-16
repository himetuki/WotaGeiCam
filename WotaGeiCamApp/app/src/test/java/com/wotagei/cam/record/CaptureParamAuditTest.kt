package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import com.wotagei.cam.source.KotlinSourceScan.regionOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

/**
 * 内录采集器「档位体检」+ buffer 桶口径归一的**纯函数全表 + 源码结构守卫**（2026-10-16）。
 *
 * 两条被修的病（都是静默型）：
 * 1. `createCaptureAudioRecord` 建 AudioRecord 后只查 state、从不回读实际采样率/声道——系统静默
 *    重采样/改声道时，AAC 编码器按请求档配置、喂进去的却是另一档 PCM ⇒ 音调漂移/截断，全程无感知；
 *    而"档位可用性"预判 `getMinBufferSize` 是**麦克风口径**（默认输入源），与 playback capture 链
 *    能否吃下该档没有因果关系——回读是唯一实证闸；
 * 2. 读块/整缓冲算式在 `AudioFeeder.chunkBytes` 与 `PlaybackCaptureController` 各写一份（声称同
 *    口径却各一份代码），且捕获路把 AudioFeeder 算好的整缓冲形参直接丢弃。
 *
 * 本轮落地：回读比对抽纯函数 [captureParamMismatch]（全表）；算式并归 [AudioProbe.chunkBytes] /
 * [AudioProbe.recordBufferBytes] 单源，配"抽取前旧式逐点等效"表（旧式以逐字副本作参照系）。
 * 「尺子自己能红」：结构守卫均配突变体自证——删回读、拆 release/置因、算式回退内联，必须报红。
 */
class CaptureParamAuditTest {

    private fun maskedMain(rel: String): String = codeOnly(mainSourceText(rel))

    private fun body(rel: String, funName: String): String =
        flatten(bodyOf(maskedMain(rel), funName))

    // ------------------------------------------------------------------ A 档位体检判据（纯函数全表）

    @Test
    fun `captureParamMismatch全表_逐项相等才放行_不等必含请求与实际`() {
        // 全表：请求档 {44100,48000}×{1ch,2ch} × 回读档 {0,44100,48000}×{0,1ch,2ch}；期望 = 逐项相等
        for (reqRate in intArrayOf(44_100, 48_000)) {
            for (reqCh in intArrayOf(1, 2)) {
                for (actRate in intArrayOf(0, 44_100, 48_000)) {
                    for (actCh in intArrayOf(0, 1, 2)) {
                        val pass = reqRate == actRate && reqCh == actCh
                        val got = captureParamMismatch(reqRate, reqCh, actRate, actCh)
                        val tag = "req=$reqRate/$reqCh act=$actRate/$actCh"
                        if (pass) {
                            assertNull("逐项相等必须放行（$tag）", got)
                        } else {
                            assertNotNull("任一不等必须判不通过（$tag）", got)
                            assertTrue("短因必须含请求档（$tag）：$got", got!!.contains("${reqRate}Hz/${reqCh}ch"))
                            assertTrue("短因必须含实际档（$tag）：$got", got.contains("${actRate}Hz/${actCh}ch"))
                        }
                    }
                }
            }
        }
        // 边界显式条目：成功路径（全等）与"读回 0 = 系统未按档配置"必须各走一边
        assertNull("全等放行（成功路径零行为变化的前提）", captureParamMismatch(48_000, 2, 48_000, 2))
        assertNull("0/0 全等同样放行——判据只比相等，不替系统解释数值是否合理", captureParamMismatch(0, 0, 0, 0))
        assertNotNull("实际读回 0/0 而请求 48k 立体声必须判不通过（0 = 未按档配置）", captureParamMismatch(48_000, 2, 0, 0))
        // 单维度不符各一条：rate 不符 / ch 不符 / 双不符
        assertTrue("rate 不符也要给请求+实际", captureParamMismatch(48_000, 2, 44_100, 2)!!.contains("44100Hz"))
        assertTrue("ch 不符也要给实际声道", captureParamMismatch(48_000, 2, 48_000, 1)!!.contains("1ch"))
        assertTrue("双不符同一条短因（文案形态与用户拍板示例一致）", captureParamMismatch(48_000, 2, 44_100, 1)!!.contains("请求 48000Hz/2ch"))
    }

    /**
     * 体检结构守卫的判定本体（良品与突变体共用同一把尺）：STATE 检查之后回读、判定进独立分支、
     * 分支内 release + 置 lastInitFailure + 以 null 收口，缺任一条即不通过。
     */
    private fun auditHolds(fn: String): Boolean {
        val stateAt = fn.indexOf("rec.state != AudioRecord.STATE_INITIALIZED")
        val readAt = fn.indexOf("captureParamMismatch(sampleRate, channels, rec.sampleRate, rec.channelCount)")
        val successAt = fn.indexOf("lastInitFailure = null")
        if (stateAt < 0 || readAt <= stateAt) return false
        if (successAt < 0 || readAt >= successAt) return false
        val branchAt = fn.indexOf("if (mismatch != null)")
        if (branchAt <= readAt || branchAt >= successAt) return false
        val branchEnd = fn.indexOf("} else {", branchAt)
        if (branchEnd <= branchAt) return false
        val branch = fn.substring(branchAt, branchEnd)
        return branch.contains("rec.release()") &&
            branch.contains("lastInitFailure = mismatch") &&
            branch.trimEnd().endsWith("null")
    }

    @Test
    fun `回读体检结构_STATE之后判定_不符即释放置因返null`() {
        val raw = mainSourceText("record/PlaybackCaptureController.kt")
        val masked = codeOnly(raw)
        val fn = body("record/PlaybackCaptureController.kt", "createCaptureAudioRecord")
        assertTrue(
            "良品：必须在 STATE 检查之后回读 sampleRate/channelCount，不一致分支必须 " +
                "release + 置 lastInitFailure + 以 null 收口（成功路径 else 分支原样返回采集器）",
            auditHolds(fn)
        )
        // 日志三件（字符串字面量在遮蔽文本里看不到，回原文取函数区间）：
        // 回读不符是"本机内录链吃不下该档"的现场，req/实际/机型必须同时留档照既有诊断风格
        val region = regionOf(masked, "createCaptureAudioRecord")
        val rawFn = raw.substring(region.start, region.end)
        assertTrue(
            "回读不符必须打 e 级日志（req 与 实际 两档都留档）",
            rawFn.contains("Log.e") && rawFn.contains("req rate=") && rawFn.contains("实际 rate=")
        )
        assertTrue("回读日志必须带机型信息（跨机型差异排查，照既有诊断风格）", rawFn.contains("机型="))
        // 突变自证 1：删掉回读（改拿请求值自比 ⇒ 恒放行）——同一把尺必须报红
        val noRead = flatten(
            bodyOf(
                masked.replace(
                    "captureParamMismatch(sampleRate, channels, rec.sampleRate, rec.channelCount)",
                    "captureParamMismatch(sampleRate, channels, sampleRate, channels)"
                ),
                "createCaptureAudioRecord"
            )
        )
        assertFalse("删掉回读后守卫必须报红", auditHolds(noRead))
        // 突变自证 2：不一致分支不释放（两处 release 都换掉，本尺只看不一致分支那份）
        val noRelease = flatten(bodyOf(masked.replace("rec.release()", "Unit"), "createCaptureAudioRecord"))
        assertFalse("不一致分支拆掉 release 后守卫必须报红", auditHolds(noRelease))
        // 突变自证 3：不一致分支不置因（用户可见短因丢失 = 退回"没内录轨"的静默态）
        val noReason = flatten(bodyOf(masked.replace("lastInitFailure = mismatch", "Unit"), "createCaptureAudioRecord"))
        assertFalse("不一致分支拆掉置因后守卫必须报红", auditHolds(noReason))
        // 突变自证 4：判定算了不用（分支换恒假）——只留回读、删分支，必须报红
        val noBranch = flatten(bodyOf(masked.replace("if (mismatch != null)", "if (false)"), "createCaptureAudioRecord"))
        assertFalse("拆掉不一致分支后守卫必须报红", auditHolds(noBranch))
    }

    // ------------------------------------------------------------------ B 桶口径归一（等效性表 + 单源守卫）

    /**
     * 抽取前旧式（`AudioFeeder.chunkBytes` 与控制器私有算式的逐字副本）：等效性表的参照系。
     * 禁止按新实现改写——它一旦跟着新式变，"抽取前后数值不变"就无从证明。
     */
    private fun legacyChunkBytes(minBuf: Int, channels: Int): Int {
        val frameBytes = max(channels, 1) * 2
        val raw = max(minBuf / 2, frameBytes * 16).coerceAtMost(8192)
        return (raw / frameBytes) * frameBytes
    }

    /** 抽取前旧式的整缓冲：两处调用点的 `max(minBufferSize, chunk)` */
    private fun legacyBufferBytes(minBuf: Int, channels: Int): Int = max(minBuf, legacyChunkBytes(minBuf, channels))

    @Test
    fun `桶口径归一_与抽取前旧式逐点等效_极小极大与单双声道都取到`() {
        val minBufs = intArrayOf(
            Int.MIN_VALUE, -1, 0, 1, 2, 3, 31, 32, 33, 63, 64, 65,
            127, 128, 1000, 4095, 4096, 8191, 8192, 8193, 16384, 1 shl 20, Int.MAX_VALUE
        )
        for (minBuf in minBufs) {
            for (channels in intArrayOf(0, 1, 2, 3)) {
                assertEquals(
                    "chunk 与旧式不一致（minBuf=$minBuf ch=$channels）",
                    legacyChunkBytes(minBuf, channels), AudioProbe.chunkBytes(minBuf, channels)
                )
                assertEquals(
                    "整缓冲与旧式不一致（minBuf=$minBuf ch=$channels）",
                    legacyBufferBytes(minBuf, channels), AudioProbe.recordBufferBytes(minBuf, channels)
                )
            }
        }
        // 字面量取点：挡住"新旧一起错"的假等效（手算值直接写死，不复用公式）
        assertEquals("读块上限仍是 8192（原两处私有常量值）", 8192, AudioProbe.CHUNK_MAX)
        assertEquals("minBuf=0 单声道：下限 16 帧 = 32B", 32, AudioProbe.chunkBytes(0, 1))
        assertEquals("minBuf=0 立体声：下限 16 帧 = 64B", 64, AudioProbe.chunkBytes(0, 2))
        assertEquals("整缓冲下限同样吃 16 帧兜底", 64, AudioProbe.recordBufferBytes(0, 2))
        assertEquals("4096/立体声：半缓冲 2048、整帧", 2048, AudioProbe.chunkBytes(4096, 2))
        assertEquals(4096, AudioProbe.recordBufferBytes(4096, 2))
        assertEquals("8192/立体声：半缓冲 4096", 4096, AudioProbe.chunkBytes(8192, 2))
        assertEquals("超大 minBuf 截到 CHUNK_MAX", 8192, AudioProbe.chunkBytes(1 shl 20, 2))
        assertEquals("超大 minBuf 的整缓冲取 minBuf 本身", 1 shl 20, AudioProbe.recordBufferBytes(1 shl 20, 2))
        assertEquals("奇数字节向下对齐整帧：8191/单声道 → 4094", 4094, AudioProbe.chunkBytes(8191, 1))
        assertEquals("100/立体声：半缓冲 50 被 16 帧下限抬到 64", 64, AudioProbe.chunkBytes(100, 2))
        assertEquals(
            "channels=0 按单声道帧宽（旧式 max(channels,1) 的原样口径）",
            AudioProbe.chunkBytes(100, 1), AudioProbe.chunkBytes(100, 0)
        )
        assertEquals("channels=3 帧宽 6（旧式不夹到立体声，逐字保留）", 6 * 16, AudioProbe.chunkBytes(0, 3))
    }

    @Test
    fun `桶口径单源_AudioFeeder与内录建路必须共用AudioProbe纯函数`() {
        val feeder = maskedMain("record/AudioFeeder.kt")
        assertTrue(
            "AudioFeeder 读块必须取 AudioProbe.chunkBytes（泵读块与内录建路同函数）",
            feeder.contains("private val chunkBytes = AudioProbe.chunkBytes(minBufferSize, channels)")
        )
        assertTrue(
            "AudioFeeder 整缓冲必须取 AudioProbe.recordBufferBytes（openRecord 形参口径单源）",
            feeder.contains("openRecord(AudioProbe.recordBufferBytes(minBufferSize, channels))")
        )
        assertTrue(
            "帧宽必须取 AudioProbe.frameBytes（与读块/整帧对齐换算同源）",
            feeder.contains("private val frameBytes = AudioProbe.frameBytes(channels)")
        )
        val ctrlMasked = maskedMain("record/PlaybackCaptureController.kt")
        val ctrl = body("record/PlaybackCaptureController.kt", "createCaptureAudioRecord")
        assertTrue(
            "内录建路整缓冲必须取 AudioProbe.recordBufferBytes（与泵读块/环境建路同函数；" +
                "被丢弃的形参因此与 controller 自算恒等——输入同出 AudioProbe.minBufferSize 两源同值）",
            ctrl.contains("val bufferBytes = AudioProbe.recordBufferBytes(minBuf, channels)")
        )
        assertFalse(
            "内录建路不得再出现内联桶算式或私有上限常量（回退 = 口径再次分叉）",
            ctrl.contains("CHUNK_MAX") || ctrl.contains("frameBytes * 16") || ctrl.contains("coerceAtMost")
        )
        // 突变自证 1：控制器整缓冲换回内联旧式，同一把尺必须报红
        val ctrlMut = flatten(
            bodyOf(
                ctrlMasked.replace(
                    "val bufferBytes = AudioProbe.recordBufferBytes(minBuf, channels)",
                    "val bufferBytes = max(minBuf, chunk)"
                ),
                "createCaptureAudioRecord"
            )
        )
        assertFalse(
            "控制器整缓冲改回内联后守卫必须报红",
            ctrlMut.contains("val bufferBytes = AudioProbe.recordBufferBytes(minBuf, channels)")
        )
        // 突变自证 2：AudioFeeder 读块换回自算，同一把尺必须报红
        val feederMut = maskedMain("record/AudioFeeder.kt")
            .replace("private val chunkBytes = AudioProbe.chunkBytes(minBufferSize, channels)", "private val chunkBytes = 0")
        assertFalse(
            "AudioFeeder 读块改回自算后守卫必须报红",
            feederMut.contains("private val chunkBytes = AudioProbe.chunkBytes(minBufferSize, channels)")
        )
    }
}
