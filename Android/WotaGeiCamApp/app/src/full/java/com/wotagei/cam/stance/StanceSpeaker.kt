package com.wotagei.cam.stance

import com.wotagei.cam.diag.Diag

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import java.io.InputStream

/**
 * 站位播报器（M3a，§5.2）：预录素材拼接（非 TTS）。
 * 词位→asset 文件名映射与方案 §5.2 表逐字一致；**占位哔声 wav 随包，用户素材
 * 到位后同名替换 assets 下 voice 目录的文件，零改码**。
 *
 * 轮末语义（Y74）：不再用「最后词提交后固定 2.4s 静默」猜轮末。引擎在
 * [EngineOutput.roundEnded] 显式标记本轮最后一句；调用方用
 * `speak(line, roundEnded=true)` 入队，本类把「的说」词位**直接排在队列尾部**，
 * 因此轮内还有后续号位（间隔 3s+）时也不会提前响尾词。词与词、词与尾词的间隔
 * 按各 wav 素材实际时长安排（解析 WAV 头，缺失时回退保守值），避免长词重叠。
 *
 * AudioFocus（Y75）：首次发声申请 AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK；申请被拒或
 * 焦点丢失时清空待播队列并停掉已提交 stream；暂停/尾词播完归还焦点。轮末由
 * [endRound] 显式收口——本轮没有「的说」可播时也必须归还，不能依赖尾词词位存在。
 */
class StanceSpeaker(context: Context) {
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(audioAttributes)
        .build()
    private val ids = HashMap<String, Int>()
    private val durationsMs = HashMap<String, Long>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<String>()
    private val activeStreams = LinkedHashSet<Int>()
    private val audioManager = context.applicationContext
        .getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var queueGen = 0
    private var paused = false
    private var draining = false
    private var hasFocus = false

