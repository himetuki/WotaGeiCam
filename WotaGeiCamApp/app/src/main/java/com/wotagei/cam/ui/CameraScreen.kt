package com.wotagei.cam.ui

import android.content.Context
import android.content.res.Resources
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.wotagei.cam.R
import com.wotagei.cam.bt.BtSpeakerController
import com.wotagei.cam.camera.CameraController
import com.wotagei.cam.camera.DeviceStatus
import com.wotagei.cam.camera.DirectSink
import com.wotagei.cam.camera.GlRenderEngine
import com.wotagei.cam.camera.LevelSensor
import com.wotagei.cam.camera.PreviewOrientation
import com.wotagei.cam.camera.PreviewSink
import com.wotagei.cam.camera.PreviewStatus
import com.wotagei.cam.camera.RecordStatus
import com.wotagei.cam.core.AeMode
import com.wotagei.cam.core.hasAdjustableFocus
import com.wotagei.cam.core.AfMode
import com.wotagei.cam.core.Flash
import com.wotagei.cam.core.FrameEffect
import com.wotagei.cam.core.HudItem
import com.wotagei.cam.core.LensType
import com.wotagei.cam.core.RenderMode
import com.wotagei.cam.core.Stabilize
import com.wotagei.cam.core.TapPoint
import com.wotagei.cam.core.WbPreset
import com.wotagei.cam.core.WotaParams
import com.wotagei.cam.core.WotaTiers
import com.wotagei.cam.media.VideoThumbnail
import com.wotagei.cam.media.formatDuration
import com.wotagei.cam.media.rememberMediaRepo
import com.wotagei.cam.record.AudioProbe
import com.wotagei.cam.record.DEFAULT_AUDIO_CHANNELS
import com.wotagei.cam.record.OutputSink
import com.wotagei.cam.record.RecordError
import com.wotagei.cam.record.RecordProfile
import com.wotagei.cam.record.RecordResult
import com.wotagei.cam.record.Recorder
import com.wotagei.cam.record.Recorders
import com.wotagei.cam.record.VideoStore
import com.wotagei.cam.record.recordOrientationHint
import com.wotagei.cam.ui.anim.LocalMotion
import com.wotagei.cam.ui.design.WotaChip
import com.wotagei.cam.ui.design.WotaHit
import com.wotagei.cam.ui.design.WotaIconButton
import com.wotagei.cam.ui.design.WotaShape
import com.wotagei.cam.ui.design.WotaValueCard
import com.wotagei.cam.ui.design.pillAnchor
import com.wotagei.cam.ui.design.wotaCard
import com.wotagei.cam.ui.dialog.BtSpeakerSheet
import com.wotagei.cam.ui.dialog.CurveSheet
import com.wotagei.cam.ui.dialog.evText
import com.wotagei.cam.ui.dialog.flashLabelRes
import com.wotagei.cam.ui.dialog.freeSpaceText
import com.wotagei.cam.ui.dialog.lensLabelRes
import com.wotagei.cam.ui.dialog.observed
import com.wotagei.cam.ui.dialog.shutterText
import com.wotagei.cam.ui.dialog.stabLabelRes
import com.wotagei.cam.ui.dialog.sizeText
import com.wotagei.cam.ui.dialog.wbLabelRes
import com.wotagei.cam.ui.theme.MonoStyle
import com.wotagei.cam.ui.theme.WotaAccent
import com.wotagei.cam.ui.theme.WotaBg
import com.wotagei.cam.ui.theme.WotaDivider
import com.wotagei.cam.ui.theme.WotaHudScrim
import com.wotagei.cam.ui.theme.WotaRec
import com.wotagei.cam.ui.theme.WotaSurface
import com.wotagei.cam.ui.theme.WotaText
import com.wotagei.cam.ui.theme.WotaTextDim
import com.wotagei.cam.ui.theme.WotaWarn
import com.wotagei.cam.ui.widget.BtChip
import com.wotagei.cam.ui.widget.CameraSurface
import com.wotagei.cam.ui.widget.LevelBadge
import com.wotagei.cam.ui.widget.RefLineOverlay
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * 录制页（06 文档 §4）：顶栏 / 预览 + 参考线 + 点按对焦框 / 右侧仪表列 / 底栏 / 左参数抽屉。
 *
 * 横竖都能录：显示角 90/270（横屏）沿用「预览铺满 + 顶底栏浮在四边」；
 * 显示角 0/180（竖屏）改走列布局（顶栏 / 预览吃剩余高度等比居中 / 底栏），
 * 仪表列与抽屉把手仍贴预览两侧，参考线永远画在实际预览矩形上。
 *
 * 跨模块接缝全部走 public API：
 * - 取景：[CameraController]（页面级 ViewModel）+ [CameraSurface]，换渲染模式即换 sink 重建引擎；
 * - 录制：[Recorders.create] + [VideoStore]，DIRECT 把编码面交给 Camera2 会话，GPU 交给 [GlRenderEngine]；
 * - 回看：[VideoStore] 已清 IS_PENDING，这里只 `repo.invalidate()` 让媒体库重查。
 */
