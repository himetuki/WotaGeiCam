package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「prepare/换段失败路径不许泄漏 OutputTarget 的 fd」这条语义的源码级守卫（迭代 9 · 批次 I）。
 *
 * 缺陷本体：`OutputTarget.open`（content:// 走 openAssetFileDescriptor）成功之后、`target` 字段
 * 赋值之前/之后的几条失败路都不关 fd——`reject` 只把字段置 null、`nextSegment` 的兜底关的是旧
 * 字段值 ⇒ openAssetFileDescriptor 的 fd 悬到进程结束。修复：失败路径就地 close。
 *
 * 录制器依赖 MediaCodec/MediaRecorder 框架，JVM 无行为测试，按工程惯例落源码级守卫。
 */
class RecorderOutputCloseGuardTest {

    private val codecSrc by lazy { KotlinSourceScan.mainSourceText("record/CodecRecorder.kt") }
    private val codecMasked by lazy { KotlinSourceScan.codeOnly(codecSrc) }

    private val mrSrc by lazy { KotlinSourceScan.mainSourceText("record/MrRecorder.kt") }
    private val mrMasked by lazy { KotlinSourceScan.codeOnly(mrSrc) }

    @Test
    fun `CodecRecorder 的 reject 必须先关输出句柄再置空`() {
        val body = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(codecMasked, "reject"))
        assertTrue("锚点丢失：没截到 discard", body.contains("store.discard(sink)"))
        val close = body.indexOf("target?.close()")
        val clear = body.indexOf("target = null")
        assertTrue("CodecRecorder.reject 缺少 target?.close()——openMuxer 失败那条路的 fd 会悬空", close >= 0)
        assertTrue("close 必须在置空之前（close=$close clear=$clear）", close in 0 until clear)
    }

    @Test
    fun `CodecRecorder 的 prepare 在编码器创建失败时要回收 t`() {
        // Y20 起所有权先登记：target = t 在资源建立段之前落位，失败分支不再各自 t.close()，
        // 统一走 reject 的 target?.close()——同一枚 fd 仍只关一次，且边界外运行时异常也被覆盖。
        val body = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(codecMasked, "prepare"))
        val own = body.indexOf("target = t")
        val segment = body.indexOf("prepareSegment(")
        assertTrue("prepare 必须先登记 target（否则 reject 的 target?.close() 够不着这枚 t）", own >= 0)
        assertTrue("登记必须发生在资源建立段之前（own=$own segment=$segment）", own in 0 until segment)
        val seg = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(codecMasked, "prepareSegment"))
        assertTrue("锚点丢失：没截到编码器创建失败分支", seg.contains("if (video == null)"))
        assertTrue(
            "编码器创建失败必须经 reject（由登记过的 target 统一关 fd）",
            seg.contains("reject(sink, RecordError.PREPARE_FAILED)")
        )
    }

    @Test
    fun `CodecRecorder 换段时编码器重建失败也要就地关 t`() {
        val body = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(codecMasked, "openNextSegment"))
        assertTrue("锚点丢失：没截到换段建编码器", body.contains("createVideoEncoder(resolveVideoMime(p), p, inSurf)"))
        // 2026-10-08 换段有界化：就地关 t 改走有界封装 closeBounded(t)（关 fd 也走 MediaProvider，
        // 泵线程上无界等=静默丢内容），语义不变且更严——仍然必须"就地关"，只是不许无限等
        val close = body.indexOf("closeBounded(t)")
        val open = body.indexOf("openTargetBounded(sink)")
        assertTrue("openNextSegment 缺少失败分支的 closeBounded(t)——此时 target 字段是上一段的 null，兜底关不到", close >= 0)
        assertTrue("close 必须在 open 之后（open=$open close=$close）", close > open)
    }

    @Test
    fun `MrRecorder 的 reject 必须先关输出句柄再置空`() {
        val body = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(mrMasked, "reject"))
        assertTrue("锚点丢失：没截到 discard", body.contains("store.discard(sink)"))
        val close = body.indexOf("target?.close()")
        val clear = body.indexOf("target = null")
        assertTrue("MrRecorder.reject 缺少 target?.close()——setupRecorder 失败那条路的 fd 会悬空", close >= 0)
        assertTrue("close 必须在置空之前（close=$close clear=$clear）", close in 0 until clear)
    }
}
