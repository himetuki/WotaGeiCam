package com.wotagei.cam

import com.wotagei.cam.player.TrackSync
import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TrackSync 纯函数（批 4 偏移合成 / 低置信度口径 / 读数格式）单测。
 * 互相关本体（粗对齐+FFT 精修+置信度）复用 AudioSync，由 AudioSyncTest 钉着，不重复。
 */
class TrackSyncTest {

    // region combineOffsetMs：内容差 + 轨起点差合成总偏移

    @Test
    fun `合成_两轨同时起步只看内容差`() {
        // 环境首样本 1_000_000us、内录首样本 1_000_000us：起点差 0，内容差即总偏移
        assertEquals(120L, TrackSync.combineOffsetMs(120L, 1_000_000L, 1_000_000L))
    }

    @Test
    fun `合成_内录首样本晚10ms要加进总偏移`() {
        // 内录轨起点比环境轨晚 10_000us=10ms：总偏移 = 内容差 + 10
        assertEquals(130L, TrackSync.combineOffsetMs(120L, 1_010_000L, 1_000_000L))
    }

    @Test
    fun `合成_负偏移与起点早的情形`() {
        // 内录内容早 40ms、但轨起点早 5ms（capFirst < envFirst）：-40 + (-5) = -45
        assertEquals(-45L, TrackSync.combineOffsetMs(-40L, 995_000L, 1_000_000L))
    }

    @Test
    fun `合成_亚毫秒起点差向下取整`() {
        // 1_500us 差 = 1.5ms：µs→ms 整除向下取 1ms（1ms 级误差在听感与导出语义下可忽略）
        assertEquals(121L, TrackSync.combineOffsetMs(120L, 1_001_500L, 1_000_000L))
    }

    // endregion

    // region resolveOffsetMs：低置信度按录制起点对齐

    @Test
    fun `低置信度归零`() {
        assertEquals(0L, TrackSync.resolveOffsetMs(357L, lowConfidence = true))
    }

    @Test
    fun `正常置信度原样保留`() {
        assertEquals(357L, TrackSync.resolveOffsetMs(357L, lowConfidence = false))
        assertEquals(-42L, TrackSync.resolveOffsetMs(-42L, lowConfidence = false))
    }

    // endregion

    // region signedMs：读数符号格式

    @Test
    fun `读数_正数带加号`() {
        assertEquals("+120", TrackSync.signedMs(120L))
    }

    @Test
    fun `读数_负数自带减号`() {
        assertEquals("-45", TrackSync.signedMs(-45L))
    }

    @Test
    fun `读数_零不带号`() {
        assertEquals("0", TrackSync.signedMs(0L))
    }

    // endregion

    // region failResult：失败分类（P3-1/P3-2 行为桥，run 的失败点全经它）

    @Test
    fun `失败分类_结构非双轨_如实报没有音轨`() {
        val r = TrackSync.failResult(structuralDualAudio = false)
        assertEquals(false, r.ok)
        assertEquals(false, r.dualAudio)
        assertEquals(R.string.player_sync_no_audio, r.reasonRes)
    }

    @Test
    fun `失败分类_双轨在而读不出_报同步失败不误报没有音轨`() {
        // P3-2：探测已判双音轨的片上 extractor 打不开/首样本读不到/解码失败，
        // dualAudio 保持 true → UI 落"同步失败请重试"，不走"没有音轨"误导文案
        val r = TrackSync.failResult(structuralDualAudio = true)
        assertEquals(false, r.ok)
        assertEquals(true, r.dualAudio)
        assertEquals(R.string.player_track_sync_failed, r.reasonRes)
    }

    /**
     * extractor 打开失败必须自我回收：`runCatching { MediaExtractor().apply { setDataSource } }`
     * 在 setDataSource 抛错时只返回 null，临时实例没有任何引用；调用方的 finally 只收得到
     * 成功返回的实例 ⇒ 每次坏 URI 泄漏一枚原生 extractor。这里钉显式变量 + catch 内 release。
     */
    @Test
    fun `extractor打开失败必须release临时实例`() {
        val src = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("player/TrackSync.kt"))
        val open = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(src, "openExtractor"))
        assertTrue("openExtractor 必须有 catch 分支", open.contains("catch (e: Exception)"))
        assertTrue("catch 内必须 release 临时 extractor", open.contains("ex.release()"))
        assertTrue("不得再退回无引用的 MediaExtractor().apply 写法", !src.contains("MediaExtractor().apply"))
    }

    // endregion
}
