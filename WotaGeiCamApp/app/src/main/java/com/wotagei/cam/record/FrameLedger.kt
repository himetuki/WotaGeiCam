package com.wotagei.cam.record

import java.util.concurrent.atomic.AtomicLong

/**
 * 录制帧账的跨组件计数器（2026-10-17 起，只观测、不参与任何判定）。
 *
 * 为什么要跨组件共享：这条链的帧从相机回调（[com.wotagei.cam.camera.Camera2Engine]）到
 * GL 回调/消费（[com.wotagei.cam.camera.GlRenderEngine]）再到编码器 swap，分属不同线程与类，
 * 录制器在段收尾时才需要一张完整的账。计数器全是 [AtomicLong]（相机线程、GL 线程、泵线程各写各格），
 * **热路径零分配、零字符串拼接**；只在段收尾 `snapshot()` 一次。
 *
 * 口径警示：
 * - [FrameCounterSnapshot.cameraStarted] 数的是**重复请求**（预览/录制）的 `onCaptureStarted`
 *   回调；会话内单发 capture 刻意传 `null` 回调（见 `Camera2Engine.singleCapture`），**不计**；
 *   `onCaptureStarted` 含"帧开始曝光"语义，可能领先于实际出帧，故只作到达上界；
 * - [FrameCounterSnapshot.glCallbacks] 是 SurfaceTexture 的 `onFrameAvailable`；DIRECT 路的 GL
 *   只做预览、不喂编码器，这两个数字在 DIRECT 下只描述预览链；
 * - [FrameCounterSnapshot.encoderSwapOk] 只数换到**编码面**的 `eglSwapBuffers`，窗口面不计；
 * - 计数**不得进入 PTS 计算**——`GlRenderEngine.stampEncoderPresentation` 有源码守卫
 *   （FrameLedgerGuardTest），防止"观测值顺手参与打戳"这类退化。
 */
internal object FrameLedger {

    private val cameraStarted = AtomicLong(0L)
    private val glCallbacks = AtomicLong(0L)
    private val glConsumed = AtomicLong(0L)
    private val encoderSwapOk = AtomicLong(0L)
    private val encoderSwapFail = AtomicLong(0L)
    private val arcDropped = AtomicLong(0L)

    /** 相机 `onCaptureStarted` 帧到达（每拍一次 Long 自增，无分配） */
    fun onCameraStarted() {
        cameraStarted.incrementAndGet()
    }

    /** SurfaceTexture 回调（相机线程→GL handler；`newFrames` 之外单独计数，不改其语义） */
    fun onGlCallback() {
        glCallbacks.incrementAndGet()
    }

    /** `updateTexImage()` 成功一次（GL 线程） */
    fun onGlConsumed() {
        glConsumed.incrementAndGet()
    }

    /** 编码面 `eglSwapBuffers` 结果（成功/失败分开计；窗口面 swap 不调用本函数） */
    fun onEncoderSwap(ok: Boolean) {
        if (ok) encoderSwapOk.incrementAndGet() else encoderSwapFail.incrementAndGet()
    }

    /** 在线抽帧规则判掉一帧（每一枚被 `ArcKeepRule` 判 drop 的源帧 +1） */
    fun onArcDrop() {
        arcDropped.incrementAndGet()
    }

    /**
     * 一次原子快照（六格逐个读，不是一组事务：只用于段收尾观测，跨线程的极短窗口可接受）。
     * 每次段收尾调用一次，分配一个 data class，不在逐帧路径上。
     */
    fun snapshot(): FrameCounterSnapshot = FrameCounterSnapshot(
        cameraStarted = cameraStarted.get(),
        glCallbacks = glCallbacks.get(),
        glConsumed = glConsumed.get(),
        encoderSwapOk = encoderSwapOk.get(),
        encoderSwapFail = encoderSwapFail.get(),
        arcDropped = arcDropped.get()
    )
}

/** [FrameLedger] 的只读快照；字段口径见对象 KDoc */
internal data class FrameCounterSnapshot(
    val cameraStarted: Long,
    val glCallbacks: Long,
    val glConsumed: Long,
    val encoderSwapOk: Long,
    val encoderSwapFail: Long,
    val arcDropped: Long
)

/**
 * 段收尾帧账（录制器把跨组件快照与段内本账合成一张，交给 [frameLedgerLine] 成文）。
 *
 * [preMuxBuffered]/[preMuxDropped] 是「muxer 启动前」的账：第 1 笔先按"被 release 的样本数"
 * 记 [preMuxDropped]；第 2 笔起前者=成功补写数、后者=缓冲溢出/未启动的丢失数。
 */
internal data class FrameLedgerReport(
    val partIndex: Int,
    val gpu: Boolean,
    val durationMs: Long,
    val cameraFrames: Long,
    val glCallbacks: Long,
    val glConsumed: Long,
    val encoderSwaps: Long,
    val swapFails: Long,
    val muxSamples: Long,
    val preMuxBuffered: Long,
    val preMuxDropped: Long,
    val arcDrops: Long,
    val rotateLostMs: Long,
    val rotateFramesUpper: Long
)

