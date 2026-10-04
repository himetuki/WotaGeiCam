package com.wotagei.cam.player

import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.wotagei.cam.R
import com.wotagei.cam.media.MediaRepo
import com.wotagei.cam.media.VideoClip
import com.wotagei.cam.media.formatDuration
import com.wotagei.cam.media.rememberMediaRepo
import com.wotagei.cam.ui.WotaSettings
import com.wotagei.cam.ui.anim.LocalMotion
import com.wotagei.cam.ui.dialog.BottomPanel
import com.wotagei.cam.ui.design.WotaChip
import com.wotagei.cam.ui.design.WotaColor
import com.wotagei.cam.ui.design.WotaIconButton
import com.wotagei.cam.ui.design.WotaPillPopup
import com.wotagei.cam.ui.design.WotaShape
import com.wotagei.cam.ui.design.WotaStroke
import com.wotagei.cam.ui.design.pillAnchor
import com.wotagei.cam.ui.design.wotaCard
import com.wotagei.cam.ui.theme.MonoStyle
import com.wotagei.cam.ui.theme.WotaAccent
import com.wotagei.cam.ui.theme.WotaBg
import com.wotagei.cam.ui.theme.WotaDivider
import com.wotagei.cam.ui.theme.WotaSurface
import com.wotagei.cam.ui.theme.WotaText
import com.wotagei.cam.ui.theme.WotaTextDim
import androidx.media3.common.Player
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
    onPractice: () -> Unit,
    repo: MediaRepo = rememberMediaRepo()
) {
    val left by repo.clipById(leftMediaId).collectState(null)
    val c = left
    if (c == null) {
        Box(Modifier.fillMaxSize().background(WotaBg), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = WotaAccent)
        }
    } else {
        CompareContent(left = c, onBack = onBack, onPractice = onPractice)
    }
}

