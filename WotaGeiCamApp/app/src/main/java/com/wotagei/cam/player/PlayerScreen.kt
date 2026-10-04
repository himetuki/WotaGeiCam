// Media3 1.1.1 把 PlayerView/ExoPlayer.Builder/DefaultRenderersFactory 都标了 @UnstableApi，
// 但这是官方推荐给非通用播放器的公开入口（AGP 会按 lint 报 UnsafeOptInUsageError），整文件认下来。
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.wotagei.cam.player

import android.graphics.Color
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CompareArrows
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.wotagei.cam.R
import com.wotagei.cam.core.WotaTiers
import com.wotagei.cam.media.MediaOps
import com.wotagei.cam.media.MediaTagDialog
import com.wotagei.cam.media.VideoClip
import com.wotagei.cam.media.formatDuration
import com.wotagei.cam.media.rememberMediaOps
import com.wotagei.cam.media.rememberMediaRepo
import com.wotagei.cam.record.ArcRepairError
import com.wotagei.cam.record.ArcRepairRunner
import com.wotagei.cam.record.ArcRepairStatus
import com.wotagei.cam.ui.anim.LocalMotion
import com.wotagei.cam.ui.design.WotaChip
import com.wotagei.cam.ui.design.WotaColor
import com.wotagei.cam.ui.design.WotaIconButton
import com.wotagei.cam.ui.design.WotaPillPopup
import com.wotagei.cam.ui.design.WotaShape
import com.wotagei.cam.ui.design.pillAnchor
import com.wotagei.cam.ui.design.wotaCard
import com.wotagei.cam.ui.theme.AcrylicScrim
import com.wotagei.cam.ui.theme.MonoStyle
import com.wotagei.cam.ui.theme.WotaAccent
import com.wotagei.cam.ui.theme.WotaBg
import com.wotagei.cam.ui.theme.WotaRec
import com.wotagei.cam.ui.theme.WotaSurface
import com.wotagei.cam.ui.theme.WotaText
import com.wotagei.cam.ui.theme.WotaTextDim
import android.os.SystemClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** 双击判定窗口：单击要等满这么久才落地显隐，否则「双击启停」会顺带闪一下控件栏。
 *  提成 internal：对比播放页的沉浸式控制层（10-01）复用同一套单击/双击仲裁，两处各抄一份迟早漂移。 */
internal const val DOUBLE_TAP_WINDOW_MS = 280L

/** 弹窗定宽：不固定的话每档按自身内容包裹，点击区参差 */
private val SPEED_POPUP_PANEL_WIDTH: Dp = 148.dp

/** 光弧修复弹层定宽：要装下那句 30 字的说明小字，比倍速档（纯数字）宽一档 */
private val ARC_POPUP_PANEL_WIDTH: Dp = 240.dp

/** 剪辑导出弹层定宽：要装下「入点 xx:xx（自动向前对齐关键帧）」这类长读数 */
private val CLIP_POPUP_PANEL_WIDTH: Dp = 264.dp

/**
 * 播放页圆钮尺寸沿用 30/18dp，不复用 WotaHit.iconButton(38)/iconGlyph(19)：
 * 顶栏有 7 枚圆钮，38dp 会把文件名挤成「…」（上一轮真机已踩过 M3 IconButton ≥48dp 的同一个坑）。
 */
private val TopBarIconSize: Dp = 30.dp
private val TopBarGlyphSize: Dp = 18.dp

/** 播放/暂停是底栏主操作，比其余图标位大一圈 */
private val PlayIconSize: Dp = 36.dp
private val PlayGlyphSize: Dp = 26.dp

/** 倍速档位文案（05 文档档位定版：0.1 / 0.3 / 0.5 / 0.75 / 1.0） */
@StringRes
/**
 * 帧率读数取哪个串（#51）：整数帧率给一位小数，**非整数必须给两位**。
 * 严格 30000/1001 的成片按一位小数会渲染成「30.0 fps」，等于把非整数帧率抹平 ——
 * 而这条读数正是用户判断「我这片到底是不是 29.97」的唯一入口。
 * 帧步进数学用的是 `PlayerEngine` 里的原始 float（`1000.0 / _fps.value`），不受这里影响。
 */
internal fun fpsStepLabelRes(fps: Float): Int =
    if (kotlin.math.abs(fps - kotlin.math.round(fps)) > 0.01f) R.string.player_fps_step_exact else R.string.player_fps_step

internal fun speedLabelRes(tier: Float): Int = when (tier) {
    0.1f -> R.string.player_speed_01
    0.3f -> R.string.player_speed_03
    0.5f -> R.string.player_speed_05
    0.75f -> R.string.player_speed_075
    else -> R.string.player_speed_10
}

/** 引擎生命周期：dispose 时 releasePlayer（此后引擎所有方法都是空操作，不会再触到已释放的 player） */
@Composable
fun rememberPlayerEngine(): PlayerEngine {
    val ctx = LocalContext.current.applicationContext
    val engine = remember(ctx) { PlayerEngine(ctx) }
    DisposableEffect(engine) { onDispose { engine.releasePlayer() } }
    return engine
}

/**
 * 播放画面：**TextureView** + 缩放/镜像变换（不开 media3 自带 controller，控件全部自绘）。
 *
 * Media3 1.1.1 的 PlayerView 只公开 resizeMode（SURFACE_TYPE_* 与 keepAspectRatio 均非公开，
 * surface 类型只能通过 XML 的 app:surface_type 指定——本工程经 `view_player_surface.xml`
 * 指定为 texture_view，10-01 起弃默认 surface_view，理由见该 XML 与 factory 内注释）。
 * RESIZE_MODE_FIT 等价于旧的「保持宽高比」，超出部分由外层 graphicsLayer 缩放；
 * [mirror] 是同一层 graphicsLayer 上的水平翻转（scaleX 取负），与缩放正确复合；
 * [pan] 是同一层上的双指平移量（px，父坐标），与缩放/镜像复合；绘制点按当帧图层尺寸经
 * [clampPan] 兜底重夹，视口变化（自持旋转等）后的陈旧 pan 不会把画面拖出黑边。
 */
