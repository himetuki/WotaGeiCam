package com.wotagei.cam.camera

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.wotagei.cam.core.CameraAbility
import com.wotagei.cam.core.Flash
import com.wotagei.cam.core.FpsPick
import com.wotagei.cam.core.LensSlot
import com.wotagei.cam.core.LensType
import com.wotagei.cam.core.ParamState
import com.wotagei.cam.core.RenderMode
import com.wotagei.cam.core.Size
import com.wotagei.cam.core.WotaParams
import com.wotagei.cam.core.WotaTiers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.Executor

/** 设备状态机（失败按原因细分四类） */
enum class DeviceStatus {
    CLOSE, OPEN_ING, OPEN_SUCCEED,
    OPEN_FAILED_HIGH_FPS, OPEN_FAILED_IN_USED, OPEN_FAILED_INNER_ERROR, OPEN_FAILED_PERMISSION
}

/** 预览状态机；仅允许 START→ING（PreviewStatus.set 守卫） */
enum class PreviewStatus { INIT, START, ING, STOP, ERROR }

/** 录制状态机（与 record 包共用；中间态必须区分） */
enum class RecordStatus { IDLE, PREPARE, START, STOPPING, ERROR }

/**
 * Camera2 引擎：开关设备、建会话、下发 repeating、点按对焦、错误恢复。
 *
 * 线程模型：相机状态全部收敛到 [workHandler] 单线程；参数总线在调用方 scope（Main）采集，
 * 再 post 进工作线程，避免跨线程竞态。
 *
 * 通道分离：参数变化只走 repeating（脏标记 + 5ms 合并重发），
 * 拍照 / AF 触发走单次 capture；高速会话下两条通道都要包 createHighSpeedRequestList。
 */
