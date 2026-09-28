package com.wotagei.cam.ui

import android.content.Context
import android.content.res.Configuration
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
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import com.wotagei.cam.core.CamPill
import com.wotagei.cam.core.HudItem
import com.wotagei.cam.core.LensType
import com.wotagei.cam.core.RenderMode
import com.wotagei.cam.core.nextLensKey
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
import com.wotagei.cam.ui.anim.HudBlockPadDp
import com.wotagei.cam.ui.anim.HudRowGapDp
import com.wotagei.cam.ui.anim.LocalMotion
import com.wotagei.cam.ui.anim.LiquidMerge
import com.wotagei.cam.ui.anim.MergeScene
import com.wotagei.cam.ui.anim.MergeSlot
import com.wotagei.cam.ui.anim.chipTravelPxOf
import com.wotagei.cam.ui.anim.hudPerRowFor
import com.wotagei.cam.ui.anim.hudRoomDp
import com.wotagei.cam.ui.anim.hudStripHeightDp
import com.wotagei.cam.ui.anim.mergeAnchor
import com.wotagei.cam.ui.anim.mergePlanFor
import com.wotagei.cam.ui.anim.mergeProgressOf
import com.wotagei.cam.ui.anim.wotaPillHost
import com.wotagei.cam.ui.design.WotaChip
import com.wotagei.cam.ui.design.WotaHit
import com.wotagei.cam.ui.design.WotaIconButton
import com.wotagei.cam.ui.design.WotaShape
import com.wotagei.cam.ui.design.WotaSpace
import com.wotagei.cam.ui.design.pillAnchor
import com.wotagei.cam.ui.design.wotaCard
import com.wotagei.cam.ui.dialog.CurveSheet
import com.wotagei.cam.ui.dialog.evText
import com.wotagei.cam.ui.dialog.flashLabelRes
import com.wotagei.cam.ui.dialog.lensLabelRes
import com.wotagei.cam.ui.dialog.observed
import com.wotagei.cam.ui.dialog.shutterText
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
import com.wotagei.cam.ui.widget.AttitudeCard
import com.wotagei.cam.ui.widget.RefLineOverlay
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * 录制页（06 文档 §4）：顶栏 / 预览 + 参考线 + 点按对焦框 / 左右两枚悬浮竖 Dock / 底栏那枚紧凑胶囊 Dock
 * + **录制键右侧**的常驻读数块（六项第 4 条：从画面左下搬过来，横屏右手拇指点得到）。
 *
 * 横竖都能录，且**共用同一套布局**：画面吃满整屏，顶栏、底栏、左右竖 Dock 与常驻读数全浮在它上面，
 * 各自用 safeDrawing / 实测避让量贴边（不再有"竖屏走列布局"那套分支，也没有左参数抽屉与抽屉把手——
 * 创作项已按六项第 2 条收进左竖 Dock）。参考线永远画在实际预览矩形上。
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
    // #54：控件胶囊的显隐位掩码，默认全开；关掉的那几颗整颗不出现（不是变灰）
    val hiddenPills = CamPill.hiddenOf(WotaSettings.hudPills(settingsPrefs))
    val levelBuzz = WotaSettings.levelBuzzEnabled(settingsPrefs)
    // §59/§74：容量段取哪一档取决于顶栏真能给多宽。窗口宽扣掉顶栏固定预留（左右内边距 16 +
    // 胶囊与设置入口的间距 6 + 设置入口约 44），再按录制页文本高度折算回 100% 基准
    val configuration = LocalConfiguration.current
    val windowWidthDp = configuration.screenWidthDp.dp
    val topBarRoomDp = ((windowWidthDp - 66.dp) /
        WotaSettings.textScale(settingsPrefs, WotaSettings.KEY_TEXT_SCALE_CAMERA)).value
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
    /**
     * 进入录制态就收掉就近胶囊（S3-2，与 [openSheet] 里那句 `pop = null` 同语义）。
     * 三个"开面板"的触发点都 gate 了录制中，但 `onRecordClick` 不清 pop ⇒ 长按开镜头面板后再按快门，
     * 面板仍按**未变换**的布局矩形定位，而那颗此刻已位移并淡到 alpha 0，浮层就飘在空位上。
     */
    LaunchedEffect(recording) { if (recording) pop = null }

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

    // ---- 避让量：能实测的一律由控件自己在布局期回报，量不到的（可视右缘）留一个共享常量
    // 顶栏第二行是告警条（DIRECT + 斑马纹这类常见组合会出现），竖 Dock 只让第一行的 44dp 必然叠上去；
    // 竖 Dock 的宽度跟着标签与字体缩放变（字体 120% 时比 100% 宽约两成），写死的宽度账两次都没算对。
    // 初值给兜底常量或预测值：首帧量不到时按兜底避让，量到之后由实测值接管，不写第二个常量。
    val hudDensity = LocalDensity.current
    var topBarH by remember { mutableStateOf(TopBarSpace) }
    // S2-2 C：底栏带高（左右竖 Dock 与读数块的下边界）改实测回报。原来的 76dp 是"真机量过两次"的
    // 经验值，AGENTS.md 禁止这类机型性魔法常量；首帧兜底改由底栏自己的布局输入算出来（同一条式子）
    var bottomBarH by remember { mutableStateOf(BottomBarSpaceFallback) }
    // S3-3：快门居中的基准从「根容器」统一到「可视窗口」。§58 那段不可视带只有横屏有（挖孔在左、
    // 可视右缘 1532px），竖屏根容器就等于可视宽，把避让量套到竖屏反而会把快门反向推偏，故按方向取。
    // 改的是这枚 Dock 的**居中父区域**，Dock 内部左右两槽等宽的不变量与动画布局参数一条没动。
    val dockEndShift = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        VisibleEndInset
    } else {
        0.dp
    }
    // 六项第 4 条：常驻读数块自己贴右下，它实际占多高由它自己回报，右竖 Dock 的下边界跟着抬起来。
    // 原来这里是 `leftDockW`（HUD 在画面左下时让开左竖 Dock 的实测宽）——HUD 搬走后再没人读它，
    // 连同 LeftDock 的 onWidthChanged 与 LeftDockSpace 常量一起收掉，不留无人读的状态。
    // S3-5：喂 hudPerRowFor 的是 hudRoomDp（窗口宽 − 右缘避让量 − 块内左右内边距），与读数块自己
    // `padding(end = VisibleEndInset)` 用的是同一个避让量；直接传 screenWidthDp 会把"贴边"当"装得下"。
    val hudRoom = hudRoomDp(windowWidthDp.value, VisibleEndInset.value)
    val hudPerRow = remember(hudItems.size, hudDensity.fontScale, hudRoom) {
        hudPerRowFor(hudItems.size, hudDensity.fontScale, hudRoom)
    }
    // S3-6：初值按行数预测而不是 0——0 会让进页首帧的右 Dock 下界按未扣值算，最低那颗与读数块叠一帧。
    // 旋转换档时这里仍是上一轮的实测值，与 topBarH/bottomBarH 同一套"两轮收敛"手法，下一帧就正。
    var hudStripH by remember {
        mutableStateOf(hudStripHeightDp(hudItems.size, hudPerRow, hudDensity.fontScale).dp)
    }

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
                // 六项第 4 条：常驻读数从画面左下搬到**录制键右侧**（横屏右手拇指可达），
                // 所以这里不再组合 ParamsHud。预览层里只剩画面、参考线、对焦框与点按对焦层。
            }
        }
    }

    val topBar: @Composable (Modifier) -> Unit = { barModifier ->
        TopBar(
            hidden = hiddenPills,
            sizeLabel = sizeText(size),
            // §59：窄屏（或文本高度被放大到 120%）时先退成只剩容量。判断放在调用方——
            // 这里拿得到根容器的实测宽度，而在 TopCapsule 里套测量层会把整段容量从树里吞掉
            capacityLabel = com.wotagei.cam.core.capacityTierText(
                freeMb,
                bitrate + com.wotagei.cam.record.BitratePolicy.AUDIO_BITRATE,
                topBarRoomDp
            ),
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
            freeModifier = anchorOf(PillKey.STORAGE),
            modifier = barModifier,
            onSizeClick = { pop = PillKey.SIZE },
            onFreeClick = { pop = PillKey.STORAGE },
            onSettingsClick = { if (recording) lockTip() else onOpenSettings() }
        )
    }

    val bottomBar: @Composable (Modifier) -> Unit = { barModifier ->
        BottomBar(
            // 六项第 7 条：镜头入口从顶栏搬到这里那颗，标签读当前镜头名
            lensLabel = stringResource(lensLabelRes(slot?.type ?: lens)),
            hidden = hiddenPills,
            recording = recStatus == RecordStatus.START,
            busy = recStatus == RecordStatus.PREPARE || recStatus == RecordStatus.STOPPING,
            lastUri = lastUri,
            lensModifier = anchorOf(PillKey.LENS),
            modifier = barModifier,
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
            onThumbClick = { if (recording) lockTip() else onOpenGallery() },
            onCycleLens = {
                if (recording) {
                    lockTip()
                } else {
                    // 顺序与颗数全从运行时枚举出的镜头表取，不写死「广角↔前置」这种两档假设
                    val keys = ui.lenses.map { it.key }
                    val target = ui.lenses.firstOrNull { it.key == nextLensKey(keys, slot?.key) }
                    when {
                        // 相机还没枚举完就点：说"等预览"而不是"只有一颗镜头"，后者是假信息
                        keys.isEmpty() -> showTip(app.getString(R.string.cam_wait_preview))
                        target == null -> showTip(app.getString(R.string.cam_lens_only_one))
                        else -> ctrl.switchLens(target)
                    }
                }
            },
            onOpenLensPanel = { if (recording) lockTip() else pop = PillKey.LENS }
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
            LeftDock(
                refLineOn = refLines != 0,
                effect = frameEffect,
                flash = flash,
                flashAvailable = canFlash,
                curveOn = !curveStack.isPassthrough,
                hidden = hiddenPills,
                modifierFor = anchorOf,
                onRefLineClick = { pop = PillKey.REFLINE },
                onMonitorClick = { pop = PillKey.MONITOR },
                onFlashClick = { pop = PillKey.FLASH },
                onCurveClick = { openSheet(Sheet.CURVE) },
                // 左缘让位与顶栏第一颗胶囊同一条竖线（8dp）。这台机横屏的挖孔在左侧，
                // 但 §58 实测 displayCutoutPadding 在这里给 0，所以仍按顶栏既有的经验值对齐。
                // 上下夹在顶栏与底栏之间：占满全高会伸到顶栏胶囊与底栏 Dock 上（横屏只有约 360dp 高），
                // 上下边界都读实测值（S2-1 顶栏 / S2-2 C 底栏）——告警条出现时顶栏不止 44dp，
                // 底栏也不止那条 76dp 的经验值
                modifier = Modifier.align(Alignment.CenterStart)
                    .padding(start = WotaSpace.s, top = topBarH, bottom = bottomBarH)
            )
            RightDock(
                roll = roll,
                pitch = pitch,
                levelEnabled = levelEnabled,
                db = recDb,
                showVolume = recording && audioEnabled,
                btConnected = btActive?.connected == true,
                btVolumePct = btVolumePct,
                zoomLabel = String.format(java.util.Locale.US, "%.1fx", zoom.value),
                focusLabel = stringResource(R.string.pill_focus),
                hidden = hiddenPills,
                // #54 的「对焦」开关 + #44 的定焦判定，两个都得认：
                // 之前只认 focusUsable，设置页那颗「对焦」开关按下去没有任何反应（假开关）
                showFocus = focusUsable && CamPill.FOCUS !in hiddenPills,
                focusActive = afMode == AfMode.MANUAL,
                stabLabel = stringResource(R.string.cam_p_stab),
                stabActive = stabilize != Stabilize.OFF,
                stabModifier = anchorOf(PillKey.STAB),
                zoomModifier = anchorOf(PillKey.ZOOM),
                focusModifier = anchorOf(PillKey.FOCUS),
                btModifier = anchorOf(PillKey.BT),
                onZoomClick = { pop = PillKey.ZOOM },
                onFocusClick = { pop = PillKey.FOCUS },
                onStabClick = { pop = PillKey.STAB },
                onBtClick = { pop = PillKey.BT },
                // #58：这台机横屏下根容器实测宽 1600，而窗口可用右缘只到 1532（差 68px ≈ 34dp），
                // displayCutoutPadding() 与 safeDrawingPadding() 在这台机都给 0（挖孔在左侧、系统栏沉浸式隐藏），
                // 于是右对齐的整栏右半边被推到可视区之外。让位量取 [VisibleEndInset]（单一共享来源，S1-1）：
                // 读数块与底栏的居中基准读的是同一个数，不再各处散写 34.dp。这个让位量是量出来的，别改小。
                // 上下同样夹在顶栏与底栏之间：整栏占满全高时，录制中出现音量表会把姿态仪顶到右上角设置钮上。
                // 上边界读顶栏实测高（S2-1：告警条那一行也算顶栏，只让 44dp 时 Dock 顶颗会压在它上面）
                // 下边界 = 底栏实测高 + 常驻读数块实测高（六项第 4 条把读数搬到录制键右侧之后，
                // 两枚都在右缘，不互相让位就会叠字；读数块空了回报 0，缝就收回去）
                modifier = Modifier.align(Alignment.CenterEnd)
                    .padding(end = VisibleEndInset, top = topBarH, bottom = bottomBarH + hudStripH)
            )
            // S3-3：这枚 Dock 的居中父区域扣掉横屏的右缘不可视带 ⇒ 快门落在**可视窗口**水平中心，
            // 不再是 1600px 根容器的中心（旧基准偏右 17dp）。S2-2 C：整排实测高回报给三处下边界。
            bottomBar(
                Modifier
                    .align(Alignment.BottomCenter)
                    .safeDrawingPadding()
                    .padding(end = dockEndShift)
                    .onSizeChanged { bottomBarH = with(hudDensity) { it.height.toDp() } }
            )
            // 六项第 4 条：快门速度 / 帧率 / 码率这几颗常驻读数搬到**录制键右侧**（横屏右手拇指可达）。
            // 它不进底栏那枚 Dock：Dock 内左右两槽必须等宽快门才居中，读数进去就把整枚 Dock 撑到 500dp 以上，
            // 横屏 800dp 宽都嫌挤、竖屏直接溢出；所以在 Dock 右侧另起一竖排（一行 1~3 颗，按可用宽与字体缩放取位），
            // 底边仍走 bottomBarH 这条带，与 Dock 只有上下关系、没有左右关系，也就不会互压。
            // S1-1：右缘让位与右竖 Dock 同源（原来只让 WotaSpace.s=8dp，横屏默认态整块被裁掉 26dp，
            // 最右那颗的副标签整段落进不可视带）
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .safeDrawingPadding()
                    .padding(end = VisibleEndInset, bottom = bottomBarH)
            ) {
                // 量高度挂在外面这层空 Box 上：ParamsHud 在"读数全关 + 未锁 AE"时直接不组合，
                // 这层就会量到 0 并回报，右竖 Dock 的下边界跟着收回（写在 ParamsHud 里就没这个节点了）
                Box(Modifier.onSizeChanged {
                    val h = with(hudDensity) { it.height.toDp() }
                    if (hudStripH != h) hudStripH = h
                }) {
                    ParamsHud(
                        items = hudItems,
                        perRow = hudPerRow,
                        valueOf = { item ->
                            val aeAuto = aeMode != AeMode.MANUAL
                            when (item) {
                                HudItem.SHUTTER -> if (aeAuto) "AUTO" else shutterText(shutter.value)
                                HudItem.ISO -> if (aeAuto) "AUTO" else "${iso.value}"
                                HudItem.EV ->
                                    if (aeMode == AeMode.AUTO) evText(ev.value, ability?.evStep ?: 1f) else null
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
                        onCycle = { item ->
                            // 录制中一律不改档（与面板同一条锁），改不动时给"本机不支持"而不是静默无反应
                            if (recording) lockTip()
                            else if (!hudCycleStep(item, params)) showTip(unsupportedText)
                        },
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
                            // 变焦的锚点平时让给右竖 Dock 那颗常驻胶囊：HUD 上的「变焦」只是读数，
                            // 两处都抢写同一个 key 会让弹窗在两个位置之间抖。
                            // 但 #54 之后那颗可以被关掉——关掉时读数自己顶上，
                            // 否则长按读数弹出的面板会因为量不到锚点而弹到屏幕原点。
                            if (item != HudItem.ZOOM || CamPill.ZOOM in hiddenPills) {
                                anchorOf(item.pillKey())
                            } else {
                                Modifier
                            }
                        }
                    )
                }
            }
        }
        topBar(
            // 顶栏整块（第一行胶囊 + 第二行告警条）的实测高回报给左右竖 Dock 当上边界（S2-1）
            Modifier.align(Alignment.TopCenter).safeDrawingPadding()
                .onSizeChanged { topBarH = with(hudDensity) { it.height.toDp() } }
        )

        // 就近胶囊：只在没有整块面板时挂出。锚点由各控件在布局期回报，正常路径同一帧就量得到；
        // 真量不到的只有两种：首帧还没测完、以及那颗控件刚被显隐开关收掉——这两种都下一帧就修正。
        // 「有触发点但没有写入方」这一种已经由 pillAnchorWriters 登记表 + PillAnchorRegistryTest 封死：
        // 蓝牙那颗当初就是没人写锚点，弹窗于是永远按 IntRect.Zero 钉在左上角（审查 S2-2，§69 缺陷族）
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
            bt = bt,
            )
        }

        CurveSheet(
            params = params,
            gpuMode = renderMode == RenderMode.GPU,
            onDismiss = { sheet = Sheet.NONE },
            visible = sheet == Sheet.CURVE,
            modifier = Modifier.matchParentSize()
        )
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

