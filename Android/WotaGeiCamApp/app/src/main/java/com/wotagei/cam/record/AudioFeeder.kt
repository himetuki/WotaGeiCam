package com.wotagei.cam.record

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.wotagei.cam.diag.Diag as Log
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

    /** 单次读块上限：约 40ms（48k 立体声 8KB），保证 stop 几十毫秒内生效（原 [AudioFeeder] 的私有常量搬入单源） */
    const val CHUNK_MAX = 8192

    /** 一帧 PCM16 字节数：`max(channels, 1) × 2`（0/负按单声道兜底；帧对齐与读块换算共用） */
    fun frameBytes(channels: Int): Int = max(channels, 1) * 2

    /**
     * 单次读块字节数：半个最小缓冲为基准、下限 16 帧、上限 [CHUNK_MAX]、向下对齐到整帧。
     * **[AudioFeeder] 泵读块与 [PlaybackCaptureController] 建内录 AudioRecord 的读块计算共用这一份**
     * ——原先是两处同式的私有算式（声称同口径却各写一份），改动只许改这里。
     */
    fun chunkBytes(minBufferSize: Int, channels: Int): Int {
        val frame = frameBytes(channels)
        val raw = max(minBufferSize / 2, frame * 16).coerceAtMost(CHUNK_MAX)
        return (raw / frame) * frame
    }

    /** AudioRecord 构造的整缓冲字节数 = `max(minBufferSize, chunkBytes(...))`（环境/内录两路建路共用口径） */
    fun recordBufferBytes(minBufferSize: Int, channels: Int): Int =
        max(minBufferSize, chunkBytes(minBufferSize, channels))

    /** 振幅 → 分贝：`20 × log10(amp / 0.1)`（`` 音量表）；amp ≤ 0 给下限，UI 直接显示 */
    fun dbOf(amplitude: Int): Float =
        if (amplitude <= 0) -160f else (20.0 * log10(amplitude.toDouble() / 0.1)).toFloat()
}

/**
 * AudioRecord 采集线程 → PCM 包队列（`` 自采管线的唯一通道，供 [CodecRecorder] 消费）。
 *
 * - 生产端：本类线程读 PCM 并算 RMS；消费端：编码器循环取包喂 AAC，两者不共享编码器句柄；
 * - 时间戳统一 `System.nanoTime() / 1000`（μs），与视频 PTS 同源时钟，避免音画漂移；
 * - 停止时投递 4 字节哨兵包（同通道 EOS），消费端据此给编码器打 `BUFFER_FLAG_END_OF_STREAM`；
 * - 双轨泛化（内录体系批 3）：泵循环/队列/EOS 哨兵/RMS **只有这一份**，环境与捕获两路各建一个
 *   实例，唯一分叉点是 AudioRecord 的构造路径（[openRecord]）——环境路照旧自查权限/参数后构造，
 *   捕获路拿 [PlaybackCaptureController] 在 Active 态建的 AudioRecord（P2：双轨并存，不是换源）。
 */
