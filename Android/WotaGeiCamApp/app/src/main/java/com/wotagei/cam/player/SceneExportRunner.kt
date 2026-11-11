package com.wotagei.cam.player

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import com.wotagei.cam.diag.Diag as Log
import com.wotagei.cam.media.VideoClip
import com.wotagei.cam.record.ORIENTATION_MUXER_CCW
import com.wotagei.cam.record.monotonicPts
import com.wotagei.cam.record.decoderFormatOf
import com.wotagei.cam.record.exportOrientationHint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * 「内置播放器内后渲染导出」的管线（2026-10-28 用户需求）：
 * `MediaExtractor → MediaCodec 解码 → 可选 2D 双边降噪 → 可选 Glow（提取+模糊）→ 可选残影累积 → 合成 → MediaCodec 重编码 → MediaMuxer`。
 *
 * 与无损剪辑（[ClipExport]）的关系：那条是 `Extractor → Muxer` 的原样重封（不重编码）；
 * 这条是**重编码 + 场景层效果**，应用用户点名的项：**Glow、画面降噪、残影（R2 第三路）**。
 * **RGB 曲线刻意不做**（用户口径：曲线在录制时已烘进成片像素，再套一次是双倍）。
 *
 * 音轨：与 [ClipExport] 同一条选轨口径（ENV=容器第一条音频、CAP=第二条），样本按
 * 「源 PTS − 首个提交帧 PTS」平移后透传（不重编码音频）。
 *
 * 失败/取消与无损导出同一纪律：muxer.stop() 之前的失败删 pending 行；
 * 之后的失败保留行（字节已在盘上，冷启救援能收），不静默。
 */
class SceneExportRunner(private val ctx: Context) {

    sealed interface Result
    data class Done(val id: Long, val name: String) : Result
    data class Failed(val reason: Reason) : Result

    /**
     * [COMMIT] 与 [MUXER] **必须分开**：COMMIT = 成片已封轨、只是没清掉 IS_PENDING（字节在盘上，
     * 保留 pending 行待冷启救援，UI 要报"导出完成但未收录"）；MUXER = 没写成，pending 行已删
     * （UI 报普通失败）。混成一个会让后者对用户撒"重启后自动恢复"的谎。
     */
    // 没有 CANCELLED：取消在 export() 的 catch (CancellationException) 里清场后原样重抛，
    // 永远不会变成 Failed（映射成失败值反而会吞掉协程取消）；别把它加回来
    enum class Reason { RANGE, NO_EFFECT, OPEN, DECODER, ENCODER, GL, MUXER, COMMIT, TIMEOUT, BUSY }

    /**
     * 一个导出 owner：取消方只标记放弃并取消 worker Job，绝不触碰 owner 持有的 URI/FD/codec。
     * 同步 Binder/native 返回后仍由这个 owner 登记资源、验票并收口，避免迟到资源泄漏或继续业务。
     */
    private class ExportOwner {
        val workerJob = SupervisorJob()
        private val abandoned = AtomicBoolean(false)
        private val lock = Any()
        private var pendingUri: android.net.Uri? = null
        private var pendingDeleted = false
        private var sealed = false
        private var progressSink: ((Float) -> Unit)? = null

        fun abandon() {
            abandoned.set(true)
            // 摘掉业务回调引用：已结束任务的迟到进度不得再污染宿主或下一轮导出。
            synchronized(lock) { progressSink = null }
            workerJob.cancel()
        }

        fun isAbandoned(): Boolean = abandoned.get()

        fun bindProgress(sink: (Float) -> Unit) = synchronized(lock) {
            progressSink = sink
        }

        fun publishProgress(value: Float) {
            val sink = synchronized(lock) {
                if (abandoned.get()) null else progressSink
            }
            sink?.invoke(value)
        }

        /** 每次公开提交前都必须重新领取许可；放弃已赢时不得再发起新的 Binder 调用。 */
        fun commitPermitted(): Boolean = synchronized(lock) { !abandoned.get() }

        /** 登记必须与放弃在同一把锁下裁决；返回 false 表示调用已放弃，调用方仍须回收。 */
        fun registerPending(uri: android.net.Uri): Boolean = synchronized(lock) {
            pendingUri = uri
            !abandoned.get()
        }