@Composable
@Suppress("LongMethod")
fun CameraScreen(
    onOpenGallery: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext }
    val owner = LocalLifecycleOwner.current
    // 门面放在导航目的地的 ViewModelStore 上：离开录制页即随栈回收，回到页面复用同一实例
    val storeOwner = LocalViewModelStoreOwner.current
    val ctrl = remember(storeOwner) {
        CameraController.of(checkNotNull(storeOwner) { "CameraScreen 必须在 NavHost 目的地内使用" })
    }
    val params = ctrl.params
    // 默认档位必须在建 sink 之前套用：渲染模式默认值会决定首个 sink 是 GL 还是直显，晚一帧就白起一个 GL 线程
    WotaSettings.applyDefaultsOnce(app, params)
    val repo = rememberMediaRepo()

    val ui by ctrl.state.observed()
    val renderMode by params.renderMode.observed()
    val size by params.size.observed()
    val fps by params.fps.observed()
    val shutter by params.shutter.observed()
    val iso by params.iso.observed()
    val ev by params.ev.observed()
    val aeMode by params.aeMode.observed()
    val wbMode by params.wbMode.observed()
    val stabilize by params.stabilize.observed()
    val afMode by params.afMode.observed()
    val kelvin by params.kelvin.observed()
    val zoom by params.zoom.observed()
    val flash by params.flash.observed()
    val frameEffect by params.frameEffect.observed()
    val zebraThreshold by params.zebraThreshold.observed()
    val zebraDensity by params.zebraDensity.observed()
    val peakingStrength by params.peakingStrength.observed()
    val peakingColor by params.peakingColor.observed()
    val curveStack by params.curve.observed()
    val bitrate by params.bitrate.observed()
    val refLines by params.refLines.observed()
    val levelEnabled by params.levelEnabled.observed()
    // 常驻 HUD 与震动开关是纯 UI 设置，直接读 prefs：走参数总线会被 applyDefaultsOnce 的
    // 「进程内只套一次」挡住，在设置页改完回录制页看不到效果
    val settingsPrefs = remember(app) { WotaSettings.of(app) }
    val hudMask = WotaSettings.hudItems(settingsPrefs)
    val levelBuzz = WotaSettings.levelBuzzEnabled(settingsPrefs)
    val focusPoint by params.focusPoint.observed()
    val lens by params.lens.observed()
    val audioEnabled by params.audioEnabled.observed()

    val runner = remember(ctrl) { RecordRunner(app, params, ctrl) }
    val recStatus by runner.status.observed()
    val recElapsed by runner.elapsedMs.observed()
    val recDb by runner.volumeDb.observed()
    val recording = recStatus == RecordStatus.START || recStatus == RecordStatus.PREPARE ||
        recStatus == RecordStatus.STOPPING

    var sheet by remember { mutableStateOf(Sheet.NONE) }
    var hint by remember { mutableStateOf<String?>(null) }
    var lastUri by remember { mutableStateOf<Uri?>(null) }
    var freeMb by remember { mutableStateOf(0L) }
    var btSheet by remember { mutableStateOf(false) }

    // ---- 就近胶囊：锚点矩形由各控件在布局期回报，弹窗同一帧就能量到位置
    val pillAnchors = remember { mutableStateMapOf<PillKey, IntRect>() }
    var pop by remember { mutableStateOf<PillKey?>(null) }
    /**
     * 控件挂锚点用：把 [pillAnchor] 贴到常驻控件上，点按时才有的坐标可锚。
     * 只在矩形真变了才写表——布局期写状态会重组，重组又重测，值没变还照写就是空转。
     */
    val anchorOf: (PillKey) -> Modifier = { key ->
        Modifier.pillAnchor { rect -> if (pillAnchors[key] != rect) pillAnchors[key] = rect }
    }
    /** 面板/抽屉/二级弹窗互斥：开任何一个都先把胶囊收掉，避免两层浮层叠字 */
    val openSheet: (Sheet) -> Unit = { s ->
        pop = null
        sheet = s
    }
    /** 只读一次总线现值，弹窗内的判据不依赖组合期的 observed 快照 */
    val closePill: () -> Unit = { pop = null }

    // ---- 提示与文案（录制失败码、锁定提示、近似帧率说明）
    val lockTipText = stringResource(R.string.cam_lock_tip)
    val unsupportedText = stringResource(R.string.unsupported_by_device)
    val approxTipText = stringResource(R.string.approx_mark_tip)
    val showTip: (String) -> Unit = { hint = it }
    val lockTip: () -> Unit = { showTip(lockTipText) }

    // 两阶段撤提示：先翻 shown=false 让 HintBar 跑完淡出，再撤掉 Popup。
    // 只有一阶段的话 Popup 窗口会跟着 hint 一起消失，淡出动画根本没机会画
    var hintShown by remember { mutableStateOf(false) }
    val hintMotion = LocalMotion.current
    LaunchedEffect(hint) {
        val text = hint ?: return@LaunchedEffect
        hintShown = true
        delay(HINT_MS)
        if (hint == text) {
            hintShown = false
            delay(hintMotion.durationMs.toLong())
            if (hint == text) hint = null
        }
    }
    LaunchedEffect(focusPoint) {
        if (focusPoint == null) return@LaunchedEffect
        delay(FOCUS_HINT_MS)
        params.focusPoint.value = null
    }
    LaunchedEffect(runner) {
        runner.result.collect { result ->
            if (result == null) return@collect
            if (result.ok) {
                lastUri = result.uri ?: lastUri
                repo.invalidate()
                // 登记失败不推翻「已保存」：文件确实在盘上，只是相册可能暂时看不见，得另外说
                val savedRes = if (result.uncommitted > 0) R.string.cam_record_saved_uncommitted
                else R.string.cam_record_saved
                showTip(app.getString(savedRes, formatDuration(result.durationMs)))
            } else {
                recordResultText(app.resources, result.error)?.let { showTip(it) }
            }
            runner.clearResult()
        }
    }
    LaunchedEffect(recStatus) { freeMb = runner.probeFreeSpace() }
    LaunchedEffect(Unit) { freeMb = runner.probeFreeSpace() }

    // ---- 权限：页面进入时补缺失权限，回调里让门面重查（缺麦克风只降级纯视频）
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { ctrl.onPermissionsResult() }
    DisposableEffect(ctrl) {
        val missing = ctrl.missingPermissions()
        if (missing.isNotEmpty()) permissionLauncher.launch(missing)
        onDispose { }
    }

    // ---- 预览 sink：GPU 用 GlRenderEngine，DIRECT 用 DirectSink（全程不建 EGL，见 camera/DirectSink 注释）
    val directSink = remember { DirectSink() }
    val activeSink: PreviewSink = remember(renderMode) {
        if (renderMode == RenderMode.GPU) GlRenderEngine() else directSink
    }
    val glEngine = activeSink as? GlRenderEngine
    DisposableEffect(ctrl, activeSink) {
        runner.glProvider = { activeSink as? GlRenderEngine }
        (activeSink as? GlRenderEngine)?.onCameraSurfaceReady = { ctrl.onPreviewSurfaceReady() }
        ctrl.bind(activeSink)
        ctrl.start()
        onDispose {
            ctrl.unbind(activeSink)
            (activeSink as? GlRenderEngine)?.release()
        }
    }

    // 预览流尺寸换了档 = 会话结构签名变了，催一次重建，别让会话继续吃旧尺寸的流
    DisposableEffect(directSink, ctrl) {
        directSink.onStreamSizeChanged = { ctrl.onPreviewSurfaceReady() }
        onDispose { directSink.onStreamSizeChanged = null }
    }

    // ---- GPU 特效档位实时下发；DIRECT 强制无特效（03 文档 §1）
    LaunchedEffect(
        glEngine, renderMode, frameEffect, zebraThreshold, zebraDensity, peakingStrength, peakingColor, curveStack
    ) {
        val gl = glEngine ?: return@LaunchedEffect
        gl.setEffect(if (renderMode == RenderMode.DIRECT) FrameEffect.NONE else frameEffect)
        gl.setZebraParameters(zebraThreshold, zebraDensity)
        gl.setPeakingParameters(peakingStrength, peakingColor)
        // 换渲染模式会 new 一个新引擎：曲线要在同一处补发，否则切回 GPU 就丢调色
        gl.setCurve(curveStack)
    }

    // ---- 方向：显示角喂给两条 sink，sink 内部再叠自己读到的缓冲矩阵旋转分量（见 camera/PreviewOrientation）
    // 槽位按 key 认（同档位可能有多颗镜头），认不到再退档位名
    val slot = remember(ui.lenses, ui.currentSlot, ui.currentLens, lens) {
        ui.currentSlot ?: ui.lenses.firstOrNull { it.type == ui.currentLens }
            ?: ui.lenses.firstOrNull { it.type == lens }
    }
    val ability = slot?.ability
    // #44：定焦镜头上「对焦」这件事整体没有意义 —— 胶囊入口与点按对焦一起收掉
    val focusUsable = ability?.hasAdjustableFocus() ?: true
    val isFront = (slot?.type ?: lens) == LensType.FRONT
    val sensorOrientation = ability?.sensorOrientation ?: 0
    var deviceDegrees by remember { mutableStateOf(0) }
    LaunchedEffect(owner, ui.device) { deviceDegrees = readDeviceDegrees(context) }
    // 正反向横屏（90↔270）窗口尺寸不变，onSizeChanged 不会触发，必须另挂显示监听才能跟着转
    DisposableEffect(app) {
        val manager = app.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                deviceDegrees = readDeviceDegrees(context)
            }
        }
        manager?.registerDisplayListener(listener, null)
        onDispose { manager?.unregisterDisplayListener(listener) }
    }
    // 残余角 = 传感器方向 − 显示旋转，由两个 sink 各自现算，UI 只喂显示角。
    // 引擎每次重开会话都会重写裸 sensorOrientation，所以 UI 侧不能再"一次性覆盖"残余角——
    // 覆盖值会在停止录制 / 错误恢复 / 换镜头后被冲掉，真机 GPU 预览顺时针 90° 就是这么来的。
    val glOrientation = PreviewOrientation.normalize(sensorOrientation - deviceDegrees)
    LaunchedEffect(glEngine, directSink, deviceDegrees) {
        glEngine?.setDisplayDegrees(deviceDegrees)
        directSink.setDisplayDegrees(deviceDegrees)
    }
    // 预览流候选尺寸必须跟着镜头换挡（PRIVATE 表），否则换镜头后仍拿旧镜头的尺寸表去选
    LaunchedEffect(directSink, slot) {
        directSink.setStreamCandidates(slot?.ability?.previewSizes ?: emptyList())
    }
    // 预览区宽高比按「画面转正后」的包围盒算，与 sink 的 contentRect 同一套语义
    val previewAspect = remember(size, glOrientation) {
        val swapped = glOrientation % 180 != 0
        val w = if (swapped) size.height else size.width
        val h = if (swapped) size.width else size.height
        if (h <= 0) 16f / 9f else w.toFloat() / h.toFloat()
    }

    // ---- 生命周期：ON_PAUSE 停录 + 停预览（02 文档 §7）
    DisposableEffect(ctrl, owner) {
        val stopOnPause = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) runner.stopAsync()
        }
        ctrl.attachLifecycle(owner.lifecycle)
        owner.lifecycle.addObserver(stopOnPause)
        onDispose {
            runner.shutdown()
            owner.lifecycle.removeObserver(stopOnPause)
            ctrl.detachLifecycle(owner.lifecycle)
            ctrl.stop()
        }
    }

    // ---- 并行模块：水平仪/俯仰仪与蓝牙音箱（实现已落盘，按其真实签名接线）
    val level = remember(app) { LevelSensor(app) }
    LaunchedEffect(levelBuzz) { level.buzzEnabled = levelBuzz }
    LaunchedEffect(deviceDegrees) {
        // 横屏页必须把显示方向喂给传感器换算，否则平举也会读出 ±90° 的横滚角
        level.setDisplayDegrees(deviceDegrees)
    }
    val roll by level.roll.observed()
    val pitch by level.pitch.observed()
    val bt = remember(app) { BtSpeakerController(app) }
    val btActive by bt.active.observed()
    val btVolumePct by bt.volumePercent.observed()
    DisposableEffect(level, owner) {
        // 加速度计只在录制页可见时采样（06 文档 §6 发热约束）
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> level.start()
                Lifecycle.Event.ON_PAUSE -> level.stop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        level.start()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            level.stop()
        }
    }
    DisposableEffect(bt) { onDispose { bt.close() } }

    val canFlash = ability?.flashAvailable == true
    // 控件条一律浮在预览之上（不再各占一条黑带）：画面吃满整屏，25% 透明的材质才透得出内容
    val hudItems = HudItem.typesOf(hudMask)

    val previewStage: @Composable (Modifier) -> Unit = { stageModifier ->
        Box(stageModifier, contentAlignment = Alignment.Center) {
            Box(
                // aspectRatio 不能写在 fillMaxSize 之后：先被撑满再按比例就完全失效，
                // 预览会退化成「按视图比例拉伸、无信箱黑边」（真机 DIRECT 实测过）
                Modifier.aspectRatio(previewAspect, matchHeightConstraintsFirst = previewAspect < 1f)
            ) {
                CameraSurface(
                    mode = renderMode,
                    sink = activeSink,
                    modifier = Modifier.matchParentSize(),
                    // 直显面尺寸变化会改会话结构签名，主动催一次重建；GL 路径同入口且幂等
                    onDisplaySurface = { surface, _, _ -> if (surface != null) ctrl.onPreviewSurfaceReady() }
                )
                RefLineOverlay(
                    mask = refLines,
                    aspect = previewAspect,
                    rollDegrees = roll,
                    showLevelLine = levelEnabled,
                    modifier = Modifier.matchParentSize()
                )
                FocusBox(point = focusPoint)
                Box(
                    Modifier
                        .matchParentSize()
                        .tapFocus(
                            onTap = { point -> if (focusUsable) writeFocusPoint(params, point) },
                            onLongPress = { point ->
                                // 组合动作：点按对焦 + 锁 AE；再长按解锁。直接读总线避免闭包取到旧值
                                writeFocusPoint(params, point)
                                when (params.aeMode.value) {
                                    AeMode.AUTO -> params.aeMode.value = AeMode.LOCK
                                    AeMode.LOCK -> params.aeMode.value = AeMode.AUTO
                                    else -> Unit
                                }
                            }
                        )
                )
                ParamsHud(
                    items = hudItems,
                    valueOf = { item ->
                        val aeAuto = aeMode != AeMode.MANUAL
                        when (item) {
                            HudItem.SHUTTER -> if (aeAuto) "AUTO" else shutterText(shutter.value)
                            HudItem.ISO -> if (aeAuto) "AUTO" else "${iso.value}"
                            HudItem.EV -> if (aeMode == AeMode.AUTO) evText(ev.value, ability?.evStep ?: 1f) else null
                            HudItem.WB -> wbShort(wbMode, kelvin.value)
                            HudItem.ZOOM -> String.format(java.util.Locale.US, "%.1fx", zoom.value)
                            HudItem.FPS ->
                                fps.value.toString() + if (!fps.exact) stringResource(R.string.approx_mark) else ""
                            // 必须走上面 observed() 的那份：直接读 params.bitrate.value 不会触发重绘，
                            // 在别处改完码率回到取景器，HUD 那张卡还停在旧值
                            HudItem.BITRATE -> "${bitrate / 1_000_000}M"
                        }
                    },
                    locked = aeMode == AeMode.LOCK,
                    onClick = { item ->
                        // 近似帧率先说明再让改：点上去弹一个"这档其实是 23.976"的浮层比直接改值有用
                        if (item == HudItem.FPS && !fps.exact) {
                            pop = PillKey.FPS
                            showTip(approxTipText)
                        } else {
                            pop = item.pillKey()
                        }
                    },
                    modifierFor = { item ->
                        // 变焦的锚点是右栏那颗常驻胶囊：HUD 上的「变焦」只是读数，
                        // 两处都抢写同一个 key 会让弹窗在两个位置之间抖
                        if (item == HudItem.ZOOM) Modifier else anchorOf(item.pillKey())
                    },
                    // 底栏压在预览区下沿，HUD 不避让就会与「监看/闪光灯」文字叠字（真机截图核对）
                    modifier = Modifier.align(Alignment.BottomStart).padding(bottom = BottomBarSpace)
                )
            }
        }
    }

    val topBar: @Composable (Modifier) -> Unit = { barModifier ->
        TopBar(
            lensLabel = stringResource(lensLabelRes(slot?.type ?: lens)),
            sizeLabel = sizeText(size),
            freeLabel = stringResource(R.string.cam_free_space, freeSpaceText(freeMb)),
            freeLow = freeMb < WotaTiers.MIN_FREE_MB,
            recording = recStatus == RecordStatus.START,
            elapsedLabel = formatDuration(recElapsed),
            status = recStatus,
            audioDegraded = ui.audioDegraded,
            legacy = ability?.isLegacy() == true,
            device = ui.device,
            renderMode = renderMode,
            effect = frameEffect,
            sizeModifier = anchorOf(PillKey.SIZE),
            lensModifier = anchorOf(PillKey.LENS),
            freeModifier = anchorOf(PillKey.STORAGE),
            modifier = barModifier,
            onSizeClick = { pop = PillKey.SIZE },
            onLensClick = { pop = PillKey.LENS },
            onFreeClick = { pop = PillKey.STORAGE },
            onSettingsClick = { if (recording) lockTip() else onOpenSettings() }
        )
    }

    val bottomBar: @Composable (Modifier) -> Unit = { barModifier ->
        BottomBar(
            refLineOn = refLines != 0,
            effect = frameEffect,
            flash = flash,
            flashAvailable = canFlash,
            curveOn = !curveStack.isPassthrough,
            recording = recStatus == RecordStatus.START,
            busy = recStatus == RecordStatus.PREPARE || recStatus == RecordStatus.STOPPING,
            lastUri = lastUri,
            modifier = barModifier,
            refLineModifier = anchorOf(PillKey.REFLINE),
            monitorModifier = anchorOf(PillKey.MONITOR),
            flashModifier = anchorOf(PillKey.FLASH),
            onRefLineClick = { pop = PillKey.REFLINE },
            onMonitorClick = { pop = PillKey.MONITOR },
            onFlashClick = { pop = PillKey.FLASH },
            onCurveClick = { openSheet(Sheet.CURVE) },
            onRecordClick = {
                when {
                    recStatus == RecordStatus.START -> runner.stopAsync()
                    recStatus != RecordStatus.IDLE -> Unit
                    ui.preview != PreviewStatus.ING -> showTip(app.getString(R.string.cam_wait_preview))
                    else -> runner.start(
                        width = size.width,
                        height = size.height,
                        fps = fps.value,
                        sensorOrientation = sensorOrientation,
                        deviceDegrees = deviceDegrees,
                        front = isFront
                    )
                }
            },
            onThumbClick = { if (recording) lockTip() else onOpenGallery() }
        )
    }

    Box(
        modifier
            .fillMaxSize()
            .background(WotaBg)
            .onSizeChanged { deviceDegrees = readDeviceDegrees(context) }
    ) {
        // 画面先铺满整屏，四边控件条浮在它上面：横竖屏共用一套布局，各自那条不透明黑带随之消失。
        // 控件条只按 safeDrawing / displayCutout 避让系统栏与挖孔，不再写死边距数值。
        previewStage(Modifier.fillMaxSize())
        // 面板打开时收起四周一圈控件：它们本来就被点外关闭层挡住点不到，留着只会和面板叠字
        if (sheet == Sheet.NONE) {
            RightRail(
                roll = roll,
                pitch = pitch,
                levelEnabled = levelEnabled,
                db = recDb,
                showVolume = recording && audioEnabled,
                btConnected = btActive?.connected == true,
                btVolumePct = btVolumePct,
                zoomLabel = String.format(java.util.Locale.US, "%.1fx", zoom.value),
                focusLabel = stringResource(R.string.pill_focus),
                showFocus = focusUsable,
                focusActive = afMode == AfMode.MANUAL,
                stabLabel = stringResource(R.string.cam_p_stab),
                stabActive = stabilize != Stabilize.OFF,
                stabModifier = anchorOf(PillKey.STAB),
                zoomModifier = anchorOf(PillKey.ZOOM),
                focusModifier = anchorOf(PillKey.FOCUS),
                onZoomClick = { pop = PillKey.ZOOM },
                onFocusClick = { pop = PillKey.FOCUS },
                onStabClick = { pop = PillKey.STAB },
                onBtClick = { btSheet = true },
                modifier = Modifier.align(Alignment.CenterEnd).displayCutoutPadding()
            )
            bottomBar(Modifier.align(Alignment.BottomCenter).safeDrawingPadding())
        }
        topBar(Modifier.align(Alignment.TopCenter).safeDrawingPadding())

        // 就近胶囊：只在没有整块面板时挂出，锚点还没量到时按屏幕原点弹（下一帧就修正，不闪）
        val popped = pop
        if (popped != null && sheet == Sheet.NONE) {
            PillHost(
                key = popped,
                anchor = pillAnchors[popped] ?: IntRect.Zero,
                params = params,
                slot = slot,
                recording = recording,
                gpuMode = renderMode == RenderMode.GPU,
                onClose = closePill,
                onLockTip = lockTip,
                onUnsupported = { showTip(unsupportedText) },
                onFocusCenter = { if (focusUsable) writeFocusPoint(params, TapPoint(0.5f, 0.5f)) },
                onPickLens = { picked -> ctrl.switchLens(picked) },
                freeMb = freeMb,
            )
        }

        CurveSheet(
            params = params,
            gpuMode = renderMode == RenderMode.GPU,
            onDismiss = { sheet = Sheet.NONE },
            visible = sheet == Sheet.CURVE,
            modifier = Modifier.matchParentSize()
        )
        if (btSheet) {
            BtSpeakerSheet(bt) { btSheet = false }
        }
        // 提示必须与胶囊同一层甚至更高：胶囊是独立窗口，写在主窗口里的提示会被它整个盖住，
        // 于是「录制中不能改画幅」这句话在真机上点了锁定项也看不见（v0.0.2 胶囊改造实测）
        hint?.let { text ->
            Popup(
                alignment = Alignment.TopCenter,
                properties = PopupProperties(focusable = false, dismissOnClickOutside = false)
            ) {
                HintBar(text = text, shown = hintShown, modifier = Modifier.padding(top = 76.dp))
            }
        }
        // 录制中会话会因换输出面而重建，预览瞬间回到 START，此时不能盖遮罩。
        // 遮罩本身改成淡入淡出：镜头切换 / 会话重建时它原来是「啪地盖上、啪地撤掉」，
        // 但淡入淡出只处理这层的进出，加载该多久还是多久 —— 不用动画去掩盖真实延迟
        val gateMotion = LocalMotion.current
        AnimatedVisibility(
            visible = ui.preview != PreviewStatus.ING && recStatus == RecordStatus.IDLE,
            enter = fadeIn(gateMotion.float),
            exit = fadeOut(gateMotion.float),
            modifier = Modifier.matchParentSize()
        ) {
            PreviewGate(
                opening = ui.device == DeviceStatus.OPEN_ING,
                needPermission = ui.needsCameraPermission,
                modifier = Modifier.fillMaxSize(),
                onRetry = {
                    val missing = ctrl.missingPermissions()
                    if (missing.isNotEmpty()) permissionLauncher.launch(missing) else ctrl.restartPreview()
                },
                onOpenSettings = { context.openAppSettings() }
            )
        }
    }
}