// InflateParams 是有意为之：AndroidView 的 factory 里 root 必须为 null——
// Compose 自己量尺寸并施加 LayoutParams，挂了 parent 反而带进错误的 LayoutParams
@android.annotation.SuppressLint("InflateParams")
@Composable
fun WotaPlayerSurface(
    engine: PlayerEngine,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
    pan: Offset = Offset.Zero,
    mirror: Boolean = false
) {
    AndroidView(
        modifier = modifier.graphicsLayer {
            // 水平镜像=渲染层翻转 scaleX 取负：纯视图变换，不碰播放位置/帧步进/倍速/AB。
            // TextureView（见 factory）是普通 View，这类变换跨驱动确定；SurfaceView 做不到
            scaleX = if (mirror) -scale else scale
            scaleY = scale
            // 双指平移（缩放同一手势）：translation 在 scale 之后按父坐标应用，不受 scaleX
            // 取负影响——镜像开着时拖动方向依然是手的方向。
            // 绘制点兜底重夹（P2，2026-10-04）：pan 态只在双指手势事件内被夹取，而本工程
            // manifest 自持旋转（configChanges 不重建 Activity），旋转/分栏宽度变化后 viewport
            // 变了、remember 的 pan 原样存活——仅靠手势夹取会露出最多半域差的底色条，直到
            // 下一次捏合。这里用 graphicsLayer 自身 size（=调用方手势 Box 的当帧实际视口）再过
            // 一次 clampPan，任何一帧的可见平移恒在域内，不依赖下一次手势；真源仍是 clampPan。
            // 防退化点：删掉这行 clampPan 不会有任何 JVM 测试变红——graphicsLayer 的绘制期
            // 行为属设备渲染路径，JVM 单测桩测不到，只能真机旋转验收实证
            val p = clampPan(pan, scale, size)
            translationX = p.x
            translationY = p.y
        },
        factory = { ctx ->
            // 面从默认 SurfaceView 换成 TextureView（app:surface_type="texture_view"）：
            // 镜像这类视图变换在 SurfaceView 上跨驱动不可靠（合成器旁路），TextureView 是确定性路径
            // （本机 API 30）。XML 定属性，代码里再设一遍做双保险（两处语义一致，不会打架）
            val view = android.view.LayoutInflater.from(ctx)
                .inflate(R.layout.view_player_surface, null, false) as PlayerView
            view.apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(Color.TRANSPARENT)
                setBackgroundColor(Color.TRANSPARENT)
            }
        },
        update = { view -> engine.bind(view) }
    )
}

/** 路由版：/player/{mediaId} —— UI 层只需传 id */
@Composable
fun PlayerScreen(
    mediaId: Long,
    onBack: () -> Unit,
    onCompare: (Long) -> Unit,
    repo: com.wotagei.cam.media.MediaRepo = rememberMediaRepo(),
    ops: MediaOps = rememberMediaOps()
) {
    val clip by repo.clipById(mediaId).collectState(null)
    val c = clip
    if (c == null) {
        Box(Modifier.fillMaxSize().background(WotaBg), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = WotaAccent)
        }
    } else {
        PlayerScreen(clip = c, onBack = onBack, onCompare = onCompare, ops = ops)
    }
}

/**
 * 单视频播放页（需求 27 行）。
 *
 * 手势：双击启停、双指缩放 1..4x、单击切控件显隐（3s 自动隐藏，DOWN 取消 / UP 重置）；
 * 画面区滑动一律不 seek —— 只有进度条能拖。
 */
