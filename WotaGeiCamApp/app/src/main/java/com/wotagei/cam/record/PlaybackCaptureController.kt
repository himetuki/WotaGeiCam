package com.wotagei.cam.record

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.max

/** 内录轨丢失原因（引擎层降级的可见化出口；UI 据此映射提示文案，事件本身不携带文案） */
enum class CaptureTrackLostReason {
    /** 内录采集器建不起来（AudioRecord 初始化失败/参数不支持/会话失效）：确定失败，立即提示 */
    INIT_FAILED,
    /** 录制中捕获源失效（采集泵读到错误码退出等；投影撤销场景 UI 另有 Revoked 提示，会被过滤） */
    SOURCE_INACTIVE,
    /** 内录通道收尾/等待窗满仍无任何真实样本，轨被废弃：本次成片没有内录轨 */
    NO_SAMPLES,
    /** 分段轮转时内录编码器重建失败：本段起内录轨退出 */
    ENCODER_REBUILD_FAILED
}

/** 内录轨丢失事件：reason 供 UI 映射文案，detail 是中文短因（仅 INIT_FAILED 用来填文案参数） */
data class CaptureTrackLost(val reason: CaptureTrackLostReason, val detail: String?)

/**
 * 设备内录（AudioPlaybackCapture）授权控制器：持有授权状态机、驱动建链管线。
 *
 * # 授权合约形态（与工程现有 ActivityResult 用法一致）
 * 批 2 用 `rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult())`
 * （同 CameraScreen picker / CompareScreen pickRight 先例）：发射 [createConsentIntent]，
 * 回执 resultCode+data 原样交 [onConsentResult]。
 *
 * # 职责切分
 * Android 依赖面（MediaProjectionManager / startForegroundService / AudioRecord 构造）全在本类；
 * 纯状态机在 [transition]（CaptureStateMachine.kt，零 android 引用，纯度守卫锁死），状态推进一律走它。
 * 服务侧顺序链见 [CaptureFgService]：startForeground → getMediaProjection → registerCallback 之后
 * 才把 projection 回传到这里，本类随后建捕获配置暂存 → Active。
 */
class PlaybackCaptureController(context: Context) {

    private val appContext: Context = context.applicationContext
    private val projectionManager: MediaProjectionManager? =
        appContext.getSystemService(MediaProjectionManager::class.java)

    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    private val _trackLost = MutableStateFlow<CaptureTrackLost?>(null)

    /**
     * 内录轨丢失事件（读走即清：UI 提示后调 [clearTrackLost] 置回 null，同因再次发生仍能发出）。
     * 引擎层（CodecRecorder）的降级路径此前只留日志——"开了内录却没内录轨"用户毫无感知，
     * 这条流是那次静默的唯一出口，任何 drop 分支都必须走到它。
     *
     * 已知竞态（登记不修）：两次上报间隔小于 UI 采集帧时，StateFlow 相等去重 + 读走即清的
     * clear 竞态可能把前者吞掉——丢的只是提示不是数据，可接受，只影响提示完整性不影响数据。
     */
    val trackLost: StateFlow<CaptureTrackLost?> = _trackLost.asStateFlow()

    /** 最近一次 [createCaptureAudioRecord] 失败的中文短因（提示文案参数；成功创建即清空） */
    @Volatile
    var lastInitFailure: String? = null
        private set

    /** 引擎层上报内录轨丢失（幂等性由上报方守卫：每个 drop 分支只报一次） */
    fun reportTrackLost(reason: CaptureTrackLostReason, detail: String?) {
        Log.e(TAG, "capture track lost: $reason detail=${detail ?: "none"}")
        _trackLost.value = CaptureTrackLost(reason, detail)
    }

    /** UI 读走事件后清槽（StateFlow 相等去重：清了才收得到下一次同因事件） */
    fun clearTrackLost() {
        _trackLost.value = null
    }

    /** 建链成功时暂存的捕获配置（usage 白名单），Active 态建 AudioRecord 用 */
    private var captureConfig: AudioPlaybackCaptureConfiguration? = null