/** 面板互斥态（就近胶囊不占这里：它可以和顶栏共存，只跟整块面板互斥） */
private enum class Sheet { NONE, CURVE }

private const val HINT_MS = 2_000L
private const val FOCUS_HINT_MS = 1_500L
private const val LED_COUNT = 6
private const val MIN_DB = 20f
private const val MAX_DB = 110f
private const val TAG_UI = "WotaUi"

/** 常驻 HUD 每行最多几格；超出的换行，避免小屏上把画面横切成一条 */
private const val HUD_PER_ROW = 3

/**
 * HUD 格 → 就近胶囊。一一对应，所以新增 [HudItem] 时这里会编译不过——
 * 故意的：逼着同时补内容宿主，避免出现「点了没反应」的格子。
 */
private fun HudItem.pillKey(): PillKey = when (this) {
    HudItem.SHUTTER -> PillKey.SHUTTER
    HudItem.FPS -> PillKey.FPS
    HudItem.BITRATE -> PillKey.BITRATE
    HudItem.ISO -> PillKey.ISO
    HudItem.EV -> PillKey.EV
    HudItem.WB -> PillKey.WB
    HudItem.ZOOM -> PillKey.ZOOM
}

/**
 * 底栏在预览区下沿占位的高度：录制键 50dp + 底栏上下内边距 3dp×2 + 4dp 呼吸。
 * 预览内 HUD 用它避让，改底栏控件尺寸时同步改这里。
 */
