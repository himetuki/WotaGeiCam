package com.wotagei.cam.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wotagei.cam.R
import com.wotagei.cam.media.ClipPickerDialog
import com.wotagei.cam.media.MediaRepo
import com.wotagei.cam.media.VideoClip
import com.wotagei.cam.media.formatDuration
import com.wotagei.cam.media.rememberMediaRepo
import com.wotagei.cam.ui.design.WotaChip
import com.wotagei.cam.ui.design.WotaColor
import com.wotagei.cam.ui.design.WotaIconButton
import com.wotagei.cam.ui.design.WotaShape
import com.wotagei.cam.ui.design.wotaCard
import com.wotagei.cam.ui.theme.AcrylicScrim
import com.wotagei.cam.ui.theme.MonoStyle
import com.wotagei.cam.ui.theme.WotaAccent
import com.wotagei.cam.ui.theme.WotaBg
import com.wotagei.cam.ui.theme.WotaDivider
import com.wotagei.cam.ui.theme.WotaSurface
import com.wotagei.cam.ui.theme.WotaText
import com.wotagei.cam.ui.theme.WotaTextDim
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/** 双引擎偏差检查周期：低频纠偏，每帧硬拉会听得出卡顿 */
private const val RESYNC_INTERVAL_MS = 2_500L

/**
 * 圆钮尺寸与播放页取齐（30/18dp，播放键大一圈），不复用 WotaHit.iconButton(38)：
 * 两屏是同一套控件，尺寸不一致会读作两套东西；且顶栏还有模式胶囊要占宽。
 */
private val BarIconSize = 30.dp
private val BarGlyphSize = 18.dp
private val PlayIconSize = 36.dp
private val PlayGlyphSize = 26.dp

/**
 * 双视频对比同步播放页（需求 27 行原文流程）：
 * 1. 媒体库打开的视频进左；2. 右侧从内置媒体库 / 手机相册再选一个；
 * 3. 同步模式二选一：基于音频自动同步（[AudioSync]）或手动拖左右独立进度条；
 * 4. 两视频下方一条统一进度条 + 统一启停 + AB 循环 + 帧步进/退，统一轴以左视频为基准，右视频按 offsetMs 平移。
 */
@Composable
fun CompareScreen(
    leftMediaId: Long,
    onBack: () -> Unit,
    repo: MediaRepo = rememberMediaRepo()
) {
    val left by repo.clipById(leftMediaId).collectState(null)
    val c = left
    if (c == null) {
        Box(Modifier.fillMaxSize().background(WotaBg), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = WotaAccent)
        }
    } else {
        CompareContent(left = c, onBack = onBack)
    }
}

