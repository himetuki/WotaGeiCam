package com.wotagei.cam.camera

import android.graphics.Rect
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.RggbChannelVector
import android.os.Build
import android.util.Range
import android.util.Rational
import com.wotagei.cam.core.AeMode
import com.wotagei.cam.core.AfMode
import com.wotagei.cam.core.CameraAbility
import com.wotagei.cam.core.Flash
import com.wotagei.cam.core.FpsPick
import com.wotagei.cam.core.RangeI
import com.wotagei.cam.core.Rect as WotaRect
import com.wotagei.cam.core.Size as WotaSize
import com.wotagei.cam.core.Stabilize
import com.wotagei.cam.core.WbPreset
import com.wotagei.cam.core.WotaTiers
import com.wotagei.cam.core.pickFpsRange
import kotlin.math.roundToInt

/**
 * 参数 → CaptureRequest.Builder 的纯函数层。
 *
 * 设计约束：
 * - 范围/档位一律来自 [CameraAbility]（运行时 characteristics），本文件不出现机型硬编码数值；
 *   产品域常量（快门上限、色温域、高速分水岭、1e9）统一取 `core/WotaTiers`，不在此重复定义；
 * - 与 Android 无耦合的计算拆成纯函数（入参用 core 的纯数据类），可直接跑 JVM 单测；
 * - 快门/fps/ISO 的钳制落在相机层而不是 UI 层。
 */
object RequestApplier {

    /** 对焦/测光框在传感器坐标系下固定 1000×1000，权重取最大 */
    private const val REGION_HALF = 1000

    /** 3840×2160 及以上带宽下 EIS 不下发（门控 2） */
    private const val EIS_BLOCK_LONG_EDGE = 3840
    private const val EIS_BLOCK_SHORT_EDGE = 2160

    /** tint 轴幅度（0–255 域）；与色温轴三锚点插值合成 双轴插值 */
    private const val TINT_SPAN_RGB = 38f
    private const val TINT_SPAN_GREEN = 26f

    /** 闪光方案：AE_MODE 语义 + 常亮灯开关（官方语义，不做反向映射） */
    enum class FlashAe { ON, ON_ALWAYS_FLASH, ON_AUTO_FLASH }

    data class FlashPlan(val ae: FlashAe, val torch: Boolean)

    /**
     * 防抖方案。null = 该键不下发（能力没给，强设会让 HAL 行为未定义， 门控 1）；
     * 非 null = 显式写 ON/OFF —— 关闭也要写值，「不写」等于交给 HAL 自主决定。
     */
    data class StabilizePlan(val oisOn: Boolean?, val eisOn: Boolean?)

    /** 下发所需的设备侧快照，由 [stateOf] 从能力一次性算出；切镜头时整套重建 */
    data class State(
        val activeArray: WotaRect,
        val isoMin: Int,
        val isoMax: Int,
        val exposureMinNs: Long,
        val exposureMaxNs: Long,
        val evMinSteps: Int,
        val evMaxSteps: Int,
        val fpsRanges: List<RangeI>,
        val maxRegionsAf: Int,
        val maxRegionsAe: Int,
        val manualFocusMaxDiopter: Float,
        val zoomMin: Float,
        val zoomMax: Float,
        val useZoomRatio: Boolean,
        val flashAvailable: Boolean,
        val oisAvailable: Boolean,
        val eisAvailable: Boolean,
        val highSpeed: Boolean,
        val eisBlockedBySize: Boolean
    ) {
        /** 能力缺失时 RangeI 是 closed(0..0)，不能拿它去 coerce，否则会把 ISO 压成 0 */
        fun clampIso(value: Int): Int = if (isoMin in 1..isoMax) value.coerceIn(isoMin, isoMax) else value

        fun clampEv(steps: Int): Int =
            if (evMaxSteps >= evMinSteps) steps.coerceIn(evMinSteps, evMaxSteps) else 0
    }

    /** 一帧请求的参数快照（值已由引擎钳制到 State 范围内） */
    data class Snapshot(
        val aeMode: AeMode,
        val iso: Int,
        val shutterNs: Long,
        val evSteps: Int,
        val fps: FpsPick?,
        val afMode: AfMode,
        /** 点按对焦期间的临时 CONTROL_AF_MODE（AUTO）；null 表示沿用 afMode */
        val afModeOverride: Int?,
        val focusDiopter: Float,
        val zoom: Float,
        val wbPreset: WbPreset,
        val kelvin: Int,
        val tint: Int,
        /** 闪光切换复位阶段的临时值；null 表示沿用用户设定 */
        val flashOverride: Flash?,
        val flash: Flash,
        val stabilize: Stabilize,
        /** null = 本帧不改测光区；非 null = 写入这些区域 */
        val afRegions: List<MeteringRectangle>?,
        val aeRegions: List<MeteringRectangle>?,
        /** 单次通道用 TRIGGER_START；repeating 通道用 TRIGGER_IDLE 复位 */
        val afTrigger: Int?
    )

