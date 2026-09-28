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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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
import com.wotagei.cam.media.MediaOps
import com.wotagei.cam.media.MediaTagDialog
import com.wotagei.cam.media.VideoClip
import com.wotagei.cam.media.formatDuration
import com.wotagei.cam.media.rememberMediaOps
import com.wotagei.cam.media.rememberMediaRepo
import com.wotagei.cam.ui.anim.LocalMotion
import com.wotagei.cam.ui.design.WotaChip
import com.wotagei.cam.ui.design.WotaColor
import com.wotagei.cam.ui.design.WotaIconButton
import com.wotagei.cam.ui.design.WotaShape
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

/** 双击判定窗口：单击要等满这么久才落地显隐，否则「双击启停」会顺带闪一下控件栏 */
private const val DOUBLE_TAP_WINDOW_MS = 280L

/** 倍速弹窗浮在底栏上方，让开的距离是底栏三行压缩后的近似高度（导航栏与挖孔另有 windowInsetsPadding 让开；第三行换 WotaChip 后高了 4dp） */
private val SPEED_POPUP_BOTTOM_GAP: Dp = 88.dp

/** 弹窗定宽：不固定的话每档按自身内容包裹，点击区参差 */
private val SPEED_POPUP_PANEL_WIDTH: Dp = 148.dp

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
 * 播放画面：SurfaceView + 缩放变换（不开 media3 自带 controller，控件全部自绘）。
 *
 * Media3 1.1.1 的 PlayerView 只公开 resizeMode（SURFACE_TYPE_* 与 keepAspectRatio 均非公开，
 * surface 类型只能通过 XML 的 app:surface_type 指定，默认已是 surface_view）。
 * RESIZE_MODE_FIT 等价于旧的「保持宽高比」，超出部分由外层 graphicsLayer 缩放。
 */
@Composable
fun WotaPlayerSurface(
    engine: PlayerEngine,
    modifier: Modifier = Modifier,
    scale: Float = 1f
) {
    AndroidView(
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        factory = { ctx ->
            PlayerView(ctx).apply {
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

    val pos by engine.positionMs.collectState(0L)
    val dur by engine.durationMs.collectState(clip.durationMs)
    val playing by engine.isPlaying.collectState(false)
    val state by engine.playbackState.collectState(Player.STATE_IDLE)
    val ab by engine.ab.collectState(AbRange())
    val speed by engine.speed.collectState(PlayerSpeedTiers.DEFAULT)
    val loop by engine.loopModeState.collectState(LoopMode.OFF)
    val fps by engine.fps.collectState(25f)
    val playError by engine.error.collectState(false)

    var controlsVisible by remember { mutableStateOf(true) }
    var banner by remember { mutableStateOf<Int?>(null) }
    val zoom = remember { mutableStateOf(1f) }
    var dragFrac by remember { mutableStateOf<Float?>(null) }
    var tagDialog by remember { mutableStateOf(false) }
    var purgeDialog by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }

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
                    .pinchZoom(zoom)
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
                WotaPlayerSurface(engine = engine, modifier = Modifier.fillMaxSize(), scale = zoom.value)
                if (state != Player.STATE_READY) {
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

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        BarIconSlot(Icons.Filled.SkipPrevious, stringResource(R.string.player_step_back)) {
                            engine.stepFrame(false)
                        }
                        BarIconSlot(
                            image = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            description = stringResource(R.string.player_play_pause),
                            accent = true,
                            size = PlayIconSize,
                            glyph = PlayGlyphSize
                        ) { engine.softPause(playing) }
                        BarIconSlot(Icons.Filled.SkipNext, stringResource(R.string.player_step_fwd)) {
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
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(horizontal = 6.dp)
                    ) {
                        WotaChip(
                            label = stringResource(R.string.player_speed_current, stringResource(speedLabelRes(speed))),
                            selected = speed != PlayerSpeedTiers.DEFAULT,
                            onClick = { speedMenu = !speedMenu }
                        )
                        Spacer(Modifier.weight(1f))
                        WotaChip(
                            label = stringResource(R.string.player_loop_one),
                            selected = loop == LoopMode.ONE,
                            onClick = {
                                engine.loopMode = if (loop == LoopMode.ONE) LoopMode.OFF else LoopMode.ONE
                            }
                        )
                        // 环形箭头区分「整片循环 / 单曲循环」，chip 只有文案表达不了这个差别
                        Icon(
                            if (loop == LoopMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                            contentDescription = stringResource(R.string.player_loop_one),
                            tint = if (loop == LoopMode.ONE) WotaColor.accent else WotaTextDim,
                            modifier = Modifier.size(16.dp)
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
                    image = Icons.Filled.ArrowBack,
                    description = stringResource(R.string.gallery_desc_back),
                    onClick = onBack,
                    modifier = Modifier.padding(6.dp),
                    size = TopBarIconSize,
                    glyph = TopBarGlyphSize
                )
            }

            SpeedTierPopup(
                visible = controlsVisible && speedMenu,
                current = speed,
                onPick = { tier ->
                    engine.setSpeed(tier)
                    speedMenu = false
                },
                onDismiss = { speedMenu = false }
            )

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
            if (zoom.value > 1.01f) {
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
                    // 时序定版（删文件 → 250ms → pop → 900ms → 刷列表）在 MediaActions.finishDelete 内
                    ops.deleteForever(listOf(clip), pop = onBack)
                }) { Text(stringResource(R.string.ok), color = WotaRec) }
            },
            dismissButton = { TextButton(onClick = { purgeDialog = false }) { Text(stringResource(R.string.cancel)) } },
            containerColor = WotaSurface
        )
    }
}

