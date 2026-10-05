package com.wotagei.cam.player

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.wotagei.cam.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 内录体系批 4：单文件双音轨的同步计算与持久化。
 *
 * 【音轨识别口径（审查修订后定版）】
 * - 双音轨段 = 第一条音轨是环境、第二条是内录。env<cap 的轨序由 CodecRecorder 结构保证：
 *   环境轨 addTrack 门排在内录门之前（批 3 轨序红线），双轨并存时序恒成立；
 * - cap-only 单音轨段（环境轨零样本废弃后）**无法与普通录像区分**——三控件静默不显，
 *   内录音频被当普通音轨播放：不误标、不崩，已知边界；
 * - 精确归因的段轨组成 sidecar 方案列为待办，本批不实现。
 *
 * 【偏移语义】[Result.offsetMs] = 内录轨内容相对环境轨晚多少毫秒（**含**两轨首样本的
 * 容器时刻差 [combineOffsetMs]）。正 = 内录晚响。导出选内录轨时按 `-offset` 平移 PTS
 * （见 [TrackRule.shiftedPts]），负 PTS 前导丢弃。
 *
 * 【互相关复用】粗对齐/FFT 精修/置信度全部复用 [AudioSync]（同模块 internal）：
 * decodeMono 已把每轨按自身首样本归零，所以互相关测得的是"内容差"，
 * 容器时刻差（feeder 启动记账里那几毫秒）单独从 extractor 读出后由 [combineOffsetMs] 合入。
 *
 * 【持久化】写 [PlayerEngine.PREFS]（wota_player_ab，keyed-by-uri 同款模式）：
 * done 标记 + 偏移毫秒。低置信度按"录制起点对齐"落 0（置信度口径复用
 * [AudioSync.MIN_CONFIDENCE] 单一真源）。
 */
object TrackSync {

    data class Result(
        val ok: Boolean,
        /** 内录轨相对环境轨的偏移（正=内录晚响）；低置信度=0（按录制起点对齐） */
        val offsetMs: Long = 0L,
        val confidence: Float = 0f,
        val lowConfidence: Boolean = false,
        /** false = 不是双音轨片（单音轨/外来视频，调用方不该发起同步） */
        val dualAudio: Boolean = true,
        val reasonRes: Int? = null
    )

    /** 容器里音轨条数（按 mime 前缀 audio/ 出现顺序数；MediaExtractor 轨序=容器轨序） */
    fun countAudioTracks(context: Context, uri: Uri): Int = audioTrackIndexes(context, uri).size

    /**
     * 跑同步：解环境/内录两条 PCM → 互相关 → 合入容器时刻差 → 落 prefs。
     * 非 suspend 的解码/相关都在 Default 调度器上，UI 层协程取消即中断。
     */
    suspend fun run(context: Context, uri: Uri): Result {
        return withContext(Dispatchers.Default) {
            // 结构性判定在前：容器音轨 <2 = 非双音轨片（dualAudio=false，"没有音轨"文案才成立）
            if (audioTrackIndexes(context, uri).size < 2) return@withContext failResult(structuralDualAudio = false)
            // 双轨在而读不出（extractor 打不开 / 首样本读不到，P3-1：占位值已根除，
            // 读不到的轨被剔除后产出不足两条）：读取失败，dualAudio 保持 true，
            // UI 落 reasonRes 兜底讲"同步失败请重试"，不许误报"没有音轨"（P3-2）
            val firstPtsUs = audioFirstPtsUs(context, uri)
            if (firstPtsUs.size < 2) return@withContext failResult(structuralDualAudio = true)
            val envPcm = AudioSync.decodeMono(context, uri, 0)
                ?: return@withContext failResult(structuralDualAudio = true)
            val capPcm = AudioSync.decodeMono(context, uri, 1)
                ?: return@withContext failResult(structuralDualAudio = true)
            if (envPcm.size < AudioSync.TARGET_SR || capPcm.size < AudioSync.TARGET_SR) {
                return@withContext Result(false, reasonRes = R.string.player_sync_too_short)
            }

            val envRel = AudioSync.envelope(envPcm)
            val capRel = AudioSync.envelope(capPcm)
            val coarseMs = AudioSync.coarseAlign(envRel, capRel)
            val (contentMs, confidence) = AudioSync.refine(envPcm, capPcm, coarseMs)
            val offsetMs = combineOffsetMs(contentMs, firstPtsUs[1], firstPtsUs[0])
            val low = confidence < AudioSync.MIN_CONFIDENCE
            // 低置信度不持久化算出来的偏移：按录制起点对齐（0），提示交给 UI 口径
            val final = resolveOffsetMs(offsetMs, low)
            persist(context, uri, final)
            Result(ok = true, offsetMs = final, confidence = confidence, lowConfidence = low)
        }
    }