    // ---------------------------------------------------------------- 能力快照

    /**
     * 一次性收全下发所需能力：区域上限、手动对焦域、变焦域、防抖能力、4K 门禁在此定型。
     * 帧率范围表按尺寸与高帧率分流，交给 core 的 `fpsRangesFor`（高速表是「尺寸→范围」）。
     */
    fun stateOf(ability: CameraAbility, size: WotaSize, highSpeed: Boolean): State {
        val ratio = ability.zoomRatioRange
        val zoomMin = ratio?.lo ?: 1f
        val zoomMax = ratio?.hi ?: ability.maxDigitalZoom.coerceAtLeast(1f)
        val longEdge = maxOf(size.width, size.height)
        val shortEdge = minOf(size.width, size.height)
        return State(
            activeArray = ability.activeArray,
            isoMin = ability.iso.lo,
            isoMax = ability.iso.hi,
            exposureMinNs = ability.exposureNs.lo.toLong(),
            exposureMaxNs = ability.exposureNs.hi.toLong(),
            evMinSteps = ability.evRange.lo,
            evMaxSteps = ability.evRange.hi,
            fpsRanges = ability.fpsRangesFor(size, highSpeed),
            maxRegionsAf = ability.maxRegionsAf,
            maxRegionsAe = ability.maxRegionsAe,
            manualFocusMaxDiopter = ability.minFocusDistanceDiopter,
            zoomMin = minOf(zoomMin, zoomMax),
            zoomMax = maxOf(zoomMin, zoomMax),
            // ZOOM_RATIO 需 SDK≥30 且镜头给了 ratio range，否则退回 CROP_REGION
            useZoomRatio = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && ratio != null,
            flashAvailable = ability.flashAvailable,
            oisAvailable = ability.oisAvailable,
            eisAvailable = ability.eisAvailable,
            highSpeed = highSpeed,
            eisBlockedBySize = longEdge >= EIS_BLOCK_LONG_EDGE && shortEdge >= EIS_BLOCK_SHORT_EDGE
        )
    }

    // ---------------------------------------------------------------- 纯计算（可单测）

    /**
     * 快门双钳制（三条硬规则）：≤ 1/fps（否则丢帧）、≤ 1/10s（防手抖糊片），
     * 最后夹进设备曝光时间范围；范围缺失（closed）时跳过对应那一刀。
     */
    fun clampShutterNs(shutterNs: Long, fps: Int, exposureMinNs: Long, exposureMaxNs: Long): Long {
        val safeFps = fps.coerceAtLeast(1)
        val perFrame = WotaTiers.NS_PER_SECOND / safeFps
        var value = minOf(shutterNs, perFrame, WotaTiers.SHUTTER_CAP_NS)
        if (exposureMaxNs > 0) value = minOf(value, exposureMaxNs)
        if (exposureMinNs > 0) value = maxOf(value, exposureMinNs)
        return if (value <= 0L) 1L else value
    }

    /** 手动曝光必须同帧给出的帧周期：1e9/fps */
    fun frameDurationNs(fps: Int): Long = WotaTiers.NS_PER_SECOND / fps.coerceAtLeast(1)

    /**
     * fps 档位 → 实际写入 CONTROL_AE_TARGET_FPS_RANGE 的范围。
     * 固定 [f,f] 优先（录像时间戳稳定的前提），无固定档时由 core 落「包含 f 且上界最大」的可变范围
     * 并标 exact=false（UI 打「※」）；高帧率分支只接受 Range(f,f)。
     * 返回 null 表示设备没有任何范围覆盖 target，由引擎上报高帧率失败并退档，
     * 而不是静默跑另一个帧率（那会让 params.fps 与成片 fps 不一致）。
     */
    fun pickFps(ranges: List<RangeI>, target: Int, highSpeed: Boolean): FpsPick? {
        if (target <= 0) return null
        if (highSpeed) return FpsPick(target, target, true)
        return pickFpsRange(ranges, target)
    }