    /**
     * 批 2 的授权框入口：`createScreenCaptureIntent()` 合约封装。
     * 返回 null = 本机拿不到 MediaProjectionManager（理论外；状态保持 Idle，调用方按 Failed 处理）。
     * 拿到 intent 才置 Authorizing：置态与发射原子，不出现"卡在授权中却没有框"的态。
     */
    fun createConsentIntent(): Intent? {
        val intent = projectionManager?.createScreenCaptureIntent() ?: return null
        apply(CaptureEvent.ConsentRequested)
        return intent
    }

    /**
     * StartActivityForResult 回执入口：RESULT_OK 之外（用户取消/返回键）一律回滚 Idle。
     * 调用时机约束：Activity 前台收到授权回执时（此时 startForegroundService 合法）。
     */
    fun onConsentResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            apply(CaptureEvent.ConsentCancelled)
            return
        }
        onConsentGranted(resultCode, data)
    }

    /**
     * 授权同意后的建链管线：startForegroundService → 服务内 startForeground →
     * getMediaProjection → registerCallback →（回执到这）建捕获配置暂存 → Active。
     * 任一步失败 → Failed(原因)。
     */
    private fun onConsentGranted(resultCode: Int, data: Intent) {
        // 脏回执防线：非授权中态（如 shutdown 后迟到的回执）不建链，与状态机「乱序回执不改态」对齐
        if (_state.value !is CaptureState.Authorizing) return
        // 重复授权（Active 下重开）：先经服务的身份守卫拆旧会话再建新链，避免双会话叠着各收一份音频。
        // 拆旧必须走 stopActiveProjection（不许直接 projection?.stop()）：程序主动拆不算用户撤销
        // （否则迟到的 onStop 会把本次重建误标成 Revoked），也不会误杀替换后的新会话。
        CaptureFgService.stopActiveProjection()
        CaptureFgService.onEstablishResult = ::onEstablishResult
        CaptureFgService.onProjectionRevoked = { apply(CaptureEvent.ProjectionStopped) }
        try {
            appContext.startForegroundService(
                CaptureFgService.startIntent(appContext, resultCode, data)
            )
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException（34 起）/ IllegalStateException 等
            clearServiceHooks()
            apply(CaptureEvent.EstablishFailed("startForegroundService: ${e.message}"))
        }
    }

    /** 服务建链回执：成功 → 暂存配置 → Active；失败 → Failed(原因) */
    private fun onEstablishResult(proj: MediaProjection?, error: String?) {
        if (proj == null) {
            clearServiceHooks()
            apply(CaptureEvent.EstablishFailed(error ?: "unknown"))
            return
        }
        // 脏回执防线（与 onConsentGranted 同型）：非授权中态不落配置不改态，
        // 防批 2 若出现并发授权路径时成功回执覆写正在收尾的会话配置
        if (_state.value !is CaptureState.Authorizing) return
        val builder = AudioPlaybackCaptureConfiguration.Builder(proj)
        // usage 白名单取自 [captureUsages]：官方仅允许捕获这三个 usage 的播放，
        // 集合必须在守卫测试锁死——漏一个就是"该类播放整类收不到"的静默缩圈
        for (usage in captureUsages()) builder.addMatchingUsage(usage)
        captureConfig = builder.build()
        apply(CaptureEvent.Established)
    }

    /**
     * Active 态建设备内录 AudioRecord；非 Active / 无会话 / 参数本机不支持 → null。
     * 每个 null 分支都写 [lastInitFailure]（引擎层 INIT_FAILED 提示的文案参数）——
     * 失败原因不许只留日志：采集器建不起来的机型上用户只会看到"没内录轨"。
     * buffer 口径对齐 [AudioFeeder]：半最小缓冲为读块（下限 16 帧、上限 8192、整帧对齐），
     * 整缓冲 = max(minBufferSize, chunk)。
     */
    @Suppress("MissingPermission") // 内录路径本不需要 RECORD_AUDIO；工程 manifest 已声明并做运行时检查
    fun createCaptureAudioRecord(sampleRate: Int, channels: Int): AudioRecord? {
        if (_state.value !is CaptureState.Active) {
            lastInitFailure = "内录会话已失效"
            return null
        }
        val config = captureConfig ?: run {
            lastInitFailure = "内录会话配置缺失"
            return null
        }
        val minBuf = AudioProbe.minBufferSize(sampleRate, channels)
        if (minBuf <= 0) {
            lastInitFailure = "采样参数本机不支持"
            return null
        }
        val frameBytes = max(channels, 1) * 2
        val chunk = run {
            val raw = max(minBuf / 2, frameBytes * 16).coerceAtMost(CHUNK_MAX)
            (raw / frameBytes) * frameBytes
        }
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioProbe.channelConfig(channels))
            .build()
        return try {
            val rec = AudioRecord.Builder()
                .setAudioFormat(format)
                .setAudioPlaybackCaptureConfig(config)
                .setBufferSizeInBytes(max(minBuf, chunk))
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "capture AudioRecord not initialized (state=${rec.state})")
                lastInitFailure = "采集器初始化失败"
                rec.release()
                null
            } else {
                lastInitFailure = null
                rec
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "capture AudioRecord build failed: ${e.message}")
            lastInitFailure = "系统拒绝创建内录采集器"
            null
        }
    }

    /**
     * 主动关停：拆会话、停服务、清钩子 → Idle。幂等。
     * 拆会话走服务的身份守卫路径（[CaptureFgService.stopActiveProjection]）：
     * 迟到的 onStop 不会把这里刚归的 Idle 误改回 Revoked。
     */
    fun shutdown() {
        clearServiceHooks()
        captureConfig = null
        lastInitFailure = null
        CaptureFgService.stopActiveProjection()
        appContext.stopService(Intent(appContext, CaptureFgService::class.java))
        apply(CaptureEvent.Shutdown)
    }

    private fun clearServiceHooks() {
        CaptureFgService.onEstablishResult = null
        CaptureFgService.onProjectionRevoked = null
    }

    private fun apply(event: CaptureEvent) {
        _state.update { transition(it, event) }
    }

    companion object {
        /** 同 [AudioFeeder] 读块上限口径 */
        private const val CHUNK_MAX = 8192

        /**
         * 捕获 usage 白名单（纯函数，供守卫单测锁集合）：官方 `addMatchingUsage` 仅接受
         * MEDIA / GAME / UNKNOWN 三个 usage，传其它值抛 IllegalArgumentException；
         * 漏掉任何一个 = 该类播放整类收不到（用户感知"开了内录只有环境音"）。
         * 期望值在单测里用字面量 `intArrayOf(1, 14, 0)` 对照（USAGE_GAME=14，USAGE_ALARM 才是 4，
         * JVM 探针实跑 android-34 jar 常量实证），防常量引用被悄悄替换。
         */
        internal fun captureUsages(): IntArray = intArrayOf(
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_UNKNOWN
        )

        @Volatile
        private var singleton: PlaybackCaptureController? = null

        /**
         * 单例宿主（批 1 交接约束 P3-2）：控制器状态机与投影会话的生命周期归属 **app** 而非
         * 录制页——旋转/重建组合、离页再回，[state] 都必须是同一份（会话还在，开关就还亮着）。
         * 挂在 [WotaApp] 式服务定位器（同 `MediaRepo.get(app)` 先例）还是 Activity 级 remember？
         * 选单例：MediaProjection 会话由前台服务持有、独立于任何 Activity 存活，控制器跟着
         * Activity 重建反而会出现"会话在、状态丢了"的孤儿态。
         */
        fun get(context: Context): PlaybackCaptureController =
            singleton ?: synchronized(this) {
                singleton ?: PlaybackCaptureController(context.applicationContext).also { singleton = it }
            }
    }
}