@Composable
private fun CompareContent(left: VideoClip, onBack: () -> Unit, onPractice: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val leftEngine = rememberPlayerEngine()
    val rightEngine = rememberPlayerEngine()
    val launchSync = rememberSyncLauncher()

    var right by remember { mutableStateOf<VideoClip?>(null) }
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

    // #4（用户 2026-09-30 反馈；**10-01 修订：两窗分别单独缩放**，不做同倍率）：各一枚 zoom 态，
    // 手势挂**各自那一栏**的 Box 上——pointerInput 只收自己布局界内的事件，压哪栏就缩哪栏。
    // 手势本体复用单播放页那条 pinchZoom（1..4x，第二指才消费，单指滑动不受影响）。
    // 一指各压一栏的双指手势两边都凑不齐两指、两栏都不动，这是自然语义，不需要特殊处理。
    // 左/右标签在 graphicsLayer 之外，缩放时保持原大不跟着糊。
    val zoomL = remember { mutableStateOf(1f) }
    val zoomR = remember { mutableStateOf(1f) }

    // 沉浸式浮层（仿 HarmonyOS 6/7 / iOS 26，10-01）：单击显隐控制层、双击启停——
    // TapArbiter 与窗口常量复用单播放页的 internal 件，不各抄一份；不做自动隐藏，
    // 口径与单播放页一致（显隐只由画面单击决定）。
    var controlsVisible by remember { mutableStateOf(true) }
    var toggleJob by remember { mutableStateOf<Job?>(null) }
    val tapArbiter = remember { TapArbiter(DOUBLE_TAP_WINDOW_MS) }
    val tapScope = rememberCoroutineScope()
    val motion = LocalMotion.current
    // 音频源（参考图 Side A/B）：此前两台引擎全音量齐播（PlayerEngine.setVolume 全工程零
    // 调用方），对比场景双声混音没法听；选定一边、另一边静音，默认 Side A
    var audioSide by remember { mutableStateOf(0) }
    // 倍速就近弹层（复用单播放页 SpeedTierPopup），锚点与开关两枚状态
    var speedMenu by remember { mutableStateOf(false) }
    // 水平镜像（10-01 修订）：0=关 1=左片 2=右片 3=双片都翻。会话态不持久化；
    // 弹层三选（左/右/全），点当前已选项=关闭（归 0），约定写进 MirrorPopup 的注释
    var mirror by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var mirrorMenu by remember { mutableStateOf(false) }
    var mirrorAnchor by remember { mutableStateOf(androidx.compose.ui.unit.IntRect.Zero) }
    var speedAnchor by remember { mutableStateOf(androidx.compose.ui.unit.IntRect.Zero) }
    // 历史面板开关（顶栏「历史」胶囊），面板本体在文件尾 CompareHistorySheet
    var historyOpen by remember { mutableStateOf(false) }
    val repo = rememberMediaRepo()
    val prefs = remember(app) { WotaSettings.of(app) }

    // 右侧视频改走系统图库选择器（ACTION_PICK 直开系统相册）：测试机无 GMS，
    // Photo Picker 会退化成文档界面，ACTION_PICK 才是用户熟悉的相册入口。
    // 回调把 content Uri 还原成 mediaId 再经仓库反查，选中后记进对比历史。
    val pickRight = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // 坏 Uri / 解析失败一律按没选成处理：给错误横幅而不是静默吞。
            // parseId 只认数字末段（media uri）；个别相册/文件管理器会回私有 provider uri，
            // 那条路回退查 _ID 列（读走 MediaStore 自带权限，不经 uri grant）
            val uri = result.data?.data
            var id = uri?.let { runCatching { ContentUris.parseId(it) }.getOrNull() } ?: 0L
            if (id <= 0L && uri != null) {
                id = runCatching {
                    app.contentResolver.query(
                        uri, arrayOf(android.provider.MediaStore.Video.Media._ID), null, null, null
                    )?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
                }.getOrNull() ?: 0L
            }
            if (id <= 0L) {
                banner = R.string.compare_pick_failed
            } else {
                tapScope.launch {
                    val clip = repo.clipById(id).first()
                    if (clip == null) {
                        banner = R.string.compare_pick_failed
                    } else {
                        right = clip
                        offsetMs.value = 0L
                        CompareHistory.record(prefs, clip.id, left.id)
                    }
                }
            }
        }
    }

    fun launchPicker() {
        // 不加 FLAG_GRANT_READ_URI_PERMISSION：它只对启动 intent 自己的 uri 有意义，
        // 结果 uri 的读授权由相册在 result 里自行授予，我们的读数走 MediaStore 自带权限
        pickRight.launch(Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI))
    }

    // 练习闭环回程消费（2026-10-04 方向 4）：本页 ON_RESUME 时先自愈清 armed（录制失败/
    // 没录就退的尾巴），再收 pendingRightId 填右槽。不引 lifecycle-runtime-compose，
    // LifecycleEventObserver 三行的事。收货后记进对比历史，与手动选右片同一待遇
    val practiceOwner = LocalLifecycleOwner.current
    DisposableEffect(practiceOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            if (ComparePractice.armed) {
                ComparePractice.armed = false
                ComparePractice.pendingRightId = null
            }
            val id = ComparePractice.pendingRightId
            if (id != null) {
                ComparePractice.pendingRightId = null
                tapScope.launch {
                    val clip = repo.clipById(id).first()
                    if (clip != null) {
                        right = clip
                        offsetMs.value = 0L
                        CompareHistory.record(prefs, clip.id, left.id)
                    }
                }
            }
        }
        practiceOwner.lifecycle.addObserver(obs)
        onDispose { practiceOwner.lifecycle.removeObserver(obs) }
    }

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

    // Side A/B 落音量：切边或右片换片后重下一次（另一边压成 0，选中的一边全量）
    LaunchedEffect(audioSide, r?.uri) {
        leftEngine.setVolume(if (audioSide == 0) 1f else 0f)
        rightEngine.setVolume(if (audioSide == 1 && r != null) 1f else 0f)
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
        // ① 视频层：铺满整屏（仿参考图的沉浸式）。单击显隐控制层、双击启停（TapArbiter 与
        //    单播放页同一套仲裁）；控制层没吃掉的空档，指头会落到这一层的 onTap 上。
        //    双指缩放挂各栏自己的 Box（#4 的 10-01 修订）：压哪栏缩哪栏，两窗倍率互不相干。
        Row(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        val now = SystemClock.uptimeMillis()
                        toggleJob?.cancel()
                        if (tapArbiter.onPointerUp(now) == TapArbiter.Decision.PlayPause) {
                            setPlaying(!playing)
                        } else {
                            toggleJob = tapScope.launch {
                                delay(DOUBLE_TAP_WINDOW_MS)
                                if (tapArbiter.onToggleDue(SystemClock.uptimeMillis())) {
                                    controlsVisible = !controlsVisible
                                    // 控制层收起时把就近弹层一并收掉（审查 P3）：
                                    // 否则 speedMenu/mirrorMenu 残留，再展开时弹层凭空复现
                                    if (!controlsVisible) {
                                        speedMenu = false
                                        mirrorMenu = false
                                    }
                                }
                            }
                        }
                    })
                }
        ) {
            // clipToBounds：镜像/缩放是绘制期变换（graphicsLayer 默认不裁剪），不裁会越过
            // 2dp 分隔线压到邻栏（审查 P2；单页全屏无感，分栏后任何一次捏合都可见）
            Box(Modifier.weight(split).fillMaxHeight().clipToBounds().pinchZoom(zoomL)) {
                WotaPlayerSurface(engine = leftEngine, modifier = Modifier.fillMaxSize(), scale = zoomL.value, mirror = mirror == 1 || mirror == 3)
                ZoomBadge(zoomL.value, Modifier.align(Alignment.TopStart).padding(8.dp))
                SideLabel(stringResource(R.string.compare_left, left.name), Modifier.align(Alignment.TopEnd))
            }
            Box(Modifier.width(2.dp).fillMaxHeight().background(WotaDivider)) {}
            Box(Modifier.weight(1f - split).fillMaxHeight().clipToBounds().pinchZoom(zoomR)) {
                if (r == null) {
                    // 占位底色与另一栏的信箱同色：用 WotaSurface 会让两栏"看起来一高一低"，
                    // 实际两栏 Box 都是 fillMaxHeight 等高的
                    Box(Modifier.fillMaxSize().background(WotaBg), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.compare_pick_right),
                            // 默认字阶即 bodyLarge（正文）：textLo 压 bg 只有 3.82 过不了 4.5，升 textMid；
                            // 可点语义由整块 12dp 内边距的点击区承担
                            color = WotaColor.textMid,
                            modifier = Modifier.clickable { launchPicker() }.padding(12.dp)
                        )
                    }
                } else {
                    WotaPlayerSurface(engine = rightEngine, modifier = Modifier.fillMaxSize(), scale = zoomR.value, mirror = mirror == 2 || mirror == 3)
                    ZoomBadge(zoomR.value, Modifier.align(Alignment.TopStart).padding(8.dp))
                    SideLabel(stringResource(R.string.compare_right, r.name), Modifier.align(Alignment.TopEnd))
                }
            }
        }

        // ② 控制层：浮在画面上。insets 只包这一层（视频仍然全幅出血），顶栏贴状态栏/挖孔、
        //    底栏贴导航栏，方向性避让交给系统（#68 铁律：不写死方向常量）
        Column(Modifier.matchParentSize().safeDrawingPadding()) {
            // 顶栏（仿 HarmonyOS/iOS 26 浮层芯片组：无整条 bar 底，控件各自带半透衬底）
            Box(Modifier.fillMaxWidth()) {
                // 全限定调用：这里在 overlay Column 的 Box 里，ColumnScope 扩展重载会被
                // 隐式接收者挡下（编译器点名的那条），与页内 banner 的写法保持一致
                androidx.compose.animation.AnimatedVisibility(
                    visible = controlsVisible,
                    enter = fadeIn(motion.float) + scaleIn(motion.float, initialScale = 0.92f),
                    exit = fadeOut(motion.float) + scaleOut(motion.float, targetScale = 0.92f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            // 竖屏 360dp 装不下整排芯片（审查 P1：溢出即尾部裁切、历史/＋不可达），
                            // 允许横向滚动。⚠ 这里**不许用 weight Spacer 占位**：horizontalScroll 注入
                            // 无限宽度约束后 weight 子项不报错、而是塌成 0 宽（foundation 1.5.4 的
                            // RowColumnMeasurementHelper 走 minWidth 分支，剩余空间恒 0），「＋」会贴死
                            // 历史胶囊。两端分布改用 SpaceBetween + 三段分组承担
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // 第一段：返回 + 同步模式（手动=选中蓝底，自动=深底——当前模式要可辨识）
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            WotaIconButton(
                                image = Icons.Outlined.ArrowBack,
                                description = stringResource(R.string.gallery_desc_back),
                                onClick = onBack,
                                size = BarIconSize,
                                glyph = BarGlyphSize
                            )
                            ComparePill(
                                label = stringResource(if (manual) R.string.compare_mode_manual else R.string.compare_mode_auto),
                                selected = manual,
                                onClick = { manual = !manual }
                            )
                        }
                        // 第二段：音频源与同步动作
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ComparePill(
                                label = stringResource(R.string.compare_side_a),
                                selected = audioSide == 0,
                                onClick = { audioSide = 0 }
                            )
                            ComparePill(
                                label = stringResource(R.string.compare_side_b),
                                selected = audioSide == 1,
                                onClick = {
                                    val clip = r
                                    if (clip == null) banner = R.string.compare_need_right else audioSide = 1
                                }
                            )
                            ComparePill(
                                label = stringResource(R.string.compare_sync),
                                selected = syncing,
                                onClick = {
                                    val clip = r
                                    if (clip == null) banner = R.string.compare_need_right else runAudioSync(clip)
                                }
                            )
                            ComparePill(
                                label = stringResource(R.string.compare_history),
                                selected = historyOpen,
                                onClick = { historyOpen = !historyOpen }
                            )
                        }
                        // 第三段：添加右片
                        WotaIconButton(
                            image = Icons.Outlined.Add,
                            description = stringResource(R.string.compare_add_right),
                            onClick = { launchPicker() },
                            size = BarIconSize,
                            glyph = BarGlyphSize
                        )
                    }
                }
                // 控制层隐藏时只留返回钮（与单播放页同款），免得卡在播放页里出不去
                androidx.compose.animation.AnimatedVisibility(
                    visible = !controlsVisible,
                    enter = fadeIn(motion.float),
                    exit = fadeOut(motion.float),
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    WotaIconButton(
                        image = Icons.Outlined.ArrowBack,
                        description = stringResource(R.string.gallery_desc_back),
                        onClick = onBack,
                        modifier = Modifier.padding(6.dp),
                        size = BarIconSize,
                        glyph = BarGlyphSize
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // 底部控制层：细进度条直接压在画面上，时间/控件各自带半透衬底（仿参考图）
            androidx.compose.animation.AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(motion.float) + scaleIn(motion.float, initialScale = 0.92f),
                exit = fadeOut(motion.float) + scaleOut(motion.float, targetScale = 0.92f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    // 手动模式双进度条（offset 同刻取两路 live 位置的逻辑一行未动），
                    // 收进一枚半透卡：白底进度线压在亮画面上读不出，衬底是可读性的下限
                    if (manual) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                // WotaShape.card 是 Dp 件位值（24dp）不是 Shape，wotaCard 要 Shape 就地包
                                .wotaCard(RoundedCornerShape(WotaShape.card))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
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
                    }
                    // 统一轴：A/B 刻度由 WotaSeekBar 自带，拖动=双引擎同帧 seek（逻辑不变）
                    WotaSeekBar(
                        fraction = unifiedFrac,
                        aFraction = aFrac,
                        bFraction = bFrac,
                        onDrag = { dragUnified = it },
                        onDragEnd = { frac ->
                            seekUnified(frac)
                            dragUnified = null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp)
                    )
                    // 时间读数与控件**合并成同一行**（用户 10-01 指令：下侧所有控件都要与
                    // 「当前播放时长 / 视频总时长」同一行）。三段分组 + SpaceBetween：运输组在左、
                    // 时间读数居中、AB/倍速/镜像在右——横屏 766dp 三段同时放得下，竖屏 360dp
                    // 放不下时仍可横向滚动。
                    // ⚠ 同顶栏那条：horizontalScroll 里**不许用 weight Spacer**——注入无限宽度约束后
                    // weight 子项不报错、直接塌成 0 宽（审查员反汇编 foundation 1.5.4 证实）。
                    // 这里 SpaceBetween 还能把三段分到两端与中间，是因为 fillMaxWidth 在 scroll 之外：
                    // 内容没超屏时内层行拿到的最小宽=视口宽（不是 0），三段才有余量可分。
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            WotaIconButton(
                                image = Icons.Outlined.SkipPrevious,
                                description = stringResource(R.string.player_step_back),
                                onClick = { stepBoth(false) },
                                size = BarIconSize,
                                glyph = BarGlyphSize
                            )
                            WotaIconButton(
                                image = if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                description = stringResource(R.string.player_play_pause),
                                selected = true,
                                onClick = { setPlaying(!playing) },
                                size = PlayIconSize,
                                glyph = PlayGlyphSize
                            )
                            WotaIconButton(
                                image = Icons.Outlined.SkipNext,
                                description = stringResource(R.string.player_step_fwd),
                                onClick = { stepBoth(true) },
                                size = BarIconSize,
                                glyph = BarGlyphSize
                            )
                        }
                        // 第二段：统一轴读数（左=当前、中=右偏移、右=总时长）。
                        // 三个读数从原来独立一行搬进来，功能一个不少；偏移仍在中间，
                        // 与统一轴「以左片为基准、右片按 offsetMs 平移」的语义对位。
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            TimePill(formatDuration(leftPos))
                            TimePill(stringResource(R.string.compare_offset, formatDuration(offsetMs.value)))
                            TimePill(formatDuration(leftDuration))
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            AbRow(
                                ab = ab,
                                onA = { leftEngine.markA() },
                                onB = { if (!leftEngine.markB()) banner = R.string.player_ab_invalid },
                                onLoop = { if (!leftEngine.toggleAbLoop()) banner = R.string.player_ab_invalid },
                                onClear = { leftEngine.clearAb() }
                            )
                            WotaChip(
                                label = stringResource(speedLabelRes(speed)),
                                selected = speed != PlayerSpeedTiers.DEFAULT,
                                modifier = Modifier.pillAnchor { rect -> speedAnchor = rect },
                                onClick = { speedMenu = !speedMenu }
                            )
                            ComparePill(
                                label = stringResource(R.string.compare_mirror),
                                selected = mirror != 0,
                                modifier = Modifier.pillAnchor { rect -> mirrorAnchor = rect },
                                onClick = { mirrorMenu = !mirrorMenu }
                            )
                            // 对着左片练（2026-10-04 方向 4）：左路参考片 AB 循环先圈好段，
                            // 这里一键压栈录制页；录完自动弹回来把新片填进右槽（桥见 ComparePractice）
                            ComparePill(
                                label = stringResource(R.string.compare_practice),
                                selected = false,
                                onClick = {
                                    setPlaying(false)
                                    ComparePractice.armed = true
                                    onPractice()
                                }
                            )
                        }
                    }
                }
            }
        }

        // 倍速就近弹层（与单播放页同一枚：锚在底部那颗倍速胶囊上；控制层收起时一并收）
        if (controlsVisible && speedMenu) {
            SpeedTierPopup(
                anchor = speedAnchor,
                current = speed,
                onPick = { tier ->
                    leftEngine.setSpeed(tier)
                    rightEngine.setSpeed(tier)
                    speedMenu = false
                },
                onDismiss = { speedMenu = false }
            )
        }
        // 镜像三选弹层（10-01 修订）：与倍速弹层同一套就近弹范式
        if (controlsVisible && mirrorMenu) {
            MirrorPopup(
                anchor = mirrorAnchor,
                current = mirror,
                onPick = { picked -> mirror = if (picked == mirror) 0 else picked },
                onDismiss = { mirrorMenu = false }
            )
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
        // 历史半屏面板：换装录制页同一套自绘半模态（ui/design/SheetSpec 纪律，宿主 BottomPanel）
        // ——原先 M3 ModalBottomSheet(fillMaxHeight(0.55f)) 接不上 480dp 宽/最小高 320/短边 90%
        // 这套档位。BottomPanel 是同层覆盖，必须挂在铺满整屏的根 Box 里（放在最后=绘制在最上）；
        // AnimatedVisibility 隐藏时不组合内容，等价于原先 if(historyOpen) 的按需组合。
        BottomPanel(
            visible = historyOpen,
            onDismiss = { historyOpen = false },
            title = stringResource(R.string.compare_history)
        ) {
            CompareHistorySheet(
                prefs = prefs,
                repo = repo,
                onPick = { clip ->
                    right = clip
                    offsetMs.value = 0L
                    CompareHistory.record(prefs, clip.id, left.id)
                    historyOpen = false
                }
            )
        }
    }
}