    /**
     * 色温 + tint → 0–255 域 RGB（双轴插值）。
     * 色温轴：2000/6000/10000K 三锚点线性插值（锚点即 1.0–3.0 增益域端点映回 0–255）；
     * tint 轴：以中点为 0 的 ±偏移，同向抬/压 R、B，反向调整 G。
     * 方向遵循白平衡语义：预设越低（假定光源越暖）补蓝越多，预设越高补红越多。
     */
    fun kelvinTintToRgb255(kelvin: Int, tint: Int): FloatArray {
        val u = ((kelvin - WotaTiers.KELVIN_MIN).toFloat() /
            (WotaTiers.KELVIN_MAX - WotaTiers.KELVIN_MIN)).coerceIn(0f, 1f)
        val v = ((tint - WotaTiers.TINT_MIN).toFloat() /
            (WotaTiers.TINT_MAX - WotaTiers.TINT_MIN)).coerceIn(0f, 1f)
        val r = lerp3(u, 0.298f, 0.549f, 1f) * 255f
        val g = lerp3(u, 0.502f, 0.549f, 0.502f) * 255f
        val b = lerp3(u, 1f, 0.549f, 0.298f) * 255f
        val t = (v - 0.5f) * 2f                       // −1 偏绿 / +1 偏品红
        return floatArrayOf(
            (r + t * TINT_SPAN_RGB).coerceIn(0f, 255f),
            (g - t * TINT_SPAN_GREEN).coerceIn(0f, 255f),
            (b + t * TINT_SPAN_RGB).coerceIn(0f, 255f)
        )
    }

    /** 0–255 → 增益 1.0–3.0（线性映射，不自己构造色度矩阵） */
    fun gain255ToUnit(x: Float): Float = 1f + (x / 255f) * 2f

    /** 由 0–255 RGB 组 RggbChannelVector（绿通道均分两半） */
    fun rggbFromRgb255(rgb: FloatArray): RggbChannelVector {
        val r = gain255ToUnit(rgb[0])
        val g = gain255ToUnit(rgb[1])
        val b = gain255ToUnit(rgb[2])
        return RggbChannelVector(r, g / 2f, g / 2f, b)
    }

    /**
     * 归一化点按坐标 → 传感器坐标的 1000×1000 框（换算式：x' = x×arrayW − 半框宽，再 clamp）。
     * TapPoint 的 x/y 已是 [0,1] 且按 sensorOrientation 摆正，所以这里不再除预览尺寸。
     * 返回 left/top/right/bottom。
     */
    fun meteringBox(arrayWidth: Int, arrayHeight: Int, normX: Float, normY: Float): IntArray {
        val left = (normX * arrayWidth - REGION_HALF).roundToInt()
            .coerceIn(0, maxOf(0, arrayWidth - REGION_HALF))
        val top = (normY * arrayHeight - REGION_HALF).roundToInt()
            .coerceIn(0, maxOf(0, arrayHeight - REGION_HALF))
        return intArrayOf(left, top, left + REGION_HALF, top + REGION_HALF)
    }

    /** 全幅测光框（点按超时后恢复用，对应  U0()） */
    fun fullFrameBox(arrayWidth: Int, arrayHeight: Int): IntArray =
        intArrayOf(0, 0, arrayWidth, arrayHeight)

    /** box(left,top,right,bottom) → Camera2 需要的 MeteringRectangle（构造器入参是 x,y,width,height,weight） */
    fun meteringRect(box: IntArray): MeteringRectangle = MeteringRectangle(
        box[0], box[1], box[2] - box[0], box[3] - box[1], MeteringRectangle.METERING_WEIGHT_MAX
    )

    /** CROP_REGION 中心裁切公式：z=1 即全幅。返回 left/top/right/bottom */
    fun cropBox(arrayWidth: Int, arrayHeight: Int, zoom: Float, zoomMin: Float, zoomMax: Float): IntArray {
        val z = zoom.coerceIn(zoomMin, zoomMax).coerceAtLeast(1f)
        val halfW = arrayWidth / (2f * z)
        val halfH = arrayHeight / (2f * z)
        val left = (arrayWidth / 2f - halfW).roundToInt().coerceIn(0, arrayWidth)
        val top = (arrayHeight / 2f - halfH).roundToInt().coerceIn(0, arrayHeight)
        val right = (arrayWidth / 2f + halfW).roundToInt().coerceIn(left + 1, arrayWidth)
        val bottom = (arrayHeight / 2f + halfH).roundToInt().coerceIn(top + 1, arrayHeight)
        return intArrayOf(left, top, right, bottom)
    }