        fun markSealed() = synchronized(lock) {
            sealed = true
        }

        /** 同一 owner 至多领取一次 pending 删除权；外部 Binder 调用不在锁内执行。 */
        fun deletePending(ctx: Context) {
            val uri = synchronized(lock) {
                if (sealed || pendingDeleted) null
                else pendingUri?.also { pendingDeleted = true }
            } ?: return
            runCatching { ctx.contentResolver.delete(uri, null, null) }
        }
    }

    suspend fun export(
        src: VideoClip,
        startMs: Long,
        endMs: Long,
        track: SelectedTrack,
        offsetMs: Long,
        effects: SceneExportEffects,
        onProgress: (Float) -> Unit
    ): Result = suspendCancellableCoroutine { continuation ->
        val owner = ExportOwner()
        owner.bindProgress(onProgress)
        val settled = AtomicBoolean(false)
        val finish: (Result) -> Unit = { result ->
            if (settled.compareAndSet(false, true)) resumeIfActive(continuation, result)
        }
        // 独立等待侧：准备期的 insert/open/codec/GL 或失败清理的 delete 都可能永久阻塞，
        // 不能等主循环才建 deadline。预算到点只放弃 owner 并结束 caller，迟到资源仍由原 owner 收口。
        val deadlineAt = SystemClock.elapsedRealtime() +
            (src.durationMs.coerceAtLeast(endMs) * 4L).coerceAtLeast(60_000L) + 60_000L
        val watchdog = Thread({
            // 先让 owner 真正起跑再计时：极短命任务由 worker 自己收口，避免 watchdog 抢在
            // insert 之前放弃而让「封轨前取消必须回收 pending」的既有语义失真。
            try {
                Thread.sleep(WATCHDOG_GRACE_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            while (!owner.isAbandoned()) {
                if (SystemClock.elapsedRealtime() > deadlineAt) {
                    owner.abandon()
                    finish(Failed(Reason.TIMEOUT))
                    return@Thread
                }
                try {
                    Thread.sleep(WATCHDOG_POLL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, EXPORT_WATCHDOG_THREAD).apply { isDaemon = true }
        watchdog.start()
        continuation.invokeOnCancellation {
            // 不 close/delete/join；worker 负责同步调用返回后的登记、验票和一次回收。
            owner.abandon()
            watchdog.interrupt()
        }
        try {
            EXPORT_OWNER_EXECUTOR.execute {
                val result = try {
                    runBlocking(owner.workerJob) {
                        performExport(owner, src, startMs, endMs, track, offsetMs, effects) {
                            owner.publishProgress(it)
                        }
                    }
                } catch (_: CancellationException) {
                    null
                } catch (failure: Throwable) {
                    Log.w(TAG, "后渲染导出 owner 异常：${failure.message}", failure)
                    Failed(Reason.ENCODER)
                } finally {
                    owner.workerJob.cancel()
                    watchdog.interrupt()
                }
                result?.let { finish(it) }
            }
        } catch (_: RejectedExecutionException) {
            owner.abandon()
            watchdog.interrupt()
            finish(Failed(Reason.BUSY))
        }
    }

    private suspend fun performExport(
        owner: ExportOwner,
        src: VideoClip,
        startMs: Long,
        endMs: Long,
        track: SelectedTrack,
        offsetMs: Long,
        effects: SceneExportEffects,
        onProgress: (Float) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        // 上一轮 GL owner 线程仍未真正退出（有界 release 只是返回）时，不为新任务再建第二套资源。
        if (glOwnerSettling()) return@withContext Failed(Reason.BUSY)
        val range = ClipRule.normalize(startMs, endMs, src.durationMs)
            ?: return@withContext Failed(Reason.RANGE)
        val (sMs, eMs) = range
        val startUs = sMs * 1000L
        val endUs = eMs * 1000L
        // UI 已把无效果时的后渲染 chip 置灰；走到这里说明面板开着期间设置被改了。
        // 单独一枚 reason：报 RANGE 会让用户看到"保留段太短"这种假话
        if (!effects.active) {
            Log.w(TAG, "后渲染被调用但没有可应用的效果（光晕/残影关闭且降噪 OFF），不建空任务")
            return@withContext Failed(Reason.NO_EFFECT)
        }

        lateinit var pendingUri: android.net.Uri
        var sealed = false
        var submitted = 0L
        var gl: SceneExportGl? = null
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var videoTrack = -1
        var muxerStarted = false
        try {
            pendingUri = insertPending(ClipRule.clipNameOf(src.name))
                ?: return@withContext Failed(Reason.OPEN)
            // insert 返回后立即登记，再验放弃票；取消回调不持有 URI，也不负责回收。
            owner.registerPending(pendingUri)
            val job = coroutineContext[Job]
            coroutineContext.ensureActive()
            val afd = ctx.contentResolver.openAssetFileDescriptor(pendingUri, "rw")
                ?: return@withContext fail(owner, Reason.OPEN)
            afd.use { fd ->
                // open 返回后先进入 owner 的 use 作用域，再验票；取消时由同一 owner 关闭迟到 FD。
                coroutineContext.ensureActive()
                muxer = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                // 容器方向必须恰好生效一次：解码器喂的是剥过 KEY_ROTATION 的 format（见下
                // dec.configure），像素保持 as-buffered 原样 ⇒ 方向只能靠这里的 hint 补。
                // 读数走双源（retriever 权威 + extractor 兜"部分机型给 0"），与无损/选轨导出
                // 同一枚 readSourceRotationHint；写出经 exportOrientationHint 与录制/修复路同口径
                runCatching {
                    muxer?.setOrientationHint(
                        exportOrientationHint(readSourceRotationHint(ctx, src.uri), ORIENTATION_MUXER_CCW)
                    )
                }

                val ex = MediaExtractor()
                extractor = ex
                ex.setDataSource(ctx, src.uri, null)
                val vIdx = (0 until ex.trackCount).firstOrNull {
                    ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                } ?: return@withContext fail(owner, Reason.DECODER)
                val vFormat = ex.getTrackFormat(vIdx)
                val audioIndices = (0 until ex.trackCount).filter {
                    ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }
                // 选轨口径与 ClipExport 同源：ENV = 第一条、CAP = 第二条（不存在则退回 ENV）
                val audioIdx = when {
                    track == SelectedTrack.CAP && audioIndices.size >= 2 -> audioIndices[1]
                    audioIndices.isNotEmpty() -> audioIndices[0]
                    else -> -1
                }
                val audioFormat = if (audioIdx >= 0) ex.getTrackFormat(audioIdx) else null
                val width = vFormat.getInteger(MediaFormat.KEY_WIDTH)
                val height = vFormat.getInteger(MediaFormat.KEY_HEIGHT)
                val srcFps = vFormat.intOr(MediaFormat.KEY_FRAME_RATE, 0)
                val srcBitrate = vFormat.intOr(MediaFormat.KEY_BIT_RATE, 0)

                // ---- 时间基准：入点之后第一个**视频样本**的源 PTS。
                // 必须先扫出来（只读样本头、不解码），因为音轨预扫和「建不建音轨」都发生在
                // muxer.start() 之前，而 start() 一过就再也不能 addTrack
                ex.selectTrack(vIdx)
                ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                while (ex.sampleTime in 0 until startUs) {
                    if (!ex.advance()) break
                }
                val basePtsUs = ex.sampleTime
                if (basePtsUs < 0L || basePtsUs >= endUs) return@withContext fail(owner, Reason.RANGE)
                // 内录轨按同步偏移平移（与 ClipExport 同一枚纯函数，别在别处再写一份）
                val shiftUs = TrackRule.shiftUsFor(track, offsetMs)

                // ---- 编码器（surface 输入）
                val encFormat = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, width, height
                ).apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, sceneExportBitrate(srcBitrate))
                    setInteger(MediaFormat.KEY_FRAME_RATE, sceneExportFps(srcFps))
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                encoder = enc
                enc.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val inputSurface = enc.createInputSurface()
                enc.start()

                // ---- GL（先建，解码器要它的 Surface）
                val engine = SceneExportGl(width, height, effects)
                gl = engine
                if (!engine.start()) return@withContext fail(owner, Reason.GL)
                if (!engine.attachEncoderSurface(inputSurface)) return@withContext fail(owner, Reason.GL)
                val decoderSurface = engine.decoderOutputSurface()
                    ?: return@withContext fail(owner, Reason.GL)

                val dec = MediaCodec.createDecoderByType(
                    vFormat.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_VIDEO_AVC
                )
                decoder = dec
                // 喂解码器的 format 必须剥掉 KEY_ROTATION（与 ArcRepairRunner 同款）：平台把
                // "带旋转的 format + 有输出面"解释成把旋转烘进解码像素，而 muxer 又写了同一个
                // hint ⇒ 双重旋转（竖录/前摄横录导出方向错误）。剥离后像素 as-buffered，
                // 与上面写出的 hint 恰好各生效一次；width/height 原样，90° 源不会被压扁。
                dec.configure(decoderFormatOf(vFormat), decoderSurface, null, 0)
                dec.start()

                // ---- muxer：音轨格式先入，视频轨等编码器回报
                // 选中轨在区间内没有可写样本就不建轨（零样本轨会让 muxer.stop() 抛异常，
                // 而异常路径会删掉整份输出——视频明明完好）。预扫与拷贝同口径见 [audioTrackWritable]
                val audioTrack = if (audioFormat != null && audioIdx >= 0 &&
                    audioTrackWritable(
                        ex, audioIdx, basePtsUs, shiftUs, endUs - basePtsUs,
                        AUDIO_TAIL_TOLERANCE
                    )
                ) {
                    muxer?.addTrack(audioFormat) ?: -1
                } else {
                    if (audioFormat != null) {
                        Log.w(TAG, "选中轨在区间内没有可写样本，本片只导出视频")
                    }
                    -1
                }

                // 先只读样本头扫出目标区间的最后一个视频样本索引：这能证明“后面没有界内样本”，
                // 而不像输出 PTS 前缀那样把未来单调当安全前提。扫描只 advance()，不解码、不读数据。
                // 含 PARTIAL_FRAME 的输入可能一个样本不是完整访问单元，退回读到 EOF 的保守路径。
                val scan = if (endUs > startUs) scanTargetSamples(ex, vIdx, startUs, endUs) else null
                val lastTargetIndex = scan?.takeIf { it.safe }?.lastIndex ?: -1
                val expectedTargets = scan?.takeIf { it.safe }?.expected ?: 0
                val boundedInput = lastTargetIndex >= 0
                ex.selectTrack(vIdx)
                ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                val decInfo = MediaCodec.BufferInfo()
                val encInfo = MediaCodec.BufferInfo()
                var inputEos = false
                var decEos = false
                var fedSamples = 0
                val deadline = SystemClock.elapsedRealtime() +
                    (src.durationMs.coerceAtLeast(eMs) * 4L).coerceAtLeast(60_000L) + 60_000L

                while (!decEos) {
                    if (job?.isActive == false) throw CancellationException()
                    coroutineContext.ensureActive()
                    if (SystemClock.elapsedRealtime() > deadline) return@withContext fail(owner, Reason.TIMEOUT)

                    // 喂解码器
                    if (!inputEos) {
                        val inIdx = dec.dequeueInputBuffer(10_000L)
                        if (inIdx >= 0) {
                            if (boundedInput && fedSamples > lastTargetIndex) {
                                // 目标区间最后一个样本已喂入；EOS 让 decoder 排空已持有的前向参考，
                                // 不再喂后面的整段源。EOS 后还会核验实际提交数，映射异常宁可显式失败。
                                dec.queueInputBuffer(
                                    inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputEos = true
                            } else {
                                val buf = dec.getInputBuffer(inIdx)!!
                                val size = ex.readSampleData(buf, 0)
                                if (size < 0) {
                                    dec.queueInputBuffer(
                                        inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputEos = true
                                } else {
                                    dec.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                                    fedSamples++
                                    ex.advance()
                                }
                            }
                        }
                    }
                    // 取解码帧
                    when (val outIdx = dec.dequeueOutputBuffer(decInfo, 10_000L)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> if (outIdx >= 0) {
                            val eos = decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            val ptsUs = decInfo.presentationTimeUs
                            val submit = !eos && sceneExportShouldSubmit(ptsUs, startUs, endUs)
                            dec.releaseOutputBuffer(outIdx, true)
                            if (!eos) {
                                // 单调前缀不能证明后面没有界内 PTS；未建立源的完整访问单元及依赖
                                // 边界契约前，继续喂到真实 EOF，不能用首个越界输出截掉未读目标帧。
                                // 解码器每吐一帧就换一次纹理：区间外的帧也要消费掉，否则 SurfaceTexture
                                // 只会停在最新一帧，后续提交帧的时机会错位
                                if (!engine.awaitFrame()) return@withContext fail(owner, Reason.GL)
                                if (submit) {
                                    if (!engine.acquireAndRender(sceneExportPtsUs(ptsUs, basePtsUs))) {
                                        return@withContext fail(owner, Reason.GL)
                                    }
                                    submitted++
                                    val total = (endUs - basePtsUs).coerceAtLeast(1L)
                                    onProgress(((ptsUs - basePtsUs).toFloat() / total).coerceIn(0f, 0.99f))
                                } else {
                                    engine.acquireOnly()
                                }
                            }
                            if (eos) decEos = true
                        }
                    }
                    // 抽编码器输出
                    while (true) {
                        when (val idx = enc.dequeueOutputBuffer(encInfo, 0L)) {
                            MediaCodec.INFO_TRY_AGAIN_LATER -> break
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                if (!muxerStarted) {
                                    videoTrack = muxer!!.addTrack(enc.outputFormat)
                                    muxer!!.start()
                                    muxerStarted = true
                                }
                            }
                            else -> {
                                if (idx >= 0) {
                                    val eosEnc = encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                    val codecConfig = encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                                    if (!codecConfig && encInfo.size > 0 && muxerStarted) {
                                        val out = enc.getOutputBuffer(idx)!!
                                        out.position(encInfo.offset)
                                        out.limit(encInfo.offset + encInfo.size)
                                        muxer!!.writeSampleData(videoTrack, out, encInfo)
                                    }
                                    enc.releaseOutputBuffer(idx, false)
                                    if (eosEnc) break
                                }
                            }
                        }
                    }
                }

                if (boundedInput && submitted != expectedTargets.toLong()) {
                    // 有界路径依赖“样本=完整访问单元且与输出帧一一对应”。若实跑数量不符，
                    // 宁可显式失败也不发布缺帧成片；此时尚未封轨，pending 由 owner 回收。
                    Log.w(
                        TAG,
                        "有界输入核验失败：目标样本=$expectedTargets 实际提交=$submitted"
                    )
                    return@withContext fail(owner, Reason.RANGE)
                }
                if (submitted <= 0L || basePtsUs < 0L) return@withContext fail(owner, Reason.RANGE)

                // 视频 EOS：信号 + 抽干
                enc.signalEndOfInputStream()
                var encDone = false
                val drainDeadline = SystemClock.elapsedRealtime() + 30_000L
                while (!encDone && SystemClock.elapsedRealtime() < drainDeadline) {
                    when (val idx = enc.dequeueOutputBuffer(encInfo, 10_000L)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                videoTrack = muxer!!.addTrack(enc.outputFormat)
                                muxer!!.start()
                                muxerStarted = true
                            }
                        }
                        else -> if (idx >= 0) {
                            val eosEnc = encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            val codecConfig = encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (!codecConfig && encInfo.size > 0 && muxerStarted) {
                                val out = enc.getOutputBuffer(idx)!!
                                out.position(encInfo.offset)
                                out.limit(encInfo.offset + encInfo.size)
                                muxer!!.writeSampleData(videoTrack, out, encInfo)
                            }
                            enc.releaseOutputBuffer(idx, false)
                            if (eosEnc) encDone = true
                        }
                    }
                }
                if (!encDone) return@withContext fail(owner, Reason.ENCODER)
                if (!muxerStarted) {
                    owner.deletePending(ctx)
                    return@withContext Failed(Reason.MUXER)
                }

                // ---- 音轨透传：源 PTS − basePts + 内录偏移（与视频同一时间基准）
                if (audioTrack >= 0) {
                    copyAudio(ex, audioIdx, audioTrack, muxer!!, basePtsUs, shiftUs, endUs, job)
                }
                // 包括最后一包/advance 到 EOF 的取消窄窗：未完成音轨不能继续封轨公开。
                coroutineContext.ensureActive()
                muxer!!.stop()
                sealed = true
                owner.markSealed()
            }

            // ---- 提交（与 ClipExport 同一条 IS_PENDING 口径；commit 全败保留行待冷启救援）
            // 此处取消时 sealed 已置位：原样抛出，但不得删掉已正常封轨的成片。
            coroutineContext.ensureActive()
            // 每次公开提交前重新领许可：首次 Binder 阻塞期间 caller 已取消并完成后，
            // 返回 0 也不能再发起第二次公开；已进入的许可若返回成功则允许完成且不删封轨片。
            var committed = false
            if (owner.commitPermitted()) {
                committed = runCatching { commit(pendingUri) }.getOrDefault(false)
                if (!committed && owner.commitPermitted()) {
                    committed = runCatching { commit(pendingUri) }.getOrDefault(false)
                }
            }
            if (!committed) {
                Log.w(TAG, "commit 两连败，保留 pending 行待冷启救援 uri=$pendingUri")
                return@withContext Failed(Reason.COMMIT)
            }
            val id = ContentUris.parseId(pendingUri)
            Log.i(TAG, "后渲染导出完成 id=$id ${sMs}..$eMs glow=${effects.glow != null} denoise=${effects.denoise} echo=${effects.echo != null} frames=$submitted")
            Done(id, ClipRule.clipNameOf(src.name))
        } catch (e: CancellationException) {
            if (!sealed) owner.deletePending(ctx)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "后渲染导出失败：${e.message}", e)
            if (!sealed) owner.deletePending(ctx)
            Failed(mapError(e))
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { muxer?.release() }
            val engine = gl
            if (engine != null) {
                engine.release()
                // release 是“有界等待”，不代表 GL 线程真退出；线程仍活着就把准入关到实际退出。
                if (engine.glThreadAlive()) SETTLING_GL_OWNER.set(engine.exportThread())
            }
            runCatching { extractor?.release() }
        }
    }

    private data class TargetSampleScan(val lastIndex: Int, val expected: Int, val safe: Boolean)

    /**
     * 只读样本头定位目标区间的最后一个视频样本；不解码、不读 payload。
     * 含 [MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME] 时一个样本可能不是完整访问单元，
     * 无法把“最后目标样本”等同于“最后目标输出帧”，返回 safe=false 让调用方读源到尾。
     */
    private fun scanTargetSamples(
        ex: MediaExtractor,
        videoTrack: Int,
        startUs: Long,
        endUs: Long
    ): TargetSampleScan = runCatching {
        for (i in 0 until ex.trackCount) runCatching { ex.unselectTrack(i) }
        ex.selectTrack(videoTrack)
        ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        var index = 0
        var lastIndex = -1
        var expected = 0
        var safe = true
        while (true) {
            // EOF 的唯一判据是样本索引 -1；开放 GOP 的负 PTS 是合法前导，不能当文件结束。
            val track = ex.sampleTrackIndex
            if (track < 0) break
            val pts = ex.sampleTime
            if (track == videoTrack) {
                if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) safe = false
                if (pts >= startUs && pts < endUs) {
                    lastIndex = index
                    expected++
                }
                index++
            }
            if (!ex.advance()) break
        }
        TargetSampleScan(lastIndex, expected, safe)
    }.getOrElse { TargetSampleScan(-1, 0, false) }

    // ---------------------------------------------------------------- 音轨

    /**
     * 音轨样本透传：`输出 PTS = 源 PTS − basePts + shift`（负值丢弃、不 clip 到 0）。
     *
     * 与 [ClipExporter] 的 copy() 音频支**逐条同口径**：
     * - 判据（封轨/丢弃）吃**平移后**的时刻，写出前再过逐轨单调桥 [monotonicPts]
     *   （B 帧源的样本序会在界内外来回跳，同轨倒退会让 MPEG4Writer 抛异常）；
     * - 出点用「连续越界满容忍数才封轨」，与 [audioTrackWritable] 预扫同判据；
     * - 平移后为负的头部样本持续丢弃，直到可写样本或 EOF；偏移可以跨过任意多个 AAC 包。
     */
    private fun copyAudio(
        ex: MediaExtractor,
        audioIdx: Int,
        outTrack: Int,
        muxer: MediaMuxer,
        basePtsUs: Long,
        shiftUs: Long,
        endUs: Long,
        job: Job?
    ) {
        // **必须先全部取消选择**：MediaExtractor 的选择是累加的，视频轨在编码循环里还选着，
        // 不撤就会读到音视频交错的样本流——音轨里混进整帧视频（真机实测：成片音轨码率
        // 5.2 Mbps、AAC 帧里夹着 20KB 的巨块）
        for (i in 0 until ex.trackCount) runCatching { ex.unselectTrack(i) }
        ex.selectTrack(audioIdx)
        ex.seekTo(basePtsUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val endRelUs = endUs - basePtsUs
        val info = MediaCodec.BufferInfo()
        var buf: ByteBuffer = ByteBuffer.allocateDirect(INITIAL_AUDIO_BUF)
        var tail = 0
        var lastPts = -1L
        while (true) {
            job?.ensureActive()
            val size: Long = ex.sampleSize
            if (size < 0L) break
            val rel = ex.sampleTime - basePtsUs
            val outRel = rel + shiftUs
            if (outRel > endRelUs) {
                if (++tail >= AUDIO_TAIL_TOLERANCE) break
            } else if (ClipRule.keepSample(outRel)) {
                tail = 0
                if (buf.capacity() < size) {
                    buf = ByteBuffer.allocateDirect((size * 2).toInt().coerceAtLeast(INITIAL_AUDIO_BUF))
                }
                buf.clear()
                val n: Int = ex.readSampleData(buf, 0)
                if (n < 0) break
                val fix = monotonicPts(lastPts, outRel)
                lastPts = fix.ptsUs
                @Suppress("WrongConstant") // sampleFlags 与 BUFFER_FLAG_* 同值体系（同 ClipExport）
                info.set(0, n, fix.ptsUs, ex.sampleFlags)
                muxer.writeSampleData(outTrack, buf, info)
            } else {
                tail = 0
            }
            if (!ex.advance()) break
        }
        job?.ensureActive()
    }

    // ---------------------------------------------------------------- 输出

    private fun insertPending(name: String): android.net.Uri? = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, ClipRule.RELATIVE_PATH)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
    }.getOrNull()