/**
 * 「历史」面板**内容**：把 [CompareHistory] 的记录逐条经 [MediaRepo.clipById] 反查成视频——
 * 还在的显示片名 + 时长（整行点选直接设为右侧视频，并把这条记回队首再收面板），
 * 已不存在的（删了/换库）显示灰色占位行，行尾删除钮把这条记录摘掉后就地刷新。
 *
 * 容器（标题/关闭钮/scrim/滚动/宽高纪律）全部由录制页同一枚 [BottomPanel] 提供——
 * 本函数只管行列表。换装取舍：M3 ModalBottomSheet 的**手势下滑关闭**没有等价搬过来
 * （自绘容器做拖拽收起要另起 anchoredDraggable 一套，代价大于收益），关闭语义由
 * scrim 点击 + 标题行关闭钮承担，与录制页全部面板一致；M3 的半开档/手势条避让
 * 也一并由 BottomPanel 的最小高 320dp 与 safeDrawingPadding 取代。
 * 行配色全部即时切换（#81：面板内不做任何颜色/尺寸动画）。
 */
@Composable
private fun CompareHistorySheet(
    prefs: SharedPreferences,
    repo: MediaRepo,
    onPick: (VideoClip) -> Unit
) {
    // 记录 → (记录, 反查结果)；反查 null = 视频已不存在。面板由 BottomPanel 的
    // AnimatedVisibility 承载：隐藏时不组合、打开瞬间才进组合，LaunchedEffect 首组合必跑；
    // 行内删除后靠 refresh 换 key 重解析。
    // 解析直接用 LaunchedEffect 自己的协程（查询本体在 flowOn(IO)），不丢进外部 scope：
    // 外部 scope 在换 key 重解析时不会取消上一个任务，旧结果可能反过来盖掉刚删完的新列表。
    var items by remember { mutableStateOf<List<Pair<CompareHistory.Entry, VideoClip?>>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) {
        val resolved = ArrayList<Pair<CompareHistory.Entry, VideoClip?>>()
        CompareHistory.entries(prefs).forEach { entry ->
            resolved += entry to repo.clipById(entry.rightId).first()
        }
        items = resolved
    }
    Column(Modifier.fillMaxWidth()) {
        if (items.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().padding(vertical = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.compare_history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    // 底从 WotaSurface 换成 BottomPanel 的 AcrylicScrim(0xB3)：正文压 scrim
                    // 只准 textHi（textMid worst ≈4.28 < 4.5），与录制页面板同一纪律
                    color = WotaText
                )
            }
        } else {
            items.forEach { (entry, clip) ->
                val c = clip
                if (c == null) {
                    // 失效行：灰度语义交给删除线 + 行尾删除钮
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.compare_history_missing),
                            style = MaterialTheme.typography.bodyMedium,
                            // 正文压 AcrylicScrim 只准 textHi（同上）；「失效」语义由删除线承担
                            color = WotaText,
                            textDecoration = TextDecoration.LineThrough,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            CompareHistory.remove(prefs, entry.rightId)
                            refresh++
                        }) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.compare_history_remove),
                                tint = WotaTextDim
                            )
                        }
                    }
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(c) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            c.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = WotaText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            formatDuration(c.durationMs),
                            style = MonoStyle,
                            // 时长读数与片名同为正文字号（MonoStyle=bodyLarge）：压 scrim 一并升 textHi
                            color = WotaText
                        )
                    }
                }
            }
        }
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

