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
 * 掐头去尾无损剪辑导出（用户 2026-10-04 定版方向 3）。
 *
 * **无损** = 不重编码：`MediaExtractor` 逐样本读源 MP4，`MediaMuxer` 原样写出，只改 PTS。
 * 精度是**视频关键帧**：入点自动向前对齐到最近的 I 帧（解码必须从关键帧起），出点按 PTS 截止。
 * 音画同步靠"两轨同减一个公共偏移"保住——偏移取视频首样本（关键帧）的 PTS，早于它的音频样本
 * 直接丢弃（那段画面本来就多出来了，丢掉对应音频才不会整体错位）。
 *
 * **音轨由用户选**（2026-10-18 用户定版）：只导选中的那一条（默认环境音、可选内录），
 * 不再"只认第一条"（双轨素材的内录被静默丢）也不再"多轨全导"。选中的轨在区间内没有可写样本时
 * 按纯视频导出——零样本轨会让 `muxer.stop()` 抛异常，把视频完好的整份输出判废删掉。
 *
 * B 帧容错：按 PTS 判"越界"在有 B 帧的流里会提前停（解码序靠后的 B 帧 PTS 反而小），
 * 所以每轨都按"连续越界样本数"封轨（视频 32 / 音频 8，典型 B 帧深度 ≤ 4），界内迟到样本照写。
 *
 * sidecar：源片带 `.drops.json`（本 App 抽帧成片）时，剪辑区间做平移重映射后写进新片同名
 * sidecar。平移基准（关键帧的输出帧序号）不靠从头数样本——抽帧成片的 PTS 恒为
 * "帧序号 × 1e6/dstFps"（ArcKeepRule 的均匀帧序号时间戳定版），所以
 * `shift = (baseUs × dstFps + 5×10⁵) / 10⁶` 闭式反推即可；`(k, n)` 里的 n 是增量
 * （"该帧之前丢了几个源帧"），与全局序号无关，平移后原样保留（见 [ClipRule.remapDrops]）。
 * 外来视频没有 sidecar，这条链路自然不走。
 */
object ClipRule {

    /** 保留段下限：比它短视为误操作（300ms 在 24fps 下连 8 帧都不到，没有导出价值） */
    const val MIN_KEEP_MS = 300L

    /** 剪辑成片子目录（媒体库按 WotaGeiCam 关键字收录，Clip 子目录自然在库内） */
    const val RELATIVE_PATH = "Movies/WotaGeiCam/Clip"

    /**
     * 入出点规范化：起点非负、终点不超片长、终点必须晚于起点、保留段不短于 [MIN_KEEP_MS]。
     * @return (startMs, endMs)；不合法返回 null（调用方给「保留段太短」提示）
     */
    fun normalize(startMs: Long, endMs: Long, durationMs: Long): Pair<Long, Long>? {
        val s = startMs.coerceIn(0L, durationMs.coerceAtLeast(0L))
        val e = endMs.coerceIn(0L, durationMs.coerceAtLeast(0L))
        if (e - s < MIN_KEEP_MS) return null
        return s to e
    }

    /**
     * 平移后相对 PTS 为负的样本该不该写进 Muxer：**不写**（丢弃）。导出侧单一真源，
     * 剪辑/选轨导出的视频轨与音频轨共用同一条判据。
     *
     * 丢负 PTS 是对的：负 rel 的样本是开放 GOP（源带 B 帧）里入点关键帧**之前**的那批前导 B 帧，
     * 它们引用的是剪辑起点之前的画面，本就不该出现在切片里；与音频轨"早于公共基准的样本丢弃"
     * 是同一口径（负时长丢失 = 偏移量本身，属对齐的物理代价），也不能 clip 到 0——同刻多样本会把 Muxer 顶回去。
     *
     * 这条守卫防的是（2026-10-09 真机解盒实证）：直写负 pts 后 MediaMuxer 给**整条视频轨**
     * `baseMediaDecodeTime` 加了 162000000 个 90k-tick（=1800s）偏移，并写出 `sample_count=0`
     * 的畸形 `ctts`；忽略 `elst` 或拒绝畸形 `ctts` 的播放器（QQ 内嵌核、部分系统相册核）把视频错锚、
     * 把音频判空 ⇒ 用户看到的"我这播放就正常，QQ 里无声/音画不同步"。
     */
    fun keepSample(relUs: Long): Boolean = relUs >= 0L

