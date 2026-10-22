package com.wotagei.cam.player

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.wotagei.cam.R
import com.wotagei.cam.diag.Diag as Log
import com.wotagei.cam.record.AudioPreflightPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

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
 * done 标记 + 偏移毫秒。MANUAL 低置信度按"录制起点对齐"落 0；AUTO（停止后自动校准）
 * 低置信/越界直接不写（置信度口径复用 [AudioSync.MIN_CONFIDENCE]，后校准门槛见
 * [com.wotagei.cam.record.AudioPreflightPolicy.MIN_CONFIDENCE]）。
 */
object TrackSync {

    /** 偏移来源：AUTO = 录制停止后自动校准（可被手动覆盖）；MANUAL = 用户点「同步音轨」 */
    enum class Source { AUTO, MANUAL }

    /** 后校准的落盘决策（纯函数，JVM 全测）：只有 Write 才允许写 prefs */
    internal sealed interface AutoDecision {
        /** 已有手动结果：自动校准永不覆盖 */
        data object ManualProtected : AutoDecision
        /** 置信度不足：不写 */
        data object LowConfidence : AutoDecision
        /** 超出 ±[AudioPreflightPolicy.MAX_AUTO_OFFSET_MS]：只记日志，不写 */
        data object OutOfRange : AutoDecision
        /** 写入该偏移（|offset| < 20ms 已归零） */
        data class Write(val offsetMs: Long) : AutoDecision
    }