    /** 本轮已显式收口：队列真正播完（而非提交完）后必须归还焦点。 */
    private var releaseAfterDrain = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        mainHandler.post {
            when {
                change == AudioManager.AUDIOFOCUS_GAIN -> hasFocus = true
                change <= 0 -> {
                    // 焦点被拒/丢失：本轮不再发声，待播词全部作废（保留池，恢复后可复用）。
                    hasFocus = false
                    clearQueueAndStop()
                }
            }
        }
    }

    /** minSdk 29 起用带 AudioAttributes 的焦点请求，避免已弃用的 stream/gain 三重载。 */
    private val focusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener(focusListener)
            .build()

    init {
        for (name in WORD_FILES) {
            try {
                val afd = context.assets.openFd("voice/$name.wav")
                ids[name] = pool.load(afd, 1)
                afd.close()
            } catch (e: Exception) {
                Diag.w(TAG, "语音占位缺失 voice/$name.wav：${e.message}")
            }
            // 时长解析失败不影响发声：只影响词间隔精度，回退 [DEFAULT_WORD_MS]。
            wavDurationMs(context, name)?.let { durationsMs[name] = it }
        }
    }

    /**
     * 播报一句话（如「2号位，往左」）→ 拆词位入队顺序播。同文本节流由引擎负责。
     * [roundEnded]=true 表示这是本轮最后一句：把「的说」作为词位直接追加到队列尾，
     * 队列前面的词（包括后续到达的新一轮）播完才轮到它。
     */
    @JvmOverloads
    fun speak(text: String, roundEnded: Boolean = false) {
        if (paused) return
        val tokens = tokenize(text)
        if (tokens.isEmpty()) {
            // Y74/Y75：轮末已到但这句话没有可播词位（空句/未识别文本）——不能吞掉轮末，
            // 否则上一轮持有的焦点永远等不到「的说」。转交 [endRound] 保证队列尾归还。
            if (roundEnded) endRound()
            return
        }
        if (!ensureFocus()) return
        // 新的一轮重新开始，上一轮延迟归还不能误伤本轮（新轮稍后会自行收口）。
        releaseAfterDrain = false
        pending.addAll(tokens)
        if (roundEnded) {
            pending.add("end")
            releaseAfterDrain = true
        }
        if (!draining) {
            draining = true
            drain(queueGen)
        }
    }

    /**
     * 显式轮末（Y74/Y75）：即使本轮没有可播的尾词，也必须让焦点在已提交词真正播完后归还。
     * 队列还有词、或最后一个词仍在播时只做标记，由 [drain] 在素材时长走完的回调里归还；
     * 真正空闲（队列空且没有在等播的词）才立即归还。
     */
    fun endRound() {
        if (paused) return
        releaseAfterDrain = true
        if (pending.isEmpty() && !draining) {
            releaseAfterDrain = false
            abandonFocus()
        }
    }

    /** 「X号位，<词>」/「缺人」/「多人」/「全员，完成」→ 词位文件名序列。 */
    private fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        when (text) {
            "缺人" -> { out.add("st_missing"); return out }
            "多人" -> { out.add("st_extra"); return out }
            "全员，完成" -> { out.add("all_done"); return out }
        }
        val m = Regex("^(\\d)号位，(.+)$").find(text) ?: return out
        out.add("num_${m.groupValues[1]}")
        when (m.groupValues[2]) {
            "往左" -> out.add("dir_left")
            "往右" -> out.add("dir_right")
            "前沿" -> out.add("dir_forward")
            "往前" -> out.add("dir_forward")
            "后沿" -> out.add("dir_backward")
            "往后" -> out.add("dir_backward")
            "完成" -> out.add("st_done")
        }
        return out
    }

    /**
     * 顺序播词：每个词播完的等待时间 = 该素材时长 + 词间静默。队列空了也要等
     * 最后一个词的真实时长走完才结束本轮；期间新词继续入队，轮末收口统一在回调里归还。
     */
    private fun drain(gen: Int) {
        if (paused || gen != queueGen) {
            draining = false
            return
        }
        val next = pending.firstOrNull()
        if (next == null) {
            draining = false
            return
        }
        if (!ensureFocus()) {
            draining = false
            return
        }
        pending.removeFirst()
        val id = ids[next] ?: 0
        if (id != 0) {
            val stream = pool.play(id, 1f, 1f, 1, 0, 1f)
            if (stream != 0) activeStreams += stream
        }
        val wait = durationMs(next) + WORD_GAP_MS
        // 最后一个词也要等素材时长真正走完才结束本轮：期间到达的新词继续入队，
        // 轮末收口（即使没有「的说」）也在回调里归还，不能在词还在播时就放焦点。
        mainHandler.postDelayed({
            if (gen == queueGen && !paused) {
                if (pending.isNotEmpty()) {
                    drain(gen)
                } else {
                    draining = false
                    if (next == "end" || releaseAfterDrain) {
                        releaseAfterDrain = false
                        abandonFocus()
                    }
                }
            } else {
                draining = false
            }
        }, wait)
    }

    private fun ensureFocus(): Boolean {
        val manager = audioManager ?: return true
        if (hasFocus) return true
        val result = manager.requestAudioFocus(focusRequest)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            clearQueueAndStop()
            return false
        }
        hasFocus = true
        return true
    }

    private fun abandonFocus() {
        if (hasFocus) {
            runCatching { audioManager?.abandonAudioFocusRequest(focusRequest) }
        }
        hasFocus = false
    }

    private fun clearQueueAndStop() {
        queueGen++
        draining = false
        releaseAfterDrain = false
        pending.clear()
        activeStreams.toList().forEach { pool.stop(it) }
        activeStreams.clear()
    }

    /** 起录/离页时作废延迟任务并停掉已经交给 SoundPool 的 stream，池本身可复用。 */
    fun pause() {
        paused = true
        clearQueueAndStop()
        abandonFocus()
    }

    fun resume() {
        paused = false
    }

    /** 作废在途队列并释放。 */
    fun release() {
        pause()
        pool.release()
    }

    private fun durationMs(token: String): Long = durationsMs[token] ?: DEFAULT_WORD_MS

    /** 读 WAV 头算时长（PCM/任意 chunk 顺序）；非 WAV/流式 data size 时返回 null。 */
    private fun wavDurationMs(context: Context, name: String): Long? = try {
        context.assets.open("voice/$name.wav").use { input -> parseWavDurationMs(input) }
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val TAG = "StanceSpeaker"
        const val WORD_GAP_MS = 300L

        /** 素材时长不可解析时的保守词长（足够覆盖占位哔声，又不至于拖慢队列）。 */
        const val DEFAULT_WORD_MS = 600L

        /** 词位文件名（assets 的 voice 目录下同名点 wav；用户素材同名替换零改码）。 */
        val WORD_FILES = listOf(
            "num_1", "num_2", "num_3", "num_4", "num_5",
            "dir_left", "dir_right", "dir_forward", "dir_backward",
            "st_done", "all_done", "st_missing", "st_extra", "end",
        )

        /**
         * 纯函数：解析 RIFF/WAVE 的 fmt.byteRate 与 data.size → 毫秒。
         * 供 JVM 单测钉死队列节奏；异常结构返回 null（调用方回退默认词长）。
         */
        internal fun parseWavDurationMs(input: InputStream): Long? {
            val bytes = input.readBytes()
            if (bytes.size < 12) return null
            if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") return null
            if (String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return null
            var pos = 12
            var byteRate = 0L
            var dataStart = -1
            var dataSize = -1L
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4, Charsets.US_ASCII)
                val size = le32(bytes, pos + 4)
                if (size < 0) return null
                val body = pos + 8
                if (id == "fmt ") {
                    if (body + 16 > bytes.size) return null
                    byteRate = le32(bytes, body + 8)
                } else if (id == "data") {
                    dataStart = body
                    dataSize = size
                    break
                }
                // pos 指向块头（id+size），前进量必须从块头算；若从 body 再 +8，
                // 会跳过 data 块头落到音频字节里，真实素材永远解析失败退回默认词长。
                val advance = 8L + size + (size and 1L)
                if (pos + advance > bytes.size) return null
                pos = (pos + advance).toInt()
            }
            if (byteRate <= 0 || dataStart < 0) return null
            if (dataSize <= 0 || dataSize == 0xFFFF_FFFFL) dataSize = (bytes.size - dataStart).toLong()
            if (dataSize <= 0) return null
            return dataSize * 1000L / byteRate
        }

        private fun le32(b: ByteArray, off: Int): Long =
            ((b[off].toLong() and 0xFF) or
                ((b[off + 1].toLong() and 0xFF) shl 8) or
                ((b[off + 2].toLong() and 0xFF) shl 16) or
                ((b[off + 3].toLong() and 0xFF) shl 24))
    }
}