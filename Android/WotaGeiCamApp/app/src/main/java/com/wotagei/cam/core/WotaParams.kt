package com.wotagei.cam.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 参数三件套：值 + 范围 + 是否可用（00 文件第 7 节）。
 * range=null 表示本机无该能力，enabled=false 时 UI 灰显；exact=false 表示只能给近似值（如 fps 无固定档）。
 * T 上界必须 Comparable：ClosedRange<T> 的自身约束（契约里写的 `<T>` 无法编译，此处补界）。
 */
data class ParamState<T : Comparable<T>>(
    val value: T,
    val range: ClosedRange<T>? = null,
    val enabled: Boolean = true,
    val exact: Boolean = true
)

/**
 * 参数总线：每个可调项一个 StateFlow，UI 只读流、camera 层只读快照。
 * 切镜头调用 [applyAbility] 重算范围并钳制当前值。
 */
class WotaParams(private val scope: CoroutineScope) {

    val lens = MutableStateFlow(LensType.WIDE)
    val renderMode = MutableStateFlow(RenderMode.GPU)
    val size = MutableStateFlow(Size(1920, 1080))
    /**
     * 用户（或出厂默认）要的画幅档，与「当前镜头实际生效的 [size]」分开记：
     * 换镜头只把这一档在新镜头能力里重新落档，切回来还能恢复原分辨率。
     */
    var requestedSize = Size(1920, 1080)
        private set

    /** UI 选档走这里：同时记意图并立即生效 */
    fun requestSize(s: Size) {
        requestedSize = s
        size.value = s
    }
    val fps = MutableStateFlow(ParamState(25, exact = true))

    /**
     * 音频降噪 / 画面降噪（2026-10-26 用户需求：两项默认关）。
     * 与相机参数放同一条总线：面板即改即生效，落盘由设置持久化层负责（键见 ParamsPersist）。
     * 画面降噪的实际生效档还要过 [com.wotagei.cam.core.effectiveVideoDenoise]（DIRECT 模式退回 HAL）。
     */
    val audioDenoise = MutableStateFlow(com.wotagei.cam.core.AudioDenoise.OFF)
    val videoDenoise = MutableStateFlow(com.wotagei.cam.core.VideoDenoise.OFF)
    val shutter = MutableStateFlow(ParamState(40_000_000L))          // ns，1/25
    val iso = MutableStateFlow(ParamState(100))
    val aeMode = MutableStateFlow(AeMode.AUTO)
    val ev = MutableStateFlow(ParamState(0))                          // 步数，非倍率
    val afMode = MutableStateFlow(AfMode.CONTINUOUS_VIDEO)
    val manualFocusDiopter = MutableStateFlow(ParamState(0f))
    val focusPoint = MutableStateFlow<TapPoint?>(null)

    /**
     * 对焦锁定（用户反馈第 2 条）：锁定的唯一真源 + 锁定锚点（最近一次点按处，缺省画面中心）。
     *
     * **刻意不持久化**：这两个字段不进 `ParamsPersist.KEYS` 键白名单——锁定是瞬时拍摄态，
     * 冷启/清后台回来不该自动锁焦；存活范围与清锁规则见 [applyAbility]（会话重建保持、
     * 相机整套重开清除），引擎按本字段的流事件做保持/解除。
     */
    val focusLocked = MutableStateFlow(false)
    val focusLockPoint = MutableStateFlow<TapPoint?>(null)

    val zoom = MutableStateFlow(ParamState(1f))
    val wbMode = MutableStateFlow(WbPreset.AUTO)
    val kelvin = MutableStateFlow(ParamState(5500))
    val tint = MutableStateFlow(ParamState(0))
    val flash = MutableStateFlow(Flash.OFF)
    val stabilize = MutableStateFlow(Stabilize.OFF)
    val bitrate = MutableStateFlow(10_000_000)
    val sampleRate = MutableStateFlow(48_000)
    val audioEnabled = MutableStateFlow(true)
    val frameEffect = MutableStateFlow(FrameEffect.NONE)
    val zebraThreshold = MutableStateFlow(0.8f)
    val zebraDensity = MutableStateFlow(2)
    val peakingStrength = MutableStateFlow(0.6f)
    val peakingColor = MutableStateFlow(PeakingColor.RED)

    /**
     * 补弧合并差分死区 D（8bit luma 级）：`Δluma > D` 时整颗像素取被抽帧，否则保持保留帧。
     * 默认 12；写入必须走 [setArcMergeDeadband]（同步发布到 [ArcMergeDeadband] 供 GL/离线
     * 修复等无参数总线实例的消费方读取），直接改 `.value` 会让两处读数分叉。
     */
    val arcMergeDeadband = MutableStateFlow(ArcMergeDeadband.DEFAULT)

