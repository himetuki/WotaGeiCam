package com.wotagei.cam.record

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.wotagei.cam.core.WotaTiers
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** 需求声道数默认值（`` 参数表：声道硬编码 2） */
const val DEFAULT_AUDIO_CHANNELS = 2

/**
 * 音频侧能力探测（``）：音源、采样率逐档可用性、录音权限。
 * UI 的采样率灰显与两条引擎的音频参数都只走这里，避免两处判断漂移。
 */
object AudioProbe {

    /** 权限检查点 1/2：参数层与采集线程各查一次；缺权限→静默降级纯视频，不报错中断 */
    fun hasRecordPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * 音源：`CAMCORDER(5)` 优先（面向录像调校的信号处理），探测不到内建 mic 时回退 `MIC(1)`。
     * 判据取运行时设备列表，不看机型。
     */
    fun pickSource(ctx: Context): Int {
        val am = ctx.getSystemService(AudioManager::class.java)
        val builtin = am?.getDevices(AudioManager.GET_DEVICES_INPUTS)
            ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC && it.isSource }
        if (builtin != null) return MediaRecorder.AudioSource.CAMCORDER
        Log.i(TAG, "no builtin mic on input devices, audio source fallback to MIC")
        return MediaRecorder.AudioSource.MIC
    }

    /** 采样率 × 声道数的最小缓冲区；返回值 ≤0（ERROR_BAD_VALUE 等）即该组合本机不支持 */
    fun minBufferSize(sampleRate: Int, channels: Int): Int = try {
        AudioRecord.getMinBufferSize(sampleRate, channelConfig(channels), AudioFormat.ENCODING_PCM_16BIT)
    } catch (e: IllegalArgumentException) {
        -1
    }

    /** 需求三档采样率逐档探测：`getMinBufferSize != ERROR_BAD_VALUE` 才在 UI 亮（04 文件 §4） */
    fun isSampleRateSupported(sampleRate: Int, channels: Int = DEFAULT_AUDIO_CHANNELS): Boolean =
        minBufferSize(sampleRate, channels) > 0

    /** 产品采样率档位 ∩ 本机可用档；交集外的档 UI 灰显「本机不支持」 */
    fun supportedSampleRates(channels: Int = DEFAULT_AUDIO_CHANNELS): List<Int> =
        WotaTiers.SAMPLE_RATES.filter { isSampleRateSupported(it, channels) }

    /** `WotaTiers.FPS` 中 >60 的高速档（需 CONSTRAINED_HIGH_SPEED_VIDEO，录入走 CodecRecorder） */
    fun highSpeedFpsTiers(): List<Int> = WotaTiers.FPS.filter { it > Recorders.MR_MAX_FPS }

    fun channelConfig(channels: Int): Int =
        if (channels >= 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO

    /** 振幅 → 分贝：`20 × log10(amp / 0.1)`（`` 音量表）；amp ≤ 0 给下限，UI 直接显示 */
    fun dbOf(amplitude: Int): Float =
        if (amplitude <= 0) -160f else (20.0 * log10(amplitude.toDouble() / 0.1)).toFloat()
}

/**
 * AudioRecord 采集线程 → PCM 包队列（`` 自采管线的唯一通道，供 [CodecRecorder] 消费）。
 *
 * - 生产端：本类线程读 PCM 并算 RMS；消费端：编码器循环取包喂 AAC，两者不共享编码器句柄；
 * - 时间戳统一 `System.nanoTime() / 1000`（μs），与视频 PTS 同源时钟，避免音画漂移；
 * - 停止时投递 4 字节哨兵包（同通道 EOS），消费端据此给编码器打 `BUFFER_FLAG_END_OF_STREAM`。
 */