private val BottomBarSpace = 60.dp

// ------------------------------------------------------------------ 顶栏

@Composable
@Suppress("LongParameterList")
private fun TopBar(
    lensLabel: String,
    sizeLabel: String,
    freeLabel: String,
    freeLow: Boolean,
    recording: Boolean,
    elapsedLabel: String,
    status: RecordStatus,
    audioDegraded: Boolean,
    legacy: Boolean,
    device: DeviceStatus,
    renderMode: RenderMode,
    effect: FrameEffect,
    sizeModifier: Modifier = Modifier,
    lensModifier: Modifier = Modifier,
    freeModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
    onSizeClick: () -> Unit,
    onLensClick: () -> Unit,
    onFreeClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    Column(modifier.fillMaxWidth()) {
        // 参考图顶栏是一排浮在画面上的胶囊，不是一条通栏黑带
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            WotaChip(label = lensLabel, selected = false, modifier = lensModifier, onClick = onLensClick)
            WotaChip(label = sizeLabel, selected = false, modifier = sizeModifier, onClick = onSizeClick)
            // 帧率读数在可自定义的常驻 HUD，顶栏只留「镜头 · 画幅 · 剩余空间」
            WotaChip(
                label = freeLabel,
                selected = false,
                modifier = freeModifier,
                valueColor = if (freeLow) WotaRec else null,
                onClick = onFreeClick
            )
            // 计时与状态是**读数**，不是入口：给它们挂 onClick 只会让人以为点开有配置项（§37 第 4 条）
            // 计时 / 状态胶囊是取景器里最显眼的一次进出（按快门就出现），硬切会让人觉得画面顿了一下。
            // 只淡入缩放、不展开宽度：布局一次到位，旁边几颗胶囊不会跟着挤。
            val motion = LocalMotion.current
            val stateLabel = when (status) {
                RecordStatus.PREPARE -> stringResource(R.string.cam_state_prepare)
                RecordStatus.STOPPING -> stringResource(R.string.cam_state_stopping)
                else -> null
            }
            AnimatedVisibility(
                visible = recording || stateLabel != null,
                enter = fadeIn(motion.float) + scaleIn(motion.float, initialScale = 0.86f),
                exit = fadeOut(motion.float) + scaleOut(motion.float, targetScale = 0.86f)
            ) {
                if (recording) {
                    WotaChip(label = elapsedLabel, selected = false, valueColor = WotaRec, dot = WotaRec)
                } else {
                    WotaChip(label = stateLabel.orEmpty(), selected = false, valueColor = WotaWarn)
                }
            }
            Spacer(Modifier.weight(1f))
            WotaIconButton(
                image = Icons.Filled.Settings,
                description = stringResource(R.string.cam_settings),
                onClick = onSettingsClick
            )
        }
        // 能力/权限告警条：高帧率降级 > 缺麦克风 > LEGACY > DIRECT 下特效失效
        val warnRes = when {
            device == DeviceStatus.OPEN_FAILED_HIGH_FPS -> R.string.cam_state_high_fps
            audioDegraded -> R.string.no_audio_record_tip
            legacy -> R.string.capability_limited_tip
            effect != FrameEffect.NONE && renderMode == RenderMode.DIRECT -> R.string.cam_direct_no_effect
            else -> null
        }
        warnRes?.let {
            Text(
                text = stringResource(it),
                style = MaterialTheme.typography.labelSmall,
                color = WotaWarn,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 10.dp, end = 10.dp, top = 2.dp)
                    .wotaCard(WotaShape.pill)
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun HudText(text: String, onClick: () -> Unit, tint: Color = WotaText) {
    val motion = LocalMotion.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) motion.pressScale else 1f, motion.float)
    Text(
        text = text,
        style = MonoStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
        color = tint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(6.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 3.dp)
    )
}