    /**
     * sidecar 平移重映射：剪辑保留输出帧序号 `shift` 及其之后的部分，位次整体前移 [shift]。
     * n 是增量（"该帧之前丢了几个源帧"），与全局序号无关，平移后原样保留；
     * shift 之前的账目随被剪掉的画面一起丢弃。
     */
    fun remapDrops(log: ArcDropLog, shift: Int): ArcDropLog =
        ArcDropLog(
            mode = log.mode,
            dstFps = log.dstFps,
            drops = log.drops.mapNotNull { (k, n) -> if (k >= shift) (k - shift) to n else null }
        )

    /** 关键帧的输出帧序号：抽帧成片 PTS = 帧序号 × 1e6/dstFps 的闭式反推（四舍五入） */
    fun keyFrameIndexOf(baseUs: Long, dstFps: Int): Int =
        ((baseUs * dstFps + 500_000L) / 1_000_000L).toInt().coerceAtLeast(0)

    /** 剪辑成片命名：`edit_<源名去扩展名>.mp4`（用户 2026-10-04 定版：处理过的片名上前缀可辨；
     *  MediaStore 同名冲突自行追加序号） */
    fun clipNameOf(srcName: String): String {
        val dot = srcName.lastIndexOf('.')
        val stem = if (dot > 0) srcName.substring(0, dot) else srcName
        return "edit_$stem.mp4"
    }

    /**
     * 音轨样本"是否会被写出"的单一判据（纯函数，JVM 全测）。两处必须吃同一条：
     * ① 建 muxer 轨之前的预扫（决定这条轨进不进容器）；② 拷贝循环里真正写样本。
     *
     * 为什么预扫与写出必须同口径：muxer 的轨在 `addTrack` 时就定型，之后一个样本都没写进去，
     * `muxer.stop()` 必抛（MPEG4Writer 对零样本轨的硬约束）；而导出链的异常路径会删掉整份输出
     * ——视频完好却被判失败。剪辑导出传 `shiftUs = 0` 并带出点 [endRelUs]；选轨导出无出点
     * （[endRelUs] = null）并带对齐平移。
     */
    fun remuxAudioSampleWritable(relUs: Long, shiftUs: Long, endRelUs: Long?): Boolean {
        if (relUs + shiftUs < 0L) return false
        return endRelUs == null || relUs <= endRelUs
    }
}

/**
 * 预扫一条音轨：在 `[baseUs, baseUs + endRelUs]` 内是否至少有**一枚**会被写出的样本。
 *
 * 调用方约束：本函数会把 extractor 的选择与游标全部挪走（只留这一条轨、seek 到 [baseUs]），
 * 返回后必须重新 `selectTrack` 并从 [baseUs] 重新定位。
 *
 * 条件封轨与拷贝循环同口径：连续 [tailTolerance] 枚越界样本判该轨已结束（B 帧重排容忍）。
 */
