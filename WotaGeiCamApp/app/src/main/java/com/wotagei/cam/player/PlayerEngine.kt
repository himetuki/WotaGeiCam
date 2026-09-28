// Media3 1.1.1 把 PlayerView/ExoPlayer.Builder/DefaultRenderersFactory 都标了 @UnstableApi，
// 但这是官方推荐给非通用播放器的公开入口（AGP 会按 lint 报 UnsafeOptInUsageError），整文件认下来。
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.wotagei.cam.player

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.wotagei.cam.media.VideoClip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** 循环模式：不循环 / 单视频循环（AB 段循环用 [AbRange]，不走 repeat） */
enum class LoopMode { OFF, ONE }

/** AB 段：-1 表示未打点 */
data class AbRange(val aMs: Long = -1L, val bMs: Long = -1L, val loopEnabled: Boolean = false) {
    val valid: Boolean get() = aMs >= 0 && bMs > aMs
}

/** 倍速档位（需求 27 行：0.1 / 0.3 / 0.5 / 0.75 / 1.0） */
object PlayerSpeedTiers {
    val TIERS = listOf(0.1f, 0.3f, 0.5f, 0.75f, 1.0f)
    const val DEFAULT = 1.0f
    fun normalize(f: Float): Float = TIERS.minByOrNull { kotlin.math.abs(it - f) } ?: DEFAULT
}

/**
 * Media3 封装。
 *
 * 【两种"暂停"严格区分】（记录的真实 NPE 事故：官方 stepFrame 先调破坏性 pause() 再读 currentPosition）
 * - [softPause]：唯一允许的「暂停」语义，只改 playWhenReady，播放器与状态保留，seek/步进/恢复都可用；
 * - [releasePlayer]：不可逆，之后任何方法都不得触碰 player（本类内部一律走 null 检查，released 后全部空操作）。
 *
 * 进度只有一个数据源：120ms 轮询 tick 同时产出 [positionMs]、[durationMs] 与 AB 回绕（/5）。
 *
 * 1.1.1 无 SeekParameters，精确逐帧待升级 Media3 后补（见 [stepFrame]）。
 */