@Composable
private fun Dot() {
    Box(Modifier.padding(horizontal = 1.dp).size(3.dp).background(WotaDivider, CircleShape))
}

// ------------------------------------------------------------------ 预览内 HUD

/**
 * 常驻参数读数：上屏哪几项由设置页 `hud_items` 决定，默认「快门 / 帧率 / 码率」。
 * 每格点按弹自己的就近胶囊（[modifierFor] 负责把锚点矩形回报给上层）；
 * [valueOf] 返回 null 表示该项当前不适用，直接不画。
 */
@Composable
private fun ParamsHud(
    items: List<HudItem>,
    valueOf: @Composable (HudItem) -> String?,
    locked: Boolean,
    onClick: (HudItem) -> Unit,
    modifierFor: (HudItem) -> Modifier,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty() && !locked) return
    val motion = LocalMotion.current
    // 参考图里参数是「一张一张独立小卡」，不是一整块面板，所以这里不再套外层底
    Column(
        modifier.padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items.chunked(HUD_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { item ->
                    val value = valueOf(item)
                    // 值变 null（如 AE 锁定时 EV 不适用）不该「啪」地消失。但 MotionSpec 明确
                    // 不许在预览层做布局参数动画，所以只淡出 + 微沉，不用 expandIn/shrinkOut
                    var lastValue by remember(item) { mutableStateOf(value) }
                    if (value != null) lastValue = value
                    AnimatedVisibility(
                        visible = value != null,
                        enter = fadeIn(motion.float) + slideInVertically(motion.offset) { it / 3 },
                        exit = fadeOut(motion.float) + slideOutVertically(motion.offset) { it / 3 }
                    ) {
                        WotaValueCard(
                            label = stringResource(item.labelRes),
                            value = lastValue.orEmpty(),
                            accentValue = lastValue == "AUTO",
                            modifier = modifierFor(item),
                            onClick = { onClick(item) }
                        )
                    }
                }
            }
        }
        // 长按对焦锁 AE 时这颗提示凭空出现，是最容易被当成「画面闪了一下」的硬切
        AnimatedVisibility(
            visible = locked,
            enter = fadeIn(motion.float) + slideInVertically(motion.offset) { it },
            exit = fadeOut(motion.float) + slideOutVertically(motion.offset) { it }
        ) {
            Text(
                text = stringResource(R.string.cam_ae_lock),
                style = MaterialTheme.typography.labelSmall,
                color = WotaWarn,
                modifier = Modifier
                    .wotaCard(WotaShape.pill)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun HudValue(label: String, value: String, onClick: () -> Unit) {
    val motion = LocalMotion.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) motion.pressScale else 1f, motion.float)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(6.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 1.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = WotaTextDim)
        Spacer(Modifier.width(3.dp))
        Text(
            text = value,
            style = MonoStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
            color = WotaText
        )
    }
}

/** 白平衡读数：手动档显示色温，其余显示预设名（预设名走资源） */
@Composable
private fun wbShort(mode: WbPreset, kelvin: Int): String =
    if (mode == WbPreset.MANUAL) "${kelvin}K" else stringResource(wbLabelRes(mode))

