package com.wotagei.cam.player

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.provider.MediaStore
import com.wotagei.cam.diag.Diag as Log
import com.wotagei.cam.media.VideoClip
import com.wotagei.cam.record.ArcDropLog
import com.wotagei.cam.record.ORIENTATION_MUXER_CCW
import com.wotagei.cam.record.exportOrientationHint
import com.wotagei.cam.record.monotonicPts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

/**
 * 选轨：容器音轨序（识别口径见 [TrackSync] 头注——双音轨段第一条=环境、第二条=内录）。
 */
enum class SelectedTrack { ENV, CAP }

/**
 * 选轨导出的**纯函数**（JVM 单测钉口径，见 TrackExportTest）。
 *
 * 【负 PTS 语义裁决：丢弃前导，不 clip 到 0】MediaMuxer 的 MP4 封轨要求每轨 PTS 单调不减
 * 且 ≥0——把负 PTS 样本钳到 0 会造成同刻多样本，真机 muxer 直接抛异常；丢弃与 ClipExporter
 * "早于公共基准的音频样本丢弃"是同一既有口径，前导丢失时长 = 偏移量，属对齐的物理代价。
 */
object TrackRule {

    /** 选轨成片子目录（媒体库按 WotaGeiCam 关键字收录，Inau 子目录自然在库内） */
    const val RELATIVE_PATH = "Movies/WotaGeiCam/Inau"

    /**
     * 平移量（µs）：选环境轨=0（环境轨本就是视频时间轴的参照）；选内录轨=−offset
     * （[TrackSync.Result.offsetMs] 正=内录晚响，导出把它提前 offset 毫秒对齐环境轨）。
     */
    fun shiftUsFor(track: SelectedTrack, offsetMs: Long): Long =
        if (track == SelectedTrack.CAP) -offsetMs * 1000L else 0L

    /**
     * 平移后的输出 PTS（µs）；早于 0（负 PTS，MP4 不收）返回 null = 丢弃该前导样本。
     * [baseUs] 是视频首样本 PTS（输出 0 点），与 ClipExporter 的公共偏移基准同义。
     * 丢弃判据委托 [ClipRule.keepSample]（导出侧单一真源，与视频轨同一条规则）。
     */
    fun shiftedPts(srcPtsUs: Long, baseUs: Long, shiftUs: Long): Long? {
        val v = srcPtsUs - baseUs + shiftUs
        return if (ClipRule.keepSample(v)) v else null
    }

    /** 选轨成片命名：`inau_<源名去扩展名>.mp4`（用户裁决前缀，Internal Audio 可辨） */
    fun nameOf(srcName: String): String {
        val dot = srcName.lastIndexOf('.')
        val stem = if (dot > 0) srcName.substring(0, dot) else srcName
        return "inau_$stem.mp4"
    }
}

/**
 * 选轨导出执行器（批 4）：**全片**导出 = 视频 + 选中的**单**音轨（另一条不写入），不重编码。
 *
 * 与 ClipExporter 同源技法照抄口径：PTS 平移到 0 起、B 帧容忍（全片导出无出点截断，
 * 封轨只看 EOF，容忍数实际用不上但循环结构保持一致）、IS_PENDING 两段式入库。
 * 失败/取消语义随 #4 与 ClipExporter 同轮对齐：muxer.stop 之前的失败/取消删 pending 记录
 * （写一半的废段该删），muxer.stop 之后（成片字节在盘上）commit 全败、取消或后续异常一律
 * **保留行不删**（catch 按 bytesComplete 分岔，封轨后异常改道 Fail(COMMIT) 与全败同口径），
 * 冷启由 [com.wotagei.cam.record.StalePendingSweep.rescuePendingRows] 收录——口径逐字同
 * ClipExporter，详见其类注。
 *
 * 复用 [ClipResult]：Done 带 mediaId/name；选轨缺失（如 cap-only 段选内录）归 MUX 失败——
 * 三控件在单音轨片上根本不显示，这条路径只是防御性兜底。
 *
 * sidecar（#6）：选轨导出是**同轨全片 remux**——不裁帧、输出 0 点 = 视频首样本（baseUs 平移
 * 对位次无影响），源片带 `.drops.json` 时档案**逐字直写**成片同名 sidecar，不做
 * [ClipRule.remapDrops]（shift=0 的重映射是语义空操作，直写才是口径）。commit 成功与
 * "全败保留"两条路径都写（与 ClipExporter 同一套纪律）。
 */