class PlayerEngine(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tick: Job? = null

    private var player: ExoPlayer? = null
    private val boundViews = ArrayList<PlayerView>()

    /** 步进已发出但尚未落地时记下的目标：seek 未完成期间 currentPosition 仍是旧值，连点要在它之上继续推进 */
    private var pendingStepMs: Long? = null
    private var pendingStepTicks = 0

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackState = MutableStateFlow(Player.STATE_IDLE)
    val playbackState: StateFlow<Int> = _playbackState.asStateFlow()

    private val _error = MutableStateFlow(false)
    val error: StateFlow<Boolean> = _error.asStateFlow()

    private val _fps = MutableStateFlow(DEFAULT_FPS)
    val fps: StateFlow<Float> = _fps.asStateFlow()

    private val _ab = MutableStateFlow(AbRange())
    val ab: StateFlow<AbRange> = _ab.asStateFlow()

    private val _speed = MutableStateFlow(PlayerSpeedTiers.DEFAULT)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _loopModeState = MutableStateFlow(LoopMode.OFF)
    val loopModeState: StateFlow<LoopMode> = _loopModeState.asStateFlow()

    var released: Boolean = false
        private set

    var currentUri: Uri? = null
        private set

    /**
     * AB 回绕扩展点（对比页用它让两台 ExoPlayer 同时回到 A）。
     * 返回 true 表示外部已处理，引擎不再自行 seekTo(A)。
     */
    var abWrapHook: ((aMs: Long, posMs: Long) -> Boolean)? = null

    /** 与规格 `var loopMode: LoopMode` 对齐；Compose 侧读 [loopModeState] */
    var loopMode: LoopMode
        get() = _loopModeState.value
        set(value) {
            _loopModeState.value = value
            applyRepeat()
        }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
        }

        override fun onPlaybackStateChanged(state: Int) {
            _playbackState.value = state
            if (state == Player.STATE_READY) refreshDurations()
        }

        override fun onTracksChanged(tracks: Tracks) {
            readFrameRate(tracks)
        }

        override fun onPlayerError(error: PlaybackException) {
            _error.value = true
        }
    }

    // region 生命周期

    /** 幂等：同一 uri 只换源一次；播放器实例复用，不重建（避免离屏页反复 release） */
    fun attach(uri: Uri) {
        if (released) return
        val p = player ?: build().also { player = it }
        if (currentUri == uri) {
            if (tick?.isActive != true) startTick()
            return
        }
        currentUri = uri
        _error.value = false
        _positionMs.value = 0L
        pendingStepMs = null
        p.setMediaItem(MediaItem.fromUri(uri))
        p.playWhenReady = false
        restoreAb(uri)
        p.prepare()
        startTick()
    }

    private fun build(): ExoPlayer {
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        val selector = DefaultTrackSelector(context)
        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderers)
            .setTrackSelector(selector)
            .build()
            .apply {
                // 帧步进精度：Media3 1.1.1 无 SeekParameters（media3-common classes.jar 内不存在该类，
                // Player 也无 setSeekParameters），精确逐帧待升级 Media3 后补；当前按帧栅格对齐目标后依赖 seekTo 的默认同步帧对齐（见 stepFrame）
                addListener(listener)
                repeatMode = Player.REPEAT_MODE_OFF
            }
    }

    /** 非破坏性暂停：唯一允许的「暂停」语义 */
    fun softPause(paused: Boolean) {
        val p = player ?: return
        // 恢复播放即放弃步进目标：位置从此由播放推进决定，继续护着会把 AB 回绕卡住
        if (!paused) pendingStepMs = null
        p.playWhenReady = !paused
        _isPlaying.value = if (paused) false else p.isPlaying
    }

    /** 不可逆销毁；此后所有方法都是空操作 */
    fun releasePlayer() {
        if (released) return
        released = true
        tick?.cancel()
        tick = null
        boundViews.toList().forEach { runCatching { it.player = null } }
        boundViews.clear()
        val p = player
        player = null
        currentUri = null
        runCatching { p?.removeListener(listener) }
        runCatching { p?.release() }
        _isPlaying.value = false
        _playbackState.value = Player.STATE_IDLE
    }

    // endregion

    // region 播放控制

    fun seekTo(ms: Long) {
        val p = player ?: return
        val target = ms.coerceAtLeast(0L)
        pendingStepMs = null
        p.seekTo(target)
        // 这里保留乐观覆写：拖进度条要即时反馈，回跳由拖动中本地值覆盖承担
        _positionMs.value = target
    }

    /** 同一时刻可读的真实位置（对比页算左右偏移用，避开 120ms 轮询的量化误差） */
    fun livePositionMs(): Long {
        val t = player?.currentPosition ?: -1L
        return if (t >= 0L) t else _positionMs.value
    }

    /**
     * 帧步进 / 帧后退。
     *
     * 目标一律对齐帧栅格（前进取 floor+1、后退取 ceil-1），反复点不会停在非帧首、来回点也不漂移；
     * 1.1.1 无 SeekParameters，对齐只能在这里自己做（见类注释）。
     * 不乐观覆写 [positionMs]（进度只由轮询回读，避免 seek 未完成时进度条回跳），
     * 连点不吞步靠 [pendingStepMs] 记住上一次已发出的目标。
     * 先 [softPause] 再读位置 —— 绝不能调破坏性 pause（NPE 事故）。
     *
     * @return 本次对齐后的目标位置（对比页要用它同步右引擎，此时 [positionMs] 还没跟上）
     */
    fun stepFrame(fwd: Boolean): Long {
        val p = player ?: return _positionMs.value
        softPause(true)
        val frameMs = 1000.0 / _fps.value.coerceAtLeast(1f)
        val target = FrameStep.next(pendingStepMs, p.currentPosition, frameMs, fwd, _durationMs.value)
        p.seekTo(target)
        pendingStepMs = target
        pendingStepTicks = 0
        return target
    }

    fun setSpeed(f: Float) {
        val tier = PlayerSpeedTiers.normalize(f)
        _speed.value = tier
        player?.setPlaybackSpeed(tier)
    }

    fun setVolume(v: Float) {
        player?.volume = v.coerceIn(0f, 1f)
    }

    private fun applyRepeat() {
        val p = player ?: return
        val ab = _ab.value
        // AB 段循环激活时交出循环控制权，避免与 REPEAT_MODE_ONE 打架
        p.repeatMode = if (_loopModeState.value == LoopMode.ONE && !ab.loopEnabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    // endregion

    // region AB 段循环

    /** 打点 A：若已有 B 且 B ≤ A，则清 B 并关循环 */
    fun markA(): Boolean {
        if (released) return false
        val pos = currentPosition()
        val cur = _ab.value
        val b = if (cur.bMs > pos) cur.bMs else -1L
        _ab.value = AbRange(pos, b, cur.loopEnabled && b > 0 && b > pos)
        persistAb()
        applyRepeat()
        return true
    }

    /** 打点 B：需已有 A 且 B > A，成功即自动开循环 */
    fun markB(): Boolean {
        if (released) return false
        val cur = _ab.value
        val pos = currentPosition()
        if (cur.aMs < 0 || pos <= cur.aMs) return false
        _ab.value = AbRange(cur.aMs, pos, true)
        persistAb()
        applyRepeat()
        return true
    }

    /** 循环开关：仅在 A/B 合法时能开启 */
    fun toggleAbLoop(): Boolean {
        val cur = _ab.value
        if (!cur.valid) return false
        _ab.value = cur.copy(loopEnabled = !cur.loopEnabled)
        persistAb()
        applyRepeat()
        return true
    }

    fun clearAb() {
        _ab.value = AbRange()
        persistAb()
        applyRepeat()
    }

    /** 打点用的位置：步进目标尚未落地时以目标为准，否则 A/B 会打在 seek 之前的旧帧上 */
    private fun currentPosition(): Long = pendingStepMs ?: player?.currentPosition ?: _positionMs.value

    private fun restoreAb(uri: Uri) {
        val sp = prefs()
        val a = sp.getLong(key(KEY_A, uri), -1L)
        val b = sp.getLong(key(KEY_B, uri), -1L)
        val loop = sp.getBoolean(key(KEY_LOOP, uri), false)
        _ab.value = AbRange(a, b, loop && a >= 0 && b > a)
        applyRepeat()
    }

    private fun persistAb() {
        val uri = currentUri ?: return
        val ab = _ab.value
        prefs().edit()
            .putLong(key(KEY_A, uri), ab.aMs)
            .putLong(key(KEY_B, uri), ab.bMs)
            .putBoolean(key(KEY_LOOP, uri), ab.loopEnabled)
            .apply()
    }

    private fun key(prefix: String, uri: Uri): String = "$prefix:$uri"

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // endregion

    // region 轮询（唯一进度源）

    private fun startTick() {
        tick?.cancel()
        // ExoPlayer 只允许在主线程访问，轮询协程必须钉在 Main 上（否则 verifyApplicationThread 直接崩）
        tick = scope.launch(Dispatchers.Main.immediate) {
            while (isActive) {
                pollOnce()
                delay(POLL_MS)
            }
        }
    }

    private fun pollOnce() {
        val p = player ?: return
        if (released) return
        val pos = p.currentPosition.coerceAtLeast(0L)
        resolvePendingStep(pos)
        _positionMs.value = pos
        val dur = p.duration?.coerceAtLeast(0L) ?: 0L
        if (dur != _durationMs.value) _durationMs.value = dur

        val ab = _ab.value
        // 步进未落地期间不做 AB 回绕，否则刚跨出去的一帧会被立刻拉回 A 点
        if (ab.loopEnabled && ab.valid && pendingStepMs == null && pos >= ab.bMs) {
            val handled = abWrapHook?.invoke(ab.aMs, pos) == true
            if (!handled) {
                p.seekTo(ab.aMs)
                _positionMs.value = ab.aMs
            }
        }
    }

    /** 目标已到位（同一帧内）或迟迟不到位（被 clamp / seek 失败）都收摊，交回常规轮询与 AB 回绕 */
    private fun resolvePendingStep(pos: Long) {
        val pending = pendingStepMs ?: return
        pendingStepTicks++
        val halfFrameMs = (1000.0 / _fps.value.coerceAtLeast(1f) / 2.0).roundToInt().coerceAtLeast(1)
        if (abs(pos - pending) <= halfFrameMs || pendingStepTicks >= STEP_PENDING_MAX_TICKS) pendingStepMs = null
    }

    private fun refreshDurations() {
        val p = player ?: return
        _durationMs.value = p.duration?.coerceAtLeast(0L) ?: 0L
    }

    private fun readFrameRate(tracks: Tracks) {
        var rate = 0f
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until group.length) {
                val f = group.getTrackFormat(i)
                if (f.frameRate > 0f) {
                    rate = f.frameRate
                    break
                }
            }
            if (rate > 0f) break
        }
        if (rate <= 0f) rate = probeFrameRate(currentUri)
        _fps.value = if (rate > 0f) rate else DEFAULT_FPS
    }

    /** MediaStore 不给 fps，用 retriever 的拍摄帧率兜底，仍失败用 25 */
    private fun probeFrameRate(uri: Uri?): Float {
        if (uri == null) return 0f
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull() ?: 0f
        } catch (e: Exception) {
            0f
        } finally {
            runCatching { retriever.release() }
        }
    }

    // endregion

    // region Surface 绑定

    /** 供 PlayerView 绑定；release 之后调用只把 player 置空，不访问已释放实例 */
    fun bind(view: PlayerView) {
        if (!boundViews.contains(view)) boundViews.add(view)
        view.player = if (released) null else player
    }

    fun unbind(view: PlayerView) {
        boundViews.remove(view)
        runCatching { if (view.player === player) view.player = null }
    }

    // endregion

    companion object {
        private const val POLL_MS = 120L

        /** 步进目标迟迟不落地时的兜底轮询数（约 360ms），防止 AB 回绕被长期抑制 */
        private const val STEP_PENDING_MAX_TICKS = 3
        private const val DEFAULT_FPS = 25f
        private const val PREFS = "wota_player_ab"
        private const val KEY_A = "ab_a"
        private const val KEY_B = "ab_b"
        private const val KEY_LOOP = "ab_loop"
    }
}

/** 便捷：媒体库页只需 uri + duration */
fun PlayerEngine.attach(clip: VideoClip) = attach(clip.uri)