// ------------------------------------------------------------------ 右侧仪表列

/**
 * 右栏：水平仪 + 音量表 + 蓝牙，再加两颗常驻的「变焦 / 对焦」胶囊（用户 2026-09-27 定的位置）。
 * 这两颗是取景时最高频的调节，所以不进 HUD 可选项、也不藏进任何面板，直接常驻可点。
 */
@Composable
@Suppress("LongParameterList")
private fun RightRail(
    roll: Float,
    pitch: Float,
    levelEnabled: Boolean,
    db: Float,
    showVolume: Boolean,
    btConnected: Boolean,
    btVolumePct: Int,
    zoomLabel: String,
    focusLabel: String,
    showFocus: Boolean,
    focusActive: Boolean,
    stabLabel: String,
    stabActive: Boolean,
    stabModifier: Modifier,
    zoomModifier: Modifier,
    focusModifier: Modifier,
    onZoomClick: () -> Unit,
    onFocusClick: () -> Unit,
    onStabClick: () -> Unit,
    onBtClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 竖向居中而不是「贴顶 + 写死 34dp」：上下控件条现在浮在画面之上，靠边排就会被压住
    Column(
        modifier.width(54.dp).fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, alignment = Alignment.CenterVertically)
    ) {
        LevelBadge(roll, pitch, levelEnabled)
        if (showVolume) VolumeLeds(db)
        BtChip(connected = btConnected, volumePct = btVolumePct, onClick = onBtClick)
        WotaChip(
            label = zoomLabel,
            selected = false,
            modifier = zoomModifier,
            onClick = onZoomClick
        )
        // #44：定焦镜头没有一样东西能调，整颗入口隐藏，不摆一排灰选项
        if (showFocus) {
            WotaChip(
                label = focusLabel,
                selected = focusActive,
                modifier = focusModifier,
                onClick = onFocusClick
            )
        }
        WotaChip(
            label = stabLabel,
            selected = stabActive,
            modifier = stabModifier,
            onClick = onStabClick
        )
    }
}

/** 音量表：`amplitude()` → dB 后 6 格 LED，仅录制中显示（06 文档 §4） */
@Composable
private fun VolumeLeds(db: Float) {
    val lit = (((db.coerceIn(MIN_DB, MAX_DB) - MIN_DB) / (MAX_DB - MIN_DB)) * LED_COUNT).roundToInt()
    Column(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(WotaHudScrim)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Filled.GraphicEq,
            contentDescription = stringResource(R.string.cam_volume_meter),
            tint = WotaTextDim,
            modifier = Modifier.size(13.dp)
        )
        Spacer(Modifier.height(4.dp))
        // 自顶向下画，点亮数从底部起算
        for (index in LED_COUNT downTo 1) {
            val on = index <= lit
            val color = when {
                !on -> WotaDivider
                index >= LED_COUNT -> WotaRec
                index == LED_COUNT - 1 -> WotaWarn
                else -> WotaAccent
            }
            Box(
                Modifier
                    .padding(vertical = 1.dp)
                    .width(16.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}

// ------------------------------------------------------------------ 底栏

@Composable
@Suppress("LongParameterList")
private fun BottomBar(
    refLineOn: Boolean,
    effect: FrameEffect,
    flash: Flash,
    flashAvailable: Boolean,
    curveOn: Boolean,
    recording: Boolean,
    busy: Boolean,
    lastUri: Uri?,
    refLineModifier: Modifier = Modifier,
    monitorModifier: Modifier = Modifier,
    flashModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
    onRefLineClick: () -> Unit,
    onMonitorClick: () -> Unit,
    onFlashClick: () -> Unit,
    onCurveClick: () -> Unit,
    onRecordClick: () -> Unit,
    onThumbClick: () -> Unit
) {
    // 参考图底栏没有通栏黑带，而且录制键恒在屏幕正中：左右两组用 Box 分别贴边，
    // 用两个等权 Spacer 会被较宽的一侧挤偏（真机截图核对过）
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(
            Modifier.align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            WotaIconButton(
                image = Icons.Filled.GridOn,
                description = stringResource(R.string.cam_p_refline),
                selected = refLineOn,
                modifier = refLineModifier,
                onClick = onRefLineClick
            )
            WotaChip(
                label = stringResource(
                    if (effect == FrameEffect.NONE) R.string.cam_p_monitor else effectShortRes(effect)
                ),
                selected = effect != FrameEffect.NONE,
                modifier = monitorModifier,
                onClick = onMonitorClick
            )
            // 曲线与斑马纹同级，是创作项，不该藏在「更多」里（§37 第 2 条）
            WotaChip(
                label = stringResource(R.string.cam_p_curve),
                selected = curveOn,
                onClick = onCurveClick
            )
            // 本机没闪光灯就不占这一格（§37 第 5 条）：能力一律从 characteristics 读
            if (flashAvailable) {
                WotaIconButton(
                    image = flashIcon(flash),
                    description = stringResource(flashLabelRes(flash)),
                    selected = flash != Flash.OFF,
                    modifier = flashModifier,
                    onClick = onFlashClick
                )
            }
        }
        RecordButton(
            recording = recording,
            busy = busy,
            modifier = Modifier.align(Alignment.Center),
            onClick = onRecordClick
        )
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .size(40.dp)
                .wotaCard(WotaShape.medium)
                .clickable(onClick = onThumbClick)
        ) {
            val uri = lastUri
            if (uri != null) {
                VideoThumbnail(uri = uri, modifier = Modifier.matchParentSize(), px = 160)
            } else {
                Icon(
                    Icons.Filled.PhotoLibrary,
                    contentDescription = stringResource(R.string.cam_gallery_entry),
                    tint = WotaTextDim,
                    modifier = Modifier.padding(9.dp).size(22.dp)
                )
            }
        }
    }
}

@StringRes
private fun effectShortRes(effect: FrameEffect): Int = when (effect) {
    FrameEffect.ZEBRA -> R.string.cam_effect_zebra
    FrameEffect.PEAKING -> R.string.cam_effect_peaking
    FrameEffect.NONE -> R.string.cam_p_monitor
}

/**
 * 闪光四档图标。material-icons-extended 1.5.4 里没有 `AutoFlash`/`Torch` 这两个图标名，
 * 自动闪光用 `FlashAuto`、常亮手电用 `FlashlightOn`（语义一致且同包可解析）。
 */
private fun flashIcon(flash: Flash): ImageVector = when (flash) {
    Flash.OFF -> Icons.Filled.FlashOff
    Flash.ON -> Icons.Filled.FlashOn
    Flash.AUTO -> Icons.Filled.FlashAuto
    Flash.TORCH -> Icons.Filled.FlashlightOn
}

@Composable
private fun IconHud(
    icon: ImageVector,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val motion = LocalMotion.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) motion.pressScale else 1f, motion.float)
    Box(
        Modifier
            .size(38.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(10.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (selected) WotaAccent else WotaText,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun TextHud(text: String, selected: Boolean, onClick: () -> Unit) {
    val motion = LocalMotion.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) motion.pressScale else 1f, motion.float)
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (selected) WotaAccent else WotaText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(8.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    )
}

/** 录制键：外圈常驻，内部圆点（待机）↔ 方角块（停止），中间态用转圈 */
@Composable
private fun RecordButton(
    recording: Boolean,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val motion = LocalMotion.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) motion.pressScale else 1f, motion.float)
    Box(
        modifier
            .size(WotaHit.recordTouch)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(WotaHit.recordRing)
                .border(3.dp, if (recording) WotaRec else WotaText, CircleShape)
                .padding(5.dp),
            contentAlignment = Alignment.Center
        ) {
            if (busy) {
                CircularProgressIndicator(color = WotaRec, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            } else if (recording) {
                Box(Modifier.size(WotaHit.recordStop).background(WotaRec, RoundedCornerShape(5.dp)))
            } else {
                Box(Modifier.size(WotaHit.recordDot).background(WotaRec, CircleShape))
            }
        }
    }
}

