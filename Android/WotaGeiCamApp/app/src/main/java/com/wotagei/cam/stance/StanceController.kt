package com.wotagei.cam.stance

import com.wotagei.cam.diag.Diag

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.media.ImageReader
import android.net.Uri
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 站位功能集成中枢（M2c/M3）：持有分析管线、检测器、引擎、判定头、播报器，
 * 对 UI 只暴露 StateFlow 与少量动作。生命周期：
 * - **预览态专属**：录制开始自动关闭（D10/§5.1）、切前置直接关闭开关重置（D13）；
 * - 引擎槽挂/摘：enable→setAnalysisTarget(surface, reader)（会话重建一次，勿反复）；
 *   disable→setAnalysisTarget(null)（同样一次）；录制窗口用 [suspendForRecording]/
 *   [resumeAfterRecording]（引擎槽不动，只停本管线回调，避免反复重建会话）；
 * - 模型组：首启从 assets 复制内置包 → activeDir（用户包 > 内置）→ 装配检测器/头/安全区；
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
        val gpuOn: Boolean = false,
        val versionText: String = "",           // 模型组/判定头/安全区版本文本
        val fallbackTip: String? = null,        // 降级/错误提示（弹一次）
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var analysis: StanceAnalysis? = null
    private var detector: StanceDetector? = null
    private var speaker: StanceSpeaker? = null
    private var reader: ImageReader? = null
    private var analysisSurface: Surface? = null
    private var attached = false
    private var prepared = false
    private var recording = false

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
            gpuOn = prefs.getBoolean(Prefs.GPU, false),
        )
    }

    object Prefs {
        const val ON = "stance_on"
        const val MODE = "stance_mode"
        const val N = "stance_n"
        const val BETA = "stance_beta"
        const val BRIGHTEN = "stance_brighten"
        const val GPU = "stance_gpu"
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("wota_settings", Context.MODE_PRIVATE)

    // ---- 生命周期 ----

    /** 进入预览态调（首次做模型组装配）。幂等。 */
    fun prepare(context: Context) {
        if (prepared) return
        prepared = true
        val prefs = prefs(context)
        loadPrefs(prefs)
        speaker = StanceSpeaker(context)
        try {
            val (dir, source) = StanceModelPack.activeDir(context)
            val meta = StanceModelPack.validateDir(dir)!!
            applyPack(dir, meta, source)
        } catch (e: Exception) {
            Diag.w(TAG, "模型组装配失败：${e.message}")
            _state.value = _state.value.copy(fallbackTip = "模型组装配失败：${e.message}")
        }
    }

    private fun applyPack(dir: java.io.File, meta: Map<String, Any?>, source: String) {
        val marginsText = java.io.File(dir, "margins.json").readText()
        val headText = java.io.File(dir, "head.json").readText()
        StanceSpec.loadCalibratedBands(marginsText)
        val head = StanceHead.fromJson(headText)
        val det = StanceDetector()
        val gpuOn = _state.value.gpuOn
        val ok = det.load(
            java.io.File(dir, "yolo11n_int8.tflite"),
            java.io.File(dir, "yolo11n_fp32.tflite"),
            preferGpu = gpuOn,
        )
        detector = det
        val analysis = StanceAnalysis { out ->
            _state.value = _state.value.copy(output = out)
            speaker?.let { sp -> out.lines.forEach { sp.speak(it) } }
            // 引擎参数热更新（弹窗改 β/模式时保持引擎同步）
            syncEngineParams()
        }
        analysis.detector = det
        analysis.head = head
        analysis.brighten = _state.value.brighten
        analysis.rotationDegrees = rotation
        this.analysis = analysis
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
    }

    private fun syncEngineParams() {
        val a = analysis ?: return
        val s = _state.value
        if (a.engine.beta != s.beta) a.engine.setBeta(s.beta)
        if (a.engine.mode != s.mode || a.engine.nManual != s.nManual) {
            a.engine.setMode(s.mode, s.nManual)
        }
        a.brighten = s.brighten
    }

    /** 开/关站位提示（弹窗开关）。开=挂 analysis 槽+resume；关=摘槽（各一次会话重建）。 */
    fun setEnabled(context: Context, on: Boolean) {
        _state.value = _state.value.copy(enabled = on)
        prefs(context).edit().putBoolean(Prefs.ON, on).apply()
        if (on) attachAnalysis(context) else detachAnalysis(context)
    }

    private fun attachAnalysis(context: Context) {
        val a = analysis ?: return
        if (attached || recording) return
        val size = try {
            characteristicsProvider?.invoke()?.let {
                StanceAnalysis.pickYuvSize(it, targetAspectProvider?.invoke())
            } ?: StanceAnalysis.DEFAULT_SIZE
        } catch (_: Exception) {
            StanceAnalysis.DEFAULT_SIZE
        }
        val surface = a.start(size)
        attached = true
        a.resumeListening()
        syncEngineParams()
        attachHook?.invoke(surface, a.reader())
    }

    /** 方向变化（转屏/换镜头）：分析帧转正角随之更新，已挂的管线立即生效。 */
    fun setRotation(degrees: Int) {
        rotation = ((degrees % 360) + 360) % 360
        analysis?.rotationDegrees = rotation
    }

    /** M2c 接缝：宿主（CameraScreen）注入——挂/摘引擎 analysis 槽的真正实现。 */
    var attachHook: ((Surface, ImageReader?) -> Unit)? = null

    /** detach 时把 null 交回引擎（摘槽）。 */
    var detachHook: (() -> Unit)? = null

    private fun detachAnalysis(context: Context) {
        if (!attached) return
        attached = false
        analysis?.pauseListening()
        detachHook?.invoke()
    }

    /** 录制开始（引擎槽不动，只停管线回调——避免 DIRECT 顶槽/反复重建会话）。 */
    fun suspendForRecording() {
        recording = true
        analysis?.pauseListening()
    }

    /** 停录恢复。 */
    fun resumeAfterRecording() {
        recording = false
        if (_state.value.enabled && attached) analysis?.resumeListening()
    }

    /** 切前置（D13：直接关闭开关重置）。 */
    fun disableForFrontLens(context: Context) {
        setEnabled(context, false)
        analysis?.pauseListening()
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

    /** GPU/NPU 加速开关（enabled 由 UI 联动设置页"使用GPU"=KEY_DEFAULT_RENDER==GPU）。 */
    fun setGpu(context: Context, on: Boolean) {
        _state.value = _state.value.copy(gpuOn = on)
        prefs(context).edit().putBoolean(Prefs.GPU, on).apply()
        // 重建检测器（backend 切换）；重建期管线暂停一拍
        val dir = try { StanceModelPack.activeDir(context).first } catch (_: Exception) { return }
        val meta = StanceModelPack.validateDir(dir) ?: return
        applyPack(dir, meta, if (dir == StanceModelPack.userDir(context)) "user" else "builtin")
    }

    /** SAF 导入模型组（D20 用户自助替换）。 */
    fun importPack(context: Context, uri: Uri): Boolean {
        return try {
            StanceModelPack.importZip(context, uri)
            val (dir, source) = StanceModelPack.activeDir(context)
            val meta = StanceModelPack.validateDir(dir)!!
            applyPack(dir, meta, source)
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
            val (dir, source) = StanceModelPack.activeDir(context)
            val meta = StanceModelPack.validateDir(dir)!!
            applyPack(dir, meta, source)
            true
        } catch (e: Exception) {
            _state.value = _state.value.copy(fallbackTip = "导入失败：${e.message}（已保持内置模型组）")
            false
        }
    }

    /** 退出预览页释放。 */
    fun release() {
        detachHook?.invoke()
        analysis?.release()
        detector?.close()
        speaker?.release()
        analysis = null
        detector = null
        speaker = null
        prepared = false
        attached = false
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