@Composable
fun PlayerScreen(
    clip: VideoClip,
    onBack: () -> Unit,
    onCompare: (Long) -> Unit,
    ops: MediaOps = rememberMediaOps()
) {
    val engine = rememberPlayerEngine()
    val scope = rememberCoroutineScope()

    // 光弧修复后台执行器（用户需求第 7 项）：Context 取 applicationContext，避免把 Activity 钉在
    // 一条可能跑几十秒的后台线程上。**只由按钮触发**，这里没有任何自动启动。
    val app = LocalContext.current.applicationContext
    val arcRunner = remember(app) { ArcRepairRunner(app) }
    val arcStatus by arcRunner.status.collectState(ArcRepairStatus.IDLE)
    val arcProgress by arcRunner.progress.collectState(0f)
    var arcMenu by remember { mutableStateOf(false) }
    var arcAnchor by remember { mutableStateOf(androidx.compose.ui.unit.IntRect.Zero) }
    // 抽帧目标帧率：产品口径 = 强制档两枚（24/25），默认 24；源实测帧率由管线自己量（容器会撒谎）
    var arcDstFps by remember { mutableIntStateOf(WotaTiers.REQUIRED_FPS.min()) }
    // 退出页面：把 HandlerThread 收干净，但**正在跑的任务不掐**——shutdown() 会把 cancelRequested
    // 置真并等 3s 收尾，等于丢掉用户已经发起的修复；这种情形下让它跑到结束自行写回相册
    // （run 返回前这条线程不会退，是一次性的、可容忍的驻留）。读 runner.status.value 而非外层
    // 那个 by 值，后者在 onDispose 时已经是组合初值、读到的是陈旧快照。
    DisposableEffect(arcRunner) {
        onDispose { if (arcRunner.status.value != ArcRepairStatus.RUNNING) arcRunner.shutdown() }
    }

    val pos by engine.positionMs.collectState(0L)
    val dur by engine.durationMs.collectState(clip.durationMs)
    val playing by engine.isPlaying.collectState(false)
    val state by engine.playbackState.collectState(Player.STATE_IDLE)
    val ab by engine.ab.collectState(AbRange())
    // 倍速弹窗改成就近弹：量住底栏最右那颗胶囊，弹窗跟着它走，不再手算底栏高度
    var speedAnchor by remember { mutableStateOf(androidx.compose.ui.unit.IntRect.Zero) }
    val speed by engine.speed.collectState(PlayerSpeedTiers.DEFAULT)
    val loop by engine.loopModeState.collectState(LoopMode.OFF)
    val fps by engine.fps.collectState(25f)
    val playError by engine.error.collectState(false)

    var controlsVisible by remember { mutableStateOf(true) }
    var banner by remember { mutableStateOf<Int?>(null) }
    val zoom = remember { mutableStateOf(1f) }
    // 双指平移（2026-10-04）：与缩放同一手势，域由 pinchZoom 内 clampPan 夹住，捏回 1x 自动归零
    val pan = remember { mutableStateOf(Offset.Zero) }
    var dragFrac by remember { mutableStateOf<Float?>(null) }
    var tagDialog by remember { mutableStateOf(false) }
    var purgeDialog by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    // 水平镜像（10-01 修订）：练习照镜用的会话态，不持久化——要不要记住偏好等用户提了再做
    var mirrored by remember { mutableStateOf(false) }

    // 掐头去尾剪辑导出（2026-10-04 方向 3）：区间复用底栏 A/B 标记，不引入第二套设点手势；
    // 执行走 ClipExporter 无损拷贝。进度/取消都是会话态。页面退出时组合级 scope 取消，
    // 导出协程随之取消并删 pending 记录，不留半截文件
    val clipExporter = remember(app) { ClipExporter(app) }
    val clipRepo = rememberMediaRepo()
    var clipMenu by remember { mutableStateOf(false) }
    var clipAnchor by remember { mutableStateOf(androidx.compose.ui.unit.IntRect.Zero) }
    var clipExporting by remember { mutableStateOf(false) }
    var clipProgress by remember { mutableStateOf(0f) }
    var clipJob by remember { mutableStateOf<Job?>(null) }

    // 控件栏没有任何自动隐藏：显隐只由画面单击决定，静置、播放中、拖动进度、帧步进都不会让它消失
    val tapArbiter = remember { TapArbiter(DOUBLE_TAP_WINDOW_MS) }
    var toggleJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(tapArbiter) { onDispose { toggleJob?.cancel() } }

    LaunchedEffect(clip.uri) {
        engine.attach(clip.uri)
        engine.softPause(false)
    }
    DisposableEffect(ops) {
        ops.onMessage = { res -> banner = res }
        onDispose { ops.onMessage = null }
    }
    LaunchedEffect(banner) {
        if (banner != null) {
            delay(2600)
            banner = null
        }
    }
    LaunchedEffect(playError) {
        if (playError) banner = R.string.player_error
    }
    // 后台修复的终态提示走现成反馈条（2.6s 自动隐）；进行中的进度另给一行小字，见下面 Box。
    // NO_NEED（源实测帧率不高于目标，没有帧可抽）不是故障，给专门文案避免吓到用户
    LaunchedEffect(arcStatus) {
        when (arcStatus) {
            ArcRepairStatus.DONE -> banner = R.string.player_arc_done
            ArcRepairStatus.FAILED ->
                banner = if (arcRunner.result.value?.error == ArcRepairError.NO_NEED) {
                    R.string.player_arc_no_need
                } else {
                    R.string.player_arc_failed
                }
            else -> Unit
        }
    }

    val duration = if (dur > 0L) dur else clip.durationMs
    val fraction = dragFrac ?: if (duration > 0L) (pos.toFloat() / duration.toFloat()) else 0f
    val aFrac = if (ab.aMs >= 0 && duration > 0) ab.aMs.toFloat() / duration.toFloat() else null
    val bFrac = if (ab.bMs >= 0 && duration > 0) ab.bMs.toFloat() / duration.toFloat() else null

    // 屏上没有 M3 Surface/Scaffold 兜底，环境色必须在根上给一次，否则裸 Text/Icon 落回黑色
    CompositionLocalProvider(LocalContentColor provides WotaText) {
        Box(Modifier.fillMaxSize().background(WotaBg)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pinchZoom(zoom, pan)
                    .pointerInput(Unit) {
                        // 只注册 onTap：双击由 TapArbiter 自己判，避免库的延迟语义与挂起定时器打架
                        detectTapGestures(onTap = {
                            val now = SystemClock.uptimeMillis()
                            toggleJob?.cancel()
                            if (tapArbiter.onPointerUp(now) == TapArbiter.Decision.PlayPause) {
                                engine.softPause(playing)
                            } else {
                                toggleJob = scope.launch {
                                    delay(DOUBLE_TAP_WINDOW_MS)
                                    if (tapArbiter.onToggleDue(SystemClock.uptimeMillis())) {
                                        controlsVisible = !controlsVisible
                                    }
                                }
                            }
                        })
                    }
            ) {
                WotaPlayerSurface(engine = engine, modifier = Modifier.fillMaxSize(), scale = zoom.value, pan = pan.value, mirror = mirrored)
                // 转圈只代表"还没画面"：IDLE（未起播）与 BUFFERING（缓冲中）。
                // STATE_ENDED 是播完停在末帧，不是加载——判据漏了它就会在播完那一刻
                // 凭空转圈且不消失（用户 2026-10-01 反馈的"播完后出现加载动画"即此）；
                // 重播由 [PlayerEngine.softPause] 的 seek(0) 处理，与这枚圈无关
                if (state != Player.STATE_READY && state != Player.STATE_ENDED) {
                    CircularProgressIndicator(
                        Modifier.align(Alignment.Center).size(34.dp),
                        color = WotaAccent,
                        strokeWidth = 3.dp
                    )
                }
            }

            // 双击显隐控件是播放页最高频的一次变化，整块硬切最刺眼：顶栏从上方落、底栏从下方升，
            // 两条 AnimatedVisibility 互不干涉，因为它们的 align 各自挂在 Box 上
            val cm = LocalMotion.current
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(cm.float) + slideInVertically(cm.offset) { -it },
                exit = fadeOut(cm.float) + slideOutVertically(cm.offset) { -it },
                modifier = Modifier.align(Alignment.TopStart)
            ) {
                PlayerTopBar(
                    modifier = Modifier,
                    clip = clip,
                    onBack = onBack,
                    onLike = { ops.setLike(clip, !clip.liked) },
                    onTag = { tagDialog = true },
                    onShare = { ops.share(listOf(clip)) },
                    onTrash = {
                        if (clip.isTrashed) ops.restoreFromTrash(listOf(clip)) else ops.moveToTrash(listOf(clip))
                    },
                    onPurge = { purgeDialog = true },
                    onCompare = { onCompare(clip.id) }
                )
            }

            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(cm.float) + slideInVertically(cm.offset) { it },
                exit = fadeOut(cm.float) + slideOutVertically(cm.offset) { it },
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Column(
                    Modifier
                        .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout))
                        .background(AcrylicScrim)
                        .padding(bottom = 2.dp)
                ) {
                    val timeStyle = MonoStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp)
                    ) {
                        Text(formatDuration(pos), style = timeStyle, color = WotaText)
                        WotaSeekBar(
                            fraction = fraction,
                            aFraction = aFrac,
                            bFraction = bFrac,
                            onDrag = { dragFrac = it },
                            onDragEnd = { frac ->
                                engine.seekTo((frac * duration).toLong())
                                dragFrac = null
                            },
                            modifier = Modifier.weight(1f)
                        )
                        Text(formatDuration(duration), style = timeStyle, color = WotaTextDim)
                    }

                    // 一行装下全部控件（用户 2026-09-28：三行太吃画面）。倍速钉在最右，
                    // 左边那组在窄屏上横向可滚，不会因为并排而被裁掉
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())
                        ) {
                            BarIconSlot(Icons.Outlined.SkipPrevious, stringResource(R.string.player_step_back)) {
                                engine.stepFrame(false)
                            }
                            BarIconSlot(
                                image = if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                description = stringResource(R.string.player_play_pause),
                                accent = true,
                                size = PlayIconSize,
                                glyph = PlayGlyphSize
                            ) { engine.softPause(playing) }
                            BarIconSlot(Icons.Outlined.SkipNext, stringResource(R.string.player_step_fwd)) {
                                engine.stepFrame(true)
                            }
                            Text(
                                stringResource(fpsStepLabelRes(fps), fps),
                                style = MaterialTheme.typography.labelSmall,
                                color = WotaTextDim,
                                maxLines = 1,
                                modifier = Modifier.padding(start = 2.dp)
                            )
                            AbRow(
                                ab = ab,
                                onA = { engine.markA() },
                                onB = { if (!engine.markB()) banner = R.string.player_ab_invalid },
                                onLoop = { if (!engine.toggleAbLoop()) banner = R.string.player_ab_invalid },
                                onClear = { engine.clearAb() }
                            )
                            Spacer(Modifier.width(4.dp))
                            WotaChip(
                                label = stringResource(R.string.player_loop_one),
                                selected = loop == LoopMode.ONE,
                                onClick = {
                                    engine.loopMode = if (loop == LoopMode.ONE) LoopMode.OFF else LoopMode.ONE
                                }
                            )
                            // 环形箭头区分「整片循环 / 单曲循环」，chip 只有文案表达不了这个差别
                            Icon(
                                if (loop == LoopMode.ONE) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat,
                                contentDescription = stringResource(R.string.player_loop_one),
                                tint = if (loop == LoopMode.ONE) WotaColor.accent else WotaTextDim,
                                modifier = Modifier.size(16.dp)
                            )
                            // 水平镜像（10-01 修订）：练习动作时照镜看，选中=accent 蓝底白线
                            BarIconSlot(
                                Icons.Outlined.Flip,
                                stringResource(R.string.player_mirror),
                                accent = mirrored
                            ) { mirrored = !mirrored }
                            // 光弧修复入口（用户需求第 7 项）：默认关闭、绝不自动跑；点开才在弹层里
                            // 二选一（GPU/CPU）起后台任务。进行中亮 accent，给一个"在忙"的静态记号，
                            // 与顶栏收藏/删除的选中语义同一套（不是小字色，不受 accent 对比度约束）。
                            // BarIconSlot 不收 modifier，套一层 Box 只为挂 pillAnchor 量锚点。
                            Box(Modifier.pillAnchor { arcAnchor = it }) {
                                BarIconSlot(
                                    Icons.Outlined.AutoFixHigh,
                                    stringResource(R.string.player_arc_repair),
                                    accent = arcStatus == ArcRepairStatus.RUNNING
                                ) { arcMenu = !arcMenu }
                            }
                            // 掐头去尾导出入口（2026-10-04 方向 3）：区间=A/B 标记。导出中亮 accent，
                            // 与光弧的"在忙"记号同一套语义；面板只承担确认与进度，设点仍走 A/B
                            Box(Modifier.pillAnchor { clipAnchor = it }) {
                                BarIconSlot(
                                    Icons.Outlined.ContentCut,
                                    stringResource(R.string.player_clip),
                                    accent = clipExporting
                                ) { clipMenu = !clipMenu }
                            }
                        }
                        // 倍速永远在最右：它是这一行里唯一带弹层的入口，弹窗就锚在这颗上面
                        WotaChip(
                            label = stringResource(R.string.player_speed_current, stringResource(speedLabelRes(speed))),
                            selected = speed != PlayerSpeedTiers.DEFAULT,
                            modifier = Modifier.pillAnchor { rect -> speedAnchor = rect },
                            onClick = { speedMenu = !speedMenu }
                        )
                    }
                }
            }

            // 控件隐藏时只保留一个退出按钮，避免卡在播放页；和控件条交错淡入淡出，中间不留空档
            AnimatedVisibility(
                visible = !controlsVisible,
                enter = fadeIn(cm.float),
                exit = fadeOut(cm.float),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
            ) {
                WotaIconButton(
                    image = Icons.Outlined.ArrowBack,
                    description = stringResource(R.string.gallery_desc_back),
                    onClick = onBack,
                    modifier = Modifier.padding(6.dp),
                    size = TopBarIconSize,
                    glyph = TopBarGlyphSize
                )
            }

            if (controlsVisible && speedMenu) {
                SpeedTierPopup(
                    anchor = speedAnchor,
                    current = speed,
                    onPick = { tier ->
                        engine.setSpeed(tier)
                        speedMenu = false
                    },
                    onDismiss = { speedMenu = false }
                )
            }
            // 剪辑导出就近弹层（2026-10-04 方向 3）：锚在底栏剪辑钮上，导出中重开面板可看进度
            if (controlsVisible && clipMenu) {
                ClipExportPopup(
                    anchor = clipAnchor,
                    aMs = ab.aMs,
                    bMs = ab.bMs,
                    durationMs = duration,
                    exporting = clipExporting,
                    progress = clipProgress,
                    onExport = {
                        engine.softPause(true)
                        clipMenu = false
                        clipExporting = true
                        clipProgress = 0f
                        clipJob = scope.launch {
                            try {
                                // 区间在起跑那一刻定版：导出期间用户再动 A/B 不影响这一轮
                                when (val r = clipExporter.export(clip, ab.aMs, ab.bMs) { p -> clipProgress = p }) {
                                    is ClipResult.Done -> {
                                        banner = R.string.player_clip_done
                                        clipRepo.invalidate()
                                    }
                                    is ClipResult.Fail ->
                                        banner = if (r.reason == ClipError.RANGE) {
                                            R.string.player_clip_too_short
                                        } else {
                                            R.string.player_clip_failed
                                        }
                                }
                            } finally {
                                clipExporting = false
                                clipJob = null
                            }
                        }
                    },
                    onCancel = {
                        clipJob?.cancel()
                        clipJob = null
                    },
                    onDismiss = { clipMenu = false }
                )
            }

            if (controlsVisible && arcMenu) {
                ArcRepairPopup(
                    anchor = arcAnchor,
                    dstFps = arcDstFps,
                    onPickFps = { arcDstFps = it },
                    onPick = { useGpu ->
                        arcMenu = false
                        // 重复启动保护：管线本身同一时刻只跑一条（runner.busy 也会兜一层），
                        // 这里给个明确提示，避免用户以为点第二下没反应。DONE/FAILED 不算在忙，
                        // 允许接着再修一条（runner.start 会自行重置 result/progress）。
                        if (arcStatus == ArcRepairStatus.RUNNING) {
                            banner = R.string.player_arc_busy
                        } else {
                            arcRunner.start(clip.uri, useGpu, arcDstFps)
                        }
                    },
                    onDismiss = { arcMenu = false }
                )
            }

            // 反馈条从底边浮起再淡出；文案钉在快照上，否则退场那帧会闪成空条
            val msg = banner
            var msgSnapshot by remember { mutableStateOf<Int?>(null) }
            if (msg != null) msgSnapshot = msg
            AnimatedVisibility(
                visible = msg != null,
                enter = fadeIn(cm.float) + slideInVertically(cm.offset) { it },
                exit = fadeOut(cm.float) + slideOutVertically(cm.offset) { it },
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Box(
                    Modifier
                        .padding(bottom = 120.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(WotaSurface)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) { Text(stringResource(msgSnapshot ?: 0), style = MaterialTheme.typography.bodyMedium) }
            }
            // 后台修复进行中的轻量反馈：一行等宽小字，不占控件栏、不挡画面、与控制栏显隐无关。
            // 位置压在反馈条上方（banner 走 120dp），两条同时在位时不会互相盖住。
            if (arcStatus == ArcRepairStatus.RUNNING) {
                Text(
                    text = stringResource(R.string.player_arc_running, (arcProgress * 100f).toInt()),
                    style = MonoStyle,
                    color = WotaText,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 168.dp)
                )
            }
            // 偏离 1x 即显示：缩小也是合法观感（下限 0.1x），缩着却没倍率读数等于盲调
            if (zoom.value > 1.01f || zoom.value < 0.99f) {
                Text(
                    text = stringResource(R.string.player_zoom, zoom.value),
                    style = MonoStyle,
                    color = WotaTextDim,
                    modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)).padding(top = 56.dp)
                )
            }
        }
    }

    if (tagDialog) MediaTagDialog(clip = clip, ops = ops, onDismiss = { tagDialog = false })

    if (purgeDialog) {
        AlertDialog(
            onDismissRequest = { purgeDialog = false },
            title = { Text(stringResource(R.string.gallery_purge_title)) },
            text = { Text(stringResource(R.string.gallery_purge_body)) },
            confirmButton = {
                TextButton(onClick = {
                    purgeDialog = false
                    // 时序定版（删文件 → 250ms → pop → 900ms → 刷列表）在 MediaActions.finishDelete 内。
                    // 「刷列表」= releaseListRefresh() 里的 invalidate，所以这里不必再传 refresh
                    // （媒体库内两处批量删除传的 refresh = repo.invalidate()，与开闸那一下重复，
                    // 属双保险，不是这条链成立的必要条件）。
                    // 也**别**把 ops.deleteForever 挪到本屏的 scope 之外照旧想、或把收尾改回可取消：
                    // onBack 一出栈，本屏 rememberCoroutineScope 连同收尾的 delay 一起被取消，
                    // 少了 runDeleteFinish 里的 NonCancellable，MediaRepo 的刷新闸门就永久停在 true ——
                    // 2026-10-02 真机两轮复现的「删完返回媒体库全页签空列表、只能杀进程恢复」就是这个。
                    ops.deleteForever(listOf(clip), pop = onBack)
                }) { Text(stringResource(R.string.ok), color = WotaRec) }
            },
            dismissButton = { TextButton(onClick = { purgeDialog = false }) { Text(stringResource(R.string.cancel)) } },
            containerColor = WotaSurface
        )
    }
}