// ------------------------------------------------------------------ 取景覆盖

/**
 * 点按对焦框：黄框 + 中心点，出现时由大到小回弹（06 文档 §4 跟手动画）。
 * 带 [BoxScope] 接收者：覆盖层要与预览同尺寸，只能在 Box 内容作用域里取 `matchParentSize()`。
 */
@Composable
private fun BoxScope.FocusBox(point: TapPoint?) {
    val motion = LocalMotion.current
    val visible = point != null
    val alpha by animateFloatAsState(if (visible) 1f else 0f, motion.float)
    val scale by animateFloatAsState(if (visible) 1f else 1.6f, motion.float)
    Canvas(Modifier.matchParentSize().alpha(alpha)) {
        val p = point ?: return@Canvas
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val cx = p.x * size.width
        val cy = p.y * size.height
        val half = 72.dp.toPx() / 2f * scale
        val stroke = 2.dp.toPx()
        drawRect(
            color = WotaWarn,
            topLeft = Offset(cx - half, cy - half),
            size = GeoSize(half * 2f, half * 2f),
            style = Stroke(width = stroke)
        )
        drawCircle(color = WotaWarn, radius = 2.5.dp.toPx(), center = Offset(cx, cy))
    }
}

/** 顶部横幅提示（不用 Snackbar，避免与取景器抢层） */
/**
 * 顶部短提示。只在有文案时挂（调用方用独立 `Popup` 包住它），
 * 所以这里不再需要淡出动画——淡出留给文案自己那 2 秒的生命周期。
 */
