package com.wotagei.cam.stance

import com.wotagei.cam.diag.Diag

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.media.ImageReader
import android.net.Uri
import android.util.Size
import android.view.Surface
import com.wotagei.cam.camera.AnalysisSessionDecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 站位功能集成中枢（M2c/M3）：持有分析管线、检测器、引擎、判定头、播报器，
 * 对 UI 只暴露 StateFlow 与少量动作。生命周期：
 * - **预览态专属**：录制窗口自动暂停（叠加+播报+推理全停，开关不重置，停录恢复）；
 *   切前置直接关闭并重置开关（D13）；离页停听、回场恢复；
 * - 引擎槽挂/摘：enable→setAnalysisTarget(surface, reader)（会话重建一次，勿反复）；
 *   disable→setAnalysisTarget(null)（同样一次）；录制窗口用 [suspendForRecording]/
 *   [resumeAfterRecording]（引擎槽不动，只停本管线回调，避免反复重建会话）；
 * - 模型组：首启异步从 assets 复制内置包 → activeDir（用户包 > 内置）→ 装配检测器/头/安全区；
 *   热换复用同一条分析管线（[StanceAnalysis.updateModel]），用户包坏文件自动回退内置（D20）；
 * - 持久化：直接 prefs 读写（站位参数是实时旋钮，不入 ParamsPersist 录制档案语义）。
 */