/**
 * AB 段循环四按钮：A / B / 循环 / 清除（激活蓝底白字，accent 见 Tokens —— ）。
 * 四个动作散排时读作 4 个独立控件，套一层卡片让它们成一个控件组。
 */
@Composable
internal fun AbRow(
    ab: AbRange,
    onA: () -> Unit,
    onB: () -> Unit,
    onLoop: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        modifier = Modifier
            .wotaCard(WotaShape.medium)
            .padding(horizontal = 3.dp, vertical = 1.dp)
    ) {
        AbPill(stringResource(R.string.player_ab_a), ab.aMs >= 0, onA)
        AbPill(stringResource(R.string.player_ab_b), ab.bMs >= 0, onB)
        AbPill(stringResource(R.string.player_ab_loop), ab.loopEnabled, onLoop)
        AbPill(stringResource(R.string.player_ab_clear), false, onClear)
    }
}

/**
 * 组内迷你胶囊沿用自绘：WotaChip 的 12/6dp 内边距 ×4 枚会把底栏第二行撑溢出（窄屏真机量过），
 * 这里只借它的配色语义（选中 accentSurface 实底 + onAccent 字——labelMedium 白字小字的承载面，
 * 白字对 #007DFF(accent) 只有 3.91:1 不过 AA 正文，对 #0A59F7 5.55:1 过）。
 */