    /**
     * 闪光灯映射按 **Android 官方语义**：ON→ON_ALWAYS_FLASH（补光常闪）、AUTO→ON_AUTO_FLASH（自动预闪）、
     * TORCH→AE_MODE=ON + FLASH_MODE=TORCH。常见实现里有反向映射的写法，此处不做。
     * 手动曝光（AE_MODE=OFF）下无法用 AE 驱动的闪光模式，退化为 FLASH_MODE=OFF，torch 仍保留。
     */
    fun flashPlan(flash: Flash, flashAvailable: Boolean, manualAe: Boolean): FlashPlan {
        if (!flashAvailable) return FlashPlan(FlashAe.ON, false)
        if (manualAe) return FlashPlan(FlashAe.ON, flash == Flash.TORCH)
        return when (flash) {
            Flash.OFF -> FlashPlan(FlashAe.ON, false)
            Flash.ON -> FlashPlan(FlashAe.ON_ALWAYS_FLASH, false)
            Flash.AUTO -> FlashPlan(FlashAe.ON_AUTO_FLASH, false)
            Flash.TORCH -> FlashPlan(FlashAe.ON, true)
        }
    }

    /**
     * 防抖三门控：能力门控 / 4K 禁 EIS / 高帧率会话整体不下发。
     * 「显式关闭写 OFF 而不是不写」体现为返回 false 而不是 null。
     */
    fun stabilizePlan(
        stabilize: Stabilize,
        oisAvailable: Boolean,
        eisAvailable: Boolean,
        eisBlockedBySize: Boolean,
        highSpeed: Boolean
    ): StabilizePlan {
        if (highSpeed) return StabilizePlan(null, null)
        val wantsOis = stabilize == Stabilize.OIS || stabilize == Stabilize.OIS_EIS
        val wantsEis = stabilize == Stabilize.EIS || stabilize == Stabilize.OIS_EIS
        val ois = if (oisAvailable) wantsOis else null
        // 4K 下即使用户选了 EIS 也不下发该键（带宽不足，HAL 会静默降帧）
        val eis = if (eisAvailable && !(wantsEis && eisBlockedBySize)) wantsEis else null
        return StabilizePlan(ois, eis)
    }

    /** 单位色度矩阵：声明 TRANSFORM_MATRIX 模式但不注入矩阵，色彩完全交给 gains */
    fun identityColorTransform(): ColorSpaceTransform {
        val one = Rational(1, 1)
        val zero = Rational(0, 1)
        // 构造器收的是一维行主序 9 元素 Rational 数组，不是 3×3 二维数组
        return ColorSpaceTransform(arrayOf(one, zero, zero, zero, one, zero, zero, zero, one))
    }

    /** 手动色温档（写 gains 的分支）；core 的 WbPreset.MANUAL 即 AWB_MODE=OFF */
    fun isManualWhiteBalance(preset: WbPreset): Boolean = preset == WbPreset.MANUAL

    /** ：单次与手动在 Camera2 层同义 = AF_MODE_OFF + 屈光度 */
    fun writesManualFocusDiopter(mode: AfMode): Boolean = mode.cam2Mode == CaptureRequest.CONTROL_AF_MODE_OFF

    // ---------------------------------------------------------------- 主入口

    /**
     * 把一帧参数快照写进 builder。调用方负责选择 repeating 或单次通道。
     * 约定：一组语义一次写全（手动曝光三件套、色温四件套、防抖两键），不留半状态。
     */
    fun apply(builder: CaptureRequest.Builder, state: State, p: Snapshot) {
        applyFps(builder, p.fps)
        applyExposure(builder, state, p)
        applyAutoFocus(builder, state, p)
        applyZoom(builder, state, p.zoom)
        applyWhiteBalance(builder, p)
        applyFlash(builder, state, p)
        applyStabilize(builder, state, p.stabilize)
        applyMeteringRegions(builder, state, p)
    }