    private fun commit(uri: android.net.Uri): Boolean = runCatching {
        val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        ctx.contentResolver.update(uri, values, null, null) > 0
    }.getOrDefault(false)

    private fun fail(owner: ExportOwner, reason: Reason): Failed {
        owner.deletePending(ctx)
        return Failed(reason)
    }

    private fun mapError(e: Exception): Reason = when (e) {
        is IllegalStateException -> Reason.MUXER
        else -> Reason.ENCODER
    }

    private fun MediaFormat.intOr(key: String, fallback: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback

    companion object {
        private const val TAG = "WotaSceneExport"
        private const val EXPORT_OWNER_THREAD = "WotaSceneExportOwner"
        private const val EXPORT_WATCHDOG_THREAD = "WotaSceneExportWatchdog"
        private const val WATCHDOG_POLL_MS = 25L
        private const val WATCHDOG_GRACE_MS = 1_000L

        /** 上一轮 GL 线程未退出时保留身份，直到确认死亡才允许新任务建第二套 GL/codec。 */
        private val SETTLING_GL_OWNER = java.util.concurrent.atomic.AtomicReference<Thread?>()

        private fun glOwnerSettling(): Boolean {
            val thread = SETTLING_GL_OWNER.get() ?: return false
            if (thread.isAlive) return true
            SETTLING_GL_OWNER.compareAndSet(thread, null)
            return false
        }

        /** 单 owner、零等待队列；忙时立即拒绝，不堆积导出请求，也不 CallerRuns。 */
        private val EXPORT_OWNER_EXECUTOR = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            java.util.concurrent.SynchronousQueue(),
            { task -> Thread(task, EXPORT_OWNER_THREAD).apply { isDaemon = true } }
        )

        private fun resumeIfActive(
            continuation: kotlinx.coroutines.CancellableContinuation<Result>, result: Result
        ) {
            if (continuation.isActive) runCatching { continuation.resume(result) }
        }

        /** 音轨"连续越界即封轨"的容忍样本数（AAC 帧间无重排，与 ClipExport 同一枚值） */
        private const val AUDIO_TAIL_TOLERANCE = 8

        /** 音轨样本缓冲初始容量；单枚 AAC 帧远小于此，不再按需翻倍之外额外开销 */
        private const val INITIAL_AUDIO_BUF = 256 * 1024
    }
}