/**
 * AB 段循环四按钮：A / B / 循环 / 清除（激活绿色 —— ）。
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
 * 这里只借它的配色语义（选中 accent 实底 + onAccent 字）。
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
            .background(if (active) WotaColor.accent else ComposeColor.Transparent)
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

/** 倍速选择弹窗：底栏窄，五档收进来；点面板外关闭 */
@Composable
private fun SpeedTierPopup(
    visible: Boolean,
    current: Float,
    onPick: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val motion = LocalMotion.current
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(motion.float),
            exit = fadeOut(motion.float)
        ) {
            Box(Modifier.fillMaxSize().clickable(onClick = onDismiss))
        }
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(motion.float) + slideInVertically(motion.offset) { it },
            exit = fadeOut(motion.float) + slideOutVertically(motion.offset) { it },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout))
                .padding(end = 10.dp, bottom = SPEED_POPUP_BOTTOM_GAP)
        ) {
            Column(
                Modifier
                    .width(SPEED_POPUP_PANEL_WIDTH)
                    .clip(WotaShape.large)
                    // 弹层压在视频上要近实底（同 WotaMenuPopup 的壳）：hudScrim 的 25% 透明在亮画面上读不清
                    .background(WotaColor.surface.copy(alpha = 0.97f))
                    .border(1.dp, WotaColor.acrylicBorder, WotaShape.large)
                    .padding(vertical = 4.dp)
            ) {
                Text(
                    stringResource(R.string.player_speed_title),
                    style = MaterialTheme.typography.labelSmall,
                    color = WotaTextDim,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 3.dp)
                )
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
                            color = if (picked) WotaColor.accent else WotaText,
                            modifier = Modifier.weight(1f)
                        )
                        if (picked) {
                            Icon(
                                Icons.Filled.Check,
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
        BarIconSlot(Icons.Filled.ArrowBack, stringResource(R.string.gallery_desc_back), onClick = onBack)
        Text(
            clip.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
        )
        BarIconSlot(Icons.Filled.CompareArrows, stringResource(R.string.gallery_menu_compare), onClick = onCompare)
        BarIconSlot(
            if (clip.liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            // 图标已经分状态了，标签也必须跟着分：原来恒报「已收藏」，未收藏的成片读屏也说成已收藏
            stringResource(if (clip.liked) R.string.gallery_desc_liked else R.string.gallery_desc_unliked),
            tint = if (clip.liked) WotaRec else WotaText,
            onClick = onLike
        )
        BarIconSlot(Icons.Filled.Tune, stringResource(R.string.gallery_menu_tags), onClick = onTag)
        BarIconSlot(Icons.Filled.Share, stringResource(R.string.gallery_menu_share), onClick = onShare)
        if (clip.isTrashed) {
            BarIconSlot(Icons.Filled.Restore, stringResource(R.string.gallery_menu_restore), onClick = onTrash)
        } else {
            BarIconSlot(Icons.Filled.Delete, stringResource(R.string.gallery_menu_trash), onClick = onTrash)
        }
        BarIconSlot(
            Icons.Filled.DeleteForever,
            stringResource(R.string.gallery_menu_purge),
            tint = WotaRec,
            onClick = onPurge
        )
    }
}

// region 手势与进度条

/** 双指缩放 1..4x：只在第二指按下后才消费，单指滑动不消费（因此画面滑动不会 seek） */
private fun Modifier.pinchZoom(zoomState: MutableState<Float>, min: Float = 1f, max: Float = 4f): Modifier =
    pointerInput(min, max) {
        awaitEachGesture {
            var baseDist = 0f
            var baseZoom = zoomState.value
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.filter { it.pressed }
                when {
                    pressed.size >= 2 -> {
                        val d = (pressed[0].position - pressed[1].position).getDistance()
                        if (baseDist <= 0f) {
                            baseDist = d
                            baseZoom = zoomState.value
                        } else if (d > 0f) {
                            zoomState.value = (baseZoom * (d / baseDist)).coerceIn(min, max)
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
                    onDrag(last)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.pressed } ?: break
                        last = fracOf(change.position.x, size.width)
                        change.consume()
                        onDrag(last)
                    }
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
            drawLine(WotaColor.outline, Offset(0f, cy), Offset(w, cy), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            drawLine(WotaColor.accent, Offset(0f, cy), Offset(w * f, cy), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            aFraction?.let {
                drawLine(WotaColor.textMid, Offset(w * it, cy - tick), Offset(w * it, cy + tick), strokeWidth = 2.dp.toPx())
            }
            bFraction?.let {
                drawLine(WotaColor.textMid, Offset(w * it, cy - tick), Offset(w * it, cy + tick), strokeWidth = 2.dp.toPx())
            }
            drawCircle(WotaColor.textHi, radius = 5.dp.toPx(), center = Offset(w * f, cy))
        }
    }
}

private fun fracOf(x: Float, widthPx: Int): Float =
    if (widthPx <= 0) 0f else (x / widthPx.toFloat()).coerceIn(0f, 1f)

// endregion

@Composable
private fun <T> Flow<T>.collectState(initial: T): State<T> = collectAsState(initial)