/**
 * 镜像三选弹层（10-01 修订）：左镜像 / 右镜像 / 全镜像，三行等长各 3 字。
 * 与单播放页 [SpeedTierPopup] 同一套 [WotaPillPopup] 就近弹范式，120dp 定宽（10-01
 * 用户指令收窄弹窗）——宽度账：3 字×12sp ≈ 36dp + 行内左右边距 20dp + 对勾 15dp ≈ 71dp，
 * 文本区 85dp 富余，即使 fontScale 放大也装得下。
 * 约定：**点当前已选项 = 关闭镜像**（调用方把 0 写回去）；再点底部胶囊收弹层。
 * 激活语义由行尾 accent 对勾承担（图形件压 3.0 档）；文字保持浅色——
 * accent 换 HarmonyOS 蓝后不当小字色的既定纪律。
 */
@Composable
private fun MirrorPopup(
    anchor: androidx.compose.ui.unit.IntRect,
    current: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    WotaPillPopup(anchor, onDismiss, title = stringResource(R.string.compare_mirror)) {
        Column(Modifier.width(120.dp)) {
            listOf(
                1 to R.string.compare_mirror_left,
                2 to R.string.compare_mirror_right,
                3 to R.string.compare_mirror_all
            ).forEach { (value, res) ->
                val picked = value == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(value) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(res),
                        style = MaterialTheme.typography.labelMedium,
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
 * 仿 HarmonyOS 6/7 / iOS 26 的**浮层胶囊芯片**（参考图 Side A/Side B/同步 那一枚）：
 * 未选中=半透黑衬底（`WotaColor.scrim` 令牌）+ 浅字，选中=accentSurface 实底 + onAccent 字
 * （labelMedium 白字小字的承载面，白字对 #007DFF(accent) 只有 3.91:1 不过 AA 正文，
 * 对 #0A59F7 5.55:1 过——见 WotaColor.accentSurface 注），
 * 细高光描边收边。颜色**即时切换**（#81 第 1 条禁动画颜色）。
 * 不直接用 WotaChip 的原因：它的未选中态是透明底，浮在亮画面上读不出来——
 * 参考图的未选中片自带深色胶囊底，这就是那层衬底。
 */
@Composable
private fun ComparePill(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .clip(WotaShape.pill)
            .background(if (selected) WotaColor.accentSurface else WotaColor.scrim)
            .border(WotaStroke.hairline, WotaColor.acrylicBorder, WotaShape.pill)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) WotaColor.onAccent else WotaColor.textHi,
            maxLines = 1
        )
    }
}

/** 底部「当前 / 右偏移 / 总时长」读数的半透胶囊：等宽字压在任意画面上都要有衬底，否则亮场读不出（#36 同族）。10-01 起这三个读数与控件同处一行 */
@Composable
private fun TimePill(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MonoStyle,
        color = WotaColor.textHi,
        maxLines = 1,
        modifier = modifier
            .wotaCard(WotaShape.pill)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

/** 每片缩放倍率角标（参考图左上角 x1.2088724 那枚）：1x 附近不组合；缩小（下限 0.1x）同样显示 */
@Composable
private fun ZoomBadge(zoom: Float, modifier: Modifier = Modifier) {
    if (zoom in 0.99f..1.01f) return
    Text(
        stringResource(R.string.player_zoom_badge, zoom),
        style = MonoStyle,
        color = WotaColor.textHi,
        maxLines = 1,
        modifier = modifier
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
