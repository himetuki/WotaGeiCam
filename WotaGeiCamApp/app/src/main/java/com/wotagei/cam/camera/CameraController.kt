package com.wotagei.cam.camera

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.view.Surface
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import com.wotagei.cam.core.CameraAbility
import com.wotagei.cam.core.LensSlot
import com.wotagei.cam.core.LensType
import com.wotagei.cam.core.WotaParams
import com.wotagei.cam.core.enumerateLenses
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UI 侧需要的相机状态摘要（相机参数本身走 [WotaParams]，这里只放引擎态） */
data class CameraUiState(
    val device: DeviceStatus = DeviceStatus.CLOSE,
    val preview: PreviewStatus = PreviewStatus.INIT,
    val lenses: List<LensSlot> = emptyList(),
    val currentLens: LensType = LensType.WIDE,
    val needsCameraPermission: Boolean = false,
    val audioDegraded: Boolean = false,
    /**
     * 当前镜头槽位。档位会重复（多颗同焦段后摄/多颗前摄），UI 要高亮「正在使用」哪一颗时必须用它，
     * [currentLens] 只够显示档位名。
     */
    val currentSlot: LensSlot? = null
)

/**
 * 相机门面（UI 唯一入口， 设计决定一/二：UI 只写参数或调门面，绝不直调 Camera2）。
 *
 * 用法：`CameraController.of(activity)` → [bind] 预览面 → [start]；
 * 参数读写一律走 [params]（StateFlow 总线），fps 是否命中固定范围读 `params.fps.value.exact`。
 */
class CameraController(app: Application) : AndroidViewModel(app), DefaultLifecycleObserver {

    companion object {
        fun of(owner: ViewModelStoreOwner): CameraController =
            ViewModelProvider(owner)[CameraController::class.java]
    }

    /** 自建 scope：不依赖 viewModelScope，免得隐式要求 lifecycle-viewmodel-ktx */
    private val bus: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val params: WotaParams = WotaParams(bus)

    private val ui = MutableStateFlow(CameraUiState())
    val state: StateFlow<CameraUiState> = ui

    private var engine: Camera2Engine? = null
    private var boundSink: PreviewSink? = null
    private var lensSlots: List<LensSlot> = emptyList()
    private var pendingStart = false
    private var busyLoading = false

    /** 枚举期间收到的换挡请求先记一笔，等当前这次跑完补做一次（不排队成一串，只做最后一次即可） */
    private var reloadWanted = false

    /** 用户选中的槽位（[LensSlot.key]）；档位重名时靠它指认具体哪一颗 */
    private var chosenSlotKey: String? = null

    val engineOrNull: Camera2Engine? get() = engine

    // ------------------------------------------------- 预览面

    /** 绑定预览输出面；换面（DIRECT↔GPU）时重建引擎，参数总线保持不变 */
    fun bind(sink: PreviewSink) {
        if (boundSink === sink && engine != null) return
        engine?.close()
        boundSink = sink
        engine = Camera2Engine(
            context = getApplication(),
            params = params,
            sink = sink,
            scope = bus,
            onDeviceStatus = { status -> publishOnMain { ui.value = ui.value.copy(device = status) } },
            onPreviewStatus = { status -> publishOnMain { ui.value = ui.value.copy(preview = status) } }
        ).also { it.attach() }
        if (pendingStart) start()
    }

    /**
     * 引擎的状态回调都在相机工作线程上（`publishDevice`/`publishPreview` 是直调，
     * 而它们的全部调用点都在 workHandler 里，会话回调的 executor 也是 workExecutor）。
     * StateFlow 单次赋值线程安全，但 `ui.value = ui.value.copy(...)` 是读-改-写，
     * 与主线程上 `openCurrentLens` 的那几次写并发就会丢更新 —— 表现为偶发卡在「打开中」。
     */
    private fun publishOnMain(block: () -> Unit) {
        bus.launch { block() }
    }

    /** 视图销毁时解绑，避免引擎持有已失效的 Surface */
    fun unbind(sink: PreviewSink) {
        if (boundSink !== sink) return
        engine?.close()
        engine = null
        boundSink = null
    }