/** 在线合帧数 = SurfaceTexture 回调数 − `updateTexImage` 次数（负数按 0 夹紧，快照非事务） */
internal fun frameMerged(callbacks: Long, consumed: Long): Long =
    (callbacks - consumed).coerceAtLeast(0L)

/** 厘帧/秒（整数，避免浮点格式化踩 locale）；时长非正 ⇒ 0 */
internal fun frameCentiFps(frames: Long, durationMs: Long): Long =
    if (frames <= 0L || durationMs <= 0L) 0L else frames * 100_000L / durationMs

/** 厘帧/秒的两位小数文本（纯整数拼装） */
internal fun frameCentiText(centi: Long): String =
    "${centi / 100}.${(centi % 100).toString().padStart(2, '0')}"

/**
 * 逐级差值（"相机给了多少 → GL 收了多少 → swap 多少 → muxer 多少"的桥）：
 * 每段只回答"这一级比上一级少了多少"，负数按 0 夹紧（快照非事务 / DIRECT 路 GL 只做预览）。
 */
internal data class FrameStageDrops(
    val cameraToGl: Long,
    val glToSwap: Long,
    val swapToMux: Long
)

internal fun frameStageDrops(report: FrameLedgerReport): FrameStageDrops = FrameStageDrops(
    cameraToGl = (report.cameraFrames - report.glCallbacks).coerceAtLeast(0L),
    glToSwap = (report.glConsumed - report.encoderSwaps).coerceAtLeast(0L),
    swapToMux = (report.encoderSwaps - report.muxSamples).coerceAtLeast(0L)
)

/**
 * 段收尾**独立一行**帧账（中文，字段名清晰；不并入既有 `stampObsLine` 与段尾行，保持向后可读）。
 *
 * 这是一座"计划→行为"桥：调用方把 [FrameLedgerReport] 原样交进来，成文只做拼装与四级差值，
 * 所以 JVM 可以断言"每个数字真的落在那一行里"，不会出现"删掉一个字段测试仍全绿"的假绿。
 */
internal fun frameLedgerLine(report: FrameLedgerReport): String {
    val merged = frameMerged(report.glCallbacks, report.glConsumed)
    val stage = frameStageDrops(report)
    val cameraFps = frameCentiText(frameCentiFps(report.cameraFrames, report.durationMs))
    val muxFps = frameCentiText(frameCentiFps(report.muxSamples, report.durationMs))
    val path = if (report.gpu) "GPU" else "DIRECT(GL仅预览)"
    // DIRECT 路没有编码 swap（相机面直连编码器），"GL→swap / swap→mux" 两级差没有语义：
    // 打印数字会被读成"丢了这么多帧"，这里显式写不适用。
    val stageText = if (report.gpu) {
        "逐级差=相机→GL ${stage.cameraToGl}/GL→swap ${stage.glToSwap}/swap→mux ${stage.swapToMux}"
    } else {
        "逐级差=相机→GL ${stage.cameraToGl}/GL→swap 不适用(DIRECT无编码swap)/swap→mux 不适用"
    }
    return "帧账 part=${report.partIndex} 路径=$path 墙钟=${report.durationMs}ms " +
        "相机到达=${report.cameraFrames} GL回调=${report.glCallbacks} GL消费=${report.glConsumed} " +
        "GL合帧=$merged 编码swap=${report.encoderSwaps} swap失败=${report.swapFails} " +
        "muxer视频=${report.muxSamples} 起录补写=${report.preMuxBuffered} 起录丢弃=${report.preMuxDropped} " +
        "有意抽帧=${report.arcDrops} " +
        "换段丢=${report.rotateLostMs}ms/${report.rotateFramesUpper}帧(上界) " +
        stageText + " " +
        "实测相机=${cameraFps}fps 成片=${muxFps}fps"
}

/**
 * 会话收尾的段清单摘要（与 `.drops.json` 的 `segments` 同源：段序号 / 全局首帧 / 段内帧数 /
 * 该段之前换段腿丢的 ms）。只做拼装，供诊断导出正文使用；空表返回空串（旧行为零变化）。
 */
internal fun frameSessionSummary(videoSamples: List<Int>, lostMs: List<Long>): String {
    if (videoSamples.isEmpty()) return ""
    val sb = StringBuilder("会话段清单(与.drops.json同源) 段数=")
    sb.append(videoSamples.size)
    var first = 0
    for (i in videoSamples.indices) {
        val count = videoSamples[i]
        sb.append(" [段").append(i).append(" first=").append(first)
            .append(" count=").append(count)
            .append(" lost=").append(lostMs.getOrNull(i) ?: 0L).append("ms]")
        first += count
    }
    return sb.toString()
}
