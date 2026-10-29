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
import com.wotagei.cam.core.LensPickPreference
import com.wotagei.cam.core.LensSlot
import com.wotagei.cam.core.LensType
import com.wotagei.cam.core.WotaParams
import com.wotagei.cam.core.WotaTag
import com.wotagei.cam.core.dumpLenses
import com.wotagei.cam.core.enumerateLenses
import com.wotagei.cam.core.pickCapabilityDefault
import com.wotagei.cam.diag.Diag as Log
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
    /**
     * 用户显式选过镜头的持久化位（[LensPickPreference]）：false 期间 pickSlot 走能力协商默认
     * （后摄里最大视频档最大的那颗，不认档位标签）；switchLens 一次之后永久尊重用户选择。
     */
    private var lensPickedByUser = LensPickPreference.isPicked(app)
    private val bus: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val params: WotaParams = WotaParams(bus)

    private val ui = MutableStateFlow(CameraUiState())
    val state: StateFlow<CameraUiState> = ui

    /**
     * 帧率被**自动降档**的提示槽（2026-10-26）：引擎在一次高速档请求失败后把 fps 退到可达上限，
     * 这里原样转给界面（值 = 退到的帧率）。界面读走提示后调 [clearFpsDowngrade] 清槽——
     * StateFlow 相等去重，不清就收不到下一次同值事件（与 [RecordRunner.ambientLostNotice] 同一手法）。
     */
    private val fpsDowngrade = MutableStateFlow<Int?>(null)
    val fpsDowngraded: StateFlow<Int?> = fpsDowngrade

    fun clearFpsDowngrade() {
        fpsDowngrade.value = null
    }

    private var engine: Camera2Engine? = null
    private var boundSink: PreviewSink? = null
    private var lensSlots: List<LensSlot> = emptyList()
    private var pendingStart = false
    private var busyLoading = false

    // ------------------------------------------------- 设置页覆盖保活（2026-10-29 手动白平衡绿帧）

    /**
     * 设置页正推在录制页之上（MainActivity 的目的地回调维护，navigate/popBackStack 时同步触发，
     * 先于旧页 dispose，读到的必是新值）。true 期间录制页 dispose **不关相机、不释 GL**。
     *
     * 为什么需要：导航离开录制页会销毁 GL 预览面（SurfaceTexture 随引擎释放一起没了），相机会话
     * 失去输出面只能整条重开——卓易通桥（华为 ALN-AL00 实测）在会话重建的首几拍无视请求元数据、
     * 沿用 HAL 里残留的 AWB_OFF 状态，就是用户看到的"回设置再回来闪一下绿"（种子 CCM/稳压窗都
     * 拦不住，绿帧发生在重建间隙、与请求内容无关）。覆盖期保活让会话跨越设置往返始终活着，
     * 回场零重建零闪帧。只对设置页置真：其他目的地（媒体库等）可能一待几分钟，相机常开费电发热。
     */
    var previewKeptBehind: Boolean = false

    /** GPU 预览引擎的**页面级缓存**：跨设置往返复用同一实例（SurfaceTexture 活着 = 会话不用重建）。释放权在 [onCleared]，页面 dispose 不再碰它 */
    private var cachedGlSink: GlRenderEngine? = null

    /** GPU 预览引擎取用口：首次创建，之后同一实例（组合销毁重建也拿回同一个） */
    fun glSink(): GlRenderEngine = cachedGlSink ?: GlRenderEngine().also { cachedGlSink = it }

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
            onPreviewStatus = { status -> publishOnMain { ui.value = ui.value.copy(preview = status) } },
            onFpsDowngraded = { to -> publishOnMain { fpsDowngrade.value = to } }
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

    /** 显示面挂摘/重挂（设置页覆盖往返）：布防引擎侧手动白平衡稳压窗（绿帧取证，2026-10-29） */
    fun onPreviewSurfaceTransient() {
        engine?.onSinkSurfaceTransient()
    }

    // ------------------------------------------------- 生命周期

    /** 打开相机并起预览；权限缺失时只置 [CameraUiState.needsCameraPermission] 并等回调 */
    fun start() {
        if (engine == null) {
            pendingStart = true
            return
        }
        // 覆盖保活回场（设置页往返）：设备与预览都在跑，这里任何"重开"都会撞上桥的会话重建
        // 窗口（手动白平衡绿帧）——原样返回，预览本来就没停过。只 OPEN 不预览（ON_PAUSE 后）
        // 的设备仍走下面正常路径由 resume()/这里补 startPreview。
        if (engineOrNull?.deviceStatus == DeviceStatus.OPEN_SUCCEED &&
            ui.value.preview == PreviewStatus.ING
        ) {
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
        // 预览一直在跑（覆盖保活回场）：不重提请求——面板已验证"同会话重发请求"不闪绿，
        // 但"重启预览 + 首拍重提交"是否在桥上也干净没有证据，能不动就不动
        if (e.deviceStatus == DeviceStatus.OPEN_SUCCEED && ui.value.preview == PreviewStatus.ING) return
        if (e.deviceStatus == DeviceStatus.OPEN_SUCCEED) e.startPreview() else start()
    }

    override fun onPause(owner: LifecycleOwner) {
        // 设置页覆盖保活（2026-10-29 手动白平衡绿帧）：覆盖期连预览都不停——一旦停了，
        // 回场就变成"重启预览"，桥在重启首拍照样可能吐过渡帧，白保活
        if (previewKeptBehind) return
        pause()
    }

    override fun onResume(owner: LifecycleOwner) {
        if (previewKeptBehind) return
        resume()
    }

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
        // GL 引擎的释放权在这里（页面 dispose 不再 release）：跨设置往返复用同一实例
        cachedGlSink?.release()
        cachedGlSink = null
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
                // 打开的是哪一颗物理头必须能从日志对上：ability.dump 的 label 只是档位名（WIDE），
                // 同档位多颗/归档存疑时只有 key + 等效焦距 + 最大视频档能定位现场
                Log.i(
                    WotaTag.LENS,
                    "open slot=${slot.key} type=${slot.type} eq=${slot.eqFocal}mm " +
                        "videoMax=${slot.ability.videoSizes.maxOfOrNull { it.pixels }} " +
                        "afModes=${slot.ability.afModes} minFocusD=${slot.ability.minFocusDistanceDiopter}"
                )
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
        if (slots.isNotEmpty()) {
            lensSlots = slots
            // 镜头枚举全量落日志（M1 门禁的字面）：哪颗物理头被归进哪个档、各自的最大视频档——
            // 「画质只有 720p」「广角是定焦」这类反馈，没有这张表就无法判断是枚举归档问题
            // 还是该颗镜头本来如此（2026-10-26 marble 反馈的取证缺口）
            dumpLenses(slots)
        }
        // 能力协商的出厂默认（用户指令「能力协商，而非能力假设」）：从未显式选过镜头时不认
        // 档位标签，开后摄里最大视频档最大的那颗——个别机型会把小底定焦头归进「广角」档
        // （marble 实测：出厂「广角」最大 1600x1200 且定焦）。显式切换过一次后完全尊重选择。
        if (!lensPickedByUser) {
            pickCapabilityDefault(lensSlots)?.let {
                Log.i(WotaTag.LENS, "capability default -> ${it.key} type=${it.type}")
                return it
            }
        }
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
        markLensPickedByUser()
        reopenCurrentLens()
    }

    /** 按槽位切镜头（[LensSlot.key] 唯一指认一颗镜头，档位重名时也只有这条准） */
    fun switchLens(slot: LensSlot) {
        params.lens.value = slot.type
        chosenSlotKey = slot.key
        markLensPickedByUser()
        reopenCurrentLens()
    }

    /** 显式切换即永久关闭能力协商默认（持久化：冷启后也不再替用户选镜头） */
    private fun markLensPickedByUser() {
        if (!lensPickedByUser) {
            lensPickedByUser = true
            LensPickPreference.markPicked(getApplication())
        }
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