    /** GPU 引擎的输出面就绪（GlRenderEngine.onCameraSurfaceReady）时调用，立刻重试建会话 */
    fun onPreviewSurfaceReady() {
        engine?.onSinkSurfaceReady()
    }

    // ------------------------------------------------- 生命周期

    /** 打开相机并起预览；权限缺失时只置 [CameraUiState.needsCameraPermission] 并等回调 */
    fun start() {
        if (engine == null) {
            pendingStart = true
            return
        }
        if (!granted(Manifest.permission.CAMERA)) {
            ui.value = ui.value.copy(needsCameraPermission = true)
            pendingStart = true
            return
        }
        ui.value = ui.value.copy(needsCameraPermission = false)
        pendingStart = false
        // 缺 RECORD_AUDIO 只降级纯视频，不打断取景
        applyAudioPermission()
        openCurrentLens()
    }

    /** 停止预览并释放设备（离开相机页） */
    fun stop() {
        pendingStart = false
        engine?.stopPreview()
        engine?.close()
        engine = null
        boundSink = null
    }

    /** ON_PAUSE：停预览（停录由 record 包观察预览状态自行完成） */
    fun pause() {
        engine?.stopPreview()
    }

    /** ON_RESUME：设备还在就重起预览，被系统收回了就整条重开 */
    fun resume() {
        val e = engine ?: run { pendingStart = true; return }
        if (e.deviceStatus == DeviceStatus.OPEN_SUCCEED) e.startPreview() else start()
    }

    override fun onPause(owner: LifecycleOwner) = pause()

    override fun onResume(owner: LifecycleOwner) = resume()

    fun attachLifecycle(lifecycle: Lifecycle) {
        lifecycle.addObserver(this)
    }

    fun detachLifecycle(lifecycle: Lifecycle) {
        lifecycle.removeObserver(this)
    }

    override fun onCleared() {
        engine?.close()
        engine = null
        boundSink = null
        bus.cancel()
    }

    // ------------------------------------------------- 权限结果入口