    fun setArcMergeDeadband(value: Int) {
        val clamped = ArcMergeDeadband.clamp(value)
        arcMergeDeadband.value = clamped
        ArcMergeDeadband.publish(clamped)
    }

    /** RGB 曲线：只走 GPU 渲染链（DIRECT 无片元 pass），恒等即关闭 */
    val curve = MutableStateFlow(CurveStack.IDENTITY)
    val refLines = MutableStateFlow(0)                                // RefLineType 位掩码
    val levelEnabled = MutableStateFlow(true)

    /**
     * 换挡：按新镜头能力重算 range 并钳制当前值。
     * 顺序有依赖——快门上限要用帧率（≤1/fps），故帧率先算。
     */
    fun applyAbility(a: CameraAbility) {
        // 画幅先落档：帧率区间是按尺寸查的，顺序反过来会拿旧尺寸的区间去配新尺寸
        applySize(a)
        val targetFps = fps.value.value.coerceAtLeast(1)
        // 一档的完整决议交给 core.decideFps（普通表先来、覆盖不了才看高速表）——UI 的可用性/※、
        // 引擎的下发与会话选择都走同一支，这里只把结果落进总线，不再自己判模态
        val pick = a.decideFps(size.value, targetFps)?.pick
        fps.value = if (pick == null) ParamState(targetFps, null, enabled = false, exact = false)
        else ParamState(targetFps, pick.lo..pick.hi, true, pick.exact)

        // 快门上限吃「实际生效的帧周期上界」= 上面 pick 到的 hi（无固定 [f,f] 时是设备的可变范围上界），
        // 不是用户档位本身：否则用户选 24fps、设备只给 [15,30]（非精确）时，列表会按 1/24 判可选，
        // 下发/回写却按 30fps 帧周期压回 1/30，露出一个点不住的死档位。
        applyExposure(a, targetFps)
        applyEv(a)
        applyZoom(a)
        applyFocus(a)
        applyWhiteBalance(a)
        applyModeGates(a)

        // activeArray 随镜头变化，旧点按坐标语义失效（换算依赖 activeArray）
        focusPoint.value = null
        // 对焦锁定清锁（Q4）：换镜头/切渲染模式/离开取景页再回/权限重开等一切经本函数的
        // 「相机整套重开」都要解锁，锚点随锁一起失效（未锁定时不触碰——解锁后的残留锚点
        // 没有任何读取方，下一次上锁由 UI 整份覆盖）。这是清锁的唯一落点。
        if (focusLocked.value) {
            focusLocked.value = false
            focusLockPoint.value = null
        }
        a.dump(lens.value.name)
    }

    /**
     * @param targetFps 帧率**档位**（用户选的那档）。快门上限的帧周期口径与它同源
     *              （2026-10-04 r11 起与下发侧 `exposurePlan` 一致：都吃档位、不再吃 range.hi——
     *              可变范围 [24,30] 上选 24，旧口径会把 1/24 压回 1/30，强制档成死档）。
     */
    private fun applyExposure(a: CameraAbility, targetFps: Int) {
        iso.value = if (a.iso.ok()) {
            ParamState(clamped(iso.value.value, a.iso.lo, a.iso.hi), a.iso.lo..a.iso.hi)
        } else {
            ParamState(iso.value.value, null, enabled = false)
        }

        // 快门上限吃**帧率档位**（2026-10-04 r11 与下发侧同源）：r10 起下发/帧周期都按档位
        // （可变范围 [24,30] 选 24 时帧周期 41.67ms 在合法域内、HAL 放到 24fps），UI 可选域
        // 若仍按 hi=30 算，换镜头 applyAbility 一跑就会把用户选的 1/24 静默压回 1/30。
        // 上限的算法抽在 core 的 [shutterCeilingNs]（纯函数，有 JVM 单测）：先把设备上限抬到
        // 强制档 1/24、1/25，再过 1/10s 与 1/帧周期。
        if (a.exposureNs.ok()) {
            val lo = a.exposureNs.lo.toLong()
            val hi = shutterCeilingNs(a.exposureNs.hi.toLong(), targetFps)
            shutter.value = if (hi >= lo) {
                ParamState(clamped(shutter.value.value, lo, hi), lo..hi)
            } else {
                // 设备最短曝光下限已超出一帧周期，该帧率下手动快门无解
                ParamState(lo, null, enabled = false)
            }
        } else {
            shutter.value = ParamState(shutter.value.value, null, enabled = false)
        }
    }

    private fun applyEv(a: CameraAbility) {
        ev.value = if (a.evRange.ok()) {
            ParamState(clamped(ev.value.value, a.evRange.lo, a.evRange.hi), a.evRange.lo..a.evRange.hi)
        } else {
            ParamState(0, null, enabled = false)
        }
    }

