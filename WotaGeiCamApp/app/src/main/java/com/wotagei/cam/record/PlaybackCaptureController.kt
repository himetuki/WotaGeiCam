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
        captureConfig = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        apply(CaptureEvent.Established)
    }

    /**
     * Active 态建设备内录 AudioRecord；非 Active / 无会话 / 参数本机不支持 → null。
     * buffer 口径对齐 [AudioFeeder]：半最小缓冲为读块（下限 16 帧、上限 8192、整帧对齐），
     * 整缓冲 = max(minBufferSize, chunk)。
     */
    @Suppress("MissingPermission") // 内录路径本不需要 RECORD_AUDIO；工程 manifest 已声明并做运行时检查
    fun createCaptureAudioRecord(sampleRate: Int, channels: Int): AudioRecord? {
        if (_state.value !is CaptureState.Active) return null
        val config = captureConfig ?: return null
        val minBuf = AudioProbe.minBufferSize(sampleRate, channels)
        if (minBuf <= 0) return null
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
                rec.release()
                null
            } else {
                rec
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "capture AudioRecord build failed: ${e.message}")
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
    }
}