class AudioFeeder(
    private val ctx: Context,
    val sampleRate: Int,
    val channels: Int,
    val source: Int,
    /** = `MediaFormat.KEY_MAX_INPUT_SIZE` 的来源（`` 参数表：max-input-size = minBufferSize） */
    val minBufferSize: Int
) {

    /** 一段 PCM 与其 μs 时间戳；**ptsUs < 0 = 通道结束哨兵**（真实包时间戳恒为 nanoTime µs、恒正；
     *  哨兵 data 取 [EOS_SIZE] 长只是历史形状——判定看 pts 不看长度，4 字节恰是双声道 2 帧的合法 PCM） */
    class Packet(val data: ByteArray, val ptsUs: Long)

    companion object {
        const val EOS_SIZE = 4
        /** 队列上限：按 ~20ms/包算约 1.3s 音频；满了丢最旧并计数，绝不阻塞采集线程 */
        private const val QUEUE_CAPACITY = 64
        private const val OFFER_WAIT_MS = 20L
        /** 单次读块上限：约 40ms（48k 立体声 8KB），保证 stop 几十毫秒内生效 */
        private const val CHUNK_MAX = 8192
    }

    private val queue = LinkedBlockingQueue<Packet>(QUEUE_CAPACITY)
    private val frameBytes = max(channels, 1) * 2

    /** 半个最小缓冲区为读块，下限 16 帧、上限 [CHUNK_MAX]，并向下对齐到整帧 */
    private val chunkBytes = run {
        val raw = max(minBufferSize / 2, frameBytes * 16).coerceAtMost(CHUNK_MAX)
        (raw / frameBytes) * frameBytes
    }

    @Volatile private var running = false
    @Volatile private var rms = 0
    @Volatile private var dropped = 0
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    /**
     * 权限检查点 2/2：创建 AudioRecord 前再查一次。
     * @return false = 无权限或初始化失败，调用方静默降级为纯视频（不录了再丢）
     */
    @Suppress("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        if (!AudioProbe.hasRecordPermission(ctx)) {
            Log.i(TAG, "feeder skipped: RECORD_AUDIO denied, video only")
            return false
        }
        if (AudioProbe.minBufferSize(sampleRate, channels) <= 0) {
            Log.i(TAG, "feeder skipped: sr=$sampleRate ch=$channels unsupported")
            return false
        }
        val rec = try {
            AudioRecord(
                source, sampleRate, AudioProbe.channelConfig(channels),
                AudioFormat.ENCODING_PCM_16BIT, max(minBufferSize, chunkBytes)
            )
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "AudioRecord bad args: ${e.message}")
            null
        } catch (e: RuntimeException) {
            Log.e(TAG, "AudioRecord init failed: ${e.message}")
            null
        } ?: return false
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized (state=${rec.state})")
            rec.release()
            return false
        }
        queue.clear()
        record = rec
        running = true
        dropped = 0
        try {
            rec.startRecording()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "startRecording failed: ${e.message}")
            rec.release()
            record = null
            return false
        }
        thread = Thread({ pump(rec) }, "WotaAudioFeeder").apply {
            priority = Thread.NORM_PRIORITY + 2
            start()
        }
        Log.i(TAG, "feeder start sr=$sampleRate ch=$channels src=$source chunk=$chunkBytes")
        return true
    }

    private fun pump(rec: AudioRecord) {
        val buf = ByteArray(chunkBytes)
        while (running) {
            val n = try {
                rec.read(buf, 0, buf.size)
            } catch (e: RuntimeException) {
                Log.e(TAG, "pcm read threw: ${e.message}")
                -1
            }
            if (!running) break
            if (n < 0) {
                Log.e(TAG, "pcm read error=$n, stop feeder")
                break
            }
            if (n >= frameBytes) {
                val copy = buf.copyOf(n - n % frameBytes)
                rms = rmsOf(copy)
                val pkt = Packet(copy, System.nanoTime() / 1000L)
                val ok = try {
                    queue.offer(pkt, OFFER_WAIT_MS, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    false
                }
                if (!ok) {
                    dropped++
                    if (dropped == 1 || dropped % 50 == 0) Log.e(TAG, "pcm queue full, dropped=$dropped")
                }
            }
        }
        try {
            rec.stop()
        } catch (e: IllegalStateException) {
            Log.i(TAG, "feeder rec.stop ignored: ${e.message}")
        }
        // EOS 哨兵与 PCM 走同一通道，消费端见 size==4 即停
        try {
            val sent = queue.offer(Packet(ByteArray(EOS_SIZE), -1L), 200L, TimeUnit.MILLISECONDS)
            if (!sent) {
                // 编码侧另有 queueAudioEos 兜底，所以这不算致命；但不记就会变成
                // 「队列里到底还剩几包」无从判断 —— 尾音丢失与它同源
                Log.w(TAG, "EOS 哨兵入队超时，queue=${queue.size} 包未消费")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** 消费端取包；null = 超时内无数据 */
    fun poll(timeoutMs: Long): Packet? = try {
        queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    }

    /** PCM 均方根，量程对齐 `MediaRecorder.getMaxAmplitude()`（0..32767），UI 音量表两引擎通用 */
    fun amplitude(): Int = rms

    /** 队列内未喂给编码器的包数：stop 前尽量排空，减少尾音丢失 */
    fun pending(): Int = queue.size

    /** 分段边界丢弃积压：上一段的 PCM 不能落进新文件，否则 PTS 倒退 */
    fun drain() {
        val n = queue.size
        queue.clear()
        if (n > 0) Log.i(TAG, "pcm queue drained at segment boundary, dropped=$n")
    }

    /** 停止采集并释放 AudioRecord；幂等。EOS 哨兵由 [pump] 的退出分支投递 */
    fun stop() {
        val t = thread
        thread = null
        if (running) {
            running = false
            try {
                t?.join(300L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        val rec = record
        record = null
        try {
            rec?.stop()
        } catch (e: IllegalStateException) {
            // 已停止/未启动，忽略
        }
        rec?.release()
        if (dropped > 0) Log.i(TAG, "feeder stopped, pcm dropped=$dropped")
    }

    private fun rmsOf(pcm: ByteArray): Int {
        val n = pcm.size / 2
        if (n == 0) return 0
        var sum = 0.0
        for (i in 0 until n) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val s = (hi shl 8) or lo
            sum += (s * s).toDouble()
        }
        return sqrt(sum / n).toInt().coerceIn(0, 32767)
    }
}
