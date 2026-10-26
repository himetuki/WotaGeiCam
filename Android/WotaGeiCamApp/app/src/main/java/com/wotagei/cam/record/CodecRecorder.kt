package com.wotagei.cam.record

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import com.wotagei.cam.diag.Diag as Log
import android.view.Surface
import com.wotagei.cam.core.WotaTiers
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * 自研管线：`MediaCodec + MediaMuxer + AudioRecord`（04 文件 §3：fps > 60 的高速路径；
 * 内录体系批 3 起也是双轨路径——arcConvert / captureAudio / 高帧率三者任一命中都走本引擎）。
 *
 * 关键点：
 * - 输入面：DIRECT 路（Camera2 当 producer）用 `MediaCodec.createPersistentInputSurface()`
 *   （API 29 起）跨分段复用不重建；GPU 路（EGL 当 producer）必须 per-codec
 *   `codec.createInputSurface()`（persistent 面挂 EGL 真机 EGL_BAD_ALLOC，见 [prepareGpuVideoEncoder]），
 *   换段重挂经 [onInputSurfaceRecreated] 同步通知；
 * - 视频参数：COLOR_FormatSurface + BITRATE_MODE_VBR + KEY_I_FRAME_INTERVAL=1 + KEY_FRAME_RATE=fps；
 * - 音频参数：`audio/mp4a-latm` + AAC-LC + 128k + CHANNEL_COUNT + KEY_MAX_INPUT_SIZE=minBufferSize，
 *   PTS 用 `System.nanoTime()/1000`，EOS 走 PCM 同一通道的 4 字节哨兵（``）；
 * - **双轨编排（P2：单 MP4 双 AAC 音轨）**：环境/内录各一套 feeder + 编码器 + 逐通道状态
 *   （[AudioChannel]，heldPkt/lastPts/EOS 标志互不串扰），addTrack 顺序 = 环境轨先、内录轨后
 *   （轨序即 tkhd track_ID 顺序，批 4 播放器按此识别）；Muxer 只在「视频就绪 且 环境轨就绪
 *   且（未启用捕获 或 捕获轨就绪）」时 start()（[startMuxerIfReady]）；
 * - **NTSC 帧率的容器声明取舍（定案 2026-10-11）**：`KEY_FRAME_RATE` 是 int，**保持写整数
 *   24/30/60 不变**，不因为新增 NTSC 网格而改成 24000/1001 或浮点。理由三条：
 *   1. 容器帧率声明只是**播放器 UI 显示与时长预估的 hint**，AV 同步一律以 PTS 为准——
 *      PTS 由 GL 侧按 `ntscGridStepNs` 的真步长逐帧写入（见 `camera/GlRenderEngine`），
 *      声明写 24 不会让成片真值变回 24；
 *   2. `ffprobe` 的 `r_frame_rate` 由 `stts`（真实帧长表）**反推**，会如实报出 23.976 量级，
 *      不依赖我们写进容器的那个整数；
 *   3. 播放器侧用 `MediaFormat.frameRate`(int) 显示「24.0」，差 0.1% 可接受，非整数的
 *      两位小数文案已备好；把它改成 Volume 形态对**显示**零收益，副作用是播放器整条链回归。
 *   一句话：**网格要真、声明可以粗**——这两件事在本工程里必须分开看。
 * - **录制中捕获源失效**（投影撤销 → [AudioFeeder.captureActive] 变 false）：内录轨就地 EOS 收尾、
 *   环境+视频继续录——成片仍是合法 MP4（内录轨比另两轨短，播放器按轨长对齐），不中断录制；
 * - 停止顺序：codec.stop/release → muxer.stop/release → AudioRecord.stop/release。
 */