    private fun applyFps(builder: CaptureRequest.Builder, pick: FpsPick?) {
        if (pick == null) return
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(pick.lo, pick.hi))
    }

    private fun applyExposure(builder: CaptureRequest.Builder, state: State, p: Snapshot) {
        val manual = p.aeMode == AeMode.MANUAL
        // AE 锁独立于 AE_MODE（锁定不改 AE_MODE 取值）
        builder.set(CaptureRequest.CONTROL_AE_LOCK, !manual && p.aeMode == AeMode.LOCK)
        if (manual) {
            // 三件套一次写全：AE OFF + ISO + 曝光时间 + 帧周期
            val fps = p.fps?.hi ?: WotaTiers.HIGH_SPEED_FPS
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, state.clampIso(p.iso))
            builder.set(
                CaptureRequest.SENSOR_EXPOSURE_TIME,
                clampShutterNs(p.shutterNs, fps, state.exposureMinNs, state.exposureMaxNs)
            )
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs(fps))
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            // EV 以「步数 int」贯穿全链路
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, state.clampEv(p.evSteps))
        }
    }

    private fun applyAutoFocus(builder: CaptureRequest.Builder, state: State, p: Snapshot) {
        val mode = p.afModeOverride ?: p.afMode.cam2Mode
        builder.set(CaptureRequest.CONTROL_AF_MODE, mode)
        if (mode == CaptureRequest.CONTROL_AF_MODE_OFF) {
            val diopter = p.focusDiopter.coerceIn(0f, state.manualFocusMaxDiopter.coerceAtLeast(0f))
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, diopter)
        }
        p.afTrigger?.let { builder.set(CaptureRequest.CONTROL_AF_TRIGGER, it) }
    }

    // 走 CONTROL_ZOOM_RATIO 的前提是 state.useZoomRatio，而它在构造 State 时已由 SDK_INT >= R 把住
    // （见本文件 useZoomRatio 的赋值）；该 key 是编译期内联的字符串常量，API 29 不会执行到这里
    @android.annotation.SuppressLint("NewApi")
    private fun applyZoom(builder: CaptureRequest.Builder, state: State, zoom: Float) {
        if (state.useZoomRatio) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom.coerceIn(state.zoomMin, state.zoomMax))
        } else {
            val array = state.activeArray
            val box = cropBox(array.arrayWidth, array.arrayHeight, zoom, state.zoomMin, state.zoomMax)
            builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(box[0], box[1], box[2], box[3]))
        }
    }

    private fun applyWhiteBalance(builder: CaptureRequest.Builder, p: Snapshot) {
        if (isManualWhiteBalance(p.wbPreset)) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            builder.set(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
            )
            builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, identityColorTransform())
            builder.set(
                CaptureRequest.COLOR_CORRECTION_GAINS,
                rggbFromRgb255(kelvinTintToRgb255(p.kelvin, p.tint))
            )
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, p.wbPreset.awbMode)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
        }
    }

    private fun applyFlash(builder: CaptureRequest.Builder, state: State, p: Snapshot) {
        val manual = p.aeMode == AeMode.MANUAL
        val wanted = p.flashOverride ?: p.flash
        val plan = flashPlan(wanted, state.flashAvailable, manual)
        // 手动曝光下 AE_MODE 已由曝光分支置 OFF，这里只补 FLASH_MODE
        if (!manual) {
            builder.set(
                CaptureRequest.CONTROL_AE_MODE,
                when (plan.ae) {
                    FlashAe.ON -> CaptureRequest.CONTROL_AE_MODE_ON
                    FlashAe.ON_ALWAYS_FLASH -> CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                    FlashAe.ON_AUTO_FLASH -> CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                }
            )
        }
        builder.set(
            CaptureRequest.FLASH_MODE,
            if (plan.torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
        )
    }

    private fun applyStabilize(builder: CaptureRequest.Builder, state: State, stabilize: Stabilize) {
        val plan = stabilizePlan(
            stabilize, state.oisAvailable, state.eisAvailable,
            state.eisBlockedBySize, state.highSpeed
        )
        plan.oisOn?.let {
            builder.set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (it) CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                else CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
            )
        }
        plan.eisOn?.let {
            builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (it) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            )
        }
    }

    private fun applyMeteringRegions(builder: CaptureRequest.Builder, state: State, p: Snapshot) {
        // 区域数上限可能为 0，写了也不生效，直接跳过
        p.afRegions?.let {
            if (state.maxRegionsAf > 0) builder.set(CaptureRequest.CONTROL_AF_REGIONS, it.toTypedArray())
        }
        p.aeRegions?.let {
            if (state.maxRegionsAe > 0) builder.set(CaptureRequest.CONTROL_AE_REGIONS, it.toTypedArray())
        }
    }

    private fun lerp3(t: Float, a: Float, b: Float, c: Float): Float =
        if (t <= 0.5f) a + (b - a) * (t * 2f) else b + (c - b) * ((t - 0.5f) * 2f)
}