internal fun audioTrackWritable(
    ex: MediaExtractor,
    track: Int,
    baseUs: Long,
    shiftUs: Long,
    endRelUs: Long?,
    tailTolerance: Int = 8
): Boolean {
    for (i in 0 until ex.trackCount) runCatching { ex.unselectTrack(i) }
    ex.selectTrack(track)
    ex.seekTo(baseUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
    var outOfRange = 0
    try {
        while (outOfRange < tailTolerance) {
            val size = ex.sampleSize
            if (size < 0L) return false
            val rel = ex.sampleTime - baseUs
            if (endRelUs != null && rel > endRelUs) {
                outOfRange++
            } else {
                outOfRange = 0
                if (ClipRule.remuxAudioSampleWritable(rel, shiftUs, endRelUs)) return true
            }
            if (!ex.advance()) return false
        }
        return false
    } finally {
        runCatching { ex.unselectTrack(track) }
    }
}

/** 导出结果：[Done] 带 MediaStore 媒体 id（pending uri 自带，commit 前已定） */
sealed interface ClipResult {
    data class Done(val mediaId: Long, val name: String) : ClipResult
    data class Fail(val reason: ClipError) : ClipResult
}

/**
 * [ClipError.COMMIT]：Muxer 已封轨成片、**只是清 IS_PENDING 没清掉**（Provider 瞬时忙/抛异常）。
 * 与 [MUX] 分开是因为二者归因完全不同——MUX 是没写出来，COMMIT 是写出来了但相册看不见。
 * COMMIT 路径**保留 pending 行不删**（#4 定版，对齐 VideoStore.seal 的"只报不删"口径），
 * 冷启由 [com.wotagei.cam.record.StalePendingSweep.rescuePendingRows] 收录。
 */
enum class ClipError { RANGE, OPEN, MUX, CANCELLED, COMMIT }

/**
 * 执行器（录制管线之外的第二条"读源→写库"链路，入库走与 VideoStore 同一套 IS_PENDING 两段式
 * ——写完清 0 相册立即可见）。
 *
 * 失败语义（#4 定版，对齐录制路 VideoStore.seal 的"只报不删"）：**muxer.stop() 之前**的失败/
 * 取消照旧删 pending 记录（写一半的废段该删，不留 0 字节幽灵条目）；**muxer.stop() 之后**
 * （成片字节已在盘上）commit 全败、取消或任何后续异常一律**保留行不删**：收尾 catch 也按
 * [bytesComplete] 分岔，封轨后异常改道 Fail(COMMIT)（与 commit 全败同口径）。MediaStore 删行
 * 会连带删文件，删记录就是把用户的成片丢掉。留下的 pending 行对外不可见（相册、本应用自己的
 * 列表都查不到），
 * 冷启由 [com.wotagei.cam.record.StalePendingSweep.rescuePendingRows] 收录。
 * sidecar 在「commit 成功」与「全败保留」两条路径都写：被救援回的成片档案要完整；
 * sidecar 写失败只少一份补弧档案，不牵连导出成败。
 */
class ClipExporter(private val ctx: Context) {

    companion object {
        private const val TAG = "ClipExport"

        /** 每轨"连续越界即封轨"的容忍样本数：视频要盖过 B 帧重排深度，音频 AAC 帧间无重排 */
        private const val VIDEO_TAIL_TOLERANCE = 32
        private const val AUDIO_TAIL_TOLERANCE = 8

        /** 进度回调节流：样本粒度太频，按已读字节比例变化超 1% 才回一次 */
        private const val PROGRESS_STEP = 0.01f

        /** 样本缓冲初始容量（4K 码率的视频帧可以远超 1MB，不够时按需翻倍） */
        private const val INITIAL_BUF = 1 shl 20

        /** commit 延时重试两档（#4：Provider 瞬时忙多半秒级恢复，共 4 次尝试 = 立即 2 + 延时 2） */
        private const val COMMIT_RETRY_DELAY_1_MS = 1500L
        private const val COMMIT_RETRY_DELAY_2_MS = 3000L
    }

    /**
     * 把 [src] 的 `[startMs, endMs]` 段无损导出成新入库条目。
     * [track] = 用户选中的音轨（ENV=容器第一条环境音、CAP=第二条内录）；只写这一条，不默认全导。
     * [onProgress] 0..1（IO 线程回调，写 Compose State 是线程安全的）。
     * 取消 = 调用方 cancel 协程；本函数在取消路径上删 pending 记录后原样重抛。
     */
    suspend fun export(
        src: VideoClip,
        startMs: Long,
        endMs: Long,
        track: SelectedTrack,
        onProgress: (Float) -> Unit
    ): ClipResult = withContext(Dispatchers.IO) {
        val range = ClipRule.normalize(startMs, endMs, src.durationMs)
            ?: return@withContext ClipResult.Fail(ClipError.RANGE)
        val (sMs, eMs) = range
        val pendingUri = insertPending(ClipRule.clipNameOf(src.name))
            ?: return@withContext ClipResult.Fail(ClipError.OPEN)
        // 循环在非 suspend 的 copy() 里跑，取消检查靠把 Job 递进去（coroutineContext 是 suspend 属性，
        // 在 copy() 里直接摸不到）
        val job = coroutineContext[Job]
        // muxer.stop() 已过 = 容器里是完整合法 MP4：此后的失败一律保留行不删（#4，救援通道回收）
        var bytesComplete = false
        try {
            val afd = ctx.contentResolver.openAssetFileDescriptor(pendingUri, "rw")
                ?: return@withContext fail(pendingUri, ClipError.OPEN)
            afd.use {
                val muxer = MediaMuxer(afd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                try {
                    // 纯 remux 不动像素：源带容器旋转（自录横屏片常带 90/180/270）时方向只能靠
                    // metadata 透传，不写 hint 成片方向错；源 hint=0 时写出 0、行为不变。
                    // 读数走双源（readSourceRotationHint），写出必须经 exportOrientationHint 统一口径
                    val hint = readSourceRotationHint(ctx, src.uri)
                    muxer.setOrientationHint(exportOrientationHint(hint, ORIENTATION_MUXER_CCW))
                    val baseUs = copy(src, sMs * 1000L, eMs * 1000L, track, job, muxer, onProgress)
                    muxer.stop()
                    bytesComplete = true
                    // 清 pending 失败必须改口上报：IS_PENDING 残留时 MediaStore 对外隐藏该行，
                    // 用户会看到"导出成功"却在相册里找不到成片，且事后无锚点可归因。
                    // 失败多半是 Provider 瞬时忙：立即重试 1 次（现状口径）+ 延时 1.5s/3s 各再试 1 次
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
                        // 全败（共 4 次）：成片字节已在盘上（muxer.stop 已过），删行 = 丢成片
                        //（MediaStore 删行连带删文件，与 VideoStore.seal 同口径只报不删）。
                        // 保留 pending 行，冷启由 StalePendingSweep.rescuePendingRows 收录；
                        // sidecar 按成功路径同款写——被救援回的成片档案要完整。
                        Log.e(TAG, "commit 全败（共 4 次）uri=$pendingUri，成片保留待冷启救援")
                        writeSidecar(src, pendingUri, baseUs)
                        return@withContext ClipResult.Fail(ClipError.COMMIT)
                    }
                    writeSidecar(src, pendingUri, baseUs)
                    val id = ContentUris.parseId(pendingUri)
                    Log.i(TAG, "done id=$id $sMs..$eMs ms baseUs=$baseUs")
                    ClipResult.Done(id, ClipRule.clipNameOf(src.name))
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
     * 拷贝主体：从入点关键帧读到出点，样本原样写进 [muxer]，PTS 平移到 0 起。
     * 音轨只写用户选中的那一条（[track]：ENV=容器第一条、CAP=第二条），不默认全导。
     * B 帧源的 PTS 会在界内界外来回跳（见上）：**判据**（越界封轨/负值丢弃）仍吃 raw 值，
     * **写出的戳**再过逐轨单调桥 [monotonicPts]——同轨倒退会让 MPEG4Writer 抛
     * IllegalStateException 结束导出，录制链在 CodecRecorder.writeSample 有同一层兜底。
     * @return 公共偏移基准（入点关键帧的源 PTS，微秒）——sidecar 平移反推要用
     */
    private fun copy(
        src: VideoClip,
        startUs: Long,
        endUs: Long,
        track: SelectedTrack,
        job: Job?,
        muxer: MediaMuxer,
        onProgress: (Float) -> Unit
    ): Long {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, src.uri, null)
            var videoTrack = -1
            // 全部 audio/ 轨按容器轨序收：ENV = 第一条、CAP = 第二条（与 TrackSync 的识别口径同源）。
            // 实际写哪一条由**用户选**（[track]），不再"只认第一条"、也不"全部导出"。
            val audioTracks = ArrayList<Int>()
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    videoTrack < 0 && mime.startsWith("video/") -> videoTrack = i
                    mime.startsWith("audio/") -> audioTracks.add(i)
                }
            }
            if (videoTrack < 0) throw IllegalStateException("no video track")
            ex.selectTrack(videoTrack)

            // 第一步：入点向前对齐关键帧（此阶段只选了视频轨，扫到的都是视频样本）
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var buf = ByteBuffer.allocate(INITIAL_BUF)
            var baseUs = -1L
            while (baseUs < 0) {
                val size = ex.sampleSize // 注意：getSampleSize 返回 long（android-34 javap 实证）
                if (size < 0) throw IllegalStateException("no sync frame before EOF")
                if (size > buf.capacity()) buf = ByteBuffer.allocate(size.toInt() * 2)
                ex.readSampleData(buf, 0)
                if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) baseUs = ex.sampleTime
                else ex.advance()
            }
            val endRel = endUs - baseUs

            // 第二步：只对**用户选中的那条**音轨做预扫。两条理由：
            // ① 用户选了哪条就只导哪条（不再默认全导，也不再只认第一条）；
            // ② 选中的轨在区间内没有可写样本（或源里没有第二条）时不建轨——零样本轨会让
            //    muxer.stop() 抛异常，而导出链的异常路径会删掉整份输出（视频明明完好）。
            val audioOrdinal = if (track == SelectedTrack.ENV) 0 else 1
            val requested = audioTracks.getOrNull(audioOrdinal)
            val audioTrack = requested?.takeIf {
                audioTrackWritable(ex, it, baseUs, shiftUs = 0L, endRelUs = endRel, tailTolerance = AUDIO_TAIL_TOLERANCE)
            }
            if (requested == null) {
                Log.i(TAG, "源只有 ${audioTracks.size} 条音轨，选中轨#$audioOrdinal 不存在，本片只导出视频")
            } else if (audioTrack == null) {
                Log.w(TAG, "选中轨#$audioOrdinal 在剪辑区间内没有可写样本，本片只导出视频")
            }

            // 第三步：视频 +（可写的）选中音轨一起选中，两轨同位（baseUs）起读，相对关系不破坏
            ex.selectTrack(videoTrack)
            if (audioTrack != null) ex.selectTrack(audioTrack)
            ex.seekTo(baseUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val vIdx = muxer.addTrack(ex.getTrackFormat(videoTrack))
            val aIdx = audioTrack?.let { muxer.addTrack(ex.getTrackFormat(it)) } ?: -1
            muxer.start()

            val info = MediaCodec.BufferInfo()
            val srcBytes = src.sizeBytes.coerceAtLeast(1L)
            var readTotal = 0L
            var lastReported = 0f
            var videoWritten = 0
            var videoDropped = 0
            var vTail = 0
            var aTail = 0
            var videoClosed = false
            var audioClosed = aIdx < 0
            // B 帧源的单调兜底账本（#8）：两轨各一枚 lastPts + 修正计数 + 最大倒退量。
            // 同轨倒退会让 MPEG4Writer 抛 IllegalStateException 让导出当场结束，写前过桥兜底
            var vLastPts = -1L
            var aLastPts = -1L
            var vPtsFixes = 0
            var aPtsFixes = 0
            var vPtsMaxBackUs = 0L
            var aPtsMaxBackUs = 0L
            while (!videoClosed || !audioClosed) {
                // 取消检查挂在每个样本上：这一步很轻，是循环里最密的检查点
                job?.ensureActive()
                val size = ex.sampleSize // getSampleSize 返回 long；写入路径用 int 版
                if (size < 0) break // EOF（尾轨先到头是常态，另一轨继续）
                if (size > buf.capacity()) buf = ByteBuffer.allocate(size.toInt() * 2)
                ex.readSampleData(buf, 0)
                val isVideo = ex.sampleTrackIndex == videoTrack
                val rel = ex.sampleTime - baseUs
                if (isVideo) {
                    if (rel > endRel) {
                        // 连续越界满容忍数才封轨：B 帧重排会让 PTS 在界内界外来回跳
                        if (++vTail >= VIDEO_TAIL_TOLERANCE) videoClosed = true
                    } else {
                        vTail = 0
                        // 负 rel（开放 GOP 的前导 B 帧）丢弃：与音频分支同口径。直写负 pts 会让
                        // Muxer 给整轨加巨大 baseMediaDecodeTime 偏移 + 畸形 ctts（见 ClipRule.keepSample）
                        if (ClipRule.keepSample(rel)) {
                            // 写出前过单调桥：修正属 B 帧源预期行为，逐样本只记 d 级（默认关）不刷屏
                            val fix = monotonicPts(vLastPts, rel)
                            if (fix.advanced) {
                                vPtsFixes++
                                if (fix.backUs > vPtsMaxBackUs) vPtsMaxBackUs = fix.backUs
                                Log.d(TAG, "视频轨 pts 修正 cur=$rel → ${fix.ptsUs}（back=${fix.backUs}us）")
                            }
                            vLastPts = fix.ptsUs
                            // flags 直传 extractor 的 sampleFlags：SAMPLE_FLAG_SYNC(1) 与
                            // BUFFER_FLAG_SYNC_FRAME(1) 同值同义，Extractor→Muxer 转写的通行做法
                            @Suppress("WrongConstant")
                            info.set(0, size.toInt(), fix.ptsUs, ex.sampleFlags)
                            muxer.writeSampleData(vIdx, buf, info)
                            videoWritten++
                        } else {
                            videoDropped++
                        }
                    }
                } else if (!audioClosed) {
                    if (rel > endRel) {
                        if (++aTail >= AUDIO_TAIL_TOLERANCE) audioClosed = true
                    } else if (aIdx >= 0 && ClipRule.keepSample(rel)) {
                        // 早于公共基准的音频样本（AAC 帧界早于视频关键帧）丢弃：负 PTS 会崩 Muxer。
                        // 单调桥与视频轨同口径（两轨独立账本）
                        val fix = monotonicPts(aLastPts, rel)
                        if (fix.advanced) {
                            aPtsFixes++
                            if (fix.backUs > aPtsMaxBackUs) aPtsMaxBackUs = fix.backUs
                            Log.d(TAG, "音频轨 pts 修正 cur=$rel → ${fix.ptsUs}（back=${fix.backUs}us）")
                        }
                        aLastPts = fix.ptsUs
                        @Suppress("WrongConstant") // 同上：sampleFlags 与 BUFFER_FLAG_* 同值体系
                        info.set(0, size.toInt(), fix.ptsUs, ex.sampleFlags)
                        muxer.writeSampleData(aIdx, buf, info)
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
            // 修正汇总（B 帧源预期行为）：逐样本明细在 d 档（默认关），收尾只落一条 i 级。
            // 插值用 ${} 花括号形式（与 CodecRecorder 段尾汇总同款）：源码守卫只认花括号里的代码
            if (vPtsFixes > 0 || aPtsFixes > 0) {
                Log.i(TAG, "pts修正=v${vPtsFixes}(back=${vPtsMaxBackUs}) a${aPtsFixes}(back=${aPtsMaxBackUs})")
            }
            // 丢弃必须可见：静默丢样本无从判断是源没有 B 帧还是守卫在起作用
            if (videoDropped > 0) {
                Log.i(TAG, "视频负PTS样本丢弃=$videoDropped（开放GOP前导B帧，丢弃为保 Muxer 时间轴合规）")
            }
            Log.i(TAG, "音轨写入：选中轨#$audioOrdinal ${if (aIdx >= 0) "已建轨" else "未建轨(纯视频)"} 容器音轨 ${audioTracks.size} 条")
            if (videoWritten == 0) throw IllegalStateException("empty range: no video sample")
            return baseUs
        } finally {
            runCatching { ex.release() }
        }
    }

    // region MediaStore pending 两段式（与 VideoStore 同一模式，目录换成 Clip 子目录）

    /**
     * commit 延时重试一档：等 [delayMs] 后再清一次 IS_PENDING。
     * 醒后先 ensureActive（用户已取消就向上抛走 cancel 分支按 bytesComplete 处置，不白等）；
     * 每次失败只落一条 [Log.w]（全败另有 [Log.e] 锚点，不逐次刷屏）。
     */
    private suspend fun delayedCommit(uri: android.net.Uri, job: Job?, delayMs: Long): Boolean {
        delay(delayMs)
        job?.ensureActive()
        if (commit(uri)) return true
        Log.w(TAG, "commit 延时重试仍败 uri=$uri delayMs=$delayMs")
        return false
    }

    /**
     * sidecar 平移落盘（commit 成功与"全败保留"两条路径共用）：成片 PTS = 帧序号 × 1e6/dstFps，
     * 闭式反推关键帧位次。写失败只少一份补弧档案，不牵连导出成败。
     */
    private fun writeSidecar(src: VideoClip, pendingUri: android.net.Uri, baseUs: Long) {
        val srcLog = src.dataPath?.let { ArcDropLog.readFrom(it) }
        val outPath = queryData(pendingUri)
        if (srcLog != null && outPath != null) {
            val shift = ClipRule.keyFrameIndexOf(baseUs, srcLog.dstFps)
            val ok = ArcDropLog.writeTo(outPath, ClipRule.remapDrops(srcLog, shift))
            Log.i(TAG, "sidecar remap shift=$shift ok=$ok")
        }
    }

    private fun insertPending(name: String): android.net.Uri? = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.RELATIVE_PATH, ClipRule.RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        }
        ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
    }.getOrNull()

    /**
     * 清 IS_PENDING（两段式的第二段）。true = 相册里立刻可见。
     *
     * 判据取 update 的**受影响行数**，判据与 [com.wotagei.cam.record.VideoStore.commit] 逐字同口径：
     * `update` 不抛异常也可能一行没改（URI 已失效），把这种"没清掉"当成功，等于把
     * "导出成功但相册里没有"继续瞒下去。抛异常与 0 行一律归 false，由调用方重试并改口上报。
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

    /** pending 记录的真实路径（DATA 列）；sidecar 与它同目录同名 */
    private fun queryData(uri: android.net.Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    }.getOrNull()

    /**
     * 写一半（muxer.stop 之前）的失败/取消收尾：删 pending 记录。sidecar 尚未写，无需清理；
     * muxer.stop 之后的失败**不走这里**（成片字节在盘上，删行 = 丢成片，见类注）。
     */
    private fun fail(uri: android.net.Uri, reason: ClipError): ClipResult {
        runCatching { ctx.contentResolver.delete(uri, null, null) }
        Log.i(TAG, "failed reason=$reason")
        return ClipResult.Fail(reason)
    }

    // endregion
}