/** 底栏那排自己的上下外边距（[BottomBar] 外层 Box 的 padding，同时进 [BottomBarSpaceFallback] 的算式） */
private val BottomBarOuterPadV = 6.dp

/** 底板内、录制键之外的上下内边距（[BottomBar] 内层 Dock 的 padding，同时进 [BottomBarSpaceFallback]） */
private val DockInnerPadV = 5.dp

/**
 * 底栏带高的**首帧兜底值**（S2-2 C）：真值由底栏那层 `onSizeChanged` 回报给 `bottomBarH`，
 * 左右竖 Dock 与常驻读数块的下边界都读那条状态。
 *
 * 原来这里是一条 `76.dp` 常量，注释写着"真机量过两次"——那是 AGENTS.md 明令禁止的机型性魔法常量，
 * 而且比实测值多让了 4dp（白浪费带高）。兜底算式改用底栏自己的布局输入：
 * 录制键 [WotaHit.recordTouch] + 底板上下内边距 5×2 + 底栏上下外边距 6×2 = **72dp**，
 * 三项都是这排实际用的数（上面两个常量就是那两处 padding），不是量出来的经验值。
 * （声明顺序有讲究：文件级属性按声明顺序初始化，被引用的两个 padding 必须排在前面。）
 */
private val BottomBarSpaceFallback = WotaHit.recordTouch + DockInnerPadV * 2 + BottomBarOuterPadV * 2

