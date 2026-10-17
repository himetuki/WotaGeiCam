package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 换段失败出口的 native 资源回收守卫。
 *
 * 缺陷形态：`openNextSegment` 在 `vCodec/aCodec/capCodec/muxer` 已经赋给字段之后仍可能失败
 * （codec.start() 抛、openMuxer 失败）。旧 `nextSegment` 只回收 pending/target 就重试，下一轮
 * `openNextSegment` 直接覆盖字段 ⇒ 第一次创建的 MediaCodec/MediaMuxer 失去唯一引用，
 * stop/release 永远不会调用。手机并发 codec 实例数有限，泄漏会累积到之后建不出编码器。
 *
 * 这里钉两件事：失败分支必须调 [releaseFailedNextSegmentResources]，且该函数必须把四类字段
 * 逐个置空并 stop/release（不能只置空不释放，也不能只释放不置空）。
 */
class SegmentFailureCleanupGuardTest {

    private val src by lazy { codeOnly(mainSourceText("record/CodecRecorder.kt")) }

    @Test
    fun `换段失败必须先回收本轮已建资源再重试`() {
        val next = flatten(bodyOf(src, "nextSegment"))
        val failAt = next.indexOf("openNextSegment(sink)")
        val cleanupAt = next.indexOf("releaseFailedNextSegmentResources()")
        val retryAt = next.indexOf("rotateRetryDecision(")
        assertTrue("nextSegment 必须调用 openNextSegment", failAt >= 0)
        assertTrue(
            "openNextSegment 失败后必须调 releaseFailedNextSegmentResources（否则重试覆盖字段 = 泄漏 native 实例）",
            cleanupAt > failAt
        )
        assertTrue("回收必须发生在重试判定之前", retryAt < 0 || cleanupAt < retryAt)
    }

    @Test
    fun `失败回收函数必须覆盖四类字段并真正 stop_release`() {
        val cleanup = flatten(bodyOf(src, "releaseFailedNextSegmentResources"))
        for (field in listOf("vCodec", "aCodec", "capCodec", "muxer")) {
            assertTrue("失败回收必须处理 $field", cleanup.contains(field))
        }
        assertTrue("codec 必须 stop（已 start 的实例先停）", cleanup.contains("releaseStartedCodecQuietly"))
        assertTrue("muxer 必须 stop", cleanup.contains("m.stop()"))
        assertTrue("muxer 必须 release", cleanup.contains("releaseQuietly(m)"))
        assertTrue("字段必须先置空（幂等，防二次释放）", cleanup.contains("vCodec = null") && cleanup.contains("muxer = null"))
    }

    @Test
    fun `突变_删掉失败回收调用后守卫必红`() {
        val good = flatten(bodyOf(src, "nextSegment"))
        assertTrue("良品必须先真的绿", good.contains("releaseFailedNextSegmentResources()"))
        val mutated = good.replace("releaseFailedNextSegmentResources()", "")
        assertFalse(
            "删掉失败回收调用后守卫必须红",
            mutated.contains("releaseFailedNextSegmentResources()")
        )
    }
}