class Camera2Engine(
    context: Context,
    private val params: WotaParams,
    private val sink: PreviewSink,
    private val scope: CoroutineScope,
    private val onDeviceStatus: (DeviceStatus) -> Unit = {},
    private val onPreviewStatus: (PreviewStatus) -> Unit = {}
) {

    companion object {
        private const val TAG = "WotaCam"

        /** 参数合并刷新窗口（杜绝滑杆请求风暴） */
        private const val MERGE_DELAY_MS = 5L

        /** 点按对焦超时恢复（必须实现，否则一次点击永久改掉焦点策略） */
        private const val TAP_RESTORE_MS = 4_500L

        /** 闪光切换间隔（先复位→重发→100ms→新值） */
        private const val FLASH_GAP_MS = 100L

        /** 手动色温保活周期：逐帧重发会与脏标记通道互相触发，这里节流 */
        private const val WB_KEEP_ALIVE_MS = 500L

        /**
         * 输出面未就绪时的兜底重试（首次 bind 时 GL 面可能还没建出来）。
         * 主路径是 [onSinkSurfaceReady] 回调，这里的轮询只兜住「回调丢了」的情况。
         */
        private const val SINK_WAIT_MS = 60L
        private const val SINK_WAIT_MAX = 20

        /** 占用/内部错误后的自动重开 */
        private const val REOPEN_DELAY_MS = 800L
        private const val REOPEN_MAX = 2
    }

    private val manager = context.applicationContext
        .getSystemService(Context.CAMERA_SERVICE) as CameraManager

    // 引擎线程兜底取证（审查 P1）：本线程原先没有 UncaughtExceptionHandler，任何漏网异常 =
    // 静默进程死亡（正是「EV 可见时调快门/帧率闪退」的表现通道）。这里只负责**取证**：
    // 记完必须原样交回默认处理器（Android 上即 KillApplicationHandler）——吞掉不交会让进程变
    // 僵尸（相机线程死了、UI 还在），且 crash 不进 crash buffer/dropbox，恰恰丢掉最需要的崩栈。
    private val workThread = HandlerThread("WotaCamEngine").apply {
        uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, e ->
            Log.e(TAG, "引擎线程未捕获异常（取证后交默认处理终止进程）：${paramSnapshot()}", e)
            Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(thread, e)
        }
        start()
    }
    private val workHandler = Handler(workThread.looper)
    private val workExecutor = Executor { runnable -> workHandler.post(runnable) }

    @Volatile
    private var threadRunning = true

    // ------------------------------------------------- 相机态（只在 work 线程读写）

    private var lens: LensSlot? = null
    private var ability: CameraAbility? = null
    private var reqState: RequestApplier.State? = null

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var highSpeedSession: CameraConstrainedHighSpeedCaptureSession? = null
    private var reqBuilder: CaptureRequest.Builder? = null

    /** 录像面：DIRECT 模式由 record 包提供 MediaRecorder 的 surface；v0.0.1 不挂 YUV 分析流 */
    private var extraSurface: Surface? = null
    private var extraReader: ImageReader? = null

    private var chosenFps: FpsPick? = null
    private var highSpeedActive = false
    private var structuralSignature = ""
    private var desiredPreview = false
    private var sinkWaitRetries = 0
    private var reopenRetries = 0
    private var flashResetStage = false
    private var lastWbKeepAliveMs = 0L

    /** 关闭代数：异步回调只认最新一次开机的结果，防止旧回调写脏状态 */
    private var generation = 0

    /**
     * 会话配置代数：`createCaptureSession` 是异步的，配置回调到达可能晚于 closeSessionQuietly。
     * 只认最新一次发起的配置——过期回调（stopPreview / 换面之后才配置完的那次）必须当场关闭，
     * 否则会把刚停掉的预览在后台复活（旧 session 赋回字段并重发 repeating，相机持续推流）。
     */
    private var sessionAttempt = 0

    // 点按对焦上下文
    private var tapAfModeOverride: Int? = null
    private var tapAfRegions: List<MeteringRectangle>? = null
    private var tapAeRegions: List<MeteringRectangle>? = null
    private var tapTrigger: Int? = null
    private var tapRestoreTask: Runnable? = null

    private var dirty = false

    /**
     * 合并刷新任务本体必须自带 try（审查 P1）：[markDirty] 把它直发 Handler（不经 [post] 的
     * catch 包装），apply/build 抛出的任何 RuntimeException 都会穿透到引擎线程未捕获 → 进程死亡。
     * catch 里带参数快照（诊断 C 项）：真机一次 logcat 就能对上「崩的那一下下发的是什么档」。
     */
    private val flushTask = Runnable {
        try {
            dirty = false
            if (session != null || highSpeedSession != null) applyRepeatingNow()
        } catch (e: Exception) {
            Log.e(TAG, "flushTask 异常：${paramSnapshot()}", e)
        }
    }

    private val collectJobs = mutableListOf<Job>()

    @Volatile
    var deviceStatus = DeviceStatus.CLOSE
        private set

    @Volatile
    var previewStatus = PreviewStatus.INIT
        private set

    // ------------------------------------------------- 参数总线采集

    /** 订阅参数总线：结构变化重建会话，其余走合并刷新。由 CameraController 在 open 之前调用一次 */
    fun attach() {
        if (collectJobs.isNotEmpty()) return
        collectJobs += combine(
            params.size, params.renderMode, params.fps, params.shutter, params.iso
        ) { size, renderMode, fps, shutter, iso ->
            Pulse(size, renderMode, fps.value, shutter.value, iso.value)
        }.onEach { pulse -> post { onStructuralPulse(pulse) } }.launchIn(scope)

        collectJobs += merge(*dirtySources())
            .onEach { post { markDirty() } }
            .launchIn(scope)

        // 闪光切换要「先复位再置新值」，不能只靠普通脏刷新
        collectJobs += params.flash.drop(1)
            .onEach { post { onFlashChanged() } }
            .launchIn(scope)
    }

    /** 只改下发内容、不改会话形状的参数源；merge 要变参数组，故这里直接给 Array */
    private fun dirtySources(): Array<Flow<Unit>> = with(params) {
        arrayOf(
            aeMode.map { Unit }, ev.map { Unit }, afMode.map { Unit },
            manualFocusDiopter.map { Unit }, zoom.map { Unit },
            wbMode.map { Unit }, kelvin.map { Unit }, tint.map { Unit },
            stabilize.map { Unit }
        )
    }

    private data class Pulse(
        val size: Size,
        val renderMode: RenderMode,
        val fps: Int,
        val shutterNs: Long,
        val iso: Int
    )

    // ------------------------------------------------- 公开操作

    /** 打开镜头：先整套关干净，再按新能力重建快照（切镜头 = 能力换挡） */
    fun open(slot: LensSlot) {
        post {
            val keepPreview = desiredPreview
            val gen = ++generation
            teardownDevice()
            desiredPreview = keepPreview
            lens = slot
            ability = slot.ability
            if (slot.ability.isLegacy()) {
                Log.w(TAG, "镜头 ${slot.logicId} 是 LEGACY 等级，手动参数可能不生效")
            }
            highSpeedActive = slot.ability.highSpeedCapable && fpsValue() > WotaTiers.HIGH_SPEED_FPS
            reqState = RequestApplier.stateOf(slot.ability, params.size.value, highSpeedActive)
            chosenFps = null
            structuralSignature = ""
            sink.setOrientation(slot.ability.sensorOrientation, slot.type == LensType.FRONT)
            pushSinkFrameSize(params.size.value)
            publishDevice(DeviceStatus.OPEN_ING)
            try {
                // 真机坑（WIKO GAR-AN60 / Android 11）：屏幕锁定或进程在后台时相机服务直接拒开
                // （`cannot open camera "0" from background`），回调成 CAMERA_DISABLED →
                // 表现为 OPEN_FAILED_PERMISSION，与运行时权限无关；adb 自动化须先亮屏解锁再 am start。
                manager.openCamera(slot.logicId, deviceCallback(gen), workHandler)
            } catch (e: SecurityException) {
                failDevice(DeviceStatus.OPEN_FAILED_PERMISSION, "缺少相机权限")
            } catch (e: CameraAccessException) {
                failDevice(mapAccessReason(e.reason), "openCamera 失败 reason=${e.reason}")
            } catch (e: IllegalArgumentException) {
                failDevice(DeviceStatus.OPEN_FAILED_INNER_ERROR, "cameraId 不可用：${slot.logicId}")
            }
        }
    }

    /**
     * 终止性关闭，顺序固定：停 ImageReader → 停 repeating → 关 session → 关 device → 清 Handler
     * （乱序是 preview 泄漏与 ANR 的常见根因）
     */
    fun close() {
        collectJobs.forEach { it.cancel() }
        collectJobs.clear()
        // 先在调用方线程同步封口：置假后所有 post 的入队/执行守卫即刻生效，close 返回后旧引擎
        // 上不会再有任何任务起跑（审查 D 项·补：quitSafely 对已到期的消息照发不误，只把拆除与
        // quit 合并成单 post 还封不住「合并任务执行前入队」的 post——它会在 quit 之后被补发）
        threadRunning = false
        // 拆除与退线程必须是单个原子任务（审查 D 项）：原先两枚 post 之间有执行间隙，bind() 换
        // 引擎时旧引擎 open 的 post 插进去的话 manager.openCamera 已发起而线程旋即退出，onOpened
        // 投到已退出的 looper 被静默丢弃 ⇒ 相机句柄无人 close。合并后插队点不复存在。
        // try/finally 保住「哪怕拆除抛异常也一定退出线程」（原先由 post 包装的 catch 与独立的
        // quit 任务分别承担这两半语义，合并后必须一起兜住）。
        // 诚实边界：openCamera 已在飞、onOpened 晚于 quit 到达的那一路（非本窗口）投递即丢，
        // Camera2 无撤销打开的 API，只能靠 onOpened 自身的代数/threadRunning 守卫兜住 quit 前到达的情形。
        workHandler.post {
            try {
                stopImageReaders()
                teardownDevice()
                extraSurface = null
                lens = null
                ability = null
                reqState = null
                chosenFps = null
                generation++
            } catch (e: Exception) {
                Log.w(TAG, "终止拆除异常（线程仍退出）：${paramSnapshot()}", e)
            } finally {
                workThread.quitSafely()
            }
        }
    }

    /** 停 ImageReader（v0.0.1 无分析流，只有调用方经 setExtraTarget 交进来的 reader 才非空） */
    private fun stopImageReaders() {
        val reader = extraReader ?: return
        runCatching { reader.setOnImageAvailableListener(null, null) }
        runCatching { reader.close() }
        extraReader = null
    }

    fun startPreview() {
        post {
            desiredPreview = true
            sinkWaitRetries = 0
            if (device == null) return@post     // onOpened 里会接着建会话
            ensureSession()
        }
    }

    /** 只停画面、保留设备与能力快照（ON_PAUSE / 停止录制后用） */
    fun stopPreview() {
        post {
            desiredPreview = false
            restoreTapFocusNow("stopPreview")
            workHandler.removeCallbacks(flushTask)
            dirty = false
            stopRepeatingQuietly()
            closeSessionQuietly()
            structuralSignature = ""
            publishPreview(PreviewStatus.STOP)
        }
    }

    /** 合并刷新入口：置脏 → 5ms 后一次性重发 repeating */
    fun markDirty() {
        if (!threadRunning || dirty) return
        dirty = true
        workHandler.postDelayed(flushTask, MERGE_DELAY_MS)
    }

    /** 错误恢复：保留设备、重建会话并重发（换渲染模式 / 高帧率失败后走这条） */
    fun restartPreview() {
        post {
            structuralSignature = ""
            closeSessionQuietly()
            if (device == null) reopenLastLens() else if (desiredPreview) ensureSession()
        }
    }

    /**
     * 追加输出面。DIRECT 模式下 record 包把 MediaRecorder 的 surface 交进来，会话形状随之重建；
     * reader 非 null 时由引擎在关闭序列里先停它。
     */
    fun setExtraTarget(surface: Surface?, reader: ImageReader? = null) {
        post {
            if (reader !== extraReader) stopImageReaders()
            extraSurface = surface
            extraReader = reader
            structuralSignature = ""
            if (desiredPreview) ensureSession()
        }
    }

    /**
     * 点按对焦三段式：repeating(AUTO + region) → 单发 TRIGGER_START → TRIGGER_IDLE，
     * 并挂 4.5s 超时恢复原 AF 模式 + 全幅测光。
     * 入参与 core 的 TapPoint 同域：[0,1] 归一化且已按 sensorOrientation 摆正。
     */
    fun tapToFocus(normX: Float, normY: Float) {
        post {
            val state = reqState ?: return@post
            if (session == null && highSpeedSession == null) return@post
            val array = state.activeArray
            if (array.isEmpty()) return@post
            cancelTapRestore()
            val box = RequestApplier.meteringBox(array.arrayWidth, array.arrayHeight, normX, normY)
            val region = listOf(RequestApplier.meteringRect(box))
            // 区域数上限可能为 0，此时写了也不生效，只发触发
            if (state.maxRegionsAf > 0) {
                tapAfRegions = region
                tapAfModeOverride = CaptureRequest.CONTROL_AF_MODE_AUTO
            }
            if (state.maxRegionsAe > 0) tapAeRegions = region
            applyRepeatingNow()                                     // 段 1
            tapTrigger = CaptureRequest.CONTROL_AF_TRIGGER_START
            singleCapture("af-trigger-start")                       // 段 2
            tapTrigger = CaptureRequest.CONTROL_AF_TRIGGER_IDLE
            applyRepeatingNow()                                     // 段 3
            scheduleTapRestore()
        }
    }

    /** 拍照/静帧单次通道（与 repeating 分离）；v0.0.1 无 UI 入口，接口先留好 */
    fun captureStill(reader: ImageReader, onImage: (Image) -> Unit) {
        post {
            val dev = device ?: return@post
            val state = reqState ?: return@post
            val hs = highSpeedSession
            val normal = session
            if (hs == null && normal == null) return@post
            val builder = try {
                dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            } catch (e: RuntimeException) {
                Log.w(TAG, "拍照模板创建失败：${e.message}")
                return@post
            }
            builder.addTarget(reader.surface)
            syncFpsPick(writeBack = false)
            RequestApplier.apply(builder, state, snapshot())
            val request = builder.build()
            reader.setOnImageAvailableListener({ latest ->
                val image = latest.acquireLatestImage()             // 丢旧保新
                if (image != null) {
                    runCatching { onImage(image) }
                    image.close()
                }
            }, workHandler)
            try {
                if (hs != null) hs.captureBurst(hs.createHighSpeedRequestList(request), null, workHandler)
                else normal?.capture(request, null, workHandler)
            } catch (e: CameraAccessException) {
                Log.w(TAG, "单次 capture 失败：${e.message}")
            } catch (e: RuntimeException) {
                Log.w(TAG, "高速请求列表被拒，改走普通会话重建：${e.message}")
                restartPreview()
            }
        }
    }

    // ------------------------------------------------- 会话

    private fun fpsValue(): Int = params.fps.value.value

    /**
     * 把「相机帧尺寸」推给输出面（GPU 侧要用它设 SurfaceTexture 默认缓冲与等比适配源比例）。
     * sink 的 onSizeChanged 语义是相机帧而非视图尺寸，视图尺寸由 UI 侧自己给（03 文档第 3 节）。
     */
    private fun pushSinkFrameSize(size: Size) {
        runCatching { sink.onSizeChanged(size.width, size.height) }
            .onFailure { Log.w(TAG, "sink.onSizeChanged 失败：${it.message}") }
    }

    /** 输出面就绪回调（GPU 引擎的 SurfaceTexture 建好后由上层通知），立即重试建会话 */
    fun onSinkSurfaceReady() {
        post {
            sinkWaitRetries = 0
            if (desiredPreview && device != null) ensureSession()
        }
    }

    /** 会话输出面集合由渲染模式决定（03 文档第 1 节：GPU 下录像面不进相机会话） */
    private fun currentTargets(): List<Surface> {
        val base = sink.cameraTargets()
        val extra = extraSurface ?: return base
        return if (params.renderMode.value == RenderMode.DIRECT) base + extra else base
    }

    private fun wantHighSpeed(): Boolean {
        if (fpsValue() <= WotaTiers.HIGH_SPEED_FPS) return false
        return ability?.highSpeedCapable == true
    }

    private fun signatureOf(size: Size, mode: RenderMode, highSpeed: Boolean, targets: List<Surface>): String =
        "${size.width}x${size.height}|$mode|hs=$highSpeed|n=${targets.size}|${sink.structuralToken()}"

    private fun ensureSession() {
        val dev = device
        if (dev == null) {
            desiredPreview = true
            return
        }
        val targets = currentTargets()
        if (targets.isEmpty()) {
            if (sinkWaitRetries < SINK_WAIT_MAX) {
                sinkWaitRetries++
                postDelayed(SINK_WAIT_MS) { if (desiredPreview) ensureSession() }
            } else {
                failPreview("预览输出面始终为空（GL/Surface 未就绪）")
            }
            return
        }
        sinkWaitRetries = 0
        val highSpeed = wantHighSpeed()
        val sig = signatureOf(params.size.value, params.renderMode.value, highSpeed, targets)
        if (sig == structuralSignature && (session != null || highSpeedSession != null)) {
            applyRepeatingNow()
            return
        }
        structuralSignature = sig
        highSpeedActive = highSpeed
        ability?.let { reqState = RequestApplier.stateOf(it, params.size.value, highSpeed) }
        closeSessionQuietly()
        publishPreview(PreviewStatus.START)

        val physicalId = lens?.physicalId
        val configs = targets.map { surface ->
            OutputConfiguration(surface).apply {
                if (physicalId != null) {
                    try {
                        setPhysicalCameraId(physicalId)
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "物理镜头 $physicalId 未登记，退回逻辑相机下发")
                    }
                }
            }
        }
        val config = SessionConfiguration(
            if (highSpeed) SessionConfiguration.SESSION_HIGH_SPEED
            else SessionConfiguration.SESSION_REGULAR,
            configs,
            workExecutor,
            sessionCallback(highSpeed, targets, ++sessionAttempt)
        )
        try {
            dev.createCaptureSession(config)
        } catch (e: SecurityException) {
            failDevice(DeviceStatus.OPEN_FAILED_PERMISSION, "建会话时权限丢失")
        } catch (e: CameraAccessException) {
            if (highSpeed) downgradeHighSpeed("高速会话创建失败 reason=${e.reason}")
            else failDevice(mapAccessReason(e.reason), "createCaptureSession 失败 reason=${e.reason}")
        } catch (e: IllegalArgumentException) {
            if (highSpeed) downgradeHighSpeed("高速输出面组合不被支持")
            else failDevice(DeviceStatus.OPEN_FAILED_INNER_ERROR, "会话输出面组合非法（尺寸/格式不受支持）")
        } catch (e: IllegalStateException) {
            // 手里的 CameraDevice 已被系统收回：createCaptureSession 抛 IllegalStateException。
            // 切渲染模式（换 sink → 重建会话）的瞬间最容易撞上；不接住就是工作线程直接崩掉，
            // 表现成「切 GPU 就闪退」。按设备断开同一路径收尾，交回 UI 引导重开
            Log.w(TAG, "建会话时设备已关闭：${e.message}")
            handleDeviceLost(dev, stale = false)
        }
    }

    /** 设备失效的统一收尾：断开回调与建会话撞已关闭都走这里，保证两条路的状态迁移一致 */
    private fun handleDeviceLost(dead: CameraDevice, stale: Boolean) {
        runCatching { dead.close() }
        if (stale) return
        device = null
        session = null
        highSpeedSession = null
        reqBuilder = null
        desiredPreview = false
        structuralSignature = ""
        publishPreview(PreviewStatus.STOP)
        publishDevice(DeviceStatus.CLOSE)
    }

    private fun sessionCallback(highSpeed: Boolean, targets: List<Surface>, attempt: Int) =
        object : CameraCaptureSession.StateCallback() {
            private val gen = generation

            override fun onConfigured(configured: CameraCaptureSession) {
                // attempt 检查堵住「配置发起后被 stopPreview/换面撤掉」的窗口：此时 session 字段
                // 还是 null，closeSessionQuietly 无会话可关，回调若照常落地就会复活预览
                if (gen != generation || attempt != sessionAttempt || !threadRunning) {
                    runCatching { configured.close() }
                    return
                }
                val dev = device ?: return
                if (highSpeed) {
                    val hs = configured as? CameraConstrainedHighSpeedCaptureSession
                    if (hs == null) {
                        downgradeHighSpeed("未取得 CameraConstrainedHighSpeedCaptureSession 实例")
                        return
                    }
                    highSpeedSession = hs
                } else {
                    highSpeedSession = null
                }
                session = configured
                prepareBuilder(dev, targets)
                publishPreview(PreviewStatus.ING)
                applyRepeatingNow()
            }

            override fun onConfigureFailed(failed: CameraCaptureSession) {
                runCatching { failed.close() }
                if (gen != generation || attempt != sessionAttempt) return
                if (highSpeed) downgradeHighSpeed("高速会话 onConfigureFailed fps=${fpsValue()}")
                else failDevice(DeviceStatus.OPEN_FAILED_INNER_ERROR, "普通会话 onConfigureFailed targets=${targets.size}")
            }
        }

    /** 模板按 AF 模式选：录像连续 → TEMPLATE_RECORD，拍照连续 → TEMPLATE_PREVIEW */
    private fun prepareBuilder(dev: CameraDevice, targets: List<Surface>) {
        val pictureCf = params.afMode.value.cam2Mode ==
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        val template = if (pictureCf) CameraDevice.TEMPLATE_PREVIEW else CameraDevice.TEMPLATE_RECORD
        val builder = try {
            dev.createCaptureRequest(template)
        } catch (e: RuntimeException) {
            Log.w(TAG, "createCaptureRequest 失败：${e.message}")
            return
        }
        targets.forEach { surface -> builder.addTarget(surface) }
        reqBuilder = builder
    }

    private fun snapshot(): RequestApplier.Snapshot = with(params) {
        RequestApplier.Snapshot(
            aeMode = aeMode.value,
            iso = iso.value.value,
            shutterNs = shutter.value.value,
            evSteps = ev.value.value,
            fps = chosenFps,
            afMode = afMode.value,
            afModeOverride = tapAfModeOverride,
            focusDiopter = manualFocusDiopter.value.value,
            zoom = zoom.value.value,
            wbPreset = wbMode.value,
            kelvin = kelvin.value.value,
            tint = tint.value.value,
            flashOverride = if (flashResetStage) Flash.OFF else null,
            flash = flash.value,
            stabilize = stabilize.value,
            afRegions = tapAfRegions,
            aeRegions = tapAeRegions,
            afTrigger = tapTrigger
        )
    }

    /** 崩溃取证用的参数快照（一行）：读参数总线不受引擎线程状态影响，给各 catch 分支共用 */
    private fun paramSnapshot(): String = with(params) {
        "aeMode=${aeMode.value} ev=${ev.value.value}steps(en=${ev.value.enabled}) " +
            "fps=${fps.value.value}(exact=${fps.value.exact}) shutter=${shutter.value.value}ns " +
            "iso=${iso.value.value} hs=$highSpeedActive session=${session != null}"
    }

    private fun applyRepeatingNow() {
        val builder = reqBuilder
        val state = reqState
        if (builder == null || state == null) return
        // 审查 P1：syncFpsPick/apply/build 原先都在 try 外，任何 RuntimeException 直接穿透
        // （flushTask 与 onConfigured 都无包装）→ 引擎线程未捕获即进程死亡。一并纳入，
        // 让下面的分类 catch 对「下发前的最后计算」同样生效。
        try {
            syncFpsPick(writeBack = true)
            RequestApplier.apply(builder, state, snapshot())
            val request = builder.build()
            val hs = highSpeedSession
            val normal = session
            if (hs != null) hs.setRepeatingBurst(hs.createHighSpeedRequestList(request), null, workHandler)
            else normal?.setRepeatingRequest(request, captureCallback, workHandler)
        } catch (e: CameraAccessException) {
            Log.w(TAG, "repeating 下发失败：${paramSnapshot()}", e)
            failDevice(mapAccessReason(e.reason), "repeating 下发失败 reason=${e.reason}")
        } catch (e: IllegalArgumentException) {
            // P2 误路由修复：原先不分会话类型一律 downgradeHighSpeed——普通会话被 IAE 时会
            // 静默把用户 fps 改写成 30、清空结构签名整个重建会话，还误报 OPEN_FAILED_HIGH_FPS。
            // 只有高速会话的「高速请求列表被拒」才是 fps 降级的正确语义；普通会话保持现状
            // （不改参数、不重建），现场留日志取证。
            if (highSpeedSession != null) {
                Log.w(TAG, "高速请求列表被拒：${paramSnapshot()}", e)
                downgradeHighSpeed("高速请求列表被拒：fps=${fpsValue()}")
            } else {
                Log.w(TAG, "repeating 请求被拒（普通会话，保持现状不重建）：${paramSnapshot()}", e)
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "会话已失效，走重建恢复：${paramSnapshot()}", e)
            restartPreview()
        }
    }

    private fun singleCapture(tag: String) {
        val builder = reqBuilder ?: return
        val state = reqState ?: return
        RequestApplier.apply(builder, state, snapshot())
        val request = builder.build()
        val hs = highSpeedSession
        val normal = session
        try {
            if (hs != null) hs.captureBurst(hs.createHighSpeedRequestList(request), null, workHandler)
            else normal?.capture(request, null, workHandler)
        } catch (e: CameraAccessException) {
            Log.w(TAG, "$tag 单发失败：${e.message}")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "$tag 高速请求列表构造失败：${e.message}")
        } catch (e: IllegalStateException) {
            Log.w(TAG, "$tag 时会话已关闭")
        }
    }

    /** 手动色温需周期重发维持，按 500ms 节流 */
    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            if (!RequestApplier.isManualWhiteBalance(params.wbMode.value)) return
            val now = System.currentTimeMillis()
            if (now - lastWbKeepAliveMs < WB_KEEP_ALIVE_MS) return
            lastWbKeepAliveMs = now
            markDirty()
        }
    }

    // ------------------------------------------------- fps 选择与钳制

    /**
     * 每次下发前对齐 fps：固定 [f,f] 优先，否则落可变范围并标 exact=false；
     * 总线值与快门上限的回写只在 [writeBack] 为真时做（避免在拍照通道里改用户值）。
     */
    private fun syncFpsPick(writeBack: Boolean) {
        val state = reqState ?: return
        val target = fpsValue()
        val normal = RequestApplier.pickFps(state.fpsRanges, target, state.highSpeed)
        if (normal != null) {
            chosenFps = normal
            if (writeBack) {
                // 总线保持用户档位（24 就是 24），不可用固定范围时用 exact 标记「※」
                writeBackFps(target, normal.exact)
                // 帧周期钳制吃**范围下界**（2026-10-04 r26 与 r10/r11 同源）：lo=档位（[24,30] 的
                // lo=24），1/24 的 41.67ms 在合法域 [1/30, 1/24] 内不再被 hi=30 钳回 1/30——
                // 旧口径在回写时就把强制档改写掉，r10 的下发修正根本见不到 1/24
                clampShutterToFrame(normal.lo)
            }
            return
        }
        // target 完全不在设备能力内（多为 120/240 无高帧率）：退到可达上限并报一次高帧率失败
        val ceiling = ceilingFps(state)
        val fallback = RequestApplier.pickFps(state.fpsRanges, ceiling, false) ?: return
        chosenFps = fallback
        if (writeBack) {
            Log.w(TAG, "设备状态→OPEN_FAILED_HIGH_FPS：fps=$target 无可用范围，退到 $ceiling")
            publishDevice(DeviceStatus.OPEN_FAILED_HIGH_FPS)
            writeBackFps(ceiling, fallback.exact)
            clampShutterToFrame(fallback.lo)
            if (device != null) publishDevice(DeviceStatus.OPEN_SUCCEED)
        }
    }

    private fun clampShutterToFrame(fps: Int) {
        val state = reqState ?: return
        val current: ParamState<Long> = params.shutter.value
        val clamped = RequestApplier.clampShutterNs(
            current.value, fps, state.exposureMinNs, state.exposureMaxNs,
            // 同 applyExposure：1/24、1/25 是强制档，回写时也不许被设备曝光范围改掉
            // （帧周期那一刀仍生效：25fps 下切到 1/24 会被压回 1/25，与钳制口径一致）
            forceDeviceRange = WotaTiers.isRequiredShutterNs(current.value)
        )
        if (current.value != clamped) params.shutter.value = current.copy(value = clamped)
    }

    /** 设备可达的最高帧率（无高帧率能力时压到 60） */
    private fun ceilingFps(state: RequestApplier.State): Int {
        val cap = if (ability?.highSpeedCapable == true) Int.MAX_VALUE else WotaTiers.HIGH_SPEED_FPS
        var best = 0
        for (r in state.fpsRanges) {
            val usable = minOf(r.hi, cap)
            if (usable > r.lo && usable > best) best = usable
        }
        return if (best > 0) best else (state.fpsRanges.minOfOrNull { it.hi } ?: 25)
    }

    /** 只在引擎确实拿到了可用范围时回写，顺带把换挡时可能被置灰的档位恢复可用 */
    private fun writeBackFps(value: Int, exact: Boolean) {
        val current: ParamState<Int> = params.fps.value
        if (current.value == value && current.exact == exact && current.enabled) return
        params.fps.value = current.copy(value = value, exact = exact, enabled = true)
    }

    private fun onStructuralPulse(pulse: Pulse) {
        val slotAbility = ability ?: return
        val previous = reqState
        val highSpeed = pulse.fps > WotaTiers.HIGH_SPEED_FPS && slotAbility.highSpeedCapable
        // 每次结构脉冲都重建能力快照：fps 档位表、4K 门禁、active array 都跟着尺寸/分支变
        reqState = RequestApplier.stateOf(slotAbility, pulse.size, highSpeed)
        pushSinkFrameSize(pulse.size)
        if (previous != null && previous.highSpeed != highSpeed) chosenFps = null
        clampIsoToAbility()
        val sig = signatureOf(pulse.size, pulse.renderMode, highSpeed, currentTargets())
        if (sig != structuralSignature) {
            if (desiredPreview && device != null) ensureSession() else markDirty()
        } else {
            markDirty()
        }
    }

    private fun clampIsoToAbility() {
        val state = reqState ?: return
        val current: ParamState<Int> = params.iso.value
        val clamped = state.clampIso(current.value)
        if (current.value != clamped) params.iso.value = current.copy(value = clamped)
    }

    // ------------------------------------------------- 点按对焦超时恢复

    private fun scheduleTapRestore() {
        cancelTapRestore()
        val task = Runnable { restoreTapFocusNow("timeout") }
        tapRestoreTask = task
        workHandler.postDelayed(task, TAP_RESTORE_MS)
    }

    private fun cancelTapRestore() {
        tapRestoreTask?.let { workHandler.removeCallbacks(it) }
        tapRestoreTask = null
    }

    /** 恢复用户原 AF 模式 + 全幅测光（否则一次点击永久改掉焦点策略） */
    private fun restoreTapFocusNow(reason: String) {
        cancelTapRestore()
        if (tapAfModeOverride == null && tapAfRegions == null && tapAeRegions == null) return
        tapAfModeOverride = null
        tapTrigger = null
        val state = reqState
        if (state != null) {
            val array = state.activeArray
            val full = RequestApplier.fullFrameBox(array.arrayWidth, array.arrayHeight)
            val regions = listOf(RequestApplier.meteringRect(full))
            if (state.maxRegionsAf > 0) tapAfRegions = regions
            if (state.maxRegionsAe > 0) tapAeRegions = regions
        }
        applyRepeatingNow()
        // 全幅与 HAL 默认等价，随后取消显式覆盖，后续帧少写两个键
        tapAfRegions = null
        tapAeRegions = null
        if (reason != "timeout") Log.i(TAG, "点按对焦已恢复（$reason）")
    }

    // ------------------------------------------------- 闪光复位序列

    private fun onFlashChanged() {
        if (session == null && highSpeedSession == null) return
        flashResetStage = true                                     // 1) 先复位为 OFF
        applyRepeatingNow()
        postDelayed(FLASH_GAP_MS) {                                // 2) 重发 → 100ms → 新值
            flashResetStage = false
            if (session != null || highSpeedSession != null) applyRepeatingNow()
        }
    }

    // ------------------------------------------------- 设备回调与错误映射

    private fun deviceCallback(gen: Int) = object : CameraDevice.StateCallback() {
        override fun onOpened(opened: CameraDevice) {
            if (gen != generation || !threadRunning) {
                runCatching { opened.close() }
                return
            }
            device = opened
            reopenRetries = 0
            publishDevice(DeviceStatus.OPEN_SUCCEED)
            if (desiredPreview) ensureSession()
        }

        override fun onDisconnected(disconnected: CameraDevice) {
            Log.w(TAG, "设备被上层收回（onDisconnected），停预览并等待重开")
            handleDeviceLost(disconnected, stale = gen != generation)
        }

        override fun onError(errored: CameraDevice, error: Int) {
            runCatching { errored.close() }
            if (gen != generation) return
            val status = when (error) {
                CameraDevice.StateCallback.ERROR_CAMERA_IN_USE,
                CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> DeviceStatus.OPEN_FAILED_IN_USED

                CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> DeviceStatus.OPEN_FAILED_PERMISSION

                else -> if (highSpeedActive) DeviceStatus.OPEN_FAILED_HIGH_FPS
                else DeviceStatus.OPEN_FAILED_INNER_ERROR
            }
            failDevice(status, "device onError code=$error")
        }
    }

    /** CameraAccessException 只公开 getReason()（无 getCode）；reason 1/2/3 与 StateCallback 错误码同值 */
    private fun mapAccessReason(reason: Int): DeviceStatus = when (reason) {
        CameraAccessException.CAMERA_IN_USE,
        CameraAccessException.MAX_CAMERAS_IN_USE -> DeviceStatus.OPEN_FAILED_IN_USED

        CameraAccessException.CAMERA_DISABLED -> DeviceStatus.OPEN_FAILED_PERMISSION

        else -> DeviceStatus.OPEN_FAILED_INNER_ERROR
    }

    /** 高帧率失败：fps 退到 ≤60 的可达上限，用普通会话重建，并把降级结果写回总线 */
    private fun downgradeHighSpeed(reason: String) {
        publishDevice(DeviceStatus.OPEN_FAILED_HIGH_FPS)
        val state = reqState
        val ceiling = state?.fpsRanges
            ?.filter { it.hi <= WotaTiers.HIGH_SPEED_FPS }
            ?.maxOfOrNull { it.hi }
        if (state == null || ceiling == null) {
            Log.w(TAG, "高帧率失败且无可退让档位：$reason")
            return
        }
        Log.w(TAG, "设备状态→OPEN_FAILED_HIGH_FPS：$reason，fps 退到 $ceiling")
        val pick = RequestApplier.pickFps(state.fpsRanges, ceiling, false) ?: return
        highSpeedActive = false
        chosenFps = pick
        writeBackFps(ceiling, pick.exact)
        structuralSignature = ""
        publishDevice(DeviceStatus.OPEN_SUCCEED)
        if (desiredPreview) ensureSession()
    }

    private fun failDevice(status: DeviceStatus, reason: String) {
        Log.w(TAG, "设备状态→$status：$reason")
        publishDevice(status)
        publishPreview(PreviewStatus.STOP)
        when (status) {
            // 占用/内部错误可自动重试；权限要等用户授权；高帧率由 downgradeHighSpeed 接管
            DeviceStatus.OPEN_FAILED_IN_USED, DeviceStatus.OPEN_FAILED_INNER_ERROR -> scheduleReopen()
            else -> Unit
        }
    }

    private fun failPreview(reason: String) {
        Log.w(TAG, "预览失败：$reason")
        publishPreview(PreviewStatus.ERROR)
    }

    private fun scheduleReopen() {
        if (reopenRetries >= REOPEN_MAX) {
            Log.w(TAG, "自动重开次数用尽，停在失败态等用户操作")
            return
        }
        reopenRetries++
        postDelayed(REOPEN_DELAY_MS) { reopenLastLens() }
    }

    private fun reopenLastLens() {
        val slot = lens ?: return
        desiredPreview = true
        open(slot)
    }

    private fun publishDevice(status: DeviceStatus) {
        deviceStatus = status
        onDeviceStatus(status)
    }

    private fun publishPreview(status: PreviewStatus) {
        if (status == PreviewStatus.ING && previewStatus != PreviewStatus.START) return
        previewStatus = status
        onPreviewStatus(status)
    }

    // ------------------------------------------------- 拆除（固定顺序）

    private fun stopRepeatingQuietly() {
        try {
            val hs = highSpeedSession
            if (hs != null) hs.stopRepeating() else session?.stopRepeating()
        } catch (e: Exception) {
            Log.w(TAG, "stopRepeating 忽略：${e.message}")
        }
    }

    private fun closeSessionQuietly() {
        // 先使配置中的在途回调作废（session 字段此刻可能是 null，下面的 close 关不到它）
        sessionAttempt++
        val hs = highSpeedSession
        val normal = session
        highSpeedSession = null
        session = null
        reqBuilder = null
        runCatching { hs?.close() }
        runCatching { normal?.close() }
    }

    /** 停 repeating → 关 session → 关 device；reader 与 Handler 由 close() 负责 */
    private fun teardownDevice() {
        cancelTapRestore()
        workHandler.removeCallbacks(flushTask)
        dirty = false
        flashResetStage = false
        tapAfModeOverride = null
        tapAfRegions = null
        tapAeRegions = null
        tapTrigger = null
        stopRepeatingQuietly()
        closeSessionQuietly()
        runCatching { device?.close() }
        device = null
        structuralSignature = ""
    }

    // ------------------------------------------------- 线程工具

    private fun post(block: () -> Unit) = postDelayed(0L, block)

    private fun postDelayed(delayMs: Long, block: () -> Unit) {
        if (!threadRunning) return
        val task = Runnable {
            if (!threadRunning) return@Runnable
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "引擎任务异常", e)
            }
        }
        if (delayMs <= 0L) workHandler.post(task) else workHandler.postDelayed(task, delayMs)
    }
}
