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
    val shutter = MutableStateFlow(ParamState(40_000_000L))          // ns，1/25
    val iso = MutableStateFlow(ParamState(100))
    val aeMode = MutableStateFlow(AeMode.AUTO)
    val ev = MutableStateFlow(ParamState(0))                          // 步数，非倍率
    val afMode = MutableStateFlow(AfMode.CONTINUOUS_VIDEO)
    val manualFocusDiopter = MutableStateFlow(ParamState(0f))
    val focusPoint = MutableStateFlow<TapPoint?>(null)
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
        val highSpeed = targetFps > WotaTiers.HIGH_SPEED_FPS
        val pick = pickFpsRange(a.fpsRangesFor(size.value, highSpeed), targetFps)
        fps.value = if (pick == null) ParamState(targetFps, null, enabled = false, exact = false)
        else ParamState(targetFps, pick.lo..pick.hi, true, pick.exact)

        // 快门上限吃「实际生效的帧周期上界」= 上面 pick 到的 hi（无固定 [f,f] 时是设备的可变范围上界），
        // 不是用户档位本身：否则用户选 24fps、设备只给 [15,30]（非精确）时，列表会按 1/24 判可选，
        // 下发/回写却按 30fps 帧周期压回 1/30，露出一个点不住的死档位。
        applyExposure(a, targetFps, pick?.hi)
        applyEv(a)
        applyZoom(a)
        applyFocus(a)
        applyWhiteBalance(a)
        applyModeGates(a)

        // activeArray 随镜头变化，旧点按坐标语义失效（换算依赖 activeArray）
        focusPoint.value = null
        a.dump(lens.value.name)
    }

    /**
     * @param fpsHi 实际生效的帧率上界（`pickFpsRange` 的 `.hi`）；为 null（该档 fps 不可用）时
     *              退回用户档位 [targetFps]，与改动前行为一致。帧周期一律吃这个量，不吃 [targetFps]。
     */
    private fun applyExposure(a: CameraAbility, targetFps: Int, fpsHi: Int?) {
        iso.value = if (a.iso.ok()) {
            ParamState(clamped(iso.value.value, a.iso.lo, a.iso.hi), a.iso.lo..a.iso.hi)
        } else {
            ParamState(iso.value.value, null, enabled = false)
        }

        // 三条硬规则：快门 ≤ 设备上限、≤ 1/10s 防手抖、≤ 1/fps 防丢帧。
        // 上限的算法抽在 core 的 [shutterCeilingNs]（纯函数，有 JVM 单测）：它先把设备上限抬到
        // 强制档 1/24、1/25，再过 1/10s 与 1/帧周期。帧周期吃的是**实际生效的 fps 上界**（[fpsHi]）：
        // 25fps 下 1/24 被 40ms 帧周期压回；24fps 退化成非精确范围（如 [15,30]，上界 30）时，
        // 1/24 同样会被 30fps 的帧周期压回——不能只看用户档位那个 24。
        if (a.exposureNs.ok()) {
            val lo = a.exposureNs.lo.toLong()
            val hi = shutterCeilingNs(a.exposureNs.hi.toLong(), fpsHi ?: targetFps)
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

        afMode.value = if (a.afModes.isEmpty()) AfMode.MANUAL else {
            AfMode.PREFERENCE.firstOrNull { a.afModes.contains(it.cam2Mode) } ?: AfMode.MANUAL
        }
    }

    private fun applyWhiteBalance(a: CameraAbility) {
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
