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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import com.wotagei.cam.core.FrameEffect
import com.wotagei.cam.core.CamPill
import com.wotagei.cam.core.HudItem
import com.wotagei.cam.core.LensType
import com.wotagei.cam.core.RenderMode
import com.wotagei.cam.core.nextLensKey
import com.wotagei.cam.core.Stabilize
import com.wotagei.cam.core.TapPoint
import com.wotagei.cam.core.WotaParams
import com.wotagei.cam.core.WotaTiers
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
import com.wotagei.cam.ui.anim.MergeDebugBadge
import com.wotagei.cam.ui.design.WotaSpace
import com.wotagei.cam.ui.dialog.CurveSheet
import com.wotagei.cam.ui.dialog.evText
import com.wotagei.cam.ui.dialog.lensLabelRes
import com.wotagei.cam.ui.dialog.observed
import com.wotagei.cam.ui.dialog.shutterText
import com.wotagei.cam.ui.dialog.sizeText
import com.wotagei.cam.ui.theme.WotaAccent
import com.wotagei.cam.ui.theme.WotaBg
import com.wotagei.cam.ui.theme.WotaDivider
import com.wotagei.cam.ui.theme.WotaSurface
import com.wotagei.cam.ui.theme.WotaText
import com.wotagei.cam.ui.theme.WotaWarn
import com.wotagei.cam.ui.widget.CameraSurface
import com.wotagei.cam.ui.widget.RefLineOverlay
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * 录制页（06 文档 §4）：**五枚可拖动的容器**浮在预览之上 —— 顶栏胶囊组 / 左竖 Dock / 右竖 Dock /
 * **录制键右侧**的常驻读数块（六项第 4 条）/ 底栏那枚紧凑胶囊 Dock（13 号计划第 5 条的两级模型）。
 *
 * 渲染与定位的代码全在 `ui/HudLayer.kt`（与「编辑控件」页同一条路），这一页只管三件事：
 * ① 从相机 / 录制 / 参数总线取这一帧的读数造 [HudCtx]；② 从 prefs 读 `hud_layout` 位置表并把
 * 用户拖过的坐标钳进安全区（[clampZonePos]）；③ 就近胶囊的锚点表与弹出宿主。
 * 位置表的哨兵档（用户没拖过）走 B1–B3 的原生对齐：画面吃满整屏，五枚容器各自用 safeDrawing /
 * 实测避让量贴边；没有"竖屏走列布局"的分支，也没有左参数抽屉（创作项已按六项第 2 条收进左竖 Dock）。
 * 参考线永远画在实际预览矩形上。
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
    // §59/§74：容量段取哪一档取决于顶栏真能给多宽。这条账放在 safeW 量到之后再算（见下面的 topBarRoomDp），
    // 因为它必须与顶栏胶囊组自己的布局上限 topBarMaxWidthDp **同一个来源**：套了 safeDrawingPadding()
    // 之后的安全区实测宽，而不是 configuration.screenWidthDp（本机横屏两者差 34dp，就是挖孔那条边）
    val configuration = LocalConfiguration.current
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

    // ---- 位置表（hud_layout）与避让量：能实测的一律由控件自己在布局期回报，**贴边避让本身交给系统**
    // （下面那层套了 safeDrawingPadding() 的盒子），这一层不留任何按方向写死的避让常量。
    // 顶栏第二行是告警条（DIRECT + 斑马纹这类常见组合会出现），竖 Dock 只让第一行的 44dp 必然叠上去；
    // 竖 Dock 的宽度跟着标签与字体缩放变（字体 120% 时比 100% 宽约两成），写死的宽度账两次都没算对。
    // 初值一律给兜底常量或预测值：首帧量不到时按兜底避让，量到之后由实测值接管。
    val hudDensity = LocalDensity.current
    // 编辑页「保存」走 commit()（同步落盘，理由见 WotaSettings.setHudLayout），这条监听在写入返回后立刻
    // 打到 → 录制页实时生效，杀进程重进读的是同一份 prefs
    val hudLayout = rememberHudLayout(settingsPrefs)
    var topBarH by remember { mutableIntStateOf(TopBarSpace.value.roundToInt()) }
    // 安全区实测尺寸就是位置表 (x, y) 的坐标参考（"root 的安全区"）：挖孔与系统栏让掉的那条边**已经包含**
    // 在这份尺寸里，所以它同时是钳制上限、顶栏容量段取档、读数块取档的同一个可用宽来源（旧写法在这里
    // 另扣一笔写死的 34dp 右缘让位，而那条带会随横屏 90/270 换边 ⇒ 两个姿态必错一次，已删，见 §九·补）。
    // 初值取 configuration 的运行时估算（不是新写的机型魔法数），量到之后由 onSizeChanged 接管；
    // 旋转换档先按新方向估算，下一帧实测校正
    var safeW by remember(configuration.orientation) { mutableIntStateOf(configuration.screenWidthDp) }
    var safeH by remember(configuration.orientation) { mutableIntStateOf(configuration.screenHeightDp) }
    // #70 A 定版「横屏永远同行」的判据：就用这两行已经在用的那个 orientation（同一个真源，不另造第二套
    // "横屏"定义，比如按窗口宽高比现推——那会和这里复位 safeW/safeH 的条件分叉）
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    // §59/§74：容量段取档的可用宽 = 安全区实测宽 − 顶栏右端固定件预留，再按录制页文本高度折算回 100% 基准。
    // 它必须与胶囊组自己的布局上限 topBarMaxWidthDp 同一条账、同一个来源，否则判据比实际盒子宽
    val topBarRoomDp = ((safeW - TopBarChromeReserveDp).dp /
        WotaSettings.textScale(settingsPrefs, WotaSettings.KEY_TEXT_SCALE_CAMERA)).value
    // 五枚卡片本体在窗口里的实测矩形：钳制要的容器尺寸与第 8 条要的"底栏现在在哪"都只读这一份。
    // ⚠ 必须**随方向复位**（与上面 safeW/safeH 同一个 key）：旋转那一刻这批矩形还是上一副姿态的尺寸，
    // 而 #70 A 的读数块决策吃的正是里面的宽度（底板宽、块宽）。不复位的话，竖屏按整幅排出来的
    // 294dp 块宽会在新姿态的首帧被当成"实测越线"，配合退化档的自反馈就把读数永久闩在错误那一档
    // （#70 修复批次第 1 条点名的帮凶）。复位后首帧退回 [BottomDockWidthFallback] 等兜底值，
    // 下一帧实测接管，与 topBarH / dockStripH 那套"两轮收敛"手法同一条。
    val zoneRects = remember(configuration.orientation) { mutableStateMapOf<HudZone, IntRect>() }
    val bottomOuterPadDp = BottomBarOuterPadV.value.roundToInt()
    val dockCardH = zoneRects[HudZone.BOTTOM].dpHeightToDp(hudDensity)
    // S2-2 C：底栏那一排占的带高 = 卡片实测高 + 卡片外上下边距各一份（原来的 76dp 是"真机量过两次"的
    // 经验值，AGENTS.md 禁止这类机型性常量）；首帧量不到时退回底栏自己的布局算式 BottomBarSpaceFallback
    val dockStripH = if (dockCardH > 0) dockCardH + 2 * bottomOuterPadDp
    else BottomBarSpaceFallback.value.roundToInt()
    // ---- 可见条目集 = 设置里开着显示的那些。关掉的整颗不出现，但**仍留在位置表里**（见
    // [HudLayoutTable.visibleOrderOf]：过滤只读表、不改表），所以"隐藏再打开显示"回到的是用户放好的位置。
    // #54 的开关位 + #44 的定焦判定 + 运行时「本机有无闪光灯」+ 音量表只在录制中出现，四条判据都收在
    // 这里，少认一条就是假开关（历史上「对焦」那颗按下去没反应就是这个）
    val visibleEntries: Set<HudEntry> = buildSet {
        CamPill.ALL.filter { it !in hiddenPills }.filter { p ->
            when (p) {
                CamPill.FLASH -> canFlash
                CamPill.FOCUS -> focusUsable
                CamPill.LEVEL -> levelEnabled
                CamPill.VOLUME -> recording && audioEnabled
                else -> true
            }
        }.forEach { add(HudEntry.of(it)) }
        hudItems.forEach { add(HudEntry.of(it)) }
    }
    // S3-5：读数块的取档判据必须是"调用点同一个表达式"算出的可用宽（旧写法直接传 screenWidthDp 会把
    // "贴边"当"装得下"，传旧的 34dp 避让量则会在挖孔换边的那个姿态里双重让位）。
    // #70 A 之后那条表达式收进了 planReadoutRow（HudLayout.kt，纯函数 + JVM 用例）：它一次算清
    // 「底边停哪一档 + 一行几颗 + 可用内宽」，本文件与编辑页都只调它，不再各摆一份 hudRoomDp/hudPerRowFor。
    val readoutCount = hudLayout.visibleOrderOf(HudZone.READOUT, visibleEntries).size
    val baseArea = HudAreaDp(
        width = safeW,
        height = safeH,
        topAvoidDp = topBarH,
        bottomAvoidDp = dockStripH
    )
    // ---- #70 A：读数块与底栏**共用底部那一条横带**，不再被"底栏带高"抬到 Dock 上方。
    // 一次决策同时给出三件事，谁都不许另写一份：① 底边停在哪一档、② 一行几颗、③ 可用内宽。
    // 定版口径（用户 2026-09-29 12:20）：横屏**永远同行**、列数上限两颗；只有竖屏保留"退回整排之上"。
    // 底边那一档不是直接把 BottomBarOuterPadV 交给容器：读数块自己还吃一枚 HudBlockPadDp 内边距，
    // 计划里减掉它，对齐的才是**胶囊可见底边**与**底板可见底边**（第 6 条那笔 6dp 账）。
    // 输入是实测底栏宽（首帧量不到按 BottomDockWidthFallback 兜底，与 dockStripH 同一套两轮收敛手法）、
    // 安全区实测宽，以及读数块自己的实测宽（竖屏那道"估宽说谎时别压上底板"的安全网；横屏不读它，
    // 否则列数↔块宽会形成每帧振荡的环，理由见 planReadoutRow）。
    val dockCardW = zoneRects[HudZone.BOTTOM].dpWidthToDp(hudDensity)
    val readoutCardRect = zoneRects[HudZone.READOUT]
    val readoutCardW = readoutCardRect.dpWidthToDp(hudDensity)
    val readoutPlan = remember(
        readoutCount, hudDensity.fontScale, safeW, dockCardW, dockStripH, readoutCardW, isLandscape
    ) {
        planReadoutRow(
            readoutCount = readoutCount,
            fontScale = hudDensity.fontScale,
            safeWidthDp = safeW,
            dockWidthDp = if (dockCardW > 0) dockCardW else BottomDockWidthFallback.value.roundToInt(),
            dockStripDp = dockStripH,
            dockRowBaselineDp = bottomOuterPadDp,
            endPadDp = HudEdgePad.value,
            dockGapDp = WotaSpace.s.value,
            landscape = isLandscape,
            // 实测块宽做竖屏安全网：`hudPerRowFor` 的字宽是算术估计，副标签「感光度 / 曝光补偿」比它假设的
            // 两汉字宽，估宽偏小时真值会压上底板。量不到那一帧传 0 = 先按估宽走，下一帧实测接管
            readoutWidthDp = readoutCardW
        )
    }
    // 读数块那枚容器的 area：底边由上面那次决策说了算（横屏恒与底栏同一行，那一档的让位已经在计划里
    // 减掉块自己的内边距 ⇒ 对齐的是可见底边），带高与钳制都只读这一份
    val readoutArea = baseArea.copy(bottomAvoidDp = readoutPlan.bottomAvoidDp)
    // S3-6：读数块高度首帧按行数预测而不是 0——0 会让进页首帧的右 Dock 下界按未扣值算，最低那颗叠一帧。
    // 旋转换档时它仍是上一轮那一档，与 topBarH 同一套"两轮收敛"手法，下一帧就正。
    val hudStripH = readoutCardRect.dpHeightToDp(hudDensity).let {
        if (it > 0) it else hudStripHeightDp(readoutCount, readoutPlan.perRow, hudDensity.fontScale).roundToInt()
    }
    // 六项第 4 条之后右竖 Dock 与读数块都在右缘：右 Dock 的下界还要多让开读数块那一截，否则会叠字。
    // #70 A 之后这两枚在**同一条横带**上（各占一半），所以那截让位与底栏带高取大而不是求和，
    // 见 areaForRightDock 的 KDoc（同基线档下读数块矮于底栏那一排时，那条缝整个收回去）
    val rightArea = areaForRightDock(baseArea, hudStripH, readoutPlan.bottomAvoidDp)
    // 顶栏胶囊组的宽度上限：抽取前它待在 `fillMaxWidth` 的顶栏 Row 里，右边被设置入口顶死，
    // 装不下时靠 Text 的 maxLines+Ellipsis 收；搬进独立可拖容器后没有那层约束了，120% 文本 + 窄窗
    // 会直接从右缘画出去。参考区从「窗口宽」换成「安全区实测宽」，与顶栏那行 Row 当年同一个基准。
    val topBarMaxWidthDp = (safeW - TopBarChromeReserveDp).coerceAtLeast(0).dp

    fun areaOf(zone: HudZone): HudAreaDp = when (zone) {
        HudZone.RIGHT -> rightArea
        HudZone.READOUT -> readoutArea
        else -> baseArea
    }

    /**
     * 表里的位置 → 这一帧真正用的位置。
     * - 两轴都是哨兵（用户没把这枚容器拖离定稿位置）原样返回，由 [HudZoneBox] 走 B1–B3 的原生对齐分支；
     * - 有绝对轴的先钳进安全区。容器尺寸量不到那一帧按 0 算 ⇒ 左上角坐标原样成立、不跳回默认位置，
     *   下一帧实测接管"装不装得下"。**底栏**钳完的 x 恒为哨兵（[clampZonePos] 那一支），所以它只可能
     *   带绝对 y，[HudZoneBox] 因此仍按 fillMaxWidth + 居中画它。
     */
    fun placementOf(zone: HudZone): ZonePlacement {
        val raw = hudLayout.posOf(zone)
        if (raw.isDefault) return raw
        val rect = zoneRects[zone]
        val w = rect.dpWidthToDp(hudDensity)
        val h = rect.dpHeightToDp(hudDensity)
        return clampZonePos(zone, raw.xDp, raw.yDp, w, h, areaOf(zone))
    }

    // 「镜头」那颗归属哪枚容器由表说了算：不在底栏时底栏那个右槽留等宽空区，那颗在别的容器里
    // 由那枚容器泛化渲染（同一颗出现两遍 = 两个锚点写入方抢写同一个 PillKey，弹窗就会抖）
    val lensEntry = HudEntry.of(CamPill.LENS)
    val lensInBottomDock = lensEntry in visibleEntries &&
        hudLayout.sourceZoneOf(lensEntry) == HudZone.BOTTOM

    // ---- 第 8 条：长按底栏 Dock 换栏（只作用 y，结果写进 hud_layout 的底栏容器那一份坐标）
    var dockDragging by remember { mutableStateOf(false) }
    // 跟手位移只进 graphicsLayer（红线：不动布局参数），松手才换算成 dp 写表
    var dockShiftPx by remember { mutableFloatStateOf(0f) }
    val dockDrag = HudDockDrag(
        dragging = dockDragging,
        // gate 在这一条：PREPARE / START / STOPPING 一律不许进入拖拽——正在录的时候不能让人把底栏
        // （连着"停止录制"那一指）搬走。返回 false 时 HudBottomZone 里改播锁提示
        onAllowStart = {
            if (dockDragBlocked(recStatus)) {
                false
            } else {
                dockDragging = true
                dockShiftPx = 0f
                true
            }
        },
        onDragY = { dy -> dockShiftPx += dy },
        onDrop = {
            val area = baseArea
            val hDp = if (dockCardH > 0) dockCardH else BottomBarSpaceFallback.value.roundToInt() - 2 * bottomOuterPadDp
            val y = dockSnapY(
                area,
                heightDp = hDp,
                bottomPadDp = bottomOuterPadDp,
                gapDp = WotaSpace.s.value.roundToInt(),
                deltaYDp = pxToDp(dockShiftPx, hudDensity.density)
            )
            // **只写 y，x 留哨兵**：底栏横向永远由 fillMaxWidth + 居中决定，90↔270 翻转与横竖换档都会
            // 重新居中快门。旧写法在这里把"当前实测左缘"换算成绝对 x 一起写进表，纯长按不动也会落一个
            // 绝对值，此后那枚 Dock 就再也不跟可视中心走了（B4 审查 S1 那条设计缺陷）。
            // 哨兵值取 ZonePlacement.DEFAULT.xDp，与表里"没拖过"的那一轴同一个数。
            // commit() 返回 false = 没落成盘（重启就回原栏），不许静默，给一句提示
            val ok = WotaSettings.setHudLayout(
                settingsPrefs,
                hudLayout.withZonePos(HudZone.BOTTOM, ZonePlacement.DEFAULT.xDp, y)
            )
            if (!ok) showTip(app.getString(R.string.hud_edit_save_failed))
            dockDragging = false
            dockShiftPx = 0f
        },
        onBlocked = lockTip
    )
    // 拖到一半开始录制（快门那颗仍可用）：立刻退出拖拽，吸收/分裂由录制态那一条接手，进度从当前值续
    LaunchedEffect(recStatus) {
        if (dockDragBlocked(recStatus)) {
            dockDragging = false
            dockShiftPx = 0f
        }
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

                // 六项第 4 条：常驻读数从画面左下搬到**录制键右侧**（横屏右手拇指可达），所以预览层里
                // 只剩画面、参考线、对焦框与点按对焦层；读数块是浮在安全区里的第五枚容器。
            }
        }
    }

    // ---- 一帧 HUD 的全部输入：录制页这份读实时参数总线，编辑页那份读出厂默认，渲染只有一条路
    val hudCtx = HudCtx(
        recording = recStatus == RecordStatus.START,
        busy = recStatus == RecordStatus.PREPARE || recStatus == RecordStatus.STOPPING,
        // 计时与状态是**读数**不是入口：给它们挂 onClick 只会让人以为点开有配置项（§37 第 4 条）
        recStateLabel = when (recStatus) {
            RecordStatus.PREPARE -> stringResource(R.string.cam_state_prepare)
            RecordStatus.STOPPING -> stringResource(R.string.cam_state_stopping)
            else -> null
        },
        elapsedLabel = formatDuration(recElapsed),
        sizeLabel = sizeText(size),
        // §59：窄屏（或文本高度被放大到 120%）时先退成只剩容量。判断放在调用方——这里拿得到根容器的
        // 实测宽度，而在胶囊组里套测量层会把整段容量从树里吞掉
        capacityLabel = com.wotagei.cam.core.capacityTierText(
            freeMb,
            bitrate + com.wotagei.cam.record.BitratePolicy.AUDIO_BITRATE,
            topBarRoomDp
        ),
        freeLow = freeMb < WotaTiers.MIN_FREE_MB,
        zoomLabel = String.format(java.util.Locale.US, "%.1fx", zoom.value),
        focusLabel = stringResource(R.string.pill_focus),
        stabLabel = stringResource(R.string.cam_p_stab),
        stabActive = stabilize != Stabilize.OFF,
        focusActive = afMode == AfMode.MANUAL,
        refLineOn = refLines != 0,
        monitorLabel = stringResource(
            if (frameEffect == FrameEffect.NONE) R.string.cam_p_monitor else effectShortRes(frameEffect)
        ),
        monitorActive = frameEffect != FrameEffect.NONE,
        curveOn = !curveStack.isPassthrough,
        flash = flash,
        // 六项第 7 条：镜头入口从顶栏搬到底栏那颗，标签读当前镜头名
        lensLabel = stringResource(lensLabelRes(slot?.type ?: lens)),
        btConnected = btActive?.connected == true,
        btVolumePct = btVolumePct,
        levelEnabled = levelEnabled,
        roll = roll,
        pitch = pitch,
        db = recDb,
        lastUri = lastUri,
        aeLocked = aeMode == AeMode.LOCK,
        readoutValue = { item ->
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
        readoutPerRow = readoutPlan.perRow,
        anchorOf = { entry ->
            val key = entry.pillKey()
            when {
                key == null -> Modifier
                // 变焦的锚点平时让给竖 Dock 那颗常驻胶囊：读数上的「变焦」只是读数，两处都抢写同一个
                // key 会让弹窗在两个位置之间抖。但 #54 之后那颗可以被关掉或被挪出可见集——这时读数自己
                // 顶上，否则长按读数弹出的面板会因为量不到锚点而弹到屏幕原点
                key == PillKey.ZOOM && entry.kind == HudEntryKind.READOUT &&
                    HudEntry.of(CamPill.ZOOM) in visibleEntries -> Modifier
                else -> Modifier.pillAnchorReport(pillAnchors, key)
            }
        },
        onSizeClick = { pop = PillKey.SIZE },
        onFreeClick = { pop = PillKey.STORAGE },
        onRefLineClick = { pop = PillKey.REFLINE },
        onMonitorClick = { pop = PillKey.MONITOR },
        onFlashClick = { pop = PillKey.FLASH },
        onCurveClick = { openSheet(Sheet.CURVE) },
        onZoomClick = { pop = PillKey.ZOOM },
        onFocusClick = { pop = PillKey.FOCUS },
        onStabClick = { pop = PillKey.STAB },
        onBtClick = { pop = PillKey.BT },
        onLensCycle = {
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
        onOpenLensPanel = { if (recording) lockTip() else pop = PillKey.LENS },
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
        onReadoutCycle = { item ->
            // 录制中一律不改档（与面板同一条锁），改不动时给"本机不支持"而不是静默无反应
            if (recording) lockTip()
            else if (!hudCycleStep(item, params)) showTip(unsupportedText)
        },
        onReadoutOpen = { item ->
            // 近似帧率先说明再让改：点上去弹一个"这档其实是 23.976"的浮层比直接改值有用
            if (item == HudItem.FPS && !fps.exact) {
                pop = PillKey.FPS
                showTip(approxTipText)
            } else {
                pop = item.pillKey()
            }
        }
    )

    /** 卡片本体的窗口矩形进实测表：值真变了才写。布局期写状态会重组，空转一次就是白掉一帧 */
    fun putCardRect(zone: HudZone, rect: IntRect) {
        if (zoneRects[zone] != rect) zoneRects[zone] = rect
    }

    Box(
        modifier
            .fillMaxSize()
            .background(WotaBg)
            .onSizeChanged { deviceDegrees = readDeviceDegrees(context) }
    ) {
        // 画面先铺满整屏，五枚容器浮在它上面：横竖屏共用一套布局，各自那条不透明黑带随之消失。
        // 容器统一放进这层套了 safeDrawingPadding() 的盒子里——它就是位置表 (x, y) 的坐标参考，
        // 系统栏与挖孔由这一层**按它实际所在的那条边**自动避让，容器自己不再各写一份（也不许再往容器
        // 上补按方向写死的让位量：那 68px 是竖屏顶边居中的挖孔，横屏 90/270 会让它换边，
        // 写死 end 就必错一次。docs/plan/13 §九·补，任务 #68）
        previewStage(Modifier.fillMaxSize())
        Box(
            Modifier
                .matchParentSize()
                .safeDrawingPadding()
                .onSizeChanged {
                    val w = with(hudDensity) { it.width.toDp().value.roundToInt() }
                    val h = with(hudDensity) { it.height.toDp().value.roundToInt() }
                    if (w != safeW) safeW = w
                    if (h != safeH) safeH = h
                }
        ) {
            // 顶栏那排的固定件（设置入口 + 告警条）实测高回报给竖 Dock 当上边界（S2-1）
            HudTopChrome(
                audioDegraded = ui.audioDegraded,
                legacy = ability?.isLegacy() == true,
                deviceFailedHighFps = ui.device == DeviceStatus.OPEN_FAILED_HIGH_FPS,
                effect = frameEffect,
                renderMode = renderMode,
                onSettingsClick = { if (recording) lockTip() else onOpenSettings() },
                onHeightChanged = { if (topBarH != it) topBarH = it }
            )
            // #73 取证钩子生效时的标识：顶栏正中一枚胶囊，写明钉住的进度与时长倍率。
            // 钩子关着（release / debug / 没带 adb extra）时它**根本不组合**，所以截图里看见它
            // 就等于这张是钩子态，不会把钉住的中间帧当成正常渲染写进验收结论。
            // 位置只吃 safeDrawingPadding()（贴边避让交系统），不写任何方向常量。
            MergeDebugBadge(Modifier.align(Alignment.TopCenter).padding(top = WotaSpace.xs))
            // 顶栏胶囊组（可拖动）：录制计时/状态那颗 + 画幅 | 容量
            // 不进 sheet 互斥那一道门：抽取前的 TopBar 是整条顶栏（胶囊组 + 设置入口 + 告警条），
            // 门只夹住四周一圈控件，顶栏恒组合。曲线面板开着又在录的时候，计时那颗是唯一的"还在录"
            // 凭证，跟着四周一起收掉就等于把录制指示藏了，所以这里与 [HudTopChrome] 同进同出。
            // 宽度上限补回抽取前那条 fillMaxWidth Row 给的硬约束（见 topBarMaxWidthDp）：胶囊组一旦
            // 脱离那行 Row 就没有布局兜底了，字宽估算是纯算术，估算偏了只会从右缘溢出去。
            HudZoneBox(
                zone = HudZone.TOP,
                placement = placementOf(HudZone.TOP),
                area = baseArea,
                modifier = Modifier.widthIn(max = topBarMaxWidthDp),
                onCardRect = { putCardRect(HudZone.TOP, it) }
            ) {
                HudTopZone(hudLayout.visibleOrderOf(HudZone.TOP, visibleEntries), hudCtx)
            }
            if (sheet == Sheet.NONE) {
                HudZoneBox(
                    zone = HudZone.LEFT,
                    placement = placementOf(HudZone.LEFT),
                    area = baseArea,
                    onCardRect = { putCardRect(HudZone.LEFT, it) }
                ) {
                    HudDockZone(
                        HudZone.LEFT,
                        hudLayout.visibleOrderOf(HudZone.LEFT, visibleEntries),
                        hudCtx,
                        zoneBandHeight(HudZone.LEFT, baseArea)
                    )
                }
                // 右缘只留 HudEdgePad 那枚 8dp 设计留白（与左竖 Dock 的起始边同一枚令牌），贴边避让全在
                // 外层那层 safeDrawingPadding()：挖孔落到哪条边它就避哪条，本层不再按方向补让位量
                // （任务 #68 删掉的就是那笔写死的 34dp，出处见 docs/plan/13 §九·补）。
                // 上下夹在顶栏与底栏之间：整栏占满全高时，录制中出现音量表会把姿态仪顶到设置钮上。
                HudZoneBox(
                    zone = HudZone.RIGHT,
                    placement = placementOf(HudZone.RIGHT),
                    area = rightArea,
                    onCardRect = { putCardRect(HudZone.RIGHT, it) }
                ) {
                    HudDockZone(
                        HudZone.RIGHT,
                        hudLayout.visibleOrderOf(HudZone.RIGHT, visibleEntries),
                        hudCtx,
                        zoneBandHeight(HudZone.RIGHT, rightArea)
                    )
                }
                // 六项第 4 条：快门速度 / 帧率 / 码率这几颗常驻读数搬到**录制键右侧**（横屏右手拇指可达）。
                // 它不进底栏那枚 Dock：Dock 内左右两槽必须等宽快门才居中，读数进去就把整枚 Dock 撑到
                // 500dp 以上，横屏 800dp 宽都嫌挤、竖屏直接溢出。
                // #70 A：这块的底边与带高读的是 readoutArea（planReadoutRow 那次决策），不再读 baseArea——
                // 后者带的是"底栏那一排的带高"，把它当 padding(bottom=) 用就是把读数手算抬到 Dock 上方，
                // 那是 §14.2 里被点名第二次的写法。绝对落位那一支（placementOf）也走同一个 areaOf 出口，
                // 所以钳制带与原生对齐带不会分叉。
                HudZoneBox(
                    zone = HudZone.READOUT,
                    placement = placementOf(HudZone.READOUT),
                    area = readoutArea,
                    onCardRect = { putCardRect(HudZone.READOUT, it) }
                ) {
                    HudReadoutZone(
                        hudLayout.visibleOrderOf(HudZone.READOUT, visibleEntries),
                        hudCtx,
                        zoneBandHeight(HudZone.READOUT, readoutArea)
                    )
                }
                // 这枚 Dock 的居中父区域 = 套了 safeDrawingPadding() 之后的**整宽安全区** ⇒ 快门中心就是
                // 可视窗口水平中心，挖孔落到左短边还是右短边都跟着中心走。旧写法在这里又扣一笔写死的 34dp
                // （外层已经避过让位了，等于双重让位），于是两个横屏姿态都往**左**偏 34px：带在左时中心落在
                // 800px（应为 834px）、带在右时 732px（应为 766px）。任务 #68 已删那笔扣减。
                // S2-2 C：整排实测高回报给三处下边界。第 8 条：长按这枚底板 → 吸收两颗 → 上下拖（只进
                // graphicsLayer）→ 松手落上栏/下栏，**只有 y 进表**（x 恒哨兵，见 onDrop 那段）。
                // 镜头那颗被挪去别的容器时这枚 Dock 只留等宽空槽（右槽实测宽归 0，槽宽回到 34dp 那一档）
                HudZoneBox(
                    zone = HudZone.BOTTOM,
                    placement = placementOf(HudZone.BOTTOM),
                    area = baseArea,
                    shiftYPx = { if (dockDragging) dockShiftPx else 0f },
                    onCardRect = { putCardRect(HudZone.BOTTOM, it) }
                ) {
                    HudBottomZone(
                        showLens = lensInBottomDock,
                        ctx = hudCtx,
                        drag = dockDrag
                    )
                }
            }
        }

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
private const val TAG_UI = "WotaUi"

/**
 * 顶栏右端固定件吃掉的宽度（左右内边距 8+8 + 胶囊组与设置入口的间距 6 + 圆形设置入口约 44）。
 * 抽取前它是顶栏那行 `fillMaxWidth` Row 的自然结果，现在两条判据都要显式用它：
 * 容量段取档用的 `topBarRoomDp`（§59/§74）与胶囊组的布局宽度上限 `topBarMaxWidthDp`，必须是同一条账。
 */
private const val TopBarChromeReserveDp = 66


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