/**
 * 可视区右缘的让位量：**单一共享来源**（审查 S1-1）。右竖 Dock、常驻读数块、底栏 Dock 的居中基准
 * 三处都读它，不再各处散写 `34.dp`。
 *
 * 出处是 §58 的实测：这台机横屏根容器宽 1600px、可视右缘只到 1532px，差 68px ≈ 34dp，
 * 而 `displayCutoutPadding()` 与 `safeDrawingPadding()` 在这台机都给 0（挖孔在左侧 + 系统栏沉浸式隐藏），
 * 所以右对齐的浮层右半边被推到可视区之外。读数组块原来只让 `WotaSpace.s`(8dp) ⇒ 右缘落在 1584px、
 * 越界 52px ≈ 26dp，B3 把默认方向改成横屏之后这就是默认态可见破损。
 *
 * 它是"量出来的"机型值而不是推出来的——理想形态是运行时回报可视右缘，但 §58 已证明这台机上系统
 * insets 两条路都给 0，没有可信的运行时来源，用 `screenWidthDp` 反推又会把已经验过的避让量赌掉，
 * 所以按审查定稿留常量 + 实测出处，**换机必须重量**（已列进真机点验清单）。
 */
private val VisibleEndInset = 34.dp

/**
 * 顶栏避让量的**首帧兜底值**：只到第一行那排（38dp 圆形设置钮 + 上下内边距 4+2 = 44dp）。
 * 真值由 [TopBar] 那层 `onSizeChanged` 回报给 `topBarH`：顶栏是 Column，第二行还有能力/权限告警条约 22dp
 * （DIRECT 渲染 + 开斑马纹、缺麦克风、LEGACY 都会触发，是常见组合），只让 44dp 时竖 Dock 顶颗会压在它上面。
 * 这里不再是"顶栏有多高"的结论，只是量到之前的占位，别再往这里加第二段常量。
 */
private val TopBarSpace = 44.dp