    data class Result(
        val ok: Boolean,
        /** 内录轨相对环境轨的偏移（正=内录晚响）；MANUAL 低置信度=0（按录制起点对齐） */
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
     * [source] 决定落盘策略：MANUAL 永远覆盖（用户动作）；AUTO 受 [autoDecision] 约束，
     * 低置信/越界不写、已有 MANUAL 结果不覆盖。
     */
    suspend fun run(context: Context, uri: Uri, source: Source = Source.MANUAL): Result {
        return withContext(Dispatchers.Default) {
            val existing = if (source == Source.AUTO) cachedSource(context, uri) else null
            if (source == Source.AUTO && existing == Source.MANUAL) {
                Log.i(TAG, "自动校准跳过：该片已有手动同步结果 uri=$uri")
                return@withContext Result(ok = false, dualAudio = true)
            }
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
            if (source == Source.MANUAL) {
                // MANUAL：低置信度按录制起点落 0（用户主动同步，落盘语义保留）；
                // AUTO 不走这里——低置信由 autoDecision 直接判"不写"。
                val final = resolveOffsetMs(offsetMs, low)
                persist(context, uri, final, Source.MANUAL, confidence)
                Result(ok = true, offsetMs = final, confidence = confidence, lowConfidence = low)
            } else {
                when (val decision = autoDecision(offsetMs, confidence, existing)) {
                    is AutoDecision.Write -> {
                        // 关键：写盘必须走锁内复核的 persistAuto。解码前读到的 existing 只是快速拒绝，
                        // 后台 AUTO 与用户 MANUAL 并发时，只有锁内最后一眼才能保证 MANUAL 优先。
                        val written = persistAuto(context, uri, decision.offsetMs, confidence)
                        Result(
                            ok = written,
                            offsetMs = decision.offsetMs,
                            confidence = confidence,
                            lowConfidence = low
                        )
                    }
                    AutoDecision.LowConfidence -> {
                        Log.i(TAG, "自动校准低置信不写：offset=$offsetMs conf=$confidence uri=$uri")
                        Result(ok = false, offsetMs = offsetMs, confidence = confidence, lowConfidence = true)
                    }
                    AutoDecision.OutOfRange -> {
                        Log.i(TAG, "自动校准偏移超范围只记日志：offset=$offsetMs conf=$confidence uri=$uri")
                        Result(ok = false, offsetMs = offsetMs, confidence = confidence)
                    }
                    AutoDecision.ManualProtected ->
                        Result(ok = false, offsetMs = offsetMs, confidence = confidence)
                }
            }
        }
    }

    /** 录制停止后的自动校准入口（PostStopSync 专用；低置信/越界/手动保护都不写） */
    suspend fun autoRun(context: Context, uri: Uri): Result = run(context, uri, Source.AUTO)

    /** 一次原子读出的同步结果（offset/source/confidence 来自同一把锁，UI 不会读到半新半旧） */
    data class SyncSnapshot(val offsetMs: Long, val source: Source, val confidence: Float)

    fun cachedSync(context: Context, uri: Uri): SyncSnapshot? = synchronized(ioLock) {
        val sp = prefs(context)
        val key = uri.toString()
        if (!sp.getBoolean(doneKey(key), false)) return@synchronized null
        val source = sourceLocked(sp, key) ?: return@synchronized null
        SyncSnapshot(
            offsetMs = sp.getLong(offsetKey(key), 0L),
            source = source,
            confidence = sp.getFloat(confKey(key), 0f)
        )
    }

    /** 之前跑过同步的偏移（毫秒）；从未跑过返回 null。导出弹窗读数与导出平移共用它 */
    fun cachedOffsetMs(context: Context, uri: Uri): Long? = cachedSync(context, uri)?.offsetMs

    /**
     * 上次结果的来源；null = 未校准。
     * **旧记录兼容**：`sync_done=true` 但无 `sync_source`（本功能上线前只有手动按钮），视为 MANUAL
     * ——这样自动校准不会把老用户手动调好的结果冲掉。
     */
    fun cachedSource(context: Context, uri: Uri): Source? = cachedSync(context, uri)?.source

    /** 上次结果的置信度；未校准返回 null */
    fun cachedConfidence(context: Context, uri: Uri): Float? = synchronized(ioLock) {
        val sp = prefs(context)
        val key = uri.toString()
        if (sp.getBoolean(doneKey(key), false)) sp.getFloat(confKey(key), 0f) else null
    }

    /** 上次结果的落盘时刻（epoch ms）；未校准返回 null */
    fun cachedAtMs(context: Context, uri: Uri): Long? = synchronized(ioLock) {
        val sp = prefs(context)
        val key = uri.toString()
        if (sp.getBoolean(doneKey(key), false)) sp.getLong(atKey(key), 0L) else null
    }

    /**
     * 写 AUTO 结果：**锁内复核 source**，已有 MANUAL 则拒绝。返回 true = 真的写入了。
     * 这是 AUTO 的唯一落盘入口——解码前那次读取不能替代这里的复核（TOCTOU）。
     */
    internal fun persistAuto(context: Context, uri: Uri, offsetMs: Long, confidence: Float): Boolean =
        synchronized(ioLock) {
            val key = uri.toString()
            writeAutoPrefs(prefs(context), key, offsetMs, confidence).also { written ->
                if (!written) Log.i(TAG, "自动校准结果不覆盖手动同步：uri=$uri")
            }
        }

    private fun persist(context: Context, uri: Uri, offsetMs: Long, source: Source, confidence: Float) {
        synchronized(ioLock) {
            writeSyncPrefs(
                prefs(context), uri.toString(), offsetMs, source, confidence,
                System.currentTimeMillis()
            )
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PlayerEngine.PREFS, Context.MODE_PRIVATE)

    private fun sourceLocked(sp: android.content.SharedPreferences, key: String): Source? =
        sourceOfPrefs(sp, key)

    /**
     * 纯 prefs 读写（internal 供 JVM 测试注入 FakePrefs）：
     * 生产路径在 [ioLock] 内调用；测试可独立验证「manual 已写 → auto 复核拒绝」。
     */
    internal fun sourceOfPrefs(sp: android.content.SharedPreferences, key: String): Source? =
        sourceOfStored(sp.getBoolean(doneKey(key), false), sp.getString(sourceKey(key), null))

    internal fun writeSyncPrefs(
        sp: android.content.SharedPreferences,
        key: String,
        offsetMs: Long,
        source: Source,
        confidence: Float,
        nowMs: Long = System.currentTimeMillis()
    ) {
        sp.edit()
            .putBoolean(doneKey(key), true)
            .putLong(offsetKey(key), offsetMs)
            .putString(sourceKey(key), if (source == Source.AUTO) "auto" else "manual")
            .putFloat(confKey(key), confidence)
            .putLong(atKey(key), nowMs)
            .apply()
    }

    /** AUTO 写盘的行为桥：锁内复核 MANUAL 保护；返回 false = 被手动结果拦下 */
    internal fun writeAutoPrefs(
        sp: android.content.SharedPreferences,
        key: String,
        offsetMs: Long,
        confidence: Float,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (sourceOfPrefs(sp, key) == Source.MANUAL) return false
        writeSyncPrefs(sp, key, offsetMs, Source.AUTO, confidence, nowMs)
        return true
    }

    private fun doneKey(key: String) = "sync_done:$key"
    private fun offsetKey(key: String) = "sync_offset:$key"
    private fun sourceKey(key: String) = "sync_source:$key"
    private fun confKey(key: String) = "sync_conf:$key"
    private fun atKey(key: String) = "sync_at:$key"

    /** 读写锁：SharedPreferences 自身线程安全，但「读 source → 决定写」是复合操作，必须整体互斥 */
    private val ioLock = Any()

    private const val TAG = "WotaTrackSync"
    /** 后校准的最小可辨偏移：低于它落 0（与预校准同一听感门槛） */
    private const val AUTO_MIN_OFFSET_MS = AudioPreflightPolicy.MIN_PRE_SHIFT_MS.toLong()

    // region 纯函数（JVM 单测钉口径）

    /**
     * 后校准落盘决策（TrackSyncAutoTest 直测的行为桥）：
     * 手动保护 > 低置信 > 越界 > 20ms 归零 > 写原值。
     */
    internal fun autoDecision(
        offsetMs: Long,
        confidence: Float,
        existing: Source?,
        minConfidence: Double = AudioPreflightPolicy.MIN_CONFIDENCE
    ): AutoDecision {
        if (existing == Source.MANUAL) return AutoDecision.ManualProtected
        if (confidence < minConfidence) return AutoDecision.LowConfidence
        if (!AudioPreflightPolicy.autoOffsetWithinRange(offsetMs, confidence.toDouble(), minConfidence)) {
            return AutoDecision.OutOfRange
        }
        val value = if (abs(offsetMs) < AUTO_MIN_OFFSET_MS) 0L else offsetMs
        return AutoDecision.Write(value)
    }

    /** 旧记录来源解析（纯函数）：未 done → null；done 但无/未知 source → 老的手动记录 */
    internal fun sourceOfStored(done: Boolean, rawSource: String?): Source? {
        if (!done) return null
        return if (rawSource == "auto") Source.AUTO else Source.MANUAL
    }

    /**
     * 合成总偏移：互相关的"内容差"（各轨已按自身首样本归零）+ 两轨首样本的容器时刻差。
     * cap 0 时刻晚于 env 0 时刻 = 内录整体晚，直接相加。
     */
    fun combineOffsetMs(contentOffsetMs: Long, capFirstPtsUs: Long, envFirstPtsUs: Long): Long =
        contentOffsetMs + (capFirstPtsUs - envFirstPtsUs) / 1000L

    /** 低置信度=按录制起点对齐（0）；正常态原样保留。仅 MANUAL 落盘路径使用 */
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

    private fun openExtractor(context: Context, uri: Uri): MediaExtractor? {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(context, uri, null)
            ex
        } catch (e: Exception) {
            // setDataSource 抛错时临时 extractor 只在这里有引用：调用方的 finally 只收得到
            // 成功返回的实例，这里不 release 就是每次坏 URI 泄漏一枚原生 extractor。
            runCatching { ex.release() }
            null
        }
    }

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
