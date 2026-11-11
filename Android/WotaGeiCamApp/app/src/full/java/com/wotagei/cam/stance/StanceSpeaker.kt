package com.wotagei.cam.stance

import com.wotagei.cam.diag.Diag

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper

/**
 * 站位播报器（M3a，§5.2）：预录素材拼接（非 TTS）。
 * 词位→asset 文件名映射与方案 §5.2 表逐字一致；**占位哔声 wav 随包，用户素材
 * 到位后同名替换 assets 下 voice 目录的文件，零改码**。
 * SoundPool 池 + 顺序播放队列（词间 300ms）；音量走媒体流（USAGE_ASSISTANCE_
 * NAVIGATION_GUIDANCE，外放会被自家麦克风收进环境回声——设置页披露文案照 §5.2）。
 */
class StanceSpeaker(context: Context) {
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .build()
    private val ids = HashMap<String, Int>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<String>()
    private val activeStreams = LinkedHashSet<Int>()
    private var queueGen = 0
    private var paused = false

    init {
        for (name in WORD_FILES) {
            try {
                val afd = context.assets.openFd("voice/$name.wav")
                ids[name] = pool.load(afd, 1)
                afd.close()
            } catch (e: Exception) {
                Diag.w(TAG, "语音占位缺失 voice/$name.wav：${e.message}")
            }
        }
    }

    /**
     * 播报一句话（如「2号位，往左」）→ 拆词位入队顺序播。同文本节流由引擎负责。
     * **口癖「的说」是一轮的句号**（用户 r06 口径修正）：一次轮询 = 引擎把全体状态
     * 播完（可能跨多拍多条，节流 2s/条）——本轮**静默 [END_QUIET_MS]** 后补播一次
     * end，轮内有新句则取消计时继续播。每句都带口癖是错的（用户澄清）。
     */
    fun speak(text: String) {
        if (paused) return
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return
        pending.clear()
        pending.addAll(tokens)
        queueGen++
        drain(queueGen)
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

    private fun drain(gen: Int) {
        val next = pending.firstOrNull() ?: return
        if (paused || gen != queueGen) return
        val id = ids[next] ?: 0
        if (id != 0) {
            val stream = pool.play(id, 1f, 1f, 1, 0, 1f)
            if (stream != 0) activeStreams += stream
        }
        pending.removeFirst()
        if (pending.isNotEmpty()) {
            mainHandler.postDelayed({ if (gen == queueGen) drain(gen) }, WORD_GAP_MS)
        } else {
            scheduleEnd(gen)   // 本句播完：若静默窗内无新句 → 口癖收尾
        }
    }

    /**
     * 口癖收尾计时：[END_QUIET_MS] 内无新播报（= 本轮确实播完了）→ 播一次「的说」。
     * 新 speak 会使 queueGen 前进、旧计时器自动失效（轮内继续播不误触）。
     */
    private fun scheduleEnd(gen: Int) {
        mainHandler.postDelayed({
            if (gen != queueGen || pending.isNotEmpty()) return@postDelayed
            if (paused) return@postDelayed
            val id = ids["end"] ?: 0
            if (id != 0) {
                val stream = pool.play(id, 1f, 1f, 1, 0, 1f)
                if (stream != 0) activeStreams += stream
            }
        }, END_QUIET_MS)
    }

    /** 起录/离页时作废延迟任务并停掉已经交给 SoundPool 的 stream，池本身可复用。 */
    fun pause() {
        paused = true
        queueGen++
        pending.clear()
        activeStreams.toList().forEach { pool.stop(it) }
        activeStreams.clear()
    }

    fun resume() {
        paused = false
    }

    /** 作废在途队列并释放。 */
    fun release() {
        pause()
        pool.release()
    }

    companion object {
        private const val TAG = "StanceSpeaker"
        const val WORD_GAP_MS = 300L

        /** 口癖静默窗：本轮播完后无新句达此时长才补「的说」（须 > 节流间隔 2s，防轮内误触）。 */
        const val END_QUIET_MS = 2400L

        /** 词位文件名（assets 的 voice 目录下同名点 wav；用户素材同名替换零改码）。 */
        val WORD_FILES = listOf(
            "num_1", "num_2", "num_3", "num_4", "num_5",
            "dir_left", "dir_right", "dir_forward", "dir_backward",
            "st_done", "all_done", "st_missing", "st_extra", "end",
        )
    }
}