class AudioFeeder private constructor(
    val sampleRate: Int,
    val channels: Int,
    val source: Int,
    /** = `MediaFormat.KEY_MAX_INPUT_SIZE` 的来源（`` 参数表：max-input-size = minBufferSize） */
    val minBufferSize: Int,
    /** AudioRecord 构造路径（唯一分叉点）：入参=整缓冲字节数；null = 本源不可用，静默降级纯视频 */
    private val openRecord: (bufferBytes: Int) -> AudioRecord?,
    /** 捕获源健康度外部判据（null = 无此判据，环境源恒 null）。投影撤销后部分机型 read 只回静音
     *  不回错、EOS 判据探不到——用 controller 的 Active 与否兜底保证「撤销即内录轨收尾」的确定语义 */
    private val healthy: (() -> Boolean)? = null,
    /**
     * 是否需要逐块算 PCM 幅度（RMS）。**只有环境路开着**：
     * - 环境源：`CodecRecorder.amplitude()` → `ui/CameraScreen` 音量表（P6：内录模式下音量条仍显环境音 RMS）；
     * - 内录源：**关**（2026-10-16 改回 r30 口径）。代价账（2026-10-09 记过）：RMS 是逐样本 Double
     *   累加 + 每块一次 `sqrt`，48kHz 立体声每秒约 4.8 万次乘加，跑在**要跟 AudioRecord 内部
     *   缓冲赛跑的采集线程**上——r30（05f0742）当初正因掉帧把它关掉过；后来为段收尾静音判读
     *   又打开（3bc1acd）。静音判读现已改低成本判据（[sawNonZero]：按字节扫"任一非零、命中
     *   断扫"），逐样本 RMS 在内录路没有读者——不得回退。
     */
    private val needsAmplitude: Boolean = true,
    /** 环境音降噪档（2026-10-26）：仅环境路生效；内录轨恒 OFF（别人的成品混音不二次降噪） */
    private val audioDenoise: com.wotagei.cam.core.AudioDenoise = com.wotagei.cam.core.AudioDenoise.OFF
) {

    /**
     * 环境音（麦克风）路径：构造签名与行为与泛化前逐字一致，权限/minBuffer 检查也留在这一路。
     */
    @Suppress("MissingPermission")
    constructor(
        ctx: Context,
        sampleRate: Int,
        channels: Int,
        source: Int,
        minBufferSize: Int,
        audioDenoise: com.wotagei.cam.core.AudioDenoise = com.wotagei.cam.core.AudioDenoise.OFF
    ) : this(sampleRate, channels, source, minBufferSize, { bufferBytes ->
        // 权限检查点 2/2：创建 AudioRecord 前再查一次（原 start() 逐字搬入，行为不变）
        if (!AudioProbe.hasRecordPermission(ctx)) {
            Log.i(TAG, "feeder skipped: RECORD_AUDIO denied, video only")
            null
        } else if (AudioProbe.minBufferSize(sampleRate, channels) <= 0) {
            Log.i(TAG, "feeder skipped: sr=$sampleRate ch=$channels unsupported")
            null
        } else try {
            AudioRecord(
                source, sampleRate, AudioProbe.channelConfig(channels),
                AudioFormat.ENCODING_PCM_16BIT, bufferBytes
            )
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "AudioRecord bad args: ${e.message}")
            null
        } catch (e: RuntimeException) {
            Log.e(TAG, "AudioRecord init failed: ${e.message}")
            null
        }
    }, audioDenoise = audioDenoise)

    /**
     * 捕获（AudioPlaybackCapture）路径：AudioRecord 由 controller 在 Active 态建
     * （buffer 口径同环境路，见 [PlaybackCaptureController.createCaptureAudioRecord]）。
     * 健康判据挂 controller 态：录制中会话被撤销/重授权 → [captureActive] 变 false。
     */
    constructor(
        capture: PlaybackCaptureController,
        sampleRate: Int,
        channels: Int,
        minBufferSize: Int
    ) : this(sampleRate, channels, SOURCE_CAPTURE, minBufferSize, { _ ->
        // 整缓冲形参在捕获路无消费点：AudioRecord 的整缓冲由 controller 内部自算，但计算式已并归
        // [AudioProbe.recordBufferBytes] 单源（与这里 openRecord 的口径同函数；输入 aacMinBuf 与
        // controller 的 minBuf 同出 AudioProbe.minBufferSize，见 CodecRecorder 的「两源同值」注释）
        // ⇒ 丢弃形参与 controller 自算恒等，等效性表在 CaptureParamAuditTest 钉住
        capture.createCaptureAudioRecord(sampleRate, channels)
        // needsAmplitude = false：内录路不做逐样本 Double RMS（见 [needsAmplitude] 的代价账）；
        // 静音判读的数据基础改由 [sawNonZero] 锁存提供（录制中巡检与段收尾共用）
    }, healthy = { capture.state.value is CaptureState.Active }, needsAmplitude = false)

    /** 一段 PCM 与其 μs 时间戳；**ptsUs < 0 = 通道结束哨兵**（真实包时间戳恒为 nanoTime µs、恒正；
     *  哨兵 data 取 [EOS_SIZE] 长只是历史形状——判定看 pts 不看长度，4 字节恰是双声道 2 帧的合法 PCM） */
    class Packet(val data: ByteArray, val ptsUs: Long)

    companion object {
        const val EOS_SIZE = 4
        /** 队列上限：按 ~20ms/包算约 1.3s 音频；满了 offer 等不到空位就丢**当前这枚新包**并计数（已入队旧包保留，PTS 仍单调），绝不长阻塞采集线程 */
        private const val QUEUE_CAPACITY = 64
        private const val OFFER_WAIT_MS = 20L
        /** 捕获路径的音源占位（AudioPlaybackCapture 无传统音源语义，仅日志可辨） */
        const val SOURCE_CAPTURE = -1
        /** 空读告警节流：每累计这么多次数再记一条（真机可调点） */
        private const val ZERO_LOG_EVERY = 100
        /** 空读告警节流：距上次告警超过这么久也记一条（真机可调点） */
        private const val ZERO_LOG_INTERVAL_MS = 5_000L
    }

    private val queue = LinkedBlockingQueue<Packet>(QUEUE_CAPACITY)
    /** 帧宽的唯一真源在 [AudioProbe.frameBytes]（与读块/整缓冲换算同源） */
    private val frameBytes = AudioProbe.frameBytes(channels)

    /** 读块字节数：口径唯一真源在 [AudioProbe.chunkBytes]（泵读块与内录建路共用；改口径只改那里） */
    private val chunkBytes = AudioProbe.chunkBytes(minBufferSize, channels)

    @Volatile private var running = false
    @Volatile private var rms = 0
    /**
     * 本场（feeder 一次 [start] 到 [stop]）是否见过任一非零 int16 样本：内录静音判读的低成本
     * 数据基础（2026-10-16 灵敏化，替代逐样本 RMS）。数字静音流 = 全程恒定零样本（真机实证），
     * 见过一次非零即永久置位——**段界（[drain]）不复位**，语义是"整场"；[start] 复位
     * （feeder 随每场录制新建/重启 ⇒ "见过非零"按场计）。写入在采集线程、读在引擎泵线程。
     */
    @Volatile var sawNonZero: Boolean = false
        private set
    @Volatile private var dropped = 0
    /**
     * 空读/短读计数（`0 <= n < frameBytes`）：这类读不产生样本、循环却照跑，
     * 不记账的话「采集器活着但拿不到整帧样本」与「采集器根本没起」在日志里长得一模一样
     * （后者 start 失败路径有 `feeder skipped`/`feeder start` 日志，前者一条都没有）。
     */
    @Volatile private var zeroReads = 0
    /** 上次空读告警的墙钟毫秒（[ZERO_LOG_INTERVAL_MS] 节流用） */
    @Volatile private var lastZeroLogMs = 0L
    /** 已建的 AudioRecord；captureActive/stop 跨线程读，@Volatile 保证可见性 */
    @Volatile private var record: AudioRecord? = null
    /** 泵已到 EOS（通道关闭）：captureActive 的第二判据 */
    @Volatile private var eosSeen = false
    /** 本源第一枚真正入队的 PCM 包 PTS（启动记账：批 4 双轨对齐校正的数据基础） */
    @Volatile var firstPtsUs: Long = 0L
        private set
    private var thread: Thread? = null

    /**
     * 捕获源健康度：AudioRecord 已建 且 通道未到 EOS 且（无外部判据 或 判据通过）。
     * 引擎只在**捕获源**实例上查询（内录体系批 3：录制中投影撤销 → false → 内录轨提前 EOS 收尾，
     * 环境+视频继续）；环境源实例不查（三条件对它同样成立，语义无害）。
     */
    fun captureActive(): Boolean {
        return record != null && !eosSeen && healthy?.invoke() != false
    }

    /**
     * 环境源活性判据（S1-M3-05 收口：引擎段循环每拍问一次）：AudioRecord 已建 且 通道未到 EOS
     * ——即**采集线程未退出且未到 EOS**。与 [captureActive] 的**前两判据同口径**，但不含外部
     * 健康判据（healthy 对环境源恒 null，语义无害，无需重复）。
     *
     * 三种收场都落 false，语义不许含混：
     * - read 返回负值/抛异常（采集线程死亡）：pump 退出即置 [eosSeen] ⇒ 恒 false。**不能只看
     *   [record]**：死亡并不置空 record 字段（那要等引擎收尾调 [stop]），只看它会把死源判成活；
     * - [stop] 整段停机：record 置空 ⇒ false；
     * - 死亡是**事后**发现（没有事件回调）：引擎下一拍问到时才为 false——段间死亡的收口靠引擎
     *   每段第一拍问一次，不依赖队列里还有没有 EOS 哨兵（哨兵可能已被上一段消费或被 drain 清掉）。
     *
     * 花括号体不是风格偏好：结构守卫用 bodyOf 抓函数体做突变自证，表达式体让守卫失去输入
     * （同 `CodecRecorder.capSampleWaitTick` 先例）。
     */
    fun alive(): Boolean {
        return record != null && !eosSeen
    }

    /**
     * 环境音降噪挂载点（2026-10-26）：随 AudioRecord 建立、随 [stop] 释放；`INACTIVE` = 不降噪。
     * 内录路（捕获）不挂——别人的成品混音不二次降噪。
     */
    private var denoiser: AudioDenoiser = AudioDenoiser.INACTIVE

    /**
     * 效果器**实际**挂上了吗（读 [AudioDenoiser.active]）。UI 侧要的是这个而不是"用户选了哪档"：
     * 机型没有 NoiseSuppressor / 启用失败时，面板选着"系统降噪"而环境音其实是原样录的。
     * 录制侧据此在 health 里置"要了却没生效"，由录制页提示一次。
     */
    val denoiseActive: Boolean get() = denoiser.active

    /**
     * 建 AudioRecord（构造路径分叉点）→ 起泵线程。环境路的权限/参数检查在 [openRecord] 内。
     * @return false = 无权限或初始化失败，调用方静默降级为纯视频（不录了再丢）
     */
    fun start(): Boolean {
        if (running) return true
        val rec = openRecord(AudioProbe.recordBufferBytes(minBufferSize, channels)) ?: return false
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized (state=${rec.state})")
            rec.release()
            return false
        }
        // 降噪必须在 startRecording 之前挂上（效果器跟 session 走，起录后再挂会丢开头一段）
        denoiser = AudioDenoiser.attach(rec.audioSessionId, audioDenoise)
        queue.clear()
        record = rec
        running = true
        dropped = 0
        zeroReads = 0
        sawNonZero = false
        lastZeroLogMs = 0L
        eosSeen = false
        firstPtsUs = 0L
        try {
            rec.startRecording()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "startRecording failed: ${e.message}")
            // 次序与 [stop] 同一条：**先摘效果器再放 AudioRecord**（效果器持着那条 session，
            // AudioRecord 释放后再放会报错）。这一支此前漏了——起录失败时效果器要等调用方收尾的
            // `f.stop()` 才放，那时 AudioRecord 早已释放（2026-10-26 修正）；守卫同轮补了这条次序。
            denoiser.release()
            denoiser = AudioDenoiser.INACTIVE
            rec.release()
            record = null
            return false
        }
        thread = Thread({
            // 【音频类优先级（2026-10-09）】采集线程按音频类调度（Android 自家 AudioRecord 回调线程
            // 就是这一档）：原来设的是 `priority = NORM_PRIORITY + 2`——那是 **Java 线程优先级**，
            // 不映射到 Linux nice 值，等于没设；在"GL 渲染 + 相机 + 两路编码"同时跑的真机上，
            // 负载尖峰会把采集线程饿到 → AudioRecord 缓冲溢出/丢包 = 录音断续。
            // 两路（环境 + 内录）各自一条本线程，都走这一处。
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
            pump(rec)
        }, "WotaAudioFeeder").apply {
            start()
        }
        Log.i(TAG, "feeder start sr=$sampleRate ch=$channels src=$source chunk=$chunkBytes")
        return true
    }

    private fun pump(rec: AudioRecord) {
        val buf = ByteArray(chunkBytes)
        while (running) {
            // 捕获源健康检查（环境源 healthy=null 恒跳过）：会话撤销→立刻退出投 EOS 哨兵，
            // 消费端据此给内录轨收尾（撤销后 read 只回静音不回错的机型靠这条兜住）
            val health = healthy
            if (health != null && !health()) {
                Log.i(TAG, "capture source inactive, pump exit src=$source")
                break
            }
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
                // 本场"见过非零"锁存（见 [sawNonZero]）：见到任一非零字节即永久置位并**跳过后续
                // 所有块的扫描**（早停）。恒定零流才会整块扫完——一块几千次整数比较，可忽略。
                if (!sawNonZero && hasNonZeroByte(copy)) sawNonZero = true
                // 幅度只有环境路要（音量表；见 needsAmplitude 的说明）
                if (needsAmplitude) rms = rmsOf(copy)
                val pkt = Packet(copy, System.nanoTime() / 1000L)
                val ok = try {
                    queue.offer(pkt, OFFER_WAIT_MS, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    false
                }
                if (!ok) {
                    dropped++
                    if (dropped == 1 || dropped % 50 == 0) Log.e(TAG, "pcm queue full, dropped=$dropped")
                } else if (firstPtsUs == 0L) {
                    // 启动记账：本源第一枚真正入队的 PCM 包（与另一轨首包差 = 启动偏移）
                    firstPtsUs = pkt.ptsUs
                    Log.i(TAG, "首包记账 src=$source pts=$firstPtsUs")
                }
            } else {
                // 空读/短读（0 <= n < frameBytes）：不产样本但循环照跑，留痕才能与「采集器没起」区分
                zeroReads++
                val now = SystemClock.elapsedRealtime()
                if (zeroReads == 1 || zeroReads % ZERO_LOG_EVERY == 0 || now - lastZeroLogMs >= ZERO_LOG_INTERVAL_MS) {
                    lastZeroLogMs = now
                    Log.w(
                        TAG,
                        "pcm 空/短读 src=$source n=$n frameBytes=$frameBytes 累计 $zeroReads 次" +
                            "（采集在跑但拿不到整帧样本，检查源是否活着/无声播放）"
                    )
                }
            }
        }
        eosSeen = true
        try {
            rec.stop()
        } catch (e: IllegalStateException) {
            Log.i(TAG, "feeder rec.stop ignored: ${e.message}")
        }
        // EOS 哨兵与 PCM 走同一通道，消费端按 pts<0 识别（J 批口径：不看包长）
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

    /**
     * 分段边界丢弃积压：上一段的 PCM 不能落进新文件，否则 PTS 倒退。
     * **不复位 [sawNonZero]**（段界只清队列）：它的语义是"本场是否见过非零样本"，
     * 段收尾判读与跨段静音巡检共用的就是这份整场账面。
     */
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
        // 先摘效果器再停/放 AudioRecord：效果器持着 session，AudioRecord 释放后再释放会报
        denoiser.release()
        denoiser = AudioDenoiser.INACTIVE
        val rec = record
        record = null
        try {
            rec?.stop()
        } catch (e: IllegalStateException) {
            // 已停止/未启动，忽略
        }
        rec?.release()
        if (dropped > 0 || zeroReads > 0) {
            Log.i(TAG, "feeder stopped src=$source pcm dropped=$dropped zeroReads=$zeroReads")
        }
    }

    /**
     * 本块 PCM 里是否存在非零样本。int16 小端下"样本为 0 ⇔ 两字节全零"，所以按字节查
     * "任一非 0"与逐样本判等完全等价、且更快；见到第一个非零字节即返回（早停）。
     */
    private fun hasNonZeroByte(pcm: ByteArray): Boolean {
        for (b in pcm) if (b.toInt() != 0) return true
        return false
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