/**
 * 底栏 Dock **右槽**（镜头那颗）宽度的首帧兜底值，真值由那颗在布局期回报（见 [BottomBar]）。
 *
 * 宽度账（100% 字体缩放）：镜头那颗是最宽的一颗，`WotaChip` 三字下限 = 13sp × 3 = 39dp，
 * 加左右内边距 12+12 = **63dp**；字体缩放 120% 时约 70.8dp（下限走 sp 所以跟着涨），
 * 实测值把这一档吃进来，兜底值只管第一帧——§58/§73 两次"按 100% 量出来的固定宽在 120% 下不够用"就是这个坑。
 * 左槽（缩略图）不共用这个数：它有自己的 [ThumbBoxSpace]，两槽各自兜底（S2-1）。
 */
private val DockSlotSpace = 63.dp

/**
 * 缩略图那格的边长：底栏左槽唯一的内容尺寸（HEAD 遗留的裸值 `.size(34.dp)`，S2-1 提成常量）。
 * 它同时是左槽宽度的首帧兜底——有/无素材都画在这同一枚方框里，所以素材切换不改槽宽。
 */
private val ThumbBoxSpace = 34.dp

// ------------------------------------------------------------------ 顶栏

@Composable
@Suppress("LongParameterList")
private fun TopBar(
    sizeLabel: String,
    hidden: Set<CamPill>,
    capacityLabel: String,
    freeLow: Boolean,
    recording: Boolean,
    elapsedLabel: String,
    status: RecordStatus,
    audioDegraded: Boolean,
    legacy: Boolean,
    device: DeviceStatus,
    renderMode: RenderMode,
    effect: FrameEffect,
    // 两枚锚点必传（无默认值）：这两颗都有 `pop = PillKey.X` 触发点，漏挂就是"浮层甩到屏幕原点"那一族
    sizeModifier: Modifier,
    freeModifier: Modifier,
    modifier: Modifier = Modifier,
    onSizeClick: () -> Unit,
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
            // 元信息胶囊组与 REC 胶囊互斥，见下面两条 AnimatedVisibility
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
                // 录制中顶栏只说一件事：在录、录了多久。元信息那组整组让位（用户 2026-09-28 鸿蒙化第 1 条）
                if (recording) {
                    WotaChip(label = elapsedLabel, selected = false, valueColor = WotaRec, dot = WotaRec)
                } else {
                    WotaChip(label = stateLabel.orEmpty(), selected = false, valueColor = WotaWarn)
                }
            }
            AnimatedVisibility(
                visible = !recording && stateLabel == null,
                enter = fadeIn(motion.float) + scaleIn(motion.float, initialScale = 0.86f),
                exit = fadeOut(motion.float) + scaleOut(motion.float, targetScale = 0.86f)
            ) {
                TopCapsule(
                    sizeLabel = sizeLabel,
                    capacityLabel = capacityLabel,
                    hidden = hidden,
                    freeLow = freeLow,
                    sizeModifier = sizeModifier,
                    capacityModifier = freeModifier,
                    onSizeClick = onSizeClick,
                    onCapacityClick = onFreeClick
                )
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

/** 有 AUTO 档的几项：只有它们"切到手动才变蓝"，帧率/码率常年蓝着等于没有强调 */
private val HUD_AUTO_ITEMS = setOf(HudItem.SHUTTER, HudItem.ISO, HudItem.EV, HudItem.WB)

/**
 * 常驻参数读数：上屏哪几项由设置页 `hud_items` 决定，默认「快门 / 帧率 / 码率」。
 * 每格点按循环取值、长按弹自己的就近胶囊（[modifierFor] 负责把锚点矩形回报给上层）；
 * [valueOf] 返回 null 表示该项当前不适用，直接不画。
 *
 * 六项第 4 条之后它贴在**录制键右侧**（调用方给 `Alignment.BottomEnd`），所以行与行都**右对齐**：
 * 最后一行不满时也贴右缘，不会在右边留一段空白让人以为被裁了。
 * [perRow] 由调用方按窗口宽与字体缩放算出来（[hudPerRowFor]），这里只照数分行——
 * 竖屏 360dp 宽时一行 3 颗会顶出右缘裁字，这是 §58/§73 那一族"按某一档字体缩放写死宽度"的老坑。
 */
@Composable
private fun ParamsHud(
    items: List<HudItem>,
    perRow: Int,
    valueOf: @Composable (HudItem) -> String?,
    locked: Boolean,
    onClick: (HudItem) -> Unit,
    onCycle: (HudItem) -> Unit,
    modifierFor: (HudItem) -> Modifier,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty() && !locked) return
    val motion = LocalMotion.current
    val columns = if (perRow < 1) 1 else perRow
    // 参数是一颗一颗独立的悬浮胶囊，不是一整块面板，所以这里不套外层底
    Column(
        // 内边距与间隔走 hudRoomDp/hudStripHeightDp 的同源常量（S3-5）：改了这里就必须同时改那两条算式
        modifier.padding(HudBlockPadDp.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(HudRowGapDp.dp)
    ) {
        items.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(HudRowGapDp.dp)) {
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
                        // 悬浮参数胶囊：点按循环取值、长按开就近面板（鸿蒙化第 2 条）。
                        // 「切到手动值即变蓝」只对本来有 AUTO 档的几项成立，否则帧率/码率会常年蓝着
                        val manual = item in HUD_AUTO_ITEMS && lastValue != "AUTO"
                        WotaChip(
                            label = lastValue.orEmpty(),
                            selected = false,
                            modifier = modifierFor(item),
                            secondary = stringResource(item.labelRes),
                            valueColor = if (manual) WotaAccent else null,
                            onClick = { onCycle(item) },
                            onLongClick = { onClick(item) }
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

// ------------------------------------------------------------------ 左右悬浮竖 Dock

/**
 * 右竖 Dock（六项第 2 条）：姿态仪 + 音量 + 蓝牙 + 变焦/对焦/防抖，
 * 从原来的「裸卡片堆叠」改成与底栏同一套卡片语言 —— 一枚圆角底板，内部各控件仍挂自己的就近锚点。
 *
 * 宽度不写死：由底板内最宽那颗控件决定。§58（当时的右栏，即现在的右竖 Dock，被裁 68px）与 §73
 * （`-10.6°` 被裁成 `-10.`）都是"按 100% 字体缩放量出来的固定宽"在 120% 下不够用，而字体缩放是既定变量。
 *
 * **竖向顶部对齐 + 可滚**（S3-3）：横屏窗口约 360dp 高，扣掉顶栏与"底栏 + 读数块"才是 Dock 的带高。
 * 四段全是实测回报值（S2-1 顶栏 / S2-2 C 底栏 / 读数块自己），下面的算式只是它们在本机默认档的量级：
 * 顶栏第一行 44dp（告警条出现 ≈66dp）、底栏 ≈72dp、默认 3 颗读数一行 ≈42dp（满配 7 颗 3 行 ≈114dp）
 * ⇒ 带高 ≈ **202dp**（告警条 180dp / 满配读数 130dp / 两者叠加 108dp）。
 *
 * 内容高（100% 字体）：姿态仪 74 + 音量表 71 + 蓝牙 42 + 变焦/对焦/防抖各 30 + 4 段间隔 16 + 底板内边距 8。
 * **S2-2 B：录制中把音量表与姿态仪并成一行两列**（音量表只在 `recording && audioEnabled` 才组合，
 * 并入后这一行取两者较大值 74dp，省下的正是那 71 + 4 ≈ 75dp）⇒ 录制中与未录的内容高都是 **230dp**。
 * 露出颗数（累计上边界 vs 带高，未录 230 里少音量表那一颗）：
 * · 默认档 202dp 带：8 → 74(并排 2 颗) → 128(蓝牙) → 162(变焦) → 196(对焦) → 230(防抖) ⇒ **露 5 颗、折 1 颗**
 *   （修前是同一算式下 82/157/203 ⇒ 露 2~3 颗、折 4 颗，变焦是第一颗被推下去的）
 * · 告警条 180dp 带：到变焦 162dp 为止 ⇒ **露 4 颗、折 2 颗**
 * · 满配 7 读数 130dp 带：到蓝牙 128dp 为止 ⇒ **露 3 颗、折 3 颗**
 * · 告警条 + 满配 108dp 带：只剩并排那一行 ⇒ **露 2 颗、折 4 颗**
 * 折下去的那些仍取得到：这排本来就挂 verticalScroll，顶部对齐保证高频项先露脸（内容超出时"居中"排布
 * 会把上下两头都顶出去，没有意义）。未录时音量表不组合，也就不需要并排，观感与 B1 一致。
 *
 * 宽度账（并排后变宽，S2-2 要求核过不越界）：并排行 = 姿态仪 54 + 间距 4 + 音量表 28 = 86，加底板内边距
 * 8 ⇒ 底板宽 ≈ **94dp**（未录时由 63dp 的变焦胶囊决定，≈71dp）。横屏可视右缘 766dp 减 34dp 让位后
 * 从 672dp 起画，右侧余量充足；竖屏 360dp 下占 [232, 326]，与贴左缘的左竖 Dock（约 [8, 79]）不相接。
 * 宽度不由常量决定、由最宽那行撑出来，所以字体 120% 时自动加宽，§58/§73 那族"固定宽在缩放下裁字"
 * 在这里不复发。
 *
 * 底板只在至少有一颗要画时才组合，否则全关掉后左/右会留一枚空壳。
 * 底板圆角用 [WotaShape.card] 而不是 pill（S3-4）：这枚盒子约 94×202dp，`RoundedCornerShape(percent = 50)`
 * 的半径取短边一半 ≈47dp，`wotaCard` 第一环就是 clip，会把首尾那颗卡片的外角各削掉一截；
 * 14dp 的 card 只把底板自己收成圆角矩形，不再咬内容。姿态仪与蓝牙这两颗在 Dock 内不再自绘底（`card = false`），
 * 免得底板 + 内层卡两层 hudScrim 叠成"卡中卡"。
 */
@Composable
@Suppress("LongParameterList")
private fun RightDock(
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
    hidden: Set<CamPill>,
    focusActive: Boolean,
    stabLabel: String,
    stabActive: Boolean,
    stabModifier: Modifier,
    zoomModifier: Modifier,
    focusModifier: Modifier,
    btModifier: Modifier,
    onZoomClick: () -> Unit,
    onFocusClick: () -> Unit,
    onStabClick: () -> Unit,
    onBtClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val showLevel = levelEnabled && CamPill.LEVEL !in hidden
    val showVolumeLed = showVolume && CamPill.VOLUME !in hidden
    val showBt = CamPill.BT !in hidden
    val showZoom = CamPill.ZOOM !in hidden
    val showStab = CamPill.STAB !in hidden
    if (!showLevel && !showVolumeLed && !showBt && !showZoom && !showFocus && !showStab) return
    Column(
        modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        // 顶部对齐：内容超出带高时先保住姿态仪/音量/蓝牙这几颗常驻项露脸（S3-3）
        verticalArrangement = Arrangement.spacedBy(WotaSpace.xs, alignment = Alignment.Top)
    ) {
        Column(
            Modifier
                .wotaCard(WotaShape.card)
                .padding(WotaSpace.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(WotaSpace.xs)
        ) {
            // S2-2 B：录制中（音量表出现时）把姿态仪与音量表并成一行两列，省下的 ≈75dp 正好把
            // 变焦/对焦从折叠线下捞回来（露出颗数见本函数 KDoc 的算式）。音量表只在
            // `recording && audioEnabled` 才组合，所以未录时这里退回一行一列、观感与 B1 一致。
            if (showLevel && showVolumeLed) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(WotaSpace.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AttitudeCard(roll, pitch, levelEnabled, card = false)
                    VolumeLeds(db, card = false)
                }
            } else {
                if (showLevel) AttitudeCard(roll, pitch, levelEnabled, card = false)
                if (showVolumeLed) VolumeLeds(db, card = false)
            }
            // btModifier 必传：这颗有 `pop = PillKey.BT` 触发点。B1 之前这里没人挂锚点，
            // pillAnchors[BT] 恒为空 → 面板按 IntRect.Zero 永久钉在左上角，而入口在右缘（§69 缺陷族）
            if (showBt) BtChip(
                connected = btConnected,
                volumePct = btVolumePct,
                modifier = btModifier,
                card = false,
                onClick = onBtClick
            )
            if (showZoom) WotaChip(
                label = zoomLabel,
                selected = false,
                modifier = zoomModifier,
                onClick = onZoomClick
            )
            // #44：定焦镜头没有一样东西能调，整颗入口隐藏，不摆一排灰选项
            if (showFocus) WotaChip(
                label = focusLabel,
                selected = focusActive,
                modifier = focusModifier,
                onClick = onFocusClick
            )
            if (showStab) WotaChip(
                label = stabLabel,
                selected = stabActive,
                modifier = stabModifier,
                onClick = onStabClick
            )
        }
    }
}

/**
 * 左竖 Dock（六项第 2 条）：底栏左组那四颗创作项（参考线 / 屏幕监看 / RGB 曲线 / 闪光灯）收进来，
 * 与右 Dock 同一枚圆角底板、同样竖向可滚。闪光灯的显隐仍由「本机有无闪光灯」这条运行时能力决定（§37 第 5 条）。
 *
 * 各颗的就近锚点用 [modifierFor] 按 key 取（同 [ParamsHud] 的写法），真实理由有两条：
 * ① 这里已经 `@Suppress("LongParameterList")`，再塞三个具名 Modifier 形参会把参数表继续撑大；
 * ② 传的是**整个取锚点的函数**，调用点一次给全，比"四选一别漏挂某个 key"更难出错。
 * 原先注释写的「为了避开 lint 的 ModifierParameter」不成立：同批 [RightDock] 就挂了三个具名
 * Modifier 形参并被 lint 点名（ModifierParameter 是 Warning，B1 基线里那 4 条就是它）。
 *
 * 原先这颗要回报自身宽度给画面左下的常驻读数当避让量（S3-2）。六项第 4 条把读数搬到录制键右侧之后
 * 再没人读那个宽度，`onWidthChanged` 形参与 `LeftDockSpace` 常量一起收掉，不留无人读的状态与参数。
 *
 * 叠字风险按重算的账重述（旧注释写的「读数块宽 ≤234dp」是 2 颗那一档，3 颗那档不是这个数）：
 * 读数块 100% **294dp**、120% **348dp**（宽度账见 hudPerRowFor），贴右缘且让开 [VisibleEndInset]；
 * 左竖 Dock 贴左缘 ≈[8, 79]dp（监看那颗胶囊 63dp + 底板内边距）。**两者外接矩形在竖屏 360dp 下会重叠**
 * （块左缘 360−34−294 = 32dp < 79dp），不叠字的真实理由是"竖向错开"：左 Dock 顶部对齐、内容高约 160dp，
 * 读数块只占带底 42dp（满配 3 行 114dp），竖屏 800dp 高的窗口里两者相离 >400dp。
 * 横屏 360dp 高时左 Dock 内容与读数块也错开（160 < 202−42），但余量小。**这条只有真机截图能定案**，
 * 已列入点验清单；本批不砍控件也不缩字号。
 */
@Composable
@Suppress("LongParameterList")
private fun LeftDock(
    refLineOn: Boolean,
    effect: FrameEffect,
    flash: Flash,
    flashAvailable: Boolean,
    curveOn: Boolean,
    hidden: Set<CamPill>,
    modifierFor: (PillKey) -> Modifier,
    onRefLineClick: () -> Unit,
    onMonitorClick: () -> Unit,
    onFlashClick: () -> Unit,
    onCurveClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val showRefLine = CamPill.REFLINE !in hidden
    val showMonitor = CamPill.MONITOR !in hidden
    val showCurve = CamPill.CURVE !in hidden
    val showFlash = flashAvailable && CamPill.FLASH !in hidden
    if (!showRefLine && !showMonitor && !showCurve && !showFlash) return
    Column(
        modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WotaSpace.xs, alignment = Alignment.CenterVertically)
    ) {
        Column(
            Modifier
                .wotaCard(WotaShape.card)
                .padding(WotaSpace.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(WotaSpace.xs)
        ) {
            if (showRefLine) WotaIconButton(
                image = Icons.Filled.GridOn,
                description = stringResource(R.string.cam_p_refline),
                selected = refLineOn,
                modifier = modifierFor(PillKey.REFLINE),
                onClick = onRefLineClick
            )
            if (showMonitor) WotaChip(
                label = stringResource(
                    if (effect == FrameEffect.NONE) R.string.cam_p_monitor else effectShortRes(effect)
                ),
                selected = effect != FrameEffect.NONE,
                modifier = modifierFor(PillKey.MONITOR),
                onClick = onMonitorClick
            )
            // 曲线与斑马纹同级，是创作项，不该藏在「更多」里（§37 第 2 条）
            if (showCurve) WotaChip(
                label = stringResource(R.string.cam_p_curve),
                selected = curveOn,
                onClick = onCurveClick
            )
            if (showFlash) WotaIconButton(
                image = flashIcon(flash),
                description = stringResource(flashLabelRes(flash)),
                selected = flash != Flash.OFF,
                modifier = modifierFor(PillKey.FLASH),
                onClick = onFlashClick
            )
        }
    }
}

/** 音量表：`amplitude()` → dB 后 6 格 LED，仅录制中显示（06 文档 §4）
 *
 * [card] 与 [AttitudeCard]、[BtChip] 同一条规则（S3-4）：单独摆的时候自带一层底，
 * 放进竖 Dock 时传 false，免得底板 + 内层底两层 hudScrim 叠成"卡中卡"。
 */
@Composable
private fun VolumeLeds(db: Float, card: Boolean = true) {
    val lit = (((db.coerceIn(MIN_DB, MAX_DB) - MIN_DB) / (MAX_DB - MIN_DB)) * LED_COUNT).roundToInt()
    Column(
        Modifier
            .then(if (card) Modifier.clip(RoundedCornerShape(8.dp)).background(WotaHudScrim) else Modifier)
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

/**
 * 底栏只剩中央那枚**紧凑悬浮胶囊 Dock**（缩略图 / 快门 / 镜头），外加六项第 3 条的录制态融合动画。
 *
 * ## 两个不变量同时成立（鸿蒙化第 4 条要的观感 + B1 要的居中）
 * ① 底板只包住内容：`底板宽 = 2 × 槽宽 + 录制键 + 2 × (条目间距 + 底板内边距)`，
 *    槽宽 = `max(缩略图实测宽, 镜头那颗实测宽)`；材质沿用 [wotaCard]（hudScrim + 顶部高光描边），
 *    不加模糊、不加投影。
 * ② 录制键中心恒等于**可视窗口**水平中心（S3-3：旧写法的基准是根容器，横屏那 34dp 不可视带会让快门
 *    在画面上偏右 17dp；调用点已把这段避让量从居中父区域扣掉），**与这两颗是否显示无关**。
 *    算式（两槽强制等宽是唯一的承重条件）：
 *    设底板全宽 W、内边距 P、槽宽 S、录制键宽 R，内容区宽 = W − 2P = 2S + R + 2G（G 为条目间距）。
 *    底板在可视区里居中 → 底板中心 = 可视中心；录制键在内容区里由 [Alignment.Center]
 *    定位 → 它到内容区左缘 = (W − 2P)/2 = S + G + R/2，到左内边缘算起正好 R/2 + (S + G) ⇒
 *    录制键中心 = P + S + G + R/2 = (W − 2P)/2 + P = **W/2** = 底板中心 = 可视窗口中心。
 *    S 只由 `max(左, 右)` 决定，两颗各自显示与否只改变 S 的大小、不改变"两槽等宽"这件事，
 *    所以**两档可达**开关组合下算式同形，快门都不跳（缩略图没有 `CamPill` 开关位、不可隐藏）：
 *    · 镜头开：S = max(34, 63) = 63 → W = 2×63 + 50 + 2×(12+8) = 216dp
 *      （有无素材都画在同一枚 [ThumbBoxSpace] 方框里，所以素材切换不改变 S）
 *    · 镜头关：S = max(34, 0) = 34 → W = 2×34 + 50 + 40 = 158dp
 *    两档 W/2 恒等于底板中心 ⇒ 不变量②与①同时成立。
 *    录制中**保持等宽空槽**（审查定版第 2 条）：空出来的那一段不裁、不缩，改它就要动动画期间的
 *    布局参数，踩红线。
 *
 * B1 那版用两个 `weight(1f)` 的空格把快门顶到正中，代价是底板 `fillMaxWidth()` 铺满整排、退化成
 * 贴底通栏条，①被牺牲掉了。本版换法：等宽槽 + 实测宽 ⇒ ①②都保住，代价是**两轮布局**——
 * 首帧按各槽自己的兜底值（右槽 [DockSlotSpace]、左槽 [ThumbBoxSpace]）起算，量到实测宽之后第二帧收敛
 * （与顶栏高度、底栏高度回报同一套手法；S2-1 补的就是左槽那一路 `onSizeChanged`，修前它只有声明没有写入）。
 * 槽位本身不裁剪内容：S 取的是最大值，窄的那一侧只是内边距里多一段等宽空区。
 *
 * ## 六项第 3 条：录制态「水滴融入 / 细胞分裂」，只作用这两颗（码率那颗不参与）
 * `RecordStatus.START` → 缩略图与镜头那颗被录制键吸收；停止 → 分裂回原位。几何与纪律全在
 * [com.wotagei.cam.ui.anim.LiquidMerge]（可复用连通体绘制，B4 的拖拽动感复用同一套代码）。
 * 四条落地口径：
 * - **不动布局参数**：两颗的原位槽位宽度全程不变，动画只作用 translation / scale / alpha / path，
 *   所以底板既不重排也不跳动。就近锚点也不会打架：进度 0（未录制）时那几项变换全是单位变换，
 *   而录制中这三颗只回 [lockTip]、不开浮层，`pillAnchor` 回报的矩形没有任何一条路径会读到位移态
 *   （面板已开再按录制的那一条由 `LaunchedEffect(recording) { pop = null }` 收掉，S3-2）。
 * - **本体位移有上限，上限就是实测空隙**：两颗的圆心距只有 68.5~83dp，而镜头那颗 63dp 宽填满槽位，
 *   近缘离录制键触摸盒只剩 ≈12dp（120% 字体缩放 ≈3.6dp）。位移量取
 *   `min(圆心距 × TRAVEL_FRACTION, 实测空隙)`（[com.wotagei.cam.ui.anim.LiquidMerge.clearancePx]），
 *   所以录制中点在录制键上永远不会被那颗吃掉——停止录制是最高优先级手势，这一点不让给动画。
 *   两颗在录制中仍走既有 [lockTip] 语义：命中区留在原位附近，点得到、给提示。
 * - **可打断**：进度用 `animateFloatAsState` 驱动，`recording` 中途翻转时它从**当前值**继续往新的
 *   目标走（Compose 的 animateFloatAsState 语义），不跳回起点、不卡死。
 *   12 号包写的"跟手"是**拖拽**语境的词，本批的触发是状态切换、没有指针可跟，所以这里没有跟手，
 *   只有可打断——照实写在这里，不冒充实现了跟手。
 * - **PLAIN 档直接切换**：[mergePlanFor] 给 PLAIN 返回 `animated=false / drawWaist=false / travel=false`，
 *   三条都由桥函数落到行为上：调用点**不创建** `animateFloatAsState`（S3-1，PLAIN 下没有动画机器），
 *   进度走 [com.wotagei.cam.ui.anim.mergeProgressOf]（只认 recording，0/1），位移走
 *   [chipTravelPxOf] 恒 0，绘制层走 [com.wotagei.cam.ui.anim.waistVisibleFor] 第一条就 return。
 *   这是"确实消失"而不是"变快"：没有动画状态，也就没有动画窗口。
 *
 * 六项第 7 条：顶栏的镜头段并入这里那颗。单击按顺序循环到下一颗可用镜头，长按打开镜头就近面板，
 * 标签读当前镜头名（广角 / 超广角 / 长焦 / 前置）。#54 的 `CamPill.LENS` 开关跟着搬到这颗上。
 */
@Composable
@Suppress("LongParameterList")
private fun BottomBar(
    lensLabel: String,
    hidden: Set<CamPill>,
    recording: Boolean,
    busy: Boolean,
    lastUri: Uri?,
    // 镜头那颗的锚点必传：这颗有 `pop = PillKey.LENS` 触发点，漏挂长按面板就弹到左上角
    lensModifier: Modifier,
    modifier: Modifier = Modifier,
    onRecordClick: () -> Unit,
    onThumbClick: () -> Unit,
    onCycleLens: () -> Unit,
    onOpenLensPanel: () -> Unit
) {
    val showLens = CamPill.LENS !in hidden
    val motion = LocalMotion.current
    val plan = remember(motion.mode) { mergePlanFor(motion.mode) }
    val scene = remember { MergeScene() }
    val density = LocalDensity.current
    // 进度取的是 `recording`，而它含 PREPARE / START / STOPPING 三态（见上面的定义），所以真实时序是：
    // **PREPARE 一上来就开始吸 → START 吸到底 → STOPPING 全程保持吸 → 回到 IDLE 才细胞分裂**（S2-3①：
    // 旧注释写的"PREPARE 转圈时不动、STOPPING 一开始就分裂"与实现相反，行为是对的，改的是注释）。
    // PLAIN 档**不创建**动画状态（S3-1）：anim 为 null，连 120ms 的 tween 都不跑
    val anim = if (plan.animated) animateFloatAsState(if (recording) 1f else 0f, motion.float) else null
    // 进度只经 mergeProgressOf 这一条桥取（S4-1 要测的就是它）：PLAIN 恒 0/1，动画档读当前值以便打断续接
    val progress: () -> Float = { mergeProgressOf(plan, recording, anim?.value) }
    // 位移上限：那颗的近缘与录制键触摸盒之间的实测空隙。绘制期算，零分配；
    // 这一项是硬约束——那颗淡出后命中区还在（alpha=0 仍可点），越过空隙就会把"停止录制"那一指吃掉
    val thumbClearance = {
        LiquidMerge.clearancePx(
            scene.cx(MergeScene.THUMB), scene.halfWidth(MergeScene.THUMB),
            scene.cx(MergeScene.RECORD), scene.halfWidth(MergeScene.RECORD)
        )
    }
    val lensClearance = {
        LiquidMerge.clearancePx(
            scene.cx(MergeScene.LENS), scene.halfWidth(MergeScene.LENS),
            scene.cx(MergeScene.RECORD), scene.halfWidth(MergeScene.RECORD)
        )
    }
    // 两槽各自兜底（S2-1：以前一处 63dp 兼两槽，实测前左槽按右槽的宽度算，底板宽到 216dp 才收敛）
    val lensFallbackPx = remember(density) { with(density) { DockSlotSpace.toPx() }.roundToInt() }
    val thumbFallbackPx = remember(density) { with(density) { ThumbBoxSpace.toPx() }.roundToInt() }
    val padPx = remember(density) { with(density) { WotaSpace.s.toPx() } }
    val gapPx = remember(density) { with(density) { WotaSpace.m.toPx() } }
    val recordPx = remember(density) { with(density) { WotaHit.recordTouch.toPx() } }
    var thumbW by remember { mutableIntStateOf(thumbFallbackPx) }
    var lensW by remember { mutableIntStateOf(if (showLens) lensFallbackPx else 0) }
    // 那颗被 CamPill 关掉时不会再有布局回调，宽度必须主动归零，否则底板留在上一轮的宽度上；
    // 重新打开时先按右槽自己的兜底宽起算，少一次"从 0 长出来"的观感。
    // 左槽（缩略图）恒在树里，第一帧布局就回报实测宽 ⇒ 两槽都"首帧兜底、第二帧实测接管"（S2-1 补的就是左槽）
    LaunchedEffect(showLens) { lensW = if (showLens) lensFallbackPx else 0 }
    val slotW = MergeSlot.slotWidthPx(thumbW.toFloat(), lensW.toFloat())
    val slotDp = remember(slotW, density) { with(density) { slotW.toDp() } }
    val dockW = remember(slotW, recordPx, gapPx, padPx, density) {
        with(density) { MergeSlot.dockWidthPx(slotW, recordPx, gapPx, padPx).toDp() }
    }
    Box(
        modifier
            .fillMaxWidth()
            // 这里的 vertical 与下面底板那层的 vertical 就是 [BottomBarSpaceFallback] 的算式输入，
            // 改任一处等于改首帧兜底值（同源，别分叉）
            .padding(horizontal = WotaSpace.s, vertical = BottomBarOuterPadV)
    ) {
        // 悬浮胶囊 Dock（鸿蒙化第 4 条）：素材缩略图 / 快门 / 镜头三件事共用一枚 hudScrim + 高光描边的壳，
        // 底板宽度就是上面那条算式，只包住内容，不再是铺满整排的贴底圆角条
        Box(
            Modifier
                .align(Alignment.Center)
                .width(dockW)
                .wotaCard(WotaShape.pill)
                // 连通体画在底板之上、两颗之下：宿主节点自己报原点与尺寸，两颗报窗口坐标，绘制时相减
                .mergeAnchor(scene, MergeScene.CANVAS)
                .wotaPillHost(scene, plan, progress)
                .padding(horizontal = WotaSpace.s, vertical = DockInnerPadV)
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(slotDp),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    Modifier
                        .mergeAnchor(scene, MergeScene.THUMB)
                        // S2-1：左槽也回报实测宽（与右槽对称）。缩略图恒在树里，所以第一帧布局就量得到，
                        // 兜底值只管这一帧之前的组合；槽宽不再由右槽的 63dp 兼任
                        .onSizeChanged { if (it.width != thumbW) thumbW = it.width }
                        .graphicsLayer {
                            val p = progress()
                            alpha = LiquidMerge.chipAlpha(p)
                            val s = LiquidMerge.chipScale(p)
                            scaleX = s
                            scaleY = s
                            translationX = chipTravelPxOf(
                                plan, p,
                                scene.cx(MergeScene.RECORD) - scene.cx(MergeScene.THUMB),
                                thumbClearance()
                            )
                        }
                        .size(ThumbBoxSpace)
                        .clip(WotaShape.small)
                        .clickable(onClick = onThumbClick),
                    contentAlignment = Alignment.Center
                ) {
                    val uri = lastUri
                    if (uri != null) {
                        VideoThumbnail(uri = uri, modifier = Modifier.matchParentSize(), px = 160)
                    } else {
                        Icon(
                            Icons.Filled.PhotoLibrary,
                            contentDescription = stringResource(R.string.cam_gallery_entry),
                            tint = WotaTextDim,
                            modifier = Modifier.padding(6.dp).size(20.dp)
                        )
                    }
                }
            }
            RecordButton(
                recording = recording,
                busy = busy,
                modifier = Modifier.align(Alignment.Center).mergeAnchor(scene, MergeScene.RECORD),
                onClick = onRecordClick
            )
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(slotDp),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (showLens) WotaChip(
                    label = lensLabel,
                    selected = false,
                    // 锚点 + 实测宽 + 融合位移三件事都挂在这颗的同一个节点上；
                    // 宽度回报必须在 mergeAnchor 之外另算，所以拆开写、不叠 then 的歧义
                    modifier = lensModifier
                        .onSizeChanged { if (it.width != lensW) lensW = it.width }
                        .mergeAnchor(scene, MergeScene.LENS)
                        .graphicsLayer {
                            val p = progress()
                            alpha = LiquidMerge.chipAlpha(p)
                            val s = LiquidMerge.chipScale(p)
                            scaleX = s
                            scaleY = s
                            translationX = chipTravelPxOf(
                                plan, p,
                                scene.cx(MergeScene.RECORD) - scene.cx(MergeScene.LENS),
                                lensClearance()
                            )
                        },
                    onClick = onCycleLens,
                    onLongClick = onOpenLensPanel
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
        if (recording && !busy) {
            // 呼吸光环：录制中这一圈缓慢涨落，余光里也能确认"还在录"；只动 scale/alpha，
            // 不碰布局参数（MotionSpec 的既有约束），所以不会把预览层挤一下
            val grow = androidx.compose.animation.core.rememberInfiniteTransition().animateFloat(
                initialValue = 0.94f,
                targetValue = 1.14f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    androidx.compose.animation.core.keyframes {
                        durationMillis = 1_600
                        0.94f at 0
                        1.14f at 800 with androidx.compose.animation.core.LinearOutSlowInEasing
                        0.94f at 1_600
                    }
                )
            )
            Box(
                Modifier
                    .size(WotaHit.recordRing)
                    .graphicsLayer {
                        scaleX = grow.value
                        scaleY = grow.value
                        alpha = 1f - (grow.value - 0.94f) / 0.2f * 0.72f
                    }
                    .border(2.dp, WotaRec.copy(alpha = 0.5f), CircleShape)
            )
        }
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

/** 顶栏胶囊组里的一段：文本 + 自己的就近锚点 + 点击 */
private data class TopSeg(val label: String, val anchor: Modifier, val tint: Color, val onClick: () -> Unit)

/**
 * 顶栏元信息收成**一枚**胶囊（用户 2026-09-28 鸿蒙化第 1 条）：
 * 原先「广角 / 1920x1080 16:9 / 剩余 96.2G」是散在画面上的三颗，读起来像三个入口；
 * 现在共用一层 hudScrim 壳 + 高光描边，段与段之间一条细线，点各自段仍开各自的就近弹窗。
 *
 * 容量段顺手把裸字节换算成「96.2G · 3h18m」—— 录制时真正想知道的是"还能录多久"，不是"还剩多少字节"。
 * 每一段仍受 #54 的 `CamPill` 开关控制，关掉的段整段不组合。
 *
 * 六项第 7 条：镜头段整段删除，那颗入口与 `CamPill.LENS` 开关一起搬到底栏 Dock 的镜头那颗。
 */
/** 两段（画幅 / 容量）各带自己的就近锚点 Modifier，命名规则让位给语义（lint 的 ModifierParameter 只认单个 modifier 形参） */
@android.annotation.SuppressLint("ModifierParameter")
@Composable
private fun TopCapsule(
    sizeLabel: String,
    capacityLabel: String,
    hidden: Set<CamPill>,
    freeLow: Boolean,
    sizeModifier: Modifier,
    capacityModifier: Modifier,
    onSizeClick: () -> Unit,
    onCapacityClick: () -> Unit
) {
    val visibleSize = CamPill.SIZE !in hidden
    val visibleStorage = CamPill.STORAGE !in hidden
    if (!visibleSize && !visibleStorage) return
    // §59：窄屏判断不放在这里。原先套的 `BoxWithConstraints` 是 TopBar 那行 Row 的无权重子节点，
    // 测量语义不同，会把整段容量读数从节点树里吞掉（真机 dump 里连节点都没有）。
    // 现在只收调用方算好的现成文案。
    val segs = buildList {
        if (visibleSize) add(TopSeg(sizeLabel, sizeModifier, WotaText, onSizeClick))
        if (visibleStorage) {
            add(TopSeg(capacityLabel, capacityModifier, if (freeLow) WotaRec else WotaText, onCapacityClick))
        }
    }
    Row(
        Modifier
            .wotaCard(WotaShape.pill)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        segs.forEachIndexed { index, seg ->
            if (index > 0) {
                Box(Modifier.width(1.dp).height(12.dp).background(WotaDivider))
            }
            Text(
                text = seg.label,
                style = MaterialTheme.typography.labelMedium,
                color = seg.tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = seg.anchor
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable(onClick = seg.onClick)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}