    /**
     * UI 据此决定是否弹权限申请。**显式全量申请**（用户 2026-10-03 指令）：CAMERA 与
     * RECORD_AUDIO 恒一起申请，不再受 `audioEnabled` 门控——首启一次性把运行时权限要齐，
     * 避免后续开音/开功能时再补弹。（传感器类无需任何 manifest/运行时权限，不在此列。）
     */
    fun missingPermissions(): Array<String> {
        val list = mutableListOf<String>()
        if (!granted(Manifest.permission.CAMERA)) list += Manifest.permission.CAMERA
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            list += Manifest.permission.RECORD_AUDIO
        }
        return list.toTypedArray()
    }

    /** 权限申请结束后调用：重查权限，该起相机就起，缺音频就降级 */
    fun onPermissionsResult() {
        applyAudioPermission()
        start()
    }

    private fun applyAudioPermission() {
        if (granted(Manifest.permission.RECORD_AUDIO)) {
            if (ui.value.audioDegraded) ui.value = ui.value.copy(audioDegraded = false)
            return
        }
        if (params.audioEnabled.value) {
            params.audioEnabled.value = false
            ui.value = ui.value.copy(audioDegraded = true)
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(getApplication(), permission) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------- 镜头

    private fun openCurrentLens() {
        val e = engine ?: return
        // 正在枚举时不能把这次请求丢掉：`pickSlot` 要给每颗镜头读 characteristics，
        // 几十到几百毫秒，期间连点两颗药丸是很常见的操作。丢掉会留下
        // 「params.lens 已经改成新档位、实际还开着旧镜头」的顶栏与取景不一致
        if (busyLoading) {
            reloadWanted = true
            return
        }
        busyLoading = true
        bus.launch {
            try {
                val manager = cameraManager()
                val slot = withContext(Dispatchers.IO) { pickSlot(manager) }
                if (slot == null) {
                    ui.value = ui.value.copy(device = DeviceStatus.OPEN_FAILED_INNER_ERROR)
                    return@launch
                }
                chosenSlotKey = slot.key
                ui.value = ui.value.copy(currentLens = slot.type, currentSlot = slot, lenses = lensSlots)
                applyAbilityToParams(slot.ability)
                e.open(slot)
                e.startPreview()
            } finally {
                busyLoading = false
                if (reloadWanted) {
                    reloadWanted = false
                    openCurrentLens()
                }
            }
        }
    }

    private fun cameraManager(): CameraManager =
        getApplication<Application>().getSystemService(Context.CAMERA_SERVICE) as CameraManager

    /** 每次换挡重新枚举（很轻，且能覆盖镜头被系统临时下线的情况） */
    private fun pickSlot(manager: CameraManager): LensSlot? {
        val slots = runCatching { enumerateLenses(manager) }.getOrDefault(emptyList())
        if (slots.isNotEmpty()) lensSlots = slots
        // 先认槽位（同档位多颗时才准），再退到档位名，最后退到列表首颗
        return lensSlots.firstOrNull { it.key == chosenSlotKey }
            ?: lensSlots.firstOrNull { it.type == params.lens.value }
            ?: lensSlots.firstOrNull { it.type == LensType.WIDE }
            ?: lensSlots.firstOrNull()
    }

    /** 切镜头 = 整套能力换挡：重收 range 并回填默认值 */
    fun switchLens(type: LensType) {
        params.lens.value = type
        // 按档位切：清掉槽位偏好，让 pickSlot 重新落到该档位的第一颗
        chosenSlotKey = null
        reopenCurrentLens()
    }

    /** 按槽位切镜头（[LensSlot.key] 唯一指认一颗镜头，档位重名时也只有这条准） */
    fun switchLens(slot: LensSlot) {
        params.lens.value = slot.type
        chosenSlotKey = slot.key
        reopenCurrentLens()
    }

    private fun reopenCurrentLens() {
        val e = engine ?: return
        val resolved = lensSlots.firstOrNull { it.key == chosenSlotKey }
            ?: lensSlots.firstOrNull { it.type == params.lens.value }
        val opened = ui.value.currentSlot
        // 已经开着同一颗就不要再拆一次会话（点当前镜头是常见误操作）
        if (resolved != null && resolved.key == opened?.key && e.deviceStatus == DeviceStatus.OPEN_SUCCEED) return
        openCurrentLens()
    }

    private fun applyAbilityToParams(ability: CameraAbility) {
        params.applyAbility(ability)
        // 换挡可能改动多个参数值，统一压一次脏标记让请求跟上
        engine?.markDirty()
    }

    // ------------------------------------------------- 点按对焦

    /** 归一化入口（与 core 的 TapPoint 同域：[0,1]，已按 sensorOrientation 摆正） */
    fun tapToFocus(normX: Float, normY: Float) {
        engine?.tapToFocus(normX, normY)
    }

    /** 像素入口：UI 只有视图坐标时用这个，内部按视图尺寸归一化 */
    fun tapToFocusInView(viewX: Float, viewY: Float, viewWidth: Int, viewHeight: Int) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        engine?.tapToFocus(viewX / viewWidth, viewY / viewHeight)
    }

    /** 总线兜底：UI 直接写 params.focusPoint 时也认（core 会把它按能力换挡清空，属正常） */
    private fun observeFocusPoint() {
        bus.launch {
            params.focusPoint.drop(1).collect { point ->
                if (point != null) tapToFocus(point.x, point.y)
            }
        }
    }

    // ------------------------------------------------- 与其他模块的接缝

    /** record 包登记编码面；GPU 模式下引擎不会把它挂进相机会话（由 GL 承接） */
    fun setRecordingTarget(surface: Surface?, reader: ImageReader? = null) {
        engine?.setExtraTarget(surface, reader)
    }

    /** 批量写多个参数后手动统一下发一次（引擎自身已按 5ms 合并，通常不需要调） */
    fun applyParams() {
        engine?.markDirty()
    }

    /** 错误恢复：保留设备、重建会话 */
    fun restartPreview() {
        engine?.restartPreview()
    }

    init {
        observeFocusPoint()
    }
}
