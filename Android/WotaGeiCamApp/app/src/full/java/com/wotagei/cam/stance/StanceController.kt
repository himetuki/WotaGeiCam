package com.wotagei.cam.stance

import com.wotagei.cam.diag.Diag

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.media.ImageReader
import android.net.Uri
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        val mode: String = JudgementEngine.MODE_AUTO,
        val nManual: Int = 1,
        val beta: Float = StanceSpec.BETA_DEFAULT,
        val brighten: Boolean = false,
        val gpuMode: String = StanceDetector.MODE_AUTO,
        val versionText: String = "",           // 模型组/判定头/安全区版本文本
        val fallbackTip: String? = null,        // 降级/错误提示（弹一次）
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    @Volatile private var analysis: StanceAnalysis? = null
    @Volatile private var detector: StanceDetector? = null
    private var speaker: StanceSpeaker? = null
    private var attached = false
    private var prepared = false
    private var recording = false

    /**
     * 模型组装配串行线程（2026-11-07）：首启 copy 13.8MB + 全量 sha256 + Interpreter 构造，
     * 旧实现发生在 CameraScreen 首帧组合里（主线程）——冷启被拖慢、低端机有 ANR 风险。
     * 单线程保证"导入/切 GPU/首启"多次装配不并发。
     */
    private var packJob: Job? = null
    private var packExecutor: ExecutorService = newPackExecutor()
    private var packScope = CoroutineScope(SupervisorJob() + packExecutor.asCoroutineDispatcher())

    /** 预览页是否在组合里（离页停听、回场恢复；相机与引擎槽保持不动，不反复重建会话）。 */
    private var onPage = true

    /** 用户开了开关但模型组/引擎尚未就绪：就绪点补挂一次。 */
    private var pendingAttach = false

    /** 前置镜头标记（D13：前置不提供站位提示；录制窗口切镜头被 UI 锁住，留 pendingDetach 兜底）。 */
    @Volatile
    private var frontLens = false
    private var pendingDetach = false

    /** 预取 characteristics（挂流前选 YUV 尺寸；引擎侧无 characteristics 缓存，由 UI 传入）。 */
    var characteristicsProvider: (() -> CameraCharacteristics?)? = null

    /** 目标画幅比（传感器坐标，宽/高）：分析流按它选同比例 YUV 档，叠框才和预览对齐。 */
    var targetAspectProvider: (() -> Float?)? = null

    /** 分析帧转正角（sensorOrientation − displayRotation，由 UI 现算喂入）。 */
    @Volatile
    private var rotation = 0

    // ---- 持久化键（站位参数=实时旋钮，prefs 即改即存；不入 ParamsPersist 档案语义） ----

    fun loadPrefs(prefs: android.content.SharedPreferences) {
        _state.value = _state.value.copy(
            enabled = prefs.getBoolean(Prefs.ON, false),
            mode = prefs.getString(Prefs.MODE, null) ?: JudgementEngine.MODE_AUTO,
            nManual = prefs.getInt(Prefs.N, 1).coerceIn(1, 5),
            beta = prefs.getFloat(Prefs.BETA, StanceSpec.BETA_DEFAULT).coerceIn(StanceSpec.BETA_MIN, StanceSpec.BETA_MAX),
            brighten = prefs.getBoolean(Prefs.BRIGHTEN, false),
            gpuMode = prefs.getString(Prefs.GPU_MODE, null)
                ?: (if (prefs.getBoolean(Prefs.GPU_LEGACY, false)) StanceDetector.MODE_FORCE else StanceDetector.MODE_AUTO),
        )
    }

    object Prefs {
        const val ON = "stance_on"
        const val MODE = "stance_mode"
        const val N = "stance_n"
        const val BETA = "stance_beta"
        const val BRIGHTEN = "stance_brighten"

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
                reloadActive(app)
                withContext(Dispatchers.Main) {
                    if (_state.value.enabled) maybeAttachAfterGate()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Diag.w(TAG, "模型组装配失败：${e.message}")
                    _state.value = _state.value.copy(fallbackTip = "模型组装配失败：${e.message}")
                }
            }
        }
    }

    private fun resetPackScope() {
        packJob?.cancel()
        packJob = null
        packScope.cancel()
        packExecutor.shutdownNow()
        packExecutor = newPackExecutor()
        packScope = CoroutineScope(SupervisorJob() + packExecutor.asCoroutineDispatcher())
    }

    /**
     * 装配当前激活模型组（用户包优先 > 内置），**用户包加载失败自动回退内置**（D20）。
     * 装配复用既有 [StanceAnalysis] 管线（[StanceAnalysis.updateModel]），不重建线程/reader。
     */
    private fun reloadActive(context: Context) {
        val (dir, source) = StanceModelPack.activeDir(context)
        val meta = StanceModelPack.validateDir(dir) ?: throw IllegalStateException("模型组校验失败")
        try {
            applyPack(dir, meta, source)
        } catch (e: Exception) {
            if (source != "user") throw e
            Diag.w(TAG, "用户模型组不可用，回退内置：${e.message}")
            val builtin = StanceModelPack.builtinDir(context)
            var bMeta = StanceModelPack.validateDir(builtin)
            if (bMeta == null) {
                StanceModelPack.installBuiltin(context)
                bMeta = StanceModelPack.validateDir(builtin)
            }
            if (bMeta == null) throw e
            applyPack(builtin, bMeta, "builtin")
            _state.value = _state.value.copy(
                fallbackTip = "用户模型组不可用（${e.message}），已回退内置模型组",
            )
        }
    }

    private fun applyPack(dir: java.io.File, meta: Map<String, Any?>, source: String) {
        dumpGpuEnvironment()
        val det = StanceDetector()
        try {
            val ok = det.load(
                java.io.File(dir, "yolo11n_int8.tflite"),
                java.io.File(dir, "yolo11n_fp32.tflite"),
                gpuMode = _state.value.gpuMode,
            )
            if (!ok) throw IllegalStateException("检测模型不可加载（张量规格/文件异常）")
            // 先验模型后读标定：坏模型不能留下半套新安全区
            val head = StanceHead.fromJson(java.io.File(dir, "head.json").readText())
            val marginsText = java.io.File(dir, "margins.json").readText()
            val a = analysis ?: StanceAnalysis { out -> onOutput(out) }.also {
                it.rotationDegrees = rotation
                it.brighten = _state.value.brighten
                analysis = it
            }
            // 安全区覆盖与检测器一起在分析线程生效（MARGIN_BANDS 是全局可变，不能与 observe 并发写）
            a.updateModel(det, head, marginsText)
            detector = det
            val headMeta = meta["head_meta"] as? Map<*, *>
            val version = buildString {
                append("模型组 ${meta["created_at"] ?: "?"}（$source）")
                append("｜判定头 ${headMeta?.get("trained_at") ?: "?"} val_acc=${headMeta?.get("val_acc") ?: "?"}")
                append("｜安全区 ${meta["margins_sessions"]?.toString()?.take(36)}…")
            }
            _state.value = _state.value.copy(
                backendName = det.backendName,
                versionText = version,
                fallbackTip = if (det.backendName.contains("回退")) "GPU 不可用已回退 CPU" else _state.value.fallbackTip,
            )
            syncEngineParams()
        } catch (e: Exception) {
            // 新检测器没交给管线就自己关掉，别把旧模型一起拖下水
            det.close()
            throw e
        }
    }

    /** 分析线程回调（Main 线程）：状态 + 播报 + 引擎参数同步（同步本身走分析线程，见下）。 */
    private fun onOutput(out: EngineOutput) {
        _state.value = _state.value.copy(output = out)
        speaker?.let { sp -> out.lines.forEach { sp.speak(it) } }
        syncEngineParams()
    }

    private fun syncEngineParams() {
        val a = analysis ?: return
        val s = _state.value
        // 必须走分析线程：observe() 与参数写入并发会撕裂判定状态（见 updateEngineConfig）
        a.updateEngineConfig(s.beta, s.mode, s.nManual, s.brighten)
    }

    /** 开/关站位提示（弹窗开关）。开=挂 analysis 槽+resume；关=摘槽（各一次会话重建）。 */
    fun setEnabled(context: Context, on: Boolean) {
        if (recording) {
            // 录制窗口两向都拒绝：挂/摘槽都会重建相机会话（MediaRecorder 面在会话里）——
            // 状态保持录制前的样子（本功能录制时暂停，不是关闭），停录后自动恢复。
            _state.value = _state.value.copy(fallbackTip = "录制中不可调整站位提示——停录后再试")
            return
        }
        if (on && frontLens) {
            // D13：前置自拍场景没有无人值守痛点，方向语义也只对后置成立
            _state.value = _state.value.copy(
                enabled = false,
                running = false,
                fallbackTip = "前置镜头不提供站位提示——切回后置后再开",
            )
            return
        }
        _state.value = _state.value.copy(enabled = on)
        prefs(context).edit().putBoolean(Prefs.ON, on).apply()
        if (on) attachAnalysis(context) else detachAnalysis(context)
    }

    private fun attachAnalysis(context: Context) {
        val a = analysis
        if (a == null) {
            // 模型组还在装配（异步）：记一笔，装配完成后补挂
            pendingAttach = _state.value.enabled && onPage && !recording
            return
        }
        if (attached || recording || !onPage) return
        val size = try {
            characteristicsProvider?.invoke()?.let {
                StanceAnalysis.pickYuvSize(it, targetAspectProvider?.invoke())
            } ?: StanceAnalysis.DEFAULT_SIZE
        } catch (_: Exception) {
            StanceAnalysis.DEFAULT_SIZE
        }
        val surface = a.start(size)
        a.resumeListening()
        syncEngineParams()
        val hooked = attachHook?.invoke(surface, a.reader()) ?: true
        attached = hooked
        pendingAttach = !hooked
        if (!hooked) a.pauseListening()
        _state.value = _state.value.copy(running = hooked)
    }

    /** 方向变化（转屏/换镜头）：分析帧转正角随之更新，已挂的管线立即生效。 */
    fun setRotation(degrees: Int) {
        rotation = ((degrees % 360) + 360) % 360
        analysis?.rotationDegrees = rotation
    }

    /** M2c 接缝：宿主（CameraScreen）注入——挂引擎 analysis 槽的真正实现。
     * 返回 false = 引擎尚未就绪（调用方保持未挂状态，等就绪点补挂）。 */
    var attachHook: ((Surface, ImageReader?) -> Boolean)? = null

    /** detach 时把 null 交回引擎（摘槽）。 */
    var detachHook: (() -> Unit)? = null

    private fun detachAnalysis(context: Context) {
        pendingAttach = false
        if (!attached) {
            _state.value = _state.value.copy(running = false, output = null)
            return
        }
        attached = false
        analysis?.pauseListening()
        detachHook?.invoke()
        _state.value = _state.value.copy(running = false, output = null)
    }

    /**
     * 录制窗口开始（§5.1：叠加+播报+推理全停）。**引擎槽不动**——只停本管线回调，
     * 避免在 MediaRecorder 面已在会话里时再重建一次会话（分析流留槽由录制会话承载）。
     */
    fun suspendForRecording() {
        recording = true
        analysis?.pauseListening()
        _state.value = _state.value.copy(running = false, output = null)
    }

    /** 停录恢复（开关状态/持久化都不动——下一次开录仍自动暂停，无需用户重开）。 */
    fun resumeAfterRecording() {
        recording = false
        if (pendingDetach) {
            // 录制窗口内切了前置：当时摘槽会打断录制，停录后补摘
            pendingDetach = false
            detachAnalysis(appContext)
            return
        }
        maybeAttachAfterGate()
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
        if (!_state.value.enabled) return
        val a = analysis
        // 引擎可能在上次离页时关闭了分析 reader（unbind/重建）——面已死就按"未挂"重来
        if (a != null && attached && !a.isSurfaceLive()) attached = false
        maybeAttachAfterGate()
    }

    /** 预览页离开组合：停听 + 清叠加（相机可被别的页面保活，但站位播报不该在设置页响）。 */
    fun onPageExit() {
        onPage = false
        analysis?.pauseListening()
        _state.value = _state.value.copy(running = false, output = null)
    }

    /** 门控放开（非录制 + 在页 + 后置）时：已挂→恢复监听；未挂→补挂（含装配未完成的 pending）。 */
    private fun maybeAttachAfterGate() {
        if (!_state.value.enabled || recording || !onPage || frontLens) return
        val a = analysis
        if (a == null) {
            pendingAttach = true
            return
        }
        if (attached) {
            a.resumeListening()
            _state.value = _state.value.copy(running = true)
        } else {
            attachAnalysis(appContext)
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

    /** 切前置（D13：直接关闭开关重置；录制窗口只暂停，待停录补摘）。 */
    fun disableForFrontLens(context: Context) {
        _state.value = _state.value.copy(enabled = false, running = false, output = null)
        prefs(context).edit().putBoolean(Prefs.ON, false).apply()
        analysis?.pauseListening()
        pendingAttach = false
        if (recording) {
            pendingDetach = true
        } else {
            detachAnalysis(context)
        }
    }

    /** 弹窗动作：β/模式/人数/提亮/加速开关（全部即改即存 + 热更新）。 */
    fun setBeta(context: Context, v: Float) {
        _state.value = _state.value.copy(beta = v)
        prefs(context).edit().putFloat(Prefs.BETA, v).apply()
        syncEngineParams()
    }

    fun setMode(context: Context, mode: String, n: Int) {
        _state.value = _state.value.copy(mode = mode, nManual = n.coerceIn(1, 5))
        prefs(context).edit().putString(Prefs.MODE, mode).putInt(Prefs.N, n.coerceIn(1, 5)).apply()
        syncEngineParams()
    }

    fun setBrighten(context: Context, on: Boolean) {
        _state.value = _state.value.copy(brighten = on)
        prefs(context).edit().putBoolean(Prefs.BRIGHTEN, on).apply()
        syncEngineParams()
    }

    /**
     * GPU 环境 dump（r03）：SOC/Vulkan 硬事实进诊断日志——卓易通转发图形栈下
     * "白名单不通过"到底缺什么，一次日志看清（GL 字符串需 GL 上下文，另由
     * Detector 的 delegate 判定结果补充）。
     */
    private fun dumpGpuEnvironment() {
        try {
            val soc = listOf(
                "SOC_MODEL=${android.os.Build.SOC_MODEL.takeIf { it.isNotEmpty() } ?: "?"}",
                "SOC_MFR=${android.os.Build.SOC_MANUFACTURER.takeIf { it.isNotEmpty() } ?: "?"}",
                "HW=${android.os.Build.HARDWARE}",
            ).joinToString(" ")
            val vk = "vulkan=${appContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)}" +
                " vulkanCompute=${appContext.packageManager.hasSystemFeature("android.hardware.vulkan.compute")}"
            Diag.i(TAG, "GPU 环境 $soc $vk")
        } catch (_: Exception) {
        }
    }

    /** GPU/NPU 加速三态（r03：auto/force/off；enabled 由 UI 联动设置页"使用GPU"=KEY_DEFAULT_RENDER==GPU）。 */
    fun setGpuMode(context: Context, mode: String) {
        _state.value = _state.value.copy(gpuMode = mode)
        prefs(context).edit().putString(Prefs.GPU_MODE, mode).apply()
        // 换 backend = 换检测器；装配走后台线程（Interpreter + GpuDelegate 构造可达数百 ms），
        // 复用同一条分析管线（旧检测器在分析线程内关闭）
        reloadAsync(context.applicationContext)
    }

    /** SAF 导入模型组（D20 用户自助替换）。解压在调用线程（用户动作），模型装配异步。 */
    fun importPack(context: Context, uri: Uri): Boolean {
        return try {
            StanceModelPack.importZip(context, uri)
            reloadAsync(context.applicationContext)
            true
        } catch (e: Exception) {
            _state.value = _state.value.copy(fallbackTip = "导入失败：${e.message}（已保持内置模型组）")
            false
        }
    }

    /** 「自行存入模型」：扫描外部模型目录最新 zip 导入（用户十七稿通道）。 */
    fun importFromExternalDir(context: Context): Boolean {
        return try {
            StanceModelPack.importLatestFromExternal(context)
            reloadAsync(context.applicationContext)
            true
        } catch (e: Exception) {
            _state.value = _state.value.copy(fallbackTip = "导入失败：${e.message}（已保持内置模型组）")
            false
        }
    }

    /** 退出预览页/窗口销毁释放（Activity finish；配置变更不调——与 CameraController 同级）。 */
    fun release() {
        detachHook?.invoke()
        resetPackScope()
        analysis?.release()
        detector?.close()
        speaker?.release()
        analysis = null
        detector = null
        speaker = null
        prepared = false
        attached = false
        pendingAttach = false
        pendingDetach = false
        frontLens = false
        recording = false
        onPage = true
        _state.value = _state.value.copy(running = false, output = null, backendName = "")
    }

    companion object {
        private const val TAG = "StanceController"
        @Volatile
        private var instance: StanceController? = null

        /** 页面级单例（预览页生命周期=窗口生命周期，与 CameraController 同级）。 */
        fun get(context: Context): StanceController =
            instance ?: synchronized(this) {
                instance ?: StanceController(context.applicationContext).also { instance = it }
            }
    }
}
