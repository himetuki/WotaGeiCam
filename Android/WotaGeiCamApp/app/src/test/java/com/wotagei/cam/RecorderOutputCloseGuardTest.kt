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
    fun `CodecRecorder 的 prepare 在编码器创建失败时要就地关 t`() {
        val body = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(codecMasked, "prepare"))
        // 此时 target 字段还没轮到赋值，reject 的 target?.close() 够不着这枚 t
        assertTrue(
            "prepare 缺少编码器失败分支的 t.close()——首个 fd 从第一条失败路就开始漏",
            body.contains("t.close()")
        )
        assertTrue("锚点丢失：没截到编码器创建", body.contains("createVideoEncoder(mime, p, inSurf)"))
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