class StanceController private constructor(
    private val appContext: Context,
) {
    /** UI 状态（CameraScreen 弹窗/叠加消费）。 */
    data class UiState(
        val enabled: Boolean = false,
        val running: Boolean = false,           // 管线在跑（enabled 且非录制/前置窗口）
        val backendName: String = "",           // 当前加速层（含回退标记）
        val output: EngineOutput? = null,       // 最新判定输出（叠加/弹窗消费）
        /** B14：判定输出 → 预览内容区的映射几何（随输出/转屏/换挡刷新）。 */
        val geometry: StanceOverlayGeometry? = null,
        val mode: String = JudgementEngine.MODE_AUTO,
        val nManual: Int = 1,
        val beta: Float = StanceSpec.BETA_DEFAULT,
        val brighten: Boolean = false,
        val gpuMode: String = StanceDetector.MODE_AUTO,
        val lineIntervalMs: Long = StanceSpec.LINE_INTERVAL_MS,
        val pollIntervalMs: Long = StanceSpec.POLL_INTERVAL_MS,
        val versionText: String = "",           // 模型组/判定头/安全区版本文本
        val fallbackTip: String? = null,        // 降级/错误提示（弹一次）
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val modelLock = Any()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var modelGeneration = 0L
    @Volatile private var analysis: StanceAnalysis? = null
    @Volatile private var detector: StanceDetector? = null
    private var speaker: StanceSpeaker? = null
    private var attached = false
    private var prepared = false
    private var recording = false
    private var analysisSessionDecision = AnalysisSessionDecision.ABSENT

    /**
     * 模型组装配串行线程（2026-11-07）：首启 copy 13.8MB + 全量 sha256 + Interpreter 构造，
     * 旧实现发生在 CameraScreen 首帧组合里（主线程）——冷启被拖慢、低端机有 ANR 风险。
     * 单线程只管调度；跨分析 owner 挂起期间，由 packMutex 保证文件事务不与装配交错。
     */
    private val packMutex = Mutex()
    private var packJob: Job? = null
    private var packExecutor: ExecutorService = newPackExecutor()
    private var packScope = CoroutineScope(SupervisorJob() + packExecutor.asCoroutineDispatcher())

    /** 预览页是否在组合里（离页停听、回场恢复；相机与引擎槽保持不动，不反复重建会话）。 */
    private var onPage = true

    /** 用户开了开关但模型组/引擎尚未就绪：就绪点补挂一次。 */
    private var pendingAttach = false

    /**
     * 挂槽意图代（Y08）：分配是异步的，期间任何摘槽/销毁/重置都 +1；
     * 迟到的分配结果不得越过新意图去挂引擎槽。
     */
    private var attachIntent = 0L

    /** 已向分析 owner 投递、尚未回到 Main 的分配请求（避免重复决策叠加多次 startAsync）。 */
    private var attachPending = false

    private var attachedReaderKey: String? = null

    /** 已挂 reader 的分配尺寸（B14 几何：分析帧的实际像素尺寸，不是推算值）。 */
    @Volatile
    private var analysisStreamSize: Size? = null

    /**
     * 预览流实际几何（B14）：由 CameraScreen 注入——DIRECT 取 SurfaceTexture 缓冲尺寸，
     * GL 取相机帧尺寸；附转正/镜像与实测 `SCALER_CROP_REGION` 宽高比。
     */
    var previewGeometryProvider: (() -> PreviewStreamGeometry?)? = null

    /** 前置镜头标记（D13：前置不提供站位提示；录制窗口切镜头被 UI 锁住，留 pendingDetach 兜底）。 */
    @Volatile
    private var frontLens = false
    private var pendingDetach = false

    /**
     * B16：设置页渲染模式是否为 GPU。渲染不是 GPU 时推理加速也不可用（弹窗现有文案口径），
     * 装配必须与 UI 同口径——否则 DIRECT 下界面显示「关」而后端实际是 GPU。
     * 偏好仍保留在 prefs；渲染切回 GPU 后按 `gpuMode` 重新装配。
     */
    @Volatile
    private var renderGpuAllowed = true

    /** 预取 characteristics（挂流前选 YUV 尺寸；引擎侧无 characteristics 缓存，由 UI 传入）。 */
    var characteristicsProvider: (() -> CameraCharacteristics?)? = null

    /**
     * 实际生效镜头的标识（Y09/B14）：分析 reader 的复用键 = 该标识 + YUV 尺寸。
     * 由 UI 喂 `ui.currentSlot?.key`（**实际槽位**，不是请求档位——与 Y70 同一条真值）。
     */
    var lensKeyProvider: (() -> String?)? = null

    /** 目标画幅比（传感器坐标，宽/高）：分析流按它选同比例 YUV 档，叠框才和预览对齐。 */
    var targetAspectProvider: (() -> Float?)? = null

    /**
     * 镜头切换未决（Y70）：从请求切镜到新槽位落定/新会话决策到达之前，旧会话的帧带着旧 FOV，
     * 不能喂给已按新画幅构建的判定与叠框 ⇒ 先停分析；落定后由 [maybeAttachAfterGate] 恢复。
     */
    @Volatile
    private var lensSettling = false

    /** 分析帧转正角（sensorOrientation − displayRotation，由 UI 现算喂入）。 */
    @Volatile
    private var rotation = 0

    // ---- 持久化键（站位参数=实时旋钮，prefs 即改即存；不入 ParamsPersist 档案语义） ----

    fun loadPrefs(prefs: android.content.SharedPreferences) {
        _state.update {
            it.copy(
                enabled = prefs.getBoolean(Prefs.ON, false),
                mode = prefs.getString(Prefs.MODE, null) ?: JudgementEngine.MODE_AUTO,
                nManual = prefs.getInt(Prefs.N, 1).coerceIn(1, 5),
                beta = prefs.getFloat(Prefs.BETA, StanceSpec.BETA_DEFAULT)
                    .coerceIn(StanceSpec.BETA_MIN, StanceSpec.BETA_MAX),
                brighten = prefs.getBoolean(Prefs.BRIGHTEN, false),
                lineIntervalMs = prefs.getLong(Prefs.LINE_MS, StanceSpec.LINE_INTERVAL_MS)
                    .coerceIn(StanceSpec.INTERVAL_MIN_MS, StanceSpec.INTERVAL_MAX_MS),
                pollIntervalMs = prefs.getLong(Prefs.POLL_MS, StanceSpec.POLL_INTERVAL_MS)
                    .coerceIn(StanceSpec.INTERVAL_MIN_MS, StanceSpec.INTERVAL_MAX_MS),
                gpuMode = prefs.getString(Prefs.GPU_MODE, null)
                    ?: (if (prefs.getBoolean(Prefs.GPU_LEGACY, false)) StanceDetector.MODE_FORCE else StanceDetector.MODE_AUTO),
            )
        }
    }

    object Prefs {
        const val ON = "stance_on"
        const val MODE = "stance_mode"
        const val N = "stance_n"
        const val BETA = "stance_beta"
        const val BRIGHTEN = "stance_brighten"
        const val LINE_MS = "stance_line_interval_ms"
        const val POLL_MS = "stance_poll_interval_ms"

        /** GPU 模式三态（r03）：auto/force/off。旧布尔键只读迁移一次，不再写。 */
        const val GPU_MODE = "stance_gpu_mode"
        const val GPU_LEGACY = "stance_gpu"
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("wota_settings", Context.MODE_PRIVATE)

    // ---- 生命周期 ----

    /** 进入预览态调（首次做模型组装配）。幂等。 */
    fun prepare(context: Context) {
        if (prepared) return
        prepared = true
        loadPrefs(prefs(context))
        speaker = StanceSpeaker(context)
        reloadAsync(context.applicationContext)
    }

    /** 装配线程（daemon：进程退出不被它拖住；Activity 重建后在 [release] 里换新）。 */
    private fun newPackExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "WotaStancePack").apply { isDaemon = true }
        }

    /**
     * 异步装配当前激活模型组（主线程不碰 13.8MB 的 copy/sha256/Interpreter）。
     * 装配完成回主线程：开关仍开就补挂（含模型组未就绪期间用户已开的情况）。
     */
    private fun reloadAsync(app: Context) {
        packJob?.cancel()
        packJob = packScope.launch {
            try {
                val request = currentCoroutineContext()
                packMutex.withLock {
                    // native 不可抢停：等 owner 真正退出才解锁；仍用原请求验票，取消后不交接。
                    withContext(NonCancellable) {
                        request.ensureActive()
                        reloadActive(app, request)
                    }
                }
                withContext(Dispatchers.Main) {
                    if (_state.value.enabled) maybeAttachAfterGate()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Diag.w(TAG, "模型组装配失败：${e.message}")
                    _state.update { it.copy(fallbackTip = "模型组装配失败：${e.message}") }
                }
            }
        }
    }

    private fun resetPackScope() {
        packJob?.cancel()
        packJob = null
        packScope.cancel()
        // 保留已接受的 continuation/解锁任务；强制清队列会让跨 scope 的 Mutex 永久占用。
        packExecutor.shutdown()
        packExecutor = newPackExecutor()
        packScope = CoroutineScope(SupervisorJob() + packExecutor.asCoroutineDispatcher())
    }

    /**
     * 装配当前激活模型组（用户包优先 > 内置），**用户包加载失败自动回滚上一套/内置**（D20/R16/Y76）。
     * 装配复用既有 [StanceAnalysis] 管线（[StanceAnalysis.updateModel]），不重建线程/reader。
     */
    private suspend fun reloadActive(context: Context, request: CoroutineContext) {
        val (dir, source) = StanceModelPack.activeDir(context)
        val meta = StanceModelPack.validateDir(dir) ?: throw IllegalStateException("模型组校验失败")
        try {
            val applied = applyPack(dir, meta, source, request)
            // R16：新包只有在检测器首次初始化成功后才确认；导入期的 previous 到这里才允许删除。
            // GPU 成功不能证明承诺的 CPU 兜底可装配——否则删掉上一套后一旦 GPU 运行期失败，
            // 用户会永久失去可回滚模型。只在确有 previous 待删时探针一次，正常重复装载零成本。
            if (source == "user") {
                if (StanceModelPack.hasPrevious(context.filesDir) && !applied.verifyCpuFallback()) {
                    throw IllegalStateException("CPU 兜底模型不可装配（GPU 成功不能替代）；已保留上一套模型组")
                }
                StanceModelPack.confirmActivePack(context.filesDir)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (source != "user") throw e
            Diag.w(TAG, "用户模型组不可用，回滚：${e.message}")
            val outcome = StanceModelPack.rollbackActivePack(context.filesDir)
            Diag.i(TAG, "回滚结果 ${outcome.status}：${outcome.message}")
            val tip = "新模型组不可用（${e.message}），${outcome.message}"
            if (outcome.status == StanceModelPack.RollbackStatus.RESTORED_PREVIOUS) {
                // 上一套此前确认可用：再装配一次，成功即恢复用户模型组（不回退内置）。
                val restored = StanceModelPack.validateDir(StanceModelPack.userDir(context))
                if (restored != null) {
                    try {
                        applyPack(StanceModelPack.userDir(context), restored, "user（回滚）", request)
                        StanceModelPack.confirmActivePack(context.filesDir)
                        _state.update { it.copy(fallbackTip = tip) }
                        return
                    } catch (c: CancellationException) {
                        throw c
                    } catch (retry: Exception) {
                        Diag.w(TAG, "恢复的上一套模型组仍不可加载：${retry.message}")
                    }
                }
            }
            val builtin = StanceModelPack.builtinDir(context)
            var bMeta = StanceModelPack.validateDir(builtin)
            if (bMeta == null) {
                StanceModelPack.installBuiltin(context)
                bMeta = StanceModelPack.validateDir(builtin)
            }
            if (bMeta == null) throw IllegalStateException("$tip；内置模型组也不可用")
            try {
                applyPack(builtin, bMeta, "builtin", request)
            } catch (c: CancellationException) {
                throw c
            } catch (b: Exception) {
                throw IllegalStateException("$tip；内置模型组也不可用（${b.message}）")
            }
            _state.update { it.copy(fallbackTip = "$tip，已回退内置模型组") }
        }
    }

    private suspend fun applyPack(
        dir: java.io.File,
        meta: Map<String, Any?>,
        source: String,
        request: CoroutineContext,
    ): StanceDetector {
        request.ensureActive()
        val epoch = synchronized(modelLock) { modelGeneration }
        dumpGpuEnvironment()
        val head = StanceHead.fromJson(java.io.File(dir, "head.json").readText())
        val marginsText = java.io.File(dir, "margins.json").readText()
        // Y68：判定语义指纹包含 CPU 主检测模型与实际 GPU 检测资产；同包换后端指纹不变，保留判定状态。
        val modelFingerprint = StanceModelPack.semanticFingerprint(meta)
        val a = synchronized(modelLock) {
            request.ensureActive()
            if (epoch != modelGeneration) throw CancellationException("模型组装配已作废")
            analysis ?: StanceAnalysis { out -> onOutput(out) }.also {
                it.rotationDegrees = rotation
                it.brighten = _state.value.brighten
                analysis = it
            }
        }
        val headMeta = meta["head_meta"] as? Map<*, *>
        val version = buildString {
            append("模型组 ${meta["created_at"] ?: "?"}（$source）")
            append("｜判定头 ${headMeta?.get("trained_at") ?: "?"} val_acc=${headMeta?.get("val_acc") ?: "?"}")
            append("｜安全区 ${meta["margins_sessions"]?.toString()?.take(36)}…")
        }
        // native 创建/首验/热换/关闭在同一 owner；不可取消的 native 返回后再次验票再交接。
        var applied: StanceDetector? = null
        a.onAnalysis {
            val next = StanceDetector()
            try {
                request.ensureActive()
                val ok = next.load(
                    java.io.File(dir, "yolo11n_int8.tflite"),
                    java.io.File(dir, "yolo11n_fp32.tflite").takeIf { it.isFile }
                        ?: java.io.File(dir, "yolo11n_fp16.tflite"),
                    gpuMode = effectiveGpuMode(),
                    // Y66：坐标单位由清单显式声明；旧包缺字段时传 null，检测器保留值域自适应。
                    coordUnit = StanceModelPack.coordinateUnit(meta),
                )
                if (!ok) throw IllegalStateException("检测模型不可加载（张量规格/文件异常）")
                synchronized(modelLock) {
                    request.ensureActive()
                    if (epoch != modelGeneration || analysis !== a) throw CancellationException("模型组装配已作废")
                    next.onBackendChanged = { backend, reason ->
                        synchronized(modelLock) {
                            if (epoch == modelGeneration && detector === next) {
                                val available = next.isLoaded()
                                if (!available) {
                                    a.pauseListening()
                                    // Speaker 的队列归 Main；迟到通知还须验模型身份，不能停新模型的播报。
                                    mainHandler.post {
                                        synchronized(modelLock) {
                                            if (epoch == modelGeneration && detector === next && !next.isLoaded()) speaker?.pause()
                                        }
                                    }
                                }
                                _state.update {
                                    it.copy(
                                        backendName = backend,
                                        fallbackTip = reason,
                                        running = available && it.running,
                                        output = if (available) it.output else null,
                                    )
                                }
                            }
                        }
                    }
                    a.updateModel(next, head, marginsText, modelFingerprint)
                    detector = next
                    applied = next
                    _state.update {
                        it.copy(
                            backendName = next.backendName,
                            versionText = version,
                            fallbackTip = next.gpuFallbackReason ?: it.fallbackTip,
                        )
                    }
                    syncEngineParams()
                }
            } catch (error: Throwable) {
                next.close()
                throw error
            }
        }
        request.ensureActive()
        return requireNotNull(applied) { "模型组装配未完成" }
    }

    /** 分析线程回调（Main 线程）：状态 + 播报 + 引擎参数同步（同步本身走分析线程，见下）。 */
    private fun onOutput(out: EngineOutput) {
        if (!_state.value.enabled || recording || !onPage || frontLens || !_state.value.running) return
        // B14：几何随分析输出刷新，不挂到预览每帧。
        val geometry = buildOverlayGeometry()
        _state.update { it.copy(output = out, geometry = geometry) }
        // Y74：轮末由引擎显式标记，尾词只跟在最后一句后面入队（不再猜固定静默窗）。
        speaker?.let { sp ->
            out.lines.forEachIndexed { i, line ->
                sp.speak(line, roundEnded = out.roundEnded && i == out.lines.lastIndex)
            }
            // Y74/Y75：即使末句无词位，也在队列真正播完后归还焦点。
            if (out.roundEnded) sp.endRound()
        }
        syncEngineParams()
    }

    /**
     * B14：用**实测/枚举**输入构建叠加几何——分析帧实际尺寸（分配时记录）、预览流实际尺寸
     * （宿主注入）、转正/镜像、以及 `SCALER_CROP_REGION` 实测宽高比（缺失时显式回退枚举
     * active-array，再缺失则标注 ANALYSIS_FALLBACK）。任何一项不成立就返回 null，不拿推算值冒充实测。
     */
    private fun buildOverlayGeometry(): StanceOverlayGeometry? {
        val analysisSize = analysisStreamSize ?: return null
        val preview = previewGeometryProvider?.invoke() ?: return null
        return StanceOverlayGeometry.create(
            analysisWidth = analysisSize.width,
            analysisHeight = analysisSize.height,
            analysisRotation = rotation,
            preview = preview,
        )
    }

    private fun syncEngineParams() {
        val a = analysis ?: return
        val s = _state.value
        // 必须走分析线程：observe() 与参数写入并发会撕裂判定状态（见 updateEngineConfig）
        a.updateEngineConfig(s.beta, s.mode, s.nManual, s.brighten, s.lineIntervalMs, s.pollIntervalMs)
    }

    /** 开/关站位提示（弹窗开关）。开=挂 analysis 槽+resume；关=摘槽（各一次会话重建）。 */
    fun setEnabled(context: Context, on: Boolean) {
        StancePerfProbe.note("setEnabled on=$on rec=$recording front=$frontLens now=${_state.value.enabled}")
        if (recording) {
            // 录制窗口两向都拒绝：挂/摘槽都会重建相机会话（MediaRecorder 面在会话里）——
            // 状态保持录制前的样子（本功能录制时暂停，不是关闭），停录后自动恢复。
            _state.update { it.copy(fallbackTip = "录制中不可调整站位提示——停录后再试") }
            return
        }
        if (on && frontLens) {
            // D13：前置自拍场景没有无人值守痛点，方向语义也只对后置成立
            _state.update {
                it.copy(
                    enabled = false,
                    running = false,
                    fallbackTip = "前置镜头不提供站位提示——切回后置后再开",
                )
            }
            return
        }
        _state.update { it.copy(enabled = on) }
        prefs(context).edit().putBoolean(Prefs.ON, on).apply()
        if (on) attachAnalysis() else detachAnalysis()
    }

    private fun attachAnalysis() {
        val a = analysis
        if (a == null || detector?.isLoaded() != true) {
            // 未装配或后端故障时只保留意图；会话接纳不能让不可用模型开始判定。
            pendingAttach = _state.value.enabled && onPage && !recording
            StancePerfProbe.note(
                "attach pending analysis=${a != null} loaded=${detector?.isLoaded()} page=$onPage rec=$recording"
            )
            return
        }
        if (attached || attachPending || recording || !onPage || frontLens || lensSettling) {
            StancePerfProbe.note(
                "attach skip attached=$attached pending=$attachPending rec=$recording " +
                    "page=$onPage front=$frontLens settling=$lensSettling"
            )
            return
        }
        val size = analysisSizeForCurrentTarget()
        // Y08：尺寸只从枚举快照算；reader 的创建/复用/回收全部投给分析 owner 线程。
        // Main 上不出现 CameraManager 查询，也不出现 ImageReader 准备。
        attachIntent++
        val intent = attachIntent
        val key = readerKeyFor(size)
        attachPending = true
        StancePerfProbe.note("attach start size=$size key=$key")
        a.startAsync(size, key) { surface, reader ->
            onAnalysisSurfaceReady(intent, a, size, key, surface, reader)
        }
    }

    /**
     * 分析 owner 完成分配后的 Main 回调（Y08）。迟到的结果不得越过摘槽/销毁/新门控：
     * 意图代、功能开关、录制/离页/前置/切镜未决、已挂状态全部复验后才碰引擎槽。
     */
    private fun onAnalysisSurfaceReady(
        intent: Long,
        source: StanceAnalysis,
        size: Size,
        key: String,
        surface: Surface?,
        reader: ImageReader?,
    ) {
        if (intent != attachIntent) {
            StancePerfProbe.note("surface stale intent=$intent cur=$attachIntent")
            return
        }
        attachPending = false
        if (analysis !== source || attached || recording || !onPage || frontLens || lensSettling || !_state.value.enabled) {
            StancePerfProbe.note(
                "surface rejected src=${analysis === source} attached=$attached rec=$recording " +
                    "page=$onPage front=$frontLens settling=$lensSettling enabled=${_state.value.enabled}"
            )
            return
        }
        if (detector?.isLoaded() != true) {
            pendingAttach = true
            StancePerfProbe.note("surface pending: detector not loaded")
            return
        }
        if (key != readerKeyFor(analysisSizeForCurrentTarget())) {
            StancePerfProbe.note("surface key changed, re-attach")
            attachAnalysis()
            return
        }
        if (surface == null || reader == null) {
            pendingAttach = true
            _state.update { it.copy(fallbackTip = ALLOCATION_TIP) }
            StancePerfProbe.note("surface allocation failed (null surface/reader)")
            return
        }
        syncEngineParams()
        val hooked = attachHook?.invoke(surface, reader) ?: false
        StancePerfProbe.note("surface hook hooked=$hooked size=$size key=$key")
        attached = hooked
        attachedReaderKey = key.takeIf { hooked }
        analysisStreamSize = size.takeIf { hooked }
        pendingAttach = !hooked
        // 新槽还未收到自己的会话回执；不能拿上一张分析面的 INCLUDED 放行。
        speaker?.pause()
        source.pauseListening()
        _state.update {
            it.copy(running = false, fallbackTip = it.fallbackTip.takeUnless { tip -> hooked && tip == ALLOCATION_TIP })
        }
    }

    /**
     * 分析 reader 的配置键（Y09/B14）：镜头 + 画幅（YUV 尺寸）任一变化都必须重开一张面。
     * 镜头标识取「实际槽位」（[lensKeyProvider]，与 D13 的 frontLens 同一条真值）。
     */
    private fun readerKeyFor(size: Size): String =
        "${lensKeyProvider?.invoke() ?: "lens?"}|${size.width}x${size.height}"

    private fun analysisSizeForCurrentTarget(): Size = try {
        characteristicsProvider?.invoke()?.let { StanceAnalysis.pickYuvSize(it, targetAspectProvider?.invoke()) }
            ?: StanceAnalysis.DEFAULT_SIZE
    } catch (_: Exception) {
        StanceAnalysis.DEFAULT_SIZE
    }

    /** 镜头或画幅换挡时，只在需要换 YUV 面时摘槽；录制窗口内延迟到停录。 */
    fun onAnalysisTargetChanged() {
        if (!_state.value.enabled || recording || !onPage || frontLens || lensSettling) return
        if (attached && attachedReaderKey != readerKeyFor(analysisSizeForCurrentTarget())) detachAnalysis()
        maybeAttachAfterGate()
    }

    /** 接纳的是分析槽意图，不是强行降帧率；高速拒绝期间保留同一 reader，普通档再恢复。 */
    fun onAnalysisSessionDecision(decision: AnalysisSessionDecision) {
        analysisSessionDecision = decision
        if (decision == AnalysisSessionDecision.INCLUDED) {
            _state.update {
                if (it.fallbackTip == HIGH_SPEED_TIP || it.fallbackTip == COMBINATION_TIP) {
                    it.copy(fallbackTip = null)
                } else it
            }
            maybeAttachAfterGate()
            return
        }
        analysis?.pauseListening()
        speaker?.pause()
        // 高速保留帧率，组合被拒则等下一次镜头/分析协商（Y69）。两种降级各有提示。
        _state.update {
            it.copy(
                running = false,
                output = null,
                geometry = null,
                fallbackTip = when {
                    !it.enabled -> it.fallbackTip
                    decision == AnalysisSessionDecision.BLOCKED_HIGH_SPEED -> HIGH_SPEED_TIP
                    decision == AnalysisSessionDecision.BLOCKED_COMBINATION -> COMBINATION_TIP
                    else -> it.fallbackTip
                },
            )
        }
    }

    /** 方向变化（转屏/换镜头）：分析帧转正角随之更新，已挂的管线立即生效。 */
    fun setRotation(degrees: Int) {
        rotation = ((degrees % 360) + 360) % 360
        analysis?.rotationDegrees = rotation
        // B14：转屏会改变分析帧的转正角，叠框几何必须立刻跟着换；不必等下一帧输出。
        // 只有**当前已挂会话**才发布几何：摘槽/离页/切镜未决期间的转屏不得把旧 reader 的
        // 画幅映射留在 UiState 里（重挂后由第一帧输出重新构建）。
        val geometry = if (attached) buildOverlayGeometry() else null
        _state.update { if (it.geometry != geometry) it.copy(geometry = geometry) else it }
    }

    /** M2c 接缝：宿主（CameraScreen）注入——挂引擎 analysis 槽的真正实现。
     * 返回 false = 引擎尚未就绪（调用方保持未挂状态，等就绪点补挂）。 */
    var attachHook: ((Surface, ImageReader?) -> Boolean)? = null

    /** detach 时把 null 交回引擎（摘槽）。 */
    var detachHook: (() -> Unit)? = null

    private fun detachAnalysis() {
        attachIntent++   // Y08：在途分配即作废，迟到的面不得重新挂回会话
        attachPending = false
        analysisStreamSize = null
        attachedReaderKey = null
        speaker?.pause()
        analysis?.pauseListening()
        // Hook 可能已在 Controller 暂存槽位（即使 engine 尚未建立），摘槽仍要清掉这份借用。
        attached = false
        pendingAttach = false
        analysisSessionDecision = AnalysisSessionDecision.ABSENT
        detachHook?.invoke()
        _state.update { it.copy(running = false, output = null, geometry = null) }
    }

    /**
     * 录制窗口开始（§5.1：叠加+播报+推理全停）。**引擎槽不动**——只停本管线回调，
     * 避免在 MediaRecorder 面已在会话里时再重建一次会话（分析流留槽由录制会话承载）。
     */
    fun suspendForRecording() {
        recording = true
        speaker?.pause()
        analysis?.pauseListening()
        _state.update { it.copy(running = false, output = null, geometry = null) }
    }

    /** 停录恢复（开关状态/持久化都不动——下一次开录仍自动暂停，无需用户重开）。 */
    fun resumeAfterRecording() {
        recording = false
        if (analysis == null || analysisSessionDecision == AnalysisSessionDecision.INCLUDED) speaker?.resume()
        if (pendingDetach) {
            // 录制窗口内切了前置：当时摘槽会打断录制，停录后补摘
            pendingDetach = false
            detachAnalysis()
            return
        }
        onAnalysisTargetChanged()
    }

    /**
     * 录制态同步（CameraScreen 观察 recStatus 喂入）。真值来源只有这一处：
     * 进入录制 → suspend；退出录制 → 若开关仍开则恢复监听/补挂。
     */
    fun setRecordingActive(active: Boolean) {
        if (active) suspendForRecording() else resumeAfterRecording()
    }

    /** 预览页进入组合：开关仍开则恢复监听（离页期间只是暂停，不摘槽）。 */
    fun onPageEnter() {
        onPage = true
        StancePerfProbe.note("pageEnter enabled=${_state.value.enabled} analysis=${analysis != null} loaded=${detector?.isLoaded()}")
        if (!_state.value.enabled) return
        val a = analysis
        // 引擎可能在上次离页时关闭了分析 reader（unbind/重建）——面已死就按"未挂"重来
        if (a != null && attached && !a.isSurfaceLive()) detachAnalysis()
        onAnalysisTargetChanged()
    }

    /**
     * 相机 Controller 销毁（非配置变更/系统回收）但站位功能意图仍在：不撤销开关，
     * 只把 reader 的关闭交给分析 owner 排队；下次进页会重新 start 一张有效面。
     */
    fun onCameraControllerCleared() {
        attachIntent++   // Y08：销毁后迟到的分配结果不得再挂槽
        attachPending = false
        attached = false
        pendingAttach = false
        analysisStreamSize = null
        analysis?.pauseListening()
        speaker?.pause()
        analysis?.releaseReader()
        _state.update { it.copy(running = false, output = null, geometry = null) }
    }

    /** 预览页离开组合：停听 + 清叠加（相机可被别的页面保活，但站位播报不该在设置页响）。 */
    fun onPageExit() {
        onPage = false
        speaker?.pause()
        analysis?.pauseListening()
        _state.update { it.copy(running = false, output = null, geometry = null) }
    }

    /** 门控放开（非录制 + 在页 + 后置 + 镜头已落定）时：已挂→恢复监听；未挂→补挂（含装配未完成的 pending）。 */
    private fun maybeAttachAfterGate() {
        if (!_state.value.enabled || recording || !onPage || frontLens || lensSettling) {
            StancePerfProbe.note(
                "gate blocked enabled=${_state.value.enabled} rec=$recording page=$onPage " +
                    "front=$frontLens settling=$lensSettling"
            )
            return
        }
        val a = analysis
        if (a == null) {
            pendingAttach = true
            return
        }
        if (detector?.isLoaded() != true) {
            pendingAttach = true
            a.pauseListening()
            speaker?.pause()
            _state.update { it.copy(running = false, output = null, geometry = null) }
            return
        }
        if (attached) {
            if (analysisSessionDecision != AnalysisSessionDecision.INCLUDED) return
            speaker?.resume()
            a.resumeListening()
            _state.update { it.copy(running = true) }
        } else {
            attachAnalysis()
        }
    }

    /**
     * 镜头切换（D13）：前置 = 直接关闭并重置开关，切回后置需手动再开。
     * 录制窗口内不摘槽（会打断录制）——记 pendingDetach，停录后补摘。
     */
    fun setFrontLens(front: Boolean) {
        frontLens = front
        if (front && _state.value.enabled) disableForFrontLens(appContext)
    }

    /**
     * 镜头切换未决（Y70）：切镜窗口内先停分析——旧会话的帧带着旧 FOV，不能喂给按新画幅构建的
     * 判定与叠框。落定（实际槽位已发布 / 新会话决策到达）后由 [maybeAttachAfterGate] 恢复。
     */
    fun setLensSettling(settling: Boolean) {
        if (lensSettling == settling) return
        lensSettling = settling
        if (settling) {
            speaker?.pause()
            analysis?.pauseListening()
            _state.update { it.copy(running = false, output = null, geometry = null) }
        } else {
            maybeAttachAfterGate()
        }
    }

    /** 切前置（D13：直接关闭开关重置；录制窗口只暂停，待停录补摘）。 */
    fun disableForFrontLens(context: Context) {
        _state.update { it.copy(enabled = false, running = false, output = null, geometry = null) }
        prefs(context).edit().putBoolean(Prefs.ON, false).apply()
        speaker?.pause()
        analysis?.pauseListening()
        pendingAttach = false
        if (recording) {
            pendingDetach = true
        } else {
            detachAnalysis()
        }
    }

    /** 弹窗动作：β/模式/人数/提亮/加速开关（全部即改即存 + 热更新）。 */
    fun setBeta(context: Context, v: Float) {
        _state.update { it.copy(beta = v) }
        prefs(context).edit().putFloat(Prefs.BETA, v).apply()
        syncEngineParams()
    }

    fun setMode(context: Context, mode: String, n: Int) {
        _state.update { it.copy(mode = mode, nManual = n.coerceIn(1, 5)) }
        prefs(context).edit().putString(Prefs.MODE, mode).putInt(Prefs.N, n.coerceIn(1, 5)).apply()
        syncEngineParams()
    }

    fun setBrighten(context: Context, on: Boolean) {
        _state.update { it.copy(brighten = on) }
        prefs(context).edit().putBoolean(Prefs.BRIGHTEN, on).apply()
        syncEngineParams()
    }

    /** 轮询内间隔（每号位播报间隔，用户 r06 口径）。 */
    fun setLineInterval(context: Context, ms: Long) {
        val v = ms.coerceIn(StanceSpec.INTERVAL_MIN_MS, StanceSpec.INTERVAL_MAX_MS)
        _state.update { it.copy(lineIntervalMs = v) }
        prefs(context).edit().putLong(Prefs.LINE_MS, v).apply()
        syncEngineParams()
    }

    /** 轮询间间隔（两轮之间的静默，用户 r06 口径）。 */
    fun setPollInterval(context: Context, ms: Long) {
        val v = ms.coerceIn(StanceSpec.INTERVAL_MIN_MS, StanceSpec.INTERVAL_MAX_MS)
        _state.update { it.copy(pollIntervalMs = v) }
        prefs(context).edit().putLong(Prefs.POLL_MS, v).apply()
        syncEngineParams()
    }

    /**
     * GPU 环境 dump（r03）：SOC/Vulkan 硬事实进诊断日志——卓易通转发图形栈下
     * "白名单不通过"到底缺什么，一次日志看清（GL 字符串需 GL 上下文，另由
     * Detector 的 delegate 判定结果补充）。
     */
    private fun dumpGpuEnvironment() {
        try {
            // SOC 字段自 API 31 才存在；旧系统只读 HARDWARE，不能靠 catch Exception 接链接错误。
            val soc = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                listOf(
                    "SOC_MODEL=${android.os.Build.SOC_MODEL.takeIf { it.isNotEmpty() } ?: "?"}",
                    "SOC_MFR=${android.os.Build.SOC_MANUFACTURER.takeIf { it.isNotEmpty() } ?: "?"}",
                    "HW=${android.os.Build.HARDWARE}",
                ).joinToString(" ")
            } else {
                "HW=${android.os.Build.HARDWARE}"
            }
            val vk = "vulkan=${appContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)}" +
                " vulkanCompute=${appContext.packageManager.hasSystemFeature("android.hardware.vulkan.compute")}"
            Diag.i(TAG, "GPU 环境 $soc $vk")
        } catch (_: Exception) {
        }
    }

    /** GPU/NPU 加速三态（r03：auto/force/off；enabled 由 UI 联动设置页"使用GPU"=KEY_DEFAULT_RENDER==GPU）。 */
    fun setGpuMode(context: Context, mode: String) {
        _state.update { it.copy(gpuMode = mode) }
        prefs(context).edit().putString(Prefs.GPU_MODE, mode).apply()
        // 换 backend = 换检测器；装配走后台线程（Interpreter + GpuDelegate 构造可达数百 ms），
        // 复用同一条分析管线（旧检测器在分析线程内关闭）
        reloadAsync(context.applicationContext)
    }

    /**
     * 渲染模式联动的推理加速准入（B16）：设置页渲染不是 GPU 时推理加速也不可用，
     * 弹窗与装配必须同口径——切回 GPU 后按已存偏好重新装配。未装配时只记标记，
     * 避免首启重复装配。
     */
    fun setRenderGpuAllowed(allowed: Boolean) {
        if (renderGpuAllowed == allowed) return
        renderGpuAllowed = allowed
        if (prepared) reloadAsync(appContext)
    }

    /** 装配实际使用的 GPU 模式：渲染模式不允许时一律降级为 CPU。 */
    private fun effectiveGpuMode(): String =
        if (renderGpuAllowed) _state.value.gpuMode else StanceDetector.MODE_OFF

    /** 导入先验包，成功才替换装配请求；失败不能取消冷启/切 GPU 尚未完成的装配。 */
    private fun importAsync(app: Context, action: () -> Unit) {
        packScope.launch {
            try {
                packMutex.withLock { action() }
                withContext(Dispatchers.Main) { reloadAsync(app) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    // Y76：按真实回滚状态给提示，不能把「待恢复」说成「未变」。
                    val status = (e as? StanceModelPack.ImportException)?.rollback
                    val tip = if (status == StanceModelPack.RollbackStatus.UNTOUCHED || status == null) {
                        "导入失败：${e.message}（原模型组未变）"
                    } else {
                        "导入失败：${e.message}"
                    }
                    Diag.w(TAG, tip)
                    _state.update { it.copy(fallbackTip = tip) }
                }
            }
        }
    }

    fun importPack(context: Context, uri: Uri) {
        val app = context.applicationContext
        importAsync(app) { StanceModelPack.importZip(app, uri) }
    }

    /** 外部目录仍须用户明确点击；和 SAF 走同一个导入事务。 */
    fun importFromExternalDir(context: Context) {
        val app = context.applicationContext
        importAsync(app) { StanceModelPack.importLatestFromExternal(app) }
    }

    /** 退出预览页/窗口销毁释放（Activity finish；配置变更不调——与 CameraController 同级）。 */
    fun release() {
        attachIntent++   // Y08：释放后在途分配不得再回调挂槽
        attachPending = false
        val oldAnalysis = synchronized(modelLock) {
            modelGeneration++
            val old = analysis
            analysis = null
            detector = null
            prepared = false
            analysisStreamSize = null
            _state.update { it.copy(running = false, output = null, geometry = null, backendName = "") }
            old
        }
        detachHook?.invoke()
        resetPackScope()
        oldAnalysis?.release()
        speaker?.release()
        speaker = null
        attached = false
        pendingAttach = false
        pendingDetach = false
        frontLens = false
        recording = false
        analysisSessionDecision = AnalysisSessionDecision.ABSENT
        onPage = true
    }

    companion object {
        private const val TAG = "StanceController"
        private const val ALLOCATION_TIP = "无法准备站位分析画面，可重新开启站位提示再试"
        private const val HIGH_SPEED_TIP = "当前高速会话不支持站位分析，已暂停分析与播报；保留所选帧率，回普通档自动恢复"

        /** Y69：设备拒绝了「预览/编码 + 分析 YUV」的组合——预览与录制不受影响，只是分析被暂停。 */
        private const val COMBINATION_TIP = "当前画幅下设备不接受分析流与预览同时输出，已暂停站位分析与播报；换镜头或重新开启分析可再试"
        @Volatile
        private var instance: StanceController? = null

        /** 页面级单例（预览页生命周期=窗口生命周期，与 CameraController 同级）。 */
        fun get(context: Context): StanceController =
            instance ?: synchronized(this) {
                instance ?: StanceController(context.applicationContext).also { instance = it }
            }
    }
}