@Composable
private fun AbPill(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        // 正文不写死字号，字号一律由 MaterialTheme.typography 给
        style = MaterialTheme.typography.labelMedium,
        color = if (active) WotaColor.onAccent else WotaColor.textMid,
        maxLines = 1,
        modifier = Modifier
            .clip(WotaShape.pill)
            .background(if (active) WotaColor.accentSurface else ComposeColor.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

/**
 * 顶栏/底栏图标位：直接复用录制页那枚 [WotaIconButton]（hudScrim 圆卡 + 高光边 + 按压缩放）。
 * 尺寸仍要显式压到 30dp：M3 1.1.2 的 IconButton 会把布局撑到 ≥48dp（minimumInteractiveComponentSize
 * 不受父约束上限压缩），七枚就把文件名挤成「…」。
 */
@Composable
private fun BarIconSlot(
    image: ImageVector,
    description: String,
    accent: Boolean = false,
    tint: ComposeColor? = null,
    size: Dp = TopBarIconSize,
    glyph: Dp = TopBarGlyphSize,
    onClick: () -> Unit
) {
    if (tint == null) {
        WotaIconButton(
            image = image,
            description = description,
            onClick = onClick,
            selected = accent,
            size = size,
            glyph = glyph
        )
    } else {
        // WotaIconButton 的选中态只有 accent，收藏红/彻底删除红这类专色沿用同一枚底自己画
        Box(
            Modifier.size(size).wotaCard(CircleShape).clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(image, contentDescription = description, tint = tint, modifier = Modifier.size(glyph))
        }
    }
}

/**
 * 倍速选择弹窗：底栏窄，五档收进来。
 *
 * 2026-09-28 起改走 [WotaPillPopup] 就近弹在底栏最右那颗倍速胶囊旁边 —— 原来它浮在整条底栏上方，
 * 靠一个手算的 88dp 让位，底栏行数一变就对不上；壳、点外关闭、退场动画也都不用自己再写一份。
 * 提成 internal：对比播放页（10-01 沉浸式改造）的倍速入口复用同一枚弹层，五档与壳不各抄一份。
 */
@Composable
internal fun SpeedTierPopup(
    anchor: androidx.compose.ui.unit.IntRect,
    current: Float,
    onPick: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    WotaPillPopup(anchor, onDismiss, title = stringResource(R.string.player_speed_title)) {
        Column(Modifier.width(SPEED_POPUP_PANEL_WIDTH)) {
            PlayerSpeedTiers.TIERS.forEach { tier ->
                val picked = tier == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(tier) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(speedLabelRes(tier)),
                        style = MaterialTheme.typography.labelMedium,
                        // 选中态不用 accent 当文字色：accent 换 HarmonyOS 蓝（2026-10-01）后
                        // 蓝字压 WotaSurface 小字 CR≈3.37 过不了正文档 4.5；选中语义由旁边那枚
                        // accent 对勾承担（图形件过 3.0 大字/图形档即可，contrast-audit 实测）
                        color = WotaText,
                        modifier = Modifier.weight(1f)
                    )
                    if (picked) {
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = null,
                            tint = WotaColor.accent,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 剪辑导出就近弹层（2026-10-04 方向 3）：区间就是底栏 A/B 标记，这里只做**确认与进度**——
 * 入点会自动向前对齐关键帧（无损剪的物理边界），读数行把它讲在明处。未圈满 A/B 时只给引导文案；
 * 导出中换进度条与取消钮，重开面板同一状态（进度是共享 State，不是弹层的局部变量）。
 */
@Composable
private fun ClipExportPopup(
    anchor: androidx.compose.ui.unit.IntRect,
    aMs: Long,
    bMs: Long,
    durationMs: Long,
    exporting: Boolean,
    progress: Float,
    onExport: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    val range = ClipRule.normalize(aMs, bMs, durationMs)
    WotaPillPopup(
        anchor = anchor,
        onDismiss = onDismiss,
        modifier = Modifier.width(CLIP_POPUP_PANEL_WIDTH),
        title = stringResource(R.string.player_clip_title)
    ) {
        if (range == null) {
            Text(
                stringResource(R.string.player_clip_need_ab),
                style = MaterialTheme.typography.bodySmall,
                color = WotaTextDim
            )
        } else {
            Text(
                stringResource(R.string.player_clip_in, formatDuration(range.first)),
                style = MaterialTheme.typography.bodySmall,
                color = WotaText
            )
            Text(
                stringResource(R.string.player_clip_out, formatDuration(range.second)),
                style = MaterialTheme.typography.bodySmall,
                color = WotaText
            )
            Text(
                stringResource(
                    R.string.player_clip_keep,
                    formatDuration(range.second - range.first),
                    formatDuration(durationMs)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = WotaTextDim
            )
            if (exporting) {
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth(),
                    color = WotaColor.accent,
                    trackColor = WotaColor.outline
                )
                WotaChip(
                    label = stringResource(R.string.player_clip_cancel),
                    selected = false,
                    onClick = onCancel
                )
            } else {
                WotaChip(
                    label = stringResource(R.string.player_clip_export),
                    selected = true,
                    onClick = onExport
                )
            }
        }
    }
}

/**
 * 光弧修复的方式选择弹层（用户需求第 7 项，v2 抽帧+补弧口径）：写法照 [SpeedTierPopup]——
 * 就近弹在底栏那枚修复钮旁边。
 *
 * 「目标帧率」两档是**选中态**（带对勾、点选切换）；下面两行是**二选一的动作**，不给对勾、
 * 点完即关并在上层起后台任务。说明小字讲清抽帧+补弧语义与"不改原片"。
 */
@Composable
private fun ArcRepairPopup(
    anchor: androidx.compose.ui.unit.IntRect,
    dstFps: Int,
    onPickFps: (Int) -> Unit,
    onPick: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    WotaPillPopup(anchor, onDismiss, title = stringResource(R.string.player_arc_repair)) {
        Column(Modifier.width(ARC_POPUP_PANEL_WIDTH)) {
            // 目标帧率：产品口径就是强制档那两枚（REQUIRED_FPS），不是任意档位表
            Text(
                stringResource(R.string.player_arc_target),
                style = MaterialTheme.typography.labelSmall,
                color = WotaTextDim,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
            )
            WotaTiers.REQUIRED_FPS.sorted().forEach { fps ->
                Text(
                    text = (if (fps == dstFps) "✓ " else "") + stringResource(R.string.player_arc_fps, fps),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (fps == dstFps) WotaAccent else WotaText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPickFps(fps) }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
            // 行尾留空：一挂对勾就会被读成"当前选中项"，而这里点哪个是发起动作
            Text(
                stringResource(R.string.player_arc_gpu),
                style = MaterialTheme.typography.labelMedium,
                color = WotaText,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(true) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
            Text(
                stringResource(R.string.player_arc_cpu),
                style = MaterialTheme.typography.labelMedium,
                color = WotaText,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(false) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
            Text(
                stringResource(R.string.player_arc_note),
                style = MaterialTheme.typography.labelSmall,
                color = WotaTextDim,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun PlayerTopBar(
    modifier: Modifier = Modifier,
    clip: VideoClip,
    onBack: () -> Unit,
    onLike: () -> Unit,
    onTag: () -> Unit,
    onShare: () -> Unit,
    onTrash: () -> Unit,
    onPurge: () -> Unit,
    onCompare: () -> Unit
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(AcrylicScrim)
            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 顶栏 7 枚圆钮压到 30dp：M3 IconButton 强制 ≥48dp，七个就把文件名挤成「…」
        BarIconSlot(Icons.Outlined.ArrowBack, stringResource(R.string.gallery_desc_back), onClick = onBack)
        Text(
            clip.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
        )
        BarIconSlot(Icons.Outlined.CompareArrows, stringResource(R.string.gallery_menu_compare), onClick = onCompare)
        BarIconSlot(
            if (clip.liked) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
            // 图标已经分状态了，标签也必须跟着分：原来恒报「已收藏」，未收藏的成片读屏也说成已收藏
            stringResource(if (clip.liked) R.string.gallery_desc_liked else R.string.gallery_desc_unliked),
            tint = if (clip.liked) WotaRec else WotaText,
            onClick = onLike
        )
        BarIconSlot(Icons.Outlined.Tune, stringResource(R.string.gallery_menu_tags), onClick = onTag)
        BarIconSlot(Icons.Outlined.Share, stringResource(R.string.gallery_menu_share), onClick = onShare)
        if (clip.isTrashed) {
            BarIconSlot(Icons.Outlined.Restore, stringResource(R.string.gallery_menu_restore), onClick = onTrash)
        } else {
            BarIconSlot(Icons.Outlined.Delete, stringResource(R.string.gallery_menu_trash), onClick = onTrash)
        }
        BarIconSlot(
            Icons.Outlined.DeleteForever,
            stringResource(R.string.gallery_menu_purge),
            tint = WotaRec,
            onClick = onPurge
        )
    }
}

// region 手势与进度条

/**
 * 平移夹取：平移域=仅放大态，逐轴 `±视口×(s−1)/2`——放大后的画面恒盖住视口，拖不出黑边；
 * s≤1 时域为 0（自然禁用），捏回 1x 的过程中平移随夹取连续归零，无需专门复位动作。
 * half≤0 直接给 0：s<1 时 `-half > half` 会让 coerceIn 域倒置抛异常，视口未量出（0）同理。
 */
internal fun clampPan(pan: Offset, scale: Float, viewport: Size): Offset =
    Offset(
        clampPanAxis(pan.x, viewport.width * (scale - 1f) / 2f),
        clampPanAxis(pan.y, viewport.height * (scale - 1f) / 2f)
    )

private fun clampPanAxis(v: Float, half: Float): Float = if (half <= 0f) 0f else v.coerceIn(-half, half)

/** 双指缩放 0.1..4x + 双指平移（用户 2026-10-04 需求）：缩放走双指张合、平移走双指整体位移，
 *  同一手势并行；平移只在第二指按下后才开始消费——**单指行为零变化**（单击显隐、双击启停、
 *  画面滑动不 seek 均不受影响）。
 *  提成 internal：对比播放页（#4，用户 2026-09-30 反馈「对比播放没有双指缩放」）复用同一份
 *  手势与同一档上下限，两边各抄一份迟早各改各的。[panState] 无默认值必传：漏挂让编译期拦截，
 *  避免出现"能缩放不能拖"的半实现调用点。 */
internal fun Modifier.pinchZoom(
    zoomState: MutableState<Float>,
    panState: MutableState<Offset>,
    min: Float = 0.1f,
    max: Float = 4f
): Modifier =
    pointerInput(min, max) {
        awaitEachGesture {
            var baseDist = 0f
            var baseZoom = zoomState.value
            var baseCentroid = Offset.Zero
            var basePan = panState.value
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.filter { it.pressed }
                when {
                    pressed.size >= 2 -> {
                        val d = (pressed[0].position - pressed[1].position).getDistance()
                        val c = (pressed[0].position + pressed[1].position) / 2f
                        if (baseDist <= 0f) {
                            // 第二指落下那一刻记基准：距离定缩放、质心定平移、平移取当下值
                            baseDist = d
                            baseZoom = zoomState.value
                            baseCentroid = c
                            basePan = panState.value
                            // 落下即消费本枚事件：静置双指 touch（按下→抬起零移动）的 down/up
                            // 若全程无 consumed 变更，Main pass 的 detectTapGestures 会把它判成
                            // 一次单击——幽灵 tap（控制层误显隐 / 经 TapArbiter 误启停）。
                            // 此处消费后 tap 观察在该事件即取消，与"有移动时首个 move 消费"
                            // 的取消时点同效；后续 move 的消费仍由 d>0f 分支承担，逻辑不变；
                            // 第一指的 up（pressed.size 回落 1，走 else 分支）不消费也不受影响
                            // ——tap 早在本枚已被判死。单指路径不经本分支，零影响。
                            // 防退化点：删掉这行 consume 不会有任何 JVM 测试变红——指针消费
                            // 时序属设备输入路径，JVM 单测桩测不到，只能真机验收实证：
                            // 静置双指按下即抬起，控制层显隐不得切换、播放不得启停（单播放页
                            // 与对比页两处 pinchZoom 调用点各验一遍）。
                            event.changes.forEach { it.consume() }
                        } else if (d > 0f) {
                            val scale = (baseZoom * (d / baseDist)).coerceIn(min, max)
                            zoomState.value = scale
                            panState.value = clampPan(
                                basePan + (c - baseCentroid),
                                scale,
                                Size(size.width.toFloat(), size.height.toFloat())
                            )
                            event.changes.forEach { it.consume() }
                        }
                    }
                    pressed.isEmpty() -> {
                        baseDist = 0f
                        break
                    }
                    else -> baseDist = 0f
                }
            }
        }
    }

/**
 * 进度条：唯一可拖动 seek 的入口，轨道上画 A/B 标记。
 * 拖动中本地值覆盖轮询值，松手才 seek。
 *
 * 样式来源=用户 2026-10-01 视觉规格：已播放 [WotaColor.sliderFill]（白）、未播放轨道
 * [WotaColor.sliderRest]（#333）、thumb 是直径 6px@2x 的白色微小圆点、拖拽时圆点放大——
 * 放大走 if 跳变，颜色/尺寸动画被 MotionHygieneTest 守卫拦，不做动画。
 * 规格里「thumb 散发微光」一条不做：本工程设计纪律禁投影/发光（见 Tokens.kt 头注）。
 */
@Composable
fun WotaSeekBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    aFraction: Float? = null,
    bFraction: Float? = null,
    enabled: Boolean = true,
    onDrag: (Float) -> Unit = {},
    onDragEnd: (Float) -> Unit = {}
) {
    var dragging by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(enabled) {
                awaitEachGesture {
                    if (!enabled) return@awaitEachGesture
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var last = fracOf(down.position.x, size.width)
                    down.consume()
                    dragging = true
                    onDrag(last)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.pressed } ?: break
                        last = fracOf(change.position.x, size.width)
                        change.consume()
                        onDrag(last)
                    }
                    dragging = false
                    onDragEnd(last)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxWidth().height(20.dp)) {
            val cy = size.height / 2f
            val w = size.width
            val f = fraction.coerceIn(0f, 1f)
            val tick = 7.dp.toPx()
            drawLine(WotaColor.sliderRest, Offset(0f, cy), Offset(w, cy), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            drawLine(WotaColor.sliderFill, Offset(0f, cy), Offset(w * f, cy), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            aFraction?.let {
                drawLine(WotaColor.textMid, Offset(w * it, cy - tick), Offset(w * it, cy + tick), strokeWidth = 2.dp.toPx())
            }
            bFraction?.let {
                drawLine(WotaColor.textMid, Offset(w * it, cy - tick), Offset(w * it, cy + tick), strokeWidth = 2.dp.toPx())
            }
            // 微光规格按工程禁投影纪律不做，2026-10-01
            drawCircle(WotaColor.sliderFill, radius = (if (dragging) 5.dp else 3.dp).toPx(), center = Offset(w * f, cy))
        }
    }
}

private fun fracOf(x: Float, widthPx: Int): Float =
    if (widthPx <= 0) 0f else (x / widthPx.toFloat()).coerceIn(0f, 1f)

// endregion

@Composable
private fun <T> Flow<T>.collectState(initial: T): State<T> = collectAsState(initial)