    private fun applyZoom(a: CameraAbility) {
        // ZOOM_RATIO 优先（SDK≥30 + 有能力），否则回退数码变焦上限
        val ratio = a.zoomRatioRange
        val range = if (ratio != null && ratio.ok()) ratio else RangeF(1f, a.maxDigitalZoom.coerceAtLeast(1f))
        zoom.value = if (range.ok()) {
            ParamState(clamped(zoom.value.value, range.lo, range.hi), range.lo..range.hi)
        } else {
            ParamState(1f, null, enabled = false)
        }
    }

    private fun applyFocus(a: CameraAbility) {
        val maxDiopter = a.minFocusDistanceDiopter
        manualFocusDiopter.value = if (maxDiopter > 0f) {
            ParamState(clamped(manualFocusDiopter.value.value, 0f, maxDiopter), 0f..maxDiopter)
        } else {
            ParamState(0f, null, enabled = false)   // 定焦镜头（前摄常见）无手动对焦能力
        }

        // 换挡口径（用户反馈第 1 条 · 拍板 Q3）：AF 模式**能力含当前档就保留**，只在能力不含时
        // 才回落 PREFERENCE 首档。旧口径无条件回填连续档，会把「持久化 AF 模式」恢复的值当场
        // 覆盖掉（恢复 MANUAL 的用户换一次镜头/冷启一次就变回连续）；定焦镜头（afModes 只含
        // OFF）仍按 MANUAL 兜底不变。
        afMode.value = when {
            a.afModes.isEmpty() -> AfMode.MANUAL
            a.afModes.contains(afMode.value.cam2Mode) -> afMode.value
            else -> AfMode.PREFERENCE.firstOrNull { a.afModes.contains(it.cam2Mode) } ?: AfMode.MANUAL
        }
    }

    private fun applyWhiteBalance(a: CameraAbility) {
        // 手动档的能力回弹（2026-10-26 反馈 #6）：存量用户可能在不支持 TRANSFORM_MATRIX 的
        // 机器上持久化过 MANUAL（变绿+滑杆失效的成因）。能力表到了才发现不支持 ⇒ 弹回 AUTO
        // （与帧率自动降档同一条"自动变更必须可见"的纪律，弹回原因由 UI 侧提示）。
        // colorCorrectionModes 空集合 = 隐藏键拿不到（无法判定）⇒ 不回弹，保持既有行为。
        val manualSupported = a.manualWhiteBalancePossible()
        if (wbMode.value == WbPreset.MANUAL && !manualSupported) {
            wbMode.value = WbPreset.AUTO
        }
        if (a.awbModes.isNotEmpty() && !a.awbModes.contains(wbMode.value.awbMode)) {
            wbMode.value = WbPreset.values()
                .firstOrNull { it != WbPreset.MANUAL && a.awbModes.contains(it.awbMode) }
                ?: WbPreset.MANUAL
        }
        // 色温/色调是产品滑杆域（02 文件第 6 节双轴插值），不随机型变化
        kelvin.value = ParamState(
            clamped(kelvin.value.value, WotaTiers.KELVIN_MIN, WotaTiers.KELVIN_MAX),
            WotaTiers.KELVIN_MIN..WotaTiers.KELVIN_MAX
        )
        tint.value = ParamState(
            clamped(tint.value.value, WotaTiers.TINT_MIN, WotaTiers.TINT_MAX),
            WotaTiers.TINT_MIN..WotaTiers.TINT_MAX
        )
    }

    /** 落档到当前镜头能力：只按「用户要的那档」求解，见 [resolveSizeFor] */
    private fun applySize(a: CameraAbility) {
        val picked = resolveSizeFor(requestedSize, a.videoSizes) ?: return
        if (!sizeCloseTo(size.value, picked, VideoSizeCandidates.TOLERANCE_PX)) size.value = picked
    }

    private fun applyModeGates(a: CameraAbility) {
        if (aeMode.value == AeMode.MANUAL && !a.manualExposurePossible()) aeMode.value = AeMode.AUTO
        if (!a.flashAvailable) flash.value = Flash.OFF
        stabilize.value = when (stabilize.value) {
            Stabilize.OIS -> if (a.oisAvailable) Stabilize.OIS else Stabilize.OFF
            Stabilize.EIS -> if (a.eisAvailable) Stabilize.EIS else Stabilize.OFF
            Stabilize.OIS_EIS -> when {
                a.oisAvailable && a.eisAvailable -> Stabilize.OIS_EIS
                a.oisAvailable -> Stabilize.OIS
                a.eisAvailable -> Stabilize.EIS
                else -> Stabilize.OFF
            }
            Stabilize.OFF -> Stabilize.OFF
        }
    }

    private fun <T : Comparable<T>> clamped(value: T, lo: T, hi: T): T = when {
        hi < lo -> value
        value < lo -> lo
        value > hi -> hi
        else -> value
    }
}