class TrackExporter(private val ctx: Context) {

    companion object {
        private const val TAG = "TrackExport"

        /** 样本缓冲初始容量（同 ClipExporter：4K 帧可远超 1MB，不够按需翻倍） */
        private const val INITIAL_BUF = 1 shl 20

        /** 进度回调节流：按已读字节比例变化超 1% 才回一次 */
        private const val PROGRESS_STEP = 0.01f

        /** commit 延时重试两档（与 ClipExporter 同一套：共 4 次尝试 = 立即 2 + 延时 2） */
        private const val COMMIT_RETRY_DELAY_1_MS = 1500L
        private const val COMMIT_RETRY_DELAY_2_MS = 3000L
    }

    /**
     * 全片选轨导出。[offsetMs] 用 [TrackSync.cachedOffsetMs] 的持久化值（未同步传 0）。
     * [onProgress] 0..1（IO 线程回调，写 Compose State 是线程安全的）。
     */
    suspend fun export(
        src: VideoClip,
        track: SelectedTrack,
        offsetMs: Long,
        onProgress: (Float) -> Unit
    ): ClipResult = withContext(Dispatchers.IO) {
        val pendingUri = insertPending(TrackRule.nameOf(src.name))
            ?: return@withContext ClipResult.Fail(ClipError.OPEN)
        val job = coroutineContext[Job]
        // muxer.stop() 已过 = 容器里是完整合法 MP4：此后的失败一律保留行不删（与 ClipExporter 同口径）
        var bytesComplete = false
        try {
            val afd = ctx.contentResolver.openAssetFileDescriptor(pendingUri, "rw")
                ?: return@withContext fail(pendingUri, ClipError.OPEN)
            afd.use {
                val muxer = MediaMuxer(afd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                try {
                    // 纯 remux 不动像素：源带容器旋转时方向只能靠 metadata 透传，不写 hint 成片方向错；
                    // 源 hint=0 时写出 0、行为不变。读数走双源（readSourceRotationHint），
                    // 写出必须经 exportOrientationHint 统一口径（与 ClipExporter 同一套）
                    val hint = readSourceRotationHint(ctx, src.uri)
                    muxer.setOrientationHint(exportOrientationHint(hint, ORIENTATION_MUXER_CCW))
                    copy(src, track, TrackRule.shiftUsFor(track, offsetMs), job, muxer, onProgress)
                    muxer.stop()
                    bytesComplete = true
                    // 清 pending 失败必须改口上报（与 ClipExporter 同一处置）：IS_PENDING 残留时
                    // MediaStore 对外隐藏该行，用户看到"导出成功"却在相册里找不到成片且无锚点可归因。
                    // 失败多半是 Provider 瞬时忙：立即重试 1 次 + 延时 1.5s/3s 各再试 1 次（共 4 次）
                    var committed = commit(pendingUri)
                    if (!committed) committed = commit(pendingUri)
                    if (!committed) {
                        Log.w(TAG, "commit 立即两连败，转延时重试 uri=$pendingUri")
                        committed = delayedCommit(pendingUri, job, COMMIT_RETRY_DELAY_1_MS)
                    }
                    if (!committed) {
                        committed = delayedCommit(pendingUri, job, COMMIT_RETRY_DELAY_2_MS)
                    }
                    if (!committed) {
                        // 全败：成片字节已在盘上，删行 = 丢成片（MediaStore 删行连带删文件）。
                        // 保留 pending 行，冷启由 StalePendingSweep.rescuePendingRows 收录；
                        // sidecar 照成功路径同款写（#6：全片直写无平移），被救援回的成片档案要完整
                        Log.e(TAG, "commit 全败（共 4 次）uri=$pendingUri，成片保留待冷启救援")
                        writeSidecar(src, pendingUri)
                        return@withContext ClipResult.Fail(ClipError.COMMIT)
                    }
                    writeSidecar(src, pendingUri)
                    val id = ContentUris.parseId(pendingUri)
                    Log.i(TAG, "done id=$id track=$track offsetMs=$offsetMs")
                    ClipResult.Done(id, TrackRule.nameOf(src.name))
                } finally {
                    runCatching { muxer.release() }
                }
            }
        } catch (e: CancellationException) {
            if (bytesComplete) {
                // 成片已封轨：取消也不删行（同全败口径），留待冷启救援
                Log.i(TAG, "取消但成片已封轨，保留 pending 行待冷启救援 uri=$pendingUri")
            } else {
                fail(pendingUri, ClipError.CANCELLED)
            }
            throw e
        } catch (e: Exception) {
            if (bytesComplete) {
                // 封轨后异常：成片字节已在盘上，删行 = 丢成片；与 commit 全败同口径保留待救援
                Log.e(TAG, "封轨后异常，成片已保留待救援 uri=$pendingUri: ${e.message}")
                return@withContext ClipResult.Fail(ClipError.COMMIT)
            } else {
                Log.e(TAG, "export failed: ${e.message}")
                fail(pendingUri, ClipError.MUX)
            }
        }
    }

    /**
     * 全片拷贝：视频 + 选中单音轨，读到底为止（无出点截断，B 帧容忍不需判界）。
     * 音轨建轨前先过 [audioTrackWritable] 预扫：平移后全为负的轨（零样本）不进 muxer——
     * 零样本轨会让 `muxer.stop()` 抛异常，从而把整份（视频完好的）输出判废删掉。
     */
    private fun copy(
        src: VideoClip,
        track: SelectedTrack,
        shiftUs: Long,
        job: Job?,
        muxer: MediaMuxer,
        onProgress: (Float) -> Unit
    ) {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, src.uri, null)
            var videoTrack = -1
            val audioTracks = ArrayList<Int>()
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    videoTrack < 0 && mime.startsWith("video/") -> videoTrack = i
                    mime.startsWith("audio/") -> audioTracks.add(i)
                }
            }
            if (videoTrack < 0) throw IllegalStateException("no video track")
            // 音轨序即识别口径：ENV=第一条、CAP=第二条；选轨缺失=源与预期不符（防御性兜底）
            val audioOrdinal = if (track == SelectedTrack.ENV) 0 else 1
            val audioTrack = audioTracks.getOrNull(audioOrdinal)
                ?: throw IllegalStateException("audio track #$audioOrdinal missing (have ${audioTracks.size})")
            ex.selectTrack(videoTrack)

            // 输出 0 点 = 视频首样本（关键帧）：全片导出从 0 读起，首个样本即同步帧
            ex.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var buf = ByteBuffer.allocate(INITIAL_BUF)
            var baseUs = -1L
            while (baseUs < 0) {
                val size = ex.sampleSize
                if (size < 0) throw IllegalStateException("no sync frame at start")
                if (size > buf.capacity()) buf = ByteBuffer.allocate(size.toInt() * 2)
                ex.readSampleData(buf, 0)
                if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) baseUs = ex.sampleTime
                else ex.advance()
            }

            // 零样本音轨防线（2026-10-18）：addTrack 之后一个样本都没写出去，muxer.stop() 会抛
            // （MPEG4Writer 对零样本轨的硬约束），而导出链的异常路径会删掉整份输出——视频明明
            // 完好却被判失败。选内录轨时按 −offset 平移，若整条音轨都早于 0（前导全被丢）就会命中；
            // 预扫后不建该轨，本片按纯视频导出，不牵连视频。
            val audioWritable = audioTrack >= 0 &&
                audioTrackWritable(ex, audioTrack, baseUs, shiftUs, endRelUs = null)
            if (audioTrack >= 0 && !audioWritable) {
                Log.w(TAG, "选轨音频平移后没有可写样本，本片只导出视频（track=$audioTrack shiftUs=$shiftUs）")
            }
            // 预扫挪走了 extractor 的选择与游标：恢复「视频 +（可写）音轨」并从 0 点重新定位
            ex.selectTrack(videoTrack)
            if (audioWritable) ex.selectTrack(audioTrack)
            ex.seekTo(baseUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val vIdx = muxer.addTrack(ex.getTrackFormat(videoTrack))
            val aIdx = if (audioWritable) muxer.addTrack(ex.getTrackFormat(audioTrack)) else -1
            muxer.start()

            val info = MediaCodec.BufferInfo()
            val srcBytes = src.sizeBytes.coerceAtLeast(1L)
            var readTotal = 0L
            var lastReported = 0f
            var videoWritten = 0
            var videoDropped = 0
            var audioWritten = 0
            // B 帧源的单调兜底账本（#8，与 ClipExporter.copy 同构）：两轨各一枚 lastPts +
            // 修正计数 + 最大倒退量。同轨倒退会让 MPEG4Writer 抛 IllegalStateException 让导出
            // 当场结束，写前过桥兜底（丢弃判据仍吃 raw 值，只改写出的戳）
            var vLastPts = -1L
            var aLastPts = -1L
            var vPtsFixes = 0
            var aPtsFixes = 0
            var vPtsMaxBackUs = 0L
            var aPtsMaxBackUs = 0L
            while (true) {
                // 取消检查挂在每个样本上（与 ClipExporter 同一检查密度）
                job?.ensureActive()
                val size = ex.sampleSize
                if (size < 0) break // 两轨都到底
                if (size > buf.capacity()) buf = ByteBuffer.allocate(size.toInt() * 2)
                ex.readSampleData(buf, 0)
                if (ex.sampleTrackIndex == videoTrack) {
                    // baseUs 是"首个**读到**的样本"，全片导出从 0 读起它必是同步帧——但它**不等于**首样本：
                    // 外部源的开放 GOP（带 B 帧）里，关键帧之后那批前导 B 帧的 PTS 早于它，rel 为负
                    // （closed GOP 才恒 ≥ 0）。负 pts 直写会让 Muxer 给整轨加巨大 baseMediaDecodeTime
                    // 偏移并生成畸形 ctts，故与音频轨同口径丢弃。
                    val rel = ex.sampleTime - baseUs
                    if (ClipRule.keepSample(rel)) {
                        // 写出前过单调桥（#8）：B 帧源预期行为，逐样本只记 d 级（默认关）不刷屏
                        val fix = monotonicPts(vLastPts, rel)
                        if (fix.advanced) {
                            vPtsFixes++
                            if (fix.backUs > vPtsMaxBackUs) vPtsMaxBackUs = fix.backUs
                            Log.d(TAG, "视频轨 pts 修正 cur=$rel → ${fix.ptsUs}（back=${fix.backUs}us）")
                        }
                        vLastPts = fix.ptsUs
                        @Suppress("WrongConstant") // sampleFlags 与 BUFFER_FLAG_* 同值体系（同 ClipExporter）
                        info.set(0, size.toInt(), fix.ptsUs, ex.sampleFlags)
                        muxer.writeSampleData(vIdx, buf, info)
                        videoWritten++
                    } else {
                        videoDropped++
                    }
                } else if (aIdx >= 0) {
                    // 选内录轨：PTS 平移 −offset 对齐环境轨；负 PTS 前导丢弃（TrackRule 裁决）。
                    // 丢弃判据吃 shiftedPts 的 raw 值，写出的戳再过单调桥（两轨独立账本）
                    val outPts = TrackRule.shiftedPts(ex.sampleTime, baseUs, shiftUs)
                    if (outPts != null) {
                        val fix = monotonicPts(aLastPts, outPts)
                        if (fix.advanced) {
                            aPtsFixes++
                            if (fix.backUs > aPtsMaxBackUs) aPtsMaxBackUs = fix.backUs
                            Log.d(TAG, "音频轨 pts 修正 cur=$outPts → ${fix.ptsUs}（back=${fix.backUs}us）")
                        }
                        aLastPts = fix.ptsUs
                        @Suppress("WrongConstant")
                        info.set(0, size.toInt(), fix.ptsUs, ex.sampleFlags)
                        muxer.writeSampleData(aIdx, buf, info)
                        audioWritten++
                    }
                }
                ex.advance()
                readTotal += size
                val frac = (readTotal.toFloat() / srcBytes).coerceIn(0f, 1f)
                if (frac - lastReported >= PROGRESS_STEP) {
                    lastReported = frac
                    onProgress(frac)
                }
            }
            onProgress(1f)
            if (videoWritten == 0) throw IllegalStateException("empty source: no video sample")
            // 修正汇总（B 帧源预期行为）：逐样本明细在 d 档（默认关），收尾只落一条 i 级。
            // 插值用 ${} 花括号形式（与 CodecRecorder 段尾汇总同款）：源码守卫只认花括号里的代码
            if (vPtsFixes > 0 || aPtsFixes > 0) {
                Log.i(TAG, "pts修正=v${vPtsFixes}(back=${vPtsMaxBackUs}) a${aPtsFixes}(back=${aPtsMaxBackUs})")
            }
            // 丢弃必须可见：静默丢样本无从判断是源没有 B 帧还是守卫在起作用
            if (videoDropped > 0) {
                Log.i(TAG, "视频负PTS样本丢弃=$videoDropped（开放GOP前导B帧，丢弃为保 Muxer 时间轴合规）")
            }
            Log.i(TAG, "video=$videoWritten audio=$audioWritten shiftUs=$shiftUs")
        } finally {
            runCatching { ex.release() }
        }
    }

    // region MediaStore pending 两段式（与 ClipExporter 同一模式，目录换成 Inau 子目录）

    /**
     * commit 延时重试一档：等 [delayMs] 后再清一次 IS_PENDING。
     * 醒后先 ensureActive（用户已取消就向上抛走 cancel 分支按 bytesComplete 处置，不白等）；
     * 每次失败只落一条 [Log.w]（全败另有 [Log.e] 锚点，不逐次刷屏）。
     * 与 [com.wotagei.cam.player.ClipExporter] 的同名实现逐字同口径。
     */
    private suspend fun delayedCommit(uri: android.net.Uri, job: Job?, delayMs: Long): Boolean {
        delay(delayMs)
        job?.ensureActive()
        if (commit(uri)) return true
        Log.w(TAG, "commit 延时重试仍败 uri=$uri delayMs=$delayMs")
        return false
    }

    /**
     * sidecar 携带（#6）：全片 remux 无位次平移，源档案**逐字直写**成片同名 sidecar
     * （[ArcDropLog] 自拼 `.drops.json`，落点参数是成片路径）。写失败只少一份补弧档案，
     * 不牵连导出成败。
     */
    private fun writeSidecar(src: VideoClip, pendingUri: android.net.Uri) {
        val srcLog = src.dataPath?.let { ArcDropLog.readFrom(it) }
        val outPath = queryData(pendingUri)
        if (srcLog != null && outPath != null) {
            val ok = ArcDropLog.writeTo(outPath, srcLog)
            Log.i(TAG, "sidecar direct ok=$ok")
        }
    }

    private fun insertPending(name: String): android.net.Uri? {
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.RELATIVE_PATH, TrackRule.RELATIVE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            }
            ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull()
    }

    /**
     * 清 IS_PENDING（两段式的第二段）。true = 相册里立刻可见。
     * 判据取 update 的受影响行数，与 [com.wotagei.cam.record.VideoStore.commit] 同口径：
     * 不抛异常也可能一行没改（URI 已失效），把"没清掉"当成功就会继续瞒着用户。
     */
    private fun commit(uri: android.net.Uri): Boolean = runCatching {
        ctx.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null, null
        ) > 0
    }.getOrElse {
        Log.e(TAG, "commit 抛异常 uri=$uri: ${it.message}")
        false
    }

    /**
     * 写一半（muxer.stop 之前）的失败/取消收尾：删 pending 记录，不留 0 字节幽灵条目。
     * muxer.stop 之后的失败**不走这里**（成片字节在盘上，删行 = 丢成片，见类注）。
     */
    private fun fail(uri: android.net.Uri, reason: ClipError): ClipResult {
        runCatching { ctx.contentResolver.delete(uri, null, null) }
        Log.i(TAG, "failed reason=$reason")
        return ClipResult.Fail(reason)
    }

    /** pending 记录的真实路径（DATA 列）；sidecar 与它同目录同名（照抄 ClipExporter 同名实现） */
    private fun queryData(uri: android.net.Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    }.getOrNull()

    // endregion
}