@Composable
private fun HintBar(text: String, shown: Boolean, modifier: Modifier = Modifier) {
    // 提示条挂在独立 Popup 里，Popup 一组合就要动：animateFloatAsState 首次组合直接落在
    // 目标值不会跑，所以用 Animatable 从 0 起。退场同理 —— 由上层的 shown 翻 false 驱动，
    // 等动画跑完再真正撤掉 Popup（见 CameraScreen 里 hintShown 那段两阶段计时）
    val mtn = LocalMotion.current
    val appear = remember { androidx.compose.animation.core.Animatable(0f) }
    val slidePx = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.toPx() }
    LaunchedEffect(shown) { appear.animateTo(if (shown) 1f else 0f, mtn.float) }
    Box(
        modifier
            .graphicsLayer {
                alpha = appear.value
                translationY = -(1f - appear.value) * slidePx
            }
            .clip(RoundedCornerShape(10.dp))
            .background(WotaSurface.copy(alpha = 0.94f))
            .border(1.dp, WotaDivider, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = WotaText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 预览未就绪时的遮罩：只给重试与去设置，不遮挡录制条 */
@Composable
private fun PreviewGate(
    opening: Boolean,
    needPermission: Boolean,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Box(modifier.background(WotaBg.copy(alpha = 0.62f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (opening) {
                CircularProgressIndicator(color = WotaAccent, strokeWidth = 2.dp)
            } else {
                Text(
                    text = stringResource(if (needPermission) R.string.cam_no_camera_perm else R.string.cam_preview_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = WotaText
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    GateButton(stringResource(R.string.retry), onClick = onRetry)
                    GateButton(stringResource(R.string.perm_open_settings), onClick = onOpenSettings)
                }
            }
        }
    }
}

@Composable
private fun GateButton(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = WotaAccent,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

/** 点按/长按预览区：容器已按画面比例布局，故视图坐标即画面归一化坐标 */
private fun Modifier.tapFocus(onTap: (TapPoint) -> Unit, onLongPress: (TapPoint) -> Unit): Modifier =
    pointerInput(Unit) {
        detectTapGestures(
            onTap = { offset -> onTap(toTapPoint(offset, size.width, size.height)) },
            onLongPress = { offset -> onLongPress(toTapPoint(offset, size.width, size.height)) }
        )
    }

private fun toTapPoint(offset: Offset, viewW: Int, viewH: Int): TapPoint =
    if (viewW <= 0 || viewH <= 0) TapPoint(0.5f, 0.5f)
    else TapPoint(
        (offset.x / viewW).coerceIn(0f, 1f),
        (offset.y / viewH).coerceIn(0f, 1f)
    )

/**
 * 写点按对焦：先置 null 再写新值。
 * 总线是 StateFlow，同一点重复轻触会被相等值去重而丢掉一次对焦触发，置空可保证每次都下发。
 */
private fun writeFocusPoint(params: WotaParams, point: TapPoint) {
    params.focusPoint.value = null
    params.focusPoint.value = point
}

// ------------------------------------------------------------------ 录制链路

/**
 * 录制控制：`prepare/start/stop` 全部投到带 Looper 的工作线程（[Recorder] 线程契约），
 * UI 只读 [status]/[elapsedMs]/[volumeDb]/[result] 四条流，200ms 轮询一次时长与振幅（04 文档 §6）。
 *
 * 编码面交接：DIRECT → [CameraController.setRecordingTarget]（会话形状变化会重建会话）；
 * GPU → [GlRenderEngine.setOutputSurface]（尺寸必须与编码器逐像素一致，03 文档 §3.8）。
 */
private class RecordRunner(
    private val app: Context,
    private val params: WotaParams,
    private val ctrl: CameraController
) {

    private val thread = HandlerThread("WotaRecordCtl").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile
    var glProvider: () -> GlRenderEngine? = { null }

    private var recorder: Recorder? = null
    private var polling = false

    val status = MutableStateFlow(RecordStatus.IDLE)
    val elapsedMs = MutableStateFlow(0L)
    val volumeDb = MutableStateFlow(MIN_DB - 40f)
    val result = MutableStateFlow<RecordResult?>(null)

    fun probeFreeSpace(): Long = runCatching { VideoStore(app).freeSpaceMb() }.getOrDefault(0L)

    /**
     * 起录状态机：IDLE → PREPARE →（等会话/编码面就绪）→ START。
     * 校验顺序：可重入守卫 → 建 pending → prepare → 取编码面 → 交给相机 → start。
     */
    @Suppress("LongParameterList")
    fun start(width: Int, height: Int, fps: Int, sensorOrientation: Int, deviceDegrees: Int, front: Boolean) {
        handler.post {
            if (status.value != RecordStatus.IDLE) {
                Log.i(TAG_UI, "录制已在 ${status.value}，忽略重复 start")
                return@post
            }
            status.value = RecordStatus.PREPARE
            val store = VideoStore(app)
            store.checkFreeSpace()?.let { code ->
                failNow(code); return@post
            }
            val renderMode = params.renderMode.value
            val profile = buildProfile(width, height, fps, sensorOrientation, deviceDegrees, front, renderMode)
            val pending = store.createPending(0)
            if (pending == null) {
                failNow(RecordError.NO_OUTPUT); return@post
            }
            val sink = OutputSink.Pending(pending)
            val rec = Recorders.create(app, profile, store)
            recorder = rec
            if (!rec.prepare(profile, sink)) {
                rec.release()
                // prepare 内部已按原因码记日志；这里把余量不足与通用失败区分给 UI
                failNow(store.checkFreeSpace() ?: RecordError.PREPARE_FAILED)
                return@post
            }
            val surface = rec.surface as? Surface
            if (surface == null) {
                rec.stop()
                rec.release()
                failNow(RecordError.NO_OUTPUT); return@post
            }
            if (renderMode == RenderMode.DIRECT) {
                ctrl.setRecordingTarget(surface)
                awaitPreview(PreviewStatus.ING)
            } else {
                glProvider()?.setOutputSurface(surface, profile.width, profile.height, profile.fps)
                awaitEncoderSurface()
            }
            rec.start()
            status.value = RecordStatus.START
            polling = true
            handler.post(pollTask)
            Log.i(
                TAG_UI,
                "record start ${profile.width}x${profile.height}@${profile.fps} gpu=${profile.useGpu} " +
                    "hint=${profile.orientationHint} audio=${profile.audioEnabled}"
            )
        }
    }

    /** 非阻塞停止（ON_PAUSE 用）：投递到工作线程，避免卡主线程 */
    fun stopAsync() {
        handler.post { stopInternal() }
    }

    /** 退出页面：同步收尾，保证文件写完、pending 清掉之后再让相机释放 */
    fun shutdown() {
        val latch = CountDownLatch(1)
        handler.post {
            runCatching { stopInternal() }
            latch.countDown()
            thread.quitSafely()
        }
        val ok = runCatching { latch.await(3, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!ok) Log.w(TAG_UI, "录制收尾超时，交给引擎兜底")
    }

    fun clearResult() {
        result.value = null
    }

    // ------- 以下都在工作线程执行

    /**
     * 200ms 轮询任务。写成 `object : Runnable` 而不是 `Runnable { … postDelayed(pollTask) }`：
     * 后者在自身初始化表达式里引用 `pollTask`，省略类型标注会撞上递归类型检查，
     * 补上类型标注又会被判「变量未初始化」；用匿名对象体，重投时直接 `this` 自引用。
     */
    private val pollTask: Runnable = object : Runnable {
        override fun run() {
            val rec = recorder
            if (!polling || rec == null) return
            elapsedMs.value = rec.elapsedMs
            volumeDb.value = AudioProbe.dbOf(rec.amplitude())
            if (status.value == RecordStatus.START) handler.postDelayed(this, POLL_MS)
        }
    }

    private fun stopInternal() {
        val rec = recorder
        if (rec == null) {
            polling = false
            if (status.value != RecordStatus.IDLE) status.value = RecordStatus.IDLE
            return
        }
        polling = false
        status.value = RecordStatus.STOPPING
        val out = rec.stop()
        rec.release()
        recorder = null
        // AOSP 顺序：先 stop 编码器，再把编码面从会话里摘掉
        if (params.renderMode.value == RenderMode.DIRECT) ctrl.setRecordingTarget(null)
        else glProvider()?.setOutputSurface(null, 0, 0)
        elapsedMs.value = out.durationMs
        volumeDb.value = MIN_DB - 40f
        status.value = if (out.error != null) RecordStatus.ERROR else RecordStatus.IDLE
        result.value = out
    }

    private fun failNow(code: String) {
        polling = false
        recorder = null
        status.value = RecordStatus.ERROR
        result.value = RecordResult.fail(code)
        handler.postDelayed({ if (status.value == RecordStatus.ERROR) status.value = RecordStatus.IDLE }, 80L)
        Log.w(TAG_UI, "录制失败：$code")
    }

    /** 会话重建是异步的（DIRECT 换了输出面），等预览回到 ING 再 start，避免编码器拿不到帧 */
    private fun awaitPreview(target: PreviewStatus) {
        var waited = 0L
        while (waited < WAIT_MS) {
            if (ctrl.state.value.preview == target) return
            Thread.sleep(POLL_SLICE_MS)
            waited += POLL_SLICE_MS
        }
        Log.w(TAG_UI, "等待预览会话重建超时，继续 start")
    }

    private fun awaitEncoderSurface() {
        val gl = glProvider() ?: return
        var waited = 0L
        while (waited < WAIT_MS && !gl.isOutputSurfaceBound()) {
            Thread.sleep(POLL_SLICE_MS)
            waited += POLL_SLICE_MS
        }
    }

    private fun buildProfile(
        width: Int,
        height: Int,
        fps: Int,
        sensorOrientation: Int,
        deviceDegrees: Int,
        front: Boolean,
        renderMode: RenderMode
    ): RecordProfile = RecordProfile(
        width = width,
        height = height,
        fps = fps,
        captureRate = fps.toFloat(),
        bitrate = params.bitrate.value,
        codec = if (width.toLong() * height.toLong() >= HEVC_MIN_PIXELS) {
            RecordProfile.CODEC_HEVC
        } else {
            RecordProfile.CODEC_H264
        },
        sampleRate = params.sampleRate.value,
        channels = DEFAULT_AUDIO_CHANNELS,
        audioEnabled = params.audioEnabled.value,
        orientationHint = recordOrientationHint(
            sensorOrientation = sensorOrientation,
            deviceDegrees = deviceDegrees,
            front = front,
            direct = renderMode == RenderMode.DIRECT
        ),
        mirrored = front,
        useGpu = renderMode == RenderMode.GPU
    )

    companion object {
        private const val POLL_MS = 200L
        private const val POLL_SLICE_MS = 20L
        private const val WAIT_MS = 1_500L
    }
}

/** 4K 及以上默认走 HEVC（参数总线没有编码格式项，见交付说明缺口） */
private const val HEVC_MIN_PIXELS = 4L * 1920 * 1080

/** 设备显示旋转角（录制页锁横屏，取 0/90/180/270） */
@Suppress("DEPRECATION")
private fun readDeviceDegrees(context: Context): Int = try {
    val rotation = context.findActivity()
        ?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
    when (rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
} catch (e: Exception) {
    Log.w(TAG_UI, "读取显示旋转失败：${e.message}")
    0
}

/** 录制失败码 → 中文提示：机器码在 record 包，文案按码在此映射 */
private fun recordResultText(res: Resources, code: String?): String? {
    if (code == null) return null
    val freeMb = code.substringAfter(':', "").toLongOrNull()
    return when {
        code.startsWith(RecordError.LOW_STORAGE) && freeMb != null ->
            res.getString(R.string.cam_record_low_storage, freeMb, WotaTiers.MIN_FREE_MB)
        code.startsWith(RecordError.LOW_STORAGE) -> res.getString(R.string.cam_record_low_storage_short)
        code == RecordError.NO_OUTPUT -> res.getString(R.string.cam_record_no_output)
        code == RecordError.PREPARE_FAILED -> res.getString(R.string.cam_record_prepare_failed)
        code == RecordError.START_FAILED -> res.getString(R.string.cam_record_start_failed)
        code.startsWith(RecordError.ENGINE_ERROR) -> res.getString(R.string.cam_record_engine_error)
        code == RecordError.TOO_SHORT -> res.getString(R.string.cam_record_too_short)
        code == RecordError.STOP_FAILED -> res.getString(R.string.cam_record_stop_failed)
        code == RecordError.RELEASED -> res.getString(R.string.cam_record_released)
        else -> res.getString(R.string.cam_record_failed_generic)
    }
}