    /** 之前跑过同步的偏移（毫秒）；从未跑过返回 null。导出弹窗读数与导出平移共用它 */
    fun cachedOffsetMs(context: Context, uri: Uri): Long? {
        val sp = context.getSharedPreferences(PlayerEngine.PREFS, Context.MODE_PRIVATE)
        return if (sp.getBoolean(doneKey(uri), false)) sp.getLong(offsetKey(uri), 0L) else null
    }

    private fun persist(context: Context, uri: Uri, offsetMs: Long) {
        context.getSharedPreferences(PlayerEngine.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(doneKey(uri), true)
            .putLong(offsetKey(uri), offsetMs)
            .apply()
    }

    private fun doneKey(uri: Uri) = "sync_done:$uri"
    private fun offsetKey(uri: Uri) = "sync_offset:$uri"

    // region 纯函数（JVM 单测钉口径）

    /**
     * 合成总偏移：互相关的"内容差"（各轨已按自身首样本归零）+ 两轨首样本的容器时刻差。
     * cap 0 时刻晚于 env 0 时刻 = 内录整体晚，直接相加。
     */
    fun combineOffsetMs(contentOffsetMs: Long, capFirstPtsUs: Long, envFirstPtsUs: Long): Long =
        contentOffsetMs + (capFirstPtsUs - envFirstPtsUs) / 1000L

    /** 低置信度=按录制起点对齐（0）；正常态原样保留 */
    fun resolveOffsetMs(offsetMs: Long, lowConfidence: Boolean): Long = if (lowConfidence) 0L else offsetMs

    /**
     * 失败分类（P3-1/P3-2 的行为桥，run 的失败点全经它，TrackSyncTest 直测）：
     * 结构性双轨不足 → dualAudio=false +「没有音轨」（文案如实）；
     * 双轨在而读不出（extractor 打不开/首样本读不到/解码失败）→ dualAudio=true +
     * 「同步失败请重试」——探测已判双音轨的片上误报"没有音轨"是误导（P3-2）。
     */
    internal fun failResult(structuralDualAudio: Boolean): Result =
        if (structuralDualAudio) {
            Result(ok = false, dualAudio = true, reasonRes = R.string.player_track_sync_failed)
        } else {
            Result(ok = false, dualAudio = false, reasonRes = R.string.player_sync_no_audio)
        }

    /** 「已同步 +Xms」读数的符号格式：正带加号、负自带减号、零不带号 */
    fun signedMs(offsetMs: Long): String = if (offsetMs > 0L) "+$offsetMs" else "$offsetMs"

    // endregion

    // region extractor 辅助

    private fun openExtractor(context: Context, uri: Uri): MediaExtractor? = runCatching {
        MediaExtractor().apply { setDataSource(context, uri, null) }
    }.getOrNull()

    /** 容器音轨下标表（出现顺序；与 Media3 音频组序同源——都来自容器轨序） */
    private fun audioTrackIndexes(context: Context, uri: Uri): List<Int> {
        val ex = openExtractor(context, uri) ?: return emptyList()
        return try {
            val list = ArrayList<Int>()
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) list.add(i)
            }
            list
        } finally {
            runCatching { ex.release() }
        }
    }

    /**
     * 每条音轨首个样本的容器 PTS（µs，按音轨序）。PCM 归零丢了这层信息，
     * 同步合成时要把"轨起点差"加回来（见 [combineOffsetMs]）。
     * **读不到首样本的轨直接剔除，不设占位值**（P3-1）：Long.MIN_VALUE 一旦进合成式
     * `(capPts − envPts)` 会补码回绕成约 +9.2e15 µs 的巨大正值，荒谬偏移被持久化后
     * 导出时把内录轨全部样本判负丢弃；剔除后产出不足两条由 [run] 按"读取失败"收口。
     */
    private fun audioFirstPtsUs(context: Context, uri: Uri): List<Long> {
        val ex = openExtractor(context, uri) ?: return emptyList()
        return try {
            val list = ArrayList<Long>()
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                ex.selectTrack(i)
                ex.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                if (ex.sampleSize >= 0) list.add(ex.sampleTime) // 读不到就剔除该轨
                ex.unselectTrack(i)
            }
            list
        } finally {
            runCatching { ex.release() }
        }
    }

    // endregion
}
