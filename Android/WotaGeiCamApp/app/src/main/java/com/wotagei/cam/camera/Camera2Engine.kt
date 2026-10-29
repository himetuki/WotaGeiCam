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
import android.os.SystemClock
import com.wotagei.cam.diag.Diag as Log
import android.view.Surface
import com.wotagei.cam.core.CameraAbility
import com.wotagei.cam.core.Flash
import com.wotagei.cam.core.FpsPick
import com.wotagei.cam.core.LensSlot
import com.wotagei.cam.core.LensType
import com.wotagei.cam.core.LowFpsHint
import com.wotagei.cam.core.LowFpsWatch
import com.wotagei.cam.core.ParamState
import com.wotagei.cam.core.RangeI
import com.wotagei.cam.core.RenderMode
import com.wotagei.cam.core.WbTelemetry
import com.wotagei.cam.core.Size
import com.wotagei.cam.core.TapPoint
import com.wotagei.cam.core.WotaParams
import com.wotagei.cam.core.WotaTiers
import com.wotagei.cam.record.FrameLedger
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
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
    private val onPreviewStatus: (PreviewStatus) -> Unit = {},
    /**
     * 帧率被**自动降档**的回执（2026-10-26）：一次高速档请求失败后，引擎改用普通会话并把 fps 退到
     * 可达上限，这里把"退到了多少"报给界面。用户口径：所有自动降档都要看得见——否则他只看到
     * 帧率自己变了，只会当成 bug。
     */
    private val onFpsDowngraded: (Int) -> Unit = {}
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
        /** AE/AF 状态遥测的拍点间隔（反馈 #1/#7 方案F）：1s 一拍，d 级 */
        private const val AE_AF_TELEMETRY_MS = 1_000L

        /** 帧率守卫的独立采样拍（2026-10-28 审查补盲区）：与遥测节拍同量级，1s 一拍 */
        private const val LOW_FPS_SAMPLE_PERIOD_MS = 1_000L

        /**
         * 手动白平衡运行时探测的判负上限（反馈 #6）：保活拍点 500ms × 4 = 2s 内 HAL 一直
         * 不回报 TRANSFORM_MATRIX ⇒ 判定增益不被采纳，弹回自动。
         */
        private const val WB_PROBE_MAX_FAILS = 4

        private const val REOPEN_DELAY_MS = 800L
        private const val REOPEN_MAX = 2

        /**
         * 设备状态回调专用 Looper：**进程内单例、永不 quit**。
         *
         * 为什么不用引擎自己的 workHandler：`close()` 收尾会 `quitSafely()`，此后迟到的
         * `onOpened` 投递即丢——已经打开的 CameraDevice 没人 close，相机句柄泄漏到进程结束，
         * 再次进相机页可能直接 CAMERA_IN_USE。Camera2 没有"撤销打开"的 API，唯一能做的是
         * 保证回调**一定有人收**：收到后按代数判定，过期就当场 close。
         *
         * 回调本体只做「判代 + 投回引擎线程」，不碰引擎状态；投递失败（workHandler 已退）
         * 也当场 close——这是兜底的兜底（[deviceCallback]）。
         */
        private val callbackThread: HandlerThread by lazy { HandlerThread("WotaCamCallback").apply { start() } }
        private val callbackHandler: Handler by lazy { Handler(callbackThread.looper) }
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

    /**
     * 本场会话是否已往 builder 写过**非 OFF** 的 HAL 降噪（2026-10-26）：决定退出该档时要不要
     * 显式写 OFF 把残留清掉（「写 OFF」与「没写过」是两件不同的事）。生命周期跟 builder 走：
     * [prepareBuilder] 建新 builder 时清零。
     */
    private var halNrWritten = false

    /** 「清不掉」这条日志每场只打一次（未声明 OFF 的机型），避免逐帧刷屏 */
    private var halNrStuckLogged = false

    /**
     * 当前 repeating builder 挂的目标面（2026-10-26 反馈 #1 方案A）：AE 档翻转时要**整 builder
     * 重建**（SENSOR 三键在自动档必须从请求里缺席，而 Camera2 无单键 remove，"不写"清不掉
     * 上一档写的值）——重建需要同一批 targets，故在 [prepareBuilder] 里存一份。
     */
    private var builderTargets: List<Surface> = emptyList()

    /**
     * 当前 repeating builder 对应的 AE 档（true=手动/AE_OFF）：与参数总线不一致 ⇒ 翻转发生，
     * 下一次下发前先重建 builder。null = builder 刚建、还没对过账。
     */
    private var builderAeManual: Boolean? = null

    /**
     * 上一次**打过日志**的降噪下发值（2026-10-26）：只在变化时打，避免合成刷新每 5ms 一行。
     * 初值 null 而不是 0：0 是 `NOISE_REDUCTION_MODE_OFF` 的合法值，用它当初值会让
     * "第一次就该写 OFF"的那一帧被当成"没变化"而不打日志；null 又恰好是"本次不写键"，
     * 所以再用 [halNrLoggedOnce] 区分"还没打过"与"已经打过且现在不写"。
     */
    private var halNrLastLogged: Int? = null
    private var halNrLoggedOnce = false

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

    /** 手动白平衡探测的连续不合拍数（见 WB_PROBE_MAX_FAILS；引擎线程独占写） */
    private var wbProbeFails = 0

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
        // 画面降噪档变化（2026-10-26）：HAL 档只改下发内容（脏刷新即可）；GPU↔HAL 切换要重建会话
        // ——GPU 档是"GL 侧处理、相机侧按 OFF"，进 GPU 双边/退回 HAL 时结构签名不变但下发键变，
        // 走合并刷新；renderMode 变化本就走结构脉冲重建，无需在此处理
        collectJobs += params.videoDenoise.drop(1)
            .onEach { post { markDirty() } }
            .launchIn(scope)

        collectJobs += params.flash.drop(1)
            .onEach { post { onFlashChanged() } }
            .launchIn(scope)

        // 对焦锁定（用户反馈第 2 条）：总线是唯一真源——锁定/解除在这里回执到引擎。
        // drop(1) 跳过订阅时的现状（引擎整套重开会经 applyAbility 先清锁，不欠历史账）
        collectJobs += params.focusLocked.drop(1)
            .onEach { locked -> post { if (locked) engageFocusLock("bus") else releaseFocusLock() } }
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
            highSpeedActive = slot.ability.highSpeedCapable &&
                slot.ability.needsHighSpeed(params.size.value, fpsValue())
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
                manager.openCamera(slot.logicId, deviceCallback(gen), callbackHandler)
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
        lowFpsSampler.cancel()
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
        // 迟到的 onOpened：改投进程级 [callbackHandler] 后**一定有人收**——收到先判代，
        // 过期就 close；投回本线程失败（本 looper 已退）也当场 close。已无"投递即丢"的窗口。
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
            // 锁定期跨停预览存活（Q4：ON_PAUSE→ON_RESUME 不丢锁，恢复时会话重建会复扫一次）；
            // 未锁时照旧复位点按覆写（原语义一字不变）
            if (!params.focusLocked.value) restoreTapFocusNow("stopPreview")
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
            // 对焦锁定期拒绝一切点按重焦（引擎级权威第二层，UI 已挡一层）——
            // 「锁定后轻触不重焦」这条防误触语义不能只靠 UI（R6）
            if (params.focusLocked.value) return@post
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

    /**
     * 锁定对焦（用户反馈第 2 条）：以锚点（[WotaParams.focusLockPoint]，缺省画面中心）做一次
     * 单次自动对焦后**不排超时恢复**——repeating 保持 `AF_MODE=AUTO + 区域 + TRIGGER_IDLE`，
     * 镜头停在合焦位直到显式解除（[releaseFocusLock]）。
     *
     * 幂等：会话重建（起录/停录/换段/恢复/错误重开）后由 `onConfigured` 反复重放。
     * 与 [tapToFocus] 的两处差别：① 不排 4.5s 恢复；② AUTO 覆写与区域支持**解耦**
     * （能写 AUTO 就写，区域能力为 0 就不写区——R10）。无会话时安全早退（由 onConfigured 补）。
     */
    private fun engageFocusLock(reason: String) {
        // R3a：锁定以「单次自动对焦」（AF_MODE_AUTO）为载体，能力不含它的镜头不做假开关
        // （UI 侧同步禁用锁定条目）；能力未就绪（镜头还没开）同样不动
        val slotAbility = ability ?: return
        if (!slotAbility.afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)) {
            Log.w(TAG, "锁定对焦不可用（$reason）：afModes=${slotAbility.afModes} 不含 AUTO")
            return
        }
        val state = reqState ?: return
        if (session == null && highSpeedSession == null) return
        val array = state.activeArray
        if (array.isEmpty()) return
        cancelTapRestore()
        val anchor = params.focusLockPoint.value ?: TapPoint(0.5f, 0.5f)
        val box = RequestApplier.meteringBox(array.arrayWidth, array.arrayHeight, anchor.x, anchor.y)
        val region = listOf(RequestApplier.meteringRect(box))
        // AUTO 覆写与区域解耦：写 AUTO 不依赖 maxRegionsAf（区域上限为 0 时只发模式 + 触发）
        tapAfModeOverride = CaptureRequest.CONTROL_AF_MODE_AUTO
        if (state.maxRegionsAf > 0) tapAfRegions = region
        if (state.maxRegionsAe > 0) tapAeRegions = region
        applyRepeatingNow()                                     // 段 1：AUTO + 区域
        tapTrigger = CaptureRequest.CONTROL_AF_TRIGGER_START
        singleCapture("af-lock-start")                          // 段 2：单发 START（高速会话由 singleCapture 包列表）
        tapTrigger = CaptureRequest.CONTROL_AF_TRIGGER_IDLE
        applyRepeatingNow()                                     // 段 3：保持（不排恢复）
        Log.i(
            TAG,
            "对焦已锁定（$reason）：锚点=(${anchor.x}, ${anchor.y}) afModes=${slotAbility.afModes} " +
                "maxRegionsAf=${state.maxRegionsAf}"
        )
    }

    /** 解除对焦锁定：恢复用户原 AF 模式 + 全幅测光（与点按超时恢复共用同一支） */
    private fun releaseFocusLock() {
        restoreTapFocusNow("unlock")
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
            applyParams(builder, state)
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

    /**
     * 显示面挂摘/重挂（设置页覆盖往返，GL 侧 [GlRenderEngine.onDisplaySurfaceTransient] 转入）：
     * 布防手动白平衡稳压窗。桥在显示面切换的过渡拍直接吐 HAL 过渡帧——请求此刻是 AWB_OFF
     * 就是绿帧，与会话是否重建无关（r23 实测：相机全程存活仍闪）。窗内请求走自动，过渡拍
     * 就是自然色；回场稳过后与滑杆拖动同一条"同会话重发"路径切回手动，不再闪。
     */
    fun onSinkSurfaceTransient() {
        post {
            // 只布防、**不**立刻重发请求（2026-10-29 四段取证）：表面交换瞬间的 setRepeatingRequest
            // 本身就是桥的过渡帧触发器——r26/r27 加了 markDirty 反而把绿帧找回来（真机连拍复现），
            // r25 无 markDirty 时全程干净。hold 到下一次自然重建（用户动滑杆/保活拍）才生效。
            wbHoldUntilMs = SystemClock.elapsedRealtime() + WB_SESSION_HOLD_MS
        }
    }

    /** 会话输出面集合由渲染模式决定（03 文档第 1 节：GPU 下录像面不进相机会话） */
    private fun currentTargets(): List<Surface> {
        val base = sink.cameraTargets()
        val extra = extraSurface ?: return base
        return if (params.renderMode.value == RenderMode.DIRECT) base + extra else base
    }

    private fun wantHighSpeed(): Boolean {
        val a = ability ?: return false
        if (!a.highSpeedCapable) return false
        // 帧率普通会话覆盖不了（厂商把 50/60 只放进高速表，如 vivo V2520A）⇒ 高速会话；
        // 旧阈值 fps > 60 会让 50/60 落回普通会话，而普通流根本没有这两档
        return a.needsHighSpeed(params.size.value, fpsValue())
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
        // 输出面构造的崩溃防护（2026-10-13 真机 P0）：OutputConfiguration(surface) 构造内部会查
        // surface 尺寸（SurfaceUtils.getSurfaceSize），录制重试期间 GL/编码侧已 release 旧输出面时
        // 直接抛 IllegalArgumentException("Surface was abandoned")——原先裸在 try 外，异常沿
        // WotaCamEngine 线程未捕获即终止进程。收进 try 后走既有 failDevice 收尾（与下方
        // createCaptureSession 的 IAE 同一失败码），自动重开/用户重试接管。
        val configs = try {
            targets.map { surface ->
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
        } catch (e: IllegalArgumentException) {
            failDevice(DeviceStatus.OPEN_FAILED_INNER_ERROR, "输出面已失效（Surface abandoned 或参数非法）")
            return
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
                // 重开计数归零点（自 onOpened 挪来，2026-10-13）：只有会话真正配置成功才算
                // "重开成功"——configure 连续失败的设备上 reopenRetries 得以累积到 REOPEN_MAX，
                // scheduleReopen 触顶后停在失败态交 UI 恢复，不再无限重开
                reopenRetries = 0
                publishPreview(PreviewStatus.ING)
                // 手动白平衡稳压窗（2026-10-29 用户真机报障）：设置页返回等场景会重建会话，
                // 桥/HAL 对"一帧请求里的整套 WB 键"可能非原子生效——AWB_OFF 先落、矩阵/增益
                // 后落，中间那几拍就是裸 OFF 的传感器原色绿。会话活起来的头一拍先按自动走，
                // 短稳压后再切手动（与面板里热切同一条不闪绿的路径）。
                wbHoldUntilMs = SystemClock.elapsedRealtime() + WB_SESSION_HOLD_MS
                applyRepeatingNow()
                // 会话重建复锁（Q4）：起录/停录/换段/暂停恢复/错误重开都会换一条会话，
                // 锁定态必须重放一次扫描，否则镜头被 HAL 停在重启后的未知位（R3 的观感代价）
                if (params.focusLocked.value) engageFocusLock("session-restart")
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
        builderTargets = targets
        // 新 builder = 请求里没有我们写过的任何键 ⇒ 残留账清零（生命周期与 builder 严格一致）；
        // AE 档对齐当前值（新 builder 无 SENSOR 三键，manual 档下一次 apply 会补写）
        halNrWritten = false
        halNrStuckLogged = false
        halNrLastLogged = null
        halNrLoggedOnce = false
        builderAeManual = params.aeMode.value == com.wotagei.cam.core.AeMode.MANUAL
    }

    /**
     * 画面降噪的实际档（2026-10-26）：GPU 双边只在 GPU 渲染下成立，DIRECT 时退回 HAL 快速档
     * ——口径与 UI 的 [com.wotagei.cam.core.effectiveVideoDenoise] 同一支，不许各写一份。
     */
    private fun videoDenoiseActual(): com.wotagei.cam.core.VideoDenoise =
        com.wotagei.cam.core.effectiveVideoDenoise(
            params.videoDenoise.value,
            params.renderMode.value == RenderMode.GPU
        )

    /**
     * 下发一帧参数——**三个通道（repeating / 单发 / 拍照前）共用**，`NOISE_REDUCTION_MODE` 的
     * "写过什么"就记在这里（唯一副作用点）。
     *
     * 为什么需要记：`CaptureRequest.Builder` 每会话只建一次、跨多次下发复用，且 Camera2 没有单键
     * remove ⇒ "不再写这个键"不等于"清掉上一次写的值"。策略本身在
     * [com.wotagei.cam.core.noiseReductionModeToSend]（纯函数、JVM 全测），这里只管
     * ①把决议结果塞进快照 ②把"写过什么"记回来。
     */
    private fun applyParams(builder: CaptureRequest.Builder, state: RequestApplier.State) {
        val requested = videoDenoiseActual()
        val mode = com.wotagei.cam.core.noiseReductionModeToSend(
            requested, state.noiseReductionModes, halNrWritten
        )
        // 画面降噪的**下发真值**（2026-10-26 取证）：只在"这次要写的值变了"时打一行，不跟着逐帧刷
        // （合成刷新 5ms 一拍会淹掉 logcat）。null 的两种来历必须分开报，否则排障时分不清
        // "用户关了"与"本机没声明这一档"：
        //   · requested=OFF 且本场没写过 ⇒ 正常（不碰厂商默认）
        //   · requested=HAL 但不在声明表 ⇒ 该档在本机不可用（面板本应已灰显）
        if (!halNrLoggedOnce || mode != halNrLastLogged) {
            halNrLoggedOnce = true
            halNrLastLogged = mode
            val why = when {
                mode != null -> "写入 ${halNrName(mode)}"
                // GPU 双边是**设计上**不写 HAL 键（由 GL 管线独占，避免 HAL+GL 双重降噪）——
                // 这种情况必须与"HAL 档在本机不可用"分开报，否则排障会误判成能力缺失
                requested == com.wotagei.cam.core.VideoDenoise.GPU_BILATERAL ->
                    "不写（GPU 双边由 GL 管线独占，HAL 侧按不降噪处理）" +
                        if (halNrWritten) "；本次写 OFF 清残留" else ""
                requested != com.wotagei.cam.core.VideoDenoise.OFF ->
                    "不写（本机未声明该档：$requested，declared 里没有）"
                else -> "不写（OFF；本场未写过该键）"
            }
            Log.i(
                TAG,
                "画面降噪下发 requested=$requested highSpeed=${state.highSpeed}" +
                    " declared=${state.noiseReductionModes.sorted()} -> $why"
            )
        }
        if (mode == null && halNrWritten && !halNrStuckLogged) {
            // 退出 HAL 档却清不掉：本机没声明 OFF。留一条日志（用户排障时能看出"关不掉"的原因），
            // 不阻断任何东西——下一场会话（新 builder）自然干净。
            halNrStuckLogged = true
            Log.w(TAG, "退出画面降噪但本机未声明 NOISE_REDUCTION_MODE_OFF，本场请求仍带上一档（下次开会话恢复）")
        }
        RequestApplier.apply(builder, state, snapshot(mode))
        if (mode != null) {
            halNrWritten = mode != CaptureRequest.NOISE_REDUCTION_MODE_OFF
        }
    }

    /** 降噪模式的日志短名（Camera2 的 NOISE_REDUCTION_MODE_* 就这五档） */
    private fun halNrName(mode: Int): String = when (mode) {
        CaptureRequest.NOISE_REDUCTION_MODE_OFF -> "OFF(0)"
        CaptureRequest.NOISE_REDUCTION_MODE_FAST -> "FAST(1)"
        CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY -> "HIGH_QUALITY(2)"
        CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL -> "MINIMAL(3)"
        CaptureRequest.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG -> "ZSL(4)"
        else -> "mode($mode)"
    }

    private fun snapshot(halNoiseReductionMode: Int?): RequestApplier.Snapshot = with(params) {
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
            // 兜底同理：能力表说增益不被采纳、但 GPU 管线在 ⇒ 请求侧保持自动白平衡，
            // 色温/色调交给画面算法（见 WotaParams.publishWbPlan）
            wbByGl = wbByGlNow(),
            // 手动档随增益冻结的 HAL 回报 CCM（null = 没观测到 ⇒ 下发侧退恒等）
            wbSeedCcm9 = lastAwbCcm9,
            flashOverride = if (flashResetStage) Flash.OFF else null,
            flash = flash.value,
            stabilize = stabilize.value,
            afRegions = tapAfRegions,
            aeRegions = tapAeRegions,
            afTrigger = tapTrigger,
            halNoiseReductionMode = halNoiseReductionMode,
            fpsTarget = fpsValue()
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
            // AE 档翻转（反馈 #1 方案A）：SENSOR 三键在自动档必须从请求里**缺席**，
            // 而 builder 无删键 ⇒ 整个重建（键集天然自洽，applyParams 随后全量重放其余键）。
            // 放在 try 内：与 syncFpsPick/apply/build 同一条 P1 纪律（任何 RuntimeException 不许穿透）。
            val manualNow = params.aeMode.value == com.wotagei.cam.core.AeMode.MANUAL
            if (builderAeManual != manualNow) {
                device?.takeIf { builderTargets.isNotEmpty() }?.let { prepareBuilder(it, builderTargets) }
                builderAeManual = manualNow
                Log.i(TAG, "AE 档翻转 → 重建 repeating builder（manual=$manualNow），清 SENSOR 三键残留")
            }
            // builder 可能刚被重建：apply 与 build 都必须落在新 builder 上——
            // 【2026-10-27 复审修掉的 P1】重建后若仍 build 旧 builder，翻转那一次下发的是
            // 带 SENSOR 三键残值的旧请求（闪动修复在翻转瞬间静默失效，且 AUTO 档此后
            // 没有别的下发时机会自愈）。prepareBuilder 失败时 reqBuilder 不动 = 沿用旧值。
            val effectiveBuilder = reqBuilder ?: builder
            applyParams(effectiveBuilder, state)
            val request = effectiveBuilder.build()
            val hs = highSpeedSession
            val normal = session
            if (hs != null) hs.setRepeatingBurst(hs.createHighSpeedRequestList(request), frameCountCallback, workHandler)
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
        applyParams(builder, state)
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

    /**
     * 高速会话的帧计数回调（2026-10-17 帧账）：高速路原先传 `null` 回调，若直接复用
     * [captureCallback] 会把「手动白平衡 500ms 保活」带进高速路（行为变更）。这里只做
     * `onCaptureStarted` 自增，其余一概不碰，保证既有 request/callback 语义逐字不变。
     */
    private val frameCountCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            timestamp: Long,
            frameNumber: Long
        ) {
            FrameLedger.onCameraStarted()
        }
    }

    /** 手动色温需周期重发维持，按 500ms 节流（单调钟，见下） */
    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        /**
         * 相机帧到达计数（2026-10-17 帧账）：**只自增一枚原子计数**，不读参数、不重发请求、
         * 不改任何回调语义。单发请求不带本回调（见 [singleCapture]），不会混进手动 AF 的额外帧。
         */
        override fun onCaptureStarted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            timestamp: Long,
            frameNumber: Long
        ) {
            FrameLedger.onCameraStarted()
        }

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            camResultCount++
            logAeAfTelemetry(result)
            val manual = RequestApplier.isManualWhiteBalance(params.wbMode.value)
            logWbTelemetry(result, manual)
            // 种子捕获：只要回报显示 3A 自己的色彩管理还在跑（模式不是 TRANSFORM_MATRIX），
            // HAL 回报的 CCM 就是"活"的，抓来当种子（500ms 节流）。手动 HAL 档回报的是我们
            // 冻结的种子，不抓；稳压窗/门控期间请求走自动，回报是 FAST ⇒ 照常续上种子——
            // 这样"手动档从会话第一拍就开着"（冷启 prefs 恢复）也能在几百毫秒内拿到种子。
            val reportedCcm = runCatching {
                result.get(android.hardware.camera2.CaptureResult.COLOR_CORRECTION_MODE)
            }.getOrNull()
            val nowMs = SystemClock.elapsedRealtime()
            if (reportedCcm != CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX &&
                nowMs - lastAwbCcmAtMs > 500
            ) {
                transform9Of(result)?.let {
                    lastAwbCcm9 = it
                    lastAwbCcmAtMs = nowMs
                }
            }
            if (!manual) {
                wbProbeFails = 0
                return
            }
            // 单调钟而非 System.currentTimeMillis()（2026-10-10 S3-03）：墙钟被往回调时
            // `now - lastWbKeepAliveMs` 变负、`< WB_KEEP_ALIVE_MS` 恒真 ⇒ 手动白平衡保活永久停摆，
            // 颜色慢慢漂回自动且无错误码。同族先例：record/Recorder.kt 的 ElapsedClock。
            val now = SystemClock.elapsedRealtime()
            if (now - lastWbKeepAliveMs < WB_KEEP_ALIVE_MS) return
            lastWbKeepAliveMs = now
            markDirty()
            // 请求侧不在手动 HAL 路径时探测不适用：画面算法路径请求本就不带 TRANSFORM_MATRIX；
            // 种子未到手（下发侧留在自动等种子）同理——都不是"HAL 拒绝"的证据，不许误闩
            if (wbByGlNow() || lastAwbCcm9 == null) {
                wbProbeFails = 0
                return
            }
            // 手动白平衡**运行时探测**（2026-10-26 反馈 #6）：公开 API 拿不到
            // `android.colorCorrection.availableModes`（隐藏键，getKeys 不暴露，真机实证），
            // "支不支持 TRANSFORM_MATRIX"只能行为探测——我们请求了 TRANSFORM_MATRIX，
            // HAL 回报的却是别的模式 ⇒ 增益没被采纳（华为 ALN-AL00：AWB_OFF 被采纳画面变绿、
            // 增益被忽略滑杆无响应）。连续 4 拍（≈2s）不合 ⇒ 弹回 AUTO 并留日志。
            // 判据只在探测窗口连续累计；用户切回自动即清零。
            val applied = runCatching { result.get(android.hardware.camera2.CaptureResult.COLOR_CORRECTION_MODE) }
                .getOrNull()
            if (applied == CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX) {
                wbProbeFails = 0
            } else {
                wbProbeFails++
                Log.w(
                    TAG,
                    "手动白平衡探测：请求 TRANSFORM_MATRIX 但 HAL 回报 applied=$applied（连续 $wbProbeFails 拍）"
                )
                if (wbProbeFails >= WB_PROBE_MAX_FAILS) {
                    wbProbeFails = 0
                    // 2026-10-28：不再把手动档弹回自动——"HAL 不采纳增益"不再是死路，
                    // 而是切换到画面算法的信号（WIKO 实测：AWB_MODE_OFF 生效、增益被丢，
                    // 画面只剩传感器原色发绿）。闩锁由 wbApply 统一裁决。
                    // 只在**翻真的那一次**留日志：这一拍每 2s 一轮，不封口会刷屏。
                    if (!params.wbHalRejected.value) {
                        Log.w(TAG, "本机 HAL 不采纳手动白平衡增益（模式回声不是 TRANSFORM_MATRIX）⇒ 改走画面算法")
                        params.wbHalRejected.value = true
                    }
                }
            }
            // 「AWB 未停」判据（2026-10-29 三段取证定稿）：请求 AWB_MODE=OFF 被采纳时规范要求
            // 回报 INACTIVE（或缺报）；连续 4 拍仍在 SEARCHING/CONVERGED/LOCKED ⇒ 本机 3A 压根
            // 没停，增益/矩阵随时可能被它覆盖。华为 ALN-AL00/卓易通桥实测正是如此：
            //   · r22（无保活）：表面重挂触发重开会话，新会话首拍采纳增益 → 只闪单帧绿；
            //   · r26（新增保活、曾删本判据）：会话跨设置存活，表面重挂后桥长期丢弃增益
            //     → 持续绿屏（真机连拍 G/B≈9.6 不收敛），比闪一帧还糟；
            //   · r25（保活+本判据）：闩锁改走画面算法，颜色全程正常（2000K 蓝补偿）。
            // 结论：这枚回报是这台桥『增益不会被采纳』的唯一可靠信号，判据必须留；
            // 诚实起见提示文案已改为「本机白平衡不接受手动增益」（见 strings_camera）。
            val awbState = runCatching {
                result.get(android.hardware.camera2.CaptureResult.CONTROL_AWB_STATE)
            }.getOrNull()
            if (awbState != null &&
                awbState != android.hardware.camera2.CaptureResult.CONTROL_AWB_STATE_INACTIVE
            ) {
                wbAwbActiveFails++
                if (wbAwbActiveFails >= WB_PROBE_MAX_FAILS && !params.wbHalRejected.value) {
                    wbAwbActiveFails = 0
                    Log.w(TAG, "本机请求 AWB_OFF 但回报 awbState=$awbState（自动白平衡未停）⇒ 改走画面算法")
                    params.wbHalRejected.value = true
                }
            } else {
                wbAwbActiveFails = 0
            }
        }
    }

    /**
     * AE/AF 状态遥测（2026-10-26 反馈 #1/#7 方案F）：每秒一拍，d 级（详细档才记）。
     *
     * 为什么是它：曝光闪动 / 弱光对焦差这类 3A 行为问题，此前现场**只有结果**（用户口述"闪"），
     * 没有中间态。`CONTROL_AE_STATE / AF_STATE / AF_MODE` 每秒快照进日志后，"闪动时刻 AE 在
     * SEARCHING 还是 CONVERGED""弱光下 AF 卡在 ACTIVE_SCAN"这类归因一眼可判——取证密度
     * 是本轮的明线任务（用户指令：日志多解释）。`get` 失败按"未知"打，不让单键异常炸回调。
     *
     * 频率与级别：1s 一拍不会淹没缓冲；d 级吃详细档（Diag 拆闸后 logcat 的 d/v 仍受详细档），
     * 不开详细档就零输出——遥测是排查工具，不是常态记录。
     */
    private var lastAeAfTelemetryMs = 0L

    // ---- 运行时帧率坍塌守卫（2026-10-28 用户6/7；判定逻辑在 core.LowFpsWatch，JVM 单测）----
    // 到帧率最诚实的源头就是 capture result 的到达节奏（成片卡不卡的物理成因）。
    // 计数在结果回调累加；采样由 [lowFpsSampler] 的**独立 1s 定时拍**驱动——不挂在结果回调上：
    // HAL 完全断流（零回调）时结果路径整体停摆，挂在结果上的采样也会跟着失声，守卫对
    // "一颗帧都不来"的病态反而哑火（2026-10-28 审查补盲区）。定时拍读计数即可把零到达
    // 如实喂成 0fps。取景/录制全程运行；设备不在 OPEN_SUCCEED 时采样函数自己清提示。
    private var camResultCount = 0L
    private var camRateLastMs = 0L
    private var lowFpsWatch: LowFpsWatch? = null

    private val lowFpsSampler: Job = scope.launch {
        while (isActive) {
            delay(LOW_FPS_SAMPLE_PERIOD_MS)
            sampleCameraRate(SystemClock.elapsedRealtime())
        }
    }

    private fun logAeAfTelemetry(result: TotalCaptureResult) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAeAfTelemetryMs < AE_AF_TELEMETRY_MS) return
        lastAeAfTelemetryMs = now
        val aeState = runCatching { result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE) }.getOrNull()
        val afState = runCatching { result.get(android.hardware.camera2.CaptureResult.CONTROL_AF_STATE) }.getOrNull()
        val afMode = runCatching { result.get(android.hardware.camera2.CaptureResult.CONTROL_AF_MODE) }.getOrNull()
        val aeFps = runCatching {
            result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)?.let { "[${it.lower},${it.upper}]" }
        }.getOrNull()
        Log.d(
            TAG,
            "3A遥测 aeState=$aeState afState=$afState afMode=$afMode fpsRange=${aeFps ?: "无"}" +
                " lensFocus=${runCatching { result.get(android.hardware.camera2.CaptureResult.LENS_FOCUS_DISTANCE) }.getOrNull()}"
        )
    }

    /**
     * 一拍实测到帧率喂给守卫；状态翻转才写总线（StateFlow 同值天然去重，这里省的是装帧）。
     * 会话没开成功时不判也不留旧提示——取景器都不在，"帧率低"无从谈起。
     * 换档（目标变了）换新守卫：旧账的宽限/连续/触发态对新档位全是噪音。
     */
    private fun sampleCameraRate(now: Long) {
        val last = camRateLastMs
        camRateLastMs = now
        val count = camResultCount
        camResultCount = 0
        if (deviceStatus != DeviceStatus.OPEN_SUCCEED) {
            lowFpsWatch = null
            if (params.lowFpsHint.value != null) params.lowFpsHint.value = null
            return
        }
        val target = params.fps.value.value
        if (target <= 0) return
        val watch = lowFpsWatch?.takeIf { it.targets(target) } ?: LowFpsWatch(target).also { lowFpsWatch = it }
        if (last == 0L) return  // 首拍只立基准（这段窗口跨了会话建立，速率不可信）
        val measured = count * 1000f / (now - last).coerceAtLeast(1L)
        val alarm = watch.onSample(now, measured)
        val hint = if (alarm) {
            LowFpsHint(targetFps = target, achievedFps = measured.roundToInt().coerceAtLeast(1))
        } else {
            null
        }
        if (params.lowFpsHint.value != hint) {
            Log.i(TAG, "帧率守卫：target=${target}fps 实测=${"%.1f".format(measured)}fps ⇒ ${if (alarm) "提示" else "解除"}")
            params.lowFpsHint.value = hint
        }
    }

    // ------------------------------------------------- 白平衡取证遥测（28 §2.4 批次 1）

    /** AUTO 档快照节流：1s 一拍（与 3A 遥测同节奏），d 级——详细档才输出，常态零刷屏 */
    private val WB_AUTO_TELEMETRY_MS = 1_000L

    /** MANUAL 档对比节流：2s 一拍，i 级——用户停留手动档才有输出，且这是 B3 前最重要的取证行 */
    /**
     * 这一拍手动白平衡是否走画面算法（= 请求侧必须留自动白平衡）。
     * 判据与 [com.wotagei.cam.core.wbApplyPlan] 同源：手动档 + 能力表说增益不被采纳 + GPU 管线在。
     * 2026-10-29 补上运行期闩锁（[com.wotagei.cam.core.WotaParams.wbHalRejected]）：wbApply 已吃
     * 这枚闩锁，快照侧漏吃 ⇒ 请求仍在发 AWB_MODE_OFF 而 GL 又在乘增益，两条腿打架——两侧必须
     * 同一份答案。
     */
    private fun wbByGlNow(): Boolean =
        params.wbMode.value == com.wotagei.cam.core.WbPreset.MANUAL &&
            params.renderMode.value == com.wotagei.cam.core.RenderMode.GPU &&
            // 与 WotaParams.wbApply 同口径：只有 HAL **明确声明** TRANSFORM_MATRIX 才走 HAL
            (ability?.halDeclaresTransformMatrix() != true ||
                params.wbHalRejected.value ||
                // 会话重建稳压窗：窗内先按自动走，WB 键整套装稳后再切手动
                SystemClock.elapsedRealtime() < wbHoldUntilMs)

    /** 增益采纳判据的相对容差（见 logWbTelemetry）：HAL 量化/归一化不算未采纳 */
    private val WB_ADOPT_TOL = 0.15f

    /** 连续多少拍对不上就判定「本机不采纳手动增益」（≈2s，与 500ms 保活拍同频） */
    private val WB_ADOPT_MAX_FAILS = 4

    /** 连续不合格拍数（引擎线程独占写） */
    private var wbAdoptFails = 0

    /** 「请求 AWB_OFF 但回报里 AWB 还在跑」的连续计数（探测窗同频，引擎线程独占写） */
    private var wbAwbActiveFails = 0

    /**
     * 手动档 `COLOR_CORRECTION_TRANSFORM` 的种子（见 [RequestApplier.Snapshot.wbSeedCcm9]）：
     * 自动档帧结果里 HAL 回报的最新 CCM（行优先 9 元素）。切手动档后 AWB 停更，矩阵就冻结在
     * 这份上——正是"绝对色温"语义需要的那个传感器→sRGB 映射。只增不改空判：单帧缺报不冲掉
     * 上一次的好观测；换镜头后 ≤0.5s 内可能短暂带上一颗头的种子，比退恒等更接近真值。
     */
    private var lastAwbCcm9: FloatArray? = null

    /** 种子捕获节流（500ms）：CCM 随光源慢漂，没必要逐帧抓 */
    private var lastAwbCcmAtMs = 0L


    /**
     * 会话重建后的手动白平衡稳压窗截止时刻（见 onConfigured 成功侧）。窗内请求侧按自动白平衡
     * 走（与画面算法互不相干：wbApply 仍是 byHal ⇒ GL 不乘增益，画面就是纯自动）。
     */
    private var wbHoldUntilMs = 0L

    /** 稳压窗时长：桥/HAL 把整套 WB 键落稳的经验值，0.8s 内以自动档示人不闪绿 */
    private val WB_SESSION_HOLD_MS = 800L

    private val WB_MANUAL_TELEMETRY_MS = 2_000L

    private var lastWbAutoTelemetryMs = 0L
    private var lastWbManualTelemetryMs = 0L

    /**
     * 白平衡取证遥测（28 §2.4；判读表见 core/WbTelemetry 的 KDoc）：
     * - AUTO：回报 gains / CCM / AWB 状态——「中性增益在哪、R1 是否成立」的实据；
     * - MANUAL：请求 gains（下发侧纯口径 [RequestApplier.manualGains]）vs 回报 gains + 采纳判据
     *   ——「HAL 是否覆盖增益」的实据（TRANSFORM_MATRIX 回报对不代表增益被采纳）。
     * 所有键 runCatching 读取；格式化交给 [WbTelemetry]（JVM 单测覆盖）。
     */
    private fun logWbTelemetry(result: TotalCaptureResult, manual: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (manual) {
            if (wbByGlNow()) {
                // 已改走画面算法：请求侧捕的是 AWB，本来就没有我们的增益，不再比对
                wbAdoptFails = 0
                return
            }
            if (now - lastWbManualTelemetryMs < WB_MANUAL_TELEMETRY_MS) return
            lastWbManualTelemetryMs = now
            val request = RequestApplier.manualGains(params.kelvin.value.value, params.tint.value.value)
            val reported = gainsOf(result)
            // 采纳判据用**相对容差**（0.15）：量化/归一化不算未采纳，HAL 整套丢掉增益才算
            val adopted = WbTelemetry.gainsAdopted(request, reported, tol = WB_ADOPT_TOL)
            if (!adopted) {
                wbAdoptFails++
                if (wbAdoptFails >= WB_ADOPT_MAX_FAILS && !params.wbHalRejected.value) {
                    // 连续多拍"请求了却没回报" ⇒ 本机 HAL 不采纳手动增益（WIKO：AWB_MODE_OFF 照做、
                    // gains 丢弃，画面只剩传感器原色发绿）。改由画面算法接管 + 运行时提示。
                    Log.w(
                        TAG,
                        "本机 HAL 不采纳手动白平衡增益（连续 $wbAdoptFails 拍请求与回报对不上）⇒ 改走画面算法"
                    )
                    params.wbHalRejected.value = true
                    markDirty()
                }
            } else {
                wbAdoptFails = 0
            }
            Log.i(
                TAG,
                "WB遥测[manual] 请求=${WbTelemetry.formatGains(request)} 回报=${WbTelemetry.formatGains(reported)}" +
                    " 采纳=${if (adopted) "是" else "否（HAL 覆盖/未采纳）"}" +
                    " ccm=${WbTelemetry.formatTransform9(transform9Of(result))}" +
                    " awbState=${runCatching { result.get(android.hardware.camera2.CaptureResult.CONTROL_AWB_STATE) }.getOrNull()}"
            )
        } else {
            if (now - lastWbAutoTelemetryMs < WB_AUTO_TELEMETRY_MS) return
            lastWbAutoTelemetryMs = now
            val gains = gainsOf(result)
            Log.d(
                TAG,
                "WB遥测[auto] gains=${WbTelemetry.formatGains(gains)} 比值=${WbTelemetry.formatGainsRatios(gains)}" +
                    " ccm=${WbTelemetry.formatTransform9(transform9Of(result))}" +
                    " awbState=${runCatching { result.get(android.hardware.camera2.CaptureResult.CONTROL_AWB_STATE) }.getOrNull()}"
            )
        }
    }

    /** `COLOR_CORRECTION_GAINS`（RggbChannelVector）→ [R,G,B]；缺报/异常按「无」处理（保持花括号体：守卫按函数体定位） */
    private fun gainsOf(result: TotalCaptureResult): FloatArray? {
        return runCatching {
            val v = result.get(android.hardware.camera2.CaptureResult.COLOR_CORRECTION_GAINS)
                ?: return@runCatching null
            floatArrayOf(v.red, v.greenEven, v.blue)
        }.getOrNull()
    }

    /** `COLOR_CORRECTION_TRANSFORM` → 行优先 9 元素；缺报/异常都按「无」处理 */
    private fun transform9Of(result: TotalCaptureResult): FloatArray? = runCatching {
        val t = result.get(android.hardware.camera2.CaptureResult.COLOR_CORRECTION_TRANSFORM)
            ?: return@runCatching null
        FloatArray(9) { idx -> t.getElement(idx % 3, idx / 3).toFloat() }
    }.getOrNull()

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
            if (chosenFps != normal) {
                // 帧率决议留痕（2026-10-26）：50/60 这类"区间内由 HAL 定"的档，事后只能靠这行
                // 判断到底下发成了什么；ranges 一并打出来，机型差异（表长什么样）不用重编就能核。
                // 这就是"给适配留的口子"：真机 logcat 一行 `adb logcat -s WotaCam | grep 帧率决议`
                Log.i(
                    TAG,
                    "帧率决议 fps=$target -> [${normal.lo},${normal.hi}] exact=${normal.exact} " +
                        "highSpeed=${state.highSpeed} ranges=[${rangesSummary(state.fpsRanges)}]"
                )
            }
            chosenFps = normal
            if (writeBack) {
                // 总线保持用户档位（24 就是 24），不可用固定范围时用 exact 标记「※」
                writeBackFps(target, normal.exact)
                // 帧周期钳制吃**范围下界**（2026-10-04 r26 与 r10/r11 同源）：lo=档位（[24,30] 的
                // lo=24），1/24 的 41.67ms 在合法域 [1/30, 1/24] 内不再被 hi=30 钳回 1/30——
                // 旧口径在回写时就把强制档改写掉，r10 的下发修正根本见不到 1/24
                // 钳制基准 = **目标档位**（不是区间下界）：可变高速档 [30,120] 的 lo=30 会让
                // 50/60 档放行 1/30 死档（帧周期 20ms 装不下），见 Snapshot.fpsTarget 的说明
                clampShutterToFrame(target)
            }
            return
        }
        // target 完全不在设备能力内（多为 120/240 无高帧率）：退到可达上限并报一次高帧率失败
        val ceiling = ceilingFps(state)
        if (ceiling <= 0) {
            // 无退档目标：cap 之下一个可用范围都没有，保持总线原值（随便退到一个数只会写回
            // 设备给不出的帧率）。2026-10-10 S3-S3-01：旧兜底 minOfOrNull{hi} 正是这条的来源。
            // **行为一字未动**（早退、不发 OPEN_FAILED_HIGH_FPS、不回写帧率），本轮只把这行
            // 扩成可判读的一行：ranges + target + cap + ceiling 判定，logcat 一行 grep 得完。
            // 判读口径见 docs/LEGACY退档抽验卡-v1.md。
            val capText = if (fpsCap() == Int.MAX_VALUE) "MAX" else fpsCap().toString()
            Log.w(
                TAG,
                "设备状态→OPEN_FAILED_HIGH_FPS：fps=$target 无可用范围且无退档目标，保持 $target；" +
                    "ranges=[${rangesSummary(state.fpsRanges)}] cap=$capText ceiling=$ceiling " +
                    "判定=保持总线原值·不回写帧率·不发高帧率失败态"
            )
            return
        }
        val fallback = RequestApplier.pickFps(state.fpsRanges, ceiling, false) ?: return
        chosenFps = fallback
        if (writeBack) {
            Log.w(TAG, "设备状态→OPEN_FAILED_HIGH_FPS：fps=$target 无可用范围，退到 $ceiling")
            publishDevice(DeviceStatus.OPEN_FAILED_HIGH_FPS)
            writeBackFps(ceiling, fallback.exact)
            clampShutterToFrame(ceiling)
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

    /**
     * 设备可达的最高帧率（无高帧率能力时压到 60）；算式在纯函数 [FrameRateFallback.ceilingFps] 里。
     *
     * 返回 0 = cap 之下一个可用范围都没有（无退档目标），[syncFpsPick] 据此保持总线原值——
     * 旧口径在这里退到 `minOfOrNull { it.hi }`，那是全表**最小**上界，等于绕开 cap 写回一个设备
     * 根本给不出的帧率；同时固定档因 `usable > r.lo` 恒假而被全排除（2026-10-10 S3-S3-01）。
     */
    // 保持**花括号体**：既有接线守卫用 bodyOf 定位本函数体，改成表达式体会让守卫直接失去输入。
    private fun ceilingFps(state: RequestApplier.State): Int {
        return FrameRateFallback.ceilingFps(state.fpsRanges, fpsCap())
    }

    /**
     * 高帧率封顶（ms 无关，纯帧率）：无高帧率能力时压到 [WotaTiers.HIGH_SPEED_FPS]。
     *
     * 单一真源：算式**只**在这里。退档口径与退档日志都要报这个值，抄第二份就会让
     * 「日志说的 cap」与「实际算的 cap」分叉，而分叉出来的日志比没日志更坏（判读被带偏）。
     */
    // 保持**花括号体**：接线守卫用 bodyOf 定位本函数体（`#15` 新增的 cap 单一真源守卫），
    // 改成表达式体会让守卫直接失去输入。
    private fun fpsCap(): Int {
        return if (ability?.highSpeedCapable == true) Int.MAX_VALUE else WotaTiers.HIGH_SPEED_FPS
    }

    /** 能力帧率表的行内摘要（`15-15,24-24,…`）：退档日志要能一行读完设备到底报了什么 */
    private fun rangesSummary(ranges: List<RangeI>): String =
        if (ranges.isEmpty()) "空" else ranges.joinToString(",") { "${it.lo}-${it.hi}" }

    /** 只在引擎确实拿到了可用范围时回写，顺带把换挡时可能被置灰的档位恢复可用 */
    private fun writeBackFps(value: Int, exact: Boolean) {
        val current: ParamState<Int> = params.fps.value
        if (current.value == value && current.exact == exact && current.enabled) return
        // 走 setFps 单一写入者（28 §4.4）：引擎自动降档也是「用户可见的档位变化」，
        // 快门可选域必须同一拍按新档位重算，否则退到 24fps 后滑杆仍被 1/60 顶住
        params.setFps(current.copy(value = value, exact = exact, enabled = true))
    }

    private fun onStructuralPulse(pulse: Pulse) {
        val slotAbility = ability ?: return
        val previous = reqState
        val highSpeed = slotAbility.highSpeedCapable && slotAbility.needsHighSpeed(pulse.size, pulse.fps)
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
        // 竞态双保险：锁定在 4.5s 窗口内开启时，超时恢复不许把锁定拉回连续模式
        if (params.focusLocked.value) return
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

    /**
     * 设备状态回调：跑在进程级 [callbackHandler] 上（永不 quit），**不直接改引擎状态**——
     * 一律投回 workHandler；`Handler.post` 在 looper 已退时返回 false，此时就地把句柄 close。
     * 这条"投递失败也 close"是"迟到 onOpened 泄漏相机句柄"的根治点（旧实现投到 workHandler，
     * close() 之后投递即丢）。
     */
    private fun deviceCallback(gen: Int) = object : CameraDevice.StateCallback() {
        override fun onOpened(opened: CameraDevice) {
            val queued = workHandler.post {
                if (gen != generation || !threadRunning) {
                    runCatching { opened.close() }
                    return@post
                }
                // 并发重开兜底：新设备已就位时这枚过期句柄必须关掉；绝不能覆盖字段
                //（覆盖 = 旧句柄再也没人 close）
                val cur = device
                if (cur != null && cur !== opened) {
                    runCatching { opened.close() }
                    return@post
                }
                device = opened
                // 注意：重开计数**不在这里归零**（2026-10-13 真机缺陷已修）。归零点挪到了
                // onConfigured 成功侧——否则「open 成功 → configure 反复失败」的设备上每轮重开
                // 都会把计数清零，REOPEN_MAX 永不可达 ⇒ 黑屏无限重开循环
                publishDevice(DeviceStatus.OPEN_SUCCEED)
                if (desiredPreview) ensureSession()
            }
            if (!queued) runCatching { opened.close() }
        }

        override fun onDisconnected(disconnected: CameraDevice) {
            val queued = workHandler.post {
                if (!threadRunning) {
                    runCatching { disconnected.close() }
                    return@post
                }
                Log.w(TAG, "设备被上层收回（onDisconnected），停预览并等待重开")
                handleDeviceLost(disconnected, stale = gen != generation)
            }
            if (!queued) runCatching { disconnected.close() }
        }

        override fun onError(errored: CameraDevice, error: Int) {
            // 错误路径先关设备，不依赖投递是否成功
            runCatching { errored.close() }
            workHandler.post {
                if (!threadRunning || gen != generation) return@post
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
    }

    /** CameraAccessException 只公开 getReason()（无 getCode）；reason 1/2/3 与 StateCallback 错误码同值 */
    private fun mapAccessReason(reason: Int): DeviceStatus = when (reason) {
        CameraAccessException.CAMERA_IN_USE,
        CameraAccessException.MAX_CAMERAS_IN_USE -> DeviceStatus.OPEN_FAILED_IN_USED

        CameraAccessException.CAMERA_DISABLED -> DeviceStatus.OPEN_FAILED_PERMISSION

        else -> DeviceStatus.OPEN_FAILED_INNER_ERROR
    }

    /**
     * 高帧率失败：退到**普通表**里 ≤60 的可达上限，用普通会话重建，并把降级结果写回总线。
     *
     * 【2026-10-26 修正】退档候选原先取自 `state.fpsRanges`——高速态下它就是**高速表**，
     * 而高速表的 `hi` 全是 120/240，`filter { hi <= 60 }` 恒空 ⇒ 退档分支只打一行日志就 return：
     * 不写回 fps、不重建会话 ⇒ **取景器假死**（用户要切镜头才恢复）。这次的 50/60 修复正好把
     * "高速请求被拒"从几乎不可达变成常见路径，所以这条必须同时修。
     * 普通表才装着"本机正常能跑的帧率"，退档要找的正是它。
     */
    private fun downgradeHighSpeed(reason: String) {
        publishDevice(DeviceStatus.OPEN_FAILED_HIGH_FPS)
        val state = reqState
        val normalRanges = normalFpsRangesOf()
        val ceiling = normalRanges
            .filter { it.hi <= WotaTiers.HIGH_SPEED_FPS }
            .maxOfOrNull { it.hi }
        if (state == null || ceiling == null) {
            // ability 拿不到（open 失败窗口）时 normalRanges 为空 ⇒ 走这条明确失败：
            // 设备状态已是 OPEN_FAILED_HIGH_FPS，UI 有遮罩与自动重开路径，不假装能退档
            Log.w(TAG, "高帧率失败且无可退让档位：$reason（能力表可用=${ability != null}）")
            return
        }
        Log.w(TAG, "设备状态→OPEN_FAILED_HIGH_FPS：$reason，fps 退到 $ceiling")
        val pick = RequestApplier.pickFps(normalRanges, ceiling, false) ?: return
        highSpeedActive = false
        chosenFps = pick
        writeBackFps(ceiling, pick.exact)
        // 档位被系统改掉了：把原因摆到界面上（用户要求"所有自动降档都要看得见"），
        // 否则他只看到帧率自己变了、以为是 bug。
        onFpsDowngraded(ceiling)
        structuralSignature = ""
        publishDevice(DeviceStatus.OPEN_SUCCEED)
        if (desiredPreview) ensureSession()
    }

    /**
     * 普通（非高速）帧率表：退档候选的唯一来源。
     *
     * 【2026-10-26 二修】兜底链原来是 `ability?.fpsRanges ?: state?.fpsRanges`——`ability == null`
     * 时会退到 [RequestApplier.State.fpsRanges]，而高速态下它**就是高速表**：`filter { hi <= 60 }`
     * 恒空，退档分支又变回"打一行日志就 return"的原坑（取景器假死）。所以这一层**只认能力表**：
     * ability 拿不到（open 失败窗口）就返回空表，让 [downgradeHighSpeed] 走"无可退让档位"的
     * 明确失败路径并留日志——宁可如实失败，不拿错误的表装普通表硬退。
     */
    private fun normalFpsRangesOf(): List<com.wotagei.cam.core.RangeI> =
        ability?.fpsRanges ?: emptyList()

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
            // 终态（2026-10-13）：不再排下一轮重开。device/preview 状态由 failDevice 已发布，
            // 预览停在失败态交 UI 遮罩 / 重进页面恢复——计数归零点已挪到 onConfigured 成功侧，
            // 这里的触顶在「configure 反复失败」的真机形态下真正可达
            Log.w(TAG, "自动重开次数用尽（多次重开失败），停在失败态等用户操作")
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