class CodecRecorder(
    private val ctx: Context,
    private val store: VideoStore,
    /** 输出路由探测（预校准只在 A2DP 时跑；默认实现零权限、失败按非 A2DP） */
    private val routeProbe: AudioRouteProbe = AndroidAudioRouteProbe(ctx)
) : Recorder {

    companion object {
        /**
         * 输出侧轮询粒度。
         *
         * 【10ms → 2ms（2026-10-09）】这段超时是**泵线程一轮的最坏阻塞时长**：内录开启时每拍串行
         * 3 次（环境音频排空 / 内录音频排空 / 视频排空），最坏 30ms；而视频排水排在两次音频喂码
         * **之后**（:1308-1338 喂完才到 :1339），泵一旦慢下来就先顶住编码面 → 背压一路回压到 GL
         * 渲染线程 → GL 错过 24fps 名义网格的落点 = 成片空档（真机实测每 4 秒一个空格）。
         * 2ms 仍远大于一次 dequeue 的正常往返（亚毫秒级），不会把泵变成空转；真正的兜底是队列深度。
         */
        private const val DEQUEUE_TIMEOUT_US = 2_000L

        /**
         * 空闲自适应退避（2026-10-24 性能基线轮 P3）：连续 [DEQUEUE_IDLE_STREAK_BACKOFF] 轮
         * 三通道输出全空时，下一轮 dequeue 超时从 2ms 放宽到本值。
         *
         * 依据（模拟器基线 trace，15.25s 录制窗口）：泵线程 13,043 次调度、平均 44µs/次
         * ≈855 次唤醒/秒——来自 2ms×3 通道的轮询；放大量级（8ms）仍低于 2026-10-09
         * 回退掉的 10ms 档（那一档的问题是最坏 3×10=30ms 全忙阻塞），而退避只在
         * 「三通道连续空转」时生效：任一通道出数即清零回 2ms，活跃流路径逐字不变。
         * 最坏阻塞 3×8=24ms < 30ms（旧 10ms 档），背压上界仍受控。
         */
        internal const val DEQUEUE_BACKOFF_TIMEOUT_US = 8_000L

        /** 连续全空多少轮后启用退避超时（4 轮 × 2ms ≈ 8ms 无产出才算真空闲） */
        internal const val DEQUEUE_IDLE_STREAK_BACKOFF = 4

        /**
         * 抽干窗口的**静默预算**（idle budget）：窗口内**没有任何推进**时才按它判超时。
         *
         * 【原是一刀切 1200ms 绝对 deadline（起钟即定），超时即 `ENGINE_ERROR:DRAIN` + 整段删】——
         * 那条判据分不清"编码器只是慢"与"编码器已死"：真机形态是收尾抽干超时，而 muxer 早已写
         * 完 moov、整段（最长约 20 分钟）可播，用户却看到"失败"且文件被删。现在由 [drainWaitTick]
         * 在抽干窗口里观测到推进时顺延本预算；**无任何推进 ⇒ 与原实现逐字一致**（起窗后 1200ms
         * 毫无音讯仍判超时）——真卡死的停止不许被拖长。
         */
        internal const val DRAIN_LIMIT_MS = 1_200L

        /**
         * 抽干总时长**硬上限**（[drainWaitTick] 到线强制超时，推进也不救）。
         *
         * 【与 [LOOP_JOIN_MS] 的关系：这不是取值口味，是可达性】stop() 的 joinLoop 只等
         * [LOOP_JOIN_MS](2500ms)，超了就走 finishWithoutTeardown（只记账不拆资源；已封段如实
         * 带出，用户看到的是失败码 + 已保存段数——见"录制结果如实化"）；而抽干之后泵线程还要跑完
         * 「closeSegmentEngine（codec/muxer stop + moov 落盘）+ sealCurrent（关 fd + commit）」，
         * 典型本机是数十~数百 ms 量级。预算分解：
         * 硬上限 1900 + 预留封段收尾 500 = 2400 < 2500（留 100ms 给线程调度与 join 粒度）。
         * 顺延最多把抽干从 1200 放宽到 1900（"慢而不死"多拿 700ms 宽限），一旦吃到 join 线，
         * 「保下已录内容」就变成「连收尾都交不出去」——顺延只在"仍赶得上 join"的前提下才有意义。
         * 常量关系由测试钉住（DrainWaitGuardTest 的预算分解，同 CAP_SAMPLE_WAIT_MS 先例）。
         *
         * 【停止落在换段腿内的叠加组合：既有行为，非本轮顺延引入】用户按停止时泵线程若正卡在
         * 换段腿里，[ROTATE_LEG_BUDGET_MS](3000ms) > [LOOP_JOIN_MS](2500ms) 的结构事实决定：
         * 腿尾先要跑完，随后这段收尾还要再吃一整段抽干（本上限）才轮得到收尾落地——最坏腿尾
         * 3000 + 抽干 1900 + 封段收尾 ≈ 5400ms，与 2500 的 join 窗口差了一个量级 ⇒ 这条组合下
         * stop() 必然走 [finishWithoutTeardown]（只记账不拆资源，泵线程出来自己封段）。
         * 本轮的进度感知只改变这条组合的**上限压**：抽干最坏从静默 1200 放宽到 1900（多 700ms
         * 宽限）；"腿内停止 ⇒ 走 finishWithoutTeardown"这条组合修复前就在，不是本轮引入。
         *
         * 【轮转路径同样吃这条上限】抽干起点就发了 EOS，窗口内相机新帧进不了编码器 = 丢帧；
         * 而轮转丢帧账（rotateLegStartMs）从 beginRotateLeg 起算、**不含抽干段**——抽干拖多久，
         * 就有多久的丢帧不入账。不许为了"多等"把轮转丢帧窗口拉大。
         */
        internal const val DRAIN_HARD_LIMIT_MS = 1_900L

        internal const val LOOP_JOIN_MS = 2_500L
        /** 分段阈值余量：估算写量不含容器结构开销，提前 2% 换段 */
        private const val ROTATE_SAFETY = 0.98
        /** MediaFormat KEY_I_FRAME_INTERVAL 单位是秒 */
        private const val I_FRAME_INTERVAL_SEC = 1
        /** 延时摄影专用键（04 文件 §3） */
        private const val KEY_CAPTURE_RATE = "capture-rate"
        /** BufferQueue 模式下的历史返回值，仍需吞掉（API 29 起标废弃，故不引用常量） */
        private const val INFO_OUTPUT_BUFFERS_CHANGED = -3
        /** muxer 写失败的现场快照里"这笔来自哪条轨"（见 [muxFailDiagnostic]） */
        private const val TRACK_VIDEO = "V"
        private const val TRACK_ENV = "A"
        private const val TRACK_CAP = "C"
        /**
         * 内录样本等待窗：muxer 启动被"启用但无样本"的内录通道卡住时的有界放弃时限。
         * 部分机型 playback capture 无媒体在播时不回缓冲——无界等待会让 muxer 永不启动，
         * 停止时按 NO_VIDEO_TRACK 整段作废（用户连视频都拿不到）。窗满按废弃放行：
         * 环境+视频照常出片，内录轨丢失经 reportTrackLost 可见化。
         *
         * 【必须短于起录自检长窗 RecordHealth.FULL_WINDOW_MS(3000)——这不是取值口味，是可达性】
         * cap 无样本时自检三格恒不满（muxStarted 被 cap 闸卡死），healthVerdict 会在 3000ms
         * 判废 abandonAndRestart、3 次后 GIVE_UP 整段失败。两窗同在 elapsedRealtime 域起算，
         * 等待窗若 ≥ 长窗（历史取 5000ms 即如此），窗满放行永远轮不到、自检先动手——"防整段
         * 作废"形同虚设，不回缓冲机型（华为系）表现为"开内录必 SELF_CHECK_FAILED"。
         * 预算：窗起点（视频 addTrack ≤800ms）+ 本窗 1500 + muxer 启动到首视频样本再被自检
         * 读到（≤2 拍 360ms）< 3000。正常双轨机型有媒体播放时内录首样本（PCM 20~40ms/包 +
         * AAC 首输出）远快于 1500ms，不触发（成功路径零改变）。
         */
        internal const val CAP_SAMPLE_WAIT_MS = 1_500L

        /**
         * 起录缓冲的**字节上限**（2026-10-17）：muxer 启动前已编码的视频样本先复制进
         * [PreMuxVideoBuffer]，容器可写后按**到达顺序**补写，不再直接丢。
         *
         * 量级依据：最长等待窗 = [CAP_SAMPLE_WAIT_MS] 1.5s；本工程自动码率最高档 = 产品档位
         * 上限 50Mbps（[com.wotagei.cam.core.WotaTiers.BITRATES] 末项）⇒ 1.5s ≈ 9.4MB，
         * 关键帧峰值再留约 3 倍余量 ⇒ 32MiB。固定上限而不是按堆比例，是为了让"最多占多少内存"
         * 在代码里可一眼读出（任何设备都不会超过 32MiB）。
         */
        internal const val PRE_MUX_BUFFER_MAX_BYTES = 32L * 1024L * 1024L

        /**
         * 内录样本等待窗的一拍推进（纯函数，"计划→行为"桥：起窗/清窗/窗满三态各有真实语义）。
         * @param waitStartMs 窗起点（0 = 未起窗），elapsedRealtime 域
         * @param nowMs       本拍时刻，elapsedRealtime 域
         * @param waiting     本拍是否仍需等内录样本（视频+环境都就绪、内录启用且未废弃、
         *                    仍无首个真实样本、且未走失效收尾）
         * @return (新窗起点, 是否窗满放弃)：不再等待恒清窗；首拍起窗不判超时（起窗即满窗
         *         会把 0 长窗误判成超时）
         */
        internal fun capSampleWaitTick(waitStartMs: Long, nowMs: Long, waiting: Boolean): Pair<Long, Boolean> {
            // 花括号体不是风格偏好：结构守卫用 bodyOf 抓函数体做突变自证，表达式体让守卫失去输入
            return when {
                !waiting -> 0L to false
                waitStartMs == 0L -> nowMs to false
                else -> waitStartMs to (nowMs - waitStartMs > CAP_SAMPLE_WAIT_MS)
            }
        }

        /**
         * 环境采集源"可继续喂码"判据（纯函数，"计划→行为"桥：`runSegment` 每拍问一次；判 false
         * 即走一次性收口）。在场 **且** 采集线程未退出（[AudioFeeder.alive]）才为真。
         *
         * 【为什么"缺失"也算不活】feeder==null 而环境编码器在（"幽灵通道"形态——历史上由换段
         * 编码器重建失败但 profile 未改写而来）时通道永远没有喂入端：不在这里一并收口，envCh
         * 永不 hasSample、永不 dropped ⇒ muxer 启动门恒假 ⇒ 视频样本全被丢弃（黑洞）。
         * 缺失与死亡对通道的后果同型，判据一并覆盖；R4 的 profile 改写再把它挡在结构层。
         */
        internal fun ambientSourceLive(feederPresent: Boolean, feederAlive: Boolean): Boolean =
            feederPresent && feederAlive

        /**
         * 抽干窗口的一拍推进（纯函数，"计划→行为"桥：起窗 / 推进顺延 / 超时三态各有真实语义）。
         *
         * 【为什么要进度感知】收尾抽干原来只有一条绝对 deadline（起钟即定 [DRAIN_LIMIT_MS]），
         * "编码器只是慢"与"编码器已死"同判——真机形态是一条 drain timeout 把已经写完 moov、
         * 可播的整段判废删掉。本桥只对**仍在推进**的抽干放宽：观测到推进（任一轨 done 翻转 /
         * dequeue 返回过 idx>=0 或 FORMAT_CHANGED / 有样本写进 muxer）就把超时时钟顺延一个
         * 静默预算；推进一停，仍按同一条预算判超时。
         *
         * 【无推进 ⇒ 与原实现逐字一致】`nowMs > deadlineMs` 严格大于，与旧判 `now > deadline` 同口径。
         * 【顺延有界】总时长到 [DRAIN_HARD_LIMIT_MS] 即强制超时（推进也不救）——它要留住 stop()
         * 的 [LOOP_JOIN_MS] join 预算（见该常量 KDoc，是可达性不是口味）。
         *
         * @param openedAtMs 窗起点（0 = 未起窗），elapsedRealtime 域
         * @param deadlineMs 当前超时时刻（0 = 未起窗），elapsedRealtime 域
         * @param nowMs      本拍时刻，elapsedRealtime 域
         * @param progressed 本拍是否观测到推进（[CodecRecorder.runSegment] 的 drainProgress）
         * @return 三态快照：起窗本拍只记起点与首枚 deadline、不判超时（起窗即判会把 0 长窗误判成
         *         超时）；推进顺延只挪 deadline、绝不越过硬上限；无推进按静默预算判超时
         */
        internal fun drainWaitTick(
            openedAtMs: Long,
            deadlineMs: Long,
            nowMs: Long,
            progressed: Boolean
        ): DrainTick {
            // 花括号体不是风格偏好：结构守卫用 bodyOf 抓函数体做突变自证（同 capSampleWaitTick）
            val opened = if (openedAtMs == 0L) nowMs else openedAtMs
            val hardAt = opened + DRAIN_HARD_LIMIT_MS
            return when {
                openedAtMs == 0L -> DrainTick(opened, nowMs + DRAIN_LIMIT_MS, false)
                // 硬上限先于推进顺延判定：到线即超时，推进也不许把它拖过 join 预算
                nowMs >= hardAt -> DrainTick(opened, hardAt, true)
                progressed -> DrainTick(opened, minOf(nowMs + DRAIN_LIMIT_MS, hardAt), false)
                else -> DrainTick(opened, deadlineMs, nowMs > deadlineMs)
            }
        }

    /**
     * 内录静音判读的时长门槛（**收尾兜底版**）：本段内录有效样本超过 2s 才判读。太短不判——
     * 启动窗口内的短暂静音（会话刚建立、媒体还没出声）不该告警。录制中巡检用更长的
     * [CAPTURE_SILENCE_LIVE_MIN_MS]；本门槛留给"没跑满巡检门槛就停止"的短录音兜底。
     */
    internal const val CAPTURE_SILENCE_MIN_MS = 2_000L

    /**
     * 内录静音巡检的时长门槛（**录制中版**，2026-10-16 灵敏化：录制中就要能提示，不让用户
     * 录完才发现）：本段累计实喂内录 PCM ≥ 5s 且仍未见非零样本才判读。为何 5s：比
     * [CAP_SAMPLE_WAIT_MS]（1.5s，内录样本等待窗）与 `RecordHealth.FULL_WINDOW_MS`（3s，
     * 起录自检长窗）都长——巡检点排在"内录轨还没起来"的两个窗口之后起步，不与它们的形态
     * 叠报；同时是用户能等住的发现延迟（真机实证的静音段有 7.76s，5s 门槛必能覆盖）。
     */
    internal const val CAPTURE_SILENCE_LIVE_MIN_MS = 5_000L

    /**
     * 录制中静音巡检的节拍：泵循环每拍都问判据太密也无意义，约此间隔巡检一拍（发现延迟
     * ≤ 门槛 + 一拍；巡检本身只做几次 Long 运算与一次布尔读，不在热循环做重活）。
     */
    internal const val SILENCE_PROBE_INTERVAL_MS = 1_000L

    /**
     * 内录有效时长（ms）= 实喂编码器的 PCM 字节 ÷（采样率 × 帧字节）：录制中巡检与段收尾
     * 兜底共用同一换算（两处各写一份必漂移）。EOS 哨兵/喂码失败/段界丢弃都不计。
     */
    internal fun fedMsOf(fedBytes: Long, sampleRate: Int, channels: Int): Long =
        fedBytes * 1000L / (sampleRate.toLong() * channels * 2)

    /**
     * 内录纯静音判据（纯函数）：轨有真实样本产出、有效时长过 `minMs`、且**本场仍没见过任一
     * 非零样本**（数字静音流 = 全程恒定零，真机实证）三者同时成立才告警。
     * 非零锁存由 `AudioFeeder.sawNonZero` 提供——见过一次非零 ⇒ 本场永久判 false（两个触发点
     * 都拿不到 true），这就是"一旦见到非零、本场永久不再提示"的判据侧落地。
     * `minMs` 缺省 = 收尾兜底门槛；录制中巡检显式传 [CAPTURE_SILENCE_LIVE_MIN_MS]。
     */
    internal fun captureSilenceVerdict(
        hasSamples: Boolean,
        durationMs: Long,
        sawNonZero: Boolean,
        minMs: Long = CAPTURE_SILENCE_MIN_MS
    ): Boolean = hasSamples && durationMs >= minMs && !sawNonZero
}

    /** 单条音频通道的逐段状态（批 3：heldPkt / 末包 PTS / EOS 标志环境、内录各一套）。 */
    private class AudioChannel {
        /** 编码器没空位时暂存的包：留到下一轮，绝不丢帧 */
        var heldPkt: AudioFeeder.Packet? = null
        /** 本通道最后喂进编码器的真实 PCM 包 PTS（EOS 收尾时间戳基准） */
        var lastPtsUs = 0L
        /**
         * 本段喂编码器时的 PTS 平移（µs，正 = 延后）：环境轨恒 0；内录轨继承会话级
         * [preCalShiftUs]（蓝牙预校准）。**只改喂入戳，不改包内容**，EOS 也用平移后的末戳。
         */
        var ptsShiftUs = 0L
        /** muxer 轨号（addTrack 成功后 ≥0） */
        var track = -1
        /** 输出格式已到（INFO_OUTPUT_FORMAT_CHANGED 见过；内录轨 addTrack 待轨序门控放行） */
        var fmtReady = false
        /** EOS 已入队（喂到哨兵 / 停止兜底 / 捕获源失效三路共用，恒只入队一次） */
        var eosQueued = false
        /** 编码器已吐 EOS（本通道收尾完成） */
        var done = false

        // ---- 以下为零样本防线（修复轮2 P3 起环境、内录两通道共用同一套口径）----

        /** 编码器已吐出首个真实（非 EOS、非空）输出缓冲：addTrack 的前置判据 */
        var hasSample = false
        /** 通道废弃（收尾仍无真实样本）：startMuxerIfReady 按「未启用」处理 */
        var dropped = false
        /** 废弃后编码器迟到的真实样本被 release 的枚数（记账用，段收尾行打印；不进任何判定） */
        var lateDrops = 0
        /** 喂码时 getInputBuffer 拿不到缓冲、已出队包无处可送（静默丢弃记账；不进任何判定） */
        var feedDropped = 0
        /** 喂码时数据长于输入缓冲被截断的枚数（记账；不进任何判定） */
        var feedTruncated = 0
        /** 本段已成功喂进编码器的真实 PCM 字节账（EOS 哨兵/喂码失败不计）：内录静音判读的有效时长来源 */
        var fedBytes = 0L
        /** 首个真实样本的输出缓冲暂存（等 addTrack+muxer start 后补写；-1=无）及其 BufferInfo 快照 */
        var heldOutIdx = -1
        var heldPts = 0L
        var heldSize = 0
        var heldFlags = 0
    }

    private var inputSurface: Surface? = null
    private var vCodec: MediaCodec? = null
    private var aCodec: MediaCodec? = null
    /** 内录（AudioPlaybackCapture）第二音轨的编码器；null = 未启用捕获或本段已降级 */
    private var capCodec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var target: OutputTarget? = null
    private var currentSink: OutputSink? = null
    private var feeder: AudioFeeder? = null
    /** 内录捕获源 feeder（环境源 [feeder] 并存——双轨并存不是换源） */
    private var capFeeder: AudioFeeder? = null
    private var profile: RecordProfile? = null

    /** 本段两轨共用的 PTS 基准（μs，与 nanoTime 同域），避免分段后时间戳倒退 */
    private var segBaseUs = 0L

    /**
     * 逐 muxer 轨"已写出的 pts"（μs，已减去本段基准）：-1 = 本段该轨还没写过。
     *
     * 【为什么需要这本账（2026-10-08 真机根因收敛）】同一轨的 pts **倒退**会让 MPEG4Writer
     * 走失败路径——`writeSampleData` 抛 `IllegalStateException`，泵循环退出 ⇒ **整场录制当场结束**
     * （现场 `rotate-fail.log` 的 `trk=A;started=1;field=same;st=0` 那族，6/6 都是它）。
     * 换段期"暂存首样本补写"（[AudioChannel.heldOutIdx]）天然存在结构性顺序风险：那枚**较早**的样本
     * 可能在**较新**的样本之后才写出（补写点排在音频 dequeue 之后）。上游时序假设不能当保证，
     * 所以这里就地兜住：等值/倒退一律推进到 `last+1`，把致命错误降级成一枚微秒级修正，
     * 并把**次数与最大倒退量**记进失败快照（下次复现时能直接看出是不是这条机制）。
     */
    private val muxLastPtsUs = LongArray(8) { -1L }

    /**
     * **真倒退**（候选戳 < 上一戳）被就地推进的次数；[muxPtsMaxBackUs] 是其中最大的一枚倒退量。
     * 这两枚是失败快照里 `ptsfix=`/`back=` 的唯一来源——它的用途是"下次复现时能不能一眼看出是不是
     * 这条机制"，所以**只许装真倒退**（同格等值另计 [muxPtsEqualFixes]，混进来这把尺子就废了）。
     */
    private var muxPtsFixes = 0
    private var muxPtsMaxBackUs = 0L

    /**
     * **同格等值**（候选戳 == 上一戳，GL 侧契约明写「允许与上一枚相等」）被推进 1μs 的次数。
     * 属**设计内**、不是异常：单独计数、降 d 级（[Diag] 的 D 档默认关），并只并进段尾汇总行，
     * 不逐帧刷 w——否则源快于名义率时按帧级刷屏，还会把真倒退的读数淹掉。
     */
    private var muxPtsEqualFixes = 0

    /** 逐轨 pts 账复位（新段起算：换段后是新 muxer，没有历史） */
    private fun resetMuxPtsBooks() {
        java.util.Arrays.fill(muxLastPtsUs, -1L)
        muxPtsFixes = 0
        muxPtsMaxBackUs = 0L
        muxPtsEqualFixes = 0
    }
    private var aacMinBuf = 0
    private var audioSource = 0
    /** 双 feeder 的 start 时刻（μs，nanoTime 域）：启动记账（批 4 校正的数据基础） */
    private var envFeederStartUs = 0L
    private var capFeederStartUs = 0L
    /**
     * 蓝牙预校准得到的内录 PTS 平移（µs，正 = 延后）。会话级、跨段保持：同一场录制里 A2DP
     * 延迟不随换段变化；prepare 复位 0，换段**不**复位（新 AudioChannel 建段时重新继承）。
     * 只在 pump 线程起跑前写、启动后由 pump 线程读，@Volatile 兜住线程切换可见性。
     */
    @Volatile private var preCalShiftUs = 0L

    @Volatile private var state = EngineState.IDLE
    @Volatile private var engineError: String? = null

    /** 显式弃段意图（[abandonCurrentSegment] 置位，新会话/新段起始复位）：见 [segmentKeepDecision] */
    @Volatile private var abandonSegment = false

    /**
     * 会话级降级码：某段**被保留**（keep=true）却带错误码时记下该错误码的原文（典型=抽干超时
     * `ENGINE_ERROR:DRAIN`，而后 muxer 照样正常收尾、moov 已写完、整段可播）。
     *
     * 结果呈现见 [finish]：有产物 + 降级码 ⇒ 按成功呈现（error 置空、ok==true）+ UI 提示
     * "末尾可能不完整"，而不是把用户的成片说成"录制失败"。prepare 复位（新会话不带旧账）。
     */
    @Volatile private var degradedCode: String? = null

    /**
     * 本会话环境音（麦克风）路丢失（S1-M3-05 收口）：起录采集器建不起来 / prepare 期环境编码器
     * 建失败 / 录制中采集源死亡 / 换段环境编码器重建失败，四路任一命中经 [reportAmbientLost] 置位。
     *
     * 泵线程（runSegment 收口）与 prepare 所在线程都可能写 ⇒ @Volatile；读侧两处：[health] 供
     * 录制页巡检提示一次、[buildResult] 进 [RecordResult.ambientLost] 供结果文案附注。
     * prepare/release 复位（会话级标志不许跨会话残留，同 [degradedCode] 口径）。
     */
    @Volatile private var ambientLost = false

    /**
     * 用户要了音频降噪、但本机没生效（2026-10-26）：效果器没挂上（无 NoiseSuppressor / 启用失败 /
     * sessionId 无效）⇒ 环境音其实原样录的。与 [ambientLost] 同族同生命周期：起录时判定、会话级、
     * [health] 供录制页巡检提示一次。**只报"要了却没生效"**，没选降噪（OFF）不置位。
     */
    @Volatile private var denoiseInactive = false

    /** stop() 置位：循环给两轨发 EOS 后收尾 */
    @Volatile private var stopping = false

    /** 循环内写量估算触顶后置位：封当前段并接新 pending */
    @Volatile private var rotatePending = false

    private var loop: Thread? = null
    private val clock = ElapsedClock()

    /**
     * 已封段清单（[sealCurrent] 在 keep 成功后 add）。
     *
     * 【为什么是 CopyOnWriteArrayList（2026-10-16 "录制结果如实化"）】joinLoop 超时时泵线程仍
     * 活着：收尾被迫"只记账不拆资源"，但结果必须如实带出"此刻已封段"的**快照**（否则用户被告知
     * 全丢、相册里却有 N 段）。段数极小（分钟级 1~几条）、写稀（段闭一次）、读稀（收尾一次快照）
     * ⇒ COW 的复制代价可忽略；交换容器后 add 与 clear 各自原子，控制线程的 `toList()` 快照与
     * 泵线程正在进行的 add 结构性互不干扰——双触名单里它在超时闸下由"禁止触碰"降为"只读快照
     * 安全"（见 [settleRotateLegAtStop] 的 KDoc）。
     */
    private val parts = CopyOnWriteArrayList<VideoSegment>()
    private var partIndex = 0
    private var partStartMs = 0L

    /** 当前段已写视频样本数（纯内存计数，不碰任何 I/O 时序）；段闭时落进 [segSampleCounts] 并归零 */
    private var segVideoWritten = 0

    /** 起录缓冲（muxer 启动前已编码视频样本；段内生命周期，见 [PRE_MUX_BUFFER_MAX_BYTES]） */
    private var preMuxBuffer: PreMuxVideoBuffer? = null

    /** 本段起录缓冲**成功补写**进 muxer 的视频样本数（段尾帧账「起录补写」） */
    private var preMuxBuffered = 0L

    /** 本段起录缓冲的**丢失**样本数（溢出淘汰 + 缓冲释放时仍未补写；段尾帧账「起录丢弃」） */
    private var preMuxDropped = 0L

    /**
     * 当前段已写 muxer 字节数（段内累加，泵线程单写者；[health] 今天只在控制线程读它，故 @Volatile
     * 是防御未来把自检挪出该线程的零成本保险）。
     * 复用原 `runSegment` 内的局部账本（**不新开计数**），段起始归零，供 [RecorderHealth.outputBytes]。
     */
    @Volatile private var segWrittenBytes = 0L

    /**
     * 本段 muxer 是否**确认收尾**（[closeSegmentEngine] 里 `m.stop()` **正常返回**的那一刻置 true）。
     *
     * 【独立信号，不许从错误码反推】closeSegmentEngine 的置错是"首个错误码优先"
     * （`engineError = engineError ?: ...MUX_CLOSE`）：DRAIN 已置时，muxer.stop() 抛出的 MUX_CLOSE
     * 会被吞掉——拿错误码判断会把"坏容器"当"好容器"。容器能不能用必须问"stop() 到底正常返回没有"。
     * `MediaMuxer.stop()` 未 start 恒抛，正常返回本身就蕴含"本段曾 start"。
     * 段起始 / prepare / release 复位（逐段信号不许跨段残留）。
     */
    @Volatile private var muxFinalized = false

    /**
     * 本段是否真有视频样本写进 muxer（writeSample 返回 >0 时置位；与逐段账同处复位）。
     *
     * 【为什么不能复用 segVideoWritten】pumpLoop 顶部 `segSampleCounts.add(segVideoWritten);
     * segVideoWritten = 0` 排在 closeSegmentEngine/sealCurrent **之前**——keep 判据若读它，
     * 所有段都会被判成"零样本"（见 [segmentKeepDecision] 的 containerFinalized）。
     */
    private var segHadVideoSample = false

    // ---- 起录自检三格（泵线程单写者，[health] 今天只在控制线程读，故一律 @Volatile 是防御未来把
    // 自检挪出该线程的零成本保险）----
    // 三格的定义与判定见 RecordHealth.kt：建轨 / muxer 启动 / 首个视频样本落地。
    /** INFO_OUTPUT_FORMAT_CHANGED 后 addTrack 成功（videoTrack ≥ 0） */
    @Volatile private var healthVideoTrackAdded = false
    /** startMuxerIfReady 内 mx.start() 成功 */
    @Volatile private var healthMuxStarted = false
    /** 首个视频样本 writeSampleData 成功（writeSample 返回 >0） */
    @Volatile private var healthFirstVideoSample = false
    /** start() 时刻（自检窗口计时基准；未 start 为 0） */
    @Volatile private var healthStartMs = 0L

    /**
     * 逐段视频样本数账（索引=partIndex）：位次账（arcDrops）里的 k 是跨段全局视频帧序号，
     * 段与位次靠这份账对上。只在泵线程读写，stop 收尾时随 [RecordResult.segVideoSamples] 交给
     * sidecar 段清单（CameraScreen → ArcDropLog.segments）。
     */
    private val segSampleCounts = ArrayList<Int>()

    // ---- 换段腿的有界化与丢帧账（2026-10-08 真机缺陷修复，判定/记账见 RotateGuard.kt）----

    /**
     * 换段腿起点（`elapsedRealtime`；0 = 当前不在换段腿里）。**编码器被拆的那一刻**起钟——
     * 此后进来的帧写不进任何容器 = 丢帧窗口开启。泵线程写、停止路径（控制线程）读。
     *
     * 为什么是 [AtomicLong] 而不是 @Volatile Long：结算有**两处**——泵线程的 [settleRotateLeg]
     * 与控制线程的 [settleRotateLegAtStop]。@Volatile 只保证「读到的值新」，不保证
     * 「读-算-清-加」这四步原子：控制线程读出 start、泵线程抢先结算清 0、控制线程再 add
     * ⇒ 同一条腿记两遍，[rotateLoss] 读数虚高约一份部分腿（用户可见的失败结果里的丢失量也会多算）。
     * `getAndSet(0L)` 把「读 + 清」做成**一次 CAS**：谁先消费谁拿到 start，另一侧必得 0 早退，
     * 双加在结构上不可能发生，零窗口零锁。
     *
     * 「控制线程那一笔不能删」：[rotateLoss.totalMs] 被 [finishWithoutTeardown] 拿去生成
     * **用户可见**的失败结果，删掉用户看到的丢失量就少算一份。
     */
    private val rotateLegStartMs = AtomicLong(0L)

    /** 本腿里是否有入库调用撞了有界上限（泵线程与停止路径都可能置位 ⇒ @Volatile） */
    @Volatile
    private var rotateStoreTimedOut = false

    /**
     * 上一次换段腿的丢失（ms）：[settleRotateLeg] 写入；下一轮循环顶部先抄进 sidecar [segLostMs]，
     * 随后（本字段归零）由 [currentSegLostMs] 供下一段段行读取。**顺序**：段行帧账的
     * currentSegLost* 必须在 settleRotateLeg 之后赋值——写在它之前会读到上上条腿，导致第 2 段行
     * 恒 0、最后一条腿永不进段行（[FrameLedgerTest] 的三腿桥钉着）。
     */
    private var pendingLegLostMs = 0L

    /**
     * 上一次换段腿内**相机/预览帧到达数**（上界，非"精确丢帧"）：腿从编码器被拆到新段 start，
     * 这段时间进来的帧可能一部分仍写进旧段（旧编码器拆前）或新段（start 前不算），无法逐帧
     * 归属，所以只报「这条腿内到达了多少帧」作为丢帧上界，口径与 [rotateLostMs] 的时间账并列。
     */
    private var pendingLegLostFrames = 0L

    /** 换段腿起点的帧计快照（[beginRotateLeg] 写、[settleRotateLeg] 读） */
    private var rotateLegFramesBase = 0L

    /**
     * 当前段开始之前那条换段腿的账（ms / 帧上界）：泵循环顶部随段账一起落地，
     * [runSegment] 段收尾行读取。首段恒 0（没有换段腿）。
     */
    private var currentSegLostMs = 0L
    private var currentSegLostFrames = 0L

    /**
     * 本段是否请求"重开本段"（新段的 muxer 首笔就写不进 ⇒ 有界重建，见 [muxRecoverDecision]）。
     * 泵线程写、泵线程读，@Volatile 只是防御未来挪出该线程。
     */
    @Volatile
    private var segmentRestartRequested = false

    /** 本会话已重建本段的次数（上界见 [muxRecoverDecision]） */
    private var segmentRestarts = 0

    /** 最近一次 muxer 写失败的现场快照（随失败码进结果/账目，见 [muxFailDiagnostic]） */
    @Volatile
    private var muxFailDetail = ""

    /**
     * 本腿**第一个**失败的环节游标（见 [RotateStage]；空串 = 还没失败）。只记第一次：
     * 后续环节的失败多半是前面卡住后的连锁反应，第一个才是根。随失败码进结果/账目——
     * 这台 ROM 吞掉应用日志，环节游标是唯一能在真机上分辨"卡在哪一步"的通道。
     */
    private var rotateFailStage = ""

    /**
     * 逐段换段丢失账（索引=partIndex，与 [segSampleCounts] **同处、同口径**追加）：
     * 记的是"这一段开始之前那次换段腿里丢掉的画面时长"。泵线程单写者，随
     * [RecordResult.segLostMs] 进 sidecar 段清单，用户事后可查"这段丢了 N 秒"。
     */
    private val segLostMs = ArrayList<Long>()

    /** 跨段丢帧累加账（泵线程 + 停止路径都会记 ⇒ 账本内部加锁） */
    private val rotateLoss = RotateLossLedger()

    /** 有界入库执行器：把走 MediaProvider/FUSE 的同步调用挪出泵线程并按预算等（见 [BoundedStore]） */
    private val storeIo = BoundedStore()

    /**
     * 判超时后迟到落地的 pending 计数（见 [OneShotHandoff]）：回收动作本身要能在账里看见
     * （"幽灵候选"有多少条），泵线程 + 入库线程都可能自增 ⇒ @Volatile。
     */
    @Volatile
    private var latePendingReaped = 0L

    /** 新 pending 的交接闸：判停摆时稳住**晚到**的那一枚（见 [OneShotHandoff]，防幽灵条目） */
    private val pendingHandoff = OneShotHandoff<Uri> { late ->
        latePendingReaped++
        Log.e(TAG, "迟到落地的 pending 就地回收（第 $latePendingReaped 枚）")
        discardBounded(late)
    }

    /** 新输出目标的交接闸：晚到的那枚自己关掉（fd 不许悬到进程结束） */
    private val targetHandoff = OneShotHandoff<OutputTarget> { t ->
        runCatching { t.close() }.onFailure { Log.w(TAG, "晚到的 output target 关闭失败：${it.message}") }
    }

    /** prepare 成功后即稳定可用：Camera2 录像面 / GL outputSurface 的写入目标 */
    override val surface: Any? get() = inputSurface

    /**
     * GPU 路换段重挂钩（[Recorder] 契约）：GPU 路输入面 per-codec（见 [prepareGpuVideoEncoder]），
     * 分段轮转换编码器必须换面，泵线程在建好新面后同步回调、等 GL 挂好才继续。
     * DIRECT 路面是 persistent surface 跨段复用，本钩子不置位（永不回调）。
     */
    @Volatile override var onInputSurfaceRecreated: ((Any) -> Unit)? = null

    // region 准备

    override fun prepare(p: RecordProfile, sink: OutputSink): Boolean {
        if (state != EngineState.IDLE) {
            Log.e(TAG, "codec prepare ignored, state=$state")
            return false
        }
        state = EngineState.PREPARE
        engineError = null
        degradedCode = null
        ambientLost = false
        denoiseInactive = false
        abandonSegment = false
        stopping = false
        rotatePending = false
        // 帧账从新会话起算（诊断导出只留本次录制的段账，不混上一场）
        Log.clearFrameAccount()
        healthVideoTrackAdded = false
        healthMuxStarted = false
        healthFirstVideoSample = false
        healthStartMs = 0L
        parts.clear()
        partIndex = 0
        partStartMs = 0L
        segVideoWritten = 0
        segWrittenBytes = 0L
        preMuxBuffer = null
        preMuxBuffered = 0L
        preMuxDropped = 0L
        muxFinalized = false
        segHadVideoSample = false
        segSampleCounts.clear()
        resetRotateBooks()
        envFeederStartUs = 0L
        capFeederStartUs = 0L
        preCalShiftUs = 0L
        clock.reset()
        // 分步耗时打点（2026-10-12，纯观测）：注册表 REG-10「起录 1~2s 延迟（低端机 insert+prepare
        // 串行链正常量级）」从未有过分步读数，这行给将来动起录链攒第一手数据。零行为变更。
        val pt0 = SystemClock.elapsedRealtime()
        store.checkFreeSpace()?.let {
            reject(sink, it)
            return false
        }
        // 2026-10-12 有界化：openAssetFileDescriptor 同样走 MediaProvider（与 insert 同族卡顿前科），
        // prepare 期不在换段腿（rotateLegStartMs=0）→ rotateBudgetLeftMs() 自动给 STORE_CALL_BUDGET_MS；
        // 超时回 null → 下方 reject(NO_OUTPUT) 判起录失败，与泵路/换段腿同一套 BoundedStore 体系
        val t = openTargetBounded(sink)
        if (t == null || !t.usable) {
            reject(sink, RecordError.NO_OUTPUT)
            return false
        }
        val ptOpen = SystemClock.elapsedRealtime()
        val mime = resolveVideoMime(p)
        // GPU 路（EGL 当 producer）输入面必须 per-codec（[prepareGpuVideoEncoder]，真机实测
        // persistent surface 挂 EGL 会 EGL_BAD_ALLOC）；DIRECT 路（Camera2 当 producer）保持
        // persistent surface 跨会话复用的既有口径，行为不变。
        val v: MediaCodec? = if (p.useGpu) {
            prepareGpuVideoEncoder(mime, p)
        } else {
            val inSurf = inputSurface ?: MediaCodec.createPersistentInputSurface().also { inputSurface = it }
            createVideoEncoder(mime, p, inSurf)
        }
        if (v == null) {
            // t 已打开（fd 在手）但 target 字段还没轮到赋值，reject 的 target?.close() 够不着它：
            // 必须在这里先关，否则 openAssetFileDescriptor 的 fd 一直悬到进程结束
            t.close()
            closeSegmentEngine()
            reject(sink, RecordError.PREPARE_FAILED)
            return false
        }
        val ptVenc = SystemClock.elapsedRealtime()
        vCodec = v
        // 静音 / 无权限 / 采样率不支持 → 完全不初始化音频通路（环境、内录两轨一起关）；
        // 音源双开定版（2026-10-07）：内录轨不再挂在环境编码器之下，「仅内录」（环境音关）
        // 也吃同一套音频通路参数（AudioRecord minBufferSize 两源同值），minBuf 按「任一轨要建」起算
        val minBuf = if ((p.audioUsable || p.captureAudio) && AudioProbe.hasRecordPermission(ctx))
            AudioProbe.minBufferSize(p.sampleRate, p.channels) else 0
        aacMinBuf = if (minBuf > 0) minBuf else 0
        audioSource = AudioProbe.pickSource(ctx)
        val a = if (minBuf > 0 && p.audioEnabled) createAudioEncoder(p, minBuf) else null
        if (a == null && p.audioUsable)
            Log.i(TAG, "audio dropped on codec path: sr=${p.sampleRate} ch=${p.channels} minBuf=$minBuf")
        // 环境编码器建失败且能力面本应可行 ⇒ 上报可见化（S1-M3-05 第二路）。判据读清：
        // **只在 minBuf>0 && p.audioEnabled（= 真尝试建过编码器）时**报——minBuf=0 的两种成因
        // （无 RECORD_AUDIO 权限 / 采样率组合不支持）是产品明确的纯视频降级，走上面那行既有日志
        // 与面板侧提示，从这里上报会把"有意降级"误报成"丢轨"。
        if (a == null && minBuf > 0 && p.audioEnabled) reportAmbientLost("prepare 期环境编码器建失败")
        aCodec = a
        // 内录第二音轨（P2：单 MP4 双 AAC）：参数与钟域同环境轨；两轨编码器**独立**创建
        //（2026-10-07 双开定版：环境音关不连坐内录，仅内录组合合法）
        val c = if (minBuf > 0 && p.captureAudio) createAudioEncoder(p, minBuf) else null
        if (c == null && p.captureAudio)
            Log.i(TAG, "capture track dropped on codec path: sr=${p.sampleRate} ch=${p.channels} minBuf=$minBuf")
        capCodec = c
        // 两轨全没建起来才清：仅内录（a=null 合法）时 aacMinBuf 还要留给分段轮转重建 cap 编码器
        if (a == null && c == null) aacMinBuf = 0
        // 两轨**独立**降级（双开定版 2026-10-07）：环境编码器建失败只关环境（内录不连坐，
        // 反之亦然），与 833 行重建闸「环境路降级不该连坐健康的内录路」同一口径。
        // 旧「a 失败两条全关」只在 cap 前置=env 已建的时代必要，现在 c 自己判自己
        profile = when {
            a == null && p.audioEnabled -> p.copy(audioEnabled = false)
            c == null && p.captureAudio -> p.copy(captureAudio = false)
            else -> p
        }
        val ptAudio = SystemClock.elapsedRealtime()
        target = t
        currentSink = sink
        if (!openMuxer(t, p.orientationHint)) {
            closeSegmentEngine()
            reject(sink, RecordError.NO_OUTPUT)
            return false
        }
        val ptMux = SystemClock.elapsedRealtime()
        Log.i(TAG, "prepare steps: open=${ptOpen - pt0} venc=${ptVenc - ptOpen} " +
            "audio=${ptAudio - ptVenc} mux=${ptMux - ptAudio} total=${ptMux - pt0}ms（REG-10 起录延迟观测）")
        Log.i(TAG, "codec prepared part=$partIndex ${p.width}x${p.height}@${p.fps} mime=$mime " +
            "bps=${p.effectiveBitrate()} audio=${if (a == null) "off" else "${p.sampleRate}Hz/${p.channels}ch"} " +
            "capture=${if (c == null) "off" else "on"} " +
            "hint=${p.orientationHint} gpu=${p.useGpu} maxFile=${WotaTiers.MAX_FILE_BYTES}")
        return true
    }

    /** HEVC 只在「存在支持 Surface 输入且吃下该分辨率的编码器」时用，否则回退 H264 */
    private fun resolveVideoMime(p: RecordProfile): String {
        if (!p.hevc) return MediaFormat.MIMETYPE_VIDEO_AVC
        val name = findSurfaceEncoder(MediaFormat.MIMETYPE_VIDEO_HEVC, p.width, p.height)
        if (name == null) {
            Log.i(TAG, "no surface-capable hevc encoder for ${p.width}x${p.height}, fallback avc")
            return MediaFormat.MIMETYPE_VIDEO_AVC
        }
        return MediaFormat.MIMETYPE_VIDEO_HEVC
    }

    /** 能力一律运行时读（AGENTS.md：禁止硬编码机型数值） */
    private fun findSurfaceEncoder(mime: String, w: Int, h: Int): String? {
        val infos = try {
            @Suppress("DEPRECATION")
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        } catch (e: Exception) {
            Log.e(TAG, "codec list unavailable: ${e.message}")
            return null
        }
        for (info in infos) {
            if (!info.isEncoder) continue
            if (!info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) continue
            val caps = try {
                info.getCapabilitiesForType(mime)
            } catch (e: IllegalArgumentException) {
                continue
            }
            if (!caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) continue
            val ok = try {
                caps.isFormatSupported(MediaFormat.createVideoFormat(mime, w, h))
            } catch (e: Exception) {
                false
            }
            if (ok) return info.name
        }
        return null
    }

    /**
     * 找一枚"能吃 YUV420 ByteBuffer 输入"的编码器，顺带给出它接受的颜色格式。
     *
     * 与 [findSurfaceEncoder] 分开命名（不改旧名以免动既有调用点）是因为两者探测的条件不同：
     * Surface 路线要 `COLOR_FormatSurface`，YUV 路线要 `COLOR_FormatYUV420*`，一类机型的两个能力
     * 不一定出现在同一枚编码器上（真机实测就有只吐 Flexible、不给 Surface 的实现）。
     *
     * 颜色格式按「Flexible → SemiPlanar → Planar」优先级挑：**Flexible 优先**是因为只有它保证
     * `getInputImage()` 能拿到带合法 rowStride/pixelStride 的 Image，从而不必猜内存布局；
     * 后两者只能在 `getInputBuffer()` 上手写字节序，是最差情况下的退路。
     */
    private fun findYuvEncoder(mime: String, w: Int, h: Int): Pair<String, Int>? {
        val infos = try {
            @Suppress("DEPRECATION")
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        } catch (e: Exception) {
            Log.e(TAG, "codec list unavailable: ${e.message}")
            return null
        }
        val priority = intArrayOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        )
        for (info in infos) {
            if (!info.isEncoder) continue
            if (!info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) continue
            val caps = try {
                info.getCapabilitiesForType(mime)
            } catch (e: IllegalArgumentException) {
                continue
            }
            // 先过分辨率闸：吃不下的编码器即便自称支持 mime 也不能用
            val ok = try {
                caps.isFormatSupported(MediaFormat.createVideoFormat(mime, w, h))
            } catch (e: Exception) {
                false
            }
            if (!ok) continue
            val cf = priority.firstOrNull { caps.colorFormats.contains(it) } ?: continue
            return info.name to cf
        }
        return null
    }

    /**
     * 建一枚 YUV420 ByteBuffer 输入的编码器（能力探测走 [findYuvEncoder]）。
     *
     * **为什么单独开一个方法而不是复用 [createVideoEncoder]**：录制与 GPU 路线走 Surface 输入，
     * 帧的时间戳由输入面的生产者按系统墙钟生成，代码给不了 PTS；光弧修复的 **CPU 路线**要把逐平面
     * 混合结果直接写进编码器输入缓冲并要求**自己指定 PTS**，只能走 ByteBuffer 模式（surface 传 null）。
     * 真机教训：曾用 `Surface.lockCanvas()` 往输入面写帧，1.875s 的素材 CPU 处理了 41s，容器里的
     * 帧步长就被写成 ≈0.46s（21 倍慢放）——这不是"处理快一点"能绕过的，必须换成显式 PTS。
     *
     * 除 `KEY_COLOR_FORMAT` 外，其余参数与 [createVideoEncoder] **逐字相同**：两条路线的产物码率/画质
     * 必须同口径，否则同一素材走 GPU 与 CPU 会得到两种画质（AGENTS.md：不为同一件事写第二份口径）。
     */
    internal fun createYuvEncoder(mime: String, p: RecordProfile): MediaCodec? {
        val found = findYuvEncoder(mime, p.width, p.height)
        if (found == null) {
            Log.e(TAG, "no yuv420 encoder for $mime ${p.width}x${p.height}")
            return null
        }
        val (name, colorFormat) = found
        val codec = try {
            MediaCodec.createByCodecName(name)
        } catch (e: IOException) {
            Log.e(TAG, "yuv encoder create failed ($name): ${e.message}")
            return null
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "bad yuv encoder name ($name): ${e.message}")
            return null
        }
        val fmt = MediaFormat.createVideoFormat(mime, p.width, p.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            )
            setInteger(MediaFormat.KEY_BIT_RATE, p.effectiveBitrate())
            // 整数声明：NTSC 非整数档也照写整数，理由见本文件头「NTSC 帧率的容器声明取舍」
            setInteger(MediaFormat.KEY_FRAME_RATE, p.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SEC)
            if (p.timeLapse) setFloat(KEY_CAPTURE_RATE, p.captureRate)
        }
        return try {
            // surface = null：这就是与 Surface 路线的唯一差别，PTS 交回调用方掌控
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec
        } catch (e: Exception) {
            Log.e(TAG, "yuv video configure failed: ${e.javaClass.simpleName} ${e.message}")
            releaseQuietly(codec)
            null
        }
    }

    /**
     * GPU 路的视频编码器 + 输入面建立：configure 传 null 只建不绑，随后用
     * [MediaCodec.createInputSurface] 取 **per-codec** 输入面（合法窗口 = configure 之后、
     * start 之前）。**不用** [MediaCodec.createPersistentInputSurface]：那是「MediaRecorder 与
     * MediaCodec 共享同一输入面」的专用件，拿它当 EGL 渲染目标在真机 `eglCreateWindowSurface`
     * 直接失败（EGL_BAD_ALLOC 0x3003，实测三处：ArcRepairRunner 两轮 + 24※ 录制期转换首轮——
     * 强制档恒走本引擎，这是它在本机 GPU 路的第一次真机运行，普通 30fps 走 MrRecorder 不进这里）。
     * per-codec 面随编码器实例换代，分段轮转的重挂经 [onInputSurfaceRecreated] 同步通知。
     * @return null = 编码器或输入面建立失败（内部已清理，调用方按 PREPARE_FAILED 收场）
     */
    private fun prepareGpuVideoEncoder(mime: String, p: RecordProfile): MediaCodec? {
        val v = createVideoEncoder(mime, p, null) ?: return null
        return try {
            inputSurface = v.createInputSurface()
            v
        } catch (e: Exception) {
            Log.e(TAG, "createInputSurface failed: ${e.javaClass.simpleName} ${e.message}")
            inputSurface = null
            releaseQuietly(v)
            null
        }
    }

    /**
     * 建一枚 Surface 输入的编码器（能力探测走 [findSurfaceEncoder]，参数表与录制逐字相同）。
     * `internal` 供 ArcRepair 复用：光弧修复的插帧编码器与录制必须同一套能力探测与参数口径，
     * 复制一份迟早与录制分叉（AGENTS.md：不为同一件事写第二份）。
     *
     * @param surface 输入面；**传 null = 只 configure 不绑面**，调用方随后自行
     *   `codec.createInputSurface()` 取它自己的输入面。GPU 录制路（[prepareGpuVideoEncoder]）与
     *   ArcRepair 走这条：MediaCodec 的 `createPersistentInputSurface()` 是给 MediaRecorder 共享
     *   输入面的专用件，拿它当 EGL 渲染目标在真机上 `eglCreateWindowSurface` 直接失败
     *   （EGL_BAD_ALLOC 0x3003，实测）。DIRECT 录制路（Camera2 当 producer）照旧传 persistent
     *   输入面，行为逐字不变。
     */
    internal fun createVideoEncoder(mime: String, p: RecordProfile, surface: Surface?): MediaCodec? {
        val name = findSurfaceEncoder(mime, p.width, p.height)
        val codec = try {
            if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createEncoderByType(mime)
        } catch (e: IOException) {
            Log.e(TAG, "no video encoder for $mime: ${e.message}")
            return null
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "bad encoder name: ${e.message}")
            return null
        }
        val fmt = MediaFormat.createVideoFormat(mime, p.width, p.height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            )
            setInteger(MediaFormat.KEY_BIT_RATE, p.effectiveBitrate())
            // 整数声明：NTSC 非整数档也照写整数，理由见本文件头「NTSC 帧率的容器声明取舍」
            setInteger(MediaFormat.KEY_FRAME_RATE, p.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SEC)
            if (p.timeLapse) setFloat(KEY_CAPTURE_RATE, p.captureRate)
        }
        return try {
            codec.configure(fmt, surface, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec
        } catch (e: Exception) {
            Log.e(TAG, "video configure failed: ${e.javaClass.simpleName} ${e.message}")
            releaseQuietly(codec)
            null
        }
    }

    private fun createAudioEncoder(p: RecordProfile, minBuf: Int): MediaCodec? {
        val codec = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        } catch (e: Exception) {
            Log.e(TAG, "no aac encoder: ${e.message}")
            return null
        }
        val fmt = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, p.sampleRate, p.channels
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, p.audioBitrate)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, p.channels)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuf) // = AudioRecord minBufferSize
        }
        return try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec
        } catch (e: Exception) {
            Log.e(TAG, "audio configure failed: ${e.message}")
            releaseQuietly(codec)
            null
        }
    }

    private fun openMuxer(t: OutputTarget, hint: Int): Boolean {
        val m = try {
            val fd = t.fileDescriptor
            if (fd != null) MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            else {
                val path = t.path ?: return false
                MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }
        } catch (e: Exception) {
            Log.e(TAG, "muxer open failed: ${e.message}")
            null
        } ?: return false
        // MediaMuxer 侧的旋转标记（旋转节）；GL 已按方向画，所以这里只做容器元数据。
        // 写出值统一经 exportOrientationHint，与 ArcRepairRunner 同一约定（见 ORIENTATION_MUXER_CCW），
        // 避免"修了修复路、录制路还错"
        try {
            m.setOrientationHint(exportOrientationHint(hint, ORIENTATION_MUXER_CCW))
        } catch (e: IllegalStateException) {
            Log.i(TAG, "muxer orientation hint ignored: ${e.message}")
        }
        muxer = m
        return true
    }

    // endregion

    // region 启停

    /**
     * 蓝牙内录预校准（[Recorder] 契约）：只在双音轨编码器都建好、且当前输出是 A2DP 时跑。
     * 任何失败都只清 [preCalShiftUs] 并返回 false——不置错误、不删 pending；成功路径最多增加
     * 约 800ms 有界采集，失败/取消由 shouldCancel 在轮询粒度内返回。
     */
    override fun preflightAudioSync(shouldCancel: () -> Boolean): Boolean {
        val p = profile ?: return false
        if (state != EngineState.PREPARE) return false
        val envOn = p.audioEnabled && aCodec != null
        val capOn = p.captureAudio && capCodec != null
        // 路由探测失败按"不是 A2DP"处理：预校准只是增益功能，不许把异常带进起录链
        val a2dp = runCatching { routeProbe.isA2dpOutput() }.getOrDefault(false)
        if (!AudioPreflightPolicy.shouldPreflight(envOn, capOn, a2dp)) {
            preCalShiftUs = 0L
            return false
        }
        val r = runCatching {
            AudioPreflightCapture(ctx).capture(p.sampleRate, p.channels, audioSource, shouldCancel)
        }.getOrElse {
            // 预校准自身异常也只留日志：它只是增益功能，不许打断起录
            Log.w(TAG, "蓝牙预校准异常：${it.javaClass.simpleName} ${it.message}")
            PreflightResult(false)
        }
        if (!r.ok) {
            preCalShiftUs = 0L
            Log.i(TAG, "蓝牙预校准未生效，按原时间轴录制")
            return false
        }
        preCalShiftUs = r.shiftUs
        Log.i(
            TAG,
            "蓝牙预校准 offset=${r.offsetMs} conf=${r.confidence} shift=${preCalShiftUs}us"
        )
        return true
    }

    override fun start() {
        if (state != EngineState.PREPARE) {
            Log.e(TAG, "codec start ignored, state=$state")
            return
        }
        // 内录静音告警的"按场复位"（2026-10-16 灵敏化：去重粒度从捕获会话改为每场录制）：
        // 连录两场都全静音要能各提示一次；上场的节流账不带进本场。此处是每场录制唯一入口
        PlaybackCaptureController.get(ctx).resetCaptureSilenceTake()
        // 音频采集与编码器同刻启动，避免 PCM 积压落到下一个 PTS 基准上（环境、内录两个 feeder）
        if (aCodec != null && feeder == null) startFeederOrDropAudio()
        if (capCodec != null && capFeeder == null) startCapFeederOrDropAudio()
        try {
            vCodec?.start()
            aCodec?.start()
            capCodec?.start()
        } catch (e: Exception) {
            Log.e(TAG, "codec start failed: ${e.message}")
            engineError = RecordError.START_FAILED
            state = EngineState.ERROR
            closeSegmentEngine()
            stopFeeder()
            stopCapFeeder()
            discardCurrent(RecordError.START_FAILED)
            state = EngineState.IDLE
            return
        }
        segBaseUs = System.nanoTime() / 1000L
        resetMuxPtsBooks()
        state = EngineState.START
        healthStartMs = SystemClock.elapsedRealtime() // 自检窗口从这里起算
        partStartMs = clock.elapsedMs()
        clock.startSegment()
        stopping = false
        rotatePending = false
        loop = Thread({
            // 【泵线程降级 2026-10-09】原为 THREAD_PRIORITY_AUDIO(-16)，现改回 THREAD_PRIORITY_DEFAULT(0)。
            // 为什么反向：这条线程 **90% 时间阻塞在 `dequeueOutputBuffer(2ms)`**（等编码器吐数据），
            // 还兼做 MediaMuxer 的文件 IO——典型的「让出 CPU 型」负载，抬到 -16 是把让出者当成抢占者。
            // 更要命的是：GL 渲染线程要吃 CPU 时，nice -16 的泵排在它前面；而 GL 是成片帧的**唯一
            // 生产者**，它错过 24fps 名义网格的落点 = 成片空一帧（真机实测每 4 秒一个空格），
            // 泵慢则是背压回传到编码面 → GL，同样烧成片。**烧成片的链条更短、更贵。**
            // 音频侧不吃亏：两枚 AudioFeeder 采集线程仍留在 -16（要跟 AudioRecord 内部缓冲赛跑），
            // 且 PCM 队列有 ~1.3s 深度冗余（QUEUE_CAPACITY=64），泵晚 10ms 起步根本追不上队列。
            // 故此处的调度档与采集档**故意不再相同**（原守卫钉死「两处都音频类」已随之作废）。
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT) }
            pumpLoop()
        }, "WotaCodecMux").apply {
            start()
        }
        // 启动记账（批 4 双轨对齐校正的数据基础）：双 feeder 的 start 时刻与间隔都在 nanoTime 钟域，
        // 各源首包 PTS 由 feeder 自报（「首包记账」行）——环境/内录两轨的启动偏移一行可查
        if (envFeederStartUs > 0 || capFeederStartUs > 0) {
            Log.i(
                TAG,
                "启动记账 envStart=${envFeederStartUs}us capStart=${capFeederStartUs}us " +
                    "feeder间隔=${if (envFeederStartUs > 0 && capFeederStartUs > 0) capFeederStartUs - envFeederStartUs else -1}us"
            )
        }
        Log.i(
            TAG,
            "codec start part=$partIndex from=${partStartMs}ms " +
                "audio=${if (feeder == null) "off" else "on"} capture=${if (capFeeder == null) "off" else "on"}"
        )
    }

    /**
     * 环境音（麦克风）路丢失的**唯一上报口**（S1-M3-05 收口）：置会话级标志（[health] 供录制页
     * 提示一次、[buildResult] 供结果文案附注）并留一条 E 级日志。标志是布尔或 ⇒ 幂等。
     *
     * 调用边界（"该报才报"由调用方保证）：只在「用户要求了环境音、能力面本应可行却没成」时报
     * ——有意降级不算：权限缺失 / 采样率组合不支持（`minBuf=0` 一族）走既有日志与面板提示，
     * **不**从这里上报（那两种是产品明确的纯视频降级，用户已被告知）。
     *
     * @param why 中文短因（环境音丢在哪一环；只进日志，不进 UI——文案在资源文件里按语义统一）
     */
    private fun reportAmbientLost(why: String) {
        ambientLost = true
        Log.e(TAG, "环境音轨丢失：$why（视频照常，本会话不再录环境音）")
    }

    private fun startFeederOrDropAudio() {
        val p = profile ?: return
        val f = AudioFeeder(ctx, p.sampleRate, p.channels, audioSource, aacMinBuf, p.audioDenoise)
        if (f.start()) {
            feeder = f
            envFeederStartUs = System.nanoTime() / 1000L
            // 降噪"要了却没生效"：只在这时才有真值（attach 的成败要到起录才知道）——
            // 报给 health，由录制页提示一次（用户口径：自动降档都要看得见）
            if (p.audioDenoise != com.wotagei.cam.core.AudioDenoise.OFF && !f.denoiseActive) {
                denoiseInactive = true
                Log.w(TAG, "音频降噪未生效（本机无 NoiseSuppressor 或启用失败），环境音按原样录")
            }
            return
        }
        // 起不来就整条音频通路撤掉：视频继续（的静默降级）。profile 一并改写（P3，与内录
        // 首启失败同族口径）：轮转不再重建环境编码器——只置 aCodec=null 的话轮转会复活编码器
        // 而采集源已死，后续段出零样本幽灵轨、muxer 启动不变式恒卡死
        f.stop()
        releaseQuietly(aCodec)
        aCodec = null
        profile = p.copy(audioEnabled = false)
        // 能走到这里 = prepare 已把环境编码器建出来（aCodec != null ⇒ minBuf>0 = 权限在、参数
        // 支持，用户要了环境音），采集器却在启动一刻失败——"能力面本应可行却没成"，与内录
        // INIT_FAILED 对称上报（有意降级不在此列：那些在 prepare 就因 minBuf=0 不进本函数）
        reportAmbientLost("环境采集器启动失败")
        Log.i(TAG, "audio feeder unavailable, continue video only")
    }

    /**
     * 内录捕获源 feeder：AudioRecord 由 [PlaybackCaptureController.createCaptureAudioRecord]
     * 在 Active 态建。起不来（会话已失效/参数不支持）就整条内录通路撤掉：环境音+视频继续——
     * 「会话活着但起不来」与「录制中失效」都降级，成片至少是视频+环境音。
     */
    private fun startCapFeederOrDropAudio() {
        val p = profile ?: return
        val f = AudioFeeder(
            capture = PlaybackCaptureController.get(ctx),
            sampleRate = p.sampleRate,
            channels = p.channels,
            minBufferSize = aacMinBuf
        )
        if (f.start()) {
            capFeeder = f
            capFeederStartUs = System.nanoTime() / 1000L
            return
        }
        // 失败原因可见化（此前只留日志）：采集器建不起来的机型上用户只会看到"没内录轨"，
        // detail 取 controller 在 createCaptureAudioRecord 各失败分支记下的中文短因
        val ctrl = PlaybackCaptureController.get(ctx)
        ctrl.reportTrackLost(CaptureTrackLostReason.INIT_FAILED, ctrl.lastInitFailure)
        f.stop()
        releaseQuietly(capCodec)
        capCodec = null
        // P0-b：profile 一并改写——只释放 capCodec 的话轮转会复活编码器而 feeder 恒 null，
        // 后续段出零样本幽灵轨（同族降级口径：采集失败 → profile 改写 → 轮转不空试）
        profile = p.copy(captureAudio = false)
        Log.i(TAG, "capture feeder unavailable, continue without capture track")
    }

    override fun stop(): RecordResult {
        val prev = state
        if (prev == EngineState.IDLE) return finish(engineError)
        if (prev != EngineState.START) {
            // PREPARE / ERROR：一帧都没写，直接回收 pending
            stopping = true
            // 同样走「超时即只记账」闸：PREPARE/ERROR 下泵线程本就没起（loop==null，闸恒放行），
            // 但闸放在这里是为了**只有一处**决定"泵线程还活着时能不能拆"，不许各分支各写一份
            if (!joinLoop()) {
                settleRotateLegAtStop()
                return finishWithoutTeardown()
            }
            closeSegmentEngine()
            stopFeeder()
            stopCapFeeder()
            discardCurrent(engineError ?: RecordError.BAD_STATE)
            return finish(engineError ?: RecordError.BAD_STATE)
        }
        state = EngineState.STOPPING
        stopping = true
        // 循环内完成 EOS → 抽干 → 封段 → 提交。泵线程若还在换段腿里（这一步现在有界，见
        // ROTATE_LEG_BUDGET_MS），停止等待就可能超时——**不许当"没什么事"放行**：
        // 那正是真机上"用户按了停止、内容已经丢了几十秒、结果却报已保存"的形态，
        // 所以超时即按换段受阻结算（明确失败 + 丢帧账进结果）。
        // 【但只记账，不拆资源】：泵线程还活着时，它才是 vCodec/aCodec/capCodec/muxer/target/
        // currentSink/parts 的单写者（完整双触名单见 [settleRotateLegAtStop] 的 KDoc）。
        // 控制线程在这时候抢拆，就是同一枚 MediaMuxer 被两线程 stop/release（native 释放后
        // 另一线程还在写样本 = 崩溃路径）、同一枚 pending 被两路 seal、parts.add 与 clear 撞车。
        // （2026-10-16：parts 已 COW 化，超时路径只**读快照**——在名单里是唯一获准的安全读，
        // 仍然不许 add/clear。）
        if (!joinLoop()) {
            settleRotateLegAtStop()
            return finishWithoutTeardown()
        }
        closeSegmentEngine()
        // AudioRecord 的 stop/release 固定排在 codec、muxer 之后（环境、内录两个 feeder）
        stopFeeder()
        stopCapFeeder()
        return finish(engineError)
    }

    /**
     * 显式弃段（[Recorder.abandonCurrentSegment] 契约）：置位后本段封段一律 keep=false。
     *
     * 【为何本路也要接】审查时核对过 runSegment 的 stopping 分支与 pumpLoop 的 seal 路径：
     * 「muxer 没 start」会先置 [RecordError.NO_VIDEO_TRACK]、「muxer.stop 抛」会在 closeSegmentEngine
     * 里置 `ENGINE_ERROR:MUX_CLOSE`——这两态本来就会 discard。但存在一条**无错误码、三格不全**的态：
     * `healthVideoTrackAdded=true`（轨已建）且 `muxStarted=true`（muxer 已启）却**没有首个视频样本写入**
     * （`healthFirstVideoSample=false`，如 auto-stop 的前 3s 长窗内视频编码器迟迟不吐样本），此时
     * `ok=true、engineError=null、dur>=MIN_KEEP` 会 keep=true 把只有容器的空片 commit 进相册——正是
     * MR 路静默零帧的同族形态。所以本路不允许赌，同样接上 [abandonSegment]。
     */
    override fun abandonCurrentSegment() {
        abandonSegment = true
        Log.i(TAG, "codec abandon current segment requested")
    }

    override fun release() {
        if (state == EngineState.START || state == EngineState.STOPPING) stop()
        stopping = true
        // 泵线程还活着时不许拆资源（stop() 的双触名单）：它会照常走 pumpLoop 的
        // closeSegmentEngine/sealCurrent 把 codec/muxer 拆掉、把本段封掉。抢拆就是两线程同拆。
        // 本对象每个会话新建一个（CameraScreen 收尾即 `recorder = null`），不复用 ⇒ 这里直接返回，
        // 不必把它复位成 IDLE；真拆不掉时也已经只剩进程退出这一条兜底。
        if (!joinLoop()) {
            Log.e(TAG, "release 时泵线程仍未退出：只记账不拆资源（资源与封段由泵线程收尾自己完成）")
            return
        }
        closeSegmentEngine()
        stopFeeder()
        stopCapFeeder()
        val s = inputSurface
        inputSurface = null
        s?.release()
        state = EngineState.IDLE
        engineError = null
        degradedCode = null
        ambientLost = false
        denoiseInactive = false
        abandonSegment = false
        clock.reset()
        parts.clear()
        partIndex = 0
        partStartMs = 0L
        segVideoWritten = 0
        segWrittenBytes = 0L
        preMuxBuffer?.clear()
        preMuxBuffer = null
        muxFinalized = false
        segHadVideoSample = false
        segSampleCounts.clear()
        resetRotateBooks()
        Log.i(TAG, "codec released")
    }

    /** 换段腿/丢帧账复位（新会话、新段序列与释放三处共用同一份口径，不许各写一份） */
    private fun resetRotateBooks() {
        rotateLegStartMs.set(0L)
        rotateStoreTimedOut = false
        pendingLegLostMs = 0L
        pendingLegLostFrames = 0L
        rotateLegFramesBase = 0L
        currentSegLostMs = 0L
        currentSegLostFrames = 0L
        rotateFailStage = ""
        latePendingReaped = 0L
        segmentRestartRequested = false
        segmentRestarts = 0
        muxFailDetail = ""
        segLostMs.clear()
        rotateLoss.clear()
    }

    override val elapsedMs: Long get() = clock.elapsedMs()

    /**
     * 起录自检读数：三格在泵循环里已有对应内部状态（建轨 / muxer 启动 / 首样本），这里只提升成
     * 可读快照。errorCode 与 [engineError] **同源**（不另立一份会漂移的镜像）——写入点分布在
     * 泵循环/收尾多处，任何一处漏镜像都会让 UI 读到过期的"健康"。
     * [RecorderHealth.outputBytes] 填本段已写 muxer 字节账（[segWrittenBytes]，复用写入点已有的账，
     * 不新开计数）——本路 UI 走三格 [healthVerdict]、不读它，但两个通道都如实填，语义不打折。
     */
    override fun health(): RecorderHealth {
        return RecorderHealth(
            videoTrackAdded = healthVideoTrackAdded,
            muxStarted = healthMuxStarted,
            firstVideoSample = healthFirstVideoSample,
            errorCode = engineError,
            elapsedMs = if (healthStartMs > 0L) SystemClock.elapsedRealtime() - healthStartMs else 0L,
            outputBytes = segWrittenBytes,
            milestonesObservable = true,
            // 环境音丢失（S1-M3-05 可见化）：录制页巡检读到即提示一次（结果文案另有附注，见 buildResult）
            ambientLost = ambientLost,
            // 音频降噪"要了却没生效"（2026-10-26）：同一条巡检提示通道，见字段注释
            denoiseInactive = denoiseInactive
        )
    }

    /** PCM 均方根；量程与 MediaRecorder.getMaxAmplitude 一致，UI 音量表两引擎通用。
     *  只读**环境**源（P6 裁决：内录模式下音量条仍显环境音 RMS，不加第二根，内录源不入表） */
    override fun amplitude(): Int = feeder?.amplitude() ?: 0

    // endregion

    // region 编码器循环

    private fun pumpLoop() {
        var keepGoing = true
        while (keepGoing) {
            val ok = try {
                runSegment()
            } catch (e: Exception) {
                Log.e(TAG, "mux loop threw: ${e.javaClass.simpleName} ${e.message}")
                engineError = engineError ?: "${RecordError.ENGINE_ERROR}:LOOP"
                false
            }
            // 本段要不要"重开"（muxer 首笔写不进的有限重建，见 writeSample）：与换段共用同一条腿
            val restart = segmentRestartRequested
            segmentRestartRequested = false
            // 段闭落账（泵线程单写者，无竞争）：无论本段成废都要落，索引才与 partIndex 一一对齐；
            // 落完归零，下一段从 0 重新计。换段丢帧账与段账同处落地（首段前没有换段腿 ⇒ 恒 0）。
            // 这里落地的是**上一轮**轮转结算的腿（sidecar segLostMs 与段行同一口径）；本轮结算的
            // 新腿在 settleRotateLeg 之后才写 currentSegLost*（下一轮 runSegment 的段行读到它）
            segSampleCounts.add(segVideoWritten)
            segVideoWritten = 0
            segLostMs.add(pendingLegLostMs)
            pendingLegLostMs = 0L
            pendingLegLostFrames = 0L
            val dur = clock.closeSegment()
            // 这一轮到底要不要换段（判据必须在拆编码器**之前**取好：换段腿的起钟与结算都以它为准，
            // 否则停止时的最后一段封段会被算进"换段腿"——那份慢不该被说成"丢了内容"）
            val willRotate = (ok && engineError == null && rotatePending) || (restart && engineError == null)
            // 换段腿起钟：**编码器即将被拆**那一刻（此后进来的帧写不进任何容器 = 丢帧窗口开启）
            if (willRotate) beginRotateLeg()
            // 【单写者】下面这两步是 codec/muxer/target/currentSink/parts 的**唯一**拆除与封段入口：
            // 控制线程（stop/release）在 joinLoop 超时时只记账、绝不进来抢（双触名单见
            // settleRotateLegAtStop 的 KDoc；parts 已 COW 化后那份"记账"里可以包含只读快照）。
            // 正常停录由 Thread.join 提供 happens-before，不受影响。
            // 先拆 codec/muxer（moov 写完）再封段提交，顺序反了相册会拿到半截文件
            closeSegmentEngine()
            // 弃段意图（自检判废）压过"看着正常"：见 segmentKeepDecision。
            // containerFinalized 由两个**独立**信号合成：muxFinalized（muxer.stop() 正常返回）与
            // segHadVideoSample（本段确有视频样本）——都不许从错误码反推（那个置错是首错优先，
            // MUX_CLOSE 会被 DRAIN 吞掉），也不许读 segVideoWritten（上面几行刚把它清零）
            val keep = segmentKeepDecision(
                abandoned = abandonSegment,
                ok = ok,
                errorCode = engineError,
                durationMs = dur,
                minKeepMs = RecordProfile.MIN_KEEP_MS,
                containerFinalized = muxFinalized && segHadVideoSample
            )
            // 降级呈现：段被保留却带错误码（典型=抽干超时而后容器正常收尾）⇒ 会话记降级码，
            // 结果按"已保存 + 末尾可能不完整"呈现（见 [finish]），原始诊断码同时留日志
            if (keep && engineError != null) {
                degradedCode = engineError
                Log.w(TAG, "本段带错误码但容器已收尾，保留并按降级呈现：$engineError")
            }
            sealCurrent(dur, keep)
            // 停录途中不再换段：`stopping` 置位后 rotatePending 往往恰好也为真（阈值刚触顶就被停），
            // 照旧换段会白建一枚 pending 再按 NO_VIDEO_TRACK 丢掉（附带一个假错误码落进结果）
            val wantRotate = willRotate && !stopping
            var rotated = false
            if (wantRotate) {
                // 重建本段时换段意图可能没置过：这里补清（下一次阈值触顶照常换段）
                rotatePending = false
                rotated = nextSegment()
                // 换段腿失败：一律走带丢帧账+环节游标的明确失败码（可见、可事后分辨环节）。
                // 真机现场就是这条把整场录制当场结束掉（r18 后可见，此前只有一行日志）
                if (!rotated) engineError = engineError ?: rotateStallError(rotateLoss.totalMs, rotateFailStage)
                if (rotated && restart) {
                    segmentRestarts++
                    noteSegmentRestartLoss(dur)
                    Log.e(TAG, "本段已重建（第 $segmentRestarts 次）：丢失 $dur ms + 换段腿")
                }
            }
            // 本腿结算：入库撞上限或腿超预算 ⇒ ROTATE_STALL（明确失败，看门狗/收尾把原因亮给用户）；
            // 没越界但有损失也记账（[segLostMs] → sidecar，用户事后可查"这段丢了 N 秒"）
            settleRotateLeg(judgeStall = wantRotate)
            // 本轮结算的腿账要等 settleRotateLeg 返回才存在：现在写进 currentSegLost*，下一轮
            // runSegment 的段行才读到**紧邻它之前**那条腿（旧位置读的是上上条 ⇒ 第 2 段行恒 0、
            // 最后一条腿永不出现在段行）。pending 留给下一轮顶部落进 sidecar segLostMs，
            // 两个口径同源同值。
            currentSegLostMs = pendingLegLostMs
            currentSegLostFrames = pendingLegLostFrames
            keepGoing = wantRotate && rotated && engineError == null
        }
        if (engineError != null) Log.i(TAG, "codec loop end with ${engineError}")
    }

    /**
     * 换段腿起钟（泵线程）。腿的定义：从编码器被拆到新段 `codec.start()` 返回（见 [ROTATE_LEG_BUDGET_MS]）。
     */
    private fun beginRotateLeg() {
        rotateLegStartMs.set(SystemClock.elapsedRealtime())
        // 帧账上界起点：腿内的相机/预览回调数（不是精确丢帧，见 pendingLegLostFrames）
        rotateLegFramesBase = FrameLedger.snapshot().glCallbacks
        rotateStoreTimedOut = false
        rotateFailStage = ""
    }

    /** 记下本腿第一个失败的环节（后续失败不覆盖：第一个才是根因，见 [rotateFailStage]） */
    private fun noteRotateStage(stage: String) {
        if (rotateFailStage.isEmpty()) rotateFailStage = stage
    }

    /**
     * 换段腿的剩余预算（ms）：腿内按 [ROTATE_LEG_BUDGET_MS] 递减，腿外（停止封段等）用
     * [STORE_CALL_BUDGET_MS]。<=0 = 预算已尽 ⇒ 下一次有界调用直接判超时（不许再发无预算的调用）。
     */
    private fun rotateBudgetLeftMs(): Long {
        val start = rotateLegStartMs.get()
        if (start == 0L) return STORE_CALL_BUDGET_MS
        return ROTATE_LEG_BUDGET_MS - (SystemClock.elapsedRealtime() - start)
    }

    /** 入库调用撞上限（或无预算可发）时的记账：本腿结算会据此判明确失败 */
    private fun noteStoreTimeout(what: String) {
        rotateStoreTimedOut = true
        Log.e(TAG, "换段整备受阻：$what 超过有界上限（本腿剩余预算已算尽），按有界结果继续")
    }

    /**
     * 重建本段的丢失记账：失败的本段一笔都没写成，但它占掉的那段墙钟确实没内容
     * （[durMs] = 该段从 startSegment 到失败那一刻），必须与换段腿的丢失一起进账。
     */
    private fun noteSegmentRestartLoss(durMs: Long) {
        if (durMs <= 0L) return
        pendingLegLostMs += durMs
        rotateLoss.add(durMs)
    }

    /**
     * 换段腿结算（泵线程）：把腿耗时折成丢帧账，越界即判明确失败 [RecordError.ROTATE_STALL]。
     * 记账只落 [pendingLegLostMs]（由下一轮循环顶部随段账落地，索引才对得上 partIndex）；
     * 停止路径的腿由 [settleRotateLegAtStop] 单独结算（那条路的泵线程还卡在腿里没出来）。
     *
     * @param judgeStall 本腿是否真的走到了轮转（false = 停止途中取消了轮转，腿里只有最后一段的
     *   封段：那份耗时该记进丢失账、但**不许**判失败——封段慢不等于录制受阻）
     */
    private fun settleRotateLeg(judgeStall: Boolean) {
        // 读 + 清一次做完：泵线程与控制线程的结算只有一方能拿到非零 start（见字段 KDoc）
        val start = rotateLegStartMs.getAndSet(0L)
        if (start == 0L) return
        val legMs = SystemClock.elapsedRealtime() - start
        val lost = rotateLostMs(legMs)
        pendingLegLostMs = lost
        // 帧上界：这条腿内 GL 回调到达数（相机可能在腿里也在出帧；DIRECT 路 GL 仅预览，
        // 口径仍是"这段时间相机交了多少帧"，不是"精确丢了多少"）
        val legFrames = FrameLedger.snapshot().glCallbacks - rotateLegFramesBase
        pendingLegLostFrames = legFrames.coerceAtLeast(0L)
        rotateLegFramesBase = 0L
        rotateLoss.add(lost)
        if (lost > 0L) {
            Log.e(
                TAG,
                "换段受阻：本腿 ${legMs}ms（良性开销 ${ROTATE_BENIGN_MS}ms），丢帧约 ${lost}ms；" +
                    "累计 ${rotateLoss.totalMs}ms/${rotateLoss.count} 次"
            )
        }
        if (!judgeStall) return
        if (rotateStallDecision(rotateStoreTimedOut, legMs)) {
            Log.e(TAG, "换段受阻超过上限 ${ROTATE_LEG_BUDGET_MS}ms（腿 ${legMs}ms），明确失败并保留已录分段")
            engineError = engineError ?: rotateStallError(rotateLoss.totalMs, rotateFailStage)
        }
    }

    /**
     * 停止路径的换段腿结算（控制线程；泵线程此刻仍卡在腿里）。判据收严（[rotateStopStallDecision]）：
     * 停止时最后一段封段本身就可能慢，误报"丢了 N 秒"比漏报更糟。只写此时已经安全的量：
     * 账本内部加锁、错误码 @Volatile——**不碰**泵线程单写者的段账表（那边可能同时在校验/写）。
     *
     * 【双触名单：joinLoop 超时那一刻，控制线程与泵线程会同时触碰的字段】
     * 前提：`stop()`/`release()` 里的 [joinLoop] 有界等待（[LOOP_JOIN_MS]）可能超时，而泵线程
     * 还卡在换段腿里（类注在 stop() 上写明这是真机形态：腿预算 [ROTATE_LEG_BUDGET_MS] 本就
     * 比 join 窗口长）。这些字段全是普通 `private var`，无 @Volatile、无锁（唯一例外是
     * @Volatile 的 stopping/abandonSegment/engineError 与内部加锁的 [rotateLoss]）：
     *
     * | 字段 | 泵线程在哪写 | 控制线程原本在哪写 | 后果 | 现在的处置 |
     * |---|---|---|---|---|
     * | `vCodec`/`aCodec`/`capCodec` | pumpLoop→[closeSegmentEngine]、openNextSegment 建/降级 | stop/release→closeSegmentEngine | 同一枚 MediaCodec 两线程 stop/release；更糟的是 native 释放后泵线程还在 `writeSampleData` ⇒ 崩溃 | **闸拦下**：超时只记账，泵线程自己收 |
     * | `muxer` | 同上 + openMuxer | 同上 | 同上（`field=gone` 那族快照就是它的指纹） | 同上 |
     * | `target`/`currentSink` | [sealCurrent]/[discardCurrent]/openNextSegment | stop 的 discardCurrent、closeTargetBounded | 同一枚 pending 被两路 seal/commit ⇒ 双提交或"提交又被丢弃" | 同上 |
     * | `parts`（CopyOnWriteArrayList，2026-10-16 起） | [sealCurrent]→add | [finish]/[release] 的 clear；[finishWithoutTeardown]→`toList()` **只读快照** | 交换容器前：并发 add 与 clear 撞车 ⇒ 结构性异常，或**丢一条已封段**（文件已 commit 但不在结果里）。交换后：add/clear/快照三者的内部同步由容器给出，结果如实带出"此刻已封段" | 超时路径**只许** `toList()` 快照（[finishWithoutTeardown] 走 [sealedPartsResult]），仍不许 add/clear；clear 只许在确认泵线程已停的收尾（[finish]/[release]/[prepare]）里做 |
     * | `feeder`/`capFeeder` | openNextSegment→stopFeeder/stopCapFeeder | stop/release→stopFeeder/stopCapFeeder | AudioRecord 两线程 stop（幂等，代价小） | 同上 |
     * | `profile` | openNextSegment 降级改写 | 只读 | 读半旧值 ⇒ 本段轨组合判错 | 只读，且降级写发生在腿内、超时闸不涉及写 |
     * | `segSampleCounts`/`segLostMs`/`segVideoWritten` | pumpLoop 每轮段闭落账 | finish/release 复位 | 索引与 partIndex 错位 | 同上（finish 不调）；**快照路径也不读它们**（普通容器、可能正在被写 ⇒ 宁缺勿假） |
     *
     * **为什么不用「加锁」**：锁只能让两边的**收尾**互斥，挡不住泵线程正在 runSegment 里往
     * muxer 写样本——控制线程照样能在它写到一半时把 muxer 释放掉。所以选的是"只记账不拆资源"。
     * 剩下的窗口（控制线程读 `clock.elapsedMs()`/`muxFailDetail` 造失败快照）只是只读，
     * 最坏是读到略旧的值，不产生写写冲突。
     */
    private fun settleRotateLegAtStop() {
        // 与 [settleRotateLeg] 互斥：腿起点由双方 `getAndSet(0L)` **消费**，先到者独得，后到者必得 0 早退。
        // 下面这一笔 `rotateLoss.add(lost)` **不许删**（用户可见失败结果里的丢失量靠它），
        // 也不许再加第二笔——双加已被上面的原子读清结构性地排除。
        val start = rotateLegStartMs.getAndSet(0L)
        if (start == 0L) return
        val legMs = SystemClock.elapsedRealtime() - start
        val lost = rotateLostMs(legMs)
        rotateLoss.add(lost)
        val stall = rotateStopStallDecision(rotateStoreTimedOut, legMs)
        Log.e(
            TAG,
            "停止时换段腿仍未返回（已 ${legMs}ms，丢帧约 ${lost}ms）：" +
                if (stall) "按换段受阻明确失败，已录分段照常保留" else "只记账（未达兜底判据，不误报失败）"
        )
        if (stall) engineError = engineError ?: rotateStallError(rotateLoss.totalMs, rotateFailStage)
    }

    // ---- 有界入库调用（2026-10-08 真机缺陷修复）：会走 MediaProvider/FUSE 的同步调用一律经这里 ----
    // 为什么必须挪出泵线程：泵线程是唯一的产出线程，这一腿里任何一步无界等待都等于"整机静默丢内容"
    // （真机 30~45s 空洞、无错误码、计时冻结）。上限见 ROTATE_LEG_BUDGET_MS / STORE_CALL_BUDGET_MS。

    /** 关当前段的输出句柄（有界）：晚到的 close 幂等，超时只记账 */
    private fun closeTargetBounded() {
        val t = target
        target = null
        closeBounded(t)
    }

    /** 关一枚输出目标（有界）：失败路径也不许在泵线程上无界等；收摊类调用保底 [CLEANUP_MIN_BUDGET_MS] */
    private fun closeBounded(t: OutputTarget?) {
        if (t == null) return
        val r = storeIo.run(cleanupBudgetMs(rotateBudgetLeftMs())) { t.close() }
        if (r is Bounded.TimedOut) noteStoreTimeout("target.close")
        // 库内成功原路径全静默（只有超时分支有日志）→ 媒体库入库成功这件事在日志里完全无痕；补一条 i 级留痕。
        // 每段一次（closeBounded 经 closeTargetBounded 调用），不刷屏
        else Log.i(TAG, "output target closed，有界内完成 part=$partIndex")
    }

    /** 建一枚新 pending（有界）：超时/失败回 null；晚到的那枚经 [pendingHandoff] 就地回收 */
    private fun createPendingBounded(partIndex: Int): Uri? {
        pendingHandoff.reset()
        val r = storeIo.run(rotateBudgetLeftMs()) {
            pendingHandoff.publish(store.createPending(partIndex))
        }
        return when (r) {
            is Bounded.Done -> if (r.value) {
                pendingHandoff.claim()
            } else {
                // insert 当场返回空（真机那条"第 1~2 次换段就死"的死法）：也是 INSERT 环节
                noteRotateStage(RotateStage.INSERT)
                null
            }
            Bounded.TimedOut -> {
                noteStoreTimeout("createPending")
                noteRotateStage(RotateStage.INSERT)
                discardBounded(pendingHandoff.abandon())
                null
            }
        }
    }

    /** 回收一枚 pending（有界、幂等）：泵线程不许在回收上再等一次无界调用 */
    private fun discardBounded(uri: Uri?) {
        if (uri == null) return
        val r = storeIo.run(STORE_CALL_BUDGET_MS) { store.discard(OutputSink.Pending(uri)) }
        if (r is Bounded.TimedOut) Log.e(TAG, "pending 回收超时（记录只能留给系统兜底）：$uri")
    }

    /** 打开输出目标（有界）：超时回 null；晚到的那枚自己关掉（fd 不许悬到进程结束） */
    private fun openTargetBounded(sink: OutputSink): OutputTarget? {
        targetHandoff.reset()
        val r = storeIo.run(rotateBudgetLeftMs()) { targetHandoff.publish(OutputTarget.open(ctx, sink)) }
        return when (r) {
            is Bounded.Done -> if (r.value) targetHandoff.claim() else null
            Bounded.TimedOut -> {
                noteStoreTimeout("OutputTarget.open")
                noteRotateStage(RotateStage.OPEN)
                // 交接闸里已落地的那枚由我们自己关（晚到的由 discard 回调关）
                targetHandoff.abandon()?.let { closeBounded(it) }
                null
            }
        }
    }

    /** 封一段并入库（有界）：超时回 null（晚到的 commit 仍会把文件落地，内容不丢） */
    private fun sealBounded(sink: OutputSink, durationMs: Long, keep: Boolean): VideoSegment? {
        val r = storeIo.run(rotateBudgetLeftMs()) {
            store.seal(sink, partIndex, partStartMs, durationMs, keep)
        }
        return when (r) {
            is Bounded.Done -> r.value
            Bounded.TimedOut -> {
                noteStoreTimeout("store.seal")
                noteRotateStage(RotateStage.SEAL)
                null
            }
        }
    }

    /**
     * 分段：换 pending uri、重建 codec+muxer（输入面与采集线程沿用），并在本线程内重启。
     *
     * **有界重试**（2026-10-08 真机缺陷）：这台 ROM 在上一段 33MB pending 文件的异步收尾期间，
     * 建新 pending / 开新输出的入库调用会久等甚至直接失败，一次失败就返回 false = 整场录制当场
     * 结束（真机现场正是"第 1~2 次换段后整轮无段"）。重试仍在换段腿预算内（[rotateRetryDecision]），
     * 不许把有界做成无界；失败的 pending 每轮就地回收，不留幽灵。
     */
    private fun nextSegment(): Boolean {
        var attempt = 0
        while (true) {
            attempt++
            val uri = createPendingBounded(partIndex + 1)
            if (uri != null) {
                val sink = OutputSink.Pending(uri)
                if (openNextSegment(sink)) return true
                // openNextSegment 的失败出口可能在 vCodec/aCodec/capCodec/muxer 已经写进字段之后
                // （start() 抛异常、openMuxer 失败）。这里必须先拆掉本轮已建的 native 资源，再重试：
                // 下一轮 openNextSegment 会直接覆盖字段，旧实例从此没有引用，stop/release 永远不会发生。
                // 手机并发 codec 实例数有限，泄漏会累积到之后建不出编码器（本类换段注释的同一风险）。
                releaseFailedNextSegmentResources()
                discardBounded(uri) // 没写进去的 pending 立刻回收，避免幽灵条目
                closeTargetBounded()
                // 行已回收：当前段句柄一并清空（否则后续收尾会去封一条已被删掉的记录）
                currentSink = null
            }
            if (!rotateRetryDecision(attempt, rotateBudgetLeftMs())) {
                rotateFailStage = if (rotateFailStage.isEmpty()) RotateStage.ROTATE else rotateFailStage
                Log.e(TAG, "换段失败（第 $attempt 次尝试后放弃，剩预算 ${rotateBudgetLeftMs()}ms）")
                return false
            }
            Log.i(TAG, "换段受阻：第 $attempt 次尝试失败，退让 ${ROTATE_RETRY_BACKOFF_MS}ms 后重试")
            SystemClock.sleep(ROTATE_RETRY_BACKOFF_MS)
        }
    }

    private fun openNextSegment(sink: OutputSink.Pending): Boolean {
        val p = profile ?: return false
        // 腿预算已被前面的入库调用吃光就不再往下走：后面全是编码器/muxer 的 native 重建（无上限可给），
        // 预算既尽就该交回 pumpLoop 按 ROTATE_STALL 明确失败，而不是带着没预算的腿继续赌
        if (rotateLegStartMs.get() != 0L && rotateBudgetLeftMs() <= 0L) {
            noteStoreTimeout("rotate budget spent")
            return false
        }
        val t = openTargetBounded(sink) ?: run {
            noteRotateStage(RotateStage.OPEN)
            return false
        }
        // GPU 路：per-codec 输入面随编码器实例换代——新面建好后**同步**让 GL 重挂（[Recorder]
        // 的 onInputSurfaceRecreated 契约，等挂好才 start），旧面在 GL 解绑后再释放；
        // 不重挂的话新段帧全落旧面（旧 codec 已停），GL 侧还持有已 release 的 native 面。
        // DIRECT 路（Camera2 当 producer）：persistent surface 跨段复用，行为不变。
        val v: MediaCodec?
        if (p.useGpu) {
            v = createVideoEncoder(resolveVideoMime(p), p, null)
            if (v == null) {
                // 编码器重建失败：target 字段此刻还是上一段的 null（sealCurrent 已清），
                // 调用方 nextSegment 的 target?.close() 关不到这枚 t，必须就地关
                noteRotateStage(RotateStage.ENCODE)
                closeBounded(t)
                return false
            }
            val newSurf = try {
                v.createInputSurface()
            } catch (e: Exception) {
                Log.e(TAG, "segment createInputSurface failed: ${e.javaClass.simpleName} ${e.message}")
                releaseQuietly(v)
                closeBounded(t)
                return false
            }
            val old = inputSurface
            inputSurface = newSurf
            // 回调跑在泵线程 try/catch 之外（pumpLoop 只兜 runSegment）：接线方抛异常会烧掉
            // 这枚 t 的 fd + 留幽灵 pending。公开契约不许假设实现不抛——就地折断换段
            //（返回 false 走 nextSegment 的 discard 收口），engineError 让 pumpLoop 退出
            // 而不是带着没挂好的面续录
            val rebindOk = runCatching { onInputSurfaceRecreated?.invoke(newSurf) }
                .onFailure { Log.e(TAG, "input surface rebind threw: ${it.message}") }
                .isSuccess
            if (!rebindOk) {
                engineError = engineError ?: "${RecordError.ENGINE_ERROR}:REBIND"
                // 【同族漏修补齐】这枚 v 已 configure、也建过面，但 `vCodec = v` 排在下面（本分支先 return），
                // closeSegmentEngine() 够不着它 ⇒ 必须就地放掉。漏放的代价不是内存：手机并发 codec
                // 实例数有限，泄漏到 GC finalizer 才回收，之后的录制可能直接建不出编码器。
                // （面 newSurf 不在此列：`inputSurface = newSurf` 已把它挂上字段，release() 收得住。）
                releaseQuietly(v)
                closeBounded(t)
                return false
            }
            runCatching { old?.release() }
                .onFailure { Log.w(TAG, "old input surface release failed: ${it.message}") }
        } else {
            val inSurf = inputSurface
            if (inSurf == null) {
                noteRotateStage(RotateStage.ENCODE)
                closeBounded(t)
                return false
            }
            v = createVideoEncoder(resolveVideoMime(p), p, inSurf)
            if (v == null) {
                noteRotateStage(RotateStage.ENCODE)
                closeBounded(t)
                return false
            }
        }
        vCodec = v
        // 重建闸按 profile 改写判定（P0-b/P3：feeder 首启失败/捕获失效都已改写 profile，
        // 这里不再空试复活编码器）。环境、内录两块**独立**：环境路降级不该连坐健康
        // 的内录路（反之亦然），成片按各自在场的轨组合出
        if (aacMinBuf > 0 && p.audioEnabled) {
            val a = createAudioEncoder(p, aacMinBuf)
            aCodec = a
            if (a == null) {
                // 环境编码器重建失败：同步停采集，避免 PCM 队列空转。**profile 一并改写**：
                // 保留 audioEnabled 的话，下段编码器一旦重建成功就出现 `a != null` 而 feeder == null
                // 的幽灵通道——envCh 永不喂样本、永不 dropped ⇒ muxer 启动不变式恒卡死 ⇒ 后续段
                // 视频样本全被丢弃（黑洞），直到用户停止按 NO_VIDEO_TRACK 判废整段。
                // 【取舍已定】瞬时性编码器创建失败（实例池被占等）从此结束本会话环境音，换取
                // 不再黑洞；与捕获路 ENCODER_REBUILD_FAILED 支同款（profile 改写 + 上报），
                // 成片是"前段有环境音、后段纯视频"的合法 MP4
                stopFeeder()
                profile = p.copy(audioEnabled = false)
                reportAmbientLost("换段环境编码器重建失败")
                Log.e(TAG, "env encoder rebuild failed, drop ambient track for rest of series")
            }
        }
        if (aacMinBuf > 0 && p.captureAudio) {
            // 内录编码器重建（captureAudio 恒走本引擎，profile 已带标志）。重建失败=内录轨从
            // 本段起降级退出（环境+视频继续录），profile 一并改写——后续段不再空试，与 prepare 的
            // 降级语义一致；成片仍是合法 MP4（前段双轨、后段单环境轨）
            val c2 = createAudioEncoder(p, aacMinBuf)
            capCodec = c2
            if (c2 == null) {
                stopCapFeeder()
                profile = p.copy(captureAudio = false)
                // 重建失败可见化：本段起内录轨退出，环境+视频继续录
                val ctl = PlaybackCaptureController.get(ctx)
                ctl.reportTrackLost(CaptureTrackLostReason.ENCODER_REBUILD_FAILED, null)
                Log.i(TAG, "capture encoder rebuild failed, drop capture track for rest of series")
            }
        }
        if (!openMuxer(t, p.orientationHint)) {
            noteRotateStage(RotateStage.MUX)
            closeBounded(t)
            return false
        }
        target = t
        currentSink = sink
        feeder?.drain() // 丢弃上一段积压，避免旧 PCM 落进新文件造成 PTS 倒退
        capFeeder?.drain() // 双轨同口径：内录轨积压一并丢弃
        return try {
            v.start()
            aCodec?.start()
            capCodec?.start()
            segBaseUs = System.nanoTime() / 1000L
            resetMuxPtsBooks()
            partIndex++
            partStartMs = clock.elapsedMs()
            clock.startSegment()
            Log.i(TAG, "codec segment $partIndex started")
            true
        } catch (e: Exception) {
            Log.e(TAG, "segment restart failed: ${e.message}")
            noteRotateStage(RotateStage.START)
            false
        }
    }

    /**
     * 换段失败出口的 native 资源收尾：只释放字段里**本轮新建**的 codec/muxer，不改 engineError
     * （失败阶段已经由 [noteRotateStage] 记录，这里再置错误码会把"可重试的换段受阻"误升级成
     * 场级失败）。与 [closeSegmentEngine] 的差别是：不碰 target/currentSink/feeder/输入面，
     * 那些由 [nextSegment] 的既有路径处理；也不读 muxer.stop() 的异常来判坏容器——本轮 muxer
     * 可能根本没 start 过，这里只回收。
     *
     * 幂等：字段先置空再释放，重复调用不会二次 stop/release。
     */
    private fun releaseFailedNextSegmentResources() {
        val v = vCodec
        vCodec = null
        releaseStartedCodecQuietly(v)
        val a = aCodec
        aCodec = null
        releaseStartedCodecQuietly(a)
        val c = capCodec
        capCodec = null
        releaseStartedCodecQuietly(c)
        val m = muxer
        muxer = null
        if (m != null) {
            try {
                m.stop()
            } catch (e: RuntimeException) {
                // 本轮 muxer 没 start 过是常态（openNextSegment 里 openMuxer 只 configure）；
                // 这里只是回收，不把 stop 异常当容器失败信号
                Log.i(TAG, "换段失败回收 muxer：stop ignored (${e.javaClass.simpleName})")
            }
            releaseQuietly(m)
        }
    }

    /** 只回收本轮已建的 codec：stop 失败也继续 release（release 才是 native 句柄的最终归还） */
    private fun releaseStartedCodecQuietly(c: MediaCodec?) {
        if (c == null) return
        try {
            c.stop()
        } catch (e: RuntimeException) {
            Log.i(TAG, "换段失败回收 codec：stop ignored (${e.javaClass.simpleName})")
        }
        releaseQuietly(c)
    }

    /**
     * 单段循环：喂 PCM（环境/内录双通道）→ 抽轨写 muxer → 处理停止/分段。
     * Muxer 启动条件严格守住「视频就绪 且 环境轨就绪 且（未启用捕获 或 捕获轨就绪）」。
     * @return false = 本段没等到视频轨，或中途出错
     */
    private fun runSegment(): Boolean {
        val v = vCodec ?: return false
        val a = aCodec
        val c = capCodec
        val ef = feeder
        val cf = capFeeder
        val mx = muxer ?: return false
        // 段起始清自检三格：换段后新编码器/muxer 从零起算，上一段的真值不能冒充本段健康
        healthVideoTrackAdded = false
        healthMuxStarted = false
        healthFirstVideoSample = false
        // 段起始一并复位弃段意图：一次判废只作用于被弃的那一段，不许拖累之后每一段
        abandonSegment = false
        val bi = MediaCodec.BufferInfo()
        // 逐通道状态各一套（批 3：heldPkt/lastPts/EOS 标志环境、内录分开）。状态生命周期本就
        // 只在单段内（pumpLoop 每段重建编码器并清 held 包），段内局部比实例字段更不容易串轨
        val envCh = if (a != null) AudioChannel() else null
        val capCh = if (c != null) AudioChannel() else null
        // 蓝牙预校准只作用于内录轨：环境轨是时间轴的参照，恒不平移。换段重建 AudioChannel 时
        // 重新继承会话级 shift（prepare 已复位；本会话后续段沿用同一测量值）
        capCh?.ptsShiftUs = preCalShiftUs
        // P1：捕获源失效的一次性贯穿收尾标志（teardown 幂等，恒只做一次）
        var capTornDown = false
        // 环境采集源不活的一次性贯穿收尾标志（S1-M3-05 收口，与 capTornDown 同款幂等）：标志后
        // 照常消费残留包/哨兵，profile 改写使后续段不再重建环境编码器，本段内重复进入只做消费
        var envTornDown = false
        // 内录样本等待窗起点（0 = 未起窗；[capSampleWaitTick] 单写者推进）
        var capWaitStartMs = 0L
        // 录制中静音巡检的节拍起点（0 = 本段未巡过；elapsedRealtime 域，单写者推进）
        var lastSilenceProbeMs = 0L
        var videoTrack = -1
        var muxStarted = false
        var videoDone = false
        var videoEosQueued = false
        // 段起始归零本段写入字节账（复用实例字段，供 health() 读产出活性；见 segWrittenBytes），
        // 逐段容器收尾信号同处复位：muxFinalized/segHadVideoSample 只描述**本段**（见各自 KDoc）
        segWrittenBytes = 0L
        muxFinalized = false
        segHadVideoSample = false
        var deadline = 0L
        // 抽干窗口的进度感知状态（[drainWaitTick] 单写者推进）：窗起点 / 顺延次数（只进一条汇总
        // 日志，不刷屏）/ 本拍推进观测（循环末尾读走即清、三路 dequeue 见真东西即置位）/ 本拍超时判定
        var drainOpenedAtMs = 0L
        var drainExtensions = 0
        var drainProgress = false
        var drainTimedOut = false
        // 空闲退避状态（见 [DEQUEUE_BACKOFF_TIMEOUT_US]）：连续无产出轮数与当前轮的超时档
        var dequeueIdleStreak = 0
        var dequeueUs = DEQUEUE_TIMEOUT_US
        // 各轨最后样本的媒体时长（已按段基准归一），用来在一行日志里看出轨间与墙钟的漂移
        var vPtsLastUs = 0L
        var aPtsLastUs = 0L
        var cPtsLastUs = 0L
        val segStartNs = System.nanoTime()
        // 帧账基线：本段只结算"从 runSegment 起走到段尾"的增量（跨组件计数器是单调的，
        // 段首段尾各取一次快照；快照非事务，只作观测）
        val frameBase = FrameLedger.snapshot()
        // 起录缓冲（第 2 笔）：muxer 启动前编码器吐出的视频样本先复制进来，容器可写后补写。
        // 旧实现此处直接 release 丢样本（见 PreMuxVideoBuffer 类注）
        preMuxBuffer = PreMuxVideoBuffer(PRE_MUX_BUFFER_MAX_BYTES)
        preMuxBuffered = 0L
        preMuxDropped = 0L

        while (true) {
            // 本拍三通道是否都空（空闲退避的推进输入，见循环底部；见 [DEQUEUE_BACKOFF_TIMEOUT_US]）
            var anyOutThisTick = false
            // 本段请求重建（新段首笔就写不进 muxer，见 writeSample）：立刻收口，**不置错误码**，
            // 交 pumpLoop 按"重开一段"处理（engineError 一置，录制中途看门狗就会抢先把整场停掉）
            if (segmentRestartRequested) {
                Log.e(TAG, "本段按重建收口（muxer 首笔写不进），part=$partIndex")
                return false
            }
            // 起录补写（第 2 笔）：muxer 启动后先把"启动前已编码"的旧样本按**到达顺序**补写，
            // 再处理新样本。补写失败由 writeSample 走既有重建/失败通路；缓冲为空时只做一次空读，不分配
            if (muxStarted && preMuxBuffer?.size != 0) {
                val lastFlushedPts = flushPreMuxSamples(mx, videoTrack)
                if (lastFlushedPts > 0L) vPtsLastUs = lastFlushedPts
            }
            // 【暂存首样本补写必须排在"新样本直写"之前】换段后头几拍里，先到的音频输出缓冲
            // 会因"轨未 add / muxer 未 start"被暂存（heldOutIdx），等门开了再写回。原实现把补写
            // 放在音频 dequeue **之后**：若 muxer 恰在内录轨那道门才启动（`startMuxerIfReady`
            // 要求内录轨也就绪），下一拍就会先直写一枚**较新**的样本、再把**较早**的暂存样本补写
            // 上去 ⇒ 同一轨 pts 倒退 ⇒ MPEG4Writer 抛 IllegalStateException ⇒ **整场录制当场结束**
            // （2026-10-08 真机现场 `trk=A;started=1;field=same;st=0` 那族）。放到循环开头即结构性消除。
            // writeSample 里另有逐轨单调兜底（[muxLastPtsUs]），两层都在：一层保序、一层保不死。
            if (a != null && envCh != null && envCh.heldOutIdx >= 0 && envCh.track >= 0 && muxStarted) {
                bi.set(0, envCh.heldSize, envCh.heldPts, envCh.heldFlags)
                segWrittenBytes += writeSample(mx, TRACK_ENV, envCh.track, a.getOutputBuffer(envCh.heldOutIdx), bi)
                a.releaseOutputBuffer(envCh.heldOutIdx, false)
                envCh.heldOutIdx = -1
            }
            if (c != null && capCh != null && capCh.heldOutIdx >= 0 && capCh.track >= 0 && muxStarted) {
                bi.set(0, capCh.heldSize, capCh.heldPts, capCh.heldFlags)
                segWrittenBytes += writeSample(mx, TRACK_CAP, capCh.track, c.getOutputBuffer(capCh.heldOutIdx), bi)
                c.releaseOutputBuffer(capCh.heldOutIdx, false)
                capCh.heldOutIdx = -1
            }
            // 喂 PCM：环境/内录各自独立通道（feedAudioInput 单份共用，EOS 判定逐字沿用）
            // 环境源活性每拍问桥（[ambientSourceLive]）：在场（含 ef==null 的幽灵通道形态）且
            // 采集线程未退出才可继续喂码。判不活 ⇒ 走下面的同构收口；判据不依赖队列里还有没有
            // 哨兵——段间死亡（上一段已消费哨兵 / openNextSegment 的 drain() 清掉未消费哨兵）
            // 由它在**新段第一拍**发现，喂完残留走兜底 EOS，通道按 dropped 放行 muxer。
            val envLive = ambientSourceLive(ef != null, ef?.alive() == true)
            if (a != null && envCh != null && !envCh.done) {
                if (envLive && ef != null) {
                    // 活：原样喂。eosQueued 后不再喂（停止路 EOS 已入队，残留留段界丢弃，口径不变）
                    if (!envCh.eosQueued) feedAudioInput(a, ef, envCh)
                } else {
                    // 环境采集源不活（ef==null 的幽灵通道 / 采集线程死亡）。【为什么必须收口而不是
                    // 只跳过】死 feeder 的队列永远为空 ⇒ envCh 永不 hasSample、永不 dropped ⇒
                    // startMuxerIfReady 的环境轨门恒假 ⇒ muxer 永不启动 ⇒ 视频样本全被 release
                    // 丢弃（到用户停止才按 NO_VIDEO_TRACK 判废删整段）。收口与内录失效支同构：
                    // 一次性（上报 + 拆 feeder + profile 改写——不改写的话下段编码器一旦重建成功，
                    // 幽灵通道原地复现）；随后照常喂残留包与哨兵，通道按 EOS 分支的 dropped 收尾
                    if (!envTornDown) {
                        envTornDown = true
                        reportAmbientLost("环境采集源不活 part=$partIndex")
                        stopFeeder()
                        profile = profile?.copy(audioEnabled = false)
                        Log.i(TAG, "env source inactive, tear down ambient track part=$partIndex")
                    }
                    // 残留包与哨兵照常消费（ef==null 的幽灵形态跳过喂码）；不消费的话编码器
                    // 拿不到 EOS，抽干窗口要白等一个预算
                    if (ef != null && !envCh.eosQueued) feedAudioInput(a, ef, envCh)
                    if (!envCh.eosQueued && (ef == null || ef.pending() == 0) && envCh.heldPkt == null) {
                        // 队列已排空仍未收尾：哨兵没进队（死亡与 drain() 竞态 / offer 超时）→ 兜底直发
                        queueAudioEos(a, envCh)
                    }
                }
            }
            if (c != null && cf != null && capCh != null && !capCh.done) {
                if (cf.captureActive()) {
                    feedAudioInput(c, cf, capCh)
                } else {
                    // 录制中捕获源失效（投影撤销/会话终止/重授权）。
                    // 贯穿收尾与 eosQueued 解耦（修复轮2 P1）：泵线程健康失败退出**必然**投
                    // EOS 哨兵（FIFO 排在残留真实包后），主循环消费到哨兵即置位 eosQueued——
                    // 收尾若锁在「!eosQueued && 队列空」门内，唯一触发窗口只剩几十毫秒竞态，
                    // 之后永久跳过（僵尸 feeder 残留 + profile 残 true → 轮转空试复活编码器）。
                    // captureActive()==false 本身就是充分条件：一次性幂等 teardown；
                    // 队列里的残留包与哨兵照常由 feedAudioInput 消费收尾（成片=有样本的
                    // 短轨；无样本走 dropped，见内录输出支路），环境+视频不受影响继续录。
                    if (!capTornDown) {
                        capTornDown = true
                        // 失效可见化（此前只留日志）：read 错误/静默死亡导致的失效用户毫无感知；
                        // 投影撤销场景 UI 层会按 captureState 过滤（Revoked 已有独立提示）
                        val ctl = PlaybackCaptureController.get(ctx)
                        ctl.reportTrackLost(CaptureTrackLostReason.SOURCE_INACTIVE, null)
                        stopCapFeeder()
                        profile = profile?.copy(captureAudio = false)
                        Log.i(TAG, "capture source inactive, tear down capture track part=$partIndex")
                    }
                    feedAudioInput(c, cf, capCh)
                    if (!capCh.eosQueued && cf.pending() == 0 && capCh.heldPkt == null) {
                        // 队列已排空仍未收尾：哨兵没进队（offer 超时等罕见竞态）→ 兜底直发
                        queueAudioEos(c, capCh)
                    }
                }
            }
            if (!videoDone) {
                val idx = v.dequeueOutputBuffer(bi, dequeueUs)
                // 抽干推进观测（见 [drainWaitTick]）：吐了任何东西（数据/格式/EOS）即算这一拍有推进。
                // 两行分开写：DrainWaitGuardTest 按文本钉住第一行的推进观测形态；第二行是 P3 空闲退避的输入
                if (idx >= 0 || idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) drainProgress = true
                if (idx >= 0 || idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) anyOutThisTick = true
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrack = mx.addTrack(v.getOutputFormat())
                        healthVideoTrackAdded = true // 自检第一格：编码器产出格式、muxer 建轨成功
                        if (!muxStarted) muxStarted = startMuxerIfReady(mx, videoTrack, envCh, capCh)
                        Log.i(TAG, "video track=$videoTrack fmt=${v.getOutputFormat()}")
                    }
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    idx == INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    idx >= 0 -> {
                        val eos = bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (!eos && bi.size > 0) {
                            if (muxStarted) {
                                // 本拍容器刚启动（同拍更早的 startMuxerIfReady）：旧样本必须先补写，
                                // 再写当前这枚较新的样本（同一轨 PTS 保序）
                                if (preMuxBuffer?.size != 0) {
                                    // 返回值不在这里回填 vPtsLastUs：本拍随后会写当前样本并覆盖它
                                    flushPreMuxSamples(mx, videoTrack)
                                }
                                // 位次账只认视频帧：写成功才计数（writeSample 失败返回 0），音频轨不入账
                                val wrote = writeSample(mx, TRACK_VIDEO, videoTrack, v.getOutputBuffer(idx), bi)
                                segWrittenBytes += wrote
                                if (wrote > 0) segVideoWritten++
                                // 自检第三格：首个视频样本真写进 muxer（writeSample 失败返回 0，不计）
                                if (wrote > 0) healthFirstVideoSample = true
                                // 容器收尾判据的另一半（见 [segmentKeepDecision] 的 containerFinalized）：
                                // 与 muxFinalized 合成"容器已确认收尾"，只能由写成功置位
                                if (wrote > 0) segHadVideoSample = true
                                vPtsLastUs = bi.presentationTimeUs
                            } else {
                                // muxer 尚未启动：把已编码样本复制进有界缓冲，容器可写后按到达顺序补写。
                                // 复制是必要的——MediaCodec 的 output buffer release 后内容失效。
                                // 先 canAccept 再分配：注定拒收的超大样本不许做一次瞬时大分配。
                                val src = v.getOutputBuffer(idx)
                                if (src == null || preMuxBuffer?.canAccept(bi.size) != true) {
                                    preMuxDropped++
                                } else {
                                    val copy = ByteArray(bi.size)
                                    src.position(bi.offset)
                                    src.get(copy, 0, bi.size)
                                    val evicted = preMuxBuffer?.offer(
                                        PreMuxVideoBuffer.Sample(bi.presentationTimeUs, bi.flags, copy)
                                    ) ?: 1L
                                    preMuxDropped += evicted
                                }
                            }
                        }
                        v.releaseOutputBuffer(idx, false)
                        if (eos) videoDone = true
                    }
                }
            }
            if (a != null && envCh != null && !envCh.done) {
                val idx = a.dequeueOutputBuffer(bi, dequeueUs)
                if (idx >= 0 || idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) drainProgress = true // 同视频路
                if (idx >= 0 || idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) anyOutThisTick = true // 同视频路（P3 空闲退避输入）
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> envCh.fmtReady = true
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    idx == INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    idx >= 0 -> {
                        val eos = bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        when {
                            eos -> {
                                a.releaseOutputBuffer(idx, false)
                                envCh.done = true
                                // P3：与内录通道对称的零样本防线——收尾仍无真实样本（feeder
                                // 产出首个 PCM 前死亡）→ 通道废弃，轨不进 muxer，启动闸按
                                // 「未启用音频」重评，段照常成片而非整段作废
                                if (!envCh.hasSample) {
                                    envCh.dropped = true
                                    if (!muxStarted) muxStarted = startMuxerIfReady(mx, videoTrack, envCh, capCh)
                                }
                            }
                            // 通道废弃后编码器迟到的真实样本照 release（既有行为）；这里只补记账：
                            // 「废弃那一刻编码器还吐了多少」原来完全无迹，成片丢了多少无从判断
                            envCh.dropped -> { envCh.lateDrops++; a.releaseOutputBuffer(idx, false) }
                            envCh.track >= 0 && muxStarted -> {
                                // 正常路：muxer 已接纳本轨，照写
                                if (bi.size > 0) {
                                    segWrittenBytes += writeSample(mx, TRACK_ENV, envCh.track, a.getOutputBuffer(idx), bi)
                                    aPtsLastUs = bi.presentationTimeUs
                                }
                                a.releaseOutputBuffer(idx, false)
                            }
                            envCh.heldOutIdx < 0 && bi.size > 0 -> {
                                // muxer 尚未接纳本轨：首个真实样本暂存不释放，门开后补写
                                //（与内录通道同构，「已产出样本 ⇒ 轨必有 ≥1 写入样本」）
                                envCh.heldOutIdx = idx
                                envCh.heldPts = bi.presentationTimeUs
                                envCh.heldSize = bi.size
                                envCh.heldFlags = bi.flags
                                envCh.hasSample = true
                            }
                            else -> a.releaseOutputBuffer(idx, false) // 暂存已占/空缓冲：启动前样本照既有口径丢弃
                        }
                    }
                }
            }
            if (c != null && capCh != null && !capCh.done) {
                val idx = c.dequeueOutputBuffer(bi, dequeueUs)
                if (idx >= 0 || idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) drainProgress = true // 同视频路
                if (idx >= 0 || idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) anyOutThisTick = true // 同视频路（P3 空闲退避输入）
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> capCh.fmtReady = true
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    idx == INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    idx >= 0 -> {
                        val eos = bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        when {
                            eos -> {
                                c.releaseOutputBuffer(idx, false)
                                capCh.done = true
                                // P0-a：收尾仍无任何真实样本 → 通道废弃，轨不进 muxer
                                //（零样本轨会让 muxer.stop 抛异常、缺 moov 的坏片被提交）。
                                // P1：废弃=未启用——可能正是内录轨挡着 muxer start，
                                // 就地重评启动闸（dropped 通道此后恒按未启用处理，无重入问题）
                                if (!capCh.hasSample) {
                                    // 防重上报：窗满放弃已置 dropped 并报过 NO_SAMPLES，EOS 收尾不再重复；
                                    // 失效收尾（capTornDown）已报 SOURCE_INACTIVE，废弃原因以更准的那条为准
                                    if (!capCh.dropped && !capTornDown) {
                                        val ctl = PlaybackCaptureController.get(ctx)
                                        ctl.reportTrackLost(CaptureTrackLostReason.NO_SAMPLES, null)
                                    }
                                    capCh.dropped = true
                                    if (!muxStarted) muxStarted = startMuxerIfReady(mx, videoTrack, envCh, capCh)
                                }
                            }
                            // 与环境通道同族：迟到样本照 release，只补记账（零样本废弃轨的丢弃量需要可查）
                            capCh.dropped -> { capCh.lateDrops++; c.releaseOutputBuffer(idx, false) }
                            capCh.track >= 0 && muxStarted -> {
                                // 正常路：muxer 已接纳本轨，照写
                                if (bi.size > 0) {
                                    segWrittenBytes += writeSample(mx, TRACK_CAP, capCh.track, c.getOutputBuffer(idx), bi)
                                    cPtsLastUs = bi.presentationTimeUs
                                }
                                c.releaseOutputBuffer(idx, false)
                            }
                            capCh.heldOutIdx < 0 && bi.size > 0 -> {
                                // muxer 尚未接纳本轨（未 addTrack 或未 start）：首个真实样本暂存
                                // 不释放，门开后补写——addTrack 前置判据 hasSample 由此而来，
                                // 且「已产出样本 ⇒ 轨必有 ≥1 写入样本」结构成立（P0-a）
                                capCh.heldOutIdx = idx
                                capCh.heldPts = bi.presentationTimeUs
                                capCh.heldSize = bi.size
                                capCh.heldFlags = bi.flags
                                capCh.hasSample = true
                            }
                            else -> c.releaseOutputBuffer(idx, false) // 暂存已占/空缓冲：启动前样本照既有口径丢弃
                        }
                    }
                }
            }
            // 环境轨 addTrack 门（P3 对称）：格式到 + 已见首个真实样本才准入；零样本（dropped）
            // 按「未启用音频」处理。**必须排在内录门之前**——轨序红线：环境轨先 addTrack
            //（轨序即 tkhd track_ID 顺序，批 4 播放器按 video 后第一/二条音轨识别环境/内录）
            if (a != null && envCh != null && !envCh.dropped && envCh.fmtReady && envCh.hasSample &&
                envCh.track < 0
            ) {
                envCh.track = mx.addTrack(a.getOutputFormat())
                if (!muxStarted) muxStarted = startMuxerIfReady(mx, videoTrack, envCh, capCh)
                Log.i(TAG, "env audio track=${envCh.track}")
            }
            // 暂存补写已上移到本拍开头（保序；见那里注释），此处不再补
            // 内录轨 addTrack 门（每轮幂等尝试）+ 零样本防线（P0-a）：必须晚于环境轨
            //（环境轨本就不存在=编码器缺席，或已废弃 dropped——两者都不再有「先于谁」可言，
            // 才允许内录轨自由 add；env 轨废弃仍挡门的话会复现 P1 同款 muxStarted 恒 false），
            // 且必须**已见首个真实样本**（hasSample）——从未产出样本的通道已标 dropped，
            // startMuxerIfReady 按「未启用捕获」处理，零样本轨从结构上不可能出现。fmtReady 只
            // 证明格式到了，hasSample 才证明有内容可写；FORMAT_CHANGED 事件错过不丢格式：
            // getOutputFormat() 随时可取当前格式，等门开再 add 即可
            if (c != null && capCh != null && !capCh.dropped && capCh.fmtReady && capCh.hasSample &&
                capCh.track < 0 && (envCh == null || envCh.dropped || envCh.track >= 0)
            ) {
                capCh.track = mx.addTrack(c.getOutputFormat())
                if (!muxStarted) muxStarted = startMuxerIfReady(mx, videoTrack, envCh, capCh)
                Log.i(TAG, "capture audio track=${capCh.track}")
            }
            // 暂存补写已上移到本拍开头（保序；见那里注释），此处不再补
            // 内录样本等待窗（E6 防整段作废）：muxer 启动被"启用但无样本"的内录通道卡住时，
            // 从其余轨就绪那拍起等 [CAP_SAMPLE_WAIT_MS]；窗满按废弃放行（dropped + 重评启动闸 +
            // NO_SAMPLES 可见化），环境+视频照常出片。部分机型 playback capture 无媒体在播时
            // 不回缓冲，无界等待会让 muxer 永不启动 → 停止时整段作废、用户连视频都拿不到。
            // 正常双轨机型有媒体播放时内录首样本远快于窗，此分支不触发（成功路径零改变）。
            // 窗条件含 !capTornDown：失效收尾的原因码以 SOURCE_INACTIVE 为准，不叠报 NO_SAMPLES。
            val capWaiting = !muxStarted && videoTrack >= 0 &&
                (envCh == null || envCh.dropped || envCh.track >= 0) &&
                capCh != null && !capCh.dropped && !capCh.hasSample && !capTornDown
            val (capWaitStart, capWaitExpired) = capSampleWaitTick(
                capWaitStartMs, SystemClock.elapsedRealtime(), capWaiting
            )
            capWaitStartMs = capWaitStart
            if (capWaitExpired && capCh != null) {
                capCh.dropped = true
                val ctl = PlaybackCaptureController.get(ctx)
                ctl.reportTrackLost(CaptureTrackLostReason.NO_SAMPLES, null)
                if (!muxStarted) muxStarted = startMuxerIfReady(mx, videoTrack, envCh, capCh)
                Log.i(TAG, "capture sample wait window expired, drop capture track part=$partIndex")
            }
            // 内录纯静音**低频巡检**（2026-10-16 灵敏化：录制中就要能提示，不让用户录完才发现）。
            // 与拍子同线程、约 [SILENCE_PROBE_INTERVAL_MS] 一拍；条件 = 内录轨在场（已见真实样本、
            // 未废弃、未走失效收尾）且本段已喂足 [CAPTURE_SILENCE_LIVE_MIN_MS] 且仍未见非零样本。
            // 命中走与收尾兜底同一出口（reportCaptureSilence：按场节流、提示位与 UI 通道全复用）。
            // 本拍只读账（fedBytes/sawNonZero）——不停录、不置错、不碰文件与账目路径。
            val probeNowMs = SystemClock.elapsedRealtime()
            if (probeNowMs - lastSilenceProbeMs >= SILENCE_PROBE_INTERVAL_MS) {
                lastSilenceProbeMs = probeNowMs
                if (cf != null && capCh != null && capCh.hasSample && !capCh.dropped && !capTornDown) {
                    val fedMs = fedMsOf(capCh.fedBytes, cf.sampleRate, cf.channels)
                    if (captureSilenceVerdict(capCh.hasSample, fedMs, cf.sawNonZero, CAPTURE_SILENCE_LIVE_MIN_MS)) {
                        PlaybackCaptureController.get(ctx).reportCaptureSilence()
                    }
                }
            }
            if (stopping || rotatePending) {
                if (deadline == 0L) {
                    if (!muxStarted) {
                        Log.e(TAG, "stop before muxer started, give up segment")
                        engineError = engineError ?: RecordError.NO_VIDEO_TRACK
                        break
                    }
                    if (!videoEosQueued) {
                        v.signalEndOfInputStream() // 4 字节哨兵只在音频通道，视频走 input surface 的 EOS
                        videoEosQueued = true
                    }
                }
                // 抽干窗口推进（纯函数三态桥，见 [drainWaitTick]）：起窗 / 推进顺延（至多到硬上限）/
                // 超时。起点由首拍记下——硬上限与汇总日志都以它为基准
                val tick = drainWaitTick(
                    drainOpenedAtMs, deadline, SystemClock.elapsedRealtime(), drainProgress
                )
                if (drainOpenedAtMs != 0L && tick.deadlineMs > deadline) drainExtensions++
                drainOpenedAtMs = tick.openedAtMs
                deadline = tick.deadlineMs
                drainTimedOut = tick.timedOut
                // P2：双通道 EOS 兜底**每轮重试**（置位=真正入队，那一拍 dequeueInputBuffer(0)
                // 返回 -1 就下一轮再来）——锁进 deadline==0L 单发门的话，满负荷拍点 EOS 丢失、
                // done 恒不置位 → 抽干超时误废整段（单段录制即整条丢失）。
                // 重试上界仍是 deadline（静默预算 DRAIN_LIMIT_MS 顺延后的当前值）
                if (a != null && envCh != null && !envCh.eosQueued) queueAudioEos(a, envCh)
                if (c != null && capCh != null && !capCh.eosQueued) queueAudioEos(c, capCh)
            }
            if (videoDone && (envCh?.done ?: true) && (capCh?.done ?: true)) {
                // 收尾前最后一笔补写：muxer 刚启动就赶上视频 EOS 时，上一拍顶部还没轮到补写
                if (muxStarted && preMuxBuffer?.size != 0) {
                    val lastFlushedPts = flushPreMuxSamples(mx, videoTrack)
                    if (lastFlushedPts > 0L) vPtsLastUs = lastFlushedPts
                }
                break
            }
            if (drainTimedOut) {
                Log.e(TAG, "drain timeout video=$videoDone env=${envCh?.done ?: "off"} cap=${capCh?.done ?: "off"}")
                engineError = engineError ?: "${RecordError.ENGINE_ERROR}:DRAIN"
                break
            }
            if (!rotatePending && segWrittenBytes >= (WotaTiers.MAX_FILE_BYTES * ROTATE_SAFETY).toLong()) {
                Log.i(TAG, "written=$segWrittenBytes reached MAX_FILE_BYTES limit, rotate")
                rotatePending = true
            }
            // 本拍推进观测读走即清（见 [drainWaitTick]）：抽干窗口的顺延只看"刚过去的这一拍有没有动"，
            // 清晚了会把窗口开启前的老活动误算成推进（清在循环末尾与清在下一拍开头等价）
            // 空闲退避推进（P3）：出数即回 2ms 档；连续全空到阈值才放宽到 8ms——任一通道有产出，
            // 下一拍立即恢复原节奏，活跃流路径逐字不变
            dequeueIdleStreak = if (anyOutThisTick) 0 else dequeueIdleStreak + 1
            dequeueUs = if (dequeueIdleStreak >= DEQUEUE_IDLE_STREAK_BACKOFF) DEQUEUE_BACKOFF_TIMEOUT_US else DEQUEUE_TIMEOUT_US
            drainProgress = false
        }
        if (videoTrack < 0) {
            Log.e(TAG, "no video track produced in segment $partIndex")
            engineError = engineError ?: RecordError.NO_VIDEO_TRACK
            return false
        }
        // 内录纯静音判读（段收尾兜底，2026-10-16 灵敏化后仍在）：录制中巡检因故漏检（没跑满
        // 巡检门槛就停止的短录音、或巡检窗内轨状态未就绪）时在这里补判一次；与录制中提示共用
        // 同一按场节流锁存——同场 60s 内不会因收尾再叠一条。门：内录轨真实在场（hasSample 且
        // 未废弃）且未走失效收尾——轨不在场时 NO_SAMPLES / SOURCE_INACTIVE 各有语义，静音判读
        // 不叠报。判据数据基础 = AudioFeeder.sawNonZero（整场锁存，段界不复位）+ 实喂字节换算时长
        var capSilenceInfo = "off"
        if (cf != null && capCh != null && capCh.hasSample && !capCh.dropped && !capTornDown) {
            val capFedMs = fedMsOf(capCh.fedBytes, cf.sampleRate, cf.channels)
            val capSilent = captureSilenceVerdict(capCh.hasSample, capFedMs, cf.sawNonZero)
            if (capSilent) PlaybackCaptureController.get(ctx).reportCaptureSilence()
            capSilenceInfo = "见过非零=${if (cf.sawNonZero) "是" else "否"} 有效=${capFedMs}ms 静音=${if (capSilent) "是" else "否"}"
        }
        // 抽干汇总（一条，不刷屏）：顺延次数与总等待是分辨"编码器慢"与"编码器死"的两枚读数
        //（超时路径也走到这里：上面 break 出循环后照常落这条账）
        if (drainOpenedAtMs > 0L) {
            Log.i(
                TAG,
                "抽干 part=$partIndex 共等 ${SystemClock.elapsedRealtime() - drainOpenedAtMs}ms" +
                    "（顺延 $drainExtensions 次，静默预算 ${DRAIN_LIMIT_MS}ms，硬上限 ${DRAIN_HARD_LIMIT_MS}ms）"
            )
        }
        // 三轨时长并排给出一行即可判定漂移：视频/环境/内录互为轨间偏移，与墙钟差即整体滑移
        // （本机无 >60fps 能力，这条路径的音画同步只能靠日志取证，见 docs/plan/10 §19 与任务 #33）
        Log.i(
            TAG,
            "segment $partIndex drained video=$videoDone env=${envCh?.done ?: "off"} cap=${capCh?.done ?: "off"} " +
                "环境丢弃=${envCh?.lateDrops ?: 0} 内录丢弃=${capCh?.lateDrops ?: 0} " +
                "喂码丢弃=${envCh?.feedDropped ?: 0}/${capCh?.feedDropped ?: 0} " +
                "截断=${envCh?.feedTruncated ?: 0}/${capCh?.feedTruncated ?: 0} " +
                // pts 单调兜底的两枚账（见 [muxPtsFixes]）：等值是设计内同格多帧，真倒退才是异常
                "pts修正=${muxPtsFixes}(等值${muxPtsEqualFixes}) " +
                "bytes=$segWrittenBytes 视频=${vPtsLastUs / 1000f}ms 环境=${aPtsLastUs / 1000f}ms 内录=${cPtsLastUs / 1000f}ms " +
                "墙钟=${(System.nanoTime() - segStartNs) / 1_000_000f}ms " +
                "内录静音[$capSilenceInfo]"
        )
        // 帧账独立一行（2026-10-17）：回答"相机给了多少 → GL 收/合了多少 → 提交编码器多少 →
        // 写进 muxer 多少 → 有意抽帧多少 → 起录/换段丢多少"。不并入上面两行（向后可读），
        // 同时进诊断导出的结构化段（Diag.frameAccount，独立于日志总开关，见该函数 KDoc）
        // 段尾兜底：抽干超时等 break 出口可能错过顶部补写；容器已启动就再补一次，未启动则
        // 把仍在缓冲里的样本计入丢失（它们已离开编码器，不能静默消失）
        if (muxStarted && preMuxBuffer?.size != 0) {
            // 上面那段日志已经读过 vPtsLastUs，这里补写只为把样本落进容器
            flushPreMuxSamples(mx, videoTrack)
        }
        val preMuxLeft = preMuxBuffer?.clear() ?: 0
        if (preMuxLeft > 0) {
            preMuxDropped += preMuxLeft
            Log.w(TAG, "起录缓冲段尾未补写：part=$partIndex 丢弃 $preMuxLeft 笔（muxStarted=$muxStarted）")
        }
        val frameEnd = FrameLedger.snapshot()
        val ledgerLine = frameLedgerLine(
            FrameLedgerReport(
                partIndex = partIndex,
                gpu = profile?.useGpu == true,
                durationMs = (System.nanoTime() - segStartNs) / 1_000_000L,
                cameraFrames = (frameEnd.cameraStarted - frameBase.cameraStarted).coerceAtLeast(0L),
                glCallbacks = (frameEnd.glCallbacks - frameBase.glCallbacks).coerceAtLeast(0L),
                glConsumed = (frameEnd.glConsumed - frameBase.glConsumed).coerceAtLeast(0L),
                encoderSwaps = (frameEnd.encoderSwapOk - frameBase.encoderSwapOk).coerceAtLeast(0L),
                swapFails = (frameEnd.encoderSwapFail - frameBase.encoderSwapFail).coerceAtLeast(0L),
                muxSamples = segVideoWritten.toLong(),
                preMuxBuffered = preMuxBuffered,
                preMuxDropped = preMuxDropped,
                arcDrops = (frameEnd.arcDropped - frameBase.arcDropped).coerceAtLeast(0L),
                rotateLostMs = currentSegLostMs,
                rotateFramesUpper = currentSegLostFrames
            )
        )
        Log.i(TAG, ledgerLine)
        Log.frameAccount(ledgerLine)
        return true
    }

    /**
     * 把起录缓冲里的样本按**到达顺序**补写进 muxer（第 2 笔）。返回最后一枚**写成功**样本的原始
     * PTS（与 `vPtsLastUs` 同域；0 = 没写成任何一枚）。
     *
     * **不排序**：编码器输出的是码流访问单元顺序，含 B 帧或 PTS 非单调时按 PTS 重排会变成解码
     * 顺序错乱（离线修复链 [ArcRepairRunner] 同样只靠单调兜底、不排序）。同 PTS/倒退的推进由
     * [writeSample] 的既有逐轨单调兜底处理（[monotonicPts]）。写失败/本段被要求重建时，
     * 未写出的样本计入 [preMuxDropped]——它们已经离开编码器，不补写就是永久丢失。
     */
    private fun flushPreMuxSamples(mx: MediaMuxer, track: Int): Long {
        val buf = preMuxBuffer ?: return 0L
        val pending = buf.drainArrivalOrder()
        if (pending.isEmpty()) return 0L
        val info = MediaCodec.BufferInfo()
        var lastPts = 0L
        var written = 0
        for (i in pending.indices) {
            if (segmentRestartRequested) {
                preMuxDropped += (pending.size - i).toLong()
                break
            }
            val sample = pending[i]
            info.set(0, sample.bytes.size, sample.ptsUs, sample.flags)
            val wrote = writeSample(mx, TRACK_VIDEO, track, ByteBuffer.wrap(sample.bytes), info)
            segWrittenBytes += wrote
            if (wrote > 0) {
                segVideoWritten++
                preMuxBuffered++
                healthFirstVideoSample = true
                segHadVideoSample = true
                lastPts = sample.ptsUs
                written++
            } else {
                preMuxDropped++
            }
        }
        if (written > 0) Log.d(TAG, "起录补写 part=$partIndex 写出=$written 丢弃=$preMuxDropped")
        return lastPts
    }

    /**
     * Muxer 启动不变式（批 3 双轨版）：视频就绪 且 环境轨就绪 且（未启用捕获 或 捕获轨就绪）。
     * 「轨就绪」= 编码器本就不存在（静音/无权限降级，通道为 null）或已 addTrack。
     * addTrack 顺序即 tkhd track_ID 顺序（环境先、内录后），本函数只做启动闸、不改轨序。
     */
    private fun startMuxerIfReady(mx: MediaMuxer, videoTrack: Int, envCh: AudioChannel?, capCh: AudioChannel?): Boolean {
        if (videoTrack < 0) return false
        // P3：未启用音频（envCh=null）或环境轨已废弃（dropped：从未产出真实样本）→ 无需等环境轨
        if (envCh != null && !envCh.dropped && envCh.track < 0) return false
        // P0-a：未启用捕获（capCh=null）或捕获轨已废弃 → 无需等内录轨
        if (capCh != null && !capCh.dropped && capCh.track < 0) return false
        return try {
            mx.start()
            healthMuxStarted = true // 自检第二格：muxer 真启动（此后样本落盘）
            Log.i(TAG, "muxer start video=$videoTrack env=${envCh?.track ?: "none"} cap=${capCh?.track ?: "none"}")
            true
        } catch (e: IllegalStateException) {
            Log.e(TAG, "muxer start failed: ${e.message}")
            false
        }
    }

    /** PTS 统一到本段基准（两轨同一时钟，分段后不出现倒退） */
    private fun writeSample(mx: MediaMuxer, kind: String, track: Int, buf: ByteBuffer?, info: MediaCodec.BufferInfo): Int {
        if (track < 0 || buf == null) return 0
        val shifted = info.presentationTimeUs - segBaseUs
        if (shifted < 0L) {
            // 基准取的是换段瞬间的 nanoTime，音频包可能在那之前就已入队：夹紧会把这几帧往前挪，
            // 量级必须能在日志里看到，否则分段处的音画偏移无从判断（本机无高帧率能力，改语义前先取证）
            Log.w(TAG, "段 $partIndex pts 早于基准 ${-shifted}us，已夹紧 track=$track")
            info.presentationTimeUs = 0L
        } else {
            info.presentationTimeUs = shifted
        }
        // 逐轨单调保证（判定走 [monotonicPts] 桥，见 [muxLastPtsUs] 注释）：等值/倒退就地推进到
        // last+1，绝不把倒退交给 MPEG4Writer（它会抛 IllegalStateException 让整场录制结束）。
        // 【两枚账】同格等值（候选 == 上一戳）是 GL 侧明写的设计内契约、不是异常，单独计数并降 d 级；
        // 真倒退保留 w 级并进失败快照。两者混记会让快照里 `ptsfix=` 这把尺子读不出真倒退。
        val ti = if (track in muxLastPtsUs.indices) track else -1
        if (ti >= 0) {
            val lastUs = muxLastPtsUs[ti]
            val fix = monotonicPts(lastUs, info.presentationTimeUs)
            if (fix.advanced) {
                if (info.presentationTimeUs == lastUs) {
                    // 同格等值：只计数 + d 级留痕，逐帧刷 w 是本条要治的洪泛
                    muxPtsEqualFixes++
                    Log.d(TAG, "轨 $track pts 与上一枚同格相等（$lastUs）⇒ 推进 1µs（同格设计内，不计异常）")
                } else {
                    muxPtsFixes++
                    if (fix.backUs > muxPtsMaxBackUs) muxPtsMaxBackUs = fix.backUs
                    Log.w(
                        TAG,
                        "轨 $track pts 倒退（cur=${info.presentationTimeUs} last=$lastUs）" +
                            "⇒ 推进到 ${fix.ptsUs}（倒退 ${fix.backUs}us）"
                    )
                }
            }
            info.presentationTimeUs = fix.ptsUs
            muxLastPtsUs[ti] = fix.ptsUs
        }
        return try {
            mx.writeSampleData(track, buf, info)
            info.size
        } catch (e: IllegalStateException) {
            // 现场快照（这台 ROM 无日志：这是唯一能分辨"写超停"与"写先于 start"的通道）
            val diag = muxFailDiagnostic(
                kind = kind, started = true, fieldSame = muxer === mx,
                partIndex = partIndex, track = track, size = info.size,
                ptsUs = info.presentationTimeUs, flags = info.flags, segBaseUs = segBaseUs,
                writtenBytes = segWrittenBytes, videoSamples = segVideoWritten,
                restarts = segmentRestarts, stopping = stopping, rotatePending = rotatePending
            ) + ";ptsfix=${muxPtsFixes};back=${muxPtsMaxBackUs}us"
            muxFailDetail = diag
            Log.e(TAG, "writeSampleData failed: ${e.message} [$diag]")
            if (muxRecoverDecision(freshSegmentMuxFailure(segVideoWritten, segWrittenBytes), segmentRestarts)) {
                // 本段一笔都没写成 ⇒ 重建本段（换一套 fd/编码器/muxer 再试），不判废整场。
                // **不置 engineError**：置了录制中途看门狗会抢先 stopInternal（r18 那条可见失败通道
                // 留给"重建也不成"的终局），丢失与现场快照照记
                segmentRestartRequested = true
                Log.e(TAG, "新段首笔样本就写不进 muxer，重建本段（第 ${segmentRestarts + 1} 次）")
            } else {
                engineError = engineError ?: muxFailError(diag)
            }
            0
        }
    }

    /**
     * 消费一条 PCM 通道（环境/内录共用**这一份**，EOS 判定逐字沿用）：取包 → 编码器输入；
     * EOS 哨兵以 pts<0 识别（``）。喂到哨兵即置位 [AudioChannel.eosQueued]——停止/捕获源
     * 失效路径的兜底 EOS（[queueAudioEos]）据此不再重复入队。
     */
    private fun feedAudioInput(codec: MediaCodec, src: AudioFeeder, ch: AudioChannel) {
        val pkt = ch.heldPkt ?: src.poll(0) ?: return
        val idx = codec.dequeueInputBuffer(0)
        if (idx < 0) {
            ch.heldPkt = pkt // 编码器没空位：本包留到下一轮，绝不丢帧
            return
        }
        ch.heldPkt = null
        // EOS 判定只看 pts<0（真实包 pts 恒为 nanoTime µs、恒正，哨兵恒 -1）。长度不能并列成
        // 第二判据：哨兵 data 恰 4 字节与「双声道 2 帧的合法短读」同形，size==4 支路（01028b9
        // 加 pts<0 时留在 OR 里的旧判定）会把真 PCM 误判成 EOS 提前掐断音轨
        if (pkt.ptsUs < 0) {
            queueEosOn(codec, idx, ch)
            return
        }
        // 这枚已从队列消费、heldPkt 也已清（丢了就真没了）→ 原来 `?: return` 静默吞掉，计数留痕
        val buf = codec.getInputBuffer(idx) ?: run { ch.feedDropped++; return }
        val len = minOf(pkt.data.size, buf.capacity())
        // 容量不足导致截断：原来只有 len 一个数字，看不出是不是被砍过
        if (len < pkt.data.size) ch.feedTruncated++
        buf.clear()
        buf.put(pkt.data, 0, len)
        // 内录轨的整体 PTS 平移（预校准；环境轨恒 0）：从喂入编码器这一层改时间轴，
        // 不改 PCM 内容。EOS 的末戳也用平移后的值（见 eosPts），避免收尾戳比样本还早。
        val ptsUs = pkt.ptsUs + ch.ptsShiftUs
        try {
            codec.queueInputBuffer(idx, 0, len, ptsUs, 0)
            ch.lastPtsUs = ptsUs
            ch.fedBytes += len // 有效样本字节账（静音判读的时长来源；入队失败不计）
        } catch (e: IllegalStateException) {
            Log.e(TAG, "audio queueInput failed: ${e.message}")
        }
    }

    /** 停止/轮转/捕获源失效三路的 EOS 兜底：入队成功才置位（P2）——idx<0 不置位，下一轮重试 */
    private fun queueAudioEos(codec: MediaCodec, ch: AudioChannel) {
        val idx = codec.dequeueInputBuffer(0)
        if (idx < 0) return
        queueEosOn(codec, idx, ch)
    }

    private fun queueEosOn(codec: MediaCodec, idx: Int, ch: AudioChannel) {
        try {
            codec.queueInputBuffer(idx, 0, 0, eosPts(ch), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            // 置位=真正入队（P2）：旧单发语义先置位再入队，idx<0/入队抛异常时 EOS 实际丢了
            // 而标志已立，喂入门与兜底都不再试 → done 恒不置位 → DRAIN 超时把当段误废。
            // 失败不置位，三条触发路（喂到哨兵后的补发、停止兜底、失效兜底）下一轮重试
            ch.eosQueued = true
        } catch (e: IllegalStateException) {
            Log.e(TAG, "audio eos queue failed: ${e.message}")
        }
    }

    private fun eosPts(ch: AudioChannel): Long =
        if (ch.lastPtsUs > 0L) ch.lastPtsUs else System.nanoTime() / 1000L

    // endregion

    // region 收尾

    /**
     * 等泵循环收尾。
     *
     * 【超时即只记账的闸】返回 false 表示泵线程仍卡在换段腿/收尾里：调用方**必须**按
     * "泵线程单写者"处理，只记账、**不许**去碰 codec/muxer/target/currentSink/parts
     * （双触名单与后果见 [settleRotateLegAtStop] 的 KDoc；`parts` 已 COW 化后唯一获准的例外是
     * [finishWithoutTeardown] 里的 `toList()` 只读快照，仍不许 add/clear）。
     *
     * 【线程句柄不许在超时时丢掉】只在确认线程已退出时才置空 [loop]：丢早了，调用方随后
     * 紧接着的 [release] 里第二次 joinLoop 会拿到 null、直接判"已退出"，于是在泵线程**仍然活着**
     * 的情况下又去拆一遍资源——超时闸就被绕过去了。
     *
     * @return true = 循环已退出（收尾完整）；false = 超时仍未退出
     */
    private fun joinLoop(): Boolean {
        val t = loop ?: return true
        try {
            t.join(LOOP_JOIN_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (t.isAlive) {
            Log.e(TAG, "mux loop alive after ${LOOP_JOIN_MS}ms（只记账不拆资源）")
            return false
        }
        loop = null
        return true
    }

    /**
     * 泵线程还没退出时的停止结果：**只读已经安全的量**造一份快照就返回，
     * **不拆资源、不封段、不清状态**。
     *
     * 【为什么不调 [finish]】finish 会 `parts.clear()` / `segSampleCounts.clear()` 并置 IDLE，
     * 而 parts 正是泵线程 [sealCurrent] 的写入目标——并发 clear 与 add 撞车要么抛结构性异常、
     * 要么把刚封好的那一段从结果里抹掉（文件已 commit 但用户看不到）。
     *
     * 【结果必须如实（2026-10-16 "录制结果如实化"）】`parts` 已 COW 化 ⇒ 这里的 `toList()`
     * 只读快照与泵线程正在进行的 add 结构性互不干扰，已封段经 [sealedPartsResult] 如实带进
     * 结果（旧行为返回无产物的失败结果：用户被告知全丢、相册里却有 N 段）。逐段样本/丢失账
     * 仍是泵线程单写者的普通容器 ⇒ 一律不带（宁缺勿假）。失败码与丢失账照旧保留（可查）。
     *
     * 【谁来收尾】泵线程从换段腿里出来后照常走 [pumpLoop] 的 closeSegmentEngine/sealCurrent，
     * 资源与封段都是它自己做；本对象停在 STOPPING，调用方紧接着的 [release] 会**再 join 一次**，
     * 那时泵线程通常已自己收干净，控制线程再拆全是空操作（[joinLoop] 超时不丢句柄就是为了这个）。
     */
    private fun finishWithoutTeardown(): RecordResult {
        val lost = rotateLoss.totalMs
        // 错误码：已有原因码就保留；否则按"停止时换段腿未返回"记换段受阻（带丢失账与环节游标，
        // UI 侧呈现计划把失败码语义转成不吓人的附注）。绝不返回"成功但零产物"。
        val err = engineError ?: rotateStallError(lost, rotateFailStage)
        // 已封段只读快照：COW 的 toList 与泵线程的 add 结构性互不干扰；空快照时桥函数退回旧失败形态
        val sealed = parts.toList()
        Log.e(TAG, "停止时泵线程未退出：只记账不拆资源（已封 ${sealed.size} 段如实带出，封段由泵线程自己收），失败码 $err")
        return sealedPartsResult(
            sealed = sealed,
            err = err,
            durationMs = clock.elapsedMs(),
            seriesId = store.seriesId,
            lostMs = lost,
            rotateStalls = rotateLoss.count,
            muxDiag = muxFailDetail,
            ambientLost = ambientLost,
            latePending = latePendingReaped
        )
    }

    private fun stopFeeder() {
        feeder?.stop()
        feeder = null
    }

    private fun stopCapFeeder() {
        capFeeder?.stop()
        capFeeder = null
    }

    /** 固定停止顺序（04 文件 §3）：codec.stop/release → muxer.stop/release */
    private fun closeSegmentEngine() {
        // 起录缓冲随段释放：正常段尾已补写/清空；异常路径（runSegment 早退、muxer 始终没起来）
        // 里剩下的样本在这里计入丢失，保证"已编码却未入库"的帧有账可查，也避免跨段残留
        preMuxBuffer?.let { buf ->
            val left = buf.clear()
            if (left > 0) {
                preMuxDropped += left
                Log.w(TAG, "起录缓冲随段释放：part=$partIndex 丢弃 $left 笔")
            }
        }
        preMuxBuffer = null
        val v = vCodec
        if (v != null) {
            try {
                v.stop()
            } catch (e: IllegalStateException) {
                Log.i(TAG, "video codec stop ignored")
            } catch (e: RuntimeException) {
                Log.e(TAG, "video codec stop threw: ${e.message}")
            }
            releaseQuietly(v)
            vCodec = null
        }
        val a = aCodec
        if (a != null) {
            try {
                a.stop()
            } catch (e: IllegalStateException) {
                Log.i(TAG, "audio codec stop ignored")
            } catch (e: RuntimeException) {
                Log.e(TAG, "audio codec stop threw: ${e.message}")
            }
            releaseQuietly(a)
            aCodec = null
        }
        val c = capCodec
        if (c != null) {
            try {
                c.stop()
            } catch (e: IllegalStateException) {
                Log.i(TAG, "capture codec stop ignored")
            } catch (e: RuntimeException) {
                Log.e(TAG, "capture codec stop threw: ${e.message}")
            }
            releaseQuietly(c)
            capCodec = null
        }
        val m = muxer
        if (m != null) {
            try {
                m.stop()
                // 容器正常收尾（moov 写完）＝"本段可播"的独立信号：只由 stop() 正常返回置位，
                // 供 segmentKeepDecision 判"删不删"用——绝不由错误码反推（见 muxFinalized 字段注）
                muxFinalized = true
            } catch (e: IllegalStateException) {
                // P0-c：不仅「没 start 过」会抛——start 过但某轨零样本（幽灵轨）一样抛。
                // 这两种状态下容器必然缺 moov/轨不完整，本段按废片处理：置 engineError 让
                // sealCurrent 走 keep=false，绝不把损坏 MP4 静默提交进相册。
                // 真「没 start 过」的路（NO_VIDEO_TRACK 等）engineError 已先置，?: 保留首个原因码
                Log.i(TAG, "muxer stop failed (not started or zero-sample track): ${e.message}")
                engineError = engineError ?: "${RecordError.ENGINE_ERROR}:MUX_CLOSE"
            }
            releaseQuietly(m)
            muxer = null
        }
    }

    private fun releaseQuietly(c: MediaCodec?) {
        try {
            c?.release()
        } catch (e: RuntimeException) {
            Log.e(TAG, "codec release threw: ${e.message}")
        }
    }

    private fun releaseQuietly(m: MediaMuxer?) {
        try {
            m?.release()
        } catch (e: RuntimeException) {
            Log.e(TAG, "muxer release threw: ${e.message}")
        }
    }

    private fun reject(sink: OutputSink, code: String) {
        Log.e(TAG, "codec prepare rejected: $code")
        engineError = code
        // prepare 中途失败（openMuxer 失败走的就是这条）target 里还攥着打开的 fd，置空前必须关
        target?.close()
        target = null
        currentSink = null
        // 2026-10-12 有界化（原注释只留痕）：回收同样会撞 MediaProvider 卡顿（与 insert 同族），
        // 走固定 STORE_CALL_BUDGET_MS 预算；超时只记账——晚到的 delete 由 StalePendingSweep
        // （IS_PENDING=1 + 龄 ≥1h + 文件不在盘 → 冷启清）兜底，调用方 reject 照常返回
        val t0 = SystemClock.elapsedRealtime()
        val r = storeIo.run(STORE_CALL_BUDGET_MS) { store.discard(sink) }
        val costMs = SystemClock.elapsedRealtime() - t0
        if (r is Bounded.TimedOut) {
            Log.e(TAG, "prepare 失败回收输出记录超时（${STORE_CALL_BUDGET_MS}ms 预算，记录留给系统兜底清扫）")
        } else if (costMs > STORE_CALL_BUDGET_MS) {
            Log.w(TAG, "prepare 失败回收输出记录耗时 ${costMs}ms（库内完成，慢于预算留痕）")
        }
        state = EngineState.IDLE
    }

    private fun sealCurrent(durationMs: Long, keep: Boolean) {
        val sink = currentSink ?: return
        // 输出句柄先关，再 commit/discard（写完才清 IS_PENDING）。**两步都有界**：关 fd / 查 SIZE、DATA /
        // 清 IS_PENDING 全走 MediaProvider，这台机在 33MB 级 pending 文件收尾上会长时间不返回（真机
        // 现场：段已写满 31.5MB、计时冻结、无新文件、无残留——正是卡在这两步之间的形态）
        closeTargetBounded()
        currentSink = null
        val seg = sealBounded(sink, durationMs, keep) ?: return
        // 废片不能进 parts：seal 已经把这条 MediaStore 记录删掉了，列进去就会让
        // buildResult 拿到一个不存在的 uri，out.ok 成立 → UI 报「已保存」而文件其实没了
        // （超时的情况下 seg 为 null：晚到的 commit 仍会把文件落地，只是不进 parts 清单）
        if (keep) parts.add(seg)
    }

    private fun discardCurrent(code: String) {
        val sink = currentSink ?: return
        Log.i(TAG, "discard unusable output: $code")
        closeTargetBounded()
        currentSink = null
        val r = storeIo.run(rotateBudgetLeftMs()) {
            store.seal(sink, partIndex, partStartMs, 0L, keep = false)
        }
        if (r is Bounded.TimedOut) noteStoreTimeout("discardCurrent")
    }

    private fun buildResult(err: String?): RecordResult {
        val first = parts.firstOrNull()
        return RecordResult(
            uri = first?.uri,
            path = first?.path,
            bytes = parts.sumOf { it.bytes },
            durationMs = clock.elapsedMs(),
            error = err,
            seriesId = store.seriesId,
            parts = parts.toList(),
            uncommitted = parts.count { !it.committed },
            segVideoSamples = segSampleCounts.toList(),
            segLostMs = segLostMs.toList(),
            lostMs = rotateLoss.totalMs,
            rotateStalls = rotateLoss.count,
            latePending = latePendingReaped,
            segmentRestarts = segmentRestarts,
            muxDiag = muxFailDetail,
            // 环境音丢失进结果（S1-M3-05）：ok 时 UI 在「已保存」上附注；与 degradedCode 同时命中
            // 走组合文案（两件事互不矛盾）。失败结果（parts 为空）文案由错误码承担，不带本注
            ambientLost = ambientLost
        )
    }

    /** ERROR 只是中间态：清完废片必回 IDLE（04 §6） */
    private fun finish(error: String?): RecordResult {
        // 无产物也要把换段丢帧账带上（"整段丢了 N 秒"是用户唯一能看到的解释，
        // 见 RecordError.ROTATE_STALL 与 recordResultText 的文案映射）
        val lost = rotateLoss.totalMs
        val degraded = degradedCode
        val out = when {
            parts.isEmpty() ->
                RecordResult.fail(error ?: RecordError.TOO_SHORT, clock.elapsedMs(), lost, muxFailDetail)
            // 降级呈现：有产物 + 降级码 ⇒ 按成功呈现（error 置空、ok==true、degradedCode 带诊断原文），
            // UI 走"已保存 + 末尾可能不完整"文案——文件确实在盘上、moov 已写完，说成"失败"是骗人。
            // **只在错误就是那条降级码时成立**：engineError 是首错优先、不会换码，相等即同一回事；
            // 万一不等（将来引入并行的第二错误源）宁可回落失败呈现，绝不拿降级码盖住更硬的失败
            degraded != null && degraded == error -> buildResult(null).copy(degradedCode = degraded)
            else -> buildResult(error)
        }
        state = EngineState.IDLE
        engineError = null
        degradedCode = null
        clock.reset()
        // 会话段清单摘要进诊断导出正文（与 .drops.json 的 segments 同源：先取 out 里的快照，
        // 再清泵线程单写者的段账，顺序不许反）
        val sessionLine = frameSessionSummary(out.segVideoSamples, out.segLostMs)
        if (sessionLine.isNotEmpty()) Log.frameAccount(sessionLine)
        parts.clear()
        partIndex = 0
        partStartMs = 0L
        segVideoWritten = 0
        muxFinalized = false
        segHadVideoSample = false
        segSampleCounts.clear()
        resetRotateBooks()
        return out
    }

    // endregion
}

/**
 * [CodecRecorder.drainWaitTick] 的三态快照（起窗 / 顺延 / 超时各自只改一个字段）。
 * [openedAtMs] 是硬的：它只作硬上限基准（起点 + [CodecRecorder.DRAIN_HARD_LIMIT_MS]），不随顺延移动。
 */
internal data class DrainTick(
    /** 窗起点（0 = 未起窗），elapsedRealtime 域 */
    val openedAtMs: Long,
    /** 当前超时时刻（顺延后）；判硬上限超时时是硬上限线本身 */
    val deadlineMs: Long,
    /** 本拍是否判超时 */
    val timedOut: Boolean
)