@Composable
private fun CompareContent(left: VideoClip, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val leftEngine = rememberPlayerEngine()
    val rightEngine = rememberPlayerEngine()
    val launchSync = rememberSyncLauncher()

    var right by remember { mutableStateOf<VideoClip?>(null) }
    var picker by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    val offsetMs = remember { mutableStateOf(0L) }
    var syncing by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf<Int?>(null) }
    var dragUnified by remember { mutableStateOf<Float?>(null) }
    var dragLeft by remember { mutableStateOf<Float?>(null) }
    var dragRight by remember { mutableStateOf<Float?>(null) }

    val leftPos by leftEngine.positionMs.collectState(0L)
    val leftDur by leftEngine.durationMs.collectState(left.durationMs)
    val rightPos by rightEngine.positionMs.collectState(0L)
    val rightDur by rightEngine.durationMs.collectState(0L)
    // 启停态取两引擎并集：只读左引擎会在右引擎自己播完/绕回后把按钮状态显示错
    val leftPlaying by leftEngine.isPlaying.collectState(false)
    val rightPlaying by rightEngine.isPlaying.collectState(false)
    val playing = leftPlaying || rightPlaying
    val ab by leftEngine.ab.collectState(AbRange())
    val speed by leftEngine.speed.collectState(PlayerSpeedTiers.DEFAULT)

    // v0.0.1 固定 50/50，拖动分隔线改比例留到 v0.0.2（05 文档第 6 节第 5 条）
    val split = 0.5f

    LaunchedEffect(left.uri) {
        leftEngine.attach(left.uri)
        leftEngine.loopMode = LoopMode.ONE
    }
    val r = right
    LaunchedEffect(r?.uri) {
        val clip = r ?: return@LaunchedEffect
        rightEngine.attach(clip.uri)
        // 右引擎不开 REPEAT_ONE：它会先于左视频绕回导致错位，绕回改由下面的低频纠偏跟随左轴
        rightEngine.loopMode = LoopMode.OFF
        rightEngine.seekTo(leftEngine.positionMs.value + offsetMs.value)
    }

    // AB 回绕：统一轴回到 A 时右视频同步到 A+offset（hook 返回 true，左引擎不再自己 seek）
    DisposableEffect(rightEngine, offsetMs) {
        leftEngine.abWrapHook = { aMs, _ ->
            val left2 = aMs
            leftEngine.seekTo(left2)
            rightEngine.seekTo(left2 + offsetMs.value)
            true
        }
        onDispose { leftEngine.abWrapHook = null }
    }

    // 两台 ExoPlayer 各跑各的音频时钟，低频纠偏：超过阈值才一次小幅 seek，不逐帧硬拉
    LaunchedEffect(r?.uri) {
        if (r == null) return@LaunchedEffect
        var prevLeft = leftEngine.livePositionMs()
        while (true) {
            delay(RESYNC_INTERVAL_MS)
            // 拖轨期间把控制权让给用户，别让纠偏 seek 抢手
            if (dragLeft != null || dragRight != null || dragUnified != null) continue
            val leftLive = leftEngine.livePositionMs()
            val wrapped = leftLive < prevLeft - 1L
            prevLeft = leftLive
            val desired = (leftLive + offsetMs.value).coerceAtLeast(0L)
            val rd = rightEngine.durationMs.value
            if (rd > 0L && desired > rd - 1L) continue
            val frameMs = (1000.0 / leftEngine.fps.value.coerceAtLeast(1f)).roundToLong().coerceAtLeast(1L)
            val drift = rightEngine.livePositionMs() - desired
            val ended = rightEngine.playbackState.value == Player.STATE_ENDED
            // AB 循环的回绕由 hook 负责，这里别再补一次 seek，否则一次循环多点一下
            if (ended || (wrapped && !leftEngine.ab.value.loopEnabled) || abs(drift) >= max(40L, frameMs)) {
                rightEngine.seekTo(desired)
            }
        }
    }

    val leftDuration = if (leftDur > 0L) leftDur else left.durationMs
    val unifiedFrac = dragUnified ?: if (leftDuration > 0L) leftPos.toFloat() / leftDuration.toFloat() else 0f
    val aFrac = if (ab.aMs >= 0 && leftDuration > 0) ab.aMs.toFloat() / leftDuration.toFloat() else null
    val bFrac = if (ab.bMs >= 0 && leftDuration > 0) ab.bMs.toFloat() / leftDuration.toFloat() else null

    fun setPlaying(play: Boolean) {
        leftEngine.softPause(!play)
        rightEngine.softPause(!play)
    }

    fun seekUnified(frac: Float) {
        val t = (frac * leftDuration).toLong().coerceAtLeast(0L)
        leftEngine.seekTo(t)
        rightEngine.seekTo((t + offsetMs.value).coerceAtLeast(0L))
    }

    fun stepBoth(fwd: Boolean) {
        rightEngine.softPause(true)
        // 步进后 positionMs 要等轮询才跟上，右引擎必须用 stepFrame 返回的目标值平移
        val target = leftEngine.stepFrame(fwd)
        rightEngine.seekTo((target + offsetMs.value).coerceAtLeast(0L))
    }

    fun runAudioSync(clip: VideoClip) {
        syncing = true
        launchSync(app, left, clip) { res ->
            syncing = false
            if (res.ok) {
                offsetMs.value = res.offsetMs
                rightEngine.seekTo((leftEngine.positionMs.value + res.offsetMs).coerceAtLeast(0L))
                manual = false
                banner = if (res.lowConfidence) R.string.player_sync_low_confidence else R.string.player_sync_done
            } else {
                banner = res.reasonRes ?: R.string.player_sync_failed
            }
        }
    }

    Box(Modifier.fillMaxSize().background(WotaBg)) {
        // 整页只包一次 insets：用 safeDrawing 而不是 systemBars，横屏挖孔屏上
        // 左上的返回钮才不会被孔挖掉（对比页从没在横屏下截过图，这点在 #40 里核）
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                Modifier.fillMaxWidth().background(AcrylicScrim).padding(horizontal = 2.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                WotaIconButton(
                    image = Icons.Filled.ArrowBack,
                    description = stringResource(R.string.gallery_desc_back),
                    onClick = onBack,
                    size = BarIconSize,
                    glyph = BarGlyphSize
                )
                Text(
                    stringResource(R.string.compare_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = WotaText,
                    modifier = Modifier.weight(1f)
                )
                WotaChip(
                    label = stringResource(if (manual) R.string.compare_mode_manual else R.string.compare_mode_auto),
                    selected = manual,
                    onClick = { manual = !manual }
                )
                WotaIconButton(
                    image = Icons.Filled.Audiotrack,
                    description = stringResource(R.string.compare_audio_sync),
                    // 同步进行中压成未选中态，避免和「已选中 accent」混淆
                    selected = !syncing,
                    onClick = {
                        val clip = r
                        if (clip == null) banner = R.string.compare_need_right else runAudioSync(clip)
                    },
                    size = BarIconSize,
                    glyph = BarGlyphSize
                )
                WotaIconButton(
                    image = Icons.Filled.Add,
                    description = stringResource(R.string.compare_add_right),
                    onClick = { picker = true },
                    size = BarIconSize,
                    glyph = BarGlyphSize
                )
            }

            Row(Modifier.fillMaxWidth().weight(1f)) {
                Box(Modifier.weight(split).fillMaxHeight()) {
                    WotaPlayerSurface(engine = leftEngine, modifier = Modifier.fillMaxSize())
                    SideLabel(stringResource(R.string.compare_left, left.name), Modifier.align(Alignment.BottomStart))
                }
                Box(Modifier.width(2.dp).fillMaxHeight().background(WotaDivider)) {}
                Box(Modifier.weight(1f - split).fillMaxHeight()) {
                    if (r == null) {
                        // 占位底色与另一栏的信箱同色：用 WotaSurface 会让两栏"看起来一高一低"，
                        // 实际两栏 Box 都是 fillMaxHeight 等高的
                        Box(Modifier.fillMaxSize().background(WotaBg), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.compare_pick_right),
                                color = WotaTextDim,
                                modifier = Modifier.clickable { picker = true }.padding(12.dp)
                            )
                        }
                    } else {
                        WotaPlayerSurface(engine = rightEngine, modifier = Modifier.fillMaxSize())
                        SideLabel(stringResource(R.string.compare_right, r.name), Modifier.align(Alignment.BottomEnd))
                    }
                }
            }

            // 手动模式：左右各一条独立进度条
            if (manual) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(stringResource(R.string.compare_left_tag), style = MaterialTheme.typography.labelSmall, color = WotaTextDim)
                    WotaSeekBar(
                        fraction = dragLeft ?: if (leftDuration > 0) leftPos.toFloat() / leftDuration.toFloat() else 0f,
                        onDrag = { dragLeft = it },
                        onDragEnd = { frac ->
                            val target = (frac * leftDuration).toLong().coerceAtLeast(0L)
                            // 偏移必须同一时刻取两路真实位置，轮询值会把最多 120ms 的量化误差算进偏移
                            val rightLive = rightEngine.livePositionMs()
                            leftEngine.seekTo(target)
                            dragLeft = null
                            offsetMs.value = rightLive - target
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(stringResource(R.string.compare_right_tag), style = MaterialTheme.typography.labelSmall, color = WotaTextDim)
                    WotaSeekBar(
                        fraction = dragRight ?: run {
                            val rd = if (rightDur > 0) rightDur else r?.durationMs ?: 0L
                            if (rd > 0) (rightPos.toFloat() / rd.toFloat()) else 0f
                        },
                        onDrag = { dragRight = it },
                        onDragEnd = { frac ->
                            val rd = if (rightDur > 0) rightDur else r?.durationMs ?: 0L
                            val target = (frac * rd).toLong().coerceAtLeast(0L)
                            // 先取左路瞬时位置再落右路 seek，两路同一时刻才不会把轮询量化误差算进偏移
                            val leftLive = leftEngine.livePositionMs()
                            rightEngine.seekTo(target)
                            dragRight = null
                            offsetMs.value = target - leftLive
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 统一轴：进度 + 时长
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp)) {
                Text(formatDuration(leftPos), style = MonoStyle, color = WotaText)
                WotaSeekBar(
                    fraction = unifiedFrac,
                    aFraction = aFrac,
                    bFraction = bFrac,
                    onDrag = { dragUnified = it },
                    onDragEnd = { frac ->
                        seekUnified(frac)
                        dragUnified = null
                    },
                    modifier = Modifier.weight(1f)
                )
                Text(formatDuration(leftDuration), style = MonoStyle, color = WotaTextDim)
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(horizontal = 4.dp)
            ) {
                WotaIconButton(
                    image = Icons.Filled.SkipPrevious,
                    description = stringResource(R.string.player_step_back),
                    onClick = { stepBoth(false) },
                    size = BarIconSize,
                    glyph = BarGlyphSize
                )
                WotaIconButton(
                    image = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    description = stringResource(R.string.player_play_pause),
                    selected = true,
                    onClick = { setPlaying(!playing) },
                    size = PlayIconSize,
                    glyph = PlayGlyphSize
                )
                WotaIconButton(
                    image = Icons.Filled.SkipNext,
                    description = stringResource(R.string.player_step_fwd),
                    onClick = { stepBoth(true) },
                    size = BarIconSize,
                    glyph = BarGlyphSize
                )
                AbRow(
                    ab = ab,
                    onA = { leftEngine.markA() },
                    onB = { if (!leftEngine.markB()) banner = R.string.player_ab_invalid },
                    onLoop = { if (!leftEngine.toggleAbLoop()) banner = R.string.player_ab_invalid },
                    onClear = { leftEngine.clearAb() }
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                PlayerSpeedTiers.TIERS.forEach { tier ->
                    WotaChip(
                        label = stringResource(speedLabelRes(tier)),
                        selected = speed == tier,
                        onClick = {
                            leftEngine.setSpeed(tier)
                            rightEngine.setSpeed(tier)
                        }
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.compare_offset, formatDuration(offsetMs.value)),
                    style = MonoStyle,
                    color = WotaTextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }

        // 同步结果反馈条：从底边浮起、3.2s 后淡出。文案钉在快照上，
        // 否则退场动画那一帧 banner 已置空，条子会先变成空的再滑走
        val msg = banner
        val mtn = com.wotagei.cam.ui.anim.LocalMotion.current
        val snapshot = androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
        // 必须在组合期间写：LaunchedEffect 是组合之后才跑的，那样 AnimatedVisibility 首次组合
        // 会读到资源 id 0 并抛 NotFoundException（真机实测闪退过）
        if (msg != null) snapshot.intValue = msg
        LaunchedEffect(msg) {
            if (msg != null) {
                kotlinx.coroutines.delay(3200)
                banner = null
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = msg != null,
            enter = androidx.compose.animation.fadeIn(mtn.float) +
                androidx.compose.animation.slideInVertically(mtn.offset) { it },
            exit = androidx.compose.animation.fadeOut(mtn.float) +
                androidx.compose.animation.slideOutVertically(mtn.offset) { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Box(
                Modifier
                    .padding(bottom = 120.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(WotaSurface)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) { Text(stringResource(snapshot.intValue), style = MaterialTheme.typography.bodyMedium, color = WotaText) }
        }
        if (syncing) {
            Box(Modifier.fillMaxSize().background(WotaColor.scrim), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = WotaAccent)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.compare_syncing), color = WotaText, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }

    if (picker) {
        ClipPickerDialog(
            title = stringResource(R.string.compare_pick_right),
            onPick = { clip ->
                picker = false
                right = clip
                offsetMs.value = 0L
            },
            onDismiss = { picker = false }
        )
    }
}

@Composable
private fun SideLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelSmall,
        color = WotaColor.textHi,
        // 压在画面上必须有底：裸字（旧版 WotaTextDim 无底）遇到亮场直接读不出来，
        // 底用与 #36 时长角标同一个 wotaCard 胶囊，别再造一份一次性 background。
        // widthIn 给长文件名封顶，否则胶囊会铺满整栏、把画面全挡住。
        modifier = modifier
            .padding(6.dp)
            .widthIn(max = 140.dp)
            .wotaCard(WotaShape.pill)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

/** 在 Default 线程跑音频对齐，结果回主线程 */
@Composable
private fun rememberSyncLauncher(): (android.content.Context, VideoClip, VideoClip, (AudioSync.Result) -> Unit) -> Unit {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    return { ctx, l, rr, done ->
        scope.launch {
            val res = AudioSync.align(ctx, l.uri, rr.uri)
            done(res)
        }
    }
}

@Composable
private fun <T> Flow<T>.collectState(initial: T): State<T> = collectAsState(initial)
